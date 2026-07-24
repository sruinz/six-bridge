# PoC 검증 결과

검증일: 2026-07-24

모바일 변환 재검증일: 2026-07-25

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

게이트 A를 완료했다.

## 스마트 컨텍스트 메뉴 실기기 결과

네 확장 검증의 첫 대상으로 스마트 컨텍스트 메뉴를 Fold7에 설치했다.
초기 산출물은 `resources.arsc`가 압축된 오래된 래퍼라 Android 11 설치 규칙에
걸렸으며, 현행 빌더로 `versionCode 2`를 다시 생성했다. 새 래퍼는
`zipalign`, APK v3 서명, SixBundle 해시 검증을 통과했고 다음 호스트 검사가
성공했다.

- 래퍼 APK 설치
- `six://ex` Activity 해석
- ContentProvider의 CRX3 스트림
- 삼성 Add-ons 목록 표시와 활성화
- 브라우저 재시작 뒤 `ON`, `OnExtensionLoaded`, `addAction` 등록

그러나 action popup 진입점을 찾을 수 없었고, 터치로 본문 텍스트를 선택해도
스마트 컨텍스트 메뉴 UI가 나타나지 않았다. 삼성 인터넷의 기본 선택 기능만
표시됐다.

소스와 런타임 로그를 비교한 결과 content script가 `mouseup`과 `mousedown`
이벤트만 처리하고 모바일의 `touch`, `pointer`, `selectionchange`를 처리하지
않는 것이 확인됐다. `contextMenus` 권한도 선언만 했고 실제 메뉴 등록 코드는
없다.

Chrome 웹 스토어 CRX의 `_metadata` 디렉터리에 대해 삼성 인터넷이 시스템
예약 이름 경고를 남겼지만, 해당 경고 뒤에 설치·로딩·action 등록이 계속
완료됐으므로 주된 실행 실패와 분리해 기록한다.

이 결과로 기존 정적 `compatible` 판정은 설치 API 호환성만 설명하며 모바일
입력·UI 호환성을 과대 판정한다는 사실이 확인됐다. 네 확장 검증을 계속하기
전에 확장별 하드코딩이 없는 범용 모바일 변환 엔진과 변환 상태 보고를
구현해야 한다.

또한 제공된 기준 APK는 `30.0.2.61`이지만 실기기 설치 버전은
`30.0.0.67`로 확인됐다. 네 확장의 최종 합격 판정은 브라우저 버전을
일치시키거나 설치 버전의 프로필을 별도로 검증한 뒤 수행한다.

## 스마트 컨텍스트 메뉴 모바일 변환 재검증

### Host-side 결과

2026-07-25 03:44 KST에 실제 개발 source를 범용 변환 pipeline에 입력했다.
확장 ID·이름을 조건으로 한 별도 변환 분기는 사용하지 않았다.

변환 전 decision은 다음과 같았다.

| adapter | disposition | 적용 |
|---|---|---:|
| `desktop-selection-event-bridge` | `automatic` | 아니요 |
| `action-popup-entry` | `requires-approval` | 아니요 |
| `storage-fallback` | `unavailable` | 아니요 |

`action-popup-entry`를 승인한 변환 결과 selection과 popup adapter가
`applied=true`가 됐다. `storage-fallback`은 동적·계산된 storage 접근의
의미를 안전하게 보존할 수 없어 원래 제한과 증거를 유지한
`unavailable`, `applied=false`다.

첫 실제 draft에서는 기존의 미선택 `unavailable` decision까지 새 adapter로
잘못 판정하는 pipeline 결함이 발견됐다. 회귀 테스트를 먼저 추가한 뒤,
변환 전에도 없고 적용 proof에도 없는 decision만 새 계획으로 차단하도록
수정했다. 기존 미선택 decision은 위험도, disposition, 적용 상태, 원본
evidence, 변경·제한을 객체 전체 비교로 보존한다. evidence 추가·삭제·변경,
중복 identity, 실제 새 decision은 모두 별도 테스트로 fail-closed를
확인했다.

popup 어댑터의 정확한 bytes와 wiring이 증명된 생성 content asset만 storage
위험 분석에서 제외했다. 이 생성 asset은 안전한 storage fallback을
오염시키지 않지만, 같은 원본 runtime closure의 별도 `eval` 등 위험 신호는
계속 `unavailable`로 차단한다.

