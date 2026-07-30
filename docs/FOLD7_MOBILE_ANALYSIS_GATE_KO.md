# Fold7 모바일 분석 안전 게이트

이 문서는 `SM_F966N` Fold7에서 SIX Bridge manager의 모바일 분석 복구를
검증하는 절차다. 자동 게이트는 manager debug APK를 덮어 설치하고 실행 상태와
진단 자료만 읽는다. Bitwarden URL 입력과 화면 판정은 자동화하지 않는다.

## 사전 조건

- 저장소 루트에서 명령을 실행한다.
- ADB serial은 `192.0.2.10:39359`를 정확히 사용한다.
- 아래 APK가 빌드되어 있어야 한다.
  - `android-manager/build/outputs/apk/debug/android-manager-debug.apk`
  - `android-manager/build/outputs/apk/androidTest/debug/android-manager-debug-androidTest.apk`
- 기존 wrapper, manager app data, `.local/` 키와
  `cache/recovery-bitwarden-20260730`은 그대로 보존한다.

## 자동 안전 점검

```bash
./scripts/fold7-mobile-analysis-gate.sh 192.0.2.10:39359
```

스크립트는 다음 작업만 순서대로 수행한다.

1. exact serial의 ADB 연결 상태를 확인한다.
2. manager debug APK를 `install -r`로 덮어 설치한다.
3. `dev.sixbridge.manager`를 강제 종료한 뒤 launcher intent로 한 번 실행한다.
4. manager PID를 확인한다.
5. manager sandbox의 `cache/sixbridge-analysis-*` 디렉터리 이름만 읽는다.
6. 기존 logcat을 지우지 않고 `sixbridge`, `AndroidRuntime`, `ANR` 일치 행만
   `.local/evidence/<timestamp>-mobile-analysis-gate.log`에 기록한다.

ADB 연결, 설치 또는 PID 확인이 실패하면 즉시 중단한다. logcat에 일치 행이
없는 경우에는 빈 evidence 파일을 남기고 성공한다. 이는 오류 로그가 없다는
뜻일 뿐 아래 사용자 흐름의 성공을 대신 판정하지 않는다.

## 사용자 수동 확인

아래 단계는 화면을 직접 보고 순서대로 수행한다.

1. 자동 안전 점검 명령이 성공했는지 확인한다.
2. manager의 Chrome 웹 스토어 주소 입력 화면에 Bitwarden URL을 직접
   입력한다.
3. 30초 안에 결과가 `UNKNOWN`으로 끝나고 데스크탑 분석 안내가 표시되는지
   확인한다. 무한 spinner 또는 자동 재시도가 있으면 실패로 기록한다.
4. 같은 URL로 재시도하고 분석 중 `취소하고 정리`를 누른다.
5. 2초 안에 홈으로 진입하며 기존 확장의 제거 버튼이 반응하는지 확인한다.
   실제 제거 확인 화면에서는 승인하지 않고 취소한다.
6. 다시 분석 중 manager를 강제 종료한 뒤 재실행한다. 시작 화면에 spinner가
   남지 않고 자동 분석이 재개되지 않는지 확인한다.
7. 데스크탑 앱이 없는 현재 PoC에서는 새 `.six` 파일을 전제로 하지 않는다.
   스마트 컨텍스트 메뉴의 원본 ZIP 또는 CRX를 manager에서 선택해 온디바이스
   분석·변환하고, 생성된 래퍼 APK를 설치한 뒤 삼성 인터넷의 실제 context
   menu가 동작하는지 확인한다. 과거 `.six`의 extension ID가 현재 설치본과
   다르면 중복 설치가 되므로 사용하지 않는다.
8. `파일 앱 열기`를 눌러 chooser가 표시되는지 확인하고, 설치 완료 뒤
   설치 출처 결과가 예상한 파일 앱 경로와 일치하는지 확인한다.
9. review 화면의 원본 extension ID, wrapper package, APK SHA-256,
   signer SHA-256이 변환 산출물과 모두 일치하는지 확인한다.
10. Play Protect 경고가 표시되면 플랫폼 제한으로 증거에 기록하고 우회하거나
    보호 기능을 끄지 않는다.

각 단계의 결과, 시각, 화면 메시지와 관련 evidence 파일명을 기록한다. 수동
판정이 하나라도 불명확하면 성공으로 간주하지 않는다.

## 읽기 전용 상태 확인

자동 게이트 뒤 다음 명령으로 실행 상태를 읽을 수 있다.

```bash
adb -s 192.0.2.10:39359 shell pidof dev.sixbridge.manager
adb -s 192.0.2.10:39359 shell dumpsys cpuinfo dev.sixbridge.manager
adb -s 192.0.2.10:39359 shell run-as dev.sixbridge.manager \
  find cache -maxdepth 1 -name 'sixbridge-analysis-*' -type d -print
adb -s 192.0.2.10:39359 shell run-as dev.sixbridge.manager \
  find files/conversion-work -maxdepth 2 -type f -print
adb -s 192.0.2.10:39359 shell run-as dev.sixbridge.manager \
  find cache/recovery-bitwarden-20260730 -maxdepth 2 -print
```

기대 결과는 manager PID가 존재하고, idle 상태에서 CPU가 반복 분석 수준으로
계속 증가하지 않으며, 시작 직후 활성 `sixbridge-analysis-*`가 누적되지 않는
것이다. `files/conversion-work`와 격리 Bitwarden 경로는 존재하는 항목을
확인하기만 한다.

## 삭제·롤백 없는 안전 경계

이 게이트에서는 다음 작업을 하지 않는다.

- `logcat -c`
- `pm clear`
- app data 또는 cache 삭제
- manager나 wrapper uninstall
- `.local/`의 CRX 키, PKCS12 키, APK, CRX3 또는 evidence 삭제
- `cache/recovery-bitwarden-20260730` 삭제 또는 이동
- Play Protect 우회

실패해도 자동 롤백이나 정리 삭제를 수행하지 않는다. 실행을 중단하고 현재
화면, evidence, PID, CPU, cache listing을 보존한다. 격리 Bitwarden 자료는
사용자가 새 빌드의 수동 복구를 확인한 뒤 별도 승인한 후속 작업에서만 exact
경로를 다시 출력하고 처리한다.
