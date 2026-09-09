# Android 종목 인사이트 구현·디자인 승인안

작성: 2026-09-09 KST · 기준: 일반 릴리스 v1.9.5 / main `fdaea1e`

**제안: PC 없이 실행되는 Android 앱에 전체 화면 종목 인사이트를 추가하고, 같은 릴리스에서 SaveTicker 인증·저장·잠금 경계를 보완한다.** 기존 앱의 색상·글꼴·헤더·전환을 유지하며 웹에서 제공하는 정보의 의미와 정밀도를 모바일에서도 보존한다.

이 문서는 승인된 설계 기준을 보존한다. 사용자 승인 후 v1.9.6 구현을 진행했으며 최신 상태는 [구현·검증 기록](android-insight-validation.md)에 정리했다. HTML 시안은 예시 데이터로 동작하며 계정 입력·로그인·API 통신을 하지 않는다.

- [클릭 가능한 디자인](../design/android-insight/index.html)
- [디자인 규격과 Compose 대응](../design/android-insight/DESIGN.md)
- [웹 데이터·시각화 계약](insight-visualization.md)

## 1. 확인된 가능성과 출시 전 남은 검증

| 항목 | 확인 결과 / 구현 결정 |
| --- | --- |
| 독립 실행 | 실제 Android API 35 에뮬레이터의 Dalvik + OkHttp 4.12에서 직접 연결 검증 완료. PC 백엔드를 필수로 두지 않는다. |
| 인증·정보 API | 사용자 승인 범위의 검증에서 로그인 HTTP 200, AVGO의 7개 정보 endpoint 모두 HTTP 200. 비밀번호·쿠키를 결과 파일이나 로그에 기록하지 않았다. |
| 가격 데이터 | `bars?range=1y&interval=day` HTTP 200, 252개 OHLCV. 수치 누락·중복 시각·가격 범위 모순·음수 거래량 없음, 오름차순 확인. |
| 확인 범위 | 일봉 표본 2025-09-05~2026-09-04. 수정주가 여부, 거래소 날짜 해석, 진행 중 당일 봉, 다른 종목·시장과 제한 정책은 출시 전 검증한다. |
| 첫 차트 범위 | 확인된 1년 일봉을 받아 1M/3M/6M/1Y로 잘라 표시. 선/캔들 전환, 거래량, 선택 봉 OHLCV. 분봉·실시간 캔들·1년 초과는 이번 범위 밖이다. |
| 기존 웹과 차이 | 웹의 가격 영역은 TradingView이며 `/api/asset-insight`의 `history`는 빈 배열이다. Android용 bars adapter를 별도로 구현한다. |
| 현재 SDK | minSdk 26 / compileSdk 36 / targetSdk 34. API 35는 검증 기기 수준이다. 이번 기능에 targetSdk 변경을 묵시적으로 포함하지 않는다. |

관측된 SaveTicker 웹 API는 Android에서 기술적으로 동작하지만 장기 지원을 보장하는 공개 계약으로 간주하지 않는다. 공급자 변경은 독립 adapter에서 처리하고, 지원되지 않는 시장·종목은 명시적으로 표시한다.

## 2. 화면 흐름과 시각 규격

`보유종목 → 기존 보유 상세 → 종목 인사이트 → 뒤로가기 시 기존 위치 복원`

SaveTicker 연결 설정 진입점은 `설정 → 연결 → SaveTicker`에 둔다. 보유 상세에는 인사이트 진입점만 둔다. 미연결 상태에서 인사이트를 누르면 연결이 필요하다는 안내와 함께 연결 설정 화면으로 바로 이동한다. 연결 성공 후 원래 종목 인사이트를 열고, 취소하거나 뒤로 가면 보유 상세 위치를 복원한다. 설정에서 직접 진입했을 때는 뒤로 가면 설정으로 돌아간다. 하단 주 탐색 바는 기존 상세 화면처럼 숨긴다.

