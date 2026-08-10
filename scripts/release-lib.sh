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
SIXBRIDGE_RELEASE_PROJECT_ROOT=''

release_fail() {
    printf 'release 검증 실패: %s\n' "$1" >&2
    return 1
}

release_utf8_locale() {
    local charmap
    release_system_executable /usr/bin/env || return 1
    release_system_executable /usr/bin/locale || return 1
    if ! charmap=$(/usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty \
        LANG=C LC_CTYPE=UTF-8 /usr/bin/locale charmap 2>/dev/null); then
        release_fail 'UTF-8 release locale을 사용할 수 없습니다.'
        return 1
    fi
    [[ "${charmap}" = UTF-8 ]] || {
        release_fail 'release locale charmap은 UTF-8이어야 합니다.'
        return 1
    }
    printf '%s\n' "${charmap}"
}

release_bind_project_root() {
    local requested=${1:-} physical
    [[ -n "${requested}" && "${requested}" = /* && -d "${requested}" && ! -L "${requested}" ]] || {
        release_fail 'release project root는 canonical absolute directory여야 합니다.'
        return 1
    }
    physical="$(CDPATH= builtin cd -P -- "${requested}" && builtin pwd -P)" || return 1
    [[ "${physical}" = "${requested}" ]] || {
        release_fail 'release project root canonical path가 입력과 다릅니다.'
        return 1
    }
    release_trusted_directory "${physical}" || return 1
    release_trusted_directory "${physical}/scripts" || return 1
    if [[ -n "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" ]]; then
        [[ "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" = "${physical}" ]] || {
            release_fail 'release project root는 다시 bind할 수 없습니다.'
            return 1
        }
        return 0
    fi
    SIXBRIDGE_RELEASE_PROJECT_ROOT=${physical}
    readonly SIXBRIDGE_RELEASE_PROJECT_ROOT
}

release_before_atomic_publish() {
    :
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
    local path=$1 device inode owner mode ctime
    release_directory "${path}" || return 1
    device=$(release_stat_value '%d' "${path}") || return 1
    inode=$(release_stat_value '%i' "${path}") || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    ctime=$(release_stat_value '%c' "${path}") || return 1
    printf '%s:%s:%s:%s:%s\n' "${device}" "${inode}" "${owner}" "${mode}" "${ctime}"
}

# Container contents can be changed only by verifier-owned private work and
# snapshot directories. Its identity therefore omits ctime while retaining the
# properties that prevent container replacement, ownership, and mode changes.
release_container_identity() {
    local path=$1 device inode owner mode
    release_directory "${path}" || return 1
    device=$(release_stat_value '%d' "${path}") || return 1
    inode=$(release_stat_value '%i' "${path}") || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    printf '%s:%s:%s:%s\n' "${device}" "${inode}" "${owner}" "${mode}"
}

release_private_directory() {
    local path=${1:-} owner mode current_owner
    release_directory "${path}" || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    current_owner=$(/usr/bin/id -u) || return 1
    [[ "${owner}" = "${current_owner}" ]] || {
        release_fail 'trusted directory owner가 현재 사용자와 다릅니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#077)) = 0 ]] || {
        release_fail 'trusted directory는 group/other 접근을 허용하면 안 됩니다.'
        return 1
    }
    release_link_count "${path}" >/dev/null
}

release_trusted_directory() {
    local path=${1:-} owner mode current_owner
    release_directory "${path}" || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    current_owner=$(/usr/bin/id -u) || return 1
    [[ "${owner}" = "${current_owner}" ]] || {
        release_fail 'trusted directory owner가 현재 사용자와 다릅니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        release_fail 'trusted directory는 group/other write를 허용하면 안 됩니다.'
        return 1
    }
    release_link_count "${path}" >/dev/null
}

release_validate_release_container() {
    local container=${1:-} local_root="${SIXBRIDGE_RELEASE_PROJECT_ROOT}/.local"
    local physical_container physical_local relative
    release_trusted_directory "${local_root}" || return 1
    release_private_directory "${container}" || return 1
    physical_container="$(CDPATH= builtin cd -P -- "${container}" && builtin pwd -P)"
    physical_local="$(CDPATH= builtin cd -P -- "${local_root}" && builtin pwd -P)"
    case "${physical_container}/" in
        "${physical_local}/"*) ;;
        *) release_fail 'trusted container는 프로젝트 .local 아래에 있어야 합니다.'; return 1 ;;
    esac
    relative=${physical_container#"${physical_local}/"}
    [[ "${relative}" != */* ]] || {
        release_fail 'trusted container는 .local의 직접 child여야 합니다.'
        return 1
    }
}

release_validate_stage_root() {
    local stage_root=${1:-} container=${2:-}
    local physical_stage physical_container
    release_private_directory "${stage_root}" || return 1
    release_validate_release_container "${container}" || return 1
    physical_stage="$(CDPATH= builtin cd -P -- "${stage_root}" && builtin pwd -P)"
    physical_container="$(CDPATH= builtin cd -P -- "${container}" && builtin pwd -P)"
    case "${physical_stage}/" in
        "${physical_container}/"*) ;;
        *) release_fail 'private stage root가 허용된 container 밖에 있습니다.'; return 1 ;;
    esac
    [[ "${physical_stage}" != "${physical_container}" ]] || {
        release_fail 'private stage root는 container 자체일 수 없습니다.'
        return 1
    }
    release_stage_identity "${physical_stage}" >/dev/null
    release_container_identity "${physical_container}" >/dev/null
}

release_assert_stage_identity() {
    local stage_root=$1 container=$2 expected_stage=$3 expected_container=$4 actual
    release_validate_stage_root "${stage_root}" "${container}" || return 1
    actual=$(release_stage_identity "${stage_root}") || return 1
    [[ "${actual}" = "${expected_stage}" ]] || {
        release_fail 'private stage root changed during verification.'
        return 1
    }
    actual=$(release_container_identity "${container}") || return 1
    [[ "${actual}" = "${expected_container}" ]] || {
        release_fail 'trusted container changed during verification.'
        return 1
    }
}

