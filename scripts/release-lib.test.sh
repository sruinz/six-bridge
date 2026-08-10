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
  saved=$(/bin/cat -- "${FAKE_APKSIGNER_SWAP_RESTORE_PATH}")
  printf 'replaced' >"${FAKE_APKSIGNER_SWAP_RESTORE_PATH}"
  printf '%s' "${saved}" >"${FAKE_APKSIGNER_SWAP_RESTORE_PATH}"
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
 attach)
   source=${!#}
   if [[ "${FAKE_HDIUTIL_REQUIRE_SNAPSHOT:-0}" = 1 ]]; then
     case "${source}" in */.sixbridge-release-snapshot.*/artifact) ;; *) exit 30;; esac
   fi
   [[ -z "${FAKE_HDIUTIL_REPLACE_PATH:-}" ]] || printf 'replaced' >"${FAKE_HDIUTIL_REPLACE_PATH}"
   if [[ -n "${FAKE_HDIUTIL_SWAP_RESTORE_PATH:-}" ]]; then
     saved=$(/bin/cat -- "${FAKE_HDIUTIL_SWAP_RESTORE_PATH}")
     printf 'replaced' >"${FAKE_HDIUTIL_SWAP_RESTORE_PATH}"
     printf '%s' "${saved}" >"${FAKE_HDIUTIL_SWAP_RESTORE_PATH}"
   fi
   shift
   while [[ "$#" -gt 0 ]]; do [[ "$1" = -mountpoint ]] && { mountpoint=$2; shift 2; } || shift; done
   /bin/mkdir -p "${mountpoint}/SIX Bridge.app/Contents"
   : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
   [[ "${FAKE_EXTRA_APP:-0}" != 1 ]] || /bin/mkdir -p "${mountpoint}/Injected.app/Contents"
   [[ "${FAKE_HDIUTIL_PARTIAL_ATTACH:-0}" != 1 ]] || exit 31
   ;;
 detach)
   [[ -z "${FAKE_HDIUTIL_DETACH_LOG:-}" ]] || printf 'detach\n' >>"${FAKE_HDIUTIL_DETACH_LOG}"
   /bin/rm -rf "$2"
   ;;
 *) exit 22;;
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

fake_java_home="${FIXTURE_ROOT}/fake-java-home"
/bin/mkdir -p "${fake_java_home}/bin"
/bin/cat >"${fake_java_home}/bin/java" <<'EOF'
#!/usr/bin/env bash
joined=" $* "
case "${joined}" in
  *apksigner.jar*)
    printf '%s\n' 'FAKE_JVM_USED'
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FAKE_SIGNER}"
    ;;
  *com.android.tools.apk.analyzer.ApkAnalyzerCli*)
    printf '%s\n' 'FAKE_JVM_USED'
    case "${joined}" in
      *' application-id '*) printf '%s\n' "${FAKE_APPLICATION_ID}" ;;
      *' version-name '*) printf '%s\n' "${FAKE_VERSION_NAME}" ;;
      *' version-code '*) printf '%s\n' "${FAKE_VERSION_CODE}" ;;
      *) exit 42 ;;
    esac
    ;;
  *) exit 41 ;;
esac
EOF
/bin/chmod +x "${fake_java_home}/bin/java"

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
release_bind_project_root "${ROOT_DIR}"

[[ "$(release_linux_stat_format '%g')" = '%g' ]] || \
    fail 'Linux stat group format mapping이 올바르지 않습니다.'

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

release_run_apksigner() {
    "${SIXBRIDGE_APKSIGNER}" "$@"
}

release_run_apkanalyzer() {
    "${SIXBRIDGE_APKANALYZER}" "$@"
}

