#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
groups=(
  advanced-runtime
  base-runtime
  identity-privacy
  network
  tab-page
  user-interface
)
receiver_pid=
reverse_set=false
reverse_critical=false
pending_signal=0
serial=
profile_input=

usage() {
  cat <<'EOF'
사용법: scripts/fold7-api-probe.sh --serial <adb-serial> --profile <base-profile.json>
EOF
}

die() {
  echo "오류: $*" >&2
  exit 1
}

on_signal() {
  if [ "$reverse_critical" = true ]; then
    if [ "$pending_signal" -eq 0 ]; then
      pending_signal=$1
    fi
    return 0
  fi
  exit "$1"
}

cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [ -n "$receiver_pid" ] && kill -0 "$receiver_pid" 2>/dev/null; then
    kill -TERM "$receiver_pid" 2>/dev/null || true
    attempt=0
    while kill -0 "$receiver_pid" 2>/dev/null && [ "$attempt" -lt 50 ]; do
      attempt=$((attempt + 1))
      sleep 0.05
    done
    if kill -0 "$receiver_pid" 2>/dev/null; then
      kill -KILL "$receiver_pid" 2>/dev/null || true
    fi
  fi
  if [ -n "$receiver_pid" ]; then
    wait "$receiver_pid" 2>/dev/null || true
  fi
  if [ "$reverse_set" = true ]; then
    if ! "$adb_bin" -s "$serial" reverse --remove tcp:47171 >/dev/null 2>&1; then
      echo "경고: 이 실행이 설정한 adb reverse mapping을 제거하지 못했습니다." >&2
    fi
  fi
  exit "$status"
}

trap cleanup EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

serial_seen=false
profile_seen=false
help_count=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$serial_seen" = false ] || { usage >&2; exit 2; }
      [ "$#" -ge 2 ] && [ -n "$2" ] || { usage >&2; exit 2; }
      serial=$2
      serial_seen=true
      shift 2
      ;;
    --profile)
      [ "$profile_seen" = false ] || { usage >&2; exit 2; }
      [ "$#" -ge 2 ] && [ -n "$2" ] || { usage >&2; exit 2; }
      profile_input=$2
      profile_seen=true
      shift 2
      ;;
    --help|-h)
      help_count=$((help_count + 1))
      shift
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

if [ "$help_count" -gt 0 ]; then
  if [ "$help_count" -eq 1 ] && [ "$serial_seen" = false ] && [ "$profile_seen" = false ]; then
    usage
    exit 0
  fi
  usage >&2
  exit 2
fi

[ "$serial_seen" = true ] && [ "$profile_seen" = true ] || {
  usage >&2
  exit 2
}
case "$serial" in
  *[!A-Za-z0-9._:-]*|'')
    echo "오류: ADB serial 형식이 올바르지 않습니다." >&2
    exit 2
    ;;
esac

[ -f "$profile_input" ] && [ ! -L "$profile_input" ] || {
  echo "오류: profile은 symlink가 아닌 regular file이어야 합니다." >&2
  exit 2
}
profile_parent=$(CDPATH= cd -- "$(dirname -- "$profile_input")" && pwd -P)
profile="$profile_parent/$(basename -- "$profile_input")"

adb_bin=$(command -v adb) || die "adb를 찾을 수 없습니다."
node_bin=$(command -v node) || die "node를 찾을 수 없습니다."
local_sixbridge="$project_dir/cli/build/install/sixbridge/bin/sixbridge"
using_local_sixbridge=false
if [ -x "$local_sixbridge" ]; then
  sixbridge_bin=$local_sixbridge
  using_local_sixbridge=true
else
  sixbridge_bin=$(command -v sixbridge) || die "sixbridge CLI를 찾을 수 없습니다."
fi
receiver_script="$project_dir/scripts/api-probe-receiver.mjs"
[ -f "$receiver_script" ] && [ ! -L "$receiver_script" ] || die "API probe receiver script를 검증할 수 없습니다."

