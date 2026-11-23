#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")/.." && pwd -P)"
readonly LIBRARY="${ROOT_DIR}/scripts/release-lib.sh"
readonly FIXTURE_ROOT="$(/usr/bin/mktemp -d "${ROOT_DIR}/.local/release-lib-test.XXXXXX")"
readonly SIGNER='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
readonly GIT_COMMIT='abcdef0123456789abcdef0123456789abcdef01'
readonly DMG_MOUNT_MANIFEST="${FIXTURE_ROOT}/dmg-mounts.manifest"
readonly DMG_MOUNT_NONCE="$(LC_ALL=C /usr/bin/uuidgen | /usr/bin/tr '[:upper:]' '[:lower:]')"
readonly DMG_HDIUTIL_STATE_ROOT="${FIXTURE_ROOT}/dmg-hdiutil-state"
: >"${DMG_MOUNT_MANIFEST}"

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

cleanup() {
    cleanup_fake_dmg_manifest "${DMG_MOUNT_MANIFEST}"
    /bin/rm -rf "${FIXTURE_ROOT}"
}
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
    /bin/mkdir -p "${FIXTURE_ROOT}/bin" "${DMG_HDIUTIL_STATE_ROOT}"
    /bin/chmod 700 "${DMG_HDIUTIL_STATE_ROOT}"
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
set -euo pipefail
state_root=${FAKE_HDIUTIL_STATE_ROOT:?}
state_source="${state_root}/source"
state_mountpoint="${state_root}/mountpoint"
state_device="${state_root}/device"

write_inventory() {
  local plist image_path image_count=1 index mode=${FAKE_HDIUTIL_INFO_MODE:-normal}
  if [[ "${FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH:-0}" = 1 && ! -f "${state_source}" ]]; then
    mode=normal
  fi
  [[ "${mode}" != fail ]] || {
    printf 'fake hdiutil info failure\n' >&2
    return 41
  }
  [[ "${mode}" != malformed ]] || {
    printf 'not a plist\n'
    return 0
  }
  plist=$(/usr/bin/mktemp "${state_root}/info.XXXXXX.plist")
  /usr/bin/plutil -create xml1 "${plist}"
  /usr/bin/plutil -insert images -array "${plist}"
  if [[ -f "${state_source}" ]]; then
    image_path=$(/bin/cat -- "${state_source}")
    [[ -z "${FAKE_HDIUTIL_IMAGE_PATH_OVERRIDE:-}" ]] || image_path=${FAKE_HDIUTIL_IMAGE_PATH_OVERRIDE}
    [[ "${mode}" != duplicate ]] || image_count=2
    index=0
    while [[ "${index}" -lt "${image_count}" ]]; do
      /usr/bin/plutil -insert "images.${index}" -dictionary "${plist}"
      /usr/bin/plutil -insert "images.${index}.image-path" -string "${image_path}" "${plist}"
      /usr/bin/plutil -insert "images.${index}.system-entities" -array "${plist}"
      /usr/bin/plutil -insert "images.${index}.system-entities.0" -dictionary "${plist}"
      if [[ "${FAKE_HDIUTIL_DEVICE_ONLY_ATTACH:-0}" != 1 ]]; then
        if [[ "${mode}" = wrong-mount ]]; then
          /usr/bin/plutil -insert "images.${index}.system-entities.0.mount-point" -string \
            '/private/tmp/sixbridge-dmg.wrong' "${plist}"
        else
          /usr/bin/plutil -insert "images.${index}.system-entities.0.mount-point" -string \
            "$(/bin/cat -- "${state_mountpoint}")" "${plist}"
        fi
      fi
      if [[ "${mode}" != missing-dev ]]; then
        if [[ "${mode}" = invalid-dev ]]; then
          /usr/bin/plutil -insert "images.${index}.system-entities.0.dev-entry" -string '/dev/not-a-disk' "${plist}"
        else
          /usr/bin/plutil -insert "images.${index}.system-entities.0.dev-entry" -string "$(/bin/cat -- "${state_device}")" "${plist}"
        fi
      fi
      index=$((index + 1))
    done
  fi
  /bin/cat -- "${plist}"
  /bin/rm -f -- "${plist}"
}

case "$1" in
 info)
   [[ "${2:-}" = -plist ]] || exit 40
   if [[ -n "${FAKE_HDIUTIL_INFO_COUNTER:-}" ]]; then
     info_count=0
     [[ ! -s "${FAKE_HDIUTIL_INFO_COUNTER}" ]] || info_count=$(/bin/cat -- "${FAKE_HDIUTIL_INFO_COUNTER}")
     info_count=$((info_count + 1))
     printf '%s\n' "${info_count}" >"${FAKE_HDIUTIL_INFO_COUNTER}"
     if [[ -n "${FAKE_HDIUTIL_INFO_FAIL_AT:-}" &&
           "${info_count}" = "${FAKE_HDIUTIL_INFO_FAIL_AT}" ]]; then
       printf 'fake exact inventory query failure\n' >&2
       exit 43
     fi
   fi
   write_inventory
   ;;
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
   [[ -z "${FAKE_HDIUTIL_MOUNT_LOG:-}" ]] || printf '%s\n' "${mountpoint}" >>"${FAKE_HDIUTIL_MOUNT_LOG}"
   if [[ "${FAKE_HDIUTIL_REQUIRE_LOCAL_MOUNT:-0}" = 1 ]]; then
     case "${mountpoint}" in /private/tmp/sixbridge-dmg.*) ;; *) printf 'fake external mountpoint rejected\n' >&2; exit 32;; esac
     [[ "$(/usr/bin/stat -f '%u:%Lp' "${mountpoint}")" = "$(/usr/bin/id -u):700" ]] || exit 33
   fi
   if [[ "${FAKE_HDIUTIL_ATTACH_FAIL:-0}" = 1 ]]; then
     printf 'fake attach raw failure\n' >&2
     exit 34
   fi
   if [[ -n "${FAKE_HDIUTIL_SOURCE_RECORD:-}" ]]; then
     printf '%s\n' "${source}" >"${FAKE_HDIUTIL_SOURCE_RECORD}"
   fi
   if [[ "${FAKE_HDIUTIL_DEVICE_ONLY_ATTACH:-0}" = 1 ]]; then
     printf '%s' "${source}" >"${state_source}"
     printf '%s' "${mountpoint}" >"${state_mountpoint}"
     printf '%s' '/dev/disk71' >"${state_device}"
     exit 31
   fi
   record_fake_dmg_object() {
     candidate=$1
     [[ -n "${FAKE_HDIUTIL_MOUNT_MANIFEST:-}" && -n "${FAKE_HDIUTIL_MOUNT_NONCE:-}" ]] || return 0
     case "${FAKE_HDIUTIL_MOUNT_NONCE}" in *[!0-9a-f-]*) exit 37 ;; esac
     marker="${candidate}/.sixbridge-fixture-${FAKE_HDIUTIL_MOUNT_NONCE}"
     (umask 077; printf '%s' "${FAKE_HDIUTIL_MOUNT_NONCE}" >"${marker}")
     /bin/chmod 600 "${marker}"
     identity=$(/usr/bin/stat -f '%d:%i' "${candidate}")
     printf '%s\t%s\t%s\n' "${candidate}" "${identity}" "${FAKE_HDIUTIL_MOUNT_NONCE}" \
       >>"${FAKE_HDIUTIL_MOUNT_MANIFEST}"
   }
   record_fake_dmg_object "${mountpoint}"
   /bin/mv "${mountpoint}" "${mountpoint}.underlay"
   record_fake_dmg_object "${mountpoint}.underlay"
   /bin/mkdir -m 700 "${mountpoint}"
   record_fake_dmg_object "${mountpoint}"
   case "${FAKE_DMG_APPLICATIONS_MODE:-normal}" in
     normal) /bin/ln -s /Applications "${mountpoint}/Applications" ;;
     missing) ;;
     wrong-target) /bin/ln -s /private/tmp "${mountpoint}/Applications" ;;
     regular) printf 'not a link\n' >"${mountpoint}/Applications" ;;
     *) exit 46 ;;
   esac
   app_bundle="${mountpoint}/SIX Bridge.app"
   /bin/mkdir -p "${app_bundle}/Contents/app" "${app_bundle}/Contents/Resources"
   : >"${app_bundle}/Contents/Info.plist"
   case "${FAKE_DMG_ICON_MODE:-normal}" in
     normal) /bin/cp -- "${FAKE_DMG_ICON_SOURCE:?}" "${app_bundle}/Contents/Resources/SIX Bridge.icns" ;;
     missing) ;;
     wrong-content) printf 'wrong icon\n' >"${app_bundle}/Contents/Resources/SIX Bridge.icns" ;;
     *) exit 47 ;;
   esac
   app_properties_root=$(/usr/bin/mktemp -d "${state_root}/app-properties.XXXXXX")
   printf 'name=SIX Bridge\napplicationId=dev.sixbridge.desktop\nversion=%s\nminimumMacOs=13.0\n' \
     "${FAKE_APP_PROPERTIES_VERSION:-0.1.3}" >"${app_properties_root}/app.properties"
   app_jar="${app_bundle}/Contents/app/desktop-app-0.1.3-SNAPSHOT-${FAKE_APP_JAR_SUFFIX:-fixture}.jar"
   if [[ -n "${FAKE_APP_PROPERTIES_ARCHIVE_MODE:-}" ]]; then
     /usr/bin/perl -MIO::Compress::Zip -e '
       use strict;
       use warnings;
       my ($output, $properties, $mode) = @ARGV;
       open(my $source, q{<}, $properties) or die $!;
       binmode($source);
       local $/;
       my $canonical = <$source>;
       close($source) or die $!;
       my $first_name = $mode eq q{missing} ? q{placeholder} : q{app.properties};
       my $zip = IO::Compress::Zip->new($output, Name => $first_name)
         or die $IO::Compress::Zip::ZipError;
       if ($mode eq q{oversize}) {
         $zip->print($canonical . (q{x} x 4097));
       } else {
         $zip->print($canonical);
       }
       if ($mode eq q{duplicate}) {
         $zip->newStream(Name => q{app.properties})
           or die $IO::Compress::Zip::ZipError;
         $zip->print($canonical);
       } elsif ($mode eq q{nul}) {
         $zip->newStream(Name => "unsafe\0name")
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{double-slash}) {
         $zip->newStream(Name => q{unsafe//name})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{dot-slash}) {
         $zip->newStream(Name => q{./unsafe})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{drive-prefix}) {
         $zip->newStream(Name => q{C:/unsafe})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{absolute}) {
         $zip->newStream(Name => q{/unsafe})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{parent}) {
         $zip->newStream(Name => q{../unsafe})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{backslash}) {
         $zip->newStream(Name => q{unsafe\name})
           or die $IO::Compress::Zip::ZipError;
         $zip->print(q{unsafe});
       } elsif ($mode eq q{non-target-bomb}) {
         $zip->newStream(Name => q{oversized-resource})
           or die $IO::Compress::Zip::ZipError;
         my $remaining = 16 * 1024 * 1024 + 1;
         my $block = q{x} x 65536;
         while ($remaining > 0) {
           my $size = $remaining > length($block) ? length($block) : $remaining;
           $zip->print(substr($block, 0, $size));
           $remaining -= $size;
         }
       }
       $zip->close() or die $IO::Compress::Zip::ZipError;
     ' "${app_jar}" "${app_properties_root}/app.properties" \
       "${FAKE_APP_PROPERTIES_ARCHIVE_MODE}"
   else
     (
       CDPATH= builtin cd -- "${app_properties_root}"
       /usr/bin/zip -q -X "${app_jar}" app.properties
     )
   fi
   if [[ "${FAKE_EXTRA_MATCHING_APP_JAR_SYMLINK:-0}" = 1 ]]; then
     /bin/ln -s -- "$('/usr/bin/basename' "${app_jar}")" \
       "${app_bundle}/Contents/app/desktop-app-0.1.3-SNAPSHOT-symlink.jar"
   fi
   app_jar_basename=$(/usr/bin/basename -- "${app_jar}")
   cfg_jar_basename=${FAKE_APP_CFG_JAR_BASENAME:-${app_jar_basename}}
   case "${FAKE_APP_CFG_MODE:-normal}" in
     normal)
       printf '[Application]\napp.classpath=$APPDIR/%s\napp.mainclass=dev.sixbridge.desktop.MainKt\n' \
         "${cfg_jar_basename}" >"${app_bundle}/Contents/app/SIX Bridge.cfg"
       ;;
     wrong-section)
       printf '[Application]\napp.classpath=$APPDIR/dependency.jar\napp.mainclass=dev.sixbridge.desktop.MainKt\n[JavaOptions]\napp.classpath=$APPDIR/%s\n' \
         "${cfg_jar_basename}" >"${app_bundle}/Contents/app/SIX Bridge.cfg"
       ;;
     evil-first)
       printf '[Application]\napp.classpath=$APPDIR/evil.jar\napp.classpath=$APPDIR/%s\napp.mainclass=dev.sixbridge.desktop.MainKt\n' \
         "${cfg_jar_basename}" >"${app_bundle}/Contents/app/SIX Bridge.cfg"
       ;;
     wrong-mainclass)
       printf '[Application]\napp.classpath=$APPDIR/%s\napp.mainclass=dev.sixbridge.desktop.EvilKt\n' \
         "${cfg_jar_basename}" >"${app_bundle}/Contents/app/SIX Bridge.cfg"
       ;;
     *) exit 45 ;;
   esac
   /bin/rm -rf -- "${app_properties_root}"
   [[ "${FAKE_EXTRA_APP:-0}" != 1 ]] || /bin/mkdir -p "${mountpoint}/Injected.app/Contents"
   printf '%s' "${source}" >"${state_source}"
   printf '%s' "${mountpoint}" >"${state_mountpoint}"
   printf '%s' '/dev/disk71' >"${state_device}"
   [[ "${FAKE_HDIUTIL_PARTIAL_ATTACH:-0}" != 1 ]] || exit 31
   if [[ -n "${FAKE_HDIUTIL_READY:-}" ]]; then
     [[ -z "${FAKE_HDIUTIL_CHILD_RECORD:-}" ]] || printf '%s\n' "$$" >"${FAKE_HDIUTIL_CHILD_RECORD}"
     : >"${FAKE_HDIUTIL_READY}"
     while [[ ! -e "${FAKE_HDIUTIL_CONTINUE}" ]]; do /bin/sleep 0.05; done
   fi
   ;;
 detach)
   [[ -z "${FAKE_HDIUTIL_DETACH_LOG:-}" ]] || printf 'detach %s\n' "${2:-}" >>"${FAKE_HDIUTIL_DETACH_LOG}"
   if [[ -n "${FAKE_HDIUTIL_SOURCE_RECORD:-}" ]]; then
     source=$(/bin/cat -- "${FAKE_HDIUTIL_SOURCE_RECORD}")
     [[ -f "${source}" ]] || exit 35
   fi
   [[ "${FAKE_HDIUTIL_DETACH_FAIL:-0}" != 1 ]] || exit 36
   [[ -f "${state_device}" ]] || exit 38
   [[ "${2:-}" = "$(/bin/cat -- "${state_device}")" ]] || exit 39
   mountpoint=$(/bin/cat -- "${state_mountpoint}")
   if [[ -d "${mountpoint}.underlay" && ! -L "${mountpoint}.underlay" ]]; then
     /bin/rm -rf "${mountpoint}"
     [[ -z "${FAKE_HDIUTIL_MOUNT_NONCE:-}" ]] || \
       /bin/rm -f -- "${mountpoint}.underlay/.sixbridge-fixture-${FAKE_HDIUTIL_MOUNT_NONCE}"
     /bin/mv "${mountpoint}.underlay" "${mountpoint}"
   else
     /usr/bin/find "${mountpoint}" -mindepth 1 -maxdepth 1 -exec /bin/rm -rf {} +
   fi
   [[ "${FAKE_HDIUTIL_DETACH_STALE:-0}" = 1 ]] || \
     /bin/rm -f -- "${state_source}" "${state_mountpoint}" "${state_device}"
   ;;
 *) exit 22;;