화면 순서는 헤더, **가격 차트**, 정렬된 선택 일봉 수치, 현재가·종목 설명, 개요·재무·애널리스트·옵션·내부자·뉴스, **데이터 기준 시점**이다. 헤더에는 뒤로가기, 종목명, 새로고침을 둔다. 상단 헤더와 화면 아래 고정 섹션 탐색은 기존 앱의 둥근 Liquid Glass 재질을 적용한다. 하단 캡슐에서 본문의 해당 위치로 이동하며 페이지 좌우 스와이프는 사용하지 않는다. 선택 일봉의 날짜와 이전·다음 조작은 한 행, 시가·고가·저가·종가는 동일 폭 네 열, 거래량은 별도의 정렬된 행으로 배치한다.

기존 `DashboardColors`와 `MaterialTheme`를 그대로 사용한다. 다크 배경 `#0E0F12`, 강조 `#91BDFF`; 라이트 배경 `#F6F7F9`, 강조 `#245BD6`. 기존 Manrope와 한국어 시스템 폴백, 본문 14~16sp, 섹션 제목 20sp, 좌우 20dp / 섹션 간 24dp를 기준으로 한다. Liquid Glass는 헤더와 조작부에 적용하고 데이터는 구분선과 여백으로 나눈다.

- 터치 영역 최소 48dp. 작은 화면에서 라벨과 값은 줄바꿈하고 큰 글꼴에서도 내용을 자르지 않는다.
- 상승/하락은 기존 앱의 의미 색을 따르고 부호·문구를 병기한다. 콜/풋은 모든 차트에서 같은 역할 색을 사용한다.
- 상세 진입 약 220ms, 펼침 약 180ms. 시스템 애니메이션 축소 설정을 존중한다.
- 차트의 드래그 선택 외에 이전/다음 값 버튼과 정확한 표를 제공한다. TalkBack에 날짜·값·단위·변화를 읽어준다.
- 가로 막대나 차트에 정확한 값을 다 적어 겹치게 하지 않는다. 선택 값과 펼침 표에서 원래 정밀도를 확인한다.

## 3. 섹션별 구현 계약

| 영역 | 기본 화면 | 전체 데이터·상호작용 |
| --- | --- | --- |
| 가격 | 일봉 가격 차트와 거래량, 기간/선·캔들 선택 | 터치 선택 날짜·OHLCV, 버튼 탐색과 정확한 값 표. 일봉 기준일과 현재가 시각 별도 표시 |
| 종목·개요 | 회사명·티커·시장·업종, 현재가/등락, 당일·52주 범위, 8개 핵심 지표 | 제공된 비교값·업종 평균·전년 대비·기준 분기·공매도 변화를 라벨-값 행으로 모두 보존 |
| 재무 | 회계 분기 매출 막대와 선택 분기 YoY | 제공된 모든 분기의 기간·매출·YoY·대체 표시/방향. 정확한 값 표를 펼침. 제공되지 않은 영업이익·FCF 차트를 만들지 않음 |
| 애널리스트 | 매수/보유/매도 구성, 현재가가 포함된 목표가 범위 | 제공된 모든 증권사·날짜·의견·이전/현재 목표가·변화 행 |
| 옵션 | 거래량/PCR, 거래량·OI·프리미엄 콜/풋 비중 | 동일 시각 3/7/30일 평균 대비 비율, 만기, Max Pain·Call/Put Wall·Gamma Flip, Net GEX와 1% 변화 감마 각각의 단위·기준 시각 |
| 내부자 | 기간·매수/매도 건수·부호 있는 순금액 | 공급된 모든 거래의 날짜·이름·직책·거래 코드·금액. 제공되지 않은 주식 수·단가를 추정하지 않음 |
| 뉴스 | 제목·매체·날짜·원문 이동 | 서버 계약상 최대 3건인 공급 목록을 추가로 자르지 않음. HTTPS 링크 검증 후 외부 브라우저로 이동 |

