#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
script="$project_dir/scripts/fold7-converted-extension.sh"
library="$project_dir/scripts/fold7-validation-lib.sh"

# shellcheck source=fold7-validation-lib.sh
. "$library"

expect_exit() {
  expected=$1
  shift
  set +e
  "$@" >/dev/null 2>&1
  actual=$?
  set -e
  if [ "$actual" -ne "$expected" ]; then
    echo "예상 exit=$expected, 실제 exit=$actual: $*" >&2
    exit 1
  fi
}

expect_exit 0 "$script" --help
expect_exit 2 "$script" --unknown-option
expect_exit 2 "$script" --host-only --serial serial-1
expect_exit 2 "$script"

fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
profile="$fixture/profile.json"
cat >"$profile" <<'EOF'
{
  "browserPackage": "com.sec.android.app.sbrowser",
  "versionName": "30.0.2.61",
  "versionCode": 1300261502
}
EOF
dump="$fixture/dumpsys.txt"
cat >"$dump" <<'EOF'
Package [com.sec.android.app.sbrowser] (abc):
    versionCode=1300261502 minSdk=30 targetSdk=36
    versionName=30.0.2.61
EOF

six_profile_matches "$dump" "$profile"
for mutation in package version version_code missing_package missing_version missing_code; do
  mutated="$fixture/dumpsys-$mutation.txt"
  case "$mutation" in
    package) sed 's/com.sec.android.app.sbrowser/com.example.wrong/' "$dump" >"$mutated" ;;
    version) sed 's/30.0.2.61/30.0.0.67/' "$dump" >"$mutated" ;;
    version_code) sed 's/1300261502/1300067502/' "$dump" >"$mutated" ;;
    missing_package) sed '/Package \[/d' "$dump" >"$mutated" ;;
    missing_version) sed '/versionName=/d' "$dump" >"$mutated" ;;
    missing_code) sed '/versionCode=/d' "$dump" >"$mutated" ;;
  esac
  if six_profile_matches "$mutated" "$profile"; then
    echo "profile mismatch를 허용했습니다: $mutation" >&2
    exit 1
  fi
done

safe_root="$fixture/safe-root"
external="$fixture/external"
mkdir "$safe_root" "$external"
printf '외부 원본\n' >"$external/marker"
mkdir "$safe_root/.local"
ln -s "$external" "$safe_root/.local/evidence"
if six_secure_directory "$safe_root/.local/evidence" "$safe_root/.local"; then
  echo "evidence directory symlink를 허용했습니다." >&2
  exit 1
fi
grep -Fx '외부 원본' "$external/marker" >/dev/null

publish_parent="$fixture/publish"
mkdir "$publish_parent"
printf '외부 대상\n' >"$external/target"
ln -s "$external/target" "$publish_parent/report.json"
printf '새 보고서\n' >"$publish_parent/.report.tmp"
six_atomic_publish \
  "$publish_parent/.report.tmp" \
  "$publish_parent/report.json" \
  "$publish_parent"
[ ! -L "$publish_parent/report.json" ]
grep -Fx '새 보고서' "$publish_parent/report.json" >/dev/null
grep -Fx '외부 대상' "$external/target" >/dev/null

before="$fixture/before.json"
after="$fixture/after.json"
cat >"$before" <<'EOF'
{
  "status": "compatible",
  "conversionState": "unavailable",
  "adapterDecisions": [
    {
      "id": "action-popup-entry",
      "version": 2,
      "risk": "behavior-change",
      "disposition": "requires-approval",
      "applied": false,
      "evidence": [{"relativePath":"manifest.json","line":12,"detail":"action.default_popup=popup.html"}],
      "changes": ["삼성 Add-ons 설정 메뉴에 기존 action popup을 연결합니다."],
      "remainingLimitations": ["popup은 삼성 Add-ons 상세 화면의 설정 메뉴에서 별도 탭으로 열립니다."]
    },
    {
      "id": "desktop-selection-event-bridge",
      "version": 1,
      "risk": "safe",
      "disposition": "automatic",
      "applied": false,
      "evidence": [{"relativePath":"content.js","line":159,"detail":"정적인 mouseup 선택 listener"}],
      "changes": ["해당 content script 앞에 모바일 선택 event bridge를 추가합니다."],
      "remainingLimitations": []
    },
    {
      "id": "storage-fallback",
      "version": 1,
      "risk": "safe",
      "disposition": "unavailable",
      "applied": false,
      "evidence": [{"relativePath":"content.js","line":77,"detail":"global root identifier가 있어 자동 변환할 수 없습니다."}],
      "changes": [],
      "remainingLimitations": ["동적 또는 계산된 storage 접근의 의미를 안전하게 보존할 수 없습니다."]
    }
  ]
}
EOF
jq '
  .conversionState = "converted" |
  .adapterDecisions |= map(
    if .id == "action-popup-entry" or .id == "desktop-selection-event-bridge"
    then .applied = true else . end
  )
