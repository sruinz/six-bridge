# PoC 검증 결과

검증일: 2026-07-24

## 호스트 검증

- Kotlin core, CLI, 테스트 소스가 JDK 26과 JVM target 17에서 컴파일된다.
- 실제 Android SDK 래퍼 빌드를 포함한 JUnit 테스트 20개가 통과했다.
- 제공된 삼성 인터넷 APK에서 버전 `30.0.2.61`, versionCode
  `1300261502`, 확장 API namespace 58개, 래퍼 계약 표식 4개를 추출했다.
- 추출 프로필이 `profiles/sbrowser-30.0.2.61.json`과 일치한다.
- 기준 APK SHA-256은
  `6fad5501a3f9823c7dd9fafdfe1204f7246e487c8dea9c192426c07c8c48c981`이다.
- 최소 게이트와 네 대상 확장의 래퍼가 `apksigner verify` 및
  `apkanalyzer` 계약 검사를 통과했다.
- 동일한 최소 소스를 재빌드했을 때 CRX ID, 래퍼 패키지, 서명자가 유지되고
  `versionCode`가 단조 증가했다.
- Android 11+ 설치 규칙에 맞게 `resources.arsc`를 비압축·4바이트 정렬한
  래퍼로 수정했으며, 실제 Fold7 업데이트 설치가 성공했다.

현재 로컬 래퍼 인증서 SHA-256은
`436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a`이다.
이는 로컬 상태이며 프로젝트 전체의 공개 릴리스 식별자로 취급해서는 안 된다.

## 확장 정적 분석

| 순서 | 확장 프로그램 | 정적 결과 | 비지원 API |
|---:|---|---|---|
| 1 | 스마트 컨텍스트 메뉴 | compatible | 없음 |
| 2 | AI 레시피 추출기 | compatible | 없음 |
| 3 | Video Download to NAS | compatible | 없음 |
| 4 | MediaNab | partial | downloads, nativeMessaging |

네 입력 CRX3 모두 서명 검증을 통과했다. 바이트 비교 결과 생성된
`dist/<id>.crx`가 Chrome 웹 스토어 입력과 동일했다.

## Fold7 게이트 현황

다음 항목은 실제 SM-F966N에서 확인됐다.

- 무선 ADB 연결
- 래퍼 APK 설치
- `six://ex` Activity 해석
- ContentProvider의 `Cr24` CRX3 스트림
- 삼성 Add-ons 목록 인식
- 확장 활성화
- 삼성 인터넷 재실행 뒤 활성 상태 유지

모바일 UI에서 action 팝업 접근이 어려워 실제 확장 런타임 실행 여부는 아직
최종 확인하지 않았다. 일반 HTTPS 페이지에 표시되는 `SIX Bridge OK` 진단
배지로 content script, background service worker, runtime 메시징,
`storage.local` 실행을 확인한 뒤 게이트 A를 완료한다.

Android 관리자 앱 단계는 게이트 A가 완료될 때까지 시작하지 않는다.
