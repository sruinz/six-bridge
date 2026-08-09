#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"

fail() {
  printf '오류: %s\n' "$1" >&2
  exit 1
}

reject_rg_matches() {
  label=$1
  shift
  if matches=$(rg "$@" 2>&1); then
    printf '%s\n' "$matches" >&2
    fail "$label"
  else
    status=$?
    if [ "$status" -ne 1 ]; then
      printf '%s\n' "$matches" >&2
      fail "정적 검사를 실행하지 못했습니다: $label"
    fi
  fi
}

default_java_home="${SIXBRIDGE_DEFAULT_JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
default_android_home="/opt/homebrew/share/android-commandlinetools"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_command="$JAVA_HOME/bin/java"
elif [ -x "$default_java_home/bin/java" ]; then
  JAVA_HOME="$default_java_home"
  export JAVA_HOME
  java_command="$JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
  fail "JDK를 찾을 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요: $JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
  java_command=$(command -v java)
else
  fail "JDK를 찾을 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요."
fi

java_bin_dir=$(dirname -- "$java_command")
for jdk_tool in java javac keytool; do
  [ -x "$java_bin_dir/$jdk_tool" ] ||
    fail "JDK bin/${jdk_tool}을(를) 찾을 수 없습니다. JAVA_HOME에 완전한 JDK 17 이상 경로를 지정하세요."
done
primary_java_home=$(CDPATH= cd -- "$java_bin_dir/.." && pwd)

java_version_output=$("$java_command" -version 2>&1) ||
  fail "JDK를 실행할 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요."
java_major=$(printf '%s\n' "$java_version_output" |
  awk -F '"' 'NR == 1 { split($2, parts, "."); if (parts[1] == "1") print parts[2]; else print parts[1] }')
case "$java_major" in
  ''|*[!0-9]*)
    fail "JDK 버전을 확인할 수 없습니다. JAVA_HOME에 JDK 17 이상 경로를 지정하세요."
    ;;
esac
[ "$java_major" -ge 17 ] ||
  fail "JDK 17 이상이 필요합니다. 현재 major version: $java_major"

default_android_test_java_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [ -n "${SIXBRIDGE_ANDROID_TEST_JAVA_HOME:-}" ]; then
  android_test_java_home="$SIXBRIDGE_ANDROID_TEST_JAVA_HOME"
elif [ -x "$default_android_test_java_home/bin/java" ]; then
  android_test_java_home="$default_android_test_java_home"
else
  android_test_java_home="$primary_java_home"
fi
if [ -n "${SIXBRIDGE_SECURE_ZIP_JAVA_HOME:-}" ]; then
  secure_zip_java_home="$SIXBRIDGE_SECURE_ZIP_JAVA_HOME"
elif [ -x "$default_java_home/bin/java" ]; then
  secure_zip_java_home="$default_java_home"
else
  secure_zip_java_home="$primary_java_home"
fi

for java_home_case in \
  "Android 단위 테스트:$android_test_java_home" \
  "보안 ZIP 경계 테스트:$secure_zip_java_home"; do
  java_home_label=${java_home_case%%:*}
  java_home_path=${java_home_case#*:}
  [ -x "$java_home_path/bin/java" ] ||
    fail "$java_home_label JDK를 찾을 수 없습니다: $java_home_path"
  for jdk_tool in java javac keytool; do
    [ -x "$java_home_path/bin/$jdk_tool" ] ||
      fail "$java_home_label JDK bin/${jdk_tool}을(를) 찾을 수 없습니다: $java_home_path"
  done
  split_java_version_output=$("$java_home_path/bin/java" -version 2>&1) ||
    fail "$java_home_label JDK를 실행할 수 없습니다: $java_home_path"
  split_java_major=$(printf '%s\n' "$split_java_version_output" |
    awk -F '"' 'NR == 1 { split($2, parts, "."); if (parts[1] == "1") print parts[2]; else print parts[1] }')
  case "$split_java_major" in
    ''|*[!0-9]*)
      fail "$java_home_label JDK 버전을 확인할 수 없습니다: $java_home_path"
      ;;
  esac
  [ "$split_java_major" -ge 17 ] ||
    fail "$java_home_label에 JDK 17 이상이 필요합니다. 현재 major version: $split_java_major"
done

if [ -n "${ANDROID_HOME:-}" ]; then
  android_sdk_root="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  android_sdk_root="$ANDROID_SDK_ROOT"
elif [ -d "$default_android_home" ]; then
  android_sdk_root="$default_android_home"
else
  fail "Android SDK를 찾을 수 없습니다. ANDROID_HOME에 SDK 36 경로를 지정하세요."
fi

[ -f "$android_sdk_root/platforms/android-36/android.jar" ] ||
  fail "Android SDK platform 36 android.jar가 없습니다. ANDROID_HOME을 확인하세요: $android_sdk_root"
for sdk_tool in aapt2 d8 zipalign apksigner; do
  [ -x "$android_sdk_root/build-tools/36.0.0/$sdk_tool" ] ||
    fail "Android SDK build-tools 36.0.0 ${sdk_tool}을(를) 찾을 수 없습니다. ANDROID_HOME을 확인하세요: $android_sdk_root"
