#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

host_gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"
test_home="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-desktop-distribution.XXXXXX")"
trap 'rm -rf -- "$test_home"' EXIT
test_gradle_home="$test_home/gradle"
mkdir -p "$test_gradle_home"
for cache_name in caches wrapper jdks; do
  [[ -e "$host_gradle_home/$cache_name" ]] || continue
  ln -s "$host_gradle_home/$cache_name" "$test_gradle_home/$cache_name"
done

credential_names=(
  SIXBRIDGE_MAC_SIGNING_IDENTITY
  SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID
  SIXBRIDGE_MAC_NOTARIZATION_PASSWORD
  SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID
)

run_unsigned_gradle() {
  env \
    -u "${credential_names[0]}" \
    -u "${credential_names[1]}" \
    -u "${credential_names[2]}" \
    -u "${credential_names[3]}" \
    HOME="$test_home" \
    GRADLE_USER_HOME="$test_gradle_home" \
    ./gradlew "$@"
}

fail() {
  echo "desktop distribution 검증 실패: $1" >&2
  exit 1
}

distribution_contains_credential() {
  local scan_root="$1"
  local probe="$2"
  local status=0

  rg -a --hidden --no-ignore -q -F -- "$probe" "$scan_root" || status=$?
  case "$status" in
    0) return 0 ;;
    1) return 1 ;;
    *) fail "credential content scan을 실행하지 못했습니다." ;;
  esac
}

tasks_output="$(run_unsigned_gradle :desktop-app:tasks --all)"
rg -q '^packageDmg([[:space:]]|$)' <<<"$tasks_output" || fail "packageDmg task가 없습니다."
rg -q '^packageMsi([[:space:]]|$)' <<<"$tasks_output" || fail "packageMsi task가 없습니다."

build_script="desktop-app/build.gradle.kts"
rg -q 'TargetFormat\.Dmg, TargetFormat\.Msi' "$build_script" || fail "DMG·MSI target format이 없습니다."
rg -q 'b438ea76-04ca-4aa7-af12-4b717e75551b' "$build_script" || fail "Windows upgrade UUID가 일치하지 않습니다."
rg -q 'packageVersion = "1\.0\.0"' "$build_script" || fail "native package build version이 일치하지 않습니다."

properties_file="desktop-app/src/main/resources/app.properties"
[[ "$(awk -F= '$1 == "name" { print substr($0, index($0, "=") + 1) }' "$properties_file")" == "SIX Bridge" ]] || fail "앱 이름이 일치하지 않습니다."
[[ "$(awk -F= '$1 == "applicationId" { print $2 }' "$properties_file")" == "dev.sixbridge.desktop" ]] || fail "applicationId가 일치하지 않습니다."
[[ "$(awk -F= '$1 == "version" { print $2 }' "$properties_file")" == "0.1.0" ]] || fail "version이 일치하지 않습니다."
[[ "$(awk -F= '$1 == "minimumMacOs" { print $2 }' "$properties_file")" == "13.0" ]] || fail "minimumMacOs가 일치하지 않습니다."

credential_probe="sixbridge-distribution-credential-probe"
set +e
partial_output="$(
  env \
    -u "${credential_names[1]}" \
    -u "${credential_names[2]}" \
    -u "${credential_names[3]}" \
    HOME="$test_home" \
    GRADLE_USER_HOME="$test_gradle_home" \
    "${credential_names[0]}=$credential_probe" \
    ./gradlew :desktop-app:help 2>&1
)"
partial_status=$?
set -e
[[ $partial_status -ne 0 ]] || fail "부분 macOS credential이 거부되지 않습니다."
rg -q 'macOS 배포 인증 정보는 네 항목을 함께 제공해야 합니다\.' <<<"$partial_output" || fail "부분 credential 오류가 일치하지 않습니다."
if rg -q -F "$credential_probe" <<<"$partial_output"; then
  fail "credential 값이 Gradle output에 노출됐습니다."
fi

set +e
complete_output="$(
  env \
    HOME="$test_home" \
    GRADLE_USER_HOME="$test_gradle_home" \
    "${credential_names[0]}=$credential_probe-identity" \
    "${credential_names[1]}=$credential_probe-apple-id" \
    "${credential_names[2]}=$credential_probe-password" \
    "${credential_names[3]}=$credential_probe-team-id" \
    ./gradlew :desktop-app:help 2>&1
)"
complete_status=$?
set -e
[[ $complete_status -eq 0 ]] || fail "전체 macOS credential이 configuration에서 거부됐습니다."
if rg -q -F "$credential_probe" <<<"$complete_output"; then
  fail "credential 값이 Gradle output에 노출됐습니다."
fi

