#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly PREPARE_SCRIPT="${ROOT_DIR}/scripts/prepare-release.sh"
readonly FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-prepare-release-test.XXXXXX")"
readonly RELEASE_PASSWORD='fixture-release-password'
readonly SECRET_PROBE='fixture-secret-probe'
readonly TEMPLATE_KEYSTORE="${FIXTURE_ROOT}/fixture-release.p12"
readonly PRIVATE_TMP_RELEASE_ENTRIES_BEFORE="$(find /private/tmp -maxdepth 1 -name 'sixbridge-release-*' -print 2>/dev/null | LC_ALL=C sort)"

cleanup() { rm -rf "${FIXTURE_ROOT}"; }
trap cleanup EXIT

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file=$1 expected=$2 message=$3
    grep -Fq -- "${expected}" "${file}" || {
        cat "${file}" >&2
        fail "${message}"
    }
}

assert_not_contains() {
    local file=$1 unexpected=$2 message=$3
    if grep -Fq -- "${unexpected}" "${file}"; then
        cat "${file}" >&2
        fail "${message}"
    fi
}

assert_no_final_directory() {
    local root=$1
    local final="${root}/.local/releases/v0.1.0"
    [[ ! -e "${final}" && ! -L "${final}" ]] || fail "실패한 경우 final release directory가 남았습니다: ${final}"
}

assert_no_release_work_entries() {
    local root=$1 entries
    [[ -d "${root}/.local/releases" ]] || return 0
    entries=$(find "${root}/.local/releases" -name '.sixbridge-*' -print)
    [[ -z "${entries}" ]] || fail "실패한 prepare가 내부 release 작업 경로를 남겼습니다: ${entries}"
}

assert_log_has_no_secret() {
    local file=$1
    assert_not_contains "${file}" "${RELEASE_PASSWORD}" '실제 비밀번호가 출력되었습니다.'
    assert_not_contains "${file}" "${SECRET_PROBE}" 'secret probe가 출력되었습니다.'
}

assert_exact_release_files() {
    local final=$1 actual expected
    actual="$(find "${final}" -maxdepth 1 -mindepth 1 -exec basename {} \; | LC_ALL=C sort)"
    expected=$'RELEASE-METADATA.json\nRELEASE-NOTES-KO.md\nSHA256SUMS\nSIX-Bridge-0.1.0-macos-unsigned.dmg\nSIX-Bridge-Manager-0.1.0-android.apk'
    [[ "${actual}" = "${expected}" ]] || fail "release 파일 구성이 정확히 다섯 개가 아닙니다: ${actual}"
}

assert_call_count() {
    local log=$1 expected=$2 count
    count=$(grep -Fxc -- "${expected}" "${log}" || true)
    [[ "${count}" = 1 ]] || fail "호출 횟수가 기대와 다릅니다 (${expected}): ${count}"
}

copy_fixture_library() {
    local root=$1
    cp "${ROOT_DIR}/scripts/release-lib.sh" "${root}/scripts/release-lib.sh"
    cat >>"${root}/scripts/release-lib.sh" <<'EOF'

# Fixture-only tool boundary. Production release-lib.sh has no environment tool
# override; this copied fixture replaces only external build-artifact probes.
release_tool() {
    case "$1" in
        apksigner) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apksigner" ;;
        apkanalyzer) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apkanalyzer" ;;
        hdiutil) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/hdiutil" ;;
        plistbuddy) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/PlistBuddy" ;;
        codesign) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/codesign" ;;
        jq) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/jq" ;;
        shasum) printf '%s\n' /usr/bin/shasum ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}

