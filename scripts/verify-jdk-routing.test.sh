#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT

mkdir -p "$fixture_dir/scripts" "$fixture_dir/bin"
cp "$project_dir/scripts/verify.sh" "$fixture_dir/scripts/verify.sh"

cat > "$fixture_dir/gradlew" <<'SCRIPT'
#!/bin/sh
printf '%s|%s\n' "$JAVA_HOME" "$*" >> "$SIXBRIDGE_JAVA_LOG"
SCRIPT
chmod +x "$fixture_dir/gradlew"

cat > "$fixture_dir/bin/rg" <<'SCRIPT'
#!/bin/sh
exit 1
SCRIPT
cat > "$fixture_dir/bin/node" <<'SCRIPT'
#!/bin/sh
exit 0
SCRIPT
cat > "$fixture_dir/bin/bash" <<'SCRIPT'
#!/bin/sh
exit 0
SCRIPT
chmod +x "$fixture_dir/bin/rg" "$fixture_dir/bin/node" "$fixture_dir/bin/bash"

jdk17=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
jdk26=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
android_home=/opt/homebrew/share/android-commandlinetools

SIXBRIDGE_JAVA_LOG="$fixture_dir/java-routing.log" \
JAVA_HOME="$jdk17" \
SIXBRIDGE_ANDROID_TEST_JAVA_HOME="$jdk17" \
SIXBRIDGE_SECURE_ZIP_JAVA_HOME="$jdk26" \
ANDROID_HOME="$android_home" \
PATH="$fixture_dir/bin:$PATH" \
  "$fixture_dir/scripts/verify.sh"

invocation_count=$(wc -l < "$fixture_dir/java-routing.log" | tr -d ' ')
if [ "$invocation_count" -ne 4 ]; then
  printf '예상한 Gradle 호출은 4개지만 실제로는 %s개입니다.\n' "$invocation_count" >&2
  exit 1
fi
invocation_1=$(sed -n '1p' "$fixture_dir/java-routing.log")
invocation_2=$(sed -n '2p' "$fixture_dir/java-routing.log")
invocation_3=$(sed -n '3p' "$fixture_dir/java-routing.log")
invocation_4=$(sed -n '4p' "$fixture_dir/java-routing.log")

case "$invocation_1" in
  "$jdk26|"*':bundle-contract:test'*':core:test'*) ;;
  *)
    printf '보안 core suite가 JDK 26으로 실행되지 않았습니다: %s\n' "$invocation_1" >&2
    exit 1
    ;;
esac
case "$invocation_1" in
  *':desktop-app:test'*)
    printf 'JDK 26 core suite에 desktop-app이 섞였습니다: %s\n' "$invocation_1" >&2
    exit 1
    ;;
esac
case "$invocation_2" in
  "$jdk17|"*':desktop-app:test'*) ;;
  *)
    printf 'desktop-app suite가 JDK 17로 실행되지 않았습니다: %s\n' "$invocation_2" >&2
    exit 1
    ;;
esac
case "$invocation_3" in
  "$jdk17|"*':android-manager:testDebugUnitTest'*':android-manager:assembleDebug'*) ;;
  *)
    printf '일반 Android suite가 JDK 17로 실행되지 않았습니다: %s\n' "$invocation_3" >&2
    exit 1
    ;;
esac
case "$invocation_4" in
  "$jdk26|"*'OnDeviceInputServiceTest'*'DefaultConversionRuntimeTest'*) ;;
  *)
    printf '보안 ZIP 경계 suite가 JDK 26으로 실행되지 않았습니다: %s\n' "$invocation_4" >&2
    exit 1
    ;;
esac

printf 'verify.sh JDK 분리 라우팅 테스트 통과\n'
