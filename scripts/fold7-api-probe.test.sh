#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
runner_source="$project_dir/scripts/fold7-api-probe.sh"

pass_count=0

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  pass_count=$((pass_count + 1))
  echo "ok $pass_count - $1"
}

expect_status() {
  expected=$1
  shift
  set +e
  "$@" 9<&- >"$case_root/stdout" 2>"$case_root/stderr"
  actual=$?
  set -e
  [ "$actual" -eq "$expected" ] || {
    sed -n '1,120p' "$case_root/stdout" >&2 || true
    sed -n '1,120p' "$case_root/stderr" >&2 || true
    fail "exit $expected 예상, 실제 $actual: $*"
  }
}

expect_nonzero() {
  set +e
  "$@" 9<&- >"$case_root/stdout" 2>"$case_root/stderr"
  actual=$?
  set -e
  [ "$actual" -ne 0 ] || fail "실패해야 하는데 성공했습니다: $*"
}

assert_log_count() {
  expected=$1
  pattern=$2
  actual=$(grep -F -c -- "$pattern" "$command_log" || true)
  [ "$actual" -eq "$expected" ] || fail "log '$pattern' count $expected 예상, 실제 $actual"
}

assert_exact_log_count() {
  expected=$1
  line=$2
  actual=$(grep -F -x -c -- "$line" "$command_log" || true)
  [ "$actual" -eq "$expected" ] || fail "exact log '$line' count $expected 예상, 실제 $actual"
}

assert_no_log() {
  pattern=$1
  if grep -F -- "$pattern" "$command_log" >/dev/null 2>&1; then
    fail "금지된 명령이 실행됨: $pattern"
  fi
}

assert_event_count() {
  expected=$1
  pattern=$2
  actual=$(grep -F -c -- "$pattern" "$events" || true)
  [ "$actual" -eq "$expected" ] || fail "event '$pattern' count $expected 예상, 실제 $actual"
}

new_fixture() {
  hash -r
  case_root=$(mktemp -d "${TMPDIR:-/tmp}/six-probe-runner-test.XXXXXX")
  fixture_project="$case_root/project"
  fake_bin="$case_root/bin"
  command_log="$case_root/commands.log"
  profile="$fixture_project/profiles/sbrowser.json"
  mkdir -p "$fixture_project/scripts" "$fixture_project/profiles" "$fake_bin" "$case_root/home"
  : >"$command_log"
  cp "$runner_source" "$fixture_project/scripts/fold7-api-probe.sh"
  chmod +x "$fixture_project/scripts/fold7-api-probe.sh"
  : >"$fixture_project/scripts/api-probe-receiver.mjs"
  printf '%s\n' '{"fixture":"profile"}' >"$profile"

  cat >"$fake_bin/date" <<'EOF'
#!/bin/bash
if [ -r /dev/fd/9 ]; then
  printf '%s\n' 'fd9-leaked-before:date' >>"$SIX_TEST_EVENTS"
fi
printf 'runner-pid=%s\n' "$PPID" >>"$SIX_TEST_EVENTS"
if [ -n "${SIX_TEST_DATE_REPLACE_PROFILE:-}" ]; then
  replacement="${SIX_TEST_DATE_REPLACE_PROFILE}.replacement.$$"
  printf '%s\n' '{"fixture":"replaced"}' >"$replacement"
  mv "$replacement" "$SIX_TEST_DATE_REPLACE_PROFILE"
fi
printf '%s\n' '20990102-030405'
EOF

  cat >"$fake_bin/sleep" <<'EOF'
#!/bin/bash
if [ "${SIX_TEST_SLEEP_MODE:-fast}" = block ] || [ "${1:-}" = 0.05 ]; then
  exec /bin/sleep "$@"
fi
exit 0
EOF

  cat >"$fake_bin/mkdir" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ -r /dev/fd/9 ]; then
  printf '%s\n' 'fd9-leaked-before:mkdir' >>"$SIX_TEST_EVENTS"
