# Fold7 기술 게이트 및 확장 검증

## 사전 조건

- USB 또는 무선 디버깅을 활성화한 Galaxy Fold7
- 삼성 인터넷 30.0.2.61
- 명시적인 serial로 확인되는 ADB 연결
- 업데이트 빌드마다 동일하게 유지되는 `.local/keystore/`

## 게이트 A — 최소 자체 서명 래퍼

1. 기준 프로필로 `fixtures/minimal-mv3`를 빌드한다.
2. `scripts/fold7-gate.sh <adb-serial>`을 실행한다. 또는 `device install`로
   `.six` 번들을 설치한 다음 `device smoke`를 실행한다.
3. 생성된 `.local/evidence/*.log`를 보관한다.
4. 삼성 인터넷에서 `internet://extension`을 연다.
5. `SIX Bridge Technical Gate`가 표시되고 활성화되는지 확인한다.
6. 일반 HTTPS 페이지에서 `SIX Bridge OK` 진단 배지가 표시되는지 확인한다.
   이 배지는 content script, MV3 background service worker, runtime 메시징,
   `storage.local`이 모두 성공해야만 나타난다.
7. 페이지를 새로고침해 배지가 한 번만 다시 나타나는지 확인한다.
8. 삼성 인터넷을 완전히 종료·재실행한 뒤 활성 상태와 배지 실행이 유지되는지
   확인한다.

`fold7-gate.sh`는 최초 런타임 기술 게이트에서 확정한 extension ID
`odgobepbaifhaibnnkpcclekeaogainb`와 해당 고정 wrapper 파일명을 계속
사용하는 레거시 검증 스크립트다. 변환된 확장의 후속 검증은 `build`가 만든
SixBundle basename을 `device install`과 `device smoke`에 전달한다. 두
경로의 파일명을 서로 바꾸지 않는다.

패키지가 설치되고 `six://ex`도 해석되지만 Add-ons에 나타나지 않으면 원본
Chrome 웹 스토어 CRX와 개발 키로 다시 패킹한 CRX를 각각 시험하고 결과를
기록한다. 삼성 인터넷 APK는 수정하지 않으며 Android 관리자 앱 단계도
시작하지 않는다.

## 게이트 B — 네 확장 프로그램

`validation/extensions.json`의 순서대로 실행한다. 각 시나리오마다 다음을
기록한다.

- 번들 SHA-256과 래퍼 `versionCode`
- 휴대전화 serial, Android 빌드, 삼성 인터넷 버전
- 정확한 사용자 동작과 성공/실패 결과
- 관련 스크린샷과 `.local/evidence` 로그 경로
- 실행한 API와 발생한 런타임 예외

앞의 세 확장은 목록의 주요 시나리오가 모두 동작해야 통과한다. MediaNab은
지원되는 storage, tabs, webRequest, offscreen 경로가 동작하고,
`downloads`와 `nativeMessaging`이 다른 기능을 중단시키지 않은 채
비지원으로 정확히 진단되면 PoC를 통과한다.

## 게이트 C — 변환된 스마트 컨텍스트 메뉴

실제 개발 source를 복사하거나 수정하지 않고 다음 명령으로 host 검증과
Fold7 install/smoke를 연속 실행한다.

```bash
./scripts/fold7-converted-extension.sh \
  --serial 'adb-DEVICE_SERIAL._adb-tls-connect._tcp' \
  --profile profiles/sbrowser-30.0.0.67.json
```

기기 없이 host 결과만 만들 때는 `--host-only`를 사용한다. `--host-only`가
아니면 비어 있지 않은 `--serial`이 필수이며 둘을 함께 지정할 수 없다. 다른
source나 profile은 각각 `--source`, `--profile`로 지정한다. 스크립트는
실행 전에 `.local`, `.local/evidence`, `dist`가 symlink가 아닌 예상 실제
parent의 디렉터리인지 확인하고 다음을 검사한다.

