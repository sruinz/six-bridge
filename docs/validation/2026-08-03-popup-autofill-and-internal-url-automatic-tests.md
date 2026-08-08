# 팝업 자동완성 보호·브라우저 내부 주소 자동 검증

## 결론

데스크탑 portable `.sixdraft` replay와 Android 직접 변환이 같은 core
production API를 사용하며, 두 새 adapter의 canonical report, 적용 adapter 집합과
변환 source SHA-256이 일치함을 자동 테스트로 확인했다.

같은 입력·profile·확장 서명 key로 재변환했을 때 extension ID, wrapper package,
wrapper signer와 변환 source SHA-256도 유지됐다. popup 보호를 승인하지 않은 경로는
원본 ZIP bytes와 `PARTIAL` 진단을 보존했다.

제품 production source/resource에는 특정 제3자 확장 ID·이름 분기가 없으며,
popup 보호 asset은 입력값·storage·network API를 사용하지 않는다.

## 검증 기준

- 시작 commit: `f98ff45d7d20d5d53f75c0729ead9f4dbc2e2d73`
- 검증 환경: JDK 26, Kotlin/JVM 및 Android debug 계측 test source
- Samsung Internet profile 파일:
  `profiles/sbrowser-30.0.2.61.json`
- profile 파일 SHA-256:
  `296971df57ed43fb5448d296badd0fa453a54efbd32814f5e47046d576214f8b`
- popup 보호 fixture tree SHA-256:
  `32907abb3fe3a30bfaa6a177a44d243d5b845fc0caca26e8a53957d80127c27d`
- 내부 주소 fixture tree SHA-256:
  `a8666b137cf152dfe766b6654a5e83790e5c9dfec20961a8b7a68ca83cd44251`

fixture tree hash는 상대 경로 순으로 각 파일의 SHA-256을 계산한 뒤 그 목록 전체에
다시 SHA-256을 적용한 값이다.

## 자동 검증 결과

### core 플랫폼 동일성과 결정성

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew :core:test \
  --tests 'dev.sixbridge.core.conversion.ConversionPreparationEngineTest' \
  --tests 'dev.sixbridge.core.draft.SixDraftReplayTest' \
  -Pkotlin.jvm.target.validation.mode=IGNORE
```

- 결과: PASS, 33 tests, failures 0, errors 0, skipped 0
- 검증 항목:
  - direct 변환과 portable draft replay의 report·adapter ID·source hash 동일성
  - popup 원본 유지의 byte-identical no-op과 `PARTIAL` 진단 보존
  - 동일 key의 extension ID, wrapper package와 wrapper signer 유지
  - adapter evidence에 입력값·cookie·방문 URL 미포함

### Android 계측 source

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew :android-manager:compileDebugAndroidTestKotlin \
  -Pkotlin.jvm.target.validation.mode=IGNORE
```

- 결과: PASS, `BUILD SUCCESSFUL`
- Fold7 계측 실행: 보류. `adb devices -l`에 연결 기기가 없어 Task 8 실기기
  gate에서 실행한다.

### popup guard runtime과 정적 경계

```sh
node --test fixtures/popup-native-autofill-guard/guard-runtime.test.mjs
```

- 결과: PASS, 2 tests, fail 0

다음 production 범위 정적 검사는 모두 출력 없이 PASS했다.

```sh
rg -ni \
  'nngceckbapebfimnlniiiahkandclblb|dhdgffkkebhmkfjojejmpbldmpobfkfo|bitwarden|tampermonkey' \
  core/src/main desktop-app/src/main android-manager/src/main

rg -n \
  'fetch\(|XMLHttpRequest|chrome\.storage|browser\.storage|\.value\b' \
  core/src/main/resources/dev/sixbridge/adapters/popup-native-autofill-guard.v1.js
```

두 명령은 일치 항목이 없을 때 PASS다.

## 기존 기준선과 분리한 미완료 검증

전체 JDK 26 unit suite에는 이번 변경 전부터 존재하는 기준선 실패가 있다.

- core 전체: 32 failures
- Android debug unit: 844 tests 중 124 failures
- Android 실패의 공통 원인: JDK 26에서 Robolectric ASM `ClassReader`가 class를
  해석하지 못하는 기존 환경 호환 문제

Task 7 focused 33 tests와 Android 계측 source compile은 위 기준선과 독립적으로
PASS했다.

`bash scripts/verify-mobile-conversion.sh`는 기존
`fixtures/desktop-mobile-golden`에 source project identity 표식이 없어 다음 기존
fail-closed 계약에서 중단됐다.

```text
표식 없는 확장 소스에는 source project identity가 필요합니다.
```

해당 script는 시작 commit `f98ff45`과 비교해 변경되지 않았으며 Task 7 회귀가 아니다.

## 개인정보 경계

검증 산출물에는 계정, server URL, 입력값, cookie, storage 내용 또는 방문 URL을
기록하지 않았다. SIX Bridge의 popup 보호 asset도 이 데이터를 읽거나 저장하거나
전송하지 않는다.
