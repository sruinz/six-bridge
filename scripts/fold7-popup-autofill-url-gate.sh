#!/bin/bash
set -euo pipefail

usage() {
  echo "사용법: $0 <adb-serial>" >&2
  exit 2
}

[[ "$#" -eq 1 ]] || usage
serial=$1
[[ -n "$serial" ]] || usage
case "$serial" in
  -*|*[[:space:]]*|*[!A-Za-z0-9._:-]*) usage ;;
esac

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
profile="$project_dir/profiles/sbrowser-30.0.0.67.json"
popup_source="$project_dir/fixtures/popup-native-autofill-guard"
url_source="$project_dir/fixtures/browser-internal-url-mapping"
adb_bin=${SIX_GATE_ADB:-adb}
gradle_bin=${SIX_GATE_GRADLE:-"$project_dir/gradlew"}
manager_apk=${SIX_GATE_MANAGER_APK:-"$project_dir/android-manager/build/outputs/apk/debug/android-manager-debug.apk"}
manager_test_apk=${SIX_GATE_MANAGER_TEST_APK:-"$project_dir/android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk"}
evidence_dir=${SIX_GATE_EVIDENCE_DIR:-"$project_dir/.local/evidence"}
gate_workspace=${SIX_GATE_WORKSPACE:-"$project_dir/.local/gates/popup-autofill-url"}
manager_package=dev.sixbridge.manager
instrumentation=dev.sixbridge.manager.test/androidx.test.runner.AndroidJUnitRunner
gate_class=dev.sixbridge.manager.conversion.PopupAutofillUrlGateInstrumentedTest
remote_sessions=files/popup-autofill-url-gate/sessions
evidence_tmp=
evidence_log=
run_dir=
remote_session=
remote_session_owned=false

for dependency in "$adb_bin" "$gradle_bin" cmp dd find jq mkdir mktemp shasum sort unzip zip; do
  command -v "$dependency" >/dev/null 2>&1 || {
    echo "필수 명령을 찾을 수 없습니다: $dependency" >&2
    exit 2
  }
done
for required in "$profile" "$popup_source/manifest.json" "$url_source/manifest.json"; do
  [[ -e "$required" && ! -L "$required" ]] || {
    echo "필수 입력을 찾을 수 없습니다: $required" >&2
    exit 2
  }
done

mkdir -p "$evidence_dir" "$gate_workspace"
[[ -d "$evidence_dir" && ! -L "$evidence_dir" ]] || {
  echo "안전한 evidence 디렉터리가 아닙니다: $evidence_dir" >&2
  exit 2
}
[[ -d "$gate_workspace" && ! -L "$gate_workspace" ]] || {
  echo "안전한 gate workspace가 아닙니다: $gate_workspace" >&2
  exit 2
}
chmod 700 "$evidence_dir" "$gate_workspace"
timestamp=$(date +%Y%m%d-%H%M%S)-$$
evidence_log="$evidence_dir/$timestamp-popup-autofill-url-gate.log"
[[ ! -e "$evidence_log" && ! -L "$evidence_log" ]] || {
  echo "evidence 경로가 이미 존재합니다: $evidence_log" >&2
  exit 2
}
evidence_tmp=$(mktemp "$evidence_dir/.popup-autofill-url-gate.XXXXXX")
run_dir=$(mktemp -d "$gate_workspace/.popup-autofill-url-run.XXXXXX")
chmod 600 "$evidence_tmp"
chmod 700 "$run_dir"

record() {
  printf '%s\n' "$1"
  printf '%s\n' "$1" >>"$evidence_tmp"
}

fail() {
  record "failure=$1"
  printf '%s\n' "$1" >&2
  exit "${2:-3}"
}

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [[ "$remote_session_owned" == true && -n "$remote_session" ]]; then
    "$adb_bin" -s "$serial" shell -T run-as "$manager_package" \
      rm -r "$remote_session" >/dev/null 2>&1 || \
      printf '%s\n' '경고: app-private gate session을 정리하지 못했습니다.' >&2
  fi
  if [[ -n "$evidence_tmp" && -f "$evidence_tmp" ]]; then
    chmod 600 "$evidence_tmp"
    mv -f "$evidence_tmp" "$evidence_log"
    chmod 600 "$evidence_log"
    printf 'evidence=%s\n' "$evidence_log"
  fi
  if [[ -n "$run_dir" && -d "$run_dir" && ! -L "$run_dir" ]]; then
    rm -rf "$run_dir"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

