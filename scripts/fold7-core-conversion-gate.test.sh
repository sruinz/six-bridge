#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-core-conversion-gate.sh"
readonly GATE_CLASS="dev.sixbridge.manager.conversion.CoreConversionRuntimeInstrumentedTest"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file=$1
    local expected=$2
    local message=$3
    grep -Fq -- "$expected" "$file" || fail "$message"
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    local message=$3
    if grep -Fq -- "$unexpected" "$file"; then
        fail "$message"
    fi
}

assert_exit() {
    local expected=$1
    shift
    local output
    output=$(mktemp "${TMPDIR:-/tmp}/sixbridge-core-gate-exit.XXXXXX")
    if "$@" >"$output" 2>&1; then
        actual=0
    else
        actual=$?
    fi
    [ "$actual" = "$expected" ] || {
        cat "$output" >&2
        fail "exit code가 ${expected}이어야 합니다. 실제: ${actual}"
    }
    ASSERT_OUTPUT=$output
}

make_apks() {
    local dex_value=${1:-Ldev/sixbridge/core/ConversionPreparationEngine;}
    local work="${FIXTURE_ROOT}/apk-work"
    rm -rf -- "$work"
    mkdir -p "$work"
    printf '%s\n' "$dex_value" >"${work}/classes.dex"
    (
        cd "$work"
        zip -q "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug/android-manager-debug.apk" \
            classes.dex
        zip -q "${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk" \
            classes.dex
    )
}

make_fixture() {
    FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-core-gate.XXXXXX")
    mkdir -p \
        "${FIXTURE_ROOT}/scripts" \
        "${FIXTURE_ROOT}/bin" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug" \
        "${FIXTURE_ROOT}/.local"
    cp "$GATE_SCRIPT" "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh"
    cp "${ROOT_DIR}/scripts/fold7-validation-lib.sh" \
        "${FIXTURE_ROOT}/scripts/fold7-validation-lib.sh"
    chmod +x "${FIXTURE_ROOT}/scripts/"*.sh
    printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>"$SIXBRIDGE_TEST_GRADLE_LOG"\n' \
        >"${FIXTURE_ROOT}/gradlew"
    chmod +x "${FIXTURE_ROOT}/gradlew"
    make_apks

    cat >"${FIXTURE_ROOT}/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_ADB_LOG"
if [ "$1" = devices ]; then
  printf 'List of devices attached\n'
  printf 'serial-1\tdevice product:q7qksx model:SM_F966N device:q7q\n'
  exit 0
fi
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
  get-state)
    printf '%s\n' "${ADB_STATE:-device}"
    ;;
  install)
    printf 'Success\n'
    ;;
  shell)
    shift
    case "$*" in
      'getprop ro.product.model') printf 'SM_F966N\n';;
      'getprop ro.build.version.sdk') printf '%s\n' "${ADB_API:-36}";;
      *'am instrument'*)
        if [ "${INSTRUMENT_RESULT:-ok}" = fail ]; then
          printf 'FAILURES!!!\nTests run: 2,  Failures: 1\n'
          exit 1
        elif [ "${INSTRUMENT_RESULT:-ok}" = one ]; then
          printf 'SIXBRIDGE_CORE_PREPARE_OK sourceSha256=%064d planSha256=%064d adapter=desktop-selection-event-bridge\n' 0 0
          printf 'OK (1 test)\n'
        else
          printf 'SIXBRIDGE_CORE_PREPARE_OK sourceSha256=%064d planSha256=%064d adapter=desktop-selection-event-bridge\n' 0 1
          printf 'SIXBRIDGE_DRAFT_REPLAY_OK schemaVersion=2 signerFingerprint=%064d sourceSha256=%064d planSha256=%064d adapter=desktop-selection-event-bridge\n' 2 0 1
          printf 'OK (2 tests)\n'
        fi
        ;;
      *) exit 2;;
    esac
    ;;
  *) exit 2;;
esac
EOF
    chmod +x "${FIXTURE_ROOT}/bin/adb"
}

