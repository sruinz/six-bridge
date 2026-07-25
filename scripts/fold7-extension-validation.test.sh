#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
validation="$project_dir/validation/extensions.json"
library="$project_dir/scripts/fold7-validation-lib.sh"

# shellcheck source=fold7-validation-lib.sh
. "$library"

expect_failure() {
  local name=$1
  shift
  if "$@" >/dev/null 2>&1; then
    echo "실패해야 할 검증이 통과했습니다: $name" >&2
    exit 1
  fi
}

expect_exit() {
  local expected=$1
  local name=$2
  shift 2
  local actual
  set +e
  "$@" >/dev/null 2>&1
  actual=$?
  set -e
  if [ "$actual" -ne "$expected" ]; then
    echo "$name 예상 exit=$expected, 실제 exit=$actual" >&2
    exit 1
  fi
}

expect_case_failure() {
  local mutation=$1
  local input="$fixture/extensions-$mutation.json"
  jq "$2" "$validation" >"$input"
  expect_failure "잘못된 case: $mutation" \
    six_validation_case "$input" meemjdgdjhjfhcfchppfapgifiipjnlo
}

expect_zip_entry_once() {
  local source_zip=$1
  local entry=$2
  [ "$(zipinfo -1 "$source_zip" | grep -Fxc "$entry")" -eq 1 ]
}

assert_no_reserved_without_pipefail() (
  set +o pipefail
  six_assert_no_reserved_zip_root "$1"
)

fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

case_json=$(six_validation_case "$validation" meemjdgdjhjfhcfchppfapgifiipjnlo)
[ "$(printf '%s' "$case_json" | jq -cS .)" = "$(jq -cS '
  .extensions[] |
  select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo") |
  {id, name, expectedStatus, requiredScenarios, fold7Validation}
' "$validation")" ]

expect_case_failure duplicate_id \
  '.extensions += [.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")]'
expect_case_failure unsafe_slug \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.slug) = "../ai"'
expect_case_failure unknown_contract \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.contract) = "unknown"'
expect_case_failure duplicate_adapter \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.approvedAdapters) = ["action-popup-entry", "action-popup-entry"]'
expect_case_failure unsupported_adapter \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.approvedAdapters) = ["unknown"]'
expect_case_failure non_string_adapter \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.approvedAdapters) = [1]'
expect_case_failure invalid_before_state \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.beforeState) = "unknown"'
expect_case_failure invalid_after_state \
  '(.extensions[] | select(.id == "meemjdgdjhjfhcfchppfapgifiipjnlo")
    .fold7Validation.afterState) = "unknown"'

smart_before="$fixture/smart-before.json"
smart_after="$fixture/smart-after.json"
cat >"$smart_before" <<'EOF'
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
' "$smart_before" >"$smart_after"
six_assert_case_reports "$validation" lneaoklobohnoncgckmbbjfiajgpbdde \
  "$smart_before" "$smart_after"

ai_before="$fixture/ai-before.json"
ai_after="$fixture/ai-after.json"
cat >"$ai_before" <<'EOF'
{
  "status": "compatible",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": [],
  "usedApis": ["tabs", "storage", "runtime", "contextMenus"],
  "issues": [{"code":"storage_area_unknown","blocking":false}]
}
EOF
cat >"$ai_after" <<'EOF'
{
  "status": "compatible",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": [],
  "convertedExtensionId": "meemjdgdjhjfhcfchppfapgifiipjnlo"
}
EOF
six_assert_case_reports "$validation" meemjdgdjhjfhcfchppfapgifiipjnlo \
  "$ai_before" "$ai_after"
jq '.usedApis = ["runtime"]' "$ai_before" >"$fixture/ai-before-invalid.json"
expect_failure "AI 레시피 usedApis 계약" \
  six_assert_case_reports "$validation" meemjdgdjhjfhcfchppfapgifiipjnlo \
  "$fixture/ai-before-invalid.json" "$ai_after"

video_case=$(six_validation_case \
  "$validation" fchehlladkkanoekpjffcfffpfbdaalj)
[ "$(printf '%s' "$video_case" | jq -r '.expectedStatus')" = partial ]
[ "$(printf '%s' "$video_case" |
  jq -r '.fold7Validation.contract')" = video-nas-passthrough-v1 ]
[ "$(printf '%s' "$video_case" |
  jq -c '.fold7Validation.approvedAdapters')" = '[]' ]

