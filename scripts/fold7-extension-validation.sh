#!/bin/bash
set -euo pipefail

usage() {
  cat <<'EOF'
사용법:
  scripts/fold7-extension-validation.sh --case-id <id> --source <경로> [옵션]

옵션:
  --case-id <id>        validation/extensions.json의 확장 ID
  --source <경로>       검증할 확장 source 경로
  --profile <경로>      삼성 인터넷 profile 경로 override
  --serial <adb-serial> host 검증 뒤 지정한 기기에 install/smoke 실행
  --host-only           기기 install/smoke를 실행하지 않음
  -h, --help            도움말 표시
EOF
}

usage_error() {
  echo "$1" >&2
  exit 2
}

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
# shellcheck source=fold7-validation-lib.sh
. "$project_dir/scripts/fold7-validation-lib.sh"
case_id=
source_input=
profile_input="$project_dir/profiles/sbrowser-30.0.0.67.json"
serial=
host_only=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --case-id)
      [ "$#" -ge 2 ] || usage_error "--case-id 값이 필요합니다."
      [ -z "$case_id" ] || usage_error "--case-id는 한 번만 지정할 수 있습니다."
      case_id=$2
      shift 2
      ;;
    --source)
      [ "$#" -ge 2 ] || usage_error "--source 값이 필요합니다."
      source_input=$2
      shift 2
      ;;
    --profile)
      [ "$#" -ge 2 ] || usage_error "--profile 값이 필요합니다."
      profile_input=$2
      shift 2
      ;;
    --serial)
      [ "$#" -ge 2 ] || usage_error "--serial 값이 필요합니다."
      serial=$2
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

[ -n "$case_id" ] || usage_error "--case-id 값이 필요합니다."
[ -n "$source_input" ] || usage_error "--source 값이 필요합니다."
if [ "$host_only" = true ]; then
  [ -z "$serial" ] || usage_error \
    "--host-only와 --serial은 함께 사용할 수 없습니다."
else
  [ -n "$serial" ] || usage_error \
    "--host-only가 아니면 --serial 값이 필요합니다."
fi
[ -d "$source_input" ] ||
  usage_error "확장 source 디렉터리를 찾을 수 없습니다: $source_input"
source_dir=$(CDPATH= cd -- "$source_input" && pwd -P)
[ -f "$profile_input" ] ||
  usage_error "브라우저 profile을 찾을 수 없습니다: $profile_input"
profile_dir=$(CDPATH= cd -- "$(dirname -- "$profile_input")" && pwd -P)
profile="$profile_dir/$(basename -- "$profile_input")"

validation_manifest=${SIX_VALIDATION_MANIFEST:-"$project_dir/validation/extensions.json"}
[ -f "$validation_manifest" ] ||
  usage_error "확장 검증 manifest를 찾을 수 없습니다: $validation_manifest"
case_json=$(six_validation_case "$validation_manifest" "$case_id") ||
  usage_error "검증 case를 정확히 읽을 수 없습니다: $case_id"
case_slug=$(printf '%s' "$case_json" |
  jq -er '.fold7Validation.slug') ||
  usage_error "검증 case slug를 읽을 수 없습니다: $case_id"
case_name=$(printf '%s' "$case_json" | jq -er '.name') ||
  usage_error "검증 case 이름을 읽을 수 없습니다: $case_id"
case_contract=$(printf '%s' "$case_json" |
  jq -er '.fold7Validation.contract') ||
  usage_error "검증 case contract를 읽을 수 없습니다: $case_id"

adapter_args=()
while IFS= read -r adapter_id; do
  [ -n "$adapter_id" ] || continue
  adapter_args+=(--approve-adapter "$adapter_id")
done < <(printf '%s' "$case_json" |
  jq -er '.fold7Validation.approvedAdapters[]?')

