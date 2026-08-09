#!/usr/bin/env bash

# Shared fail-closed verification primitives for the v0.1.0 release scripts.
# This file is sourced by prepare-release.sh and publish-release-tag.sh.

readonly SIXBRIDGE_RELEASE_VERSION='0.1.0'
readonly SIXBRIDGE_ANDROID_FILE='SIX-Bridge-Manager-0.1.0-android.apk'
readonly SIXBRIDGE_MACOS_FILE='SIX-Bridge-0.1.0-macos-unsigned.dmg'
readonly SIXBRIDGE_RELEASE_FILES=(
    "${SIXBRIDGE_ANDROID_FILE}"
    "${SIXBRIDGE_MACOS_FILE}"
    'SHA256SUMS'
    'RELEASE-METADATA.json'
    'RELEASE-NOTES-KO.md'
)
readonly SIXBRIDGE_RELEASE_TEST_MODE="${SIXBRIDGE_RELEASE_TEST_MODE:-0}"

release_fail() {
    printf 'release 검증 실패: %s\n' "$1" >&2
    return 1
}

release_require_version() {
    [[ "${1:-}" = "${SIXBRIDGE_RELEASE_VERSION}" ]] ||
        release_fail "지원하지 않는 release version입니다: ${1:-없음}"
}

release_regular_file() {
    local path=${1:-}
    [[ -n "${path}" && ! -L "${path}" && -f "${path}" ]] ||
        release_fail 'regular file이 필요합니다.'
}

release_directory() {
    local path=${1:-}
    [[ -n "${path}" && ! -L "${path}" && -d "${path}" ]] ||
        release_fail 'symbolic link가 아닌 directory가 필요합니다.'
}

release_stage_identity() {
    local path=$1 device inode owner mode
    release_directory "${path}" || return 1
    device=$(release_stat_value '%d' "${path}") || return 1
    inode=$(release_stat_value '%i' "${path}") || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    printf '%s:%s:%s:%s\n' "${device}" "${inode}" "${owner}" "${mode}"
}

release_validate_stage_root() {
    local stage_root=${1:-} container=${2:-}
    local physical_stage physical_container owner mode current_owner
    release_directory "${stage_root}" || return 1
    release_directory "${container}" || return 1
    physical_stage="$(CDPATH= cd -P -- "${stage_root}" && pwd -P)"
    physical_container="$(CDPATH= cd -P -- "${container}" && pwd -P)"
    case "${physical_stage}/" in
        "${physical_container}/"*) ;;
        *) release_fail 'private stage root가 허용된 container 밖에 있습니다.'; return 1 ;;
    esac
    [[ "${physical_stage}" != "${physical_container}" ]] || {
        release_fail 'private stage root는 container 자체일 수 없습니다.'
        return 1
    }
    owner=$(release_stat_value '%u' "${physical_stage}") || return 1
    current_owner=$(/usr/bin/id -u) || {
        release_fail '현재 release owner를 확인할 수 없습니다.'
        return 1
    }
    [[ "${owner}" = "${current_owner}" ]] || {
        release_fail 'private stage root owner가 현재 사용자와 다릅니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${physical_stage}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 077)) = 0 ]] || {
        release_fail 'private stage root mode는 group/other 접근을 허용하면 안 됩니다.'
        return 1
    }
    release_link_count "${physical_stage}" >/dev/null
    release_stage_identity "${physical_stage}" >/dev/null
}

release_assert_stage_identity() {
    local stage_root=$1 expected=$2 actual
    actual=$(release_stage_identity "${stage_root}") || return 1
    [[ "${actual}" = "${expected}" ]] || release_fail 'private stage root changed during verification.'
}

release_stat_value() {
    local format=$1 path=$2 value
    if [[ "$(/usr/bin/uname -s)" = Darwin ]] && value=$(/usr/bin/stat -f "${format}" "${path}" 2>/dev/null); then
        printf '%s\n' "${value}"
        return 0
    fi
    case "${format}" in
        '%d') format='%d' ;;
        '%i') format='%i' ;;
        '%z') format='%s' ;;
        '%l') format='%h' ;;
        '%u') format='%u' ;;
        '%Lp') format='%a' ;;
        *) release_fail 'stat format을 변환할 수 없습니다.'; return 1 ;;
    esac
    if ! value=$(/usr/bin/stat -c "${format}" "${path}" 2>/dev/null); then
        release_fail 'file identity를 확인할 수 없습니다.'
        return 1
    fi
    printf '%s\n' "${value}"
}

