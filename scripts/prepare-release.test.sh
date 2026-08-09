#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly PREPARE_SCRIPT="${ROOT_DIR}/scripts/prepare-release.sh"
readonly FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-prepare-release-test.XXXXXX")"
readonly RELEASE_PASSWORD='fixture-release-password'
readonly SECRET_PROBE='fixture-secret-probe'
readonly EXPECTED_COMMIT='abcdef0123456789abcdef0123456789abcdef01'

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

assert_exact_release_files() {
    local final=$1 actual expected
    actual="$(find "${final}" -maxdepth 1 -mindepth 1 -type f -exec basename {} \; | LC_ALL=C sort)"
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
        jq) printf '%s\n' /usr/bin/jq ;;
        shasum) printf '%s\n' /usr/bin/shasum ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}

release_run_apksigner() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apksigner" "$@"; }
release_run_apkanalyzer() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apkanalyzer" "$@"; }
EOF
}

create_fixture_tools() {
    local root=$1
    local bin="${root}/bin"
    mkdir -p "${bin}"

    cat >"${bin}/git" <<'EOF'
#!/usr/bin/env bash
[[ "$*" = 'rev-parse HEAD' ]] || exit 81
printf '%s\n' 'abcdef0123456789abcdef0123456789abcdef01'
EOF
    cat >"${bin}/keytool" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${SIXBRIDGE_PREPARE_LOG}"
[[ "${SIXBRIDGE_PREPARE_KEYTOOL_FAIL:-0}" != 1 ]] || exit 61
printf '%s\n' 'SHA256: 01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF'
EOF
    cat >"${bin}/apksigner" <<'EOF'
#!/usr/bin/env bash
[[ "${SIXBRIDGE_PREPARE_APK_VERIFY_FAIL:-0}" != 1 ]] || exit 62
printf '%s\n' 'Signer #1 certificate SHA-256 digest: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
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
    chmod +x "${bin}"/* "${root}/scripts/verify.sh" "${root}/gradlew"
}

setup_case() {
    local name=$1
    CASE_ROOT="${FIXTURE_ROOT}/${name}"
    CASE_LOG="${CASE_ROOT}/calls.log"
    rm -rf "${CASE_ROOT}"
    mkdir -p "${CASE_ROOT}/scripts" "${CASE_ROOT}/docs/releases" "${CASE_ROOT}/.local/keystore"
    chmod 700 "${CASE_ROOT}/.local" "${CASE_ROOT}/.local/keystore"
    : >"${CASE_LOG}"
    copy_fixture_library "${CASE_ROOT}"
    create_fixture_tools "${CASE_ROOT}"
    cp "${ROOT_DIR}/docs/releases/v0.1.0-ko.md" "${CASE_ROOT}/docs/releases/v0.1.0-ko.md"
    printf '%s\n' key-material >"${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
    chmod 600 "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
}

run_prepare() {
    (
        cd "${CASE_ROOT}"
        env \
            PATH="${CASE_ROOT}/bin:${PATH}" \
            SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
            SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" \
            SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
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

setup_case invalid-version
expect_failure invalid-version '지원하지 않는 release version' \
    env PATH="${CASE_ROOT}/bin:${PATH}" SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${PREPARE_SCRIPT}" 9.9.9
assert_no_final_directory "${CASE_ROOT}"

setup_case missing-key
rm "${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
expect_failure missing-key 'regular file' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case missing-credential
expect_failure missing-credential 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' \
    env -u SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
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
expect_failure verify-failure '전체 검증' run_prepare env SIXBRIDGE_PREPARE_VERIFY_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case android-failure
expect_failure android-failure 'Android release build' run_prepare env SIXBRIDGE_PREPARE_ANDROID_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case dmg-build-failure
expect_failure dmg-build-failure 'macOS DMG build' run_prepare env SIXBRIDGE_PREPARE_DMG_BUILD_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case artifact-verification-failure
expect_failure artifact-verification-failure 'APK' run_prepare env SIXBRIDGE_PREPARE_APK_VERIFY_FAIL=1
assert_no_final_directory "${CASE_ROOT}"

setup_case success-one
run_prepare
final_one="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${final_one}"
assert_call_count "${CASE_LOG}" verify
assert_call_count "${CASE_LOG}" ':android-manager:assembleRelease'
assert_call_count "${CASE_LOG}" '-Pcompose.desktop.packaging.checkJdkVendor=false :desktop-app:packageDmg'
assert_not_contains "${CASE_LOG}" "${RELEASE_PASSWORD}" '호출 log에 실제 비밀번호가 기록되었습니다.'
if rg -n --hidden --glob '!*' "${SECRET_PROBE}|${RELEASE_PASSWORD}" "${final_one}" >/dev/null; then
    fail '정상 release directory에 secret probe 또는 비밀번호가 포함되었습니다.'
fi

setup_case success-two
run_prepare
final_two="${CASE_ROOT}/.local/releases/v0.1.0"
assert_exact_release_files "${final_two}"
cmp "${final_one}/RELEASE-METADATA.json" "${final_two}/RELEASE-METADATA.json" || fail '두 root의 metadata가 결정적이지 않습니다.'
cmp "${final_one}/SHA256SUMS" "${final_two}/SHA256SUMS" || fail '두 root의 checksum이 결정적이지 않습니다.'
assert_contains "${final_one}/RELEASE-METADATA.json" "${EXPECTED_COMMIT}" 'metadata에 현재 git commit이 없습니다.'

printf 'prepare release 검증 통과\n'
