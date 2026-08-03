# Fold7 API capability probe 검증

## 목적

이 검증은 삼성 인터넷 30.x가 노출하는 namespace만 보고 지원을
추정하지 않고, `namespace.member` 호출을 Fold7에서 실제로 실행해
`VERIFIED`, `EXPOSED`, `RESTRICTED`, `FAILED`를 구분하는 절차다. 삼성
인터넷 APK는 수정하지 않으며, 실제 계정·비밀번호·방문 기록·사용자
쿠키를 수집하지 않는 안전한 case만 실행한다.

## Host 선행 검증

```bash
./gradlew :cli:installDist
./scripts/api-probe-generation.test.sh
node --test \
  scripts/api-probe-receiver.test.mjs \
  scripts/api-probe-artifact-verify.test.mjs
bash scripts/fold7-api-probe.test.sh
```

`api-probe-generation.test.sh`는 여섯 source tree, Manifest V3, 최소 권한,
catalog SHA-256, 금지 token, 결정론적 생성, CLI 호환성 분석을 한번에
검증한다. macOS CLI도 데스크탑 앱과 같은 `Jimfs`
`SecureDirectoryStream` 작업공간을 사용하므로 보안 경계를 완화하지
않고 분석한다.

## Fold7 실행

삼성 인터넷의 실제 버전과 같은 base profile을 선택한다. 2026-08-03
검증 기준은 `30.0.0.67` 이었다.

```bash
./gradlew :cli:installDist
adb_serial='adb-DEVICE_SERIAL._adb-tls-connect._tcp'
./scripts/fold7-api-probe.sh \
  --serial "$adb_serial" \
  --profile profiles/sbrowser-30.0.0.67.json
```

스크립트는 다음 여섯 그룹을 생성하고 Android 관리자로 래퍼를 만든
뒤 실기기에 설치한다.

- `advanced-runtime`
- `base-runtime`
- `identity-privacy`
- `network`
- `tab-page`
- `user-interface`

삼성 인터넷 Add-ons에서 각 probe를 활성화하고 상세 화면에서 전체
case와 현재 case 수가 같은지, `미실행 0`인지 확인한다. 사용자 gesture
case가 있으면 표시된 버튼을 직접 누른다. 모든 case가 종료된 뒤
`증거 전송`이 완료되었는지 확인한다.

## `identity-privacy` manifest 격리

`webAuthenticationProxy` 권한을 포함한 control 래퍼는 삼성 인터넷이
`동작 안 함`으로 분류했고 상세 화면을 열 수 없었다. 그 권한만 제외한
격리 래퍼는 정상 활성화되었고 나머지 6 case가 전부 종료되었다.
따라서 `webAuthenticationProxy.onRemoteSessionStateChange`는 단순 미노출이 아니라
manifest load 차단으로 기록한다.

격리 evidence는 다음 계약을 모두 포함해야 한다.

- control 래퍼의 로드 실패
- 단일 권한 제외 variant의 로드 성공
- control과 variant manifest SHA-256
- 제외한 permission/manifest key
- 실행한 variant의 전체 case evidence

## Evidence 검증과 후보 profile

runner가 출력한 session 경로를 그대로 사용한다. `find | tail`로
다른 세션을 암묵적으로 선택하지 않는다.

```bash
evidence_dir='.local/evidence/api-probe/<session-id>'
candidate='profiles/candidates/sbrowser-30.0.0.67-probed.json'

cli/build/install/sixbridge/bin/sixbridge probe verify-evidence \
  --input "$evidence_dir"
mkdir -p profiles/candidates
cli/build/install/sixbridge/bin/sixbridge probe build-profile \
  --base profiles/sbrowser-30.0.0.67.json \
  --evidence "$evidence_dir" \
  --manifest-load-isolation \
    .local/evidence/api-probe/<isolation-session>/identity-privacy.load-isolation.json \
  --variant-evidence \
    .local/evidence/api-probe/<isolation-session>/identity-privacy.json \
  --output "$candidate"
git diff --no-index \
  profiles/sbrowser-30.0.0.67.json \
  "$candidate"
```

후보는 반드시 `profiles/candidates/`에 새 파일로 저장한다. evidence,
브라우저·APK·signer provenance, 격리 결과와 기존 확장 회귀를 검토하기
전에는 base profile을 덮어쓰지 않는다. baseline 승격은 별도 승인
작업으로 다룬다.

## 2026-08-03 Fold7 결과

- 브라우저: 삼성 인터넷 `30.0.0.67`
- 장치: Fold7 `SM-F966N`
- catalog SHA-256:
  `88acdaad29e031d2ea7db63fdd137f07d196de98bb230af5b64c40859e7af746`
- 검증 member: 33
- `VERIFIED`: 18
- `EXPOSED`: 10
- `RESTRICTED`: 1
- `FAILED`: 4
- 논리 profile digest:
  `1d4b4408997d9819816de55efeb02d24d4181629794c210436a521c484d0d096`
- 후보 profile 파일 SHA-256:
  `1120d2d77e754c35b70cf01ddde73c4f81bfe061527efb96480c89d567b957ed`

`FAILED`는 `debugger.attach`, `search.query`, `userScripts.getScripts`,
`webAuthenticationProxy.onRemoteSessionStateChange`다. `RESTRICTED`는
`tts.getVoices`다. `EXPOSED`는 객체·member 노출까지만 확인했으므로
실제 의미 보존을 입증한 `VERIFIED`로 올리지 않는다.

## Rollback과 정리

- 후보 profile에 문제가 있으면 파일을 폐기하고 기존 base profile을
  계속 사용한다.
- probe 래퍼는 SIX Bridge 관리자의 표준 제거 흐름에서 사용자가
  Android 제거를 승인한다.
- Add-ons에 항목이 남으면 삼성 인터넷을 완전히 종료하고
  재실행해 상태를 다시 확인한다.
- `.local/evidence` 원본은 Git에 추가하지 않고 후보 판정의 로컬
  provenance로 보존한다.