trim_cr() {
  tr -d '\r'
}

source_tree_sha256() {
  local source_root=$1 source_file source_hash
  (
    cd "$source_root"
    while IFS= read -r source_file; do
      source_hash=$(shasum -a 256 "$source_file" | awk '{print $1}')
      printf '%s  %s\n' "$source_hash" "${source_file#./}"
    done < <(find . -type f -print | LC_ALL=C sort)
  ) | shasum -a 256 | awk '{print $1}'
}

create_fixture_zip() {
  local source_root=$1 output=$2 project_id=$3 staging relative destination
  staging="$run_dir/$(basename "${output%.zip}")-source"
  mkdir "$staging"
  while IFS= read -r relative; do
    destination="$staging/${relative#./}"
    mkdir -p "$(dirname "$destination")"
    cp "$source_root/${relative#./}" "$destination"
  done < <(cd "$source_root" && find . -type f -print | LC_ALL=C sort)
  printf 'sixbridge-project-v1:%s\n' "$project_id" >"$staging/.sixbridge-project-id"
  (
    cd "$staging"
    find . -type f -print | LC_ALL=C sort | zip -X -q "$output" -@
  )
  [[ -s "$output" && ! -L "$output" ]] || fail "합성 fixture ZIP 생성에 실패했습니다."
}

adb_capture() {
  local destination=$1
  shift
  "$adb_bin" -s "$serial" "$@" >"$destination" 2>/dev/null
}

record '# SIX Bridge Fold7 popup autofill and internal URL gate'
record 'device-selected=true'
record "profile-sha256=$(shasum -a 256 "$profile" | awk '{print $1}')"
record "popup-source-tree-sha256=$(source_tree_sha256 "$popup_source")"
record "internal-url-source-tree-sha256=$(source_tree_sha256 "$url_source")"

state_file="$run_dir/device-state.txt"
adb_capture "$state_file" get-state || fail "지정한 ADB 기기에 연결할 수 없습니다."
state=$(trim_cr <"$state_file")
[[ "$state" == device ]] || fail "지정한 ADB 기기가 device 상태가 아닙니다."

model_file="$run_dir/device-model.txt"
adb_capture "$model_file" shell getprop ro.product.model || fail "기기 모델을 확인할 수 없습니다."
model=$(trim_cr <"$model_file")
[[ "$model" == SM-F966N ]] || fail "검증 대상 Fold7 모델이 아닙니다: $model"
api_file="$run_dir/android-api.txt"
adb_capture "$api_file" shell getprop ro.build.version.sdk || fail "Android API를 확인할 수 없습니다."
api=$(trim_cr <"$api_file")
[[ "$api" =~ ^[0-9]+$ ]] || fail "Android API 값이 올바르지 않습니다."
[[ "$api" -ge 30 ]] || fail "Android API 30 이상이 필요합니다."
record "device-model=$model"
record "android-api=$api"

browser_package=$(jq -er '.browserPackage' "$profile") || fail "profile browser package를 읽을 수 없습니다."
profile_version=$(jq -er '.versionName' "$profile") || fail "profile browser version을 읽을 수 없습니다."
profile_version_code=$(jq -er '.versionCode | tostring' "$profile") || fail "profile browser versionCode를 읽을 수 없습니다."
browser_dump="$run_dir/browser-package.txt"
adb_capture "$browser_dump" shell dumpsys package "$browser_package" || \
  fail "Samsung Internet package 정보를 읽을 수 없습니다."
