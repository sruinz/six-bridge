# Fold7 시스템 문서 앱 설치 게이트

## 목적과 판정 원칙

이 문서는 Fold7에서 SIX Bridge 관리자가 내보낸 래퍼 APK를 Google
DocumentsUI의 다운로드 화면에서 사용자가 직접 선택해 설치했을 때, 설치
출처와 래퍼 신원 및 Samsung Internet 확장 동작을 검증하는 절차다.

자동 명령은 관리자 APK 업데이트, 설치 상태와 로그의 읽기 전용 수집까지만
수행한다. DocumentsUI에서 APK 선택, Android 설치 승인, Add-ons 활성화와 실제
기능 확인은 사용자가 화면을 보고 수행한다. 수동 항목을 확인하지 않은 실행은
자동 검증이 성공해도 전체 게이트 합격으로 기록하지 않는다.

## 고정 환경과 기준 신원

- 기기: Samsung Galaxy Fold7 `SM-F966N`
- ADB serial: `192.0.2.10:39359`
- manager package: `dev.sixbridge.manager`
- 시스템 문서 앱 package: `com.google.android.documentsui`
- 허용 installer와 initiating package:
  `com.google.android.packageinstaller`
- 검증 wrapper package:
  `dev.sixbridge.ext.ddcpmlcaanjillmjmnlmngimhbbojgam`
- 기준 APK SHA-256:
  `8a0b44558e22d16deaf7a92e8e8119629cadbfcc88dbbd8dae160fb41b30a6c2`
- 기준 signer SHA-256:
  `55f73d78f4597a14039162a63985a831766e402e3910bbb44b3c71d8740c0c0f`

기존 AI 레시피, Video Download to NAS와 다른 정상 래퍼는 제거하거나
업데이트하지 않는다. 이 게이트는 삼성 인터넷 APK를 수정하지 않으며 root,
Shizuku, 무인 설치, 설치 출처 extra 또는 APK URI 전달을 사용하지 않는다.

## 전체 합격 조건

- `파일 앱 열기`가 chooser나 서드파티 파일 앱이 아니라 Google
  DocumentsUI의 다운로드 화면을 연다.
- top task의 launch origin이 manager이고 Intent action·category가 각각
  `android.intent.action.VIEW_DOWNLOADS`,
  `android.intent.category.DEFAULT`이며 component package가 Google
  DocumentsUI다.
- 설치본의 installer와 initiating package가 모두
  `com.google.android.packageinstaller`다.
- originating package가 `null`이고 `packageSource=3`이다.
- 설치된 APK의 package, SHA-256과 signer SHA-256이 위 기준 신원과 같다.
- Samsung Internet 로그의 해당 package type이 `dev_ext`다.
- Add-ons 목록과 상세 화면에 `작동 안 함`, `지원 안 함` 또는 다른 지원 불가
  표시가 없다.
- 확장을 활성화할 수 있고 스마트 컨텍스트 메뉴의 실제 텍스트 선택 동작이
  실행된다.
- Samsung Internet을 완전히 종료하고 다시 시작한 뒤에도 활성 상태와 기능이
  유지된다.

위 조건 중 하나라도 확인되지 않으면 불합격이다. 화면을 보지 않은 수동
항목은 합격이 아니라 `사용자 확인 필요`로 기록한다.

## 1. 관리자 검증 빌드 준비

저장소 루트에서 다음 블록을 한 shell에서 순서대로 실행한다. 블록은
`set -euo pipefail`을 사용하며, 기기 상태와 model을 exact 값으로 먼저
검증한다. build, connected test, fresh XML 또는 exit code 검증 중 하나라도
실패하면 `adb install -r`에 도달하지 않는다.

