#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-ondevice-packager-gate.sh"
readonly EXTENSION_ID="abcdefghijklmnopabcdefghijklmnop"
readonly PACKAGE_NAME="dev.sixbridge.ext.${EXTENSION_ID}"
readonly SIGNER_SHA256="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file=$1
    local expected=$2
    local message=$3
    grep -Fq -- "$expected" "$file" || fail "$message"
}

assert_exit() {
    local expected=$1
    shift
    local output
    output=$(mktemp "${TMPDIR:-/tmp}/sixbridge-ondevice-gate-output.XXXXXX")
    if "$@" >"$output" 2>&1; then
        actual=0
    else
        actual=$?
    fi
    [ "$actual" = "$expected" ] || {
        cat "$output" >&2
        fail "exit code가 ${expected}이어야 합니다. 실제: ${actual}"
    }
    ASSERT_OUTPUT=$output
}

make_apk() {
    local output=$1
    local version_marker=$2
    local resource_mode=$3
    local resource_alignment=$4
    local work="${FIXTURE_ROOT}/apk-${version_marker}-${resource_mode}-${resource_alignment}"
    rm -rf -- "$work"
    rm -f -- "$output"
    mkdir -p "$work/assets"
    printf 'Cr24 fixture extension\n' >"$work/assets/extension.crx"
    printf 'resource-%s\n' "$version_marker" >"$work/resources.arsc"
    printf 'version=%s\n' "$version_marker" >"$work/version.txt"
    if [ "$resource_mode" = compressed ]; then
        awk 'BEGIN { for (i = 0; i < 128; i++) print "compressible-resource" }' \
            >"$work/resources.arsc"
    fi
    if [ "$resource_alignment" = unaligned ]; then
        printf x >"$work/pad"
        (cd "$work" && zip -q -0 "$output" pad)
        (cd "$work" && zip -q "${output}" resources.arsc assets/extension.crx version.txt)
    elif [ "$resource_mode" = compressed ]; then
        (cd "$work" && zip -q "$output" resources.arsc assets/extension.crx version.txt)
    else
        (cd "$work" && zip -q -0 "$output" resources.arsc assets/extension.crx version.txt)
    fi
}

write_state() {
    local state_file=$1
    local v1=$2
    local v2=$3
    local package_name=${4:-$PACKAGE_NAME}
    local v1_version=${5:-1}
    local v2_version=${6:-2}
    local crx_sha
    local v1_sha
    local v2_sha
    crx_sha=$(unzip -p "$v1" assets/extension.crx | shasum -a 256 | awk '{print $1}')
    v1_sha=$(shasum -a 256 "$v1" | awk '{print $1}')
    v2_sha=$(shasum -a 256 "$v2" | awk '{print $1}')
    cat >"${FIXTURE_ROOT}/device/gate-state.json" <<EOF
{"schemaVersion":1,"extensionId":"${EXTENSION_ID}","packageName":"${package_name}","wrapperSignerSha256":"${SIGNER_SHA256}","crxSha256":"${crx_sha}","v1Sha256":"${v1_sha}"}
EOF
    cat >"${FIXTURE_ROOT}/device/gate-result.json" <<EOF
{"schemaVersion":1,"v1":{"file":"gate-v1.apk","sha256":"${v1_sha}","versionCode":${v1_version}},"v2":{"file":"gate-v2.apk","sha256":"${v2_sha}","versionCode":${v2_version}}}
EOF
    cp "$v1" "${FIXTURE_ROOT}/device/gate-v1.apk"
    cp "$v2" "${FIXTURE_ROOT}/device/gate-v2.apk"
    printf '%s\n' "$crx_sha" >"$state_file"
}

make_fixture() {
    FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-ondevice-gate.XXXXXX")
    mkdir -p \
        "${FIXTURE_ROOT}/scripts" \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0" \
        "${FIXTURE_ROOT}/bin" \
        "${FIXTURE_ROOT}/device" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug" \
        "${FIXTURE_ROOT}/.local"
    cp "$GATE_SCRIPT" "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh"
    cp "${ROOT_DIR}/scripts/fold7-validation-lib.sh" "${FIXTURE_ROOT}/scripts/fold7-validation-lib.sh"
    chmod +x "${FIXTURE_ROOT}/scripts/"*.sh
    printf '#!/bin/sh\nexit 0\n' >"${FIXTURE_ROOT}/gradlew"
    chmod +x "${FIXTURE_ROOT}/gradlew"
    : >"${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
    : >"${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"
    make_apk "${FIXTURE_ROOT}/device/source-v1.apk" v1 stored aligned
    make_apk "${FIXTURE_ROOT}/device/source-v2.apk" v2 stored aligned
    write_state "${FIXTURE_ROOT}/device/crx.sha" \
        "${FIXTURE_ROOT}/device/source-v1.apk" "${FIXTURE_ROOT}/device/source-v2.apk"

    cat >"${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "$1" = verify ]; then
  printf 'Verified using v1 scheme (JAR signing): false\n'
  printf 'Verified using v2 scheme (APK Signature Scheme v2): %s\n' "${APKSIGNER_V2:-true}"
  printf 'Verified using v3 scheme (APK Signature Scheme v3): %s\n' "${APKSIGNER_V3:-true}"
  printf 'Signer #1 certificate SHA-256 digest: %s\n' "${SIXBRIDGE_TEST_SIGNER}"
  exit 0
fi
exit 2
EOF
    cat >"${FIXTURE_ROOT}/sdk/build-tools/36.0.0/aapt2" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
apk=''
for argument in "$@"; do
  case "$argument" in *.apk) apk=$argument;; esac