release_run_apksigner() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apksigner" "$@"; }
release_run_apkanalyzer() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apkanalyzer" "$@"; }
release_java_executable() { printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/jdk/bin/java"; }

eval "$(declare -f release_verify_directory | /usr/bin/sed '1s/release_verify_directory/release_verify_directory_real/')"
release_verify_directory() {
    local directory=$1
    release_verify_directory_real "$@" || return 1
    case "${SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION:-}" in
        content) printf '%s\n' tampered >>"${directory}/${SIXBRIDGE_ANDROID_FILE}" ;;
        directory) /bin/mkdir "${directory}/unexpected-directory" ;;
        symlink) /bin/ln -s nowhere "${directory}/unexpected-symlink" ;;
        '') ;;
        *) return 97 ;;
    esac
    case "${SIXBRIDGE_PREPARE_PUBLISH_BARRIER:-}" in
        '') ;;
        content|directory|final)
            case "${directory##*/}" in
                .sixbridge-release-publish.*)
                    : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-publish-barrier-fired"
                    case "${SIXBRIDGE_PREPARE_PUBLISH_BARRIER}" in
                        content) printf '%s\n' snapshot-barrier-tamper >>"${STAGE_DIR}/${SIXBRIDGE_ANDROID_FILE}" ;;
                        directory) /bin/mkdir "${STAGE_DIR}/snapshot-barrier-directory" ;;
                        final)
                            /bin/mkdir "${FINAL_DIR}"
                            printf '%s\n' sentinel >"${FINAL_DIR}/sentinel"
                            ;;
                    esac
                    ;;
            esac
            ;;
        *) return 96 ;;
    esac
}

release_before_atomic_publish() {
    local rebound entry source_leaf
    case "${SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER:-}" in
        '') ;;
        content)
            : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired"
            printf '%s\n' native-publish-tamper >>"${PUBLISH_DIR}/${SIXBRIDGE_ANDROID_FILE}"
            ;;
        directory)
            : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired"
            /bin/mkdir "${PUBLISH_DIR}/native-publish-directory"
            ;;
        rebind)
            : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired"
            rebound="${PUBLISH_DIR}.fixture-rebound"
            /bin/mv "${PUBLISH_DIR}" "${rebound}"
            /bin/mkdir "${PUBLISH_DIR}"
            for entry in "${SIXBRIDGE_ANDROID_FILE}" "${SIXBRIDGE_MACOS_FILE}" SHA256SUMS RELEASE-METADATA.json RELEASE-NOTES-KO.md; do
                /bin/mv "${rebound}/${entry}" "${PUBLISH_DIR}/${entry}"
            done
            /bin/rmdir "${rebound}"
            : >"${PUBLISH_DIR}/external-rebind-sentinel"
            ;;
        container-rebind)
            : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired"
            source_leaf=${PUBLISH_DIR##*/}
            rebound="${RELEASE_CONTAINER}.fixture-rebound"
            /bin/mv "${RELEASE_CONTAINER}" "${rebound}"
            /bin/mkdir "${RELEASE_CONTAINER}"
            /bin/mv "${rebound}/${source_leaf}" "${RELEASE_CONTAINER}/${source_leaf}"
            : >"${RELEASE_CONTAINER}/external-container-sentinel"
            ;;
        *) return 95 ;;
    esac
}
EOF
}

create_fixture_tools() {
    local root=$1
    local bin="${root}/bin"
    mkdir -p "${bin}"
    mkdir -p "${bin}/jdk/bin"

    cat >"${bin}/git" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-GIT $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exec /usr/bin/git "$@"
EOF
    cat >"${bin}/keytool" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-KEYTOOL $*" >>"${SIXBRIDGE_PREPARE_LOG}"
[[ "${SIXBRIDGE_PREPARE_KEYTOOL_FAIL:-0}" != 1 ]] || exit 61
printf '%s\n' 'SHA256: 01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF'
EOF
    cat >"${bin}/env" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-ENV $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exec /usr/bin/env "$@"
EOF
    cat >"${bin}/perl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-PERL $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exit 99
EOF
    cat >"${bin}/FixtureLeak.pm" <<'EOF'
BEGIN {
    open(my $marker, '>', $ENV{SIXBRIDGE_PERL_STARTUP_MARKER}) or die $!;
    print {$marker} $ENV{SIXBRIDGE_PERL_STARTUP_SECRET};
    close $marker or die $!;
}
1;
EOF
    cat >"${bin}/jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
exit 98
EOF
cat >"${bin}/jdk/bin/keytool" <<'EOF'
#!/usr/bin/env bash
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" = -keystore ]]; then
    keystore=$2
    break
  fi
  shift
done
[[ "$(cat "${keystore}")" != keytool-fail ]] || exit 61
printf '%s\n' 'SHA256: 01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF'
EOF
    cat >"${bin}/apksigner" <<'EOF'
#!/usr/bin/env bash
[[ "${SIXBRIDGE_PREPARE_APK_VERIFY_FAIL:-0}" != 1 ]] || exit 62
if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = 1 ]]; then
  printf '%s\n' "${TMPDIR}" >"${SIXBRIDGE_PREPARE_TMPDIR_RECORD}"
  : >"${TMPDIR}/sixbridge-release-fixture-child-temp"
  /bin/sleep 600 &
  printf '%s\n' "$$ $!" >"${SIXBRIDGE_PREPARE_CHILD_PID}"
  : >"${SIXBRIDGE_PREPARE_READY}"
  wait "$!"