fi
target=
for argument in "$@"; do target=$argument; done
case "$target" in
  */.local/probe-build/20990102-030405)
    case "${SIX_TEST_SNAPSHOT_PRECLAIM:-none}" in
      regular)
        /bin/mkdir "$target"
        printf '%s\n' 'victim-regular' >"$target/base-profile.json"
        exit 0
        ;;
      symlink)
        /bin/mkdir "$target"
        /bin/ln -s "$SIX_TEST_SNAPSHOT_VICTIM" "$target/base-profile.json"
        exit 0
        ;;
    esac
    ;;
  */.local/evidence/api-probe/20990102-030405)
    if [ "${SIX_TEST_MKDIR_MODE:-normal}" = evidence_race ] && [ ! -e "$target" ]; then
      /bin/mkdir "$target"
      printf '%s\n' 'competitor' >"$target/owner.txt"
      exit 98
    fi
    ;;
esac
exec /bin/mkdir "$@"
EOF

  cat >"$fake_bin/adb" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ -r /dev/fd/9 ]; then
  printf '%s\n' 'fd9-leaked-after:adb' >>"$SIX_TEST_EVENTS"
fi
printf 'ADB' >>"$SIX_TEST_COMMAND_LOG"
printf ' %s' "$@" >>"$SIX_TEST_COMMAND_LOG"
printf '\n' >>"$SIX_TEST_COMMAND_LOG"
[ "$1" = -s ] || exit 71
shift 2
case "$1" in
  get-state)
    if [ "${SIX_TEST_ADB_MODE:-device}" = offline ]; then
      printf '%s\n' 'offline'
    else
      printf '%s\n' 'device'
    fi
    ;;
  reverse)
    if [ "$2" = --remove ]; then
      printf '%s\n' 'reverse-removed' >>"$SIX_TEST_EVENTS"
      [ "${SIX_TEST_ADB_MODE:-device}" != reverse_remove_fail ] || exit 75
    else
      [ "$2" = --no-rebind ] && [ "$3" = tcp:47171 ] && [ "$4" = tcp:47171 ] || {
        printf '%s\n' 'unsafe reverse rebind' >&2
        exit 77
      }
      if [ "${SIX_TEST_ADB_MODE:-device}" = preexisting_reverse ]; then
        printf '%s\n' 'pre-existing-mapping-preserved' >>"$SIX_TEST_EVENTS"
        exit 76
      fi
      if [ "${SIX_TEST_ADB_MODE:-device}" = reverse_fail ]; then
        exit 72
      fi
      printf '%s\n' 'reverse-set' >>"$SIX_TEST_EVENTS"
      case "${SIX_TEST_ADB_MODE:-device}" in
        signal_after_reverse_term)
          printf 'signal-target=%s\n' "$PPID" >>"$SIX_TEST_EVENTS"
          kill -TERM "$PPID"
          ;;
        signal_after_reverse_int)
          printf 'signal-target=%s\n' "$PPID" >>"$SIX_TEST_EVENTS"
          kill -INT "$PPID"
          ;;
      esac
    fi
    ;;
  install)
    count=0
    [ ! -f "$SIX_TEST_INSTALL_COUNT" ] || count=$(cat "$SIX_TEST_INSTALL_COUNT")
    count=$((count + 1))
    printf '%s\n' "$count" >"$SIX_TEST_INSTALL_COUNT"
    if [ "${SIX_TEST_INSTALL_FAIL_AT:-0}" -eq "$count" ]; then
      exit 73
    fi
    printf '%s\n' 'Success'
    ;;
  shell)
    ;;
  *)
    exit 74
    ;;
esac
EOF

  cat >"$fake_bin/sixbridge" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ -r /dev/fd/9 ]; then
  printf '%s\n' 'fd9-leaked-after:sixbridge' >>"$SIX_TEST_EVENTS"
fi
printf 'SIX' >>"$SIX_TEST_COMMAND_LOG"
printf ' %s' "$@" >>"$SIX_TEST_COMMAND_LOG"
printf '\n' >>"$SIX_TEST_COMMAND_LOG"
printf 'cli-path=%s\n' "$0" >>"$SIX_TEST_EVENTS"
command_name=$1
shift
if [ "$command_name" = --help ]; then
  printf '%s\n' 'Commands:'
  printf '%s\n' '  build  build command'
  if [ "${SIX_TEST_CLI_CONTRACT:-compatible}" != stale ]; then
    printf '%s\n' '  probe  probe command'
  fi
  exit 0
