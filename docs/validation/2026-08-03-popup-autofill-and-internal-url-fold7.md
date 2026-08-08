# 팝업 자동완성 보호·브라우저 내부 주소 Fold7 실기기 검증

## 상태

- 자동 shell 계약: PASS
- production CLI build·`verify-build`: PASS
- Android 계측 APK 조립: PASS
- Fold7 실기기 gate: PASS
- 기준 Samsung Internet: `30.0.0.67` (`versionCode 1300067502`)
- 대상 기기: `SM-F966N`, Android API 36
- evidence:
  `.local/evidence/20260808-231456-84023-popup-autofill-url-gate.log`

2026-08-08 실제 Fold7에서 팝업 입력 보호와 내부 주소 안전 안내를 확인했다.
수동 확인 전후 Samsung Internet PID는 모두 `8940`이었고
`browser-process-survived=true`, `gate-result=passed`가 기록됐다.

## 안전 범위

`scripts/fold7-popup-autofill-url-gate.sh`는 다음 두 합성 fixture만 실제 CLI
`build` 경로로 변환하고 그 래퍼를 설치한다.

- `fixtures/popup-native-autofill-guard`
- `fixtures/browser-internal-url-mapping`

기존 AI 레시피, Video Download to NAS, 스마트 컨텍스트 메뉴, 자막 또는 다른
사용자 확장은 제거하거나 업데이트하지 않는다. Samsung Internet APK도 수정하지
않는다. 합성 package가 이미 있으면 업데이트하지 않고 즉시 중단한다. 재검증이
필요하면 사용자가 Add-ons 이름과 package를 확인한 뒤 해당 합성 래퍼만 직접
제거해야 한다. 자동 입력·클릭, logcat 수집과 방문 URL 수집은 수행하지 않는다.

## 실행 전 준비

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE :cli:installDist

adb devices -l
```

기기가 `device` 상태인지 확인한 뒤 표시된 serial을 그대로 전달한다.

```sh
./scripts/fold7-popup-autofill-url-gate.sh '<adb-serial>'
```

스크립트는 모델·Android API·Samsung Internet package/version을 확인하고 두 합성
래퍼의 설치, `six://ex` discovery, CRX3 ContentProvider와 Samsung Add-ons route를
검증한 뒤 수동 확인 지점에서 대기한다. 변환 identity와 서명 키는 다른 변환 작업과
섞이지 않는 `.local/gates/popup-autofill-url/` 전용 workspace에 유지한다.

## 호스트 production 경로 사전 검증

전용 workspace에서 두 fixture를 연속 두 번 build해 각 extension ID와 wrapper
package가 유지되고 `wrapperVersionCode`만 1에서 2로 증가하는 것을 확인했다.
두 build의 `verify-build`도 통과했다.

- raw profile SHA-256:
  `3cbf345a2e5e539c676445bba8d95c33dbb90259887423a0339e6eb13277bfa6`
- popup fixture tree SHA-256:
  `bbbe7bdf69b94bd75a9f9945cc8427a19ad3de7ad621105a0c1449f85151facb`
- internal URL fixture tree SHA-256:
  `41c3900e7eee226f1754a28c63fb58cfefdcef5a6365bd6694bd7d33f2a95131`
- popup input ZIP SHA-256:
  `2654cbc608f1171af62a08e4dd2543c0c48ba14b156b786d11561abbe029d899`
- internal URL input ZIP SHA-256:
  `f63f46a1130327199cf5382cfe85f73a46e78ad00de85b70aa0e2ff42539a634`

## 수동 체크리스트

1. Samsung Add-ons에서 `Popup Native Autofill Guard`와
   `Browser internal URL mapping fixture`를 활성화한다.
2. popup 보호 fixture의 `text`, `email`, `password`, `url`, `tel`, `search`
   입력을 각각 눌러 Samsung Internet process가 종료되지 않는지 확인한다.