' "$before" >"$after"

six_assert_conversion_reports "$before" "$after"
for mutation in extra popup_version popup_evidence popup_risk popup_change popup_limit; do
  broken="$fixture/after-$mutation.json"
  case "$mutation" in
    extra) jq '.adapterDecisions += [.adapterDecisions[0] | .id = "extra"]' "$after" >"$broken" ;;
    popup_version) jq '(.adapterDecisions[] | select(.id=="action-popup-entry") | .version) = 1' "$after" >"$broken" ;;
    popup_evidence) jq '(.adapterDecisions[] | select(.id=="action-popup-entry") | .evidence[0].detail) = "변조"' "$after" >"$broken" ;;
    popup_risk) jq '(.adapterDecisions[] | select(.id=="action-popup-entry") | .risk) = "safe"' "$after" >"$broken" ;;
    popup_change) jq '(.adapterDecisions[] | select(.id=="action-popup-entry") | .changes) = ["변조"]' "$after" >"$broken" ;;
    popup_limit) jq '(.adapterDecisions[] | select(.id=="action-popup-entry") | .remainingLimitations) = []' "$after" >"$broken" ;;
  esac
  if six_assert_conversion_reports "$before" "$broken"; then
    echo "변조 report를 허용했습니다: $mutation" >&2
    exit 1
  fi
done

fixture_project="$fixture/project"
fixture_source="$fixture/source"
fixture_profile="$fixture_project/profiles/sbrowser-30.0.0.67.json"
fixture_cli="$fixture_project/cli/build/install/sixbridge/bin/sixbridge"
mkdir -p \
  "$fixture_project/scripts" \
  "$(dirname -- "$fixture_profile")" \
  "$(dirname -- "$fixture_cli")" \
  "$fixture_source"
fixture_profile_dir=$(CDPATH= cd -- "$(dirname -- "$fixture_profile")" && pwd -P)
fixture_profile_real="$fixture_profile_dir/$(basename -- "$fixture_profile")"
cp "$script" "$fixture_project/scripts/fold7-converted-extension.sh"
cp "$library" "$fixture_project/scripts/fold7-validation-lib.sh"
jq '
  .versionName = "30.0.0.67" |
  .versionCode = 1300067502
' "$profile" >"$fixture_profile"
git -C "$fixture_source" init -q
printf 'fixture source\n' >"$fixture_source/source.txt"

fixture_bundle="$fixture/bundle.six"
fixture_crx="$fixture/extension.crx"
fixture_wrapper="$fixture/wrapper.apk"
fixture_bundle_dir="$fixture/bundle"
mkdir "$fixture_bundle_dir"
cat >"$fixture_bundle_dir/extension.json" <<'EOF'
{
  "extensionId": "dncifaeoabkkmddoeeccpjdkifeoemfd",
  "wrapperPackage": "dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd",
  "wrapperVersionCode": 10
}
EOF
cp "$after" "$fixture_bundle_dir/compatibility.json"
(
  cd "$fixture_bundle_dir"
  zip -q -X "$fixture_bundle" extension.json compatibility.json
)
printf 'Cr24fixture\n' >"$fixture_crx"
printf 'fixture wrapper\n' >"$fixture_wrapper"

