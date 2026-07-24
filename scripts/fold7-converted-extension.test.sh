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

echo "fold7 converted extension shell tests passed"
