#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VERIFY_SCRIPT="${ROOT_DIR}/scripts/verify.sh"
readonly SOURCE_APK="${ROOT_DIR}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly DIST_DIR="${ROOT_DIR}/dist"
readonly OUTPUT_APK="${DIST_DIR}/sixbridge-manager-debug.apk"

fail() {
    printf '오류: %s\n' "$1" >&2
    exit 1
}

[[ -x "${VERIFY_SCRIPT}" ]] ||
    fail "전체 검증 스크립트를 실행할 수 없습니다: ${VERIFY_SCRIPT}"

mkdir -p "${DIST_DIR}"
rm -f -- "${OUTPUT_APK}"

"${VERIFY_SCRIPT}"

[[ -f "${SOURCE_APK}" ]] ||
    fail "검증 뒤 debug APK를 찾을 수 없습니다: ${SOURCE_APK}"

temporary_apk="$(mktemp "${DIST_DIR}/.sixbridge-manager-debug.apk.XXXXXX")"
trap 'rm -f -- "${temporary_apk}"' EXIT
cp -- "${SOURCE_APK}" "${temporary_apk}"
mv -f -- "${temporary_apk}" "${OUTPUT_APK}"
trap - EXIT

printf '패키징 완료: %s\n' "${OUTPUT_APK}"
shasum -a 256 "${OUTPUT_APK}"
