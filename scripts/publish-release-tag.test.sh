#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")/.." && pwd -P)"
readonly PUBLISH_SCRIPT="${ROOT_DIR}/scripts/publish-release-tag.sh"
readonly FIXTURE_ROOT="$(/usr/bin/mktemp -d "${ROOT_DIR}/.local/publish-release-tag-test.XXXXXX")"
readonly SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

cleanup() { /bin/rm -rf -- "${FIXTURE_ROOT}"; }
trap cleanup EXIT
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

assert_contains() {
    /usr/bin/grep -Fq -- "$2" "$1" || { /bin/cat -- "$1" >&2; fail "$3"; }
}

assert_not_contains() {
    ! /usr/bin/grep -Fq -- "$2" "$1" || { /bin/cat -- "$1" >&2; fail "$3"; }
}

assert_no_remote_tag() {
    ! /usr/bin/git --git-dir="${CASE_ORIGIN}" show-ref --verify --quiet refs/tags/v0.1.0 ||
        fail "${1}: remote tag가 생성되었습니다."
}

expect_failure() {
    local label=$1 expected=$2 input=$3 output status
    output="${FIXTURE_ROOT}/${label}.log"
    set +e
    if [[ "${input}" = EOF ]]; then
        run_publish </dev/null >"${output}" 2>&1
    else
        printf '%s\n' "${input}" | run_publish >"${output}" 2>&1
    fi
    status=$?
    set -e
    [[ "${status}" -ne 0 ]] || { /bin/cat -- "${output}" >&2; fail "${label}: 실패해야 할 게시가 성공했습니다."; }
    assert_contains "${output}" "${expected}" "${label}: 거부 사유가 없습니다."
}

write_fixture_library() {
    /bin/cp -- "${ROOT_DIR}/scripts/release-lib.sh" "${CASE_ROOT}/scripts/release-lib.sh"
    /bin/cat >>"${CASE_ROOT}/scripts/release-lib.sh" <<'EOF'

# Fixture-only external artifact probes. The publish entrypoint and all
# directory/metadata/checksum checks remain production code.
release_tool() {
    case "$1" in
        apksigner) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apksigner" ;;
        apkanalyzer) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apkanalyzer" ;;
        hdiutil) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/hdiutil" ;;
        plistbuddy) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/PlistBuddy" ;;
        codesign) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/codesign" ;;
        jq) printf '%s\n' /usr/bin/jq ;;
        shasum) printf '%s\n' /usr/bin/shasum ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}
release_run_apksigner() { "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apksigner" "$@"; }
release_run_apkanalyzer() { "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apkanalyzer" "$@"; }
release_before_tag_creation() {
    [[ "${SIXBRIDGE_PUBLISH_FIXTURE_RACE:-}" = head ]] || return 0
    printf '%s\n' "$1" >"${SIXBRIDGE_PUBLISH_FIXTURE_RACE_MARKER}"
    /usr/bin/git -C "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" commit --quiet --allow-empty -m fixture-head-race
}
release_before_tag_push() {
    local tag_oid=$1 other_commit
    [[ "${SIXBRIDGE_PUBLISH_FIXTURE_RACE:-}" = tag-ref ]] || return 0
    printf '%s\n' "${tag_oid}" >"${SIXBRIDGE_PUBLISH_FIXTURE_RACE_MARKER}"
    /usr/bin/git -C "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" commit --quiet --allow-empty -m fixture-tag-ref-race
    other_commit=$(/usr/bin/git -C "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" rev-parse HEAD)
    /usr/bin/git -C "${SIXBRIDGE_RELEASE_PROJECT_ROOT}" update-ref refs/tags/v0.1.0 "${other_commit}"
}
EOF
}

write_fixture_tools() {
    /bin/mkdir -p -- "${CASE_ROOT}/bin"
    /bin/cat >"${CASE_ROOT}/bin/git" <<'EOF'
#!/usr/bin/env bash
: >"${SIXBRIDGE_PUBLISH_PATH_GIT_MARKER}"
exec /usr/bin/git "$@"
EOF
    /bin/cat >"${CASE_ROOT}/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_SIGNER}"
EOF
    /bin/cat >"${CASE_ROOT}/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  application-id) printf '%s\n' dev.sixbridge.manager ;;
  version-name) printf '%s\n' 0.1.0 ;;
  version-code) printf '%s\n' 1 ;;
  *) exit 19 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/hdiutil" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  attach)
    shift
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" = -mountpoint ]]; then mountpoint=$2; shift 2; else shift; fi
    done
    /bin/mkdir -p "${mountpoint}/SIX Bridge.app/Contents"
    : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
    ;;
  detach) /bin/rm -rf -- "$2" ;;
  *) exit 20 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  'Print :CFBundleIdentifier') printf '%s\n' dev.sixbridge.desktop ;;
  'Print :CFBundleShortVersionString') printf '%s\n' 0.1.0 ;;
  'Print :CFBundleVersion') printf '%s\n' 1.0.0 ;;
  'Print :LSMinimumSystemVersion') printf '%s\n' 13.0 ;;
  *) exit 21 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/codesign" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'code object is not signed at all' >&2
