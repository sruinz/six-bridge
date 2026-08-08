# 팝업 자동완성 보호·브라우저 내부 주소 자동 검증

## 결론

데스크탑의 실제 public producer인
`SixBridgeService.draftPortableResolved`가 만든 portable `.sixdraft` payload와
저장된 합성 fixture payload가 일치함을 확인했다. Android의 실제 production
consumer인 `DefaultConversionRuntime.prepare`는 같은 `.sixdraft`의 원본 ZIP을
직접 변환한 경우와 draft replay한 경우에 report, plan, 적용 adapter와 변환
source bytes가 일치했다.

같은 입력·profile·확장 서명 key로 재변환했을 때 extension ID, wrapper package,
wrapper signer와 변환 source SHA-256도 유지됐다. popup 보호를 선택하지 않은
portable draft는 빈 서명 계획, 원본 ZIP bytes, `PARTIAL` 진단과
`PASSTHROUGH` 상태를 보존했다.

Android 계측 테스트는 컴파일됐고, Task 8 Fold7 gate의 production conversion
instrumentation도 PASS했다. direct/replay parity 전용 계측 class는 이번 수동
gate에서 별도로 실행하지 않았으므로 JVM parity 결과와 구분한다.

## 검증 기준

- 시작 commit: `f98ff45d7d20d5d53f75c0729ead9f4dbc2e2d73`
- 독립 검토 보완 기준 commit: `bb29c37`
- 검증 환경: JDK 26, Kotlin/JVM, Android debug test source
- Samsung Internet profile:
  `profiles/sbrowser-30.0.2.61.json`
- 기준 profile 파일 SHA-256:
  `00f84b1736298500a71205cc46a009de8747abc68d72e987c3e180de1e54c89c`
- 합성 변환 profile feature:
  `extension.options-page.user-accessible`
- 합성 변환 canonical profile SHA-256:
  `e5f2486009d7eabf31a66222f2ed08dcf55b55961dbe594748449fa5f17e4955`
- popup 원본 유지 profile feature:
  `extension.options-page.user-accessible`
- popup 원본 유지 canonical profile SHA-256:
  `e5f2486009d7eabf31a66222f2ed08dcf55b55961dbe594748449fa5f17e4955`
- 공용 합성 fixture:
  `fixtures/popup-autofill-internal-url-platform`
- 공용 합성 fixture tree SHA-256:
  `e3c5416f6f7313865f2a18388622d03262373276a92630045621acd304d3f846`
- Android 계측 asset:
  `android-manager/src/androidTest/assets/popup-autofill-internal-url-platform.sixdraft`
- 계측 asset SHA-256:
  `bdd95c4be2dbf0145e46000968c9b60a92bc172e3ef9d6c461e91a734a620a6d`
- popup 원본 유지 Android 계측 asset:
  `android-manager/src/androidTest/assets/popup-autofill-original.sixdraft`
- popup 원본 유지 계측 asset SHA-256:
  `4bf5f7baaa3090622cbe0d9106a30f60169cb6153ec4549aebcc23c3b9de85ce`

fixture tree hash는 상대 경로 순으로 각 파일의 SHA-256을 계산한 목록 전체에
다시 SHA-256을 적용한 값이다. 저장된 두 `.sixdraft`는 공용 합성 fixture만
담고 있으며 실제 계정, server URL, cookie, storage 또는 방문 기록을 포함하지
않는다. 기준 profile 파일 SHA-256은 입력 파일의 무결성 표식이고, 각 canonical
profile SHA-256은 테스트에 실제로 사용한 feature 집합까지 canonical 직렬화한
생산 경로 provenance 값이다.

## 자동 검증 결과

### core 생산 경로·replay·결정성

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE :core:test \
  --tests 'dev.sixbridge.core.conversion.BrowserInternalUrlMapperTest' \
  --tests 'dev.sixbridge.core.conversion.BrowserInternalUrlAdapterTest' \
  --tests 'dev.sixbridge.core.conversion.ConversionCompositionTest' \
  --tests 'dev.sixbridge.core.conversion.ConversionPreparationEngineTest' \
  --tests 'dev.sixbridge.core.profile.BrowserArchiveEvidenceScannerTest' \
  --tests 'dev.sixbridge.core.draft.SixDraftReplayTest' \
  --tests 'dev.sixbridge.core.PortableResolvedDraftTest.public portable draft는 Android production runtime용 canonical fixture와 같다' \
  --tests 'dev.sixbridge.core.PortableResolvedDraftTest.popup 원본 유지 portable draft는 passthrough와 빈 signed plan을 보존한다'