video_before="$fixture/video-before.json"
video_after="$fixture/video-after.json"
cat >"$video_before" <<'EOF'
{
  "status": "partial",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": ["webNavigation"],
  "usedApis": [
    "webRequest", "tabs", "storage", "runtime",
    "webNavigation", "contextMenus", "action"
  ],
  "issues": [
    {"code":"unsupported_api","blocking":false},
    {"code":"storage_area_unknown","blocking":false}
  ]
}
EOF
cat >"$video_after" <<'EOF'
{
  "status": "partial",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": ["webNavigation"],
  "usedApis": [
    "action", "contextMenus", "runtime", "storage",
    "tabs", "webNavigation", "webRequest"
  ],
  "issues": [
    {"code":"unsupported_api","blocking":false},
    {"code":"storage_area_unknown","blocking":false}
  ],
  "convertedExtensionId": "abcdefghijklmnopabcdefghijklmnop"
}
EOF
six_assert_case_reports "$validation" \
  fchehlladkkanoekpjffcfffpfbdaalj "$video_before" "$video_after"
jq '.unsupportedApis = []' "$video_before" >"$fixture/video-before-no-api.json"
expect_failure "Video NAS unsupportedApis 계약" \
  six_assert_case_reports "$validation" \
  fchehlladkkanoekpjffcfffpfbdaalj \
  "$fixture/video-before-no-api.json" "$video_after"
jq '.adapterDecisions = [{"id":"storage-fallback"}]' "$video_before" \
  >"$fixture/video-before-adapter.json"
expect_failure "Video NAS adapter 계약" \
  six_assert_case_reports "$validation" \
  fchehlladkkanoekpjffcfffpfbdaalj \
  "$fixture/video-before-adapter.json" "$video_after"
jq '.status = "compatible"' "$video_before" >"$fixture/video-before-compatible.json"
expect_failure "Video NAS status 계약" \
  six_assert_case_reports "$validation" \
  fchehlladkkanoekpjffcfffpfbdaalj \
  "$fixture/video-before-compatible.json" "$video_after"

smart_root="$fixture/smart-source"
mkdir -p "$smart_root/sixbridge"
printf '%s\n' \
  '{"manifest_version":3,"options_ui":{"page":"popup.html","open_in_tab":true}}' \
  >"$smart_root/manifest.json"
printf 'provenance\n' >"$smart_root/sixbridge/action-popup-entry.v2.json"
printf 'bridge\n' >"$smart_root/sixbridge/desktop-selection-event-bridge.v1.js"
smart_zip="$fixture/smart.zip"
(
  cd "$smart_root"
  zip -q -X -r "$smart_zip" .
)
six_assert_transformed_contract smart-context-v2 "$smart_zip"

ai_root="$fixture/ai-source"
mkdir -p "$ai_root/images"
printf '%s\n' \
  '{"manifest_version":3,"background":{"service_worker":"background.js"},"options_ui":{"page":"options.html","open_in_tab":false},"permissions":["storage","activeTab","contextMenus"]}' \
  >"$ai_root/manifest.json"
for entry in background.js options.html options.js; do
  printf '%s\n' "$entry" >"$ai_root/$entry"
done
printf '다국어 ZIP 항목 검증\n' >"$ai_root/검증-파일.txt"
for entry in icon16.png icon48.png icon128.png; do
  printf '%s\n' "$entry" >"$ai_root/images/$entry"
done
ai_zip="$fixture/ai.zip"
(
  cd "$ai_root"
  zip -q -X -r "$ai_zip" .
)
six_assert_transformed_contract ai-recipe-passthrough-v1 "$ai_zip"
for entry in \
  background.js options.html options.js \
  images/icon16.png images/icon48.png images/icon128.png; do
  expect_zip_entry_once "$ai_zip" "$entry"
done

video_root="$fixture/video-source"
mkdir -p "$video_root"
cat >"$video_root/manifest.json" <<'EOF'
{
  "manifest_version": 3,
  "name": "Video NAS fixture",
  "version": "1.0",
  "permissions": ["contextMenus", "storage", "activeTab", "webRequest"],
  "host_permissions": ["<all_urls>"],
  "background": {"service_worker": "background.js"},
  "action": {},
  "content_scripts": [{
    "matches": ["<all_urls>"],
    "js": ["content.js"]
  }],
  "options_ui": {"page": "options.html", "open_in_tab": false}
}
EOF
for entry in background.js content.js options.html options.js options.css; do
  printf '%s\n' "$entry" >"$video_root/$entry"
