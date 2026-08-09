#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-gradle-signing-guard.XXXXXX")"
readonly FIXTURE_PROJECT="${FIXTURE_ROOT}/project"
readonly OUTPUT="${FIXTURE_ROOT}/gradle.log"
readonly RELEASE_PASSWORD='fixture-release-password'

cleanup() {
    rm -rf "${FIXTURE_ROOT}"
}
trap cleanup EXIT

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

rsync -a \
    --exclude '.git' \
    --exclude '.gradle' \
    --exclude '.local' \
    --exclude '.worktrees' \
    --exclude '**/build' \
    "${ROOT_DIR}/" "${FIXTURE_PROJECT}/"
mkdir -p "${FIXTURE_ROOT}/outside"
ln -s "${FIXTURE_ROOT}/outside" "${FIXTURE_PROJECT}/.local"

set +e
(
    cd "${FIXTURE_PROJECT}"
    SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        ./gradlew :android-manager:verifyManagerReleaseSigningInput
) >"${OUTPUT}" 2>&1
exit_code=$?
set -e

[[ "${exit_code}" -ne 0 ]] || fail 'parent symlink Gradle guard가 성공했습니다.'
grep -Fq 'release keystore parent에 symbolic link가 있습니다' "${OUTPUT}" || {
    cat "${OUTPUT}" >&2
    fail 'parent symlink Gradle guard 오류가 없습니다.'
}
if grep -Fq "${RELEASE_PASSWORD}" "${OUTPUT}"; then
    cat "${OUTPUT}" >&2
    fail 'Gradle guard 출력에 fixture 비밀번호가 있습니다.'
fi

printf 'Android manager release signing parent guard 검증 통과\n'
