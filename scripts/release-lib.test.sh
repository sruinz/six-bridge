#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")/.." && pwd -P)"
readonly LIBRARY="${ROOT_DIR}/scripts/release-lib.sh"
readonly FIXTURE_ROOT="$(/usr/bin/mktemp -d "${ROOT_DIR}/.local/release-lib-test.XXXXXX")"
readonly SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
readonly GIT_COMMIT='abcdef0123456789abcdef0123456789abcdef01'

cleanup() { /bin/rm -rf "${FIXTURE_ROOT}"; }
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

assert_contains() { /usr/bin/grep -Fq -- "$2" "$1" || { /bin/cat "$1" >&2; fail "$3"; }; }
assert_not_contains() { ! /usr/bin/grep -Fq -- "$2" "$1" || { /bin/cat "$1" >&2; fail "$3"; }; }
expect_failure() {
    local label=$1 expected=$2 output status
    shift 2
    output="${FIXTURE_ROOT}/${label}.log"
    set +e
    "$@" >"${output}" 2>&1
    status=$?
    set -e
    [[ "${status}" -ne 0 ]] || { /bin/cat "${output}" >&2; fail "${label}: 실패해야 할 검증이 성공했습니다."; }
    assert_contains "${output}" "${expected}" "${label}: 거부 사유가 없습니다."
}
make_file() { /bin/mkdir -p "$(/usr/bin/dirname -- "$1")"; printf '%s' "$2" >"$1"; }

create_tools() {
    /bin/mkdir -p "${FIXTURE_ROOT}/bin"
    /bin/cat >"${FIXTURE_ROOT}/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
[[ "${FAKE_APKSIGNER_FAIL:-0}" != 1 ]] || exit 17
[[ -z "${FAKE_APKSIGNER_REPLACE_PATH:-}" ]] || printf 'replaced' >"${FAKE_APKSIGNER_REPLACE_PATH}"
if [[ -n "${FAKE_APKSIGNER_SWAP_RESTORE_PATH:-}" ]]; then
  saved="${FAKE_APKSIGNER_SWAP_RESTORE_PATH}.saved"
  /bin/mv "${FAKE_APKSIGNER_SWAP_RESTORE_PATH}" "${saved}"
  printf 'replaced' >"${FAKE_APKSIGNER_SWAP_RESTORE_PATH}"
  /bin/mv "${saved}" "${FAKE_APKSIGNER_SWAP_RESTORE_PATH}"
fi
if [[ "${FAKE_APKSIGNER_REQUIRE_SNAPSHOT:-0}" = 1 ]]; then
  case "${!#}" in */.sixbridge-release-snapshot.*/artifact) ;; *) exit 29;; esac
fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FAKE_SIGNER}"
[[ "${FAKE_EXTRA_SIGNER:-0}" != 1 ]] || printf 'Signer #2 certificate SHA-256 digest: %s\n' "${FAKE_SIGNER}"
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
case "$2" in application-id) printf '%s\n' "${FAKE_APPLICATION_ID}";; version-name) printf '%s\n' "${FAKE_VERSION_NAME}";; version-code) printf '%s\n' "${FAKE_VERSION_CODE}";; *) exit 19;; esac
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/hdiutil" <<'EOF'
#!/usr/bin/env bash
case "$1" in
 attach) shift; while [[ "$#" -gt 0 ]]; do [[ "$1" = -mountpoint ]] && { mountpoint=$2; shift 2; } || shift; done; /bin/mkdir -p "${mountpoint}/SIX Bridge.app/Contents"; : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"; [[ "${FAKE_EXTRA_APP:-0}" != 1 ]] || /bin/mkdir -p "${mountpoint}/Injected.app/Contents";;
 detach) /bin/rm -rf "$2";; *) exit 22;;