done
video_zip="$fixture/video.zip"
(
  cd "$video_root"
  zip -q -X -r "$video_zip" .
)
six_assert_transformed_contract video-nas-passthrough-v1 "$video_zip"
mv "$video_root/options.css" "$fixture/video-options.css"
video_missing_entry_zip="$fixture/video-missing-entry.zip"
(
  cd "$video_root"
  zip -q -X -r "$video_missing_entry_zip" .
)
mv "$fixture/video-options.css" "$video_root/options.css"
expect_failure "Video NAS ZIP 필수 entry" \
  six_assert_transformed_contract video-nas-passthrough-v1 "$video_missing_entry_zip"
mkdir "$video_root/_metadata"
printf 'reserved\n' >"$video_root/_metadata/foreign.json"
video_reserved_zip="$fixture/video-reserved.zip"
(
  cd "$video_root"
  zip -q -X -r "$video_reserved_zip" .
)
expect_failure "Video NAS ZIP 예약 root" \
  six_assert_transformed_contract video-nas-passthrough-v1 "$video_reserved_zip"
mv "$video_root/_metadata" "$fixture/video-metadata"
jq 'del(.permissions[] | select(. == "webRequest"))' "$video_root/manifest.json" \
  >"$fixture/video-manifest.json"
mv "$fixture/video-manifest.json" "$video_root/manifest.json"
video_permission_zip="$fixture/video-no-permission.zip"
(
  cd "$video_root"
  zip -q -X -r "$video_permission_zip" .
)
expect_failure "Video NAS ZIP permission" \
  six_assert_transformed_contract video-nas-passthrough-v1 "$video_permission_zip"

mkdir "$ai_root/_metadata"
printf 'reserved\n' >"$ai_root/_metadata/foreign.json"
reserved_zip="$fixture/ai-reserved.zip"
(
  cd "$ai_root"
  zip -q -X -r "$reserved_zip" .
)
expect_failure "예약 ZIP root" \
  six_assert_transformed_contract ai-recipe-passthrough-v1 "$reserved_zip"
printf '손상 ZIP\n' >"$fixture/corrupt.zip"
expect_failure "손상 ZIP" \
  six_assert_no_reserved_zip_root "$fixture/corrupt.zip"
expect_failure "손상 ZIP(pipefail 비활성)" \
  assert_no_reserved_without_pipefail "$fixture/corrupt.zip"
expect_failure "알 수 없는 ZIP contract" \
  six_assert_transformed_contract unknown "$ai_zip"

runner="$project_dir/scripts/fold7-extension-validation.sh"
ai_wrapper="$project_dir/scripts/fold7-ai-recipe.sh"
expect_exit 2 "AI wrapper --source 필수" "$ai_wrapper"
expect_exit 0 "AI wrapper 도움말" "$ai_wrapper" --help
expect_exit 2 "AI wrapper case ID 고정" \
  "$ai_wrapper" --case-id lneaoklobohnoncgckmbbjfiajgpbdde
expect_failure "공용 runner --case-id 필수" \
  "$runner" --source "$ai_root" --host-only
expect_failure "공용 runner --source 필수" \
  "$runner" --case-id meemjdgdjhjfhcfchppfapgifiipjnlo --host-only
expect_failure "공용 runner host/device 모드 충돌" \
  "$runner" \
    --case-id meemjdgdjhjfhcfchppfapgifiipjnlo \
    --source "$ai_root" \
    --host-only \
    --serial fixture-serial