두 SixDraft는 strict reader 검증을 통과했고 SHA-256이 모두
`56dc38969c43869732a82a78c640e86084c7cfa332a4c98be041e7d0e474f722`로
일치했다. source archive SHA-256은
`de0c607c94383a597905f260b635dc7d470dafe484fa6f5bd082154588fdcf81`,
변환 archive SHA-256은
`ca4761c486b54c88dda4439e92b67d908cd57448857e089d78648c2a34239bff`다.

최종 자동 실행 산출물은 다음과 같다.

| 항목 | 값 |
|---|---|
| 변환 extension ID | `dncifaeoabkkmddoeeccpjdkifeoemfd` |
| wrapper package | `dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd` |
| wrapper versionCode | `9` |
| CRX SHA-256 | `18e2cc6864b941099633cb683dd6ceca015eaebff6d85c8a0488da89cf19b58f` |
| wrapper SHA-256 | `20fad1755b74bc11d53396f5a15ec106ea55bb647946dc9afa2f513adc28c639` |
| SixBundle SHA-256 | `54117fc6e600d8c451dad2d9dfae45715ec1806a3db93b28f4dd9be925808e91` |

공용 `verify-build`로 외부 CRX·wrapper와 SixBundle bytes, CRX ID·hash,
wrapper package·versionCode·signer, embedded CRX, APK v3 서명, zipalign,
metadata·Activity·Provider 구조화 manifest 계약을 모두 검사했다. 외부
CRX와 wrapper는 각각 128MiB를 넘으면 읽기 전에 거부하고, 허용 범위에서는
bounded read와 streaming compare를 사용한다.

실제 source repository에는 실행 전부터 다음 사용자 상태가 있었다.

```text
 D icon_master_backup.png
?? icon_master123.png
```

relative-path/content tree SHA-256은 실행 전후 모두
`2d5c1a951a76b2a071207ce6cc0bf1659642b20d53ff3e04b0a8a2139bd6f8bc`였고,
위 Git 상태도 동일했다. source 파일을 수정·정리·커밋하지 않았다.

### Fold7 자동 smoke 결과

- 기기: `SM-F966N`
- serial: `adb-DEVICE_SERIAL._adb-tls-connect._tcp`
- 삼성 인터넷: `30.0.0.67`, `versionCode 1300067502`
- 적용 profile: `30.0.2.61`
- 래퍼 설치: 성공
- `six://ex` discovery: 성공
- ContentProvider CRX3 stream: 성공

증거는
`.local/evidence/20260725-040944-smart-context-converted.log.Wufwj5`와
`.local/evidence/.smart-context-run.KzpSGy/1784920198-dncifaeoabkkmddoeeccpjdkifeoemfd.log`에
있다.

device smoke는 provider raw CRX를 로그에 넣지 않는다. 최종 evidence에는
`providerMagic=Cr24`, `providerBytes=290509`,
`providerSha256=18e2cc6864b941099633cb683dd6ceca015eaebff6d85c8a0488da89cf19b58f`
요약만 있으며 `provider=<raw>` 필드는 없다. fake ADB의 NUL 뒤 sentinel이
evidence에 전혀 나타나지 않는 회귀 테스트로 이 경계를 확인했다.

binary process의 stderr는 byte 기준 1MiB로 제한한다. 큰 단일-line stderr가
작은 테스트 상한을 넘으면 수집한 원문과 sentinel을 폐기하고 placeholder만
남긴다. 이때 parent와 stdout/stderr pipe를 보유한 background descendant를
모두 강제 종료하고, process stream close와 daemon reader bounded join으로
무한 대기를 막는다. stdout 대상 symlink는 프로세스 시작 전에 거부하며 실제
open에도 `NOFOLLOW_LINKS`를 사용한다.

### Runtime 보류 결과

브라우저와 profile 버전이 다르므로 변환 기능의 runtime 최종 합격은
보류한다. 자동 smoke는 설치·discovery·provider 계약만 증명하며 다음
항목을 증명하지 않는다.

- Add-ons 활성화와 브라우저 재시작 후 유지
- 한 selection당 스마트 컨텍스트 UI 한 번 표시
- pointer·touch·selectionchange 중복 방지
- `확장 열기`를 통한 popup 탭 진입
- `storage.local` 설정 저장과 재시작 후 유지
- 해당 사용자 조작 구간의 service worker 예외·resource 누락·무한 반복 부재

`storage.sync`는 미검증이다. 일치 profile에서 위 항목을 사용자가 직접
확인하기 전에는 스마트 컨텍스트 메뉴 모바일 변환을 최종 합격으로 기록하지
않는다.
