#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE_DIR="${ROOT_DIR}/android-manager"
readonly BUILD_FILE="${MODULE_DIR}/build.gradle.kts"
readonly SOURCE_MANIFEST="${MODULE_DIR}/src/main/AndroidManifest.xml"
readonly DEBUG_APK="${MODULE_DIR}/build/outputs/apk/debug/android-manager-debug.apk"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly APKANALYZER="${SDK_ROOT}/cmdline-tools/latest/bin/apkanalyzer"
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
        "/*[local-name()='manifest']/*[local-name()='uses-permission'][${attr_name}='android.permission.REQUEST_INSTALL_PACKAGES']" 1 \
        "REQUEST_INSTALL_PACKAGES must be declared once"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='uses-permission'][${attr_name}='android.permission.INTERNET' or ${attr_name}='android.permission.QUERY_ALL_PACKAGES']" 0 \
        "INTERNET and QUERY_ALL_PACKAGES must not be declared"
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

[[ -f "${BUILD_FILE}" ]] || fail "Android manager build file is missing: ${BUILD_FILE}"
[[ -f "${SOURCE_MANIFEST}" ]] || fail "source manifest is missing: ${SOURCE_MANIFEST}"
[[ -x "${APKANALYZER}" ]] || fail "apkanalyzer is missing: ${APKANALYZER}"
[[ -f "${DEBUG_APK}" ]] || fail "debug APK is missing: ${DEBUG_APK}"

grep -Eq 'namespace[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "namespace must be dev.sixbridge.manager"
grep -Eq 'applicationId[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "applicationId must be dev.sixbridge.manager"

manifest_contract "${SOURCE_MANIFEST}" false

java_runtime_home="${JAVA_HOME:-}"
if [[ ! -x "${java_runtime_home}/bin/java" ]]; then
    java_runtime_home="${FALLBACK_JAVA_HOME}"
fi
[[ -x "${java_runtime_home}/bin/java" ]] ||
    fail "Java runtime is missing: ${java_runtime_home}/bin/java"

rendered_manifest="$(mktemp "${TMPDIR:-/tmp}/six-bridge-manager-manifest.XXXXXX")"
trap 'rm -f "${rendered_manifest}"' EXIT
JAVA_HOME="${java_runtime_home}" \
    "${APKANALYZER}" manifest print "${DEBUG_APK}" >"${rendered_manifest}"
manifest_contract "${rendered_manifest}" true

printf 'PASS: Android manager source and APK manifest contracts\n'