```bash
set -euo pipefail

SERIAL='192.0.2.10:39359'
PACKAGE='dev.sixbridge.ext.ddcpmlcaanjillmjmnlmngimhbbojgam'
EXPECTED_MODEL='SM-F966N'
MANAGER_APK="$PWD/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
TEST_RESULTS_DIR="$PWD/android-manager/build/outputs/androidTest-results/connected/debug"

DEVICE_STATE="$(adb -s "$SERIAL" get-state | tr -d '\r')"
if [[ "$DEVICE_STATE" != 'device' ]]; then
  printf '불합격: adb state=%q (expected device)\n' "$DEVICE_STATE" >&2
  exit 1
fi

DEVICE_MODEL="$(
  adb -s "$SERIAL" shell getprop ro.product.model |
    tr -d '\r'
)"
if [[ "$DEVICE_MODEL" != "$EXPECTED_MODEL" ]]; then
  printf '불합격: model=%q (expected %s)\n' \
    "$DEVICE_MODEL" "$EXPECTED_MODEL" >&2
  exit 1
fi

EVIDENCE_ROOT="$PWD/.local/evidence"
RUN_ID="$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$EVIDENCE_ROOT"
EVIDENCE_DIR="$(
  mktemp -d \
    "$EVIDENCE_ROOT/${RUN_ID}-system-documents-install-gate.XXXXXX"
)"
printf 'SERIAL=%s\nMODEL=%s\n' "$SERIAL" "$DEVICE_MODEL" |
  tee "$EVIDENCE_DIR/device.txt"
printf 'EVIDENCE_DIR=%s\n' "$EVIDENCE_DIR"

ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME="$PWD/.local/gradle-home" \
gradle --no-daemon :android-manager:assembleDebug 2>&1 |
  tee "$EVIDENCE_DIR/manager-build.log"

shasum -a 256 "$MANAGER_APK" |
  tee "$EVIDENCE_DIR/manager-debug.sha256"

TEST_STARTED_MARKER="$EVIDENCE_DIR/connected-test.started"
: > "$TEST_STARTED_MARKER"
ANDROID_SERIAL="$SERIAL" \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME="$PWD/.local/gradle-home" \
gradle --no-daemon :android-manager:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.sixbridge.manager.navigation.FileManagerNavigatorInstrumentedTest,dev.sixbridge.manager.install.ManualApkExportInstrumentedTest,dev.sixbridge.manager.ui.ManagerAppTest \
  2>&1 |
  tee "$EVIDENCE_DIR/connected-test.log"

TEST_XML=''
TEST_XML_COUNT=0
while IFS= read -r candidate; do
  TEST_XML="$candidate"
  TEST_XML_COUNT=$((TEST_XML_COUNT + 1))
done < <(
  find "$TEST_RESULTS_DIR" -maxdepth 1 -type f \
    -name 'TEST-SM-F966N -*_android-manager-.xml' \
    -newer "$TEST_STARTED_MARKER" -print
)
if [[ "$TEST_XML_COUNT" -ne 1 ]]; then
  printf '불합격: fresh Fold7 XML count=%s (expected 1)\n' \
    "$TEST_XML_COUNT" >&2
  exit 1
fi

TEST_EXIT_FILE="$TEST_RESULTS_DIR/test-result-exit-code.txt"
if [[ ! -f "$TEST_EXIT_FILE" || ! "$TEST_EXIT_FILE" -nt "$TEST_STARTED_MARKER" ]]; then
  printf '불합격: fresh test-result-exit-code.txt가 없습니다.\n' >&2
  exit 1
fi
TEST_EXIT_CODE="$(tr -d '[:space:]' < "$TEST_EXIT_FILE")"
if [[ "$TEST_EXIT_CODE" != '0' ]]; then
  printf '불합격: connected test exit code=%q (expected 0)\n' \
    "$TEST_EXIT_CODE" >&2
  exit 1
fi

TEST_SUMMARY="$(sed -n '2p' "$TEST_XML")"
if [[ "$TEST_SUMMARY" != *'tests="42"'* ||
      "$TEST_SUMMARY" != *'failures="0"'* ||
      "$TEST_SUMMARY" != *'errors="0"'* ||
      "$TEST_SUMMARY" != *'skipped="0"'* ]]; then
  printf '불합격: connected test XML summary=%s\n' "$TEST_SUMMARY" >&2
  exit 1
fi
cp "$TEST_XML" "$EVIDENCE_DIR/connected-test.xml"
cp "$TEST_EXIT_FILE" "$EVIDENCE_DIR/connected-test-exit-code.txt"

INSTALL_OUTPUT="$(
  adb -s "$SERIAL" install -r "$MANAGER_APK" 2>&1 |
    tee "$EVIDENCE_DIR/manager-install.log"
)"
INSTALL_RESULT="$(
  printf '%s\n' "$INSTALL_OUTPUT" |
    tail -n 1 |
    tr -d '\r'
)"
if [[ "$INSTALL_RESULT" != 'Success' ]]; then
  printf '불합격: adb install result=%q (expected Success)\n' \
    "$INSTALL_RESULT" >&2
  exit 1
fi
```

