# Fold7 온디바이스 packager 기술 게이트

## 대상

- 기기: SM-F966N (Android 16)
- 삼성 인터넷: `com.sec.android.app.sbrowser` 30.0.0.67 (`versionCode` 1300067502)
- 실행 일시: 2026-07-27
- evidence: `.local/evidence/20260727-170817-ondevice-packager-gate/`

## 자동 검증 결과

- `generateV1AndPersistIdentityEvidence`: `OK (1 test)`
- 별도 process의 `generateV2AfterProcessRestartAndMatchIdentity`: `OK (1 test)`
- host gate는 두 method 사이에 `am force-stop dev.sixbridge.manager`를 실행해
  실제 app process 종료 뒤 Android Keystore 신원과 예약 binding이 유지되는지 검증한다.
- host `apksigner` 검증: v2는 `--min-sdk-version 24`, v3는
  `--min-sdk-version 30`으로 각각 성공했다.
- `resources.arsc`가 STORED이고 APK local header의 파일 데이터 offset이
  4-byte aligned임을 확인했다.
- v1 APK 설치 뒤 동일 signer의 v2 APK를 `install -r`로 업데이트했고,
  설치된 `versionCode` 2를 확인했다.
- `six://ex` discovery가 wrapper package로 resolve되고, exported Provider가
  `Cr24`로 시작하는 CRX3 stream을 제공함을 확인했다.
- `internet://extension` intent로 삼성 인터넷 Add-ons 화면을 열었다.

## 공개 산출물 식별값

- extension ID: `gnnkbhaffbmipdhnnfmachnfmgbbnkhp`
- wrapper package: `dev.sixbridge.ext.gnnkbhaffbmipdhnnfmachnfmgbbnkhp`
- wrapper signer SHA-256: `5d2ea272ca09365377c14a6003303672c2ab47ae8b9090f8f38fb6d8fd144f58`
- CRX SHA-256: `7b7fae973e2475647f98646352ddf89fe9d0bde42169354e69eead71f281dcca`
- v1 APK SHA-256: `875f9b0d81c053b8bc075e5e46699002db111e3eaa9f266750fee5c12b91c12c`
- v2 APK SHA-256: `0024a6b56adc53a98bccf82939333abff908e3a88426cf2b8395045c15080d31`

Android Keystore alias 이외의 키 정보나 개인 데이터는 기록하지 않는다.

## 사용자 실기기 확인: 완료

- 확인 일시: 2026-07-27
- 확인 결과: 사용자가 Samsung Internet Add-ons에서 아래 네 항목을 모두
  `정상`으로 확인했다.

1. `SIX Bridge On-Device Gate` 새 Add-on 표시
2. Add-on 활성화
3. 최소 fixture content 동작
4. 삼성 인터넷 재시작 뒤 활성 상태 유지
