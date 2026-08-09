#!/usr/bin/env bash
set -euo pipefail

ROOT_INPUT="${SIXBRIDGE_RELEASE_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)}"
[[ -d "${ROOT_INPUT}" && ! -L "${ROOT_INPUT}" ]] || {
    printf 'release 검증 실패: release root는 symbolic link가 아닌 directory여야 합니다.\n' >&2
    exit 1
}
ROOT_DIR="$(CDPATH= cd -P -- "${ROOT_INPUT}" && pwd -P)"
readonly ROOT_DIR

# shellcheck source=release-lib.sh
source "${ROOT_DIR}/scripts/release-lib.sh"

readonly KEYSTORE="${ROOT_DIR}/.local/keystore/six-bridge-manager-release.p12"
readonly RELEASE_NOTES="${ROOT_DIR}/docs/releases/v0.1.0-ko.md"
readonly RELEASE_CONTAINER="${ROOT_DIR}/.local/releases"
readonly FINAL_DIR="${RELEASE_CONTAINER}/v0.1.0"
readonly LOCK_DIR="${RELEASE_CONTAINER}/.sixbridge-v0.1.0.lock"
readonly ANDROID_OUTPUT="${ROOT_DIR}/android-manager/build/outputs/apk/release/android-manager-release.apk"
readonly DMG_OUTPUT="${ROOT_DIR}/desktop-app/build/compose/binaries/main/dmg/SIX Bridge-1.0.0.dmg"

STAGE_DIR=''
STAGE_IDENTITY=''
CONTAINER_IDENTITY=''
LOCK_IDENTITY=''
OWNED_WORK_DIRECTORIES=()

stable_directory_identity() {
    local identity
    identity=$(release_stage_identity "$1") || return 1
    printf '%s\n' "${identity%:*}"
}

remove_owned_directory() {
    local path=$1 expected=$2 actual
    [[ -n "${path}" && -n "${expected}" ]] || return 0
    [[ -d "${path}" && ! -L "${path}" ]] || return 0
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 0
    actual=$(stable_directory_identity "${path}") || return 0
    [[ "${actual}" = "${expected}" ]] || return 0
    /bin/rm -rf -- "${path}"
}

release_register_owned_work_directory() {
    local path=$1 identity
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 1
    identity=$(stable_directory_identity "${path}") || return 1
    OWNED_WORK_DIRECTORIES+=("${path}|${identity}")
}

cleanup_owned_work_directories() {
    local record path identity
    for record in "${OWNED_WORK_DIRECTORIES[@]:-}"; do
        [[ -n "${record}" ]] || continue
        path=${record%%|*}
        identity=${record#*|}
        remove_owned_directory "${path}" "${identity}" || true
    done
}

cleanup_stage() {
    remove_owned_directory "${STAGE_DIR}" "${STAGE_IDENTITY%:*}" || true
    cleanup_owned_work_directories || true
    remove_owned_directory "${LOCK_DIR}" "${LOCK_IDENTITY}" || true
}
trap cleanup_stage EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

require_android_credentials() {
    local store_password=${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}
    local key_password=${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}
    [[ -n "${store_password}" ]] || {
        release_fail 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD를 설정하세요.'
        return 1
    }
    [[ -n "${key_password}" ]] || {
        release_fail 'SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD를 설정하세요.'
        return 1
    }
    [[ "${#store_password}" -ge 16 && "${#key_password}" -ge 16 ]] || {
        release_fail 'release key 비밀번호는 각각 16자 이상이어야 합니다.'
        return 1
    }
    [[ "${store_password}" = "${key_password}" ]] || {
        release_fail 'store 및 key 비밀번호는 동일해야 합니다.'
        return 1
    }
}

validate_keystore() {
    local keystore_dir physical_dir expected_dir mode
    release_trusted_directory "${ROOT_DIR}/.local" || return 1
    keystore_dir="${ROOT_DIR}/.local/keystore"
    release_private_directory "${keystore_dir}" || return 1
    physical_dir="$(CDPATH= cd -P -- "${keystore_dir}" && pwd -P)"
    expected_dir="${ROOT_DIR}/.local/keystore"
    [[ "${physical_dir}" = "${expected_dir}" ]] || {
        release_fail 'release keystore parent가 프로젝트 .local/keystore 밖을 가리킵니다.'
        return 1
    }
    release_private_file "${KEYSTORE}" || return 1
    mode=$(release_stat_value '%Lp' "${KEYSTORE}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#077)) = 0 ]] || {
        release_fail 'release keystore mode는 0600 상당이어야 합니다.'
        return 1
    }
}

release_keytool() {
    local java_path keytool_path
    java_path=$(release_java_executable) || return 1
    keytool_path="${java_path%/java}/keytool"
    release_trusted_executable "${keytool_path}" || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR=/tmp LANG=C LC_ALL=C \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD}" \
        "${keytool_path}" "$@"
}