cat >"$fixture_cli" <<'EOF'
#!/bin/bash
set -euo pipefail

command_name=$1
shift
device_command=
if [ "$command_name" = device ]; then
  device_command=$1
  shift
fi
output=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output)
      output=$2
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

case "$command_name" in
  analyze)
    cp "$SIX_TEST_BEFORE" "$output"
    ;;
  draft)
    cp "$SIX_TEST_DRAFT" "$output"
    ;;
  verify-draft|verify-build)
    ;;
  build)
    cp "$SIX_TEST_BUNDLE" "$output"
    artifact_dir=$(dirname -- "$output")
    cp "$SIX_TEST_CRX" "$artifact_dir/smart-context-converted.crx"
    cp "$SIX_TEST_WRAPPER" \
      "$artifact_dir/smart-context-converted-wrapper.apk"
    ;;
  device)
    printf '%s\n' "$device_command" >>"$SIX_TEST_DEVICE_CALLS"
    ;;
  *)
    echo "예상하지 않은 fixture command입니다: $command_name" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$fixture_cli"

fixture_bin="$fixture/bin"
fixture_adb="$fixture_bin/adb"
mkdir "$fixture_bin"
cat >"$fixture_adb" <<'EOF'
#!/bin/bash
set -euo pipefail

case "$1" in
  devices)
    printf '%s\n' \
      'List of devices attached' \
      'fixture-serial device product:fixture model:fixture'
    ;;
  -s)
    shift 2
    if [ "$*" = "shell dumpsys package com.sec.android.app.sbrowser" ]; then
      cat "$SIX_TEST_BROWSER_DUMP"
    else
      echo "예상하지 않은 fixture adb command입니다: $*" >&2
      exit 1
    fi
    ;;
  *)
    echo "예상하지 않은 fixture adb command입니다: $*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$fixture_adb"

run_option_entry_fixture() {
  variant=$1
  expected=$2
  profile_mode=$3
  source_root="$fixture/transformed-$variant"
  source_zip="$fixture/transformed-$variant.zip"
  draft_zip="$fixture/draft-$variant.sixdraft"
  output_file="$fixture/output-$variant.txt"
  mkdir "$source_root"
  case "$variant" in
    missing_options_ui)
      printf '{"manifest_version":3}\n' >"$source_root/manifest.json"
      mkdir "$source_root/sixbridge"
      printf 'provenance\n' \
        >"$source_root/sixbridge/action-popup-entry.v2.json"
      ;;
    *)
      printf '%s\n' \
        '{"manifest_version":3,"options_ui":{"page":"popup.html","open_in_tab":true}}' \
        >"$source_root/manifest.json"
      if [ "$variant" != missing_provenance ]; then
        mkdir "$source_root/sixbridge"
        printf 'provenance\n' \
          >"$source_root/sixbridge/action-popup-entry.v2.json"
      fi
      ;;
  esac
  if [ "$variant" = reserved_provenance ]; then
    mkdir "$source_root/_sixbridge"
    printf 'reserved provenance\n' \
      >"$source_root/_sixbridge/action-popup-entry.v2.json"
  fi
  if [ "$variant" != missing_selection ]; then
    mkdir -p "$source_root/sixbridge"
    printf 'selection bridge\n' \
      >"$source_root/sixbridge/desktop-selection-event-bridge.v1.js"
  fi
  if [ "$variant" = reserved_selection ]; then
    mkdir -p "$source_root/_sixbridge"
    printf 'reserved selection bridge\n' \
      >"$source_root/_sixbridge/desktop-selection-event-bridge.v1.js"
  fi
  if [ "$variant" = arbitrary_reserved_root ]; then
    mkdir -p "$source_root/_metadata"
    printf 'foreign metadata\n' >"$source_root/_metadata/foreign.json"
  fi
  case "$variant" in
    legacy_content)
      printf 'legacy\n' >"$source_root/action-popup-entry-content.v1.js"
      ;;
    legacy_background)
      printf 'legacy\n' >"$source_root/action-popup-entry-background.v1.js"
      ;;
    legacy_bootstrap)
      printf 'legacy\n' \
        >"$source_root/_sixbridge-action-popup-entry-bootstrap.v1.js"
      ;;
    legacy_content_backup)
      printf 'backup\n' \
        >"$source_root/action-popup-entry-content.v1.js.backup"
      ;;
  esac
  (
    cd "$source_root"
    zip -q -X -r "$source_zip" .
  )
  draft_root="$fixture/draft-root-$variant"
  mkdir "$draft_root"
  cp "$source_zip" "$draft_root/source.zip"
  (
    cd "$draft_root"
    zip -q -X "$draft_zip" source.zip
  )

  set -- --host-only --source "$fixture_source"
  if [ "$profile_mode" = explicit ]; then
    set -- "$@" --profile "$fixture_profile"
  fi
  set +e
  SIX_TEST_BEFORE="$before" \
    SIX_TEST_DRAFT="$draft_zip" \
    SIX_TEST_BUNDLE="$fixture_bundle" \
    SIX_TEST_CRX="$fixture_crx" \
    SIX_TEST_WRAPPER="$fixture_wrapper" \
    "$fixture_project/scripts/fold7-converted-extension.sh" "$@" \
    >"$output_file" 2>&1
  actual=$?
  set -e
  if [ "$actual" -ne "$expected" ]; then
    cat "$output_file" >&2
    echo "$variant fixture 예상 exit=$expected, 실제 exit=$actual" >&2
    exit 1
  fi
  if [ "$expected" -eq 0 ]; then
    grep -Fx 'optionsEntryContract=true' "$output_file" >/dev/null
    grep -Fx 'persistentActionButtonAbsent=true' "$output_file" >/dev/null
  fi
}

