# Fold7 삼성 내 파일 설치 게이트

## 목적

이 절차는 SIX Bridge가 만든 래퍼를 삼성 `내 파일`에서 사용자가 직접
설치했는지 검증한다. gate는 설치를 대신하지 않는다. 특히 `adb install`,
`PackageInstaller` session, APK URI를 연 installer Intent로 수동 승인 단계를
우회하지 않는다.

지원 기준 기기는 Fold7 `SM-F966N`, 기준 브라우저는 Samsung Internet
30.0.2.61이다.

## `.six`와 APK의 역할

- `.six`는 변환 결과의 보관·공유·백업·전송 파일이다. `wrapper.apk`,
  `extension.json`, `compatibility.json`을 함께 보존한다. `.six` 자체를
  Android installer에서 열지 않는다.
- APK는 `.six` 안의 검증된 `wrapper.apk` 바이트를 그대로 내보낸 설치
  파일이다. 재패키징하거나 다시 서명하지 않는다. 삼성 `내 파일`에서
  사용자가 직접 선택할 때만 설치에 사용한다.
- `pre` gate가 출력하는 `preparedApk`는 비교용 기준 APK다. 제품 흐름에서는
  SIX Bridge의 `설치 APK 내보내기`로 Downloads에 저장한 APK를 사용한다.
  두 APK의 SHA-256은 같아야 한다.

## 준비

1. Fold7에서 `개발자 옵션 > 무선 디버깅`을 켜고 host의 `adb devices -l`에서
   상태가 `device`, model이 `SM-F966N`인 serial 하나만 선택한다.
2. 다음 도구를 준비한다.

   - `adb`
   - `jq`
   - `rg`
   - `shlock`
   - `zipinfo`, `unzip`
   - Android SDK 36의 `apkanalyzer`, `apksigner`

3. manager debug APK를 최신 코드로 빌드한다.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
GRADLE_USER_HOME="$PWD/.local/gradle-home" \
gradle :android-manager:assembleDebug
```

4. 검증할 `.six`의 절대 경로와 예상 wrapper package를 확인한다. wrapper
   package 형식은 `dev.sixbridge.ext.<32자 extension ID>`다.

## 사용자 절차 8단계

1. SIX Bridge에서 `.six`를 가져오거나 Fold7에서 온디바이스 변환을 완료한다.
2. 검토 화면에서 `설치 APK 내보내기`를 누른다.
3. Android 문서 저장 화면에서 APK를 `Downloads`에 저장한다.
4. SIX Bridge의 `삼성 내 파일 열기`를 눌러 삼성 `내 파일` 런처를 연다.
5. 삼성 `내 파일`의 Downloads에서 방금 저장한 APK를 직접 선택한다.
6. Android 설치 화면에서 설치 또는 업데이트를 사용자가 승인한다.
7. SIX Bridge로 돌아와 정확한 항목의 `설치 상태 확인`을 누른다.
8. Samsung Internet의 추가 기능 화면에서 `지원 안 함` 표시가 없고 확장을
   활성화할 수 있는지 확인한다.

4~6단계에서 다른 파일 관리자, 브라우저 다운로드 화면이나 host ADB로 APK를
열거나 설치하면 정상 My Files origin 증거가 남지 않는다.

## Gate 실행

사용자 설치 전에 `pre`를 한 번 실행한다.

```bash
export SIXBRIDGE_ADB_SERIAL='192.0.2.10:39359'
export SIXBRIDGE_TEST_BUNDLE='/absolute/path/to/result.six'

scripts/fold7-myfiles-install-gate.sh pre \
  "$SIXBRIDGE_ADB_SERIAL" \
  "$SIXBRIDGE_TEST_BUNDLE"
```

`pre`는 다음 항목을 확인하고 `.local/evidence/<시각>-myfiles-install-gate/`에
baseline을 기록한다.

- manager final manifest에 `REQUEST_INSTALL_PACKAGES`와
  `dev.sixbridge.manager.install` authority가 없음
- manager final manifest에 `com.sec.android.app.myfiles` query가 있음
- `SystemInstallCoordinator`, `DefaultManagerRepository` dex에 직접 installer
  session·APK `ACTION_VIEW`·gateway 참조가 없음
- `.six`에 symlink, 중복 entry, 경로 이탈이 없고 정확한 세 entry만 있음
- wrapper package, `versionCode`, signer, extension ID와 SHA-256
- pre 시점 대상 package의 설치 유무, `lastUpdateTime`, `codePath`,
  `base.apk` 경로·inode/크기/mtime
- `pm path` 실패가 단순 미설치인지 `dumpsys package`의 정확한
  `Unable to find package` 결과로 교차 확인
- 고유 nonce가 붙은 `SixBridgeMyFilesGate` log marker의 sub-second epoch와
  비교용 `preparedApk`

`pre` 성공 뒤 위 사용자 절차 2~6단계를 수행한다. 사용자가 설치 또는
업데이트를 승인하기 전에는 `post`를 실행하지 않는다. 설치 뒤 `pre`를 다시
실행하면 새 설치 세대가 baseline이 되므로 반드시 설치 전에 실행한 `pre`를
그대로 사용한다.

```bash
scripts/fold7-myfiles-install-gate.sh post \
  "$SIXBRIDGE_ADB_SERIAL" \
  dev.sixbridge.ext.ddcpmlcaanjillmjmnlmngimhbbojgam
