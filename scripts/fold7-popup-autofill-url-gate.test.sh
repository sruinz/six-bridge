#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
gate="$project_dir/scripts/fold7-popup-autofill-url-gate.sh"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

assert_contains() {
  local value=$1 expected=$2 message=$3
  [[ "$value" == *"$expected"* ]] || fail "$message: $expected"
}

assert_not_contains() {
  local value=$1 unexpected=$2 message=$3
  [[ "$value" != *"$unexpected"* ]] || fail "$message: $unexpected"
}

[[ -f "$gate" ]] || fail "gate script가 없습니다: $gate"
for html in \
  "$project_dir/fixtures/browser-internal-url-mapping/popup.html" \
  "$project_dir/fixtures/browser-internal-url-mapping/options.html"
do
  grep -Fiq '<meta charset="utf-8">' "$html" || \
    fail "internal URL fixture에 UTF-8 charset 계약이 없습니다: $html"
done
if grep -Eq 'chrome\.tabs\.create|window\.open|location\.assign' \
  "$project_dir/fixtures/browser-internal-url-mapping/popup.js"; then
  fail 'internal URL fixture에 브라우저 종료 위험이 있는 direct navigation probe가 남았습니다.'
fi
gate_text=$(cat "$gate")
assert_contains "$gate_text" 'PopupAutofillUrlGateInstrumentedTest' \
  'Fold7 production conversion instrumentation 계약이 없습니다.'
assert_contains "$gate_text" ':android-manager:assembleDebugAndroidTest' \
  'Android test APK build 계약이 없습니다.'
assert_contains "$gate_text" 'cmd package list packages --user current' \
  '미설치 package를 정상 상태로 구분하는 preflight 조회가 없습니다.'
assert_not_contains "$gate_text" '"$cli_bin" build' \
  'macOS CLI에서 최종 CRX/APK 서명을 시도합니다.'

fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-popup-url-gate.XXXXXX")
trap 'rm -rf "$fixture_root"' EXIT
fake_bin="$fixture_root/bin"
evidence_dir="$fixture_root/evidence"
gate_workspace="$fixture_root/workspace"
remote_root="$fixture_root/remote"
command_log="$fixture_root/commands.log"
pid_counter="$fixture_root/pid-counter"
installed_packages="$fixture_root/installed-packages"
manager_apk="$fixture_root/android-manager-debug.apk"
manager_test_apk="$fixture_root/android-manager-debug-androidTest.apk"
mkdir -p "$fake_bin" "$evidence_dir" "$gate_workspace" "$remote_root"
printf 'manager apk\n' >"$manager_apk"
printf 'manager test apk\n' >"$manager_test_apk"

cat >"$fake_bin/gradlew" <<'EOF'
#!/bin/bash
set -euo pipefail
printf 'GRADLE %s\n' "$*" >>"$SIX_TEST_COMMAND_LOG"
[[ "$*" == *':android-manager:assembleDebug'* ]] || exit 64
[[ "$*" == *':android-manager:assembleDebugAndroidTest'* ]] || exit 64
EOF
chmod +x "$fake_bin/gradlew"

cat >"$fake_bin/adb" <<'EOF'
#!/bin/bash
set -euo pipefail
printf 'ADB %s\n' "$*" >>"$SIX_TEST_COMMAND_LOG"
[[ "${1:-}" == -s && -n "${2:-}" ]] || exit 64
shift 2

remote_path() {
  printf '%s/%s' "$SIX_TEST_REMOTE_ROOT" "$1"
}