release_git() {
    release_system_executable /usr/bin/git || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR=/tmp LANG=C LC_ALL=C \
        /usr/bin/git -C "${ROOT_DIR}" "$@"
}

release_clean_commit() {
    local status commit
    if ! status=$(release_git status --porcelain=v1 --untracked-files=all 2>&1); then
        release_fail 'git worktree 상태를 읽지 못했습니다.'
        return 1
    fi
    [[ -z "${status}" ]] || {
        release_fail 'release 준비는 clean git worktree에서만 실행할 수 있습니다.'
        return 1
    }
    if ! commit=$(release_git rev-parse HEAD 2>&1); then
        release_fail '현재 git commit을 읽지 못했습니다.'
        return 1
    fi
    [[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || {
        release_fail '현재 git commit 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${commit}"
}

read_signer_sha256() {
    local raw_output signer_line signer
    if ! raw_output=$(release_keytool -list -v -storetype PKCS12 -keystore "${KEYSTORE}" \
        -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
        -alias six-bridge-manager 2>&1); then
        release_fail 'keytool로 Android release signer를 읽지 못했습니다.'
        return 1
    fi
    signer_line=$(printf '%s\n' "${raw_output}" | /usr/bin/awk '/^[[:space:]]*SHA256:[[:space:]]*/ { print; found++ } END { if (found != 1) exit 1 }') || {
        release_fail 'keytool signer SHA-256 fingerprint가 정확히 하나여야 합니다.'
        return 1
    }
    signer=$(printf '%s' "${signer_line#*:}" | /usr/bin/tr -d '[:space:]:' | /usr/bin/tr '[:upper:]' '[:lower:]')
    [[ "${signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'keytool signer SHA-256 fingerprint 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${signer}"
}

create_release_container() {
    [[ ! -L "${ROOT_DIR}/.local" && -d "${ROOT_DIR}/.local" ]] || {
        release_fail '.local directory가 필요합니다.'
        return 1
    }
    if [[ ! -e "${RELEASE_CONTAINER}" && ! -L "${RELEASE_CONTAINER}" ]]; then
        (umask 077 && /bin/mkdir "${RELEASE_CONTAINER}") || {
            release_fail 'release staging container를 만들지 못했습니다.'
            return 1
        }
    fi
    release_validate_release_container "${RELEASE_CONTAINER}"
}

acquire_release_lock() {
    if ! (umask 077 && /bin/mkdir "${LOCK_DIR}"); then
        release_fail '같은 release version 준비가 이미 실행 중입니다.'
        return 1
    fi
    release_validate_stage_root "${LOCK_DIR}" "${RELEASE_CONTAINER}" || return 1
    LOCK_IDENTITY=$(stable_directory_identity "${LOCK_DIR}") || return 1
}

copy_to_stage() {
    local source=$1 destination=$2
    release_regular_file "${source}" || return 1
    /bin/cp -p -- "${source}" "${destination}" || {
        release_fail 'release artifact를 stage에 복사하지 못했습니다.'
        return 1
    }
    release_require_single_link_file "${destination}" || return 1
    STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}") || return 1
    CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}") || return 1
}

capture_release_file_identities() {
    local file
    for file in "${SIXBRIDGE_RELEASE_FILES[@]}"; do
        printf '%s|%s\n' "${file}" "$(release_file_identity "${STAGE_DIR}/${file}")" || return 1
    done
}

assert_release_file_identities() {
    local identities=$1 file identity
    while IFS='|' read -r file identity; do
        [[ -n "${file}" ]] || continue
        release_assert_file_identity "${STAGE_DIR}/${file}" "${identity}" || return 1
    done <<<"${identities}"
}

assert_exact_release_entries() {
    local entries entry basename count=0
    if ! entries=$(/usr/bin/find "${STAGE_DIR}" -maxdepth 1 -mindepth 1 -print 2>&1); then
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
    [[ "${count}" = 5 ]] || release_fail '필수 release file이 누락되었습니다.'
}

release_require_version "${1:-}"
require_android_credentials
validate_keystore
release_regular_file "${RELEASE_NOTES}"
START_COMMIT=$(release_clean_commit)
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}
create_release_container
acquire_release_lock
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}

cd "${ROOT_DIR}"
if ! "${ROOT_DIR}/scripts/verify.sh"; then
    release_fail '전체 검증에 실패했습니다.'
    exit 1
fi
if ! ./gradlew :android-manager:assembleRelease; then
    release_fail 'Android release build에 실패했습니다.'
    exit 1
fi
if ! env -u SIXBRIDGE_MAC_SIGNING_IDENTITY \
    -u SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID \
    -u SIXBRIDGE_MAC_NOTARIZATION_PASSWORD \
    -u SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID \
    ./gradlew -Pcompose.desktop.packaging.checkJdkVendor=false :desktop-app:packageDmg; then
    release_fail 'macOS DMG build에 실패했습니다.'
    exit 1
fi

release_regular_file "${ANDROID_OUTPUT}"
release_regular_file "${DMG_OUTPUT}"
[[ "$(release_clean_commit)" = "${START_COMMIT}" ]] || {
    release_fail 'build 또는 검증 중 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}

STAGE_DIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-release-stage.XXXXXX") || {
    release_fail 'release stage directory를 만들지 못했습니다.'
    exit 1
}
release_validate_stage_root "${STAGE_DIR}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

copy_to_stage "${ANDROID_OUTPUT}" "${STAGE_DIR}/${SIXBRIDGE_ANDROID_FILE}"
copy_to_stage "${DMG_OUTPUT}" "${STAGE_DIR}/${SIXBRIDGE_MACOS_FILE}"
copy_to_stage "${RELEASE_NOTES}" "${STAGE_DIR}/RELEASE-NOTES-KO.md"

SIGNER_SHA256=$(read_signer_sha256)
release_verify_apk "${STAGE_DIR}/${SIXBRIDGE_ANDROID_FILE}" "${SIGNER_SHA256}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"

DMG_WORK_DIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-prepare-dmg.XXXXXX") || {
    release_fail 'DMG verification work directory를 만들지 못했습니다.'
    exit 1
}
release_private_directory "${DMG_WORK_DIR}" || { /bin/rm -rf -- "${DMG_WORK_DIR}"; exit 1; }
release_register_owned_work_directory "${DMG_WORK_DIR}"
if ! release_verify_dmg "${STAGE_DIR}/${SIXBRIDGE_MACOS_FILE}" "${DMG_WORK_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"; then
    /bin/rm -rf -- "${DMG_WORK_DIR}"
    exit 1
