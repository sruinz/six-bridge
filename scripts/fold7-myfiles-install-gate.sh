#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat >&2 <<EOF
사용법:
  $0 pre <adb-serial> <six-bundle>
  $0 post <adb-serial> <wrapper-package>
EOF
    exit 2
}

fail() {
    printf '오류: %s\n' "$1" >&2
    exit "${2:-3}"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "필수 도구를 찾을 수 없습니다: $1" 2
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

readonly MODE=${1:-}
readonly SERIAL=${2:-}
readonly INPUT=${3:-}
readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly EVIDENCE_ROOT="${SIXBRIDGE_EVIDENCE_ROOT:-${ROOT_DIR}/.local/evidence}"
readonly MANAGER_PACKAGE="dev.sixbridge.manager"
readonly MANAGER_INSTALL_AUTHORITY="${MANAGER_PACKAGE}.install"
readonly GOOGLE_INSTALLER="com.google.android.packageinstaller"
readonly SAMSUNG_MYFILES="com.sec.android.app.myfiles"
readonly SAMSUNG_BROWSER="com.sec.android.app.sbrowser"
readonly DEFAULT_MANAGER_APK="${ROOT_DIR}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly MANAGER_APK="${MANAGER_APK:-$DEFAULT_MANAGER_APK}"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly APKANALYZER="${APKANALYZER:-${SDK_ROOT}/cmdline-tools/latest/bin/apkanalyzer}"
readonly APKSIGNER="${APKSIGNER:-${SDK_ROOT}/build-tools/36.0.0/apksigner}"
readonly SAFE_SERIAL=$(printf '%s' "$SERIAL" | tr -c '[:alnum:]_.-' '_')
readonly CURRENT_RUN_FILE="${EVIDENCE_ROOT}/.myfiles-install-gate-current-${SAFE_SERIAL}"
readonly LOCK_DIR="${EVIDENCE_ROOT}/.myfiles-install-gate-lock-${SAFE_SERIAL}"

RUN_DIR=
EXTRACT_DIR=
POINTER_TEMP=
LOCK_ACQUIRED=0

cleanup() {
    if [ -n "$EXTRACT_DIR" ]; then
        case "$EXTRACT_DIR" in
            "${TMPDIR:-/tmp}/sixbridge-myfiles-gate."*)
                /bin/rm -rf -- "$EXTRACT_DIR"
                ;;
        esac
    fi
    if [ -n "$POINTER_TEMP" ]; then
        case "$POINTER_TEMP" in
            "${EVIDENCE_ROOT}/.myfiles-install-gate-current."*)
                /bin/rm -f -- "$POINTER_TEMP"
                ;;
        esac
    fi
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        /bin/rm -f -- "${LOCK_DIR}/owner"
        rmdir "$LOCK_DIR" 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

case "$MODE" in
    pre)
        [ "$#" -eq 3 ] || usage
        ;;
    post)
        [ "$#" -eq 3 ] || usage
        [[ "$INPUT" =~ ^dev\.sixbridge\.ext\.[a-p]{32}$ ]] ||
            fail "wrapper package 형식이 올바르지 않습니다: $INPUT" 2
        ;;
    *)
        usage
        ;;
esac
case "$SERIAL" in
    ""|-*|*[[:space:]]*) usage ;;
esac

ensure_tools() {
    require_command adb
    require_command awk
    require_command rg
    require_command sed
    require_command shasum
    [ -x "$APKANALYZER" ] ||
        fail "apkanalyzer를 찾지 못했습니다: $APKANALYZER" 2
    [ -x "$APKSIGNER" ] ||
        fail "apksigner를 찾지 못했습니다: $APKSIGNER" 2
    if [ "$MODE" = pre ]; then
        require_command jq
        require_command sort
        require_command unzip
        require_command uniq
        require_command zipinfo
    fi
}

ensure_evidence_root() {
    [ ! -L "$EVIDENCE_ROOT" ] ||
        fail "evidence root가 symlink입니다: $EVIDENCE_ROOT"
    mkdir -p "$EVIDENCE_ROOT"
    [ -d "$EVIDENCE_ROOT" ] ||
        fail "evidence root를 준비하지 못했습니다: $EVIDENCE_ROOT"
}

