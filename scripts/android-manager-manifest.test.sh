#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly MODULE_DIR="${ROOT_DIR}/android-manager"
readonly BUILD_FILE="${MODULE_DIR}/build.gradle.kts"
readonly SOURCE_MANIFEST="${MODULE_DIR}/src/main/AndroidManifest.xml"
readonly RES_DIR="${MODULE_DIR}/src/main/res"
readonly GENERATED_PATHS="${RES_DIR}/xml/generated_bundle_paths.xml"
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
readonly AAPT_NS="http://schemas.android.com/aapt"
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
    local allowed_permissions="${attr_name}='android.permission.INTERNET' or ${attr_name}='android.permission.REQUEST_INSTALL_PACKAGES' or ${attr_name}='android.permission.REQUEST_DELETE_PACKAGES' or ${attr_name}='android.permission.POST_NOTIFICATIONS'"
    local -a required_permissions=(
        "android.permission.INTERNET"
        "android.permission.REQUEST_INSTALL_PACKAGES"
        "android.permission.REQUEST_DELETE_PACKAGES"
        "android.permission.POST_NOTIFICATIONS"
    )
    local -a forbidden_permissions=(
        "android.permission.DELETE_PACKAGES"
        "android.permission.QUERY_ALL_PACKAGES"
        "android.permission.READ_EXTERNAL_STORAGE"
        "android.permission.WRITE_EXTERNAL_STORAGE"
        "android.permission.MANAGE_EXTERNAL_STORAGE"
        "android.permission.READ_MEDIA_AUDIO"
        "android.permission.READ_MEDIA_IMAGES"
        "android.permission.READ_MEDIA_VIDEO"
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        "android.permission.ACCESS_MEDIA_LOCATION"
        "android.permission.FOREGROUND_SERVICE"
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
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
    assert_xpath_count "${manifest}" \
        "${permission_elements}[not(${allowed_permissions})]" 0 \
        "source manifest must not declare permissions outside the exact allowlist"
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
        "android.permission.INTERNET"
        "android.permission.REQUEST_INSTALL_PACKAGES"
        "android.permission.REQUEST_DELETE_PACKAGES"
        "android.permission.POST_NOTIFICATIONS"
    )
    local -a forbidden_permissions=(
        "android.permission.DELETE_PACKAGES"
        "android.permission.QUERY_ALL_PACKAGES"
        "android.permission.READ_EXTERNAL_STORAGE"
        "android.permission.WRITE_EXTERNAL_STORAGE"
        "android.permission.MANAGE_EXTERNAL_STORAGE"
        "android.permission.READ_MEDIA_AUDIO"
        "android.permission.READ_MEDIA_IMAGES"
        "android.permission.READ_MEDIA_VIDEO"
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        "android.permission.ACCESS_MEDIA_LOCATION"
        "android.permission.FOREGROUND_SERVICE"
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
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
    while IFS= read -r permission; do
        case "${permission}" in
            android.permission.INTERNET|\
            android.permission.REQUEST_INSTALL_PACKAGES|\
            android.permission.REQUEST_DELETE_PACKAGES|\
            android.permission.POST_NOTIFICATIONS|\
            dev.sixbridge.manager.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)
                ;;
            *)
                fail "APK permission is outside the exact allowlist: ${permission}"
                ;;
        esac
    done < <(apk_permission_names "${xmltree}")
}

