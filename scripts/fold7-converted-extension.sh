#!/bin/bash
set -euo pipefail

usage() {
  cat <<'EOF'
사용법:
  scripts/fold7-converted-extension.sh [옵션]

옵션:
  --serial <adb-serial>  host 검증 뒤 지정한 기기에 install/smoke 실행
  --source <경로>       확장 source 경로 override
  --profile <경로>      삼성 인터넷 profile 경로 override
  --host-only           기기 install/smoke를 실행하지 않음
  -h, --help            도움말 표시
EOF
}

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
# shellcheck source=fold7-validation-lib.sh
. "$project_dir/scripts/fold7-validation-lib.sh"
source_input='/workspace/T5다운로드/작업중/미니메뉴 확장프로그램/smart-context-menu'
profile_input="$project_dir/profiles/sbrowser-30.0.0.67.json"
serial=
host_only=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$#" -ge 2 ] || {
        echo "--serial 값이 필요합니다." >&2
        exit 2
      }
      serial=$2
      shift 2
      ;;
    --source)
      [ "$#" -ge 2 ] || {
        echo "--source 값이 필요합니다." >&2
        exit 2
      }
      source_input=$2
      shift 2
      ;;
    --profile)
      [ "$#" -ge 2 ] || {
        echo "--profile 값이 필요합니다." >&2
        exit 2
      }
      profile_input=$2
      shift 2
      ;;
    --host-only)
      host_only=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "알 수 없는 옵션입니다: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ "$host_only" = true ] && [ -n "$serial" ]; then
  echo "--host-only와 --serial은 함께 사용할 수 없습니다." >&2
  exit 2
fi
if [ "$host_only" = false ] && [ -z "$serial" ]; then
  echo "--host-only가 아니면 --serial 값이 필요합니다." >&2
  exit 2
fi
if [ ! -d "$source_input" ]; then
  echo "확장 source 디렉터리를 찾을 수 없습니다: $source_input" >&2
  exit 2
fi
source_dir=$(CDPATH= cd -- "$source_input" && pwd -P)
if [ ! -f "$profile_input" ]; then
  echo "브라우저 profile을 찾을 수 없습니다: $profile_input" >&2
  exit 2
fi
profile_dir=$(CDPATH= cd -- "$(dirname -- "$profile_input")" && pwd -P)
profile="$profile_dir/$(basename -- "$profile_input")"
if ! git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "source는 상태 보존을 검사할 수 있는 Git repository여야 합니다." >&2
  exit 2
fi

cli="$project_dir/cli/build/install/sixbridge/bin/sixbridge"
if [ ! -x "$cli" ]; then
  echo "sixbridge installDist 결과를 찾을 수 없습니다: $cli" >&2
  exit 2
fi
for command_name in jq shasum zipinfo unzip; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "필수 명령을 찾을 수 없습니다: $command_name" >&2
    exit 2
  }
done
if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
  unset JAVA_HOME
fi
if [ -z "${JAVA_HOME:-}" ] &&
  [ -x '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java' ]; then
  export JAVA_HOME='/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'
fi
if [ -n "${JAVA_HOME:-}" ]; then
  java_command="$JAVA_HOME/bin/java"
else
  java_command=java
fi
if ! "$java_command" -version >/dev/null 2>&1; then
  echo "실행 가능한 JDK를 찾을 수 없습니다. JAVA_HOME을 설정하세요." >&2
  exit 2
fi

local_dir="$project_dir/.local"
evidence_dir="$local_dir/evidence"
dist_dir="$project_dir/dist"
six_secure_directory "$local_dir" "$project_dir" || {
  echo "안전한 .local 디렉터리를 준비할 수 없습니다." >&2
  exit 2
}
six_secure_directory "$evidence_dir" "$local_dir" || {
  echo "안전한 evidence 디렉터리를 준비할 수 없습니다." >&2
  exit 2
}
six_secure_directory "$dist_dir" "$project_dir" || {
  echo "안전한 dist 디렉터리를 준비할 수 없습니다." >&2
  exit 2
}
timestamp=$(date +%Y%m%d-%H%M%S)
run_dir=$(mktemp -d "$evidence_dir/.smart-context-run.XXXXXX")
[ -d "$run_dir" ] && [ ! -L "$run_dir" ] || {
  echo "안전한 evidence run 디렉터리를 만들 수 없습니다." >&2
  exit 2
}
evidence_log=$(mktemp "$evidence_dir/$timestamp-smart-context-converted.log.XXXXXX")
report="$evidence_dir/smart-context-before.json"
first_draft="$evidence_dir/smart-context-a.sixdraft"
second_draft="$evidence_dir/smart-context-b.sixdraft"
bundle="$dist_dir/smart-context-converted.six"
crx="$dist_dir/smart-context-converted.crx"
wrapper="$dist_dir/smart-context-converted-wrapper.apk"
descriptor="$evidence_dir/smart-context-extension.json"
after_report="$evidence_dir/smart-context-after.json"
report_work=$(mktemp "$evidence_dir/.smart-context-before.XXXXXX")
first_draft_work=$(mktemp "$evidence_dir/.smart-context-a.sixdraft.XXXXXX")
second_draft_work=$(mktemp "$evidence_dir/.smart-context-b.sixdraft.XXXXXX")
descriptor_work=$(mktemp "$evidence_dir/.smart-context-extension.XXXXXX")
after_report_work=$(mktemp "$evidence_dir/.smart-context-after.XXXXXX")

