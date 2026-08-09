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
readonly ANDROID_OUTPUT="${ROOT_DIR}/android-manager/build/outputs/apk/release/android-manager-release.apk"
readonly DMG_OUTPUT="${ROOT_DIR}/desktop-app/build/compose/binaries/main/dmg/SIX Bridge-1.0.0.dmg"

STAGE_DIR=''
STAGE_IDENTITY=''
CONTAINER_IDENTITY=''

cleanup_stage() {
    if [[ -n "${STAGE_DIR}" && -n "${STAGE_IDENTITY}" && -n "${CONTAINER_IDENTITY}" ]] &&
        release_assert_stage_identity "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${STAGE_IDENTITY}" "${CONTAINER_IDENTITY}"; then
        /bin/rm -rf -- "${STAGE_DIR}"
    fi
}
trap cleanup_stage EXIT

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

read_signer_sha256() {
    local raw_output signer_line signer
    if ! raw_output=$(keytool -list -v -storetype PKCS12 -keystore "${KEYSTORE}" \
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

release_require_version "${1:-}"
require_android_credentials
release_regular_file "${KEYSTORE}"
release_regular_file "${RELEASE_NOTES}"
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
create_release_container
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
if ! release_verify_dmg "${STAGE_DIR}/${SIXBRIDGE_MACOS_FILE}" "${DMG_WORK_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"; then
    /bin/rm -rf -- "${DMG_WORK_DIR}"
    exit 1
fi
/bin/rm -rf -- "${DMG_WORK_DIR}"

release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
GIT_COMMIT=$(git rev-parse HEAD) || {
    release_fail '현재 git commit을 읽지 못했습니다.'
    exit 1
}
release_write_metadata "${STAGE_DIR}" "${GIT_COMMIT}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_write_checksums "${STAGE_DIR}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
release_verify_directory "${STAGE_DIR}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}
release_assert_stage_identity "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${STAGE_IDENTITY}" "${CONTAINER_IDENTITY}"
/bin/mv -- "${STAGE_DIR}" "${FINAL_DIR}"
STAGE_DIR=''
STAGE_IDENTITY=''
CONTAINER_IDENTITY=''

printf 'v%s release directory를 준비했습니다: %s\n' "${SIXBRIDGE_RELEASE_VERSION}" "${FINAL_DIR}"
