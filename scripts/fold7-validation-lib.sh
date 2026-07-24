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
