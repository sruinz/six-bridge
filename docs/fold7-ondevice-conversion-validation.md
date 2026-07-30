# Fold7 온디바이스 변환 검증

## 자동 검증 결과

- 검증일: 2026-07-29
- 기기: Fold7 `SM-F966N`
- ADB serial: `192.0.2.10:39359`
- 자동 게이트:
  `scripts/fold7-ondevice-conversion-flow.sh 192.0.2.10:39359`
- 결과: 초기 변환과 업데이트 변환 모두 통과
- evidence:
  `.local/evidence/20260729-135953-ondevice-conversion-flow.KG2MAa`
- extension ID: `hdencdpngagionipobobpbbnjfnlhbci`
- package: `dev.sixbridge.ext.hdencdpngagionipobobpbbnjfnlhbci`
- signer SHA-256:
  `55f73d78f4597a14039162a63985a831766e402e3910bbb44b3c71d8740c0c0f`
- versionCode: `13` → `14`

게이트는 실제 온디바이스 분석·변환·CRX3 서명·wrapper 서명·`.six` 생성을
두 번 수행한다. 두 결과의 extension ID, package와 signer가 같고
versionCode가 증가했는지 검사한다. host에서는 evidence schema와 모든
SHA-256, `.six` 정확한 entry, descriptor·provenance, CRX3 header·proof·
signature·공개 키 유도 extension ID,
APK v2·v3 서명과 `six://ex`/Provider 계약을 다시 확인한다.

게이트가 수행하는 기기 변경은 관리자 debug APK와 test APK의 `install -r`,
테스트 앱 데이터 기록, 삼성 Add-ons 공개 진입점 실행뿐이다. 래퍼를 자동
설치·제거하지 않으며 `adb uninstall`, `pm clear`, downgrade 또는 사용자
데이터 삭제를 실행하지 않는다.

## 수동 UI 검증 절차

다음 절차는 자동 게이트와 별도로 사용자가 화면을 보고 확인해야 한다.

1. Fold7의 SIX Bridge 관리자 홈에서 `이 기기에서 변환`을 누른다.
2. 다음 허용된 실제 URL을 입력하고 삼성 인터넷 profile, 호환성 결과와
   adapter 결정을 확인한다.

   `https://chromewebstore.google.com/detail/스마트-컨텍스트-메뉴/lneaoklobohnoncgckmbbjfiajgpbdde?hl=ko`

3. 필요한 동작 변경 adapter만 사용자가 직접 승인한다.
4. `.six`를 생성하고 Android 파일 선택기로 안전한 위치에 저장한다.
5. 결과 화면에서 설치를 누르고 Android 시스템 설치 확인 화면에서 대상
   package를 확인한 뒤 사용자가 직접 승인한다.
6. 삼성 인터넷의 Add-ons 화면에서 스마트 컨텍스트 메뉴를 찾아 활성화한다.
7. 일반 웹 페이지에서 텍스트를 선택해 스마트 컨텍스트 메뉴 기능을
   실행하고 결과를 확인한다.
8. 삼성 인터넷을 완전히 종료한 뒤 재실행하고 Add-ons 활성 상태와 일반 웹
   페이지의 텍스트 선택 기능이 모두 유지되는지 다시 확인한다.
9. 같은 URL을 다시 변환·업데이트해 extension ID, package와 signer가
   유지되고 versionCode가 증가하는지 확인한다. Android 시스템 업데이트
   확인 화면도 사용자가 직접 승인한다.
10. 4단계에서 저장한 `.six`를 관리자의 `.six 파일 추가` 흐름으로 다시
    가져와 식별 정보와 호환성 보고서가 동일하게 표시되는지 확인한다.

## 수동 UI 검증 결과

- 검증일: 2026-07-30
- 결과: 위 1~10단계 완료
- evidence:
  `.local/evidence/20260730-224010-chrome-store-url-manual-gate`
- 입력 원본 Chrome Extension ID:
  `lneaoklobohnoncgckmbbjfiajgpbdde`
- 변환 Extension ID:
  `logkhkjljapjeiljhemdfpgendeodflm`
- package:
  `dev.sixbridge.ext.logkhkjljapjeiljhemdfpgendeodflm`
