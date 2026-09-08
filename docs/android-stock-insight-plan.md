# Android 종목 인사이트 추가 계획

기준: v1.9.5 웹 종목정보와 현재 Android Compose UI. 이 문서는 후속 구현 계획이며, Android 종목 인사이트는 v1.9.5에 구현되어 있지 않다.

## 권장 방향

Android는 현재처럼 PC 없이 독립 실행하는 구조를 유지한다. 기존 보유종목 상세를 대체하지 않고, 상세 화면에 `종목 인사이트` 진입점을 추가한다. 인사이트는 전체 화면으로 열고 기존 뒤로가기, 목록 위치 복원, 상세 화면 전환 모션을 재사용한다.

웹의 외형을 축소해서 넣기보다 같은 데이터와 의미를 Android의 화면 구조로 표현한다. 차트는 첫 콘텐츠로 두며, Liquid Glass는 현재 헤더와 조작부에 집중한다. 지표와 차트마다 별도 유리 카드를 만들지 않는다.

## 화면 구성과 탐색

1. 기존 스타일의 뒤로가기·종목명·새로고침 헤더.
2. 가격 차트와 기간 선택. 현재가·등락·장 상태 및 차트 공급원·기준 시각 표시.
3. `개요 / 재무 / 애널리스트 / 옵션 / 내부자 / 뉴스` 섹션 이동 버튼.
4. 한 열로 이어지는 섹션. 넓은 기기에서만 차트와 표의 나란한 배치를 허용한다.

탭은 해당 섹션으로 이동하는 용도로 사용한다. 페이지 전체 좌우 스와이프는 차트의 드래그·확대와 충돌하므로 추가하지 않는다. 바텀시트는 지표 설명이나 기간 선택처럼 짧은 보조 작업에 한정한다. 기존 상세 화면과 같이 하단 주 탐색 바는 숨긴다.

## 정보량을 유지하는 모바일 표현

| 섹션 | 기본 표시 | 펼쳐서 확인할 내용 |
| --- | --- | --- |
| 개요 | 현재가, 당일/52주 범위, 핵심 지표의 값·단위 | 업종 평균, 전년 대비, 공매도 변화, 회계 분기와 기준일 |
| 재무 | 분기별 매출 막대와 선택한 분기의 매출·YoY | 제공된 모든 분기의 정확한 값 |
| 애널리스트 | 매수/보유/매도 구성, 현재가와 목표가 범위 | 증권사, 평가일, 투자의견, 이전/현재 목표가 전체 목록 |
| 옵션 | 거래량·PCR, 콜/풋 3종 비중 | 동일 시각 거래량 비교, 만기, Max Pain·벽·Gamma Flip, Net GEX |
| 내부자 | 기간, 매수/매도 건수, 부호 있는 순거래 금액 | 제공된 거래 전체의 날짜·주체·직책·거래 유형·금액 |
| 뉴스 | 제목, 매체, 날짜, 원문 링크 | 공급된 모든 제목. 현재 웹 계약은 최대 3건 |

긴 표를 작은 글자로 줄이거나 화면 밖으로 밀지 않는다. 날짜·주체를 행 제목으로 두고 나머지 필드를 라벨–값 형태로 재배치한다. `정확한 값 보기`, `전체 N건 보기`로 처음 보이는 양을 조절하되 필드를 제거하거나 목록을 다시 잘라내지 않는다. 출처·단위·갱신 시각·오래된 데이터 여부와 잠정 집계/전일 스냅샷 표시는 관련 값 가까이에 항상 둔다.

색상은 `DashboardColors`에 있는 차트·의미 토큰과 현재 MaterialTheme를 따른다. 콜/풋, 매수/매도의 색 역할은 화면 전체에서 일관되게 유지하고, 이름·부호·범례로도 구분한다. 현재 상세 화면의 좌우 20dp, 섹션 간 24dp 간격을 출발점으로 삼는다.

## 데이터 연결과 선행 검증

### 우선안: Android에서 직접 연결

`KisRepository`에 새 공급자를 섞지 않고 `InsightDataSource`와 `SaveTickerInsightRepository`를 별도로 둔다. 사용자는 설정의 `인사이트 연결`에서 자신의 SaveTicker 계정으로 연결하고 상태 확인·연결 해제·캐시 삭제를 할 수 있다.

자격정보는 기존 PIN 기반 암호화 방식을 검토해 별도 저장 영역에 보관하고, 인증 쿠키는 메모리에만 둔다. 인증은 고정된 SaveTicker HTTPS origin으로만 보낸다. 잠금·로그아웃 시 진행 중 요청과 메모리 쿠키를 정리한다. 계좌번호·잔고·브로커 토큰은 SaveTicker로 보내지 않으며 사용자 비밀번호를 APK에 넣지 않는다.

Python에서 확인한 로그인·웹 API 응답이 Android의 정상 HTTP 클라이언트에서도 동작하는지 먼저 검증한다. 현재 사이트의 웹 API를 사용하는 만큼 공급자 변경은 adapter 내부에서 대응한다.

