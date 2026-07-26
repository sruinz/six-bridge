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

Android 관리자 debug APK 패키징:

```sh
./scripts/package-manager-debug.sh
shasum -a 256 dist/sixbridge-manager-debug.apk
```

패키징 스크립트는 전체 검증을 다시 실행해 성공한 경우에만 새 debug APK를
`dist/`에 배치한다. 검증이 실패하면 이전 APK를 배포 대상으로 남기지 않는다.

## Android 관리자와 사용자 파일

Android 관리자 1단계의 파일 역할은 다음과 같다.

| 파일 | 역할 |
|---|---|
| `sixbridge-manager.apk` | 사용자가 최초 설치하는 SIX Bridge 관리자 앱 |
| `<확장 이름>.six` | 변환 확장을 보관·공유하고 관리자에서 가져오는 사용자 파일 |
| `.six` 내부 `wrapper.apk` | Android에 실제 설치되는 확장별 래퍼 package |
| 래퍼 내부 CRX3 | 래퍼가 삼성 인터넷에 제공하는 실제 확장 본체 |

일반 사용자는 ADB를 사용하지 않는다. Android 파일 앱에서 관리자 APK를
설치한 뒤 관리자의 `.six 파일 추가`를 누르면 시스템 파일 선택기가 열린다.
여기서 기기에 저장한 `.six`를 선택하고 호환성·권한·서명·버전을 검토한 다음,
Android의 출처 허용 설정과 시스템 설치 승인 화면을 직접 확인한다.

- `compatible`: 검토 뒤 설치할 수 있다.
- `partial`: 제한 사항을 확인하는 추가 동의 뒤 설치할 수 있다.
- `blocked`, `unknown`: 설치할 수 없다.
- 업데이트: 같은 package·같은 signer·더 높은 versionCode만 허용한다.
- 제거: 표준 Android 제거 화면을 사용하고 실제 package를 다시 확인한다.
- Add-ons: 공개 `internet://extension`으로 삼성 인터넷의 전체 Add-ons
  화면을 열며 특정 확장의 활성 상태를 관리자가 추정하지 않는다.

debug APK는 개발 검증용 파일명인 `sixbridge-manager-debug.apk`로 생성된다.
일반 배포본은 릴리스 서명과 검증을 거친 `sixbridge-manager.apk`여야 한다.
상세 절차와 경계는 [Android 관리자 사용자 흐름](docs/android-manager-user-flow.md),
[Android 관리자 보안 경계](docs/android-manager-security-boundary.md),
[Fold7 관리자 검증표](docs/fold7-manager-validation.md)를 참고한다.

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
mkdir -p .local/test-output

sixbridge draft \
  --input fixtures/desktop-mobile-golden \
  --profile profiles/sbrowser-30.0.2.61.json \
  --approve-adapter action-popup-entry \
  --output .local/test-output/desktop-mobile.sixdraft
```

개발용 CRX3, 래퍼 APK, SixBundle을 직접 만들 수도 있다.

```sh
mkdir -p dist

sixbridge build \
  --input fixtures/desktop-mobile-golden \
  --profile profiles/sbrowser-30.0.2.61.json \
  --approve-adapter action-popup-entry \
  --output dist/desktop-mobile.six
```

`--output dist/desktop-mobile.six`를 지정하면 같은 basename의
`desktop-mobile.crx`, `desktop-mobile-wrapper.apk`,
`desktop-mobile.six`를 만든다. `--output`을 생략하면 canonical 입력 문자열의
SHA-256에서 만든 결정적 basename을 `dist/`에 사용한다. 산출물 파일명과
무관하게 실제 extension ID는 CRX, descriptor, 래퍼 package에 유지된다.
`draft`와 `build`는 출력 부모 폴더를 자동 생성하지 않는다. 명령 실행 전에
위 예시처럼 `mkdir -p <출력-부모>`로 만들고, 해당 폴더의 identity와 권한을
검증할 수 있게 해야 한다.

CLI `build`의 APK 서명은 개발 검증용이다. 일반 사용자에게 설치할 최종
래퍼 서명과 설치 승인은 Android 관리자가 담당한다. 변환 과정에서 source를
다시 패킹하면 원본 Chrome 웹 스토어 확장 ID가 바뀔 수 있다.

## 삼성 Add-ons 설정 진입점

`action-popup-entry` version 2는 browser profile이 삼성 Add-ons 상세
설정 메뉴의 option page 계약을 증명할 때만 제안된다. 승인하면
`manifest.json`에 기존 popup을 가리키는 `options_ui`를 추가하고
`sixbridge/action-popup-entry.v2.json`에 비실행 provenance를 기록한다.
페이지 상시 `확장 열기` 버튼과 popup runtime message shim은 생성하지
않는다.

자동 selection 어댑터는
`sixbridge/desktop-selection-event-bridge.v1.js`를 생성한다. 두 생성
asset은 삼성 loader가 거부하는 선행 밑줄 root를 피하며, 변환 산출물에는
`_sixbridge/` 같은 선행 밑줄 root entry가 없어야 한다.

삼성 상세 화면의 `six_uninstall`은 `web_store` 계열 확장의 브라우저 내부
CRX를 제거하는 기능이므로 SIX Bridge의 `dev_ext` 래퍼 제거에 사용하지
않는다. 래퍼 APK 제거는 다음 Android 관리자가
`Intent.ACTION_DELETE`와 `package:<wrapperPackage>`로 요청한다.

입력은 로컬 소스 디렉터리, ZIP, CRX3, Chrome 웹 스토어 URL을 지원한다.
Edge 소스·ZIP·CRX3도 같은 경로를 사용하며 Edge 스토어 URL 자동 다운로드는
후속 범위다.

## Fold7 개발 검증

ADB는 개발 중 래퍼 계약과 실기기 동작을 검증하는 용도로만 사용한다.
일반 사용자 설치 절차의 전제 조건이 아니다.

```sh
sixbridge device install \
  --bundle dist/<bundle-basename>.six \
  --serial <adb-serial>

