# Fold7 SIX Bridge 관리자 검증표

## 기록 원칙

- `[x]`는 해당 명령이나 사용자 절차를 실제로 실행하고 증거를 확인한
  경우에만 표시한다.
- `[ ]`는 미실행, 실패 또는 증거 미확보 상태다.
- 자동 검증과 Fold7 사용자 수동 검증을 서로 대체하지 않는다.
- 실패 항목은 체크하지 않고 재현 절차·관찰 결과·다음 조치를 기록한다.
- 개인정보, token, 원본 URI, signing key와 파일 bytes는 증거에 포함하지 않는다.
- `.local/evidence/`의 원시 증거는 Git에 커밋하지 않는다.

## 기기와 산출물

- 기기 모델: `SM-F966N` (Fold7)
- Android 버전: Android 16 (API 36)
- 삼성 인터넷 버전: `30.0.0.67` (versionCode `1300067502`)
- 관리자 APK: `dist/sixbridge-manager-debug.apk`
- 최종 수정 관리자 APK SHA-256:
  `af3b469e2ef59d47544f5d889ea99d49b9e49b2cf5ab706a4fc16d4a3d0da395`
- 과거 2026-07-27 아이콘 동시성 최종 관리자 APK SHA-256:
  `0b77f88516f58d3153a9769e459e6afba4b243160f16dc987571fad3db37c905`
- 과거 2026-07-27 1차 아이콘 동시성 관리자 APK SHA-256:
  `c005580f1a69b28576ec6e9aa3f5ba483f50d5ee26d213b697d45260fc22977c`
- 과거 2026-07-26 Task 5 관리자 APK SHA-256:
  `574b0bd4b7fbb267ee400d3ea43559c605d30b7055d9814502e3cd9ae65f5704`
- 과거 자동 검증 일자: 2026-07-26
- 아이콘 동시성 최종 자동 검증 일시: 2026-07-27 09:18~09:22 KST
- 관리자 launcher icon 보정 검증 일시: 2026-07-27 11:33~11:56 KST
- 아이콘 동시성 최종 Fold7 serial:
  `adb-DEVICE_SERIAL._adb-tls-connect._tcp`
- 개발용 무선 ADB 검증 일시: 2026-07-26 15:40 KST
- 개발용 증거:
  `.local/evidence/20260726-154314-android-manager-phase1.log`
- Task 5 제거·아이콘 증거:
  `.local/evidence/20260726-182158-manager-uninstall-icons.log`
- Task 5 Home screenshot:
  `.local/evidence/manager-uninstall-icons-home.png`
  (`107e57b98f71f0e29cc291e2a25c63850e45e74082ed45af8d224a1aee6666da`)
- Fold7 Task 5 실행 일시: 2026-07-26 18:18~18:37 KST
- 실행자: 사용자(제거 확인·취소·승인), Codex(설치·읽기 전용 증거)

## 자동 검증

아래 항목은 host에서 실행하는 사전 검증이다. Fold7 최종 사용자 흐름의
성공을 의미하지 않는다.

- [x] `./scripts/verify.sh`
  - 증거: Gradle 74 task 성공, Node 89/89 통과, 기존 shell/mobile conversion
    통과, Android manifest와 distribution 계약 `PASS`
- [x] 아이콘 동시성 지정 회귀
  - 명령 범위: `:android-manager:testDebugUnitTest --rerun-tasks`에
    `ManagerRepositoryTest`, `CatalogReconcilerTest`,
    `CatalogIconEnricherTest`, `UninstallCoordinatorTest`,
    `ManagerViewModelTest` 다섯 class를 `--tests`로 지정했다.
  - 최종 결과: 118/118 통과, failures/errors/skipped 0
