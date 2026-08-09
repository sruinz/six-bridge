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
grep -Fq 'SHA256SUMS' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "SHA-256 확인 절차가 없습니다."
grep -Fq 'RELEASE-METADATA.json' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "signer metadata 안내가 없습니다."
grep -Fq 'Gatekeeper' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Gatekeeper 제한 안내가 없습니다."
grep -Fq 'https://developer.apple.com/support/developer-id/' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Apple Developer ID 링크가 없습니다."
grep -Fq 'https://developer.apple.com/help/glossary/developer-id-certificate/' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Apple Developer ID certificate 링크가 없습니다."
grep -Fq 'https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Apple notarization 링크가 없습니다."
grep -Fq '사용자에게 있습니다.' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "사용자 책임 안내가 없습니다."
grep -Fq '스토어 URL을 입력하면 extension ID와 다운로드 요청이 Google 및 허용된 redirect host에 전송됩니다.' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "스토어 URL 네트워크 경계가 없습니다."
grep -Fq '분석·변환 내용과 생성 결과를 원격 분석 서버에 업로드하지 않습니다.' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "분석·변환 업로드 금지 경계가 없습니다."
grep -Fq '변환된 확장 자체의 외부 네트워크 통신은 해당 확장의 동작입니다.' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "변환 확장 네트워크 경계가 없습니다."
grep -Fq '비밀번호, 토큰, 개인키, keystore' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "비밀정보 제출 금지 안내가 없습니다."
grep -Fq '계정·cookie·storage 내용' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "개인 데이터 제출 금지 안내가 없습니다."
grep -Fq '원본/변환 확장과 사용자 데이터가 포함된 로그' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "민감 로그 제출 금지 안내가 없습니다."
printf 'release 계약 검증 통과\n'