generate_artifact() {
  local output_root=$1 slug=$2 extension_id=$3 adapter=$4 package wrapper_hash six_hash applied
  package="dev.sixbridge.ext.$extension_id"
  mkdir -p "$output_root" "$output_root/.work-$slug"
  printf 'fake wrapper %s\n' "$package" >"$output_root/$slug-wrapper.apk"
  wrapper_hash=$(shasum -a 256 "$output_root/$slug-wrapper.apk" | awk '{print $1}')
  applied=true
  if [[ "${SIX_TEST_MODE:-success}" == adapter-missing && "$slug" == popup-autofill ]]; then
    applied=false
  fi
  printf '{"schemaVersion":1,"extensionId":"%s","name":"%s","version":"1.0","sourceKind":"ZIP","crxSha256":"%064d","wrapperSha256":"%s","wrapperPackage":"%s","wrapperVersionCode":1,"signerSha256":"%064d"}\n' \
    "$extension_id" "$slug" 0 "$wrapper_hash" "$package" 1 \
    >"$output_root/.work-$slug/extension.json"
  printf '{"schemaVersion":5,"status":"PARTIAL","conversionState":"converted","browserProfileSha256":"%064d","sourceSha256":"%064d","originalSourceSha256":"%064d","planSha256":"%064d","adapterDecisions":[{"id":"%s","version":1,"risk":"review_required","disposition":"user_approved","applied":%s,"evidence":[],"changes":[],"remainingLimitations":[]}]}\n' \
    2 3 4 5 "$adapter" "$applied" >"$output_root/.work-$slug/compatibility.json"
  cp "$output_root/$slug-wrapper.apk" "$output_root/.work-$slug/wrapper.apk"
  (
    cd "$output_root/.work-$slug"
    zip -q "$output_root/$slug.six" wrapper.apk extension.json compatibility.json
  )
  six_hash=$(shasum -a 256 "$output_root/$slug.six" | awk '{print $1}')
  printf '{"schemaVersion":1,"slug":"%s","adapterId":"%s","adapterApplied":%s,"conversionState":"converted","profileSha256":"%064d","sourceSha256":"%064d","originalSourceSha256":"%064d","planSha256":"%064d","sixFile":"%s.six","sixSha256":"%s","wrapperFile":"%s-wrapper.apk","wrapperSha256":"%s","crxSha256":"%064d","extensionId":"%s","packageName":"%s","signerSha256":"%064d","versionCode":1,"appliedAdapterIds":["%s"]}\n' \
    "$slug" "$adapter" "$applied" 2 3 4 5 "$slug" "$six_hash" "$slug" "$wrapper_hash" 0 \
    "$extension_id" "$package" 1 "$adapter" >"$output_root/$slug-result.json"
  rm -rf "$output_root/.work-$slug"
}