- 변환 전 adapter decision
- 같은 입력으로 만든 SixDraft 두 개의 strict 검증과 SHA-256 일치
- `smart-context-converted.crx`,
  `smart-context-converted-wrapper.apk`,
  `smart-context-converted.six` basename 계약
- `verify-build`를 통한 CRX3 ID·hash, SixBundle 외부 산출물 결합, wrapper
  embedded CRX·package·versionCode·signer 검증
- 외부 CRX·wrapper를 각각 128MiB로 사전 제한하고 bounded read·streaming
  compare 사용
- APK v3 서명, zipalign, metadata·Activity·Provider 구조화 manifest 계약
- 변환 후 정확한 세 adapter의 version과 selection·popup 적용 상태,
  미적용 storage decision 전체 객체 보존
- 설치 package, `versionName`, 숫자 `versionCode`와 profile의 정확한 일치
- 실제 source의 `git status --short`와 relative-path/content tree hash가
  실행 전후 같은지 여부

임시 보고서와 draft는 예측 불가능한 이름으로 만들고 검사가 끝난 뒤
`evidence` 디렉터리 안에서 고정 이름으로 원자적으로 게시한다. device smoke도
공유 evidence root가 아니라 실행마다 새로 만든 run 디렉터리를 사용한다.

device smoke는 ContentProvider raw CRX를 evidence에 기록하지 않는다. byte
기반으로 최대 128MiB까지만 수집한 뒤 `providerMagic`, `providerBytes`,
`providerSha256` 비가역 요약만 텍스트 로그에 기록하고 임시 raw 파일은
삭제한다. binary process의 stderr도 1MiB로 제한한다. 제한 초과 시 수집한
stderr 원문을 폐기하고 고정 placeholder만 남기며, process tree 강제 종료,
세 process stream close, daemon reader의 시간 제한 join을 수행한다. 임시
stdout 파일은 symlink가 아닌 기존 일반 파일만 허용하고 실제 open에도
`NOFOLLOW_LINKS`를 적용한다.

2026-07-25 04:09 KST의 초기 자동 실행
`.local/evidence/20260725-040944-smart-context-converted.log.Wufwj5`는
`30.0.2.61` profile로 `30.0.0.67` 기기를 검사해 profile 불일치로
보류했던 과거 증거다. 이후 설치본과 정확히 일치하는 profile을 생성해
다시 검증했다.

### v14 host와 device 자동 결과

최종 v14 자동 검증은 위 명령에
`--profile profiles/sbrowser-30.0.0.67.json`을 명시해 실행했다.

- main evidence:
  `.local/evidence/20260725-095304-smart-context-converted.log.iZlDAW`
- loader evidence:
  `.local/evidence/20260725-095304-v14-loader-after-tab-logcat.txt`
- 기기/브라우저 profile:
  `SM-F966N`, `com.sec.android.app.sbrowser` /
  `30.0.0.67` / `1300067502`
- extension ID:
  `dncifaeoabkkmddoeeccpjdkifeoemfd`
- wrapper package:
  `dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd`
- wrapper `versionCode`: `14`
- CRX SHA-256:
  `db747086b8f9356f570945ed884730803fdc48c343ee96c905723a4a037fa489`
- wrapper APK SHA-256:
  `a132b210b942e693039bd5b6ef6749e4f3eba6bf9d299de90f328043592f90d1`
- SixBundle SHA-256:
  `e2ba072a4596992b5cd271ee74ce292bfb00f6475e55ccb39683d83f978a9f15`
- wrapper signer SHA-256:
  `436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a`

main evidence에서 `profileMatch=true`, `deviceChecks=passed`,
`installSuccess=true`, `discoverySuccess=true`,
`providerReadSuccess=true`, `sourceUnchanged=true`를 확인했다. provider CRX
SHA-256은 build CRX SHA-256과 일치한다. transformed source에는 다음
두 entry가 각각 존재하며 선행 밑줄 root entry는 0개다.

