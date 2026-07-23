# SIX Bridge PoC 아키텍처

SIX Bridge는 삼성 인터넷 APK를 수정하지 않는다. 확장 프로그램을 CRX3
페이로드로 변환하고, 이를 서명된 Android 래퍼 패키지에 포함한 다음 삼성
인터넷에서 확인된 개발용 확장 검색 계약을 사용한다.

```text
소스 / ZIP ── 고정 RSA 키 ── CRX3 ─────────┐
                                           ├─ 서명된 wrapper.apk ─┐
Chrome 웹 스토어 URL ── 원본 CRX3 보존 ───┘                      ├─ SixBundle
호환성 분석기 ───────────────────────────── compatibility.json ─┤
확장·서명자 메타데이터 ──────────────────── extension.json ─────┘
```

## 신뢰 및 식별자 경계

- `.local/keystore/crx/`에는 로컬에서 패킹한 확장마다 하나의 RSA 개인 키를
  저장한다. CRX ID는 DER 공개 키의 SHA-256 해시 중 처음 128비트를 사용하며,
  각 16진수 자릿값을 `a`부터 `p`까지의 문자로 변환한다.
- `.local/keystore/wrapper.p12`는 모든 래퍼 APK에 서명한다. 비밀번호는
  소유자만 읽을 수 있는 `.local/keystore/wrapper.pass`에 저장한다.
- Chrome 웹 스토어 CRX3 입력은 다시 패킹하지 않는다. 원본 바이트, 서명,
  확장 ID를 그대로 보존한다.
- 래퍼 패키지명은 `dev.sixbridge.ext.<extensionId>`이다. 서명된 APK 검증이
  성공한 뒤에만 `versionCode`를 확정한다.

## 확인된 삼성 래퍼 계약

기준 30.0.2.61 APK에는 다음 동작을 수행하는 코드가 포함되어 있다.

- `six://ex`에 대응하는 exported `VIEW`/`BROWSABLE` Activity 검색
- Application metadata의 `extension-id` 읽기
- `content://<package>.contentprovider`에서 CRX 스트림 읽기
- ADB로 설치한 패키지를 `dev_ext` 경로로 분류

이는 버전에 종속된 관찰 결과이며 공개 SDK의 보장 사항이 아니다. 새로운
30.x에서 API namespace 또는 래퍼 표식이 달라지면 `unknown`으로 판정하고,
검토 전에는 빌드하지 않는다.

삼성의 공식
[확장 프로그램 가이드](https://developer.samsung.com/internet/android/extension-guide.html)는
일반 확장 배포를 승인제 프로그램으로 설명한다. 따라서 여기서 사용하는
개발용 계약은 PoC 인터페이스로만 취급한다.

## 단계 게이트

Android 관리자 앱은 이 단계에서 의도적으로 만들지 않는다. 자체 서명한
`fixtures/minimal-mv3` 래퍼가 Fold7의 삼성 Add-ons에 나타나고, 활성화되며,
실제 확장 런타임이 실행되고, 브라우저 재시작 뒤에도 활성 상태가 유지되어야
관리자 앱 단계로 넘어갈 수 있다.