done
case "$*" in
  *'dump badging'*)
    version=1
    case "$apk" in *gate-v2.apk) version=2;; esac
    printf "package: name='%s' versionCode='%s' versionName='1.0'\n" "$SIXBRIDGE_TEST_PACKAGE" "$version"
    ;;
  *'dump xmltree'*)
    printf 'extension-id "%s"\n' "$SIXBRIDGE_TEST_EXTENSION"
    printf 'authority "%s.contentprovider"\n' "$SIXBRIDGE_TEST_PACKAGE"
    printf 'scheme="six" host="ex"\n'
    ;;
  *'dump resources'*) :;;
  *) exit 2;;
esac
EOF
    cat >"${FIXTURE_ROOT}/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
log="$SIXBRIDGE_TEST_ADB_LOG"
printf '%s\n' "$*" >>"$log"
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
  get-state)
    [ "${ADB_STATE:-device}" = device ] && printf 'device\n' || printf '%s\n' "${ADB_STATE}"
    ;;
  install)
    printf 'Success\n'
    ;;
  exec-out)
    shift
    case "${*: -1}" in
      *gate-v1.apk) cat "$SIXBRIDGE_TEST_DEVICE/gate-v1.apk";;
      *gate-v2.apk) cat "$SIXBRIDGE_TEST_DEVICE/gate-v2.apk";;
      *gate-state.json) cat "$SIXBRIDGE_TEST_DEVICE/gate-state.json";;
      *gate-result.json) cat "$SIXBRIDGE_TEST_DEVICE/gate-result.json";;
      *) exit 2;;
    esac
    ;;
  shell)
    shift
    case "$*" in
      *'am instrument'*)
        case "$*" in
          *generateV1*) [ "${INSTRUMENT_FAIL:-}" = V1 ] && exit 1;;
          *generateV2*) [ "${INSTRUMENT_FAIL:-}" = V2 ] && exit 1;;
        esac
        printf 'OK (1 test)\n'
        ;;
      *'pm path'*) [ "${PACKAGE_EXISTS:-0}" = 1 ] && printf 'package:/data/app/%s/base.apk\n' "$SIXBRIDGE_TEST_PACKAGE";;
      *'dumpsys package'*) printf 'versionCode=2\n';;
      *'resolve-activity'*) printf '%s/dev.sixbridge.runtime.SixDiscoveryActivity\n' "$SIXBRIDGE_TEST_PACKAGE";;
      *'content read'*) printf '%s' "${PROVIDER_MAGIC:-Cr24}";;
      *'am start'*) printf 'Starting: Intent\n';;
      *'logcat'*) :;;
      *) exit 2;;
    esac
    ;;
  *) exit 2;;
esac
EOF
    chmod +x \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner" \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0/aapt2" \
        "${FIXTURE_ROOT}/bin/adb"
}

run_gate() {
    local output=$1
    shift
    if [ "$output" = /dev/null ]; then
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        ANDROID_HOME="${FIXTURE_ROOT}/sdk" \
        SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" \
        SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" \
        SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" \
        bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" "$@"
    else
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        ANDROID_HOME="${FIXTURE_ROOT}/sdk" \
        SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" \
        SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" \
        SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" \
        bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" "$@" >"$output" 2>&1
    fi
}

expect_failure() {
    local expected=$1
    shift
    local output
    output=$(mktemp "${TMPDIR:-/tmp}/sixbridge-ondevice-gate-failure.XXXXXX")
    if "$@" >"$output" 2>&1; then
        cat "$output" >&2
        fail "실패해야 하는 gate가 성공했습니다: $expected"
    fi
    assert_contains "$output" "$expected" "예상 거부 이유가 없습니다: $expected"
    rm -f -- "$output"
}

