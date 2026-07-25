#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE_DIR="${ROOT_DIR}/android-manager"
readonly BUILD_FILE="${MODULE_DIR}/build.gradle.kts"
readonly SOURCE_MANIFEST="${MODULE_DIR}/src/main/AndroidManifest.xml"
readonly DEBUG_APK="${MODULE_DIR}/build/outputs/apk/debug/android-manager-debug.apk"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly APKANALYZER="${SDK_ROOT}/cmdline-tools/latest/bin/apkanalyzer"
readonly AAPT2="${SDK_ROOT}/build-tools/36.0.0/aapt2"
readonly ANDROID_NS="http://schemas.android.com/apk/res/android"
readonly FALLBACK_JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_xpath_count() {
    local xml_file="$1"
    local xpath="$2"
    local expected="$3"
    local message="$4"
    local actual

    actual="$(xmllint --xpath "count(${xpath})" "${xml_file}")"
    [[ "${actual}" == "${expected}" ]] ||
        fail "${message} (expected ${expected}, got ${actual})"
}

source_permission_contract() {
    local manifest="$1"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local permission_elements="/*[local-name()='manifest']/*[starts-with(local-name(), 'uses-permission')]"

    assert_xpath_count "${manifest}" \
        "${permission_elements}[${attr_name}='android.permission.REQUEST_INSTALL_PACKAGES']" 1 \
        "REQUEST_INSTALL_PACKAGES must be declared once across uses-permission elements"
    assert_xpath_count "${manifest}" \
        "${permission_elements}[${attr_name}='android.permission.INTERNET' or ${attr_name}='android.permission.QUERY_ALL_PACKAGES']" 0 \
        "INTERNET and QUERY_ALL_PACKAGES must not be declared in uses-permission elements"
}

apk_permission_names() {
    local xmltree="$1"

    awk '
        /^[[:space:]]*E: uses-permission[^[:space:]]*[[:space:]]/ {
            in_permission = 1
            next
        }
        /^[[:space:]]*E: / {
            in_permission = 0
        }
        in_permission && /:name(\([^)]*\))?="/ {
            name = $0
            sub(/^.*:name(\([^)]*\))?="/, "", name)
            sub(/".*$/, "", name)
            print name
            in_permission = 0
        }
    ' "${xmltree}"
}

apk_permission_count() {
    local xmltree="$1"
    local permission="$2"

    apk_permission_names "${xmltree}" |
        awk -v permission="${permission}" '
            $0 == permission {
                count++
            }
            END {
                print count + 0
            }
        '
}

apk_permission_contract() {
    local xmltree="$1"
    local required_count
    local forbidden_count

    required_count="$(apk_permission_count \
        "${xmltree}" "android.permission.REQUEST_INSTALL_PACKAGES")"
    [[ "${required_count}" == "1" ]] ||
        fail "APK REQUEST_INSTALL_PACKAGES must be declared once across uses-permission elements (expected 1, got ${required_count})"

    forbidden_count="$(
        apk_permission_names "${xmltree}" |
            awk '
                $0 == "android.permission.INTERNET" ||
                    $0 == "android.permission.QUERY_ALL_PACKAGES" {
                    count++
                }
                END {
                    print count + 0
                }
            '
    )"
    [[ "${forbidden_count}" == "0" ]] ||
        fail "APK INTERNET and QUERY_ALL_PACKAGES must not be declared in uses-permission elements (expected 0, got ${forbidden_count})"
}

