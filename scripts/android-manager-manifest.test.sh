#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE_DIR="${ROOT_DIR}/android-manager"
readonly BUILD_FILE="${MODULE_DIR}/build.gradle.kts"
readonly SOURCE_MANIFEST="${MODULE_DIR}/src/main/AndroidManifest.xml"
readonly RES_DIR="${MODULE_DIR}/src/main/res"
readonly ICON_BACKGROUND="${RES_DIR}/drawable/ic_launcher_background.xml"
readonly ICON_FOREGROUND="${RES_DIR}/drawable/ic_launcher_foreground.xml"
readonly ICON_MONOCHROME="${RES_DIR}/drawable/ic_launcher_monochrome.xml"
readonly ICON_V26="${RES_DIR}/mipmap-anydpi-v26/ic_launcher.xml"
readonly ICON_ROUND_V26="${RES_DIR}/mipmap-anydpi-v26/ic_launcher_round.xml"
readonly ICON_V33="${RES_DIR}/mipmap-anydpi-v33/ic_launcher.xml"
readonly ICON_ROUND_V33="${RES_DIR}/mipmap-anydpi-v33/ic_launcher_round.xml"
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
    local permission
    local -a required_permissions=(
        "android.permission.REQUEST_INSTALL_PACKAGES"
        "android.permission.REQUEST_DELETE_PACKAGES"
    )
    local -a forbidden_permissions=(
        "android.permission.DELETE_PACKAGES"
        "android.permission.INTERNET"
        "android.permission.QUERY_ALL_PACKAGES"
    )

    for permission in "${required_permissions[@]}"; do
        assert_xpath_count "${manifest}" \
            "${permission_elements}[${attr_name}='${permission}']" 1 \
            "${permission} must be declared once across uses-permission elements"
    done

    for permission in "${forbidden_permissions[@]}"; do
        assert_xpath_count "${manifest}" \
            "${permission_elements}[${attr_name}='${permission}']" 0 \
            "${permission} must not be declared in uses-permission elements"
    done
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
    local permission
    local permission_count
    local -a required_permissions=(
        "android.permission.REQUEST_INSTALL_PACKAGES"
        "android.permission.REQUEST_DELETE_PACKAGES"
    )
    local -a forbidden_permissions=(
        "android.permission.DELETE_PACKAGES"
        "android.permission.INTERNET"
        "android.permission.QUERY_ALL_PACKAGES"
    )

    for permission in "${required_permissions[@]}"; do
        permission_count="$(apk_permission_count "${xmltree}" "${permission}")"
        [[ "${permission_count}" == "1" ]] ||
            fail "APK ${permission} must be declared once across uses-permission elements (expected 1, got ${permission_count})"
    done

    for permission in "${forbidden_permissions[@]}"; do
        permission_count="$(apk_permission_count "${xmltree}" "${permission}")"
        [[ "${permission_count}" == "0" ]] ||
            fail "APK ${permission} must not be declared in uses-permission elements (expected 0, got ${permission_count})"
    done
}