release_require_artifact_stage() {
    local artifact=${1:-} stage_root=${2:-} container=${3:-}
    local physical_artifact physical_stage
    release_validate_stage_root "${stage_root}" "${container}" || return 1
    release_regular_file "${artifact}" || return 1
    physical_artifact="$(CDPATH= builtin cd -P -- "$(/usr/bin/dirname -- "${artifact}")" && builtin pwd -P)/$(/usr/bin/basename -- "${artifact}")"
    physical_stage="$(CDPATH= builtin cd -P -- "${stage_root}" && builtin pwd -P)"
    case "${physical_artifact}" in
        "${physical_stage}"/*) ;;
        *) release_fail 'artifact가 private stage root 밖에 있습니다.'; return 1 ;;
    esac
}

release_linux_stat_format() {
    local format=$1
    case "${format}" in
        '%d') format='%d' ;;
        '%i') format='%i' ;;
        '%z') format='%s' ;;
        '%l') format='%h' ;;
        '%c') format='%Z' ;;
        '%u') format='%u' ;;
        '%g') format='%g' ;;
        '%Lp') format='%a' ;;
        *) release_fail 'stat format을 변환할 수 없습니다.'; return 1 ;;
    esac
    printf '%s\n' "${format}"
}

release_stat_value() {
    local format=$1 path=$2 value
    if [[ "$(/usr/bin/uname -s)" = Darwin ]] && value=$(/usr/bin/stat -f "${format}" "${path}" 2>/dev/null); then
        printf '%s\n' "${value}"
        return 0
    fi
    format=$(release_linux_stat_format "${format}") || return 1
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

release_create_verification_snapshot() {
    local artifact=$1 container=$2 snapshot_parent=${3:-$2} snapshot_dir snapshot snapshot_identity artifact_identity artifact_digest snapshot_digest
    if [[ "${snapshot_parent}" = "${container}" ]]; then
        release_validate_release_container "${container}" || return 1
    else
        release_validate_private_work_directory "${snapshot_parent}" "${container}" || return 1
    fi
    artifact_identity=$(release_file_identity "${artifact}") || return 1
    snapshot_dir=$(/usr/bin/mktemp -d "${snapshot_parent}/.sixbridge-release-snapshot.XXXXXX") || {
        release_fail 'verification snapshot directory를 만들지 못했습니다.'
        return 1
    }
    release_private_directory "${snapshot_dir}" || { /bin/rm -rf "${snapshot_dir}"; return 1; }
    snapshot="${snapshot_dir}/artifact"
    if ! /bin/cp -p "${artifact}" "${snapshot}"; then
        /bin/rm -rf "${snapshot_dir}"
        release_fail 'verification snapshot을 만들지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${artifact}" "${artifact_identity}" || { /bin/rm -rf "${snapshot_dir}"; return 1; }
    snapshot_identity=$(release_file_identity "${snapshot}") || { /bin/rm -rf "${snapshot_dir}"; return 1; }
    artifact_digest=${artifact_identity##*:}
    snapshot_digest=${snapshot_identity##*:}
    [[ "${snapshot_digest}" = "${artifact_digest}" ]] || {
        /bin/rm -rf "${snapshot_dir}"
        release_fail 'verification snapshot 내용이 원본과 다릅니다.'
        return 1
    }
    printf '%s\n' "${snapshot_dir}"
}

release_remove_verification_snapshot() {
    local snapshot_dir=${1:-}
    [[ -n "${snapshot_dir}" && -d "${snapshot_dir}" && ! -L "${snapshot_dir}" ]] || return 0
    /bin/rm -rf "${snapshot_dir}"
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
    local root='' line key value properties="${SIXBRIDGE_RELEASE_PROJECT_ROOT}/local.properties" physical_properties
    if [[ -e "${properties}" || -L "${properties}" ]]; then
        release_private_file "${properties}" || return 1
        physical_properties="$(CDPATH= builtin cd -P -- "$(/usr/bin/dirname -- "${properties}")" && builtin pwd -P)/$(/usr/bin/basename -- "${properties}")"
        [[ "${physical_properties}" = "${SIXBRIDGE_RELEASE_PROJECT_ROOT}/local.properties" ]] || {
            release_fail 'local.properties가 project root 밖에 있습니다.'
            return 1
        }
        while IFS= read -r line || [[ -n "${line}" ]]; do
            key=${line%%=*}
            value=${line#*=}
            [[ "${key}" = sdk.dir && "${value}" = /* ]] && root=${value}
        done <"${properties}"
    fi
    [[ -n "${root}" ]] || root='/opt/homebrew/share/android-commandlinetools'
    release_trusted_directory "${root}" || return 1
    printf '%s\n' "$(CDPATH= builtin cd -P -- "${root}" && builtin pwd -P)"
}

release_resolve_homebrew_java_at() {
    local prefix=$1 formula=$2 physical_prefix formula_link java_home java_path
    local relative version formula_directory version_directory directory
    [[ "${prefix}" = /* && "${formula}" =~ ^[A-Za-z0-9@._+-]+$ ]] || {
        release_fail 'Homebrew JDK resolver 입력이 올바르지 않습니다.'
        return 1
    }
    release_trusted_directory "${prefix}" || return 1
    physical_prefix="$(CDPATH= cd -P -- "${prefix}" && pwd -P)" || return 1
    [[ "${physical_prefix}" = "${prefix}" ]] || {
        release_fail 'Homebrew JDK prefix canonical 경로가 올바르지 않습니다.'
        return 1
    }
    release_trusted_directory "${physical_prefix}/opt" || return 1
    release_trusted_directory "${physical_prefix}/Cellar" || return 1
    formula_link="${physical_prefix}/opt/${formula}"
    [[ -L "${formula_link}" && -d "${formula_link}" ]] || {
        release_fail '고정된 Homebrew JDK를 찾을 수 없습니다.'
        return 1
    }
    java_home="$(CDPATH= cd -P -- "${formula_link}/libexec/openjdk.jdk/Contents/Home" 2>/dev/null && pwd -P)" || {
        release_fail '고정된 Homebrew JDK를 찾을 수 없습니다.'
        return 1
    }
    case "${java_home}" in
        "${physical_prefix}/Cellar/${formula}/"*/libexec/openjdk.jdk/Contents/Home) ;;
        *) release_fail 'JDK canonical 경로가 Homebrew Cellar 밖에 있습니다.'; return 1 ;;
    esac
    relative=${java_home#"${physical_prefix}/Cellar/${formula}/"}
    version=${relative%%/*}
    [[ -n "${version}" && "${relative}" = "${version}/libexec/openjdk.jdk/Contents/Home" ]] || {
        release_fail 'JDK canonical 경로 구조가 올바르지 않습니다.'
        return 1
    }
    formula_directory="${physical_prefix}/Cellar/${formula}"
    version_directory="${formula_directory}/${version}"
    for directory in \
        "${formula_directory}" \
        "${version_directory}" \
        "${version_directory}/libexec" \
        "${version_directory}/libexec/openjdk.jdk" \
        "${version_directory}/libexec/openjdk.jdk/Contents" \
        "${java_home}" \
        "${java_home}/bin"; do
        release_trusted_directory "${directory}" || return 1
    done
    java_path="${java_home}/bin/java"
    release_trusted_executable "${java_path}" || return 1
    printf '%s\n' "${java_path}"
}

release_java_executable() {
    local raw_output java_path java_version major formula
    # This project already standardizes its local Android builds on Homebrew
    # JDK homes. Do not accept JAVA_HOME/PATH here: caller-controlled runtime
    # selection would let an SDK launcher execute an unverified JVM.
    release_system_directory / || return 1
    release_system_directory /opt || return 1
    release_trusted_directory /opt/homebrew || return 1
    release_trusted_directory /opt/homebrew/opt || return 1
    release_trusted_directory /opt/homebrew/Cellar || return 1
    for formula in openjdk@17 openjdk; do
        [[ -L "/opt/homebrew/opt/${formula}" && -d "/opt/homebrew/opt/${formula}" ]] || continue
        java_path=$(release_resolve_homebrew_java_at /opt/homebrew "${formula}") || return 1
        break
    done
    [[ -n "${java_path:-}" ]] || {
        release_fail '고정된 Homebrew JDK를 찾을 수 없습니다.'
        return 1
    }
    release_utf8_locale >/dev/null || return 1
    if ! raw_output=$(/usr/bin/env -i PATH=/usr/bin:/bin HOME="${SIXBRIDGE_RELEASE_PROJECT_ROOT}" \
        LANG=C LC_CTYPE=UTF-8 "${java_path}" -version 2>&1); then
        release_fail 'JDK version을 확인하지 못했습니다.'
        return 1
    fi
    java_version=$(printf '%s\n' "${raw_output}" | /usr/bin/awk -F'"' '/version/ { print $2; exit }')
    major=${java_version%%.*}
    [[ "${major}" =~ ^[0-9]+$ && ${major} -ge 17 ]] || {
        release_fail 'JDK 17 이상이 필요합니다.'
        return 1
    }
    printf '%s\n' "${java_path}"
}

release_resolve_homebrew_tool_at() {
    local prefix=$1 formula=$2 binary=$3 physical_prefix formula_link bin_directory
    local physical relative version formula_directory version_directory
    [[ "${prefix}" = /* && "${formula}" =~ ^[A-Za-z0-9@._+-]+$ && "${binary}" =~ ^[A-Za-z0-9._+-]+$ ]] || {
        release_fail 'Homebrew 도구 resolver 입력이 올바르지 않습니다.'
        return 1
    }
    release_trusted_directory "${prefix}" || return 1
    physical_prefix="$(CDPATH= cd -P -- "${prefix}" && pwd -P)" || return 1
    [[ "${physical_prefix}" = "${prefix}" ]] || {
        release_fail 'Homebrew prefix canonical 경로가 올바르지 않습니다.'
        return 1
    }
    release_trusted_directory "${physical_prefix}/opt" || return 1
    release_trusted_directory "${physical_prefix}/Cellar" || return 1
    formula_link="${physical_prefix}/opt/${formula}"
    [[ -L "${formula_link}" && -d "${formula_link}" ]] || {
        release_fail "고정된 ${binary} 도구를 찾을 수 없습니다. Homebrew ${formula}를 설치하세요."
        return 1
    }
    bin_directory="$(CDPATH= cd -P -- "${formula_link}/bin" 2>/dev/null && pwd -P)" || {
        release_fail "고정된 ${binary} 도구를 찾을 수 없습니다. Homebrew ${formula}를 설치하세요."
        return 1
    }
    physical="${bin_directory}/${binary}"
    case "${physical}" in
        "${physical_prefix}/Cellar/${formula}/"*) ;;
        *) release_fail "${binary} canonical 경로가 Homebrew Cellar 밖에 있습니다."; return 1 ;;
    esac
    relative=${physical#"${physical_prefix}/Cellar/${formula}/"}
    version=${relative%%/*}
    [[ -n "${version}" && "${relative}" = "${version}/bin/${binary}" ]] || {
        release_fail "${binary} canonical 경로 구조가 올바르지 않습니다."
        return 1
    }
    formula_directory="${physical_prefix}/Cellar/${formula}"
    version_directory="${formula_directory}/${version}"
    release_trusted_directory "${formula_directory}" || return 1
    release_trusted_directory "${version_directory}" || return 1
    release_trusted_directory "${bin_directory}" || return 1
    release_trusted_executable "${physical}" || return 1
    printf '%s\n' "${physical}"
}

release_homebrew_tool() {
    release_system_directory / || return 1
    release_system_directory /opt || return 1
    release_resolve_homebrew_tool_at /opt/homebrew "$1" "$2"
}

release_node_executable() { release_homebrew_tool node node; }
release_ripgrep_executable() { release_homebrew_tool ripgrep rg; }

release_run_java() {
    local java_path
    java_path=$(release_java_executable) || return 1
    release_utf8_locale >/dev/null || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${SIXBRIDGE_RELEASE_PROJECT_ROOT}" \
        LANG=C LC_CTYPE=UTF-8 "${java_path}" "$@"
}

release_trusted_executable() {
    local path=${1:-}
    release_regular_file "${path}" || return 1
    [[ -x "${path}" ]] || {
        release_fail '신뢰할 수 있는 release 검증 도구를 실행할 수 없습니다.'
        return 1
    }
    release_private_file "${path}" || return 1
}

release_system_executable() {
    local path=${1:-} owner mode links
    release_regular_file "${path}" || return 1
    [[ -x "${path}" ]] || {
        release_fail '신뢰할 수 있는 system release 도구를 실행할 수 없습니다.'
        return 1
    }
    owner=$(release_stat_value '%u' "${path}") || return 1
    [[ "${owner}" = 0 ]] || {
        release_fail 'system release 도구 owner가 root가 아닙니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        release_fail 'system release 도구가 group/other write를 허용합니다.'
        return 1
    }
    links=$(release_link_count "${path}") || return 1
    [[ "${links}" = 1 ]] || {
        # Apple ships selected sealed-system tools as hardlinks. The exception
        # is deliberately limited to immutable absolute paths used only for
        # discovery/digest work; arbitrary system hardlinks remain rejected.
        if [[ "$(/usr/bin/uname -s)" = Darwin ]]; then
            case "${path}" in
                /usr/bin/shasum|/usr/bin/git|/usr/bin/keytool) return 0 ;;
            esac
        fi
        release_fail 'system release 도구에 hardlink가 포함되어 있습니다.'
        return 1
    }
}

release_trusted_ancestor_directory() {
    local path=${1:-} expected_owner=${2:-} owner mode
    release_directory "${path}" || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    [[ "${expected_owner}" =~ ^[0-9]+$ && "${owner}" = "${expected_owner}" ]] || {
        release_fail 'trusted ancestor directory owner가 올바르지 않습니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        release_fail 'trusted ancestor directory가 group/other write를 허용합니다.'
        return 1
    }
    release_link_count "${path}" >/dev/null
}

release_system_directory() {
    release_trusted_ancestor_directory "${1:-}" 0 || {
        release_fail 'system directory trust 검증에 실패했습니다.'
        return 1
    }
}

release_system_perl_directory() {
    local path=$1 physical parent
    case "${path}" in /System/Library/Perl/*) ;; *) release_fail 'system Perl library가 sealed system 밖에 있습니다.'; return 1 ;; esac
    [[ -d "${path}" && ! -L "${path}" ]] || { release_fail 'system Perl library directory 형식이 올바르지 않습니다.'; return 1; }
    physical="$(CDPATH= cd -P -- "${path}" && pwd -P)" || return 1
    case "${physical}" in /System/Library/Perl/*) ;; *) release_fail 'system Perl library canonical containment가 올바르지 않습니다.'; return 1 ;; esac
    parent="${physical}"
    while [[ "${parent}" != /System ]]; do
        release_system_directory "${parent}" || return 1
        parent=$(/usr/bin/dirname -- "${parent}")
    done
}

release_parse_system_perl_contract() {
    local output=$1 perl privlib archlib
    IFS='|' read -r perl privlib archlib <<<"${output}"
    [[ "${perl}" = /usr/bin/perl && -n "${privlib}" && -n "${archlib}" && "${output}" != *$'\n'* ]] || {
        release_fail 'system Perl library 출력 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s|%s|%s\n' "${perl}" "${privlib}" "${archlib}"
}

release_system_perl() {
    local output perl privlib archlib
    release_system_executable /usr/bin/perl || return 1
    output=$(/usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        /usr/bin/perl -MConfig -e 'print "$^X|$Config{privlib}|$Config{archlib}\n";') || {
        release_fail 'system Perl library 경로를 읽지 못했습니다.'
        return 1
    }
    output=$(release_parse_system_perl_contract "${output}") || return 1
    IFS='|' read -r perl privlib archlib <<<"${output}"
    release_system_perl_directory "${privlib}" || return 1
    release_system_perl_directory "${archlib}" || return 1
    printf '%s|%s|%s\n' "${perl}" "${privlib}" "${archlib}"
}

release_darwin_atomic_publish_preflight() {
    local kernel_major
    release_system_executable /usr/bin/uname || return 1
    [[ "$(/usr/bin/uname -s)" = Darwin ]] || {
        release_fail 'atomic no-replace publish는 Darwin renameatx_np가 필요합니다.'
        return 1
    }
    kernel_major=$(/usr/bin/uname -r | /usr/bin/cut -d. -f1) || return 1
    [[ "${kernel_major}" =~ ^[0-9]+$ && "${kernel_major}" -ge 22 ]] || {
        release_fail 'atomic no-replace publish는 macOS 13 이상이 필요합니다.'
        return 1
    }
}

release_private_file() {
    local path=${1:-} owner mode current_owner links
    release_regular_file "${path}" || return 1
    owner=$(release_stat_value '%u' "${path}") || return 1
    current_owner=$(/usr/bin/id -u) || return 1
    [[ "${owner}" = "${current_owner}" ]] || {
        release_fail 'trusted file owner가 현재 사용자와 다릅니다.'
        return 1
    }
    mode=$(release_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        release_fail 'trusted file은 group/other write를 허용하면 안 됩니다.'
        return 1
    }
    links=$(release_link_count "${path}") || return 1
    [[ "${links}" = 1 ]] || {
        release_fail 'trusted file에 hardlink가 포함되어 있습니다.'
        return 1
    }
}

release_validate_private_descendant_file() {
    local parent=$1 path=$2 physical_parent physical_path ancestor
    release_trusted_directory "${parent}" || return 1
    release_private_file "${path}" || return 1
    physical_parent="$(CDPATH= builtin cd -P -- "${parent}" && builtin pwd -P)"
    physical_path="$(CDPATH= builtin cd -P -- "$(/usr/bin/dirname -- "${path}")" && builtin pwd -P)/$(/usr/bin/basename -- "${path}")"
    case "${physical_path}" in
        "${physical_parent}"/*) ;;
        *) release_fail 'Android SDK file이 canonical parent 밖에 있습니다.'; return 1 ;;
    esac
    ancestor="$(/usr/bin/dirname -- "${physical_path}")"
    while [[ "${ancestor}" != "${physical_parent}" ]]; do
        release_trusted_directory "${ancestor}" || return 1
        ancestor="$(/usr/bin/dirname -- "${ancestor}")"
    done
}

release_validate_android_classpath() {
    local lib=$1 scan_parent scan_dir jars_file links_file jar status=0
    release_trusted_directory "${lib}" || return 1
    scan_parent="${SIXBRIDGE_RELEASE_PROJECT_ROOT}/.local"
    release_trusted_directory "${scan_parent}" || return 1
    scan_dir=$(/usr/bin/mktemp -d "${scan_parent}/.sixbridge-sdk-classpath.XXXXXX") || {
        release_fail 'Android SDK classpath 임시 directory를 만들지 못했습니다.'
        return 1
    }
    release_private_directory "${scan_dir}" || { /bin/rm -rf "${scan_dir}"; return 1; }
    jars_file="${scan_dir}/jars.nul"
    links_file="${scan_dir}/links.nul"
    if ! /usr/bin/find "${lib}" -xdev -type l -print0 >"${links_file}"; then
        /bin/rm -rf "${scan_dir}"
        release_fail 'Android SDK classpath 열거에 실패했습니다.'
        return 1
    fi
    if [[ -s "${links_file}" ]]; then
        /bin/rm -rf "${scan_dir}"
        release_fail 'Android SDK classpath에 symbolic link가 포함되어 있습니다.'
        return 1
    fi
    if ! /usr/bin/find "${lib}" -xdev -type f -name '*.jar' -print0 >"${jars_file}"; then
        /bin/rm -rf "${scan_dir}"
        release_fail 'Android SDK classpath 열거에 실패했습니다.'
        return 1
    fi
    while IFS= read -r -d '' jar; do
        release_validate_private_descendant_file "${lib}" "${jar}" || { status=1; break; }
    done <"${jars_file}"
    /bin/rm -rf "${scan_dir}"
    return "${status}"
}

release_validate_android_sdk() {
    local root=$1 revision_file revision='' apksigner_lib apksigner_jar apkanalyzer_lib apkanalyzer_jar
    release_trusted_directory "${root}" || return 1
    release_trusted_directory "${root}/build-tools" || return 1
    release_trusted_directory "${root}/build-tools/36.0.0" || return 1
    revision_file="${root}/build-tools/36.0.0/source.properties"
    release_validate_private_descendant_file "${root}/build-tools/36.0.0" "${revision_file}" || return 1
    while IFS= read -r revision || [[ -n "${revision}" ]]; do
        [[ "${revision}" = 'Pkg.Revision=36.0.0' ]] && break
    done <"${revision_file}"
    [[ "${revision}" = 'Pkg.Revision=36.0.0' ]] || {
        release_fail 'Android SDK build-tools 36.0.0 revision 계약이 올바르지 않습니다.'
        return 1
    }
    apksigner_lib="${root}/build-tools/36.0.0/lib"
    release_trusted_directory "${apksigner_lib}" || return 1
    apksigner_jar="${apksigner_lib}/apksigner.jar"
    release_validate_private_descendant_file "${apksigner_lib}" "${apksigner_jar}" || return 1
    release_trusted_directory "${root}/cmdline-tools" || return 1
    release_trusted_directory "${root}/cmdline-tools/latest" || return 1
    apkanalyzer_lib="${root}/cmdline-tools/latest/lib"
    release_trusted_directory "${apkanalyzer_lib}" || return 1
    apkanalyzer_jar="${apkanalyzer_lib}/apkanalyzer-classpath.jar"
    release_validate_private_descendant_file "${apkanalyzer_lib}" "${apkanalyzer_jar}" || return 1
    release_validate_android_classpath "${apkanalyzer_lib}"
}

release_run_apksigner() {
    local sdk_root jar
    sdk_root=$(release_android_sdk_root) || return 1
    release_validate_android_sdk "${sdk_root}" || return 1
    jar="${sdk_root}/build-tools/36.0.0/lib/apksigner.jar"
    release_run_java -Xmx1024M -jar "${jar}" "$@"
}

release_run_apkanalyzer() {
    local sdk_root jar
    sdk_root=$(release_android_sdk_root) || return 1
    release_validate_android_sdk "${sdk_root}" || return 1
    jar="${sdk_root}/cmdline-tools/latest/lib/apkanalyzer-classpath.jar"
    release_run_java "-Dcom.android.sdklib.toolsdir=${sdk_root}/cmdline-tools/latest" -classpath "${jar}" com.android.tools.apk.analyzer.ApkAnalyzerCli "$@"
}

release_tool() {
    local name=$1 sdk_root path
    case "${name}" in
        apksigner|apkanalyzer)
            sdk_root=$(release_android_sdk_root) || return 1
            release_validate_android_sdk "${sdk_root}" || return 1
            [[ "${name}" = apksigner ]] && path="${sdk_root}/build-tools/36.0.0/lib/apksigner.jar" || path="${sdk_root}/cmdline-tools/latest/lib/apkanalyzer-classpath.jar"
            ;;
        hdiutil) path='/usr/bin/hdiutil' ;;
        plistbuddy) path='/usr/libexec/PlistBuddy' ;;
        codesign) path='/usr/bin/codesign' ;;
        jq) path='/usr/bin/jq' ;;
        shasum) path='/usr/bin/shasum' ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
    case "${name}" in
        apksigner|apkanalyzer) release_private_file "${path}" || return 1 ;;
        *) release_system_executable "${path}" || return 1 ;;
    esac
    printf '%s\n' "${path}"
}

release_verify_apk() {
    local apk=${1:-} expected_signer=${2:-} stage_root=${3:-} container=${4:-}
    local raw_output signer_lines actual_signer application_id version_name version_code apksigner_output
    local artifact_identity snapshot_identity snapshot_dir snapshot stage_identity container_identity

    release_require_artifact_stage "${apk}" "${stage_root}" "${container}" || return 1
    release_require_single_link_file "${apk}" || return 1
    [[ "${expected_signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'expected signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    artifact_identity=$(release_file_identity "${apk}") || return 1
    snapshot_dir=$(release_create_verification_snapshot "${apk}" "${container}") || return 1
    if declare -F release_register_owned_work_directory >/dev/null; then
        release_register_owned_work_directory "${snapshot_dir}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    fi
    snapshot="${snapshot_dir}/artifact"
    snapshot_identity=$(release_file_identity "${snapshot}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    stage_identity=$(release_stage_identity "${stage_root}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    container_identity=$(release_container_identity "${container}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }

    apksigner_output=$(/usr/bin/mktemp "${TMPDIR:-/tmp}/sixbridge-release-apksigner.XXXXXX") || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK verifier output 임시 파일을 만들지 못했습니다.'
        return 1
    }
    if declare -F run_release_step >/dev/null; then
        if ! run_release_step release_run_apksigner verify --verbose --print-certs "${snapshot}" >"${apksigner_output}" 2>&1; then
            /bin/rm -f "${apksigner_output}"
            release_remove_verification_snapshot "${snapshot_dir}"
            release_fail 'APK signature verification에 실패했습니다.'
            return 1
        fi
        raw_output=$(/bin/cat "${apksigner_output}")
    elif ! raw_output=$(release_run_apksigner verify --verbose --print-certs "${snapshot}" 2>&1); then
        /bin/rm -f "${apksigner_output}"
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK signature verification에 실패했습니다.'
        return 1
    fi
    /bin/rm -f "${apksigner_output}"
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_file_identity "${apk}" "${artifact_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    signer_lines=$(printf '%s\n' "${raw_output}" | /usr/bin/awk -F': ' '/Signer #[0-9]+ certificate SHA-256 digest:/ {print $2}')
    [[ "$(printf '%s\n' "${signer_lines}" | /usr/bin/awk 'NF {count += 1} END {print count + 0}')" = 1 ]] || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK signer는 정확히 하나여야 합니다.'
        return 1
    }
    actual_signer=${signer_lines}
    actual_signer=$(printf '%s' "${actual_signer}" | /usr/bin/tr '[:upper:]' '[:lower:]')
    [[ "${actual_signer}" = "${expected_signer}" ]] || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK signer SHA-256가 예상 값과 다릅니다.'
        return 1
    }

    if ! application_id=$(release_run_apkanalyzer manifest application-id "${snapshot}" 2>&1); then
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK application ID를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_file_identity "${apk}" "${artifact_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    [[ "${application_id}" = 'dev.sixbridge.manager' ]] || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK application ID가 계약과 다릅니다.'
        return 1
    }
    if ! version_name=$(release_run_apkanalyzer manifest version-name "${snapshot}" 2>&1); then
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK versionName을 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_file_identity "${apk}" "${artifact_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    [[ "${version_name}" = "${SIXBRIDGE_RELEASE_VERSION}" ]] || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK versionName이 계약과 다릅니다.'
        return 1
    }
    if ! version_code=$(release_run_apkanalyzer manifest version-code "${snapshot}" 2>&1); then
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK versionCode를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_file_identity "${apk}" "${artifact_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    [[ "${version_code}" = '1' ]] || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'APK versionCode가 계약과 다릅니다.'
        return 1
    }
    release_remove_verification_snapshot "${snapshot_dir}" || return 1
    release_assert_file_identity "${apk}" "${artifact_identity}"
}

release_dmg_detach() {
    local mountpoint=$1 hdiutil
    hdiutil=$(release_tool hdiutil) || return 1
    if ! "${hdiutil}" detach "${mountpoint}" >/dev/null 2>&1; then
        release_fail 'DMG mount detach에 실패했습니다.'
        return 1
    fi
    /bin/rmdir "${mountpoint}" 2>/dev/null || true
}

release_dmg_cleanup() {
    local mountpoint=${1:-} snapshot_dir=${2:-} attached=${3:-0} status=0
    if [[ "${attached}" = 1 ]]; then
        release_dmg_detach "${mountpoint}" || status=1
    else
        /bin/rmdir "${mountpoint}" 2>/dev/null || true
    fi
    release_remove_verification_snapshot "${snapshot_dir}" || status=1
    return "${status}"
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
    local dmg=${1:-} work_dir=${2:-} stage_root=${3:-} container=${4:-}
    local mountpoint app_path plist raw_output bundle_id product_version native_version minimum_version
    local hdiutil codesign dmg_identity snapshot_identity snapshot_dir snapshot stage_identity container_identity app_candidate app_count=0

    release_require_artifact_stage "${dmg}" "${stage_root}" "${container}" || return 1
    release_require_single_link_file "${dmg}" || return 1
    release_validate_private_work_directory "${work_dir}" "${container}" || return 1
    hdiutil=$(release_tool hdiutil) || return 1
    release_tool plistbuddy >/dev/null || return 1
    codesign=$(release_tool codesign) || return 1
    dmg_identity=$(release_file_identity "${dmg}") || return 1
    snapshot_dir=$(release_create_verification_snapshot "${dmg}" "${container}" "${work_dir}") || return 1
    if declare -F release_register_owned_work_directory >/dev/null; then
        release_register_owned_work_directory "${snapshot_dir}" || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    fi
    snapshot="${snapshot_dir}/artifact"
    snapshot_identity=$(release_file_identity "${snapshot}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    stage_identity=$(release_stage_identity "${stage_root}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }
    container_identity=$(release_container_identity "${container}") || { release_remove_verification_snapshot "${snapshot_dir}"; return 1; }

    mountpoint=$(/usr/bin/mktemp -d "${work_dir}/sixbridge-dmg.XXXXXX") || {
        release_remove_verification_snapshot "${snapshot_dir}"
        release_fail 'DMG mount directory를 만들지 못했습니다.'
        return 1
    }
    if ! raw_output=$("${hdiutil}" attach -readonly -nobrowse -mountpoint "${mountpoint}" "${snapshot}" 2>&1); then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG read-only mount에 실패했습니다.'
        return 1
    fi
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    release_assert_file_identity "${dmg}" "${dmg_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }

    app_path="${mountpoint}/SIX Bridge.app"
    while IFS= read -r -d '' app_candidate; do
        [[ -d "${app_candidate}" && ! -L "${app_candidate}" ]] || continue
        app_count=$((app_count + 1))
    done < <(/usr/bin/find "${mountpoint}" -mindepth 1 -maxdepth 1 -name '*.app' -print0)
    if [[ "${app_count}" != 1 || -L "${app_path}" || ! -d "${app_path}" ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG app bundle이 정확히 하나여야 합니다.'
        return 1
    fi
    plist="${app_path}/Contents/Info.plist"
    if [[ -L "${plist}" || ! -f "${plist}" ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG Info.plist가 없습니다.'
        return 1
    fi
    bundle_id=$(release_dmg_value "${plist}" CFBundleIdentifier) || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    product_version=$(release_dmg_value "${plist}" CFBundleShortVersionString) || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    native_version=$(release_dmg_value "${plist}" CFBundleVersion) || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    minimum_version=$(release_dmg_value "${plist}" LSMinimumSystemVersion) || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    if [[ "${bundle_id}" != 'dev.sixbridge.desktop' ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG bundle ID가 계약과 다릅니다.'
        return 1
    fi
    if [[ "${product_version}" != "${SIXBRIDGE_RELEASE_VERSION}" ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG product version이 계약과 다릅니다.'
        return 1
    fi
    if [[ "${native_version}" != '1.0.0' ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG native package version이 계약과 다릅니다.'
        return 1
    fi
    if [[ "${minimum_version}" != '13.0' ]]; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG minimum macOS version이 계약과 다릅니다.'
        return 1
    fi
    if raw_output=$("${codesign}" -dv --verbose=4 "${app_path}" 2>&1); then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'DMG는 v0.1.0에서 미서명이어야 합니다.'
        return 1
    fi
    if ! printf '%s\n' "${raw_output}" | /usr/bin/grep -Fq 'not signed at all'; then
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        release_fail 'codesign이 미서명 상태를 확인하지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${snapshot}" "${snapshot_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    release_assert_file_identity "${dmg}" "${dmg_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || {
        release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || true
        return 1
    }
    release_dmg_cleanup "${mountpoint}" "${snapshot_dir}" 1 || return 1
    release_assert_file_identity "${dmg}" "${dmg_identity}"
}

release_file_contains_secret() {
    local path=${1:-} secret=${2:-} expected_identity=${3:-}
    local perl_contract perl privlib archlib scanner_status
    [[ -n "${secret}" && -n "${expected_identity}" ]] || {
        release_fail 'binary secret scanner 입력이 비어 있습니다.'
        return 2
    }
    perl_contract=$(release_system_perl) || return 2
    IFS='|' read -r perl privlib archlib <<<"${perl_contract}"
    exec 7< <(printf '%s' "${secret}")
    if /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        SIXBRIDGE_PERL_INC="${privlib}:${archlib}" \
        "${perl}" -e '
            BEGIN { @INC = split(/:/, $ENV{SIXBRIDGE_PERL_INC}); }
            use strict;
            use warnings;
            use Digest::SHA;
            use Fcntl qw(O_RDONLY O_NONBLOCK O_NOFOLLOW S_ISREG);
            my ($path, $expected) = @ARGV;
            defined($path) && length($path) or exit 2;
            defined($expected) && length($expected) or exit 2;
            open(my $secret_fh, q{<&=7}) or exit 2;
            binmode($secret_fh);
            local $/;
            my $secret = <$secret_fh>;
            defined($secret) && length($secret) or exit 2;
            close($secret_fh) or exit 2;
            sysopen(my $artifact_fh, $path,
                O_RDONLY | O_NONBLOCK | O_NOFOLLOW) or exit 2;
            binmode($artifact_fh);
            my @before = stat($artifact_fh);
            @before && S_ISREG($before[2]) or exit 2;
            my ($expected_device, $expected_inode, $expected_size,
                $expected_links, $expected_digest) = split(/:/, $expected, 5);
            defined($expected_digest) &&
                $expected_digest =~ /\A[0-9a-f]{64}\z/ or exit 2;
            join(q{:}, @before[0,1,7,3]) eq
                join(q{:}, $expected_device, $expected_inode,
                    $expected_size, $expected_links) or exit 2;
            my $sha = Digest::SHA->new(256);
            my $tail = q{};
            my $found = 0;
            my $total = 0;
            while (1) {
                my $read = read($artifact_fh, my $chunk, 65536);
                defined($read) or exit 2;
                last if $read == 0;
                $total += $read;
                $sha->add($chunk);
                my $window = $tail . $chunk;
                $found = 1 if index($window, $secret) >= 0;
                my $overlap = length($secret) - 1;
                if ($overlap == 0) {
                    $tail = q{};
                } elsif (length($window) > $overlap) {
                    $tail = substr($window, -$overlap);
                } else {
                    $tail = $window;
                }
            }
            my @after = stat($artifact_fh);
            @after or exit 2;
            close($artifact_fh) or exit 2;
            my $identity = join(q{:}, $before[0], $before[1], $before[7],
                $before[3], $sha->hexdigest);
            $identity eq $expected or exit 2;
            $total == $before[7] or exit 2;
            join(q{:}, @before[0,1,7,3,4,2,10]) eq
                join(q{:}, @after[0,1,7,3,4,2,10]) or exit 2;
            exit($found ? 0 : 1);
        ' "${path}" "${expected_identity}"; then
        scanner_status=0
    else
        scanner_status=$?
    fi
    exec 7<&-
    case "${scanner_status}" in
        0) return 0 ;;
        1) return 1 ;;
        *) release_fail 'binary secret scanner가 artifact identity를 검증하지 못했습니다.'; return 2 ;;
    esac
}

release_validate_private_work_directory() {
    local work_dir=${1:-} container=${2:-} physical_work physical_container
    release_validate_release_container "${container}" || return 1
    release_private_directory "${work_dir}" || return 1
    physical_work="$(CDPATH= builtin cd -P -- "${work_dir}" && builtin pwd -P)"
    physical_container="$(CDPATH= builtin cd -P -- "${container}" && builtin pwd -P)"
    case "${physical_work}/" in
        "${physical_container}/"*) ;;
        *) release_fail 'DMG work directory가 trusted container 밖에 있습니다.'; return 1 ;;
    esac
}

release_scan_tree() {
    local root=${1:-} stage_root=${2:-} container=${3:-} secret_probe=${4:-}
    local physical_root entries entry basename lower file_identity stage_identity container_identity scanner_status
    release_validate_stage_root "${stage_root}" "${container}" || return 1
    release_directory "${root}" || return 1
    physical_root="$(CDPATH= builtin cd -P -- "${root}" && builtin pwd -P)"
    case "${physical_root}" in "$(CDPATH= builtin cd -P -- "${stage_root}" && builtin pwd -P)"/*|"$(CDPATH= builtin cd -P -- "${stage_root}" && builtin pwd -P)") ;; *) release_fail 'release tree가 private stage root 밖에 있습니다.'; return 1;; esac
    stage_identity=$(release_stage_identity "${stage_root}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
    if ! entries=$(/usr/bin/find "${physical_root}" -xdev -print 2>&1); then
        release_fail 'release tree를 순회하지 못했습니다.'
        return 1
    fi
    while IFS= read -r entry; do
        [[ -n "${entry}" ]] || continue
        basename=$(/usr/bin/basename -- "${entry}")
        lower=$(printf '%s' "${basename}" | /usr/bin/tr '[:upper:]' '[:lower:]')
        case "${lower}" in
            *.p12|*.jks|*.pem|*.pk8|*.six|*.sixdraft|*.crx|*.zip)
                release_fail 'forbidden release input이 포함되어 있습니다.'
                return 1
                ;;
        esac
        [[ ! -L "${entry}" ]] || {
            release_fail 'release tree에 symbolic link가 포함되어 있습니다.'
            return 1
        }
        if [[ "${basename}" = '.local' ]]; then
            release_fail 'forbidden release input이 포함되어 있습니다.'
            return 1
        fi
        if [[ -d "${entry}" ]]; then
            continue
        fi
        release_require_single_link_file "${entry}" || return 1
        file_identity=$(release_file_identity "${entry}") || return 1
        if [[ -n "${secret_probe}" ]]; then
            if release_file_contains_secret "${entry}" "${secret_probe}" "${file_identity}"; then
                release_fail '비밀정보가 release tree에 포함되어 있습니다.'
                return 1
            else
                scanner_status=$?
                [[ "${scanner_status}" = 1 ]] || return 1
            fi
        fi
        release_assert_file_identity "${entry}" "${file_identity}" || return 1
    done <<<"${entries}"
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}"
}

release_atomic_publish_file() {
    local temp_file=$1 destination=$2
    [[ ! -e "${destination}" && ! -L "${destination}" ]] || {
        /bin/rm -f "${temp_file}"
        release_fail 'release output이 이미 존재합니다.'
        return 1
    }
    if ! /bin/ln "${temp_file}" "${destination}"; then
        /bin/rm -f "${temp_file}"
        release_fail 'release output을 no-replace 방식으로 publish하지 못했습니다.'
        return 1
    fi
    /bin/rm -f "${temp_file}"
}

release_write_metadata() {
    local directory=${1:-} git_commit=${2:-} signer=${3:-} container=${4:-}
    local apk dmg note target temp_file android_sha macos_sha
    local jq apk_identity dmg_identity note_identity stage_identity container_identity
    release_directory "${directory}" || return 1
    release_validate_stage_root "${directory}" "${container}" || return 1
    stage_identity=$(release_stage_identity "${directory}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
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
    temp_file=$(/usr/bin/mktemp "${directory}/.release-metadata.XXXXXX") || {
        release_fail 'release metadata 임시 파일을 만들지 못했습니다.'
        return 1
    }
    stage_identity=$(release_stage_identity "${directory}") || { /bin/rm -f "${temp_file}"; return 1; }
    container_identity=$(release_container_identity "${container}") || { /bin/rm -f "${temp_file}"; return 1; }
    if ! "${jq}" -S -n \
        --arg git_commit "${git_commit}" \
        --arg signer "${signer}" \
        --arg android_sha "${android_sha}" \
        --arg macos_sha "${macos_sha}" \
        '{schemaVersion: 1, productVersion: "0.1.0", gitCommit: $git_commit,
          android: {file: "SIX-Bridge-Manager-0.1.0-android.apk", applicationId: "dev.sixbridge.manager", versionName: "0.1.0", versionCode: 1, signerSha256: $signer, sha256: $android_sha},
          macos: {file: "SIX-Bridge-0.1.0-macos-unsigned.dmg", bundleId: "dev.sixbridge.desktop", productVersion: "0.1.0", nativePackageVersion: "1.0.0", minimumSystemVersion: "13.0", signed: false, notarized: false, sha256: $macos_sha}}' >"${temp_file}"; then
        /bin/rm -f "${temp_file}"
        release_fail 'release metadata를 만들지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${apk}" "${apk_identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    release_assert_file_identity "${dmg}" "${dmg_identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    release_assert_file_identity "${note}" "${note_identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    release_assert_stage_identity "${directory}" "${container}" "${stage_identity}" "${container_identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    release_atomic_publish_file "${temp_file}" "${target}"
}

release_write_checksums() {
    local directory=${1:-} container=${2:-} target temp_file file digest contents=''
    local identities='' identity stage_identity container_identity
    local checksum_files=(
        "${SIXBRIDGE_ANDROID_FILE}"
        "${SIXBRIDGE_MACOS_FILE}"
        'RELEASE-METADATA.json'
        'RELEASE-NOTES-KO.md'
    )
    release_directory "${directory}" || return 1
    release_validate_stage_root "${directory}" "${container}" || return 1
    stage_identity=$(release_stage_identity "${directory}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
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
    temp_file=$(/usr/bin/mktemp "${directory}/.release-checksums.XXXXXX") || {
        release_fail 'checksum 임시 파일을 만들지 못했습니다.'
        return 1
    }
    stage_identity=$(release_stage_identity "${directory}") || { /bin/rm -f "${temp_file}"; return 1; }
    container_identity=$(release_container_identity "${container}") || { /bin/rm -f "${temp_file}"; return 1; }
    while IFS='|' read -r file identity; do
        [[ -n "${file}" ]] || continue
        release_assert_file_identity "${directory}/${file}" "${identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    done <<<"${identities}"
    release_assert_stage_identity "${directory}" "${container}" "${stage_identity}" "${container_identity}" || { /bin/rm -f "${temp_file}"; return 1; }
    printf '%s' "${contents}" >"${temp_file}" || {
        /bin/rm -f "${temp_file}"
        release_fail 'SHA256SUMS를 만들지 못했습니다.'
        return 1
    }
    release_atomic_publish_file "${temp_file}" "${target}"
}

release_verify_metadata() {
    local directory=$1 expected_signer=$2 stage_root=$3 container=$4 metadata raw_output android_sha macos_sha jq metadata_identity apk_identity dmg_identity stage_identity container_identity
    release_validate_stage_root "${stage_root}" "${container}" || return 1
    metadata="${directory}/RELEASE-METADATA.json"
    release_require_single_link_file "${metadata}" || return 1
    metadata_identity=$(release_file_identity "${metadata}") || return 1
    apk_identity=$(release_file_identity "${directory}/${SIXBRIDGE_ANDROID_FILE}") || return 1
    dmg_identity=$(release_file_identity "${directory}/${SIXBRIDGE_MACOS_FILE}") || return 1
    stage_identity=$(release_stage_identity "${stage_root}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
    jq=$(release_tool jq) || return 1
    if ! raw_output=$("${jq}" -er --arg signer "${expected_signer}" '
      type == "object" and
      (keys | sort) == ["android", "gitCommit", "macos", "productVersion", "schemaVersion"] and
      .schemaVersion == 1 and .productVersion == "0.1.0" and
      (.gitCommit | test("^[0-9a-f]{40}$")) and
      (.android | type == "object" and
        (keys | sort) == ["applicationId", "file", "sha256", "signerSha256", "versionCode", "versionName"] and
        .file == "SIX-Bridge-Manager-0.1.0-android.apk" and
        .applicationId == "dev.sixbridge.manager" and .versionName == "0.1.0" and
        .versionCode == 1 and .signerSha256 == $signer and
        (.sha256 | test("^[0-9a-f]{64}$"))) and
      (.macos | type == "object" and
        (keys | sort) == ["bundleId", "file", "minimumSystemVersion", "nativePackageVersion", "notarized", "productVersion", "sha256", "signed"] and
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
    release_assert_file_identity "${directory}/${SIXBRIDGE_ANDROID_FILE}" "${apk_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_MACOS_FILE}" "${dmg_identity}" || return 1
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || return 1
    android_sha=$(release_sha256 "${directory}/${SIXBRIDGE_ANDROID_FILE}") || return 1
    macos_sha=$(release_sha256 "${directory}/${SIXBRIDGE_MACOS_FILE}") || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_ANDROID_FILE}" "${apk_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_MACOS_FILE}" "${dmg_identity}" || return 1
    if ! raw_output=$("${jq}" -er '.android.sha256' "${metadata}" 2>&1); then
        release_fail 'Android metadata SHA-256를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${metadata}" "${metadata_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_ANDROID_FILE}" "${apk_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_MACOS_FILE}" "${dmg_identity}" || return 1
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || return 1
    [[ "${raw_output}" = "${android_sha}" ]] || {
        release_fail 'Android metadata SHA-256가 다릅니다.'
        return 1
    }
    if ! raw_output=$("${jq}" -er '.macos.sha256' "${metadata}" 2>&1); then
        release_fail 'macOS metadata SHA-256를 읽지 못했습니다.'
        return 1
    fi
    release_assert_file_identity "${metadata}" "${metadata_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_ANDROID_FILE}" "${apk_identity}" || return 1
    release_assert_file_identity "${directory}/${SIXBRIDGE_MACOS_FILE}" "${dmg_identity}" || return 1
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}" || return 1
    [[ "${raw_output}" = "${macos_sha}" ]] || {
        release_fail 'macOS metadata SHA-256가 다릅니다.'
        return 1
    }
}

release_verify_checksums() {
    local directory=$1 stage_root=$2 container=$3 checksum_file expected='' expected_file file digest checksum_identity stage_identity container_identity input_identities='' identity
    local checksum_files=(
        "${SIXBRIDGE_ANDROID_FILE}"
        "${SIXBRIDGE_MACOS_FILE}"
        'RELEASE-METADATA.json'
        'RELEASE-NOTES-KO.md'
    )
    checksum_file="${directory}/SHA256SUMS"
    release_validate_stage_root "${stage_root}" "${container}" || return 1
    release_require_single_link_file "${checksum_file}" || return 1
    checksum_identity=$(release_file_identity "${checksum_file}") || return 1
    stage_identity=$(release_stage_identity "${stage_root}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
    for file in "${checksum_files[@]}"; do
        identity=$(release_file_identity "${directory}/${file}") || return 1
        input_identities="${input_identities}${file}|${identity}"$'\n'
        digest=$(release_sha256 "${directory}/${file}") || return 1
        expected="${expected}$(LC_ALL=C printf '%s  %s\n' "${digest}" "${file}")"$'\n'
    done
    # Do not create verifier-owned temporary files in the release tree: doing
    # so would modify the stage directory identity that this function checks.
    expected_file=$(/usr/bin/mktemp "${TMPDIR:-/tmp}/sixbridge-release-checksums.XXXXXX") || {
        release_fail 'checksum 비교 임시 파일을 만들지 못했습니다.'
        return 1
    }
    printf '%s' "${expected}" >"${expected_file}" || {
        /bin/rm -f "${expected_file}"
        release_fail 'checksum 비교 데이터를 만들지 못했습니다.'
        return 1
    }
    if ! /usr/bin/cmp -s "${checksum_file}" "${expected_file}"; then
        /bin/rm -f "${expected_file}"
        release_fail 'SHA256SUMS가 release 산출물과 다릅니다.'
        return 1
    fi
    /bin/rm -f "${expected_file}"
    while IFS='|' read -r file identity; do
        [[ -n "${file}" ]] || continue
        release_assert_file_identity "${directory}/${file}" "${identity}" || return 1
    done <<<"${input_identities}"
    release_assert_file_identity "${checksum_file}" "${checksum_identity}" || return 1
    release_assert_stage_identity "${stage_root}" "${container}" "${stage_identity}" "${container_identity}"
}

release_verify_directory() {
    local directory=${1:-} expected_signer=${2:-} container=${3:-}
    local physical_directory entries entry basename count=0 work_dir stage_identity container_identity
    release_validate_stage_root "${directory}" "${container}" || return 1
    [[ "${expected_signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'expected signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    physical_directory="$(CDPATH= builtin cd -P -- "${directory}" && builtin pwd -P)"
    stage_identity=$(release_stage_identity "${directory}") || return 1
    container_identity=$(release_container_identity "${container}") || return 1
    if ! entries=$(/usr/bin/find "${physical_directory}" -maxdepth 1 -mindepth 1 -print 2>&1); then
        release_fail 'release directory를 읽지 못했습니다.'
        return 1
    fi
    while IFS= read -r entry; do
        [[ -n "${entry}" ]] || continue
        basename=$(/usr/bin/basename -- "${entry}")
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
    release_scan_tree "${physical_directory}" "${directory}" "${container}" || return 1
    release_verify_checksums "${physical_directory}" "${directory}" "${container}" || return 1
    release_verify_metadata "${physical_directory}" "${expected_signer}" "${directory}" "${container}" || return 1
    release_verify_apk "${physical_directory}/${SIXBRIDGE_ANDROID_FILE}" "${expected_signer}" "${directory}" "${container}" || return 1
    work_dir=$(/usr/bin/mktemp -d "${container}/.sixbridge-release-verify.XXXXXX") || {
        release_fail 'DMG verification work directory를 만들지 못했습니다.'
        return 1
    }
    release_private_directory "${work_dir}" || { /bin/rm -rf "${work_dir}"; return 1; }
    if declare -F release_register_owned_work_directory >/dev/null; then
        release_register_owned_work_directory "${work_dir}" || { /bin/rm -rf "${work_dir}"; return 1; }
    fi
    stage_identity=$(release_stage_identity "${directory}") || { /bin/rm -rf "${work_dir}"; return 1; }
    container_identity=$(release_container_identity "${container}") || { /bin/rm -rf "${work_dir}"; return 1; }
    if ! release_verify_dmg "${physical_directory}/${SIXBRIDGE_MACOS_FILE}" "${work_dir}" "${directory}" "${container}"; then
        /bin/rm -rf "${work_dir}"
        return 1
    fi
    /bin/rm -rf "${work_dir}"
    release_assert_stage_identity "${directory}" "${container}" "${stage_identity}" "${container_identity}"
}