공급자 필드의 정확한 중첩 allowlist와 DTO fixture를 구현 첫 단계에서 확정한다. 긴 표는 모바일 라벨-값 목록으로 전환하되 `전체 N건 보기`를 통해 모든 공급 행을 제공한다. 예시 시안의 행 수·숫자는 실제 공급량이나 현재 시장 데이터가 아니다.

현재 관측한 핵심 계약은 다음과 같다. 시안과 구현 fixture도 이 필드 범위에 맞춘다.

| 공급 영역 | 보존할 필드 |
| --- | --- |
| header | `marketStatus`, `asOf`, `price`, `changePercent`, `changeBasis`, `turnover`, `dayRange`, `week52Range`, `extendedHours`, `marketCap`, `marketCapRank`, `turnoverRank` |
| key_metrics | `per`, `eps`, `revenueTtm`, `dividendYield`, `roe`, `shortInterestPct`, `daysToCover`, `shortAsOf`, `shortBasis`, `asOf`, `periodLabel` — 시가총액을 포함해 핵심 8개 |
| revenue | `source`, 각 분기의 `label/revenue/yoy/replaceText/direction` |
| analyst | `analystCount/dist/label/target/upsidePct/provider/asOf`, 최근 행의 `firm/firmKo/target/prevTarget/rating/prevRating/action/at/prevSource/upsidePct/isNew` |
| insider | `window/buyCount/sellCount/netValue/label/asOf`, 최근 행의 `name/title/value/transactionDate/transactionCode` |
| options | `optionable`, 현재·스냅샷·배치 시각과 전일/잠정 상태, `nearestExpiry/daysToExpiry/maxPain/volume/putCallRatioVolume/putCallRatioOpenInterest`, 세 비중, `optionVolumeVsAvg`, `referencePrice/netGammaExposure/gammaPer1Pct/callWall/putWall/gammaFlip` |
| news | `items[].title/publisher/link/published_at` |

`nextPollAfterMs` 같은 운영 필드는 요청 정책에만 사용한다. 중복·legacy 비율 필드(`vsAvg3d/7d/30d`)는 유효한 동일 시각 비교 계약으로 정규화하고 서로 다른 분모의 비율을 혼합하지 않는다.

### 수치 해석에서 반드시 지킬 사항

1. `null`과 0을 구별한다. 누락은 `제공 안 됨`으로 표시하고 거짓 0이나 차트를 만들지 않는다.
2. 이미 퍼센트인 원본 값에 100을 다시 곱하지 않는다. ratio와 percentage를 DTO에서 구별한다.
3. 공급자의 `revenueTtm`이라는 키만 믿지 않는다. 관측 응답의 `periodLabel`이 회계 분기이면 **분기 매출**로 표시한다.
4. 콜·풋 값이 한쪽 누락되면 100% 누적 비중으로 만들지 않는다.
5. `optionVolumeVsAvg.windows[d3/d7/d30].available=true`일 때만 `ratioPct`를 사용한다. 기준선 100%, 동일 시각 비교임을 표시한다.
6. 옵션 거래량의 전체 만기 범위는 값 옆에 유지한다. 전일 스냅샷·전일 배치·잠정 집계 상태와 날짜는 맨 아래 데이터 기준 시점 영역에서 해당 항목 이름과 함께 표시한다.
7. 주가와 GEX는 별도 축/도표로 표현한다. `gammaPer1Pct`의 기초자산 1% 변화당 USD 의미를 Net GEX와 혼동하지 않는다.
8. 내부자 기간 총계는 공급된 총계 사용. 제한된 최근 거래 행을 더해 기간 총계로 제시하지 않는다.
9. 섹션별 source/as-of/provisional 상태는 맨 아래 데이터 기준 시점 영역에 모은다. v1.9.7 사용자 요청에 따라 기본 접힘으로 변경하며, 펼치면 섹션별 공급원·상태·기준 시각·스냅샷·배치를 정렬해 표시한다. 차트 선택일, 회계 분기 축, 개별 평가·거래·뉴스 발행일 등 데이터 자체의 날짜는 원래 행에 유지한다. 다른 공급자 값을 조용히 합치지 않는다.