run_rejected_case_fixture() {
  variant=$1
  rejected_case_json=$2
  rejected_project="$fixture/rejected-$variant"
  rejected_source="$fixture/rejected-source-$variant"
  rejected_profile="$rejected_project/profile.json"
  rejected_cli="$rejected_project/cli/build/install/sixbridge/bin/sixbridge"
  rejected_calls="$fixture/rejected-calls-$variant.txt"
  rejected_output="$fixture/rejected-output-$variant.txt"
  mkdir -p \
    "$rejected_project/scripts" \
    "$(dirname -- "$rejected_cli")" \
    "$rejected_source"
  cp "$runner" "$rejected_project/scripts/fold7-extension-validation.sh"
  cp "$library" "$rejected_project/scripts/fold7-validation-lib.sh"
  cat >>"$rejected_project/scripts/fold7-validation-lib.sh" <<'EOF'

six_validation_case() {
  printf '%s\n' "$SIX_TEST_CASE_JSON"
}
EOF
  printf '{}\n' >"$rejected_project/extensions.json"
  cat >"$rejected_profile" <<'EOF'
{
  "browserPackage": "com.sec.android.app.sbrowser",
  "versionName": "30.0.0.67",
  "versionCode": 1300067502
}
EOF
  printf 'source\n' >"$rejected_source/source.txt"
  cat >"$rejected_cli" <<'EOF'
#!/bin/bash
set -euo pipefail
printf 'called\n' >>"$SIX_TEST_CLI_CALLS"
exit 9
EOF
  chmod +x "$rejected_cli"
  touch "$rejected_calls"

  set +e
  SIX_VALIDATION_MANIFEST="$rejected_project/extensions.json" \
    SIX_TEST_CASE_JSON="$rejected_case_json" \
    SIX_TEST_CLI_CALLS="$rejected_calls" \
    "$rejected_project/scripts/fold7-extension-validation.sh" \
      --case-id fixture-case \
      --source "$rejected_source" \
      --profile "$rejected_profile" \
      --host-only >"$rejected_output" 2>&1
  rejected_exit=$?
  set -e
  if [ "$rejected_exit" -ne 2 ]; then
    cat "$rejected_output" >&2
    echo "$variant fixture 예상 exit=2, 실제 exit=$rejected_exit" >&2
    exit 1
  fi
  [ ! -s "$rejected_calls" ] || {
    echo "$variant fixture가 CLI를 호출했습니다." >&2
    exit 1
  }
  if [ -e "$rejected_project/.local" ] ||
    [ -e "$rejected_project/dist" ]; then
    echo "$variant fixture가 검증 경계 디렉터리를 만들었습니다." >&2
    exit 1
  fi
  if find "$rejected_project" -name '*escape*' -print -quit |
    grep -q .; then
    echo "unsafe slug가 검증 경계 밖 파일을 만들었습니다." >&2
    exit 1
  fi
}

run_rejected_case_fixture missing-adapters \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"fixture","contract":"ai-recipe-passthrough-v1"}}'
run_rejected_case_fixture malformed-adapters \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"fixture","contract":"ai-recipe-passthrough-v1","approvedAdapters":"broken"}}'
run_rejected_case_fixture empty-adapter \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"fixture","contract":"ai-recipe-passthrough-v1","approvedAdapters":[""]}}'
run_rejected_case_fixture unsafe-slug \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"../escape","contract":"ai-recipe-passthrough-v1","approvedAdapters":[]}}'
run_rejected_case_fixture unsafe-separator \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"escape/name","contract":"ai-recipe-passthrough-v1","approvedAdapters":[]}}'
run_rejected_case_fixture unsafe-control \
  '{"id":"fixture-case","name":"fixture","fold7Validation":{"slug":"escape\tslug","contract":"ai-recipe-passthrough-v1","approvedAdapters":[]}}'

fixture_project="$fixture/project"
fixture_source="$fixture/non-git-source"
fixture_profile="$fixture_project/profile.json"
fixture_cli="$fixture_project/cli/build/install/sixbridge/bin/sixbridge"
fixture_manifest="$fixture/extensions.json"
mkdir -p \
  "$fixture_project/scripts" \
  "$(dirname -- "$fixture_cli")"
cp "$runner" "$fixture_project/scripts/fold7-extension-validation.sh"
cp "$ai_wrapper" "$fixture_project/scripts/fold7-ai-recipe.sh"
cp "$library" "$fixture_project/scripts/fold7-validation-lib.sh"
cp "$validation" "$fixture_manifest"
cp -R "$project_dir/fixtures/ai-recipe-pattern" "$fixture_source"
mkdir "$fixture_source/images"
cat >"$fixture_profile" <<'EOF'
{
  "browserPackage": "com.sec.android.app.sbrowser",
  "versionName": "30.0.0.67",
  "versionCode": 1300067502
}
EOF
for entry in icon16.png icon48.png icon128.png; do
  printf '%s\n' "$entry" >"$fixture_source/images/$entry"
done
printf 'source 불변성 포함 대상\n' >"$fixture_source/.DS_Store"

