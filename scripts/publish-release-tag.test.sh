#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")/.." && pwd -P)"
readonly PUBLISH_SCRIPT="${ROOT_DIR}/scripts/publish-release-tag.sh"
readonly FIXTURE_ROOT="$(/usr/bin/mktemp -d "${ROOT_DIR}/.local/publish-release-tag-test.XXXXXX")"
readonly SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

cleanup_fake_dmg_manifest() {
    local manifest=${1:-} candidate expected_identity nonce extra parent basename owner current_uid
    local actual_identity marker marker_state
    [[ -f "${manifest}" && ! -L "${manifest}" ]] || return 0
    current_uid=$(/usr/bin/id -u) || return 0
    while IFS=$'\t' read -r candidate expected_identity nonce extra; do
        [[ -n "${candidate}" && -n "${expected_identity}" && -n "${nonce}" && -z "${extra:-}" ]] || continue
        case "${nonce}" in *[!0-9a-f-]*) continue ;; esac
        case "${expected_identity}" in *[!0-9:]*) continue ;; esac
        parent=$(/usr/bin/dirname -- "${candidate}" 2>/dev/null) || continue
        basename=$(/usr/bin/basename -- "${candidate}" 2>/dev/null) || continue
        [[ "${parent}" = /private/tmp ]] || continue
        case "${basename}" in sixbridge-dmg.*) ;; *) continue ;; esac
        [[ -d "${candidate}" && ! -L "${candidate}" ]] || continue
        owner=$(/usr/bin/stat -f '%u' "${candidate}" 2>/dev/null) || continue
        actual_identity=$(/usr/bin/stat -f '%d:%i' "${candidate}" 2>/dev/null) || continue
        [[ "${owner}" = "${current_uid}" && "${actual_identity}" = "${expected_identity}" ]] || continue
        marker="${candidate}/.sixbridge-fixture-${nonce}"
        [[ -f "${marker}" && ! -L "${marker}" ]] || continue
        marker_state=$(/usr/bin/stat -f '%u:%Lp' "${marker}" 2>/dev/null) || continue
        [[ "${marker_state}" = "${current_uid}:600" ]] || continue
        [[ "$(/bin/cat -- "${marker}" 2>/dev/null)" = "${nonce}" ]] || continue
        /bin/rm -rf -- "${candidate}"
    done <"${manifest}"
}

cleanup_fake_dmg_manifests() {
    local fixture_root=${1:-} manifest
    [[ -d "${fixture_root}" && ! -L "${fixture_root}" ]] || return 0
    while IFS= read -r manifest; do
        cleanup_fake_dmg_manifest "${manifest}"
    done < <(/usr/bin/find "${fixture_root}" -type f -name '*.dmg-mount-manifest' -print 2>/dev/null)
}

cleanup() {
    cleanup_fake_dmg_manifests "${FIXTURE_ROOT}"
    /bin/rm -rf -- "${FIXTURE_ROOT}"
}
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

PUBLISH_FIXTURE_WAIT_STATUS=''
PUBLISH_FIXTURE_WAIT_TIMED_OUT=0

wait_for_publish_fixture_group() {
    local publish_pid=$1 timeout_marker=$2 watchdog_pid wait_status
    PUBLISH_FIXTURE_WAIT_STATUS=''
    PUBLISH_FIXTURE_WAIT_TIMED_OUT=0
    /bin/rm -f -- "${timeout_marker}"
    (
        /bin/sleep 10
        (umask 077; : >"${timeout_marker}")
        /bin/kill -TERM -- "-${publish_pid}" 2>/dev/null || true
        /bin/sleep 1
        /bin/kill -KILL -- "-${publish_pid}" 2>/dev/null || true
    ) &
    watchdog_pid=$!
    if wait "${publish_pid}"; then
        wait_status=0
    else
        wait_status=$?
    fi
    if /bin/kill -0 "${watchdog_pid}" 2>/dev/null; then
        /bin/kill -TERM "${watchdog_pid}" 2>/dev/null || true
    fi
    wait "${watchdog_pid}" 2>/dev/null || true
    PUBLISH_FIXTURE_WAIT_STATUS=${wait_status}
    if [[ -f "${timeout_marker}" ]]; then
        PUBLISH_FIXTURE_WAIT_TIMED_OUT=1
        /bin/kill -KILL -- "-${publish_pid}" 2>/dev/null || true
        return 1
    fi
    return 0
}

terminate_publish_fixture_group() {
    local publish_pid=$1 timeout_marker=$2
    /bin/kill -TERM -- "-${publish_pid}" 2>/dev/null || true
    wait_for_publish_fixture_group "${publish_pid}" "${timeout_marker}" || true
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
        plutil) printf '%s\n' /usr/bin/plutil ;;
        plistbuddy) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/PlistBuddy" ;;
        codesign) printf '%s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/codesign" ;;
        jq) printf '%s\n' /usr/bin/jq ;;
        shasum) printf '%s\n' /usr/bin/shasum ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}
release_run_apksigner() {
    local sdk_fixture_dir
    if [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = sdk-classpath ]]; then
        sdk_fixture_dir="${SIXBRIDGE_RELEASE_TRANSIENT_ROOT:-${TMPDIR:-/tmp}}/.sixbridge-sdk-classpath.fixture"
        /bin/mkdir -m 700 -- "${sdk_fixture_dir}"
        "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/sdk-classpath-barrier" "${sdk_fixture_dir}"
    fi
    "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apksigner" "$@"
}
release_run_apkanalyzer() { "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/apkanalyzer" "$@"; }
release_checksum_temp_created_barrier() {
    [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = checksum ]] || return 0
    printf '%s\n' "$1" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}"
    : >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
    while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
        /bin/sleep 0.05
    done
}
release_after_dmg_verification_cleanup_barrier() {
    local work_dir=$1 parked
    case "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" in
        post-dmg)
            printf '%s\n' "${work_dir}" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}"
            "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/post-dmg-foreground-barrier" \
                "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}" \
                "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
            ;;
        work-rebind)
            parked="${work_dir}.parked"
            /bin/mv -- "${work_dir}" "${parked}" || return 1
            /bin/mkdir -m 700 -- "${work_dir}" || return 1
            printf 'preserve\n' >"${work_dir}/sentinel"
            printf '%s\n' "${work_dir}" \
                >"${SIXBRIDGE_RELEASE_PROJECT_ROOT}/.local/publish-work-rebind.path"
            printf '%s\n' "${parked}" \
                >"${SIXBRIDGE_RELEASE_PROJECT_ROOT}/.local/publish-work-rebind.parked"
            ;;
    esac
}
publish_after_release_step_spawn() {
    [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = spawn ]] || return 0
    printf '%s\n' "$1" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}"
    printf '%s\n' "${SIXBRIDGE_RELEASE_TRANSIENT_ROOT}" \
        >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}"
    : >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
    while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
        /bin/sleep 0.05
    done
}
release_before_tag_creation() {
    if [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = tag-traps ]]; then
        trap -p EXIT HUP INT TERM >"${SIXBRIDGE_PUBLISH_FIXTURE_TRAP_RECORD}"
        return 0
    fi
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
if [[ -n "${FAKE_APKSIGNER_SNAPSHOT_RECORD:-}" ]]; then
  (umask 077; printf '%s\n' "${!#}" >"${FAKE_APKSIGNER_SNAPSHOT_RECORD}")
