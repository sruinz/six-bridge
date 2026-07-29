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
readonly CLI="${ROOT_DIR}/cli/build/install/sixbridge/bin/sixbridge"
readonly INSTRUMENTATION="${MANAGER_PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
readonly GATE_CLASS="dev.sixbridge.manager.conversion.OnDeviceConversionFlowInstrumentedTest"
readonly GATE_DIRECTORY="files/ondevice-conversion-flow"
readonly EXPECTED_MODEL="SM-F966N"

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

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "필수 명령을 찾을 수 없습니다: $1" 2
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

copy_device_file() {
    local remote=$1
    local destination=$2
    adb -s "$SERIAL" exec-out run-as "$MANAGER_PACKAGE" cat "$remote" \
        >"$destination" ||
        fail "run-as로 산출물을 회수하지 못했습니다: $remote" 3
    [ -s "$destination" ] ||
        fail "회수한 산출물이 비어 있습니다: $remote" 3
}

run_instrumentation_method() {
    local method=$1
    local label=$2
    local output="${RUN_DIR}/${label}-instrumentation.txt"
    if ! adb -s "$SERIAL" shell am instrument -w -r \
        -e class "${GATE_CLASS}#${method}" "$INSTRUMENTATION" \
        >"$output" 2>&1; then
        cat "$output" >&2
        fail "${label} instrumentation이 실패했습니다." 3
    fi
    cat "$output"
    [ "$(grep -Fxc 'OK (1 test)' "$output" || true)" -eq 1 ] ||
        fail "${label} instrumentation은 정확히 OK (1 test)여야 합니다." 3
}

