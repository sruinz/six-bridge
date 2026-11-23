
## 2026-11-23 삼성 브라우저 31.0.0.81 공식 지원 및 메이저 버전 간 확장 덮어쓰기 업데이트 계획

### 1. 실기기 증거 요약 (Fold7, Android 17 / One UI 9.0)
- 설치 패키지: `com.sec.android.app.sbrowser`
- `versionName`: `31.0.0.81` (`versionCode`: `1310081502`, `splits=[base]`)
- `sourceApkSha256`: `7e0a2d9b02f6fc4f0d86aab894d6741a644cb17027415a2f94d4e8e6fba4e396`
- `sourceApkSignerSha256`: `34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42` (기존 삼성 서명과 일치)
- `wrapperContract`: 4종 모두 `true` (`discoveryUri`, `extensionMetadata`, `contentProvider`, `devExtensionPath`)
- `supportedFeatures`: `["extension.options-page.user-accessible"]`
- `detectedApis`: 69개 (`30.0.2.61` 대비 추가 12개: `bookmarks`, `browsingData`, `downloads`, `downloadsInternal`, `feedbackPrivate`, `idltest`, `instanceID`, `power`, `readingList`, `system.network`, `test`, `topSites` / 제거 1개: `sixLogging`). `POC_SUPPORTED_APIS` 8종은 모두 유지됨.

### 2. 변경 범위
1. **`core` 프로필 다중 메이저 baseline 지원**
   - `BrowserProfile`: 기존 `SUPPORTED_MAJOR_VERSION = 30`, `BASELINE_VERSION`, `BASELINE_APK_SHA256`, `BASELINE_APIS`를 ABI/테스트 호환용으로 유지하면서 `BASELINE_31_VERSION`, `BASELINE_31_APK_SHA256`, `BASELINE_31_APIS`와 `MajorBaseline` 조회 API(`SUPPORTED_MAJOR_VERSIONS`, `baselineForMajor`, `isSupportedMajorVersion`)를 추가.
   - `BrowserArchiveEvidenceScanner`: 메이저 버전에 맞는 baseline을 조회해 API diff·split 검증 수행.
   - `profiles/sbrowser-31.0.0.81.json` 및 `desktop-app/src/main/resources/profiles/builtin/sbrowser-31.0.0.81.json` 추가.
2. **`android-manager` 프로필 허용 및 메이저 버전 간 확장 덮어쓰기 신원 연속성**
   - `AndroidSamsungBrowserProfileProvider`: `BrowserProfile.isSupportedMajorVersion(majorVersion)`로 30/31 모두 허용.
   - `ConversionIdentityDao` / `ConversionIdentityLedger` / `DefaultOnDeviceConversionPorts` / `OnDeviceConversionCoordinator`:
     - 안정 신원(`store:`, `manifest:`, `project:`) 변환 시 현재 메이저 버전 키가 DB에 없으면 기존 `ACTIVE` 신원(및 `PASSTHROUGH`/`PREPARED`의 `RETIRED` 신원)을 찾아 기존 `InstallationIdentityKey`(`identitySha256`, `extensionId`, `packageName`, `crxKeyAlias`)를 그대로 재사용해 같은 패키지로 덮어쓰기 업데이트되도록 처리.
     - 불안정 ZIP의 `listExistingTargets` / `UseExisting`도 이전 메이저 버전에서 생성된 `ACTIVE` `CONVERTED` 대상을 조회·선택할 수 있도록 확장.
     - `IdentityRequirement.Stable` 프리뷰와 `forceChallenge` 역시 재사용되는 신원과 일치하도록 연동.
3. **버전 상승 및 검증**
   - `0.1.2` (`versionCode = 3`) → `0.1.3` (`versionCode = 4`)
   - 전체 단위 테스트(`:core:test`, `:android-manager:testDebugUnitTest`, `:cli:test`, `:desktop-app:test`, `:api-probe:test`) 실행.
   - 로컬 릴리스 서명 APK 빌드 후 Fold7에 덮어쓰기 설치 및 실기기 검증.
