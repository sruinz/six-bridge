#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly PREPARE_SCRIPT="${ROOT_DIR}/scripts/prepare-release.sh"
readonly FIXTURE_ROOT="$(CDPATH= cd -P -- "$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-prepare-release-test.XXXXXX")" && pwd -P)"
readonly RELEASE_PASSWORD='fixture-release-password'
readonly SECRET_PROBE='fixture-secret-probe'
readonly TEMPLATE_KEYSTORE="${FIXTURE_ROOT}/fixture-release.p12"
readonly PRIVATE_TMP_RELEASE_ENTRIES_BEFORE="$(find /private/tmp -maxdepth 1 -name 'sixbridge-release-*' -print 2>/dev/null | LC_ALL=C sort)"
readonly PRIVATE_TMP_DMG_ENTRIES_BEFORE="$(find /private/tmp -maxdepth 1 -name 'sixbridge-dmg.*' -print 2>/dev/null | LC_ALL=C sort)"
readonly NATIVE_JDK17_JAVA='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java'

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
    /bin/rm -rf "${FIXTURE_ROOT}"
}
trap cleanup EXIT

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

if /usr/bin/perl -0777 -e '
    my $source = <>;
    exit($source =~ m{/usr/bin/env(?:[^\n]*\\\n){0,12}[^\n]*SIXBRIDGE_ANDROID_RELEASE_(?:STORE|KEY)_PASSWORD=} ? 0 : 1);
' "${PREPARE_SCRIPT}"; then
    fail 'external /usr/bin/env argv에 Android release 비밀번호 assignment가 남아 있습니다.'
fi

if /usr/bin/grep -q 'SIXBRIDGE_PREPARE_SANITIZER_\(READY\|CONTINUE\)' "${PREPARE_SCRIPT}"; then
    fail 'sanitizer 이전에 caller가 지정한 READY/CONTINUE 파일을 사용하는 경계가 남아 있습니다.'
fi

if /usr/bin/grep -Eq -- '--sixbridge-internal-worker|sixbridge-sanitized-worker-v1|SIXBRIDGE_PREPARE_BOUND|SIXBRIDGE_RELEASE_BOUND_PROJECT_ROOT' "${PREPARE_SCRIPT}"; then
    fail 'public internal worker selector, 고정 token 또는 과거 bound 환경 분기가 production entrypoint에 남아 있습니다.'
fi
if /usr/bin/grep -Eq -- 'SIXBRIDGE_RELEASE_BOUND_PROJECT_ROOT|BASH_SOURCE\[0\].*/dev/fd' \
    "${ROOT_DIR}/scripts/release-lib.sh"; then
    fail 'release-lib에 caller-selected root 또는 public FD source 분기가 남아 있습니다.'
fi

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

descendant_commands() {
    local root_pid=$1 queue pid child commands=''
    queue=${root_pid}
    while [[ -n "${queue}" ]]; do
        pid=${queue%% *}
        if [[ "${queue}" = *' '* ]]; then
            queue=${queue#* }
        else
            queue=''
        fi
        while IFS= read -r child; do
            [[ -n "${child}" ]] || continue
            commands+="$(/bin/ps -o command= -p "${child}")"$'\n'
            queue="${queue:+${queue} }${child}"
        done < <(/usr/bin/pgrep -P "${pid}" 2>/dev/null || true)
    done
    printf '%s' "${commands}"
}

source_file_identity() {
    /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        /usr/bin/perl -e '
            my @s = stat($ARGV[0]);
            @s or exit 1;
            printf "%s:%s:%s:%s:%s:%o:%s\n", @s[0,1,7,3,4], $s[2] & 07777, $s[10];
        ' "$1"
}

run_homebrew_resolver() {
    local prefix=$1 formula=$2 binary=$3
    /bin/bash -c '
        set -euo pipefail
        source "$1"
        release_resolve_homebrew_tool_at "$2" "$3" "$4"
    ' resolver "${ROOT_DIR}/scripts/release-lib.sh" "${prefix}" "${formula}" "${binary}"
}

run_homebrew_java_resolver() {
    local prefix=$1 formula=$2
    /bin/bash -c '
        set -euo pipefail
        source "$1"
        release_resolve_homebrew_java_at "$2" "$3"
    ' resolver "${ROOT_DIR}/scripts/release-lib.sh" "${prefix}" "${formula}"
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
        plutil) printf '%s\n' /usr/bin/plutil ;;
        plistbuddy) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/PlistBuddy" ;;
        codesign) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/codesign" ;;
        jq) printf '%s\n' "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/jq" ;;
        shasum) printf '%s\n' /usr/bin/shasum ;;
        *) release_fail '알 수 없는 release 도구입니다.'; return 1 ;;
    esac
}

release_run_apksigner() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apksigner" "$@"; }
release_run_apkanalyzer() { "${SIXBRIDGE_PREPARE_FIXTURE_BIN}/apkanalyzer" "$@"; }
release_java_executable() { printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/jdk/bin/java"; }
release_android_sdk_root() {
    printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/android-sdk"
}
release_validate_android_sdk() {
    [[ "$1" = "${SIXBRIDGE_RELEASE_ROOT}/runtime/android-sdk" && -d "$1" ]] || return 1
    release_validate_android_classpath "$1"
}
release_after_dmg_verification_cleanup_barrier() {
    local work_dir=$1 parked
    [[ "${SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION:-}" = work-rebind ]] || return 0
    parked="${work_dir}.parked"
    /bin/mv -- "${work_dir}" "${parked}" || return 1
    /bin/mkdir -m 700 -- "${work_dir}" || return 1
    printf 'preserve\n' >"${work_dir}/sentinel"
    printf '%s\n' "${work_dir}" \
        >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-work-rebind.path"
    printf '%s\n' "${parked}" \
        >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-work-rebind.parked"
}
release_node_executable() {
    case "${SIXBRIDGE_PREPARE_RUNTIME_MISMATCH:-}" in
        node) printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/node/bin/node-verified" ;;
        *) printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/node/bin/node" ;;
    esac
}
release_ripgrep_executable() {
    case "${SIXBRIDGE_PREPARE_RUNTIME_MISMATCH:-}" in
        rg) printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/rg/bin/rg-verified" ;;
        *) printf '%s\n' "${SIXBRIDGE_RELEASE_ROOT}/runtime/rg/bin/rg" ;;
    esac
}

eval "$(declare -f release_assert_file_identity | /usr/bin/sed '1s/release_assert_file_identity/release_assert_file_identity_real/')"
release_assert_file_identity() {
    local path=$1 expected=$2 size payload
    release_assert_file_identity_real "$@" || return 1
    if [[ "${SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER:-}" = helper-in-place &&
          "${path}" = "${PUBLISH_HELPER_SOURCE:-}" &&
          "${expected}" = "${PUBLISH_HELPER_SOURCE_IDENTITY:-}" &&
          ! -e "${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-helper-source-mutated" ]]; then
        : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired"
        : >"${SIXBRIDGE_RELEASE_ROOT}/.local/releases/.fixture-helper-source-mutated"
        size=$(release_stat_value '%z' "${path}") || return 1
        payload='open(my $marker, q{>}, q{.local/releases/.fixture-helper-source-executed}) or die; print {$marker} qq{executed\n}; close($marker) or die; exit 0;'
        /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
            /usr/bin/perl -e '
                my ($path, $size, $payload) = @ARGV;
                $payload .= "\n";
                length($payload) <= $size or die "payload too large\n";
                $payload .= "#" x ($size - length($payload));
                open(my $out, q{>}, $path) or die $!;
                binmode($out);
                print {$out} $payload or die $!;
                close($out) or die $!;
            ' "${path}" "${size}" "${payload}" || return 1
        [[ "$(release_stat_value '%z' "${path}")" = "${size}" ]] || return 1
    fi
}

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
        helper-in-place) : ;;
        *) return 95 ;;
    esac
}
EOF
}

create_fixture_tools() {
    local root=$1
    local bin="${root}/bin"
    mkdir -p "${bin}"
    mkdir -p "${root}/runtime/jdk/bin" "${root}/runtime/node/bin" \
        "${root}/runtime/rg/bin" "${root}/runtime/android-sdk"

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
    cat >"${bin}/node" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-NODE $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exit 99
EOF
    cat >"${bin}/rg" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "PATH-RG $*" >>"${SIXBRIDGE_PREPARE_LOG}"
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
    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'if [[ "${1:-}" = --sixbridge-encoding-probe ]]; then' \
        "  exec \"${NATIVE_JDK17_JAVA}\" -XshowSettings:properties -version" \
        'fi' \
        'printf '\''%s\n'\'' "RUNTIME-JAVA $*" >>"${SIXBRIDGE_PREPARE_LOG}"' \
        'exit 0' >"${root}/runtime/jdk/bin/java"
cat >"${root}/runtime/jdk/bin/keytool" <<'EOF'
#!/usr/bin/env bash
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" = -keystore ]]; then
    keystore=$2
    break
  fi
  shift
done
if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = utf8-nonascii ]]; then
  [[ "${LANG}" = C && "${LC_CTYPE}" = UTF-8 && -z "${LC_ALL+x}" ]] || exit 62
  case "${keystore}" in
    *'한글경로'*) ;;
    *) exit 63 ;;
  esac