## 4. 연결과 오류 경험

| 상태 | 표시·동작 |
| --- | --- |
| 미연결 | 인사이트 선택 시 안내와 함께 연결 설정으로 자동 이동. SaveTicker 이메일/비밀번호, 저장 선택, 연결 결과를 처리한 뒤 원래 종목 인사이트로 복귀 |
| 정상 | 마지막 갱신 시각은 통합 기준 시점 영역에 표시. 당겨서/헤더 새로고침은 동일 요청 병합과 재시도 제한을 준수 |
| 부분 성공 | 성공한 섹션은 표시하고 실패 섹션에 개별 오류·재시도. 전체 화면을 빈 상태로 바꾸지 않음 |
| 오프라인 | 같은 유효 연결 세션의 마지막 성공값을 표시하고, 상단에는 오프라인 상태를, 맨 아래에는 원래 기준 시각을 표시. 캐시가 없으면 명확한 빈 상태 |
| 인증 만료 | 재로그인 1회 실패 시 `다시 연결`. 진행 중 요청과 쿠키·메모리 데이터를 폐기. 만료된 데이터를 정상값으로 표시하지 않음 |
| 429 | 공급자 재시도 지시를 우선 적용하고 일시 대기 표시. 새로고침을 연속 요청으로 증폭하지 않음 |
| 미지원·빈 응답 | `지원하지 않는 종목/시장`과 `아직 제공된 데이터 없음`을 구별 |
| 연결 해제 | 현재 요청·쿠키·캐시 제거, 저장 비밀과 키 제거. 다시 사용하려면 연결 필요 |
| 캐시 삭제 | 데이터만 비우고 현재 연결은 유지. 기능 이름과 실제 범위를 일치시킴 |

연결 화면의 **“이 기기에 연결 정보 저장”은 기본 꺼짐**이다. 꺼짐이면 연결 정보와 쿠키는 현재 잠금 해제 세션 메모리에만 둔다. 켜짐이면 기기의 안전한 저장소에 암호화하며 기기 화면 잠금이 없는 경우 저장을 허용하지 않는다. 시안의 입력란은 읽기 전용 예시이고 입력·저장·인증을 실제로 수행하지 않는다.

제안 잠금 정책: 앱이 실제 백그라운드로 이동하면 앱을 잠그고 복귀 시 PIN을 요구한다. 뉴스 외부 이동 후에도 적용된다. 단순 화면 교체나 회전은 잠금으로 보지 않는다. 저장을 꺼둔 연결은 잠금 후 다시 입력해야 하며, 저장된 연결은 PIN 해제 후 복호화하여 새 세션을 만든다.

## 5. 보안 보완 범위

### Desktop: 인증 우회와 세션 수명

현재 `/api/asset-insight`에는 명시적 dashboard 세션 검증이 없고 잠금/로그아웃 후에도 전역 SaveTicker 연결과 캐시가 남는다. 정상 데스크톱 실행은 loopback으로 제한되지만, 서버가 외부에 열리면 미로그인 호출이 소유자의 연결로 데이터를 가져올 수 있다. 비밀번호가 응답으로 유출되었다는 의미는 아니다.