sixbridge device smoke \
  --bundle dist/<bundle-basename>.six \
  --serial <adb-serial>
```

확장별 공용 검증 runner와 AI 레시피 추출기·Video Download to NAS
wrapper는 다음처럼 실행한다. 실제 source 경로는 저장소에 고정하지 않고
호출할 때 전달한다.

```sh
source_dir='/workspace/T5다운로드/vscode/AI Recipe Extractor'
serial='adb-DEVICE_SERIAL._adb-tls-connect._tcp'

./scripts/fold7-extension-validation.sh \
  --case-id meemjdgdjhjfhcfchppfapgifiipjnlo \
  --source "$source_dir" \
  --profile profiles/sbrowser-30.0.0.67.json \
  --host-only

./scripts/fold7-ai-recipe.sh \
  --source "$source_dir" \
  --profile profiles/sbrowser-30.0.0.67.json \
  --serial "$serial"

source_dir='/workspace/T5다운로드/작업중/vdtn/Video-Download-to-NAS-main/Video-Download-to-NAS-Extension-main/video download to nas'

./scripts/fold7-video-nas.sh \
  --source "$source_dir" \
  --profile profiles/sbrowser-30.0.0.67.json \
  --host-only

./scripts/fold7-video-nas.sh \
  --source "$source_dir" \
  --profile profiles/sbrowser-30.0.0.67.json \
  --serial "$serial"
```

삼성 인터넷의 `더보기 메뉴 사용자 설정` 또는 `툴바 사용자 설정`에서
확장 action을 배치할 수 있다. AI 레시피 추출기는 이 기본 action으로 현재
YouTube 페이지를 전달하므로, YouTube가 길게 누르기 동작을 가로채더라도
별도 플로팅 버튼이 필요하지 않다. 일반 페이지의 링크·선택 텍스트·이미지·
동영상에서는 삼성 기본 컨텍스트 메뉴의 확장 항목도 그대로 사용한다.

Video Download to NAS의 Config URL 인증과 page·HLS 요청은 일회성 LAN
receiver로 검증할 수 있다.

```sh
node scripts/video-nas-lan-receiver.mjs \
  --mode page \
  --advertise-host <Fold7에서-접근-가능한-LAN-IP> \
  --expected-url <검증할-페이지-URL> \
  --evidence .local/evidence/<실행명>-video-nas-page.json

node scripts/video-nas-lan-receiver.mjs \
  --mode hls \
  --advertise-host <Fold7에서-접근-가능한-LAN-IP> \
  --evidence .local/evidence/<실행명>-video-nas-hls.json
```

receiver는 임의의 빈 포트와 기본 10분 제한으로 실행된다. `page` 모드는
stdout의 `configUrl`을 VDtN options에 저장한 뒤 검증할 페이지에서 action의
`PAGE` 항목을 선택한다. `hls` 모드는 같은 `configUrl`을 저장하고 stdout의
`probeUrl`을 연 뒤 action에서 `HLS` 항목을 선택한다. 두 URL에는 일회성
비밀값이 있으므로 stdout에서 사용자에게만 전달하고 문서·로그·Git에는
복사하지 않는다.

최소 기술 게이트는 `./scripts/fold7-gate.sh <adb-serial>`로 실행할 수 있다.

생성된 소스 스냅샷, 키, 검증 증거는 Git에서 제외된 `.local/`에 저장된다.
CRX 키나 래퍼 PKCS12 키를 잃으면 확장 ID 또는 Android 업데이트 서명이
바뀌므로 안전하게 백업해야 한다.

상세 내용은 [아키텍처](docs/architecture.md),
[Fold7 검증 절차](docs/fold7-validation.md),
[PoC 검증 결과](docs/poc-results.md)를 참고한다.