make_fake_android_sdk() {
    local sdk_root=$1
    /bin/mkdir -p "${sdk_root}/build-tools/36.0.0/lib" "${sdk_root}/cmdline-tools/latest/lib"
    /bin/chmod 700 "${sdk_root}" "${sdk_root}/build-tools" "${sdk_root}/build-tools/36.0.0" \
        "${sdk_root}/build-tools/36.0.0/lib" "${sdk_root}/cmdline-tools" \
        "${sdk_root}/cmdline-tools/latest" "${sdk_root}/cmdline-tools/latest/lib"
    make_file "${sdk_root}/build-tools/36.0.0/source.properties" 'Pkg.Revision=36.0.0'
    make_file "${sdk_root}/build-tools/36.0.0/lib/apksigner.jar" jar
    make_file "${sdk_root}/cmdline-tools/latest/lib/apkanalyzer-classpath.jar" jar
    make_file "${sdk_root}/cmdline-tools/latest/lib/dependency.jar" jar
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

fake_sdk="${FIXTURE_ROOT}/fake-sdk"
make_fake_android_sdk "${fake_sdk}"
release_validate_android_sdk "${fake_sdk}"
fake_external_lib="${FIXTURE_ROOT}/fake-external-build-tools-lib"
/bin/mv "${fake_sdk}/build-tools/36.0.0/lib" "${fake_external_lib}"
/bin/ln -s "${fake_external_lib}" "${fake_sdk}/build-tools/36.0.0/lib"
expect_failure sdk-build-tools-lib-symlink 'symbolic link가 아닌 directory' release_validate_android_sdk "${fake_sdk}"
/bin/rm "${fake_sdk}/build-tools/36.0.0/lib"
/bin/mv "${fake_external_lib}" "${fake_sdk}/build-tools/36.0.0/lib"
/bin/chmod 100 "${fake_sdk}/cmdline-tools/latest/lib"
expect_failure sdk-classpath-find 'Android SDK classpath 열거' release_validate_android_sdk "${fake_sdk}"
/bin/chmod 700 "${fake_sdk}/cmdline-tools/latest/lib"
release_validate_android_sdk "${fake_sdk}"

apk="${stage}/fixture.apk"; dmg="${stage}/fixture.dmg"
make_file "${apk}" apk; make_file "${dmg}" dmg
expect_failure production-apk-launcher-boundary 'APK signature verification' \
  env PATH="${fake_java_home}/bin:/usr/bin:/bin" JAVA_HOME="${fake_java_home}" \
  JAVA_OPTS='-Dfake=true' APKANALYZER_OPTS='-Dfake=true' JAVA_TOOL_OPTIONS='-Dfake=true' \
  JDK_JAVA_OPTIONS='-Dfake=true' BASH_ENV=/dev/null ENV=/dev/null CLASSPATH=/tmp/fake-classpath \
  SKIP_JDK_VERSION_CHECK=1 bash -c 'source "$1"; release_bind_project_root "$2"; release_verify_apk "$3" "$4" "$5" "$6"' _ \
  "${LIBRARY}" "${ROOT_DIR}" "${apk}" "${SIGNER}" "${stage}" "${container}"
production_apkanalyzer_log="${FIXTURE_ROOT}/production-apkanalyzer-boundary.log"
set +e
env PATH="${fake_java_home}/bin:/usr/bin:/bin" JAVA_HOME="${fake_java_home}" \
  JAVA_OPTS='-Dfake=true' APKANALYZER_OPTS='-Dfake=true' JAVA_TOOL_OPTIONS='-Dfake=true' \
  JDK_JAVA_OPTIONS='-Dfake=true' BASH_ENV=/dev/null ENV=/dev/null CLASSPATH=/tmp/fake-classpath \
  SKIP_JDK_VERSION_CHECK=1 bash -c 'source "$1"; release_bind_project_root "$2"; release_run_apkanalyzer manifest application-id "$3"' _ \
  "${LIBRARY}" "${ROOT_DIR}" "${apk}" >"${production_apkanalyzer_log}" 2>&1
production_apkanalyzer_status=$?
set -e
[[ "${production_apkanalyzer_status}" -ne 0 ]] || fail 'production apkanalyzer가 비-APK 입력을 허용했습니다.'
assert_not_contains "${production_apkanalyzer_log}" 'FAKE_JVM_USED' 'production apkanalyzer가 PATH/JAVA_HOME의 JVM을 사용했습니다.'
snapshot_check_dir=$(release_create_verification_snapshot "${apk}" "${container}")
snapshot_check="${snapshot_check_dir}/artifact"
[[ "$(release_link_count "${apk}")" = 1 && "$(release_link_count "${snapshot_check}")" = 1 ]] || fail 'verification snapshot이 독립 regular file이 아닙니다.'
printf 'mutated' >"${apk}"
[[ "$(/bin/cat -- "${snapshot_check}")" = apk ]] || fail 'verification snapshot이 원본 쓰기의 영향을 받습니다.'
make_file "${apk}" apk
release_remove_verification_snapshot "${snapshot_check_dir}"
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
for attempt in 1 2 3 4 5 6 7 8; do
  release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"
done
unset FAKE_APKSIGNER_SWAP_RESTORE_PATH FAKE_APKSIGNER_REQUIRE_SNAPSHOT
outside="${FIXTURE_ROOT}/outside.apk"; make_file "${outside}" apk
expect_failure apk-outside-stage 'stage root 밖' release_verify_apk "${outside}" "${SIGNER}" "${stage}" "${container}"

dmg_work="${container}/mounts"; /bin/mkdir "${dmg_work}"; /bin/chmod 700 "${dmg_work}"
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
partial_attach_log="${FIXTURE_ROOT}/partial-attach.log"; : >"${partial_attach_log}"
export FAKE_HDIUTIL_PARTIAL_ATTACH=1 FAKE_HDIUTIL_DETACH_LOG="${partial_attach_log}"
expect_failure dmg-partial-attach 'DMG read-only mount' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
unset FAKE_HDIUTIL_PARTIAL_ATTACH FAKE_HDIUTIL_DETACH_LOG
assert_contains "${partial_attach_log}" detach 'partial attach 뒤 detach가 실행되지 않았습니다.'
remaining_dmg_work=$(/usr/bin/find "${dmg_work}" -mindepth 1 -maxdepth 1 -print -quit)
[[ -z "${remaining_dmg_work}" ]] || fail 'partial attach 뒤 임시 mount 또는 snapshot이 남았습니다.'
export FAKE_HDIUTIL_REQUIRE_SNAPSHOT=1 FAKE_HDIUTIL_SWAP_RESTORE_PATH="${dmg}"
for attempt in 1 2 3 4 5 6 7 8; do
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
done
unset FAKE_HDIUTIL_REQUIRE_SNAPSHOT FAKE_HDIUTIL_SWAP_RESTORE_PATH
export FAKE_HDIUTIL_REPLACE_PATH="${dmg}"
expect_failure dmg-race 'artifact changed during verification' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
unset FAKE_HDIUTIL_REPLACE_PATH
make_file "${dmg}" dmg
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

fifo_scan_path="${scan_root}/rebound-fifo"
make_file "${fifo_scan_path}" safe
fifo_expected_identity=$(release_file_identity "${fifo_scan_path}")
/bin/rm "${fifo_scan_path}"
/usr/bin/mkfifo "${fifo_scan_path}"
fifo_status_file="${FIXTURE_ROOT}/fifo-scan.status"
(
  set +e
  release_file_contains_secret "${fifo_scan_path}" probe "${fifo_expected_identity}"
  printf '%s\n' "$?" >"${fifo_status_file}"
) &
fifo_scan_pid=$!
/bin/sleep 0.2
if /bin/kill -0 "${fifo_scan_pid}" 2>/dev/null; then
  /bin/kill -TERM "${fifo_scan_pid}" 2>/dev/null || true
  wait "${fifo_scan_pid}" 2>/dev/null || true
  fail 'path가 FIFO로 바뀐 binary secret scanner가 block되었습니다.'
fi
wait "${fifo_scan_pid}" 2>/dev/null || true
[[ "$(/bin/cat "${fifo_status_file}")" = 2 ]] || fail 'FIFO scanner 오류가 clean으로 처리되었습니다.'
/bin/rm "${fifo_scan_path}"

safe_identity=$(release_file_identity "${scan_root}/safe.txt")
set +e
release_file_contains_secret "${scan_root}/safe.txt" '' "${safe_identity}"
empty_secret_status=$?
release_file_contains_secret "${scan_root}/safe.txt" probe ''
empty_identity_status=$?
set -e
[[ "${empty_secret_status}" = 2 && "${empty_identity_status}" = 2 ]] || \
  fail '비어 있는 scanner secret/identity가 clean 상태로 처리되었습니다.'

binary_secret='binary-secret-probe'
printf 'prefix\0%s\0suffix' "${binary_secret}" >"${scan_root}/binary-secret.bin"
expect_failure scan-binary-secret '비밀정보' release_scan_tree \
  "${scan_root}" "${stage}" "${container}" "${binary_secret}"
/bin/rm "${scan_root}/binary-secret.bin"

printf 'prefix\0\0%s\0\0suffix' "${binary_secret}" >"${scan_root}/multiple-nul-secret.bin"
expect_failure scan-multiple-nul-secret '비밀정보' release_scan_tree \
  "${scan_root}" "${stage}" "${container}" "${binary_secret}"
/bin/rm "${scan_root}/multiple-nul-secret.bin"

newline_secret=$'line-one\nline-two'
printf 'prefix\0%s\0suffix' "${newline_secret}" >"${scan_root}/newline-secret.bin"
expect_failure scan-newline-secret '비밀정보' release_scan_tree \
  "${scan_root}" "${stage}" "${container}" "${newline_secret}"
/bin/rm "${scan_root}/newline-secret.bin"

printf '%65535s' '' | /usr/bin/tr ' ' a >"${scan_root}/chunk-boundary-secret.bin"
printf '%s' "${binary_secret}" >>"${scan_root}/chunk-boundary-secret.bin"
expect_failure scan-chunk-boundary-secret '비밀정보' release_scan_tree \
  "${scan_root}" "${stage}" "${container}" "${binary_secret}"
/bin/rm "${scan_root}/chunk-boundary-secret.bin"

printf '%131072s' '' | /usr/bin/tr ' ' a >"${scan_root}/single-byte-clean.bin"
release_scan_tree "${scan_root}" "${stage}" "${container}" Z
/bin/rm "${scan_root}/single-byte-clean.bin"

ancestor_fixture="${FIXTURE_ROOT}/trusted-system-ancestor"
/bin/mkdir "${ancestor_fixture}"
/bin/chmod 755 "${ancestor_fixture}"
release_trusted_ancestor_directory "${ancestor_fixture}" "$(/usr/bin/id -u)"
/bin/chmod 775 "${ancestor_fixture}"
expect_failure writable-system-ancestor 'group/other write' \
  release_trusted_ancestor_directory "${ancestor_fixture}" "$(/usr/bin/id -u)"
/bin/chmod 755 "${ancestor_fixture}"

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

for perl_contract in \
    '/usr/bin/perl|/System/Library/Perl/5.30|/System/Library/Perl/5.30/darwin-thread-multi-2level' \
    '/usr/bin/perl|/System/Library/Perl/5.34|/System/Library/Perl/5.34/darwin-thread-multi-2level'; do
    [[ "$(release_parse_system_perl_contract "${perl_contract}")" = "${perl_contract}" ]] || fail 'version-agnostic Perl contract parser가 canonical output을 보존하지 않았습니다.'
done
expect_failure perl-contract-invalid '출력 형식' release_parse_system_perl_contract '/tmp/perl|/System/Library/Perl/5.34|/System/Library/Perl/5.34/arch'

[[ "$(release_utf8_locale)" = UTF-8 ]] || fail 'release UTF-8 locale charmap 계약이 올바르지 않습니다.'
utf8_java="${FIXTURE_ROOT}/utf8-java"
utf8_java_log="${FIXTURE_ROOT}/utf8-java.log"
utf8_java_arg="${FIXTURE_ROOT}/한글경로/검증-대상.apk"
/bin/mkdir -p "$(/usr/bin/dirname -- "${utf8_java_arg}")"
/bin/cat >"${utf8_java}" <<'EOF'
#!/usr/bin/env bash
[[ "${LANG}" = C && "${LC_CTYPE}" = UTF-8 && -z "${LC_ALL+x}" ]] || exit 51
[[ "${1:-}" = --sixbridge-locale-probe ]] || exit 52
case "${2:-}" in
  *'한글경로/검증-대상.apk') ;;
  *) exit 53 ;;
esac
printf '%s\n' "${2}" >"${3}"
EOF
/bin/chmod 700 "${utf8_java}"
release_java_executable() { printf '%s\n' "${utf8_java}"; }
LC_ALL=C release_run_java --sixbridge-locale-probe "${utf8_java_arg}" "${utf8_java_log}"
[[ "$(/bin/cat -- "${utf8_java_log}")" = "${utf8_java_arg}" ]] || \
    fail 'release_run_java가 한글 경로 argv를 정확히 보존하지 않았습니다.'

printf 'release-lib 검증 통과\n'