case "${1:-}" in
  get-state)
    echo device
    ;;
  install)
    if [[ "${2:-}" == -r ]]; then
      echo Success
      exit 0
    fi
    if [[ "${SIX_TEST_MODE:-success}" == install-failure ]]; then
      echo 'Failure [INSTALL_FAILED_INVALID_APK]'
      exit 0
    fi
    case "${2:-}" in
      *popup-autofill-wrapper.apk)
        echo dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa >>"$SIX_TEST_INSTALLED_PACKAGES"
        ;;
      *browser-internal-url-wrapper.apk)
        echo dev.sixbridge.ext.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb >>"$SIX_TEST_INSTALLED_PACKAGES"
        ;;
      *) exit 64 ;;
    esac
    echo Success
    ;;
  exec-out)
    [[ "${2:-}" == run-as && "${3:-}" == dev.sixbridge.manager && "${4:-}" == cat ]] || exit 64
    cat "$(remote_path "$5")"
    ;;
  shell)
    shift
    if [[ "${1:-}" == -T && "${2:-}" == run-as && "${3:-}" == dev.sixbridge.manager ]]; then
      shift 3
      case "${1:-}" in
        mkdir)
          shift
          if [[ "${1:-}" == -p ]]; then
            shift
            mkdir -p "$(remote_path "$1")"
          else
            mkdir "$(remote_path "$1")"
          fi
          ;;
        rm)
          [[ "${2:-}" == -r ]] || exit 64
          rm -rf "$(remote_path "$3")"
          ;;
        dd)
          [[ "${2:-}" == of=* ]] || exit 64
          destination=${2#of=}
          mkdir -p "$(dirname "$(remote_path "$destination")")"
          dd of="$(remote_path "$destination")" status=none
          ;;
        *) exit 64 ;;
      esac
      exit 0
    fi
    case "${1:-} ${2:-} ${3:-}" in
      'getprop ro.product.model ')
        [[ "${SIX_TEST_MODE:-success}" == wrong-model ]] && echo SM-G999N || echo SM-F966N
        ;;
      'getprop ro.build.version.sdk ')
        [[ "${SIX_TEST_MODE:-success}" == api-too-low ]] && echo 29 || echo 36
        ;;
      'dumpsys package com.sec.android.app.sbrowser')
        echo 'Package [com.sec.android.app.sbrowser]'
        [[ "${SIX_TEST_MODE:-success}" == version-mismatch ]] && \
          echo '  versionName=30.0.0.66' || echo '  versionName=30.0.0.67'
        [[ "${SIX_TEST_MODE:-success}" == version-code-mismatch ]] && \
          echo '  versionCode=1300067501 minSdk=30 targetSdk=36' || \
          echo '  versionCode=1300067502 minSdk=30 targetSdk=36'
        ;;
      'cmd package list')
        [[ "${4:-}" == packages && "${5:-}" == --user && \
          "${6:-}" == current && -n "${7:-}" ]] || exit 64
        package=$7
        [[ "${SIX_TEST_MODE:-success}" == package-query-failure ]] && exit 1
        if [[ "${SIX_TEST_MODE:-success}" == existing-package ]] || \
          grep -Fxq "$package" "$SIX_TEST_INSTALLED_PACKAGES" 2>/dev/null; then
          echo "package:$package"
        fi
        ;;
      'pm path '*)
        package=${3:-unknown}
        if grep -Fxq "$package" "$SIX_TEST_INSTALLED_PACKAGES" 2>/dev/null; then
          echo "package:/data/app/$package/base.apk"
        fi
        ;;
      'cmd package resolve-activity')
        package=
        while [[ "$#" -gt 0 ]]; do
          if [[ "$1" == -p ]]; then package=$2; break; fi
          shift
        done
        [[ "${SIX_TEST_MODE:-success}" == discovery-failure ]] && \
          echo 'No activity found' || echo "$package/dev.sixbridge.runtime.SixDiscoveryActivity"
        ;;
      'content read '*)
        [[ "${SIX_TEST_MODE:-success}" == provider-failure ]] && printf 'FAILfake' || printf 'Cr24fake'
        ;;
      'am start '*)
        [[ "${SIX_TEST_MODE:-success}" == route-failure ]] && \
          echo 'Error: Activity not started' || echo 'Starting: Intent'
        ;;
      'pidof com.sec.android.app.sbrowser ')
        count=0
        [[ -f "$SIX_TEST_PID_COUNTER" ]] && count=$(cat "$SIX_TEST_PID_COUNTER")
        count=$((count + 1))
        printf '%s' "$count" >"$SIX_TEST_PID_COUNTER"
        [[ "${SIX_TEST_MODE:-success}" == process-gone && "$count" -gt 1 ]] && exit 1
        if [[ "${SIX_TEST_MODE:-success}" == process-changed && "$count" -gt 1 ]]; then
          echo 5252
        else
          echo 4242
        fi
        ;;
      'am instrument '*)
        session=
        while [[ "$#" -gt 0 ]]; do
          if [[ "$1" == sixbridgeSession ]]; then session=$2; break; fi
          shift
        done
        [[ -n "$session" ]] || exit 64
        input_root="$(remote_path "files/popup-autofill-url-gate/sessions/$session/input")"
        [[ -s "$input_root/profile.json" && -s "$input_root/popup-autofill.zip" && \
          -s "$input_root/browser-internal-url.zip" ]] || exit 65
        output_root="$(remote_path "files/popup-autofill-url-gate/sessions/$session/output")"
        generate_artifact "$output_root" popup-autofill aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa popup-native-autofill-guard
        generate_artifact "$output_root" browser-internal-url bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb browser-internal-url-mapper
        echo 'INSTRUMENTATION_STATUS: test=convertFixturesThroughProductionCoordinator'
        echo 'OK (1 test)'
        ;;
      *)
        echo "unexpected adb shell command: $*" >&2
        exit 64
        ;;
    esac
    ;;
  *)
    echo "unexpected adb command: $*" >&2
    exit 64
    ;;
