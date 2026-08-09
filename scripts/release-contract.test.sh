#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
files=(
  SIX-Bridge-Manager-0.1.0-android.apk
  SIX-Bridge-0.1.0-macos-unsigned.dmg
  SHA256SUMS RELEASE-METADATA.json RELEASE-NOTES-KO.md
)

fail() { printf 'release 계약 검증 실패: %s\n' "$1" >&2; exit 1; }

[[ -f "$ROOT_DIR/LICENSE" ]] || fail "LICENSE가 없습니다."
grep -Fq 'Version 2.0, January 2004' "$ROOT_DIR/LICENSE" || fail "Apache-2.0 원문이 아닙니다."
[[ -f "$ROOT_DIR/docs/RELEASE_KO.md" ]] || fail "한글 배포 문서가 없습니다."
[[ -f "$ROOT_DIR/docs/releases/v0.1.0-ko.md" ]] || fail "릴리스 노트 원본이 없습니다."
grep -Fq 'Apache-2.0' "$ROOT_DIR/README.md" || fail "README 라이선스 표기가 없습니다."
for file in "${files[@]}"; do
  grep -Fq "$file" "$ROOT_DIR/docs/RELEASE_KO.md" || fail "공개 파일명이 없습니다: $file"
done
grep -Fq '미서명·미공증' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "macOS 제한 안내가 없습니다."
grep -Fq 'Google Play Protect' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Android 경고 안내가 없습니다."
printf 'release 계약 검증 통과\n'