- 인증을 캐시 조회와 공급자 호출 **앞에서** 검사하며 실제 HTTP 401을 유지한다. 넓은 예외 처리에서 인증 실패를 삼키지 않는다.
- dashboard session별 insight context가 credentials lease·connector·쿠키·두 캐시·진행 중 요청을 소유한다.
- 잠금/로그아웃 시 generation 폐기 → 취소 → 자격정보 메모리 참조·쿠키·캐시 제거. 전체 설정 초기화만 모든 context와 저장 정보를 제거한다.
- `await` 후, 후속 공급자 호출 전, 쿠키 채택·캐시 기록·응답 직전에 identity/generation을 재검사한다. 취소된 Python thread가 물리적으로 계속 끝나더라도 결과를 채택하지 않는다.
- 로그아웃한 요청은 Yahoo fallback을 실행하지 않는다. 한 세션의 로그아웃이 다른 로그인 세션의 데이터를 섞거나 지우지 않게 한다.
- 일반 로컬 실행의 기본 바인딩을 loopback으로 통일한다. 기존 명시적 서버 운영 옵션과 문서는 검토하여 의도적으로 노출하는 경우만 허용한다.

### Desktop: 평문 설정에서 OS 저장소로 이전

현재 로컬 `.env`에 있는 SaveTicker 이메일·비밀번호는 저장소에서 제외되어 있으나 평문 저장이다. 비밀번호는 기존 감사에서 Git 이력·배포물에 발견되지 않았다. 계정 이메일과 같은 값이 Git 작성자 정보로 공개된 것은 별개 문제이며 문서나 시안에 실제 값을 넣지 않는다.

macOS Keychain / Windows Credential Manager의 명시적으로 허용된 backend만 사용하는 `credential_store.py`를 추가한다. 무작위 local profile ID를 키로 사용하고 이메일과 비밀번호는 단일 보호 payload에 함께 넣는다. 임의의 평문 keyring backend로 자동 전환하지 않는다.

승인 후 이전 순서: 원래 두 값 읽기 → OS 저장소 쓰기 → 재조회·동일성 검증 → 다른 설정을 보존한 `.env` 임시 파일(0600) 작성 → 원자적 교체 → 환경 변수·config 메모리 복사 제거. 재시작 후에도 중간 실패를 복구할 수 있도록 단계를 기록한다. 실패 시 기존 값을 먼저 삭제하지 않고 `이전 미완료`를 표시한다. 새 연결은 세션 입력만 허용하며 평문에 새로 저장하지 않는다.