manifest_contract() {
    local manifest="$1"
    local package_check="$2"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local attr_exported="@*[local-name()='exported' and namespace-uri()='${ANDROID_NS}']"
    local attr_icon="@*[local-name()='icon' and namespace-uri()='${ANDROID_NS}']"
    local attr_round_icon="@*[local-name()='roundIcon' and namespace-uri()='${ANDROID_NS}']"
    local launcher_activity="/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='activity'][*[local-name()='intent-filter']/*[local-name()='action'][${attr_name}='android.intent.action.MAIN'] and *[local-name()='intent-filter']/*[local-name()='category'][${attr_name}='android.intent.category.LAUNCHER']]"

    if [[ "${package_check}" == "true" ]]; then
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest'][@package='dev.sixbridge.manager']" 1 \
            "built APK package must be dev.sixbridge.manager"
    fi

    if [[ "${package_check}" == "false" ]]; then
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest']/*[local-name()='application'][${attr_icon}='@mipmap/ic_launcher']" 1 \
            "application icon must reference @mipmap/ic_launcher"
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest']/*[local-name()='application'][${attr_round_icon}='@mipmap/ic_launcher_round']" 1 \
            "application roundIcon must reference @mipmap/ic_launcher_round"
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

assert_file_exists() {
    local file="$1"
    local message="$2"

    [[ -f "${file}" ]] || fail "${message}: ${file}"
}

assert_adaptive_icon_source() {
    local file="$1"
    local monochrome_count="$2"
    local attr_drawable="@*[local-name()='drawable' and namespace-uri()='${ANDROID_NS}']"

    assert_xpath_count "${file}" "/*[local-name()='adaptive-icon']" 1 \
        "adaptive icon root must exist"
    assert_xpath_count "${file}" \
        "/*[local-name()='adaptive-icon']/*[local-name()='background'][${attr_drawable}='@drawable/ic_launcher_background']" 1 \
        "adaptive icon background must reference the launcher background"
    assert_xpath_count "${file}" \
        "/*[local-name()='adaptive-icon']/*[local-name()='foreground'][${attr_drawable}='@drawable/ic_launcher_foreground']" 1 \
        "adaptive icon foreground must reference the launcher foreground"
    assert_xpath_count "${file}" \
        "/*[local-name()='adaptive-icon']/*[local-name()='monochrome']" \
        "${monochrome_count}" \
        "adaptive icon monochrome layer count is incorrect"
    if [[ "${monochrome_count}" == "1" ]]; then
        assert_xpath_count "${file}" \
            "/*[local-name()='adaptive-icon']/*[local-name()='monochrome'][${attr_drawable}='@drawable/ic_launcher_monochrome']" 1 \
            "adaptive icon monochrome must reference the launcher monochrome"
    fi
}

source_icon_contract() {
    local attr_fill_type="@*[local-name()='fillType' and namespace-uri()='${ANDROID_NS}']"
    local attr_fill_color="@*[local-name()='fillColor' and namespace-uri()='${ANDROID_NS}']"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local attr_pivot_x="@*[local-name()='pivotX' and namespace-uri()='${ANDROID_NS}']"
    local attr_pivot_y="@*[local-name()='pivotY' and namespace-uri()='${ANDROID_NS}']"
    local attr_scale_x="@*[local-name()='scaleX' and namespace-uri()='${ANDROID_NS}']"
    local attr_scale_y="@*[local-name()='scaleY' and namespace-uri()='${ANDROID_NS}']"
    local attr_stroke_color="@*[local-name()='strokeColor' and namespace-uri()='${ANDROID_NS}']"
    local attr_translate_x="@*[local-name()='translateX' and namespace-uri()='${ANDROID_NS}']"
    local attr_translate_y="@*[local-name()='translateY' and namespace-uri()='${ANDROID_NS}']"
    local attr_viewport_height="@*[local-name()='viewportHeight' and namespace-uri()='${ANDROID_NS}']"
    local attr_viewport_width="@*[local-name()='viewportWidth' and namespace-uri()='${ANDROID_NS}']"
    local file

    for file in \
        "${ICON_BACKGROUND}" \
        "${ICON_FOREGROUND}" \
        "${ICON_MONOCHROME}" \
        "${ICON_V26}" \
        "${ICON_ROUND_V26}" \
        "${ICON_V33}" \
        "${ICON_ROUND_V33}"; do
        assert_file_exists "${file}" "launcher icon source is missing"
    done

    for file in \
        "${ICON_BACKGROUND}" \
        "${ICON_FOREGROUND}" \
        "${ICON_MONOCHROME}"; do
        assert_xpath_count "${file}" \
            "/*[local-name()='vector'][${attr_viewport_width}='108' and ${attr_viewport_height}='108']" 1 \
            "launcher vector viewport must be 108 by 108"
    done

    grep -Fq '#6366F1' "${ICON_BACKGROUND}" ||
        fail "launcher background is missing Indigo #6366F1"
    grep -Fq '#6D28D9' "${ICON_BACKGROUND}" ||
        fail "launcher background is missing Violet #6D28D9"
    grep -Fq '#FFFFFF' "${ICON_FOREGROUND}" ||
        fail "launcher foreground is missing White #FFFFFF"
    grep -Fq '#5B21B6' "${ICON_FOREGROUND}" ||
        fail "launcher foreground is missing Deep Violet #5B21B6"
    grep -Fq '#FB7185' "${ICON_FOREGROUND}" ||
        fail "launcher foreground is missing Coral #FB7185"
    grep -Fq '#2E1065' "${ICON_FOREGROUND}" ||
        fail "launcher foreground is missing Dark Plum #2E1065 material depth"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "/*[local-name()='vector']/*[local-name()='group'][${attr_name}='launcher_mark' and ${attr_pivot_x}='52.5' and ${attr_pivot_y}='51' and ${attr_scale_x}='0.75' and ${attr_scale_y}='0.75' and ${attr_translate_x}='3' and ${attr_translate_y}='3']" 1 \
        "launcher foreground mark transform is incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "/*[local-name()='vector']/*[local-name()='group']/*[local-name()='group'][${attr_name}='material_depth' and ${attr_translate_x}='4' and ${attr_translate_y}='4']" 1 \
        "launcher foreground material depth transform is incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "/*[local-name()='vector']/*[local-name()='group']/*[1][self::*[local-name()='group'] and ${attr_name}='material_depth']" 1 \
        "launcher foreground material depth must precede the main mark"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "/*[local-name()='vector']/*[local-name()='group']/*[local-name()='group'][${attr_name}='material_depth']/*[local-name()='path'][${attr_fill_color}='#2E1065' or ${attr_stroke_color}='#2E1065']" 3 \
        "launcher foreground material depth must contain the connected puzzle, tracks, and controls"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "/*[local-name()='vector']/*[local-name()='group'][${attr_name}='launcher_mark' and ${attr_pivot_x}='52.5' and ${attr_pivot_y}='51' and ${attr_scale_x}='0.75' and ${attr_scale_y}='0.75' and ${attr_translate_x}='3' and ${attr_translate_y}='3']" 1 \
        "launcher monochrome mark transform is incorrect"
    grep -Fq 'M23,40Q23,35 28,35' "${ICON_FOREGROUND}" ||
        fail "launcher foreground is missing the approved 5dp rounded puzzle body"
    grep -Fq 'M23,40Q23,35 28,35' "${ICON_MONOCHROME}" ||
        fail "launcher monochrome is missing the approved 5dp rounded puzzle body"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "/*[local-name()='vector']/*[local-name()='group']/*[local-name()='path'][${attr_fill_type}='evenOdd']" 1 \
        "monochrome launcher must contain one evenOdd alpha cutout path"
    if grep -Eq '#6366F1|#6D28D9|#5B21B6|#FB7185|#2E1065' \
        "${ICON_MONOCHROME}"; then
        fail "monochrome launcher must not contain brand colors"
    fi

    assert_adaptive_icon_source "${ICON_V26}" 0
    assert_adaptive_icon_source "${ICON_ROUND_V26}" 0
    assert_adaptive_icon_source "${ICON_V33}" 1
    assert_adaptive_icon_source "${ICON_ROUND_V33}" 1
}

apk_icon_contract() {
    local apk="$1"
    local inspection_dir="$2"
    local entries_file="${inspection_dir}/apk-entries.txt"
    local entry
    local icon_tree
    local -a required_entries=(
        "res/mipmap-anydpi-v26/ic_launcher.xml"
        "res/mipmap-anydpi-v26/ic_launcher_round.xml"
        "res/mipmap-anydpi-v33/ic_launcher.xml"
        "res/mipmap-anydpi-v33/ic_launcher_round.xml"
    )

    unzip -Z1 "${apk}" >"${entries_file}"
    for entry in "${required_entries[@]}"; do
        grep -Fxq "${entry}" "${entries_file}" ||
            fail "built APK launcher resource is missing: ${entry}"
    done

    for entry in \
        "res/mipmap-anydpi-v26/ic_launcher.xml" \
        "res/mipmap-anydpi-v26/ic_launcher_round.xml"; do
        icon_tree="${inspection_dir}/$(basename "${entry}").v26.xmltree"
        "${AAPT2}" dump xmltree "${apk}" --file "${entry}" >"${icon_tree}"
        if grep -Fq 'E: monochrome' "${icon_tree}"; then
            fail "API 26 launcher resource must not contain monochrome"
        fi
    done

    for entry in \
        "res/mipmap-anydpi-v33/ic_launcher.xml" \
        "res/mipmap-anydpi-v33/ic_launcher_round.xml"; do
        icon_tree="${inspection_dir}/$(basename "${entry}").v33.xmltree"
        "${AAPT2}" dump xmltree "${apk}" --file "${entry}" >"${icon_tree}"
        grep -Fq 'E: monochrome' "${icon_tree}" ||
            fail "API 33 launcher resource must contain monochrome: ${entry}"
    done
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
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_DELETE_PACKAGES" />' \
        '</manifest>' >"${fixture_dir}/source-allowed.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_DELETE_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.DELETE_PACKAGES" />' \
        '</manifest>' >"${fixture_dir}/source-forbidden.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '</manifest>' \
        >"${fixture_dir}/source-missing.xml"

    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=4)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_DELETE_PACKAGES" (Raw: "android.permission.REQUEST_DELETE_PACKAGES")' \
        >"${fixture_dir}/apk-allowed.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=5)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_DELETE_PACKAGES" (Raw: "android.permission.REQUEST_DELETE_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=6)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.DELETE_PACKAGES" (Raw: "android.permission.DELETE_PACKAGES")' \
        >"${fixture_dir}/apk-forbidden.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
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
source_icon_contract
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
apk_icon_contract "${DEBUG_APK}" "${inspection_dir}"

apk_permission_contract "${apk_xmltree}"
manifest_contract "${rendered_manifest}" true

printf 'PASS: Android manager source and APK manifest contracts\n'