- [x] `./scripts/package-manager-debug.sh`
  - 과거 2026-07-26 증거: Gradle 74/74 task, Android unit 269/269,
    Node 89/89, shell·conversion·manifest·distribution 계약 통과 뒤
    `dist/sixbridge-manager-debug.apk` 패키징 완료
  - 과거 2026-07-27 1차 아이콘 동시성 증거: fresh 실행에서 Gradle 74/74
    task,
    Android unit 291/291, Node 89/89, shell·conversion·manifest·verification
    ·distribution 계약을 통과하고 같은 dist APK를 패키징했다.
  - 교차 검토 수정 뒤 최종 fresh 증거: 2026-07-27 09:18 KST부터 실행해
    Gradle 74/74 task, Android unit 297/297, Node 89/89,
    shell·conversion·manifest·verification·distribution 계약을 모두
    통과하고 1분 49초에 dist APK 패키징을 완료했다.
  - 위 1차 검증의 첫 fresh 실행에서는 이전에도 관찰된
    `StableAnalysisTest.all compatibility and mobile decisions read one stable source`
    stack frame sampling 경합이 `StableAnalysisTest.kt:156`에서 한 번
    발생했다. 해당 테스트만 정확히 격리한 fresh 재실행은 1/1 통과했다.
    1차 전체 패키징은 코드 변경 없이 다시 fresh 실행해 2분 3초,
    74/74 task로 완료했다.
  - 위 1차 전체 실행 중 `:core:test`가 오래 대기한 시점의 JVM thread dump는
    `StableAnalysisTest`가 아니라 `WrapperApkBuilderTest`의 외부 process
    종료 대기임을 가리켰고, 해당 process 종료 뒤 빌드가 정상 완료됐다.
- [x] `shasum -a 256 dist/sixbridge-manager-debug.apk`
  - 과거 2026-07-26 Task 5 증거: 패키징 출력과 독립 실행 hash가
    `574b0bd4b7fbb267ee400d3ea43559c605d30b7055d9814502e3cd9ae65f5704`로
    일치하고 fresh build APK와 byte-for-byte 동일
  - 아이콘 동시성 최종 증거: 전체 패키징 재실행 뒤 build와 dist APK가
    `0b77f88516f58d3153a9769e459e6afba4b243160f16dc987571fad3db37c905`로
    일치하고 `cmp` exit code도 `0`
- [x] Git 제외 경계
  - 증거: `git check-ignore -v`로 `dist/`, `**/build/`, Room 임시 schema,
    `local.properties`를 확인하고 canonical Room JSON schema가 계속 추적됨을
    확인
- [x] `:android-manager:connectedDebugAndroidTest`
  - 아이콘 동시성 최종 증거: 2026-07-27 09:21 KST에 지정 serial의
    `SM-F966N`에서 fresh 전체 connected 28/28 통과,
    failures/errors/skipped 0
  - 이 28건에는 Room 9건, UI 15건과
    `ContentResolverProviderStreamOpenerTest` 4건이 포함된다. Provider
    `CancellationSignal` 전달, dispatch 전 취소, 늦게 반환된 stream 종료,
    resume 직후 lease 취소 경합을 실기기에서 확인했다.
  - test APK 설치와 계측만 수행했으며 실제 extension wrapper 제거는
    실행하지 않았다.
  - 과거 최종 증거: 2026-07-26 19:43 KST에 debug test Activity를 비공개로
    병합한 APK로 Fold7에서 16/16 통과, failures/errors/skipped 0
  - 과거 10/10 기록은 아이콘 UI 회귀 테스트가 추가되기 전 부분 실행이며,
    현재 최종 connected 결과를 의미하지 않는다.
- [x] production source·schema·test 전용 Provider 경계
  - 아이콘 동시성 검토 당시 범위인 `git diff 82236be..8dce66a --check`가
    통과했고, 같은 범위에서 production manifest와 canonical Room schema
    diff가 없었다.
  - 이후 사용자 승인 launcher icon 작업의 `5ba91bf`에서 production
    manifest에 `android:icon="@mipmap/ic_launcher"`과
    `android:roundIcon="@mipmap/ic_launcher_round"` 두 속성만 추가됐다.
    현재 `git diff 82236be..HEAD -- android-manager/src/main/AndroidManifest.xml`
    결과도 이 두 승인 속성 외 변경이 없다.
  - canonical Room schema
    `android-manager/schemas/dev.sixbridge.manager.catalog.ManagerDatabase/1.json`
    는 현재까지 diff가 없고 데이터베이스 version은 계속 `1`이다.
  - 사용자 승인으로 `TestBlockingIconProvider`는
    `src/androidTest/java`의 Java 구현과 test manifest의
    `android:exported="true"`로만 존재한다.
  - `apkanalyzer manifest print dist/sixbridge-manager-debug.apk` 결과에는
    위 test Provider class와 authority가 없었다. production manifest와
    배포 APK에는 test-only Provider가 포함되지 않았다.
