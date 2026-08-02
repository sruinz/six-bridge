# macOS SIX Bridge → Fold7 SixDraft 게이트

이 절차는 macOS에서 만든 서명된 `.sixdraft`를 Fold7의 Android 관리자에
전달해 다시 분석·변환하는 흐름을 검증한다. SIX Bridge는 삼성 인터넷 APK를
수정하지 않으며, 스크립트도 `.sixdraft`, `.six`, APK를 설치하거나 Android
설치 승인을 대신하지 않는다.

## 자동 게이트

```bash
./scripts/fold7-desktop-sixdraft-gate.sh \
  --draft /안전한/경로/스마트-컨텍스트-메뉴.sixdraft \
  --serial 192.0.2.10:44085 \
  --expected-name '스마트 컨텍스트 메뉴' \
  --expected-package dev.sixbridge.ext.oicemipaegdpdgncidiplligilkmfkip
```

serial은 정확히 `192.0.2.10:44085`만 허용한다. mDNS 이름이나 다른 ADB
대상은 사용하지 않는다. 스크립트는 다음만 자동화한다.

1. 입력 `.sixdraft`를 owner-only private snapshot으로 고정한 뒤 256 MiB
   이하 크기와 SHA-256을 확인하고 hidden
   `sixbridge verify-draft --input`으로 그 snapshot을 strict host 검증한다.
2. 사용자가 스크립트가 표시한 검증된 snapshot을 Quick Share·USB·클라우드
   중 하나로 기기에 전송한 후 파일의 절대
   경로를 입력받아 host·device SHA-256이 같은지 확인한다.
3. 사용자가 Android의 `데스크탑 변환 파일 가져오기`로 가져오기, 재분석·재변환,
   최종 `.six`·래퍼 APK 생성과 파일 앱 설치를 수행한 뒤 Android package와
   logcat을 수집하고 최종 실행 로그를 정확히
   `.local/evidence/<timestamp>-desktop-sixdraft-gate.log`에 기록한다.

현재 Fold7은 Android user 0을 대상으로 하며 Secure Folder user 150은 이
게이트의 설치 대상이 아니다. 최신 `dev.sixbridge.manager` APK가 user 0에
설치되어 있는지 `pm path --user 0 dev.sixbridge.manager`로 먼저 확인한다.
`pm list packages --user 0 --show-versioncode`로 게이트가 고정한 manager
`versionCode 1`과 일치하는지도 검증한다. package 수집은 `--user 0`만
사용하고 `--expected-package`의 정확한 wrapper가 설치되지 않으면
실패한다. logcat은 `pm list packages -U --user 0`으로 manager UID를
확정하고 그 UID와 정확한 process name이 모두 일치하는 PID로만 제한하며,
전체 logcat을 저장하지 않는다. 이 스크립트는 manager APK를 설치하지 않는다.

이 evidence와 `.local/`의 키는 Git에 추가하지 않는다. `--self-test`는
확장자, private snapshot, 256 MiB 제한, SHA-256 생성, `adb install`
호출 없음과 잘못된 확장자, 손상 draft, 없는 serial, 빈 expected
name/package가 Android 상태 변경 전 거부되는지를 확인한다. manager
versionCode 불일치, wrapper package 부재, user 0 비범위 package 조회도
실패해야 한다. Secure Folder PID가 먼저 보이더라도 user 0 UID의 main
process만 선택하고, 성공·실패 모든 경로에서 private snapshot을 정리한다.

## 일반 사용자 절차

1. macOS SIX Bridge에서 URL 또는 로컬 입력을 분석한다.
2. 어댑터와 부분 호환 여부를 확인한다.
3. `.sixdraft`를 저장한다.
4. Quick Share·USB·클라우드로 Fold7에 전송한다.
5. Android에서 `데스크탑 변환 파일 가져오기`를 선택한다.
6. signer fingerprint와 보고서를 확인한다.
7. 최종 `.six`와 래퍼 APK를 생성한다.
8. 파일 앱에서 Android의 사용자 승인 설치를 직접 확인한다.
9. 삼성 인터넷 Add-ons를 활성화하고 기능을 확인한다.

## 개발 증거와 수동 판정

자동 수집이 성공해도 아래 화면 판정은 완료가 아니다. 각 실행 시각과 함께
다음을 evidence에 기록한다.

- host의 `SixDraft.read`에 해당하는 hidden `verify-draft` strict 검증
- host와 device `.sixdraft` SHA-256
- user 0 manager versionCode와 예상 wrapper package 설치 상태
- manager PID로 제한한 logcat(실행 중인 경우)
- 삼성 인터넷 Add-ons의 수동 스크린샷
- 기능 동작과 삼성 인터넷 강제 종료·재실행 뒤 활성 상태 유지
- `.local/evidence/<timestamp>-desktop-sixdraft-gate.log`에 해당하는 실행 로그

Add-ons에는 `스마트 컨텍스트 메뉴` 이름과 아이콘이 표시되고 `지원 안 함`이
없어야 한다. 텍스트 선택 메뉴를 실행하고 설정을 저장한 뒤, 삼성 인터넷을
강제 종료·재실행하여 활성 상태와 기능이 유지되는지 확인한다. 마지막으로
관리자 앱의 제거 버튼으로 래퍼를 제거한다. 화면 확인, 설치 승인, 재시작 유지,
제거 중 하나라도 기록되지 않았거나 불명확하면 종단간 게이트는 통과가 아니다.

Android는 portable source를 재분석·재변환하고 Android Keystore로 최종
CRX3와 래퍼 APK에 서명한다. macOS의 전달 signer fingerprint와 Android 최종
signer의 역할을 같은 것으로 기록하지 않는다.