esac
EOF
chmod +x "$fake_bin/adb"

run_gate() {
  local mode=$1
  rm -rf "$remote_root"
  mkdir -p "$remote_root"
  rm -f "$pid_counter" "$installed_packages"
  : >"$installed_packages"
  : >"$command_log"
  printf '\n' | env \
    SIX_GATE_ADB="$fake_bin/adb" \
    SIX_GATE_GRADLE="$fake_bin/gradlew" \
    SIX_GATE_MANAGER_APK="$manager_apk" \
    SIX_GATE_MANAGER_TEST_APK="$manager_test_apk" \
    SIX_GATE_EVIDENCE_DIR="$evidence_dir" \
    SIX_GATE_WORKSPACE="$gate_workspace" \
    SIX_TEST_COMMAND_LOG="$command_log" \
    SIX_TEST_REMOTE_ROOT="$remote_root" \
    SIX_TEST_PID_COUNTER="$pid_counter" \
    SIX_TEST_INSTALLED_PACKAGES="$installed_packages" \
    SIX_TEST_MODE="$mode" \
    "$gate" 'adb-DEVICE_SERIAL._adb-tls-connect._tcp' 2>&1
}

set +e
output=$(run_gate success)
success_status=$?
set -e
[[ "$success_status" -eq 0 ]] || { printf '%s\n' "$output" >&2; fail '성공 fixture gate가 실패했습니다.'; }
commands=$(cat "$command_log")
assert_contains "$output" 'profile-match=true' '브라우저 profile 검증이 누락되었습니다.'
assert_contains "$output" 'android-production-conversion=true' 'Android production 변환 증거가 없습니다.'
assert_contains "$output" 'popup-process-before=4242' '수동 확인 전 PID가 없습니다.'
assert_contains "$output" 'popup-process-after=4242' '수동 확인 후 PID가 없습니다.'
assert_contains "$output" 'browser-process-survived=true' '프로세스 생존 결과가 없습니다.'
assert_contains "$output" '확장 관리 주소: 로컬 한글 안내가 표시되고 브라우저가 종료되지 않아야 합니다.' \
  '안전한 확장 관리 주소 체크리스트가 없습니다.'
assert_contains "$output" 'query·fragment: 자동 이동하지 않아야 하며 무반응은 허용합니다.' \
  '미매핑 주소의 안전 기준이 없습니다.'
assert_not_contains "$output" 'tabs.create로 확장 관리' \
  '브라우저 종료 위험이 있는 direct navigation 안내가 남았습니다.'
assert_contains "$commands" ':android-manager:assembleDebugAndroidTest' 'Android test APK를 build하지 않았습니다.'
assert_contains "$commands" 'PopupAutofillUrlGateInstrumentedTest' 'production instrumentation을 실행하지 않았습니다.'
assert_contains "$commands" 'sixbridgeSession' '분리된 on-device session을 전달하지 않았습니다.'
[[ "$(grep -c '^ADB .* install -r ' "$command_log")" -eq 2 ]] || fail 'manager/test APK 두 개를 설치하지 않았습니다.'
[[ "$(grep -c '^ADB .* install .*wrapper.apk' "$command_log")" -eq 2 ]] || fail '합성 래퍼 두 개를 정확히 설치하지 않았습니다.'
assert_not_contains "$commands" 'sixbridge build' 'macOS CLI 최종 build를 호출했습니다.'
assert_not_contains "$commands" ' uninstall ' '기존 확장 제거 명령을 실행했습니다.'
assert_contains "$commands" 'am start -a android.intent.action.VIEW -d internet://extension -p com.sec.android.app.sbrowser' \
  'Samsung Add-ons route를 package 명시로 열지 않았습니다.'