3. 동적으로 추가된 `email` 입력을 눌러 process 생존을 확인한다.
4. 값을 직접 입력하고 기존 popup submit 동작이 유지되는지 확인한다.
5. options 페이지 입력에는 popup 전용 guard가 적용되지 않았는지 확인한다.
6. `chrome://extensions`, `chrome://extensions/`, `edge://extensions`,
   `edge://extensions/`가 직접 이동하지 않고 로컬 추가 기능 안내를 표시하는지
   확인한다.
7. password manager 주소가 원격 페이지 대신 로컬 수동 안내를 여는지 확인한다.
8. flags와 inspect 주소가 로컬 미지원 안내를 여는지 확인한다.
9. query가 있는 주소가 자동 치환되지 않았는지 확인한다.
10. fragment가 있는 주소가 자동 치환되지 않고 fragment가 그대로 유지되는지
    확인한다.
11. 동적 문자열 조합 주소가 자동 치환되지 않았는지 확인한다.
12. 기존 AI 레시피, Video Download to NAS, 스마트 컨텍스트 메뉴와 자막의 주요
    기능이 그대로 동작하는지 확인한다.
13. 모든 확인을 마친 뒤 터미널에서 Enter를 누른다.

Enter 전후 Samsung Internet PID가 없거나 달라지면 gate는 실패한다.

## 실기기 결과

- 팝업 fixture의 모든 입력창에서 텍스트 입력이 가능했고 브라우저가 종료되지
  않았다.
- 초기 직접 이동 진단에서 `tabs.create`는 `internet://urls`를 열었고,
  `window.open`은 브라우저 프로세스를 종료했다. 그 밖의 직접 이동도 무반응
  또는 잘못된 화면을 만들었다.
- 이 결과에 따라 `browser.internal-url.extensions-root` 지원 feature와
  `internet://extension` 치환을 제거했다.
- 교정 빌드에서는 확장 관리 주소, password manager, flags와 inspect가 외부
  이동 없이 로컬 한글 안내를 표시했다.
- query·fragment와 동적 주소는 자동 이동하지 않았으며 무반응이어도 브라우저
  프로세스는 유지됐다.
- 기존에 설치된 검증 확장들은 정상 동작했다.
- 합성 테스트 package 두 개는 gate 완료 후 정확한 package명을 확인하고
  제거했으며 다른 사용자 확장은 변경하지 않았다.

## 자동 회귀

```sh
bash scripts/fold7-popup-autofill-url-gate.test.sh
bash -n scripts/fold7-popup-autofill-url-gate.sh
git diff --check
```

- 성공 fixture: 설치·discovery·provider·route와 동일 PID 확인
- 실패 fixture: 기기 모델·Android API·browser version/versionCode 불일치, 기존
  합성 package 충돌, install·discovery·provider·Add-ons route 실패, 필수 adapter
  미적용, process 종료·교체 거부
- 인자 경계: 빈 값, option 형태와 공백 포함 serial 거부
- 개인정보 경계: stdout와 evidence에 `password=`, `email=`, `cookie=`,
  `visitedUrl=` 및 raw ADB serial 비노출

`./scripts/verify.sh`의 전역 기준선 실패는 별도 자동 검증 문서에 구분해
기록한다. 이번 변경의 focused core·Android production runtime·popup Node·fake
gate와 Android 계측 compile은 별도로 실행하며, 결과를 전체 회귀 PASS로
과장하지 않는다.

## evidence 계약

evidence는 `.local/evidence/<timestamp>-popup-autofill-url-gate.log`에 mode `600`으로
원자 게시한다. 다음 사실만 기록한다.

- 기기 선택 여부·모델·Android API
- browser package·version·profile 일치 여부
- profile과 두 source fixture의 결정적 SHA-256
- 합성 wrapper package·bundle/wrapper SHA-256
- 적용된 필수 adapter ID
- install·discovery·provider·route 결과
- 수동 확인 전후 browser PID와 생존 결과

입력값, 계정, cookie, storage, 방문 URL, raw ADB serial·endpoint와 raw logcat은
기록하지 않는다.