main() {
    [[ -x "$GATE_SCRIPT" ]] || fail "host gate script가 없습니다: $GATE_SCRIPT"

    make_fixture
    trap 'rm -rf -- "${FIXTURE_ROOT:-}"' EXIT

    assert_exit 2 bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh"
    assert_contains "$ASSERT_OUTPUT" 'Usage:' '인자 없음 사용법이 없습니다.'
    rm -f -- "$ASSERT_OUTPUT"
    assert_exit 2 bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" --bad
    rm -f -- "$ASSERT_OUTPUT"

    output="${FIXTURE_ROOT}/gate.log"
    run_gate "$output" serial-1
    assert_contains "$output" 'Host-side checks passed.' '정상 host gate가 성공하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/adb.log" 'install' 'v1/v2 install이 실행되지 않았습니다.'
    grep -q ' install .*gate-v1.apk' "${FIXTURE_ROOT}/adb.log" || fail 'v1 install이 없습니다.'
    grep -q ' install -r .*gate-v2.apk' "${FIXTURE_ROOT}/adb.log" || fail 'v2 install -r이 없습니다.'

    expect_failure '기기가 offline 상태입니다.' env ADB_STATE=offline PATH="${FIXTURE_ROOT}/bin:${PATH}" ANDROID_HOME="${FIXTURE_ROOT}/sdk" SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" serial-1
    expect_failure 'V1 instrumentation' env INSTRUMENT_FAIL=V1 PATH="${FIXTURE_ROOT}/bin:${PATH}" ANDROID_HOME="${FIXTURE_ROOT}/sdk" SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" serial-1

    original_state=$(cat "${FIXTURE_ROOT}/device/gate-state.json")
    jq '.v1Sha256 = ("0" * 64)' "${FIXTURE_ROOT}/device/gate-state.json" >"${FIXTURE_ROOT}/device/state.tmp"
    mv "${FIXTURE_ROOT}/device/state.tmp" "${FIXTURE_ROOT}/device/gate-state.json"
    expect_failure 'v1 APK SHA-256' run_gate /dev/null serial-1
    printf '%s' "$original_state" >"${FIXTURE_ROOT}/device/gate-state.json"

    jq '.packageName = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' "${FIXTURE_ROOT}/device/gate-state.json" >"${FIXTURE_ROOT}/device/state.tmp"
    mv "${FIXTURE_ROOT}/device/state.tmp" "${FIXTURE_ROOT}/device/gate-state.json"
    expect_failure 'packageName' run_gate /dev/null serial-1
    printf '%s' "$original_state" >"${FIXTURE_ROOT}/device/gate-state.json"

    original_result=$(cat "${FIXTURE_ROOT}/device/gate-result.json")
    jq '.v2.versionCode = 7' "${FIXTURE_ROOT}/device/gate-result.json" >"${FIXTURE_ROOT}/device/result.tmp"
    mv "${FIXTURE_ROOT}/device/result.tmp" "${FIXTURE_ROOT}/device/gate-result.json"
    expect_failure 'versionCode' run_gate /dev/null serial-1
    printf '%s' "$original_result" >"${FIXTURE_ROOT}/device/gate-result.json"

    expect_failure 'APK Signature Scheme v3' env APKSIGNER_V3=false PATH="${FIXTURE_ROOT}/bin:${PATH}" ANDROID_HOME="${FIXTURE_ROOT}/sdk" SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" serial-1

    make_apk "${FIXTURE_ROOT}/device/source-v1.apk" v1 compressed aligned
    write_state "${FIXTURE_ROOT}/device/crx.sha" "${FIXTURE_ROOT}/device/source-v1.apk" "${FIXTURE_ROOT}/device/source-v2.apk"
    expect_failure 'resources.arsc는 STORED' run_gate /dev/null serial-1
    make_apk "${FIXTURE_ROOT}/device/source-v1.apk" v1 stored unaligned
    write_state "${FIXTURE_ROOT}/device/crx.sha" "${FIXTURE_ROOT}/device/source-v1.apk" "${FIXTURE_ROOT}/device/source-v2.apk"
    expect_failure 'resources.arsc data offset' run_gate /dev/null serial-1
    make_apk "${FIXTURE_ROOT}/device/source-v1.apk" v1 stored aligned
    write_state "${FIXTURE_ROOT}/device/crx.sha" "${FIXTURE_ROOT}/device/source-v1.apk" "${FIXTURE_ROOT}/device/source-v2.apk"

    expect_failure 'ContentProvider' env PROVIDER_MAGIC=NOPE PATH="${FIXTURE_ROOT}/bin:${PATH}" ANDROID_HOME="${FIXTURE_ROOT}/sdk" SIXBRIDGE_TEST_DEVICE="${FIXTURE_ROOT}/device" SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-packager-gate.sh" serial-1

    rm -rf -- "${FIXTURE_ROOT}/.local/evidence"
    ln -s "${FIXTURE_ROOT}/device" "${FIXTURE_ROOT}/.local/evidence"
    expect_failure '안전한 evidence 디렉터리' run_gate /dev/null serial-1
    rm -f -- "${FIXTURE_ROOT}/.local/evidence"

    printf 'PASS: Fold7 온디바이스 packager host gate 계약\n'
}

main "$@"