```

합격 출력은 다음 다섯 값을 모두 포함한다.

```text
installer=com.google.android.packageinstaller
originating=com.sec.android.app.myfiles
classification=dev_ext
support=Allowed to be supported
wrapperByteIdentity=matched
```

`post`는 같은 serial의 마지막 성공 `pre`만 사용한다. pre와 post는 serial별
lock으로 직렬화한다. lock의 로컬 host, PID, 실행별 token을 함께 검증한다.
살아 있는 owner나 소유권을 확정할 수 없는 lock은 거부하고, 종료된 로컬
owner의 stale lock만 `shlock`으로 원자 복구한다. 설치된 `base.apk`를 회수해
package, `versionCode`, signer, extension ID와 SHA-256을 baseline과 비교하며,
대상이 pre 때 없었다가 생겼거나 `lastUpdateTime`, `codePath`, `base.apk`
stat 중 하나 이상이 달라졌는지 확인한다. 기존 설치 세대가 그대로면 같은
APK와 My Files origin이 있어도 합격하지 않는다.

삼성 로그는 고유 pre marker보다 뒤에 있어야 한다. `pre`가 marker를 기록한
직후 같은 logcat에서 캡처한 `pre-log-marker.txt`는 symlink가 아닌 일반 파일의
LF-종결 단일 `logcat -v epoch` record여야 한다. CR, NUL, 추가 record 또는
추가 byte를 거부하고, record 전체의 sub-second epoch·decimal PID/TID·`I`·tag·
baseline nonce가 정확히 일치해야 한다. `post` 시점 ring buffer에서 marker
줄이 rollover됐더라도 이 저장 증거를 경계로 사용하며, 현재 로그의 timestamp가
`preLogEpoch`보다 큰지 검사한다. 첫 줄은 정확한 target package, 둘째 줄은
정확한 extension ID를 포함해야 하므로 다른 확장의 일반적인 성공 로그로
대신할 수 없다. 저장 marker가 없거나 변조됐거나, target 로그가 경계 이전이면
fail-closed 처리한다. Samsung Internet Add-ons와 wrapper `six://ex` discovery
resolve 결과도 증거 디렉터리에 저장한다.

```text
SixPkgUpdater: isDevSixPkg - MyFiles case
SixAppDataUpdater: updateSixAppData - Allowed to be supported
```

installer와 initiating package는
`com.google.android.packageinstaller`, originating package는
`com.sec.android.app.myfiles`, `packageSource`는 Fold7 사용자 설치 값 `3`이어야
한다.

계약 또는 증거 불일치는 exit code 3, 잘못된 사용법이나 필수 도구 누락은 exit
code 2다. 실패한 `pre`는 직전 성공 pointer를 변경하지 않는다. 실패한 `post`는
같은 설치의 증거를 다시 수집할 수 있도록 pointer를 남긴다. 성공한 `post`는
`post-summary.txt`와 `post-completed.txt`를 각각 임시 파일에서 원자
게시한 뒤 마지막 단계로 pointer를 원자 소비하므로, 완료 증거 기록 실패 시
같은 pointer로 재시도할 수 있고 같은 `pre`로 두 번째 합격을 만들 수 없다.
같은 serial에서 gate가 이미 실행 중이면 종료를 기다린 뒤 다시 실행한다.
직전 gate가 강제 종료됐다면 `shlock`의 짧은 race 보호 구간이 지난 뒤
재시도하면 검증된 stale lock을 복구한다.

실패한 설치를 성공으로 해석하지 말고 같은 APK를 삼성 `내 파일`에서 다시
선택해 사용자 승인을 반복한다. 재설치 전에 새 `pre`를 실행한다.

## 수동 회귀 확인

gate 합격 뒤 다음 항목은 사람이 Fold7 UI에서 확인한다.

- Samsung Internet 추가 기능 목록에 대상 확장의 `지원 안 함` 표시가 없다.
- 확장을 활성화한 뒤 일반 HTTPS 페이지에서 텍스트 선택 동작이 실행된다.
- 설정 변경과 활성 상태가 Samsung Internet 재시작 뒤 유지된다.
- SIX Bridge 관리자 목록의 호환성 보고서, 아이콘과 제거 기능이 정상이다.
- 기존 AI 레시피, Video Download to NAS, 스마트 컨텍스트 메뉴의 package와
  설치 APK SHA-256이 검증 전 기준값에서 바뀌지 않았다.
- 같은 APK의 업데이트를 다시 삼성 `내 파일`에서 승인해도 My Files origin과
  확장 신원이 유지된다.

수동 회귀 결과와 화면 증거는 gate evidence 디렉터리 옆에 별도 기록한다.