fi
[[ "$(cat "${keystore}")" != keytool-fail ]] || exit 61
printf '%s\n' 'SHA256: 01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF'
EOF
    cat >"${root}/runtime/node/bin/node" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "RUNTIME-NODE $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exit 0
EOF
    cat >"${root}/runtime/rg/bin/rg" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "RUNTIME-RG $*" >>"${SIXBRIDGE_PREPARE_LOG}"
exit 0
EOF
    cp "${root}/runtime/node/bin/node" "${root}/runtime/node/bin/node-verified"
    cp "${root}/runtime/rg/bin/rg" "${root}/runtime/rg/bin/rg-verified"
    cat >"${bin}/apksigner" <<'EOF'
#!/usr/bin/env bash
[[ "${SIXBRIDGE_PREPARE_APK_VERIFY_FAIL:-0}" != 1 ]] || exit 62
if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = non-tty-stdin ]]; then
  [[ ! -t 0 ]] || exit 91
fi
    if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = argv ]]; then
      : >"${SIXBRIDGE_PREPARE_READY}"
      while [[ ! -e "${SIXBRIDGE_PREPARE_CONTINUE}" ]]; do /bin/sleep 0.05; done
    elif [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = 1 ]]; then
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
set -euo pipefail
state_root="${SIXBRIDGE_PREPARE_LOG}.dmg-state"
state_source="${state_root}/source"
state_mountpoint="${state_root}/mountpoint"
state_device="${state_root}/device"
fixture_root="$(CDPATH= builtin cd -- "$('/usr/bin/dirname' -- "$0")/.." && builtin pwd -P)"
/bin/mkdir -p "${state_root}"
/bin/chmod 700 "${state_root}"

write_inventory() {
  local plist
  plist=$(/usr/bin/mktemp "${state_root}/info.XXXXXX.plist")
  /usr/bin/plutil -create xml1 "${plist}"
  /usr/bin/plutil -insert images -array "${plist}"
  if [[ -f "${state_source}" ]]; then
    /usr/bin/plutil -insert images.0 -dictionary "${plist}"
    /usr/bin/plutil -insert images.0.image-path -string "$(/bin/cat -- "${state_source}")" "${plist}"
    /usr/bin/plutil -insert images.0.system-entities -array "${plist}"
    /usr/bin/plutil -insert images.0.system-entities.0 -dictionary "${plist}"
    /usr/bin/plutil -insert images.0.system-entities.0.dev-entry -string \
      "$(/bin/cat -- "${state_device}")" "${plist}"
    /usr/bin/plutil -insert images.0.system-entities.0.mount-point -string \
      "$(/bin/cat -- "${state_mountpoint}")" "${plist}"
  fi
  /bin/cat -- "${plist}"
  /bin/rm -f -- "${plist}"
}

case "$1" in
  info)
    [[ "${2:-}" = -plist ]] || exit 65
    write_inventory
    ;;
  attach)
    [[ "${SIXBRIDGE_PREPARE_DMG_VERIFY_FAIL:-0}" != 1 ]] || exit 64
    source=${!#}
    shift
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" = -mountpoint ]]; then mountpoint=$2; shift 2; else shift; fi
    done
    case "${mountpoint}" in /private/tmp/sixbridge-dmg.*) ;; *) exit 67 ;; esac
    [[ "$(/usr/bin/stat -f '%u:%Lp' "${mountpoint}")" = "$(/usr/bin/id -u):700" ]] || exit 68
    printf '%s\n' "${source}" >"${SIXBRIDGE_PREPARE_LOG}.dmg-source"
    printf '%s\n' "${mountpoint}" >"${SIXBRIDGE_PREPARE_LOG}.dmg-mount"
    manifest="${SIXBRIDGE_PREPARE_LOG}.dmg-mount-manifest"
    nonce=$(LC_ALL=C /usr/bin/uuidgen | /usr/bin/tr '[:upper:]' '[:lower:]')
    printf '%s' "${nonce}" >"${SIXBRIDGE_PREPARE_LOG}.dmg-nonce"
    /bin/chmod 600 "${SIXBRIDGE_PREPARE_LOG}.dmg-nonce"
    record_fake_dmg_object() {
      candidate=$1
      marker="${candidate}/.sixbridge-fixture-${nonce}"
      (umask 077; printf '%s' "${nonce}" >"${marker}")
      /bin/chmod 600 "${marker}"
      identity=$(/usr/bin/stat -f '%d:%i' "${candidate}")
      printf '%s\t%s\t%s\n' "${candidate}" "${identity}" "${nonce}" >>"${manifest}"
    }
    record_fake_dmg_object "${mountpoint}"
    mv "${mountpoint}" "${mountpoint}.underlay"
    record_fake_dmg_object "${mountpoint}.underlay"
    mkdir -m 700 "${mountpoint}"
    record_fake_dmg_object "${mountpoint}"
    /bin/ln -s /Applications "${mountpoint}/Applications"
    mkdir -p "${mountpoint}/SIX Bridge.app/Contents/app" \
      "${mountpoint}/SIX Bridge.app/Contents/Resources"
    /bin/cp -- "${SIXBRIDGE_RELEASE_ROOT:-${fixture_root}}/desktop-app/src/main/package-resources/six-bridge.icns" \
      "${mountpoint}/SIX Bridge.app/Contents/Resources/SIX Bridge.icns"
    : >"${mountpoint}/SIX Bridge.app/Contents/Info.plist"
    printf '%s\n' \
      '[Application]' \
      'app.classpath=$APPDIR/desktop-app-0.1.0-SNAPSHOT-fixture.jar' \
      'app.mainclass=dev.sixbridge.desktop.MainKt' \
      >"${mountpoint}/SIX Bridge.app/Contents/app/SIX Bridge.cfg"
    mkdir -p "${state_root}/jar-input"
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
    if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = dmg ]]; then
      printf '%s\n' "${TMPDIR}" >"${SIXBRIDGE_PREPARE_TMPDIR_RECORD}"
      : >"${TMPDIR}/sixbridge-release-fixture-child-temp"
      /bin/sleep 600 &
      printf '%s\n' "$$ $!" >"${SIXBRIDGE_PREPARE_CHILD_PID}"
      : >"${SIXBRIDGE_PREPARE_READY}"
      wait "$!"
    fi
    ;;
  detach)
    source=$(/bin/cat -- "${state_source}")
    [[ -f "${source}" ]] || exit 69
    printf '%s\n' 'HDIUTIL-DETACH snapshot-present=1' >>"${SIXBRIDGE_PREPARE_LOG}"
    [[ "${SIXBRIDGE_PREPARE_DMG_VERIFY_FAIL:-0}" != detach ]] || exit 70
    [[ "${2:-}" = "$(/bin/cat -- "${state_device}")" ]] || exit 71
    mountpoint=$(/bin/cat -- "${state_mountpoint}")
    rm -rf "${mountpoint}"
    nonce=$(/bin/cat -- "${SIXBRIDGE_PREPARE_LOG}.dmg-nonce")
    /bin/rm -f -- "${mountpoint}.underlay/.sixbridge-fixture-${nonce}"
    mv "${mountpoint}.underlay" "${mountpoint}"
    /bin/rm -f -- "${state_source}" "${state_mountpoint}" "${state_device}"
    ;;
  *) exit 65 ;;
esac
EOF
    cat >"${bin}/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in
  'Print :CFBundleIdentifier') printf '%s\n' dev.sixbridge.desktop ;;
  'Print :CFBundleShortVersionString') printf '%s\n' 1.0.0 ;;
  'Print :CFBundleVersion') printf '%s\n' 1.0.0 ;;
  'Print :LSMinimumSystemVersion') printf '%s\n' 13.0 ;;
  'Print :CFBundleIconFile') printf '%s\n' 'SIX Bridge.icns' ;;
  *) exit 66 ;;
esac
EOF
cat >"${bin}/codesign" <<'EOF'
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
    cat >"${bin}/jq" <<'EOF'
#!/usr/bin/env bash
[[ "${SIXBRIDGE_PREPARE_JQ_FAIL:-0}" != 1 ]] || exit 67
exec /usr/bin/jq "$@"
EOF
    cat >"${root}/scripts/verify.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
