#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-ondevice-conversion-flow.sh"
readonly EXTENSION_ID="abcdefghijklmnopabcdefghijklmnop"
readonly PACKAGE_NAME="dev.sixbridge.ext.${EXTENSION_ID}"
readonly SIGNER_SHA256="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
readonly OTHER_SIGNER_SHA256="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
readonly SOURCE_SHA256="1111111111111111111111111111111111111111111111111111111111111111"
readonly OTHER_SOURCE_SHA256="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
readonly RESULT_SHA256="2222222222222222222222222222222222222222222222222222222222222222"
readonly PLAN_SHA256="3333333333333333333333333333333333333333333333333333333333333333"
readonly PROFILE_SHA256="4444444444444444444444444444444444444444444444444444444444444444"

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

assert_not_matches() {
    local file=$1
    local pattern=$2
    local message=$3
    if grep -Eq -- "$pattern" "$file"; then
        fail "$message"
    fi
}

expect_failure() {
    local expected=$1
    local label=$2
    shift 2
    local output="${FIXTURE_ROOT}/${label}.log"
    if "$@" >"$output" 2>&1; then
        cat "$output" >&2
        fail "실패해야 하는 gate가 성공했습니다: $label"
    fi
    if ! grep -Fq -- "$expected" "$output"; then
        cat "$output" >&2
        fail "예상 거부 이유가 없습니다: $label"
    fi
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

make_wrapper() {
    local phase=$1
    local work="${FIXTURE_ROOT}/wrapper-${phase}"
    mkdir -p "${work}/assets"
    printf 'Cr24 fixture %s\n' "$phase" >"${work}/assets/extension.crx"
    printf 'wrapper %s\n' "$phase" >"${work}/marker"
    (
        cd "$work"
        zip -q "${FIXTURE_ROOT}/device/${phase}-wrapper.apk" \
            assets/extension.crx marker
    )
}

write_phase() {
    local phase=$1
    local version=$2
    local source_sha=${3:-$SOURCE_SHA256}
    local wrapper="${FIXTURE_ROOT}/device/${phase}-wrapper.apk"
    local bundle="${FIXTURE_ROOT}/device/${phase}.six"
    local bundle_work="${FIXTURE_ROOT}/bundle-${phase}"
    local wrapper_sha
    local crx_sha
    local six_sha
    wrapper_sha=$(sha256_file "$wrapper")
    crx_sha=$(unzip -p "$wrapper" assets/extension.crx | shasum -a 256 | awk '{print $1}')
    cat >"${FIXTURE_ROOT}/device/extension.json" <<EOF
{"schemaVersion":1,"extensionId":"${EXTENSION_ID}","name":"스마트 컨텍스트 메뉴","version":"1.0","sourceKind":"ZIP","crxSha256":"${crx_sha}","wrapperSha256":"${wrapper_sha}","wrapperPackage":"${PACKAGE_NAME}","wrapperVersionCode":${version},"signerSha256":"${SIGNER_SHA256}"}
EOF
    cat >"${FIXTURE_ROOT}/device/compatibility.json" <<EOF
{"schemaVersion":4,"status":"COMPATIBLE","manifestVersion":3,"extensionName":"스마트 컨텍스트 메뉴","extensionVersion":"1.0","manifestKeyPresent":false,"manifestKeyExtensionId":null,"declaredPermissions":[],"usedApis":["runtime"],"unsupportedApis":[],"issues":[],"conversionState":"converted","adapterDecisions":[],"originalExtensionId":null,"convertedExtensionId":"${EXTENSION_ID}","sourceSha256":"${RESULT_SHA256}","browserProfileSha256":"${PROFILE_SHA256}","originalSourceSha256":"${source_sha}","planSha256":"${PLAN_SHA256}"}
EOF
    mkdir -p "$bundle_work"
    cp "$wrapper" "${bundle_work}/wrapper.apk"
    cp "${FIXTURE_ROOT}/device/extension.json" "$bundle_work"
    cp "${FIXTURE_ROOT}/device/compatibility.json" "$bundle_work"
    (
        cd "$bundle_work"
        zip -q -0 "${phase}.six" \
            wrapper.apk extension.json compatibility.json
    )
    mv "${bundle_work}/${phase}.six" "$bundle"
    six_sha=$(sha256_file "$bundle")
    cat >"${FIXTURE_ROOT}/device/${phase}-result.json" <<EOF
{"schemaVersion":1,"phase":"${phase}","sourceSha256":"${source_sha}","resultSha256":"${RESULT_SHA256}","planSha256":"${PLAN_SHA256}","profileSha256":"${PROFILE_SHA256}","extensionId":"${EXTENSION_ID}","packageName":"${PACKAGE_NAME}","signerSha256":"${SIGNER_SHA256}","versionCode":${version},"sixFile":"${phase}.six","sixSha256":"${six_sha}","wrapperFile":"${phase}-wrapper.apk","wrapperSha256":"${wrapper_sha}","crxSha256":"${crx_sha}"}
EOF
}

make_fixture() {
    FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-ondevice-flow.XXXXXX")
    FIXTURE_ROOT=$(CDPATH= cd -- "$FIXTURE_ROOT" && pwd -P)
    mkdir -p \
        "${FIXTURE_ROOT}/scripts" \
        "${FIXTURE_ROOT}/bin" \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug" \
        "${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug" \
        "${FIXTURE_ROOT}/cli/build/install/sixbridge/bin" \
        "${FIXTURE_ROOT}/device" \
        "${FIXTURE_ROOT}/.local"
    cp "$GATE_SCRIPT" "${FIXTURE_ROOT}/scripts/fold7-ondevice-conversion-flow.sh"
    cp "${ROOT_DIR}/scripts/fold7-validation-lib.sh" \
        "${FIXTURE_ROOT}/scripts/fold7-validation-lib.sh"
    chmod +x "${FIXTURE_ROOT}/scripts/"*.sh
    : >"${FIXTURE_ROOT}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
    : >"${FIXTURE_ROOT}/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"
    make_wrapper initial
    make_wrapper update
    write_phase initial 17
    write_phase update 18

    cat >"${FIXTURE_ROOT}/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_GRADLE_LOG"
case "$*" in
  *':cli:run'*)
    printf 'Build artifacts verified\n'
    ;;
esac
EOF
    chmod +x "${FIXTURE_ROOT}/gradlew"

    cat >"${FIXTURE_ROOT}/cli/build/install/sixbridge/bin/sixbridge" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_CLI_LOG"
[ "$1" = verify-crx ] || exit 2
if [ "${CRX_VERIFY_FAIL:-}" = 1 ]; then
  printf 'fixture CRX3 verification failure\n' >&2
  exit 2
fi
printf 'extensionId=%s\n' "$SIXBRIDGE_TEST_EXTENSION"
printf 'CRX3 verified\n'
EOF
    chmod +x "${FIXTURE_ROOT}/cli/build/install/sixbridge/bin/sixbridge"

    cat >"${FIXTURE_ROOT}/bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '20260729-120000\n'
EOF
    chmod +x "${FIXTURE_ROOT}/bin/date"

    cat >"${FIXTURE_ROOT}/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_ADB_LOG"
if [ "$1" = devices ]; then
  printf 'List of devices attached\n'
  printf 'serial-1\tdevice product:q7qksx model:SM-F966N device:q7q\n'
  exit 0
fi
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
  get-state)
    printf '%s\n' "${ADB_STATE:-device}"
    ;;
  install)
    [ "$2" = -r ] || {
      printf 'fixture refuses non-update install\n' >&2
      exit 9
    }
    printf 'Success\n'
    ;;
  shell)
    shift
    case "$*" in
      'getprop ro.product.model') printf '%s\n' "${ADB_MODEL:-SM-F966N}";;
      'getprop ro.build.version.sdk') printf '%s\n' "${ADB_API:-36}";;
      *'am instrument'*)
        if [[ "$*" == *'#generateInitialBundleFromSmartContextZip'* ]]; then
          [ "${INSTRUMENT_FAIL:-}" != initial ] || exit 1
          printf 'OK (1 test)\n'
        elif [[ "$*" == *'#generateUpdateWithSameIdentityAndHigherVersion'* ]]; then
          [ "${INSTRUMENT_FAIL:-}" != update ] || exit 1
          printf 'OK (1 test)\n'
        else
          exit 2
        fi
        ;;
      *'am start -a android.intent.action.VIEW -d internet://extension'*)
        printf 'Starting: Intent\n'
        ;;
      *) exit 2;;
    esac
    ;;
  exec-out)
    shift
    [ "$1" = run-as ] || exit 2
    shift 2
    [ "$1" = cat ] || exit 2
    name=${2##*/}
    exec /bin/cat "${SIXBRIDGE_TEST_DEVICE_DIR}/${name}"
    ;;
  *) exit 2;;
