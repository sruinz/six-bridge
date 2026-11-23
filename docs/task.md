
## 2026-11-23 삼성 브라우저 31.0.0.81 공식 지원 및 메이저 버전 간 확장 덮어쓰기 연속성

- [x] Fold7 무선 ADB 연결 및 설치된 `com.sec.android.app.sbrowser` (`31.0.0.81`, `versionCode=1310081502`) APK 추출·프로필 분석
- [ ] `core` 다중 메이저 baseline 지원 추가 (`30.0.2.61` + `31.0.0.81`) 및 `profiles/sbrowser-31.0.0.81.json` / `desktop-app` 내장 프로필 반영
- [ ] `android-manager` 프로필 제공자에서 `31.x` 허용 및 메이저 버전 간 기존 확장 신원 재사용(덮어쓰기 업데이트) 구현
- [ ] 단위 테스트 추가·갱신 및 전체 모듈 테스트 통과 확인
- [ ] 제품 버전 `0.1.3` (`versionCode = 4`) 상승
- [ ] 로컬 릴리스 서명 APK 빌드 후 Fold7에 덮어쓰기 설치 및 실기기 검증
- [ ] `docs/walkthrough.md` 결과 기록 및 한글 로컬 깃 커밋

### 2026-11-23 완료 상태
- [x] `core` 다중 메이저 baseline 지원 추가 (`30.0.2.61` + `31.0.0.81`) 및 `profiles/sbrowser-31.0.0.81.json` / `desktop-app` 내장 프로필 반영
- [x] `android-manager` 프로필 제공자에서 `31.x` 허용 및 메이저 버전 간 기존 확장 신원 재사용(덮어쓰기 업데이트) 구현
- [x] 단위 테스트 추가·갱신 및 전체 모듈 테스트 통과 확인 (`bundle-contract`, `core`, `cli`, `api-probe`, `desktop-app`, `android-manager`)
- [x] 제품 버전 `0.1.3` (`versionCode = 4`) 상승
- [x] 로컬 릴리스 서명 APK 빌드 후 Fold7에 덮어쓰기 설치 및 실행 검증 완료