expected_path="/usr/bin:/bin:${SIXBRIDGE_RELEASE_ROOT}/runtime/node/bin:${SIXBRIDGE_RELEASE_ROOT}/runtime/rg/bin"
expected_sdk="${SIXBRIDGE_RELEASE_ROOT}/runtime/android-sdk"
[[ "${PATH}" = "${expected_path}" ]]
[[ "${JAVA_HOME}" = "${SIXBRIDGE_RELEASE_ROOT}/runtime/jdk" ]]
[[ "${GRADLE_USER_HOME}" = "${SIXBRIDGE_RELEASE_ROOT}/.local/gradle-home" ]]
[[ "${HOME}" = /var/empty ]]
[[ "$(/usr/bin/stat -f '%Lp' "${GRADLE_USER_HOME}")" = 700 ]]
[[ -r "${GRADLE_USER_HOME}" && -w "${GRADLE_USER_HOME}" && -x "${GRADLE_USER_HOME}" ]]
[[ -z "${SIXBRIDGE_PREPARE_UNTRUSTED_SECRET+x}" ]]
for poisoned in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS GRADLE_OPTS NODE_OPTIONS; do
  [[ -z "${!poisoned+x}" ]]
done
! /usr/bin/env | /usr/bin/grep -Fq 'BAD-NAME=poison'
ANDROID_HOME="${expected_sdk}"
ANDROID_SDK_ROOT="${expected_sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT
if [[ "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-0}" = utf8-nonascii ]]; then
  case "${SIXBRIDGE_RELEASE_ROOT}" in
    *'한글경로'*) ;;
    *) printf 'UTF8_DIAG: release root에 한글경로가 없습니다: %s\n' "${SIXBRIDGE_RELEASE_ROOT}" >&2; exit 79 ;;
  esac
  java_settings=$("${JAVA_HOME}/bin/java" --sixbridge-encoding-probe 2>&1)
  /usr/bin/grep -Fq 'file.encoding = UTF-8' <<<"${java_settings}" || {
    printf 'UTF8_DIAG: file.encoding이 UTF-8이 아닙니다.\n%s\n' "${java_settings}" >&2
    exit 80
  }
  /usr/bin/grep -Fq 'native.encoding = UTF-8' <<<"${java_settings}" || {
    printf 'UTF8_DIAG: native.encoding이 UTF-8이 아닙니다.\n%s\n' "${java_settings}" >&2
    exit 81
  }
  java_user_dir=$(/usr/bin/sed -n 's/^[[:space:]]*user[.]dir = //p' <<<"${java_settings}")
  case "${java_user_dir}" in
    ''|*'?'*)
      printf 'UTF8_DIAG: user.dir을 손실 없이 추출하지 못했습니다.\nuser.dir=%s\n%s\n' \
        "${java_user_dir}" "${java_settings}" >&2
      exit 82
      ;;
  esac
  [[ -d "${SIXBRIDGE_RELEASE_ROOT}" && -d "${java_user_dir}" ]] || {
    printf 'UTF8_DIAG: release root 또는 user.dir이 실제 directory가 아닙니다.\nexpected=%s\nuser.dir=%s\n' \
      "${SIXBRIDGE_RELEASE_ROOT}" "${java_user_dir}" >&2
    exit 82
  }
  release_root_identity=$(/usr/bin/stat -f '%d:%i' "${SIXBRIDGE_RELEASE_ROOT}")
  java_user_dir_identity=$(/usr/bin/stat -f '%d:%i' "${java_user_dir}")
  [[ "${java_user_dir_identity}" = "${release_root_identity}" ]] || {
    printf 'UTF8_DIAG: user.dir이 release root와 같은 directory를 가리키지 않습니다.\nexpected=%s (%s)\nuser.dir=%s (%s)\n' \
      "${SIXBRIDGE_RELEASE_ROOT}" "${release_root_identity}" \
      "${java_user_dir}" "${java_user_dir_identity}" >&2
    exit 82
  }
  [[ "${LANG}" = C && "${LC_CTYPE}" = UTF-8 && -z "${LC_ALL+x}" ]] || {
    printf 'UTF8_DIAG: locale이 다릅니다. LANG=%s LC_CTYPE=%s LC_ALL_SET=%s\n' \
      "${LANG:-}" "${LC_CTYPE:-}" "${LC_ALL+x}" >&2
    exit 83
  }
fi
"${JAVA_HOME}/bin/java" verify
node verify
rg verify
printf '%s\n' verify >>"${SIXBRIDGE_PREPARE_LOG}"
[[ "${SIXBRIDGE_PREPARE_VERIFY_FAIL:-0}" != 1 ]]
EOF
    cat >"${root}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
expected_path="/usr/bin:/bin:${SIXBRIDGE_RELEASE_ROOT}/runtime/node/bin:${SIXBRIDGE_RELEASE_ROOT}/runtime/rg/bin"
expected_sdk="${SIXBRIDGE_RELEASE_ROOT}/runtime/android-sdk"
[[ "${PATH}" = "${expected_path}" ]]
[[ "${JAVA_HOME}" = "${SIXBRIDGE_RELEASE_ROOT}/runtime/jdk" ]]
[[ "${GRADLE_USER_HOME}" = "${SIXBRIDGE_RELEASE_ROOT}/.local/gradle-home" ]]
[[ "${HOME}" = /var/empty ]]
[[ "${ANDROID_HOME:-}" = "${expected_sdk}" && "${ANDROID_SDK_ROOT:-}" = "${expected_sdk}" ]] || {
  printf 'SDK_ENV_DIAG: 검증된 Android SDK가 final Gradle에 전달되지 않았습니다.\n' >&2
  exit 75
}
for poisoned in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS GRADLE_OPTS NODE_OPTIONS; do
  [[ -z "${!poisoned+x}" ]]
done
! /usr/bin/env | /usr/bin/grep -Fq 'BAD-NAME=poison'
printf '%s\n' "$*" >>"${SIXBRIDGE_PREPARE_LOG}"
case " $* " in
  *' :android-manager:assembleRelease '*)
    "${JAVA_HOME}/bin/java" android
    node android
    rg android
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
    "${JAVA_HOME}/bin/java" dmg
    node dmg
    rg dmg
    [[ -z "${SIXBRIDGE_MAC_SIGNING_IDENTITY:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_PASSWORD:-}" && -z "${SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID:-}" ]] || exit 72
    [[ "${SIXBRIDGE_PREPARE_DMG_BUILD_FAIL:-0}" != 1 ]] || exit 73
    mkdir -p 'desktop-app/build/compose/binaries/main/dmg'
    printf '%s\n' dmg-artifact >'desktop-app/build/compose/binaries/main/dmg/SIX Bridge-1.0.0.dmg'
    ;;
  *) exit 74 ;;