fi
if [[ -n "${FAKE_APKSIGNER_CHILD_RECORD:-}" ]]; then
  (umask 077; printf '%s\n' "$$" >"${FAKE_APKSIGNER_CHILD_RECORD}")
fi
if [[ -n "${FAKE_APKSIGNER_READY:-}" ]]; then
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  (umask 077; : >"${FAKE_APKSIGNER_READY}")
  while [[ ! -f "${FAKE_APKSIGNER_CONTINUE}" ]]; do
    /bin/sleep 0.05
  done
fi
if [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = signer ]]; then
  (umask 077; printf '%s\n' "$$" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}")
  (umask 077; printf '%s\n' "${!#}" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}")
  : >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
  while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
    /bin/sleep 0.05
  done
fi
if [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = grandchild ]]; then
  (umask 077; printf '%s\n' "$$" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}")
  (umask 077; printf '%s\n' "${!#}" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}")
  "${SIXBRIDGE_PUBLISH_FIXTURE_BIN}/term-ignoring-grandchild" \
    "${SIXBRIDGE_PUBLISH_FIXTURE_GRANDCHILD_RECORD}" \
    "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}" &
  while [[ ! -s "${SIXBRIDGE_PUBLISH_FIXTURE_GRANDCHILD_RECORD}" ||
           ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}" ]]; do
    /bin/sleep 0.05
  done
  while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
    /bin/sleep 0.05
  done
fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${SIXBRIDGE_PUBLISH_FIXTURE_SIGNER}"
EOF
    /bin/cat >"${CASE_ROOT}/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
if [[ "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND:-}" = analyzer ]]; then
  (umask 077; printf '%s\n' "$$" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}")
  (umask 077; printf '%s\n' "${!#}" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}")
  : >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
  while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
    /bin/sleep 0.05
  done
fi
case "$2" in
  application-id) printf '%s\n' dev.sixbridge.manager ;;
  version-name) printf '%s\n' 0.1.0 ;;
  version-code) printf '%s\n' 1 ;;
  *) exit 19 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/sdk-classpath-barrier" <<'EOF'
#!/usr/bin/env bash
(umask 077; printf '%s\n' "$$" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD}")
(umask 077; printf '%s\n' "$1" >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD}")
: >"${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY}"
while [[ ! -f "${SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE}" ]]; do
  /bin/sleep 0.05
done
EOF
    /bin/cat >"${CASE_ROOT}/bin/post-dmg-foreground-barrier" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
(umask 077; printf '%s\n' "$$" >"$1")
(umask 077; : >"$2")
exec /bin/sleep 60
EOF
    /bin/cat >"${CASE_ROOT}/bin/term-ignoring-grandchild" <<'EOF'
#!/usr/bin/perl
use strict;
use warnings;
$SIG{TERM} = 'IGNORE';
open(my $pid_fh, '>', $ARGV[0]) or die $!;
print {$pid_fh} "$$\n" or die $!;
close($pid_fh) or die $!;
open(my $ready_fh, '>', $ARGV[1]) or die $!;
close($ready_fh) or die $!;
while (1) { select(undef, undef, undef, 0.05); }
EOF
    /bin/cat >"${CASE_ROOT}/bin/hdiutil" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state_root="${HOME}/.local/fake-hdiutil-state"
state_source="${state_root}/source"
state_mountpoint="${state_root}/mountpoint"
state_device="${state_root}/device"
/bin/mkdir -p -- "${state_root}"
/bin/chmod 700 "${state_root}"

record_fake_dmg_object() {
  local candidate=$1 marker identity
  [[ -n "${FAKE_HDIUTIL_MOUNT_MANIFEST:-}" && -n "${FAKE_HDIUTIL_MOUNT_NONCE:-}" ]] || return 0
  case "${FAKE_HDIUTIL_MOUNT_NONCE}" in *[!0-9a-f-]*) exit 37 ;; esac
  marker="${candidate}/.sixbridge-fixture-${FAKE_HDIUTIL_MOUNT_NONCE}"
  (umask 077; printf '%s' "${FAKE_HDIUTIL_MOUNT_NONCE}" >"${marker}")
  /bin/chmod 600 "${marker}"
  identity=$(/usr/bin/stat -f '%d:%i' "${candidate}")
  printf '%s\t%s\t%s\n' "${candidate}" "${identity}" "${FAKE_HDIUTIL_MOUNT_NONCE}" \
    >>"${FAKE_HDIUTIL_MOUNT_MANIFEST}"
}

write_inventory() {
  local plist
  plist=$(/usr/bin/mktemp "${state_root}/info.XXXXXX.plist")
  /usr/bin/plutil -create xml1 "${plist}"
  /usr/bin/plutil -insert images -array "${plist}"
  if [[ -f "${state_source}" ]]; then
    /usr/bin/plutil -insert 'images.0' -dictionary "${plist}"
    /usr/bin/plutil -insert 'images.0.image-path' -string "$(/bin/cat -- "${state_source}")" "${plist}"
    /usr/bin/plutil -insert 'images.0.system-entities' -array "${plist}"
    /usr/bin/plutil -insert 'images.0.system-entities.0' -dictionary "${plist}"
    /usr/bin/plutil -insert 'images.0.system-entities.0.dev-entry' -string \
      "$(/bin/cat -- "${state_device}")" "${plist}"
    /usr/bin/plutil -insert 'images.0.system-entities.0.mount-point' -string \
      "$(/bin/cat -- "${state_mountpoint}")" "${plist}"
  fi
  /bin/cat -- "${plist}"
  /bin/rm -f -- "${plist}"
}