exit 1
EOF
    /bin/chmod 700 "${CASE_ROOT}/bin/"*
}

create_release_directory() {
    local release="${CASE_ROOT}/.local/releases/v0.1.0" commit
    /bin/mkdir -p -- "${release}"
    /bin/chmod 700 "${CASE_ROOT}/.local" "${CASE_ROOT}/.local/releases" "${release}"
    printf android >"${release}/SIX-Bridge-Manager-0.1.0-android.apk"
    printf macos >"${release}/SIX-Bridge-0.1.0-macos-unsigned.dmg"
    printf '한글 릴리스 노트\n' >"${release}/RELEASE-NOTES-KO.md"
    commit=$(/usr/bin/git -C "${CASE_ROOT}" rev-parse HEAD)
    SIXBRIDGE_PUBLISH_FIXTURE_BIN="${CASE_ROOT}/bin" \
        SIXBRIDGE_PUBLISH_FIXTURE_SIGNER="${SIGNER}" \
        /bin/bash -c 'source "$1"; release_bind_project_root "$2"; release_write_metadata "$3" "$4" "$5" "$6"; release_write_checksums "$3" "$6"' \
        fixture "${CASE_ROOT}/scripts/release-lib.sh" "${CASE_ROOT}" "${release}" "${commit}" "${SIGNER}" "${CASE_ROOT}/.local/releases"
}

setup_case() {
    local name=$1
    CASE_ROOT="${FIXTURE_ROOT}/${name}/repo"
    CASE_ORIGIN="${FIXTURE_ROOT}/${name}/origin.git"
    /bin/mkdir -p -- "${CASE_ROOT}/scripts"
    /usr/bin/git init --bare --quiet "${CASE_ORIGIN}"
    /usr/bin/git -C "${CASE_ROOT}" init --quiet -b main
    /usr/bin/git -C "${CASE_ROOT}" config user.name 'SIX Bridge fixture'
    /usr/bin/git -C "${CASE_ROOT}" config user.email fixture@example.invalid
    write_fixture_library
    /bin/cp -- "${PUBLISH_SCRIPT}" "${CASE_ROOT}/scripts/publish-release-tag.sh"
    /bin/chmod 700 "${CASE_ROOT}/scripts/publish-release-tag.sh" "${CASE_ROOT}/scripts/release-lib.sh"
    write_fixture_tools
    printf '.local/\n.gitconfig\n' >"${CASE_ROOT}/.gitignore"
    printf fixture >"${CASE_ROOT}/README"
    /usr/bin/git -C "${CASE_ROOT}" add .
    /usr/bin/git -C "${CASE_ROOT}" commit --quiet -m fixture
    /usr/bin/git -C "${CASE_ROOT}" remote add origin "${CASE_ORIGIN}"
    /usr/bin/git -C "${CASE_ROOT}" push --quiet -u origin main
    printf '[user]\n\tname = Isolated global fixture\n\temail = global@example.invalid\n' >"${CASE_ROOT}/.gitconfig"
    create_release_directory
}

run_publish() {
    /usr/bin/env -i \
        PATH="${CASE_ROOT}/bin:/usr/bin:/bin" \
        HOME="${CASE_ROOT}" \
        SIXBRIDGE_PUBLISH_FIXTURE_BIN="${CASE_ROOT}/bin" \
        SIXBRIDGE_PUBLISH_FIXTURE_SIGNER="${SIGNER}" \
        SIXBRIDGE_PUBLISH_PATH_GIT_MARKER="${CASE_ROOT}/path-git-ran" \
        SIXBRIDGE_PUBLISH_FIXTURE_RACE="${PUBLISH_FIXTURE_RACE:-}" \
        SIXBRIDGE_PUBLISH_FIXTURE_RACE_MARKER="${PUBLISH_FIXTURE_RACE_MARKER:-}" \
        "${CASE_ROOT}/scripts/publish-release-tag.sh" 0.1.0
}