- [x] 관리자 launcher icon 머티리얼 3D 계약과 전체 패키징
  - 배경 vector는 `108 × 108` 전체 사각형과 Indigo `#6366F1`에서
    Violet `#6D28D9`으로 이어지는 좌상단→우하단 linear gradient 하나만
    사용한다.
  - 컬러 전경은 `108 × 108` viewport 안에서 원본 중심
    `(52.5, 51)`, `0.75`배 축소, `translateX=3`,
    `translateY=3`을 사용한다.
  - 퍼즐 몸체는 반지름 `5` 라운드 경로를 사용하고, 오른쪽 아래
    `translateX=4`, `translateY=4`의 Dark Plum `#2E1065`
    머티리얼 돌출면을 먼저 그린다.
  - 단색 전경은 같은 크기·위치·라운드를 공유하고 머티리얼 돌출면 없이
    `evenOdd` track cutout을 유지한다.
  - 돌출 track 굵기 변이, 단색 중첩 group, 최상위 sibling group과 전경
    `fillAlpha=0` 변이가 각각 계약 테스트에서 실패했다.
  - 배경 gradient의 추가 alpha, built manifest의 잘못된 launcher ID와
    v33 adaptive icon의 잘못된 foreground ID 변이도 각각 계약 테스트에서
    실패했다.
  - 빌드 APK의 `aapt2 dump resources`에서 얻은 launcher·round launcher와
    background·foreground·monochrome resource ID를 `apkanalyzer` manifest
    및 v26·v33 adaptive icon xmltree와 교차 검증했다. v26은
    background·foreground 두 자식, v33은 여기에 monochrome 한 자식만
    순서대로 포함한다.
  - 최종 `./scripts/package-manager-debug.sh`는 Gradle
    `BUILD SUCCESSFUL`, Node 89/89, shell·conversion·manifest·distribution
    계약을 통과했다.
  - 구현·계약 교차 검토는 Critical, Important와 Minor 지적 없이
    `CLEAN`으로 끝났다.

## 관리자 launcher icon 수동 검증

- [x] 보정 APK를 Fold7 기본 사용자에 `adb install -r`로 업데이트했다.
  - 설치 결과: `Success`
  - 관리자: `dev.sixbridge.manager`, versionName `0.1.0`,
    versionCode `1`
  - 업데이트 전후 설치 wrapper는 스마트 컨텍스트 메뉴, AI 레시피 추출기,
    Video Download to NAS와 SIX Bridge Technical Gate의 4개로 같았다.
- [x] 사용자가 Fold7 앱 서랍의 컬러 아이콘을 확인하고 현재 상태를
  1차 승인했다.
  - 적용값: 약 `48dp`, 우측 `1.5dp` 시각 보정, 반지름 `5` 라운드,
    Dark Plum 머티리얼 돌출면
  - 사용자 판단: “이정도면 우선 된 거 같아”
  - 아이콘 미세 조정은 후속 작업으로 남긴다.
- [ ] Android 13+ 색상 팔레트의 단색 테마 아이콘을 이번 보정 APK에서
  다시 확인했다.
  - 이번 사용자 확인은 컬러 아이콘의 1차 승인까지만 포함한다.
  - 미실행 항목을 컬러 아이콘 확인으로 대신해 통과 처리하지 않는다.

## Task 12 개발용 무선 ADB 확인

이 절의 체크는 개발용 설치와 읽기 전용 상태 확인 결과다. 아래 Fold7 완료
조건과 최종 무ADB 사용자 흐름을 대신하지 않는다.

- [x] 지정 Fold7의 모델·Android·삼성 인터넷 버전을 읽었다.
  - 결과: `SM-F966N`, Android 16/API 36, 삼성 인터넷 `30.0.0.67`
- [x] 검증한 debug 관리자 APK를 `adb install -r`로 설치하고 MainActivity를
  cold start했다.
  - 결과: 설치 `Success`, Activity `Status: ok`, 시작 시간 712ms
  - 설치 package: `dev.sixbridge.manager` version `0.1.0`, versionCode `1`
