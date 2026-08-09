#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly LIBRARY="${ROOT_DIR}/scripts/release-lib.sh"
readonly FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-release-lib-test.XXXXXX")"
readonly SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
readonly GIT_COMMIT='abcdef0123456789abcdef0123456789abcdef01'

cleanup() { rm -rf "${FIXTURE_ROOT}"; }
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

assert_contains() {
    local file=$1 expected=$2 message=$3
    grep -Fq -- "${expected}" "${file}" || { cat "${file}" >&2; fail "${message}"; }
}

assert_not_contains() {
    local file=$1 unexpected=$2 message=$3
    if grep -Fq -- "${unexpected}" "${file}"; then
        cat "${file}" >&2
        fail "${message}"
    fi
}

expect_failure() {
    local label=$1 expected=$2 output status
    shift 2
    output="${FIXTURE_ROOT}/${label}.log"
    set +e
    "$@" >"${output}" 2>&1
    status=$?
    set -e
    [[ "${status}" -ne 0 ]] || { cat "${output}" >&2; fail "${label}: 실패해야 할 검증이 성공했습니다."; }
    assert_contains "${output}" "${expected}" "${label}: 거부 사유가 없습니다."
}

create_tools() {
    mkdir -p "${FIXTURE_ROOT}/bin"
    cat >"${FIXTURE_ROOT}/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${FAKE_APKSIGNER_FAIL:-0}" != 1 ]] || exit 17
if [[ -n "${FAKE_APKSIGNER_REPLACE_PATH:-}" ]]; then
  printf 'replaced during apksigner\n' >"${FAKE_APKSIGNER_REPLACE_PATH}"
fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FAKE_SIGNER}"
if [[ "${FAKE_EXTRA_SIGNER:-0}" = 1 ]]; then
  printf 'Signer #2 certificate SHA-256 digest: %s\n' "${FAKE_EXTRA_SIGNER_VALUE:-ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff}"
fi
EOF
    cat >"${FIXTURE_ROOT}/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" = manifest ]] || exit 18
case "${2:-}" in
  application-id) printf '%s\n' "${FAKE_APPLICATION_ID}" ;;
  version-name) printf '%s\n' "${FAKE_VERSION_NAME}" ;;
  version-code) printf '%s\n' "${FAKE_VERSION_CODE}" ;;
  *) exit 19 ;;
esac
EOF
    cat >"${FIXTURE_ROOT}/bin/hdiutil" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  attach)
    [[ "${FAKE_HDIUTIL_FAIL:-0}" != 1 ]] || exit 20
    shift; mountpoint=''
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" = -mountpoint ]]; then mountpoint=$2; shift 2; else shift; fi
    done
    [[ -n "${mountpoint}" ]] || exit 21
    mkdir -p "${mountpoint}/SIX Bridge.app/Contents"
    : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
    if [[ "${FAKE_EXTRA_APP:-0}" = 1 ]]; then
      mkdir -p "${mountpoint}/Injected.app/Contents"
      : >"${mountpoint}/Injected.app/Contents/Info.plist"
    fi
    ;;
  detach) rm -rf "$2" ;;
  *) exit 22 ;;
esac
EOF
    cat >"${FIXTURE_ROOT}/bin/shasum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
/usr/bin/shasum "$@"
if [[ -n "${FAKE_SHASUM_REPLACE_PATH:-}" ]]; then
  printf 'replaced during shasum\n' >"${FAKE_SHASUM_REPLACE_PATH}"
fi
EOF
    cat >"${FIXTURE_ROOT}/bin/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${2:-}" in
  'Print :CFBundleIdentifier') printf '%s\n' "${FAKE_BUNDLE_ID}" ;;
  'Print :CFBundleShortVersionString') printf '%s\n' "${FAKE_PRODUCT_VERSION}" ;;
  'Print :CFBundleVersion') printf '%s\n' "${FAKE_NATIVE_VERSION}" ;;
  'Print :LSMinimumSystemVersion') printf '%s\n' "${FAKE_MINIMUM_VERSION}" ;;
  *) exit 23 ;;
