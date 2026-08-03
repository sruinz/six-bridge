#!/usr/bin/env bash
set -u

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cli=${SIXBRIDGE_CLI:-"$project_dir/cli/build/install/sixbridge/bin/sixbridge"}
timeout_seconds=${SIXBRIDGE_ANALYSIS_TIMEOUT_SECONDS:-300}
term_grace_seconds=${SIXBRIDGE_ANALYSIS_TERM_GRACE_SECONDS:-5}

usage() {
  cat >&2 <<'EOF'
사용법: desktop-large-extension-analysis.sh --profile <profile.json> --input <extension.crx> [--input <extension.crx> ...]
EOF
}

fail() {
  printf '오류: %s\n' "$1" >&2
  exit 3
}

require_positive_integer() {
  local name=$1
  local value=$2
  case "$value" in
    ''|*[!0-9]*) fail "$name은(는) 양의 정수여야 합니다." ;;
  esac
  [ "$value" -gt 0 ] || fail "$name은(는) 양의 정수여야 합니다."
}

canonical_regular_file() {
  local path=$1
  local canonical
  canonical=$(realpath -- "$path" 2>/dev/null) || return 1
  [ -f "$canonical" ] || return 1
  printf '%s\n' "$canonical"
}

now_millis() {
  local seconds
  seconds=$(date +%s)
  printf '%s\n' "$((seconds * 1000))"
}

command -v realpath >/dev/null 2>&1 || fail "필수 도구를 찾을 수 없습니다: realpath"
command -v jq >/dev/null 2>&1 || fail "필수 도구를 찾을 수 없습니다: jq"
[ -x /usr/bin/perl ] || fail "필수 도구를 찾을 수 없습니다: /usr/bin/perl"
require_positive_integer SIXBRIDGE_ANALYSIS_TIMEOUT_SECONDS "$timeout_seconds"
require_positive_integer SIXBRIDGE_ANALYSIS_TERM_GRACE_SECONDS "$term_grace_seconds"

profile_input=
input_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --profile)
      [ "$#" -ge 2 ] || { usage; fail "--profile 값이 필요합니다."; }
      profile_input=$2
      shift 2
      ;;
    --input)
      [ "$#" -ge 2 ] || { usage; fail "--input 값이 필요합니다."; }
      input_args+=("$2")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      fail "알 수 없는 인자입니다: $1"
      ;;
  esac
done

[ -n "$profile_input" ] || { usage; fail "--profile이 필요합니다."; }
[ "${#input_args[@]}" -gt 0 ] || { usage; fail "--input이 하나 이상 필요합니다."; }

profile=$(canonical_regular_file "$profile_input") ||
  fail "profile은 canonical regular file이어야 합니다: $profile_input"
inputs=()
for input in "${input_args[@]}"; do
  canonical=$(canonical_regular_file "$input") ||
    fail "입력은 canonical regular file이어야 합니다: $input"
  inputs+=("$canonical")
done

