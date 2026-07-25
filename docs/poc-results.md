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

실제 개발 source를 범용 변환 pipeline에 입력했으며 확장 ID·이름을
조건으로 한 별도 변환 분기는 사용하지 않았다. 초기 v9 실행은
`30.0.2.61` profile과 Fold7의 `30.0.0.67`이 달라 보류했지만, 이후
`profiles/sbrowser-30.0.0.67.json`으로 정확히 일치시켜 v14를 검증했다.

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

v14의 두 SixDraft는 strict reader 검증을 통과했고 SHA-256
`c6701b208a074260b290f047341acd7c54c122e671f2d4d4a34f2e4d15c46913`으로
일치했다. source archive SHA-256은
`de0c607c94383a597905f260b635dc7d470dafe484fa6f5bd082154588fdcf81`,
변환 archive SHA-256은
`d5269f3fcf98c149b5d94dbc5a19234effbfc2afeb835e0e0de3200dfb0f5a7c`다.
transformed source에는 다음 두 entry가 있고 선행 밑줄 root entry는 0개다.

```text
sixbridge/action-popup-entry.v2.json
sixbridge/desktop-selection-event-bridge.v1.js
```

최종 자동 실행 산출물은 다음과 같다.

| 항목 | 값 |
|---|---|
| 변환 extension ID | `dncifaeoabkkmddoeeccpjdkifeoemfd` |
| wrapper package | `dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd` |
| wrapper versionCode | `14` |
| CRX SHA-256 | `db747086b8f9356f570945ed884730803fdc48c343ee96c905723a4a037fa489` |
| wrapper APK SHA-256 | `a132b210b942e693039bd5b6ef6749e4f3eba6bf9d299de90f328043592f90d1` |
| SixBundle SHA-256 | `e2ba072a4596992b5cd271ee74ce292bfb00f6475e55ccb39683d83f978a9f15` |
| wrapper signer SHA-256 | `436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a` |

실제 source의 기존 삭제 1건과 untracked 1건 및 tree SHA-256
`2d5c1a951a76b2a071207ce6cc0bf1659642b20d53ff3e04b0a8a2139bd6f8bc`는
실행 전후 같았으며 source를 수정·정리·커밋하지 않았다.

### Fold7 v14 자동 결과

main evidence는
`.local/evidence/20260725-095304-smart-context-converted.log.iZlDAW`,
loader evidence는
`.local/evidence/20260725-095304-v14-loader-after-tab-logcat.txt`다.
`com.sec.android.app.sbrowser` / `30.0.0.67` / `1300067502`가 profile과
정확히 일치했고 `profileMatch=true`, `deviceChecks=passed`,
`installSuccess=true`, `discoverySuccess=true`,
`providerReadSuccess=true`, `sourceUnchanged=true`가 기록됐다. provider
CRX SHA-256은 build CRX SHA-256과 일치했다.

실제 HTTPS 탭 loader 흐름에서 target의 `OnExtensionInstalled`는 1건,
`handleCrxInstalled`는 `stat:1`, target `_sixbridge` 경고와 crash는
각각 0건이다. 같은 로그의 `_metadata` 경고 1건은 비활성 원본 확장
`lneaoklobohnoncgckmbbjfiajgpbdde`에서 발생했고 target과 무관하다.

### 기능별 결과

v12에서는 사용자가 수동 UI 시나리오를 확인했다. v14는 예약 asset 경로만
바꾼 업데이트이며 자동 게이트와 활성 유지만 통과했다. v14 수동 UI를 새로
확인했다는 사용자 회신은 아직 없다.

| 검증 항목 | 대상 | 결과 | evidence |
|---|---|---|---|
| 삼성 Add-ons 상세 설정 메뉴 | v12 수동 | 통과 | 사용자 확인(2026-07-25), `.local/evidence/20260725-085006-smart-context-converted.log.RuZqfU` |
| 기존 popup 탭 열기 | v12 수동 | 통과 | 사용자 확인(2026-07-25), 같은 v12 main evidence |
| 설정 저장·브라우저 재시작 유지 | v12 수동 | 통과 | 사용자 확인(2026-07-25), 같은 v12 main evidence |
| 페이지 상시 `확장 열기` 버튼 부재 | v12 수동 | 통과 | 사용자 확인(2026-07-25), 같은 v12 main evidence |
| 텍스트 선택 스마트 컨텍스트 메뉴 | v12 수동 | 통과 | 사용자 확인(2026-07-25), 같은 v12 main evidence |
| `dev_ext` 상세 제거 버튼 미제공 | v12 수동 | 통과 | 사용자 확인(2026-07-25), 같은 v12 main evidence |
| 최종 `sixbridge/` 두 entry와 선행 밑줄 root 0건 | v14 자동 | 통과 | `.local/evidence/20260725-095304-smart-context-converted.log.iZlDAW` |
| target loader 설치·활성 유지·예약 경고 0건 | v14 자동 | 통과 | `.local/evidence/20260725-095304-v14-loader-after-tab-logcat.txt` |
| 위 수동 UI 전체 재확인 | v14 수동 | 보류 | 사용자 회신 대기 |

`storage.sync`는 미검증이다. v12 수동 통과를 v14의 새 수동 UI 통과로
확대 해석하지 않는다.