acquire_serial_lock() {
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        fail "같은 serial의 gate가 이미 실행 중입니다: $SERIAL"
    fi
    LOCK_ACQUIRED=1
    printf 'pid=%s\nmode=%s\nserial=%s\n' "$$" "$MODE" "$SERIAL" \
        >"${LOCK_DIR}/owner"
}

new_run_directory() {
    local timestamp candidate suffix
    timestamp=$(date +%Y%m%d-%H%M%S)
    candidate="${EVIDENCE_ROOT}/${timestamp}-myfiles-install-gate"
    suffix=0
    while ! mkdir "$candidate" 2>/dev/null; do
        suffix=$((suffix + 1))
        [ "$suffix" -lt 100 ] ||
            fail "고유한 evidence 디렉터리를 만들지 못했습니다."
        candidate="${EVIDENCE_ROOT}/${timestamp}-myfiles-install-gate.${suffix}"
    done
    RUN_DIR=$candidate
}

publish_current_run() {
    [ ! -L "$CURRENT_RUN_FILE" ] ||
        fail "현재 pre pointer가 symlink입니다: $CURRENT_RUN_FILE"
    POINTER_TEMP=$(mktemp "${EVIDENCE_ROOT}/.myfiles-install-gate-current.XXXXXX")
    printf '%s\n' "$RUN_DIR" >"$POINTER_TEMP"
    /bin/mv -f -- "$POINTER_TEMP" "$CURRENT_RUN_FILE"
    POINTER_TEMP=
}

load_current_run() {
    local line_count
    [ -f "$CURRENT_RUN_FILE" ] && [ ! -L "$CURRENT_RUN_FILE" ] ||
        fail "성공한 My Files pre evidence를 찾지 못했습니다. pre를 먼저 실행하세요."
    line_count=$(wc -l <"$CURRENT_RUN_FILE" | tr -d '[:space:]')
    [ "$line_count" = 1 ] ||
        fail "pre evidence pointer 형식이 올바르지 않습니다."
    RUN_DIR=$(sed -n '1p' "$CURRENT_RUN_FILE")
    case "$RUN_DIR" in
        "${EVIDENCE_ROOT}/"*-myfiles-install-gate*) ;;
        *) fail "pre evidence 경로가 안전하지 않습니다." ;;
    esac
    [ -d "$RUN_DIR" ] && [ ! -L "$RUN_DIR" ] ||
        fail "pre evidence 디렉터리를 찾지 못했습니다: $RUN_DIR"
    [ -f "${RUN_DIR}/baseline.env" ] && [ ! -L "${RUN_DIR}/baseline.env" ] ||
        fail "pre baseline을 찾지 못했습니다: ${RUN_DIR}/baseline.env"
}

baseline_value() {
    local key=$1
    sed -n "s/^${key}=//p" "${RUN_DIR}/baseline.env"
}

manifest_has_extension_id() {
    local manifest=$1
    local expected=$2
    awk -v expected="$expected" '
      /<meta-data/ {
        in_metadata=1
        block=""
      }
      in_metadata {
        block=block "\n" $0
      }
      in_metadata && /\/>/ {
        if (block ~ /android:name="extension-id"/ &&
            index(block, "android:value=\"" expected "\"") > 0) {
          found=1
        }
        in_metadata=0
      }
      END { exit !found }
    ' "$manifest"
}

assert_device() {
    local model
    adb -s "$SERIAL" get-state | tr -d '\r' | grep -Fxq device ||
        fail "Fold7 ADB 연결이 device 상태가 아닙니다: $SERIAL"
    model=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
    [ "$model" = SM-F966N ] ||
        fail "연결 기기가 Fold7 SM-F966N이 아닙니다: ${model:-없음}"
}