case "$1" in
  info)
    [[ "${2:-}" = -plist ]] || exit 40
    write_inventory
    ;;
  attach)
    source=${!#}
    shift
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" = -mountpoint ]]; then mountpoint=$2; shift 2; else shift; fi
    done
    case "${mountpoint}" in /private/tmp/sixbridge-dmg.*) ;; *) exit 21 ;; esac
    [[ "$(/usr/bin/stat -f '%u:%Lp' "${mountpoint}")" = "$(/usr/bin/id -u):700" ]] || exit 22
    record_fake_dmg_object "${mountpoint}"
    /bin/mv "${mountpoint}" "${mountpoint}.underlay"
    record_fake_dmg_object "${mountpoint}.underlay"
    /bin/mkdir -m 700 "${mountpoint}"
    record_fake_dmg_object "${mountpoint}"
    /bin/mkdir -p "${mountpoint}/SIX Bridge.app/Contents/app"
    : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
    printf '%s\n' \
      '[Application]' \
      'app.classpath=$APPDIR/desktop-app-0.1.0-SNAPSHOT-fixture.jar' \
      'app.mainclass=dev.sixbridge.desktop.MainKt' \
      >"${mountpoint}/SIX Bridge.app/Contents/app/SIX Bridge.cfg"
    /bin/mkdir -p "${state_root}/jar-input"
    printf '%s\n' \
      'name=SIX Bridge' \
      'applicationId=dev.sixbridge.desktop' \
      'version=0.1.0' \
      'minimumMacOs=13.0' \
      >"${state_root}/jar-input/app.properties"
    (CDPATH= builtin cd -- "${state_root}/jar-input" && \
      /usr/bin/zip -q -X \
        "${mountpoint}/SIX Bridge.app/Contents/app/desktop-app-0.1.0-SNAPSHOT-fixture.jar" \
        app.properties)
    printf '%s' "${source}" >"${state_source}"
    printf '%s' "${mountpoint}" >"${state_mountpoint}"
    printf '%s' '/dev/disk71' >"${state_device}"
    if [[ -n "${FAKE_HDIUTIL_ATTACH_LOG:-}" ]]; then
      printf 'attach\n' >>"${FAKE_HDIUTIL_ATTACH_LOG}"
    fi
    if [[ -n "${FAKE_HDIUTIL_ATTACH_CHILD_RECORD:-}" ]]; then
      (umask 077; printf '%s\n' "$$" >"${FAKE_HDIUTIL_ATTACH_CHILD_RECORD}")
    fi
    if [[ -n "${FAKE_HDIUTIL_ATTACH_READY:-}" ]]; then
      (umask 077; : >"${FAKE_HDIUTIL_ATTACH_READY}")
    fi
    if [[ -n "${FAKE_HDIUTIL_ATTACH_CONTINUE:-}" ]]; then
      while [[ ! -f "${FAKE_HDIUTIL_ATTACH_CONTINUE}" ]]; do
        /bin/sleep 0.05
      done
    fi
    ;;
  detach)
    [[ -f "${state_device}" ]] || exit 38
    [[ "${2:-}" = "$(/bin/cat -- "${state_device}")" ]] || exit 39
    if [[ -n "${FAKE_HDIUTIL_DETACH_LOG:-}" ]]; then
      printf 'detach %s\n' "${2}" >>"${FAKE_HDIUTIL_DETACH_LOG}"
    fi
    [[ "${FAKE_HDIUTIL_DETACH_FAIL:-0}" != 1 ]] || exit 41
    mountpoint=$(/bin/cat -- "${state_mountpoint}")
    /bin/rm -rf -- "${mountpoint}"
    [[ -z "${FAKE_HDIUTIL_MOUNT_NONCE:-}" ]] || \
      /bin/rm -f -- "${mountpoint}.underlay/.sixbridge-fixture-${FAKE_HDIUTIL_MOUNT_NONCE}"
    /bin/mv "${mountpoint}.underlay" "${mountpoint}"
    /bin/rm -f -- "${state_source}" "${state_mountpoint}" "${state_device}"
    ;;
  *) exit 20 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  'Print :CFBundleIdentifier') printf '%s\n' dev.sixbridge.desktop ;;
  'Print :CFBundleShortVersionString') printf '%s\n' 1.0.0 ;;
  'Print :CFBundleVersion') printf '%s\n' 1.0.0 ;;
  'Print :LSMinimumSystemVersion') printf '%s\n' 13.0 ;;
  *) exit 21 ;;
esac
EOF
    /bin/cat >"${CASE_ROOT}/bin/codesign" <<'EOF'
#!/usr/bin/env bash
target=${!#}
case "${target}" in
  */.sixbridge-release-snapshot.*/artifact)
    printf '%s: code object is not signed at all\n' "${target}" >&2
    exit 1
    ;;
esac
printf '%s\n' 'CodeDirectory v=20500 flags=0x10002(adhoc,runtime)' 'Signature=adhoc' 'TeamIdentifier=not set' >&2
exit 0
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
        SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND="${PUBLISH_FIXTURE_BARRIER_KIND:-}" \
        SIXBRIDGE_PUBLISH_FIXTURE_TRAP_RECORD="${PUBLISH_FIXTURE_TRAP_RECORD:-}" \
        FAKE_HDIUTIL_MOUNT_MANIFEST="${CASE_ROOT}/.local/publish.dmg-mount-manifest" \
        FAKE_HDIUTIL_MOUNT_NONCE='89abcdef-0123-4567-89ab-cdef01234567' \
        FAKE_HDIUTIL_ATTACH_LOG="${CASE_ROOT}/.local/publish-attach.log" \
        "${CASE_ROOT}/scripts/publish-release-tag.sh" 0.1.0
}

recover_publish_signal_case() {
    local child=${1:-} mountpoint=${2:-} expected_mount_identity=${3:-}
    local state_root=${HOME}/.local/fake-hdiutil-state actual_mount_identity
    if [[ "${child}" =~ ^[1-9][0-9]*$ ]] && /bin/kill -0 "${child}" 2>/dev/null; then
        /bin/kill -TERM "${child}" 2>/dev/null || true
        for _ in {1..100}; do
            /bin/kill -0 "${child}" 2>/dev/null || break
            /bin/sleep 0.05
        done
    fi
    if [[ -f "${state_root}/device" ]]; then
        HOME="${HOME}" \
            FAKE_HDIUTIL_MOUNT_MANIFEST="${FAKE_HDIUTIL_MOUNT_MANIFEST}" \
            FAKE_HDIUTIL_MOUNT_NONCE="${FAKE_HDIUTIL_MOUNT_NONCE}" \
            FAKE_HDIUTIL_DETACH_LOG="${FAKE_HDIUTIL_DETACH_LOG}" \
            "${CASE_ROOT}/bin/hdiutil" detach /dev/disk71 >/dev/null 2>&1 || true
    fi
    cleanup_fake_dmg_manifest "${FAKE_HDIUTIL_MOUNT_MANIFEST}"
    if [[ -n "${mountpoint}" && -n "${expected_mount_identity}" &&
          -d "${mountpoint}" && ! -L "${mountpoint}" ]]; then
        actual_mount_identity=$(/usr/bin/stat -f '%d:%i' "${mountpoint}" 2>/dev/null || true)
        if [[ "${actual_mount_identity}" = "${expected_mount_identity}" ]]; then
            /bin/rmdir -- "${mountpoint}" 2>/dev/null || true
        fi
    fi
}

