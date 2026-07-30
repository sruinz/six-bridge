#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-mobile-analysis-gate.sh"
FIXTURE_ROOT=

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file=$1
    local expected=$2
    local message=$3
    grep -Fq -- "$expected" "$file" || {
        cat "$file" >&2
        fail "$message"
    }
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    local message=$3
    if grep -Fq -- "$unexpected" "$file"; then
        cat "$file" >&2
        fail "$message"
    fi
}

cleanup() {
    if [ -n "$FIXTURE_ROOT" ]; then
        rm -rf -- "$FIXTURE_ROOT"
    fi
}
trap cleanup EXIT

make_fixture() {
    FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-mobile-analysis-gate.XXXXXX")
    mkdir -p \
        "${FIXTURE_ROOT}/scripts" \
        "${FIXTURE_ROOT}/bin" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug"
    cp "$GATE_SCRIPT" "${FIXTURE_ROOT}/scripts/fold7-mobile-analysis-gate.sh"
    chmod +x "${FIXTURE_ROOT}/scripts/fold7-mobile-analysis-gate.sh"
    : >"${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"

    cat >"${FIXTURE_ROOT}/bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$*" = '+%Y%m%d-%H%M%S' ] || exit 90
printf '20260730-123456\n'
EOF
    cat >"${FIXTURE_ROOT}/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_ADB_LOG"
[ "${1:-}" = -s ] || exit 91
[ "${2:-}" = 192.0.2.10:39359 ] || exit 92
shift 2
command=${1:-}
case "$command" in
  get-state)
    [ "${ADB_FAIL_ON:-}" != get-state ] || exit 41
    printf 'device\n'
    ;;
  install)
    [ "${ADB_FAIL_ON:-}" != install ] || exit 42
    [ "$*" = "install -r android-manager/build/outputs/apk/debug/android-manager-debug.apk" ] ||
      exit 93
    printf 'Success\n'
    ;;
  shell)
    shift
    case "$*" in
      'am force-stop dev.sixbridge.manager') ;;
      'monkey -p dev.sixbridge.manager 1')
        printf 'Events injected: 1\n'
        ;;
      'pidof dev.sixbridge.manager')
        [ "${ADB_FAIL_ON:-}" != pidof ] || exit 43
        printf '3210\n'
        ;;
      "run-as dev.sixbridge.manager find cache -maxdepth 1 -name sixbridge-analysis-* -type d -print")
        printf 'cache/sixbridge-analysis-observed\n'
        ;;
      *) exit 94 ;;
    esac
    ;;
  logcat)
    [ "$*" = 'logcat -d -v threadtime' ] || exit 95
    case "${ADB_LOGCAT_MODE:-empty}" in
      empty) ;;
      match)
        printf 'unrelated line\n'
        printf '07-30 I sixbridge: manager started\n'
        printf '07-30 E AndroidRuntime: sample\n'
        ;;
      fail) exit 44 ;;
      *) exit 96 ;;
    esac
    ;;
  *) exit 97 ;;
esac
EOF
    chmod +x "${FIXTURE_ROOT}/bin/date" "${FIXTURE_ROOT}/bin/adb"
}

run_gate() {
    local output=$1
    shift
    (
        cd "$FIXTURE_ROOT"
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
            SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
            bash scripts/fold7-mobile-analysis-gate.sh "$@"
    ) >"$output" 2>&1
}

[ -f "$GATE_SCRIPT" ] ||
    fail "production gate script가 아직 없습니다: $GATE_SCRIPT"

make_fixture

missing_output="${FIXTURE_ROOT}/missing-serial.log"
if run_gate "$missing_output"; then
    fail "serial이 없을 때 성공했습니다."
fi
[ ! -e "${FIXTURE_ROOT}/adb.log" ] ||
    fail "serial 검증 전에 adb가 실행됐습니다."

wrong_serial_output="${FIXTURE_ROOT}/wrong-serial.log"
if run_gate "$wrong_serial_output" 192.168.0.99:5555; then
    fail "다른 serial을 gate가 허용했습니다."
fi
assert_contains "$wrong_serial_output" \
    'Fold7 serial만 허용합니다: 192.0.2.10:39359' \
    "다른 serial의 명확한 한글 거부 이유가 없습니다."