browser_version=$(sed -n 's/^[[:space:]]*versionName=//p' "$browser_dump" | head -1 | trim_cr)
browser_version_code=$(sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' "$browser_dump" | head -1 | trim_cr)
[[ "$browser_version" == "$profile_version" ]] || fail "검증되지 않은 Samsung Internet 버전입니다: $browser_version"
[[ "$browser_version_code" == "$profile_version_code" ]] || fail "검증되지 않은 Samsung Internet versionCode입니다: $browser_version_code"
record "browser-package=$browser_package"
record "browser-version=$browser_version"
record "browser-version-code=$browser_version_code"
record 'profile-match=true'

popup_zip="$run_dir/popup-autofill.zip"
url_zip="$run_dir/browser-internal-url.zip"
create_fixture_zip "$popup_source" "$popup_zip" 706f7075706175746f66696c6c677561
create_fixture_zip "$url_source" "$url_zip" 696e7465726e616c75726c6d61707065
record "popup-input-zip-sha256=$(shasum -a 256 "$popup_zip" | awk '{print $1}')"
record "internal-url-input-zip-sha256=$(shasum -a 256 "$url_zip" | awk '{print $1}')"

sdk_root=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
gradle_home=${GRADLE_USER_HOME:-"$project_dir/.local/gradle-home"}
ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root" GRADLE_USER_HOME="$gradle_home" \
  "$gradle_bin" -Pkotlin.jvm.target.validation.mode=IGNORE \
  :android-manager:assembleDebug :android-manager:assembleDebugAndroidTest \
  >"$run_dir/android-build.txt" || fail "Android gate APK build에 실패했습니다."
for apk in "$manager_apk" "$manager_test_apk"; do
  [[ -s "$apk" && ! -L "$apk" ]] || fail "Android gate APK를 찾을 수 없습니다."
  install_output="$run_dir/$(basename "$apk").install.txt"
  adb_capture "$install_output" install -r "$apk" || fail "Android gate APK 설치에 실패했습니다."
  grep -Fq 'Success' "$install_output" || fail "Android gate APK 설치 결과가 성공이 아닙니다."
done
record 'manager-test-harness-installed=true'

remote_session="$remote_sessions/$timestamp"
remote_input="$remote_session/input"
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" mkdir -p "$remote_sessions" >/dev/null || \
  fail "app-private gate session root를 만들지 못했습니다."
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" mkdir "$remote_session" >/dev/null || \
  fail "app-private gate session을 만들지 못했습니다."
remote_session_owned=true
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" mkdir "$remote_input" >/dev/null || \
  fail "app-private gate input을 만들지 못했습니다."
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" dd of="$remote_input/profile.json" <"$profile" >/dev/null || \
  fail "profile을 Fold7 app-private storage로 전달하지 못했습니다."
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" dd of="$remote_input/popup-autofill.zip" <"$popup_zip" >/dev/null || \
  fail "popup fixture를 Fold7 app-private storage로 전달하지 못했습니다."
"$adb_bin" -s "$serial" shell -T run-as "$manager_package" dd of="$remote_input/browser-internal-url.zip" <"$url_zip" >/dev/null || \
  fail "internal URL fixture를 Fold7 app-private storage로 전달하지 못했습니다."

instrumentation_log="$run_dir/android-instrumentation.txt"
adb_capture "$instrumentation_log" shell am instrument -w -r \
  -e sixbridgeSession "$timestamp" \
  -e class "$gate_class" "$instrumentation" || \
  fail "Fold7 production conversion instrumentation이 실패했습니다."
if ! grep -Fq 'OK (1 test)' "$instrumentation_log"; then
  cat "$instrumentation_log" >&2
  fail "Fold7 production conversion instrumentation 결과가 성공이 아닙니다."
fi
record 'android-production-conversion=true'

for slug in popup-autofill browser-internal-url; do
  for name in "$slug.six" "$slug-wrapper.apk" "$slug-result.json"; do
    destination="$run_dir/$name"
    "$adb_bin" -s "$serial" exec-out run-as "$manager_package" \
      cat "$remote_session/output/$name" >"$destination" || \
      fail "$slug Android 산출물을 회수하지 못했습니다."
    [[ -s "$destination" && ! -L "$destination" ]] || \
      fail "$slug Android 산출물이 비어 있습니다."
  done
done

verify_artifact() {
  local slug=$1 expected_adapter=$2 bundle wrapper result descriptor compatibility embedded
  bundle="$run_dir/$slug.six"
  wrapper="$run_dir/$slug-wrapper.apk"
  result="$run_dir/$slug-result.json"
  descriptor="$run_dir/$slug-extension.json"
  compatibility="$run_dir/$slug-compatibility.json"
  embedded="$run_dir/$slug-embedded-wrapper.apk"
  unzip -p "$bundle" wrapper.apk >"$embedded" || fail "$slug bundle wrapper를 읽을 수 없습니다."
  cmp -s "$embedded" "$wrapper" || fail "$slug bundle과 회수 wrapper가 다릅니다."
  unzip -p "$bundle" extension.json >"$descriptor" || fail "$slug extension descriptor를 읽을 수 없습니다."
  unzip -p "$bundle" compatibility.json >"$compatibility" || fail "$slug compatibility 보고서를 읽을 수 없습니다."
  jq -e --arg slug "$slug" --arg adapter "$expected_adapter" '
    .schemaVersion == 1 and .slug == $slug and
    .adapterId == $adapter and (.adapterApplied | type == "boolean") and
    .conversionState == "converted" and
    (.profileSha256 | test("^[0-9a-f]{64}$")) and
    (.sourceSha256 | test("^[0-9a-f]{64}$")) and
    (.originalSourceSha256 | test("^[0-9a-f]{64}$")) and
    (.planSha256 | test("^[0-9a-f]{64}$")) and
    (.extensionId | test("^[a-p]{32}$")) and
    .packageName == ("dev.sixbridge.ext." + .extensionId) and
    (.signerSha256 | test("^[0-9a-f]{64}$")) and
    (.versionCode | type == "number" and . >= 1 and floor == .) and
    (.appliedAdapterIds | type == "array")
  ' "$result" >/dev/null || fail "$slug Android result 계약이 올바르지 않습니다."
  jq -e --arg adapter "$expected_adapter" '
    .conversionState == "converted" and
    ([.adapterDecisions[] | select(.id == $adapter and .applied == true)] | length == 1)
  ' "$compatibility" >/dev/null || fail "$slug 필수 adapter가 적용되지 않았습니다."
  jq -e --slurpfile result "$result" '
    .browserProfileSha256 == $result[0].profileSha256 and
    .sourceSha256 == $result[0].sourceSha256 and
    .originalSourceSha256 == $result[0].originalSourceSha256 and
    .planSha256 == $result[0].planSha256
  ' "$compatibility" >/dev/null || fail "$slug provenance와 Android result가 다릅니다."
  jq -e --slurpfile result "$result" '
    .extensionId == $result[0].extensionId and
    .wrapperPackage == $result[0].packageName and
    .wrapperVersionCode == $result[0].versionCode and
    .signerSha256 == $result[0].signerSha256 and
    .wrapperSha256 == $result[0].wrapperSha256 and
    .crxSha256 == $result[0].crxSha256
  ' "$descriptor" >/dev/null || fail "$slug descriptor와 Android result가 다릅니다."
  [[ "$(shasum -a 256 "$bundle" | awk '{print $1}')" == "$(jq -er '.sixSha256' "$result")" ]] || \
    fail "$slug SixBundle hash가 Android result와 다릅니다."
  [[ "$(shasum -a 256 "$wrapper" | awk '{print $1}')" == "$(jq -er '.wrapperSha256' "$result")" ]] || \
    fail "$slug wrapper hash가 Android result와 다릅니다."
  verified_package=$(jq -er '.packageName' "$result") || fail "$slug package를 읽을 수 없습니다."
  record "$slug-package=$verified_package"
  record "$slug-wrapper-sha256=$(shasum -a 256 "$wrapper" | awk '{print $1}')"
  record "$slug-bundle-sha256=$(shasum -a 256 "$bundle" | awk '{print $1}')"
  record "$slug-adapter=$expected_adapter"
}

verify_artifact popup-autofill popup-native-autofill-guard
popup_package=$verified_package
verify_artifact browser-internal-url browser-internal-url-mapper
url_package=$verified_package
[[ "$popup_package" != "$url_package" ]] || fail "두 합성 fixture package가 충돌합니다."

preflight_package() {
  local slug=$1 package=$2 output existing
  output="$run_dir/$slug-preinstall.txt"
  adb_capture "$output" shell cmd package list packages --user current "$package" || \
    fail "$slug package 사전 설치 상태를 확인할 수 없습니다."
  existing=$(trim_cr <"$output")
  ! grep -Fxq "package:$package" <<<"$existing" || \
    fail "$slug 합성 package가 이미 설치되어 있어 업데이트하지 않습니다."
}
preflight_package popup-autofill "$popup_package"
preflight_package browser-internal-url "$url_package"

install_and_verify() {
  local slug=$1 package=$2 wrapper install_output path_output package_path discovery_output provider_output provider_magic
  wrapper="$run_dir/$slug-wrapper.apk"
  install_output="$run_dir/$slug-install.txt"
  adb_capture "$install_output" install "$wrapper" || fail "$slug 합성 래퍼 설치에 실패했습니다."
  grep -Fq 'Success' "$install_output" || fail "$slug 합성 래퍼 설치 결과가 성공이 아닙니다."
  path_output="$run_dir/$slug-package-path.txt"
  adb_capture "$path_output" shell pm path "$package" || fail "$slug 설치 package를 확인할 수 없습니다."
  package_path=$(trim_cr <"$path_output")
  [[ "$package_path" == package:* ]] || fail "$slug 설치 package가 없습니다."
  discovery_output="$run_dir/$slug-discovery.txt"
  adb_capture "$discovery_output" shell cmd package resolve-activity --brief \
    -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
    -d six://ex -p "$package" || fail "$slug six://ex discovery를 실행할 수 없습니다."
  grep -Fq "$package" "$discovery_output" || fail "$slug six://ex discovery에 실패했습니다."
  provider_output="$run_dir/$slug-provider.bin"
  adb_capture "$provider_output" shell content read --uri "content://$package.contentprovider" || \
    fail "$slug CRX provider를 읽을 수 없습니다."
  provider_magic=$(dd if="$provider_output" bs=1 count=4 2>/dev/null) || \
    fail "$slug CRX provider header를 읽을 수 없습니다."
  [[ "$provider_magic" == Cr24 ]] || fail "$slug CRX provider 계약이 올바르지 않습니다."
  record "$slug-install=true"
  record "$slug-discovery=true"
  record "$slug-provider=true"
}
install_and_verify popup-autofill "$popup_package"
install_and_verify browser-internal-url "$url_package"

route_output="$run_dir/addons-route.txt"
adb_capture "$route_output" shell am start -a android.intent.action.VIEW \
  -d internet://extension -p "$browser_package" || fail "Samsung Add-ons route를 열 수 없습니다."
grep -Eq 'Starting:|Status: ok' "$route_output" || fail "Samsung Add-ons route 결과가 성공이 아닙니다."
record 'addons-route-opened=true'

pid_before_file="$run_dir/pid-before.txt"
adb_capture "$pid_before_file" shell pidof "$browser_package" || fail "Samsung Internet process가 실행 중이 아닙니다."
pid_before=$(trim_cr <"$pid_before_file")
[[ -n "$pid_before" ]] || fail "Samsung Internet process가 실행 중이 아닙니다."
record "popup-process-before=$pid_before"

printf '%s\n' 'Samsung Add-ons에서 두 합성 확장을 활성화한 뒤 수동 체크리스트를 수행하세요.'
printf '%s\n' '팝업 입력창: 여섯 입력 유형과 동적 입력에서 입력·submit이 유지되고 브라우저가 종료되지 않아야 합니다.'
printf '%s\n' '확장 관리 주소: 로컬 한글 안내가 표시되고 브라우저가 종료되지 않아야 합니다.'
printf '%s\n' '암호 관리·flags·inspect: 각각 로컬 manual·unsupported 안내가 표시되어야 합니다.'
printf '%s\n' 'query·fragment: 자동 이동하지 않아야 하며 무반응은 허용합니다.'
printf '%s\n' '자동 입력이나 자동 클릭은 수행하지 않습니다. 확인을 마치면 Enter를 누르세요.'
IFS= read -r _ || fail "사용자 수동 확인이 완료되지 않았습니다."

pid_after_file="$run_dir/pid-after.txt"
adb_capture "$pid_after_file" shell pidof "$browser_package" || fail "Samsung Internet process가 종료되었습니다."
pid_after=$(trim_cr <"$pid_after_file")
[[ -n "$pid_after" ]] || fail "Samsung Internet process가 종료되었습니다."
record "popup-process-after=$pid_after"
[[ "$pid_after" == "$pid_before" ]] || fail "Samsung Internet process가 수동 확인 중 교체되었습니다."
record 'browser-process-survived=true'
record 'gate-result=passed'
record "evidence=$evidence_log"
