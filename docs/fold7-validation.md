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

## 게이트 C — 변환된 스마트 컨텍스트 메뉴

실제 개발 source를 복사하거나 수정하지 않고 다음 명령으로 host 검증과
Fold7 install/smoke를 연속 실행한다.

```bash
./scripts/fold7-converted-extension.sh \
  --serial 'adb-DEVICE_SERIAL._adb-tls-connect._tcp'
```

기기 없이 host 결과만 만들 때는 `--host-only`를 사용한다. `--host-only`가
아니면 비어 있지 않은 `--serial`이 필수이며 둘을 함께 지정할 수 없다. 다른
source나 profile은 각각 `--source`, `--profile`로 지정한다. 스크립트는
실행 전에 `.local`, `.local/evidence`, `dist`가 symlink가 아닌 예상 실제
parent의 디렉터리인지 확인하고 다음을 검사한다.

- 변환 전 adapter decision
- 같은 입력으로 만든 SixDraft 두 개의 strict 검증과 SHA-256 일치
- `smart-context-converted.crx`,
  `smart-context-converted-wrapper.apk`,
  `smart-context-converted.six` basename 계약
- `verify-build`를 통한 CRX3 ID·hash, SixBundle 외부 산출물 결합, wrapper
  embedded CRX·package·versionCode·signer 검증
- 외부 CRX·wrapper를 각각 128MiB로 사전 제한하고 bounded read·streaming
  compare 사용
- APK v3 서명, zipalign, metadata·Activity·Provider 구조화 manifest 계약
- 변환 후 정확한 세 adapter의 version과 selection·popup 적용 상태,
  미적용 storage decision 전체 객체 보존
- 설치 package, `versionName`, 숫자 `versionCode`와 profile의 정확한 일치
- 실제 source의 `git status --short`와 relative-path/content tree hash가
  실행 전후 같은지 여부

임시 보고서와 draft는 예측 불가능한 이름으로 만들고 검사가 끝난 뒤
`evidence` 디렉터리 안에서 고정 이름으로 원자적으로 게시한다. device smoke도
공유 evidence root가 아니라 실행마다 새로 만든 run 디렉터리를 사용한다.

device smoke는 ContentProvider raw CRX를 evidence에 기록하지 않는다. byte
기반으로 최대 128MiB까지만 수집한 뒤 `providerMagic`, `providerBytes`,
`providerSha256` 비가역 요약만 텍스트 로그에 기록하고 임시 raw 파일은
삭제한다. binary process의 stderr도 1MiB로 제한한다. 제한 초과 시 수집한
stderr 원문을 폐기하고 고정 placeholder만 남기며, process tree 강제 종료,
세 process stream close, daemon reader의 시간 제한 join을 수행한다. 임시
stdout 파일은 symlink가 아닌 기존 일반 파일만 허용하고 실제 open에도
`NOFOLLOW_LINKS`를 적용한다.

2026-07-25 04:09 KST 최종 자동 검증 증거는 다음과 같다.

- 전체 실행:
  `.local/evidence/20260725-040944-smart-context-converted.log.Wufwj5`
- device smoke:
  `.local/evidence/.smart-context-run.KzpSGy/1784920198-dncifaeoabkkmddoeeccpjdkifeoemfd.log`
- 변환 전후 report:
  `.local/evidence/smart-context-before.json`,
  `.local/evidence/smart-context-after.json`
- SixDraft:
  `.local/evidence/smart-context-a.sixdraft`,
  `.local/evidence/smart-context-b.sixdraft`

이 실행에서 APK 설치, `six://ex` discovery, ContentProvider `Cr24` read는
통과했다. provider byte 길이는 `290509`, SHA-256은
`18e2cc6864b941099633cb683dd6ceca015eaebff6d85c8a0488da89cf19b58f`로
외부 CRX와 일치하며 raw CRX 필드는 없다. 최종 wrapper `versionCode`는
`9`이다. 그러나 Fold7의 삼성
인터넷 package는 `com.sec.android.app.sbrowser`로 일치하는 반면 버전은
`30.0.0.67` (`versionCode 1300067502`)이고 적용 profile은 `30.0.2.61`
(`versionCode 1300261502`)이므로 runtime 최종 합격으로 간주하지 않는다.

정확히 일치하는 profile을 확보한 뒤 다음 항목은 사용자가 직접 확인해야 한다.

1. Add-ons에서 변환 확장을 켠다.
2. 일반 HTTPS 페이지에서 텍스트를 한 번 선택한다.
3. 스마트 컨텍스트 UI가 한 번만 나타나고 중복 생성되지 않는지 확인한다.
4. `확장 열기`가 기존 popup 페이지를 새 탭으로 여는지 확인한다.
5. 설정을 변경하고 브라우저를 재시작해 `storage.local` 유지 여부를 확인한다.
6. 해당 조작 시점 logcat에 service worker 예외, resource 누락, adapter
   반복 실행이 없는지 확인한다.

`storage.sync`는 이번 자동 검증에서 동작을 확인하지 않았다.
`storage-fallback`은 source의 동적·계산된 접근을 안전하게 변환할 수 없어
`unavailable`, `applied=false`로 보존됐다.