assert_manager_contract() {
    local class_name output
    [ -f "$MANAGER_APK" ] && [ ! -L "$MANAGER_APK" ] ||
        fail "manager APK를 찾지 못했습니다: $MANAGER_APK"

    "$APKANALYZER" manifest print "$MANAGER_APK" \
        >"${RUN_DIR}/manager-manifest.xml" ||
        fail "manager APK manifest를 읽지 못했습니다."
    if rg -Fq 'android.permission.REQUEST_INSTALL_PACKAGES' \
        "${RUN_DIR}/manager-manifest.xml"; then
        fail "manager final manifest에 REQUEST_INSTALL_PACKAGES가 남아 있습니다."
    fi
    if rg -Fq "android:authorities=\"${MANAGER_INSTALL_AUTHORITY}\"" \
        "${RUN_DIR}/manager-manifest.xml"; then
        fail "manager final manifest에 직접 설치 Provider가 남아 있습니다."
    fi
    rg -Fq 'android:name="com.sec.android.app.myfiles"' \
        "${RUN_DIR}/manager-manifest.xml" ||
        fail "manager final manifest에 삼성 내 파일 query가 없습니다."

    for class_name in \
        dev.sixbridge.manager.install.SystemInstallCoordinator \
        dev.sixbridge.manager.app.DefaultManagerRepository; do
        output="${RUN_DIR}/${class_name##*.}.dex.txt"
        "$APKANALYZER" dex code --class "$class_name" "$MANAGER_APK" >"$output" ||
            fail "$class_name dex를 읽지 못했습니다."
        if rg -q \
            'PackageInstaller;->createSession|android\.intent\.action\.VIEW|application/vnd\.android\.package-archive|SystemInstallerGateway|InstallHandoffFileProvider' \
            "$output"; then
            fail "$class_name dex에 직접 installer 참조가 남아 있습니다."
        fi
    done
    sha256_file "$MANAGER_APK" >"${RUN_DIR}/manager-apk.sha256"
}

assert_safe_bundle_entries() {
    local bundle=$1
    local entries="${RUN_DIR}/six-entries.txt"
    local listing="${RUN_DIR}/six-listing.txt"
    local expected actual

    zipinfo -1 "$bundle" >"$entries" ||
        fail ".six 중앙 디렉터리를 읽지 못했습니다."
    zipinfo -l "$bundle" >"$listing" ||
        fail ".six entry 속성을 읽지 못했습니다."
    if awk '$1 ~ /^l/ { found=1 } END { exit !found }' "$listing"; then
        fail ".six symlink entry를 허용하지 않습니다."
    fi
    if awk '
      index($0, "\\") || substr($0, 1, 1) == "/" { bad=1 }
      {
        count=split($0, part, "/")
        for (i=1; i<=count; i++) {
          if (part[i] == ".." || part[i] == ".") bad=1
        }
      }
      END { exit !bad }
    ' "$entries"; then
        fail ".six 경로 이탈 entry를 허용하지 않습니다."
    fi
    if LC_ALL=C sort "$entries" | uniq -d | grep -q .; then
        fail ".six 중복 entry를 허용하지 않습니다."
    fi
    expected=$(printf '%s\n' compatibility.json extension.json wrapper.apk)
    actual=$(LC_ALL=C sort "$entries")
    [ "$actual" = "$expected" ] ||
        fail ".six entry 집합은 wrapper.apk, extension.json, compatibility.json이어야 합니다."
}

