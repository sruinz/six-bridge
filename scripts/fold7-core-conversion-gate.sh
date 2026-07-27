#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <adb-serial>" >&2
    exit 2
fi
case "$1" in
    -*)
        echo "Usage: $0 <adb-serial>" >&2
        exit 2
        ;;
esac

readonly SERIAL=$1
readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly LOCAL_DIR="${ROOT_DIR}/.local"
readonly EVIDENCE_DIR="${LOCAL_DIR}/evidence"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly MANAGER_PACKAGE="dev.sixbridge.manager"
readonly MANAGER_APK="${ROOT_DIR}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly MANAGER_TEST_APK="${ROOT_DIR}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"
readonly INSTRUMENTATION="${MANAGER_PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
readonly GATE_CLASS="dev.sixbridge.manager.conversion.CoreConversionRuntimeInstrumentedTest"

source "${ROOT_DIR}/scripts/fold7-validation-lib.sh"

fail() {
    local message=$1
    local code=${2:-1}
    printf '오류: %s\n' "$message" >&2
    exit "$code"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "필수 도구를 찾을 수 없습니다: $1" 2
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

scan_d8_references() {
    local apk=$1
    local label=$2
    local strings_file="${RUN_DIR}/${label}-dex-strings.txt"

    unzip -p "$apk" 'classes*.dex' | LC_ALL=C strings >"$strings_file" ||
        fail "$label APK의 D8 classes.dex를 검사하지 못했습니다." 3
    if grep -Eq 'java[./]net[./]http' "$strings_file"; then
        fail "$label D8 output에 java.net.http 참조가 있습니다." 3
    fi
}

main() {
    local timestamp
    local gradle_command
    local default_java_home="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    local state
    local model
    local api
    local instrumentation_status

    require_command adb
    require_command shasum
    require_command strings
    require_command unzip
    [ -f "${ROOT_DIR}/scripts/fold7-validation-lib.sh" ] ||
        fail 'fold7 validation library가 없습니다.' 2
    [ ! -L "$LOCAL_DIR" ] ||
        fail '안전한 .local 디렉터리를 준비할 수 없습니다.' 3
    if [ ! -e "$LOCAL_DIR" ]; then
        mkdir "$LOCAL_DIR" || fail '.local 디렉터리를 만들 수 없습니다.' 3
    fi
    six_secure_directory "$LOCAL_DIR" "$ROOT_DIR" ||
        fail '안전한 .local 디렉터리를 준비할 수 없습니다.' 3
    six_secure_directory "$EVIDENCE_DIR" "$LOCAL_DIR" ||
        fail '안전한 evidence 디렉터리를 준비할 수 없습니다.' 3
    timestamp=$(date +%Y%m%d-%H%M%S)
    RUN_DIR="${EVIDENCE_DIR}/${timestamp}-core-conversion-gate"
    six_secure_directory "$RUN_DIR" "$EVIDENCE_DIR" ||
        fail '안전한 evidence run 디렉터리를 준비할 수 없습니다.' 3

    printf '# SIX Bridge Fold7 Android core conversion gate\n'
    printf 'serial=%s\n' "$SERIAL"
    adb devices -l >"${RUN_DIR}/adb-devices.txt" 2>&1 ||
        fail 'ADB 기기 목록을 읽지 못했습니다.' 3
    state=$(adb -s "$SERIAL" get-state 2>&1 | tr -d '\r')
    [ "$state" = device ] || fail '기기가 offline 상태입니다.' 3
    model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>&1 | tr -d '\r')
    api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>&1 | tr -d '\r')
    [[ "$api" =~ ^[0-9]+$ ]] && [ "$api" -ge 30 ] ||
        fail 'Android API 30 이상 기기가 필요합니다.' 3

    if [ -x "${ROOT_DIR}/gradlew" ]; then
        gradle_command="${ROOT_DIR}/gradlew"
    else
        gradle_command=gradle
    fi
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
    if [ ! -x "${JAVA_HOME:-}/bin/java" ] &&
        [ -x "${default_java_home}/bin/java" ]; then
        export JAVA_HOME="$default_java_home"
    fi
    [ -x "${JAVA_HOME:-}/bin/java" ] ||
        fail 'JDK를 찾을 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요.' 2
    if ! "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
        :android-manager:assembleDebug :android-manager:assembleDebugAndroidTest \
        >"${RUN_DIR}/gradle-build.txt" 2>&1; then
        cat "${RUN_DIR}/gradle-build.txt" >&2
        fail 'manager 또는 test APK 빌드에 실패했습니다.' 3
    fi
    [ -f "$MANAGER_APK" ] ||
        fail "manager debug APK가 없습니다: $MANAGER_APK" 3
    [ -f "$MANAGER_TEST_APK" ] ||
        fail "manager test APK가 없습니다: $MANAGER_TEST_APK" 3

    scan_d8_references "$MANAGER_APK" manager
    scan_d8_references "$MANAGER_TEST_APK" manager-test
    printf 'java.net.http=absent\n' >"${RUN_DIR}/d8-scan.txt"
    {
        printf 'serial=%s\n' "$SERIAL"
        printf 'model=%s\n' "$model"
        printf 'api=%s\n' "$api"
        printf 'gateClass=%s\n' "$GATE_CLASS"
        printf 'managerApkSha256=%s\n' "$(sha256_file "$MANAGER_APK")"
        printf 'managerTestApkSha256=%s\n' "$(sha256_file "$MANAGER_TEST_APK")"
    } >"${RUN_DIR}/device-metadata.txt"

    adb -s "$SERIAL" install -r "$MANAGER_APK" \
        >"${RUN_DIR}/install-manager.txt" 2>&1 ||
        fail 'manager debug APK 설치에 실패했습니다.' 3
    adb -s "$SERIAL" install -r "$MANAGER_TEST_APK" \
        >"${RUN_DIR}/install-manager-test.txt" 2>&1 ||
        fail 'manager test APK 설치에 실패했습니다.' 3

    set +e
    adb -s "$SERIAL" shell am instrument -w -r \
        -e class "$GATE_CLASS" "$INSTRUMENTATION" \
        >"${RUN_DIR}/instrumentation.txt" 2>&1
    instrumentation_status=$?
    set -e
    cat "${RUN_DIR}/instrumentation.txt"
    [ "$instrumentation_status" -eq 0 ] ||
        fail 'Android core conversion instrumentation이 실패했습니다.' 3
    grep -Fxq 'OK (2 tests)' "${RUN_DIR}/instrumentation.txt" ||
        fail 'instrumentation은 정확히 OK (2 tests)여야 합니다.' 3
    grep -Eq \
        'SIXBRIDGE_CORE_PREPARE_OK sourceSha256=[0-9a-f]{64} planSha256=[0-9a-f]{64} adapter=desktop-selection-event-bridge' \
        "${RUN_DIR}/instrumentation.txt" ||
        fail 'deterministic prepare hash/plan/adapter 증거가 없습니다.' 3
    grep -Eq \
        'SIXBRIDGE_DRAFT_REPLAY_OK schemaVersion=2 signerFingerprint=[0-9a-f]{64} sourceSha256=[0-9a-f]{64} planSha256=[0-9a-f]{64} adapter=desktop-selection-event-bridge' \
        "${RUN_DIR}/instrumentation.txt" ||
        fail 'schema v2 Ed25519 draft replay 증거가 없습니다.' 3

    printf 'evidence=%s\n' "$RUN_DIR"
    printf 'Host-side checks passed.\n'
}

main