- [x] 관리자 package 권한과 프로세스 오류를 확인했다.
  - 결과: 세 보안 경계 권한 중 `REQUEST_INSTALL_PACKAGES`는 존재하고
    `INTERNET`, `QUERY_ALL_PACKAGES`는 없었다. 관리자 PID의 최근
    logcat에 `FATAL EXCEPTION`과 `AndroidRuntime` 오류가 없었다.
- [x] 공개 확장 discovery query에서 SIX Bridge 규격 Activity 후보 5개를
  확인했다.
  - 결과: 설치 versionCode는 `dncif...` 14, `fdac...` 3, `iefg...` 2,
    `lnea...` 2, `odgob...` 4였다.
  - 관리자의 검증·정렬을 거친 홈 catalog 표시는 아래 별도 항목에서
    확인했다.
- [x] repository와 로컬 산출물에서 설치 정책용 bundle 후보를 조사했다.
  - `compatible` 신규: `fche...` v2 또는 `meem...` v2
  - `partial` 신규: MediaNab `ommj...` v2
  - 증가 versionCode 업데이트 후보: 설치 `odgob...` v4, bundle v8
  - 같은 versionCode 차단 후보: `dnc...` v14/v14, `fdac...` v3/v3,
    `iefg...` v2/v2
  - 위 신규·partial·업데이트·같은 버전 후보 5개의 bundle/CRX3/wrapper
    결합 계약은 `sixbridge verify-build`로 모두 통과했다.
  - 모든 조사 대상 dist bundle의 signer 지문은 같지만 설치 APK signer와의
    실제 정책 비교는 관리자 검토 화면에서 확인해야 한다.
- [ ] 다른 signer의 실기기 차단 fixture를 확인했다.
  - repository, `fixtures/`, `dist/`, `.local/`에서 별도 signer bundle을
    찾지 못했다.
  - 현재 `.local/` 키를 바꾸지 않는다. 후속 생성이 필요하면 격리된 임시
    workspace에 입력과 profile을 두고
    `sixbridge --workspace <격리-workspace> build ...`를 실행해 별도
    `.local/keystore`로 만든 뒤 package·descriptor·signer 계약을 다시
    검증해야 한다.
- [x] 관리자 홈에서 기존 래퍼의 catalog 표시와 제거 버튼을 확인했다.
  - 결과: 공개 discovery로 찾은 래퍼가 카드로 표시되고 각 카드에 `제거`
    동작이 제공됐다.

### 제거 권한 누락과 상태 전파 회귀

- 최초 실기기 제거는 관리자 UI가 아니라 privileged ADB shell이 제거
  intent를 시작한 경로였으며, Android 시스템 제거 화면과 실제 package
  삭제는 정상 동작했다.
  - 대상: `dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd`
  - 증거: `pm path` 결과가 비었고 관리자 목록에서도 카드가 사라졌다.
- 제거 화면 실행 실패의 근본 원인은 관리자 manifest에
  `REQUEST_DELETE_PACKAGES`가 누락된 것이었다. ADB shell과 관리자 UI는
  제거 요청의 호출 주체가 서로 달라 ADB shell 결과만으로 관리자 권한을
  검증할 수 없었다.
- 삭제 직후 관리자 복귀에서는 PackageManager의 이전 설치 상태를 한 번만
  읽어 `제거가 취소되었거나...`라고 잘못 표시하는 보조 회귀가 있었다.
- `DefaultUninstallCoordinator`가 최대 0.8초 동안 실제 package 소멸 조건을
  재확인하도록 수정했다.
  - 회귀 테스트는 첫 조회 `설치됨`, 다음 조회 `없음`을 재현하며 수정 전
    `CancelledOrStillInstalled`, 수정 후 `Removed`를 확인했다.
  - Android unit 219건, lint, assemble, 전체 Gradle 74 task, Node 89건,
    Android manifest·distribution 계약이 통과했다.
- 수정된 관리자 APK를 Fold7에 업데이트하고 조사 중 제거했던 스마트
  컨텍스트 메뉴 래퍼를 같은 APK로 복구했다.
- 수정 후 제거 성공과 목록 정리는 아래 Task 5 사용자 절차와 package
  재조회로 다시 확인했다.

### Task 5 제거 수동 재검증