```text
sixbridge/action-popup-entry.v2.json
sixbridge/desktop-selection-event-bridge.v1.js
```

실제 HTTPS 탭을 연 뒤 수집한 loader evidence에서 target
`dncifaeoabkkmddoeeccpjdkifeoemfd`의 `OnExtensionInstalled`는 1건이고,
바로 이어진 `handleCrxInstalled`는 `stat:1`이다. target의 `_sixbridge`
예약 이름 경고와 crash는 각각 0건이며 status는 `ON`으로 유지됐다.

같은 loader evidence에는 `_metadata` 예약 이름 경고가 1건 있지만 이는
비활성 원본 확장 `lneaoklobohnoncgckmbbjfiajgpbdde`에서 발생했다. 해당
확장은 `stat:0`이며 v14 변환 target의 경고로 집계하지 않는다.

### 수동 UI 증거 경계

사용자는 v12에서 삼성 Add-ons 상세 설정 메뉴, 기존 popup 탭, 설정
저장·브라우저 재시작 유지, 페이지 상시 버튼 부재, 텍스트 선택 메뉴를
모두 정상으로 확인했다. v14는 생성 asset의 예약 경로만 바꾼 업데이트이며
자동 게이트와 활성 유지를 통과했다. 사용자는 2026-07-25에 v14의 설정
popup과 텍스트 선택 메뉴도 정상이라고 재확인했다.

`storage.sync`는 동작을 확인하지 않았다. `storage-fallback`은 source의
동적·계산된 접근을 안전하게 변환할 수 없어 `unavailable`,
`applied=false`로 보존됐다.

## AI 레시피 추출기

### Host·device 자동 결과

실제 개발 source를 수정하지 않고 공용 runner에 입력했다. 분석 결과는
`compatible`, 변환 상태는 `passthrough`이며 별도 adapter는 적용하지 않았다.
기준 source tree SHA-256
`bf61114c94a9377577458f69bfb62cef5cfd66db40652b3d4f11f59d49930d31`은
host와 device 검증 전후에 유지됐다.

- main evidence:
  `.local/evidence/20260725-122327-ai-recipe-converted.log.PIK0ja`
- loader evidence:
  `.local/evidence/20260725-122327-ai-recipe-loader-after-tab-logcat.txt`
- 기기/브라우저 profile:
  `SM-F966N`, `com.sec.android.app.sbrowser` /
  `30.0.0.67` / `1300067502`
- extension ID:
  `fdacompblnicnhgpkjnodoojgpglbpnh`
- wrapper package:
  `dev.sixbridge.ext.fdacompblnicnhgpkjnodoojgpglbpnh`
- wrapper `versionCode`: `3`
- CRX SHA-256:
  `15cf9a99056b04224cf99bc4e5fc4ea477ead6854ec44a2bf939c63abcdcae0b`
- wrapper APK SHA-256:
  `fbb31603ba8adeffa2f0b2aa953dce6a98aa7190cd5682c6c4a23d2324f13007`
- SixBundle SHA-256:
  `b261513ef6767c75cdecf7d1c7a074aefe00b840292f648b649b2538f1f08a71`
- wrapper signer SHA-256:
  `436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a`

main evidence에서 `profileMatch=true`, `deviceChecks=passed`,
`installSuccess=true`, `discoverySuccess=true`,
`providerReadSuccess=true`, `sourceUnchanged=true`를 확인했다. provider가
제공한 CRX SHA-256도 build CRX와 일치했다.

### 모바일 진입점 결과

사용자는 일반 웹 페이지의 링크·선택 텍스트·이미지·동영상을 길게 눌렀을 때
삼성 기본 컨텍스트 메뉴에 `AI 레시피 추출하기`가 표시되는 것을 확인했다.
YouTube 모바일 플레이어는 길게 누르기를 빠르게 넘기기로 사용해 브라우저
컨텍스트 메뉴가 열리지 않는다.

