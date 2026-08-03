# 삼성 인터넷 30.0.0.67 API capability 검증 결과

## 결론

Fold7에서 6개 probe 그룹의 33개 case를 검증했다. 32개는 실행
evidence로, 1개는 manifest load 격리 evidence로 판정했다. 결과는
`VERIFIED 18`, `EXPOSED 10`, `RESTRICTED 1`, `FAILED 4`다.

이 결과로 Tampermonkey와 Bitwarden 대형 CRX 분석은 각각 약 2초와
5초에 종료됐다. 기존의 무한 `분석 중`은 재현되지 않았다. 다만 두
확장 모두 계산된 API alias를 포함하며, 필수 member 일부가 아직
미검증·실패 상태이므로 실사용 가능을 확정하지 않는다.

baseline `profiles/sbrowser-30.0.0.67.json`은 변경하지 않았다. 후보는
`profiles/candidates/sbrowser-30.0.0.67-probed.json`에 별도로 두었다.

## Provenance

- 검증일: 2026-08-03
- 장치: Samsung Galaxy Fold7 `SM-F966N`
- 브라우저 package: `com.sec.android.app.sbrowser`
- 브라우저 버전: `30.0.0.67` (`1300067502`)
- APK set SHA-256:
  `9e2270ddbd0f41e4ed85944362c66f55e96090c86e19d098eb483a4a0850d32f`
- 브라우저 signer SHA-256:
  `34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42`
- probe catalog SHA-256:
  `88acdaad29e031d2ea7db63fdd137f07d196de98bb230af5b64c40859e7af746`
- wrapper signer SHA-256:
  `c37f894f6fe3d9371c16c84449abc39d28fb55185b2a8c5854432b218e1378a1`
- 논리 profile digest:
  `1d4b4408997d9819816de55efeb02d24d4181629794c210436a521c484d0d096`
- 후보 profile 파일 SHA-256:
  `1120d2d77e754c35b70cf01ddde73c4f81bfe061527efb96480c89d567b957ed`

Evidence 원본은 Git에 포함하지 않고 다음 로컬 경로에 보존한다.

- `.local/evidence/api-probe/20260803-112029/`
- `.local/evidence/api-probe/20260803-identity-no-webauth/`

## API member 결과

| member | context | 상태 |
|---|---|---|
| `action.getTitle` | extension page | `VERIFIED` |
| `alarms.get` | service worker | `VERIFIED` |
| `commands.getAll` | extension page | `VERIFIED` |
| `contentSettings.cookies` | extension page | `EXPOSED` |
| `contextMenus.create` | service worker | `VERIFIED` |
| `cookies.get` | service worker | `EXPOSED` |
| `debugger.attach` | service worker | `FAILED` |
| `declarativeContent.onPageChanged` | service worker | `EXPOSED` |
| `declarativeNetRequest.getAvailableStaticRuleCount` | service worker | `VERIFIED` |
| `extension.isAllowedIncognitoAccess` | service worker | `VERIFIED` |
| `history.search` | service worker | `EXPOSED` |
| `i18n.getUILanguage` | extension page | `VERIFIED` |
| `identity.getRedirectURL` | extension page | `VERIFIED` |
| `idle.queryState` | service worker | `VERIFIED` |
| `management.getSelf` | service worker | `VERIFIED` |
| `notifications.getPermissionLevel` | service worker | `VERIFIED` |
| `offscreen.hasDocument` | service worker | `VERIFIED` |
| `permissions.contains` | service worker | `VERIFIED` |
| `privacy.network` | extension page | `EXPOSED` |
| `proxy.settings` | service worker | `EXPOSED` |
| `runtime.getPlatformInfo` | service worker | `VERIFIED` |
| `scripting.getRegisteredContentScripts` | service worker | `VERIFIED` |
| `search.query` | extension page | `FAILED` |
| `sidePanel.getOptions` | extension page | `VERIFIED` |
| `storage.local` | service worker | `EXPOSED` |
| `tabGroups.query` | extension page | `EXPOSED` |
| `tabs.create` | extension page | `VERIFIED` |
| `tts.getVoices` | extension page | `RESTRICTED` |
| `userScripts.getScripts` | service worker | `FAILED` |
| `webAuthenticationProxy.onRemoteSessionStateChange` | service worker | `FAILED` |
| `webNavigation.getAllFrames` | service worker | `EXPOSED` |
| `webRequest.onBeforeRequest` | service worker | `EXPOSED` |
| `windows.getCurrent` | extension page | `VERIFIED` |