release_link_count() {
    local path=$1 count
    count=$(release_stat_value '%l' "${path}") || return 1
    [[ "${count}" =~ ^[0-9]+$ ]] || {
        release_fail 'link count 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${count}"
}

release_file_identity() {
    local path=$1 device inode size links digest
    release_require_single_link_file "${path}" || return 1
    device=$(release_stat_value '%d' "${path}") || return 1
    inode=$(release_stat_value '%i' "${path}") || return 1
    size=$(release_stat_value '%z' "${path}") || return 1
    links=$(release_link_count "${path}") || return 1
    digest=$(release_sha256_unchecked "${path}") || return 1
    printf '%s:%s:%s:%s:%s\n' "${device}" "${inode}" "${size}" "${links}" "${digest}"
}

release_assert_file_identity() {
    local path=$1 expected=$2 actual
    actual=$(release_file_identity "${path}") || return 1
    [[ "${actual}" = "${expected}" ]] || release_fail 'artifact changed during verification.'
}

release_sha256_unchecked() {
    local path=$1 raw digest shasum_tool
    shasum_tool=$(release_tool shasum) || return 1
    if ! raw=$("${shasum_tool}" -a 256 "${path}" 2>&1); then
        release_fail 'SHA-256을 계산하지 못했습니다.'
        return 1
    fi
    digest=${raw%%[[:space:]]*}
    [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${digest}"
}

release_require_single_link_file() {
    local path=$1 links
    release_regular_file "${path}" || return 1
    links=$(release_link_count "${path}") || return 1
    [[ "${links}" = 1 ]] || release_fail 'hardlink가 포함되어 있습니다.'
}

release_sha256() {
    local path=${1:-} before after digest
    release_require_single_link_file "${path}" || return 1
    before=$(release_file_identity_without_hash "${path}") || return 1
    digest=$(release_sha256_unchecked "${path}") || return 1
    after=$(release_file_identity_without_hash "${path}") || return 1
    [[ "${before}" = "${after}" ]] || {
        release_fail 'artifact changed during verification.'
        return 1
    }
    printf '%s\n' "${digest}"
}

release_file_identity_without_hash() {
    local path=$1 device inode size links
    release_require_single_link_file "${path}" || return 1
    device=$(release_stat_value '%d' "${path}") || return 1
    inode=$(release_stat_value '%i' "${path}") || return 1
    size=$(release_stat_value '%z' "${path}") || return 1
    links=$(release_link_count "${path}") || return 1
    printf '%s:%s:%s:%s\n' "${device}" "${inode}" "${size}" "${links}"
}

release_android_sdk_root() {
    local root=''
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        root=${ANDROID_HOME}
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        root=${ANDROID_SDK_ROOT}
    elif [[ -d /opt/homebrew/share/android-commandlinetools ]]; then
        root='/opt/homebrew/share/android-commandlinetools'
    fi
    release_directory "${root}" || return 1
    printf '%s\n' "$(CDPATH= cd -P -- "${root}" && pwd -P)"
}

release_tool() {
    local name=$1 sdk_root path
    if [[ "${SIXBRIDGE_RELEASE_TEST_MODE}" = 1 ]]; then
        case "${name}" in
            apksigner) path=${SIXBRIDGE_APKSIGNER:-} ;;
            apkanalyzer) path=${SIXBRIDGE_APKANALYZER:-} ;;
            hdiutil) path=${SIXBRIDGE_HDIUTIL:-} ;;
            plistbuddy) path=${SIXBRIDGE_PLISTBUDDY:-} ;;
            codesign) path=${SIXBRIDGE_CODESIGN:-} ;;
            jq) path=${SIXBRIDGE_JQ:-/usr/bin/jq} ;;
            shasum) path=${SIXBRIDGE_SHASUM:-/usr/bin/shasum} ;;
            *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
        esac
    else
        case "${name}" in
            apksigner)
                sdk_root=$(release_android_sdk_root) || return 1
                path="${sdk_root}/build-tools/36.0.0/apksigner"
                ;;
            apkanalyzer)
                sdk_root=$(release_android_sdk_root) || return 1
                path="${sdk_root}/cmdline-tools/latest/bin/apkanalyzer"
                ;;
            hdiutil) path='/usr/bin/hdiutil' ;;
            plistbuddy) path='/usr/libexec/PlistBuddy' ;;
            codesign) path='/usr/bin/codesign' ;;
            jq) path='/usr/bin/jq' ;;
            shasum) path='/usr/bin/shasum' ;;
            *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
        esac
    fi
    [[ "${path}" = /* && -x "${path}" && ! -L "${path}" ]] || {
        release_fail '신뢰할 수 있는 release 검증 도구를 실행할 수 없습니다.'
        return 1
    }
    printf '%s\n' "${path}"
}

release_verify_apk() {
    local apk=${1:-} expected_signer=${2:-}
    local raw_output signer_lines actual_signer application_id version_name version_code
    local apksigner apkanalyzer artifact_identity

    release_require_single_link_file "${apk}" || return 1
    [[ "${expected_signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'expected signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    apksigner=$(release_tool apksigner) || return 1
    apkanalyzer=$(release_tool apkanalyzer) || return 1
    artifact_identity=$(release_file_identity "${apk}") || return 1

    if ! raw_output=$("${apksigner}" verify --verbose --print-certs "${apk}" 2>&1); then
        release_fail 'APK signature verification에 실패했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${artifact_identity}" || return 1
    signer_lines=$(printf '%s\n' "${raw_output}" | awk -F': ' '/Signer #[0-9]+ certificate SHA-256 digest:/ {print $2}')
    [[ "$(printf '%s\n' "${signer_lines}" | awk 'NF {count += 1} END {print count + 0}')" = 1 ]] || {
        release_fail 'APK signer는 정확히 하나여야 합니다.'
        return 1
    }
    actual_signer=${signer_lines}
    actual_signer=$(printf '%s' "${actual_signer}" | tr '[:upper:]' '[:lower:]')
    [[ "${actual_signer}" = "${expected_signer}" ]] || {
        release_fail 'APK signer SHA-256가 예상 값과 다릅니다.'
        return 1
    }

    if ! application_id=$("${apkanalyzer}" manifest application-id "${apk}" 2>&1); then
        release_fail 'APK application ID를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${artifact_identity}" || return 1
    [[ "${application_id}" = 'dev.sixbridge.manager' ]] || {
        release_fail 'APK application ID가 계약과 다릅니다.'
        return 1
    }
    if ! version_name=$("${apkanalyzer}" manifest version-name "${apk}" 2>&1); then
        release_fail 'APK versionName을 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${artifact_identity}" || return 1
    [[ "${version_name}" = "${SIXBRIDGE_RELEASE_VERSION}" ]] || {
        release_fail 'APK versionName이 계약과 다릅니다.'
        return 1
    }
    if ! version_code=$("${apkanalyzer}" manifest version-code "${apk}" 2>&1); then
        release_fail 'APK versionCode를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${artifact_identity}" || return 1
    [[ "${version_code}" = '1' ]] || {
        release_fail 'APK versionCode가 계약과 다릅니다.'
        return 1
    }
}

release_dmg_detach() {
    local mountpoint=$1 hdiutil
    hdiutil=$(release_tool hdiutil) || return 1
    if ! "${hdiutil}" detach "${mountpoint}" >/dev/null 2>&1; then
        release_fail 'DMG mount detach에 실패했습니다.'
        return 1
    fi
    rmdir "${mountpoint}" 2>/dev/null || true
}

release_dmg_value() {
    local plist=$1 key=$2 raw_output plistbuddy
    plistbuddy=$(release_tool plistbuddy) || return 1
    if ! raw_output=$("${plistbuddy}" -c "Print :${key}" "${plist}" 2>&1); then
        release_fail 'DMG Info.plist 값을 읽지 못했습니다.'
        return 1
    fi
    printf '%s\n' "${raw_output}"
}

release_verify_dmg() {
    local dmg=${1:-} work_dir=${2:-}
    local mountpoint app_path plist raw_output bundle_id product_version native_version minimum_version
    local hdiutil codesign dmg_identity app_candidate app_count=0

    release_require_single_link_file "${dmg}" || return 1
    if [[ -n "${work_dir}" && ! -e "${work_dir}" && ! -L "${work_dir}" ]]; then
        mkdir -p "${work_dir}" || return 1
    fi
    release_directory "${work_dir}" || return 1
    hdiutil=$(release_tool hdiutil) || return 1
    release_tool plistbuddy >/dev/null || return 1
    codesign=$(release_tool codesign) || return 1
    dmg_identity=$(release_file_identity "${dmg}") || return 1

    mountpoint=$(mktemp -d "${work_dir}/sixbridge-dmg.XXXXXX") || {
        release_fail 'DMG mount directory를 만들지 못했습니다.'
        return 1
    }
    if ! raw_output=$("${hdiutil}" attach -readonly -nobrowse -mountpoint "${mountpoint}" "${dmg}" 2>&1); then
        rmdir "${mountpoint}" 2>/dev/null || true
        release_fail 'DMG read-only mount에 실패했습니다.'
        return 1
    fi
    release_assert_file_identity "${dmg}" "${dmg_identity}" || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }

    app_path="${mountpoint}/SIX Bridge.app"
    for app_candidate in "${mountpoint}"/*.app; do
        [[ -d "${app_candidate}" && ! -L "${app_candidate}" ]] || continue
        app_count=$((app_count + 1))
    done
    if [[ "${app_count}" != 1 || -L "${app_path}" || ! -d "${app_path}" ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG app bundle이 정확히 하나여야 합니다.'
        return 1
    fi
    plist="${app_path}/Contents/Info.plist"
    if [[ -L "${plist}" || ! -f "${plist}" ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG Info.plist가 없습니다.'
        return 1
    fi
    bundle_id=$(release_dmg_value "${plist}" CFBundleIdentifier) || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }
    product_version=$(release_dmg_value "${plist}" CFBundleShortVersionString) || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }
    native_version=$(release_dmg_value "${plist}" CFBundleVersion) || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }
    minimum_version=$(release_dmg_value "${plist}" LSMinimumSystemVersion) || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }
    if [[ "${bundle_id}" != 'dev.sixbridge.desktop' ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG bundle ID가 계약과 다릅니다.'
        return 1
    fi
    if [[ "${product_version}" != "${SIXBRIDGE_RELEASE_VERSION}" ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG product version이 계약과 다릅니다.'
        return 1
    fi
    if [[ "${native_version}" != '1.0.0' ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG native package version이 계약과 다릅니다.'
        return 1
    fi
    if [[ "${minimum_version}" != '13.0' ]]; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG minimum macOS version이 계약과 다릅니다.'
        return 1
    fi
    if raw_output=$("${codesign}" -dv --verbose=4 "${app_path}" 2>&1); then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'DMG는 v0.1.0에서 미서명이어야 합니다.'
        return 1
    fi
    if ! printf '%s\n' "${raw_output}" | /usr/bin/grep -Fq 'not signed at all'; then
        release_dmg_detach "${mountpoint}" || true
        release_fail 'codesign이 미서명 상태를 확인하지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${dmg}" "${dmg_identity}" || {
        release_dmg_detach "${mountpoint}" || true
        return 1
    }
    release_dmg_detach "${mountpoint}"
}

release_scan_tree() {
    local root=${1:-} physical_root entries entry basename lower secret_probe file_identity
    release_directory "${root}" || return 1
    physical_root="$(CDPATH= cd -P -- "${root}" && pwd -P)"
    if ! entries=$(find "${physical_root}" -xdev -print 2>&1); then
        release_fail 'release tree를 순회하지 못했습니다.'
        return 1
    fi
    secret_probe="${SIXBRIDGE_RELEASE_SECRET_PROBE:-}"
    while IFS= read -r entry; do
        [[ -n "${entry}" ]] || continue
        [[ ! -L "${entry}" ]] || {
            release_fail 'release tree에 symbolic link가 포함되어 있습니다.'
            return 1
        }
        basename=$(basename -- "${entry}")
        if [[ "${basename}" = '.local' ]]; then
            release_fail 'forbidden release input이 포함되어 있습니다.'
            return 1
        fi
        if [[ -d "${entry}" ]]; then
            continue
        fi
        release_require_single_link_file "${entry}" || return 1
        file_identity=$(release_file_identity "${entry}") || return 1
        lower=$(printf '%s' "${basename}" | tr '[:upper:]' '[:lower:]')
        case "${lower}" in
            *.p12|*.jks|*.pem|*.pk8|*.six|*.sixdraft|*.crx|*.zip)
                release_fail 'forbidden release input이 포함되어 있습니다.'
                return 1
                ;;
        esac
        if [[ -n "${secret_probe}" ]] && grep -Fq -- "${secret_probe}" "${entry}"; then
            release_fail '비밀정보가 release tree에 포함되어 있습니다.'
            return 1
        fi
        release_assert_file_identity "${entry}" "${file_identity}" || return 1
    done <<<"${entries}"
}

release_atomic_publish_file() {
    local temp_file=$1 destination=$2
    [[ ! -e "${destination}" && ! -L "${destination}" ]] || {
        rm -f "${temp_file}"
        release_fail 'release output이 이미 존재합니다.'
        return 1
    }
    if ! ln "${temp_file}" "${destination}"; then
        rm -f "${temp_file}"
        release_fail 'release output을 no-replace 방식으로 publish하지 못했습니다.'
        return 1
    fi
    rm -f "${temp_file}"
}

release_write_metadata() {
    local directory=${1:-} git_commit=${2:-} signer=${3:-}
    local apk dmg note target temp_file android_sha macos_sha
    local jq apk_identity dmg_identity note_identity stage_parent stage_identity
    release_directory "${directory}" || return 1
    stage_parent=$(dirname -- "${directory}")
    release_validate_stage_root "${directory}" "${stage_parent}" || return 1
    stage_identity=$(release_stage_identity "${directory}") || return 1
    [[ "${git_commit}" =~ ^[0-9a-f]{40}$ ]] || {
        release_fail 'git commit 형식이 올바르지 않습니다.'
        return 1
    }
    [[ "${signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    jq=$(release_tool jq) || return 1
    apk="${directory}/${SIXBRIDGE_ANDROID_FILE}"
    dmg="${directory}/${SIXBRIDGE_MACOS_FILE}"
    note="${directory}/RELEASE-NOTES-KO.md"
    release_require_single_link_file "${apk}" || return 1
    release_require_single_link_file "${dmg}" || return 1
    release_require_single_link_file "${note}" || return 1
    apk_identity=$(release_file_identity "${apk}") || return 1
    dmg_identity=$(release_file_identity "${dmg}") || return 1
    note_identity=$(release_file_identity "${note}") || return 1
    android_sha=$(release_sha256 "${apk}") || return 1
    macos_sha=$(release_sha256 "${dmg}") || return 1
    release_assert_file_identity "${apk}" "${apk_identity}" || return 1
    release_assert_file_identity "${dmg}" "${dmg_identity}" || return 1
    release_assert_file_identity "${note}" "${note_identity}" || return 1
    target="${directory}/RELEASE-METADATA.json"
    [[ ! -e "${target}" && ! -L "${target}" ]] || {
        release_fail 'release metadata가 이미 존재합니다.'
        return 1
    }
    temp_file=$(mktemp "${directory}/.release-metadata.XXXXXX") || {
        release_fail 'release metadata 임시 파일을 만들지 못했습니다.'
        return 1
    }
    if ! "${jq}" -S -n \
        --arg git_commit "${git_commit}" \
        --arg signer "${signer}" \
        --arg android_sha "${android_sha}" \
        --arg macos_sha "${macos_sha}" \
        '{schemaVersion: 1, productVersion: "0.1.0", gitCommit: $git_commit,
          android: {file: "SIX-Bridge-Manager-0.1.0-android.apk", applicationId: "dev.sixbridge.manager", versionName: "0.1.0", versionCode: 1, signerSha256: $signer, sha256: $android_sha},
          macos: {file: "SIX-Bridge-0.1.0-macos-unsigned.dmg", bundleId: "dev.sixbridge.desktop", productVersion: "0.1.0", nativePackageVersion: "1.0.0", minimumSystemVersion: "13.0", signed: false, notarized: false, sha256: $macos_sha}}' >"${temp_file}"; then
        rm -f "${temp_file}"
        release_fail 'release metadata를 만들지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${apk_identity}" || { rm -f "${temp_file}"; return 1; }
    release_assert_file_identity "${dmg}" "${dmg_identity}" || { rm -f "${temp_file}"; return 1; }
    release_assert_file_identity "${note}" "${note_identity}" || { rm -f "${temp_file}"; return 1; }
    release_assert_stage_identity "${directory}" "${stage_identity}" || { rm -f "${temp_file}"; return 1; }
    release_atomic_publish_file "${temp_file}" "${target}"
}

release_write_checksums() {
    local directory=${1:-} target temp_file file digest contents=''
    local identities='' identity stage_parent stage_identity
    local checksum_files=(
        "${SIXBRIDGE_ANDROID_FILE}"
        "${SIXBRIDGE_MACOS_FILE}"
        'RELEASE-METADATA.json'
        'RELEASE-NOTES-KO.md'
    )
    release_directory "${directory}" || return 1
    stage_parent=$(dirname -- "${directory}")
    release_validate_stage_root "${directory}" "${stage_parent}" || return 1
    stage_identity=$(release_stage_identity "${directory}") || return 1
    target="${directory}/SHA256SUMS"
    [[ ! -e "${target}" && ! -L "${target}" ]] || {
        release_fail 'SHA256SUMS가 이미 존재합니다.'
        return 1
    }
    for file in "${checksum_files[@]}"; do
        identity=$(release_file_identity "${directory}/${file}") || return 1
        identities="${identities}${file}|${identity}"$'\n'
        digest=$(release_sha256 "${directory}/${file}") || return 1
        contents="${contents}$(LC_ALL=C printf '%s  %s\n' "${digest}" "${file}")"$'\n'
    done
    temp_file=$(mktemp "${directory}/.release-checksums.XXXXXX") || {
        release_fail 'checksum 임시 파일을 만들지 못했습니다.'
        return 1
    }
    while IFS='|' read -r file identity; do
        [[ -n "${file}" ]] || continue
        release_assert_file_identity "${directory}/${file}" "${identity}" || { rm -f "${temp_file}"; return 1; }
    done <<<"${identities}"
    release_assert_stage_identity "${directory}" "${stage_identity}" || { rm -f "${temp_file}"; return 1; }
    printf '%s' "${contents}" >"${temp_file}" || {
        rm -f "${temp_file}"
        release_fail 'SHA256SUMS를 만들지 못했습니다.'
        return 1
    }
    release_atomic_publish_file "${temp_file}" "${target}"
}

release_verify_metadata() {
    local directory=$1 expected_signer=$2 metadata raw_output android_sha macos_sha jq metadata_identity
    metadata="${directory}/RELEASE-METADATA.json"
    release_require_single_link_file "${metadata}" || return 1
    metadata_identity=$(release_file_identity "${metadata}") || return 1
    jq=$(release_tool jq) || return 1
    if ! raw_output=$("${jq}" -er --arg signer "${expected_signer}" '
      type == "object" and
      (keys | sort) == ["android", "gitCommit", "macos", "productVersion", "schemaVersion"] and
      .schemaVersion == 1 and .productVersion == "0.1.0" and
      (.gitCommit | test("^[0-9a-f]{40}$")) and
      (.android | type == "object" and
        .file == "SIX-Bridge-Manager-0.1.0-android.apk" and
        .applicationId == "dev.sixbridge.manager" and .versionName == "0.1.0" and
        .versionCode == 1 and .signerSha256 == $signer and
        (.sha256 | test("^[0-9a-f]{64}$"))) and
      (.macos | type == "object" and
        .file == "SIX-Bridge-0.1.0-macos-unsigned.dmg" and
        .bundleId == "dev.sixbridge.desktop" and .productVersion == "0.1.0" and
        .nativePackageVersion == "1.0.0" and .minimumSystemVersion == "13.0" and
        .signed == false and .notarized == false and
        (.sha256 | test("^[0-9a-f]{64}$"))
      )
    ' "${metadata}" 2>&1); then
        release_fail 'release metadata 계약이 올바르지 않습니다.'
        return 1
    fi
    [[ "${raw_output}" = 'true' ]] || {
        release_fail 'release metadata 계약이 올바르지 않습니다.'
        return 1
    }
    release_assert_file_identity "${metadata}" "${metadata_identity}" || return 1
    android_sha=$(release_sha256 "${directory}/${SIXBRIDGE_ANDROID_FILE}") || return 1
    macos_sha=$(release_sha256 "${directory}/${SIXBRIDGE_MACOS_FILE}") || return 1
    if ! raw_output=$("${jq}" -er '.android.sha256' "${metadata}" 2>&1); then
        release_fail 'Android metadata SHA-256를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${metadata}" "${metadata_identity}" || return 1
    [[ "${raw_output}" = "${android_sha}" ]] || {
        release_fail 'Android metadata SHA-256가 다릅니다.'
        return 1
    }
    if ! raw_output=$("${jq}" -er '.macos.sha256' "${metadata}" 2>&1); then
        release_fail 'macOS metadata SHA-256를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${metadata}" "${metadata_identity}" || return 1
    [[ "${raw_output}" = "${macos_sha}" ]] || {
        release_fail 'macOS metadata SHA-256가 다릅니다.'
        return 1
    }
}

release_verify_checksums() {
    local directory=$1 checksum_file expected='' expected_file file digest checksum_identity
    local checksum_files=(
        "${SIXBRIDGE_ANDROID_FILE}"
        "${SIXBRIDGE_MACOS_FILE}"
        'RELEASE-METADATA.json'
        'RELEASE-NOTES-KO.md'
    )
    checksum_file="${directory}/SHA256SUMS"
    release_require_single_link_file "${checksum_file}" || return 1
    checksum_identity=$(release_file_identity "${checksum_file}") || return 1
    for file in "${checksum_files[@]}"; do
        digest=$(release_sha256 "${directory}/${file}") || return 1
        expected="${expected}$(LC_ALL=C printf '%s  %s\n' "${digest}" "${file}")"$'\n'
    done
    expected_file=$(mktemp "${directory}/.verify-checksums.XXXXXX") || {
        release_fail 'checksum 비교 임시 파일을 만들지 못했습니다.'
        return 1
    }
    printf '%s' "${expected}" >"${expected_file}" || {
        rm -f "${expected_file}"
        release_fail 'checksum 비교 데이터를 만들지 못했습니다.'
        return 1
    }
    if ! cmp -s "${checksum_file}" "${expected_file}"; then
        rm -f "${expected_file}"
        release_fail 'SHA256SUMS가 release 산출물과 다릅니다.'
        return 1
    fi
    rm -f "${expected_file}"
    release_assert_file_identity "${checksum_file}" "${checksum_identity}"
}

release_verify_directory() {
    local directory=${1:-} expected_signer=${2:-}
    local physical_directory entries entry basename count=0 work_dir
    release_directory "${directory}" || return 1
    [[ "${expected_signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'expected signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    physical_directory="$(CDPATH= cd -P -- "${directory}" && pwd -P)"
    if ! entries=$(find "${physical_directory}" -maxdepth 1 -mindepth 1 -print 2>&1); then
        release_fail 'release directory를 읽지 못했습니다.'
        return 1
    fi
    while IFS= read -r entry; do
        [[ -n "${entry}" ]] || continue
        basename=$(basename -- "${entry}")
        case "${basename}" in
            "${SIXBRIDGE_ANDROID_FILE}"|"${SIXBRIDGE_MACOS_FILE}"|SHA256SUMS|RELEASE-METADATA.json|RELEASE-NOTES-KO.md)
                release_require_single_link_file "${entry}" || return 1
                count=$((count + 1))
                ;;
            *)
                release_fail 'release directory는 정확히 다섯 개의 필수 release file만 가져야 합니다.'
                return 1
                ;;
        esac
    done <<<"${entries}"
    [[ "${count}" = 5 ]] || {
        release_fail '필수 release file이 누락되었습니다.'
        return 1
    }
    release_scan_tree "${physical_directory}" || return 1
    release_verify_checksums "${physical_directory}" || return 1
    release_verify_metadata "${physical_directory}" "${expected_signer}" || return 1
    release_verify_apk "${physical_directory}/${SIXBRIDGE_ANDROID_FILE}" "${expected_signer}" || return 1
    work_dir=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-release-verify.XXXXXX") || {
        release_fail 'DMG verification work directory를 만들지 못했습니다.'
        return 1
    }
    if ! release_verify_dmg "${physical_directory}/${SIXBRIDGE_MACOS_FILE}" "${work_dir}"; then
        rm -rf "${work_dir}"
        return 1
    fi
    rm -rf "${work_dir}"
}