- [x] 비활성 스마트 컨텍스트 메뉴 v2의 표준 제거 화면에서 취소했다.
  - 사용자 확인: 시스템 제거 화면이 표시됐고 취소 뒤 관리자 상태와
    `dev.sixbridge.ext.lneaoklobohnoncgckmbbjfiajgpbdde` versionCode 2
    카드가 유지됐다.
  - 읽기 전용 확인: 취소 뒤 v2 package와 기존 wrapper 5개가 모두 유지됐다.
  - UI feedback 한계: 취소 안내의 exact 문구는 별도로 인용하거나 캡처하지
    못했다. 표준 화면·취소 복귀·카드·package 유지의 기능 결과만 확인했다.
- [x] 같은 v2의 제거를 승인했다.
  - 사용자 확인: 관리자에서 v2 카드가 사라졌고 삼성 인터넷 Add-ons에서도
    해당 항목이 사라졌다.
  - 읽기 전용 확인: v2 package는 없고 현재 스마트 컨텍스트 메뉴
    `dev.sixbridge.ext.dncifaeoabkkmddoeeccpjdkifeoemfd` versionCode 14는
    설치 상태로 유지됐다.
  - UI feedback 한계: 제거 성공 안내의 exact 문구는 별도로 인용하거나
    캡처하지 못했다. 카드·package·삼성 인터넷 Add-ons 항목 소멸의 기능
    결과는 확인했다.
- [x] Video Download to NAS의 표준 제거 화면에서 취소했다.
  - 사용자 확인: `dev.sixbridge.ext.iefgkdhgegchhlhcjfhcbbelkmpklmhd`
    versionCode 2 카드에서 표준 제거 화면이 표시됐고 취소 뒤 카드가 유지됐다.
  - 읽기 전용 확인: 취소 뒤 같은 package와 versionCode 2가 유지됐다.
- [x] 설치 카드에 실제 CRX 아이콘과 fallback이 표시됐다.
  - screenshot 확인: Video Download to NAS의 구름 artwork와 스마트 컨텍스트
    메뉴 v2의 돋보기 artwork는 실제 아이콘이었다.
  - SIX Bridge Technical Gate는 이름 첫 글자인 보라색 `S` fallback을
    표시했다.
- [x] 관리자 재시작 뒤 제거된 v2가 package 목록에 재등장하지 않았다.
  - `am force-stop`과 `am start` 뒤 설치 wrapper는 현재 스마트 컨텍스트 메뉴
    v14, AI 레시피 추출기 v3, Video Download to NAS v2, SIX Bridge Technical
    Gate v4의 4개였다.
  - v2 `dev.sixbridge.ext.lneaoklobohnoncgckmbbjfiajgpbdde`의 `pm path`는
    비었고 현재 v14와 Video package는 유지됐다.
  - 재시작 시점 화면이 잠겨 fresh Home screenshot은 확보하지 못했다.
    카드 소멸·Video 카드 유지는 사용자 확인, package 상태는 ADB 읽기 전용
    확인으로 구분한다.
  - 재시작 관리자 PID 범위 redaction log 107줄에서
    `FATAL EXCEPTION`·`AndroidRuntime` 표식은 0건이었다.
- [x] 관리자 권한 최소 계약을 확인했다.
  - `REQUEST_DELETE_PACKAGES`와 `REQUEST_INSTALL_PACKAGES`는 존재했다.
  - `DELETE_PACKAGES`, `INTERNET`, `QUERY_ALL_PACKAGES`는 요청 권한에서
    부재했다.

## Fold7 완료 조건

다음 10개 항목은 실제 Fold7에서 각각 확인한다. 개발용 ADB를 증거 수집에
사용할 수 있지만 최종 사용자 흐름 자체는 ADB 없이 다시 실행해야 한다.

1. - [ ] Android 파일 앱에서 관리자 APK를 ADB 없이 설치한다.
   - 절차: 파일 앱에서 APK 선택 → 출처 허용 확인 → 시스템 설치 승인
   - 증거: 설치 화면과 앱 서랍의 `SIX Bridge`, package
     `dev.sixbridge.manager`
2. - [ ] 기존 SIX Bridge 래퍼가 설치 목록에 표시된다.
   - 절차: 관리자 최초 실행 또는 재조회
   - 증거: 이름, extension ID, package, 설치 versionCode
3. - [ ] `.six` 선택 뒤 권한·호환성·adapter·signer 정보가 표시된다.
   - 절차: `.six 파일 추가` → 시스템 파일 선택기에서 fixture 선택
   - 증거: 검토 화면의 각 항목과 예상 fixture 값 비교
