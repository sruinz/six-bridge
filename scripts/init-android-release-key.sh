#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="${SIXBRIDGE_RELEASE_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)}"
readonly KEYTOOL="${SIXBRIDGE_KEYTOOL:-keytool}"
readonly KEYSTORE="${ROOT_DIR}/.local/keystore/six-bridge-manager-release.p12"
readonly KEY_ALIAS='six-bridge-manager'

fail() {
    printf 'Android release key 초기화 실패: %s\n' "$1" >&2
    exit 1
}

store_password="${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}"
key_password="${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}"

[[ -n "${store_password}" ]] || fail 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD를 설정하세요.'
[[ -n "${key_password}" ]] || fail 'SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD를 설정하세요.'
[[ "${#store_password}" -ge 16 && "${#key_password}" -ge 16 ]] || \
    fail 'release key 비밀번호는 각각 16자 이상이어야 합니다.'
[[ "${store_password}" = "${key_password}" ]] || \
    fail 'store 및 key 비밀번호는 동일해야 합니다.'
[[ ! -e "${KEYSTORE}" && ! -L "${KEYSTORE}" ]] || \
    fail "release key가 이미 존재합니다: ${KEYSTORE}"

umask 077
mkdir -p "$(dirname "${KEYSTORE}")"

if ! "$KEYTOOL" -genkeypair -storetype PKCS12 -keystore "${KEYSTORE}" \
    -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
    -keypass:env SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD \
    -alias "${KEY_ALIAS}" -keyalg RSA -keysize 3072 -validity 3650 \
    -dname 'CN=SIX Bridge Manager, OU=Release, O=SIX Bridge, L=Seoul, ST=Seoul, C=KR'; then
    rm -f "${KEYSTORE}"
    fail 'keytool이 release key 생성에 실패했습니다. 이번 실행이 만든 파일을 제거했습니다.'
fi

printf 'Android release key를 생성했습니다: %s\n' "${KEYSTORE}"
printf 'SHA-256 certificate fingerprint:\n'
"$KEYTOOL" -list -v -storetype PKCS12 -keystore "${KEYSTORE}" \
    -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
    -alias "${KEY_ALIAS}" | sed -n '/SHA256:/p'