[[ -f "${PUBLISH_SCRIPT}" ]] || fail 'publish-release-tag.sh가 없어 RED 상태입니다.'

setup_case dirty
printf dirty >>"${CASE_ROOT}/README"
expect_failure dirty 'worktree가 깨끗하지 않습니다.' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag dirty

setup_case detached
/usr/bin/git -C "${CASE_ROOT}" checkout --quiet --detach
expect_failure detached 'detached HEAD' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag detached

setup_case wrong-branch
/usr/bin/git -C "${CASE_ROOT}" checkout --quiet -b release
expect_failure wrong-branch 'main branch' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag wrong-branch

setup_case origin-mismatch
printf local >>"${CASE_ROOT}/README"
/usr/bin/git -C "${CASE_ROOT}" add README
/usr/bin/git -C "${CASE_ROOT}" commit --quiet -m local-only
expect_failure origin-mismatch 'origin/main commit이 다릅니다.' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag origin-mismatch

setup_case local-tag
/usr/bin/git -C "${CASE_ROOT}" tag -a v0.1.0 -m existing
expect_failure local-tag 'local tag가 이미 있습니다.' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag local-tag

setup_case remote-tag
competitor="${FIXTURE_ROOT}/remote-tag/competitor"
/usr/bin/git clone --quiet "${CASE_ORIGIN}" "${competitor}"
/usr/bin/git -C "${competitor}" config user.name competitor
/usr/bin/git -C "${competitor}" config user.email competitor@example.invalid
/usr/bin/git -C "${competitor}" tag -a v0.1.0 -m existing
/usr/bin/git -C "${competitor}" push --quiet origin refs/tags/v0.1.0
expect_failure remote-tag 'remote tag가 이미 있습니다.' 'v0.1.0 KEY BACKUP COMPLETE'
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0)" = \
   "$(/usr/bin/git -C "${competitor}" rev-parse refs/tags/v0.1.0)" ]] || fail '기존 remote tag가 보존되지 않았습니다.'

setup_case artifact-extra
printf extra >"${CASE_ROOT}/.local/releases/v0.1.0/unexpected.txt"
expect_failure artifact-extra '정확히 다섯 개' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag artifact-extra

setup_case artifact-missing
/bin/rm -- "${CASE_ROOT}/.local/releases/v0.1.0/RELEASE-NOTES-KO.md"
expect_failure artifact-missing '필수 release file' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag artifact-missing

setup_case artifact-corrupt
printf corrupt >>"${CASE_ROOT}/.local/releases/v0.1.0/RELEASE-METADATA.json"
expect_failure artifact-corrupt 'release metadata 값을 읽지 못했습니다.' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag artifact-corrupt

setup_case wrong-confirmation
expect_failure wrong-confirmation '백업 확인 문구가 일치하지 않습니다.' nope
assert_no_remote_tag wrong-confirmation

setup_case eof
expect_failure eof '백업 확인 입력을 읽지 못했습니다.' EOF
assert_no_remote_tag eof

setup_case global-identity-without-local-config
/usr/bin/git -C "${CASE_ROOT}" config --unset user.name
/usr/bin/git -C "${CASE_ROOT}" config --unset user.email
global_identity_log="${FIXTURE_ROOT}/global-identity.log"
printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${global_identity_log}" 2>&1
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" cat-file -t refs/tags/v0.1.0)" = tag ]] || fail '격리된 global identity 정상 case가 annotated remote tag를 만들지 못했습니다.'

setup_case no-identity
/usr/bin/git -C "${CASE_ROOT}" config --unset user.name
/usr/bin/git -C "${CASE_ROOT}" config --unset user.email
/bin/rm -- "${CASE_ROOT}/.gitconfig"
printf '[user]\n\tuseConfigOnly = true\n' >"${CASE_ROOT}/.gitconfig"
expect_failure no-identity 'Git tagger identity' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag no-identity

setup_case push-failure
/bin/cat >"${CASE_ORIGIN}/hooks/pre-receive" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
/bin/chmod 700 "${CASE_ORIGIN}/hooks/pre-receive"
expect_failure push-failure 'tag push에 실패했습니다.' 'v0.1.0 KEY BACKUP COMPLETE'
assert_no_remote_tag push-failure
[[ -n "$(/usr/bin/git -C "${CASE_ROOT}" tag -l v0.1.0)" ]] || fail 'push 실패 뒤 local annotated tag 상태가 남지 않았습니다.'