esac
EOF
    cat >"${FIXTURE_ROOT}/bin/codesign" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_CODESIGN_SIGNED:-0}" = 1 ]]; then exit 0; fi
printf 'code object is not signed at all\n' >&2
exit 1
EOF
    chmod +x "${FIXTURE_ROOT}/bin/"*
}

export SIXBRIDGE_APKSIGNER="${FIXTURE_ROOT}/bin/apksigner"
export SIXBRIDGE_APKANALYZER="${FIXTURE_ROOT}/bin/apkanalyzer"
export SIXBRIDGE_HDIUTIL="${FIXTURE_ROOT}/bin/hdiutil"
export SIXBRIDGE_PLISTBUDDY="${FIXTURE_ROOT}/bin/PlistBuddy"
export SIXBRIDGE_CODESIGN="${FIXTURE_ROOT}/bin/codesign"
export SIXBRIDGE_SHASUM="${FIXTURE_ROOT}/bin/shasum"
export SIXBRIDGE_RELEASE_TEST_MODE=1
export FAKE_SIGNER="${SIGNER}"
export FAKE_APPLICATION_ID='dev.sixbridge.manager'
export FAKE_VERSION_NAME='0.1.0'
export FAKE_VERSION_CODE='1'
export FAKE_BUNDLE_ID='dev.sixbridge.desktop'
export FAKE_PRODUCT_VERSION='0.1.0'
export FAKE_NATIVE_VERSION='1.0.0'
export FAKE_MINIMUM_VERSION='13.0'
create_tools

# RED: Task 3 library does not exist until its production contract is implemented.
source "${LIBRARY}"

tool_boundary_output="${FIXTURE_ROOT}/production-tool-boundary.log"
set +e
env -u SIXBRIDGE_RELEASE_TEST_MODE \
  SIXBRIDGE_APKSIGNER="${FIXTURE_ROOT}/bin/apksigner" \
  bash -c 'source "$1"; release_tool apksigner' _ "${LIBRARY}" >"${tool_boundary_output}" 2>&1
set -e
assert_not_contains "${tool_boundary_output}" "${FIXTURE_ROOT}/bin/apksigner" 'production mode가 arbitrary apksigner 경로를 허용했습니다.'
function_boundary_output="${FIXTURE_ROOT}/function-tool-boundary.log"
set +e
env -u SIXBRIDGE_RELEASE_TEST_MODE bash -c 'apksigner() { printf injected; }; source "$1"; release_tool apksigner' _ "${LIBRARY}" >"${function_boundary_output}" 2>&1
set -e
assert_not_contains "${function_boundary_output}" 'injected' 'production mode가 PATH shell function을 실행했습니다.'

stage_container="${FIXTURE_ROOT}/stage-container"
stage_root="${stage_container}/stage"
mkdir -p "${stage_root}"
chmod 700 "${stage_container}" "${stage_root}"
release_validate_stage_root "${stage_root}" "${stage_container}"
chmod 755 "${stage_root}"
expect_failure stage-root-mode 'private stage root' bash -c 'source "$1"; release_validate_stage_root "$2" "$3"' _ "${LIBRARY}" "${stage_root}" "${stage_container}"
chmod 700 "${stage_root}"

make_file() { mkdir -p "$(dirname -- "$1")"; printf '%s' "$2" >"$1"; }

make_release_directory() {
    local root=$1 directory="${1}/release"
    mkdir -p "${directory}"
    chmod 700 "${directory}"
    make_file "${directory}/${SIXBRIDGE_ANDROID_FILE}" 'android fixture'
    make_file "${directory}/${SIXBRIDGE_MACOS_FILE}" 'macos fixture'
    make_file "${directory}/RELEASE-NOTES-KO.md" '한글 릴리스 노트\n'
    release_write_metadata "${directory}" "${GIT_COMMIT}" "${SIGNER}"
    release_write_checksums "${directory}"
    printf '%s\n' "${directory}"
}