fixture_before="$fixture/ai-runner-before.json"
fixture_after="$fixture/ai-runner-after.json"
cat >"$fixture_before" <<'EOF'
{
  "status": "compatible",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": [],
  "usedApis": ["tabs", "storage", "runtime", "contextMenus"],
  "issues": [{"code": "storage_area_unknown", "blocking": false}]
}
EOF
cat >"$fixture_after" <<'EOF'
{
  "status": "compatible",
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "unsupportedApis": [],
  "convertedExtensionId": "meemjdgdjhjfhcfchppfapgifiipjnlo"
}
EOF
fixture_transformed="$fixture/ai-runner-transformed.zip"
(
  cd "$fixture_source"
  zip -q -X -r "$fixture_transformed" .
)
fixture_draft_root="$fixture/ai-runner-draft"
fixture_draft="$fixture/ai-runner.sixdraft"
mkdir "$fixture_draft_root"
cp "$fixture_transformed" "$fixture_draft_root/source.zip"
(
  cd "$fixture_draft_root"
  zip -q -X "$fixture_draft" source.zip
)
fixture_bundle_root="$fixture/ai-runner-bundle"
fixture_bundle="$fixture/ai-runner.six"
fixture_crx="$fixture/ai-runner.crx"
fixture_wrapper="$fixture/ai-runner-wrapper.apk"
mkdir "$fixture_bundle_root"
cat >"$fixture_bundle_root/extension.json" <<'EOF'
{
  "extensionId": "meemjdgdjhjfhcfchppfapgifiipjnlo",
  "wrapperPackage": "dev.sixbridge.ext.meemjdgdjhjfhcfchppfapgifiipjnlo",
  "wrapperVersionCode": 10
}
EOF
cp "$fixture_after" "$fixture_bundle_root/compatibility.json"
(
  cd "$fixture_bundle_root"
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
input=
output=
approved_adapter=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input)
      input=$2
      shift 2
      ;;
    --output)
      output=$2
      shift 2
      ;;
    --approve-adapter)
      approved_adapter=true
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if [ "$approved_adapter" = true ]; then
  echo "AI fixture에 승인 adapter가 전달되었습니다." >&2
  exit 1
fi
case "$command_name" in
  analyze)
    if [ "${SIX_TEST_MUTATE_SOURCE:-false}" = true ]; then
      printf '검증 중 변경\n' >>"$input/background.js"
    fi
    cp "$SIX_TEST_BEFORE" "$output"
    ;;
  draft)
    cp "$SIX_TEST_DRAFT" "$output"
    ;;
  verify-draft|verify-build)
    ;;
  build)
    cp "$SIX_TEST_BUNDLE" "$output"
    artifact_stem=${output%.six}
    cp "$SIX_TEST_CRX" "${artifact_stem}.crx"
    cp "$SIX_TEST_WRAPPER" "${artifact_stem}-wrapper.apk"
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

run_runner_fixture() {
  output_file=$1
  shift
  draft=${SIX_TEST_DRAFT_OVERRIDE:-$fixture_draft}
  SIX_VALIDATION_MANIFEST="$fixture_manifest" \
    SIX_TEST_BEFORE="$fixture_before" \
    SIX_TEST_DRAFT="$draft" \
    SIX_TEST_BUNDLE="$fixture_bundle" \
    SIX_TEST_CRX="$fixture_crx" \
    SIX_TEST_WRAPPER="$fixture_wrapper" \
    "$fixture_project/scripts/fold7-extension-validation.sh" \
      --case-id meemjdgdjhjfhcfchppfapgifiipjnlo \
      --source "$fixture_source" \
      --profile "$fixture_profile" \
      "$@" >"$output_file" 2>&1
}

run_ai_wrapper_fixture() {
  output_file=$1
  shift
  draft=${SIX_TEST_DRAFT_OVERRIDE:-$fixture_draft}
  SIX_VALIDATION_MANIFEST="$fixture_manifest" \
    SIX_TEST_BEFORE="$fixture_before" \
    SIX_TEST_DRAFT="$draft" \
    SIX_TEST_BUNDLE="$fixture_bundle" \
    SIX_TEST_CRX="$fixture_crx" \
    SIX_TEST_WRAPPER="$fixture_wrapper" \
    "$fixture_project/scripts/fold7-ai-recipe.sh" \
      --source "$fixture_source" \
      --profile "$fixture_profile" \
      "$@" >"$output_file" 2>&1
}