[[ -f "${PUBLISH_SCRIPT}" ]] || fail 'publish-release-tag.sh가 없어 RED 상태입니다.'

if [[ "${SIXBRIDGE_PUBLISH_TEST_NORMAL_ONLY:-0}" = 0 &&
      "${SIXBRIDGE_PUBLISH_TEST_PARENT_SIGNAL_ONLY:-0}" = 0 &&
      "${SIXBRIDGE_PUBLISH_TEST_OUTSIDE_DMG_SIGNAL_ONLY:-0}" = 0 &&
      "${SIXBRIDGE_PUBLISH_TEST_ORCHESTRATED:-0}" = 0 ]]; then
    focused_normal_log="${FIXTURE_ROOT}/focused-normal.log"
    focused_signal_log="${FIXTURE_ROOT}/focused-parent-signal.log"
    focused_outside_log="${FIXTURE_ROOT}/focused-outside-dmg-signal.log"
    SIXBRIDGE_PUBLISH_TEST_ORCHESTRATED=1 \
        SIXBRIDGE_PUBLISH_TEST_NORMAL_ONLY=1 \
        /bin/bash "$0" >"${focused_normal_log}" 2>&1 || {
            /bin/cat -- "${focused_normal_log}" >&2
            fail 'plain publish suite의 normal focused 회귀가 실패했습니다.'
        }
    [[ "$(/usr/bin/grep -Fxc 'publish focused normal preflight-attach=2 검증 통과' \
        "${focused_normal_log}")" = 1 ]] || {
        /bin/cat -- "${focused_normal_log}" >&2
        fail 'plain publish suite가 exact 2-preflight focused 회귀를 한 번 실행하지 않았습니다.'
    }
    /bin/cat -- "${focused_normal_log}"

    SIXBRIDGE_PUBLISH_TEST_ORCHESTRATED=1 \
        SIXBRIDGE_PUBLISH_TEST_PARENT_SIGNAL_ONLY=1 \
        /bin/bash "$0" >"${focused_signal_log}" 2>&1 || {
            /bin/cat -- "${focused_signal_log}" >&2
            fail 'plain publish suite의 parent-signal focused 회귀가 실패했습니다.'
        }
    for focused_signal_label in HUP INT TERM TERM-CLEANUP-FAIL; do
        [[ "$(/usr/bin/grep -Fxc \
            "publish focused parent-signal ${focused_signal_label} 검증 통과" \
            "${focused_signal_log}")" = 1 ]] || {
                /bin/cat -- "${focused_signal_log}" >&2
                fail "plain publish suite가 ${focused_signal_label} focused 회귀를 한 번 실행하지 않았습니다."
            }
    done
    /bin/cat -- "${focused_signal_log}"

    SIXBRIDGE_PUBLISH_TEST_ORCHESTRATED=1 \
        SIXBRIDGE_PUBLISH_TEST_OUTSIDE_DMG_SIGNAL_ONLY=1 \
        /bin/bash "$0" >"${focused_outside_log}" 2>&1 || {
            /bin/cat -- "${focused_outside_log}" >&2
            fail 'plain publish suite의 outside-DMG focused 회귀가 실패했습니다.'
        }
    for focused_barrier_kind in checksum sdk-classpath signer analyzer post-dmg spawn; do
        for focused_signal_label in HUP INT TERM; do
            [[ "$(/usr/bin/grep -Fxc \
                "publish focused outside-DMG ${focused_barrier_kind} parent-signal ${focused_signal_label} 검증 통과" \
                "${focused_outside_log}")" = 1 ]] || {
                    /bin/cat -- "${focused_outside_log}" >&2
                    fail "plain publish suite가 ${focused_barrier_kind}/${focused_signal_label} outside-DMG 회귀를 한 번 실행하지 않았습니다."
                }
        done
    done
    [[ "$(/usr/bin/grep -Fxc \
        'publish focused outside-DMG grandchild parent-signal TERM 검증 통과' \
        "${focused_outside_log}")" = 1 ]] || {
        /bin/cat -- "${focused_outside_log}" >&2
        fail 'plain publish suite가 grandchild/TERM outside-DMG 회귀를 한 번 실행하지 않았습니다.'
    }
    [[ "$(/usr/bin/grep -Fxc \
        'publish focused post-verification traps-clear 검증 통과' \
        "${focused_outside_log}")" = 1 ]] || {
        /bin/cat -- "${focused_outside_log}" >&2
        fail 'plain publish suite가 post-verification trap 해제 회귀를 한 번 실행하지 않았습니다.'
    }
    /bin/cat -- "${focused_outside_log}"
fi

if [[ "${SIXBRIDGE_PUBLISH_TEST_OUTSIDE_DMG_SIGNAL_ONLY:-0}" = 1 ]]; then
    outside_signal_failure=0
    for barrier_kind in checksum sdk-classpath signer analyzer post-dmg spawn grandchild; do
      case "${barrier_kind}" in
        grandchild) signal_cases=TERM ;;
        *) signal_cases='HUP INT TERM' ;;
      esac
      for signal_case in ${signal_cases}; do
        case "${signal_case}" in
            HUP) expected_status=129 ;;
            INT) expected_status=130 ;;
            TERM) expected_status=143 ;;
        esac
        setup_case "outside-dmg-${barrier_kind}-${signal_case}"
        signal_log="${FIXTURE_ROOT}/outside-dmg-${barrier_kind}-${signal_case}.log"
        confirmation_file="${CASE_ROOT}/.local/publish-confirmation"
        verifier_ready="${CASE_ROOT}/.local/verifier.ready"
        verifier_continue="${CASE_ROOT}/.local/verifier.continue"
        verifier_child_record="${CASE_ROOT}/.local/verifier.child"
        verifier_grandchild_record="${CASE_ROOT}/.local/verifier.grandchild"
        verifier_path_record="${CASE_ROOT}/.local/verifier.path"
        verifier_tmp="${CASE_ROOT}/.local/publish-tmp"
        push_marker="${CASE_ROOT}/.local/publish-push.marker"
        /bin/mkdir -m 700 -- "${verifier_tmp}"
        printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' >"${confirmation_file}"
        /bin/cat >"${CASE_ORIGIN}/hooks/pre-receive" <<EOF