esac
EOF
    chmod +x "${FIXTURE_ROOT}/bin/adb"

    cat >"${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
apk=${!#}
signer=$SIXBRIDGE_TEST_SIGNER
if [[ "$apk" == *update-wrapper.apk ]] && [ -n "${APKSIGNER_UPDATE_SIGNER:-}" ]; then
  signer=$APKSIGNER_UPDATE_SIGNER
fi
printf 'Verified using v2 scheme (APK Signature Scheme v2): true\n'
printf 'Verified using v3 scheme (APK Signature Scheme v3): true\n'
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$signer"
EOF
    chmod +x "${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner"

    cat >"${FIXTURE_ROOT}/sdk/build-tools/36.0.0/aapt2" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
apk=''
for argument in "$@"; do
  case "$argument" in *.apk) apk=$argument;; esac
done
version=17
case "$apk" in *update-wrapper.apk) version=18;; esac
case "$*" in
  *'dump badging'*)
    printf "package: name='%s' versionCode='%s' versionName='1.0'\n" \
      "$SIXBRIDGE_TEST_PACKAGE" "$version"
    ;;
  *'dump xmltree'*)
    printf 'E: manifest\n'
    printf '  A: package="%s" (Raw: "%s")\n' \
      "$SIXBRIDGE_TEST_PACKAGE" "$SIXBRIDGE_TEST_PACKAGE"
    printf '    E: application\n'
    if [ "${AAPT2_CONTRACT:-complete}" != missing-metadata ]; then
      printf '      E: meta-data\n'
      if [ "${AAPT2_CONTRACT:-complete}" = metadata-wrong-namespace ]; then
        printf '        A: custom:name="extension-id" (Raw: "extension-id")\n'
        printf '        A: custom:value="%s" (Raw: "%s")\n' \
          "$SIXBRIDGE_TEST_EXTENSION" "$SIXBRIDGE_TEST_EXTENSION"
      else
        printf '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="extension-id" (Raw: "extension-id")\n'
        printf '        A: http://schemas.android.com/apk/res/android:value(0x01010024)="%s" (Raw: "%s")\n' \
          "$SIXBRIDGE_TEST_EXTENSION" "$SIXBRIDGE_TEST_EXTENSION"
      fi
    fi
    if [ "${AAPT2_CONTRACT:-complete}" != missing ]; then
      printf '      E: activity\n'
      printf '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.sixbridge.runtime.SixDiscoveryActivity"\n'
      printf '        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true\n'
      printf '        E: intent-filter\n'
      printf '          E: action\n'
      printf '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.VIEW"\n'
      if [ "${AAPT2_CONTRACT:-complete}" != missing-default ]; then
        printf '          E: category\n'
        printf '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.category.DEFAULT"\n'
      fi
      printf '          E: category\n'
      printf '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.category.BROWSABLE"\n'
      printf '          E: data\n'
      printf '            A: http://schemas.android.com/apk/res/android:scheme(0x01010027)="six"\n'
      printf '            A: http://schemas.android.com/apk/res/android:host(0x01010028)="ex"\n'
      if [ "${AAPT2_CONTRACT:-complete}" = extra-filter ]; then
        printf '        E: intent-filter\n'
      fi
      if [ "${AAPT2_CONTRACT:-complete}" = extra-activity ]; then
        printf '      E: activity\n'
        printf '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="example.OtherActivity"\n'
        printf '        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false\n'
      fi
      printf '      E: provider\n'
      if [ "${AAPT2_CONTRACT:-complete}" = provider-wrong-class ]; then
        printf '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="example.OtherProvider"\n'
      else
        printf '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.sixbridge.runtime.ExtensionProvider"\n'
      fi
      if [ "${AAPT2_CONTRACT:-complete}" = provider-not-exported ]; then
        printf '        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false\n'
      else
        printf '        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true\n'
      fi
      printf '        A: http://schemas.android.com/apk/res/android:authorities(0x01010018)="%s.contentprovider"\n' \
        "$SIXBRIDGE_TEST_PACKAGE"
    fi
    ;;
  *) exit 2;;