cli="$project_dir/cli/build/install/sixbridge/bin/sixbridge"
[ -x "$cli" ] ||
  usage_error "sixbridge installDist 결과를 찾을 수 없습니다: $cli"
for command_name in jq shasum zipinfo unzip; do
  command -v "$command_name" >/dev/null 2>&1 ||
    usage_error "필수 명령을 찾을 수 없습니다: $command_name"
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
"$java_command" -version >/dev/null 2>&1 ||
  usage_error "실행 가능한 JDK를 찾을 수 없습니다. JAVA_HOME을 설정하세요."

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
run_dir=$(mktemp -d "$evidence_dir/.$case_slug-run.XXXXXX")
[ -d "$run_dir" ] && [ ! -L "$run_dir" ] || {
  echo "안전한 evidence run 디렉터리를 만들 수 없습니다." >&2
  exit 2
}
evidence_log=$(mktemp \
  "$evidence_dir/$timestamp-$case_slug-converted.log.XXXXXX")
report="$evidence_dir/$case_slug-before.json"
first_draft="$evidence_dir/$case_slug-a.sixdraft"
second_draft="$evidence_dir/$case_slug-b.sixdraft"
descriptor="$evidence_dir/$case_slug-extension.json"
after_report="$evidence_dir/$case_slug-after.json"
bundle="$dist_dir/$case_slug-converted.six"
crx="$dist_dir/$case_slug-converted.crx"
wrapper="$dist_dir/$case_slug-converted-wrapper.apk"
report_work=$(mktemp "$evidence_dir/.$case_slug-before.XXXXXX")
first_draft_work=$(mktemp \
  "$evidence_dir/.$case_slug-a.sixdraft.XXXXXX")
second_draft_work=$(mktemp \
  "$evidence_dir/.$case_slug-b.sixdraft.XXXXXX")
descriptor_work=$(mktemp \
  "$evidence_dir/.$case_slug-extension.XXXXXX")
after_report_work=$(mktemp \
  "$evidence_dir/.$case_slug-after.XXXXXX")
bundle_seed=$(mktemp "$dist_dir/.$case_slug-converted.XXXXXX")
bundle_work="${bundle_seed}.six"
/bin/mv -f -h -- "$bundle_seed" "$bundle_work"
crx_work="${bundle_work%.six}.crx"
wrapper_work="${bundle_work%.six}-wrapper.apk"

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

source_is_git=false
source_status_before=
if git -C "$source_dir" rev-parse \
  --is-inside-work-tree >/dev/null 2>&1; then
  source_is_git=true
  source_status_before=$(git -C "$source_dir" status --short)
fi
source_hash_before=$(tree_hash)
source_guard_active=true