esac
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in 'Print :CFBundleIdentifier') printf '%s\n' "${FAKE_BUNDLE_ID}";; 'Print :CFBundleShortVersionString') printf '%s\n' "${FAKE_PRODUCT_VERSION}";; 'Print :CFBundleVersion') printf '%s\n' "${FAKE_NATIVE_VERSION}";; 'Print :LSMinimumSystemVersion') printf '%s\n' "${FAKE_MINIMUM_VERSION}";; *) exit 23;; esac
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/codesign" <<'EOF'
#!/usr/bin/env bash
[[ "${FAKE_CODESIGN_SIGNED:-0}" != 1 ]] || exit 0
printf 'code object is not signed at all\n' >&2; exit 1
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/shasum" <<'EOF'
#!/usr/bin/env bash
/usr/bin/shasum "$@"
[[ -z "${FAKE_SHASUM_REPLACE_PATH:-}" ]] || printf 'replaced' >"${FAKE_SHASUM_REPLACE_PATH}"
EOF
    /bin/chmod +x "${FIXTURE_ROOT}/bin/"*
}

export SIXBRIDGE_APKSIGNER="${FIXTURE_ROOT}/bin/apksigner"
export SIXBRIDGE_APKANALYZER="${FIXTURE_ROOT}/bin/apkanalyzer"
export SIXBRIDGE_HDIUTIL="${FIXTURE_ROOT}/bin/hdiutil"
export SIXBRIDGE_PLISTBUDDY="${FIXTURE_ROOT}/bin/PlistBuddy"
export SIXBRIDGE_CODESIGN="${FIXTURE_ROOT}/bin/codesign"
export SIXBRIDGE_SHASUM="${FIXTURE_ROOT}/bin/shasum"
export FAKE_SIGNER="${SIGNER}"
export FAKE_APPLICATION_ID='dev.sixbridge.manager'
export FAKE_VERSION_NAME='0.1.0'
export FAKE_VERSION_CODE='1'
export FAKE_BUNDLE_ID='dev.sixbridge.desktop'
export FAKE_PRODUCT_VERSION='0.1.0'
export FAKE_NATIVE_VERSION='1.0.0'
export FAKE_MINIMUM_VERSION='13.0'
create_tools

# Production library must ignore test-mode and arbitrary tool environment variables.
production_log="${FIXTURE_ROOT}/production-tool-boundary.log"
set +e
env SIXBRIDGE_RELEASE_TEST_MODE=1 SIXBRIDGE_APKSIGNER="${FIXTURE_ROOT}/bin/apksigner" \
  bash -c 'source "$1"; release_tool apksigner' _ "${LIBRARY}" >"${production_log}" 2>&1
production_status=$?
set -e
assert_not_contains "${production_log}" "${FIXTURE_ROOT}/bin/apksigner" 'production release_tool이 arbitrary 경로를 사용했습니다.'
if [[ ${production_status} -ne 0 ]]; then
    assert_contains "${production_log}" 'release 검증 실패' 'production tool 실패 status의 진단이 없습니다.'
fi
expect_failure production-unknown-tool '알 수 없는 release 도구' bash -c 'source "$1"; release_tool invalid' _ "${LIBRARY}"
production_shasum_log="${FIXTURE_ROOT}/production-shasum.log"
set +e
bash -c 'source "$1"; tool=$(release_tool shasum) || exit 1; [[ "${tool}" = /usr/bin/shasum ]] || exit 1; release_sha256 "$2"' _ "${LIBRARY}" "${LIBRARY}" >"${production_shasum_log}" 2>&1
production_shasum_status=$?
set -e
[[ "${production_shasum_status}" = 0 ]] || { /bin/cat "${production_shasum_log}" >&2; fail 'production macOS shasum 검증이 실패했습니다.'; }
/usr/bin/grep -Eq '^[0-9a-f]{64}$' "${production_shasum_log}" || fail 'production shasum digest 형식이 올바르지 않습니다.'

source "${LIBRARY}"