inspect_bundle() {
    local bundle=$1
    local wrapper descriptor manifest signer_output
    local wrapper_sha package_name version_code signer extension_id
    local descriptor_package descriptor_version descriptor_signer descriptor_sha

    [ -f "$bundle" ] && [ ! -L "$bundle" ] ||
        fail ".six bundle을 찾지 못했거나 symlink입니다: $bundle"
    assert_safe_bundle_entries "$bundle"

    EXTRACT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-myfiles-gate.XXXXXX")
    wrapper="${EXTRACT_DIR}/wrapper.apk"
    descriptor="${EXTRACT_DIR}/extension.json"
    unzip -p "$bundle" wrapper.apk >"$wrapper" ||
        fail ".six wrapper.apk를 추출하지 못했습니다."
    unzip -p "$bundle" extension.json >"$descriptor" ||
        fail ".six extension.json을 추출하지 못했습니다."
    [ -s "$wrapper" ] || fail ".six wrapper.apk가 비어 있습니다."

    jq -e '
      (.extensionId | type == "string" and test("^[a-p]{32}$")) and
      (.wrapperPackage | type == "string") and
      (.wrapperVersionCode | type == "number" and floor == . and . >= 0) and
      (.signerSha256 | type == "string" and test("^[0-9a-fA-F]{64}$")) and
      (.wrapperSha256 | type == "string" and test("^[0-9a-fA-F]{64}$"))
    ' "$descriptor" >/dev/null ||
        fail ".six extension.json 신원 형식이 올바르지 않습니다."
    extension_id=$(jq -er '.extensionId' "$descriptor")
    descriptor_package=$(jq -er '.wrapperPackage' "$descriptor")
    descriptor_version=$(jq -er '.wrapperVersionCode | tostring' "$descriptor")
    descriptor_signer=$(jq -er '.signerSha256 | ascii_downcase' "$descriptor")
    descriptor_sha=$(jq -er '.wrapperSha256 | ascii_downcase' "$descriptor")

    manifest="${RUN_DIR}/baseline-wrapper-manifest.xml"
    signer_output="${RUN_DIR}/baseline-wrapper-apksigner.txt"
    "$APKSIGNER" verify --verbose --print-certs "$wrapper" >"$signer_output" ||
        fail ".six wrapper APK 서명 검증에 실패했습니다."
    "$APKANALYZER" manifest print "$wrapper" >"$manifest" ||
        fail ".six wrapper manifest를 읽지 못했습니다."
    package_name=$("$APKANALYZER" manifest application-id "$wrapper" | tr -d '\r\n')
    version_code=$("$APKANALYZER" manifest version-code "$wrapper" | tr -d '\r\n')
    signer=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
        "$signer_output" | head -n 1 | tr '[:upper:]' '[:lower:]')
    wrapper_sha=$(sha256_file "$wrapper")

    [[ "$package_name" =~ ^dev\.sixbridge\.ext\.[a-p]{32}$ ]] ||
        fail ".six wrapper package 형식이 올바르지 않습니다: $package_name"
    [[ "$version_code" =~ ^[0-9]+$ ]] ||
        fail ".six wrapper versionCode를 읽지 못했습니다."
    [[ "$signer" =~ ^[0-9a-f]{64}$ ]] ||
        fail ".six wrapper signer SHA-256을 읽지 못했습니다."
    [ "$descriptor_package" = "$package_name" ] ||
        fail ".six descriptor와 wrapper package가 다릅니다."
    [ "$descriptor_version" = "$version_code" ] ||
        fail ".six descriptor와 wrapper versionCode가 다릅니다."
    [ "$descriptor_signer" = "$signer" ] ||
        fail ".six descriptor와 wrapper signer가 다릅니다."
    [ "$descriptor_sha" = "$wrapper_sha" ] ||
        fail ".six descriptor와 wrapper SHA-256이 다릅니다."
    [ "${package_name##*.}" = "$extension_id" ] ||
        fail ".six extension ID가 wrapper package suffix와 다릅니다."
    manifest_has_extension_id "$manifest" "$extension_id" ||
        fail ".six wrapper manifest extension ID가 descriptor와 다릅니다."

    cp "$wrapper" "${RUN_DIR}/baseline-wrapper.apk"
    printf '%s\n' "$bundle" >"${RUN_DIR}/six-bundle-path.txt"
    cat >"${RUN_DIR}/baseline.env" <<EOF
serial=$SERIAL
package=$package_name
versionCode=$version_code
signerSha256=$signer
extensionId=$extension_id
wrapperSha256=$wrapper_sha
preDeviceEpoch=$(tr -d '\r\n' <"${RUN_DIR}/pre-device-epoch.txt")
EOF
}