esac
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/plutil" <<'EOF'
#!/usr/bin/env bash
if [[ "${FAKE_PLUTIL_FAIL:-0}" = 1 ]]; then
  if [[ "${FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH:-0}" != 1 ||
        -f "${FAKE_HDIUTIL_STATE_ROOT}/source" ]]; then
    exit 42
  fi
fi
exec /usr/bin/plutil "$@"
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/PlistBuddy" <<'EOF'
#!/usr/bin/env bash
case "$2" in 'Print :CFBundleIdentifier') printf '%s\n' "${FAKE_BUNDLE_ID}";; 'Print :CFBundleShortVersionString') printf '%s\n' "${FAKE_PRODUCT_VERSION}";; 'Print :CFBundleVersion') printf '%s\n' "${FAKE_NATIVE_VERSION}";; 'Print :LSMinimumSystemVersion') printf '%s\n' "${FAKE_MINIMUM_VERSION}";; 'Print :CFBundleIconFile') printf '%s\n' "${FAKE_ICON_NAME}";; *) exit 23;; esac
EOF
    /bin/cat >"${FIXTURE_ROOT}/bin/codesign" <<'EOF'
#!/usr/bin/env bash
target=${!#}
case "${target}" in
  */.sixbridge-release-snapshot.*/artifact)
    case "${FAKE_CODESIGN_DMG_CONTAINER:-unsigned}" in
      unsigned) printf '%s: code object is not signed at all\n' "${target}" >&2; exit 1 ;;
      unsigned-extra) printf '%s: code object is not signed at all\nextra diagnostic\n' "${target}" >&2; exit 1 ;;
      unsigned-wrong-path) printf '%s: code object is not signed at all\n' '/private/tmp/wrong.dmg' >&2; exit 1 ;;
      unsigned-exit-zero) printf '%s: code object is not signed at all\n' "${target}" >&2; exit 0 ;;
      adhoc) printf '%s\n' 'CodeDirectory v=20500 flags=0x2(adhoc)' 'Signature=adhoc' 'TeamIdentifier=not set' >&2; exit 0 ;;
      developer-id) printf '%s\n' 'Authority=Developer ID Application: Fixture (TEAMID)' 'TeamIdentifier=TEAMID' >&2; exit 0 ;;
      *) printf '%s\n' 'unexpected container signature fixture' >&2; exit 2 ;;
    esac
    ;;
esac
case "${FAKE_CODESIGN_APP_RESULT:-adhoc}" in
  unsigned) printf '%s: code object is not signed at all\n' "${target}" >&2; exit 1 ;;
  unsigned-extra) printf '%s: code object is not signed at all\nextra diagnostic\n' "${target}" >&2; exit 1 ;;
  unsigned-wrong-path) printf '%s: code object is not signed at all\n' '/private/tmp/Wrong.app' >&2; exit 1 ;;
  unsigned-exit-zero) printf '%s: code object is not signed at all\n' "${target}" >&2; exit 0 ;;
  developer-id) printf '%s\n' 'Authority=Developer ID Application: Fixture (TEAMID)' 'TeamIdentifier=TEAMID' >&2; exit 0 ;;
  adhoc) printf '%s\n' 'CodeDirectory v=20500 flags=0x10002(adhoc,runtime)' 'Signature=adhoc' 'TeamIdentifier=not set' >&2; exit 0 ;;
  *) printf '%s\n' 'unexpected app signature fixture' >&2; exit 2 ;;
esac
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
export SIXBRIDGE_PLUTIL="${FIXTURE_ROOT}/bin/plutil"
export SIXBRIDGE_PLISTBUDDY="${FIXTURE_ROOT}/bin/PlistBuddy"
export SIXBRIDGE_CODESIGN="${FIXTURE_ROOT}/bin/codesign"
export SIXBRIDGE_SHASUM="${FIXTURE_ROOT}/bin/shasum"
export FAKE_HDIUTIL_MOUNT_MANIFEST="${DMG_MOUNT_MANIFEST}"
export FAKE_HDIUTIL_MOUNT_NONCE="${DMG_MOUNT_NONCE}"
export FAKE_HDIUTIL_STATE_ROOT="${DMG_HDIUTIL_STATE_ROOT}"
export FAKE_SIGNER="${SIGNER}"
export FAKE_APPLICATION_ID='dev.sixbridge.manager'
export FAKE_VERSION_NAME='0.1.3'
export FAKE_VERSION_CODE='4'
export FAKE_BUNDLE_ID='dev.sixbridge.desktop'
export FAKE_PRODUCT_VERSION='1.0.3'
export FAKE_NATIVE_VERSION='1.0.3'
export FAKE_MINIMUM_VERSION='13.0'
export FAKE_ICON_NAME='SIX Bridge.icns'
export FAKE_DMG_ICON_SOURCE="${ROOT_DIR}/desktop-app/src/main/package-resources/six-bridge.icns"
export FAKE_APP_PROPERTIES_VERSION='0.1.3'
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
        plutil) printf '%s\n' "${SIXBRIDGE_PLUTIL}" ;;
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
release_create_verification_snapshot "${apk}" "${container}"
snapshot_check_dir=${SIXBRIDGE_VERIFICATION_SNAPSHOT_DIR}
snapshot_check_dir_identity=$(release_owned_directory_identity "${snapshot_check_dir}")
snapshot_check="${snapshot_check_dir}/artifact"
[[ "$(release_link_count "${apk}")" = 1 && "$(release_link_count "${snapshot_check}")" = 1 ]] || fail 'verification snapshot이 독립 regular file이 아닙니다.'
printf 'mutated' >"${apk}"
[[ "$(/bin/cat -- "${snapshot_check}")" = apk ]] || fail 'verification snapshot이 원본 쓰기의 영향을 받습니다.'
make_file "${apk}" apk
release_remove_verification_snapshot "${snapshot_check_dir}" "${snapshot_check_dir_identity}"

