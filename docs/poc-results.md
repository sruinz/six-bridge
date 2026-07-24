# PoC 검증 결과

검증일: 2026-07-24

## 호스트 검증

- Kotlin core, CLI, 테스트 소스가 JDK 26과 JVM target 17에서 컴파일된다.
- 실제 Android SDK 래퍼 빌드를 포함한 JUnit 테스트 77개와 런타임 배지 Node
  테스트 9개가 통과했다.
- 제공된 삼성 인터넷 APK에서 버전 `30.0.2.61`, versionCode
  `1300261502`, 확장 API namespace 58개, 래퍼 계약 표식 4개를 추출했다.
- 추출 프로필이 `profiles/sbrowser-30.0.2.61.json`과 일치한다.
- 기준 APK SHA-256은
  `6fad5501a3f9823c7dd9fafdfe1204f7246e487c8dea9c192426c07c8c48c981`이다.
- 최소 게이트와 네 대상 확장의 래퍼가 `apksigner verify`, `aapt2` 매니페스트
  계약 검사와 `zipalign` 정렬 검사를 통과했다.
- 동일한 최소 소스를 재빌드했을 때 CRX ID, 래퍼 패키지, 서명자가 유지되고
  `versionCode`가 단조 증가했다.
- Android 11+ 설치 규칙에 맞게 `resources.arsc`를 비압축·4바이트 정렬한
  래퍼로 수정했으며, 실제 Fold7 업데이트 설치가 성공했다.
- 최종 최소 게이트 산출물은 `versionCode 8`이며 CRX, 래퍼 APK, SixBundle의
  SHA-256은 각각
  `cc9af05ca1618caee36873d33c7ba182648b917896ba2d0ef37066aa48b1e25d`,
  `8bdf243bd1f3f70db5531187712081966701c25d06c26e790cb8a4cfbaa6b2ef`,
  `2d9b60f3943332d9285889c2fee08354032fe846278e9344708f3e0300b08580`이다.

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

## 정적 분석 범위

- manifest 런타임 리소스, HTML 실행 script, 정적 import/export와
  `importScripts`의 로컬 의존성을 재귀 분석한다.
- 문자열·주석·정규식·템플릿 표현식과 HTML 실행 문맥을 구분하고, 경로 escape와
  entity를 디코딩한 뒤 root·symlink 경계를 검사한다.
- 안전하게 해석할 수 없는 정적 경로와 import map·module 조합은 호환으로
  과대 판정하지 않고 `unknown`으로 차단한다.
- 동적으로 계산되는 script URL은 PoC의 정적 폐쇄 집합 밖이다.
  객체 멤버 또는 지역 함수로 가려진 `importScripts`는 보수적으로 의존성으로
  인식해 오탐할 수 있다.

## Fold7 게이트 현황

다음 항목은 실제 SM-F966N에서 확인됐다.

- 무선 ADB 연결
- 래퍼 APK 설치
- `six://ex` Activity 해석
- ContentProvider의 `Cr24` CRX3 스트림
- 삼성 Add-ons 목록 인식
- 확장 활성화
- 삼성 인터넷 재실행 뒤 활성 상태 유지

### 런타임 실행

- 일반 HTTPS 페이지에서 `SIX Bridge OK` 배지가 한 번 표시됐다.
- 배지는 8초 뒤 제거됐고 새로고침 시 한 번 다시 표시됐다.
- 삼성 인터넷을 재시작한 뒤에도 재활성화 없이 다른 HTTPS 페이지에서
  배지가 표시됐다.
- 최종 보안·계약 보강본인 래퍼 `versionCode 8`을 Fold7에 업데이트 설치한
  뒤에도 위의 인식, 활성화, 배지 표시·제거, 새로고침, 브라우저 재시작
  시나리오가 모두 동일하게 동작했다.
- 이 결과로 content script, MV3 background service worker, runtime 메시징,
  `storage.local` 경로의 실제 실행을 확인했다.

게이트 A를 완료했다. 다음 단계는 네 확장의 Fold7 호환성 검증이며 Android
관리자 앱 구현은 네 확장 검증 결과를 기록한 뒤 시작한다.