# Test-only resolver: production library has no sourceable hook and ignores
# environment overrides. This override exists only in this fixture process.
release_tool() {
    case "$1" in
        apksigner) printf '%s\n' "${SIXBRIDGE_APKSIGNER}" ;;
        apkanalyzer) printf '%s\n' "${SIXBRIDGE_APKANALYZER}" ;;
        hdiutil) printf '%s\n' "${SIXBRIDGE_HDIUTIL}" ;;
        plistbuddy) printf '%s\n' "${SIXBRIDGE_PLISTBUDDY}" ;;
        codesign) printf '%s\n' "${SIXBRIDGE_CODESIGN}" ;;
        jq) printf '%s\n' /usr/bin/jq ;;
        shasum) printf '%s\n' "${SIXBRIDGE_SHASUM}" ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}

container="${FIXTURE_ROOT}"
stage="${container}/stage"
/bin/mkdir -p "${stage}"; /bin/chmod 700 "${container}" "${stage}"
release_validate_stage_root "${stage}" "${container}"
/bin/chmod 755 "${container}"
expect_failure container-mode 'trusted directory' release_validate_stage_root "${stage}" "${container}"
/bin/chmod 700 "${container}"
external_container=$(/usr/bin/mktemp -d '/tmp/sixbridge-external-container.XXXXXX')
external_stage="${external_container}/stage"
/bin/mkdir -p "${external_stage}"; /bin/chmod 700 "${external_container}" "${external_stage}"
expect_failure external-container '프로젝트 .local' release_validate_stage_root "${external_stage}" "${external_container}"
/bin/rm -rf "${external_container}"

apk="${stage}/fixture.apk"; dmg="${stage}/fixture.dmg"
make_file "${apk}" apk; make_file "${dmg}" dmg
release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"
export FAKE_APKSIGNER_FAIL=1; expect_failure apk-signature 'APK signature verification' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; unset FAKE_APKSIGNER_FAIL
export FAKE_APPLICATION_ID='invalid.package'; expect_failure apk-application-id 'application ID' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_APPLICATION_ID='dev.sixbridge.manager'
export FAKE_VERSION_NAME='9.9.9'; expect_failure apk-version-name 'versionName' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_VERSION_NAME='0.1.0'
export FAKE_SIGNER='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'; expect_failure apk-signer 'signer SHA-256' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_SIGNER="${SIGNER}"
export FAKE_EXTRA_SIGNER=1; expect_failure apk-extra-signer '정확히 하나' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; unset FAKE_EXTRA_SIGNER
export FAKE_VERSION_CODE=2; expect_failure apk-version-code 'versionCode' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_VERSION_CODE=1
export FAKE_APKSIGNER_REPLACE_PATH="${apk}"; expect_failure apk-race 'artifact changed during verification' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; unset FAKE_APKSIGNER_REPLACE_PATH
make_file "${apk}" apk
export FAKE_APKSIGNER_SWAP_RESTORE_PATH="${apk}" FAKE_APKSIGNER_REQUIRE_SNAPSHOT=1
expect_failure apk-swap-restore 'stage root changed' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"
unset FAKE_APKSIGNER_SWAP_RESTORE_PATH FAKE_APKSIGNER_REQUIRE_SNAPSHOT
outside="${FIXTURE_ROOT}/outside.apk"; make_file "${outside}" apk
expect_failure apk-outside-stage 'stage root 밖' release_verify_apk "${outside}" "${SIGNER}" "${stage}" "${container}"

dmg_work="${container}/mounts"; /bin/mkdir "${dmg_work}"; /bin/chmod 700 "${dmg_work}"
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
export FAKE_BUNDLE_ID='invalid.bundle'; expect_failure dmg-bundle-id 'bundle ID' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_BUNDLE_ID='dev.sixbridge.desktop'
export FAKE_EXTRA_APP=1; expect_failure dmg-extra-app '정확히 하나' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_EXTRA_APP
export FAKE_PRODUCT_VERSION=9.9.9; expect_failure dmg-product-version 'product version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_PRODUCT_VERSION='0.1.0'
export FAKE_NATIVE_VERSION=2.0.0; expect_failure dmg-native-version 'native package version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_NATIVE_VERSION='1.0.0'
export FAKE_MINIMUM_VERSION=12.0; expect_failure dmg-minimum-version 'minimum macOS version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_MINIMUM_VERSION='13.0'
export FAKE_CODESIGN_SIGNED=1; expect_failure dmg-signed '미서명' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_CODESIGN_SIGNED
public_dmg_work="${container}/public-mounts"; /bin/mkdir "${public_dmg_work}"; /bin/chmod 777 "${public_dmg_work}"
expect_failure dmg-public-work-dir 'trusted directory' release_verify_dmg "${dmg}" "${public_dmg_work}" "${stage}" "${container}"
/bin/rmdir "${public_dmg_work}"

