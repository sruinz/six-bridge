# Fold7 온디바이스 변환 검증

## 자동 검증 결과

- 검증일: 2026-07-29
- 기기: Fold7 `SM-F966N`
- ADB serial: `192.0.2.10:39359`
- 자동 게이트:
  `scripts/fold7-ondevice-conversion-flow.sh 192.0.2.10:39359`
- 결과: 초기 변환과 업데이트 변환 모두 통과
- evidence:
  `.local/evidence/20260729-134648-ondevice-conversion-flow.ly12hK`
- extension ID: `hdencdpngagionipobobpbbnjfnlhbci`
- package: `dev.sixbridge.ext.hdencdpngagionipobobpbbnjfnlhbci`
- signer SHA-256:
  `55f73d78f4597a14039162a63985a831766e402e3910bbb44b3c71d8740c0c0f`
- versionCode: `9` → `10`

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

1. Fold7에서 SIX Bridge 관리자를 연다.
2. 홈에서 `.six 파일 추가`와 `이 기기에서 변환`이 서로 다른 동작으로
   표시되는지 확인한다.
3. `이 기기에서 변환`을 누르고 Chrome Web Store 입력을 선택한다.
4. 다음 허용된 실제 URL을 입력하고 분석을 시작한다.

   `https://chromewebstore.google.com/detail/스마트-컨텍스트-메뉴/lneaoklobohnoncgckmbbjfiajgpbdde?hl=ko`

5. 권한, adapter 승인 항목, 호환성 제한과 대상 profile을 확인한다.
6. 변환을 실행하고 결과 화면의 extension ID, package, signer와
   versionCode를 기록한다.
7. `.six 저장`을 선택해 Android 파일 선택기에서 위치를 지정한다.
8. 공유를 선택해 Android 공유 시트가 열리는지 확인한 뒤 취소한다.
9. 저장한 `.six`를 `.six 파일 추가`로 다시 열어 동일한 식별 정보와
   호환성 보고서가 표시되는지 확인한다.
10. 같은 입력을 다시 변환해 식별 정보와 signer는 유지되고 versionCode가
    증가하는지 확인한다. 설치를 선택했다면 Android 시스템 승인 화면을
    사용자가 직접 취소하거나 승인하는지 확인한다.

이 문서 작성 시점에는 위 수동 UI 절차를 실행했다고 주장하지 않는다.
자동 게이트만 완료했다.

## 알려진 기존 검증 이슈

`scripts/verify.sh`의 Android lint는
`AndroidChromeCrxDownloader.kt:479`의
`InputStream.nullInputStream()` API 33 호출 때문에 실패한다. 프로젝트
`minSdk`는 30이다. Task 11 변경과 무관한 기존 이슈이므로 이 작업에서는
수정하지 않았다. Task 11의 shell fixture, instrumentation compile,
manager unit/build와 Fold7 실기기 게이트는 별도로 검증한다.
