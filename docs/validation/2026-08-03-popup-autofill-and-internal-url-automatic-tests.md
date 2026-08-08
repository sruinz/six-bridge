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

Android 계측 테스트는 컴파일됐지만 이 검증 시점에는 Fold7가 ADB에 연결되지
않아 실기기 실행 결과를 만들지 않았다. 따라서 Android production runtime의
JVM 검증은 PASS이고, 실제 기기 검증은 보류다.

## 검증 기준

- 시작 commit: `f98ff45d7d20d5d53f75c0729ead9f4dbc2e2d73`
- 독립 검토 보완 기준 commit: `bb29c37`
- 검증 환경: JDK 26, Kotlin/JVM, Android debug test source
- Samsung Internet profile:
  `profiles/sbrowser-30.0.2.61.json`
- 기준 profile 파일 SHA-256:
  `296971df57ed43fb5448d296badd0fa453a54efbd32814f5e47046d576214f8b`
- 합성 변환 profile feature:
  `extension.options-page.user-accessible`,
  `browser.internal-url.extensions-root`
- 합성 변환 canonical profile SHA-256:
  `0633598b9afe50d72b4691cbfb480591cb336ae349563fd8f03d57a4d7474723`
- popup 원본 유지 profile feature:
  `extension.options-page.user-accessible`
- popup 원본 유지 canonical profile SHA-256:
  `e5f2486009d7eabf31a66222f2ed08dcf55b55961dbe594748449fa5f17e4955`
- 공용 합성 fixture:
  `fixtures/popup-autofill-internal-url-platform`
- 공용 합성 fixture tree SHA-256:
  `4c02095fc276d4a7e3c56c1d9f1fc382ffd2d942e5c7bf8b8e45fe028fcff3aa`
- Android 계측 asset:
  `android-manager/src/androidTest/assets/popup-autofill-internal-url-platform.sixdraft`
- 계측 asset SHA-256:
  `fde71f337cc7b415dd4ebadcf19ffb4f0a7feca114fb6888c052ae889b5e1b62`
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
  --tests 'dev.sixbridge.core.conversion.ConversionPreparationEngineTest' \
  --tests 'dev.sixbridge.core.draft.SixDraftReplayTest' \
  --tests 'dev.sixbridge.core.PortableResolvedDraftTest.public portable draft는 Android production runtime용 canonical fixture와 같다' \
  --tests 'dev.sixbridge.core.PortableResolvedDraftTest.popup 원본 유지 portable draft는 passthrough와 빈 signed plan을 보존한다'
```

- 결과: PASS, 35 tests, failures 0, errors 0, skipped 0
- 검증 항목:
  - 실제 desktop public producer와 저장된 canonical draft payload 동일성
  - direct 변환과 portable draft replay의 report·plan·adapter·source 동일성
  - popup 원본 유지의 빈 signed plan, byte-identical no-op과 상태 보존
  - 동일 key의 extension ID, wrapper package와 wrapper signer 유지
  - source/profile/plan provenance hash 관계
  - 합성 private sentinel이 report·plan·expected JSON에 노출되지 않음

### Android production consumer와 계측 source

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE \
  :android-manager:testDebugUnitTest \
  --tests 'dev.sixbridge.manager.conversion.DefaultConversionRuntimeTest.Android production runtime은 desktop portable draft와 direct ZIP을 동일하게 준비한다' \
  --tests 'dev.sixbridge.manager.conversion.DefaultConversionRuntimeTest.Android production runtime은 popup 원본 유지 draft를 no-op으로 재생한다' \
  :android-manager:compileDebugAndroidTestKotlin
```

- Android production consumer unit: PASS, 2 tests
- Android 계측 source compile: PASS, `BUILD SUCCESSFUL`
- Fold7 계측 실행: 보류. `adb devices -l`에 연결 기기가 없었다.
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

## 기존 기준선과 분리한 미완료 검증

전체 JDK 26 unit suite에는 이번 변경 전부터 존재하는 기준선 실패가 있다.

- core 전체: 32 failures
- Android debug unit: 844 tests 중 124 failures
- Android 실패의 공통 원인: JDK 26에서 Robolectric ASM `ClassReader`가 class를
  해석하지 못하는 기존 환경 호환 문제

`bash scripts/verify-mobile-conversion.sh`는 기존
`fixtures/desktop-mobile-golden`에 source project identity 표식이 없어 기존
fail-closed 계약에서 중단된다.

```text
표식 없는 확장 소스에는 source project identity가 필요합니다.
```

해당 script와 fixture는 시작 commit과 비교해 변경하지 않았다.

## 개인정보 경계

스토어 URL을 사용하면 extension ID와 다운로드 요청은 Google 및 허용된
redirect host에 전달된다. 반면 변환 산출물, 확장 runtime 입력값, 계정,
cookie, storage 내용과 방문 기록을 원격 분석 서버에 업로드하지 않는다.
popup 보호 adapter도 이 데이터를 읽거나 저장하거나 전송하지 않는다.