capture_pre_target() {
    local package_name package_path last_update code_path base_stat
    package_name=$(baseline_value package)
    adb -s "$SERIAL" shell dumpsys package "$package_name" \
        >"${RUN_DIR}/pre-target-package.txt"
    if ! adb -s "$SERIAL" shell pm path "$package_name" \
        >"${RUN_DIR}/pre-target-path.txt" 2>&1; then
        cat >>"${RUN_DIR}/baseline.env" <<EOF
preTargetPresent=0
preLastUpdateTime=absent
preCodePath=absent
preBaseApkPath=absent
preBaseApkStat=absent
EOF
        return
    fi
    package_path=$(sed -n 's/\r$//; s/^package://p' \
        "${RUN_DIR}/pre-target-path.txt" |
        awk '/\/base\.apk$/ { print; exit }')
    [ -n "$package_path" ] ||
        fail "pre target base.apk 경로를 읽지 못했습니다: $package_name"
    last_update=$(sed -n 's/^[[:space:]]*lastUpdateTime=//p' \
        "${RUN_DIR}/pre-target-package.txt" | tail -n 1 | tr -d '\r')
    code_path=$(sed -n 's/^[[:space:]]*codePath=//p' \
        "${RUN_DIR}/pre-target-package.txt" | tail -n 1 | tr -d '\r')
    [ -n "$last_update" ] ||
        fail "pre target lastUpdateTime을 읽지 못했습니다."
    [ -n "$code_path" ] ||
        fail "pre target codePath를 읽지 못했습니다."
    base_stat=$(adb -s "$SERIAL" shell stat -c '%i:%s:%Y' "$package_path" |
        tr -d '\r\n')
    [[ "$base_stat" =~ ^[0-9]+:[0-9]+:[0-9]+$ ]] ||
        fail "pre target base.apk stat을 읽지 못했습니다."
    cat >>"${RUN_DIR}/baseline.env" <<EOF
preTargetPresent=1
preLastUpdateTime=$last_update
preCodePath=$code_path
preBaseApkPath=$package_path
preBaseApkStat=$base_stat
EOF
}