삼성 인터넷은 브라우저 설정의 `더보기 메뉴 사용자 설정`과
`툴바 사용자 설정`에서 확장 action을 배치할 수 있다. 사용자는 AI 레시피
action을 더보기 메뉴에 배치했고, YouTube 영상 페이지에서 이를 눌러 현재
페이지 URL을 전달했다. 따라서 삼성 기본 UI가 다음 두 진입점을 담당한다.

- 일반 DOM 대상: 삼성 기본 컨텍스트 메뉴
- 현재 페이지 실행: 더보기 메뉴 또는 툴바의 확장 action

별도 플로팅 버튼이나 YouTube 전용 content script는 필요하지 않다.

### LAN 전달 결과

일회성 LAN receiver 증거
`.local/evidence/20260725-123139-ai-recipe-lan.json`은
`requestMatched=false`, `reason=timeout`이다. 당시 YouTube 플레이어에서
컨텍스트 메뉴를 열 수 없어 요청을 발생시키지 못했으며, 이 결과를 성공으로
해석하지 않는다. 이후 실제 서버 검증 결과와도 합쳐 쓰지 않는다.

### 실제 서버 사용자 결과

2026-07-25 사용자가 options에서 실제 서버
`https://recipe.sruinz.com`을 설정하고, 실제 떡볶이 YouTube 영상 페이지에서
더보기 메뉴의 `AI 레시피 추출기` action을 실행했다.

사용자 제공 화면과 실행 직후 로그에서 다음을 확인했다.

1. 삼성 더보기 메뉴에 AI 레시피 action이 표시됐다.
2. 로그에
   `ExecuteAction id : fdacompblnicnhgpkjnodoojgpglbpnh`가 기록됐다.
3. 실제 서버의 분석 진행 화면으로 이동했다.
4. 영상 제목·채널과 떡볶이 재료 및 조리 방법이 포함된 결과가 표시됐다.

따라서 현재 페이지 URL 획득, `storage.sync`에 저장한 서버 URL 읽기,
`tabs.create` 화면 이동과 실제 서버 요청으로 이어지는 주요 흐름은
Fold7에서 통과했다.

### 알려진 제한과 로그 관찰

- 설정값의 저장과 실제 실행 반영은 확인했지만, 삼성 인터넷을 완전히
  종료·재실행한 뒤의 설정 유지 여부는 이번 실제 서버 시나리오에서 별도로
  재확인하지 않았다.
- 실행 직후 제한 로그에는 대상 확장의 `contextMenus`, `storage`,
  `tabs.create` 관련 예외와 AndroidRuntime crash가 없었다.
- 같은 로그의 extension options 문맥에서 삼성 내부 텍스트 분석 전역 함수
  `SiTextIsRobotsIndexBlocked`, `SiTextIsPayWallPage`,
  `SiTextGetSentences` 관련 비치명 `ReferenceError`가 관찰됐다. 실제 옵션
  저장과 레시피 추출은 완료됐으므로 현재 합격 흐름을 중단시키지는 않았지만,
  삼성 인터넷 30.x 버전 비교 시 재확인할 관찰 항목으로 남긴다.

## Video Download to NAS v1.2.1

실제 개발 source를 수정하지 않고 `fold7-video-nas.sh`에 입력했다. 기준
source와 검증 종료 후 신원은 다음과 같이 일치했다.

| 항목 | 값 |
|---|---|
| source commit | `435d7c57e6cb22817471e319d3cf2e855f9b67af` |
| Git tree | `b2eb34993fcb72a20b8f7e768bacec6e13685b82` |
| source subtree | `63d6c42a6b449963269deb3c3edd7b5576733d2e` |
| source tree SHA-256 | `e19ee46d257983a972adf5100f30c41cbc57bd73a21dccbc28c7e9dda3f590e7` |
| manifest SHA-256 | `3887cab794343209c1cb7459f9b3881f5c815d96c4b8392706892f5ac1ce9dd3` |

