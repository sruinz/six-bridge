#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_APK="${ROOT_DIR}/android-wrapper-template/build/outputs/apk/release/android-wrapper-template-release-unsigned.apk"
readonly DESTINATION="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.apk"
readonly HASH_FILE="${ROOT_DIR}/android-manager/src/main/assets/wrapper-template.sha256"
readonly SDK_ROOT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly ZIPALIGN="${SDK_ROOT}/build-tools/36.0.0/zipalign"
readonly DEFAULT_JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"

fail() {
    printf '오류: %s\n' "$1" >&2
    exit 1
}

normalize_template() {
    local source_apk="$1"
    local output_apk="$2"
    local work_dir
    local archive

    [[ -f "$source_apk" ]] || fail "unsigned template source APK를 찾을 수 없습니다: $source_apk"
    work_dir="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-wrapper-template.XXXXXX")"
    archive="${work_dir}/normalized.apk"
    trap 'rm -rf -- "$work_dir"' RETURN
    mkdir -p "${work_dir}/assets"
    unzip -p "$source_apk" AndroidManifest.xml >"${work_dir}/AndroidManifest.xml"
    unzip -p "$source_apk" classes.dex >"${work_dir}/classes.dex"
    unzip -p "$source_apk" resources.arsc >"${work_dir}/resources.arsc"
    unzip -p "$source_apk" assets/extension.crx >"${work_dir}/assets/extension.crx"
    for entry in \
        "${work_dir}/AndroidManifest.xml" \
        "${work_dir}/classes.dex" \
        "${work_dir}/resources.arsc" \
        "${work_dir}/assets/extension.crx"; do
        [[ -s "$entry" ]] || fail "template runtime entry를 읽을 수 없습니다: $entry"
        TZ=UTC touch -t 198001010000 "$entry"
    done
    (
        cd "$work_dir"
        zip -q -0 -X "$archive" \
            AndroidManifest.xml \
            classes.dex \
            resources.arsc \
            assets/extension.crx
    )
    "$ZIPALIGN" -f 4 "$archive" "$output_apk"
    trap - RETURN
    rm -rf -- "$work_dir"
}

main() {
    local mode="write"
    local gradle_command
    local java_home
    local temp_dir
    local normalized
    local actual_hash
    local pinned_hash

    case "${1:-}" in
        "") ;;
        --check) mode="check" ;;
        *) fail "지원하지 않는 인자입니다: $1" ;;
    esac
    [[ $# -le 1 ]] || fail "인자는 하나만 지정할 수 있습니다."
    [[ -e "${ROOT_DIR}/.git" ]] || fail "SIX Bridge repository root에서 실행해야 합니다."
    [[ -x "$ZIPALIGN" ]] || fail "Android SDK zipalign을 찾을 수 없습니다: $ZIPALIGN"
    if [[ -x "${ROOT_DIR}/gradlew" ]]; then
        gradle_command="${ROOT_DIR}/gradlew"
    else
        gradle_command="$(command -v gradle || true)"
    fi
    [[ -n "$gradle_command" ]] || fail "Gradle을 찾을 수 없습니다."
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        java_home="$JAVA_HOME"
    else
        java_home="$DEFAULT_JAVA_HOME"
    fi
    [[ -x "${java_home}/bin/java" ]] || fail "JDK를 찾을 수 없습니다: $java_home"

    (
        cd "$ROOT_DIR"
        JAVA_HOME="$java_home" \
        ANDROID_HOME="$SDK_ROOT" \
        ANDROID_SDK_ROOT="$SDK_ROOT" \
        GRADLE_USER_HOME="${ROOT_DIR}/.local/gradle-home" \
            "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
                :android-wrapper-template:assembleRelease
    )

    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-wrapper-template-output.XXXXXX")"
    trap "rm -rf -- '${temp_dir}'" EXIT
    normalized="${temp_dir}/wrapper-template.apk"
    normalize_template "$SOURCE_APK" "$normalized"

    if [[ "$mode" == "check" ]]; then
        [[ -f "$DESTINATION" ]] || fail "committed wrapper template APK를 찾을 수 없습니다: $DESTINATION"
        [[ -f "$HASH_FILE" ]] || fail "committed wrapper template SHA-256을 찾을 수 없습니다: $HASH_FILE"
        cmp -s "$normalized" "$DESTINATION" ||
            fail "committed wrapper template APK가 현재 source와 일치하지 않습니다."
        actual_hash="$(shasum -a 256 "$DESTINATION" | awk '{print $1}')"
        pinned_hash="$(tr -d '\r\n' <"$HASH_FILE")"
        [[ "$pinned_hash" =~ ^[0-9a-f]{64}$ ]] ||
            fail "template SHA-256은 소문자 64자리 hex여야 합니다."
        [[ "$actual_hash" == "$pinned_hash" ]] ||
            fail "committed wrapper template APK와 SHA-256이 일치하지 않습니다."
        printf '검증 완료: %s\n' "$DESTINATION"
        return
    fi

    mkdir -p "$(dirname "$DESTINATION")"
    mv -f "$normalized" "${temp_dir}/published.apk"
    cp "${temp_dir}/published.apk" "${DESTINATION}.tmp"
    mv -f "${DESTINATION}.tmp" "$DESTINATION"
    shasum -a 256 "$DESTINATION" | awk '{print $1}' >"${HASH_FILE}.tmp"
    mv -f "${HASH_FILE}.tmp" "$HASH_FILE"
    printf '생성 완료: %s\n' "$DESTINATION"
}

main "$@"