manifest_contract() {
    local manifest="$1"
    local package_check="$2"
    local expected_icon_ref="${3:-}"
    local expected_round_icon_ref="${4:-}"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local attr_exported="@*[local-name()='exported' and namespace-uri()='${ANDROID_NS}']"
    local attr_icon="@*[local-name()='icon' and namespace-uri()='${ANDROID_NS}']"
    local attr_round_icon="@*[local-name()='roundIcon' and namespace-uri()='${ANDROID_NS}']"
    local attr_authorities="@*[local-name()='authorities' and namespace-uri()='${ANDROID_NS}']"
    local attr_grant_uri_permissions="@*[local-name()='grantUriPermissions' and namespace-uri()='${ANDROID_NS}']"
    local attr_resource="@*[local-name()='resource' and namespace-uri()='${ANDROID_NS}']"
    local expected_generated_authority='${applicationId}.generated'
    local generated_paths_resource="${attr_resource}='@xml/generated_bundle_paths'"
    local launcher_activity="/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='activity'][*[local-name()='intent-filter']/*[local-name()='action'][${attr_name}='android.intent.action.MAIN'] and *[local-name()='intent-filter']/*[local-name()='category'][${attr_name}='android.intent.category.LAUNCHER']]"

    if [[ "${package_check}" == "true" ]]; then
        expected_generated_authority="dev.sixbridge.manager.generated"
        generated_paths_resource="starts-with(${attr_resource}, '@ref/')"
        [[ -n "${expected_icon_ref}" ]] ||
            fail "built APK icon resource ID is missing"
        [[ -n "${expected_round_icon_ref}" ]] ||
            fail "built APK round icon resource ID is missing"
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest'][@package='dev.sixbridge.manager']" 1 \
            "built APK package must be dev.sixbridge.manager"
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest']/*[local-name()='application'][${attr_icon}='${expected_icon_ref}']" 1 \
            "built APK application icon must reference ${expected_icon_ref}"
        assert_xpath_count "${manifest}" \
            "/*[local-name()='manifest']/*[local-name()='application'][${attr_round_icon}='${expected_round_icon_ref}']" 1 \
            "built APK application roundIcon must reference ${expected_round_icon_ref}"
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
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='provider'][${attr_name}='androidx.core.content.FileProvider' and ${attr_authorities}='${expected_generated_authority}' and ${attr_exported}='false' and ${attr_grant_uri_permissions}='true']" 1 \
        "generated bundle FileProvider contract must be declared once"
    assert_xpath_count "${manifest}" \
        "/*[local-name()='manifest']/*[local-name()='application']/*[local-name()='provider'][${attr_name}='androidx.core.content.FileProvider']/*[local-name()='meta-data'][${attr_name}='android.support.FILE_PROVIDER_PATHS' and ${generated_paths_resource}]" 1 \
        "generated bundle FileProvider paths metadata must be declared once"
}