mark_pre_log_boundary() {
    local nonce marker_line marker_epoch
    nonce="pre:${SAFE_SERIAL}:$(basename "$RUN_DIR")"
    adb -s "$SERIAL" shell log -t SixBridgeMyFilesGate "$nonce" ||
        fail "Fold7 pre log marker를 기록하지 못했습니다."
    adb -s "$SERIAL" logcat -d -v epoch \
        >"${RUN_DIR}/pre-log-marker-capture.txt"
    marker_line=$(rg -F "SixBridgeMyFilesGate: ${nonce}" \
        "${RUN_DIR}/pre-log-marker-capture.txt" | tail -n 1)
    [ -n "$marker_line" ] ||
        fail "Fold7 pre log marker를 다시 읽지 못했습니다."
    marker_epoch=$(printf '%s\n' "$marker_line" | awk '{print $1}')
    [[ "$marker_epoch" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
        fail "Fold7 pre log marker epoch 형식이 올바르지 않습니다."
    printf '%s\n' "$marker_line" >"${RUN_DIR}/pre-log-marker.txt"
    cat >>"${RUN_DIR}/baseline.env" <<EOF
preLogNonce=$nonce
preLogEpoch=$marker_epoch
EOF
}

run_pre() {
    local package_name version_code signer extension_id wrapper_sha
    [ ! -e "$CURRENT_RUN_FILE" ] ||
        { [ -f "$CURRENT_RUN_FILE" ] && [ ! -L "$CURRENT_RUN_FILE" ]; } ||
        fail "현재 pre pointer가 안전한 일반 파일이 아닙니다: $CURRENT_RUN_FILE"
    new_run_directory
    assert_manager_contract
    assert_device
    adb -s "$SERIAL" shell date +%s \
        >"${RUN_DIR}/pre-device-epoch.txt"
    grep -Eq '^[0-9]+\r?$' "${RUN_DIR}/pre-device-epoch.txt" ||
        fail "Fold7 device epoch를 읽지 못했습니다."
    inspect_bundle "$INPUT"
    capture_pre_target
    mark_pre_log_boundary
    package_name=$(baseline_value package)
    version_code=$(baseline_value versionCode)
    signer=$(baseline_value signerSha256)
    extension_id=$(baseline_value extensionId)
    wrapper_sha=$(baseline_value wrapperSha256)
    cat >"${RUN_DIR}/pre-summary.txt" <<EOF
mode=pre
serial=$SERIAL
bundle=$INPUT
package=$package_name
versionCode=$version_code
signerSha256=$signer
extensionId=$extension_id
wrapperSha256=$wrapper_sha
managerDirectInstallerSurface=absent
myFilesQuery=present
EOF
    publish_current_run
    cat "${RUN_DIR}/pre-summary.txt"
    printf 'preparedApk=%s\n' "${RUN_DIR}/baseline-wrapper.apk"
    printf 'evidence=%s\n' "$RUN_DIR"
}

source_value() {
    local key=$1
    local file=$2
    sed -n "s/.*${key}=\\([^, }[:space:]]*\\).*/\\1/p" "$file" | tail -n 1
}

extract_installed_apk() {
    local package_name=$1
    local package_path
    adb -s "$SERIAL" shell pm path "$package_name" \
        >"${RUN_DIR}/wrapper-package-path.txt"
    package_path=$(sed -n 's/\r$//; s/^package://p' \
        "${RUN_DIR}/wrapper-package-path.txt" |
        awk '/\/base\.apk$/ { print; exit }')
    [ -n "$package_path" ] ||
        fail "설치된 wrapper base.apk 경로를 찾지 못했습니다: $package_name"
    adb -s "$SERIAL" shell stat -c '%i:%s:%Y' "$package_path" \
        >"${RUN_DIR}/installed-base-stat.txt" ||
        fail "설치된 wrapper base.apk stat을 읽지 못했습니다."
    tr -d '\r\n' <"${RUN_DIR}/installed-base-stat.txt" |
        grep -Eq '^[0-9]+:[0-9]+:[0-9]+$' ||
        fail "설치된 wrapper base.apk stat 형식이 올바르지 않습니다."
    adb -s "$SERIAL" exec-out cat "$package_path" \
        >"${RUN_DIR}/installed-wrapper.apk" ||
        fail "설치된 wrapper APK를 회수하지 못했습니다."
    [ -s "${RUN_DIR}/installed-wrapper.apk" ] ||
        fail "회수한 wrapper APK가 비어 있습니다."
}

inspect_installed_identity() {
    local expected_package expected_version expected_signer expected_extension
    local expected_sha package_name version_code signer extension_id installed_sha
    local manifest="${RUN_DIR}/installed-wrapper-manifest.xml"
    local signer_output="${RUN_DIR}/installed-wrapper-apksigner.txt"
    expected_package=$(baseline_value package)
    expected_version=$(baseline_value versionCode)
    expected_signer=$(baseline_value signerSha256)
    expected_extension=$(baseline_value extensionId)
    expected_sha=$(baseline_value wrapperSha256)
    installed_sha=$(sha256_file "${RUN_DIR}/installed-wrapper.apk")
    printf '%s\n' "$installed_sha" >"${RUN_DIR}/installed-wrapper.sha256"
    [ "$installed_sha" = "$expected_sha" ] ||
        fail "설치 APK SHA-256이 pre baseline과 다릅니다."

    "$APKSIGNER" verify --verbose --print-certs \
        "${RUN_DIR}/installed-wrapper.apk" >"$signer_output" ||
        fail "설치 APK 서명 검증에 실패했습니다."
    "$APKANALYZER" manifest print \
        "${RUN_DIR}/installed-wrapper.apk" >"$manifest" ||
        fail "설치 APK manifest를 읽지 못했습니다."
    package_name=$("$APKANALYZER" manifest application-id \
        "${RUN_DIR}/installed-wrapper.apk" | tr -d '\r\n')
    version_code=$("$APKANALYZER" manifest version-code \
        "${RUN_DIR}/installed-wrapper.apk" | tr -d '\r\n')
    signer=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
        "$signer_output" | head -n 1 | tr '[:upper:]' '[:lower:]')
    extension_id=${expected_package##*.}
    manifest_has_extension_id "$manifest" "$extension_id" ||
        fail "설치 APK extension ID를 확인하지 못했습니다."

    [ "$package_name" = "$expected_package" ] ||
        fail "설치 APK package가 pre baseline과 다릅니다."
    [ "$version_code" = "$expected_version" ] ||
        fail "설치 APK versionCode가 pre baseline과 다릅니다."
    [ "$signer" = "$expected_signer" ] ||
        fail "설치 APK signer가 pre baseline과 다릅니다."
    [ "$extension_id" = "$expected_extension" ] ||
        fail "설치 APK extension ID가 pre baseline과 다릅니다."
}

capture_install_source() {
    local package_name=$1
    local installer initiating originating package_source
    adb -s "$SERIAL" shell dumpsys package "$package_name" \
        >"${RUN_DIR}/wrapper-package.txt"
    installer=$(source_value installerPackageName "${RUN_DIR}/wrapper-package.txt")
    [ -n "$installer" ] ||
        installer=$(source_value installingPackageName "${RUN_DIR}/wrapper-package.txt")
    initiating=$(source_value initiatingPackageName "${RUN_DIR}/wrapper-package.txt")
    originating=$(source_value originatingPackageName "${RUN_DIR}/wrapper-package.txt")
    package_source=$(source_value packageSource "${RUN_DIR}/wrapper-package.txt")
    cat >"${RUN_DIR}/install-source.txt" <<EOF
installer=$installer
initiating=$initiating
originating=$originating
packageSource=$package_source
EOF
    [ "$installer" = "$GOOGLE_INSTALLER" ] ||
        fail "Google package installer 설치 기록이 아닙니다: ${installer:-없음}"
    [ "$initiating" = "$GOOGLE_INSTALLER" ] ||
        fail "Google package installer initiating 기록이 아닙니다: ${initiating:-없음}"
    [ "$originating" = "$SAMSUNG_MYFILES" ] ||
        fail "삼성 내 파일 origin이 아닙니다."
    [ "$package_source" = 3 ] ||
        fail "packageSource가 사용자 설치 값 3이 아닙니다."
}

assert_install_freshness() {
    local pre_present pre_last_update pre_code_path pre_base_path pre_base_stat
    local post_last_update post_code_path post_base_path post_base_stat
    pre_present=$(baseline_value preTargetPresent)
    pre_last_update=$(baseline_value preLastUpdateTime)
    pre_code_path=$(baseline_value preCodePath)
    pre_base_path=$(baseline_value preBaseApkPath)
    pre_base_stat=$(baseline_value preBaseApkStat)
    post_last_update=$(sed -n 's/^[[:space:]]*lastUpdateTime=//p' \
        "${RUN_DIR}/wrapper-package.txt" | tail -n 1 | tr -d '\r')
    post_code_path=$(sed -n 's/^[[:space:]]*codePath=//p' \
        "${RUN_DIR}/wrapper-package.txt" | tail -n 1 | tr -d '\r')
    post_base_path=$(sed -n 's/\r$//; s/^package://p' \
        "${RUN_DIR}/wrapper-package-path.txt" |
        awk '/\/base\.apk$/ { print; exit }')
    post_base_stat=$(tr -d '\r\n' <"${RUN_DIR}/installed-base-stat.txt")
    [ -n "$post_last_update" ] ||
        fail "post target lastUpdateTime을 읽지 못했습니다."
    [ -n "$post_code_path" ] ||
        fail "post target codePath를 읽지 못했습니다."
    case "$pre_present" in
        0)
            ;;
        1)
            if [ "$post_last_update" = "$pre_last_update" ] &&
                [ "$post_code_path" = "$pre_code_path" ] &&
                [ "$post_base_path" = "$pre_base_path" ] &&
                [ "$post_base_stat" = "$pre_base_stat" ]; then
                fail "target 설치 세대가 pre 이후 변경되지 않았습니다."
            fi
            ;;
        *)
            fail "pre target 설치 상태 baseline이 올바르지 않습니다."
            ;;
    esac
    cat >"${RUN_DIR}/install-freshness.txt" <<EOF