release_create_verification_snapshot "${apk}" "${container}"
snapshot_rebind_dir=${SIXBRIDGE_VERIFICATION_SNAPSHOT_DIR}
snapshot_rebind_identity=$(release_container_identity "${snapshot_rebind_dir}")
snapshot_rebind_parked="${snapshot_rebind_dir}.parked"
/bin/mv -- "${snapshot_rebind_dir}" "${snapshot_rebind_parked}"
/bin/mkdir -m 700 -- "${snapshot_rebind_dir}"
printf 'preserve\n' >"${snapshot_rebind_dir}/sentinel"
snapshot_rebind_status=0
release_remove_verification_snapshot "${snapshot_rebind_dir}" "${snapshot_rebind_identity}" || \
  snapshot_rebind_status=$?
snapshot_rebind_preserved=0
[[ -f "${snapshot_rebind_dir}/sentinel" && -d "${snapshot_rebind_parked}" ]] && \
  snapshot_rebind_preserved=1
/bin/rm -rf -- "${snapshot_rebind_dir}" "${snapshot_rebind_parked}"
[[ "${snapshot_rebind_status}" -ne 0 ]] || \
  fail 'verification snapshot same-path replacement cleanup이 성공했습니다.'
[[ "${snapshot_rebind_preserved}" = 1 ]] || \
  fail 'verification snapshot cleanup이 unrelated replacement를 삭제했습니다.'
if [[ "${SIXBRIDGE_RELEASE_TEST_ATOMIC_REMOVE_ONLY:-}" = 1 ]]; then
  printf 'release-lib atomic remove RED/GREEN 검증 통과\n'
  exit 0
fi

release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"
export FAKE_APKSIGNER_FAIL=1; expect_failure apk-signature 'APK signature verification' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; unset FAKE_APKSIGNER_FAIL
export FAKE_APPLICATION_ID='invalid.package'; expect_failure apk-application-id 'application ID' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_APPLICATION_ID='dev.sixbridge.manager'
export FAKE_VERSION_NAME='0.1.1'; expect_failure apk-old-version-name 'versionName' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_VERSION_NAME='0.1.3'
export FAKE_SIGNER='ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'; expect_failure apk-signer 'signer SHA-256' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_SIGNER="${SIGNER}"
export FAKE_EXTRA_SIGNER=1; expect_failure apk-extra-signer '정확히 하나' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; unset FAKE_EXTRA_SIGNER
export FAKE_VERSION_CODE=3; expect_failure apk-old-version-code 'versionCode' release_verify_apk "${apk}" "${SIGNER}" "${stage}" "${container}"; export FAKE_VERSION_CODE=4
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
for applications_mode in missing wrong-target regular; do
  export FAKE_DMG_APPLICATIONS_MODE=${applications_mode}
  expect_failure "dmg-applications-${applications_mode}" 'Applications 설치 링크' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
done
unset FAKE_DMG_APPLICATIONS_MODE
export FAKE_ICON_NAME='Default.icns'
expect_failure dmg-icon-name 'DMG app icon' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
export FAKE_ICON_NAME='SIX Bridge.icns'
for icon_mode in missing wrong-content; do
  export FAKE_DMG_ICON_MODE=${icon_mode}
  expect_failure "dmg-icon-${icon_mode}" 'DMG app icon' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
done
unset FAKE_DMG_ICON_MODE
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
if [[ "${SIXBRIDGE_RELEASE_TEST_DMG_INSTALL_ONLY:-0}" = 1 ]]; then
  printf 'release-lib DMG 설치 UX RED/GREEN 검증 통과\n'
  exit 0
fi
verify_app_properties_contract() {
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  export FAKE_APP_PROPERTIES_VERSION='9.9.9'
  expect_failure dmg-app-properties-version 'app.properties version' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  export FAKE_APP_PROPERTIES_VERSION='0.1.3'
  for archive_mode in missing duplicate oversize non-target-bomb nul double-slash dot-slash drive-prefix absolute parent backslash; do
    export FAKE_APP_PROPERTIES_ARCHIVE_MODE=${archive_mode}
    expect_failure "dmg-app-properties-${archive_mode}" 'app.properties version' \
      release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  done
  unset FAKE_APP_PROPERTIES_ARCHIVE_MODE
  export FAKE_EXTRA_MATCHING_APP_JAR_SYMLINK=1
  expect_failure dmg-app-properties-matching-symlink 'desktop app JAR' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  unset FAKE_EXTRA_MATCHING_APP_JAR_SYMLINK
  export FAKE_APP_CFG_JAR_BASENAME='desktop-app-0.1.3-SNAPSHOT-decoy.jar'
  expect_failure dmg-app-properties-launcher-mismatch 'launcher classpath' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  unset FAKE_APP_CFG_JAR_BASENAME
  for cfg_mode in wrong-section evil-first wrong-mainclass; do
    export FAKE_APP_CFG_MODE=${cfg_mode}
    expect_failure "dmg-app-properties-cfg-${cfg_mode}" 'launcher classpath' \
      release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  done
  unset FAKE_APP_CFG_MODE
  export FAKE_APP_JAR_SUFFIX='한글경로'
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  unset FAKE_APP_JAR_SUFFIX
  printf 'release-lib DMG app.properties RED/GREEN 검증 통과\n'
}
verify_app_properties_contract
if [[ "${SIXBRIDGE_RELEASE_TEST_APP_PROPERTIES_ONLY:-0}" = 1 ]]; then
  exit 0
fi
device_only_detach_log="${FIXTURE_ROOT}/device-only.detach"; : >"${device_only_detach_log}"
export FAKE_HDIUTIL_DEVICE_ONLY_ATTACH=1 FAKE_HDIUTIL_DETACH_LOG="${device_only_detach_log}"
expect_failure dmg-device-only-partial 'DMG read-only mount' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
[[ "$(/bin/cat -- "${device_only_detach_log}")" = 'detach /dev/disk71' ]] || \
  fail 'device-only partial attach를 exact device로 detach하지 않았습니다.'
[[ ! -e "${DMG_HDIUTIL_STATE_ROOT}/source" &&
   ! -e "${DMG_HDIUTIL_STATE_ROOT}/mountpoint" &&
   ! -e "${DMG_HDIUTIL_STATE_ROOT}/device" ]] || \
  fail 'device-only partial attach inventory가 cleanup 뒤 남았습니다.'
unset FAKE_HDIUTIL_DEVICE_ONLY_ATTACH FAKE_HDIUTIL_DETACH_LOG

for inventory_fault in fail malformed duplicate missing-dev invalid-dev plutil-fail; do
  inventory_detach_log="${FIXTURE_ROOT}/inventory-${inventory_fault}.detach"; : >"${inventory_detach_log}"
  inventory_source_record="${FIXTURE_ROOT}/inventory-${inventory_fault}.source"
  inventory_mount_log="${FIXTURE_ROOT}/inventory-${inventory_fault}.mount"
  export FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH=1 \
    FAKE_HDIUTIL_DETACH_LOG="${inventory_detach_log}" \
    FAKE_HDIUTIL_SOURCE_RECORD="${inventory_source_record}" \
    FAKE_HDIUTIL_MOUNT_LOG="${inventory_mount_log}"
  if [[ "${inventory_fault}" = plutil-fail ]]; then
    export FAKE_PLUTIL_FAIL=1
  else
    export FAKE_HDIUTIL_INFO_MODE="${inventory_fault}"
  fi
  expect_failure "dmg-inventory-${inventory_fault}" 'device inventory' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
  release_dmg_has_active_state || fail "${inventory_fault} inventory UNKNOWN 뒤 active evidence가 사라졌습니다."
  [[ ! -s "${inventory_detach_log}" ]] || fail "${inventory_fault} inventory UNKNOWN에서 detach를 실행했습니다."
  inventory_snapshot=$(/bin/cat -- "${inventory_source_record}")
  inventory_mountpoint=$(/usr/bin/tail -n 1 "${inventory_mount_log}")
  [[ -f "${inventory_snapshot}" && -d "${inventory_mountpoint}" ]] || \
    fail "${inventory_fault} inventory UNKNOWN 뒤 snapshot 또는 mountpoint가 보존되지 않았습니다."
  unset FAKE_HDIUTIL_INFO_MODE FAKE_PLUTIL_FAIL FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH
  release_dmg_cleanup_active
  [[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${inventory_detach_log}")" = 1 ]] || \
    fail "${inventory_fault} fault 해제 뒤 exact device detach가 한 번이 아닙니다."
  [[ ! -e "${inventory_snapshot}" && ! -e "${inventory_mountpoint}" ]] || \
    fail "${inventory_fault} fault 해제 cleanup 뒤 evidence가 남았습니다."
  release_dmg_has_active_state && fail "${inventory_fault} fault 해제 cleanup 뒤 active state가 남았습니다."
  unset FAKE_HDIUTIL_DETACH_LOG FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG
done

wrong_mount_detach_log="${FIXTURE_ROOT}/inventory-wrong-mount.detach"; : >"${wrong_mount_detach_log}"
export FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH=1 FAKE_HDIUTIL_INFO_MODE=wrong-mount \
  FAKE_HDIUTIL_DETACH_LOG="${wrong_mount_detach_log}"
expect_failure dmg-inventory-wrong-mount 'mountpoint' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
[[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${wrong_mount_detach_log}")" = 1 ]] || \
  fail 'attach 성공 inventory의 wrong mountpoint 뒤 exact device cleanup이 실행되지 않았습니다.'
release_dmg_has_active_state && fail 'wrong mountpoint 실패 뒤 active state가 남았습니다.'
unset FAKE_HDIUTIL_INFO_FAULT_AFTER_ATTACH FAKE_HDIUTIL_INFO_MODE FAKE_HDIUTIL_DETACH_LOG

retry_info_counter="${FIXTURE_ROOT}/dmg-second-inventory.counter"; : >"${retry_info_counter}"
retry_detach_log="${FIXTURE_ROOT}/dmg-second-inventory.detach"; : >"${retry_detach_log}"
retry_source_record="${FIXTURE_ROOT}/dmg-second-inventory.source"
retry_mount_log="${FIXTURE_ROOT}/dmg-second-inventory.mount"
export FAKE_HDIUTIL_INFO_COUNTER="${retry_info_counter}" FAKE_HDIUTIL_INFO_FAIL_AT=4 \
  FAKE_HDIUTIL_DETACH_LOG="${retry_detach_log}" \
  FAKE_HDIUTIL_SOURCE_RECORD="${retry_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${retry_mount_log}"