runner_output="$fixture/runner-output.txt"
run_runner_fixture "$runner_output" --host-only
grep -Fx 'caseId=meemjdgdjhjfhcfchppfapgifiipjnlo' "$runner_output" >/dev/null
grep -Fx 'caseSlug=ai-recipe' "$runner_output" >/dev/null
grep -Fx 'sourceGitRepository=false' "$runner_output" >/dev/null
grep -Fx 'sourceUnchanged=true' "$runner_output" >/dev/null
grep -Fx 'draftDeterministic=true' "$runner_output" >/dev/null || {
  cat "$runner_output" >&2
  echo "공용 runner deterministic 출력을 찾을 수 없습니다." >&2
  exit 1
}
grep -Fx 'hostContract=true' "$runner_output" >/dev/null
grep -Fx 'deviceChecks=skipped' "$runner_output" >/dev/null
test -f "$fixture_project/dist/ai-recipe-converted.six"
test -f "$fixture_project/dist/ai-recipe-converted.crx"
test -f "$fixture_project/dist/ai-recipe-converted-wrapper.apk"
test -f "$fixture_project/.local/evidence/ai-recipe-before.json"
test -f "$fixture_project/.local/evidence/ai-recipe-a.sixdraft"
test -f "$fixture_project/.local/evidence/ai-recipe-b.sixdraft"
test -f "$fixture_project/.local/evidence/ai-recipe-extension.json"
test -f "$fixture_project/.local/evidence/ai-recipe-after.json"

wrapper_output="$fixture/wrapper-output.txt"
run_ai_wrapper_fixture "$wrapper_output" --host-only
grep -Fx 'caseId=meemjdgdjhjfhcfchppfapgifiipjnlo' "$wrapper_output" >/dev/null
grep -Fx 'caseSlug=ai-recipe' "$wrapper_output" >/dev/null
grep -Fx 'draftDeterministic=true' "$wrapper_output" >/dev/null
grep -Fx 'hostContract=true' "$wrapper_output" >/dev/null
grep -Fx 'deviceChecks=skipped' "$wrapper_output" >/dev/null

reserved_draft_root="$fixture/ai-reserved-draft"
reserved_draft="$fixture/ai-reserved.sixdraft"
mkdir "$reserved_draft_root"
cp "$reserved_zip" "$reserved_draft_root/source.zip"
(
  cd "$reserved_draft_root"
  zip -q -X "$reserved_draft" source.zip
)
reserved_output="$fixture/wrapper-reserved-output.txt"
set +e
SIX_TEST_DRAFT_OVERRIDE="$reserved_draft" \
  run_ai_wrapper_fixture "$reserved_output" --host-only
reserved_exit=$?
set -e
if [ "$reserved_exit" -ne 3 ]; then
  cat "$reserved_output" >&2
  echo "예약 ZIP runner fixture 예상 exit=3, 실제 exit=$reserved_exit" >&2
  exit 1
fi

mutation_output="$fixture/runner-mutation-output.txt"
set +e
SIX_TEST_MUTATE_SOURCE=true \
  run_runner_fixture "$mutation_output" --host-only
mutation_exit=$?
set -e
if [ "$mutation_exit" -ne 4 ]; then
  cat "$mutation_output" >&2
  echo "source mutation fixture 예상 exit=4, 실제 exit=$mutation_exit" >&2
  exit 1
fi
grep -Fx '실제 source가 검증 중 변경되었습니다.' \
  "$mutation_output" >/dev/null

printf '%s\n' 'background.js' >"$fixture_source/background.js"
fixture_bin="$fixture/bin"
fixture_adb="$fixture_bin/adb"
fixture_dump="$fixture/browser-dump.txt"
device_calls="$fixture/device-calls.txt"
device_output="$fixture/device-output.txt"
mkdir "$fixture_bin"
touch "$device_calls"
cat >"$fixture_dump" <<'EOF'
Package [com.sec.android.app.sbrowser] (abc):
    versionCode=1300261502 minSdk=30 targetSdk=36
    versionName=30.0.2.61
EOF
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
      exit 1
    fi
    ;;
  *)
    exit 1
    ;;
esac
EOF
chmod +x "$fixture_adb"
set +e
PATH="$fixture_bin:$PATH" \
  SIX_TEST_DEVICE_CALLS="$device_calls" \
  SIX_TEST_BROWSER_DUMP="$fixture_dump" \
  run_runner_fixture "$device_output" --serial fixture-serial
device_exit=$?
set -e
if [ "$device_exit" -ne 3 ]; then
  cat "$device_output" >&2
  echo "profile mismatch fixture 예상 exit=3, 실제 exit=$device_exit" >&2
  exit 1
fi
grep -Fx 'profileMatch=false' "$device_output" >/dev/null
grep -Fx 'deviceChecks=failed' "$device_output" >/dev/null
[ "$(grep -c '^install$' "$device_calls" || true)" -eq 0 ]
[ "$(grep -c '^smoke$' "$device_calls" || true)" -eq 0 ]

echo "fold7 extension validation shell tests passed"