for secret in 'password=' 'email=' 'cookie=' 'visitedUrl='; do
  assert_not_contains "$output" "$secret" 'stdout에 민감정보 표식이 노출되었습니다.'
done
evidence=$(printf '%s\n' "$output" | sed -n 's/^evidence=//p' | tail -1)
[[ -f "$evidence" ]] || fail 'evidence 파일이 게시되지 않았습니다.'
[[ "$(stat -f '%Lp' "$evidence")" == 600 ]] || fail 'evidence 권한이 600이 아닙니다.'
evidence_text=$(cat "$evidence")
assert_not_contains "$output" 'adb-DEVICE_SERIAL._adb-tls-connect._tcp' 'stdout에 raw ADB serial이 노출되었습니다.'
assert_not_contains "$evidence_text" 'adb-DEVICE_SERIAL._adb-tls-connect._tcp' 'evidence에 raw ADB serial이 노출되었습니다.'
[[ "$(grep -c -- '-source-tree-sha256=[0-9a-f]\{64\}$' "$evidence")" -eq 2 ]] || \
  fail '두 source fixture tree hash가 evidence에 없습니다.'
for secret in 'password=' 'email=' 'cookie=' 'visitedUrl='; do
  assert_not_contains "$evidence_text" "$secret" 'evidence에 민감정보 표식이 노출되었습니다.'
done

run_failure() {
  local mode=$1 expected=$2 output status
  set +e
  output=$(run_gate "$mode")
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "$mode 실패를 허용했습니다."
  assert_contains "$output" "$expected" "$mode 오류가 없습니다."
}

run_failure wrong-model '검증 대상 Fold7 모델이 아닙니다:'
run_failure api-too-low 'Android API 30 이상이 필요합니다.'
run_failure version-mismatch '검증되지 않은 Samsung Internet 버전입니다:'
run_failure version-code-mismatch '검증되지 않은 Samsung Internet versionCode입니다:'
run_failure existing-package '합성 package가 이미 설치되어 있어 업데이트하지 않습니다.'
existing_commands=$(cat "$command_log")
assert_not_contains "$existing_commands" ' install /' '기존 package가 있는데 합성 래퍼를 설치했습니다.'
run_failure package-query-failure 'package 사전 설치 상태를 확인할 수 없습니다.'
query_commands=$(cat "$command_log")
assert_not_contains "$query_commands" ' install /' 'package 조회 실패 뒤 합성 래퍼를 설치했습니다.'
run_failure install-failure '합성 래퍼 설치 결과가 성공이 아닙니다.'
run_failure discovery-failure 'six://ex discovery에 실패했습니다.'
run_failure provider-failure 'CRX provider 계약이 올바르지 않습니다.'
run_failure adapter-missing '필수 adapter가 적용되지 않았습니다.'
run_failure route-failure 'Add-ons route 결과가 성공이 아닙니다.'
run_failure process-gone 'Samsung Internet process가 종료되었습니다.'
run_failure process-changed 'Samsung Internet process가 수동 확인 중 교체되었습니다.'

for args in "-bad-serial" "" "serial with spaces" "password=secret"; do
  set +e
  invalid=$($gate "$args" 2>&1)
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "잘못된 serial을 허용했습니다."
done
set +e
missing=$($gate 2>&1); missing_status=$?
extra=$($gate serial extra 2>&1); extra_status=$?
set -e
[[ "$missing_status" -ne 0 && "$extra_status" -ne 0 ]] || fail 'serial 인자 개수 경계를 허용했습니다.'

echo 'Fold7 popup/autofill internal URL gate fake tests passed.'