expect_failure dmg-second-inventory-fail 'device inventory' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
release_dmg_has_active_state || fail '두 번째 detach 직전 inventory 실패 뒤 active evidence가 사라졌습니다.'
[[ "${SIXBRIDGE_ACTIVE_DMG_CLEANING}" = 0 ]] || \
  fail '두 번째 detach 직전 inventory 실패 뒤 cleanup guard가 해제되지 않았습니다.'
[[ "$(trap -p HUP)" = *release_dmg_signal_exit* &&
   "$(trap -p INT)" = *release_dmg_signal_exit* &&
   "$(trap -p TERM)" = *release_dmg_signal_exit* ]] || \
  fail '두 번째 detach 직전 inventory 실패 뒤 DMG signal trap이 재설치되지 않았습니다.'
retry_snapshot=$(/bin/cat -- "${retry_source_record}")
retry_mountpoint=$(/usr/bin/tail -n 1 "${retry_mount_log}")
[[ -f "${retry_snapshot}" && -d "${retry_mountpoint}" ]] || \
  fail '두 번째 detach 직전 inventory 실패 뒤 snapshot 또는 mountpoint가 보존되지 않았습니다.'
[[ ! -s "${retry_detach_log}" ]] || \
  fail '두 번째 detach 직전 inventory 실패에서 detach를 실행했습니다.'
unset FAKE_HDIUTIL_INFO_FAIL_AT
release_dmg_cleanup_active
[[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${retry_detach_log}")" = 1 ]] || \
  fail 'inventory fault 해제 뒤 exact detach가 한 번이 아닙니다.'
[[ ! -e "${retry_snapshot}" && ! -L "${retry_snapshot}" &&
   ! -e "${retry_mountpoint}" && ! -L "${retry_mountpoint}" ]] || \
  fail 'inventory fault 해제 cleanup 뒤 evidence가 남았습니다.'
release_dmg_has_active_state && fail 'inventory fault 해제 cleanup 뒤 active state가 남았습니다.'
unset FAKE_HDIUTIL_INFO_COUNTER FAKE_HDIUTIL_DETACH_LOG \
  FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG

preclaim_snapshot_dir="${FIXTURE_ROOT}/dmg-preclaim-snapshot"
/bin/mkdir -m 700 "${preclaim_snapshot_dir}"
preclaim_snapshot="${preclaim_snapshot_dir}/artifact"
printf 'preclaim snapshot' >"${preclaim_snapshot}"
/bin/chmod 600 "${preclaim_snapshot}"
preclaim_snapshot_identity=$(release_file_identity "${preclaim_snapshot}")
preclaim_snapshot_dir_identity=$(release_owned_directory_identity "${preclaim_snapshot_dir}")
preclaim_device=$(release_stat_value '%d' "${preclaim_snapshot_dir}")
preclaim_inode=$(release_stat_value '%i' "${preclaim_snapshot_dir}")
preclaim_candidate="/private/tmp/sixbridge-dmg.${preclaim_device}.${preclaim_inode}"
[[ ! -e "${preclaim_candidate}" && ! -L "${preclaim_candidate}" ]] || \
  fail 'DMG preclaim candidate가 fixture 시작 전에 이미 존재합니다.'
/bin/mkdir -m 700 "${preclaim_candidate}"
printf 'preserve' >"${preclaim_candidate}/sentinel"
release_dmg_register_pending \
  "${preclaim_snapshot_dir}" "${preclaim_snapshot}" "${preclaim_snapshot_identity}" \
  "${preclaim_snapshot_dir_identity}"
set +e
release_create_dmg_mountpoint /private/tmp \
  >"${FIXTURE_ROOT}/dmg-preclaim.log" 2>&1
preclaim_status=$?
set -e
preclaim_active_mountpoint=${SIXBRIDGE_ACTIVE_DMG_MOUNTPOINT:-}
preclaim_active=0
release_dmg_has_active_state && preclaim_active=1
if [[ "${preclaim_status}" = 0 ]]; then
  release_dmg_cleanup_active || true
fi
preclaim_preserved=0
[[ -f "${preclaim_candidate}/sentinel" ]] && preclaim_preserved=1
/bin/rm -f -- "${preclaim_candidate}/sentinel"
/bin/rmdir -- "${preclaim_candidate}"
if release_dmg_has_active_state; then
  release_dmg_cleanup_active
fi
[[ "${preclaim_status}" -ne 0 ]] || \
  fail 'DMG preclaim collision이 random fallback mountpoint로 우회되었습니다.'
[[ "${preclaim_active_mountpoint}" = "${preclaim_candidate}" && "${preclaim_active}" = 1 ]] || \
  fail 'DMG preclaim collision 뒤 exact candidate active evidence가 보존되지 않았습니다.'
[[ "${preclaim_preserved}" = 1 ]] || \
  fail 'DMG preclaim collision cleanup이 기존 candidate를 삭제했습니다.'
[[ ! -e "${preclaim_snapshot_dir}" && ! -L "${preclaim_snapshot_dir}" ]] || \
  fail 'DMG preclaim collision 복구 cleanup 뒤 snapshot residue가 남았습니다.'
release_dmg_has_active_state && \
  fail 'DMG preclaim collision 복구 cleanup 뒤 active state가 남았습니다.'

record_failure_mount="${FIXTURE_ROOT}/dmg-record-write-failure.mount"
record_failure_identity="${FIXTURE_ROOT}/dmg-record-write-failure.identity"
record_failure_log="${FIXTURE_ROOT}/dmg-record-write-failure.log"
record_failure_sentinel=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.unrelated.XXXXXX')
/bin/chmod 700 "${record_failure_sentinel}"
: >"${record_failure_sentinel}/preserve"
export FAKE_DMG_RECORD_FAILURE_MOUNT="${record_failure_mount}" \
  FAKE_DMG_RECORD_FAILURE_IDENTITY="${record_failure_identity}"
release_dmg_mountpoint_record_barrier() {
  local created_mountpoint=$1 created_identity
  created_identity=$(release_dmg_mountpoint_identity "${created_mountpoint}") || return 1
  printf '%s\n' "${created_mountpoint}" >"${FAKE_DMG_RECORD_FAILURE_MOUNT}"
  printf '%s\n' "${created_identity}" >"${FAKE_DMG_RECORD_FAILURE_IDENTITY}"
  exec 9>&-
}
set +e
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}" \
  >"${record_failure_log}" 2>&1
record_failure_status=$?
set -e
record_failure_mountpoint=$(/bin/cat -- "${record_failure_mount}")
record_failure_expected_identity=$(/bin/cat -- "${record_failure_identity}")
record_failure_orphan=0
[[ ! -e "${record_failure_mountpoint}" && ! -L "${record_failure_mountpoint}" ]] || \
  record_failure_orphan=1
record_failure_sentinel_preserved=0
[[ -f "${record_failure_sentinel}/preserve" ]] && record_failure_sentinel_preserved=1
if [[ "${record_failure_orphan}" = 1 ]]; then
  record_failure_actual_identity=$(release_dmg_mountpoint_identity "${record_failure_mountpoint}" 2>/dev/null || true)
  if [[ "${record_failure_actual_identity}" = "${record_failure_expected_identity}" ]]; then
    /bin/rmdir -- "${record_failure_mountpoint}" || true
  fi
fi
/bin/rm -rf -- "${record_failure_sentinel}"
unset FAKE_DMG_RECORD_FAILURE_MOUNT FAKE_DMG_RECORD_FAILURE_IDENTITY
release_dmg_mountpoint_record_barrier() { :; }
[[ "${record_failure_status}" -ne 0 ]] || \
  fail 'DMG mountpoint handoff write 실패가 거부되지 않았습니다.'
[[ "${record_failure_sentinel_preserved}" = 1 ]] || \
  fail 'DMG mountpoint handoff write 실패 cleanup이 unrelated directory를 삭제했습니다.'
[[ "${record_failure_orphan}" = 0 ]] || \
  fail 'DMG mountpoint handoff write 실패 뒤 local mountpoint orphan이 남았습니다.'
release_dmg_has_active_state && \
  fail 'DMG mountpoint handoff write 실패 cleanup 후 active state가 남았습니다.'

record_rebind_mount="${FIXTURE_ROOT}/dmg-record-rebind.mount"
record_rebind_backup="${FIXTURE_ROOT}/dmg-record-rebind.backup"
record_rebind_expected="${FIXTURE_ROOT}/dmg-record-rebind.expected"
record_rebind_replacement="${FIXTURE_ROOT}/dmg-record-rebind.replacement"
record_rebind_log="${FIXTURE_ROOT}/dmg-record-rebind.log"
export FAKE_DMG_REBIND_MOUNT="${record_rebind_mount}" \
  FAKE_DMG_REBIND_BACKUP="${record_rebind_backup}" \
  FAKE_DMG_REBIND_EXPECTED="${record_rebind_expected}" \
  FAKE_DMG_REBIND_REPLACEMENT="${record_rebind_replacement}"
release_dmg_mountpoint_record_barrier() {
  local created_mountpoint=$1 expected_identity backup replacement_identity
  expected_identity=$(release_dmg_mountpoint_identity "${created_mountpoint}") || return 1
  backup="${created_mountpoint}.record-original"
  /bin/mv "${created_mountpoint}" "${backup}" || return 1
  /bin/mkdir -m 700 "${created_mountpoint}" || return 1
  : >"${created_mountpoint}/preserve"
  replacement_identity=$(release_dmg_mountpoint_identity "${created_mountpoint}") || return 1
  printf '%s\n' "${created_mountpoint}" >"${FAKE_DMG_REBIND_MOUNT}"
  printf '%s\n' "${backup}" >"${FAKE_DMG_REBIND_BACKUP}"
  printf '%s\n' "${expected_identity}" >"${FAKE_DMG_REBIND_EXPECTED}"
  printf '%s\n' "${replacement_identity}" >"${FAKE_DMG_REBIND_REPLACEMENT}"
  exec 9>&-
}
set +e
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}" \
  >"${record_rebind_log}" 2>&1
record_rebind_status=$?
set -e
record_rebind_mountpoint=$(/bin/cat -- "${record_rebind_mount}")
record_rebind_backup_path=$(/bin/cat -- "${record_rebind_backup}")
record_rebind_expected_identity=$(/bin/cat -- "${record_rebind_expected}")
record_rebind_replacement_identity=$(/bin/cat -- "${record_rebind_replacement}")
record_rebind_preserved=0
[[ -f "${record_rebind_mountpoint}/preserve" ]] && record_rebind_preserved=1
record_rebind_active=0
release_dmg_has_active_state && record_rebind_active=1
record_rebind_evidence=0
[[ -d "${SIXBRIDGE_ACTIVE_DMG_SNAPSHOT_DIR:-/nonexistent}" ]] && record_rebind_evidence=1
record_rebind_actual=$(release_dmg_mountpoint_identity "${record_rebind_mountpoint}" 2>/dev/null || true)
if [[ "${record_rebind_actual}" = "${record_rebind_replacement_identity}" ]]; then
  /bin/rm -- "${record_rebind_mountpoint}/preserve"
  /bin/rmdir "${record_rebind_mountpoint}"