assert_evidence_schema() {
    local evidence=$1
    local phase=$2
    jq -e --arg phase "$phase" '
      (keys | sort) == ([
        "crxSha256", "extensionId", "packageName", "phase",
        "planSha256", "profileSha256", "resultSha256", "schemaVersion",
        "signerSha256", "sixFile", "sixSha256", "sourceSha256",
        "versionCode", "wrapperFile", "wrapperSha256"
      ] | sort) and
      .schemaVersion == 1 and
      .phase == $phase and
      (.sourceSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.resultSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.planSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.profileSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.extensionId | type == "string" and test("^[a-p]{32}$")) and
      (.packageName == ("dev.sixbridge.ext." + .extensionId)) and
      (.signerSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.versionCode | type == "number" and . >= 1 and floor == .) and
      .sixFile == ($phase + ".six") and
      (.sixSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      .wrapperFile == ($phase + "-wrapper.apk") and
      (.wrapperSha256 | type == "string" and test("^[0-9a-f]{64}$")) and
      (.crxSha256 | type == "string" and test("^[0-9a-f]{64}$"))
    ' "$evidence" >/dev/null ||
        fail "$phase evidence schema가 올바르지 않습니다." 3
}

verify_wrapper() {
    local phase=$1
    local evidence=$2
    local wrapper=$3
    local extension_id package_name signer version crx_sha actual_signer
    local v2_signer_output="${RUN_DIR}/${phase}-apksigner-v2.txt"
    local v3_signer_output="${RUN_DIR}/${phase}-apksigner-v3.txt"
    local badging="${RUN_DIR}/${phase}-badging.txt"
    local xmltree="${RUN_DIR}/${phase}-manifest.xmltree.txt"
    extension_id=$(jq -er '.extensionId' "$evidence")
    package_name=$(jq -er '.packageName' "$evidence")
    signer=$(jq -er '.signerSha256' "$evidence")
    version=$(jq -er '.versionCode' "$evidence")
    crx_sha=$(jq -er '.crxSha256' "$evidence")

    "$APKSIGNER" verify --verbose --print-certs --min-sdk-version 24 \
        "$wrapper" >"$v2_signer_output" 2>&1 ||
        fail "$phase wrapper v2 apksigner 검증에 실패했습니다." 3
    grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' \
        "$v2_signer_output" ||
        fail "$phase wrapper v2 서명 계약이 없습니다." 3
    "$APKSIGNER" verify --verbose --print-certs --min-sdk-version 30 \
        "$wrapper" >"$v3_signer_output" 2>&1 ||
        fail "$phase wrapper v3 apksigner 검증에 실패했습니다." 3
    grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' \
        "$v3_signer_output" ||
        fail "$phase wrapper v3 서명 계약이 없습니다." 3
    actual_signer=$(sed -n \
        's/^Signer #1 certificate SHA-256 digest: //p' "$v3_signer_output" |
        head -n 1)
    [ "$actual_signer" = "$signer" ] ||
        fail "$phase wrapper signer가 evidence와 다릅니다." 3

    "$AAPT2" dump badging "$wrapper" >"$badging" ||
        fail "$phase wrapper package 정보를 읽지 못했습니다." 3
    grep -Fq "package: name='${package_name}' versionCode='${version}'" \
        "$badging" ||
        fail "$phase wrapper package/versionCode가 evidence와 다릅니다." 3
    "$AAPT2" dump xmltree "$wrapper" --file AndroidManifest.xml >"$xmltree" ||
        fail "$phase wrapper manifest를 읽지 못했습니다." 3
    grep -Fq "$extension_id" "$xmltree" &&
        grep -Fq "${package_name}.contentprovider" "$xmltree" ||
        fail "$phase wrapper 계약이 없습니다." 3
    if ! grep -Fq 'six://ex' "$xmltree"; then
        grep -Fq '="six"' "$xmltree" &&
            grep -Fq '="ex"' "$xmltree" ||
            fail "$phase wrapper 계약이 없습니다." 3
    fi

    unzip -p "$wrapper" assets/extension.crx >"${RUN_DIR}/${phase}.crx" ||
        fail "$phase wrapper에서 CRX3를 추출하지 못했습니다." 3
    [ "$(sha256_file "${RUN_DIR}/${phase}.crx")" = "$crx_sha" ] ||
        fail "$phase CRX3 SHA-256이 evidence와 다릅니다." 3
    if ! "$CLI" verify-crx \
        --input "${RUN_DIR}/${phase}.crx" \
        --expected-id "$extension_id" \
        >"${RUN_DIR}/${phase}-crx3-verification.txt" 2>&1; then
        cat "${RUN_DIR}/${phase}-crx3-verification.txt" >&2
        fail "$phase CRX3 검증에 실패했습니다." 3
    fi
    grep -Fxq "extensionId=${extension_id}" \
        "${RUN_DIR}/${phase}-crx3-verification.txt" ||
        fail "$phase CRX3 extension ID 증거가 없습니다." 3
}

verify_bundle() {
    local phase=$1
    local evidence="${RUN_DIR}/${phase}-result.json"
    local bundle="${RUN_DIR}/${phase}.six"
    local wrapper="${RUN_DIR}/${phase}-wrapper.apk"
    local extracted="${RUN_DIR}/${phase}-bundle-wrapper.apk"
    local extension_json="${RUN_DIR}/${phase}-extension.json"
    local compatibility_json="${RUN_DIR}/${phase}-compatibility.json"

    assert_evidence_schema "$evidence" "$phase"
    unzip -t "$bundle" >"${RUN_DIR}/${phase}-sixbundle-unzip-test.txt" ||
        fail "$phase SixBundle archive가 손상되었습니다." 3
    [ "$(zipinfo -1 "$bundle" | LC_ALL=C sort)" = \
        "$(printf '%s\n' compatibility.json extension.json wrapper.apk)" ] ||
        fail "$phase SixBundle entry 계약이 올바르지 않습니다." 3
    [ "$(sha256_file "$bundle")" = "$(jq -er '.sixSha256' "$evidence")" ] ||
        fail "$phase .six SHA-256이 evidence와 다릅니다." 3
    [ "$(sha256_file "$wrapper")" = "$(jq -er '.wrapperSha256' "$evidence")" ] ||
        fail "$phase wrapper SHA-256이 evidence와 다릅니다." 3
    unzip -p "$bundle" wrapper.apk >"$extracted" ||
        fail "$phase SixBundle wrapper.apk를 추출하지 못했습니다." 3
    cmp -s "$wrapper" "$extracted" ||
        fail "$phase SixBundle wrapper가 별도 wrapper와 다릅니다." 3
    unzip -p "$bundle" extension.json >"$extension_json" ||
        fail "$phase SixBundle extension.json을 읽지 못했습니다." 3
    unzip -p "$bundle" compatibility.json >"$compatibility_json" ||
        fail "$phase SixBundle compatibility.json을 읽지 못했습니다." 3
    jq -e --slurpfile evidence "$evidence" '
      .schemaVersion == 1 and
      .extensionId == $evidence[0].extensionId and
      .wrapperPackage == $evidence[0].packageName and
      .wrapperVersionCode == $evidence[0].versionCode and
      .signerSha256 == $evidence[0].signerSha256 and
      .crxSha256 == $evidence[0].crxSha256 and
      .wrapperSha256 == $evidence[0].wrapperSha256
    ' "$extension_json" >/dev/null ||
        fail "$phase SixBundle descriptor가 evidence와 다릅니다." 3
    jq -e --slurpfile evidence "$evidence" '
      .schemaVersion == 4 and
      .originalSourceSha256 == $evidence[0].sourceSha256 and
      .sourceSha256 == $evidence[0].resultSha256 and
      .planSha256 == $evidence[0].planSha256 and
      .browserProfileSha256 == $evidence[0].profileSha256 and
      .convertedExtensionId == $evidence[0].extensionId
    ' "$compatibility_json" >/dev/null ||
        fail "$phase SixBundle provenance가 evidence와 다릅니다." 3

    verify_wrapper "$phase" "$evidence" "$wrapper"
}

main() {
    local state model api timestamp
    local default_java_home="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    require_tool "$AAPT2"
    require_tool "$APKSIGNER"
    for command in adb cmp dd grep jq mktemp shasum unzip zipinfo; do
        require_command "$command"
    done
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
    RUN_DIR=$(mktemp -d \
        "${EVIDENCE_DIR}/${timestamp}-ondevice-conversion-flow.XXXXXX") ||
        fail '고유 evidence run 디렉터리를 만들 수 없습니다.' 3
    six_secure_directory "$RUN_DIR" "$EVIDENCE_DIR" ||
        fail '안전한 evidence run 디렉터리를 준비할 수 없습니다.' 3

    printf '# SIX Bridge Fold7 on-device conversion flow gate\n'
    printf 'serial=%s\n' "$SERIAL"
    adb devices -l >"${RUN_DIR}/adb-devices.txt" 2>&1 ||
        fail 'ADB 기기 목록을 읽지 못했습니다.' 3
    state=$(adb -s "$SERIAL" get-state 2>&1 | tr -d '\r')
    [ "$state" = device ] || fail '기기가 offline 상태입니다.' 3
    model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>&1 | tr -d '\r')
    api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>&1 | tr -d '\r')
    [ "$model" = "$EXPECTED_MODEL" ] ||
        fail "정확한 Fold7 ${EXPECTED_MODEL} 기기가 필요합니다." 3
    [[ "$api" =~ ^[0-9]+$ ]] && [ "$api" -ge 30 ] ||
        fail 'Android API 30 이상 기기가 필요합니다.' 3

    if [ -x "${ROOT_DIR}/gradlew" ]; then
        GRADLE_COMMAND="${ROOT_DIR}/gradlew"
    else
        GRADLE_COMMAND="${SIXBRIDGE_GRADLE:-gradle}"
    fi
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
    if [ ! -x "${JAVA_HOME:-}/bin/java" ] &&
        [ -x "${default_java_home}/bin/java" ]; then
        export JAVA_HOME="$default_java_home"
    fi
    [ -x "${JAVA_HOME:-}/bin/java" ] ||
        fail 'JDK를 찾을 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요.' 2
    "$GRADLE_COMMAND" -Pkotlin.jvm.target.validation.mode=IGNORE \
        :cli:installDist \
        :android-manager:assembleDebug :android-manager:assembleDebugAndroidTest \
        >"${RUN_DIR}/gradle-build.txt" 2>&1 ||
        fail 'CLI, manager 또는 test APK 빌드에 실패했습니다.' 3
    [ -x "$CLI" ] ||
        fail "CRX3 검증 CLI가 없습니다: $CLI" 3
    [ -f "$MANAGER_APK" ] ||
        fail "manager debug APK가 없습니다: $MANAGER_APK" 3
    [ -f "$MANAGER_TEST_APK" ] ||
        fail "manager test APK가 없습니다: $MANAGER_TEST_APK" 3
    adb -s "$SERIAL" install -r "$MANAGER_APK" \
        >"${RUN_DIR}/install-manager.txt" 2>&1 ||
        fail 'manager debug APK install -r에 실패했습니다.' 3
    adb -s "$SERIAL" install -r "$MANAGER_TEST_APK" \
        >"${RUN_DIR}/install-manager-test.txt" 2>&1 ||
        fail 'manager test APK install -r에 실패했습니다.' 3

    run_instrumentation_method \
        generateInitialBundleFromSmartContextZip initial
    run_instrumentation_method \
        generateUpdateWithSameIdentityAndHigherVersion update

    for phase in initial update; do
        copy_device_file \
            "${GATE_DIRECTORY}/${phase}-result.json" \
            "${RUN_DIR}/${phase}-result.json"
        copy_device_file \
            "${GATE_DIRECTORY}/${phase}.six" \
            "${RUN_DIR}/${phase}.six"
        copy_device_file \
            "${GATE_DIRECTORY}/${phase}-wrapper.apk" \
            "${RUN_DIR}/${phase}-wrapper.apk"
        assert_evidence_schema "${RUN_DIR}/${phase}-result.json" "$phase"
    done

    for field in sourceSha256 resultSha256 planSha256 profileSha256; do
        [ "$(jq -er ".${field}" "${RUN_DIR}/initial-result.json")" = \
            "$(jq -er ".${field}" "${RUN_DIR}/update-result.json")" ] ||
            fail "initial/update ${field}가 변경되었습니다." 3
    done
    [ "$(jq -er '.extensionId' "${RUN_DIR}/initial-result.json")" = \
        "$(jq -er '.extensionId' "${RUN_DIR}/update-result.json")" ] ||
        fail 'initial/update extension ID가 변경되었습니다.' 3
    [ "$(jq -er '.packageName' "${RUN_DIR}/initial-result.json")" = \
        "$(jq -er '.packageName' "${RUN_DIR}/update-result.json")" ] ||
        fail 'initial/update package가 변경되었습니다.' 3
    [ "$(jq -er '.signerSha256' "${RUN_DIR}/initial-result.json")" = \
        "$(jq -er '.signerSha256' "${RUN_DIR}/update-result.json")" ] ||
        fail 'initial/update signer가 변경되었습니다.' 3
    [ "$(jq -er '.versionCode' "${RUN_DIR}/update-result.json")" -gt \
        "$(jq -er '.versionCode' "${RUN_DIR}/initial-result.json")" ] ||
        fail 'update versionCode가 증가하지 않았습니다.' 3
    for phase in initial update; do
        verify_bundle "$phase"
    done

    adb -s "$SERIAL" shell am start -a android.intent.action.VIEW \
        -d internet://extension -p com.sec.android.app.sbrowser \
        >"${RUN_DIR}/launch-samsung-addons.txt" 2>&1 ||
        fail '삼성 인터넷 Add-ons 실행에 실패했습니다.' 3
    printf 'evidence=%s\n' "$RUN_DIR"
    printf 'Host-side checks passed.\n'
}

main