tree_hash() {
  (
    cd "$source_dir"
    find . -path './.git' -prune -o -type f -print0 |
      LC_ALL=C sort -z |
      while IFS= read -r -d '' file; do
        relative=${file#./}
        printf '%s\0' "$relative"
        shasum -a 256 "$file" | awk '{print $1}'
      done |
      shasum -a 256 |
      awk '{print $1}'
  )
}

source_status_before=$(git -C "$source_dir" status --short)
source_hash_before=$(tree_hash)
source_guard_active=true

verify_source_unchanged() {
  source_status_after=$(git -C "$source_dir" status --short)
  source_hash_after=$(tree_hash)
  printf '%s\n' "sourceStatusBefore:" "$source_status_before"
  printf '%s\n' "sourceStatusAfter:" "$source_status_after"
  echo "sourceTreeSha256Before=$source_hash_before"
  echo "sourceTreeSha256After=$source_hash_after"
  if [ "$source_status_before" != "$source_status_after" ] ||
    [ "$source_hash_before" != "$source_hash_after" ]; then
    echo "실제 source가 검증 중 변경되었습니다." >&2
    return 1
  fi
  echo "sourceUnchanged=true"
}

on_exit() {
  exit_code=$?
  trap - EXIT
  set +e
  if [ "$source_guard_active" = true ]; then
    verify_source_unchanged
    source_result=$?
    if [ "$source_result" -ne 0 ]; then
      exit_code=4
    fi
  fi
  echo "evidence=$evidence_log"
  cat "$evidence_log" >&3
  exec 3>&-
  exit "$exit_code"
}

trap on_exit EXIT
exec 3>&1
exec >"$evidence_log" 2>&1

echo "# SIX Bridge 스마트 컨텍스트 메뉴 변환 검증"
echo "timestamp=$timestamp"
echo "source=$source_dir"
echo "profile=$profile"
echo "serial=${serial:-none}"
echo "sourceTreeSha256=$source_hash_before"
printf '%s\n' "sourceStatusBaseline:" "$source_status_before"

"$cli" analyze \
  --input "$source_dir" \
  --profile "$profile" \
  --output "$report_work"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  --approve-adapter action-popup-entry \
  --output "$first_draft_work"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  --approve-adapter action-popup-entry \
  --output "$second_draft_work"

"$cli" verify-draft --input "$first_draft_work"
transformed_source_zip="$run_dir/transformed-source.zip"
unzip -p "$first_draft_work" source.zip >"$transformed_source_zip" || {
  echo "SixDraft에서 transformed source ZIP을 읽을 수 없습니다." >&2
  exit 3
}
if ! unzip -p "$transformed_source_zip" manifest.json |
  jq -e '
    .options_ui.page == "popup.html" and
    .options_ui.open_in_tab == true
  ' >/dev/null; then
  echo "transformed manifest의 options_ui 계약이 올바르지 않습니다." >&2
  exit 3
fi
zipinfo -1 "$transformed_source_zip" |
  grep -Fx 'sixbridge/action-popup-entry.v2.json' >/dev/null || {
  echo "action popup option entry provenance를 찾을 수 없습니다." >&2
  exit 3
}
zipinfo -1 "$transformed_source_zip" |
  grep -Fx 'sixbridge/desktop-selection-event-bridge.v1.js' >/dev/null || {
  echo "desktop selection event bridge asset을 찾을 수 없습니다." >&2
  exit 3
}
if zipinfo -1 "$transformed_source_zip" |
  awk -F/ '$1 ~ /^_/ { found=1 } END { exit !found }'; then
  echo "삼성 예약 선행 밑줄 root entry가 남아 있습니다." >&2
  exit 3
fi
if zipinfo -1 "$transformed_source_zip" |
  grep -E '(^|/)(action-popup-entry-(content|background)\.v1\.js|_sixbridge-action-popup-entry-bootstrap\.v1\.js)$' >/dev/null; then
  echo "version 1 action popup runtime asset이 남아 있습니다." >&2
  exit 3
fi
echo "optionsEntryContract=true"
echo "persistentActionButtonAbsent=true"
"$cli" verify-draft --input "$second_draft_work"
first_hash=$(shasum -a 256 "$first_draft_work" | awk '{print $1}')
second_hash=$(shasum -a 256 "$second_draft_work" | awk '{print $1}')
echo "draftSha256A=$first_hash"
echo "draftSha256B=$second_hash"
if [ "$first_hash" != "$second_hash" ]; then
  echo "동일 입력의 SixDraft hash가 다릅니다." >&2
  exit 3
fi
echo "draftDeterministic=true"

"$cli" build \
  --input "$source_dir" \
  --profile "$profile" \
  --approve-adapter action-popup-entry \
  --output "$bundle"

for generated in "$crx" "$wrapper" "$bundle"; do
  [ -f "$generated" ] || {
    echo "예상한 build 산출물을 찾을 수 없습니다: $generated" >&2
    exit 3
  }
done
[ "$(dd if="$crx" bs=1 count=4 2>/dev/null)" = "Cr24" ] || {
  echo "생성 CRX가 CRX3 stream이 아닙니다." >&2
  exit 3
}

unzip -p "$bundle" extension.json >"$descriptor_work"
unzip -p "$bundle" compatibility.json >"$after_report_work"
six_assert_conversion_reports "$report_work" "$after_report_work" || {
  echo "변환 전후 compatibility 보고서 계약이 일치하지 않습니다." >&2
  exit 3
}
"$cli" verify-build \
  --bundle "$bundle" \
  --crx "$crx" \
  --wrapper "$wrapper"

crx_hash=$(shasum -a 256 "$crx" | awk '{print $1}')
wrapper_hash=$(shasum -a 256 "$wrapper" | awk '{print $1}')
wrapper_package=$(jq -er '.wrapperPackage' "$descriptor_work")
echo "hostContract=true"
echo "crxSha256=$crx_hash"
echo "wrapperSha256=$wrapper_hash"
echo "bundleSha256=$(shasum -a 256 "$bundle" | awk '{print $1}')"
echo "extensionId=$(jq -er '.extensionId' "$descriptor_work")"
echo "wrapperPackage=$wrapper_package"
echo "wrapperVersionCode=$(jq -er '.wrapperVersionCode' "$descriptor_work")"
echo "patternDecisionChecked=true"

six_atomic_publish "$report_work" "$report" "$evidence_dir"
six_atomic_publish "$first_draft_work" "$first_draft" "$evidence_dir"
six_atomic_publish "$second_draft_work" "$second_draft" "$evidence_dir"
six_atomic_publish "$descriptor_work" "$descriptor" "$evidence_dir"
six_atomic_publish "$after_report_work" "$after_report" "$evidence_dir"

if [ "$host_only" = true ]; then
  echo "deviceChecks=skipped"
  echo "runtimeResult=수동 검증 보류"
  exit 0
fi

command -v adb >/dev/null 2>&1 || {
  echo "기기 검증에 필요한 adb 명령을 찾을 수 없습니다." >&2
  echo "deviceChecks=failed"
  echo "runtimeResult=ADB 미설치로 보류"
  exit 3
}
devices=$(adb devices -l)
printf '%s\n' "$devices"
if ! printf '%s\n' "$devices" |
  awk -v target="$serial" '$1 == target && $2 == "device" { found=1 } END { exit !found }'; then
  echo "지정한 ADB 기기가 device 상태가 아닙니다: $serial" >&2
  echo "deviceChecks=failed"
  echo "runtimeResult=기기 연결 실패로 보류"
  exit 3
fi

browser_dump_file="$run_dir/browser-dumpsys.txt"
adb -s "$serial" shell dumpsys package com.sec.android.app.sbrowser \
  >"$browser_dump_file"
browser_binding=$(six_browser_binding "$browser_dump_file") || {
  echo "설치된 삼성 인터넷의 package/version 정보를 정확히 읽을 수 없습니다." >&2
  exit 3
}
profile_binding=$(six_profile_binding "$profile") || {
  echo "profile의 package/version 계약이 올바르지 않습니다." >&2
  exit 3
}
IFS=$'\t' read -r browser_package browser_version browser_version_code \
  <<<"$browser_binding"
IFS=$'\t' read -r profile_package profile_version profile_version_code \
  <<<"$profile_binding"
echo "browserPackage=$browser_package"
echo "browserVersion=$browser_version"
echo "browserVersionCode=$browser_version_code"
echo "profilePackage=$profile_package"
echo "profileVersion=$profile_version"
echo "profileVersionCode=$profile_version_code"
if ! six_profile_matches "$browser_dump_file" "$profile"; then
  echo "profileMatch=false"
  echo "deviceChecks=failed"
  echo "runtimeResult=브라우저/profile 불일치로 최종 합격 보류"
  exit 3
fi
echo "profileMatch=true"

"$cli" device install --bundle "$bundle" --serial "$serial"
"$cli" device smoke \
  --bundle "$bundle" \
  --serial "$serial" \
  --evidence-dir "$run_dir"
echo "installSuccess=true"
echo "discoverySuccess=true"
echo "providerReadSuccess=true"
echo "deviceChecks=passed"
echo "runtimeResult=사용자 수동 확인 필요"