fi
record_rebind_backup_actual=$(release_dmg_mountpoint_identity "${record_rebind_backup_path}" 2>/dev/null || true)
if [[ "${record_rebind_backup_actual}" = "${record_rebind_expected_identity}" &&
      ! -e "${record_rebind_mountpoint}" && ! -L "${record_rebind_mountpoint}" ]]; then
  /bin/mv "${record_rebind_backup_path}" "${record_rebind_mountpoint}"
fi
SIXBRIDGE_ACTIVE_DMG_MOUNTPOINT_IDENTITY=${record_rebind_expected_identity}
record_rebind_cleanup_status=0
if release_dmg_has_active_state; then
  release_dmg_cleanup_active || record_rebind_cleanup_status=$?
else
  record_rebind_restored_identity=$(
    release_dmg_mountpoint_identity "${record_rebind_mountpoint}" 2>/dev/null || true
  )
  if [[ "${record_rebind_restored_identity}" = "${record_rebind_expected_identity}" ]]; then
    /bin/rmdir "${record_rebind_mountpoint}" || record_rebind_cleanup_status=$?
  fi
fi
unset FAKE_DMG_REBIND_MOUNT FAKE_DMG_REBIND_BACKUP FAKE_DMG_REBIND_EXPECTED \
  FAKE_DMG_REBIND_REPLACEMENT
release_dmg_mountpoint_record_barrier() { :; }
[[ "${record_rebind_status}" -ne 0 ]] || \
  fail 'DMG mountpoint same-path replacement record 실패가 거부되지 않았습니다.'
[[ "${record_rebind_preserved}" = 1 ]] || \
  fail 'DMG mountpoint child cleanup이 same-path replacement를 삭제했습니다.'
[[ "${record_rebind_active}" = 1 && "${record_rebind_evidence}" = 1 ]] || \
  fail 'DMG mountpoint same-path replacement 뒤 active evidence가 보존되지 않았습니다.'
[[ "${record_rebind_cleanup_status}" = 0 ]] || \
  fail 'DMG mountpoint same-path replacement 복구 후 cleanup에 실패했습니다.'
[[ ! -e "${record_rebind_mountpoint}" && ! -L "${record_rebind_mountpoint}" &&
   ! -e "${record_rebind_backup_path}" && ! -L "${record_rebind_backup_path}" ]] || \
  fail 'DMG mountpoint same-path replacement 복구 cleanup 후 residue가 남았습니다.'
release_dmg_has_active_state && \
  fail 'DMG mountpoint same-path replacement 복구 cleanup 후 active state가 남았습니다.'

fifo_rebind_sentinel="${FIXTURE_ROOT}/dmg-fifo-rebind.sentinel"
fifo_rebind_log="${FIXTURE_ROOT}/dmg-fifo-rebind.log"
printf 'preserve\n' >"${fifo_rebind_sentinel}"
export FAKE_DMG_FIFO_REBIND_SENTINEL="${fifo_rebind_sentinel}"
release_dmg_mountpoint_record_barrier() {
  local fifo_path="${SIXBRIDGE_ACTIVE_DMG_SNAPSHOT_DIR}/dmg-mountpoint.identity.fifo"
  [[ ! -e "${fifo_path}" && ! -L "${fifo_path}" ]] || return 1
  /bin/ln -s "${FAKE_DMG_FIFO_REBIND_SENTINEL}" "${fifo_path}" || return 1
}
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}" \
  >"${fifo_rebind_log}" 2>&1
[[ "$(/usr/bin/grep -Fc 'preserve' "${fifo_rebind_sentinel}")" = 1 ]] || \
  fail 'DMG identity FIFO pathname 재사용 파일이 변경되었습니다.'
unset FAKE_DMG_FIFO_REBIND_SENTINEL
release_dmg_mountpoint_record_barrier() { :; }
release_dmg_has_active_state && \
  fail 'DMG identity FIFO pathname 재사용 성공 뒤 active state가 남았습니다.'

malformed_fifo_log="${FIXTURE_ROOT}/dmg-malformed-fifo.log"
release_dmg_mountpoint_record_barrier() {
  printf 'malformed\n' >&9
  exec 9>&-
}
set +e
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}" \
  >"${malformed_fifo_log}" 2>&1
malformed_fifo_status=$?
set -e
release_dmg_mountpoint_record_barrier() { :; }
[[ "${malformed_fifo_status}" -ne 0 ]] || \
  fail 'DMG malformed identity FIFO payload가 거부되지 않았습니다.'
release_dmg_has_active_state && \
  fail 'DMG malformed identity FIFO payload cleanup 후 active state가 남았습니다.'