generated_paths_contract() {
    local paths="$1"
    local attr_name="@*[local-name()='name']"
    local attr_path="@*[local-name()='path']"

    assert_xpath_count "${paths}" "/*[local-name()='paths']" 1 \
        "generated bundle paths root must exist"
    assert_xpath_count "${paths}" "/*[local-name()='paths']/*" 1 \
        "only one generated bundle path may be exposed"
    assert_xpath_count "${paths}" \
        "/*[local-name()='paths']/*[local-name()='files-path'][${attr_name}='generated-bundles' and ${attr_path}='generated-bundles/']" 1 \
        "FileProvider must expose only files/generated-bundles"
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
    local attr_end_color="@*[local-name()='endColor' and namespace-uri()='${ANDROID_NS}']"
    local attr_end_x="@*[local-name()='endX' and namespace-uri()='${ANDROID_NS}']"
    local attr_end_y="@*[local-name()='endY' and namespace-uri()='${ANDROID_NS}']"
    local attr_height="@*[local-name()='height' and namespace-uri()='${ANDROID_NS}']"
    local attr_name="@*[local-name()='name' and namespace-uri()='${ANDROID_NS}']"
    local attr_path_data="@*[local-name()='pathData' and namespace-uri()='${ANDROID_NS}']"
    local attr_pivot_x="@*[local-name()='pivotX' and namespace-uri()='${ANDROID_NS}']"
    local attr_pivot_y="@*[local-name()='pivotY' and namespace-uri()='${ANDROID_NS}']"
    local attr_scale_x="@*[local-name()='scaleX' and namespace-uri()='${ANDROID_NS}']"
    local attr_scale_y="@*[local-name()='scaleY' and namespace-uri()='${ANDROID_NS}']"
    local attr_stroke_color="@*[local-name()='strokeColor' and namespace-uri()='${ANDROID_NS}']"
    local attr_stroke_line_cap="@*[local-name()='strokeLineCap' and namespace-uri()='${ANDROID_NS}']"
    local attr_stroke_width="@*[local-name()='strokeWidth' and namespace-uri()='${ANDROID_NS}']"
    local attr_start_color="@*[local-name()='startColor' and namespace-uri()='${ANDROID_NS}']"
    local attr_start_x="@*[local-name()='startX' and namespace-uri()='${ANDROID_NS}']"
    local attr_start_y="@*[local-name()='startY' and namespace-uri()='${ANDROID_NS}']"
    local attr_translate_x="@*[local-name()='translateX' and namespace-uri()='${ANDROID_NS}']"
    local attr_translate_y="@*[local-name()='translateY' and namespace-uri()='${ANDROID_NS}']"
    local attr_type="@*[local-name()='type' and namespace-uri()='${ANDROID_NS}']"
    local attr_viewport_height="@*[local-name()='viewportHeight' and namespace-uri()='${ANDROID_NS}']"
    local attr_viewport_width="@*[local-name()='viewportWidth' and namespace-uri()='${ANDROID_NS}']"
    local attr_width="@*[local-name()='width' and namespace-uri()='${ANDROID_NS}']"
    local approved_body="M23,40Q23,35 28,35H36C34,33 33,30 33,27C33,22 37,19 42,19C47,19 51,22 51,27C51,30 50,33 48,35H58Q63,35 63,40V46C65,44 68,43 71,43C77,43 82,48 82,54C82,60 77,65 71,65C68,65 65,64 63,62V78Q63,83 58,83H28Q23,83 23,78Z"
    local approved_depth_controls="M47,39C51.418,39 55,42.582 55,47C55,51.418 51.418,55 47,55C42.582,55 39,51.418 39,47C39,42.582 42.582,39 47,39ZM64,54C68.418,54 72,57.582 72,62C72,66.418 68.418,70 64,70C59.582,70 56,66.418 56,62C56,57.582 59.582,54 64,54Z"
    local approved_main_controls="M47,40C50.866,40 54,43.134 54,47C54,50.866 50.866,54 47,54C43.134,54 40,50.866 40,47C40,43.134 43.134,40 47,40ZM64,55C67.866,55 71,58.134 71,62C71,65.866 67.866,69 64,69C60.134,69 57,65.866 57,62C57,58.134 60.134,55 64,55Z"
    local approved_monochrome_cutout="${approved_body}M35,44.5H73C74.381,44.5 75.5,45.619 75.5,47C75.5,48.381 74.381,49.5 73,49.5H35C33.619,49.5 32.5,48.381 32.5,47C32.5,45.619 33.619,44.5 35,44.5ZM35,59.5H73C74.381,59.5 75.5,60.619 75.5,62C75.5,63.381 74.381,64.5 73,64.5H35C33.619,64.5 32.5,63.381 32.5,62C32.5,60.619 33.619,59.5 35,59.5Z"
    local approved_tracks="M35,47H73M35,62H73"
    local launcher_vector="/*[local-name()='vector'][count(@*)=4 and ${attr_width}='108dp' and ${attr_height}='108dp' and ${attr_viewport_width}='108' and ${attr_viewport_height}='108']"
    local background_path="${launcher_vector}/*[1][self::*[local-name()='path'] and count(@*)=1 and ${attr_path_data}='M0,0H108V108H0Z']"
    local background_fill="${background_path}/*[1][self::*[local-name()='attr'] and namespace-uri()='${AAPT_NS}' and count(@*)=1 and @name='android:fillColor']"
    local foreground_mark="${launcher_vector}/*[1][self::*[local-name()='group'] and count(@*)=7 and ${attr_name}='launcher_mark' and ${attr_pivot_x}='52.5' and ${attr_pivot_y}='51' and ${attr_scale_x}='0.75' and ${attr_scale_y}='0.75' and ${attr_translate_x}='3' and ${attr_translate_y}='3']"
    local monochrome_mark="${launcher_vector}/*[1][self::*[local-name()='group'] and count(@*)=7 and ${attr_name}='launcher_mark' and ${attr_pivot_x}='52.5' and ${attr_pivot_y}='51' and ${attr_scale_x}='0.75' and ${attr_scale_y}='0.75' and ${attr_translate_x}='3' and ${attr_translate_y}='3']"
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

    assert_xpath_count "${ICON_BACKGROUND}" "${launcher_vector}" 1 \
        "launcher background vector rendering attributes are incorrect"
    assert_xpath_count "${ICON_BACKGROUND}" "${launcher_vector}/*" 1 \
        "launcher background vector must contain only one full-canvas path"
    assert_xpath_count "${ICON_BACKGROUND}" "${launcher_vector}//*" 3 \
        "launcher background must contain only the path, fill attribute, and gradient"
    assert_xpath_count "${ICON_BACKGROUND}" "${background_path}" 1 \
        "launcher background must use the approved full-canvas path"
    assert_xpath_count "${ICON_BACKGROUND}" "${background_path}/*" 1 \
        "launcher background path must contain only one aapt fill attribute"
    assert_xpath_count "${ICON_BACKGROUND}" "${background_fill}" 1 \
        "launcher background path must use one canonical aapt fillColor"
    assert_xpath_count "${ICON_BACKGROUND}" "${background_fill}/*" 1 \
        "launcher background fill must contain only one gradient"
    assert_xpath_count "${ICON_BACKGROUND}" \
        "${background_fill}/*[1][self::*[local-name()='gradient'] and count(@*)=7 and ${attr_type}='linear' and ${attr_start_x}='0' and ${attr_start_y}='0' and ${attr_end_x}='108' and ${attr_end_y}='108' and ${attr_start_color}='#6366F1' and ${attr_end_color}='#6D28D9' and not(*)]" 1 \
        "launcher background gradient is not canonical"
    assert_xpath_count "${ICON_FOREGROUND}" "${launcher_vector}" 1 \
        "launcher foreground vector rendering attributes are incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" "${launcher_vector}/*" 1 \
        "launcher foreground vector must contain only the launcher mark"
    assert_xpath_count "${ICON_FOREGROUND}" "${foreground_mark}" 1 \
        "launcher foreground mark transform is incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${launcher_vector}//*[local-name()='group']" 2 \
        "launcher foreground must contain only the launcher mark and material depth groups"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${launcher_vector}//*[local-name()='path']" 6 \
        "launcher foreground must contain exactly six approved paths"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*" 4 \
        "launcher foreground mark must contain exactly four ordered children"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[1][self::*[local-name()='group'] and count(@*)=3 and ${attr_name}='material_depth' and ${attr_translate_x}='4' and ${attr_translate_y}='4']" 1 \
        "launcher foreground first child must be the approved material depth group"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[1]/*" 3 \
        "launcher foreground material depth must contain exactly three ordered paths"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[1]/*[1][self::*[local-name()='path'] and count(@*)=2 and ${attr_fill_color}='#2E1065' and ${attr_path_data}='${approved_body}']" 1 \
        "launcher foreground material depth puzzle path is incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[1]/*[2][self::*[local-name()='path'] and count(@*)=5 and ${attr_fill_color}='@android:color/transparent' and ${attr_stroke_color}='#2E1065' and ${attr_stroke_width}='7' and ${attr_stroke_line_cap}='round' and ${attr_path_data}='${approved_tracks}']" 1 \
        "launcher foreground material depth tracks are incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[1]/*[3][self::*[local-name()='path'] and count(@*)=2 and ${attr_fill_color}='#2E1065' and ${attr_path_data}='${approved_depth_controls}']" 1 \
        "launcher foreground material depth controls are incorrect"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[2][self::*[local-name()='path'] and count(@*)=2 and ${attr_fill_color}='#FFFFFF' and ${attr_path_data}='${approved_body}']" 1 \
        "launcher foreground second child must be the approved rounded main puzzle"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[3][self::*[local-name()='path'] and count(@*)=5 and ${attr_fill_color}='@android:color/transparent' and ${attr_stroke_color}='#5B21B6' and ${attr_stroke_width}='5' and ${attr_stroke_line_cap}='round' and ${attr_path_data}='${approved_tracks}']" 1 \
        "launcher foreground third child must be the approved main tracks"
    assert_xpath_count "${ICON_FOREGROUND}" \
        "${foreground_mark}/*[4][self::*[local-name()='path'] and count(@*)=4 and ${attr_fill_color}='#FB7185' and ${attr_stroke_color}='#FFFFFF' and ${attr_stroke_width}='2' and ${attr_path_data}='${approved_main_controls}']" 1 \
        "launcher foreground fourth child must be the approved main controls"
    assert_xpath_count "${ICON_MONOCHROME}" "${launcher_vector}" 1 \
        "launcher monochrome vector rendering attributes are incorrect"
    assert_xpath_count "${ICON_MONOCHROME}" "${launcher_vector}/*" 1 \
        "launcher monochrome vector must contain only the launcher mark"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}" 1 \
        "launcher monochrome mark transform is incorrect"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${launcher_vector}//*[local-name()='group']" 1 \
        "launcher monochrome must contain only one launcher mark group"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${launcher_vector}//*[local-name()='path']" 2 \
        "launcher monochrome must contain exactly two approved paths"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}//*[local-name()='group']" 0 \
        "launcher monochrome mark must not contain nested groups or material depth"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}/*" 2 \
        "launcher monochrome mark must contain exactly two ordered paths"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}/*[1][self::*[local-name()='path'] and count(@*)=3 and ${attr_fill_color}='#FFFFFFFF' and ${attr_fill_type}='evenOdd' and ${attr_path_data}='${approved_monochrome_cutout}']" 1 \
        "launcher monochrome first child must be the approved rounded puzzle and track cutout"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}/*[2][self::*[local-name()='path'] and count(@*)=2 and ${attr_fill_color}='#FFFFFFFF' and ${attr_path_data}='${approved_main_controls}']" 1 \
        "launcher monochrome second child must be the approved controls"
    assert_xpath_count "${ICON_MONOCHROME}" \
        "${monochrome_mark}/*[${attr_stroke_color} or ${attr_stroke_width}]" 0 \
        "launcher monochrome paths must not use strokes"
    if grep -Eq '#6366F1|#6D28D9|#5B21B6|#FB7185|#2E1065' \
        "${ICON_MONOCHROME}"; then
        fail "monochrome launcher must not contain brand colors"
    fi

    assert_adaptive_icon_source "${ICON_V26}" 0
    assert_adaptive_icon_source "${ICON_ROUND_V26}" 0
    assert_adaptive_icon_source "${ICON_V33}" 1
    assert_adaptive_icon_source "${ICON_ROUND_V33}" 1
}

resource_id() {
    local resources_file="$1"
    local resource_name="$2"
    local id
    local id_count

    id="$(awk -v resource_name="${resource_name}" '
        $1 == "resource" && $3 == resource_name {
            print $2
        }
    ' "${resources_file}")"
    id_count="$(printf '%s\n' "${id}" | awk 'NF { count++ } END { print count + 0 }')"
    [[ "${id_count}" == "1" ]] ||
        fail "built APK resource ID must be unique: ${resource_name} (got ${id_count})"
    [[ "${id}" =~ ^0x[0-9a-fA-F]+$ ]] ||
        fail "built APK resource ID is malformed: ${resource_name}"

    printf '%s\n' "${id}"
}

assert_adaptive_icon_tree() {
    local tree_file="$1"
    local background_id="$2"
    local foreground_id="$3"
    local monochrome_id="$4"
    local monochrome_count="$5"
    local actual_elements
    local actual_refs
    local expected_elements
    local expected_refs

    if [[ "${monochrome_count}" == "0" ]]; then
        expected_elements=$'adaptive-icon\nbackground\nforeground'
        printf -v expected_refs \
            'background=@%s\nforeground=@%s' \
            "${background_id}" "${foreground_id}"
    elif [[ "${monochrome_count}" == "1" ]]; then
        expected_elements=$'adaptive-icon\nbackground\nforeground\nmonochrome'
        printf -v expected_refs \
            'background=@%s\nforeground=@%s\nmonochrome=@%s' \
            "${background_id}" "${foreground_id}" "${monochrome_id}"
    else
        fail "unsupported built adaptive icon monochrome count: ${monochrome_count}"
    fi

    actual_elements="$(awk '$1 == "E:" { print $2 }' "${tree_file}")"
    [[ "${actual_elements}" == "${expected_elements}" ]] ||
        fail "built adaptive icon child order is incorrect: ${tree_file}"

    actual_refs="$(awk '
        $1 == "E:" {
            element = $2
            next
        }
        $1 == "A:" {
            if (element == "adaptive-icon" ||
                $0 !~ /:drawable(\([^)]*\))?=@0x[0-9a-fA-F]+[[:space:]]*$/) {
                print "__invalid__=" $0
                next
            }
            value = $0
            sub(/^.*=/, "", value)
            sub(/[[:space:]].*$/, "", value)
            print element "=" value
        }
    ' "${tree_file}")"
    [[ "${actual_refs}" == "${expected_refs}" ]] ||
        fail "built adaptive icon drawable references are incorrect: ${tree_file}"
}

built_icon_files_contract() {
    local rendered_manifest="$1"
    local resources_file="$2"
    local icon_v26_tree="$3"
    local icon_round_v26_tree="$4"
    local icon_v33_tree="$5"
    local icon_round_v33_tree="$6"
    local background_id
    local foreground_id
    local icon_id
    local monochrome_id
    local round_icon_id
    local file

    for file in \
        "${rendered_manifest}" \
        "${resources_file}" \
        "${icon_v26_tree}" \
        "${icon_round_v26_tree}" \
        "${icon_v33_tree}" \
        "${icon_round_v33_tree}"; do
        assert_file_exists "${file}" "built launcher icon inspection file is missing"
    done

    icon_id="$(resource_id "${resources_file}" "mipmap/ic_launcher")"
    round_icon_id="$(resource_id "${resources_file}" "mipmap/ic_launcher_round")"
    background_id="$(resource_id "${resources_file}" "drawable/ic_launcher_background")"
    foreground_id="$(resource_id "${resources_file}" "drawable/ic_launcher_foreground")"
    monochrome_id="$(resource_id "${resources_file}" "drawable/ic_launcher_monochrome")"

    manifest_contract \
        "${rendered_manifest}" true \
        "@ref/${icon_id}" "@ref/${round_icon_id}"
    assert_adaptive_icon_tree \
        "${icon_v26_tree}" \
        "${background_id}" "${foreground_id}" "${monochrome_id}" 0
    assert_adaptive_icon_tree \
        "${icon_round_v26_tree}" \
        "${background_id}" "${foreground_id}" "${monochrome_id}" 0
    assert_adaptive_icon_tree \
        "${icon_v33_tree}" \
        "${background_id}" "${foreground_id}" "${monochrome_id}" 1
    assert_adaptive_icon_tree \
        "${icon_round_v33_tree}" \
        "${background_id}" "${foreground_id}" "${monochrome_id}" 1
}

apk_icon_contract() {
    local apk="$1"
    local inspection_dir="$2"
    local rendered_manifest="$3"
    local entries_file="${inspection_dir}/apk-entries.txt"
    local resources_file="${inspection_dir}/resources.txt"
    local icon_v26_tree="${inspection_dir}/ic_launcher.v26.xmltree"
    local icon_round_v26_tree="${inspection_dir}/ic_launcher_round.v26.xmltree"
    local icon_v33_tree="${inspection_dir}/ic_launcher.v33.xmltree"
    local icon_round_v33_tree="${inspection_dir}/ic_launcher_round.v33.xmltree"
    local entry
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

    "${AAPT2}" dump resources "${apk}" >"${resources_file}"
    "${AAPT2}" dump xmltree "${apk}" \
        --file "res/mipmap-anydpi-v26/ic_launcher.xml" >"${icon_v26_tree}"
    "${AAPT2}" dump xmltree "${apk}" \
        --file "res/mipmap-anydpi-v26/ic_launcher_round.xml" >"${icon_round_v26_tree}"
    "${AAPT2}" dump xmltree "${apk}" \
        --file "res/mipmap-anydpi-v33/ic_launcher.xml" >"${icon_v33_tree}"
    "${AAPT2}" dump xmltree "${apk}" \
        --file "res/mipmap-anydpi-v33/ic_launcher_round.xml" >"${icon_round_v33_tree}"

    built_icon_files_contract \
        "${rendered_manifest}" "${resources_file}" \
        "${icon_v26_tree}" "${icon_round_v26_tree}" \
        "${icon_v33_tree}" "${icon_round_v33_tree}"
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
        '  <uses-permission-sdk-23 android:name="android.permission.INTERNET" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_DELETE_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.POST_NOTIFICATIONS" />' \
        '</manifest>' >"${fixture_dir}/source-allowed.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.INTERNET" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_DELETE_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.POST_NOTIFICATIONS" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.DELETE_PACKAGES" />' \
        '</manifest>' >"${fixture_dir}/source-forbidden.xml"
    printf '%s\n' \
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">' \
        '  <uses-permission-sdk-23 android:name="android.permission.INTERNET" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.REQUEST_INSTALL_PACKAGES" />' \
        '  <uses-permission-sdk-23 android:name="android.permission.POST_NOTIFICATIONS" />' \
        '</manifest>' \
        >"${fixture_dir}/source-missing.xml"

    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")' \
        '    E: uses-permission-sdk-23 (line=4)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=5)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_DELETE_PACKAGES" (Raw: "android.permission.REQUEST_DELETE_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=6)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")' \
        >"${fixture_dir}/apk-allowed.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")' \
        '    E: uses-permission-sdk-23 (line=4)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=5)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_DELETE_PACKAGES" (Raw: "android.permission.REQUEST_DELETE_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=6)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")' \
        '    E: uses-permission-sdk-23 (line=7)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.DELETE_PACKAGES" (Raw: "android.permission.DELETE_PACKAGES")' \
        >"${fixture_dir}/apk-forbidden.xmltree"
    printf '%s\n' \
        'N: android=http://schemas.android.com/apk/res/android' \
        '  E: manifest (line=2)' \
        '    E: uses-permission-sdk-23 (line=3)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")' \
        '    E: uses-permission-sdk-23 (line=4)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.REQUEST_INSTALL_PACKAGES" (Raw: "android.permission.REQUEST_INSTALL_PACKAGES")' \
        '    E: uses-permission-sdk-23 (line=5)' \
        '      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")' \
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

if [[ "${1:-}" == "--check-built-icons" ]]; then
    [[ "$#" == "7" ]] ||
        fail "usage: $0 --check-built-icons <manifest> <resources> <v26> <round-v26> <v33> <round-v33>"
    built_icon_files_contract "$2" "$3" "$4" "$5" "$6" "$7"
    exit 0
fi

[[ "$#" == "0" ]] || fail "unknown argument: $1"
[[ -f "${BUILD_FILE}" ]] || fail "Android manager build file is missing: ${BUILD_FILE}"
[[ -f "${SOURCE_MANIFEST}" ]] || fail "source manifest is missing: ${SOURCE_MANIFEST}"
[[ -f "${GENERATED_PATHS}" ]] || fail "generated bundle paths are missing: ${GENERATED_PATHS}"
[[ -x "${APKANALYZER}" ]] || fail "apkanalyzer is missing: ${APKANALYZER}"
[[ -x "${AAPT2}" ]] || fail "aapt2 is missing: ${AAPT2}"
[[ -f "${DEBUG_APK}" ]] || fail "debug APK is missing: ${DEBUG_APK}"

grep -Eq 'namespace[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "namespace must be dev.sixbridge.manager"
grep -Eq 'applicationId[[:space:]]*=[[:space:]]*"dev\.sixbridge\.manager"' "${BUILD_FILE}" ||
    fail "applicationId must be dev.sixbridge.manager"

source_permission_contract "${SOURCE_MANIFEST}"
generated_paths_contract "${GENERATED_PATHS}"
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
apk_icon_contract "${DEBUG_APK}" "${inspection_dir}" "${rendered_manifest}"

apk_permission_contract "${apk_xmltree}"

printf 'PASS: Android manager source and APK manifest contracts\n'