```

- 결과: PASS, 74 tests, failures 0, errors 0, skipped 0
- 검증 항목:
  - 실제 desktop public producer와 저장된 canonical draft payload 동일성
  - direct 변환과 portable draft replay의 report·plan·adapter·source 동일성
  - popup 원본 유지의 빈 signed plan, byte-identical no-op과 상태 보존
  - 동일 key의 extension ID, wrapper package와 wrapper signer 유지
  - source/profile/plan provenance hash 관계
  - 합성 private sentinel이 report·plan·expected JSON에 노출되지 않음
  - 기존 APK tuple을 포함한 동적 profile 추출이 검증되지 않은 내부 주소 직접
    이동 feature를 다시 만들지 않음

### 데스크탑 내장 profile

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE :desktop-app:test \
  --tests 'dev.sixbridge.desktop.profile.DesktopProfileCatalogTest.기본 profile은 최신 내장 30점0점2점61이다'
```

- 결과: PASS, 1 test, failures 0, errors 0, skipped 0
- `30.0.2.61`과 `30.0.0.67` 내장 profile 모두
  `browser.internal-url.extensions-root`를 지원 feature로 선언하지 않는다.

### Android production consumer와 계측 source

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE \
  :android-manager:testDebugUnitTest \
  --tests 'dev.sixbridge.manager.conversion.DefaultConversionRuntimeTest' \
  :android-manager:compileDebugAndroidTestKotlin
```

- Android production consumer unit: PASS, 7 tests
- Android 계측 source compile: PASS, `BUILD SUCCESSFUL`
- Fold7 production conversion gate instrumentation: PASS
- direct/replay parity 전용 계측 class 실행: 이번 gate 범위에서는 미실행
- 계측 테스트는 연결 복구 뒤 변환 asset과 popup 원본 유지 asset 각각에 대해
  `DefaultConversionRuntime.prepare`의 direct ZIP과 portable draft replay를
  비교하도록 구현돼 있다.

### popup guard runtime과 정적 경계

```sh
node --test fixtures/popup-native-autofill-guard/guard-runtime.test.mjs
bash scripts/android-manager-distribution.test.sh
```

- popup guard runtime: PASS, 2 tests
- Android manager 검증·패키징 계약: PASS
- 정적 경계는 다음을 fail-closed로 확인한다.
  - `rg` 누락 또는 scan 오류
  - 제품 코드의 특정 제3자 확장 ID·이름 분기
  - popup guard asset의 `fetch`, `XMLHttpRequest`, `chrome/browser.storage`
  - optional chaining과 bracket 표기를 포함한 input `.value` 접근

Node runtime 테스트는 초기 DOM, `MutationObserver`, capture `focusin` 경로에서
input value와 extension API getter가 호출되지 않는 것도 검증한다.

## 2026-08-09 전역 검증 보강

기준선에 남아 있던 테스트 fixture, portable draft 공개 경계,
정적 namespace alias, 분석 예산 수렴과 호스트 JDK 차이를 교정한 뒤
`./scripts/verify.sh`를 fresh 실행했다.

- `bundle-contract`: 60 tests, PASS
- `core`: 700 tests, PASS
- `cli`: 55 tests, PASS
- `desktop-app`: 172 tests, PASS
- `api-probe`: 83 tests, PASS
- Android JDK 17 일반 suite: 806 tests, PASS
- Android JDK 26 보안 ZIP 경계: 41 tests, PASS
- Node fixture/evidence receiver: 138 tests, PASS
- Android `lintDebug`, `assembleDebug`, instrumentation source compile: PASS
- shell fail-closed gate, mobile conversion 결정론·서명, Android 배포 계약: PASS

Robolectric은 JDK 17에서 실행하고, macOS JDK 17의 file provider가
`SecureDirectoryStream`을 지원하지 않아 실행할 수 없는 두 보안 class는
JDK 26에서 별도 실행했다. 이로써 기존 ASM 호환 문제와
파일 시스템 기능 차이를 동시에 회피하면서 보안 테스트를 누락하지
않았다.

`scripts/verify-mobile-conversion.sh`는 고정 project identity와 일회성
workspace를 사용하도록 보강했다. 따라서 사용자의 기존 `.local`
draft key 저장소나 이전 실패 상태에 영향을 주지 않으며, 원본
fixture 불변·동일 draft hash·`verify-draft`를 모두 통과했다.

상세 전역 결과는
`docs/validation/full-suite-stabilization-2026-08-09.md`에 기록했다.

## 개인정보 경계

스토어 URL을 사용하면 extension ID와 다운로드 요청은 Google 및 허용된
redirect host에 전달된다. 반면 변환 산출물, 확장 runtime 입력값, 계정,
cookie, storage 내용과 방문 기록을 원격 분석 서버에 업로드하지 않는다.
popup 보호 adapter도 이 데이터를 읽거나 저장하거나 전송하지 않는다.
