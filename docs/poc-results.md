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
| 3 | Video Download to NAS | partial | webNavigation |
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
바꾼 업데이트이며 자동 게이트와 활성 유지를 통과했다. 사용자는
2026-07-25에 v14의 설정 popup과 텍스트 선택 메뉴도 정상이라고 재확인했다.

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
| 설정 popup·텍스트 선택 메뉴 재확인 | v14 수동 | 통과 | 사용자 확인(2026-07-25) |

`storage.sync`는 미검증이다. v12 수동 통과를 v14의 새 수동 UI 통과로
확대 해석하지 않으며, v14에서 재확인한 범위는 위 표에 명시한 두 항목이다.

## AI 레시피 추출기 실기기 결과

AI 레시피 추출기는 실제 개발 source를 수정하지 않은
`passthrough` 변환으로 Fold7에 설치했다. 최종 변환 ID는
`fdacompblnicnhgpkjnodoojgpglbpnh`, wrapper package는
`dev.sixbridge.ext.fdacompblnicnhgpkjnodoojgpglbpnh`,
`versionCode`는 `3`이다.

Host·device 자동 검증에서 exact profile, 설치, discovery, provider CRX
SHA-256, APK signer와 source 불변이 모두 통과했다. 실제 서버의 기능 결과는
자동 LAN receiver와 분리해 사용자가 직접 확인했다.

| 검증 항목 | 대상 | 결과 | evidence |
|---|---|---|---|
| options 서버 URL 저장·실행 반영 | Fold7 수동 | 통과 | 실제 서버 URL 설정 후 background action이 해당 서버로 이동 |
| 설정의 브라우저 완전 재시작 유지 | Fold7 수동 | 보류 | 이번 실제 서버 시나리오에서 별도 재확인하지 않음 |
| 일반 페이지 모바일 context menu | Fold7 수동 | 통과 | 링크·선택 텍스트·이미지·동영상 메뉴에서 사용자 확인(2026-07-25) |
| YouTube 현재 페이지 실행 | Fold7 수동 | 통과 | 삼성 더보기 메뉴의 확장 action과 `ExecuteAction` 로그 |
| 실제 서버 화면 이동 | Fold7 수동 | 통과 | `recipe.sruinz.com` 분석 진행 화면 |
| 실제 레시피 추출 | Fold7 수동 | 통과 | 떡볶이 영상 제목·채널·재료·조리 방법 결과 화면 |
| 일회성 LAN receiver | 자동+수동 | 미통과 | `.local/evidence/20260725-123139-ai-recipe-lan.json`, `reason=timeout` |

YouTube 플레이어는 사이트 자체가 길게 누르기를 빠르게 넘기기로 사용하므로
삼성 기본 컨텍스트 메뉴가 열리지 않는다. 그러나 삼성 인터넷이 확장 action을
더보기 메뉴나 툴바에 배치하는 기본 기능을 제공하며 실제 추출도 통과했다.
따라서 플로팅 버튼이나 AI 레시피 전용 변환 분기는 추가하지 않는다.

자동 LAN 수신기 타임아웃은 성공으로 바꾸지 않는다. 다만 실제 서버
종단간 검증에서 현재 YouTube URL 전달, 저장된 서버 URL 사용,
`tabs.create` 이동과 결과 생성이 모두 확인됐으므로 AI 레시피의 PoC 주요
기능은 통과로 판정한다. 브라우저 완전 재시작 뒤 설정 유지와 options 문맥의
삼성 내부 `SiText*` 비치명 로그는 후속 재검증 항목이다.

## Video Download to NAS v1.2.1 실기기 결과

실제 개발 source를 수정하지 않고 공용 pipeline으로 변환했다. host 분석은
`partial`·`passthrough`, unsupported API는 `webNavigation` 하나이며
adapter는 0개다. `storage_area_unknown`도 비차단 정적 issue로 유지한다.
이를 실제 동작 결과 때문에 `compatible`로 바꾸지 않는다.

Fold7의 정확한 검증 profile은 `SM-F966N`,
`com.sec.android.app.sbrowser` / `30.0.0.67` / `1300067502`다. 최종
변환 ID는 `iefgkdhgegchhlhcjfhcbbelkmpklmhd`, wrapper package는
`dev.sixbridge.ext.iefgkdhgegchhlhcjfhcbbelkmpklmhd`,
`versionCode`는 `2`다. install·discovery·provider CRX·서명·source 불변과
Add-ons 활성화·재시작 뒤 action 실행을 확인했다.

| 검증 항목 | 자동/수동 | 결과 | evidence |
|---|---|---|---|
| page detection | Fold7 수동 | 통과 — 새 문서 action에서 영상 목록 overlay 표시 | `.local/evidence/20260725-174930-video-nas-page.json` |
| action UI | Fold7 수동 | 통과 — 목록의 항목 선택 후 요청 전송 | 같은 page evidence와 사용자 확인 |
| settings persistence | LAN 인증 | 통과 — Config 저장·브라우저 완전 재시작 뒤 인증 POST 성공 | 같은 page evidence |
| HLS detection·request | LAN 자동+수동 | 통과 — `webRequest` 감지, `HLS` badge·항목 표시와 POST 성공 | `.local/evidence/20260725-180630-video-nas-hls.json` |
| NAS authentication·request | 실제 NAS 사용자 | 통과 — 영상 목록 선택, 서버 전달과 실제 다운로드 완료 | 사용자 확인(2026-07-25) |
| context submenu | Fold7 진단 | 통과 — link·선택 텍스트 URL·일반 HTML5 video에서 resolution·audio·subtitle 모두 표시 | 사용자 확인(2026-07-25) |

page evidence SHA-256은
`e0b5b4e712abf5d796e56ec2a876f88f3f104d5de89bf315b7a35cf92a907d94`,
HLS evidence SHA-256은
`bb0d316f0b09df686be9d5877478a5be101981edbb4c9fb84835df3b8cfb2813`다.
두 evidence에는 Config URL, token, NAS 주소와 요청 원문 URL이 없다. 실제
NAS 검증도 사용자가 비밀값을 직접 입력하고 수행했으며 해당 값은 수집하거나
기록하지 않았다.

재시작 뒤 복원된 탭에서는 content script가 주입되지 않아 action 메시지가
두 번 실패했다. 새로 로드한 문서에서는 정상 동작했다. 그 성공 흐름에서도
원본 listener가 `sendResponse`를 호출하지 않아 action callback의
`runtime.lastError`가 남았고, POST 뒤 `undefined`인
`response.message`를 읽는 비치명 `TypeError`가 관찰됐다. 두 오류는 영상
목록 표시, page·HLS POST와 실제 NAS 다운로드를 막지 않았지만 결과에서
제외하거나 자동 수정하지 않는다.

`webNavigation`은 이전 HLS 목록 정리용 optional 경로이며 HLS 감지 자체는
지원되는 `webRequest`로 통과했다. YouTube player의 길게 누르기는 사이트가
재생 제어로 사용하므로 삼성 더보기 메뉴나 툴바의 VDtN action을 사용한다.
일반 DOM 대상에서는 삼성 기본 컨텍스트 메뉴와 원본 중첩 하위 메뉴가
동작한다.

따라서 Video Download to NAS의 최종 판정은
**부분 호환 상태에서 주요 기능 통과**다.