scan_root="${stage}/scan"; /bin/mkdir -p "${scan_root}"; make_file "${scan_root}/safe.txt" safe
release_scan_tree "${scan_root}" "${stage}" "${container}"
/bin/ln -s "${scan_root}/safe.txt" "${scan_root}/linked.txt"
expect_failure scan-symlink 'symbolic link' release_scan_tree "${scan_root}" "${stage}" "${container}"
/bin/rm "${scan_root}/linked.txt"
/bin/ln "${scan_root}/safe.txt" "${scan_root}/hardlinked.txt"
expect_failure scan-hardlink 'hardlink' release_scan_tree "${scan_root}" "${stage}" "${container}"
/bin/rm "${scan_root}/hardlinked.txt"
for forbidden in private.p12 private.jks private.pem private.pk8 input.six input.sixdraft input.crx fixture.zip; do
  make_file "${scan_root}/${forbidden}" bad
  expect_failure "scan-${forbidden}" 'forbidden release input' release_scan_tree "${scan_root}" "${stage}" "${container}"
  /bin/rm "${scan_root}/${forbidden}"
done
/bin/mkdir "${scan_root}/.local"
expect_failure scan-local-directory 'forbidden release input' release_scan_tree "${scan_root}" "${stage}" "${container}"
/bin/rmdir "${scan_root}/.local"
/bin/mkdir "${scan_root}/private.p12"
expect_failure scan-forbidden-directory 'forbidden release input' release_scan_tree "${scan_root}" "${stage}" "${container}"
/bin/rmdir "${scan_root}/private.p12"
make_file "${scan_root}/secret.txt" 'release-secret-probe'
/bin/mkdir -p "${FIXTURE_ROOT}/path"; /bin/cat >"${FIXTURE_ROOT}/path/grep" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${GREP_CAPTURE}"
exit 1
EOF
/bin/chmod +x "${FIXTURE_ROOT}/path/grep"; : >"${FIXTURE_ROOT}/grep-argv.log"
GREP_CAPTURE="${FIXTURE_ROOT}/grep-argv.log" PATH="${FIXTURE_ROOT}/path:${PATH}" \
  expect_failure scan-secret '비밀정보' release_scan_tree "${scan_root}" "${stage}" "${container}" 'release-secret-probe'
assert_not_contains "${FIXTURE_ROOT}/grep-argv.log" 'release-secret-probe' 'secret probe가 grep argv에 노출되었습니다.'
/bin/rm "${scan_root}/secret.txt"

make_release_directory() {
    local name=$1 parent directory
    parent="${container}/${name}"
    directory="${parent}/release"
    /bin/mkdir -p "${directory}"; /bin/chmod 700 "${parent}" "${directory}"
    make_file "${directory}/${SIXBRIDGE_ANDROID_FILE}" android
    make_file "${directory}/${SIXBRIDGE_MACOS_FILE}" macos
    make_file "${directory}/RELEASE-NOTES-KO.md" '한글 릴리스 노트'
    release_write_metadata "${directory}" "${GIT_COMMIT}" "${SIGNER}" "${container}"
    release_write_checksums "${directory}" "${container}"
    printf '%s\n' "${directory}"
}

release_one=$(make_release_directory one)
release_two=$(make_release_directory two)
/usr/bin/cmp "${release_one}/RELEASE-METADATA.json" "${release_two}/RELEASE-METADATA.json" || fail 'metadata가 root에 따라 달라집니다.'
/usr/bin/cmp "${release_one}/SHA256SUMS" "${release_two}/SHA256SUMS" || fail 'checksum이 root에 따라 달라집니다.'
release_verify_directory "${release_one}" "${SIGNER}" "${container}"
printf '\n' >>"${release_one}/SHA256SUMS"
expect_failure checksum-extra-newline 'SHA256SUMS' release_verify_directory "${release_one}" "${SIGNER}" "${container}"

