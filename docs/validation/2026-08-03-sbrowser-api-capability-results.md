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

## 기존 확장 host 회귀

| 확장 | 후보 profile 분석 | unavailable | 비고 |
|---|---|---|---|
| 스마트 컨텍스트 메뉴 | `UNKNOWN` | 없음 | 계산된 API alias로 fail-closed |
| AI 레시피 추출기 | `PARTIAL` | 없음 | `contextMenus.create`, `tabs.create` 검증 |
| Video Download to NAS | `PARTIAL` | 없음 | `contextMenus.create` 검증 |

세 확장 모두 분석은 완료됐고 없거나 실패한 API는 없었다. 기존
Fold7 기능 시나리오는 이전 base profile 래퍼에서 사용자가 통과를
확인했다. 후보 profile로 재빌드한 래퍼의 실기기 회귀와 자막
확장 source 재분석은 아직 완료하지 않았으므로 baseline 승격 근거로
사용하지 않는다.

## Tampermonkey

- CRX ID: `dhdgffkkebhmkfjojejmpbldmpobfkfo`
- 버전: `5.5.0`
- 분석 시간: 약 2초
- 상태: `UNKNOWN`
- unavailable: `downloads`, `nativeMessaging`
- 차단 근거: 계산된 namespace/browser alias
- 중요 evidence: `userScripts.getScripts`는 Fold7에서 `FAILED`

현재 결과는 사용자 스크립 등록·주입의 핵심 경로가 동작한다는
근거가 아니다. `userScripts` 대신 검증된 content script 주입 경로로
변환하려면 의미 보존 어댑터와 별도 승인이 필요하다. 설치·스크립
등록·fixture 주입·재시작 유지는 미검증이다.

## Bitwarden

- CRX ID: `nngceckbapebfimnlniiiahkandclblb`
- 버전: `2026.7.0`
- 분석 시간: 약 5초
- 상태: `UNKNOWN`
- 검증 member: 10
- unavailable: `contentScripts`, `nativeMessaging`, `sidebarAction`
- 차단 근거: 계산된 namespace/browser alias

`nativeMessaging`과 특정 사이드바 경로가 선택 기능일 가능성은 있지만,
현재 evidence만으로 핵심 popup·잠금 해제·login field 감지·autofill을
증명할 수 없다. 실제 계정 대신 로컬 fixture로 설치·활성화·popup·
잠금·해제·field 감지·autofill trigger를 순서대로 검증해야 한다.

## 남은 게이트

- 삼성 인터넷 완전 종료·재실행 후 6개 probe 활성·결과 유지
- 후보 profile로 기존 4개 확장 재분석·재빌드·실기기 회귀
- Tampermonkey의 스크립 등록·fixture 주입
- Bitwarden의 로컬 fixture autofill trigger
- 단순 namespace 노출이 아닌 실제 사용 member의 추가 probe evidence

이 게이트가 끝나기 전에는 후보 profile을 baseline으로 승격하지 않는다.