#!/bin/bash
: >"${push_marker}"
exit 1
EOF
        /bin/chmod 700 "${CASE_ORIGIN}/hooks/pre-receive"
        publish_monitor_was_enabled=0
        case $- in *m*) publish_monitor_was_enabled=1 ;; esac
        set -m
        /usr/bin/env -i \
            PATH="${CASE_ROOT}/bin:/usr/bin:/bin" \
            HOME="${CASE_ROOT}" \
            TMPDIR="${verifier_tmp}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BIN="${CASE_ROOT}/bin" \
            SIXBRIDGE_PUBLISH_FIXTURE_SIGNER="${SIGNER}" \
            SIXBRIDGE_PUBLISH_PATH_GIT_MARKER="${CASE_ROOT}/path-git-ran" \
            SIXBRIDGE_PUBLISH_FIXTURE_RACE='' \
            SIXBRIDGE_PUBLISH_FIXTURE_RACE_MARKER='' \
            SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_KIND="${barrier_kind}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_READY="${verifier_ready}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CONTINUE="${verifier_continue}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_CHILD_RECORD="${verifier_child_record}" \
            SIXBRIDGE_PUBLISH_FIXTURE_GRANDCHILD_RECORD="${verifier_grandchild_record}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BARRIER_PATH_RECORD="${verifier_path_record}" \
            FAKE_HDIUTIL_MOUNT_MANIFEST="${CASE_ROOT}/.local/publish.dmg-mount-manifest" \
            FAKE_HDIUTIL_MOUNT_NONCE='89abcdef-0123-4567-89ab-cdef01234567' \
            /usr/bin/perl -e '
                $SIG{HUP} = q{DEFAULT};
                $SIG{INT} = q{DEFAULT};
                $SIG{TERM} = q{DEFAULT};
                exec @ARGV or die qq{publish exec failed: $!\n};
            ' "${CASE_ROOT}/scripts/publish-release-tag.sh" 0.1.0 \
            <"${confirmation_file}" >"${signal_log}" 2>&1 &
        publish_pid=$!
        [[ "${publish_monitor_was_enabled}" = 1 ]] || set +m
        publish_timeout_marker="${CASE_ROOT}/.local/publish-wait.timeout"
        /bin/kill -0 -- "-${publish_pid}" 2>/dev/null || {
            terminate_publish_fixture_group "${publish_pid}" "${publish_timeout_marker}"
            fail "${barrier_kind}/${signal_case}: publisher process group을 확정하지 못했습니다."
        }
        for _ in {1..400}; do
            [[ -f "${verifier_ready}" && -s "${verifier_path_record}" ]] && break
            /bin/kill -0 "${publish_pid}" 2>/dev/null || break
            /bin/sleep 0.05
        done
        [[ -f "${verifier_ready}" && -s "${verifier_path_record}" ]] || {
            /bin/cat -- "${signal_log}" >&2 || true
            terminate_publish_fixture_group "${publish_pid}" "${publish_timeout_marker}"
            fail "${barrier_kind}/${signal_case}: verifier barrier에 도달하지 못했습니다."
        }
        verifier_path=$(/bin/cat -- "${verifier_path_record}")
        verifier_child=''
        [[ ! -s "${verifier_child_record}" ]] || verifier_child=$(/bin/cat -- "${verifier_child_record}")
        transient_root=$(/usr/bin/find "${CASE_ROOT}/.local/releases" -mindepth 1 -maxdepth 1 \
            -type d -name '.sixbridge-publish-transient.*' -print | /usr/bin/awk 'NR == 1 {print}')
        /bin/kill -s "${signal_case}" -- "-${publish_pid}"
        [[ "${barrier_kind}" != spawn ]] || : >"${verifier_continue}"
        publish_wait_succeeded=1
        wait_for_publish_fixture_group "${publish_pid}" "${publish_timeout_marker}" ||
            publish_wait_succeeded=0
        publish_status=${PUBLISH_FIXTURE_WAIT_STATUS:-255}
        publish_wait_timed_out=${PUBLISH_FIXTURE_WAIT_TIMED_OUT}
        publisher_group_alive=0
        /bin/kill -0 -- "-${publish_pid}" 2>/dev/null && publisher_group_alive=1
        verifier_child_alive=0
        if [[ -n "${verifier_child}" ]] && /bin/kill -0 "${verifier_child}" 2>/dev/null; then
            verifier_child_alive=1
        fi
        verifier_grandchild=''
        [[ ! -s "${verifier_grandchild_record}" ]] ||
            verifier_grandchild=$(/bin/cat -- "${verifier_grandchild_record}")
        verifier_grandchild_alive=0
        if [[ -n "${verifier_grandchild}" ]] &&
           /bin/kill -0 "${verifier_grandchild}" 2>/dev/null; then
            verifier_grandchild_alive=1
        fi
        verifier_group_alive=0
        if [[ "${barrier_kind}" = grandchild && -n "${verifier_child}" ]] &&
           /bin/kill -0 -- "-${verifier_child}" 2>/dev/null; then
            verifier_group_alive=1
        fi
        verifier_path_left=0
        [[ ! -e "${verifier_path}" && ! -L "${verifier_path}" ]] || verifier_path_left=1
        transient_left=0
        [[ -n "${transient_root}" && ! -e "${transient_root}" && ! -L "${transient_root}" ]] || transient_left=1
        verifier_tmp_count=$(/usr/bin/find "${verifier_tmp}" -mindepth 1 -maxdepth 1 -type f | /usr/bin/awk 'END {print NR + 0}')
        local_tag_left=0
        remote_tag_left=0
        /usr/bin/git -C "${CASE_ROOT}" show-ref --verify --quiet refs/tags/v0.1.0 && local_tag_left=1
        /usr/bin/git --git-dir="${CASE_ORIGIN}" show-ref --verify --quiet refs/tags/v0.1.0 && remote_tag_left=1
        push_marker_left=0
        [[ ! -e "${push_marker}" ]] || push_marker_left=1

        : >"${verifier_continue}"
        if [[ -n "${verifier_child}" ]]; then
            for _ in {1..100}; do
                /bin/kill -0 "${verifier_child}" 2>/dev/null || break
                /bin/sleep 0.05
            done
            /bin/kill -TERM "${verifier_child}" 2>/dev/null || true
        fi
        case "${verifier_path}" in
            "${CASE_ROOT}"/*|"${verifier_tmp}"/*) /bin/rm -rf -- "${verifier_path}" ;;
        esac
        [[ -z "${transient_root}" ]] || /bin/rm -rf -- "${transient_root}"
        /usr/bin/find "${verifier_tmp}" -mindepth 1 -maxdepth 1 -type f -delete

        if [[ "${publish_status}" != "${expected_status}" ||
              "${publish_wait_succeeded}" != 1 || "${publish_wait_timed_out}" != 0 ||
              "${publisher_group_alive}" != 0 ||
              "${verifier_child_alive}" != 0 ||
              "${verifier_grandchild_alive}" != 0 || "${verifier_group_alive}" != 0 ||
              "${verifier_path_left}" != 0 ||
              "${transient_left}" != 0 ||
              "${verifier_tmp_count}" != 0 || "${local_tag_left}" != 0 ||
              "${remote_tag_left}" != 0 || "${push_marker_left}" != 0 ]]; then
            printf 'RED outside-DMG %s/%s status=%s wait=%s timeout=%s publisher-group=%s child=%s grandchild=%s group=%s path=%s transient=%s tmp=%s local=%s remote=%s push=%s\n' \
                "${barrier_kind}" "${signal_case}" "${publish_status}" \
                "${publish_wait_succeeded}" "${publish_wait_timed_out}" "${publisher_group_alive}" \
                "${verifier_child_alive}" \
                "${verifier_grandchild_alive}" "${verifier_group_alive}" "${verifier_path_left}" \
                "${transient_left}" "${verifier_tmp_count}" "${local_tag_left}" \
                "${remote_tag_left}" "${push_marker_left}" >&2
            outside_signal_failure=1
        else
            printf 'publish focused outside-DMG %s parent-signal %s 검증 통과\n' \
                "${barrier_kind}" "${signal_case}"
        fi
      done
    done
    setup_case post-verification-traps
    PUBLISH_FIXTURE_BARRIER_KIND=tag-traps
    PUBLISH_FIXTURE_TRAP_RECORD="${CASE_ROOT}/.local/post-verification-traps"
    tag_trap_log="${FIXTURE_ROOT}/post-verification-traps.log"
    printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${tag_trap_log}" 2>&1 || {
        /bin/cat -- "${tag_trap_log}" >&2
        fail 'post-verification trap 해제 게시가 실패했습니다.'
    }
    [[ -f "${PUBLISH_FIXTURE_TRAP_RECORD}" &&
       ! -s "${PUBLISH_FIXTURE_TRAP_RECORD}" ]] || {
        /bin/cat -- "${PUBLISH_FIXTURE_TRAP_RECORD}" >&2 || true
        fail '두 번째 preflight 뒤 publish signal/EXIT trap이 해제되지 않았습니다.'
    }
    printf 'publish focused post-verification traps-clear 검증 통과\n'
    [[ "${outside_signal_failure}" = 0 ]] ||
        fail 'publish active DMG 밖 verifier signal cleanup 경계가 올바르지 않습니다.'
    printf 'publish focused outside-DMG parent-signal 전체 검증 통과\n'
    exit 0
fi

if [[ "${SIXBRIDGE_PUBLISH_TEST_NORMAL_ONLY:-0}" = 1 ]]; then
    setup_case normal-only
    normal_only_log="${FIXTURE_ROOT}/normal-only.log"
    set +e
    printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' | run_publish >"${normal_only_log}" 2>&1
    normal_only_status=$?
    set -e
    [[ "${normal_only_status}" = 0 ]] || {
        /bin/cat -- "${normal_only_log}" >&2
        fail 'normal-only 게시가 실패했습니다.'
    }
    [[ "$(/usr/bin/grep -c '^attach$' "${CASE_ROOT}/.local/publish-attach.log")" = 2 ]] ||
        fail 'normal-only 게시가 두 번의 direct preflight DMG 검증을 실행하지 않았습니다.'
    [[ "$(/usr/bin/git -C "${CASE_ROOT}" cat-file -t refs/tags/v0.1.0)" = tag ]] ||
        fail 'normal-only local tag가 annotated tag가 아닙니다.'
    [[ "$(/usr/bin/git -C "${CASE_ROOT}" rev-parse refs/tags/v0.1.0)" = \
       "$(/usr/bin/git --git-dir="${CASE_ORIGIN}" rev-parse refs/tags/v0.1.0)" ]] ||
        fail 'normal-only remote annotated tag object가 local과 다릅니다.'
    cleanup_mount=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.XXXXXX')
    cleanup_sentinel=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.unrelated.XXXXXX')
    cleanup_manifest="${CASE_ROOT}/normal-only.dmg-mount-manifest"
    cleanup_nonce='01234567-89ab-cdef-0123-456789abcdef'
    cleanup_source="${CASE_ROOT}/normal-only-cleanup.dmg"
    cleanup_sentinel_marker="${cleanup_sentinel}/.sixbridge-fixture-${cleanup_nonce}"
    printf cleanup >"${cleanup_source}"
    printf preserve >"${cleanup_sentinel}/preserve"
    printf '%s' "${cleanup_nonce}" >"${cleanup_sentinel_marker}"
    /bin/chmod 600 "${cleanup_sentinel_marker}"
    set +e
    (
        trap 'cleanup_fake_dmg_manifests "${FIXTURE_ROOT}"' EXIT
        HOME="${CASE_ROOT}" \
            FAKE_HDIUTIL_MOUNT_MANIFEST="${cleanup_manifest}" \
            FAKE_HDIUTIL_MOUNT_NONCE="${cleanup_nonce}" \
            "${CASE_ROOT}/bin/hdiutil" attach -readonly -nobrowse \
                -mountpoint "${cleanup_mount}" "${cleanup_source}" >/dev/null 2>&1 || exit 90
        printf '%s\t%s\t%s\n' "${cleanup_sentinel}" '999999:999999' "${cleanup_nonce}" \
            >>"${cleanup_manifest}"
        exit 91
    )
    cleanup_status=$?
    cleanup_mount_left=0
    cleanup_underlay_left=0
    cleanup_sentinel_preserved=0
    [[ ! -e "${cleanup_mount}" ]] || cleanup_mount_left=1
    [[ ! -e "${cleanup_mount}.underlay" ]] || cleanup_underlay_left=1
    [[ -f "${cleanup_sentinel}/preserve" ]] && cleanup_sentinel_preserved=1
    /bin/rm -rf -- "${cleanup_mount}" "${cleanup_mount}.underlay"
    /bin/rm -f -- "${cleanup_sentinel_marker}" "${cleanup_sentinel}/preserve"
    /bin/rmdir -- "${cleanup_sentinel}"
    set -e
    [[ "${cleanup_status}" = 91 ]] || fail 'normal-only manifest EXIT cleanup이 실행되지 않았습니다.'
    [[ "${cleanup_mount_left}" = 0 && "${cleanup_underlay_left}" = 0 ]] ||
        fail 'normal-only manifest EXIT cleanup이 mount/underlay를 정리하지 못했습니다.'
    [[ "${cleanup_sentinel_preserved}" = 1 ]] ||
        fail 'normal-only manifest EXIT cleanup이 무관한 same-prefix sentinel을 삭제했습니다.'
    printf 'publish focused normal preflight-attach=2 검증 통과\n'
    exit 0
fi

if [[ "${SIXBRIDGE_PUBLISH_TEST_PARENT_SIGNAL_ONLY:-0}" = 1 ]]; then
    parent_signal_failure=0
    unmanifested_sentinel=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.unmanifested.XXXXXX')
    unmanifested_nonce='fedcba98-7654-3210-fedc-ba9876543210'
    unmanifested_marker="${unmanifested_sentinel}/.sixbridge-fixture-${unmanifested_nonce}"
    unmanifested_cleanup_manifest="${FIXTURE_ROOT}/parent-signal-sentinel-cleanup.dmg-mount-manifest"
    printf preserve >"${unmanifested_sentinel}/preserve"
    printf '%s' "${unmanifested_nonce}" >"${unmanifested_marker}"
    /bin/chmod 600 "${unmanifested_marker}"
    printf '%s\t%s\t%s\n' "${unmanifested_sentinel}" \
        "$(/usr/bin/stat -f '%d:%i' "${unmanifested_sentinel}")" "${unmanifested_nonce}" \
        >"${unmanifested_cleanup_manifest}"
    for signal_label in HUP INT TERM TERM-CLEANUP-FAIL; do
        cleanup_failure=0
        case "${signal_label}" in
            HUP) signal_case=HUP; expected_status=129 ;;
            INT) signal_case=INT; expected_status=130 ;;
            TERM) signal_case=TERM; expected_status=143 ;;
            TERM-CLEANUP-FAIL) signal_case=TERM; expected_status=143; cleanup_failure=1 ;;
        esac
        setup_case "parent-signal-${signal_label}"
        signal_log="${FIXTURE_ROOT}/parent-signal-${signal_label}.log"
        confirmation_file="${CASE_ROOT}/.local/publish-confirmation"
        attach_ready="${CASE_ROOT}/.local/publish-attach.ready"
        attach_child_record="${CASE_ROOT}/.local/publish-attach.child"
        attach_continue="${CASE_ROOT}/.local/publish-attach.continue"
        detach_log="${CASE_ROOT}/.local/publish-detach.log"
        mount_manifest="${CASE_ROOT}/.local/publish.dmg-mount-manifest"
        push_marker="${CASE_ROOT}/.local/publish-push.marker"
        printf '%s\n' 'v0.1.0 KEY BACKUP COMPLETE' >"${confirmation_file}"
        /bin/cat >"${CASE_ORIGIN}/hooks/pre-receive" <<EOF
#!/bin/bash
: >"${push_marker}"
exit 1
EOF
        /bin/chmod 700 "${CASE_ORIGIN}/hooks/pre-receive"

        /usr/bin/env -i \
            PATH="${CASE_ROOT}/bin:/usr/bin:/bin" \
            HOME="${CASE_ROOT}" \
            SIXBRIDGE_PUBLISH_FIXTURE_BIN="${CASE_ROOT}/bin" \
            SIXBRIDGE_PUBLISH_FIXTURE_SIGNER="${SIGNER}" \
            SIXBRIDGE_PUBLISH_PATH_GIT_MARKER="${CASE_ROOT}/path-git-ran" \
            SIXBRIDGE_PUBLISH_FIXTURE_RACE='' \
            SIXBRIDGE_PUBLISH_FIXTURE_RACE_MARKER='' \
            FAKE_HDIUTIL_MOUNT_MANIFEST="${mount_manifest}" \
            FAKE_HDIUTIL_MOUNT_NONCE='89abcdef-0123-4567-89ab-cdef01234567' \
            FAKE_HDIUTIL_ATTACH_READY="${attach_ready}" \
            FAKE_HDIUTIL_ATTACH_CHILD_RECORD="${attach_child_record}" \
            FAKE_HDIUTIL_ATTACH_CONTINUE="${attach_continue}" \
            FAKE_HDIUTIL_DETACH_LOG="${detach_log}" \
            FAKE_HDIUTIL_DETACH_FAIL="${cleanup_failure}" \
            /usr/bin/perl -e '
                $SIG{HUP} = q{DEFAULT};
                $SIG{INT} = q{DEFAULT};
                $SIG{TERM} = q{DEFAULT};
                exec @ARGV or die qq{publish exec failed: $!\n};
            ' "${CASE_ROOT}/scripts/publish-release-tag.sh" 0.1.0 \
            <"${confirmation_file}" >"${signal_log}" 2>&1 &
        publish_pid=$!

        for _ in {1..200}; do
            [[ -f "${attach_ready}" && -s "${attach_child_record}" ]] && break
            /bin/kill -0 "${publish_pid}" 2>/dev/null || break
            /bin/sleep 0.05
        done
        [[ -f "${attach_ready}" && -s "${attach_child_record}" ]] || {
            /bin/cat -- "${signal_log}" >&2 || true
            /bin/kill -TERM "${publish_pid}" 2>/dev/null || true
            wait "${publish_pid}" 2>/dev/null || true
            fail "${signal_case}: publish attach barrier에 도달하지 못했습니다."
        }
        attach_child=$(/bin/cat -- "${attach_child_record}")
        state_root="${CASE_ROOT}/.local/fake-hdiutil-state"
        expected_snapshot=$(/bin/cat -- "${state_root}/source")
        expected_mountpoint=$(/bin/cat -- "${state_root}/mountpoint")
        expected_snapshot_dir=$(/usr/bin/dirname -- "${expected_snapshot}")
        expected_parent_work_dir=$(/usr/bin/dirname -- "${expected_snapshot_dir}")
        expected_mount_identity=''
        while IFS=$'\t' read -r manifest_candidate manifest_identity _; do
            if [[ "${manifest_candidate}" = "${expected_mountpoint}.underlay" ]]; then
                expected_mount_identity=${manifest_identity}
                break
            fi
        done <"${mount_manifest}"
        [[ -n "${expected_mount_identity}" ]] || fail "${signal_case}: original mountpoint identity를 기록하지 못했습니다."

        /bin/kill -s "${signal_case}" "${publish_pid}"
        publish_still_running=1
        for _ in {1..200}; do
            if ! /bin/kill -0 "${publish_pid}" 2>/dev/null; then
                publish_still_running=0
                break
            fi
            /bin/sleep 0.05
        done
        if [[ "${publish_still_running}" = 1 ]]; then
            /bin/kill -TERM "${publish_pid}" 2>/dev/null || true
        fi
        set +e
        wait "${publish_pid}"
        publish_status=$?
        set -e

        attach_child_alive=0
        /bin/kill -0 "${attach_child}" 2>/dev/null && attach_child_alive=1
        detach_count=0
        [[ ! -f "${detach_log}" ]] || detach_count=$(/usr/bin/grep -c '^detach /dev/disk71$' "${detach_log}" || true)
        snapshot_left=0
        mountpoint_left=0
        underlay_left=0
        parent_work_left=0
        [[ ! -e "${expected_snapshot}" ]] || snapshot_left=1
        [[ ! -e "${expected_mountpoint}" ]] || mountpoint_left=1
        [[ ! -e "${expected_mountpoint}.underlay" ]] || underlay_left=1
        [[ ! -e "${expected_parent_work_dir}" ]] || parent_work_left=1
        local_tag_left=0
        remote_tag_left=0
        /usr/bin/git -C "${CASE_ROOT}" show-ref --verify --quiet refs/tags/v0.1.0 && local_tag_left=1
        /usr/bin/git --git-dir="${CASE_ORIGIN}" show-ref --verify --quiet refs/tags/v0.1.0 && remote_tag_left=1
        push_marker_left=0
        [[ ! -e "${push_marker}" ]] || push_marker_left=1

        HOME="${CASE_ROOT}" \
            FAKE_HDIUTIL_MOUNT_MANIFEST="${mount_manifest}" \
            FAKE_HDIUTIL_MOUNT_NONCE='89abcdef-0123-4567-89ab-cdef01234567' \
            FAKE_HDIUTIL_DETACH_LOG="${detach_log}" \
            recover_publish_signal_case "${attach_child}" "${expected_mountpoint}" "${expected_mount_identity}"
        [[ -f "${unmanifested_sentinel}/preserve" ]] ||
            fail "${signal_label}: exact manifest cleanup이 무관한 same-prefix sentinel을 삭제했습니다."

        case "${cleanup_failure}" in
            0) expected_snapshot_left=0; expected_mountpoint_left=0; expected_underlay_left=0; expected_parent_work_left=0 ;;
            1) expected_snapshot_left=1; expected_mountpoint_left=1; expected_underlay_left=1; expected_parent_work_left=1 ;;
        esac
        if [[ "${publish_status}" != "${expected_status}" ||
              "${publish_still_running}" != 0 ||
              "${attach_child_alive}" != 0 ||
              "${detach_count}" != 1 ||
              "${snapshot_left}" != "${expected_snapshot_left}" ||
              "${mountpoint_left}" != "${expected_mountpoint_left}" ||
              "${underlay_left}" != "${expected_underlay_left}" ||
              "${parent_work_left}" != "${expected_parent_work_left}" ||
              "${local_tag_left}" != 0 ||
              "${remote_tag_left}" != 0 ||
              "${push_marker_left}" != 0 ]]; then
            printf 'RED %s status=%s running=%s child=%s detach=%s snapshot=%s mount=%s underlay=%s parent-work=%s local=%s remote=%s push=%s\n' \
                "${signal_label}" "${publish_status}" "${publish_still_running}" \
                "${attach_child_alive}" "${detach_count}" "${snapshot_left}" \
                "${mountpoint_left}" "${underlay_left}" "${parent_work_left}" "${local_tag_left}" \
                "${remote_tag_left}" "${push_marker_left}" >&2
            parent_signal_failure=1
        else
            printf 'publish focused parent-signal %s 검증 통과\n' "${signal_label}"
        fi
    done
    [[ "${parent_signal_failure}" = 0 ]] || fail 'publish top PID signal cleanup 경계가 올바르지 않습니다.'
    printf 'publish focused parent-signal 전체 검증 통과\n'
    exit 0
fi

setup_case work-rebind
PUBLISH_FIXTURE_BARRIER_KIND=work-rebind
expect_failure work-rebind 'identity-bound cleanup' 'v0.1.0 KEY BACKUP COMPLETE'
work_rebind_path=$(/bin/cat -- "${CASE_ROOT}/.local/publish-work-rebind.path")
work_rebind_parked=$(/bin/cat -- "${CASE_ROOT}/.local/publish-work-rebind.parked")
work_rebind_transient=$(/usr/bin/dirname -- "${work_rebind_path}")
[[ -f "${work_rebind_path}/sentinel" && -d "${work_rebind_parked}" &&
   -d "${work_rebind_transient}" ]] ||
    fail 'publish cleanup이 work replacement, original 또는 ancestor evidence를 보존하지 않았습니다.'
assert_no_remote_tag work-rebind
/bin/rm -rf -- "${work_rebind_transient}"
PUBLISH_FIXTURE_BARRIER_KIND=''

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
force_push_pattern='git[[:space:]]+push([[:space:]]+[^[:space:]]+)*[[:space:]]-f([[:space:]]|$)'
if /usr/bin/grep -Eq -- "(curl|gh[[:space:]].*(release|api)|--force|${force_push_pattern}|tag[[:space:]]+-d)" "${PUBLISH_SCRIPT}"; then
    fail 'publisher에 Release API/force/delete tag 동작이 포함되어 있습니다.'
fi
printf '%s\n' 'git push origin -f refs/tags/v0.1.0' | /usr/bin/grep -Eq -- "${force_push_pattern}" ||
    fail 'forced git push negative-control을 검출하지 못했습니다.'
printf '%s\n' 'stat -f %z artifact' '[[ -f artifact ]]' | /usr/bin/grep -Eq -- "${force_push_pattern}" &&
    fail 'force push 검출이 stat 또는 regular-file 검사에 오탐했습니다.'
assert_contains "${PUBLISH_SCRIPT}" '"${tag_oid}:refs/tags/${RELEASE_TAG}"' 'publisher가 exact tag object OID refspec을 사용하지 않습니다.'

printf 'publish release tag 검증 통과\n'
