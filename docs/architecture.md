# SIX Bridge PoC 아키텍처

SIX Bridge는 삼성 인터넷 APK를 수정하지 않는다. 확장 프로그램을 CRX3
페이로드로 변환하고, 이를 서명된 Android 래퍼 패키지에 포함한 다음 삼성
인터넷에서 확인된 개발용 확장 검색 계약을 사용한다.

```text
원본 ── analyze ── 호환성·모바일 패턴 보고서
  │
  ├── draft + 승인 ── 임시 변환 ── 서명된 SixDraft ── Android 관리자
  │
  └── build + 승인 ── 임시 변환 ── CRX3 ── wrapper.apk ── SixBundle
```

## 호환성 분석과 변환 경계

호환성은 원본 확장이 삼성 인터넷에서 어떤 API를 사용할 수 있는지에 대한
판정이다. 변환은 호환성 보고서가 식별한 데스크톱 전용 동작을 모바일
어댑터로 바꾸는 별도 단계다. `analyze`는 변환 계획까지만 계산하며 출력
보고서 외의 빌드 파일을 만들지 않는다.

어댑터는 안전하게 의미가 보존되는 `automatic`, 사용자에게 보이는 동작이
달라 명시 승인이 필요한 `requires-approval`, 안전한 변환을 증명할 수 없는
`unavailable`로 구분한다. 승인 목록은 집합으로 정규화되며, 순서가 달라도
동일한 source와 계획은 동일한 SixDraft를 만든다.

`draft`는 공용 변환 파이프라인의 결과와 전후 호환성 보고서를
`.local/keystore/draft-transfer/`의 Ed25519 키로 서명한다. 생성 직후 strict
reader로 다시 읽어 서명, 항목 제한, source·계획 hash 결합을 확인한다.
데스크톱 앱은 이 전달물을 만들 수 있고 Android 관리자는 같은 전달물을
검증한 뒤 최종 일반 사용자용 서명과 설치 승인을 담당한다.

`build`는 같은 변환 결과를 개발용 CRX3·래퍼 APK·SixBundle에 연결한다.
요청한 SixBundle이 `foo.six`이면 prepare 전에 `foo.crx`,
`foo-wrapper.apk`, `foo.six` 세 target을 확정하고 hardlink와 parent
identity를 검증한다. 출력 경로를 생략하면 canonical 입력 문자열 hash로
결정적 basename을 만든다. 파일명은 extension ID가 아니며 실제 ID는 CRX,
descriptor와 래퍼 package에 유지된다.

`draft`와 `build`의 출력 부모 폴더는 명령 전에 사용자가 만들어야 한다.
SIX Bridge는 이를 자동 생성하지 않고, 승인 검사와 persistent prepare보다
먼저 기존 폴더의 identity·권한과 모든 target을 캡처한다. 예를 들어
`dist/foo.six`를 만들려면 먼저 `mkdir -p dist`를 실행한다.

ADB 설치는 이 개발 결과의 삼성 인터넷 인식과 런타임을 확인하기 위한
검증 경로일 뿐, 일반 사용자 흐름의 필수 구성요소가 아니다.

## 신뢰 및 식별자 경계

- `.local/keystore/crx/`에는 로컬에서 패킹한 확장마다 하나의 RSA 개인 키를
  저장한다. CRX ID는 DER 공개 키의 SHA-256 해시 중 처음 128비트를 사용하며,
  각 16진수 자릿값을 `a`부터 `p`까지의 문자로 변환한다.
- `.local/keystore/wrapper.p12`는 모든 래퍼 APK에 서명한다. 비밀번호는
  소유자만 읽을 수 있는 `.local/keystore/wrapper.pass`에 저장한다.
- Chrome 웹 스토어 CRX3 입력은 다시 패킹하지 않는다. 원본 바이트, 서명,
  확장 ID를 그대로 보존한다. 다만 모바일 어댑터를 적용해 source가 바뀌면
  변환 전용 키로 다시 패킹하므로 원본 스토어 ID와 달라질 수 있다.
- `.local/keystore/draft-transfer/`에는 데스크톱과 Android 관리자 사이의
  SixDraft 전달 서명용 Ed25519 공개 키와 개인 키를 분리해 저장한다.
- 래퍼 패키지명은 `dev.sixbridge.ext.<extensionId>`이다. 서명된 APK 검증이
  성공한 뒤에만 `versionCode`를 확정한다.

현재 PoC의 파일시스템 위협 모델은 신뢰된 단일 사용자 workspace다. 같은
OS 계정의 다른 프로세스가 `prepare` 도중 보호 경로나 출력 경로를 동시에
변경하는 상황은 지원하지 않으며, 공개 다중 사용자 변환 서비스의 격리
경계로 사용해서는 안 된다. identity 변경은 최종 publication guard가
거부할 수 있지만, inode·권한을 유지한 내용 변경이나 swap 후 원상 복구를
포함한 모든 동시 변경의 탐지를 보장하지 않는다. 게시가 실패하기 전에
생성된 source identity, CRX 키, wrapper 키가 `.local/`에 남거나 단조 증가
version 카운터가 앞설 수 있으므로 workspace 전체를 하나의 원자적
트랜잭션으로 취급하지 않는다.

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
