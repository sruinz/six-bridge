# SIX Bridge Android 관리자 사용자 흐름

## 파일 역할

SIX Bridge 관리자는 변환된 확장의 설치 수명주기를 담당한다. 사용자에게
보이는 파일과 Android·삼성 인터넷이 실제로 사용하는 파일은 서로 다르다.

| 파일 | 역할 |
|---|---|
| `sixbridge-manager.apk` | 사용자가 최초 한 번 설치하는 관리자 앱 |
| `<확장 이름>.six` | 변환 확장을 보관·공유·전송하고 관리자에서 가져오는 파일 |
| `.six` 내부 `wrapper.apk` | Android에 실제 설치되는 확장별 래퍼 package |
| 래퍼 내부 CRX3 | 래퍼가 삼성 인터넷에 제공하는 실제 확장 본체 |

래퍼 APK만 직접 설치하면 `.six`의 호환성 보고서와 관리자 검토 절차를
건너뛴다. 공식 사용자 흐름에서는 `.six`를 관리자에서 가져온다.

개발 검증 스크립트는 `dist/sixbridge-manager-debug.apk`를 만든다. 이 파일은
debug 서명 산출물이다. 일반 배포본은 릴리스 서명과 검증을 거친
`sixbridge-manager.apk`여야 한다.

## 준비

- Android 11 이상 삼성 기기
- 삼성 인터넷 30.x
- 기기로 안전하게 전달한 관리자 APK와 `.six`
- 설치 과정의 Android 시스템 안내를 직접 확인할 사용자

일반 사용자 절차에는 ADB, root, Shizuku, Android SDK가 필요하지 않다.
자동 설치나 무인 승인은 지원하지 않는다.

## ADB 없이 관리자 설치

1. 관리자 APK의 배포처와 SHA-256을 별도 신뢰 경로로 확인한다.
2. USB 파일 전송, 승인된 클라우드 저장소 또는 안전한 메신저로 APK를 기기에
   저장한다.
3. Android 파일 앱에서 `sixbridge-manager.apk`를 누른다. 개발 검증본이면
   파일명은 `sixbridge-manager-debug.apk`다.
4. Android가 요청하면 해당 파일 앱에 대해서만 `이 출처의 앱 허용` 설정을
   확인한다.
5. 시스템 설치 화면에서 앱 이름과 package를 확인하고 설치한다.
6. 앱 서랍에서 `SIX Bridge`를 실행한다.

관리자 설치 뒤 파일 앱의 출처 허용을 계속 유지할 필요가 없다면 다시 끌 수
있다. 관리자를 제거해도 이미 설치된 확장 래퍼 package는 자동 제거되지 않는다.

## `.six` 가져오기와 설치

1. `<확장 이름>.six`를 기기의 다운로드 폴더처럼 찾기 쉬운 위치에 저장한다.
2. SIX Bridge 관리자 홈에서 `.six 파일 추가`를 누른다.
3. Android Storage Access Framework 파일 선택기에서 파일 앱을 선택하고
   `.six`를 고른다.
4. 관리자가 bundle 구조, `wrapper.apk`, APK signer, package, versionCode,
   metadata, Provider, `six://ex` Activity와 내장 CRX3 계약을 검증할 때까지
   기다린다.
5. 검토 화면에서 다음 정보를 확인한다.

   - 확장 이름·버전·extension ID와 Android package
   - 요청 권한
   - 호환성 상태와 이슈
   - 적용된 adapter와 남은 제한
   - wrapper versionCode와 APK signer SHA-256

6. 상태별 설치 정책을 확인한다.

   - `compatible`: 일반 검토 뒤 설치할 수 있다.
   - `partial`: 제한과 이슈를 이해했다는 추가 확인 뒤 설치할 수 있다.
   - `blocked`: 설치 버튼이 비활성화된다.
   - `unknown`: 설치 버튼이 비활성화된다.

7. 설치를 누른다. Android가 요구하면 SIX Bridge 관리자의 `알 수 없는 앱
   설치` 권한 화면에서 허용 여부를 직접 결정한다.
8. Android 시스템 설치 승인 화면에서 대상 앱을 다시 확인하고 승인하거나
   취소한다.
9. 관리자가 실제 설치 package를 다시 조회하고 홈 목록을 갱신하는지 확인한다.

파일 선택기에서 취소하면 설치는 시작되지 않는다. 설치·취소·실패 뒤 관리자가
소유한 임시 `.six`, wrapper와 CRX bytes는 정리되며 원본 파일을 영구
보관하지 않는다.

## 설치 상태와 보고서

홈 목록은 삼성 인터넷의 활성화 상태가 아니라 Android package 설치 상태다.
각 항목에서 이름, extension ID, package와 설치 versionCode를 확인할 수 있다.
각 카드의 실제 아이콘은 설치된 래퍼가 보유한 로컬 CRX에서 읽는다. 아이콘을
읽거나 디코딩할 수 없으면 확장 이름의 첫 글자를 fallback 아이콘으로 표시한다.

`보고서`는 마지막으로 검증한 호환성 자료를 표시한다. 외부에서 직접 설치한
정상 SIX Bridge 래퍼는 발견할 수 있지만, 관리자가 검증한 보고서가 없으면
`보고서 없음`으로 표시한다. 보고서는 악성 코드 검사 결과가 아니다.

## 업데이트

기존 확장과 같은 package의 새 `.six`를 가져오면 다음 조건을 모두 만족할
때만 업데이트할 수 있다.

1. 기존 package와 새 descriptor package가 같다.
2. 설치된 APK와 새 wrapper APK의 signer가 같다.
3. 새 wrapper의 versionCode가 더 높다.

signer가 다르거나 versionCode가 같거나 낮으면 차단한다. 같은 버전 강제
재설치와 downgrade는 지원하지 않는다. 업데이트도 Android 시스템 승인
화면을 생략하지 않는다.

## 제거

1. 홈에서 대상 확장의 `제거`를 누른다.
2. 관리자가 연 표준 Android 제거 확인 화면에서 앱 이름과 package를 확인한다.
3. 제거하거나 취소한다.
4. 관리자로 돌아오면 앱이 실제 package 존재 여부를 재조회한다.

관리자 manifest의 `REQUEST_DELETE_PACKAGES`는 이 표준 확인 화면을 열기 위한
권한이며 자동 삭제 권한이 아니다. 관리자는 확인 화면을 생략하거나 사용자를
대신해 제거를 승인하지 않는다.

제거가 실제로 확인된 경우에만 홈 목록과 catalog를 정리한다. 래퍼 package를
제거하면 그 래퍼가 제공하던 CRX3도 함께 사라진다.

## 삼성 Add-ons

홈 상단의 `삼성 Add-ons 열기`는 공개 `internet://extension` 진입점으로
삼성 인터넷의 전체 Add-ons 화면을 연다. 특정 확장 상세 화면으로 직접
이동하지 않으며 사용자가 목록에서 대상을 선택한다.

확장 활성화·비활성화, 권한, 비밀 모드 허용과 `options_ui` 설정은 삼성
인터넷이 담당한다. 관리자는 이 내부 상태를 추정하거나 대신 변경하지 않는다.