## macOS 입력 두 종류 비교

스마트 컨텍스트 메뉴에 대해 Chrome 웹 스토어 URL과 CRX3를 각각 `.sixdraft`로
만든다. 두 파일이 schema v2이며 source kind가 `ZIP` 또는 `CRX`이고
`PREPARED_CRX`가 아닌지 확인한다. profile, conversion plan, expected hash는
같고 입력 종류와 원본 provenance만 달라야 한다. 실행 전후 데스크톱 app data와
프로젝트 `.local/keystore/crx`에 새 CRX key가 생성되지 않았는지도 별도 기록한다.

## 2026-08-02 실기기 검증 결과

스마트 컨텍스트 메뉴 `1.1.1`의 실제 Chrome 웹 스토어 CRX3와 사용자가 제공한
Chrome 웹 스토어 상세 URL을 macOS 제품 엔진으로 각각 분석·변환했다.

- 원본 CRX3 SHA-256:
  `94d42d2024525e63c0dad65ec5a8c775cff92b4cbdc707bf62209cbed9d14995`
- 로컬 CRX 입력 draft: 39,252 bytes,
  SHA-256 `9d801d88e2608a7d42b2c9e85955d2204d5edf5cfc5d70bf4a06c0097d5200eb`
- Chrome 웹 스토어 URL 입력 draft: 39,264 bytes,
  SHA-256 `bdbcbfa805027a5b7a4bca56014f38a060e7f52153c1526a21215f8eef0c02e5`
- 두 draft 모두 hidden `verify-draft` strict 검증을 통과했다.
- 입력 provenance의 `inputKind`는 각각 `crx`와 `chrome-web-store`로 달랐다.
  원본 CRX bytes, 원본 SHA-256, 확장 ID·버전, profile, conversion plan,
  변환 전후 compatibility와 expected hash는 일치했다.
- 두 결과는 schema v2와 source kind `CRX`를 사용했으며
  `PREPARED_CRX`를 사용하지 않았다.
- 제품 엔진은 portable draft signer만 사용했고 CRX key를 생성하지 않는
  기존 회귀 검증을 유지했다. 프로젝트 `.local/`의 키는 읽거나 변경하지
  않았으며 Git 대상에도 포함하지 않았다.

URL 입력 draft를 Fold7 `192.0.2.10:44085`의
`/sdcard/Download/SIXBridge-smart-context-store.sixdraft`로 전송했다. host와
device SHA-256은 모두
`bdbcbfa805027a5b7a4bca56014f38a060e7f52153c1526a21215f8eef0c02e5`로
일치했다. 사용자는 Android 관리자의 명시적 가져오기 버튼으로 선택하고 최종
`.six`·래퍼 APK 생성과 시스템 설치 화면을 직접 승인했다.

최종 래퍼 `dev.sixbridge.ext.oicemipaegdpdgncidiplligilkmfkip`은 user 0에
`versionCode 1`, APK Signature Scheme v3,
`installerPackageName=com.google.android.packageinstaller`로 설치됐다.
삼성 인터넷 Add-ons에서 이름·아이콘이 표시되고 `지원 안 함`이 없었으며,
텍스트 선택 기능과 설정 저장이 동작했다. 삼성 인터넷 강제 종료·재실행과
페이지 새로고침 뒤에도 활성 상태와 기능이 유지됐다. 마지막으로 관리자 앱의
제거 버튼으로 래퍼를 제거했고 `pm path --user 0`에서도 패키지가 사라진 것을
확인했다.

실제 입력 검증 중 자동 `desktop-selection-event-bridge`가 manifest를
재직렬화하면 미선택 `action-popup-entry` 근거의 line만 이동해 draft 생성이
과도하게 거부되는 결함을 발견했다. 재분석 비교는 evidence의 path·detail과
결정의 모든 의미 필드를 계속 엄격히 비교하되 line 이동만 허용하고 원본
decision을 보존하도록 수정했다. evidence 추가·삭제·detail 변경 거부 회귀
테스트와 실제 CRX·URL 변환을 모두 통과했다.

자동 게이트 실행 로그는
`20260801-223036-desktop-sixdraft-gate.log`로 수집했다. 실제 실행에서는 보호된
프로젝트 `.local/`을 건드리지 않기 위해 `/private/tmp/sixbridge-task12-evidence/`
아래에 저장했으며, 기본 스크립트 계약과 self-test는
`.local/evidence/<timestamp>-desktop-sixdraft-gate.log` 경로를 검증한다.

## 버전과 Windows 경계

macOS native package build version은 계획 편차로 `1.0.0`이며 제품 메타데이터는
`0.1.0`이다.

Windows x86-64 MSI 구성과 owner-only ACL 코드는 포함되어 있으나,
실제 Windows MSI 생성·설치·코드 서명·SmartScreen 검증은 아직 완료되지
않았습니다.

## macOS 자동 테스트 경계

macOS 기본 filesystem은 `SecureDirectoryStream`을 제공하지 않는다. 따라서
기본 임시 경로를 사용하는 core와 Android 입력 서비스의 SDS 의존 unit은
각각 `OwnedTempCleanupException`과 `OnDeviceInputException`으로
fail-closed한다. 이는 승인된 보안 정책이며 통과를 위해 core 조건을 완화하거나
Jimfs를 core·Android에 노출하지 않는다. 이 경로는 Jimfs secure root 집중
테스트와 Fold7 실기기 게이트로 검증한다.

별도로 발견된 관리자 아이콘 fallback의 extended grapheme 실패는
`java.text.BreakIterator` 대신 `\X` cluster를 사용하도록 수정했으며
`HomeScreenTest` 3건이 통과했다.