manifest_contract() {
    local manifest="$1"
    local package_check="$2"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local attr_exported="@*[local-name()='exported' and namespace-uri()='${ANDROID_NS}']"
    local launcher_activity="/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='activity'][*[local-name()='intent-filter']/*[local-name()='action'][${attr_name}='android.intent.action.MAIN'] and *[local-name()='intent-filter']/*[local-name()='category'][${attr_name}='android.intent.category.LAUNCHER']]"

    if [[ "${package_check}" == "true" ]]; then
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest'][@package='dev.sixbridge.manager']" 1 \
            "built APK package must be dev.sixbridge.manager"
    fi

    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='queries']/*[local-name()='intent'][*[local-name()='action'][${attr_name}='android.intent.action.VIEW'] and *[local-name()='category'][${attr_name}='android.intent.category.BROWSABLE'] and *[local-name()='data'][@*[local-name()='scheme' and namespace-uri()='${ANDROID_NS}']='six' and @*[local-name()='host' and namespace-uri()='${ANDROID_NS}']='ex']]" 1 \
        "six://ex VIEW/BROWSABLE activity query must be declared once"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='queries']/*[local-name()='package'][${attr_name}='com.sec.android.app.sbrowser']" 1 \
        "Samsung Internet package query must be declared once"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='queries']/*[local-name()='intent'][*[local-name()='action'][${attr_name}='android.intent.action.VIEW'] and *[local-name()='data'][@*[local-name()='scheme' and namespace-uri()='${ANDROID_NS}']='internet' and @*[local-name()='host' and namespace-uri()='${ANDROID_NS}']='extension']]" 1 \
        "internet://extension VIEW intent query must be declared once"
    assert_xpath_count "${manifest}" "${launcher_activity}[${attr_exported}='true']" 1 \
        "exactly one exported launcher activity must exist"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='application']/*[self::*[local-name()='activity'] or self::*[local-name()='activity-alias'] or self::*[local-name()='service'] or self::*[local-name()='receiver'] or self::*[local-name()='provider']][${attr_exported}='true' and not(self::*[local-name()='activity'] and *[local-name()='intent-filter']/*[local-name()='action'][${attr_name}='android.intent.action.MAIN'] and *[local-name()='intent-filter']/*[local-name()='category'][${attr_name}='android.intent.category.LAUNCHER'])]" 0 \
        "only the launcher activity may be exported"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='receiver'][not(${attr_exported}) or ${attr_exported}!='false']" 0 \
        "every receiver must explicitly set android:exported=false"
}

run_permission_self_test() {
    local cleanup_command
    local fixture_dir
    local output_file

    fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/six-bridge-permission-test.XXXXXX")"
    output_file="${fixture_dir}/command-output.txt"
    printf -v cleanup_command 'rm -rf -- %q' "${fixture_dir}"
    trap "${cleanup_command}" EXIT

    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '</manifest>' >"${fixture_dir}/source-allowed.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.INTERNET" />' \
        '</manifest>' >"${fixture_dir}/source-forbidden.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />' \
        >"${fixture_dir}/source-missing.xml"

    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        >"${fixture_dir}/apk-allowed.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=5)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.QUERY_ALL_PACKAGES" (Raw: "android.permission.QUERY_ALL_PACKAGES")' \
        >"${fixture_dir}/apk-forbidden.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        >"${fixture_dir}/apk-missing.xmltree"

    if ! bash "${BASH_SOURCE[0]}" --check-source-permissions \
        "${fixture_dir}/source-allowed.xml" >"${output_file}" 2>&1; then
        fail "self-test: source uses-permission-sdk-23 required permission was rejected"
    fi
    if bash "${BASH_SOURCE[0]}" --check-source-permissions \
        "${fixture_dir}/source-forbidden.xml" >"${output_file}" 2>&1; then
        fail "self-test: source uses-permission-sdk-23 forbidden permission was accepted"
    fi
    if bash "${BASH_SOURCE[0]}" --check-source-permissions \
        "${fixture_dir}/source-missing.xml" >"${output_file}" 2>&1; then
        fail "self-test: source missing required permission was accepted"
    fi
    if ! bash "${BASH_SOURCE[0]}" --check-apk-permissions \
        "${fixture_dir}/apk-allowed.xmltree" >"${output_file}" 2>&1; then
        fail "self-test: APK uses-permission-sdk-23 required permission was rejected"
    fi
    if bash "${BASH_SOURCE[0]}" --check-apk-permissions \
        "${fixture_dir}/apk-forbidden.xmltree" >"${output_file}" 2>&1; then
        fail "self-test: APK uses-permission-sdk-23 forbidden permission was accepted"
    fi
    if bash "${BASH_SOURCE[0]}" --check-apk-permissions \
        "${fixture_dir}/apk-missing.xmltree" >"${output_file}" 2>&1; then
        fail "self-test: APK missing required permission was accepted"
    fi

    printf 'PASS: permission variant self-test\n'
}

if [[ "${1:-}" == "--self-test" ]]; then
    run_permission_self_test
    exit 0
fi

if [[ "${1:-}" == "--check-source-permissions" ]]; then
    [[ "$#" == "2" ]] ||
        fail "usage: $0 --check-source-permissions <source-manifest>"
    [[ -f "$2" ]] || fail "source permission fixture is missing: $2"
    source_permission_contract "$2"
    exit 0
fi

if [[ "${1:-}" == "--check-apk-permissions" ]]; then
    [[ "$#" == "2" ]] ||
        fail "usage: $0 --check-apk-permissions <aapt2-xmltree>"
    [[ -f "$2" ]] || fail "APK permission fixture is missing: $2"
    apk_permission_contract "$2"
    exit 0
fi

[[ "$#" == "0" ]] || fail "unknown argument: $1"
[[ -f "${BUILD_FILE}" ]] || fail "Android manager build file is missing: ${BUILD_FILE}"
[[ -f "${SOURCE_MANIFEST}" ]] || fail "source manifest is missing: ${SOURCE_MANIFEST}"
[[ -x "${APKANALYZER}" ]] || fail "apkanalyzer is missing: ${APKANALYZER}"
[[ -x "${AAPT2}" ]] || fail "aapt2 is missing: ${AAPT2}"
[[ -f "${DEBUG_APK}" ]] || fail "debug APK is missing: ${DEBUG_APK}"

grep -Eq 'namespace[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "namespace must be dev.sixbridge.manager"
grep -Eq 'applicationId[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "applicationId must be dev.sixbridge.manager"

source_permission_contract "${SOURCE_MANIFEST}"
manifest_contract "${SOURCE_MANIFEST}" false

java_runtime_home="${JAVA_HOME:-}"
if [[ ! -x "${java_runtime_home}/bin/java" ]]; then
    java_runtime_home="${FALLBACK_JAVA_HOME}"
fi
[[ -x "${java_runtime_home}/bin/java" ]] ||
    fail "Java runtime is missing: ${java_runtime_home}/bin/java"

inspection_dir="$(mktemp -d "${TMPDIR:-/tmp}/six-bridge-manager-manifest.XXXXXX")"
rendered_manifest="${inspection_dir}/AndroidManifest.xml"
apk_xmltree="${inspection_dir}/AndroidManifest.xmltree"
trap 'rm -rf "${inspection_dir}"' EXIT
JAVA_HOME="${java_runtime_home}" \
    "${APKANALYZER}" manifest print "${DEBUG_APK}" >"${rendered_manifest}"
"${AAPT2}" dump xmltree "${DEBUG_APK}" --file AndroidManifest.xml >"${apk_xmltree}"

apk_permission_contract "${apk_xmltree}"
manifest_contract "${rendered_manifest}" true

printf 'PASS: Android manager source and APK manifest contracts\n'