fi
if [ "$command_name" = probe ]; then
  [ "${SIX_TEST_CLI_CONTRACT:-compatible}" != stale ] || exit 86
  if [ "$#" -eq 0 ]; then
    printf '%s\n' 'Commands:'
    printf '%s\n' '  generate         generate command'
    printf '%s\n' '  prepare-session  prepare command'
    printf '%s\n' '  verify-evidence  verify command'
    printf '%s\n' '  build-profile    profile command'
    exit 0
  fi
  command_name="probe-$1"
  shift
fi
output=
input=
session_id=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) output=$2; shift 2 ;;
    --input) input=$2; shift 2 ;;
    --session-id) session_id=$2; shift 2 ;;
    *) shift ;;
  esac
done
case "$command_name" in
  probe-generate)
    mkdir -p "$output"
    for group in advanced-runtime base-runtime identity-privacy network tab-page user-interface; do
      mkdir "$output/$group"
      printf '%s\n' "$group" >"$output/$group/manifest.json"
    done
    ;;
  build)
    group=$(basename -- "$input")
    if [ "${SIX_TEST_BUILD_FAIL_GROUP:-}" = "$group" ]; then
      exit 81
    fi
    printf '%s\n' bundle >"$output"
    printf '%s\n' crx >"${output%.six}.crx"
    printf '%s\n' wrapper >"${output%.six}-wrapper.apk"
    ;;
  probe-prepare-session)
    [ "${SIX_TEST_PREPARE_FAIL:-0}" -eq 0 ] || exit 82
    printf '{"schemaVersion":1,"sessionId":"%s"}\n' "$session_id" >"$output"
    chmod 600 "$output"
    ;;
  probe-verify-evidence)
    [ "${SIX_TEST_VERIFY_FAIL:-0}" -eq 0 ] || exit 83
    ;;
  probe-build-profile)
    [ "${SIX_TEST_PROFILE_FAIL:-0}" -eq 0 ] || exit 84
    printf '%s\n' candidate >"$output"
    ;;
  *)
    exit 85
    ;;
esac
EOF

  cat >"$fake_bin/node" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ -r /dev/fd/9 ]; then
  printf '%s\n' 'fd9-leaked-after:node' >>"$SIX_TEST_EVENTS"
fi
printf 'NODE' >>"$SIX_TEST_COMMAND_LOG"
printf ' %s' "$@" >>"$SIX_TEST_COMMAND_LOG"
printf '\n' >>"$SIX_TEST_COMMAND_LOG"
printf 'node-pid=%s\n' "$$" >>"$SIX_TEST_EVENTS"
mode=${SIX_TEST_NODE_MODE:-success}
if [ "$mode" = early_exit ]; then
  printf '%s\n' 'receiver early exit' >&2
  exit 91
fi
if [ "$mode" = port_collision ]; then
  printf '%s\n' 'EADDRINUSE' >&2
  exit 92
fi
root=
config=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --root) root=$2; shift 2 ;;
    --session-config) config=$2; shift 2 ;;
    *) shift ;;
  esac
done
session_id=$(basename -- "$(dirname -- "$config")")
[ -d "$root/$session_id" ] && [ ! -L "$root/$session_id" ] || {
  printf '%s\n' 'receiver requires pre-owned safe session directory' >&2
  exit 93
}
permissions=$(LC_ALL=C ls -ld "$root/$session_id")
case "$permissions" in
  d???------*) ;;
  *) printf '%s\n' 'receiver requires owner-only session directory' >&2; exit 94 ;;
esac
trap 'printf "node-term=%s\\n" "$$" >>"$SIX_TEST_EVENTS"; exit 0' TERM INT
if [ "$mode" != never_ready ]; then
  printf '%s\n' 'SIX API probe receiver: http://127.0.0.1:47171/evidence'
fi
if [ "$mode" = success ] || [ "$mode" = partial ]; then
  mkdir -p "$root/$session_id"
  groups='advanced-runtime base-runtime identity-privacy network tab-page user-interface'
  for group in $groups; do
    printf '{"group":"%s"}\n' "$group" >"$root/$session_id/$group.json"
    [ "$mode" != partial ] || break
  done