4. - [ ] `compatible` 설치와 `partial` 추가 확인 설치가 성공한다.
   - 절차: 두 fixture를 각각 가져오고 시스템 승인 완료
   - 증거: `compatible` 일반 설치, `partial` 명시 확인, 실제 package 재조회
5. - [ ] 같은 signer·더 높은 versionCode 업데이트가 성공한다.
   - 절차: 기존 package 설치 뒤 update fixture 가져오기
   - 증거: 시스템 업데이트 승인과 증가한 실제 versionCode
6. - [ ] 다른 signer와 같거나 낮은 versionCode가 차단된다.
   - 절차: signer 불일치, 같은 versionCode, 낮은 versionCode fixture 각각 선택
   - 증거: 설치 버튼 차단과 서로 구분되는 한글 안내
7. - [x] 표준 제거와 실제 목록 정리가 동작한다.
   - 절차: 제거 취소 1회, 제거 성공 1회, 관리자 복귀
   - 증거: 스마트 컨텍스트 메뉴 v2의 취소·성공, Video Download to NAS의
     취소, package 재조회와 관리자·삼성 인터넷 Add-ons 목록 변화
8. - [ ] 삼성 인터넷의 전체 Add-ons 화면을 열 수 있다.
   - 절차: 홈의 `삼성 Add-ons 열기`
   - 증거: `internet://extension`으로 열린 전체 Add-ons 목록
9. - [ ] 삼성 인터넷 재시작 뒤 설치 확장의 활성 상태가 유지된다.
   - 절차: 삼성 인터넷에서 확장 활성 확인 → 앱 완전 종료 → 재시작
   - 증거: 삼성 인터넷이 표시하는 활성 상태와 실제 확장 동작
10. - [ ] 관리자 재시작 뒤 catalog와 실제 설치 package가 일치한다.
    - 절차: 외부 설치·제거 상태를 만든 뒤 관리자 완전 종료·재시작
    - 증거: 실제 package를 기준으로 추가·갱신·정리된 홈 목록

## 최종 무ADB 사용자 흐름

- [ ] 개발 설치본을 제거한 상태에서 파일 앱으로 관리자 APK를 설치했다.
- [ ] 관리자 안의 시스템 파일 선택기로 `.six`를 선택했다.
- [ ] 출처 허용과 시스템 설치 승인 외에 ADB·root·Shizuku를 사용하지 않았다.
- [ ] 설치, Add-ons 이동, 제거와 재시작 검증까지 완료했다.

## 실행 결과

Task 12의 개발용 ADB 설치·기동·package query·fixture 조사와 관리자 홈
catalog 표시는 확인했다. 표준 Android 제거 자체도 실제 package 소멸까지
확인했으며, 제거 직후 잘못된 취소 안내를 수정한 관리자 APK를 다시
설치했다. Task 5에서 표준 제거 취소·승인·다른 wrapper 취소, 실제 아이콘과
fallback, v2 package·관리자 카드·삼성 인터넷 Add-ons 항목 정리, 관리자
재시작 뒤 package 정리 유지와 치명 오류 부재를 확인했다. 위에 체크하지 않은
나머지 무ADB 설치·업데이트·차단·삼성 인터넷 재시작 항목은 미완료 상태로
유지한다. 2026-07-27 아이콘 동시성 최종 회귀에서는 production
manifest·Room schema version `1` 유지, Android unit 297/297, Fold7
connected 28/28, 전체 패키징 74/74와 Node 89/89, build/dist APK
byte 동일성을 자동 검증했다. 이번 최종 회귀에서는 실제 wrapper 제거 또는
새 UI 수동 캡처를 수행하지 않았으므로 기존 사용자 확인 범위를 확대해
주장하지 않는다. 이어진 관리자 launcher icon 보정에서는 컬러 전경을
약 `48dp`로 줄이고 우측 시각 보정, 라운드 모서리와 머티리얼 돌출면을
적용했다. 전체 패키징과 구조 계약 교차 검토 뒤 Fold7에 업데이트했으며,
사용자는 컬러 아이콘을 1차 승인했다. 단색 테마 아이콘 재확인과 추가 미세
조정은 실행하지 않았으므로 후속 항목으로 유지한다.
