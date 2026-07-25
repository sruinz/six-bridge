#!/bin/bash

six_secure_directory() {
  local path=$1
  local expected_parent=$2
  local expected_parent_real actual_parent actual
  [ ! -L "$path" ] || return 1
  if [ ! -e "$path" ]; then
    mkdir "$path" || return 1
  fi
  [ -d "$path" ] && [ ! -L "$path" ] || return 1
  expected_parent_real=$(CDPATH= cd -- "$expected_parent" && pwd -P) || return 1
  actual_parent=$(CDPATH= cd -- "$(dirname -- "$path")" && pwd -P) || return 1
  actual=$(CDPATH= cd -- "$path" && pwd -P) || return 1
  [ "$actual_parent" = "$expected_parent_real" ] || return 1
  [ "$actual" = "$expected_parent_real/$(basename -- "$path")" ]
}

six_atomic_publish() {
  local source=$1
  local destination=$2
  local verified_parent=$3
  local verified_parent_real source_parent destination_parent
  [ -f "$source" ] && [ ! -L "$source" ] || return 1
  verified_parent_real=$(CDPATH= cd -- "$verified_parent" && pwd -P) || return 1
  source_parent=$(CDPATH= cd -- "$(dirname -- "$source")" && pwd -P) || return 1
  destination_parent=$(CDPATH= cd -- "$(dirname -- "$destination")" && pwd -P) ||
    return 1
  [ "$source_parent" = "$verified_parent_real" ] || return 1
  [ "$destination_parent" = "$verified_parent_real" ] || return 1
  /bin/mv -f -h -- "$source" "$destination"
}