로그아웃은 저장 비밀을 유지하되 세션 접근을 폐기한다. `연결 해제/저장 정보 삭제` 또는 전체 초기화는 저장 비밀도 삭제한다. 패키징된 macOS/Windows 앱의 저장·재조회·접근 거부·업데이트 후 동작을 실제 검증한다. [keyring 공식 문서](https://keyring.readthedocs.io/en/latest/)

향후 커밋 작성자 이메일은 GitHub noreply로 설정한다. 이미 공개된 Git 이력의 이메일은 그대로 남으므로 숨겨졌다고 안내하지 않는다. 공개 이력 재작성·강제 push는 이번 승인 범위에 넣지 않는다.

### Android: 별도 비밀 저장소와 네트워크 경계

기존 `SettingsManager`는 PIN 기반 PBKDF2 + AES-GCM이며 Android Keystore가 아니다. 새 SaveTicker 비밀은 기존 증권 계정 JSON에 섞지 않는다.

- `InsightCredentialStore`가 profile별 AndroidKeyStore AES-GCM 키와 `noBackupFilesDir`의 versioned 암호문을 관리한다. 매 저장마다 새 IV, profile ID/schema version AAD. 이메일·비밀번호 모두 암호화한다.
- local profile ID는 계좌번호·브로커 토큰에서 유도하지 않는 무작위 ID로 생성하고 계좌 편집 시 유지한다.
- 앱 PIN 해제 후에만 복호화한다. 앱 PIN은 OS Keystore 사용자 인증과 같지 않으며 생체인증·StrongBox를 필수로 두지 않는다. 화면 잠금 미설정/Keystore 실패는 세션 연결만 허용한다.
- 키 소실·무효화·인증 태그 불일치 시 해당 연결 비밀을 제거하고 재연결을 안내한다. 자동 복원 로그인이나 평문 후퇴를 하지 않는다.
- Android 11 이하와 12 이상 모두 ST 관련 backup/cloud/device-transfer 제외 규칙을 명시한다. `allowBackup` 한 줄만으로 전송 차단을 보장한다고 가정하지 않는다.
- PIN 상태·connection ID·generation·memory cookie jar·HTTP call/job은 `InsightSessionManager`가 소유한다. 잠금·초기화·연결 변경 후 늦게 완료된 로그인도 쿠키를 되살리지 못하게 한다.
- 전용 OkHttp client로 고정 HTTPS origin만 허용하고 redirect를 끈다. body/header logging을 설치하지 않는다. 증권 client/interceptor·계좌번호·잔고·토큰을 전달하지 않는다.
- 쿠키 만료를 존중한다. 401은 동시 요청 전체에서 공유하는 재로그인 1회만 허용한다. 재실패/403은 만료, 429는 대기 상태로 처리한다.
- Kotlin/Python 모두 중첩 필드까지 allowlist DTO를 적용하고 unknown upstream 필드를 버린다. 로그에는 endpoint 종류와 상태 코드만 남긴다.

근거: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [키 인증 API](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder), [Android 백업 규칙](https://developer.android.com/identity/data/autobackup).

## 6. 구현 구조와 파일 책임

| 영역 | 추가·변경 위치 | 책임 |
| --- | --- | --- |
| Desktop 인증 | `session_store.py`, `routes/auth_pages.py`, 새 insight context manager | 세션별 수명·폐기·경합 방어 |
| Desktop 저장 | `config.py`, 새 `credential_store.py`, 연결 설정 endpoint/UI, 패키징 | OS 저장·기존 설정 이전·실패 복구 |
| Desktop 제공자 | `services/saveticker_service.py`, `routes/insight.py` | 인증 선행, scoped connector/cache, nested allowlist |
| Android 저장·세션 | `InsightCredentialStore.kt`, `InsightSettingsManager.kt`, `InsightSessionManager.kt` | 암호화·연결 상태·잠금·쿠키 |
| Android 계약 | `InsightModels.kt`, `InsightDataSource.kt`, `SaveTickerInsightRepository.kt`, bars adapter | nullable DTO, 정규화, source/as-of/status |
| Android UI | `StockInsightScreen.kt`, `InsightSections.kt`, `InsightCharts.kt`, `InsightPresentation.kt` | 전체 화면·시각화·정확한 값·접근성 |
| 앱 통합 | `HoldingDetailScreen.kt`, `SettingsScreen.kt`, `Navigation.kt`, `DashboardNavigationMotion.kt`, lifecycle owner | 진입/복귀·연결 설정·화면 수명 |
| Android 설정 | Manifest, backup XML, 의존성 | ST HTTPS 및 백업 제외, SDK 범위 유지 |

기존 `KisRepository`의 증권 통신과 별도 repository로 구성한다. `DashboardScaffold`, `ScreenBackground`, `DashboardTopBar`, header buttons, `SectionHeader`, `ResponsiveDetailRow`, 로딩/오류/빈 상태를 재사용한다. 차트는 기존 Compose Canvas 기반으로 구현한다.

메모리 캐시 키: profile/connection generation + market + ticker + bars range/interval. 정상 5분, 오류 60초를 기본으로 하되 429 재시도 지시를 우선한다. 같은 요청을 합치고 화면을 떠난 요청의 결과가 다른 종목이나 폐기된 세션을 덮지 않게 한다. 잠금·로그아웃 경계를 넘어 stale 데이터를 보존하지 않는다.

## 7. 승인 후 작업 순서와 완료 조건

1. **보안 경계:** Desktop 인증·세션 폐기·nested allowlist. 미인증 warm/cold cache 401, 공급자 호출 0회, 잠금 중 로그인/fetch 완료 후 쿠키·캐시 부활 0회 검증.
2. **저장소:** OS 보안 저장과 재실행 가능한 이전, Android Keystore·backup 제외·PIN/lifecycle. 저장 거부/읽기 불일치/파일 교체 실패/키 소실/재시작과 다른 설정 보존 검증.
3. **데이터:** Android 직접 adapter와 공통 비밀 없는 fixture. AVGO/AAPL, 7개 섹션+일봉, source·단위·정밀도·행 수를 웹과 대조. 시간대·수정주가·진행 중 봉의 확인 결과를 UI 문구에 반영.
4. **화면:** 승인된 디자인을 Compose로 구현. 진입/뒤로가기/목록 복원, 모든 공급 필드·행, 정확한 표, 선택 값, 다크/라이트와 축소 모션.
5. **QA:** API 26·35 에뮬레이터 및 가능한 실제 기기, 360dp와 큰 글꼴, TalkBack, chart drag/vertical scroll, 회전·백그라운드·뉴스 복귀, offline/401/403/429/partial/unsupported/unknown field, AVGO→AAPL→AVGO 경합.
6. **일반 릴리스:** 관련 테스트·Android 빌드·Desktop 패키징 통과 후 APK/macOS/Windows 산출물에서 자격정보·쿠키·`.env` 미포함 확인. 변경 기록·일반 업데이트 메타데이터·체크섬과 배포 상태 검증 후 출시. 다음 버전은 현재 릴리스 상태를 다시 확인해 결정(현재 기준 v1.9.6 후보).

이번 기능은 위 단계가 완료되어야 릴리스한다. 실제 기기나 특정 OS 검증을 할 수 없으면 그 공백을 명시하고 완료한 것처럼 기록하지 않는다. 시안 브라우저 검증은 네이티브 접근성·보안 동작 검증을 대신하지 않는다.

## 승인 범위

이 문서와 시안의 방향을 승인하면 **Android 전체 화면 인사이트 + 직접 연결 + 명시한 보안 저장/인증/잠금 보완 + 검증 후 일반 업데이트 릴리스**를 진행한다. 특히 기본 저장 꺼짐과 백그라운드 PIN 재요구 정책을 포함한다. 공개 Git 이력 재작성, 신규 중앙 서버 운영, 실시간 분봉은 별도 범위다.

### 디자인 수정 02 — 2026-09-09

사용자 피드백에 따라 시점·공급원 설명은 뉴스 뒤의 통합 영역으로 이동했다. 수치 비교에 필요한 기간·단위와 개별 데이터의 날짜는 보존한다. 상단/하단 바는 `LiquidGlass.kt`의 Control/Navigation 역할, 34dp 곡률, 표면 하이라이트·투명도·선택 캡슐을 시안에 대응시킨다. 하단 바는 인사이트 섹션 이동용이며 기존 앱의 주 탐색 바와 중복하지 않는다. 실제 굴절·배경 기록 효과는 승인 후 기존 native glass host를 재사용한다.

### 디자인 수정 03 — 보유 상세 진입부와 기간 버튼

인사이트 진입부는 다른 섹션의 제목·보조 설명과 같은 왼쪽 정렬, 크기, 색상, 여백을 사용하고 오른쪽에 48dp 유리 이동 조작부를 둔다. 차트 기간 버튼은 외부 그룹 박스 없이 나열하며 선택 상태만 개별 강조한다.

### 디자인 수정 04 — 연결 설정 흐름

인사이트 진입 화살표는 원형 표면 없이 `>`만 남긴다. 기간 선택은 그룹 박스 없이 선택된 원형 버튼에만 Liquid Glass를 적용한다. SaveTicker 연결 진입점은 설정의 연결 섹션으로 이동한다. 미연결 인사이트 선택 시 안내가 유지되는 연결 설정으로 이동하고, 연결 성공 후 원래 종목 인사이트로 복귀한다. 연결 화면은 공통 유리 헤더·섹션 여백·구분선 입력 행·저장 스위치·관리 행으로 통일한다. 모의 화면에서 연결/취소/해제와 진입 경로별 뒤로가기를 검증한다.
