# 제한적 UNKNOWN 강제 변환 구현 완료 기록

> 상태: v0.1.2에 구현 완료. 현재의 정식 계약은
> [v0.1.2 제한적 UNKNOWN 강제 변환 설계](superpowers/specs/2026-09-02-v0.1.2-forced-unknown-conversion-design.md)입니다.

## 완료된 정책

- 원래 상태가 `UNKNOWN`이고 모든 blocking issue가 정확히
  `api_usage_dynamic`인 입력만 중앙 `core` 정책이 강제 가능하다고 판정합니다.
- 사용자가 데스크톱, Android 관리자 또는 CLI의 현재 작업에서 위험을
  명시적으로 승인해야 합니다. 기본값은 거부이며 승인은 한 번 소비되고 다른
  입력이나 다음 작업에 재사용되지 않습니다.
- CLI 옵션은 정확히 `--force-compatibility-unknown`입니다. 일반 `--force`나
  자동 강제 변환은 제공하지 않습니다.
- 결과는 상태를 올리지 않고 원래 `UNKNOWN`, issue, 미검증 API와 서명된
  provenance를 보존합니다. `COMPATIBLE` 또는 `PARTIAL`로 표시하지 않으며
  호환성을 보증하지 않습니다.
- Android에서 강제 SixBundle을 가져오는 확인과 실제 시스템 설치 확인은 서로
  다른 작업별 승인입니다.

## 계속 차단하는 경계

다른 원인의 `UNKNOWN`과 모든 `BLOCKED`, `browser_profile_unknown`,
`analysis_budget_exceeded`, unavailable API·adapter는 계속 실패합니다. 다음
검증도 강제 승인으로 우회할 수 없습니다.

- ZIP·CRX3 구조, CRX 서명과 extension ID
- 심볼릭 링크, 경로 이동, 크기·압축률과 분석 예산
- manifest, 필수 리소스와 변환 전후 입력 identity
- source/profile/plan proof와 draft replay authorization
- CRX·APK·SixDraft·SixBundle 서명과 descriptor
- wrapper package, signer, versionCode와 설치 identity
- 보호된 입출력 경로, 원자적 게시와 임시 파일 정리

## provenance와 제품 릴리스 경계

강제 승인 scope, 원래 상태와 issue는 conversion plan proof, SixDraft 서명,
SixBundle compatibility schema와 서명된 wrapper binding에 결합됩니다. 외부
metadata만 고쳐 강제 설치 근거를 만들 수 없습니다.

제품 v0.1.2 릴리스는 관리자 APK, macOS DMG, checksum, metadata와 한글 노트의
exact-five 계약입니다. 사용자 `.six`, `.sixdraft`, CRX, 특정 확장 소스·
바이너리·변환 결과는 제품 asset에 포함하지 않습니다. 강제 변환 산출물은
사용자가 로컬에서 생성하고 별도 실기기 검증을 수행하는 대상입니다.

Samsung Internet APK, baseline browser profile과 특정 확장 ID allowlist는 이
기능을 위해 변경하지 않았습니다.