블록이 고르는 fresh instrumentation XML은 42 tests, 0 failures, 0 errors,
0 skipped이고 같은 실행에서 생성된 `test-result-exit-code.txt`가 `0`이어야
한다. XML, exit code, build/test/install 로그와 manager APK hash는 새
`$EVIDENCE_DIR`에 복사하거나 기록한다.
`androidx.test.services` package UID가 없다는 `appops` 보조 명령 경고만으로
실패 처리하지 않고 XML과 exit code를 함께 판정한다.

`get-state`가 exact `device`, model이 exact `SM-F966N`, 설치 결과 마지막
행이 exact `Success`이고 debug APK SHA-256이 실행 기록에 남아야 합격이다.
기기·model 검증은 connected test보다 먼저 끝나며, instrumentation Gradle
명령·fresh XML·fresh exit code 검증은 `adb install -r`보다 먼저 끝난다.
잘못된 기기, model 불일치, build/test/XML/exit code 실패에서는 manager APK를
설치하거나 사용자 설치 단계로 진행하지 않는다.

## 2. 래퍼 APK 내보내기와 DocumentsUI 실행

다음 단계는 Fold7 화면에서 사용자가 직접 수행한다.

1. SIX Bridge의 스마트 컨텍스트 메뉴 변환 결과에서 extension ID와 wrapper
   package가 위 기준 신원과 같은지 확인한다.
2. review의 APK SHA-256과 signer SHA-256이 위 기준값과 같은지 확인한다.
3. `설치 APK 내보내기`를 눌러 Android 문서 저장 화면에서 APK를
   `Downloads`에 저장한다.
4. `파일 앱 열기`를 누른다.
5. 열린 화면이 방금 저장한 APK가 보이는 Google DocumentsUI 다운로드
   화면인지 확인한다.

`파일 앱 열기`를 누른 직후 화면을 켠 상태에서 다음 읽기 전용 명령을
실행한다. `dumpsys activity activities`의 display 0은 task를 위에서 아래
순서로 출력하므로 `awk`는 첫 번째 top task block만 남긴다.

```bash
: "${EVIDENCE_DIR:?1단계에서 새 evidence dir를 먼저 생성하세요.}"

adb -s "$SERIAL" shell dumpsys activity activities |
  awk '
    /Display #0 \(activities from top to bottom\):/ {
      inDisplay = 1
      next
    }
    inDisplay && /^  \* Task/ {
      if (seenTask) exit
      seenTask = 1
    }
    inDisplay && seenTask {
      print
    }
  ' |
  rg 'Task\{|launchedFromPackage=|Intent \{|state=' |
  tee "$EVIDENCE_DIR/documentsui-top-task.txt"
```

Fold7 Android 16에서는 다음 필드 형식으로 출력된다. class 이름은 OS
업데이트에 따라 달라질 수 있으므로 package를 판정한다.