`EXPOSED`는 member가 노출된 것만 확인한 상태이므로 의미 보존 지원으로
승격하지 않는다. `RESTRICTED`도 정책·권한 조건이 확정되기 전에는
검증된 API로 취급하지 않는다.

## Manifest load 격리

`identity-privacy` control은 `webAuthenticationProxy` 권한을 포함했을 때
삼성 Add-ons에서 `동작 안 함`으로 표시됐고 상세 화면을 열 수
없었다. 해당 권한과 case만 제외한 variant는 정상 활성화되었고
나머지 6 case가 모두 성공했다. 따라서 해당 member를 manifest load
차단 `FAILED`로 합성했다.

## 기존 변환 후보 회귀

| 확장 | 후보 profile 분석 | 변환 | unavailable | 래퍼 |
|---|---|---|---|---|
| 스마트 컨텍스트 메뉴 | `PARTIAL` | `converted` | 없음 | `dncif…` v20 |
| AI 레시피 추출기 | `PARTIAL` | `passthrough` | 없음 | `fdac…` v4 |
| Video Download to NAS | `PARTIAL` | `passthrough` | 없음 | `iefg…` v3 |

스마트 컨텍스트 메뉴의 함수 내부 API 호출 결과와 `window` 호출
결과를 계산된 namespace alias로 잘못 판정하던 정적 분석 오탐을
수정했다. 재분석 결과 차단 issue와 unavailable API는 없었으며,
`action-popup-entry`와 `desktop-selection-event-bridge`가 각각
승인·자동 어댑터로 적용됐다. 나머지 두 확장은 소스 의미를 바꾸지
않는 `passthrough`다.

세 후보의 CRX·래퍼 APK·SixBundle 결합 계약은 모두 독립 검증을
통과했다. Fold7 설치와 smoke도 모두 다음 결과를 냈다.

- `installed=true`
- `discovery=true`
- `provider=true`

Smoke evidence는 Git에 포함하지 않고 다음 로컬 경로에 보존한다.

- `.local/evidence/20260803-candidate-profile/`

사용자가 Fold7에서 다음 기능 시나리오와 삼성 인터넷 완전 종료·재실행
후 활성 유지를 확인했다.

- 스마트 컨텍스트 메뉴: 텍스트 선택, 검색 메뉴 실행, 설정 저장
- AI 레시피 추출기: 실제 YouTube 영상에서 서버 레시피 추출
- Video Download to NAS: 영상 목록 표시, 항목 선택, NAS 전송
- 삼성 인터넷 완전 종료·재실행 후 세 확장의 활성 상태와 기능 유지

따라서 기존 변환 후보 3개의 기능·재시작 회귀 게이트는 통과했다.
MediaNab은 `validation/extensions.json`의 `boundary-diagnostic`
대상이며 변환 후보 회귀 수에 포함하지 않는다.

## Tampermonkey

- CRX ID: `dhdgffkkebhmkfjojejmpbldmpobfkfo`
- 버전: `5.5.0`
- 분석 시간: 약 2초
- 상태: `UNKNOWN`
- unavailable: `downloads`, `nativeMessaging`
- 차단 근거: 계산된 namespace/browser alias
- 중요 evidence: `userScripts.getScripts`는 Fold7에서 `FAILED`

추가 소스 검토에서 Tampermonkey 5.5.0은 기본 경로에서도 사용자 지정
`csp`와 `messaging: true`를 `configureWorld`에 전달하며,
`userscripts-dynamic` 경로는 런타임 `code` 문자열을 등록하는 것을 확인했다.
삼성 인터넷에서 검증된 `scripting` 경로는 이 동작과 `USER_SCRIPT` 실행
세계를 같은 의미로 보존하지 못한다.

따라서 `userScripts → scripting` 범용 어댑터는 구현하지 않으며
Tampermonkey를 현재 자동 변환 대상으로 승인하지 않는다. 정적 alias 분석
개선으로 현재의 `UNKNOWN` 원인을 제거하더라도 핵심 `userScripts` 의존성은
별도의 차단 근거다. Tampermonkey 역할의 삼성 인터넷 전용 UserScript 기능은
현재 범용 변환 엔진과 분리한 후속 프로젝트로 둔다.

## Bitwarden