if [[ "$cli" == */* ]]; then
  [ -x "$cli" ] || fail "sixbridge installDist 결과를 찾을 수 없습니다: $cli"
else
  cli=$(command -v "$cli") || fail "sixbridge CLI를 찾을 수 없습니다: $cli"
fi

run_dir=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-large-analysis.XXXXXX") ||
  fail "임시 분석 디렉터리를 만들 수 없습니다."
runner_pgid=$(/usr/bin/perl -e 'print getpgrp(0)') ||
  fail "runner process group을 확인할 수 없습니다."
active_pid=
active_pgid=
active_group_owned=0
group_sequence=0

safe_owned_group() {
  [ "$active_group_owned" -eq 1 ] &&
    [ -n "$active_pid" ] &&
    [ "$active_pid" = "$active_pgid" ] &&
    [ "$active_pgid" != "$runner_pgid" ] &&
    [ "$active_pgid" -gt 1 ] 2>/dev/null
}

active_group_alive() {
  safe_owned_group && /bin/kill -0 -- "-$active_pgid" 2>/dev/null
}

clear_active_process() {
  active_pid=
  active_pgid=
  active_group_owned=0
}

terminate_active_process() {
  local attempts max_attempts pid pgid
  pid=$active_pid
  pgid=$active_pgid
  [ -n "$pid" ] || return 0
  attempts=0
  max_attempts=$((term_grace_seconds * 10))

  if safe_owned_group; then
    /bin/kill -TERM -- "-$pgid" 2>/dev/null || true
    while active_group_alive && [ "$attempts" -lt "$max_attempts" ]; do
      sleep 0.1
      attempts=$((attempts + 1))
    done
    if active_group_alive; then
      /bin/kill -KILL -- "-$pgid" 2>/dev/null || true
    fi
  else
    /bin/kill -TERM "$pid" 2>/dev/null || true
    while /bin/kill -0 "$pid" 2>/dev/null && [ "$attempts" -lt "$max_attempts" ]; do
      sleep 0.1
      attempts=$((attempts + 1))
    done
    if /bin/kill -0 "$pid" 2>/dev/null; then
      /bin/kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  wait "$pid" 2>/dev/null || true
  clear_active_process
}

handle_signal() {
  local exit_code=$1
  trap - HUP INT TERM
  terminate_active_process
  exit "$exit_code"
}

cleanup_runner() {
  local exit_code=$?
  trap - EXIT HUP INT TERM
  terminate_active_process
  rm -rf "$run_dir"
  exit "$exit_code"
}

trap 'handle_signal 129' HUP
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM
trap cleanup_runner EXIT

run_with_deadline() {
  local go marker_pid marker_pgid marker_rest pid process_exit ready
  local setup_attempts timeout_attempts timeout_max_attempts
  group_sequence=$((group_sequence + 1))
  ready="$run_dir/group-$group_sequence.ready"
  go="$run_dir/group-$group_sequence.go"

  /usr/bin/perl -MPOSIX=setsid -e '
    my $ready = shift @ARGV;
    my $go = shift @ARGV;
    setsid() >= 0 or die "setsid failed: $!\n";
    my $ready_temp = "$ready.tmp";
    open my $handle, ">", $ready_temp or die "ready failed: $!\n";
    print {$handle} "$$ ", getpgrp(0), "\n";
    close $handle or die "ready close failed: $!\n";
    rename $ready_temp, $ready or die "ready rename failed: $!\n";
    until (-e $go) { select undef, undef, undef, 0.01; }
    exec {$ARGV[0]} @ARGV;
    die "exec failed: $!\n";
  ' "$ready" "$go" "$@" &
  pid=$!
  active_pid=$pid
  active_pgid=
  active_group_owned=0
  setup_attempts=0

  while [ ! -s "$ready" ]; do
    if ! /bin/kill -0 "$pid" 2>/dev/null; then
      wait "$pid"
      process_exit=$?
      clear_active_process
      return "$process_exit"
    fi
    if [ "$setup_attempts" -ge 500 ]; then
      terminate_active_process
      return 125
    fi
    sleep 0.01
    setup_attempts=$((setup_attempts + 1))
  done

  IFS=' ' read -r marker_pid marker_pgid marker_rest <"$ready"
  if [ "$marker_pid" != "$pid" ] ||
    [ "$marker_pgid" != "$pid" ] ||
    [ -n "${marker_rest:-}" ] ||
    [ "$marker_pgid" = "$runner_pgid" ] ||
    [ "$marker_pgid" -le 1 ] 2>/dev/null; then
    terminate_active_process
    return 125
  fi
  active_pgid=$marker_pgid
  active_group_owned=1
  : >"$go"
  timeout_attempts=0
  timeout_max_attempts=$((timeout_seconds * 10))

  while /bin/kill -0 "$pid" 2>/dev/null; do
    if [ "$timeout_attempts" -ge "$timeout_max_attempts" ]; then
      terminate_active_process
      return 124
    fi
    sleep 0.1
    timeout_attempts=$((timeout_attempts + 1))
  done

  wait "$pid"
  process_exit=$?
  if active_group_alive; then
    terminate_active_process
  else
    clear_active_process
  fi
  return "$process_exit"
}

overall_exit=0
index=0
for input in "${inputs[@]}"; do
  report="$run_dir/report-$index.json"
  process_log="$run_dir/process-$index.log"
  started=$(now_millis)
  run_with_deadline "$cli" analyze \
    --input "$input" \
    --profile "$profile" \
    --output "$report" >"$process_log" 2>&1
  process_exit=$?
  ended=$(now_millis)
  elapsed=$((ended - started))

  if [ "$process_exit" -eq 124 ]; then
    jq -cn --arg input "$input" --argjson elapsed "$elapsed" \
      '{input:$input,result:"TIMEOUT",elapsedMs:$elapsed}'
    overall_exit=3
    index=$((index + 1))
    continue
  fi
  if [ "$process_exit" -ne 0 ] && [ "$process_exit" -ne 2 ]; then
    printf '오류: 분석 process가 종료 코드 %s로 실패했습니다: %s\n' \
      "$process_exit" "$input" >&2
    cat "$process_log" >&2
    jq -cn --arg input "$input" --argjson elapsed "$elapsed" \
      --argjson exitCode "$process_exit" \
      '{input:$input,result:"PROCESS_ERROR",elapsedMs:$elapsed,exitCode:$exitCode}'
    overall_exit=3
    index=$((index + 1))
    continue
  fi

  summary=$(jq -ce -s --arg input "$input" --argjson elapsed "$elapsed" '
    if length == 1 and (.[0] | type) == "object"
    then .[0]
    else error("호환성 보고서는 하나의 JSON object여야 합니다.")
    end |
    if
      (.schemaVersion == 3 or .schemaVersion == 4 or .schemaVersion == 5) and
      (.status | type) == "string" and
      (.status == "compatible" or .status == "partial" or
       .status == "unknown" or .status == "blocked") and
      ((has("extensionName") | not) or (.extensionName | type) == "string") and
      has("originalExtensionId") and
      (.originalExtensionId | type) == "string" and
      (.originalExtensionId | test("^[a-p]{32}$")) and
      (.usedApis | type) == "array" and
      all(.usedApis[]; type == "string") and
      (.unsupportedApis | type) == "array" and
      all(.unsupportedApis[]; type == "string") and
      (if .schemaVersion == 5 then
        (.verifiedApis | type) == "array" and
        all(.verifiedApis[]; type == "string") and
        (.unverifiedApis | type) == "array" and
        all(.unverifiedApis[]; type == "string") and
        (.unavailableApis | type) == "array" and
        all(.unavailableApis[]; type == "string") and
        .unavailableApis == .unsupportedApis
      else true end)
    then {
      input: $input,
      status: (.status | ascii_upcase),
      elapsedMs: $elapsed,
      extensionName: (.extensionName // null),
      verifiedExtensionId: .originalExtensionId,
      usedApiCount: (.usedApis | length)
    }
    else error("호환성 보고서 계약이 잘못되었습니다.")
    end
  ' "$report" 2>"$run_dir/jq-$index.log")
  jq_exit=$?
  if [ "$jq_exit" -ne 0 ]; then
    printf '오류: 호환성 보고서를 읽을 수 없습니다: %s\n' "$input" >&2
    cat "$run_dir/jq-$index.log" >&2
    jq -cn --arg input "$input" --argjson elapsed "$elapsed" \
      '{input:$input,result:"PROCESS_ERROR",elapsedMs:$elapsed}'
    overall_exit=3
    index=$((index + 1))
    continue
  fi

  status=$(printf '%s\n' "$summary" | jq -r '.status')
  if { [ "$status" = COMPATIBLE ] || [ "$status" = PARTIAL ]; } &&
    [ "$process_exit" -ne 0 ]; then
    printf '오류: 보고서 상태와 CLI 종료 코드가 일치하지 않습니다: %s\n' "$input" >&2
    overall_exit=3
  elif { [ "$status" = UNKNOWN ] || [ "$status" = BLOCKED ]; } &&
    [ "$process_exit" -ne 2 ]; then
    printf '오류: 보고서 상태와 CLI 종료 코드가 일치하지 않습니다: %s\n' "$input" >&2
    overall_exit=3
  elif [ "$status" = UNKNOWN ] || [ "$status" = BLOCKED ]; then
    [ "$overall_exit" -eq 3 ] || overall_exit=2
  fi
  printf '%s\n' "$summary"
  index=$((index + 1))
done

exit "$overall_exit"