help_lists_command() {
  expected_command=$1
  help_output=$2
  while IFS= read -r help_line; do
    trimmed_line=${help_line#"${help_line%%[![:space:]]*}"}
    command_token=${trimmed_line%%[[:space:]]*}
    if [ "$command_token" = "$expected_command" ]; then
      return 0
    fi
  done <<EOF
$help_output
EOF
  return 1
}

cli_contract_error() {
  if [ "$using_local_sixbridge" = true ]; then
    die "프로젝트 local sixbridge CLI가 현재 source와 일치하지 않습니다. ./gradlew :cli:installDist 로 재생성해 주세요."
  fi
  die "선택한 sixbridge CLI가 필요한 API probe 명령 계약을 지원하지 않습니다."
}

if ! root_help=$("$sixbridge_bin" --help 2>/dev/null); then
  cli_contract_error
fi
help_lists_command build "$root_help" || cli_contract_error
help_lists_command probe "$root_help" || cli_contract_error
if ! probe_help=$("$sixbridge_bin" probe 2>/dev/null); then
  cli_contract_error
fi
for required_probe_command in generate prepare-session verify-evidence build-profile; do
  help_lists_command "$required_probe_command" "$probe_help" || cli_contract_error
done

ensure_directory() {
  directory=$1
  if [ -L "$directory" ]; then
    die "안전하지 않은 symlink directory입니다: $directory"
  fi
  if [ -e "$directory" ]; then
    [ -d "$directory" ] || die "directory 경로가 아닙니다: $directory"
  else
    mkdir "$directory" || die "directory를 만들 수 없습니다: $directory"
  fi
}

umask 077
ensure_directory "$project_dir/.local"
ensure_directory "$project_dir/.local/probe-build"
ensure_directory "$project_dir/.local/evidence"
ensure_directory "$project_dir/.local/evidence/api-probe"

session_id=$(date '+%Y%m%d-%H%M%S')
case "$session_id" in
  [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]) ;;
  *) die "session ID를 안전하게 생성할 수 없습니다." ;;
esac
build_dir="$project_dir/.local/probe-build/$session_id"
evidence_dir="$project_dir/.local/evidence/api-probe/$session_id"
[ ! -e "$build_dir" ] && [ ! -L "$build_dir" ] || die "기존 probe build session을 덮어쓸 수 없습니다: $build_dir"
[ ! -e "$evidence_dir" ] && [ ! -L "$evidence_dir" ] || die "기존 probe evidence session을 덮어쓸 수 없습니다: $evidence_dir"
mkdir "$build_dir" || die "probe build session을 만들 수 없습니다."
mkdir "$evidence_dir" || die "probe evidence session을 만들 수 없습니다."
profile_snapshot="$build_dir/base-profile.json"

[ -f "$profile_input" ] && [ ! -L "$profile_input" ] || {
  echo "오류: snapshot 직전 profile을 다시 검증할 수 없습니다." >&2
  exit 2
}
[ -f "$profile" ] && [ ! -L "$profile" ] || {
  echo "오류: snapshot 직전 canonical profile을 다시 검증할 수 없습니다." >&2
  exit 2
}
if ! exec 9<"$profile"; then
  echo "오류: 검증한 profile을 open descriptor로 고정할 수 없습니다." >&2
  exit 2
fi
if ! (set -C; cat <&9 >"$profile_snapshot"); then
  exec 9<&-
  die "profile session snapshot을 안전하게 새로 만들 수 없습니다."
fi
exec 9<&-
chmod 600 "$profile_snapshot"
[ -f "$profile_snapshot" ] && [ ! -L "$profile_snapshot" ] || die "profile session snapshot을 검증할 수 없습니다."

device_state=$("$adb_bin" -s "$serial" get-state) || die "ADB device 상태를 확인하지 못했습니다."
[ "$device_state" = device ] || die "ADB device가 online device 상태가 아닙니다: $device_state"
reverse_critical=true
if "$adb_bin" -s "$serial" reverse --no-rebind tcp:47171 tcp:47171; then
  reverse_status=0
  reverse_set=true
else
  reverse_status=$?
fi
reverse_critical=false
if [ "$pending_signal" -ne 0 ]; then
  exit "$pending_signal"
fi
[ "$reverse_status" -eq 0 ] || die "ADB reverse mapping을 설정하지 못했습니다."

source_dir="$build_dir/source"
"$sixbridge_bin" probe generate --output "$source_dir"

artifact_args=()
for group in "${groups[@]}"; do
  group_source="$source_dir/$group"
  bundle="$build_dir/$group.six"
  "$sixbridge_bin" build --input "$group_source" --profile "$profile_snapshot" --output "$bundle"
  crx="$build_dir/$group.crx"
  wrapper="$build_dir/$group-wrapper.apk"
  for artifact in "$bundle" "$crx" "$wrapper"; do
    [ -f "$artifact" ] && [ ! -L "$artifact" ] || die "build artifact가 symlink가 아닌 regular file이 아닙니다: $artifact"
  done
  artifact_args+=(--artifact-set "$group=$bundle")