source 저장소에 검증 전부터 있던 untracked 디렉터리 한 건도 실행 전후
동일했다. 실제 확장 source와 삼성 인터넷 APK는 수정하지 않았다.

### Host·device 자동 결과

최종 정적 판정은 `partial`, 변환 상태는 `passthrough`다. 사용 API는
`action`, `contextMenus`, `runtime`, `storage`, `tabs`, `webNavigation`,
`webRequest`이고 unsupported API는 `webNavigation` 하나다.
`storage_area_unknown`도 비차단 issue로 유지했으며 적용하거나 승인한
adapter는 없다. `webNavigation`은 optional chaining으로 이전 HLS 목록을
정리하는 경로이고 HLS 감지 자체는 `webRequest`를 사용한다.

최종 설치 산출물과 Fold7 profile은 다음과 같다.

| 항목 | 값 |
|---|---|
| 기기 | `SM-F966N` |
| 브라우저 package | `com.sec.android.app.sbrowser` |
| 브라우저 versionName / versionCode | `30.0.0.67` / `1300067502` |
| 변환 extension ID | `iefgkdhgegchhlhcjfhcbbelkmpklmhd` |
| wrapper package | `dev.sixbridge.ext.iefgkdhgegchhlhcjfhcbbelkmpklmhd` |
| wrapper versionCode / versionName | `2` / `1.0.2` |
| CRX SHA-256 | `c9f5841697650f4bab8b6dbad4f6d586f3447fdfac89af44dfd87e0f9af4947c` |
| provider CRX SHA-256 | `c9f5841697650f4bab8b6dbad4f6d586f3447fdfac89af44dfd87e0f9af4947c` |
| wrapper APK SHA-256 | `19de62bd8b9dba6574c2fb75ef0612ce93af10995d29660a03273569f6ba8d62` |
| SixBundle SHA-256 | `e60756e9b704c39d1a116d397a8db0e29113f1da7b69dbd19c58635473a6f020` |
| wrapper signer SHA-256 | `436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a` |

`profileMatch=true`, install·discovery·provider read와 독립
`verify-build`가 모두 통과했다. 관련 evidence는 다음과 같다.

| evidence | 경로 | SHA-256 |
|---|---|---|
| host main | `.local/evidence/20260725-163026-video-nas-converted.log.nhFtZY` | `7499ac34fa2de42855377f202e0b45cee088060e049aad1ba01ef6eab5a881da` |
| device main | `.local/evidence/20260725-163435-video-nas-converted.log.aqzqCU` | `59502c05beadc27d6b7def7c9c91af3467a767eecfc6b8108491f812054b1465` |
| device smoke | `.local/evidence/.video-nas-run.fUnEQn/1784964887-iefgkdhgegchhlhcjfhcbbelkmpklmhd.log` | `1d367a0f73dfba21e144e63c054e53e8ebe5d0ae094e296fdd214077fc6111f1` |
| loader·action | `.local/evidence/20260725-172600-video-nas-loader-action-target-only.txt` | `2f77b6ade02775bb3a6f9155b24257024600dfe0b6fff9cc35a60087b205d128` |

삼성 Add-ons 활성화와 브라우저 재시작 뒤 target service worker와
`ExecuteAction`을 확인했다. service worker fatal, reserved root warning,
AndroidRuntime crash는 없었다.

### 일반 page LAN 결과

사용자가 일회성 Config URL을 저장하고 삼성 인터넷을 완전히 종료·재실행한
뒤 새 문서에서 VDtN action을 실행해 `PAGE` 항목을 선택했다. receiver는
인증과 페이지 URL hash가 일치하고 `resolution=best`인 POST를 받았다.

- evidence:
  `.local/evidence/20260725-174930-video-nas-page.json`
- evidence SHA-256:
  `e0b5b4e712abf5d796e56ec2a876f88f3f104d5de89bf315b7a35cf92a907d94`