fi
printf '%s\n' "Signer #1 certificate SHA-256 digest: ${SIXBRIDGE_PREPARE_SIGNER}"
EOF
    cat >"${bin}/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  application-id) printf '%s\n' dev.sixbridge.manager ;;
  version-name) printf '%s\n' 0.1.0 ;;
  version-code) printf '%s\n' 1 ;;
  *) exit 63 ;;
esac
EOF
    cat >"${bin}/hdiutil" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  attach)
    [[ "${SIXBRIDGE_PREPARE_DMG_VERIFY_FAIL:-0}" != 1 ]] || exit 64
    shift
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" = -mountpoint ]]; then mountpoint=$2; shift 2; else shift; fi
    done
    mkdir -p "${mountpoint}/SIX Bridge.app/Contents"
    : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
    ;;
  detach) rm -rf "$2" ;;
  *) exit 65 ;;
esac
EOF
    cat >"${bin}/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  'Print :CFBundleIdentifier') printf '%s\n' dev.sixbridge.desktop ;;
  'Print :CFBundleShortVersionString') printf '%s\n' 0.1.0 ;;
  'Print :CFBundleVersion') printf '%s\n' 1.0.0 ;;
  'Print :LSMinimumSystemVersion') printf '%s\n' 13.0 ;;
  *) exit 66 ;;
esac
EOF
    cat >"${bin}/codesign" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'code object is not signed at all' >&2
exit 1
EOF
    cat >"${bin}/jq" <<'EOF'
#!/usr/bin/env bash
[[ "${SIXBRIDGE_PREPARE_JQ_FAIL:-0}" != 1 ]] || exit 67
exec /usr/bin/jq "$@"
EOF
    cat >"${root}/scripts/verify.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' verify >>"${SIXBRIDGE_PREPARE_LOG}"
[[ "${SIXBRIDGE_PREPARE_VERIFY_FAIL:-0}" != 1 ]]
EOF
    cat >"${root}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${SIXBRIDGE_PREPARE_LOG}"
case " $* " in
  *' :android-manager:assembleRelease '*)
    [[ "${SIXBRIDGE_PREPARE_ANDROID_FAIL:-0}" != 1 ]] || exit 71
    mkdir -p android-manager/build/outputs/apk/release
    printf '%s\n' android-artifact >android-manager/build/outputs/apk/release/android-manager-release.apk
    if [[ "${SIXBRIDGE_PREPARE_MUTATE_SOURCE:-0}" = 1 ]]; then
      printf '%s\n' source-mutation >>docs/releases/v0.1.0-ko.md
    fi
    if [[ "${SIXBRIDGE_PREPARE_MUTATE_HEAD:-0}" = 1 ]]; then
      /usr/bin/git -c user.name=fixture -c user.email=fixture@example.invalid commit --allow-empty -qm fixture-head-mutation
    fi
    ;;
  *' :desktop-app:packageDmg '*)
    [[ -z "${SIXBRIDGE_MAC_SIGNING_IDENTITY:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_PASSWORD:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID:-}" ]] || exit 72
    [[ "${SIXBRIDGE_PREPARE_DMG_BUILD_FAIL:-0}" != 1 ]] || exit 73
    mkdir -p 'desktop-app/build/compose/binaries/main/dmg'
    printf '%s\n' dmg-artifact >'desktop-app/build/compose/binaries/main/dmg/SIX Bridge-1.0.0.dmg'
    ;;
  *) exit 74 ;;