make_file "${FIXTURE_ROOT}/apk.apk" 'apk'
release_verify_apk "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-apksigner-nonzero 'APK signature verification' \
  env FAKE_APKSIGNER_FAIL=1 bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-package-mismatch 'application ID' \
  env FAKE_APPLICATION_ID='invalid.package' bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-version-mismatch 'versionName' \
  env FAKE_VERSION_NAME='9.9.9' bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-signer-mismatch 'signer SHA-256' \
  env FAKE_SIGNER='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff' bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-extra-signer '정확히 하나' \
  env FAKE_EXTRA_SIGNER=1 FAKE_EXTRA_SIGNER_VALUE="${SIGNER}" bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-version-code-mismatch 'versionCode' \
  env FAKE_VERSION_CODE=2 bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
expect_failure apk-replacement-race 'artifact changed during verification' \
  env FAKE_APKSIGNER_REPLACE_PATH="${FIXTURE_ROOT}/apk.apk" bash -c 'source "$1"; release_verify_apk "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/apk.apk" "${SIGNER}"
make_file "${FIXTURE_ROOT}/apk.apk" 'apk'

make_file "${FIXTURE_ROOT}/fixture.dmg" 'dmg'
release_verify_dmg "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-bundle-mismatch 'bundle ID' \
  env FAKE_BUNDLE_ID='invalid.bundle' bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-native-version-mismatch 'native package version' \
    env FAKE_NATIVE_VERSION='2.0.0' bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-product-version-mismatch 'product version' \
    env FAKE_PRODUCT_VERSION='9.9.9' bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-minimum-version-mismatch 'minimum macOS version' \
  env FAKE_MINIMUM_VERSION='12.0' bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-signed '미서명' \
  env FAKE_CODESIGN_SIGNED=1 bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"
expect_failure dmg-extra-app '정확히 하나' \
  env FAKE_EXTRA_APP=1 bash -c 'source "$1"; release_verify_dmg "$2" "$3"' _ "${LIBRARY}" "${FIXTURE_ROOT}/fixture.dmg" "${FIXTURE_ROOT}/mounts"

scan_root="${FIXTURE_ROOT}/scan"
mkdir -p "${scan_root}"
make_file "${scan_root}/safe.txt" 'safe'
release_scan_tree "${scan_root}"
ln -s "${scan_root}/safe.txt" "${scan_root}/linked.txt"
expect_failure scan-symlink 'symbolic link' bash -c 'source "$1"; release_scan_tree "$2"' _ "${LIBRARY}" "${scan_root}"
rm "${scan_root}/linked.txt"
ln "${scan_root}/safe.txt" "${scan_root}/hardlinked.txt"
expect_failure scan-hardlink 'hardlink' bash -c 'source "$1"; release_scan_tree "$2"' _ "${LIBRARY}" "${scan_root}"
rm "${scan_root}/hardlinked.txt"
for forbidden in private.p12 private.jks private.pem private.pk8 input.six input.sixdraft input.crx fixture.zip; do
  make_file "${scan_root}/${forbidden}" 'forbidden'
  expect_failure "scan-${forbidden}" 'forbidden release input' bash -c 'source "$1"; release_scan_tree "$2"' _ "${LIBRARY}" "${scan_root}"
  rm "${scan_root}/${forbidden}"
done
make_file "${scan_root}/secret.txt" 'release-secret-probe'
expect_failure scan-secret '비밀정보' \
  env SIXBRIDGE_RELEASE_SECRET_PROBE='release-secret-probe' bash -c 'source "$1"; release_scan_tree "$2"' _ "${LIBRARY}" "${scan_root}"
rm "${scan_root}/secret.txt"
mkdir "${scan_root}/.local"
expect_failure scan-local-directory 'forbidden release input' bash -c 'source "$1"; release_scan_tree "$2"' _ "${LIBRARY}" "${scan_root}"
rmdir "${scan_root}/.local"

