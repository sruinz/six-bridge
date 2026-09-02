#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${SIXBRIDGE_RELEASE_CONTRACT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
files=(
  SIX-Bridge-Manager-0.1.2-android.apk
  SIX-Bridge-0.1.2-macos-unsigned.dmg
  SHA256SUMS RELEASE-METADATA.json RELEASE-NOTES-KO.md
)

fail() { printf 'release 계약 검증 실패: %s\n' "$1" >&2; exit 1; }

[[ -f "$ROOT_DIR/LICENSE" ]] || fail "LICENSE가 없습니다."
grep -Fq 'Version 2.0, January 2004' "$ROOT_DIR/LICENSE" || fail "Apache-2.0 원문이 아닙니다."
[[ -f "$ROOT_DIR/docs/RELEASE_KO.md" ]] || fail "한글 배포 문서가 없습니다."
[[ -f "$ROOT_DIR/docs/releases/v0.1.0-ko.md" ]] || fail "v0.1.0 역사 릴리스 노트가 없습니다."
[[ -f "$ROOT_DIR/docs/releases/v0.1.1-ko.md" ]] || fail "v0.1.1 릴리스 노트가 없습니다."
[[ -f "$ROOT_DIR/docs/releases/v0.1.2-ko.md" ]] || fail "v0.1.2 릴리스 노트가 없습니다."
grep -Fq 'Apache-2.0' "$ROOT_DIR/README.md" || fail "README 라이선스 표기가 없습니다."
for file in "${files[@]}"; do
  grep -Fq "$file" "$ROOT_DIR/docs/RELEASE_KO.md" || fail "공개 파일명이 없습니다: $file"
done
listed_files="$({
  awk '
    $0 == "공개 산출물은 다음 다섯 파일입니다." { in_list = 1; next }
    in_list && /^$/ { if (seen) exit; next }
    in_list && /^- `[^`]+`$/ {
      file = $0
      sub(/^- `/, "", file)
      sub(/`$/, "", file)
      print file
      seen = 1
      next
    }
    in_list { exit }
  ' "$ROOT_DIR/docs/RELEASE_KO.md"
} | LC_ALL=C sort)"
expected_files="$(printf '%s\n' "${files[@]}" | LC_ALL=C sort)"
[[ "$listed_files" == "$expected_files" ]] || fail "공개 산출물 목록이 정확히 다섯 파일이 아닙니다."
grep -Fq '제품 버전 `0.1.2`' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "제품 버전 0.1.2 안내가 없습니다."
grep -Fq 'Android versionCode `3`' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Android versionCode 3 안내가 없습니다."
grep -Fq 'macOS native package version `1.0.2`' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "macOS native package version 1.0.2 안내가 없습니다."
grep -Fq '미서명·미공증' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "macOS 제한 안내가 없습니다."
grep -Fq 'Google Play Protect' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Android 경고 안내가 없습니다."
grep -Fq '다운로드한 APK와 DMG의 SHA-256을' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "다운로드 APK·DMG SHA-256 비교 안내가 없습니다."
grep -Fq 'shasum -a 256 SIX-Bridge-Manager-0.1.2-android.apk' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Android SHA-256 명령이 없습니다."
grep -Fq 'shasum -a 256 SIX-Bridge-0.1.2-macos-unsigned.dmg' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "macOS SHA-256 명령이 없습니다."
grep -Fq '값이 다르거나 metadata의 제품 버전·signer 정보가 기대와 다르면 설치하거나' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "checksum·metadata 불일치 중단 안내가 없습니다."
grep -Fq 'macOS Gatekeeper가 개발자를 확인할 수 없다는 경고를 표시할 수' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "Gatekeeper 개발자 확인 경고 안내가 없습니다."
grep -Fq '`SIX Bridge.app`을 `Applications` 링크로 끌어 놓으세요.' "$ROOT_DIR/docs/RELEASE_KO.md" || fail "macOS Applications 설치 안내가 없습니다."
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
grep -Fq 'https://github.com/sruinz/six-bridge/tree/v0.1.1' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "v0.1.1 절대 source URL이 없습니다."
grep -Fq 'compatibility passthrough' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "zero-adapter compatibility passthrough 안내가 없습니다."
grep -Fq 'passthrough 신원으로 오인하지 않으며' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "zero-adapter identity mode 경계가 없습니다."
grep -Fq '새로 만들거나 검증된 기존 대상으로 선택·결속한 `CONVERTED` 설치 신원' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "CreateNew·UseExisting identity 안내가 없습니다."
grep -Fq 'provisional identity' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "재시도 identity 재사용 안내가 없습니다."
grep -Fq 'storage_area_unknown' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "storage_area_unknown 경고 안내가 없습니다."
grep -Fq '비차단 정적 경고' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "storage 경고의 비차단 경계가 없습니다."
grep -Fq 'v0.1.0 Release·tag·assets' "$ROOT_DIR/docs/releases/v0.1.1-ko.md" || fail "v0.1.0 보존 안내가 없습니다."
grep -Fq 'https://github.com/sruinz/six-bridge/tree/v0.1.2' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "v0.1.2 절대 source URL이 없습니다."
grep -Fq '`api_usage_dynamic`' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "강제 가능 issue 안내가 없습니다."
grep -Fq '`--force-compatibility-unknown`' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "정확한 CLI 승인 옵션 안내가 없습니다."
grep -Fq '원래 `UNKNOWN`' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "원래 UNKNOWN 보존 안내가 없습니다."
grep -Fq '모든 `BLOCKED`' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "BLOCKED 차단 안내가 없습니다."
grep -Fq '사용자 `.six`, `.sixdraft`, CRX' "$ROOT_DIR/docs/releases/v0.1.2-ko.md" || fail "사용자 확장 데이터 제외 안내가 없습니다."
grep -Fq '[v0.1.2 다운로드](https://github.com/sruinz/six-bridge/releases/tag/v0.1.2)' "$ROOT_DIR/README.md" || fail "README v0.1.2 Release 다운로드 링크가 없습니다."
grep -Fq '[v0.1.1 역사 릴리스 노트](docs/releases/v0.1.1-ko.md)' "$ROOT_DIR/README.md" || fail "README v0.1.1 역사 링크가 없습니다."
grep -Fq '[v0.1.0 역사 릴리스 노트](docs/releases/v0.1.0-ko.md)' "$ROOT_DIR/README.md" || fail "README v0.1.0 역사 링크가 없습니다."
printf 'release 계약 검증 통과\n'