done
[ -x "$android_sdk_root/cmdline-tools/latest/bin/apkanalyzer" ] ||
  fail "Android SDK apkanalyzer가 없습니다. ANDROID_HOME/cmdline-tools/latest를 확인하세요: $android_sdk_root"

ANDROID_HOME="$android_sdk_root"
ANDROID_SDK_ROOT="$android_sdk_root"
export ANDROID_HOME ANDROID_SDK_ROOT

command -v rg >/dev/null 2>&1 ||
  fail "필수 정적 검사 도구 rg를 찾을 수 없습니다. ripgrep을 설치하세요."

reject_rg_matches \
  "제품 코드에 특정 확장 분기가 포함되어 있습니다." \
  -ni \
  'nngceckbapebfimnlniiiahkandclblb|dhdgffkkebhmkfjojejmpbldmpobfkfo|bitwarden|tampermonkey' \
  core/src/main desktop-app/src/main android-manager/src/main

reject_rg_matches \
  "팝업 보호 asset이 금지된 데이터 또는 네트워크 API를 사용합니다." \
  -n \
  -e '\bfetch\s*\(' \
  -e '\bXMLHttpRequest\b' \
  -e '\b(?:chrome|browser)\s*(?:\?\.|\.)\s*storage\b' \
  -e "\\b(?:chrome|browser)\\s*\\[\\s*['\"]storage['\"]\\s*\\]" \
  -e '(?:\?\.|\.)\s*value\b' \
  -e "\\[\\s*['\"]value['\"]\\s*\\]" \
  core/src/main/resources/dev/sixbridge/adapters/popup-native-autofill-guard.v1.js

if [ -x "$project_dir/gradlew" ]; then
  gradle_command="$project_dir/gradlew"
else
  gradle_command="gradle"
fi

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
JAVA_HOME="$secure_zip_java_home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  clean \
  :bundle-contract:test \
  :core:test \
  :cli:test \
  :api-probe:test \
  :android-wrapper-template:test \
  :cli:installDist \
  --max-workers=1

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
JAVA_HOME="$android_test_java_home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  :desktop-app:test \
  --max-workers=1

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
JAVA_HOME="$android_test_java_home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  -Psixbridge.skipSecureZipHostTests=true \
  :android-manager:testDebugUnitTest \
  :android-manager:lintDebug \
  :android-manager:assembleDebug \
  --max-workers=1

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
JAVA_HOME="$secure_zip_java_home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  :android-manager:testDebugUnitTest \
  --tests dev.sixbridge.manager.conversion.OnDeviceInputServiceTest \
  --tests dev.sixbridge.manager.conversion.DefaultConversionRuntimeTest \
  --max-workers=1

node --test fixtures/minimal-mv3/background.test.mjs \
  fixtures/minimal-mv3/content.test.mjs \
  fixtures/desktop-mobile-golden/selection-bridge.test.mjs \
  fixtures/desktop-mobile-golden/storage-fallback.test.mjs \
  fixtures/popup-native-autofill-guard/guard-runtime.test.mjs \
  scripts/api-probe-artifact-verify.test.mjs \
  scripts/api-probe-receiver.test.mjs \
  scripts/lan-receiver-evidence.test.mjs \
  scripts/ai-recipe-lan-receiver.test.mjs \
  scripts/video-nas-lan-receiver.test.mjs

bash -n scripts/fold7-gate.sh
for shell_file in \
  scripts/api-probe-generation.test.sh \
  scripts/desktop-large-extension-analysis.sh \
  scripts/desktop-large-extension-analysis.test.sh \
  scripts/fold7-api-probe.sh \
  scripts/fold7-api-probe.test.sh \
  scripts/fold7-extension-validation.sh \
  scripts/fold7-ai-recipe.sh \
  scripts/fold7-video-nas.sh \
  scripts/fold7-converted-extension.sh \
  scripts/fold7-ondevice-conversion-flow.sh \
  scripts/fold7-popup-autofill-url-gate.sh \
  scripts/fold7-validation-lib.sh \
  scripts/fold7-extension-validation.test.sh \
  scripts/fold7-converted-extension.test.sh \
  scripts/fold7-ondevice-conversion-flow.test.sh \
  scripts/fold7-popup-autofill-url-gate.test.sh \
  scripts/verify-jdk-routing.test.sh; do
  bash -n "$shell_file"
done
bash scripts/api-probe-generation.test.sh
bash scripts/desktop-large-extension-analysis.test.sh
bash scripts/fold7-api-probe.test.sh
bash scripts/fold7-extension-validation.test.sh
bash scripts/fold7-converted-extension.test.sh
bash scripts/fold7-ondevice-conversion-flow.test.sh
bash scripts/fold7-popup-autofill-url-gate.test.sh
bash scripts/verify-jdk-routing.test.sh
bash scripts/verify-mobile-conversion.sh
bash scripts/android-manager-manifest.test.sh
bash scripts/android-manager-distribution.test.sh