esac
EOF
    chmod +x "${bin}"/* "${bin}/jdk/bin/"* "${root}/scripts/verify.sh" "${root}/gradlew"
}

setup_case() {
    local name=$1
    CASE_ROOT="${FIXTURE_ROOT}/${name}"
    CASE_LOG="${CASE_ROOT}/.local/calls.log"
    rm -rf "${CASE_ROOT}"
    mkdir -p "${CASE_ROOT}/scripts" "${CASE_ROOT}/docs/releases" "${CASE_ROOT}/.local/keystore"
    chmod 700 "${CASE_ROOT}/.local" "${CASE_ROOT}/.local/keystore"
    : >"${CASE_LOG}"
    copy_fixture_library "${CASE_ROOT}"
    create_fixture_tools "${CASE_ROOT}"
    [[ ! -e "${ROOT_DIR}/scripts/release-atomic-publish.pl" ]] || \
        cp "${ROOT_DIR}/scripts/release-atomic-publish.pl" "${CASE_ROOT}/scripts/release-atomic-publish.pl"
    cp "${ROOT_DIR}/docs/releases/v0.1.0-ko.md" "${CASE_ROOT}/docs/releases/v0.1.0-ko.md"
    cp "${TEMPLATE_KEYSTORE}" "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
    chmod 600 "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
    printf '%s\n' '.local/' '**/build/' >"${CASE_ROOT}/.gitignore"
    /usr/bin/git -C "${CASE_ROOT}" init -q
    /usr/bin/git -C "${CASE_ROOT}" add .
    GIT_AUTHOR_DATE='2026-08-09T00:00:00+0000' \
        GIT_COMMITTER_DATE='2026-08-09T00:00:00+0000' \
        /usr/bin/git -C "${CASE_ROOT}" -c user.name=fixture -c user.email=fixture@example.invalid \
            commit -qm fixture
}

run_prepare() {
    (
        cd "${CASE_ROOT}"
        exec /usr/bin/env \
            PATH="${CASE_ROOT}/bin:${PATH}" \
            SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
            SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" \
            SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
            SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}" \
            SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
            SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
            "$@" \
            "${PREPARE_SCRIPT}" 0.1.0
    )
}

expect_failure() {
    local label=$1 expected=$2
    shift 2
    local output="${FIXTURE_ROOT}/${label}.log" status
    set +e
    "$@" >"${output}" 2>&1
    status=$?
    set -e
    [[ "${status}" -ne 0 ]] || { cat "${output}" >&2; fail "${label}: 실패해야 할 prepare가 성공했습니다."; }
    assert_contains "${output}" "${expected}" "${label}: 예상한 거부 사유가 없습니다."
    assert_not_contains "${output}" "${RELEASE_PASSWORD}" "${label}: 실제 비밀번호가 출력되었습니다."
    assert_not_contains "${output}" "${SECRET_PROBE}" "${label}: secret probe가 출력되었습니다."
}

printf '%s\n' key-material >"${TEMPLATE_KEYSTORE}"
chmod 600 "${TEMPLATE_KEYSTORE}"
readonly FIXTURE_SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

setup_case invalid-version
expect_failure invalid-version '지원하지 않는 release version' \
    /usr/bin/env PATH="${CASE_ROOT}/bin:${PATH}" SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${PREPARE_SCRIPT}" 9.9.9
assert_no_final_directory "${CASE_ROOT}"

setup_case missing-key
rm "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
expect_failure missing-key 'regular file' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case weak-keystore-mode
chmod 644 "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
expect_failure weak-keystore-mode '0600' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case keystore-hardlink
ln "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12" "${CASE_ROOT}/.local/keystore/second.p12"
expect_failure keystore-hardlink 'hardlink' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case keystore-parent-symlink
outside_key_dir="${FIXTURE_ROOT}/outside-keystore"
mkdir -p "${outside_key_dir}"
cp "${TEMPLATE_KEYSTORE}" "${outside_key_dir}/six-bridge-manager-release.p12"
chmod 600 "${outside_key_dir}/six-bridge-manager-release.p12"
rm -rf "${CASE_ROOT}/.local/keystore"
ln -s "${outside_key_dir}" "${CASE_ROOT}/.local/keystore"
expect_failure keystore-parent-symlink 'symbolic link' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case missing-credential
expect_failure missing-credential 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' \
    /usr/bin/env -u SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
        PATH="${CASE_ROOT}/bin:${PATH}" SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${PREPARE_SCRIPT}" 0.1.0
assert_no_final_directory "${CASE_ROOT}"

setup_case existing-directory
mkdir -p "${CASE_ROOT}/.local/releases/v0.1.0"
expect_failure existing-directory '이미 존재' run_prepare
[[ -d "${CASE_ROOT}/.local/releases/v0.1.0" && ! -L "${CASE_ROOT}/.local/releases/v0.1.0" ]] || \
    fail '기존 release directory가 보존되지 않았습니다.'