- APK signer SHA-256:
  `5c3a7b12ee77eead998cf1037f669b5f6e6a9253464641437e213ec8e8ff5d75`
- versionCode: `1` → `2`
- 최종 호환성: `compatible`

`action-popup-entry v2`는 사용자가 직접 승인했고
`desktop-selection-event-bridge v1`은 안전 어댑터로 자동 적용됐다.
변환으로 CRX 내용이 바뀌므로 Chrome 웹 스토어 원본 서명을 재사용하지
않는다. 따라서 원본 ID와 변환 ID가 다르며, 이후 같은 URL 변환에서는
Keystore 신원을 재사용해 변환 ID·package·signer를 유지한다.

최초 생성 `.six`와 사용자가 Android 파일 선택기로 저장한
`logkhkjljapjeiljhemdfpgendeodflm-1.six`의 SHA-256은 모두
`ea46218a7eae801608e5834c00fff80589941e73a66635a7f3a33c50a5ca3aa2`로
일치했다. 최초 `.six` 내부 wrapper와 시스템 설치본의 SHA-256도
`71f35eb62638321948e165160582cf4a129f68a654559db886d572a84855a866`으로
일치했다.

두 번째 변환에서 저장한 `.six`는 ZIP 무결성 검사를 통과했고 내부
`extension.json`은 같은 변환 ID·package·signer와 `versionCode 2`를
기록했다. 내부 wrapper와 시스템 업데이트 설치본의 SHA-256은 모두
`828f1cd842da3a8d67b0f41f448578340aad543bff469901763c6bc8fd037ed2`로
일치했다. 두 번째 앱 private 생성본은 작업 정리 뒤 직접 회수하지 못했으므로
저장본과 생성본의 전체 ZIP byte identity는 주장하지 않는다.

두 설치 모두 `com.google.android.packageinstaller`가
`installerPackageName`과 `initiatingPackageName`으로 기록됐고
`originatingPackageName=null`, `packageSource=3`이었다. 삼성 Add-ons에서
변환 확장을 활성화한 뒤 텍스트 선택 기능이 동작했고, 삼성 인터넷 완전
종료·재실행 뒤 활성 상태와 기능이 유지됐다. 같은 URL 업데이트 뒤에도
기능과 재시작 유지가 확인됐다.

최초 저장 `.six`를 `변환된 .six 설치` 흐름으로 다시 가져오자 설치 전 검토
화면에서 다음 정보가 원본과 동일하게 복원됐다.

- 이름·버전: `스마트 컨텍스트 메뉴 · 1.1.1`
- 원본 ID: `lneaoklobohnoncgckmbbjfiajgpbdde`
- 변환 ID와 package:
  `logkhkjljapjeiljhemdfpgendeodflm`,
  `dev.sixbridge.ext.logkhkjljapjeiljhemdfpgendeodflm`
- wrapper versionCode: `1`
- 권한: `activeTab`, `contextMenus`, `storage`
- 비차단 이슈: `storage_area_unknown`
- 두 적용 어댑터와 disposition
- wrapper SHA-256과 signer SHA-256

재가져오기 검토에서는 `설치 APK 내보내기`를 누르지 않고 취소했다.
직후 설치 package는 `versionCode 2`를 유지해 downgrade나 재설치가
발생하지 않았다.

같은 날 저장된 `SIXBridge-exact-ddcp.six`를 최신 관리자 빌드에서 가져와
Google DocumentsUI로 설치하는 별도 수동 회귀도 완료했다. 해당 설치 출처,
`dev_ext`, 활성화·기능·브라우저 재시작 유지 결과는
`docs/FOLD7_SYSTEM_DOCUMENTS_INSTALL_GATE_KO.md`에 기록했다.

## 알려진 기존 검증 이슈

`scripts/verify.sh`의 Android lint는
`AndroidChromeCrxDownloader.kt:479`의
`InputStream.nullInputStream()` API 33 호출 때문에 실패한다. 프로젝트
`minSdk`는 30이다. Task 11 변경과 무관한 기존 이슈이므로 이 작업에서는
수정하지 않았다. Task 11의 shell fixture, instrumentation compile,
manager unit/build와 Fold7 실기기 게이트는 별도로 검증한다.