- CRX ID: `nngceckbapebfimnlniiiahkandclblb`
- 버전: `2026.7.0`
- 분석 시간: 약 5초
- 상태: `UNKNOWN`
- 검증 member: 10
- unavailable: `contentScripts`, `nativeMessaging`, `sidebarAction`
- 차단 근거: 계산된 Chrome API alias와 재귀 `Proxy`

### 정적 분석 경계

`contentScripts.register`는 Manifest V2·Firefox fallback, `nativeMessaging`은
선택 권한, `sidebarAction`은 Firefox·Opera 분기에 있었다. 따라서 이 세 항목은
Chrome Manifest V3의 popup·정적 content script 실행을 곧바로 차단한다고
볼 수 없다.

다만 bundle에는 `globalThis.chrome`를 재귀 `Proxy`로 감싸는 Promise API
호환 계층도 있다. 현재 정적 분석기는 Manifest V3에서 도달하지 않는 fallback과
실행되는 계산 member 접근을 안전하게 분리할 수 없으므로 `api_usage_dynamic`을
차단 근거로 유지한다. 제품 CLI·데스크탑·Android 관리자의 `UNKNOWN` 차단은
완화하지 않았다.

### 로컬 진단용 원본 CRX 래퍼

사용자가 선택한 로컬 실험에서만 저수준 `WrapperApkBuilder`로 원본 CRX를
그대로 래핑했다. 자동 변환 결과나 배포 가능한 `.six`로 승인한 것이 아니다.

- 원본 CRX SHA-256:
  `3f05cb920192f588ef051507830884b6a8979179969e0bf7c40e01a2a8fd7fbe`
- 래퍼 내부 `assets/extension.crx` SHA-256: 원본과 동일
- 래퍼 package:
  `dev.sixbridge.ext.nngceckbapebfimnlniiiahkandclblb`
- 래퍼 `versionCode`: `1`
- 래퍼 SHA-256:
  `2712988d4d0276978cb31221f5751a003f20cdfcbf9fbee189fc4345c866f7e6`
- 래퍼 signer SHA-256:
  `436e837f7fae287a23dcead0d944bc9fb7dfdbee82fabf962fdb8846accd927a`
- APK 검증: v3 서명, 4-byte alignment, `extension-id`, discovery Activity,
  exported ContentProvider 계약 통과
- Fold7 smoke: 설치 성공, `six://ex` resolution 성공, Provider `Cr24`

삼성 인터넷 `30.0.0.67`에서 사용자가 Add-ons 활성화를 완료한 뒤 다음을
확인했다.

- DevTools에 원본 ID의 service worker가 실행됨
- 로컬 HTTP fixture의 격리 execution context에 `Bitwarden 비밀번호 관리자`
  content script와 원본 runtime ID가 존재함
- 삼성 인터넷 더보기 메뉴에 `Bitwarden 비밀번호 관리자` action이 표시됨
- action 실행 로그가 원본 ID를 가리킴
- popup에서 WebAssembly 지원·SDK 로드·state version 초기화가 성공함
- 초기 안내 화면과 `bitwarden.com` 로그인 화면이 모바일에서 정상 표시됨
- popup·background 로그에 `TypeError`, `ReferenceError`, uncaught 오류가 없음

화면 evidence와 실험 APK는 Git에 포함하지 않고 `.local/evidence/`에
보존한다. 로컬 fixture도 네트워크 제출을 막고 다음 더미 값만 사용했다.

- 사용자 이름: `six-test@example.invalid`
- 비밀번호: `not-a-real-password`

이 결과는 service worker·content script·popup의 기본 런타임 호환성이
예상보다 높다는 증거다. 그러나 content script execution context 존재만으로
login field 의미 인식이나 autofill 성공을 증명하지는 않는다. 로그인한 vault가
없어 잠금·해제, 저장 항목 매칭, autofill trigger는 실행하지 않았으며 계속
미검증으로 둔다. 실제 계정·비밀번호·운영 vault는 사용하지 않았다.

## 남은 게이트

- 삼성 인터넷 완전 종료·재실행 후 6개 probe 활성·결과 유지
- MediaNab 비지원 native 의존성의 boundary 진단 유지
- Bitwarden의 비운영 test vault 잠금·해제와 로컬 fixture autofill trigger
- 단순 namespace 노출이 아닌 실제 사용 member의 추가 probe evidence

이 게이트가 끝나기 전에는 후보 profile을 baseline으로 승격하지 않는다.
