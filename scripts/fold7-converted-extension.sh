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
source_input='/workspace/T5다운로드/작업중/미니메뉴 확장프로그램/smart-context-menu'
profile_input="$project_dir/profiles/sbrowser-30.0.2.61.json"
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

evidence_dir="$project_dir/.local/evidence"
dist_dir="$project_dir/dist"
mkdir -p "$evidence_dir" "$dist_dir"
timestamp=$(date +%Y%m%d-%H%M%S)
evidence_log="$evidence_dir/$timestamp-smart-context-converted.log"
report="$evidence_dir/smart-context-before.json"
first_draft="$evidence_dir/smart-context-a.sixdraft"
second_draft="$evidence_dir/smart-context-b.sixdraft"
bundle="$dist_dir/smart-context-converted.six"
crx="$dist_dir/smart-context-converted.crx"
wrapper="$dist_dir/smart-context-converted-wrapper.apk"
descriptor="$evidence_dir/smart-context-extension.json"
after_report="$evidence_dir/smart-context-after.json"

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
  exec 3>&- 4>&-
  exit "$exit_code"
}

trap on_exit EXIT
exec 3>&1 4>&2
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
  --output "$report"

jq -e '
  .status == "compatible" and .conversionState == "unavailable" and
  ([.adapterDecisions[].id] | sort) ==
    ["action-popup-entry", "desktop-selection-event-bridge", "storage-fallback"] and
  any(.adapterDecisions[];
    .id == "desktop-selection-event-bridge" and
    .disposition == "automatic" and .applied == false) and
  any(.adapterDecisions[];
    .id == "action-popup-entry" and
    .disposition == "requires-approval" and .applied == false) and
  any(.adapterDecisions[];
    .id == "storage-fallback" and
    .disposition == "unavailable" and .applied == false)
' "$report" >/dev/null
echo "hostPatternDecision=true"
echo "identitySpecificDecision=false"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  --approve-adapter action-popup-entry \
  --output "$first_draft"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  --approve-adapter action-popup-entry \
  --output "$second_draft"

"$cli" verify-draft --input "$first_draft"
"$cli" verify-draft --input "$second_draft"
first_hash=$(shasum -a 256 "$first_draft" | awk '{print $1}')
second_hash=$(shasum -a 256 "$second_draft" | awk '{print $1}')
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

bundle_entries=$(zipinfo -1 "$bundle" | LC_ALL=C sort)
expected_entries=$(printf '%s\n' compatibility.json extension.json wrapper.apk)
if [ "$bundle_entries" != "$expected_entries" ]; then
  echo "SixBundle 항목 계약이 일치하지 않습니다." >&2
  exit 3
fi
unzip -p "$bundle" extension.json >"$descriptor"
unzip -p "$bundle" compatibility.json >"$after_report"
jq -e '
  .status == "compatible" and .conversionState == "converted" and
  any(.adapterDecisions[];
    .id == "desktop-selection-event-bridge" and
    .disposition == "automatic" and .applied == true) and
  any(.adapterDecisions[];
    .id == "action-popup-entry" and
    .disposition == "requires-approval" and .applied == true) and
  any(.adapterDecisions[];
    .id == "storage-fallback" and
    .disposition == "unavailable" and .applied == false)
' "$after_report" >/dev/null

crx_hash=$(shasum -a 256 "$crx" | awk '{print $1}')
wrapper_hash=$(shasum -a 256 "$wrapper" | awk '{print $1}')
bundle_wrapper_hash=$(unzip -p "$bundle" wrapper.apk | shasum -a 256 | awk '{print $1}')
if [ "$wrapper_hash" != "$bundle_wrapper_hash" ] ||
  [ "$crx_hash" != "$(jq -er '.crxSha256' "$descriptor")" ] ||
  [ "$wrapper_hash" != "$(jq -er '.wrapperSha256' "$descriptor")" ]; then
  echo "SixBundle descriptor와 외부 산출물 hash가 일치하지 않습니다." >&2
  exit 3
fi
if ! zipinfo -1 "$wrapper" | grep -Fx 'assets/extension.crx' >/dev/null; then
  echo "wrapper APK에 embedded CRX가 없습니다." >&2
  exit 3
fi
embedded_crx_hash=$(unzip -p "$wrapper" assets/extension.crx |
  shasum -a 256 | awk '{print $1}')
if [ "$crx_hash" != "$embedded_crx_hash" ]; then
  echo "wrapper APK의 embedded CRX가 외부 CRX와 다릅니다." >&2
  exit 3
fi

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}
build_tools=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d |
  LC_ALL=C sort | tail -n 1)
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"
for android_tool in "$aapt2" "$apksigner" "$zipalign"; do
  [ -x "$android_tool" ] || {
    echo "Android build-tool을 찾을 수 없습니다: $android_tool" >&2
    exit 2
  }
done

"$apksigner" verify --verbose --print-certs "$wrapper"
"$zipalign" -c -P 16 4 "$wrapper"
badging=$("$aapt2" dump badging "$wrapper")
manifest=$("$aapt2" dump xmltree "$wrapper" --file AndroidManifest.xml)
wrapper_package=$(jq -er '.wrapperPackage' "$descriptor")
for contract_marker in \
  "$wrapper_package" \
  'extension-id' \
  'dev.sixbridge.runtime.SixDiscoveryActivity' \
  'six' \
  'ex' \
  "$wrapper_package.contentprovider"; do
  if ! printf '%s\n%s\n' "$badging" "$manifest" |
    grep -F "$contract_marker" >/dev/null; then
    echo "wrapper manifest 계약 표식을 찾을 수 없습니다: $contract_marker" >&2
    exit 3
  fi
done
echo "hostContract=true"
echo "crxSha256=$crx_hash"
echo "wrapperSha256=$wrapper_hash"
echo "bundleSha256=$(shasum -a 256 "$bundle" | awk '{print $1}')"
echo "extensionId=$(jq -er '.extensionId' "$descriptor")"
echo "wrapperPackage=$wrapper_package"
echo "wrapperVersionCode=$(jq -er '.wrapperVersionCode' "$descriptor")"

if [ "$host_only" = true ] || [ -z "$serial" ]; then
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

browser_dump=$(adb -s "$serial" shell dumpsys package com.sec.android.app.sbrowser)
browser_version=$(printf '%s\n' "$browser_dump" |
  sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)
browser_version_code=$(printf '%s\n' "$browser_dump" |
  sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
profile_version=$(jq -er '.versionName' "$profile")
echo "browserVersion=$browser_version"
echo "browserVersionCode=$browser_version_code"
echo "profileVersion=$profile_version"
if [ "$browser_version" = "$profile_version" ]; then
  echo "profileMatch=true"
else
  echo "profileMatch=false"
fi

"$cli" device install --bundle "$bundle" --serial "$serial"
"$cli" device smoke \
  --bundle "$bundle" \
  --serial "$serial" \
  --evidence-dir "$evidence_dir"
echo "deviceChecks=passed"
if [ "$browser_version" = "$profile_version" ]; then
  echo "runtimeResult=사용자 수동 확인 필요"
else
  echo "runtimeResult=브라우저/profile 불일치로 최종 합격 보류"
fi
