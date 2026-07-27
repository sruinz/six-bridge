#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VERIFY_SCRIPT="${ROOT_DIR}/scripts/verify.sh"
readonly TEMPLATE_TEST_SCRIPT="${ROOT_DIR}/scripts/android-wrapper-template.test.sh"
readonly SOURCE_APK="${ROOT_DIR}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly DIST_DIR="${ROOT_DIR}/dist"
readonly OUTPUT_APK="${DIST_DIR}/sixbridge-manager-debug.apk"

fail() {
    printf '오류: %s\n' "$1" >&2
    exit 1
}

temporary_apk=""
cleanup_failed_package() {
    local status=$?

    set +e
    if [[ -n "${temporary_apk}" ]]; then
        rm -f -- "${temporary_apk}"
    fi
    rm -f -- "${OUTPUT_APK}"
    exit "${status}"
}

trap cleanup_failed_package EXIT
mkdir -p "${DIST_DIR}"
rm -f -- "${OUTPUT_APK}"

[[ -x "${VERIFY_SCRIPT}" ]] ||
    fail "전체 검증 스크립트를 실행할 수 없습니다: ${VERIFY_SCRIPT}"
[[ -x "${TEMPLATE_TEST_SCRIPT}" ]] ||
    fail "템플릿 계약 스크립트를 실행할 수 없습니다: ${TEMPLATE_TEST_SCRIPT}"

"${TEMPLATE_TEST_SCRIPT}"
"${VERIFY_SCRIPT}"

[[ -f "${SOURCE_APK}" ]] ||
    fail "검증 뒤 debug APK를 찾을 수 없습니다: ${SOURCE_APK}"

temporary_apk="$(mktemp "${DIST_DIR}/.sixbridge-manager-debug.apk.XXXXXX")"
cp -- "${SOURCE_APK}" "${temporary_apk}"
mv -f -- "${temporary_apk}" "${OUTPUT_APK}"
temporary_apk=""

printf '패키징 완료: %s\n' "${OUTPUT_APK}"
shasum -a 256 "${OUTPUT_APK}"
trap - EXIT