esac
EOF
    chmod +x "${bin}"/* "${root}/runtime/jdk/bin/"* "${root}/runtime/node/bin/"* \
        "${root}/runtime/rg/bin/"* "${root}/scripts/verify.sh" "${root}/gradlew"
}

setup_case() {
    local name=$1
    CASE_ROOT="${FIXTURE_ROOT}/${name}"
    CASE_PREPARE_SCRIPT="${CASE_ROOT}/scripts/prepare-release.sh"
    CASE_LOG="${CASE_ROOT}/.local/calls.log"
    rm -rf "${CASE_ROOT}"
    mkdir -p "${CASE_ROOT}/scripts" "${CASE_ROOT}/docs/releases" "${CASE_ROOT}/.local/keystore" \
        "${CASE_ROOT}/desktop-app/src/main/package-resources"
    chmod 700 "${CASE_ROOT}/.local" "${CASE_ROOT}/.local/keystore"
    : >"${CASE_LOG}"
    copy_fixture_library "${CASE_ROOT}"
    cp "${PREPARE_SCRIPT}" "${CASE_PREPARE_SCRIPT}"
    chmod 755 "${CASE_PREPARE_SCRIPT}"
    create_fixture_tools "${CASE_ROOT}"
    [[ ! -e "${ROOT_DIR}/scripts/release-atomic-publish.pl" ]] || \
        cp "${ROOT_DIR}/scripts/release-atomic-publish.pl" "${CASE_ROOT}/scripts/release-atomic-publish.pl"
    cp "${ROOT_DIR}/docs/releases/v0.1.0-ko.md" "${CASE_ROOT}/docs/releases/v0.1.0-ko.md"
    cp "${ROOT_DIR}/desktop-app/src/main/package-resources/six-bridge.icns" \
        "${CASE_ROOT}/desktop-app/src/main/package-resources/six-bridge.icns"
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
            SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" \
            SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
            SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}" \
            SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
            SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
            "$@" \
            "${CASE_PREPARE_SCRIPT}" 0.1.0
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

[[ -x "${NATIVE_JDK17_JAVA}" ]] || fail "UTF-8 locale fixture JDK 17을 찾을 수 없습니다: ${NATIVE_JDK17_JAVA}"

if [[ "${SIXBRIDGE_PREPARE_TEST_NORMAL_ONLY:-0}" = 1 ]]; then
    setup_case normal-only
    /bin/mkdir -m 700 -- "${CASE_ROOT}/.local/releases"
    run_prepare
    normal_final="${CASE_ROOT}/.local/releases/v0.1.0"
    assert_exact_release_files "${normal_final}"
    assert_no_release_work_entries "${CASE_ROOT}"
    [[ -z "$(/usr/bin/find "${CASE_ROOT}/.local" -maxdepth 1 \
        -name '.sixbridge-sdk-classpath.*' -print)" ]] ||
        fail '정상 prepare 뒤 Android SDK classpath 임시 directory가 남았습니다.'
    normal_snapshot=$(/bin/cat -- "${CASE_LOG}.dmg-source")
    normal_mountpoint=$(/bin/cat -- "${CASE_LOG}.dmg-mount")
    [[ ! -e "${normal_snapshot}" && ! -L "${normal_snapshot}" &&
       ! -e "${normal_mountpoint}" && ! -L "${normal_mountpoint}" ]] ||
        fail '정상 prepare 뒤 DMG snapshot 또는 mountpoint가 남았습니다.'
    for normal_state in source mountpoint device; do
        [[ ! -e "${CASE_LOG}.dmg-state/${normal_state}" ]] ||
            fail "정상 prepare 뒤 fake hdiutil ${normal_state} state가 남았습니다."
    done
    printf '%s\n' 'prepare 정상 DMG inventory 통합 검증 통과'
    exit 0
fi

if [[ "${SIXBRIDGE_PREPARE_TEST_SIGNAL_ONLY:-0}" != 1 ]]; then
setup_case fake-dmg-manifest-cleanup
fixture_cleanup_source="${CASE_ROOT}/fixture-cleanup.dmg"
: >"${fixture_cleanup_source}"
fixture_cleanup_mountpoint=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.XXXXXX')
/bin/chmod 700 "${fixture_cleanup_mountpoint}"
set +e
(
    trap 'cleanup_fake_dmg_manifests "${FIXTURE_ROOT}"' EXIT
    SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" "${CASE_ROOT}/bin/hdiutil" \
        attach -readonly -nobrowse -mountpoint "${fixture_cleanup_mountpoint}" \
        "${fixture_cleanup_source}"
    exit 91
)
fixture_cleanup_status=$?
set -e
[[ "${fixture_cleanup_status}" = 91 ]] || fail "prepare fake DMG 중간 실패 status가 다릅니다: ${fixture_cleanup_status}"
if [[ -e "${fixture_cleanup_mountpoint}" || -L "${fixture_cleanup_mountpoint}" ||
      -e "${fixture_cleanup_mountpoint}.underlay" || -L "${fixture_cleanup_mountpoint}.underlay" ]]; then
    /bin/rm -rf -- "${fixture_cleanup_mountpoint}" "${fixture_cleanup_mountpoint}.underlay"
    fail 'prepare fake DMG 중간 실패 뒤 exact local overlay 또는 underlay가 남았습니다.'
fi

fixture_reused_mountpoint=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.XXXXXX')
/bin/chmod 700 "${fixture_reused_mountpoint}"
set +e
(
    trap 'cleanup_fake_dmg_manifests "${FIXTURE_ROOT}"' EXIT
    SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" "${CASE_ROOT}/bin/hdiutil" \
        attach -readonly -nobrowse -mountpoint "${fixture_reused_mountpoint}" \
        "${fixture_cleanup_source}"
    /bin/rm -rf -- "${fixture_reused_mountpoint}"
    /bin/mkdir -m 700 "${fixture_reused_mountpoint}"
    : >"${fixture_reused_mountpoint}/same-user-reused-path"
    exit 92
)
fixture_reused_status=$?
set -e
[[ "${fixture_reused_status}" = 92 ]] || fail "prepare fake DMG path reuse status가 다릅니다: ${fixture_reused_status}"
fixture_reused_preserved=0
[[ -f "${fixture_reused_mountpoint}/same-user-reused-path" ]] && fixture_reused_preserved=1
/bin/rm -rf -- "${fixture_reused_mountpoint}" "${fixture_reused_mountpoint}.underlay"
[[ "${fixture_reused_preserved}" = 1 ]] || fail 'prepare manifest cleanup이 같은 UID로 재사용된 경로를 삭제했습니다.'

fixture_unmanifested_sentinel=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.unmanifested.XXXXXX')
/bin/chmod 700 "${fixture_unmanifested_sentinel}"
: >"${fixture_unmanifested_sentinel}/preserve"
cleanup_fake_dmg_manifests "${FIXTURE_ROOT}"
fixture_sentinel_preserved=0
[[ -f "${fixture_unmanifested_sentinel}/preserve" ]] && fixture_sentinel_preserved=1
/bin/rm -rf -- "${fixture_unmanifested_sentinel}"
[[ "${fixture_sentinel_preserved}" = 1 ]] || fail 'prepare manifest에 없는 같은 prefix sentinel을 cleanup이 삭제했습니다.'

if [[ "${SIXBRIDGE_PREPARE_TEST_DMG_MANIFEST_ONLY:-0}" = 1 ]]; then
    printf '%s\n' 'prepare fake DMG manifest 검증 통과'
    exit 0
fi

setup_case forged-public-internal-worker
mkdir -p "${CASE_ROOT}/.local/gradle-home"
chmod 700 "${CASE_ROOT}/.local/gradle-home"
forged_runtime_path="/usr/bin:/bin:${CASE_ROOT}/runtime/node/bin:${CASE_ROOT}/runtime/rg/bin"
expect_failure forged-public-internal-worker '지원하지 않는 release version' \
    /bin/bash -c '
        exec 8< <(printf "%s\0" sixbridge-sanitized-worker-v1)
        exec /usr/bin/env -i \
            PATH="$1" JAVA_HOME="$2" GRADLE_USER_HOME="$3" \
            HOME=/var/empty LANG=C LC_ALL=C \
            SIXBRIDGE_RELEASE_ROOT="$4" SIXBRIDGE_PREPARE_FIXTURE_BIN="$5" \
            SIXBRIDGE_PREPARE_LOG="$6" SIXBRIDGE_PREPARE_SIGNER="$7" \
            SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="$8" \
            SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="$8" \
            "$9" --sixbridge-internal-worker 0.1.0
    ' forged-worker "${forged_runtime_path}" "${CASE_ROOT}/runtime/jdk" \
        "${CASE_ROOT}/.local/gradle-home" "${CASE_ROOT}" "${CASE_ROOT}/bin" \
        "${CASE_LOG}" "${FIXTURE_SIGNER}" "${RELEASE_PASSWORD}" "${CASE_PREPARE_SCRIPT}"
assert_no_final_directory "${CASE_ROOT}"

setup_case public-source-mode
set +e
/bin/bash --noprofile --norc -p -c '
    source "$1"
    declare -F prepare_release_worker
' public-source "${CASE_PREPARE_SCRIPT}" >"${FIXTURE_ROOT}/public-source-mode.log" 2>&1
public_source_status=$?
set -e
[[ "${public_source_status}" -ne 0 ]] || fail 'public prepare source mode가 worker 함수를 노출했습니다.'
assert_contains "${FIXTURE_ROOT}/public-source-mode.log" 'source할 수 없는 direct-exec entrypoint' \
    'public prepare source mode의 명시적 거부 사유가 없습니다.'
assert_not_contains "${FIXTURE_ROOT}/public-source-mode.log" 'prepare_release_worker' \
    'public prepare source mode에서 worker 함수가 보입니다.'
assert_no_final_directory "${CASE_ROOT}"

setup_case public-source-debug-trap
set +e
/bin/bash --noprofile --norc -p -c '
    set -T
    trap '\''if [[ "$BASH_COMMAND" == "run_preparation_worker \"\$@\"" ]]; then
        trap - DEBUG
        return 0
    fi'\'' DEBUG
    source "$0" 9.9.9
    declare -F prepare_release_worker
' "${CASE_PREPARE_SCRIPT}" >"${FIXTURE_ROOT}/public-source-debug-trap.log" 2>&1
public_source_debug_status=$?
set -e
[[ "${public_source_debug_status}" -ne 0 ]] || \
    fail '위조한 argv0와 DEBUG trap이 public prepare source mode를 우회했습니다.'
assert_contains "${FIXTURE_ROOT}/public-source-debug-trap.log" 'source할 수 없는 direct-exec entrypoint' \
    'DEBUG trap source 공격의 명시적 거부 사유가 없습니다.'
assert_not_contains "${FIXTURE_ROOT}/public-source-debug-trap.log" 'prepare_release_worker' \
    'DEBUG trap source 공격 뒤 worker 함수가 보입니다.'
assert_no_final_directory "${CASE_ROOT}"

setup_case worker-program-missing-function
/usr/bin/perl -0pi -e '
    s/publish_verified_snapshot prepare_release_worker;/publish_verified_snapshot;/
        or die "worker function list substitution failed\n";
' "${CASE_PREPARE_SCRIPT}"
expect_failure worker-program-missing-function 'worker program 필수 함수가 없습니다: prepare_release_worker' \
    run_prepare
assert_no_final_directory "${CASE_ROOT}"
assert_no_release_work_entries "${CASE_ROOT}"

setup_case forged-source-fds
forged_source_marker="${CASE_ROOT}/.local/forged-source-lib-sourced"
forged_source_lib="${CASE_ROOT}/.local/forged-release-lib.sh"
printf ': >%q\n' "${forged_source_marker}" >"${forged_source_lib}"
/bin/chmod 600 "${forged_source_lib}"
forged_source_identity=$(source_file_identity "${CASE_PREPARE_SCRIPT}")
forged_lib_identity=$(source_file_identity "${forged_source_lib}")
set +e
/bin/bash --noprofile --norc -p -c '
    exec 6<"$1"
    exec 7<"$2"
    SIXBRIDGE_PREPARE_BOUND_ROOT=$3
    SIXBRIDGE_PREPARE_BOUND_SOURCE_IDENTITY=$4
    SIXBRIDGE_PREPARE_BOUND_LIB_IDENTITY=$5
    source /dev/fd/6
' forged-source "${CASE_PREPARE_SCRIPT}" "${forged_source_lib}" "${CASE_ROOT}" \
    "${forged_source_identity}" "${forged_lib_identity}" \
    >"${FIXTURE_ROOT}/forged-source-fds.log" 2>&1
forged_source_status=$?
set -e
[[ "${forged_source_status}" -ne 0 ]] || fail 'forged source fd fixture가 성공했습니다.'
[[ ! -e "${forged_source_marker}" ]] || fail '외부 forged FD의 release-lib.sh가 source 되었습니다.'
assert_no_final_directory "${CASE_ROOT}"

poison_root="${FIXTURE_ROOT}/root-override-source-injection"
poison_marker="${poison_root}/source-marker"
mkdir -p "${poison_root}/scripts"
cat >"${poison_root}/scripts/release-lib.sh" <<EOF
: >"${poison_marker}"
release_require_version() { return 1; }
EOF
set +e
SIXBRIDGE_RELEASE_ROOT="${poison_root}" \
    SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
    SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
    "${PREPARE_SCRIPT}" 9.9.9 >"${FIXTURE_ROOT}/root-override-source-injection.log" 2>&1
poison_root_status=$?
set -e
[[ "${poison_root_status}" -ne 0 ]] || fail 'caller-selected release root fixture가 성공했습니다.'
[[ ! -e "${poison_marker}" ]] || fail 'caller-selected release root의 release-lib.sh가 source 되었습니다.'
assert_contains "${FIXTURE_ROOT}/root-override-source-injection.log" '지원하지 않는 release version' \
    'caller-selected release root를 무시한 실제 entrypoint version 거부가 없습니다.'

setup_case symlink-entrypoint
symlink_entrypoint="${CASE_ROOT}/scripts/prepare-release-link.sh"
ln -s prepare-release.sh "${symlink_entrypoint}"
expect_failure symlink-entrypoint 'symbolic link entrypoint' \
    /usr/bin/env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${symlink_entrypoint}" 9.9.9
assert_no_final_directory "${CASE_ROOT}"

setup_case writable-release-library
writable_lib_marker="${CASE_ROOT}/.local/writable-release-lib-sourced"
writable_lib_temp="${CASE_ROOT}/scripts/release-lib.writable"
{
    printf ': >%q\n' "${writable_lib_marker}"
    /bin/cat "${CASE_ROOT}/scripts/release-lib.sh"
} >"${writable_lib_temp}"
/bin/mv "${writable_lib_temp}" "${CASE_ROOT}/scripts/release-lib.sh"
/bin/chmod 666 "${CASE_ROOT}/scripts/release-lib.sh"
expect_failure writable-release-library 'group/other write' \
    /usr/bin/env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${CASE_PREPARE_SCRIPT}" 9.9.9
[[ ! -e "${writable_lib_marker}" ]] || fail 'writable release-lib.sh가 trust 검증 전에 source 되었습니다.'
assert_no_final_directory "${CASE_ROOT}"

set +e
(
    export SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}"
    export SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}"
    exec "${PREPARE_SCRIPT}" 9.9.9
) >"${FIXTURE_ROOT}/preflight-invalid-version.log" 2>&1
preflight_status=$?
set -e
[[ "${preflight_status}" -ne 0 ]] || fail '실제 runtime 전 invalid version preflight가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/preflight-invalid-version.log" '지원하지 않는 release version' \
    'invalid version이 실제 runtime prerequisite보다 먼저 거부되지 않았습니다.'

set +e
(
    unset SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD
    export SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}"
    exec "${PREPARE_SCRIPT}" 0.1.0
) >"${FIXTURE_ROOT}/preflight-missing-credential.log" 2>&1
preflight_status=$?
set -e
[[ "${preflight_status}" -ne 0 ]] || fail '실제 runtime 전 missing credential preflight가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/preflight-missing-credential.log" 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' \
    'missing credential이 실제 runtime prerequisite보다 먼저 거부되지 않았습니다.'

setup_case sdk-worker-propagation
sdk_worker_log="${FIXTURE_ROOT}/sdk-worker-propagation.log"
if ! run_prepare /usr/bin/env ANDROID_HOME=/caller/poison-sdk \
    ANDROID_SDK_ROOT=/caller/poison-sdk-root >"${sdk_worker_log}" 2>&1; then
    cat "${sdk_worker_log}" >&2
    fail '검증된 Android SDK가 verify 이후 final Gradle까지 전달되지 않았습니다.'
fi
assert_exact_release_files "${CASE_ROOT}/.local/releases/v0.1.0"
assert_log_has_no_secret "${sdk_worker_log}"

resolver_root="${FIXTURE_ROOT}/resolver-canonical"
mkdir -p "${resolver_root}/opt" "${resolver_root}/Cellar/node/25.1.0/bin"
chmod 755 "${resolver_root}/opt" "${resolver_root}/Cellar"
cat >"${resolver_root}/Cellar/node/25.1.0/bin/node" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod 700 "${resolver_root}/Cellar/node/25.1.0/bin/node"
ln -s ../Cellar/node/25.1.0 "${resolver_root}/opt/node"
set +e
resolver_output=$(run_homebrew_resolver "${resolver_root}" node node 2>"${FIXTURE_ROOT}/resolver-canonical.log")
resolver_status=$?
set -e
[[ "${resolver_status}" = 0 ]] || {
    cat "${FIXTURE_ROOT}/resolver-canonical.log" >&2
    fail 'canonical Homebrew resolver fixture가 성공하지 못했습니다.'
}
[[ "${resolver_output}" = "${resolver_root}/Cellar/node/25.1.0/bin/node" ]] || \
    fail "canonical Homebrew resolver 결과가 다릅니다: ${resolver_output}"

chmod 775 "${resolver_root}/opt"
set +e
run_homebrew_resolver "${resolver_root}" node node >"${FIXTURE_ROOT}/resolver-group-writable.out" 2>&1
resolver_status=$?
set -e
[[ "${resolver_status}" -ne 0 ]] || fail 'group-writable Homebrew resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/resolver-group-writable.out" 'group/other write' \
    'group-writable Homebrew resolver의 명확한 거부 사유가 없습니다.'
chmod 755 "${resolver_root}/opt"

chmod 777 "${resolver_root}/opt"
set +e
run_homebrew_resolver "${resolver_root}" node node >"${FIXTURE_ROOT}/resolver-other-writable.out" 2>&1
resolver_status=$?
set -e
[[ "${resolver_status}" -ne 0 ]] || fail 'other-writable Homebrew resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/resolver-other-writable.out" 'group/other write' \
    'other-writable Homebrew resolver의 거부 사유가 없습니다.'
chmod 755 "${resolver_root}/opt"

resolver_external="${FIXTURE_ROOT}/resolver-external"
resolver_external_target="${FIXTURE_ROOT}/resolver-external-target"
mkdir -p "${resolver_external}/opt" "${resolver_external}/Cellar/node" "${resolver_external_target}/bin"
cp "${resolver_root}/Cellar/node/25.1.0/bin/node" "${resolver_external_target}/bin/node"
chmod 700 "${resolver_external_target}/bin/node"
ln -s "${resolver_external_target}" "${resolver_external}/opt/node"
set +e
run_homebrew_resolver "${resolver_external}" node node >"${FIXTURE_ROOT}/resolver-external.out" 2>&1
resolver_status=$?
set -e
[[ "${resolver_status}" -ne 0 ]] || fail 'Cellar 밖 Homebrew resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/resolver-external.out" 'canonical' 'Cellar 밖 resolver의 한글 거부 사유가 없습니다.'

resolver_symlink="${FIXTURE_ROOT}/resolver-binary-symlink"
mkdir -p "${resolver_symlink}/opt" "${resolver_symlink}/Cellar/node/25.1.0/bin"
ln -s /bin/true "${resolver_symlink}/Cellar/node/25.1.0/bin/node"
ln -s ../Cellar/node/25.1.0 "${resolver_symlink}/opt/node"
set +e
run_homebrew_resolver "${resolver_symlink}" node node >"${FIXTURE_ROOT}/resolver-symlink.out" 2>&1
resolver_status=$?
set -e
[[ "${resolver_status}" -ne 0 ]] || fail 'binary symbolic link Homebrew resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/resolver-symlink.out" 'regular file' 'binary symlink resolver의 한글 거부 사유가 없습니다.'

resolver_missing="${FIXTURE_ROOT}/resolver-missing"
mkdir -p "${resolver_missing}/opt" "${resolver_missing}/Cellar/ripgrep"
set +e
run_homebrew_resolver "${resolver_missing}" ripgrep rg >"${FIXTURE_ROOT}/resolver-missing.out" 2>&1
resolver_status=$?
set -e
[[ "${resolver_status}" -ne 0 ]] || fail '누락된 Homebrew rg resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/resolver-missing.out" '고정된 rg 도구를 찾을 수 없습니다.' \
    '누락된 Homebrew rg prerequisite의 한글 거부 사유가 없습니다.'

jdk_resolver_root="${FIXTURE_ROOT}/jdk-resolver-canonical"
mkdir -p "${jdk_resolver_root}/opt" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home/bin"
chmod 755 "${jdk_resolver_root}/opt" "${jdk_resolver_root}/Cellar" \
    "${jdk_resolver_root}/Cellar/openjdk@17" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home" \
    "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home/bin"
cat >"${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home/bin/java" <<'EOF'
#!/bin/bash
exit 0
EOF
chmod 700 "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home/bin/java"
ln -s ../Cellar/openjdk@17/17.0.12 "${jdk_resolver_root}/opt/openjdk@17"
jdk_resolver_output=$(run_homebrew_java_resolver "${jdk_resolver_root}" openjdk@17) || \
    fail 'canonical Homebrew JDK resolver가 실패했습니다.'
[[ "${jdk_resolver_output}" = "${jdk_resolver_root}/Cellar/openjdk@17/17.0.12/libexec/openjdk.jdk/Contents/Home/bin/java" ]] || \
    fail "canonical Homebrew JDK resolver 결과가 다릅니다: ${jdk_resolver_output}"

chmod 775 "${jdk_resolver_root}/opt"
set +e
run_homebrew_java_resolver "${jdk_resolver_root}" openjdk@17 >"${FIXTURE_ROOT}/jdk-resolver-opt-group-writable.out" 2>&1
jdk_resolver_status=$?
set -e
[[ "${jdk_resolver_status}" -ne 0 ]] || fail 'group-writable Homebrew JDK opt resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/jdk-resolver-opt-group-writable.out" 'group/other write' \
    'group-writable Homebrew JDK opt resolver의 명확한 거부 사유가 없습니다.'
chmod 755 "${jdk_resolver_root}/opt"

chmod 775 "${jdk_resolver_root}/Cellar"
set +e
run_homebrew_java_resolver "${jdk_resolver_root}" openjdk@17 >"${FIXTURE_ROOT}/jdk-resolver-cellar-group-writable.out" 2>&1
jdk_resolver_status=$?
set -e
[[ "${jdk_resolver_status}" -ne 0 ]] || fail 'group-writable Homebrew JDK Cellar resolver가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/jdk-resolver-cellar-group-writable.out" 'group/other write' \
    'group-writable Homebrew JDK Cellar resolver의 명확한 거부 사유가 없습니다.'
chmod 755 "${jdk_resolver_root}/Cellar"

setup_case entrypoint-path-bash
entrypoint_path_marker="${CASE_ROOT}/entrypoint-path-bash-marker"
cat >"${CASE_ROOT}/bin/bash" <<EOF
#!/bin/bash
: >"${entrypoint_path_marker}"
exec /bin/bash "\$@"
EOF
chmod +x "${CASE_ROOT}/bin/bash"
set +e
/usr/bin/env -i PATH="${CASE_ROOT}/bin:/usr/bin:/bin" HOME=/var/empty LANG=C LC_ALL=C \
    SIXBRIDGE_PREPARE_WORKER=1 \
    "${PREPARE_SCRIPT}" 9.9.9 >"${FIXTURE_ROOT}/entrypoint-path-bash.log" 2>&1
entrypoint_status=$?
set -e
[[ "${entrypoint_status}" -ne 0 ]] || fail 'entrypoint PATH poison fixture가 성공했습니다.'
[[ ! -e "${entrypoint_path_marker}" ]] || fail 'entrypoint가 PATH의 bash shim을 실행했습니다.'
assert_contains "${FIXTURE_ROOT}/entrypoint-path-bash.log" '지원하지 않는 release version' \
    'entrypoint PATH poison fixture가 worker의 version 검증까지 도달하지 못했습니다.'

setup_case entrypoint-shell-startup
entrypoint_bash_env_marker="${CASE_ROOT}/entrypoint-bash-env-marker"
entrypoint_env_marker="${CASE_ROOT}/entrypoint-env-marker"
entrypoint_function_marker="${CASE_ROOT}/entrypoint-function-marker"
entrypoint_shellopts_marker="${CASE_ROOT}/entrypoint-shellopts-marker"
printf ': >%q\n' "${entrypoint_bash_env_marker}" >"${CASE_ROOT}/poison-bash-env.sh"
printf ': >%q\n' "${entrypoint_env_marker}" >"${CASE_ROOT}/poison-env.sh"
set +e
/usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LANG=C LC_ALL=C \
    BASH_ENV="${CASE_ROOT}/poison-bash-env.sh" ENV="${CASE_ROOT}/poison-env.sh" \
    SHELLOPTS=xtrace PS4='+$(: >"${SIXBRIDGE_ENTRYPOINT_SHELLOPTS_MARKER}") ' \
    SIXBRIDGE_ENTRYPOINT_FUNCTION_MARKER="${entrypoint_function_marker}" \
    SIXBRIDGE_ENTRYPOINT_SHELLOPTS_MARKER="${entrypoint_shellopts_marker}" \
    'BASH_FUNC_dirname%%=() { : > "$SIXBRIDGE_ENTRYPOINT_FUNCTION_MARKER"; /usr/bin/dirname "$@"; }' \
    SIXBRIDGE_PREPARE_WORKER=1 \
    "${PREPARE_SCRIPT}" 9.9.9 >"${FIXTURE_ROOT}/entrypoint-shell-startup.log" 2>&1
entrypoint_status=$?
set -e
[[ "${entrypoint_status}" -ne 0 ]] || fail 'entrypoint shell startup poison fixture가 성공했습니다.'
[[ ! -e "${entrypoint_bash_env_marker}" ]] || fail 'entrypoint가 inherited BASH_ENV를 실행했습니다.'
[[ ! -e "${entrypoint_env_marker}" ]] || fail 'entrypoint가 inherited ENV를 실행했습니다.'
[[ ! -e "${entrypoint_function_marker}" ]] || fail 'entrypoint가 exported dirname function을 실행했습니다.'
[[ ! -e "${entrypoint_shellopts_marker}" ]] || fail 'entrypoint가 inherited SHELLOPTS/xtrace를 실행했습니다.'
assert_contains "${FIXTURE_ROOT}/entrypoint-shell-startup.log" '지원하지 않는 release version' \
    'entrypoint shell startup poison fixture가 worker의 version 검증까지 도달하지 못했습니다.'

setup_case entrypoint-shebang-bypass
set +e
/bin/bash "${PREPARE_SCRIPT}" 9.9.9 >"${FIXTURE_ROOT}/entrypoint-shebang-bypass.log" 2>&1
entrypoint_status=$?
set -e
[[ "${entrypoint_status}" -ne 0 ]] || fail 'privileged shebang 우회 fixture가 성공했습니다.'
assert_contains "${FIXTURE_ROOT}/entrypoint-shebang-bypass.log" 'privileged Bash' \
    '직접 /bin/bash 호출의 명확한 entrypoint 거부 사유가 없습니다.'

setup_case invalid-version
expect_failure invalid-version '지원하지 않는 release version' \
    /usr/bin/env PATH="${CASE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${CASE_PREPARE_SCRIPT}" 9.9.9
[[ ! -s "${CASE_LOG}" ]] || fail 'invalid version preflight 전에 runtime/build 도구가 실행되었습니다.'
[[ ! -e "${CASE_ROOT}/.local/gradle-home" ]] || fail 'invalid version preflight 전에 GRADLE_USER_HOME이 생성되었습니다.'
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

setup_case gradle-local-symlink
outside_local="${FIXTURE_ROOT}/outside-local"
mv "${CASE_ROOT}/.local" "${outside_local}"
ln -s "${outside_local}" "${CASE_ROOT}/.local"
expect_failure gradle-local-symlink 'symbolic link' run_prepare
[[ ! -e "${outside_local}/gradle-home" && ! -L "${outside_local}/gradle-home" ]] || \
    fail 'canonical 검사 전에 외부 .local에 GRADLE_USER_HOME을 만들었습니다.'
assert_no_final_directory "${CASE_ROOT}"

setup_case gradle-home-symlink
mkdir -p "${CASE_ROOT}/.local/gradle-home-target"
chmod 700 "${CASE_ROOT}/.local/gradle-home-target"
ln -s gradle-home-target "${CASE_ROOT}/.local/gradle-home"
expect_failure gradle-home-symlink 'GRADLE_USER_HOME symbolic link' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case gradle-home-nonprivate
mkdir -p "${CASE_ROOT}/.local/gradle-home"
chmod 755 "${CASE_ROOT}/.local/gradle-home"
expect_failure gradle-home-nonprivate 'group/other 접근' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case gradle-home-nonwritable
mkdir -p "${CASE_ROOT}/.local/gradle-home"
chmod 500 "${CASE_ROOT}/.local/gradle-home"
expect_failure gradle-home-nonwritable '정확히 0700' run_prepare
assert_no_final_directory "${CASE_ROOT}"

setup_case gradle-home-no-read
mkdir -p "${CASE_ROOT}/.local/gradle-home"
chmod 300 "${CASE_ROOT}/.local/gradle-home"
expect_failure gradle-home-no-read '정확히 0700' run_prepare
chmod 700 "${CASE_ROOT}/.local/gradle-home"
assert_no_final_directory "${CASE_ROOT}"

setup_case sanitized-worker-argv
sanitizer_ready="${CASE_ROOT}/.local/sanitizer-ready"
sanitizer_continue="${CASE_ROOT}/.local/sanitizer-continue"
sanitizer_log="${FIXTURE_ROOT}/sanitized-worker-argv.log"
(
    cd "${CASE_ROOT}"
    export PATH="${CASE_ROOT}/bin:${PATH}"
    export SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin"
    export SIXBRIDGE_PREPARE_LOG="${CASE_LOG}"
    export SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}"
    export SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}"
    export SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}"
    export SIXBRIDGE_PREPARE_WORKER=1
    export SIXBRIDGE_PREPARE_UNTRUSTED_SECRET="${SECRET_PROBE}"
    export SIXBRIDGE_PREPARE_WAIT_IN_VERIFY=argv
    export SIXBRIDGE_PREPARE_READY="${sanitizer_ready}"
    export SIXBRIDGE_PREPARE_CONTINUE="${sanitizer_continue}"
    exec /usr/bin/env 'BAD-NAME=poison' "${CASE_PREPARE_SCRIPT}" 0.1.0
) >"${sanitizer_log}" 2>&1 &
sanitizer_parent_pid=$!
for _ in $(seq 1 100); do [[ -e "${sanitizer_ready}" ]] && break; /bin/sleep 0.05; done
[[ -e "${sanitizer_ready}" ]] || {
    kill -TERM "${sanitizer_parent_pid}" 2>/dev/null || true
    wait "${sanitizer_parent_pid}" 2>/dev/null || true
    cat "${sanitizer_log}" >&2
    fail 'sanitized worker가 argv 검사 barrier에 도달하지 못했습니다.'
}
sanitizer_commands=$(descendant_commands "${sanitizer_parent_pid}")
[[ "${sanitizer_commands}" != *"${RELEASE_PASSWORD}"* ]] || {
    printf '%s\n' "${sanitizer_commands}" >&2
    fail 'worker child argv에 실제 Android release 비밀번호가 노출되었습니다.'
}
[[ "${sanitizer_commands}" != *"${SECRET_PROBE}"* ]] || {
    printf '%s\n' "${sanitizer_commands}" >&2
    fail 'worker child argv에 secret probe가 노출되었습니다.'
}
: >"${sanitizer_continue}"
if ! wait "${sanitizer_parent_pid}"; then
    cat "${sanitizer_log}" >&2
    fail 'argv 검사 뒤 sanitized worker가 정상 release를 완료하지 못했습니다.'
fi
assert_exact_release_files "${CASE_ROOT}/.local/releases/v0.1.0"
assert_log_has_no_secret "${sanitizer_log}"

setup_case tty-worker-stdin
tty_worker_log="${FIXTURE_ROOT}/tty-worker-stdin.log"
set +e
(
    cd "${CASE_ROOT}"
    export PATH="${CASE_ROOT}/bin:${PATH}"
    export SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin"
    export SIXBRIDGE_PREPARE_LOG="${CASE_LOG}"
    export SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}"
    export SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}"
    export SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}"
    export SIXBRIDGE_PREPARE_WAIT_IN_VERIFY=non-tty-stdin
    /usr/bin/script -q "${tty_worker_log}" "${CASE_PREPARE_SCRIPT}" 0.1.0
)
set -e
assert_exact_release_files "${CASE_ROOT}/.local/releases/v0.1.0"
assert_log_has_no_secret "${tty_worker_log}"

setup_case 'utf8-worker-한글경로'
utf8_worker_log="${FIXTURE_ROOT}/utf8-worker-nonascii.log"
if ! run_prepare /usr/bin/env LC_ALL=C SIXBRIDGE_PREPARE_WAIT_IN_VERIFY=utf8-nonascii \
    >"${utf8_worker_log}" 2>&1; then
    cat "${utf8_worker_log}" >&2
    fail '한글 경로의 JDK 17 worker가 UTF-8 locale로 release를 완료하지 못했습니다.'
fi
assert_exact_release_files "${CASE_ROOT}/.local/releases/v0.1.0"
assert_log_has_no_secret "${utf8_worker_log}"

setup_case runtime-node-resolution-mismatch
expect_failure runtime-node-resolution-mismatch 'sanitized PATH의 node가 검증된 canonical 도구와 다릅니다.' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_RUNTIME_MISMATCH=node
assert_no_final_directory "${CASE_ROOT}"

setup_case runtime-rg-resolution-mismatch
expect_failure runtime-rg-resolution-mismatch 'sanitized PATH의 rg가 검증된 canonical 도구와 다릅니다.' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_RUNTIME_MISMATCH=rg
assert_no_final_directory "${CASE_ROOT}"

setup_case missing-credential
expect_failure missing-credential 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' \
    /usr/bin/env -u SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
        PATH="${CASE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${CASE_PREPARE_SCRIPT}" 0.1.0
[[ ! -s "${CASE_LOG}" ]] || fail 'missing credential preflight 전에 runtime/build 도구가 실행되었습니다.'
[[ ! -e "${CASE_ROOT}/.local/gradle-home" ]] || fail 'missing credential preflight 전에 GRADLE_USER_HOME이 생성되었습니다.'
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

setup_case post-verify-work-rebind
expect_failure post-verify-work-rebind 'identity-bound cleanup' \
    run_prepare /usr/bin/env SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION=work-rebind
work_rebind_path=$(/bin/cat -- "${CASE_ROOT}/.local/releases/.fixture-work-rebind.path")
work_rebind_parked=$(/bin/cat -- "${CASE_ROOT}/.local/releases/.fixture-work-rebind.parked")
work_rebind_ancestor=$(/usr/bin/dirname -- "${work_rebind_path}")
[[ -f "${work_rebind_path}/sentinel" && -d "${work_rebind_parked}" &&
   -d "${work_rebind_ancestor}" ]] ||
    fail 'prepare cleanup이 work replacement, original 또는 ancestor evidence를 보존하지 않았습니다.'
assert_no_final_directory "${CASE_ROOT}"

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

setup_case native-publish-helper-in-place
helper_mutation_log="${FIXTURE_ROOT}/native-publish-helper-in-place.log"
set +e
run_prepare /usr/bin/env SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER=helper-in-place \
    >"${helper_mutation_log}" 2>&1
helper_mutation_status=$?
set -e
[[ "${helper_mutation_status}" -ne 0 ]] || fail 'publisher helper in-place mutation을 거부해야 합니다.'
[[ -e "${CASE_ROOT}/.local/releases/.fixture-native-publish-barrier-fired" ]] || \
    fail 'publisher helper in-place mutation barrier가 실행되지 않았습니다.'
[[ ! -e "${CASE_ROOT}/.local/releases/.fixture-helper-source-executed" ]] || \
    fail '검증된 fd bytes가 아닌 in-place 변조 publisher helper가 실행됐습니다.'
assert_contains "${helper_mutation_log}" 'atomic no-replace' 'publisher helper digest 거부 사유가 없습니다.'
assert_log_has_no_secret "${helper_mutation_log}"
assert_no_final_directory "${CASE_ROOT}"

setup_case perl-startup-injection
perl_startup_marker="${CASE_ROOT}/perl-startup-marker"
run_prepare /usr/bin/env PERL5LIB="${CASE_ROOT}/bin" PERL5OPT='-MFixtureLeak' \
    SIXBRIDGE_PERL_STARTUP_MARKER="${perl_startup_marker}" SIXBRIDGE_PERL_STARTUP_SECRET="${SECRET_PROBE}"
[[ ! -e "${perl_startup_marker}" ]] || fail 'worker launcher Perl이 inherited startup module을 실행했습니다.'

setup_case success-one
run_prepare /usr/bin/env \
    JAVA_HOME=/caller/poison-jdk GRADLE_USER_HOME=/caller/poison-gradle \
    JAVA_TOOL_OPTIONS="-Dfixture.secret=${SECRET_PROBE}" _JAVA_OPTIONS="-Dfixture.secret=${SECRET_PROBE}" \
    JDK_JAVA_OPTIONS="-Dfixture.secret=${SECRET_PROBE}" GRADLE_OPTS="-Dfixture.secret=${SECRET_PROBE}" \
    NODE_OPTIONS="--require=${CASE_ROOT}/bin/poison.js"
final_one="${CASE_ROOT}/.local/releases/v0.1.0"
commit_one=$(/usr/bin/git -C "${CASE_ROOT}" rev-parse HEAD)
assert_exact_release_files "${final_one}"
assert_call_count "${CASE_LOG}" verify
assert_call_count "${CASE_LOG}" ':android-manager:assembleRelease'
assert_call_count "${CASE_LOG}" '-Pcompose.desktop.packaging.checkJdkVendor=false :desktop-app:packageDmg'
assert_call_count "${CASE_LOG}" 'RUNTIME-JAVA verify'
assert_call_count "${CASE_LOG}" 'RUNTIME-NODE verify'
assert_call_count "${CASE_LOG}" 'RUNTIME-RG verify'
assert_call_count "${CASE_LOG}" 'RUNTIME-JAVA android'
assert_call_count "${CASE_LOG}" 'RUNTIME-NODE android'
assert_call_count "${CASE_LOG}" 'RUNTIME-RG android'
assert_call_count "${CASE_LOG}" 'RUNTIME-JAVA dmg'
assert_call_count "${CASE_LOG}" 'RUNTIME-NODE dmg'
assert_call_count "${CASE_LOG}" 'RUNTIME-RG dmg'
assert_not_contains "${CASE_LOG}" "${RELEASE_PASSWORD}" '호출 log에 실제 비밀번호가 기록되었습니다.'
if rg -n --hidden --glob '!*' "${SECRET_PROBE}|${RELEASE_PASSWORD}" "${final_one}" >/dev/null; then
    fail '정상 release directory에 secret probe 또는 비밀번호가 포함되었습니다.'
fi
fi

for signal in HUP INT TERM; do
    setup_case "signal-${signal}"
    signal_log="${FIXTURE_ROOT}/signal-${signal}.log"
    signal_ready="${CASE_ROOT}/ready"
    signal_continue="${CASE_ROOT}/continue"
    signal_child="${CASE_ROOT}/child.pid"
    if [[ "${signal}" = TERM ]]; then signal_wait_mode=dmg; else signal_wait_mode=1; fi
    /usr/bin/perl -e '$SIG{HUP} = "DEFAULT"; $SIG{INT} = "DEFAULT"; $SIG{TERM} = "DEFAULT"; exec @ARGV or die "exec failed\n";' \
        /usr/bin/env \
        PATH="${CASE_ROOT}/bin:${PATH}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${CASE_ROOT}/bin" \
        SIXBRIDGE_PREPARE_LOG="${CASE_LOG}" \
        SIXBRIDGE_PREPARE_SIGNER="${FIXTURE_SIGNER}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_PREPARE_WAIT_IN_VERIFY="${signal_wait_mode}" \
        SIXBRIDGE_PREPARE_READY="${signal_ready}" \
        SIXBRIDGE_PREPARE_CONTINUE="${signal_continue}" \
        SIXBRIDGE_PREPARE_CHILD_PID="${signal_child}" \
        SIXBRIDGE_PREPARE_TMPDIR_RECORD="${CASE_ROOT}/child-tmpdir" \
        "${CASE_PREPARE_SCRIPT}" 0.1.0 >"${signal_log}" 2>&1 &
    signal_pid=$!
    signal_ready_started=${SECONDS}
    for _ in $(seq 1 400); do
        [[ -e "${signal_ready}" ]] && break
        kill -0 "${signal_pid}" 2>/dev/null || break
        /bin/sleep 0.05
    done
    [[ -e "${signal_ready}" ]] || {
        /bin/cat -- "${signal_log}" >&2 || true
        kill -TERM "${signal_pid}" 2>/dev/null || true
        wait "${signal_pid}" 2>/dev/null || true
        fail "${signal} cleanup fixture가 verifier 대기 상태에 도달하지 못했습니다."
    }
    printf '%s\n' "prepare signal ${signal} ready=$((SECONDS - signal_ready_started))s"
    signal_exit_started=${SECONDS}
    kill -"${signal}" "${signal_pid}"
    for _ in $(seq 1 200); do kill -0 "${signal_pid}" 2>/dev/null || break; /bin/sleep 0.05; done
    if kill -0 "${signal_pid}" 2>/dev/null; then
        [[ -e "${signal_child}" ]] && kill -TERM $(cat "${signal_child}") 2>/dev/null || true
        wait "${signal_pid}" 2>/dev/null || true
        fail "${signal} signal 뒤 parent-only cleanup이 bounded 시간 안에 child를 종료하지 못했습니다."
    fi
    printf '%s\n' "prepare signal ${signal} exit=$((SECONDS - signal_exit_started))s"
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
    [[ "$(find /private/tmp -maxdepth 1 -name 'sixbridge-dmg.*' -print 2>/dev/null | LC_ALL=C sort)" = "${PRIVATE_TMP_DMG_ENTRIES_BEFORE}" ]] || fail "${signal} signal 뒤 새 /private/tmp DMG mountpoint가 남았습니다."
    if [[ "${signal}" = TERM ]]; then
        assert_call_count "${CASE_LOG}" 'HDIUTIL-DETACH snapshot-present=1'
        signal_dmg_source=$(/bin/cat -- "${CASE_LOG}.dmg-source")
        signal_dmg_mount=$(/bin/cat -- "${CASE_LOG}.dmg-mount")
        [[ ! -e "${signal_dmg_source}" && ! -e "${signal_dmg_mount}" ]] || fail 'TERM 뒤 DMG snapshot 또는 local mountpoint가 남았습니다.'
    fi
    assert_log_has_no_secret "${signal_log}"
done

if [[ "${SIXBRIDGE_PREPARE_TEST_SIGNAL_ONLY:-0}" = 1 ]]; then
    printf '%s\n' 'prepare signal cleanup 검증 통과'
    exit 0
fi

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
assert_not_contains "${CASE_LOG}" 'PATH-NODE' 'PATH node shim이 실행되었습니다.'
assert_not_contains "${CASE_LOG}" 'PATH-RG' 'PATH rg shim이 실행되었습니다.'

[[ "$(find /private/tmp -maxdepth 1 -name 'sixbridge-dmg.*' -print 2>/dev/null | LC_ALL=C sort)" = "${PRIVATE_TMP_DMG_ENTRIES_BEFORE}" ]] || \
    fail 'prepare fixture 뒤 새 /private/tmp DMG mountpoint가 남았습니다.'

printf 'prepare release 검증 통과\n'
