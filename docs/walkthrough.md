
## 2026-11-23 삼성 브라우저 31.0.0.81 공식 지원 및 확장 덮어쓰기 연속성 검증 결과

### 1. 원인 및 실기기 분석
- Fold7(Android 17 / One UI 9.0)의 `com.sec.android.app.sbrowser`가 `30.x`에서 `31.0.0.81`(`versionCode=1310081502`)로 업데이트되면서 `AndroidSamsungBrowserProfileProvider`가 `SUPPORTED_MAJOR_VERSION = 30` 검사에서 `unsupported_major:31`로 거부해 `ConversionFailureCode.PROFILE_UNKNOWN` 오류가 발생함.
- Fold7에서 실제 `base.apk`를 추출해 `BrowserProfileExtractor`로 검증한 결과:
  - 서명 SHA-256: `34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42` (기존 삼성 서명 일치)
  - 래퍼 계약 마커 4종(`discoveryUri`, `extensionMetadata`, `contentProvider`, `devExtensionPath`): 모두 `true`
  - `supportedFeatures`: `extension.options-page.user-accessible` 유지
  - `detectedApis`: 69개 (`30.0.2.61` 대비 12개 추가, `sixLogging` 1개 제거, `POC_SUPPORTED_APIS` 8종 유지)

### 2. 구현 변경 사항
1. **`core` 다중 메이저 baseline 지원**
   - `BrowserProfile`: 기존 30 baseline 상수/ABI를 보존하면서 `BASELINE_31_VERSION("31.0.0.81")`, `BASELINE_31_APK_SHA256`, `BASELINE_31_APIS`, `MajorBaseline`, `MAJOR_BASELINES`, `SUPPORTED_MAJOR_VERSIONS`, `baselineForMajor()`, `isSupportedMajorVersion()` 추가.
   - `BrowserArchiveEvidenceScanner`: 메이저 버전별 baseline을 조회해 API diff 및 split 검증 수행.
   - `profiles/sbrowser-31.0.0.81.json` 추가 (`support=supported`) 및 `desktop-app` 내장 프로필 목록(`DesktopProfileCatalog`)에 등록.
2. **메이저 버전 간 확장 덮어쓰기 신원 연속성**
   - `AndroidSamsungBrowserProfileProvider`: `BrowserProfile.isSupportedMajorVersion`을 사용해 `30.x`/`31.x` 모두 지원.
   - `ConversionIdentityDao` / `ConversionIdentityLedger` / `DefaultOnDeviceConversionPorts` / `OnDeviceConversionCoordinator`:
     - 안정 신원(`store:`, `manifest:`, `project:`)은 현재 메이저 키가 DB에 없을 때 기존 `ACTIVE`(및 `PASSTHROUGH`/`PREPARED`의 `RETIRED`) 신원을 찾아 기존 `InstallationIdentityKey`(`identitySha256`, `extensionId`, `packageName`, `crxKeyAlias`)를 재사용해 덮어쓰기 업데이트하도록 변경.
     - 불안정 ZIP의 `listExistingTargets` 및 `UseExisting`도 이전 메이저 버전에서 생성된 `ACTIVE` `CONVERTED` 대상을 조회·선택할 수 있도록 확장.
   - `ExtensionKeyManager` / `ConversionPipeline`: Desktop/CLI 변환에서도 이전 메이저 키가 있으면 재사용.
3. **제품 버전 상승**: `0.1.2`(`versionCode=3`) → `0.1.3`(`versionCode=4`)

### 3. 검증 결과
- CLI `profile` 추출: `profiles/sbrowser-31.0.0.81.json` 생성, `support=supported`, `apis=8` 확인.
- 전체 단위 테스트 통과:
  - `:bundle-contract:test`, `:core:test`, `:cli:test`, `:api-probe:test` (JDK 26) — **PASS**
  - `:desktop-app:test`, `:android-manager:testDebugUnitTest` (JDK 17 + JDK 26 secure-zip host tests) — **PASS**
- 릴리스 서명 APK 빌드 및 Fold7 설치 검증:
  - `:android-manager:assembleRelease` (`lintVitalRelease` 포함) — **BUILD SUCCESSFUL**
  - `adb -s 192.0.2.10:37201 install -r android-manager-release.apk` — **Success** (`versionCode=4`, `versionName=0.1.3`, 기존 릴리스 서명 `38e2d04c` 일치)
  - 앱 실행(`dev.sixbridge.manager/.MainActivity`) 후 크래시 없이 기존 설치 확장 래퍼(`Video Download to NAS` `versionCode 2` 등) 정상 표시 확인.