- 결과:
  `mode=page`, `requestMatched=true`, `authMatched=true`

Config URL, 인증 token, NAS 주소와 요청 원문 URL은 evidence와 문서에
기록하지 않았다. 저장 후 브라우저를 완전히 재시작한 세션에서 인증 요청이
성공했으므로 options 설정의 저장·재시작 유지 경로도 통과했다.

재시작 직후 복원된 탭에서는 content script가 주입되지 않아 action의
`tabs.sendMessage`가 두 번 실패했다. 새로 탐색한 문서에서는 action,
영상 목록 overlay와 항목 선택이 정상 동작했다. 다만 `findVideos`
listener가 `sendResponse`를 호출하지 않아 성공한 새 문서 action callback에
`runtime.lastError`가 남았다. `PAGE` POST 뒤 background listener도 응답을
반환하지 않아 원본 `content.js`가 `undefined`인 `response.message`를 읽는
비치명 `TypeError`가 한 번 발생했다. POST, overlay와 서버 전달은 성공했으며
서버 요청 오류나 확장 fatal은 없었다.

### HLS LAN 결과

사용자는 HLS probe 페이지의 감지 요청 완료를 확인한 뒤 VDtN action에서
`HLS` badge와 `master.m3u8` 항목을 확인하고 해당 항목을 선택했다.
video 태그가 없는 probe 문서에서 target `background.js`의
`webRequest` 감지 로그가 기록됐고 receiver가 인증과 HLS URL hash가
일치하는 POST를 받았다.

- evidence:
  `.local/evidence/20260725-180630-video-nas-hls.json`
- evidence SHA-256:
  `bb0d316f0b09df686be9d5877478a5be101981edbb4c9fb84835df3b8cfb2813`
- 결과:
  `mode=hls`, `requestMatched=true`, `authMatched=true`,
  `resolution=best`

HLS 경로에서도 응답 callback의 `undefined response.message` TypeError와
응답 전 message port 종료가 각각 한 번 관찰됐지만, HLS 감지·overlay·POST를
막지는 않았다. `webRequest` permission 오류, service worker fatal과
AndroidRuntime crash는 없었다.

### 실제 NAS 사용자 결과

사용자는 실제 NAS token과 Config를 Fold7 options에 직접 입력했으며
assistant와 자동 검증 도구에는 전달하지 않았다. 새로 로드한 YouTube
페이지에서 VDtN action을 실행하자 현재 페이지의 영상 목록이 표시됐고,
항목 하나를 선택한 뒤 NAS 서버 전달과 실제 다운로드가 완료됐다.

이는 데스크톱과 같은 action → 영상 목록 → 항목 선택 → 서버 요청 흐름이다.
실제 Config URL, token, NAS 주소, 영상 URL과 응답 payload는 기록하지
않았다.

### 컨텍스트 메뉴와 알려진 제한

사용자 관찰로 삼성 기본 컨텍스트 메뉴의 원본 중첩 메뉴도 확인했다.

| 컨텍스트 | `Download with VDtN` 부모 | resolution·audio·subtitle 하위 메뉴 |
|---|---|---|
| 일반 HTTPS 페이지의 link 길게 누르기 | 표시 | 모두 표시 |
| 선택한 텍스트 영상 URL | 표시 | 모두 표시 |
| 일반 HTML5 video 길게 누르기 | 표시 | 모두 표시 |

YouTube player는 사이트가 길게 누르기를 재생 제어로 가로채므로 이 경로
대신 삼성 더보기 메뉴나 툴바에 배치한 extension action을 사용한다.

`webNavigation` 비지원과 `storage_area_unknown`은 실제 주요 기능 통과로
지우거나 `compatible`로 승격하지 않는다. 복원 탭의 content script
미주입과 원본 callback 응답 오류도 알려진 비치명 경계다. 따라서 최종
판정은 **부분 호환 상태에서 주요 기능 통과**다.