run_gate() {
    local output=$1
    shift
    if [ "$output" = /dev/null ]; then
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
            SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
            SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
            bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" "$@"
    else
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
            SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
            SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
            bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" "$@" \
            >"$output" 2>&1
    fi
}

expect_failure() {
    local expected=$1
    shift
    local output="${FIXTURE_ROOT}/failure.log"
    if "$@" >"$output" 2>&1; then
        cat "$output" >&2
        fail "실패해야 하는 gate가 성공했습니다: $expected"
    fi
    assert_contains "$output" "$expected" "예상 거부 이유가 없습니다: $expected"
}

main() {
    [[ -x "$GATE_SCRIPT" ]] || fail "host gate script가 없습니다: $GATE_SCRIPT"

    make_fixture
    trap 'rm -rf -- "${FIXTURE_ROOT:-}"' EXIT

    assert_exit 2 bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh"
    assert_contains "$ASSERT_OUTPUT" 'Usage:' '인자 없음 사용법이 없습니다.'
    rm -f -- "$ASSERT_OUTPUT"
    assert_exit 2 bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" --bad
    rm -f -- "$ASSERT_OUTPUT"

    output="${FIXTURE_ROOT}/gate-output.log"
    run_gate "$output" serial-1
    assert_contains "$output" 'Host-side checks passed.' '정상 host gate가 성공하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/adb.log" \
        "-e class ${GATE_CLASS}" \
        '지정 Android 계측 class만 실행하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/gradle.log" \
        ':android-manager:assembleDebug :android-manager:assembleDebugAndroidTest' \
        'manager와 test APK를 함께 빌드하지 않았습니다.'
    run_dir=$(find "${FIXTURE_ROOT}/.local/evidence" -mindepth 1 -maxdepth 1 \
        -type d -name '*-core-conversion-gate' | head -n 1)
    [ -n "$run_dir" ] || fail 'core conversion evidence 디렉터리가 없습니다.'
    assert_contains "${run_dir}/instrumentation.txt" 'OK (2 tests)' \
        '원문 instrumentation 결과가 보존되지 않았습니다.'
    assert_contains "${run_dir}/instrumentation.txt" 'SIXBRIDGE_CORE_PREPARE_OK' \
        'prepare hash 증거가 보존되지 않았습니다.'
    assert_contains "${run_dir}/instrumentation.txt" 'SIXBRIDGE_DRAFT_REPLAY_OK' \
        'draft replay 증거가 보존되지 않았습니다.'
    assert_contains "${run_dir}/device-metadata.txt" 'model=SM_F966N' \
        '기기 model 메타데이터가 없습니다.'
    assert_contains "${run_dir}/device-metadata.txt" 'api=36' \
        '기기 API 메타데이터가 없습니다.'
    assert_contains "${run_dir}/d8-scan.txt" 'java.net.http=absent' \
        'D8 금지 참조 검사 결과가 없습니다.'

    expect_failure '기기가 offline 상태입니다.' env ADB_STATE=offline \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
        bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" serial-1
    expect_failure '정확히 OK (2 tests)' env INSTRUMENT_RESULT=one \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
        bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" serial-1
    expect_failure 'API 30 이상' env ADB_API=29 \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
        bash "${FIXTURE_ROOT}/scripts/fold7-core-conversion-gate.sh" serial-1

    rm -f \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug/android-manager-debug.apk" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"
    make_apks 'Ljava/net/http/HttpClient;'
    : >"${FIXTURE_ROOT}/adb.log"
    expect_failure 'java.net.http 참조' run_gate /dev/null serial-1
    assert_not_contains "${FIXTURE_ROOT}/adb.log" ' install ' \
        'D8 금지 참조 뒤 APK install이 실행되었습니다.'

    rm -rf -- "${FIXTURE_ROOT}/.local/evidence"
    ln -s "${FIXTURE_ROOT}/bin" "${FIXTURE_ROOT}/.local/evidence"
    expect_failure '안전한 evidence 디렉터리' run_gate /dev/null serial-1

    printf 'PASS: Fold7 Android core conversion host gate 계약\n'
}

main "$@"