fi
while :; do /bin/sleep 1; done
EOF

  chmod +x "$fake_bin/date" "$fake_bin/sleep" "$fake_bin/mkdir" "$fake_bin/adb" "$fake_bin/sixbridge" "$fake_bin/node"
  events="$case_root/events.log"
  install_count="$case_root/install-count"
  : >"$events"
  export SIX_TEST_COMMAND_LOG="$command_log"
  export SIX_TEST_EVENTS="$events"
  export SIX_TEST_INSTALL_COUNT="$install_count"
  export HOME="$case_root/home"
  export PATH="$fake_bin:/usr/bin:/bin:/usr/sbin:/sbin"
  unset SIX_TEST_ADB_MODE SIX_TEST_BUILD_FAIL_GROUP SIX_TEST_INSTALL_FAIL_AT
  unset SIX_TEST_NODE_MODE SIX_TEST_PREPARE_FAIL SIX_TEST_VERIFY_FAIL SIX_TEST_PROFILE_FAIL
  unset SIX_TEST_SLEEP_MODE SIX_TEST_MKDIR_MODE SIX_TEST_DATE_REPLACE_PROFILE
  unset SIX_TEST_SNAPSHOT_PRECLAIM SIX_TEST_SNAPSHOT_VICTIM
  unset SIX_TEST_CLI_CONTRACT
  runner="$fixture_project/scripts/fold7-api-probe.sh"
}

install_fixture_local_cli() {
  local_cli="$fixture_project/cli/build/install/sixbridge/bin/sixbridge"
  /bin/mkdir -p "$(dirname -- "$local_cli")"
  cp "$fake_bin/sixbridge" "$local_cli"
  chmod +x "$local_cli"
}

cleanup_fixture() {
  rm -rf "$case_root"
}

[ -f "$runner_source" ] || fail "runner가 없어 RED: $runner_source"

new_fixture
expect_status 0 "$runner" --help
expect_status 2 "$runner" --help --unknown value
expect_status 2 "$runner" --help --serial serial-1 --profile "$profile"
expect_status 2 "$runner" --help --help
expect_status 2 "$runner"
expect_status 2 "$runner" --serial serial-1
expect_status 2 "$runner" --profile "$profile"
expect_status 2 "$runner" --unknown value --serial serial-1 --profile "$profile"
expect_status 2 "$runner" --serial serial-1 --serial serial-2 --profile "$profile"
expect_status 2 "$runner" --serial 'bad serial' --profile "$profile"
expect_status 2 "$runner" --serial serial-1 --profile "$case_root/missing-profile.json"
ln -s "$profile" "$case_root/profile-link.json"
expect_status 2 "$runner" --serial serial-1 --profile "$case_root/profile-link.json"
assert_no_log 'ADB '
cleanup_fixture
pass '단독 help만 허용하고 혼합 help·인자·serial·profile symlink를 device 접근 전 거부'

new_fixture
install_fixture_local_cli
export SIX_TEST_CLI_CONTRACT=stale
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_no_log 'ADB '
assert_no_log 'SIX probe generate --output '
[ ! -e "$fixture_project/.local/probe-build" ] || fail 'stale local CLI 실패 전에 build session 경로를 만들었습니다'
[ ! -e "$fixture_project/.local/evidence/api-probe" ] || fail 'stale local CLI 실패 전에 evidence session 경로를 만들었습니다'
grep -F './gradlew :cli:installDist' "$case_root/stderr" >/dev/null || fail 'stale local CLI 재생성 안내가 없습니다'
grep -F 'source와 일치하지 않습니다' "$case_root/stderr" >/dev/null || fail 'source/build 불일치 오류가 명확하지 않습니다'
if grep -F "cli-path=$fake_bin/sixbridge" "$events" >/dev/null 2>&1; then
  fail 'stale local CLI에서 PATH CLI로 fallback했습니다'
fi
cleanup_fixture
pass 'stale local CLI를 PATH fallback 없이 device·session 접근 전에 거부'

new_fixture
install_fixture_local_cli
expect_status 0 "$runner" --serial serial-1 --profile "$profile"
assert_exact_log_count 1 'SIX --help'
assert_exact_log_count 1 'SIX probe'
assert_log_count 1 'SIX probe generate --output '
if grep -F "cli-path=$fake_bin/sixbridge" "$events" >/dev/null 2>&1; then
  fail 'compatible local CLI 대신 PATH CLI를 사용했습니다'