for mutation in \
  missing_options_ui \
  missing_provenance \
  reserved_provenance \
  missing_selection \
  reserved_selection \
  arbitrary_reserved_root \
  legacy_content \
  legacy_background \
  legacy_bootstrap; do
  run_option_entry_fixture "$mutation" 3 explicit
done
run_option_entry_fixture legacy_content_backup 0 explicit
run_option_entry_fixture valid 0 default
grep -Fx "profile=$fixture_profile_real" "$fixture/output-valid.txt" >/dev/null

device_calls="$fixture/device-calls.txt"
device_output="$fixture/output-profile-mismatch.txt"
touch "$device_calls"
set +e
PATH="$fixture_bin:$PATH" \
  SIX_TEST_BEFORE="$before" \
  SIX_TEST_DRAFT="$fixture/draft-valid.sixdraft" \
  SIX_TEST_BUNDLE="$fixture_bundle" \
  SIX_TEST_CRX="$fixture_crx" \
  SIX_TEST_WRAPPER="$fixture_wrapper" \
  SIX_TEST_DEVICE_CALLS="$device_calls" \
  SIX_TEST_BROWSER_DUMP="$dump" \
  "$fixture_project/scripts/fold7-converted-extension.sh" \
    --serial fixture-serial \
    --source "$fixture_source" \
    --profile "$fixture_profile" \
    >"$device_output" 2>&1
device_actual=$?
set -e
if [ "$device_actual" -ne 3 ]; then
  cat "$device_output" >&2
  echo "profile mismatch device fixture 예상 exit=3, 실제 exit=$device_actual" >&2
  exit 1
fi
grep -Fx 'profileMatch=false' "$device_output" >/dev/null
grep -Fx 'deviceChecks=failed' "$device_output" >/dev/null
if grep -Fx 'installSuccess=true' "$device_output" >/dev/null ||
  grep -Fx 'deviceChecks=passed' "$device_output" >/dev/null; then
  echo "profile mismatch를 device 성공으로 기록했습니다." >&2
  exit 1
fi
install_calls=$(grep -c '^install$' "$device_calls" || true)
smoke_calls=$(grep -c '^smoke$' "$device_calls" || true)
if [ "$install_calls" -ne 0 ] || [ "$smoke_calls" -ne 0 ]; then
  echo "profile mismatch에서 device 명령을 호출했습니다: install=$install_calls smoke=$smoke_calls" >&2
  exit 1
fi

echo "fold7 converted extension shell tests passed"