for signal_case in HUP INT TERM; do
  case "${signal_case}" in HUP) expected_status=129 ;; INT) expected_status=130 ;; TERM) expected_status=143 ;; esac
  record_ready="${FIXTURE_ROOT}/dmg-record-signal-${signal_case}.ready"
  record_mount="${FIXTURE_ROOT}/dmg-record-signal-${signal_case}.mount"
  record_snapshot="${FIXTURE_ROOT}/dmg-record-signal-${signal_case}.snapshot"
  export FAKE_DMG_RECORD_READY="${record_ready}" FAKE_DMG_RECORD_MOUNT="${record_mount}" \
    FAKE_DMG_RECORD_SNAPSHOT="${record_snapshot}"
  release_dmg_mountpoint_record_barrier() {
    local created_mountpoint=$1
    printf '%s\n' "${created_mountpoint}" >"${FAKE_DMG_RECORD_MOUNT}"
    printf '%s\n' "${SIXBRIDGE_ACTIVE_DMG_SNAPSHOT}" >"${FAKE_DMG_RECORD_SNAPSHOT}"
    : >"${FAKE_DMG_RECORD_READY}"
    /bin/sleep 0.25
  }
  set -m
  (release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
  record_parent_pid=$!
  set +m
  for _ in {1..100}; do
    [[ -e "${record_ready}" ]] && break
    /bin/kill -0 "${record_parent_pid}" 2>/dev/null || break
    /bin/sleep 0.05
  done
  [[ -e "${record_ready}" ]] || {
    /bin/kill -TERM -- "-${record_parent_pid}" 2>/dev/null || true
    wait "${record_parent_pid}" 2>/dev/null || true
    fail "${signal_case} DMG record signal fixture가 pre-record barrier에 도달하지 못했습니다."
  }
  /bin/kill -"${signal_case}" -- "-${record_parent_pid}"
  set +e
  wait "${record_parent_pid}"
  record_status=$?
  set -e
  [[ "${record_status}" = "${expected_status}" ]] || \
    fail "${signal_case} DMG record signal 종료 코드가 올바르지 않습니다: ${record_status}"
  record_mountpoint=$(/bin/cat -- "${record_mount}")
  record_snapshot_path=$(/bin/cat -- "${record_snapshot}")
  [[ ! -e "${record_mountpoint}" && ! -L "${record_mountpoint}" &&
     ! -e "${record_snapshot_path}" && ! -L "${record_snapshot_path}" ]] || \
    fail "${signal_case} DMG record signal 뒤 mountpoint 또는 snapshot이 남았습니다."
  unset FAKE_DMG_RECORD_READY FAKE_DMG_RECORD_MOUNT FAKE_DMG_RECORD_SNAPSHOT
done
release_dmg_mountpoint_record_barrier() { :; }

for signal_case in HUP INT TERM; do
  case "${signal_case}" in HUP) expected_status=129 ;; INT) expected_status=130 ;; TERM) expected_status=143 ;; esac
  spawn_ready="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.ready"
  spawn_child_ready="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.child-ready"
  spawn_continue="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.continue"
  spawn_child_record="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.child"
  spawn_detach_log="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.detach"; : >"${spawn_detach_log}"
  spawn_source_record="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.source"
  spawn_mount_log="${FIXTURE_ROOT}/dmg-spawn-signal-${signal_case}.mount"
  export FAKE_DMG_SPAWN_READY="${spawn_ready}" FAKE_HDIUTIL_READY="${spawn_child_ready}" \
    FAKE_HDIUTIL_CONTINUE="${spawn_continue}" FAKE_HDIUTIL_CHILD_RECORD="${spawn_child_record}" \
    FAKE_HDIUTIL_DETACH_LOG="${spawn_detach_log}" \
    FAKE_HDIUTIL_SOURCE_RECORD="${spawn_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${spawn_mount_log}"
  release_dmg_attach_spawned_barrier() {
    local spawned_child=$1
    while [[ ! -e "${FAKE_HDIUTIL_READY}" ]]; do
      /bin/kill -0 "${spawned_child}" 2>/dev/null || return 1
      /bin/sleep 0.05
    done
    : >"${FAKE_DMG_SPAWN_READY}"
    /bin/sleep 0.25
  }
  set -m
  (release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
  spawn_parent_pid=$!
  set +m
  for _ in {1..100}; do
    [[ -e "${spawn_ready}" ]] && break
    /bin/kill -0 "${spawn_parent_pid}" 2>/dev/null || break
    /bin/sleep 0.05
  done
  [[ -e "${spawn_ready}" ]] || {
    /bin/kill -TERM "${spawn_parent_pid}" 2>/dev/null || true
    wait "${spawn_parent_pid}" 2>/dev/null || true
    fail "${signal_case} DMG spawn signal fixture가 pre-record barrier에 도달하지 못했습니다."
  }
  /bin/kill -"${signal_case}" "${spawn_parent_pid}"
  set +e
  wait "${spawn_parent_pid}"
  spawn_status=$?
  set -e
  [[ "${spawn_status}" = "${expected_status}" ]] || \
    fail "${signal_case} DMG spawn signal 종료 코드가 올바르지 않습니다: ${spawn_status}"
  spawn_child_pid=$(/bin/cat -- "${spawn_child_record}")
  ! /bin/kill -0 "${spawn_child_pid}" 2>/dev/null || {
    /bin/kill -TERM "${spawn_child_pid}" 2>/dev/null || true
    fail "${signal_case} spawn signal 뒤 attach child가 남았습니다: ${spawn_child_pid}"
  }
  [[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${spawn_detach_log}")" = 1 ]] || \
    fail "${signal_case} spawn signal cleanup의 exact detach 횟수가 1이 아닙니다."
  spawn_snapshot=$(/bin/cat -- "${spawn_source_record}")
  spawn_mountpoint=$(/usr/bin/tail -n 1 "${spawn_mount_log}")
  [[ ! -e "${spawn_snapshot}" && ! -L "${spawn_snapshot}" &&
     ! -e "${spawn_mountpoint}" && ! -L "${spawn_mountpoint}" ]] || \
    fail "${signal_case} spawn signal 뒤 snapshot 또는 mountpoint가 남았습니다."
  unset FAKE_DMG_SPAWN_READY FAKE_HDIUTIL_READY FAKE_HDIUTIL_CONTINUE \
    FAKE_HDIUTIL_CHILD_RECORD FAKE_HDIUTIL_DETACH_LOG \
    FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG
done
release_dmg_attach_spawned_barrier() { :; }

signal_exit_ready="${FIXTURE_ROOT}/dmg-signal-exit.ready"
signal_exit_attach_ready="${FIXTURE_ROOT}/dmg-signal-exit.attach-ready"
signal_exit_continue="${FIXTURE_ROOT}/dmg-signal-exit.continue"
signal_exit_child_record="${FIXTURE_ROOT}/dmg-signal-exit.child"
signal_exit_detach_log="${FIXTURE_ROOT}/dmg-signal-exit.detach"; : >"${signal_exit_detach_log}"
signal_exit_source_record="${FIXTURE_ROOT}/dmg-signal-exit.source"
signal_exit_mount_log="${FIXTURE_ROOT}/dmg-signal-exit.mount"
export FAKE_HDIUTIL_READY="${signal_exit_attach_ready}" \
  FAKE_HDIUTIL_CONTINUE="${signal_exit_continue}" \
  FAKE_HDIUTIL_CHILD_RECORD="${signal_exit_child_record}" \
  FAKE_HDIUTIL_DETACH_FAIL=1 FAKE_HDIUTIL_DETACH_LOG="${signal_exit_detach_log}" \
  FAKE_HDIUTIL_SOURCE_RECORD="${signal_exit_source_record}" \
  FAKE_HDIUTIL_MOUNT_LOG="${signal_exit_mount_log}" \
  FAKE_DMG_SIGNAL_EXIT_READY="${signal_exit_ready}"
release_dmg_signal_exit_barrier() {
  : >"${FAKE_DMG_SIGNAL_EXIT_READY}"
  /bin/sleep 0.25
}
set -m
(release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
signal_exit_pid=$!
set +m
for _ in {1..100}; do
  [[ -e "${signal_exit_attach_ready}" ]] && break
  /bin/kill -0 "${signal_exit_pid}" 2>/dev/null || break
  /bin/sleep 0.05
done
[[ -e "${signal_exit_attach_ready}" ]] || {
  /bin/kill -TERM "${signal_exit_pid}" 2>/dev/null || true
  wait "${signal_exit_pid}" 2>/dev/null || true
  fail 'DMG signal-exit fixture가 attach barrier에 도달하지 못했습니다.'
}
/bin/kill -HUP "${signal_exit_pid}"
for _ in {1..100}; do
  [[ -e "${signal_exit_ready}" ]] && break
  /bin/kill -0 "${signal_exit_pid}" 2>/dev/null || break
  /bin/sleep 0.05
done
[[ -e "${signal_exit_ready}" ]] || {
  wait "${signal_exit_pid}" 2>/dev/null || true
  fail 'DMG signal-exit cleanup failure barrier가 실행되지 않았습니다.'
}
/bin/kill -TERM "${signal_exit_pid}"
set +e
wait "${signal_exit_pid}"
signal_exit_status=$?
set -e
[[ "${signal_exit_status}" = 129 ]] || \
  fail "DMG cleanup 중 두 번째 signal이 원래 HUP 상태를 바꾸었습니다: ${signal_exit_status}"
[[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${signal_exit_detach_log}")" = 1 ]] || \
  fail 'DMG signal-exit cleanup이 재진입하여 detach를 여러 번 실행했습니다.'
signal_exit_child_pid=$(/bin/cat -- "${signal_exit_child_record}")
! /bin/kill -0 "${signal_exit_child_pid}" 2>/dev/null || {
  /bin/kill -TERM "${signal_exit_child_pid}" 2>/dev/null || true
  fail 'DMG signal-exit cleanup 뒤 attach child가 남았습니다.'
}
signal_exit_snapshot=$(/bin/cat -- "${signal_exit_source_record}")
signal_exit_mountpoint=$(/usr/bin/tail -n 1 "${signal_exit_mount_log}")
[[ -f "${signal_exit_snapshot}" && -d "${signal_exit_mountpoint}" ]] || \
  fail 'DMG signal-exit cleanup 실패 뒤 evidence가 보존되지 않았습니다.'
unset FAKE_HDIUTIL_DETACH_FAIL
"${FIXTURE_ROOT}/bin/hdiutil" detach /dev/disk71 >/dev/null
/bin/rmdir "${signal_exit_mountpoint}"
signal_exit_snapshot_dir=$(/usr/bin/dirname -- "${signal_exit_snapshot}")
signal_exit_snapshot_dir_identity=$(release_owned_directory_identity "${signal_exit_snapshot_dir}")
release_remove_verification_snapshot "${signal_exit_snapshot_dir}" \
  "${signal_exit_snapshot_dir_identity}"
unset FAKE_HDIUTIL_READY FAKE_HDIUTIL_CONTINUE FAKE_HDIUTIL_CHILD_RECORD \
  FAKE_HDIUTIL_DETACH_LOG FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG \
  FAKE_DMG_SIGNAL_EXIT_READY
release_dmg_signal_exit_barrier() { :; }

for signal_case in HUP INT TERM; do
  case "${signal_case}" in HUP) expected_status=129 ;; INT) expected_status=130 ;; TERM) expected_status=143 ;; esac
  create_ready="${FIXTURE_ROOT}/dmg-create-signal-${signal_case}.ready"
  create_continue="${FIXTURE_ROOT}/dmg-create-signal-${signal_case}.continue"
  create_snapshot_record="${FIXTURE_ROOT}/dmg-create-signal-${signal_case}.snapshot"
  create_mount_record="${FIXTURE_ROOT}/dmg-create-signal-${signal_case}.mount"
  export FAKE_DMG_CREATE_READY="${create_ready}" FAKE_DMG_CREATE_CONTINUE="${create_continue}" \
    FAKE_DMG_CREATE_SNAPSHOT_RECORD="${create_snapshot_record}" \
    FAKE_DMG_CREATE_MOUNT_RECORD="${create_mount_record}"
  release_dmg_mountpoint_created_barrier() {
    printf '%s\n' "${SIXBRIDGE_ACTIVE_DMG_SNAPSHOT}" >"${FAKE_DMG_CREATE_SNAPSHOT_RECORD}"
    printf '%s\n' "${SIXBRIDGE_ACTIVE_DMG_MOUNTPOINT}" >"${FAKE_DMG_CREATE_MOUNT_RECORD}"
    : >"${FAKE_DMG_CREATE_READY}"
    while [[ ! -e "${FAKE_DMG_CREATE_CONTINUE}" ]]; do /bin/sleep 0.05; done
  }
  set -m
  (release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
  create_pid=$!
  set +m
  for _ in {1..100}; do
    [[ -e "${create_ready}" ]] && break
    /bin/kill -0 "${create_pid}" 2>/dev/null || break
    /bin/sleep 0.05
  done
  [[ -e "${create_ready}" ]] || {
    /bin/kill -TERM "${create_pid}" 2>/dev/null || true
    wait "${create_pid}" 2>/dev/null || true
    fail "${signal_case} DMG create signal fixture가 barrier에 도달하지 못했습니다."
  }
  /bin/kill -"${signal_case}" "${create_pid}"
  set +e
  wait "${create_pid}"
  create_status=$?
  set -e
  [[ "${create_status}" = "${expected_status}" ]] || \
    fail "${signal_case} DMG create signal 종료 코드가 올바르지 않습니다: ${create_status}"
  create_snapshot=$(/bin/cat -- "${create_snapshot_record}")
  create_mountpoint=$(/bin/cat -- "${create_mount_record}")
  [[ ! -e "${create_snapshot}" && ! -L "${create_snapshot}" &&
     ! -e "${create_mountpoint}" && ! -L "${create_mountpoint}" ]] || \
    fail "${signal_case} DMG create signal 뒤 snapshot 또는 mountpoint가 남았습니다."
  unset FAKE_DMG_CREATE_READY FAKE_DMG_CREATE_CONTINUE FAKE_DMG_CREATE_SNAPSHOT_RECORD \
    FAKE_DMG_CREATE_MOUNT_RECORD
done
release_dmg_mountpoint_created_barrier() { :; }

for signal_case in HUP INT TERM; do
  case "${signal_case}" in HUP) expected_status=129 ;; INT) expected_status=130 ;; TERM) expected_status=143 ;; esac
  attach_ready="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.ready"
  attach_continue="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.continue"
  attach_child_record="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.child"
  attach_detach_log="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.detach"; : >"${attach_detach_log}"
  attach_source_record="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.source"
  attach_mount_log="${FIXTURE_ROOT}/dmg-child-signal-${signal_case}.mount"
  export FAKE_HDIUTIL_READY="${attach_ready}" FAKE_HDIUTIL_CONTINUE="${attach_continue}" \
    FAKE_HDIUTIL_CHILD_RECORD="${attach_child_record}" \
    FAKE_HDIUTIL_DETACH_LOG="${attach_detach_log}" \
    FAKE_HDIUTIL_SOURCE_RECORD="${attach_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${attach_mount_log}"
  set -m
  (release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
  attach_parent_pid=$!
  set +m
  for _ in {1..100}; do
    [[ -e "${attach_ready}" && -s "${attach_child_record}" ]] && break
    /bin/kill -0 "${attach_parent_pid}" 2>/dev/null || break
    /bin/sleep 0.05
  done
  [[ -e "${attach_ready}" && -s "${attach_child_record}" ]] || {
    /bin/kill -TERM "${attach_parent_pid}" 2>/dev/null || true
    wait "${attach_parent_pid}" 2>/dev/null || true
    fail "${signal_case} DMG attach child fixture가 barrier에 도달하지 못했습니다."
  }
  attach_child_pid=$(/bin/cat -- "${attach_child_record}")
  /bin/kill -"${signal_case}" "${attach_parent_pid}"
  set +e
  wait "${attach_parent_pid}"
  attach_parent_status=$?
  set -e
  [[ "${attach_parent_status}" = "${expected_status}" ]] || \
    fail "${signal_case} DMG attach signal 종료 코드가 올바르지 않습니다: ${attach_parent_status}"
  ! /bin/kill -0 "${attach_child_pid}" 2>/dev/null || {
    /bin/kill -TERM "${attach_child_pid}" 2>/dev/null || true
    fail "${signal_case} 뒤 exact attach child가 남았습니다: ${attach_child_pid}"
  }
  [[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${attach_detach_log}")" = 1 ]] || \
    fail "${signal_case} attach signal cleanup의 exact detach 횟수가 1이 아닙니다."
  attach_snapshot=$(/bin/cat -- "${attach_source_record}")
  attach_mountpoint=$(/usr/bin/tail -n 1 "${attach_mount_log}")
  [[ ! -e "${attach_snapshot}" && ! -L "${attach_snapshot}" &&
     ! -e "${attach_mountpoint}" && ! -L "${attach_mountpoint}" ]] || \
    fail "${signal_case} attach signal 뒤 snapshot 또는 mountpoint가 남았습니다."
  unset FAKE_HDIUTIL_READY FAKE_HDIUTIL_CONTINUE FAKE_HDIUTIL_CHILD_RECORD \
    FAKE_HDIUTIL_DETACH_LOG FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG
done

stale_detach_log="${FIXTURE_ROOT}/dmg-detach-stale.detach"; : >"${stale_detach_log}"
stale_source_record="${FIXTURE_ROOT}/dmg-detach-stale.source"
stale_mount_log="${FIXTURE_ROOT}/dmg-detach-stale.mount"
export FAKE_HDIUTIL_DETACH_STALE=1 FAKE_HDIUTIL_DETACH_LOG="${stale_detach_log}" \
  FAKE_HDIUTIL_SOURCE_RECORD="${stale_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${stale_mount_log}"
expect_failure dmg-detach-stale 'detach 후 inventory가 남아' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
release_dmg_has_active_state || fail 'detach 0 뒤 inventory가 남은 경우 active evidence가 사라졌습니다.'
stale_snapshot=$(/bin/cat -- "${stale_source_record}")
stale_mountpoint=$(/usr/bin/tail -n 1 "${stale_mount_log}")
[[ -f "${stale_snapshot}" && -d "${stale_mountpoint}" ]] || \
  fail 'detach 0 뒤 inventory가 남은 경우 snapshot 또는 mountpoint가 보존되지 않았습니다.'
[[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${stale_detach_log}")" = 1 ]] || \
  fail 'stale detach 첫 시도의 exact detach 횟수가 1이 아닙니다.'
unset FAKE_HDIUTIL_DETACH_STALE
release_dmg_cleanup_active
[[ "$(/usr/bin/grep -Fc 'detach /dev/disk71' "${stale_detach_log}")" = 2 ]] || \
  fail 'stale detach 재시도 뒤 누적 exact detach 횟수가 2가 아닙니다.'
[[ ! -e "${stale_snapshot}" && ! -L "${stale_snapshot}" &&
   ! -e "${stale_mountpoint}" && ! -L "${stale_mountpoint}" ]] || \
  fail 'stale detach 재시도 성공 뒤 evidence가 남았습니다.'
release_dmg_has_active_state && fail 'stale detach 재시도 성공 뒤 active state가 남았습니다.'
unset FAKE_HDIUTIL_DETACH_LOG FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG

outer_hup_marker="${FIXTURE_ROOT}/dmg-outer-hup.marker"
outer_int_marker="${FIXTURE_ROOT}/dmg-outer-int.marker"
outer_term_marker="${FIXTURE_ROOT}/dmg-outer-term.marker"
saved_hup_trap=$(trap -p HUP)
saved_int_trap=$(trap -p INT)
saved_term_trap=$(trap -p TERM)
trap ': >"${outer_hup_marker}"' HUP
trap ': >"${outer_int_marker}"' INT
trap ': >"${outer_term_marker}"' TERM
expected_hup_trap=$(trap -p HUP)
expected_int_trap=$(trap -p INT)
expected_term_trap=$(trap -p TERM)
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
[[ "$(trap -p HUP)" = "${expected_hup_trap}" &&
   "$(trap -p INT)" = "${expected_int_trap}" &&
   "$(trap -p TERM)" = "${expected_term_trap}" ]] || \
  fail '정상 DMG cleanup 뒤 기존 outer signal trap이 exact 복원되지 않았습니다.'
/bin/kill -HUP "$$"
[[ -f "${outer_hup_marker}" ]] || fail '복원된 outer HUP trap이 실제 signal에서 실행되지 않았습니다.'
if [[ -n "${saved_hup_trap}" ]]; then eval "${saved_hup_trap}"; else trap - HUP; fi
if [[ -n "${saved_int_trap}" ]]; then eval "${saved_int_trap}"; else trap - INT; fi
if [[ -n "${saved_term_trap}" ]]; then eval "${saved_term_trap}"; else trap - TERM; fi
if [[ "${SIXBRIDGE_RELEASE_TEST_DMG_STATE_ONLY:-0}" = 1 ]]; then
  printf 'release-lib DMG state RED/GREEN 검증 통과\n'
  exit 0
fi

local_mount_log="${FIXTURE_ROOT}/local-mount.log"; : >"${local_mount_log}"
export FAKE_HDIUTIL_REQUIRE_LOCAL_MOUNT=1 FAKE_HDIUTIL_MOUNT_LOG="${local_mount_log}"
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
assert_contains "${local_mount_log}" '/private/tmp/sixbridge-dmg.' 'DMG mountpoint가 고정 local private root 아래에 있지 않습니다.'
local_mountpoint=$(/usr/bin/tail -n 1 "${local_mount_log}")
[[ ! -e "${local_mountpoint}" && ! -L "${local_mountpoint}" ]] || fail '정상 DMG 검증 뒤 local mountpoint가 남았습니다.'
unset FAKE_HDIUTIL_REQUIRE_LOCAL_MOUNT FAKE_HDIUTIL_MOUNT_LOG

for invalid_container_result in unsigned-extra unsigned-wrong-path unsigned-exit-zero; do
  export FAKE_CODESIGN_DMG_CONTAINER="${invalid_container_result}"
  expect_failure "dmg-container-${invalid_container_result}" 'DMG container가 미서명' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
done
unset FAKE_CODESIGN_DMG_CONTAINER

export FAKE_CODESIGN_APP_RESULT=unsigned
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
for invalid_app_result in unsigned-extra unsigned-wrong-path; do
  export FAKE_CODESIGN_APP_RESULT="${invalid_app_result}"
  expect_failure "dmg-app-${invalid_app_result}" '미서명 상태' \
    release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
done
export FAKE_CODESIGN_APP_RESULT=unsigned-exit-zero
expect_failure 'dmg-app-unsigned-exit-zero' 'Developer ID 서명' \
  release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
unset FAKE_CODESIGN_APP_RESULT

fixture_cleanup_mountpoint=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.XXXXXX')
/bin/chmod 700 "${fixture_cleanup_mountpoint}"
set +e
(
  trap 'cleanup_fake_dmg_manifest "${DMG_MOUNT_MANIFEST}"' EXIT
  "${SIXBRIDGE_HDIUTIL}" attach -readonly -nobrowse \
    -mountpoint "${fixture_cleanup_mountpoint}" "${dmg}"
  exit 91
)
fixture_cleanup_status=$?
set -e
[[ "${fixture_cleanup_status}" = 91 ]] || fail "fake DMG fixture 중간 실패 status가 다릅니다: ${fixture_cleanup_status}"
if [[ -e "${fixture_cleanup_mountpoint}" || -L "${fixture_cleanup_mountpoint}" ||
      -e "${fixture_cleanup_mountpoint}.underlay" || -L "${fixture_cleanup_mountpoint}.underlay" ]]; then
  case "${fixture_cleanup_mountpoint}" in
    /private/tmp/sixbridge-dmg.*)
      /bin/rm -rf -- "${fixture_cleanup_mountpoint}" "${fixture_cleanup_mountpoint}.underlay"
      ;;
  esac
  fail 'fake DMG fixture 중간 실패 뒤 exact local overlay 또는 underlay가 남았습니다.'