fi
cleanup_fixture
pass 'compatible local CLI의 read-only 계약 확인 후 정상 실행'

new_fixture
export SIX_TEST_ADB_MODE=offline
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'ADB -s serial-1 get-state'
assert_no_log 'reverse --no-rebind tcp:47171'
cleanup_fixture
pass 'offline device 거부'

new_fixture
export SIX_TEST_ADB_MODE=preexisting_reverse
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'ADB -s serial-1 reverse --no-rebind tcp:47171 tcp:47171'
assert_no_log 'ADB -s serial-1 install -r '
assert_no_log 'ADB -s serial-1 reverse --remove tcp:47171'
grep -Fx 'pre-existing-mapping-preserved' "$events" >/dev/null || fail '기존 reverse mapping 보존 증거가 없습니다'
cleanup_fixture
pass '기존 reverse mapping이 있으면 rebind·install·remove 없이 실패'

new_fixture
export SIX_TEST_ADB_MODE=signal_after_reverse_term
expect_status 143 "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'ADB -s serial-1 reverse --no-rebind tcp:47171 tcp:47171'
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
assert_no_log 'ADB -s serial-1 install -r '
assert_event_count 1 'reverse-set'
assert_event_count 1 'reverse-removed'
runner_pid=$(sed -n 's/^runner-pid=//p' "$events")
signal_target=$(sed -n 's/^signal-target=//p' "$events")
[ -n "$runner_pid" ] && [ "$runner_pid" = "$signal_target" ] || fail "SIGTERM target이 실제 runner PID가 아닙니다: runner=$runner_pid target=$signal_target"
cleanup_fixture
pass 'reverse 성공 직후 SIGTERM에서도 ownership 확정 뒤 정확히 한 번 cleanup'

new_fixture
export SIX_TEST_ADB_MODE=signal_after_reverse_int
expect_status 130 "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'ADB -s serial-1 reverse --no-rebind tcp:47171 tcp:47171'
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
assert_no_log 'ADB -s serial-1 install -r '
assert_event_count 1 'reverse-set'
assert_event_count 1 'reverse-removed'
runner_pid=$(sed -n 's/^runner-pid=//p' "$events")
signal_target=$(sed -n 's/^signal-target=//p' "$events")
[ -n "$runner_pid" ] && [ "$runner_pid" = "$signal_target" ] || fail "SIGINT target이 실제 runner PID가 아닙니다: runner=$runner_pid target=$signal_target"
cleanup_fixture
pass 'reverse 성공 직후 SIGINT에서도 ownership 확정 뒤 정확히 한 번 cleanup'

new_fixture
expect_status 0 "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'SIX probe generate --output '
assert_log_count 6 'SIX build --input '
assert_log_count 1 'SIX probe prepare-session '
assert_log_count 1 'NODE '
assert_log_count 6 'ADB -s serial-1 install -r '
assert_log_count 1 'ADB -s serial-1 shell am start -a android.intent.action.VIEW -d internet://extension -p com.sec.android.app.sbrowser'
assert_log_count 1 'SIX probe verify-evidence --input '
assert_log_count 1 'SIX probe build-profile --base '
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
assert_log_count 1 'ADB -s serial-1 reverse --no-rebind tcp:47171 tcp:47171'
generate_line=$(grep -n -F 'SIX probe generate --output ' "$command_log" | cut -d: -f1)
last_build_line=$(grep -n -F 'SIX build --input ' "$command_log" | tail -1 | cut -d: -f1)
prepare_number=$(grep -n -F 'SIX probe prepare-session ' "$command_log" | cut -d: -f1)
node_line=$(grep -n -F 'NODE ' "$command_log" | cut -d: -f1)
first_install_line=$(grep -n -F 'ADB -s serial-1 install -r ' "$command_log" | head -1 | cut -d: -f1)
[ "$generate_line" -lt "$last_build_line" ] && [ "$last_build_line" -lt "$prepare_number" ] && \
  [ "$prepare_number" -lt "$node_line" ] && [ "$node_line" -lt "$first_install_line" ] || \
  fail "generate→build×6→prepare-session→receiver→install 순서가 아닙니다"