hidden_scan_fixture="$test_home/credential-scan-fixture"
mkdir -p "$hidden_scan_fixture"
printf '%s\n' "$credential_probe" > "$hidden_scan_fixture/.credentials"
if ! distribution_contains_credential "$hidden_scan_fixture" "$credential_probe"; then
  fail "hidden credential 누출 self-test가 누출을 탐지하지 못했습니다."
fi

run_unsigned_gradle \
  -Pcompose.desktop.packaging.checkJdkVendor=false \
  :desktop-app:processResources \
  :desktop-app:createDistributable

resource_root="desktop-app/build/resources/main"
for resource in \
  "$resource_root/profiles/builtin/sbrowser-30.0.0.67.json" \
  "$resource_root/profiles/builtin/sbrowser-30.0.2.61.json" \
  "$resource_root/profiles/trusted/profile-signing-2026-01.der"; do
  [[ -f "$resource" ]] || fail "필수 resource가 없습니다: ${resource#"$resource_root/"}"
done

app_bundle="$(find desktop-app/build/compose/binaries/main/app -maxdepth 1 -type d -name '*.app' -print -quit 2>/dev/null || true)"
[[ -n "$app_bundle" ]] || fail "packaged .app이 없습니다."
app_plist="$app_bundle/Contents/Info.plist"
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_plist")" == "dev.sixbridge.desktop" ]] || fail "packaged bundle ID가 일치하지 않습니다."
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_plist")" == "1.0.0" ]] || fail "macOS native package version이 일치하지 않습니다."
[[ "$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$app_plist")" == "13.0" ]] || fail "packaged minimum macOS version이 일치하지 않습니다."

scan_roots=(
  desktop-app/src/main/resources
  "$resource_root"
  desktop-app/build/compose/binaries/main/app
  desktop-app/build/compose/tmp
)
for scan_root in "${scan_roots[@]}"; do
  [[ -e "$scan_root" ]] || continue
  while IFS= read -r -d '' candidate; do
    relative="${candidate#"$scan_root"/}"
    case "/$relative" in
      */.local/*|*.pem|*.pk8|*.p12|*.jks|*.sixdraft|*.six|*.crx|*.zip)
        fail "정적 배포 입력에 금지된 파일이 있습니다: $relative"
        ;;
    esac
  done < <(find "$scan_root" -type f -print0)
  if distribution_contains_credential "$scan_root" "$credential_probe"; then
    fail "credential probe가 resource 또는 distribution input에 남았습니다."
  fi
done

application_jar="$(find "$app_bundle/Contents/app" -maxdepth 1 -type f -name 'desktop-app-*.jar' -print -quit)"
[[ -n "$application_jar" ]] || fail "packaged application jar가 없습니다."
application_entries="$(jar tf "$application_jar")"
for resource in \
  app.properties \
  profiles/builtin/sbrowser-30.0.0.67.json \
  profiles/builtin/sbrowser-30.0.2.61.json \
  profiles/trusted/profile-signing-2026-01.der; do
  rg -q "^${resource//./\\.}$" <<<"$application_entries" || fail "packaged resource가 없습니다: $resource"
done
if rg -q '(^|/)\.local/|\.(pem|pk8|p12|jks|sixdraft|six|crx|zip)$' <<<"$application_entries"; then
  fail "packaged application jar에 정적 비밀·작업 파일이 포함됐습니다."
fi

app_launcher="$app_bundle/Contents/MacOS/SIX Bridge"
[[ -x "$app_launcher" ]] || fail "packaged native launcher가 없습니다."
probe_home="$test_home/native-probe-home"
mkdir -p "$probe_home/Library/Application Support"
chmod 700 "$probe_home" "$probe_home/Library" "$probe_home/Library/Application Support"

run_distribution_probe() {
  local sequence="$1"
  local stdout_file="$test_home/native-probe-$sequence.stdout"
  local stderr_file="$test_home/native-probe-$sequence.stderr"
  local output

  if ! env \
    LC_ALL=C \
    LANG=C \
    JAVA_TOOL_OPTIONS="-Duser.home=$probe_home" \
    "$app_launcher" --distribution-probe >"$stdout_file" 2>"$stderr_file"; then
    fail "packaged native launcher distribution probe가 실패했습니다."
  fi
  output="$(<"$stdout_file")"
  if [[ ! "$output" =~ ^SIXBRIDGE_DISTRIBUTION_PROBE_V1\ jimfs=OK\ signer=([0-9a-f]{64})$ ]]; then
    fail "packaged native launcher distribution probe 출력이 일치하지 않습니다."
  fi
  printf '%s\n' "${BASH_REMATCH[1]}"
}

first_signer_fingerprint="$(run_distribution_probe first)"
second_signer_fingerprint="$(run_distribution_probe second)"
[[ "$first_signer_fingerprint" == "$second_signer_fingerprint" ]] || \
  fail "packaged native launcher에서 signer fingerprint가 유지되지 않습니다."

echo "desktop distribution 검증 통과"
