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

mkdir "$ai_root/_metadata"
printf 'reserved\n' >"$ai_root/_metadata/foreign.json"
reserved_zip="$fixture/ai-reserved.zip"
(
  cd "$ai_root"
  zip -q -X -r "$reserved_zip" .
)
expect_failure "예약 ZIP root" \
  six_assert_transformed_contract ai-recipe-passthrough-v1 "$reserved_zip"
expect_failure "알 수 없는 ZIP contract" \
  six_assert_transformed_contract unknown "$ai_zip"

echo "fold7 extension validation shell tests passed"