preTargetPresent=$pre_present
preLastUpdateTime=$pre_last_update
preCodePath=$pre_code_path
preBaseApkPath=$pre_base_path
preBaseApkStat=$pre_base_stat
postLastUpdateTime=$post_last_update
postCodePath=$post_code_path
postBaseApkPath=$post_base_path
postBaseApkStat=$post_base_stat
installGeneration=fresh
EOF
}

capture_samsung_logs() {
    local marker_epoch nonce package_name extension_id
    marker_epoch=$(baseline_value preLogEpoch)
    nonce=$(baseline_value preLogNonce)
    package_name=$(baseline_value package)
    extension_id=$(baseline_value extensionId)
    [[ "$marker_epoch" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
        fail "pre log marker epoch baseline이 올바르지 않습니다."
    [ -n "$nonce" ] ||
        fail "pre log marker nonce baseline이 없습니다."
    adb -s "$SERIAL" logcat -d -v epoch >"${RUN_DIR}/logcat-raw.txt"
    rg -Fq "SixBridgeMyFilesGate: ${nonce}" "${RUN_DIR}/logcat-raw.txt" ||
        fail "pre log marker가 post logcat에 남아 있지 않습니다."
    awk -v start="$marker_epoch" '$1 + 0 > start + 0' \
        "${RUN_DIR}/logcat-raw.txt" >"${RUN_DIR}/logcat-since-pre.txt"
    rg 'SixPkgUpdater|SixAppDataUpdater' "${RUN_DIR}/logcat-since-pre.txt" \
        >"${RUN_DIR}/samsung-extension.log" || true
    if ! rg -Fq \
        "SixPkgUpdater: isDevSixPkg - MyFiles case: ${package_name}" \
        "${RUN_DIR}/samsung-extension.log" ||
        ! rg -Fq \
            "SixAppDataUpdater: updateSixAppData - Allowed to be supported: ${extension_id}" \
            "${RUN_DIR}/samsung-extension.log"; then
        fail "target-bound 삼성 지원 로그를 찾지 못했습니다."
    fi
}

capture_discovery() {
    local package_name=$1
    adb -s "$SERIAL" shell cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.VIEW \
        -d internet://extension \
        -p "$SAMSUNG_BROWSER" >"${RUN_DIR}/samsung-addons-resolve.txt"
    rg -Fq "${SAMSUNG_BROWSER}/" "${RUN_DIR}/samsung-addons-resolve.txt" ||
        fail "Samsung Internet Add-ons Activity를 resolve하지 못했습니다."
    adb -s "$SERIAL" shell cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.VIEW \
        -d six://ex \
        -p "$package_name" >"${RUN_DIR}/six-discovery-resolve.txt"
    rg -Fq "${package_name}/" "${RUN_DIR}/six-discovery-resolve.txt" ||
        fail "wrapper six://ex discovery Activity를 resolve하지 못했습니다."
}

consume_current_run() {
    local current
    current=$(sed -n '1p' "$CURRENT_RUN_FILE")
    [ "$current" = "$RUN_DIR" ] ||
        fail "소비할 pre pointer가 현재 evidence와 다릅니다."
    [ ! -e "${RUN_DIR}/post-consumed-pointer" ] ||
        fail "pre pointer가 이미 소비됐습니다."
    /bin/mv -- "$CURRENT_RUN_FILE" "${RUN_DIR}/post-consumed-pointer" ||
        fail "성공한 post의 pre pointer를 소비하지 못했습니다."
    printf 'completed\n' >"${RUN_DIR}/post-completed.txt"
}

run_post() {
    local expected_serial expected_package installer originating
    load_current_run
    expected_serial=$(baseline_value serial)
    expected_package=$(baseline_value package)
    [ "$expected_serial" = "$SERIAL" ] ||
        fail "pre/post ADB serial이 다릅니다."
    [ "$expected_package" = "$INPUT" ] ||
        fail "post wrapper package가 pre baseline과 다릅니다."
    assert_device
    capture_install_source "$INPUT"
    extract_installed_apk "$INPUT"
    assert_install_freshness
    inspect_installed_identity
    capture_samsung_logs
    capture_discovery "$INPUT"
    installer=$(sed -n 's/^installer=//p' "${RUN_DIR}/install-source.txt")
    originating=$(sed -n 's/^originating=//p' "${RUN_DIR}/install-source.txt")
    cat >"${RUN_DIR}/post-summary.txt" <<EOF
mode=post
serial=$SERIAL
package=$INPUT
installer=$installer
originating=$originating
classification=dev_ext
support=Allowed to be supported
wrapperByteIdentity=matched
EOF
    consume_current_run
    cat "${RUN_DIR}/post-summary.txt"
    printf 'evidence=%s\n' "$RUN_DIR"
}

ensure_tools
ensure_evidence_root
acquire_serial_lock
case "$MODE" in
    pre) run_pre ;;
    post) run_post ;;
esac