release_one=$(make_release_directory "${FIXTURE_ROOT}/deterministic-one")
release_two=$(make_release_directory "${FIXTURE_ROOT}/deterministic-two")
cmp "${release_one}/RELEASE-METADATA.json" "${release_two}/RELEASE-METADATA.json" || fail 'metadata가 root에 따라 달라집니다.'
cmp "${release_one}/SHA256SUMS" "${release_two}/SHA256SUMS" || fail 'checksum이 root에 따라 달라집니다.'
release_verify_directory "${release_one}" "${SIGNER}"
printf 'x' >>"${release_one}/${SIXBRIDGE_ANDROID_FILE}"
expect_failure directory-corrupt-checksum 'SHA256SUMS' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_one}" "${SIGNER}"

release_newline=$(make_release_directory "${FIXTURE_ROOT}/checksum-newline")
printf '\n' >>"${release_newline}/SHA256SUMS"
expect_failure directory-checksum-extra-newline 'SHA256SUMS' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_newline}" "${SIGNER}"
release_no_newline=$(make_release_directory "${FIXTURE_ROOT}/checksum-no-newline")
perl -0pi -e 's/\n\z//' "${release_no_newline}/SHA256SUMS"
expect_failure directory-checksum-no-newline 'SHA256SUMS' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_no_newline}" "${SIGNER}"
release_checksum_byte=$(make_release_directory "${FIXTURE_ROOT}/checksum-byte")
perl -0pi -e 's/^./f/' "${release_checksum_byte}/SHA256SUMS"
expect_failure directory-checksum-one-byte 'SHA256SUMS' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_checksum_byte}" "${SIGNER}"

release_metadata_race_root="${FIXTURE_ROOT}/metadata-race"
mkdir -p "${release_metadata_race_root}/release"
chmod 700 "${release_metadata_race_root}/release"
make_file "${release_metadata_race_root}/release/${SIXBRIDGE_ANDROID_FILE}" 'android fixture'
make_file "${release_metadata_race_root}/release/${SIXBRIDGE_MACOS_FILE}" 'macos fixture'
make_file "${release_metadata_race_root}/release/RELEASE-NOTES-KO.md" 'note'
expect_failure metadata-input-race 'artifact changed during verification' \
  env FAKE_SHASUM_REPLACE_PATH="${release_metadata_race_root}/release/${SIXBRIDGE_ANDROID_FILE}" bash -c 'source "$1"; release_write_metadata "$2" "$3" "$4"' _ \
  "${LIBRARY}" "${release_metadata_race_root}/release" "${GIT_COMMIT}" "${SIGNER}"

atomic_root="${FIXTURE_ROOT}/atomic"
mkdir -p "${atomic_root}"
make_file "${atomic_root}/owned.tmp" 'owned'
mkdir "${atomic_root}/destination"
expect_failure atomic-publish-failure 'release output' bash -c 'source "$1"; release_atomic_publish_file "$2" "$3"' _ "${LIBRARY}" "${atomic_root}/owned.tmp" "${atomic_root}/destination"
[[ ! -e "${atomic_root}/owned.tmp" ]] || fail 'atomic publish 실패 뒤 소유 temp가 남았습니다.'

release_missing=$(make_release_directory "${FIXTURE_ROOT}/missing")
rm "${release_missing}/RELEASE-NOTES-KO.md"
expect_failure directory-missing-file '필수 release file' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_missing}" "${SIGNER}"
release_extra=$(make_release_directory "${FIXTURE_ROOT}/extra")
make_file "${release_extra}/unexpected.txt" 'extra'
expect_failure directory-extra-file '정확히 다섯 개' bash -c 'source "$1"; release_verify_directory "$2" "$3"' _ "${LIBRARY}" "${release_extra}" "${SIGNER}"

printf 'release-lib 검증 통과\n'