release_no_newline=$(make_release_directory no-newline)
/usr/bin/perl -0pi -e 's/\n\z//' "${release_no_newline}/SHA256SUMS"
expect_failure checksum-missing-newline 'SHA256SUMS' release_verify_directory "${release_no_newline}" "${SIGNER}" "${container}"

release_one_byte=$(make_release_directory one-byte)
first_byte=$(/bin/dd if="${release_one_byte}/SHA256SUMS" bs=1 count=1 2>/dev/null)
replacement_byte=0; [[ "${first_byte}" = 0 ]] && replacement_byte=1
printf '%s' "${replacement_byte}" | /bin/dd of="${release_one_byte}/SHA256SUMS" bs=1 count=1 conv=notrunc >/dev/null 2>&1
expect_failure checksum-one-byte 'SHA256SUMS' release_verify_directory "${release_one_byte}" "${SIGNER}" "${container}"

release_nested_metadata=$(make_release_directory nested-metadata)
nested_metadata_tmp=$(/usr/bin/mktemp "${release_nested_metadata}/.nested-metadata.XXXXXX")
/usr/bin/jq '.android.extra = true' "${release_nested_metadata}/RELEASE-METADATA.json" >"${nested_metadata_tmp}"
/bin/mv "${nested_metadata_tmp}" "${release_nested_metadata}/RELEASE-METADATA.json"
expect_failure metadata-extra-nested 'release metadata 계약' release_verify_metadata "${release_nested_metadata}" "${SIGNER}" "${release_nested_metadata}" "${container}"

release_cmp=$(make_release_directory cmp)
printf x >>"${release_cmp}/${SIXBRIDGE_ANDROID_FILE}"
cmp() { return 0; }
expect_failure checksum-path-function 'SHA256SUMS' release_verify_directory "${release_cmp}" "${SIGNER}" "${container}"
unset -f cmp

release_missing=$(make_release_directory missing)
/bin/rm "${release_missing}/RELEASE-NOTES-KO.md"
expect_failure directory-missing-file '필수 release file' release_verify_directory "${release_missing}" "${SIGNER}" "${container}"

release_extra=$(make_release_directory extra)
make_file "${release_extra}/unexpected.txt" extra
expect_failure directory-extra-file '정확히 다섯 개' release_verify_directory "${release_extra}" "${SIGNER}" "${container}"

race_parent="${container}/race"; race_directory="${race_parent}/release"
/bin/mkdir -p "${race_directory}"; /bin/chmod 700 "${race_parent}" "${race_directory}"
make_file "${race_directory}/${SIXBRIDGE_ANDROID_FILE}" android
make_file "${race_directory}/${SIXBRIDGE_MACOS_FILE}" macos
make_file "${race_directory}/RELEASE-NOTES-KO.md" note
export FAKE_SHASUM_REPLACE_PATH="${race_directory}/${SIXBRIDGE_ANDROID_FILE}"
expect_failure metadata-input-race 'artifact changed during verification' release_write_metadata "${race_directory}" "${GIT_COMMIT}" "${SIGNER}" "${container}"
unset FAKE_SHASUM_REPLACE_PATH

atomic_root="${container}/atomic"; /bin/mkdir -p "${atomic_root}"; /bin/chmod 700 "${atomic_root}"
make_file "${atomic_root}/owned.tmp" owned; /bin/mkdir "${atomic_root}/destination"
expect_failure atomic-publish-failure 'release output' release_atomic_publish_file "${atomic_root}/owned.tmp" "${atomic_root}/destination"
[[ ! -e "${atomic_root}/owned.tmp" ]] || fail 'atomic publish 실패 뒤 소유 temp가 남았습니다.'

printf 'release-lib 검증 통과\n'
