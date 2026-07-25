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