prepare_line=$(grep -F 'SIX probe prepare-session ' "$command_log")
expected_artifacts='--artifact-set advanced-runtime='
case "$prepare_line" in *"$expected_artifacts"*' --artifact-set base-runtime='*' --artifact-set identity-privacy='*' --artifact-set network='*' --artifact-set tab-page='*' --artifact-set user-interface='*) ;;
  *) fail "prepare-session artifact-set canonical 순서가 아닙니다: $prepare_line" ;;
esac
build_dir="$fixture_project/.local/probe-build/20990102-030405"
evidence_dir="$fixture_project/.local/evidence/api-probe/20990102-030405"
[ -f "$build_dir/candidate-profile.json" ] || fail "후보 profile이 없습니다"
[ -f "$build_dir/base-profile.json" ] && [ ! -L "$build_dir/base-profile.json" ] || fail 'profile snapshot이 owner session의 regular file이 아닙니다'
cmp -s "$profile" "$build_dir/base-profile.json" || fail 'profile snapshot bytes가 source와 다릅니다'
snapshot_permissions=$(LC_ALL=C ls -ld "$build_dir/base-profile.json")
case "$snapshot_permissions" in
  -rw-------*) ;;
  *) fail "profile snapshot이 owner-only mode가 아닙니다: $snapshot_permissions" ;;
esac
if grep -F 'fd9-leaked-' "$events" >/dev/null 2>&1; then
  fail "profile FD9가 의도한 snapshot writer 이외의 child에 노출됨: $(grep -F 'fd9-leaked-' "$events" | tr '\n' ' ')"
fi
[ "$(find "$evidence_dir" -type f -name '*.json' | wc -l | tr -d ' ')" -eq 6 ] || fail "evidence 6개가 보존되지 않았습니다"
node_pid=$(sed -n 's/^node-pid=//p' "$events")
node_term=$(sed -n 's/^node-term=//p' "$events")
[ -n "$node_pid" ] && [ "$node_pid" = "$node_term" ] || fail "exact receiver PID를 종료하지 않았습니다"
cleanup_fixture
pass '정상 흐름의 6-group 순서·session·evidence·cleanup'

new_fixture
export SIX_TEST_DATE_REPLACE_PROFILE="$profile"
expect_status 0 "$runner" --serial serial-1 --profile "$profile"
fixture_project_real=$(CDPATH= cd -- "$fixture_project" && pwd -P)
profile_snapshot="$fixture_project_real/.local/probe-build/20990102-030405/base-profile.json"
grep -F '"fixture":"replaced"' "$profile" >/dev/null || fail 'source profile 교체 fixture가 실행되지 않았습니다'
cmp -s "$profile" "$profile_snapshot" || fail '사전 child 완료 뒤 재검증한 source bytes가 snapshot과 다릅니다'
assert_log_count 7 "--profile $profile_snapshot"
assert_log_count 1 "--base $profile_snapshot"
assert_no_log "$profile"
cleanup_fixture
pass '사전 child 완료 뒤 profile을 재검증하고 private snapshot으로 고정'

new_fixture
export SIX_TEST_NODE_MODE=never_ready
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_no_log 'ADB -s serial-1 install -r '
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
cleanup_fixture
pass 'receiver 준비 전 APK 설치 금지'

new_fixture
export SIX_TEST_NODE_MODE=port_collision
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_no_log 'ADB -s serial-1 install -r '
grep -F 'EADDRINUSE' "$case_root/stderr" >/dev/null || grep -F 'EADDRINUSE' "$case_root/stdout" >/dev/null || fail "port 충돌 원인이 출력되지 않았습니다"
cleanup_fixture
pass 'receiver port 충돌 실패'

new_fixture
export SIX_TEST_NODE_MODE=early_exit
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_no_log 'ADB -s serial-1 install -r '
grep -F 'receiver early exit' "$case_root/stderr" "$case_root/stdout" >/dev/null || fail "receiver 조기 종료 원인이 없습니다"
cleanup_fixture
pass 'receiver 조기 종료 실패'

new_fixture
export SIX_TEST_BUILD_FAIL_GROUP=network
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_no_log 'NODE '
assert_no_log 'ADB -s serial-1 install -r '
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
[ -d "$fixture_project/.local/probe-build/20990102-030405" ] || fail "실패 build 증거가 보존되지 않았습니다"
cleanup_fixture
pass '일부 build 실패와 산출물 보존'