```text
* Task{... I=com.google.android.documentsui/... visible=true ...}
launchedFromUid=... launchedFromPackage=dev.sixbridge.manager ...
Intent { act=android.intent.action.VIEW_DOWNLOADS cat=[android.intent.category.DEFAULT] ... cmp=com.google.android.documentsui/... }
state=RESUMED ...
```

top task 하나에서 다음 조건을 모두 만족하고 화면에도 다운로드 목록이 보이면
합격이다.

- `visible=true`이고 Activity `state=RESUMED`다.
- `launchedFromPackage=dev.sixbridge.manager`다.
- `Intent`에 `act=android.intent.action.VIEW_DOWNLOADS`가 있다.
- 같은 `Intent`에
  `cat=[android.intent.category.DEFAULT]`가 있다.
- 같은 `Intent`의 `cmp=` package가
  `com.google.android.documentsui`다.

`adb shell am start -n`으로 DocumentsUI component를 직접 지정해 같은 화면을
만드는 것은 이 게이트를 대체하지 않는다. 이 경우 Fold7 출력의
`launchedFromPackage`는 `com.android.shell`이므로 불합격이다. 과거에 남은
STOPPED task나 top task가 아닌 DocumentsUI task, action·category가 없는
launch, chooser, Solid Explorer 등 서드파티 파일 앱,
`dev.sixbridge.manager` 화면 또는 Samsung Internet 화면도 불합격이다.

## 3. 사용자 승인 설치와 manager 판정

1. DocumentsUI의 다운로드 목록에서 방금 저장한 APK를 직접 누른다.
2. Android 설치 화면에서 앱 이름과 package를 다시 확인한다.
3. 사용자가 `설치` 또는 `업데이트`를 직접 승인한다.
4. 설치 완료 뒤 SIX Bridge로 돌아와 해당 항목의 `설치 상태 확인`을 누른다.
5. manager가 trusted install 완료를 표시하는지 확인한다.

사용자가 설치를 취소했거나 manager가
`설치됨 · 삼성 인터넷 지원 미확인`을 표시하면 합격으로 해석하지 않는다.
ADB `install`, manager의 APK URI 전달 또는 다른 파일 앱을 이용해 이 단계를
대체하지 않는다.

## 4. 설치 출처와 설치 세대 확인

사용자 승인 직후 다음 명령을 실행한다.

```bash
: "${EVIDENCE_DIR:?1단계에서 새 evidence dir를 먼저 생성하세요.}"

adb -s "$SERIAL" shell dumpsys package "$PACKAGE" |
  rg 'installerPackageName|initiatingPackageName|originatingPackageName|packageSource|lastUpdateTime' |
  tee "$EVIDENCE_DIR/install-source.txt"
```

합격값은 다음과 같다.

```text
installerPackageName=com.google.android.packageinstaller
initiatingPackageName=com.google.android.packageinstaller
originatingPackageName=null
packageSource=3
```

`lastUpdateTime`은 이번 사용자 승인 뒤의 시각이어야 한다. Solid Explorer 등
서드파티 package, `dev.sixbridge.manager` 또는 다른 originating package가
기록되면 불합격이다. installer나 initiating package가 다르거나
originating package가 null이 아니어도 불합격이다.

## 5. 설치 APK 바이트와 signer 확인

설치된 `base.apk`를 읽기 전용으로 회수해 기준값과 비교한다.