setup_case dangling-symlink
mkdir -p "${CASE_ROOT}/.local/releases"
ln -s "${CASE_ROOT}/missing-final" "${CASE_ROOT}/.local/releases/v0.1.0"
expect_failure dangling-symlink '이미 존재' run_prepare
[[ -L "${CASE_ROOT}/.local/releases/v0.1.0" ]] || fail '기존 dangling symlink가 보존되지 않았습니다.'

setup_case verify-failure
expect_failure verify-failure '전체 검증' run_prepare /usr/bin/env SIXBRIDGE_PREPARE_VERIFY_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case android-failure
expect_failure android-failure 'Android release build' run_prepare /usr/bin/env SIXBRIDGE_PREPARE_ANDROID_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case dmg-build-failure
expect_failure dmg-build-failure 'macOS DMG build' run_prepare /usr/bin/env SIXBRIDGE_PREPARE_DMG_BUILD_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case artifact-verification-failure
expect_failure artifact-verification-failure 'APK' run_prepare /usr/bin/env SIXBRIDGE_PREPARE_APK_VERIFY_FAIL=1
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case keytool-failure
printf '%s\n' keytool-fail >"${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
expect_failure keytool-failure 'keytool' run_prepare
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case jq-failure
expect_failure jq-failure 'release metadata' run_prepare /usr/bin/env SIXBRIDGE_PREPARE_JQ_FAIL=1
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case dirty-start
printf '%s\n' dirty-start >>"${CASE_ROOT}/docs/releases/v0.1.0-ko.md"
expect_failure dirty-start 'clean git worktree' run_prepare
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case source-change-before-publish
expect_failure source-change-before-publish 'git worktree 또는 commit이 변경' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_MUTATE_SOURCE=1
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case head-change-before-publish
expect_failure head-change-before-publish 'git worktree 또는 commit이 변경' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_MUTATE_HEAD=1
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case post-verify-content-tamper
expect_failure post-verify-content-tamper 'artifact changed during verification' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION=content
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case post-verify-extra-directory
expect_failure post-verify-extra-directory '정확히 다섯 개' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION=directory
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case post-verify-extra-symlink
expect_failure post-verify-extra-symlink '정확히 다섯 개' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION=symlink
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case snapshot-barrier-content
run_prepare /usr/bin/env SIXBRIDGE_PREPARE_PUBLISH_BARRIER=content
[[ -e "${CASE_ROOT}/.local/releases/.fixture-publish-barrier-fired" ]] || fail 'snapshot publish barrier가 마지막 검증 뒤 실행되지 않았습니다.'
snapshot_content_final="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${snapshot_content_final}"
assert_not_contains "${snapshot_content_final}/SIX-Bridge-Manager-0.1.0-android.apk" 'snapshot-barrier-tamper' '검증 뒤 stage 변조가 final에 반영되었습니다.'

setup_case snapshot-barrier-directory
run_prepare /usr/bin/env SIXBRIDGE_PREPARE_PUBLISH_BARRIER=directory
[[ -e "${CASE_ROOT}/.local/releases/.fixture-publish-barrier-fired" ]] || fail 'snapshot publish barrier가 마지막 검증 뒤 실행되지 않았습니다.'
snapshot_directory_final="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${snapshot_directory_final}"

setup_case publish-final-insertion
expect_failure publish-final-insertion 'atomic no-replace' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_PUBLISH_BARRIER=final
[[ -e "${CASE_ROOT}/.local/releases/.fixture-publish-barrier-fired" ]] || fail 'external final insertion barrier가 실행되지 않았습니다.'
[[ "$(cat "${CASE_ROOT}/.local/releases/v0.1.0/sentinel")" = sentinel ]] || fail '외부 final sentinel이 보존되지 않았습니다.'
[[ -z "$(find "${CASE_ROOT}/.local/releases/v0.1.0" -name '.sixbridge-*' -print)" ]] || fail '기존 final 아래에 stage가 중첩되었습니다.'
assert_no_release_work_entries "${CASE_ROOT}"