esac
EOF
    chmod +x "${FIXTURE_ROOT}/sdk/build-tools/36.0.0/aapt2"
}

run_gate() {
    env \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        ANDROID_HOME="${FIXTURE_ROOT}/sdk" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_GRADLE_LOG="${FIXTURE_ROOT}/gradle.log" \
        SIXBRIDGE_TEST_CLI_LOG="${FIXTURE_ROOT}/cli.log" \
        SIXBRIDGE_TEST_DEVICE_DIR="${FIXTURE_ROOT}/device" \
        SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" \
        SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" \
        SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" \
        "$@" \
        bash "${FIXTURE_ROOT}/scripts/fold7-ondevice-conversion-flow.sh" serial-1
}

main() {
    [[ -x "$GATE_SCRIPT" ]] || fail "host gate script가 없습니다: $GATE_SCRIPT"

    make_fixture
    trap 'rm -rf -- "${FIXTURE_ROOT:-}"' EXIT

    output="${FIXTURE_ROOT}/success.log"
    if ! run_gate env >"$output" 2>&1; then
        cat "$output" >&2
        fail '정상 fixture gate가 실패했습니다.'
    fi
    assert_contains "$output" 'Host-side checks passed.' '정상 gate가 성공하지 않았습니다.'
    assert_contains "$output" 'evidence=' 'evidence 경로가 출력되지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/gradle.log" \
        ':cli:installDist :android-manager:assembleDebug :android-manager:assembleDebugAndroidTest' \
        'CLI와 manager/test APK를 함께 빌드하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/cli.log" \
        'verify-crx' '공용 CRX3 verifier를 실행하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/adb.log" \
        'install -r' 'install -r로 APK를 설치하지 않았습니다.'
    assert_contains "${FIXTURE_ROOT}/adb.log" \
        'internet://extension' '삼성 인터넷 Add-ons를 실행하지 않았습니다.'
    assert_not_matches "${FIXTURE_ROOT}/adb.log" \
        '(^| )(uninstall|pm clear)( |$)' \
        '자동 삭제 또는 데이터 삭제 명령을 실행했습니다.'
    assert_not_matches "${FIXTURE_ROOT}/adb.log" \
        ' install [^-]' \
        '래퍼 또는 APK를 비업데이트 방식으로 설치했습니다.'

    second_output="${FIXTURE_ROOT}/second-success.log"
    if ! run_gate env >"$second_output" 2>&1; then
        cat "$second_output" >&2
        fail '두 번째 정상 fixture gate가 실패했습니다.'
    fi
    [ "$(sed -n 's/^evidence=//p' "$output")" != \
        "$(sed -n 's/^evidence=//p' "$second_output")" ] ||
        fail '같은 초의 gate evidence 디렉터리가 충돌했습니다.'

    expect_failure '기기가 offline 상태입니다.' offline \
        run_gate env ADB_STATE=offline
    expect_failure 'initial instrumentation이 실패했습니다.' instrumentation \
        run_gate env INSTRUMENT_FAIL=initial

    cp "${FIXTURE_ROOT}/device/initial-result.json" "${FIXTURE_ROOT}/device/initial-result.good"
    jq '.schemaVersion = 2' "${FIXTURE_ROOT}/device/initial-result.good" \
        >"${FIXTURE_ROOT}/device/initial-result.json"
    expect_failure 'evidence schema' bad-schema run_gate env
    mv "${FIXTURE_ROOT}/device/initial-result.good" "${FIXTURE_ROOT}/device/initial-result.json"

    cp "${FIXTURE_ROOT}/device/update-result.json" "${FIXTURE_ROOT}/device/update-result.good"
    jq '.sixSha256 = ("0" * 64)' "${FIXTURE_ROOT}/device/update-result.good" \
        >"${FIXTURE_ROOT}/device/update-result.json"
    expect_failure '.six SHA-256' six-hash run_gate env
    mv "${FIXTURE_ROOT}/device/update-result.good" "${FIXTURE_ROOT}/device/update-result.json"

    jq --arg signer "$OTHER_SIGNER_SHA256" '.signerSha256 = $signer' \
        "${FIXTURE_ROOT}/device/update-result.json" \
        >"${FIXTURE_ROOT}/device/update-result.changed"
    mv "${FIXTURE_ROOT}/device/update-result.changed" "${FIXTURE_ROOT}/device/update-result.json"
    expect_failure 'signer가 변경되었습니다.' signer-change run_gate env
    write_phase update 18

    jq '.extensionId = ("b" * 32) | .packageName = ("dev.sixbridge.ext." + .extensionId)' \
        "${FIXTURE_ROOT}/device/update-result.json" \
        >"${FIXTURE_ROOT}/device/update-result.changed"
    mv "${FIXTURE_ROOT}/device/update-result.changed" "${FIXTURE_ROOT}/device/update-result.json"
    expect_failure 'extension ID가 변경되었습니다.' extension-change run_gate env
    write_phase update 18

    jq '.versionCode = 17' "${FIXTURE_ROOT}/device/update-result.json" \
        >"${FIXTURE_ROOT}/device/update-result.changed"
    mv "${FIXTURE_ROOT}/device/update-result.changed" "${FIXTURE_ROOT}/device/update-result.json"
    expect_failure 'versionCode가 증가하지 않았습니다.' version-not-higher run_gate env
    write_phase update 18

    expect_failure 'Provider 계약이 없습니다.' wrapper-contract \
        run_gate env AAPT2_CONTRACT=missing

    expect_failure 'metadata 계약이 없습니다.' metadata-missing \
        run_gate env AAPT2_CONTRACT=missing-metadata

    expect_failure 'metadata 계약이 없습니다.' metadata-namespace \
        run_gate env AAPT2_CONTRACT=metadata-wrong-namespace

    expect_failure 'Provider 계약이 없습니다.' provider-class \
        run_gate env AAPT2_CONTRACT=provider-wrong-class

    expect_failure 'Provider 계약이 없습니다.' provider-exported \
        run_gate env AAPT2_CONTRACT=provider-not-exported

    expect_failure 'discovery Activity 계약이 없습니다.' discovery-default \
        run_gate env AAPT2_CONTRACT=missing-default

    expect_failure 'discovery Activity 계약이 없습니다.' discovery-activity-count \
        run_gate env AAPT2_CONTRACT=extra-activity

    expect_failure 'discovery Activity 계약이 없습니다.' discovery-filter-count \
        run_gate env AAPT2_CONTRACT=extra-filter

    expect_failure 'CRX3 검증에 실패했습니다.' invalid-crx \
        run_gate env CRX_VERIFY_FAIL=1

    write_phase update 18 "$OTHER_SOURCE_SHA256"
    expect_failure 'sourceSha256가 변경되었습니다.' source-change run_gate env
    write_phase update 18

    printf 'PASS: Fold7 온디바이스 변환 host gate fixture\n'
}

main
