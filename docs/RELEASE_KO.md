# SIX Bridge v0.1.0 공개 배포 안내

## 배포 범위

SIX Bridge v0.1.0은 사용자가 직접 개발했거나 적법한 변환 권한을 가진 Chrome/Edge 확장을 삼성 인터넷 30.x에서 시험하기 위한 로컬 도구의 첫 preview 배포입니다.
분석·변환 내용과 생성 결과를 원격 분석 서버에 업로드하지 않습니다. 프로젝트는 제3자 확장이나 사용자의 변환 결과를 배포하지 않습니다.

공개 산출물은 다음 다섯 파일입니다.

- `SIX-Bridge-Manager-0.1.0-android.apk`
- `SIX-Bridge-0.1.0-macos-unsigned.dmg`
- `SHA256SUMS`
- `RELEASE-METADATA.json`
- `RELEASE-NOTES-KO.md`

소스와 배포 스크립트는 [Apache-2.0](../LICENSE)으로 제공합니다.

## Android 관리자 APK

`SIX-Bridge-Manager-0.1.0-android.apk`는 Android 관리자 앱입니다. Android
시스템 설치 화면에서 출처 허용과 설치 승인을 사용자가 직접 확인해야 하며,
처음 설치할 때 **Google Play Protect**가 아직 인식하지 못한 개발자 앱이라는
경고를 표시할 수 있습니다. 이 경고는 서명된 APK가 즉시 안전하다는 보증이
아니며, 설치 전 `SHA256SUMS`와 `RELEASE-METADATA.json`의 SHA-256 및 signer
정보를 확인해야 합니다.

관리자 설치 뒤에는 `.six`를 가져와 호환성 보고서를 검토하고 확장별 APK
설치를 Android 시스템 화면에서 승인합니다. 삼성 인터넷 Add-ons의 활성화
상태와 실제 동작은 별도로 확인해야 합니다.

## macOS preview DMG

`SIX-Bridge-0.1.0-macos-unsigned.dmg`는 **미서명·미공증** preview입니다.
따라서 macOS Gatekeeper가 개발자를 확인할 수 없다는 경고를 표시할 수
있습니다. 이 파일은 Apple Developer ID 서명이나 Apple 공증을 받지 않았으며,
Gatekeeper를 자동으로 우회하거나 시스템 보안 설정을 변경하지 않습니다.

무료 Apple 개발자 계정은 외부 배포용 Developer ID 인증서와 Apple 공증을
제공하지 않습니다. 관련 Apple 정책은 [Developer ID 지원 문서](https://developer.apple.com/support/developer-id/),
[Developer ID certificate 용어](https://developer.apple.com/help/glossary/developer-id-certificate/),
[macOS 소프트웨어 공증 문서](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)를
확인하세요.

## 무결성 확인

배포 페이지에서 `SHA256SUMS`와 `RELEASE-METADATA.json`을 함께 내려받은 뒤,
다운로드한 APK와 DMG의 SHA-256을 `SHA256SUMS`의 값과 비교하세요. macOS에서는
예를 들어 다음 명령으로 확인할 수 있습니다.

```sh
shasum -a 256 SIX-Bridge-Manager-0.1.0-android.apk
shasum -a 256 SIX-Bridge-0.1.0-macos-unsigned.dmg
```

값이 다르거나 metadata의 제품 버전·signer 정보가 기대와 다르면 설치하거나
실행하지 마세요. `RELEASE-NOTES-KO.md`에는 해당 release의 기능과 제한이
기록됩니다.

## 네트워크와 개인정보 경계

분석·변환 내용과 생성 결과를 원격 분석 서버에 업로드하지 않습니다. 스토어 URL을 입력하면 extension ID와 다운로드 요청이 Google 및 허용된 redirect host에 전송됩니다.
이 요청은 원본 확장을 로컬로 가져오기 위한 요청이며, SIX Bridge가 분석 결과나 생성 결과를 수집하는 telemetry가 아닙니다.

변환된 확장 자체의 외부 네트워크 통신은 해당 확장의 동작입니다. SIX Bridge는
확장의 서버 요청, 계정, cookie, storage 또는 방문 기록을 수집·전송하지 않습니다.

## 공개 이슈와 지원 요청의 비밀정보 경계

공개 이슈 또는 지원 요청에는 비밀번호, 토큰, 개인키, keystore를 첨부하거나 기록하지 마세요.
계정·cookie·storage 내용, 원본/변환 확장과 사용자 데이터가 포함된 로그도 공개 이슈나 지원 요청에 첨부하지 마세요.
재현 정보가 필요하면 비밀값과 개인 데이터를 제거한 최소 재현 절차, 오류 종류, 앱과 브라우저 버전만 제공하세요.

## 지원과 제한

- 지원 기준은 삼성 인터넷 30.x와 Android 11 이상입니다.
- 삼성 인터넷 APK는 수정하지 않습니다.
- 무인 설치, root, Shizuku, Google Play 또는 Galaxy Store 등록은 지원하지
  않습니다.
- 각 확장의 변환·설치·공유 권한과 라이선스 준수 책임은 사용자에게 있습니다.