setup_case native-publish-content-tamper
expect_failure native-publish-content-tamper 'atomic no-replace' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER=content
[[ -e "${CASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired" ]] || fail 'native publish content barrier가 실행되지 않았습니다.'
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case native-publish-extra-directory
expect_failure native-publish-extra-directory 'atomic no-replace' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER=directory
[[ -e "${CASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired" ]] || fail 'native publish directory barrier가 실행되지 않았습니다.'
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case native-publish-name-rebind
expect_failure native-publish-name-rebind 'atomic no-replace' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER=rebind
[[ -e "${CASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired" ]] || fail 'native publish name-rebind barrier가 실행되지 않았습니다.'
assert_no_final_directory "${CASE_ROOT}"
[[ -n "$(find "${CASE_ROOT}/.local/releases" -name external-rebind-sentinel -print)" ]] || \
    fail '외부 name-rebind directory를 cleanup이 삭제했습니다.'

setup_case native-publish-container-rebind
expect_failure native-publish-container-rebind 'atomic no-replace' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER=container-rebind
[[ -e "${CASE_ROOT}/.local/releases/external-container-sentinel" ]] || fail '외부 container sentinel이 보존되지 않았습니다.'

setup_case perl-startup-injection
perl_startup_marker="${CASE_ROOT}/perl-startup-marker"
run_prepare /usr/bin/env PERL5LIB="${CASE_ROOT}/bin" PERL5OPT='-MFixtureLeak' \
    SIXBRIDGE_PERL_STARTUP_MARKER="${perl_startup_marker}" SIXBRIDGE_PERL_STARTUP_SECRET="${SECRET_PROBE}"
[[ ! -e "${perl_startup_marker}" ]] || fail 'worker launcher Perl이 inherited startup module을 실행했습니다.'

setup_case success-one
run_prepare
final_one="${CASE_ROOT}/.local/releases/v0.1.0"
commit_one=$(/usr/bin/git -C "${CASE_ROOT}" rev-parse HEAD)
assert_exact_release_files "${final_one}"
assert_call_count "${CASE_LOG}" verify
assert_call_count "${CASE_LOG}" ':android-manager:assembleRelease'
assert_call_count "${CASE_LOG}" '-Pcompose.desktop.packaging.checkJdkVendor=false :desktop-app:packageDmg'
assert_not_contains "${CASE_LOG}" "${RELEASE_PASSWORD}" '호출 log에 실제 비밀번호가 기록되었습니다.'
if rg -n --hidden --glob '!*' "${SECRET_PROBE}|${RELEASE_PASSWORD}" "${final_one}" >/dev/null; then
    fail '정상 release directory에 secret probe 또는 비밀번호가 포함되었습니다.'
fi

