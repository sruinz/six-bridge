# 전역 테스트 안정화 검증 결과

## 결론

2026-08-09 기준 `codex/popup-autofill-url-guard` 브랜치의 전체
검증은 통과했다. 팝업 native autofill 보호와 브라우저 내부
URL 변환의 fail-closed 정책을 느슨하게 만들지 않고, portable
`.sixdraft` 공개 생성 경계와 정적 분석 결과를 정렬했다.

특정 제3자 확장의 ID나 이름에 따라 동작하는 분기는 제품 코드에
추가하지 않았다. 변환 원본, 계정, cookie, storage, 방문 기록을
원격 분석 서버로 전송하는 경로도 추가하지 않았다.

## 수정한 원인

1. 예전 팝업 fixture가 유효한 `<head>` 삽입 경계를 갖지 않아 새
   보호기에서 의도와 달리 `UNKNOWN`으로 차단됐다.
2. 공개 `SixBridgeService.draft(input, ...)` 경계가 portable source가 아닌
   내부 prepared source 경로를 사용하고 있었다.
3. 분석 예산 초과 예외가 사용자용 `UNKNOWN` report로 수렴되지
   않았다.
4. `const api = chrome.storage`와 같은 정적 namespace alias와
   `{ chrome: value }`와 같은 object property를 동적 API 사용으로
   오판하는 경우가 있었다.
5. 일부 스냅샷·정리 테스트가 운영체제 scheduling과 JDK 파일
   시스템 구현 차이에 의존했다.
6. Android Robolectric은 JDK 26 bytecode를 읽지 못하지만,
   `SecureDirectoryStream` 보안 테스트는 macOS의 JDK 17 파일
   provider에서 실행할 수 없었다.
7. 모바일 변환 스크립트가 기존 `.local` draft key 저장소에
   의존해 이전 실패 상태의 영향을 받았다.

## 검증 구성

### JVM 모듈

JDK 26, `--max-workers=1`로 clean 후 순차 실행했다.

| 모듈 | 테스트 | 결과 |
| --- | ---: | --- |
| `bundle-contract` | 60 | PASS |
| `core` | 700 | PASS |
| `cli` | 55 | PASS |
| `desktop-app` | 172 | PASS |
| `api-probe` | 83 | PASS |
| `android-wrapper-template` | 0 | PASS (`NO-SOURCE`) |

### Android 관리자

- JDK 17 Robolectric·일반 host 단위 테스트: 806 tests, PASS
- JDK 26 보안 ZIP 경계:
  - `OnDeviceInputServiceTest`
  - `DefaultConversionRuntimeTest`
  - 합계 41 tests, PASS
- `lintDebug`: PASS
- `assembleDebug`: PASS
- `compileDebugAndroidTestKotlin`: PASS

일반 Android 테스트에서 위 두 class만 제외한 뒤 JDK 26에서
다시 전체 실행했다. 따라서 보안 경계를 검증하지 않고 넘어간
항목은 없다.

### Node·shell·변환 계약

- Node fixture/evidence receiver: 138 tests, PASS
- API probe 생성·정적 안전성·결정론 gate: PASS
- 대용량 데스크탑 확장 분석 gate: PASS
- Fold7 API probe shell gate: 33 tests, PASS
- Fold7 기존·변환·온디바이스·popup/URL fake gate: PASS
- 모바일 변환:
  - 원본 fixture tree 불변: PASS
  - 동일 입력 `.sixdraft` SHA-256 결정론: PASS
  - `verify-draft`: PASS
- Android manifest 계약: PASS
- Android 배포·패키징 계약: PASS

## 실행 명령

전역 검증은 다음 명령으로 실행했다.

```sh
./scripts/verify.sh
```

Android instrumentation source는 다음 명령으로 별도 컴파일했다.

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=.local/gradle-home \
./gradlew -Pkotlin.jvm.target.validation.mode=IGNORE \
  :android-manager:compileDebugAndroidTestKotlin --max-workers=1
```

## 비차단 경고

Android 컴파일에서 기존 deprecated API 경고와 native debug 라이브러리
strip 경고가 표시됐다. 이 변경으로 새로 추가된 컴파일·lint·test
실패는 없다.
