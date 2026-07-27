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
readonly BUILD_TOOLS="${SDK_ROOT}/build-tools/36.0.0"
readonly AAPT2="${BUILD_TOOLS}/aapt2"
readonly APKSIGNER="${BUILD_TOOLS}/apksigner"
readonly MANAGER_PACKAGE="dev.sixbridge.manager"
readonly MANAGER_APK="${ROOT_DIR}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly MANAGER_TEST_APK="${ROOT_DIR}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"
readonly INSTRUMENTATION="${MANAGER_PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
readonly GATE_CLASS="dev.sixbridge.manager.packager.OnDevicePackagerGateInstrumentedTest"
readonly GATE_DIRECTORY="files/ondevice-packager-gate"

source "${ROOT_DIR}/scripts/fold7-validation-lib.sh"

fail() {
    local message=$1
    local code=${2:-1}
    printf '오류: %s\n' "$message" >&2
    exit "$code"
}

require_tool() {
    [ -x "$1" ] || fail "필수 도구를 찾을 수 없습니다: $1" 2
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

device_run_as_copy() {
    local remote=$1
    local destination=$2
    adb -s "$SERIAL" exec-out run-as "$MANAGER_PACKAGE" cat "$remote" >"$destination" ||
        fail "run-as로 산출물을 회수하지 못했습니다: $remote" 3
    [ -s "$destination" ] || fail "회수한 산출물이 비어 있습니다: $remote" 3
}

assert_gate_json() {
    local state=$1
    local result=$2
    jq -e '
      . as $state |
      .schemaVersion == 1 and
      (.extensionId | type == "string" and test("^[a-p]{32}$")) and
      (.packageName | type == "string" and . == ("dev.sixbridge.ext." + $state.extensionId)) and
      (.wrapperSignerSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.crxSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.v1Sha256 | type == "string" and test("^[0-9a-f]{64}$"))
    ' "$state" >/dev/null || fail "gate-state.json schema 또는 packageName이 올바르지 않습니다." 3
    jq -e '
      .schemaVersion == 1 and
      (.v1 | type == "object") and (.v2 | type == "object") and
      (.v1.file == "gate-v1.apk") and (.v2.file == "gate-v2.apk") and
      (.v1.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.v2.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.v1.versionCode == 1) and (.v2.versionCode == 2)
    ' "$result" >/dev/null || fail "gate-result.json schema 또는 versionCode가 올바르지 않습니다." 3
}

assert_resources_contract() {
    local apk=$1
    local info=$2
    local header_offset
    local name_low
    local name_high
    local extra_low
    local extra_high
    local data_offset
    zipinfo -v "$apk" resources.arsc >"$info" ||
        fail "resources.arsc 정보를 읽지 못했습니다: $apk" 3
    grep -Fq 'compression method:                             none (stored)' "$info" ||
        fail "resources.arsc는 STORED여야 합니다: $apk" 3
    header_offset=$(awk '/offset of local header from start of archive:/ { print $NF; exit }' "$info")
    [[ "$header_offset" =~ ^[0-9]+$ ]] || fail "resources.arsc local header offset을 읽지 못했습니다: $apk" 3
    read -r name_low name_high extra_low extra_high < <(
        dd if="$apk" bs=1 skip=$((10#$header_offset + 26)) count=4 2>/dev/null |
            od -An -tu1
    )
    [[ "$name_low" =~ ^[0-9]+$ && "$name_high" =~ ^[0-9]+$ &&
       "$extra_low" =~ ^[0-9]+$ && "$extra_high" =~ ^[0-9]+$ ]] ||
        fail "resources.arsc local header 길이를 읽지 못했습니다: $apk" 3
    data_offset=$((10#$header_offset + 30 + name_low + (name_high << 8) + extra_low + (extra_high << 8)))
    [ $((data_offset % 4)) -eq 0 ] ||
        fail "resources.arsc data offset은 4-byte aligned여야 합니다: $apk" 3
}

verify_host_apk() {
    local apk=$1
    local expected_version=$2
    local extension_id=$3
    local package_name=$4
    local signer_sha=$5
    local crx_sha=$6
    local label=$7
    local v2_signer_output="${RUN_DIR}/${label}-apksigner-v2.txt"
    local v3_signer_output="${RUN_DIR}/${label}-apksigner-v3.txt"
    local badging="${RUN_DIR}/${label}-badging.txt"
    local xmltree="${RUN_DIR}/${label}-manifest.xmltree.txt"
    local resources="${RUN_DIR}/${label}-resources.txt"
    local local_info="${RUN_DIR}/${label}-resources.zipinfo.txt"
    local actual_signer
    local actual_crx

    [ -f "$apk" ] && [ ! -L "$apk" ] || fail "$label APK가 안전한 regular file이 아닙니다." 3
    "$APKSIGNER" verify --verbose --print-certs --min-sdk-version 24 "$apk" >"$v2_signer_output" 2>&1 ||
        fail "$label APK v2 apksigner verify에 실패했습니다." 3
    grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' "$v2_signer_output" ||
        fail "$label APK Signature Scheme v2 검증에 실패했습니다." 3
    "$APKSIGNER" verify --verbose --print-certs --min-sdk-version 30 "$apk" >"$v3_signer_output" 2>&1 ||
        fail "$label APK v3 apksigner verify에 실패했습니다." 3
    grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' "$v3_signer_output" ||
        fail "$label APK Signature Scheme v3 검증에 실패했습니다." 3
    actual_signer=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$v3_signer_output" | head -n 1)
    [ "$actual_signer" = "$signer_sha" ] || fail "$label APK signer SHA-256이 gate-state와 다릅니다." 3

    "$AAPT2" dump badging "$apk" >"$badging" || fail "$label APK badging을 읽지 못했습니다." 3
    grep -Fq "package: name='${package_name}' versionCode='${expected_version}'" "$badging" ||
        fail "$label APK package/versionCode가 gate-state와 다릅니다." 3
    "$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml >"$xmltree" ||
        fail "$label APK manifest를 읽지 못했습니다." 3
    grep -Fq "$extension_id" "$xmltree" || fail "$label APK extension-id가 다릅니다." 3
    grep -Fq "${package_name}.contentprovider" "$xmltree" ||
        fail "$label APK provider authority가 다릅니다." 3
    if ! grep -Fq 'six://ex' "$xmltree"; then
        grep -Fq '="six"' "$xmltree" || fail "$label APK six://ex scheme contract가 없습니다." 3
        grep -Fq '="ex"' "$xmltree" || fail "$label APK six://ex host contract가 없습니다." 3
    fi
    "$AAPT2" dump resources "$apk" >"$resources" || fail "$label APK resources를 읽지 못했습니다." 3
    assert_resources_contract "$apk" "$local_info"
    actual_crx=$(unzip -p "$apk" assets/extension.crx | shasum -a 256 | awk '{print $1}')
    [ "$actual_crx" = "$crx_sha" ] || fail "$label APK embedded CRX SHA-256이 gate-state와 다릅니다." 3
}

main() {
    local timestamp
    local installed
    local state
    local result
    local v1
    local v2
    local extension_id
    local package_name
    local signer_sha
    local crx_sha
    local v1_sha
    local v2_sha
    local installed_version
    local discovery
    local provider_magic
    local gradle_command
    local default_java_home="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"

    require_tool "$AAPT2"
    require_tool "$APKSIGNER"
    command -v jq >/dev/null 2>&1 || fail '필수 도구를 찾을 수 없습니다: jq' 2
    command -v adb >/dev/null 2>&1 || fail '필수 도구를 찾을 수 없습니다: adb' 2
    [ -f "${ROOT_DIR}/scripts/fold7-validation-lib.sh" ] || fail 'fold7 validation library가 없습니다.' 2
    [ ! -L "$LOCAL_DIR" ] || fail '안전한 .local 디렉터리를 준비할 수 없습니다.' 3
    if [ ! -e "$LOCAL_DIR" ]; then mkdir "$LOCAL_DIR" || fail '.local 디렉터리를 만들 수 없습니다.' 3; fi
    six_secure_directory "$LOCAL_DIR" "$ROOT_DIR" || fail '안전한 .local 디렉터리를 준비할 수 없습니다.' 3
    six_secure_directory "$EVIDENCE_DIR" "$LOCAL_DIR" || fail '안전한 evidence 디렉터리를 준비할 수 없습니다.' 3
    timestamp=$(date +%Y%m%d-%H%M%S)
    RUN_DIR="${EVIDENCE_DIR}/${timestamp}-ondevice-packager-gate"
    six_secure_directory "$RUN_DIR" "$EVIDENCE_DIR" || fail '안전한 evidence run 디렉터리를 준비할 수 없습니다.' 3
    exec 3>&1
    exec >"${RUN_DIR}/gate.log" 2>&1
    trap 'status=$?; cat "${RUN_DIR}/gate.log" >&3; exit "$status"' EXIT

    echo '# SIX Bridge Fold7 on-device packager gate'
    echo "serial=${SERIAL}"
    adb -s "$SERIAL" get-state | grep -Fxq device || fail '기기가 offline 상태입니다.' 3

    if [ -x "${ROOT_DIR}/gradlew" ]; then
        gradle_command="${ROOT_DIR}/gradlew"
    else
        gradle_command=gradle
    fi
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
    if [ ! -x "${JAVA_HOME:-}/bin/java" ] && [ -x "${default_java_home}/bin/java" ]; then
        export JAVA_HOME="$default_java_home"
    fi
    [ -x "${JAVA_HOME:-}/bin/java" ] || fail 'JDK를 찾을 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요.' 2
    "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
        :android-manager:assembleDebug :android-manager:assembleDebugAndroidTest
    [ -f "$MANAGER_APK" ] || fail "manager debug APK가 없습니다: $MANAGER_APK" 3
    [ -f "$MANAGER_TEST_APK" ] || fail "manager test APK가 없습니다: $MANAGER_TEST_APK" 3
    adb -s "$SERIAL" install -r "$MANAGER_APK"
    adb -s "$SERIAL" install -r "$MANAGER_TEST_APK"

    adb -s "$SERIAL" shell am instrument -w -r -e class \
        "${GATE_CLASS}#generateV1AndPersistIdentityEvidence" "$INSTRUMENTATION" ||
        fail 'V1 instrumentation method가 실패했습니다.' 3
    adb -s "$SERIAL" shell am instrument -w -r -e class \
        "${GATE_CLASS}#generateV2AfterProcessRestartAndMatchIdentity" "$INSTRUMENTATION" ||
        fail 'V2 instrumentation method가 실패했습니다.' 3

    state="${RUN_DIR}/gate-state.json"
    result="${RUN_DIR}/gate-result.json"
    v1="${RUN_DIR}/gate-v1.apk"
    v2="${RUN_DIR}/gate-v2.apk"
    device_run_as_copy "${GATE_DIRECTORY}/gate-state.json" "$state"
    device_run_as_copy "${GATE_DIRECTORY}/gate-result.json" "$result"
    device_run_as_copy "${GATE_DIRECTORY}/gate-v1.apk" "$v1"
    device_run_as_copy "${GATE_DIRECTORY}/gate-v2.apk" "$v2"
    assert_gate_json "$state" "$result"
    extension_id=$(jq -er '.extensionId' "$state")
    package_name=$(jq -er '.packageName' "$state")
    signer_sha=$(jq -er '.wrapperSignerSha256' "$state")
    crx_sha=$(jq -er '.crxSha256' "$state")
    v1_sha=$(jq -er '.v1Sha256' "$state")
    v2_sha=$(jq -er '.v2.sha256' "$result")
    [ "$(sha256_file "$v1")" = "$v1_sha" ] || fail 'v1 APK SHA-256이 gate-state와 다릅니다.' 3
    [ "$(sha256_file "$v1")" = "$(jq -er '.v1.sha256' "$result")" ] ||
        fail 'v1 APK SHA-256이 gate-result와 다릅니다.' 3
    [ "$(sha256_file "$v2")" = "$v2_sha" ] || fail 'v2 APK SHA-256이 gate-result와 다릅니다.' 3

    verify_host_apk "$v1" 1 "$extension_id" "$package_name" "$signer_sha" "$crx_sha" v1
    verify_host_apk "$v2" 2 "$extension_id" "$package_name" "$signer_sha" "$crx_sha" v2
    cmp -s <(unzip -p "$v1" assets/extension.crx) <(unzip -p "$v2" assets/extension.crx) ||
        fail 'v1/v2 embedded CRX가 다릅니다.' 3

    installed=$(adb -s "$SERIAL" shell pm path "$package_name" || true)
    if [ -n "$installed" ]; then
        fail "기존 wrapper package가 설치되어 있습니다: ${package_name}. 자동 삭제 또는 downgrade를 하지 않았습니다. Samsung Add-ons에서 비활성화한 뒤 Android 설정에서 수동 제거하고 다시 실행하세요." 3
    fi
    adb -s "$SERIAL" install "$v1"
    adb -s "$SERIAL" install -r "$v2"
    installed_version=$(adb -s "$SERIAL" shell dumpsys package "$package_name" |
        sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
    [ "$installed_version" = 2 ] || fail '설치된 wrapper versionCode가 2가 아닙니다.' 3

    discovery=$(adb -s "$SERIAL" shell cmd package resolve-activity --brief \
        -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
        -d six://ex -p "$package_name")
    case "$discovery" in *"$package_name"*) ;; *) fail 'six://ex discovery가 wrapper package와 일치하지 않습니다.' 3;; esac
    provider_magic=$(adb -s "$SERIAL" shell content read \
        --uri "content://${package_name}.contentprovider" | head -c 4)
    [ "$provider_magic" = Cr24 ] || fail 'ContentProvider Cr24 magic이 일치하지 않습니다.' 3
    adb -s "$SERIAL" shell am start -a android.intent.action.VIEW \
        -d internet://extension -p com.sec.android.app.sbrowser
    echo
    echo '## Relevant logcat'
    adb -s "$SERIAL" shell logcat -d -t 1000 |
        grep -E "${package_name}|${extension_id}|SixManager|SixPkg|TerraceExtensions|dev_ext" || true
    echo
    echo "evidence=${RUN_DIR}"
    echo 'Host-side checks passed.'
}

main
