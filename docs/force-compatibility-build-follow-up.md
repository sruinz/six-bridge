# 호환성 강제 생성 옵션 후속 작업

> 상태: 이 초기 검토는 승인된
> [v0.1.2 제한적 UNKNOWN 강제 변환 설계](superpowers/specs/2026-09-02-v0.1.2-forced-unknown-conversion-design.md)로
> 구체화됐다. 충돌하는 내용은 v0.1.2 설계를 따른다. 특히 v0.1.2는
> `api_usage_dynamic` 계열 `UNKNOWN`만 강제할 수 있으며 `BLOCKED`는 계속
> 차단한다.

## 목적

정적 분석 결과가 `UNKNOWN` 또는 `BLOCKED`이더라도 사용자가 확인된 위험을
직접 감수하고 테스트용 CRX, 래퍼 APK와 SixBundle을 생성할 수 있는 명시적
강제 생성 경로를 후속 기능으로 추가한다.

강제 생성은 호환성 보증이 아니다. 원래 분석 상태, issue와 미검증 API를
그대로 보존하며 결과를 `COMPATIBLE`이나 `PARTIAL`로 바꾸지 않는다.

## 사용자 흐름

- CLI에는 일반적인 `--force` 대신 호환성 차단만을 대상으로 하는 명확한
  옵션을 제공한다. 후보 이름은 `--force-compatibility`다.
- 데스크톱과 Android 관리자에서는 원래 상태와 차단 사유를 먼저 표시하고,
  사용자가 해당 변환 작업마다 별도로 승인해야 생성 버튼을 활성화한다.
- 승인은 전역 설정이나 다음 변환에 재사용하지 않는다.
- 생성 완료 화면과 파일명에는 강제 생성 산출물임을 명확하게 표시한다.
- 설치 단계에서도 원래 호환성 상태와 미검증 항목을 다시 보여 준다.

## 우회할 수 있는 범위

강제 옵션은 호환성 판정만 우회할 수 있다. 다음 안전 검증은 절대로
우회하지 않는다.

- 잘못된 ZIP·CRX3 구조, CRX 서명 또는 extension ID 불일치
- 경로 이동, 심볼릭 링크, 크기·압축률·분석 예산 제한 위반
- 읽을 수 없거나 구조적으로 잘못된 manifest와 필수 리소스 누락
- 변환 전후 입력 변경, source provenance 또는 plan hash 불일치
- CRX·APK·SixDraft·SixBundle 서명과 descriptor 정합성 실패
- wrapper package, signer, versionCode와 설치 identity 불일치
- 보호된 입력·출력 경로, 원자적 게시와 임시 파일 정리 실패

호환성 issue에는 강제 생성 가능 여부를 나타내는 분류를 추가한다. 모든
`BLOCKED` 상태를 일괄 허용하지 않고, 유효하고 안전하게 패키징할 수 있는
입력에서 발생한 API·동작 호환성 issue만 강제 대상으로 인정한다.

## 산출물 provenance

강제 생성 결과에는 최소한 다음 정보를 서명된 보고서와 SixBundle에 포함한다.

- `forcedCompatibility=true`
- 강제 생성 전 원래 `CompatibilityStatus`
- 원래 issue 코드, blocking 값과 미검증·사용 불가 API
- 사용자가 승인한 강제 생성 범위
- 적용한 adapter와 변환 전후 source SHA-256

공개 릴리스 준비와 자동 배포 gate는 강제 생성 산출물을 기본적으로 거부한다.
별도 검증과 명시적 릴리스 정책 없이는 일반 배포 자산으로 게시하지 않는다.

## 구현 범위

- `core`: 강제 가능한 issue 분류, 원래 보고서를 보존하는 conversion plan과
  provenance 계약
- `cli`: 명시적 옵션, 경고 출력, 비대화형 실행의 승인 계약
- `desktop-app`: 작업별 위험 확인과 강제 생성 결과 표시
- `android-manager`: 분석·검토·생성·설치 단계의 반복 경고와 승인 상태 유지
- `bundle-contract`: 강제 생성 provenance의 strict reader·writer 검증
- release scripts: 강제 생성 산출물 기본 거부

삼성 인터넷 APK, baseline browser profile 또는 기존 분석 결과를 강제 생성에
맞춰 수정하지 않는다. 특정 확장 ID나 제품별 allowlist도 추가하지 않는다.

## 완료 조건

1. 옵션이 없으면 기존과 동일하게 `UNKNOWN`과 `BLOCKED` 변환이 실패한다.
2. 강제 가능한 호환성 issue와 명시적 승인 조합에서만 산출물이 생성된다.
3. 구조·서명·경로·identity 관련 실패는 옵션이 있어도 계속 차단된다.
4. 원래 상태와 issue가 CLI, 화면, SixBundle에서 손실 없이 일치한다.
5. 강제 생성 여부가 재분석, draft, build와 설치 단계 사이에서 바뀌지 않는다.
6. 강제 생성 승인 순서가 산출물 provenance와 identity를 비결정적으로 바꾸지
   않는다.
7. 정상 `COMPATIBLE`·`PARTIAL` 변환과 adapter 승인 회귀 테스트가 모두
   통과한다.
8. 강제 생성 APK는 별도 수동 실기기 검증을 통과하기 전까지 호환된 것으로
   표시하거나 배포하지 않는다.
