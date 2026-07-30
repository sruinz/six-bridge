#!/usr/bin/env bash
set -euo pipefail

readonly serial="${1:?adb serial이 필요합니다}"
readonly apk="android-manager/build/outputs/apk/debug/android-manager-debug.apk"
readonly package="dev.sixbridge.manager"
readonly evidence_dir=".local/evidence"
readonly evidence="${evidence_dir}/$(date +%Y%m%d-%H%M%S)-mobile-analysis-gate.log"

mkdir -p "$evidence_dir"

adb -s "$serial" get-state
adb -s "$serial" install -r "$apk"
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell monkey -p "$package" 1
adb -s "$serial" shell pidof "$package"
adb -s "$serial" shell run-as "$package" \
    find cache -maxdepth 1 -name 'sixbridge-analysis-*' -type d -print

logcat_output=$(adb -s "$serial" logcat -d -v threadtime)
grep_status=0
printf '%s' "$logcat_output" |
    grep -E 'sixbridge|AndroidRuntime|ANR' >"$evidence" ||
    grep_status=$?
[ "$grep_status" -eq 0 ] || [ "$grep_status" -eq 1 ] ||
    exit "$grep_status"

printf '자동 안전 점검 완료: %s\n' "$evidence"
printf '%s\n' \
    'Bitwarden URL 입력과 화면 결과 확인은 문서에 따라 수동으로 진행하세요.'