fi
/bin/rm -rf -- "${DMG_WORK_DIR}"

release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
GIT_COMMIT=$(release_clean_commit)
[[ "${GIT_COMMIT}" = "${START_COMMIT}" ]] || {
    release_fail 'build 또는 검증 중 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
release_write_metadata "${STAGE_DIR}" "${GIT_COMMIT}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_write_checksums "${STAGE_DIR}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
RELEASE_FILE_IDENTITIES=$(capture_release_file_identities)
release_verify_directory "${STAGE_DIR}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${RELEASE_FILE_IDENTITIES}"
release_verify_checksums "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${RELEASE_FILE_IDENTITIES}"
assert_exact_release_entries
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}
[[ "$(release_clean_commit)" = "${START_COMMIT}" ]] || {
    release_fail '게시 직전 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
release_assert_stage_identity "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${STAGE_IDENTITY}" "${CONTAINER_IDENTITY}"
if ! /bin/mv -n -- "${STAGE_DIR}" "${FINAL_DIR}"; then
    release_fail 'release directory를 atomic no-replace 방식으로 publish하지 못했습니다.'
    exit 1
fi
[[ ! -e "${STAGE_DIR}" && ! -L "${STAGE_DIR}" ]] || {
    release_fail 'release directory publish 중 기존 final path를 교체하지 못했습니다.'
    exit 1
}
FINAL_IDENTITY=$(stable_directory_identity "${FINAL_DIR}") || {
    release_fail '게시된 final release directory inode를 읽지 못했습니다.'
    exit 1
}
[[ "${FINAL_IDENTITY}" = "${STAGE_IDENTITY%:*}" ]] || {
    release_fail '게시된 final release directory inode가 stage와 다릅니다.'
    exit 1
}
STAGE_DIR=''
STAGE_IDENTITY=''
CONTAINER_IDENTITY=''

printf 'v%s release directory를 준비했습니다: %s\n' "${SIXBRIDGE_RELEASE_VERSION}" "${FINAL_DIR}"