for signal in HUP INT TERM; do
    setup_case "signal-${signal}"
    signal_log="${FIXTURE_ROOT}/signal-${signal}.log"
    signal_ready="${CASE_ROOT}/ready"
    signal_continue="${CASE_ROOT}/continue"
    signal_child="${CASE_ROOT}/child.pid"
    /usr/bin/perl -e '$SIG{HUP} = "DEFAULT"; $SIG{INT} = "DEFAULT"; $SIG{TERM} = "DEFAULT"; exec @ARGV or die "exec failed\n";' \
        /usr/bin/env \
        PATH="${CASE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" \
        SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_PREPARE_WAIT_IN_VERIFY=1 \
        SIXBRIDGE_PREPARE_READY="${signal_ready}" \
        SIXBRIDGE_PREPARE_CONTINUE="${signal_continue}" \
        SIXBRIDGE_PREPARE_CHILD_PID="${signal_child}" \
        SIXBRIDGE_PREPARE_TMPDIR_RECORD="${CASE_ROOT}/child-tmpdir" \
        "${PREPARE_SCRIPT}" 0.1.0 >"${signal_log}" 2>&1 &
    signal_pid=$!
    for _ in $(seq 1 100); do [[ -e "${signal_ready}" ]] && break; /bin/sleep 0.05; done
    [[ -e "${signal_ready}" ]] || fail "${signal} cleanup fixture가 verifier 대기 상태에 도달하지 못했습니다."
    kill -"${signal}" "${signal_pid}"
    for _ in $(seq 1 40); do kill -0 "${signal_pid}" 2>/dev/null || break; /bin/sleep 0.05; done
    if kill -0 "${signal_pid}" 2>/dev/null; then
        [[ -e "${signal_child}" ]] && kill -TERM $(cat "${signal_child}") 2>/dev/null || true
        wait "${signal_pid}" 2>/dev/null || true
        fail "${signal} signal 뒤 parent-only cleanup이 child를 즉시 종료하지 못했습니다."
    fi
    if wait "${signal_pid}"; then
        signal_status=0
        fail "${signal} signal 뒤 prepare가 성공했습니다."
    else
        signal_status=$?
    fi
    case "${signal}" in HUP) expected_signal_status=129 ;; INT) expected_signal_status=130 ;; TERM) expected_signal_status=143 ;; esac
    [[ "${signal_status}" = "${expected_signal_status}" ]] || fail "${signal} signal status가 표준값과 다릅니다: ${signal_status}"
    read -r signal_child_pid signal_grandchild_pid <"${signal_child}"
    kill -0 "${signal_child_pid}" 2>/dev/null && fail "${signal} signal 뒤 verifier child가 남았습니다."
    kill -0 "${signal_grandchild_pid}" 2>/dev/null && fail "${signal} signal 뒤 verifier process group child가 남았습니다."
    assert_no_final_directory "${CASE_ROOT}"
    assert_no_release_work_entries "${CASE_ROOT}"
    signal_tmpdir=$(cat "${CASE_ROOT}/child-tmpdir")
    signal_root=$(CDPATH= cd -P -- "${CASE_ROOT}" && pwd -P)
    case "${signal_tmpdir}" in "${signal_root}"/.local/releases/.sixbridge-release-tmp.*) ;; *) fail "${signal} child TMPDIR이 이번 release private work root가 아닙니다: ${signal_tmpdir}" ;; esac
    [[ ! -e "${signal_tmpdir}" && ! -L "${signal_tmpdir}" ]] || fail "${signal} signal 뒤 child TMPDIR이 남았습니다."
    [[ "$(find /private/tmp -maxdepth 1 -name 'sixbridge-release-*' -print 2>/dev/null | LC_ALL=C sort)" = "${PRIVATE_TMP_RELEASE_ENTRIES_BEFORE}" ]] || fail "${signal} signal 뒤 새 /private/tmp release temp가 남았습니다."
    assert_log_has_no_secret "${signal_log}"
done

setup_case eight-way-concurrency
concurrent_successes=0
concurrent_pids=''
for index in $(seq 1 8); do
    run_prepare >"${FIXTURE_ROOT}/concurrent-${index}.log" 2>&1 &
    concurrent_pids="${concurrent_pids} $!"
done
for concurrent_pid in ${concurrent_pids}; do
    if wait "${concurrent_pid}"; then
        concurrent_successes=$((concurrent_successes + 1))
    fi
done
[[ "${concurrent_successes}" = 1 ]] || fail "8-way prepare에서 성공은 정확히 하나여야 합니다: ${concurrent_successes}"
concurrent_final="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${concurrent_final}"
assert_no_release_work_entries "${CASE_ROOT}"
for index in $(seq 1 8); do assert_log_has_no_secret "${FIXTURE_ROOT}/concurrent-${index}.log"; done

setup_case success-two
set +e
run_prepare
success_two_status=$?
set -e
[[ "${success_two_status}" = 0 ]] || fail "success-two prepare exit status가 0이 아닙니다: ${success_two_status}"
final_two="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${final_two}"
cmp "${final_one}/RELEASE-METADATA.json" "${final_two}/RELEASE-METADATA.json" || fail '두 root의 metadata가 결정적이지 않습니다.'
cmp "${final_one}/SHA256SUMS" "${final_two}/SHA256SUMS" || fail '두 root의 checksum이 결정적이지 않습니다.'
assert_contains "${final_one}/RELEASE-METADATA.json" "${commit_one}" 'metadata에 현재 git commit이 없습니다.'

setup_case path-shim
run_prepare /usr/bin/env PERL5LIB="${CASE_ROOT}/bin" PERL5OPT='-Mstrict'
assert_not_contains "${CASE_LOG}" 'PATH-ENV' 'PATH env shim이 실행되었습니다.'
assert_not_contains "${CASE_LOG}" 'PATH-PERL' 'PATH perl shim이 실행되었습니다.'
assert_not_contains "${CASE_LOG}" 'PATH-KEYTOOL' 'PATH keytool shim이 실행되었습니다.'
assert_not_contains "${CASE_LOG}" 'PATH-GIT' 'PATH git shim이 실행되었습니다.'

printf 'prepare release 검증 통과\n'