setup_case success
success_log="${FIXTURE_ROOT}/success.log"
printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${success_log}" 2>&1
[[ "$(/usr/bin/git -C "${CASE_ROOT}" cat-file -t refs/tags/v0.1.0)" = tag ]] || fail 'local v0.1.0이 annotated tag가 아닙니다.'
[[ "$(/usr/bin/git -C "${CASE_ROOT}" rev-parse refs/tags/v0.1.0)" = \
   "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0)" ]] || fail 'remote annotated tag object가 local과 다릅니다.'
for file in SIX-Bridge-Manager-0.1.0-android.apk SIX-Bridge-0.1.0-macos-unsigned.dmg SHA256SUMS RELEASE-METADATA.json RELEASE-NOTES-KO.md; do
    assert_contains "${success_log}" "${file}" "정상 출력에 release 파일명이 없습니다: ${file}"
done
[[ "$(/usr/bin/grep -Ec '^(SIX-Bridge-Manager-0.1.0-android.apk|SIX-Bridge-0.1.0-macos-unsigned.dmg|SHA256SUMS|RELEASE-METADATA.json|RELEASE-NOTES-KO.md)$' "${success_log}")" = 5 ]] || fail '정상 출력의 exact five filename 목록이 올바르지 않습니다.'
assert_contains "${success_log}" "${SIGNER}" '정상 출력에 APK signer가 없습니다.'
assert_contains "${success_log}" '미서명·미공증' '정상 출력에 macOS 미서명 경고가 없습니다.'
assert_contains "${success_log}" '수동' '정상 출력에 수동 Releases 안내가 없습니다.'
assert_not_contains "${success_log}" 'fixture-secret' '정상 출력에 비밀정보가 노출되었습니다.'
[[ ! -e "${CASE_ROOT}/path-git-ran" ]] || fail 'PATH의 git shim이 실행되었습니다.'

setup_case head-race
head_race_expected=$(/usr/bin/git -C "${CASE_ROOT}" rev-parse HEAD)
PUBLISH_FIXTURE_RACE=head
PUBLISH_FIXTURE_RACE_MARKER="${FIXTURE_ROOT}/head-race.tag-target"
printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${FIXTURE_ROOT}/head-race.log" 2>&1
[[ "$(/bin/cat "${PUBLISH_FIXTURE_RACE_MARKER}")" = "${head_race_expected}" ]] || fail 'HEAD race callback이 검증한 commit을 받지 못했습니다.'
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" cat-file -t refs/tags/v0.1.0)" = tag ]] || fail 'HEAD race remote tag가 annotated tag가 아닙니다.'
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0^{})" = "${head_race_expected}" ]] || fail 'HEAD 변경 뒤 remote tag가 verified commit을 가리키지 않습니다.'

setup_case tag-ref-race
tag_race_expected=$(/usr/bin/git -C "${CASE_ROOT}" rev-parse HEAD)
PUBLISH_FIXTURE_RACE=tag-ref
PUBLISH_FIXTURE_RACE_MARKER="${FIXTURE_ROOT}/tag-ref-race.tag-oid"
printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${FIXTURE_ROOT}/tag-ref-race.log" 2>&1
tag_race_expected_oid=$(/bin/cat "${PUBLISH_FIXTURE_RACE_MARKER}")
tag_race_remote_oid=$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0)
[[ "${tag_race_remote_oid}" = "${tag_race_expected_oid}" ]] || fail 'local tag ref 교체 뒤 remote가 검증한 annotated tag OID를 받지 않았습니다.'
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" cat-file -t "${tag_race_remote_oid}")" = tag ]] || fail 'tag ref race remote tag가 annotated tag가 아닙니다.'
[[ "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0^{})" = "${tag_race_expected}" ]] || fail 'tag ref race remote tag peeled commit이 verified commit과 다릅니다.'
unset PUBLISH_FIXTURE_RACE PUBLISH_FIXTURE_RACE_MARKER

# These publisher capabilities are intentionally absent: the fixture permits
# only a normal non-force tag ref update to its local bare origin.
if /usr/bin/grep -Eq -- '(curl|gh[[:space:]].*(release|api)|--force|[[:space:]]-f[[:space:]]|tag[[:space:]]+-d)' "${PUBLISH_SCRIPT}"; then
    fail 'publisher에 Release API/force/delete tag 동작이 포함되어 있습니다.'
fi
assert_contains "${PUBLISH_SCRIPT}" '"${tag_oid}:refs/tags/${RELEASE_TAG}"' 'publisher가 exact tag object OID refspec을 사용하지 않습니다.'

printf 'publish release tag 검증 통과\n'