```bash
: "${EVIDENCE_DIR:?1단계에서 새 evidence dir를 먼저 생성하세요.}"
if [[ ! -d "$EVIDENCE_DIR" ]]; then
  printf '불합격: evidence dir가 없습니다: %s\n' "$EVIDENCE_DIR" >&2
  exit 1
fi
INSTALLED_APK="$EVIDENCE_DIR/installed-base.apk"
if [[ -e "$INSTALLED_APK" ]]; then
  printf '불합격: 현재 실행 evidence에 APK가 이미 있습니다: %s\n' \
    "$INSTALLED_APK" >&2
  exit 1
fi

DEVICE_APK="$(
  adb -s "$SERIAL" shell pm path "$PACKAGE" |
    sed -n 's/^package://p' |
    tr -d '\r'
)"
if [[ -z "$DEVICE_APK" ]]; then
  printf '불합격: 설치된 package APK 경로를 찾지 못했습니다.\n' >&2
  exit 1
fi

adb -s "$SERIAL" pull "$DEVICE_APK" "$INSTALLED_APK" 2>&1 |
  tee "$EVIDENCE_DIR/installed-base-pull.log"

shasum -a 256 "$INSTALLED_APK" |
  tee "$EVIDENCE_DIR/installed-base.sha256"

/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/apksigner \
  verify --print-certs "$INSTALLED_APK" |
  tee "$EVIDENCE_DIR/installed-base-apksigner.txt" |
  rg 'Signer #1 certificate SHA-256 digest'

/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/apkanalyzer \
  manifest application-id "$INSTALLED_APK" |
  tee "$EVIDENCE_DIR/installed-base-application-id.txt"
```

출력 package가 `$PACKAGE`, APK SHA-256과 signer SHA-256이 기준값과 정확히
같아야 합격이다. 내보내기 전 review 값, 기존 기준 APK 또는 설치된 APK 중
어느 하나라도 SHA-256이나 signer가 다르면 불합격이다.

`.local/evidence/`는 실행 증거 보관 경로이며 Git에 추가하지 않는다. 고정
파일이나 과거 디렉터리를 재사용하지 않는다. 1단계의 timestamp `RUN_ID`와
`mktemp -d` suffix로 만든 새 `$EVIDENCE_DIR`에만 이번 실행의 pull, hash,
signer, XML과 로그를 기록해 기존 증거를 보존한다.

## 6. Samsung Internet `dev_ext` 판정

설치 출처와 바이트 신원 확인 뒤 다음 명령을 실행한다. 기존 logcat을 지우지
않고 기기 시각을 경계로 기록한 뒤 그 시각 이후의 로그만 읽는다.

```bash
: "${EVIDENCE_DIR:?1단계에서 새 evidence dir를 먼저 생성하세요.}"

LOG_START="$(
  adb -s "$SERIAL" shell "date '+%m-%d %H:%M:%S.%3N'" |
    tr -d '\r'
)"
if [[ -z "$LOG_START" ]]; then
  printf '불합격: logcat 시작 시각을 얻지 못했습니다.\n' >&2
  exit 1
fi
printf 'LOG_START=%s\n' "$LOG_START" |
  tee "$EVIDENCE_DIR/log-start.txt"

adb -s "$SERIAL" shell am force-stop com.sec.android.app.sbrowser
adb -s "$SERIAL" shell am start -W \
  -a android.intent.action.VIEW \
  -d 'internet://extension/' \
  -p com.sec.android.app.sbrowser
LOGCAT_FILE="$EVIDENCE_DIR/samsung-internet-logcat.txt"
adb -s "$SERIAL" logcat -d -T "$LOG_START" -v threadtime > "$LOGCAT_FILE"
rg "SixAppDataUpdater|SixPkgUpdater|$PACKAGE|dev_ext" "$LOGCAT_FILE" |
  tee "$EVIDENCE_DIR/samsung-internet-relevant-log.txt"

rg "$PACKAGE.*dev_ext" "$LOGCAT_FILE" |
  tee "$EVIDENCE_DIR/dev-ext-match.txt"
```

`LOG_START`가 비어 있지 않고 마지막 명령이 대상 `$PACKAGE`와 `dev_ext`를
같은 행에서 찾아야 합격이다. 다른 확장의 `dev_ext` 로그로 대신 판정하지
않는다. 대상 행의 type이 `3rd_party`이거나 시각 경계 뒤 대상 행이 없으면
불합격이다. `logcat -c`는 기존 진단 증거를 삭제하므로 이 게이트에서 실행하지
않는다.

