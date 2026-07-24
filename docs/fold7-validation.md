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
