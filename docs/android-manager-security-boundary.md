# SIX Bridge Android 관리자 보안 경계

## 관리자가 보장하는 범위

관리자는 `.six`가 SIX Bridge 설치 계약을 만족하는지 엄격히 검증하고,
Android 시스템 설치·제거 승인 흐름을 우회하지 않는다.

가져오기에서 다음 경계를 서로 독립적으로 확인한다.

1. ZIP entry가 정확히 `wrapper.apk`, `extension.json`,
   `compatibility.json`인지 확인한다.
2. 누락·추가·중복·디렉터리 entry, 경로 이동 이름, 크기·압축률 제한 위반을
   거부한다.
3. `wrapper.apk` SHA-256과 descriptor 값을 비교한다.
4. APK signer 인증서 SHA-256과 descriptor 값을 비교한다.
5. package가 `dev.sixbridge.ext.<extensionId>` 형식인지 확인한다.
6. APK versionCode와 descriptor 값을 비교한다.
7. Application metadata의 `extension-id`, exported `six://ex`
   Activity와 `<applicationId>.contentprovider` Provider를 확인한다.
8. 래퍼 내부 CRX3 hash·서명·extension ID를 확인한다.
9. 호환성 보고서의 확장 이름·버전·변환 ID 정합성을 확인한다.

구조나 식별자 중 하나라도 맞지 않으면 설치 대상으로 넘기지 않는다.

## 임의 signer를 허용하는 이유

SIX Bridge는 특정 제작자나 프로젝트 공식 키만 allowlist로 허용하지 않는다.
각 사용자가 자신의 데스크톱 변환 키로 확장을 만들 수 있어야 하므로 정상
계약을 만족하는 임의 signer의 신규 package를 받을 수 있다.

이는 signer 검증을 생략한다는 뜻이 아니다. 신규 설치에서는 `.six`
descriptor와 실제 APK signer가 일치해야 한다. 같은 package를 업데이트할
때는 설치된 APK와 새 wrapper의 signer가 반드시 같아야 하며, 새 versionCode도
더 높아야 한다. signer가 다르거나 versionCode가 같거나 낮으면 차단한다.

`.local/`의 CRX 키와 wrapper PKCS12 키는 extension ID와 Android 업데이트
연속성을 유지하는 로컬 식별자다. Git에 포함하거나 배포 문서·로그에 복사하지
않는다.

## 호환성 보고서가 보장하지 않는 것

`compatibility.json`은 데스크톱 분석 엔진이 만든 삼성 인터넷 기능 호환성
자료다. 권한, 사용 API, 알려진 제한과 adapter 결정을 검토하는 데 사용한다.

이 보고서는 malware scan, 코드 감사, 제작자 신원 보증 또는 안전 보증이
아니다. 임의 signer를 허용하는 환경에서는 제작자가 보고 내용을 속일 수도
있다. 사용자는 출처와 signer 지문을 별도로 판단해야 하며 Android 시스템
설치 확인을 보고서로 대체해서는 안 된다.

## 저장·네트워크 경계

- 원본 `.six`, wrapper APK와 CRX3는 영구 저장하지 않는다.
- 설치·취소·실패 뒤 임시 파일과 실패한 install session을 정리한다.
- 프로세스 중단 뒤에는 다음 시작 시 관리자가 소유한 오래된 임시 파일과
  미완료 session을 정리한다.
- Room catalog에는 package 식별 정보와 검증 보고서 요약만 보관한다.
- 실제 설치 여부, versionCode와 signer는 `PackageManager` 재조회 결과를
  원본 상태로 사용한다.
- 1단계 관리자 manifest에는 `INTERNET`와 `QUERY_ALL_PACKAGES` 권한이 없다.
- 패키지 조회는 `six://ex`, 삼성 인터넷 package와 공개
  `internet://extension` query로 제한한다.
- 로그에 원본 파일 bytes, CRX bytes, URI grant, 개인 경로와 signing secret을
  남기지 않는다.

1단계 관리자는 LAN 수신, 원격 변환 서버, 온디바이스 분석·변환 기능을
포함하지 않는다.

## 사용자 승인과 삼성 인터넷 경계

래퍼 설치에는 `REQUEST_INSTALL_PACKAGES`가 필요하지만 이 권한은 무인 설치
권한이 아니다. 사용자는 필요할 때 `이 출처 허용` 설정을 직접 확인하고,
각 설치·업데이트의 Android 시스템 승인 화면에서 최종 결정을 내린다.
root, Shizuku와 비공개 설치 API를 사용하지 않는다.

제거도 표준 Android 제거 화면을 사용한다. 관리자 복귀 이벤트만으로 성공을
가정하지 않고 실제 package 상태를 다시 조회한다.

삼성 인터넷은 설치된 래퍼의 활성화·비활성화, 확장 권한, 비밀 모드 허용과
상세 설정을 소유한다. 관리자는 Android package 설치 상태만 표시하며 삼성
인터넷 내부 활성 상태를 추정하지 않는다. Add-ons 이동도 특정 확장용 비공개
딥링크가 아니라 공개된 전체 화면 진입점만 사용한다.