## 7. Add-ons와 실제 기능 수동 확인

다음 항목은 사용자가 Fold7 UI에서 모두 확인한다.

1. Samsung Internet `메뉴 → 추가 기능`에서 스마트 컨텍스트 메뉴를 찾는다.
2. 목록과 상세 화면에 `작동 안 함`, `지원 안 함` 또는 지원 불가 표시가
   없는지 확인한다.
3. 확장을 활성화한다.
4. 일반 HTTPS 페이지에서 텍스트를 선택하고 스마트 컨텍스트 메뉴의 검색·복사
   동작이 실제로 실행되는지 확인한다.
5. Samsung Internet을 최근 앱에서 완전히 닫고 다시 실행한다.
6. Add-ons에서 활성 상태가 유지되는지 확인하고 같은 텍스트 선택 동작을 다시
   실행한다.

모든 항목의 시각, 화면 메시지와 캡처 파일명을 증거에 기록한다. 하나라도
직접 확인하지 않았거나 불명확하면 `사용자 확인 필요`이며 전체 게이트는 아직
합격이 아니다.

## 8. Samsung My Files 대체 회귀

Fold7의 시스템 문서 앱을 비활성화하거나 제거해 대체 경로를 만들지 않는다.
다음 단위 테스트와 기존 게이트 증거로만 회귀를 확인한다.

```bash
: "${EVIDENCE_DIR:?1단계에서 새 evidence dir를 먼저 생성하세요.}"

ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME="$PWD/.local/gradle-home" \
gradle --no-daemon :android-manager:testDebugUnitTest \
  --tests 'dev.sixbridge.manager.navigation.FileManagerNavigatorTest' \
  --tests 'dev.sixbridge.manager.install.SamsungInstallSourcePolicyTest' \
  --tests 'dev.sixbridge.manager.install.SystemInstallCoordinatorTest' \
  2>&1 |
  tee "$EVIDENCE_DIR/fallback-unit-tests.log"
```

테스트가 모두 통과하고 다음 계약이 유지되어야 합격이다.

- `viewDownloadsHandlers = { emptyList() }`일 때 production fallback
  component는 Samsung My Files launcher다.
- fallback Intent에는 APK URI, MIME type, extra, `ClipData` 또는 URI grant가
  없다.
- Google Package Installer와 Samsung My Files origin 조합은 trusted
  install로 판정된다.
- 기존 실기기 절차와 증거는 `docs/FOLD7_MYFILES_INSTALL_GATE_KO.md`의
  `installer=com.google.android.packageinstaller`,
  `originating=com.sec.android.app.myfiles`, `classification=dev_ext`,
  `wrapperByteIdentity=matched` 계약을 유지한다.

## 과거 실기기 증거와 새 실행의 구분

2026-07-30
`.local/evidence/20260730-185159-documentsui-install-gate/결과.md`에는 Google
DocumentsUI에서 위 고정 package를 설치한 뒤 installer와 initiating package가
Google Package Installer, originating package가 null, `packageSource=3`이고
Samsung Internet이 `dev_ext`로 분류한 과거 결과가 보존되어 있다.

이 증거는 DocumentsUI 경로의 타당성과 기준값을 뒷받침하지만, 현재 manager
검증 빌드에서 2·3·7단계를 다시 수행했다는 증거는 아니다. 새 빌드의
DocumentsUI 실행, 사용자 승인, Add-ons 활성화·기능·재시작 유지는 실행마다
별도 확인한다.

## 명시적 불합격 조건

다음 중 하나라도 발생하면 즉시 불합격으로 기록하고 우회 설치하지 않는다.