fi

fixture_reused_mountpoint=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.XXXXXX')
/bin/chmod 700 "${fixture_reused_mountpoint}"
set +e
(
  trap 'cleanup_fake_dmg_manifest "${DMG_MOUNT_MANIFEST}"' EXIT
  "${SIXBRIDGE_HDIUTIL}" attach -readonly -nobrowse \
    -mountpoint "${fixture_reused_mountpoint}" "${dmg}"
  /bin/rm -rf -- "${fixture_reused_mountpoint}"
  /bin/mkdir -m 700 "${fixture_reused_mountpoint}"
  : >"${fixture_reused_mountpoint}/same-user-reused-path"
  exit 92
)
fixture_reused_status=$?
set -e
[[ "${fixture_reused_status}" = 92 ]] || fail "fake DMG path reuse status가 다릅니다: ${fixture_reused_status}"
fixture_reused_preserved=0
[[ -f "${fixture_reused_mountpoint}/same-user-reused-path" ]] && fixture_reused_preserved=1
case "${fixture_reused_mountpoint}" in
  /private/tmp/sixbridge-dmg.*)
    /bin/rm -rf -- "${fixture_reused_mountpoint}" "${fixture_reused_mountpoint}.underlay"
    ;;
esac
[[ "${fixture_reused_preserved}" = 1 ]] || fail 'manifest cleanup이 같은 UID로 재사용된 경로를 삭제했습니다.'

fixture_unmanifested_sentinel=$(/usr/bin/mktemp -d '/private/tmp/sixbridge-dmg.unmanifested.XXXXXX')
/bin/chmod 700 "${fixture_unmanifested_sentinel}"
: >"${fixture_unmanifested_sentinel}/preserve"
cleanup_fake_dmg_manifest "${DMG_MOUNT_MANIFEST}"
fixture_sentinel_preserved=0
[[ -f "${fixture_unmanifested_sentinel}/preserve" ]] && fixture_sentinel_preserved=1
/bin/rm -rf -- "${fixture_unmanifested_sentinel}"
[[ "${fixture_sentinel_preserved}" = 1 ]] || fail 'manifest에 없는 같은 prefix sentinel을 cleanup이 삭제했습니다.'

attach_failure_log="${FIXTURE_ROOT}/attach-failure.log"
attach_failure_detach_log="${FIXTURE_ROOT}/attach-failure-detach.log"; : >"${attach_failure_detach_log}"
export FAKE_HDIUTIL_ATTACH_FAIL=1 FAKE_HDIUTIL_DETACH_LOG="${attach_failure_detach_log}"
set +e
release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}" >"${attach_failure_log}" 2>&1
attach_failure_status=$?
set -e
[[ "${attach_failure_status}" -ne 0 ]] || fail '일반 attach 실패를 허용했습니다.'
assert_contains "${attach_failure_log}" 'fake attach raw failure' 'hdiutil attach의 원래 오류가 보존되지 않았습니다.'
[[ ! -s "${attach_failure_detach_log}" ]] || fail '마운트되지 않은 attach 실패에 detach를 실행했습니다.'
unset FAKE_HDIUTIL_ATTACH_FAIL FAKE_HDIUTIL_DETACH_LOG

partial_attach_log="${FIXTURE_ROOT}/partial-attach.log"; : >"${partial_attach_log}"
export FAKE_HDIUTIL_PARTIAL_ATTACH=1 FAKE_HDIUTIL_DETACH_LOG="${partial_attach_log}"
expect_failure dmg-partial-attach 'DMG read-only mount' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
unset FAKE_HDIUTIL_PARTIAL_ATTACH FAKE_HDIUTIL_DETACH_LOG
assert_contains "${partial_attach_log}" detach 'partial attach 뒤 detach가 실행되지 않았습니다.'
remaining_dmg_work=$(/usr/bin/find "${dmg_work}" -mindepth 1 -maxdepth 1 -print -quit)
[[ -z "${remaining_dmg_work}" ]] || fail 'partial attach 뒤 임시 mount 또는 snapshot이 남았습니다.'