new_fixture
export SIX_TEST_INSTALL_FAIL_AT=3
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_log_count 3 'ADB -s serial-1 install -r '
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
node_pid=$(sed -n 's/^node-pid=//p' "$events")
node_term=$(sed -n 's/^node-term=//p' "$events")
[ "$node_pid" = "$node_term" ] || fail "install 실패에서 receiver exact PID cleanup 실패"
cleanup_fixture
pass '일부 install 실패 cleanup'

new_fixture
export SIX_TEST_NODE_MODE=partial
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
evidence_dir="$fixture_project/.local/evidence/api-probe/20990102-030405"
[ -f "$evidence_dir/advanced-runtime.json" ] || fail "timeout에서 받은 evidence를 삭제했습니다"
grep -F '미완료 group: base-runtime identity-privacy network tab-page user-interface' "$case_root/stderr" "$case_root/stdout" >/dev/null || fail "timeout 미완료 group 출력이 없습니다"
assert_no_log 'SIX probe verify-evidence '
cleanup_fixture
pass 'timeout 미완료 보고와 evidence 보존'

new_fixture
mkdir -p "$fixture_project/.local/probe-build/20990102-030405"
printf '%s\n' keep >"$fixture_project/.local/probe-build/20990102-030405/owner.txt"
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
grep -Fx keep "$fixture_project/.local/probe-build/20990102-030405/owner.txt" >/dev/null || fail "기존 session을 덮어썼습니다"
assert_no_log 'ADB '
cleanup_fixture
pass '기존 session output no-overwrite'

new_fixture
mkdir -p "$fixture_project/.local/evidence/api-probe/20990102-030405"
printf '%s\n' keep >"$fixture_project/.local/evidence/api-probe/20990102-030405/owner.txt"
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
grep -Fx keep "$fixture_project/.local/evidence/api-probe/20990102-030405/owner.txt" >/dev/null || fail "기존 evidence session을 덮어썼습니다"
assert_no_log 'ADB '
cleanup_fixture
pass '기존 evidence session no-overwrite'

new_fixture
export SIX_TEST_MKDIR_MODE=evidence_race
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
evidence_dir="$fixture_project/.local/evidence/api-probe/20990102-030405"
grep -Fx competitor "$evidence_dir/owner.txt" >/dev/null || fail 'evidence mkdir 경쟁자 데이터를 보존하지 않았습니다'
assert_no_log 'ADB '
cleanup_fixture
pass 'evidence session mkdir 경쟁에서 no-overwrite·device 접근 금지'

new_fixture
export SIX_TEST_SNAPSHOT_PRECLAIM=regular
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
snapshot="$fixture_project/.local/probe-build/20990102-030405/base-profile.json"
grep -Fx 'victim-regular' "$snapshot" >/dev/null || fail '선점된 regular snapshot bytes를 변경했습니다'
assert_no_log 'ADB '
cleanup_fixture
pass 'regular profile snapshot 선점 시 no-replace·device 접근 금지'

new_fixture
victim="$case_root/snapshot-victim.json"
printf '%s\n' 'victim-symlink' >"$victim"
export SIX_TEST_SNAPSHOT_PRECLAIM=symlink
export SIX_TEST_SNAPSHOT_VICTIM="$victim"
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
snapshot="$fixture_project/.local/probe-build/20990102-030405/base-profile.json"
[ -L "$snapshot" ] || fail '선점된 snapshot symlink를 교체했습니다'
grep -Fx 'victim-symlink' "$victim" >/dev/null || fail 'snapshot symlink victim bytes를 변경했습니다'
assert_no_log 'ADB '
cleanup_fixture
pass 'symlink profile snapshot 선점 시 no-follow·device 접근 금지'

new_fixture
export SIX_TEST_NODE_MODE=partial
export SIX_TEST_SLEEP_MODE=block
set +e
(
  trap - TERM
  exec "$runner" --serial serial-1 --profile "$profile"
) >"$case_root/stdout" 2>"$case_root/stderr" &
runner_pid=$!
set -e
attempt=0
while ! grep -F 'shell am start' "$command_log" >/dev/null 2>&1; do
  kill -0 "$runner_pid" 2>/dev/null || fail "signal test runner가 너무 일찍 종료됨"
  attempt=$((attempt + 1))
  [ "$attempt" -lt 100 ] || fail "signal test 준비 timeout"
  /bin/sleep 0.05