done

session_config="$build_dir/session-config.json"
"$sixbridge_bin" probe prepare-session \
  --session-id "$session_id" \
  --profile "$profile_snapshot" \
  "${artifact_args[@]}" \
  --output "$session_config"
[ -f "$session_config" ] && [ ! -L "$session_config" ] || die "session config를 검증할 수 없습니다."

receiver_log="$build_dir/receiver.log"
"$node_bin" "$receiver_script" \
  --session-config "$session_config" \
  --root "$project_dir/.local/evidence/api-probe" \
  --port 47171 >"$receiver_log" 2>&1 &
receiver_pid=$!

receiver_ready=false
attempt=0
while [ "$attempt" -lt 100 ]; do
  if grep -F 'SIX API probe receiver: http://127.0.0.1:47171/evidence' "$receiver_log" >/dev/null 2>&1; then
    if kill -0 "$receiver_pid" 2>/dev/null; then
      receiver_ready=true
      break
    fi
  fi
  if ! kill -0 "$receiver_pid" 2>/dev/null; then
    cat "$receiver_log" >&2 || true
    die "API probe receiver가 준비 전 종료되었습니다."
  fi
  attempt=$((attempt + 1))
  sleep 0.05
done
if [ "$receiver_ready" != true ]; then
  cat "$receiver_log" >&2 || true
  die "API probe receiver 준비를 확인하지 못했습니다."
fi

for group in "${groups[@]}"; do
  kill -0 "$receiver_pid" 2>/dev/null || {
    cat "$receiver_log" >&2 || true
    die "APK 설치 중 API probe receiver가 조기 종료되었습니다."
  }
  "$adb_bin" -s "$serial" install -r "$build_dir/$group-wrapper.apk"
done

"$adb_bin" -s "$serial" shell am start \
  -a android.intent.action.VIEW \
  -d internet://extension \
  -p com.sec.android.app.sbrowser

cat <<'EOF'
각 SIX API Probe를 활성화한 뒤 더보기 메뉴에서 열어 자동 항목을 실행하세요.
사용자 동작 필요 항목은 화면의 실행 버튼을 눌러 주세요.
전송 재시도가 표시되면 같은 화면에서 재시도해 주세요.
여섯 group의 evidence가 도착하면 후보 프로필 검증을 시작합니다.
EOF

wait_iteration=0
while [ "$wait_iteration" -lt 180 ]; do
  missing_groups=
  if [ -e "$evidence_dir" ] || [ -L "$evidence_dir" ]; then
    [ -d "$evidence_dir" ] && [ ! -L "$evidence_dir" ] || die "evidence session이 안전한 directory가 아닙니다."
  fi
  for group in "${groups[@]}"; do
    evidence_file="$evidence_dir/$group.json"
    if [ -e "$evidence_file" ] || [ -L "$evidence_file" ]; then
      [ -f "$evidence_file" ] && [ ! -L "$evidence_file" ] || die "evidence가 symlink가 아닌 regular file이 아닙니다: $evidence_file"
    else
      if [ -z "$missing_groups" ]; then
        missing_groups=$group
      else
        missing_groups="$missing_groups $group"
      fi
    fi
  done
  if [ -z "$missing_groups" ]; then
    break
  fi
  if ! kill -0 "$receiver_pid" 2>/dev/null; then
    cat "$receiver_log" >&2 || true
    die "evidence 대기 중 API probe receiver가 조기 종료되었습니다."
  fi
  wait_iteration=$((wait_iteration + 1))
  sleep 10
done

if [ -n "$missing_groups" ]; then
  echo "미완료 group: $missing_groups" >&2
  echo "수신된 evidence는 보존됩니다: $evidence_dir" >&2
  exit 1
fi

"$sixbridge_bin" probe verify-evidence --input "$evidence_dir"
candidate_profile="$build_dir/candidate-profile.json"
[ ! -e "$candidate_profile" ] && [ ! -L "$candidate_profile" ] || die "후보 profile을 덮어쓸 수 없습니다."
"$sixbridge_bin" probe build-profile \
  --base "$profile_snapshot" \
  --evidence "$evidence_dir" \
  --output "$candidate_profile"

echo "API probe evidence: $evidence_dir"
echo "후보 profile: $candidate_profile"
