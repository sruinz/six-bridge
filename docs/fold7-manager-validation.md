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
- Android 버전: 미기록
- 삼성 인터넷 버전: 미기록
- 관리자 APK: `dist/sixbridge-manager-debug.apk`
- 관리자 APK SHA-256:
  `be0e8824264c304185ca08996c04acb03dce17ad949fd5f2c7ee08259a7f06ae`
- 자동 검증 일자: 2026-07-26
- Fold7 최종 수동 실행 일시: 미기록
- 실행자: 미기록

## 자동 검증

아래 항목은 host에서 실행하는 사전 검증이다. Fold7 최종 사용자 흐름의
성공을 의미하지 않는다.

- [x] `./scripts/verify.sh`
  - 증거: Gradle 74 task 성공, Node 89/89 통과, 기존 shell/mobile conversion
    통과, Android manifest와 distribution 계약 `PASS`
- [x] `./scripts/package-manager-debug.sh`
  - 증거: 내부 전체 검증 성공 뒤
    `dist/sixbridge-manager-debug.apk` 패키징 완료
- [x] `shasum -a 256 dist/sixbridge-manager-debug.apk`
  - 증거: 패키징 출력과 독립 실행 hash가
    `be0e8824264c304185ca08996c04acb03dce17ad949fd5f2c7ee08259a7f06ae`로
    일치하고 fresh build APK와 byte-for-byte 동일
- [x] Git 제외 경계
  - 증거: `git check-ignore -v`로 `dist/`, `**/build/`, Room 임시 schema,
    `local.properties`를 확인하고 canonical Room JSON schema가 계속 추적됨을
    확인
- [x] `:android-manager:connectedDebugAndroidTest`
  - 증거: debug test Activity를 비공개로 병합한 APK로 Fold7에서 10/10 통과,
    failures/errors/skipped 0

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
7. - [ ] 표준 제거와 실제 목록 정리가 동작한다.
   - 절차: 제거 취소 1회, 제거 성공 1회, 관리자 복귀
   - 증거: 표준 Android 제거 화면과 package 재조회 뒤 목록 변화
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

아직 Task 12 Fold7 최종 검증을 실행하지 않았다. 위 수동 항목은 모두
미체크 상태로 유지한다.