done
kill -TERM "$runner_pid"
set +e
wait "$runner_pid"
signal_status=$?
set -e
[ "$signal_status" -eq 143 ] || fail "SIGTERM exit 143 예상, 실제 $signal_status"
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
node_pid=$(sed -n 's/^node-pid=//p' "$events")
node_term=$(sed -n 's/^node-term=//p' "$events")
[ "$node_pid" = "$node_term" ] || fail "SIGTERM exact receiver PID cleanup 실패"
cleanup_fixture
pass 'SIGTERM exact PID·reverse cleanup'

new_fixture
export SIX_TEST_NODE_MODE=partial
export SIX_TEST_SLEEP_MODE=block
runner_pid_file="$case_root/runner.pid"
set +e
/usr/bin/perl -e '
  use strict;
  use warnings;
  my $pid_file = shift @ARGV;
  my $pid = fork();
  die "fork failed" if !defined $pid;
  if ($pid == 0) {
    $SIG{INT} = "DEFAULT";
    exec @ARGV;
    die "exec failed";
  }
  open my $handle, ">", $pid_file or die "pid file failed";
  print {$handle} "$pid\n";
  close $handle;
  waitpid($pid, 0);
  my $status = $?;
  exit(128 + ($status & 127)) if $status & 127;
  exit($status >> 8);
' "$runner_pid_file" "$runner" --serial serial-1 --profile "$profile" \
  >"$case_root/stdout" 2>"$case_root/stderr" &
controller_pid=$!
set -e
attempt=0
while [ ! -s "$runner_pid_file" ]; do
  kill -0 "$controller_pid" 2>/dev/null || fail "SIGINT controller가 너무 일찍 종료됨"
  attempt=$((attempt + 1))
  [ "$attempt" -lt 100 ] || fail "SIGINT runner PID 대기 timeout"
  /bin/sleep 0.05
done
runner_pid=$(cat "$runner_pid_file")
attempt=0
while ! grep -F 'shell am start' "$command_log" >/dev/null 2>&1; do
  kill -0 "$runner_pid" 2>/dev/null || fail "SIGINT test runner가 너무 일찍 종료됨"
  attempt=$((attempt + 1))
  [ "$attempt" -lt 100 ] || fail "SIGINT test 준비 timeout"
  /bin/sleep 0.05
done
kill -INT "$runner_pid"
set +e
wait "$controller_pid"
signal_status=$?
set -e
[ "$signal_status" -eq 130 ] || fail "SIGINT exit 130 예상, 실제 $signal_status"
assert_log_count 1 'ADB -s serial-1 reverse --remove tcp:47171'
node_pid=$(sed -n 's/^node-pid=//p' "$events")
node_term=$(sed -n 's/^node-term=//p' "$events")
[ "$node_pid" = "$node_term" ] || fail "SIGINT exact receiver PID cleanup 실패"
cleanup_fixture
pass 'SIGINT exact PID·reverse cleanup'

new_fixture
export SIX_TEST_ADB_MODE=reverse_remove_fail
expect_status 0 "$runner" --serial serial-1 --profile "$profile"
grep -F 'adb reverse mapping을 제거하지 못했습니다' "$case_root/stderr" >/dev/null || fail "reverse cleanup 경고가 없습니다"
cleanup_fixture
pass 'reverse cleanup 실패가 원래 성공을 덮지 않음'

new_fixture
export SIX_TEST_VERIFY_FAIL=1
expect_nonzero "$runner" --serial serial-1 --profile "$profile"
assert_log_count 1 'SIX probe verify-evidence --input '
assert_no_log 'SIX probe build-profile '
cleanup_fixture
pass 'evidence 검증 실패 시 profile 게시 금지'

if ! git -C "$project_dir" check-ignore -q .local/probe-build/example/file; then
  fail '.local/probe-build/가 Git에서 제외되지 않습니다'
fi
pass '.local/probe-build/ Git 제외'

echo "PASS: $pass_count tests"