- Solid Explorer 등 서드파티 originating package
- manager package `dev.sixbridge.manager` origin
- Samsung Internet의 `3rd_party` 판정
- Add-ons의 `작동 안 함`, `지원 안 함` 또는 지원 불가 표시
- 기준 APK와 다른 SHA-256 또는 signer SHA-256
- chooser나 임의 파일 앱을 기본 설치 경로로 노출
- top task의 `VIEW_DOWNLOADS`·`DEFAULT`·DocumentsUI component 또는
  `launchedFromPackage=dev.sixbridge.manager` 증거 부재
- `adb shell am start -n`으로 DocumentsUI 화면을 직접 만든 결과를 manager
  launch 증거로 사용
- APK URI, `ClipData`, URI grant 또는 originating package extra를 manager
  handoff에 포함
- 사용자 승인 없이 ADB, root, Shizuku 또는 무인 설치로 wrapper를 설치

실패 시 wrapper, manager app data, `.local/` 키와 증거를 삭제하지 않는다.
현재 화면과 명령 출력을 보존한 뒤 원인을 기록한다.

## 2026-07-30 최신 관리자 빌드 실행 결과

다음 최신 관리자 빌드로 이 문서의 수동 게이트를 다시 실행했다.

- Git commit: `0c9a4cb`
- manager debug APK SHA-256:
  `d0388aa6f945be01f3430ad4005e72f993b7267cd5a6ce2bb3b23dc8bd39e7e8`
- evidence:
  `.local/evidence/20260730-221316-system-documents-latest-gate`
- 가져온 `.six`: `SIXBridge-exact-ddcp.six`
- 내보낸 APK:
  `스마트 컨텍스트 메뉴-1.1.1-ddcpml (3).apk`
- wrapper package:
  `dev.sixbridge.ext.ddcpmlcaanjillmjmnlmngimhbbojgam`
- wrapper APK SHA-256:
  `8a0b44558e22d16deaf7a92e8e8119629cadbfcc88dbbd8dae160fb41b30a6c2`
- wrapper signer SHA-256:
  `55f73d78f4597a14039162a63985a831766e402e3910bbb44b3c71d8740c0c0f`

`파일 앱 열기` 뒤 최상위 task는 다음 계약을 모두 만족했다.

- component package: `com.google.android.documentsui`
- action: `android.intent.action.VIEW_DOWNLOADS`
- category: `android.intent.category.DEFAULT`
- `launchedFromPackage=dev.sixbridge.manager`
- Activity state: `RESUMED`

사용자가 Google DocumentsUI 다운로드 화면에서 APK를 직접 선택하고 설치를
승인했다. 설치 결과는 다음과 같다.

```text
installerPackageName=com.google.android.packageinstaller
initiatingPackageName=com.google.android.packageinstaller
originatingPackageName=null
packageSource=3
```

설치된 `base.apk`는 내보낸 APK와 byte 단위로 같았고 SHA-256과 signer도
일치했다. Samsung Internet은 해당 package를 `dev_ext`와
`Allowed to be supported`로 판정했다. 사용자는 같은 이름의 기존 테스트
확장을 비활성화하고 새 `ddcp…` 확장만 활성화한 뒤 일반 웹 페이지의 텍스트
선택 기능이 동작하는 것을 확인했다.

Samsung Internet을 완전히 종료하고 Add-ons 화면으로 다시 시작한 뒤에도
다음 상태가 유지됐다.

```text
stored:ddcpm,dev.sixbridge.ext.ddcpmlcaanjillmjmnlmngimhbbojgam,스마트 컨텍스트 메뉴,1.1.1,dev_ext,Instd,0,ON
```

사용자는 재시작된 브라우저에서 페이지를 새로고침한 뒤 텍스트 선택 기능이
다시 정상 동작하는 것도 확인했다. 따라서 최신 관리자 빌드의 Google
DocumentsUI 실행, 설치 출처, wrapper 신원, `dev_ext`, 활성화·기능·재시작
유지 수동 게이트는 합격이다.
