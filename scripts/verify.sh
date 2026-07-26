#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"

fail() {
  printf '오류: %s\n' "$1" >&2
  exit 1
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

if [ -x "$project_dir/gradlew" ]; then
  gradle_command="$project_dir/gradlew"
else
  gradle_command="gradle"
fi

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  clean test installDist \
  :bundle-contract:test \
  :android-manager:testDebugUnitTest \
  :android-manager:lintDebug \
  :android-manager:assembleDebug

node --test fixtures/minimal-mv3/background.test.mjs \
  fixtures/minimal-mv3/content.test.mjs \
  fixtures/desktop-mobile-golden/selection-bridge.test.mjs \
  fixtures/desktop-mobile-golden/storage-fallback.test.mjs \
  scripts/lan-receiver-evidence.test.mjs \
  scripts/ai-recipe-lan-receiver.test.mjs \
  scripts/video-nas-lan-receiver.test.mjs

bash -n scripts/fold7-gate.sh
for shell_file in \
  scripts/fold7-extension-validation.sh \
  scripts/fold7-ai-recipe.sh \
  scripts/fold7-video-nas.sh \
  scripts/fold7-converted-extension.sh \
  scripts/fold7-validation-lib.sh \
  scripts/fold7-extension-validation.test.sh \
  scripts/fold7-converted-extension.test.sh; do
  bash -n "$shell_file"
done
bash scripts/fold7-extension-validation.test.sh
bash scripts/fold7-converted-extension.test.sh
bash scripts/verify-mobile-conversion.sh
bash scripts/android-manager-manifest.test.sh
bash scripts/android-manager-distribution.test.sh
