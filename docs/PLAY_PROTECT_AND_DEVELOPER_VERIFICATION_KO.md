# 설치 APK 신뢰 정보와 Android 보안 경고

이 문서는 2026-07-30 현재 SIX Bridge가 기기에서 생성한 설치 APK의 신뢰
정보를 어떻게 표시하고, Google Play Protect와 Android 개발자 인증을 어떻게
해석해야 하는지 정리한다.

## 설치 전 화면이 보장하는 범위

설치 전 검토 화면의 원본 Chrome Extension ID, 설치 대상 Extension ID,
package, APK SHA-256, APK signer SHA-256은 가져오기 과정에서 검증된
`.six` 보고서와 descriptor에서 전달된 값이다. 화면을 표시할 때 사용자가
저장한 APK를 다시 읽거나 hash를 다시 계산하지 않는다.

이 정보는 사용자가 설치하려는 산출물의 신원과 바이트 단위 동일성을 대조하기
위한 provenance다. 호환성 보고서와 provenance 확인은 악성 코드 검사나 보안
보증을 의미하지 않는다.

## Google Play Protect

Google의 공식 도움말에 따르면 Play Protect는 Google Play에서 받은 앱뿐
아니라 다른 출처의 앱도 검사하고, 잠재적으로 유해한 앱을 경고·비활성화·제거할
수 있다. Google이 기기·데이터·사용자에게 유해하다고 판단한 앱은 설치가
차단될 수도 있다. Google Play 밖의 미검사 앱은 코드 평가를 위해 검사를
권고받을 수 있다.

따라서 기기에서 생성한 APK에는 Android 또는 Play Protect의 보안 경고가
표시될 수 있다. 이 경고는 APK 내부의 지원되는 manifest flag로 제거할 수
있는 앱 UI가 아니며, SIX Bridge가 성공 설치를 보장할 수도 없다.

운영 원칙은 다음과 같다.

- Play Protect 비활성화나 보안 경고 우회를 사용자에게 안내하지 않는다.
- 경고가 표시되면 package, APK SHA-256, APK signer SHA-256을 검토 화면의
  값과 대조하고 설치 여부는 사용자가 결정하도록 한다.
- Play Protect가 유해 앱으로 판정하거나 설치를 차단한 경우 이를 숨기거나
  무력화하지 않는다. 잘못된 판정이라고 판단하는 배포자는 Google의 공식
  이의 제기 절차를 사용한다.

## Android 개발자 인증 일정

Android 개발자 인증은 앱의 악성 코드 검사와 동일한 제도가 아니다. 실세계의
개인·조직, package name, 앱 서명 키를 연결해 배포 주체의 책임성을 확인하는
제도다.

2026-07-30 현재 공식 문서가 밝힌 일정과 적용 범위는 다음과 같다.

- 2026년 8월: 제한 배포 계정과 미등록 앱 설치를 위한 고급 흐름의 출시가
  예정되어 있다. 제한 배포 계정은 정부 발급 신분증 없이 최대 20대의 지정
  기기에 배포하는 무료 계정 유형이다.
- 2026-09-30: 브라질, 인도네시아, 싱가포르, 태국의 모바일·태블릿에서 초기
  적용한다. 이 단계에서 설치 검증에 참여하는 스토어는 Google Play,
  HONOR App Market, OPPO App Market, Galaxy Store, Palm Store,
  V-Appstore, GetApps다.
- 2026-09-30 초기 적용은 위 참여 스토어를 통한 설치에 한정된다. 다른
  스토어나 사용자의 직접 sideload에는 그 시점의 신규 인증 요구가 아직
  적용되지 않지만, 공식 문서는 2027년 전 세계 확대에 대비하라고 안내한다.
- 2027년: 인증된 Android 기기에서 모든 앱을 대상으로 전 세계 확대할
  계획이다. FAQ는 요구 사항이 Google Play services를 통해 Android 7 이상
  인증 기기에 적용된다고 설명한다.

일정과 적용 범위는 변경될 수 있으므로 배포 정책을 결정할 때 아래 공식 문서를
다시 확인한다.

## signer 전략의 trade-off

### 온디바이스 per-device signer

각 기기가 자체 키로 wrapper APK에 서명하면 중앙 서버에 공용 private key를
보관하거나 전송하지 않아도 된다. 한 기기의 키가 유출돼도 다른 기기의 키까지
곧바로 노출되지는 않아 사고 범위가 작다.

반면 Android의 업데이트 연속성은 package name과 signer에 의존한다. 같은
package name이라도 다른 기기의 키로 서명한 APK는 기존 설치를 그대로
업데이트할 수 없다. 기기 교체·키 손실 때 동일 signer를 복구하지 못하면 기존
설치의 업데이트 연속성이 끊긴다. 또한 기기마다 signer가 달라 중앙에서 하나의
안정적인 package-signing identity를 등록·감사·지원하기 어렵다.

### 중앙 검증 signer

통제된 중앙 signer는 여러 기기에 같은 package와 signer를 제공해 업데이트
연속성, 배포물 재현, package name 및 signing key 등록을 일관되게 운영하기
쉽다. Android Developer Console은 한 package에 여러 signing key를 추가해
검증할 수 있지만, 키 수와 수명 주기를 중앙에서 관리해야 한다.

대신 중앙 private key 또는 서명 서비스가 침해되면 그 signer를 신뢰하는 모든
기기와 배포물에 영향이 퍼질 수 있다. 키 보관, 접근 통제, 감사, 교체, 복구,
서명 요청 인증이 필요한 고가치 인프라가 되며, 온디바이스 생성만으로 끝나는
구조보다 운영 의존성과 개인정보·네트워크 고려 사항이 늘어난다.

어느 전략도 Play Protect의 앱 검사나 Android 보안 경고를 면제하지 않는다.
개발자 인증은 배포 주체와 서명 신원을 확인하는 계층이고, Play Protect는
앱과 기기의 유해 동작을 검사하는 별도 계층이다.

## 공식 자료

- Google Play 고객센터, Google Play Protect:
  https://support.google.com/googleplay/answer/2812853
- Android Developers, 개발자 인증 FAQ:
  https://developer.android.com/developer-verification/guides/faq
- Android Developers, 개발자 인증 적용 범위와 일정:
  https://developer.android.com/developer-verification/guides