detach_failure_log="${FIXTURE_ROOT}/detach-failure.log"; : >"${detach_failure_log}"
detach_source_record="${FIXTURE_ROOT}/detach-source.txt"
detach_mount_log="${FIXTURE_ROOT}/detach-mount.txt"
export FAKE_HDIUTIL_DETACH_FAIL=1 FAKE_HDIUTIL_DETACH_LOG="${detach_failure_log}" \
  FAKE_HDIUTIL_SOURCE_RECORD="${detach_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${detach_mount_log}"
expect_failure dmg-detach-failure 'DMG mount detach' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"
preserved_snapshot=$(/bin/cat -- "${detach_source_record}")
preserved_mountpoint=$(/usr/bin/tail -n 1 "${detach_mount_log}")
[[ -f "${preserved_snapshot}" ]] || fail 'detach 실패 뒤 backing snapshot이 보존되지 않았습니다.'
[[ -d "${preserved_mountpoint}" ]] || fail 'detach 실패 뒤 local mountpoint가 보존되지 않았습니다.'
unset FAKE_HDIUTIL_DETACH_FAIL
release_dmg_cleanup_active
[[ ! -e "${preserved_snapshot}" && ! -e "${preserved_mountpoint}" ]] || fail 'detach 재시도 성공 뒤 보존 evidence가 정리되지 않았습니다.'
unset FAKE_HDIUTIL_DETACH_LOG FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG

for signal_case in HUP INT TERM; do
  case "${signal_case}" in HUP) expected_status=129 ;; INT) expected_status=130 ;; TERM) expected_status=143 ;; esac
  signal_ready="${FIXTURE_ROOT}/dmg-signal-${signal_case}.ready"
  signal_continue="${FIXTURE_ROOT}/dmg-signal-${signal_case}.continue"
  signal_detach_log="${FIXTURE_ROOT}/dmg-signal-${signal_case}.detach"
  signal_source_record="${FIXTURE_ROOT}/dmg-signal-${signal_case}.source"
  signal_mount_log="${FIXTURE_ROOT}/dmg-signal-${signal_case}.mount"
  : >"${signal_detach_log}"
  export FAKE_HDIUTIL_READY="${signal_ready}" FAKE_HDIUTIL_CONTINUE="${signal_continue}" \
    FAKE_HDIUTIL_DETACH_LOG="${signal_detach_log}" \
    FAKE_HDIUTIL_SOURCE_RECORD="${signal_source_record}" FAKE_HDIUTIL_MOUNT_LOG="${signal_mount_log}"
  set -m
  (release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}") &
  signal_pid=$!
  set +m
  for _ in {1..100}; do
    [[ -e "${signal_ready}" ]] && break
    /bin/kill -0 "${signal_pid}" 2>/dev/null || break
    /bin/sleep 0.05
  done
  [[ -e "${signal_ready}" ]] || { /bin/kill -TERM -- "-${signal_pid}" 2>/dev/null || true; wait "${signal_pid}" 2>/dev/null || true; fail "${signal_case} DMG signal fixture가 attach를 완료하지 못했습니다."; }
  /bin/kill -"${signal_case}" -- "-${signal_pid}"
  set +e
  wait "${signal_pid}"
  signal_status=$?
  set -e
  [[ "${signal_status}" = "${expected_status}" ]] || fail "${signal_case} DMG signal 종료 코드가 올바르지 않습니다: ${signal_status}"
  [[ "$(/usr/bin/grep -Fc detach "${signal_detach_log}")" = 1 ]] || fail "${signal_case} 중 DMG detach가 정확히 한 번 실행되지 않았습니다."
  signal_snapshot=$(/bin/cat -- "${signal_source_record}")
  signal_mountpoint=$(/usr/bin/tail -n 1 "${signal_mount_log}")
  [[ ! -e "${signal_snapshot}" && ! -e "${signal_mountpoint}" ]] || fail "${signal_case} 뒤 DMG snapshot 또는 local mountpoint가 남았습니다."
  unset FAKE_HDIUTIL_READY FAKE_HDIUTIL_CONTINUE FAKE_HDIUTIL_DETACH_LOG \
    FAKE_HDIUTIL_SOURCE_RECORD FAKE_HDIUTIL_MOUNT_LOG
done

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
export FAKE_PRODUCT_VERSION=1.0.1; expect_failure dmg-old-package-version 'package version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_PRODUCT_VERSION='1.0.3'
export FAKE_NATIVE_VERSION=1.0.1; expect_failure dmg-old-native-version 'native package version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_NATIVE_VERSION='1.0.3'
export FAKE_MINIMUM_VERSION=12.0; expect_failure dmg-minimum-version 'minimum macOS version' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; export FAKE_MINIMUM_VERSION='13.0'
export FAKE_CODESIGN_APP_RESULT=unsigned; release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_CODESIGN_APP_RESULT
export FAKE_CODESIGN_APP_RESULT=developer-id; expect_failure dmg-developer-id-signed 'Developer ID' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_CODESIGN_APP_RESULT
export FAKE_CODESIGN_DMG_CONTAINER=adhoc; expect_failure dmg-container-adhoc-signed 'DMG container가 미서명' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_CODESIGN_DMG_CONTAINER
export FAKE_CODESIGN_DMG_CONTAINER=developer-id; expect_failure dmg-container-developer-id-signed 'DMG container가 미서명' release_verify_dmg "${dmg}" "${dmg_work}" "${stage}" "${container}"; unset FAKE_CODESIGN_DMG_CONTAINER
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
for forbidden in private.p12 private.jks private.pem private.pk8 input.six input.sixdraft input.crx fixture.zip user-extension-source.zip; do
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
set -m
(
  /bin/sleep 0.25
  set +e
  release_file_contains_secret "${fifo_scan_path}" probe "${fifo_expected_identity}"
  printf '%s\n' "$?" >"${fifo_status_file}"
) &
fifo_scan_pid=$!
set +m
fifo_scan_attempts=0
while [[ ! -s "${fifo_status_file}" && "${fifo_scan_attempts}" -lt 100 ]]; do
  /bin/kill -0 "${fifo_scan_pid}" 2>/dev/null || break
  /bin/sleep 0.05
  fifo_scan_attempts=$((fifo_scan_attempts + 1))
done
if [[ ! -s "${fifo_status_file}" ]]; then
  if /bin/kill -0 "${fifo_scan_pid}" 2>/dev/null; then
    /bin/kill -TERM -- "-${fifo_scan_pid}" 2>/dev/null || true
    fifo_scan_failure='path가 FIFO로 바뀐 binary secret scanner가 block되었습니다.'
  else
    fifo_scan_failure='FIFO scanner가 status를 기록하지 않고 종료했습니다.'
  fi
  wait "${fifo_scan_pid}" 2>/dev/null || true
  fail "${fifo_scan_failure}"
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
    make_file "${directory}/SIX-Bridge-Manager-0.1.3-android.apk" android
    make_file "${directory}/SIX-Bridge-0.1.3-macos-unsigned.dmg" macos
    make_file "${directory}/RELEASE-NOTES-KO.md" '한글 릴리스 노트'
    release_write_metadata "${directory}" "${GIT_COMMIT}" "${SIGNER}" "${container}"
    release_write_checksums "${directory}" "${container}"
    printf '%s\n' "${directory}"
}

release_one=$(make_release_directory one)
release_two=$(make_release_directory two)
/usr/bin/cmp "${release_one}/RELEASE-METADATA.json" "${release_two}/RELEASE-METADATA.json" || fail 'metadata가 root에 따라 달라집니다.'
/usr/bin/cmp "${release_one}/SHA256SUMS" "${release_two}/SHA256SUMS" || fail 'checksum이 root에 따라 달라집니다.'
/usr/bin/jq -e --arg signer "${SIGNER}" '
  .productVersion == "0.1.3" and
  .android.file == "SIX-Bridge-Manager-0.1.3-android.apk" and
  .android.versionName == "0.1.3" and .android.versionCode == 4 and
  .android.signerSha256 == $signer and
  .macos.file == "SIX-Bridge-0.1.3-macos-unsigned.dmg" and
  .macos.productVersion == "0.1.3" and .macos.nativePackageVersion == "1.0.3"
' "${release_one}/RELEASE-METADATA.json" >/dev/null ||
  fail 'v0.1.3 release metadata 계약이 올바르지 않습니다.'
release_verify_directory "${release_one}" "${SIGNER}" "${container}"
verify_work_rebind_path_record="${FIXTURE_ROOT}/verify-work-rebind.path"
verify_work_rebind_parked_record="${FIXTURE_ROOT}/verify-work-rebind.parked"
release_after_dmg_verification_cleanup_barrier() {
  local work_dir=$1 parked="${work_dir}.parked"
  /bin/mv -- "${work_dir}" "${parked}" || return 1
  /bin/mkdir -m 700 -- "${work_dir}" || return 1
  printf 'preserve\n' >"${work_dir}/sentinel"
  printf '%s\n' "${work_dir}" >"${verify_work_rebind_path_record}"
  printf '%s\n' "${parked}" >"${verify_work_rebind_parked_record}"
}
set +e
release_verify_directory "${release_one}" "${SIGNER}" "${container}" \
  >"${FIXTURE_ROOT}/verify-work-rebind.log" 2>&1
verify_work_rebind_status=$?
set -e
verify_work_rebind_path=$(/bin/cat -- "${verify_work_rebind_path_record}")
verify_work_rebind_parked=$(/bin/cat -- "${verify_work_rebind_parked_record}")
verify_work_rebind_preserved=0
[[ -f "${verify_work_rebind_path}/sentinel" && -d "${verify_work_rebind_parked}" ]] && \
  verify_work_rebind_preserved=1
/bin/rm -rf -- "${verify_work_rebind_path}" "${verify_work_rebind_parked}"
unset -f release_after_dmg_verification_cleanup_barrier
[[ "${verify_work_rebind_status}" -ne 0 ]] || \
  fail 'DMG verification work same-path replacement cleanup이 성공했습니다.'
[[ "${verify_work_rebind_preserved}" = 1 ]] || \
  fail 'DMG verification work cleanup이 same-path replacement 또는 original evidence를 삭제했습니다.'
if [[ "${SIXBRIDGE_RELEASE_TEST_ATOMIC_WORK_ONLY:-0}" = 1 ]]; then
  printf 'release-lib atomic work cleanup RED/GREEN 검증 통과\n'
  exit 0
fi
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
make_file "${race_directory}/SIX-Bridge-Manager-0.1.3-android.apk" android
make_file "${race_directory}/SIX-Bridge-0.1.3-macos-unsigned.dmg" macos
make_file "${race_directory}/RELEASE-NOTES-KO.md" note
export FAKE_SHASUM_REPLACE_PATH="${race_directory}/SIX-Bridge-Manager-0.1.3-android.apk"
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
