# Android 첫 화면 토큰 오류 조사

조사 기준: 2026-09-05, GitHub `origin/main`의 `9d06f80`.

## 앱 구조와 첫 조회 경로

- `app/`: FastAPI 기반 웹·데스크톱 백엔드, KIS/Toss API 클라이언트, 다계좌 집계 서비스.
- `android-app/`: Kotlin/Compose 앱. 잔고 조회는 Python 서버를 거치지 않고 `KisRepository`에서 증권사 API를 호출한다. 중앙 주문 서버와 Toss 조회 프록시는 선택 기능이다.
- `Navigation.kt`: PIN 잠금 해제 후 계좌 프로필로 `KisRepository`를 생성한다.
- `PortfolioScreen.kt`: 최초 진입 시 `fetchDashboard()`를 호출한다.
- `KisRepository.kt`: 등록 계좌들을 병렬 조회하고, 각 KIS 계좌의 국내·해외 잔고도 병렬 조회한다. 해외는 미국·일본 조회가 추가로 병렬 실행된다.
- `SettingsManager.kt`: 계좌별 자격증명 범위에 따라 토큰을 DataStore에 저장한다.

## 코드에서 확인된 결함

사용자는 일반계좌와 연금계좌에 같은 KIS 앱키를 등록했다고 확인했다.

1. 메모리 캐시는 계좌 ID, 저장 캐시는 계좌번호·상품코드까지 포함한 범위를 사용했다. 반면 `/oauth2/tokenP` 발급 요청에는 앱키·시크릿만 들어간다. 같은 인증정보를 사용하는 일반계좌와 연금계좌도 별도 토큰을 발급·갱신해 첫 병렬 조회에서 중복 발급과 서로 다른 캐시 상태가 발생할 수 있었다.
2. 토큰 오류 처리에서 `clearAuthToken()`을 호출해 **모든 계좌의 저장 토큰**을 삭제했다. 한 계좌의 만료·거절 응답이 아직 조회를 시작하지 않은 연금계좌의 정상 토큰까지 없앨 수 있었다.
3. 메모리 토큰의 비교·삭제와 저장 토큰 삭제는 `tokenMutex` 밖에서 실행하고, 발급만 잠금으로 보호했다. 여러 요청이 같은 이전 토큰으로 실패하면, 늦게 처리된 오류가 다른 요청이 발급·저장한 새 토큰을 삭제하는 순서가 가능했다.
4. `isRateLimitError()`의 `code >= 429` 조건이 HTTP 500 인증 오류와 일반 서버 오류까지 호출량 제한으로 분류했다. 인증 복구보다 먼저 불필요한 지연 재시도를 수행했다.

첫 화면은 여러 요청이 동시에 시작되므로 이러한 결함에 노출된다. 특정 계좌의 첫 토큰이 서버에서 거절된 원인 자체는 실제 기기의 오류 코드·로그 없이 확정할 수 없다. 이 조사는 연금계좌의 권한이나 상품코드가 잘못됐다고 단정하지 않는다.

## 수정 원칙

- `AuthTokenCoordinator`에서 토큰 조회·비교·무효화·발급·저장을 같은 잠금으로 보호한다.
- 같은 KIS 앱키·시크릿을 사용하는 계좌는 토큰 발급·갱신을 공유하고, 다른 인증정보는 분리한다.
- 기존 계좌별 저장 항목은 유지한다. 같은 인증정보에 속한 항목 중 만료 여유가 60초 이상인 가장 최근 발급 토큰을 선택하고, 새 토큰 저장·무효화는 관련 항목을 한 번의 DataStore 트랜잭션으로 처리한다.
- 이전 토큰을 거절한 응답이 늦게 도착하면 이미 갱신된 토큰을 재사용한다.
- 오류 복구 시 같은 인증정보를 사용하는 계좌들의 저장 토큰만 함께 무효화한다. 설정 초기화 등에 필요한 전체 삭제는 유지한다.
- 동일한 토큰 문자열이 재발급되는 경우에도 60초 안의 성공한 복구는 공유한다. 시간이 지나거나 토큰이 만료되면 다시 복구할 수 있다. KIS 공식 샘플에도 일정 시간 내 재발급 시 같은 토큰값을 유지한다고 설명되어 있다. [공식 인증 샘플](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/kis_auth.py)
- `KisRetryPolicy`에서 인증 오류를 우선 판별하고, 실제 호출량 제한에만 지연 재시도를 적용한다.
- 요청별 인증 재시도는 한 번으로 제한한다. 무한 재발급으로 오류를 숨기지 않는다.

## 검증

Android JVM 테스트는 실제 조정·저장·오류 분류 함수를 호출하고, DataStore 입출력과 토큰 발급은 가짜 구현으로 바꿔 동시 요청과 오류 응답을 재현한다. 증권사 실계좌 API나 주문 API를 호출하지 않는다.

- `SharedAuthTokenCoordinatorTest`: 같은 키의 일반·연금계좌 첫 발급 1회, 두 계좌가 이전 토큰을 사용 중인 동시 갱신, 기존 캐시와 레거시 항목 처리, 다른 키·시크릿·브로커 분리.
- `AuthTokenCoordinatorTest`: 잠금 경합, 늦은 응답, 동일 문자열 재발급과 60초 경계, 만료 여유, 발급 실패.
- `AuthTokenPersistenceTest`: 관련 항목만 무효화, 다른 계좌·설정 보존, 레거시 토큰 재등장 방지.
- `KisRetryPolicyTest`: HTTP 500 인증 오류 우선 복구, 반복 오류 1회 제한, 실제 호출량 제한과 일반 서버 오류 구분.

2026-09-05 검증 결과: 신규 회귀 테스트 27개를 포함한 Android 전체 단위 테스트 **92개 통과**(실패·오류·건너뜀 0), `assembleDebug` 성공. 이전 결함을 임시로 다시 적용한 검증에서는 테스트가 실패했고, 모든 임시 변경을 복원한 최종 코드에서 통과했다. 실제 Android 기기와 증권사 응답을 이용한 종단 검증은 수행하지 않았다.

```powershell
$env:JAVA_HOME = 'C:\Users\torch\AppData\Local\Programs\MicrosoftJDK17'
$env:ANDROID_HOME = 'C:\Users\torch\AppData\Local\Android\Sdk'
cd android-app
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

디버그 APK는 `.debug` 애플리케이션 ID로 빌드되어 정식 앱과 별도로 설치된다. 이 APK에서 확인하려면 테스트할 계좌를 등록해야 한다. 실기기에서는 같은 앱키로 일반·연금계좌를 등록한 뒤 첫 실행·앱 재시작·새로고침에서 잔고가 표시되는지 확인한다. 오류가 남으면 `KIS_TOKEN_REFRESH_FAILED`, `KIS_HTTP_ERROR`, `KIS_API_ERROR`의 거래 ID와 `msg_cd`를 확인한다. API 키·토큰·계좌번호 원문은 공유하지 않는다.
