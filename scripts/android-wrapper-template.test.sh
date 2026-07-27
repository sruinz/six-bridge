#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DEFAULT_TEMPLATE="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.apk"
readonly HASH_FILE="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.sha256"
readonly GENERATOR="${ROOT_DIR}/scripts/generate-android-wrapper-template.sh"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly AAPT2="${SDK_ROOT}/build-tools/36.0.0/aapt2"
readonly APKSIGNER="${SDK_ROOT}/build-tools/36.0.0/apksigner"
readonly ZIPALIGN="${SDK_ROOT}/build-tools/36.0.0/zipalign"
readonly PLACEHOLDER_ID="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
readonly PACKAGE_NAME="dev.sixbridge.ext.${PLACEHOLDER_ID}"
readonly PROVIDER_AUTHORITY="${PACKAGE_NAME}.contentprovider"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

require_tool() {
    [[ -x "$1" ]] || fail "필수 Android SDK 도구를 찾을 수 없습니다: $1"
}

assert_contains() {
    local file="$1"
    local expected="$2"
    local message="$3"

    grep -Fq -- "$expected" "$file" || fail "$message"
}

assert_runtime_entries() {
    local entries_file="$1"
    local expected
    local actual

    expected=$'AndroidManifest.xml\nclasses.dex\nresources.arsc\nassets/extension.crx'
    actual="$(cat "$entries_file")"
    [[ "$actual" == "$expected" ]] ||
        fail "템플릿 runtime entry 순서 또는 구성이 올바르지 않습니다: ${actual}"
}

assert_unsigned() {
    local template="$1"
    local entries_file="$2"

    if "$APKSIGNER" verify "$template" >/dev/null 2>&1; then
        fail "템플릿은 unsigned APK여야 합니다."
    fi
    if grep -Eq '^META-INF/.*\.(RSA|DSA|EC|SF)$' "$entries_file"; then
        fail "템플릿에 서명 META-INF entry가 포함되어 있습니다."
    fi
    if grep -aFq 'APK Sig Block 42' "$template"; then
        fail "템플릿에 APK signing block이 포함되어 있습니다."
    fi
}

main() {
    local template="${SIXBRIDGE_WRAPPER_TEMPLATE_PATH:-$DEFAULT_TEMPLATE}"
    local temp_dir
    local badging
    local xmltree
    local entries
    local resources_info
    local placeholder_crx
    local actual_hash
    local pinned_hash

    [[ -d "${ROOT_DIR}/.git" ]] || fail "SIX Bridge repository root에서 실행해야 합니다."
    require_tool "$AAPT2"
    require_tool "$APKSIGNER"
    require_tool "$ZIPALIGN"
    [[ -x "$GENERATOR" ]] || fail "템플릿 생성 스크립트를 찾을 수 없습니다: $GENERATOR"
    [[ -f "$template" ]] || fail "래퍼 템플릿 APK를 찾을 수 없습니다: $template"

    if [[ "$template" == "$DEFAULT_TEMPLATE" ]]; then
        "$GENERATOR" --check
        [[ -f "$HASH_FILE" ]] || fail "템플릿 SHA-256 파일을 찾을 수 없습니다: $HASH_FILE"
        actual_hash="$(shasum -a 256 "$template" | awk '{print $1}')"
        pinned_hash="$(tr -d '\r\n' <"$HASH_FILE")"
        [[ "$pinned_hash" =~ ^[0-9a-f]{64}$ ]] ||
            fail "템플릿 SHA-256은 소문자 64자리 hex여야 합니다."
        [[ "$actual_hash" == "$pinned_hash" ]] ||
            fail "템플릿 APK와 pinned SHA-256이 일치하지 않습니다."
    fi

    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-wrapper-template.XXXXXX")"
    trap "rm -rf -- '${temp_dir}'" EXIT
    badging="${temp_dir}/badging.txt"
    xmltree="${temp_dir}/manifest.xmltree.txt"
    entries="${temp_dir}/entries.txt"
    resources_info="${temp_dir}/resources.txt"
    placeholder_crx="${temp_dir}/extension.crx"

    "$AAPT2" dump badging "$template" >"$badging"
    "$AAPT2" dump xmltree "$template" --file AndroidManifest.xml >"$xmltree"
    zipinfo -1 "$template" >"$entries"
    zipinfo -v "$template" resources.arsc >"$resources_info"
    unzip -p "$template" assets/extension.crx >"$placeholder_crx"

    assert_contains "$badging" "package: name='${PACKAGE_NAME}' versionCode='1'" \
        "템플릿 package 또는 versionCode가 올바르지 않습니다."
    assert_contains "$badging" "minSdkVersion:'30'" "템플릿 minSdkVersion이 30이 아닙니다."
    assert_contains "$badging" "targetSdkVersion:'36'" "템플릿 targetSdkVersion이 36이 아닙니다."
    assert_contains "$xmltree" "extension-id" "extension-id metadata가 없습니다."
    assert_contains "$xmltree" "\"${PLACEHOLDER_ID}\"" "extension-id metadata 값이 올바르지 않습니다."
    assert_contains "$xmltree" "\"${PROVIDER_AUTHORITY}\"" "Provider authority가 올바르지 않습니다."
    assert_contains "$xmltree" "dev.sixbridge.runtime.SixDiscoveryActivity" \
        "discovery Activity가 없습니다."
    assert_contains "$xmltree" "android.intent.action.VIEW" "VIEW Activity contract가 없습니다."
    assert_contains "$xmltree" "android.intent.category.BROWSABLE" \
        "BROWSABLE Activity contract가 없습니다."
    assert_contains "$xmltree" "\"six\"" "six://ex scheme contract가 없습니다."
    assert_contains "$xmltree" "\"ex\"" "six://ex host contract가 없습니다."
    assert_unsigned "$template" "$entries"
    assert_runtime_entries "$entries"
    printf 'SIXBRIDGE-CRX-PLACEHOLDER\n' >"${temp_dir}/expected-extension.crx"
    cmp -s "$placeholder_crx" "${temp_dir}/expected-extension.crx" ||
        fail "템플릿 extension.crx placeholder 내용이 올바르지 않습니다."
    assert_contains "$resources_info" "compression method:                             none (stored)" \
        "resources.arsc는 STORED여야 합니다."
    "$ZIPALIGN" -c -v 4 "$template" >/dev/null ||
        fail "템플릿 APK의 4바이트 정렬 검증에 실패했습니다."
    printf 'PASS: Android unsigned wrapper template 계약\n'
}

main "$@"