verify_source_unchanged() {
  local source_status_after source_hash_after
  source_status_after=
  if [ "$source_is_git" = true ]; then
    source_status_after=$(git -C "$source_dir" status --short)
  fi
  source_hash_after=$(tree_hash)
  if [ "$source_is_git" = true ]; then
    printf '%s\n' "sourceStatusBefore:" "$source_status_before"
    printf '%s\n' "sourceStatusAfter:" "$source_status_after"
  fi
  echo "sourceTreeSha256Before=$source_hash_before"
  echo "sourceTreeSha256After=$source_hash_after"
  if [ "$source_hash_before" != "$source_hash_after" ]; then
    echo "실제 source가 검증 중 변경되었습니다." >&2
    return 1
  fi
  if [ "$source_is_git" = true ] &&
    [ "$source_status_before" != "$source_status_after" ]; then
    echo "실제 source가 검증 중 변경되었습니다." >&2
    return 1
  fi
  echo "sourceGitRepository=$source_is_git"
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

echo "# SIX Bridge $case_name 변환 검증"
echo "timestamp=$timestamp"
echo "caseId=$case_id"
echo "caseSlug=$case_slug"
echo "source=$source_dir"
echo "profile=$profile"
echo "serial=${serial:-none}"
echo "sourceTreeSha256=$source_hash_before"
if [ "$source_is_git" = true ]; then
  printf '%s\n' "sourceStatusBaseline:" "$source_status_before"
fi

"$cli" analyze \
  --input "$source_dir" \
  --profile "$profile" \
  --output "$report_work"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  ${adapter_args[@]+"${adapter_args[@]}"} \
  --output "$first_draft_work"

"$cli" draft \
  --input "$source_dir" \
  --profile "$profile" \
  ${adapter_args[@]+"${adapter_args[@]}"} \
  --output "$second_draft_work"

"$cli" verify-draft --input "$first_draft_work"
transformed_source_zip="$run_dir/transformed-source.zip"
unzip -p "$first_draft_work" source.zip >"$transformed_source_zip" || {
  echo "SixDraft에서 transformed source ZIP을 읽을 수 없습니다." >&2
  exit 3
}
six_assert_transformed_contract \
  "$case_contract" "$transformed_source_zip" || {
  echo "transformed source 계약이 올바르지 않습니다: $case_contract" >&2
  exit 3
}
if [ "$case_contract" = smart-context-v2 ]; then
  echo "optionsEntryContract=true"
  echo "persistentActionButtonAbsent=true"
fi
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
  ${adapter_args[@]+"${adapter_args[@]}"} \
  --output "$bundle_work"

for generated in "$crx_work" "$wrapper_work" "$bundle_work"; do
  [ -f "$generated" ] || {
    echo "예상한 build 산출물을 찾을 수 없습니다: $generated" >&2
    exit 3
  }
done
[ "$(dd if="$crx_work" bs=1 count=4 2>/dev/null)" = "Cr24" ] || {
  echo "생성 CRX가 CRX3 stream이 아닙니다." >&2
  exit 3
}

unzip -p "$bundle_work" extension.json >"$descriptor_work"
unzip -p "$bundle_work" compatibility.json >"$after_report_work"
six_assert_case_reports \
  "$validation_manifest" "$case_id" "$report_work" "$after_report_work" || {
  echo "변환 전후 compatibility 보고서 계약이 일치하지 않습니다." >&2
  exit 3
}
"$cli" verify-build \
  --bundle "$bundle_work" \
  --crx "$crx_work" \
  --wrapper "$wrapper_work"

crx_hash=$(shasum -a 256 "$crx_work" | awk '{print $1}')
wrapper_hash=$(shasum -a 256 "$wrapper_work" | awk '{print $1}')
wrapper_package=$(jq -er '.wrapperPackage' "$descriptor_work")
echo "hostContract=true"
echo "crxSha256=$crx_hash"
echo "wrapperSha256=$wrapper_hash"
echo "bundleSha256=$(shasum -a 256 "$bundle_work" | awk '{print $1}')"
echo "extensionId=$(jq -er '.extensionId' "$descriptor_work")"
echo "wrapperPackage=$wrapper_package"
echo "wrapperVersionCode=$(jq -er '.wrapperVersionCode' "$descriptor_work")"
echo "patternDecisionChecked=true"

six_atomic_publish "$report_work" "$report" "$evidence_dir"
six_atomic_publish "$first_draft_work" "$first_draft" "$evidence_dir"
six_atomic_publish "$second_draft_work" "$second_draft" "$evidence_dir"
six_atomic_publish "$descriptor_work" "$descriptor" "$evidence_dir"
six_atomic_publish "$after_report_work" "$after_report" "$evidence_dir"
six_atomic_publish "$bundle_work" "$bundle" "$dist_dir"
six_atomic_publish "$crx_work" "$crx" "$dist_dir"
six_atomic_publish "$wrapper_work" "$wrapper" "$dist_dir"

if [ "$host_only" = true ]; then
  echo "profileMatch=skipped"
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
  awk -v target="$serial" \
    '$1 == target && $2 == "device" { found=1 } END { exit !found }'; then
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
