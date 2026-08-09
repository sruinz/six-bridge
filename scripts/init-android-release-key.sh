#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_ROOT_INPUT="${SIXBRIDGE_RELEASE_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)}"
readonly KEYTOOL="${SIXBRIDGE_KEYTOOL:-keytool}"
readonly KEY_ALIAS='six-bridge-manager'

fail() {
    printf 'Android release key 초기화 실패: %s\n' "$1" >&2
    exit 1
}

[[ -d "${RELEASE_ROOT_INPUT}" && ! -L "${RELEASE_ROOT_INPUT}" ]] || \
    fail 'release root는 symbolic link가 아닌 directory여야 합니다.'
ROOT_DIR="$(CDPATH= cd -P -- "${RELEASE_ROOT_INPUT}" && pwd -P)"

readonly ROOT_DIR
readonly KEYSTORE="${ROOT_DIR}/.local/keystore/six-bridge-manager-release.p12"

ensure_directory_component() {
    local path=$1
    local label=$2
    local physical_path

    if [[ -L "${path}" ]]; then
        fail "${label}에 symbolic link가 있습니다: ${path}"
    fi
    if [[ -e "${path}" ]]; then
        [[ -d "${path}" ]] || fail "${label}이 directory가 아닙니다: ${path}"
    elif ! mkdir -m 700 "${path}"; then
        if [[ -L "${path}" ]]; then
            fail "${label}에 symbolic link가 있습니다: ${path}"
        fi
        [[ -d "${path}" ]] || fail "${label} directory를 만들 수 없습니다: ${path}"
    fi

    [[ ! -L "${path}" && -d "${path}" ]] || \
        fail "${label}에 symbolic link가 있습니다: ${path}"
    physical_path="$(CDPATH= cd -P -- "${path}" && pwd -P)"
    [[ "${physical_path}" = "${ROOT_DIR}" || "${physical_path}" = "${ROOT_DIR}/"* ]] || \
        fail "${label}이 release root 밖을 가리킵니다: ${path}"
}

store_password="${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}"
key_password="${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}"

[[ -n "${store_password}" ]] || fail 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD를 설정하세요.'
[[ -n "${key_password}" ]] || fail 'SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD를 설정하세요.'
[[ "${#store_password}" -ge 16 && "${#key_password}" -ge 16 ]] || \
    fail 'release key 비밀번호는 각각 16자 이상이어야 합니다.'
[[ "${store_password}" = "${key_password}" ]] || \
    fail 'store 및 key 비밀번호는 동일해야 합니다.'

umask 077
ensure_directory_component "${ROOT_DIR}/.local" '.local'
ensure_directory_component "${ROOT_DIR}/.local/keystore" '.local/keystore'
[[ ! -e "${KEYSTORE}" && ! -L "${KEYSTORE}" ]] || \
    fail "release key가 이미 존재합니다: ${KEYSTORE}"

TEMP_DIR=''
TEMP_KEYSTORE=''
cleanup() {
    if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" && ! -L "${TEMP_DIR}" ]]; then
        rm -f "${TEMP_KEYSTORE}"
        rmdir "${TEMP_DIR}" 2>/dev/null || true
    fi
}
trap cleanup EXIT

TEMP_DIR="$(mktemp -d "${KEYSTORE}.tmp.XXXXXX")"
TEMP_KEYSTORE="${TEMP_DIR}/release.p12"

if ! "$KEYTOOL" -genkeypair -storetype PKCS12 -keystore "${TEMP_KEYSTORE}" \
    -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
    -keypass:env SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD \
    -alias "${KEY_ALIAS}" -keyalg RSA -keysize 3072 -validity 3650 \
    -dname 'CN=SIX Bridge Manager, OU=Release, O=SIX Bridge, L=Seoul, ST=Seoul, C=KR'; then
    fail 'keytool이 release key 생성에 실패했습니다. 이번 실행의 임시 파일만 제거합니다.'
fi
[[ -f "${TEMP_KEYSTORE}" && ! -L "${TEMP_KEYSTORE}" ]] || \
    fail 'keytool이 regular PKCS12 파일을 만들지 않았습니다.'
chmod 600 "${TEMP_KEYSTORE}"

if ! fingerprint=$("$KEYTOOL" -list -v -storetype PKCS12 -keystore "${TEMP_KEYSTORE}" \
    -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
    -alias "${KEY_ALIAS}" | sed -n '/SHA256:/p'); then
    fail 'keytool release key 검증에 실패했습니다. 이번 실행의 임시 파일만 제거합니다.'
fi
[[ -n "${fingerprint}" ]] || \
    fail 'keytool release key 검증에서 SHA-256 fingerprint를 찾지 못했습니다.'

if ! ln "${TEMP_KEYSTORE}" "${KEYSTORE}"; then
    if [[ -e "${KEYSTORE}" || -L "${KEYSTORE}" ]]; then
        fail "release key가 이미 존재합니다: ${KEYSTORE}"
    fi
    fail 'release key를 원자적으로 publish하지 못했습니다.'
fi

printf 'Android release key를 생성했습니다: %s\n' "${KEYSTORE}"
printf 'SHA-256 certificate fingerprint:\n%s\n' "${fingerprint}"