[ ! -d "${FIXTURE_ROOT}/.local/evidence" ] ||
    fail "다른 serial 거부 전에 evidence 디렉터리가 생성됐습니다."
[ ! -e "${FIXTURE_ROOT}/adb.log" ] ||
    fail "다른 serial 거부 전에 adb가 실행됐습니다."

success_output="${FIXTURE_ROOT}/success.log"
run_gate "$success_output" 192.0.2.10:39359
readonly EVIDENCE="${FIXTURE_ROOT}/.local/evidence/20260730-123456-mobile-analysis-gate.log"
[ -f "$EVIDENCE" ] || fail "evidence log가 생성되지 않았습니다."
[ ! -s "$EVIDENCE" ] || fail "일치 없는 logcat evidence가 비어 있지 않습니다."

cat >"${FIXTURE_ROOT}/expected-adb.log" <<'EOF'
-s 192.0.2.10:39359 get-state
-s 192.0.2.10:39359 install -r android-manager/build/outputs/apk/debug/android-manager-debug.apk
-s 192.0.2.10:39359 shell am force-stop dev.sixbridge.manager
-s 192.0.2.10:39359 shell monkey -p dev.sixbridge.manager 1
-s 192.0.2.10:39359 shell pidof dev.sixbridge.manager
-s 192.0.2.10:39359 shell run-as dev.sixbridge.manager find cache -maxdepth 1 -name sixbridge-analysis-* -type d -print
-s 192.0.2.10:39359 logcat -d -v threadtime
EOF
cmp -s "${FIXTURE_ROOT}/expected-adb.log" "${FIXTURE_ROOT}/adb.log" ||
    fail "허용된 ADB 명령 순서 또는 exact APK/package 계약이 다릅니다."
assert_not_contains "${FIXTURE_ROOT}/adb.log" 'logcat -c' \
    "logcat clear가 실행됐습니다."
assert_not_contains "${FIXTURE_ROOT}/adb.log" 'pm clear' \
    "app data clear가 실행됐습니다."
assert_not_contains "${FIXTURE_ROOT}/adb.log" 'uninstall' \
    "uninstall이 실행됐습니다."
assert_not_contains "${FIXTURE_ROOT}/adb.log" ' rm ' \
    "device 삭제 명령이 실행됐습니다."
assert_not_contains "${FIXTURE_ROOT}/adb.log" 'cache/recovery-bitwarden-20260730' \
    "격리 Bitwarden 경로를 조작했습니다."

: >"${FIXTURE_ROOT}/adb.log"
ADB_LOGCAT_MODE=match run_gate "${FIXTURE_ROOT}/matched.log" 192.0.2.10:39359
assert_contains "$EVIDENCE" 'sixbridge: manager started' \
    "sixbridge logcat이 evidence에 없습니다."
assert_contains "$EVIDENCE" 'AndroidRuntime: sample' \
    "AndroidRuntime logcat이 evidence에 없습니다."
assert_not_contains "$EVIDENCE" 'unrelated line' \
    "무관한 logcat이 evidence에 포함됐습니다."

for failure in get-state install pidof; do
    : >"${FIXTURE_ROOT}/adb.log"
    if ADB_FAIL_ON=$failure run_gate \
        "${FIXTURE_ROOT}/${failure}-failure.log" \
        192.0.2.10:39359; then
        fail "${failure} 실패를 gate가 통과시켰습니다."
    fi
    case "$failure" in
        get-state)
            assert_not_contains "${FIXTURE_ROOT}/adb.log" ' install ' \
                "get-state 실패 뒤 install이 실행됐습니다."
            ;;
        install)
            assert_not_contains "${FIXTURE_ROOT}/adb.log" 'force-stop' \
                "install 실패 뒤 force-stop이 실행됐습니다."
            ;;
        pidof)
            assert_not_contains "${FIXTURE_ROOT}/adb.log" 'find cache' \
                "pidof 실패 뒤 cache listing이 실행됐습니다."
            ;;
    esac
done

: >"${FIXTURE_ROOT}/adb.log"
if ADB_LOGCAT_MODE=fail run_gate \
    "${FIXTURE_ROOT}/logcat-failure.log" \
    192.0.2.10:39359; then
    fail "logcat transport 실패를 gate가 통과시켰습니다."
fi

printf 'PASS: Fold7 모바일 분석 게이트 계약\n'