six_browser_binding() {
  local dump=$1
  local browser_package browser_version browser_version_code
  browser_package=$(sed -n \
    's/^[[:space:]]*Package \[\([^]]*\)\].*/\1/p' "$dump")
  browser_version=$(sed -n \
    's/^[[:space:]]*versionName=\([^[:space:]]*\).*/\1/p' "$dump")
  browser_version_code=$(sed -n \
    's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' "$dump")
  [ "$(printf '%s\n' "$browser_package" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] ||
    return 1
  [ "$(printf '%s\n' "$browser_version" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] ||
    return 1
  [ "$(printf '%s\n' "$browser_version_code" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] ||
    return 1
  printf '%s\t%s\t%s\n' \
    "$browser_package" "$browser_version" "$browser_version_code"
}

six_profile_binding() {
  local profile=$1
  jq -er '
    select(
      ((.browserPackage | type) == "string") and
      ((.browserPackage | length) > 0) and
      ((.versionName | type) == "string") and
      ((.versionName | length) > 0) and
      ((.versionCode | type) == "number") and
      (.versionCode == (.versionCode | floor))
    ) |
    [.browserPackage, .versionName, (.versionCode | tostring)] |
    @tsv
  ' "$profile"
}

six_profile_matches() {
  local dump=$1
  local profile=$2
  local browser_binding profile_binding
  browser_binding=$(six_browser_binding "$dump") || return 1
  profile_binding=$(six_profile_binding "$profile") || return 1
  [ "$browser_binding" = "$profile_binding" ]
}

six_assert_conversion_reports() {
  local before=$1
  local after=$2
  local adapter_id before_object after_object
  jq -e '
    def exact_keys:
      [.adapterDecisions[] | [.id, .version]] | sort ==
      [
        ["action-popup-entry", 2],
        ["desktop-selection-event-bridge", 1],
        ["storage-fallback", 1]
      ];
    .status == "compatible" and
    .conversionState == "unavailable" and
    exact_keys and
    (.adapterDecisions | map(select(
      .id == "action-popup-entry" and
      .risk == "behavior-change" and
      .disposition == "requires-approval" and .applied == false and
      (.evidence | length) == 1 and
      .evidence[0].relativePath == "manifest.json" and
      (.evidence[0].line | type) == "number" and
      .evidence[0].detail == "action.default_popup=popup.html" and
      .changes ==
        ["삼성 Add-ons 설정 메뉴에 기존 action popup을 연결합니다."] and
      .remainingLimitations ==
        ["popup은 삼성 Add-ons 상세 화면의 설정 메뉴에서 별도 탭으로 열립니다."]
    )) | length == 1) and
    (.adapterDecisions | map(select(
      .id == "desktop-selection-event-bridge" and
      .risk == "safe" and
      .disposition == "automatic" and .applied == false and
      (.evidence | length) == 1 and
      .evidence[0].relativePath == "content.js" and
      (.evidence[0].line | type) == "number" and
      .evidence[0].detail == "정적인 mouseup 선택 listener" and
      .changes ==
        ["해당 content script 앞에 모바일 선택 event bridge를 추가합니다."] and
      .remainingLimitations == []
    )) | length == 1) and
    (.adapterDecisions | map(select(
      .id == "storage-fallback" and
      .risk == "safe" and .disposition == "unavailable" and
      .applied == false and .changes == [] and
      .remainingLimitations ==
        ["동적 또는 계산된 storage 접근의 의미를 안전하게 보존할 수 없습니다."]
    )) | length == 1)
  ' "$before" >/dev/null || return 1
  jq -e '
    def exact_keys:
      [.adapterDecisions[] | [.id, .version]] | sort ==
      [
        ["action-popup-entry", 2],
        ["desktop-selection-event-bridge", 1],
        ["storage-fallback", 1]
      ];
    .status == "compatible" and
    .conversionState == "converted" and
    exact_keys and
    (.adapterDecisions | map(select(
      (.id == "action-popup-entry" or
        .id == "desktop-selection-event-bridge") and
      .applied == true
    )) | length == 2) and
    (.adapterDecisions | map(select(
      .id == "storage-fallback" and .applied == false
    )) | length == 1)
  ' "$after" >/dev/null || return 1
  for adapter_id in \
    action-popup-entry \
    desktop-selection-event-bridge \
    storage-fallback; do
    before_object=$(jq -S -c --arg id "$adapter_id" \
      '.adapterDecisions[] | select(.id == $id)' "$before") || return 1
    after_object=$(jq -S -c --arg id "$adapter_id" '
      .adapterDecisions[] |
      select(.id == $id) |
      if $id == "storage-fallback" then . else .applied = false end
    ' "$after") || return 1
    [ -n "$before_object" ] && [ "$before_object" = "$after_object" ] ||
      return 1
  done
}

six_assert_validation_roles() {
  local input=$1
  jq -e '
    ((.extensions | type) == "array") and
    ((.extensions | length) > 0) and
    all(.extensions[];
      (.validationRole == "conversion-candidate") or
      (.validationRole == "boundary-diagnostic"))
  ' "$input" >/dev/null
}

six_validation_case() {
  local input=$1
  local extension_id=$2
  local result slug contract
  six_assert_validation_roles "$input" || return 1
  result=$(jq -cer --arg id "$extension_id" '
    [.extensions[] | select(.id == $id)] |
    select(length == 1) |
    .[0] |
    select(
      (.id | type) == "string" and
      (.name | type) == "string" and
      (.expectedStatus | type) == "string" and
      (.validationRole | type) == "string" and
      (.requiredScenarios | type) == "array" and
      (.fold7Validation | type) == "object" and
      (.fold7Validation.slug | type) == "string" and
      (.fold7Validation.contract | type) == "string" and
      (.fold7Validation.approvedAdapters | type) == "array" and
      (.fold7Validation.beforeState | type) == "string" and
      (.fold7Validation.afterState | type) == "string"
    ) |
    {id, name, expectedStatus, validationRole, requiredScenarios, fold7Validation}
  ' "$input") || return 1

  slug=$(printf '%s' "$result" | jq -er '.fold7Validation.slug') || return 1
  contract=$(printf '%s' "$result" | jq -er '.fold7Validation.contract') || return 1
  [[ "$slug" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || return 1
  case "$contract" in
    smart-context-v2|ai-recipe-passthrough-v1|video-nas-passthrough-v1) ;;
    *) return 1 ;;
  esac
  printf '%s' "$result" | jq -e '
    .fold7Validation.approvedAdapters as $adapters |
    all($adapters[]; type == "string") and
    ($adapters | unique | length) == ($adapters | length) and
    all($adapters[];
      . == "action-popup-entry" or
      . == "desktop-selection-event-bridge" or
      . == "storage-fallback") and
    (.fold7Validation.beforeState == "passthrough" or
      .fold7Validation.beforeState == "automatic" or
      .fold7Validation.beforeState == "requires-approval" or
      .fold7Validation.beforeState == "converted" or
      .fold7Validation.beforeState == "unavailable") and
    (.fold7Validation.afterState == "passthrough" or
      .fold7Validation.afterState == "automatic" or
      .fold7Validation.afterState == "requires-approval" or
      .fold7Validation.afterState == "converted" or
      .fold7Validation.afterState == "unavailable")
  ' >/dev/null || return 1
  printf '%s\n' "$result"
}

six_assert_case_reports() {
  local cases=$1
  local id=$2
  local before=$3
  local after=$4
  local config status before_state after_state contract
  config=$(six_validation_case "$cases" "$id") || return 1
  status=$(printf '%s' "$config" | jq -er '.expectedStatus') || return 1
  before_state=$(printf '%s' "$config" |
    jq -er '.fold7Validation.beforeState') || return 1
  after_state=$(printf '%s' "$config" |
    jq -er '.fold7Validation.afterState') || return 1
  contract=$(printf '%s' "$config" |
    jq -er '.fold7Validation.contract') || return 1

  jq -e --arg status "$status" --arg state "$before_state" \
    '.status == $status and .conversionState == $state' \
    "$before" >/dev/null || return 1
  jq -e --arg status "$status" --arg state "$after_state" \
    '.status == $status and .conversionState == $state' \
    "$after" >/dev/null || return 1

  case "$contract" in
    smart-context-v2)
      six_assert_conversion_reports "$before" "$after"
      ;;
    ai-recipe-passthrough-v1)
      jq -e '
        .status == "compatible" and
        .conversionState == "passthrough" and
        .adapterDecisions == [] and
        .unsupportedApis == [] and
        (.usedApis | sort) ==
          ["contextMenus", "runtime", "storage", "tabs"] and
        any(.issues[]; .code == "storage_area_unknown" and
          .blocking == false)
      ' "$before" >/dev/null &&
      jq -e '
        .status == "compatible" and
        .conversionState == "passthrough" and
        .adapterDecisions == [] and
        .unsupportedApis == [] and
        .convertedExtensionId != null
      ' "$after" >/dev/null
      ;;
    video-nas-passthrough-v1)
      jq -e '
        .status == "partial" and
        .conversionState == "passthrough" and
        .adapterDecisions == [] and
        .unsupportedApis == ["webNavigation"] and
        (.usedApis | sort) ==
          ["action", "contextMenus", "runtime", "storage", "tabs",
           "webNavigation", "webRequest"] and
        any(.issues[]; .code == "unsupported_api" and .blocking == false) and
        any(.issues[]; .code == "storage_area_unknown" and .blocking == false)
      ' "$before" >/dev/null &&
      jq -e '
        .status == "partial" and
        .conversionState == "passthrough" and
        .adapterDecisions == [] and
        .unsupportedApis == ["webNavigation"] and
        (.usedApis | sort) ==
          ["action", "contextMenus", "runtime", "storage", "tabs",
           "webNavigation", "webRequest"] and
        (.convertedExtensionId | type) == "string" and
        (.convertedExtensionId | test("^[a-p]{32}$")) and
        .convertedExtensionId != "fchehlladkkanoekpjffcfffpfbdaalj"
      ' "$after" >/dev/null
      ;;
    *)
      return 1
      ;;
  esac
}

six_assert_no_reserved_zip_root() {
  local source_zip=$1
  local entries
  entries=$(LC_ALL=C zipinfo -1 "$source_zip") || return 1
  printf '%s\n' "$entries" |
    LC_ALL=C awk -F/ '$1 ~ /^_/ { found=1 } END { exit found ? 1 : 0 }'
}

six_assert_zip_entry_once() {
  local source_zip=$1
  local entry=$2
  zipinfo -1 "$source_zip" |
    awk -v entry="$entry" '$0 == entry { count++ } END { exit count != 1 }'
}

six_assert_smart_context_zip() {
  local source_zip=$1
  unzip -p "$source_zip" manifest.json | jq -e '
    .options_ui.page == "popup.html" and
    .options_ui.open_in_tab == true
  ' >/dev/null || return 1
  six_assert_zip_entry_once \
    "$source_zip" sixbridge/action-popup-entry.v2.json || return 1
  six_assert_zip_entry_once \
    "$source_zip" sixbridge/desktop-selection-event-bridge.v1.js || return 1
  ! zipinfo -1 "$source_zip" |
    grep -E '(^|/)(action-popup-entry-(content|background)\.v1\.js|_sixbridge-action-popup-entry-bootstrap\.v1\.js)$'
}

six_assert_ai_recipe_zip() {
  local source_zip=$1
  local entry
  unzip -p "$source_zip" manifest.json | jq -e '
    .manifest_version == 3 and
    .background.service_worker == "background.js" and
    .options_ui.page == "options.html" and
    .options_ui.open_in_tab == false and
    (.permissions | sort) == ["activeTab", "contextMenus", "storage"]
  ' >/dev/null || return 1
  for entry in \
    background.js \
    options.html \
    options.js \
    images/icon16.png \
    images/icon48.png \
    images/icon128.png; do
    six_assert_zip_entry_once "$source_zip" "$entry" || return 1
  done
}

six_assert_video_nas_zip() {
  local source_zip=$1
  local entry
  six_assert_zip_entry_once "$source_zip" manifest.json || return 1
  unzip -p "$source_zip" manifest.json | jq -e '
    .manifest_version == 3 and
    .host_permissions == ["<all_urls>"] and
    .background.service_worker == "background.js" and
    (.action | type) == "object" and
    .options_ui.page == "options.html" and
    .options_ui.open_in_tab == false and
    (.permissions | sort) ==
      ["activeTab", "contextMenus", "storage", "webRequest"] and
    any(.content_scripts[];
      (.matches | index("<all_urls>")) != null and
      (.js | index("content.js")) != null)
  ' >/dev/null || return 1
  for entry in \
    background.js content.js options.html options.js options.css; do
    six_assert_zip_entry_once "$source_zip" "$entry" || return 1
  done
}

six_assert_transformed_contract() {
  local contract=$1
  local source_zip=$2
  six_assert_no_reserved_zip_root "$source_zip" || return 1
  case "$contract" in
    smart-context-v2)
      six_assert_smart_context_zip "$source_zip"
      ;;
    ai-recipe-passthrough-v1)
      six_assert_ai_recipe_zip "$source_zip"
      ;;
    video-nas-passthrough-v1)
      six_assert_video_nas_zip "$source_zip"
      ;;
    *)
      return 1
      ;;
  esac
}