### 가격 차트 계약은 별도로 확인

현재 웹은 TradingView로 가격 차트를 표시하고, SaveTicker 성공 응답의 `history`는 빈 배열이다. 따라서 기존 `/api/asset-insight` 응답만으로 Android 가격 차트를 만들 수는 없다.

관측된 `/api/stocks/api/v1/tickers/AVGO/bars?range=1y&interval=day`를 출발점으로 OHLCV 필드, 시간대, 휴장, 기간·간격, 수정주가 여부, 거래량, 결측, 페이징과 제한을 확인한다. 이 계약을 확인한 뒤 가격 차트를 구현한다. 공급원이 다른 시계열을 쓸 경우 차트의 공급원과 시각을 따로 표시한다.

### 대안: 사용자 소유 백엔드

웹의 정규화와 캐시를 재사용하기는 쉽지만 PC/서버 실행, 휴대폰에서의 접근성, HTTPS와 연결 인증이 필요하다. 선택 기능으로만 고려하며 기본 필수조건으로 만들지 않는다. 현재 `/api/asset-insight`에는 명시적인 세션 검증이 없으므로 그대로 외부에 공개하지 않고 인증 경계를 먼저 추가해야 한다.

## 구현 경계와 재사용

- 재사용: `DashboardScaffold`, `ScreenBackground`, `DashboardTopBar`, `HeaderIconButton`, `HeaderRefreshButton`, `SectionHeader`, `ResponsiveDetailRow`, 기존 로딩·오류·빈 상태 컴포넌트.
- 새 네트워크 영역: `InsightDataSource.kt`, `InsightModels.kt`, `SaveTickerInsightRepository.kt`, `InsightSessionManager.kt`, `InsightSettingsManager.kt`.
- 새 UI 영역: `StockInsightScreen.kt`, `InsightSections.kt`, `InsightCharts.kt`, `InsightPresentation.kt`.
- 기존 변경: `HoldingDetailScreen.kt`의 진입 버튼, `Navigation.kt`의 시장/티커 route와 연결 scope, `DashboardNavigationMotion.kt`의 상세 route, `SettingsScreen.kt`의 연결 설정.
- 차트는 기존 Compose Canvas 사용 방식에서 시작한다. 가격 차트 제스처·접근성이 충분한지 검증한 뒤 필요한 경우에만 라이브러리 도입을 검토한다.

캐시는 연결 identity + 시장 + 티커로 분리한다. 정상 데이터 5분, 오류 재시도 대기, 요청 중복 방지, 마지막 성공 데이터의 오래됨 표시를 지원한다. 종목 전환·연결 변경·잠금 이후 도착한 응답이 새 화면을 덮지 않도록 요청 수명을 화면과 연결 세션에 묶는다.

## 단계별 완료 기준

1. **계약 검증**: Android에서 정상 로그인·세션 갱신·7개 정보 섹션·가격 OHLCV 계약 확인. 실패 시 대안과 지원 범위 결정.
2. **데이터 계층**: 별도 저장·세션·repository·nullable DTO와 공통 계약 fixture 작성. 공급원, 상태, 기준일, 단위, 행 수를 웹과 대조.
3. **화면과 정보 동등성**: 기존 상세에서 전체 화면 진입, 차트 최상단, 모든 지표와 행 제공, 섹션 이동 및 복원 구현.
4. **차트와 접근성**: 터치로 값 선택, 버튼식 값 탐색, TalkBack 텍스트/표 대안, 큰 글꼴과 다크/라이트 대응.
5. **기기 검증**: 작은 화면, 긴 이름·큰 금액, 차트 드래그와 세로 스크롤, 뒤로가기, 오프라인·만료·부분 성공·미지원, AVGO→AAPL→AVGO 응답 경합, 잠금/로그아웃 정리 확인.

수치 검증에는 0과 결측 구분, 퍼센트 재곱셈 방지, 분기 매출의 TTM 오표기 방지, 불완전한 비중의 100% 강제 합산 방지, `available=true` 거래량 비율만 표시, 가격과 GEX 축 분리, 제한된 내부자 목록에서 기간 총액을 추정하지 않는 조건을 포함한다.

## 코드 근거

- [Navigation.kt](../android-app/app/src/main/java/com/koreainv/dashboard/ui/Navigation.kt): 현재 상세 route 및 직접 repository 생성.
- [HoldingDetailScreen.kt](../android-app/app/src/main/java/com/koreainv/dashboard/ui/screens/HoldingDetailScreen.kt): 상세 진입·표현과 요청 수명.
- [SettingsManager.kt](../android-app/app/src/main/java/com/koreainv/dashboard/network/SettingsManager.kt): 현재 설정·PIN 기반 자격정보 암호화.
- [AssetStatusScreen.kt](../android-app/app/src/main/java/com/koreainv/dashboard/ui/screens/AssetStatusScreen.kt): 기존 Compose Canvas 사용.
- [웹 시각화 계약](insight-visualization.md), [SaveTicker 연결](saveticker-connection-findings.md): 데이터·단위·출처·차트의 동등성 기준.
