# SIX Bridge

SIX Bridge는 데스크톱 Chrome/Edge Manifest V3 확장을 삼성 인터넷 30.x에서
시험할 수 있도록 분석·변환하는 Kotlin/JVM 도구다. 삼성 인터넷 APK를
수정하지 않으며, 무인 설치·root·Shizuku·타사 확장 공개 배포를 지원하지
않는다.

호환성 분석과 변환은 서로 다르다. `analyze`는 원본을 읽기 전용으로
검사하여 삼성 인터넷 지원 API와 모바일 진입점 차이를 보고한다. `draft`와
`build`는 그 보고서에 근거한 어댑터를 임시 작업 트리에 적용하고, 원본
소스는 변경하지 않는다.

어댑터 판정은 다음 세 종류다.

- `automatic`: 의미 보존이 확인되어 별도 승인 없이 적용한다.
- `requires-approval`: 모바일 동작 방식이 달라지므로 사용자가
  `--approve-adapter`로 명시적으로 승인해야 한다.
- `unavailable`: 안전한 자동 변환을 증명할 수 없어 적용하지 않는다.

## 요구 사항

- JDK 17 이상
- Gradle 9.5 이상
- Android SDK platform 36, build-tools 36
- 실기기 개발 검증을 수행할 때만 ADB

빌드와 전체 검증:

```sh
./scripts/verify.sh
export PATH="$PWD/cli/build/install/sixbridge/bin:$PATH"
```

기준 브라우저 프로필 생성:

```sh
sixbridge profile \
  --browser-apk "com.sec.android.app.sbrowser_30.0.2.61-1300261502_minAPI30.apk"
```

## 분석, 전달 초안, 개발 빌드

먼저 원본을 변경하지 않고 변환 가능성을 확인한다.

```sh
sixbridge analyze \
  --input fixtures/desktop-mobile-golden \
  --profile profiles/sbrowser-30.0.2.61.json \
  --output .local/test-output/desktop-mobile-before.json
```

데스크톱과 Android 관리자 사이에 전달할 서명된 `SixDraft`를 만든다. 다음
예시는 action popup을 모바일 탭 진입점으로 바꾸는 동작을 승인한다.

```sh
sixbridge draft \
  --input fixtures/desktop-mobile-golden \
  --profile profiles/sbrowser-30.0.2.61.json \
  --approve-adapter action-popup-entry \
  --output .local/test-output/desktop-mobile.sixdraft
```

개발용 CRX3, 래퍼 APK, SixBundle을 직접 만들 수도 있다.

```sh
sixbridge build \
  --input fixtures/desktop-mobile-golden \
  --profile profiles/sbrowser-30.0.2.61.json \
  --approve-adapter action-popup-entry
```

CLI `build`의 APK 서명은 개발 검증용이다. 일반 사용자에게 설치할 최종
래퍼 서명과 설치 승인은 Android 관리자가 담당한다. 변환 과정에서 source를
다시 패킹하면 원본 Chrome 웹 스토어 확장 ID가 바뀔 수 있다.

입력은 로컬 소스 디렉터리, ZIP, CRX3, Chrome 웹 스토어 URL을 지원한다.
Edge 소스·ZIP·CRX3도 같은 경로를 사용하며 Edge 스토어 URL 자동 다운로드는
후속 범위다.

## Fold7 개발 검증

ADB는 개발 중 래퍼 계약과 실기기 동작을 검증하는 용도로만 사용한다.
일반 사용자 설치 절차의 전제 조건이 아니다.

```sh
sixbridge device install \
  --bundle dist/<extension-id>.six \
  --serial <adb-serial>

sixbridge device smoke \
  --bundle dist/<extension-id>.six \
  --serial <adb-serial>
```

최소 기술 게이트는 `./scripts/fold7-gate.sh <adb-serial>`로 실행할 수 있다.

생성된 소스 스냅샷, 키, 검증 증거는 Git에서 제외된 `.local/`에 저장된다.
CRX 키나 래퍼 PKCS12 키를 잃으면 확장 ID 또는 Android 업데이트 서명이
바뀌므로 안전하게 백업해야 한다.

상세 내용은 [아키텍처](docs/architecture.md),
[Fold7 검증 절차](docs/fold7-validation.md),
[PoC 검증 결과](docs/poc-results.md)를 참고한다.
