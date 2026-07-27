#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DEFAULT_TEMPLATE="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.apk"
readonly HASH_FILE="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.sha256"
readonly GENERATOR="${ROOT_DIR}/scripts/generate-android-wrapper-template.sh"
readonly PACKAGE_SCRIPT="${ROOT_DIR}/scripts/package-manager-debug.sh"
readonly PROVIDER_SOURCE="${ROOT_DIR}/android-wrapper-template/src/main/java/dev/sixbridge/runtime/ExtensionProvider.java"
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

assert_component_block() {
    local xmltree="$1"
    local element="$2"
    local component_name="$3"
    local output="$4"

    awk -v element="$element" -v component_name="$component_name" '
        function indentation(line, first) {
            first = match(line, /[^[:space:]]/)
            return first == 0 ? length(line) : first - 1
        }
        function emit_if_requested() {
            if (capturing && !emitted && index(block, "\"" component_name "\"")) {
                printf "%s", block
                emitted = 1
            }
        }
        /^[[:space:]]*E: / {
            current_indentation = indentation($0)
            if (capturing && current_indentation <= component_indentation) {
                emit_if_requested()
                capturing = 0
                block = ""
            }
            if ($0 ~ "E: " element " ") {
                capturing = 1
                component_indentation = current_indentation
                block = $0 "\n"
                next
            }
        }
        capturing {
            block = block $0 "\n"
        }
        END {
            emit_if_requested()
        }
    ' "$xmltree" >"$output"

    [[ -s "$output" ]] || fail "${element} component를 찾을 수 없습니다: ${component_name}"
}

assert_component_attribute() {
    local block="$1"
    local attribute="$2"
    local expected="$3"
    local message="$4"

    component_attribute_matches "$block" "$attribute" "$expected" ||
        fail "$message"
}

component_attribute_matches() {
    local block="$1"
    local attribute="$2"
    local expected="$3"

    grep -Eq ":${attribute}\\([^)]*\\)=${expected}([[:space:]]|$)" "$block"
}

assert_template_component_contract() {
    local xmltree="$1"
    local temp_dir="$2"
    local activity_block="${temp_dir}/activity.txt"
    local provider_block="${temp_dir}/provider.txt"
    local exported_mutation="${temp_dir}/activity-exported-false.txt"

    assert_component_block "$xmltree" activity \
        "dev.sixbridge.runtime.SixDiscoveryActivity" "$activity_block"
    assert_component_attribute "$activity_block" exported true \
        "discovery Activity는 exported=true여야 합니다."
    assert_component_attribute "$activity_block" excludeFromRecents true \
        "discovery Activity는 excludeFromRecents=true여야 합니다."
    assert_component_attribute "$activity_block" theme '@0x01030055' \
        "discovery Activity theme은 Theme.NoDisplay여야 합니다."
    assert_component_block "$xmltree" provider \
        "dev.sixbridge.runtime.ExtensionProvider" "$provider_block"
    assert_component_attribute "$provider_block" exported true \
        "ExtensionProvider는 exported=true여야 합니다."
    assert_component_attribute "$provider_block" enabled true \
        "ExtensionProvider는 enabled=true여야 합니다."
    assert_component_attribute "$provider_block" grantUriPermissions false \
        "ExtensionProvider는 grantUriPermissions=false여야 합니다."

    awk '
        !changed && /:exported\([^)]*\)=true$/ {
            sub(/=true$/, "=false")
            changed = 1
        }
        { print }
    ' "$activity_block" >"$exported_mutation"
    if ! component_attribute_matches "$exported_mutation" exported false; then
        fail "exported=false 변이를 만들지 못했습니다."
    fi
    if component_attribute_matches "$exported_mutation" exported true; then
        fail "exported=false Activity 변이가 허용되었습니다."
    fi
}

assert_atomic_provider_source() {
    [[ -f "$PROVIDER_SOURCE" ]] || fail "ExtensionProvider source를 찾을 수 없습니다: $PROVIDER_SOURCE"
    assert_contains "$PROVIDER_SOURCE" \
        'File.createTempFile("extension.crx.", ".tmp", target.getParentFile())' \
        "ExtensionProvider는 고유한 같은 디렉터리 temporary file을 만들어야 합니다."
    assert_contains "$PROVIDER_SOURCE" 'Files.move(' \
        "ExtensionProvider는 Files.move로 CRX를 게시해야 합니다."
    assert_contains "$PROVIDER_SOURCE" 'StandardCopyOption.ATOMIC_MOVE' \
        "ExtensionProvider는 ATOMIC_MOVE를 사용해야 합니다."
    assert_contains "$PROVIDER_SOURCE" 'StandardCopyOption.REPLACE_EXISTING' \
        "ExtensionProvider는 REPLACE_EXISTING을 사용해야 합니다."
    assert_contains "$PROVIDER_SOURCE" 'Files.deleteIfExists(temporary.toPath())' \
        "ExtensionProvider는 finally에서 temporary file을 정리해야 합니다."
    if grep -Fq 'target.delete()' "$PROVIDER_SOURCE"; then
        fail "ExtensionProvider는 기존 CRX를 먼저 삭제하면 안 됩니다."
    fi
}

assert_package_script_contract() {
    [[ -f "$PACKAGE_SCRIPT" ]] || fail "패키징 스크립트를 찾을 수 없습니다: $PACKAGE_SCRIPT"
    assert_contains "$PACKAGE_SCRIPT" \
        '[[ -x "${TEMPLATE_TEST_SCRIPT}" ]] ||' \
        "패키징은 template 계약 스크립트 누락을 fail-closed로 처리해야 합니다."
    assert_contains "$PACKAGE_SCRIPT" '"${TEMPLATE_TEST_SCRIPT}"' \
        "패키징은 template 계약 스크립트를 무조건 실행해야 합니다."
}

assert_package_script_fail_closed() {
    local temp_dir="$1"
    local fixture_dir="${temp_dir}/package-fixture"
    local output_file="${temp_dir}/package-fixture-output.txt"

    mkdir -p "${fixture_dir}/scripts"
    cp "$PACKAGE_SCRIPT" "${fixture_dir}/scripts/package-manager-debug.sh"
    printf '#!/bin/sh\nexit 0\n' >"${fixture_dir}/scripts/verify.sh"
    chmod +x \
        "${fixture_dir}/scripts/package-manager-debug.sh" \
        "${fixture_dir}/scripts/verify.sh"
    if bash "${fixture_dir}/scripts/package-manager-debug.sh" >"$output_file" 2>&1; then
        fail "template 계약 스크립트가 없는 패키징 fixture가 성공했습니다."
    fi
    assert_contains "$output_file" "템플릿 계약 스크립트를 실행할 수 없습니다" \
        "패키징 fixture가 template 계약 스크립트 누락을 fail-closed로 처리하지 않았습니다."
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
    assert_atomic_provider_source
    assert_package_script_contract

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

    assert_package_script_fail_closed "$temp_dir"

    "$AAPT2" dump badging "$template" >"$badging"
    "$AAPT2" dump xmltree "$template" --file AndroidManifest.xml >"$xmltree"
    zipinfo -1 "$template" >"$entries"
    zipinfo -v "$template" resources.arsc >"$resources_info"

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
    assert_template_component_contract "$xmltree" "$temp_dir"
    assert_unsigned "$template" "$entries"
    assert_runtime_entries "$entries"
    unzip -p "$template" assets/extension.crx >"$placeholder_crx"
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
