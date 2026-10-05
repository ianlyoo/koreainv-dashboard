# Android 양도소득세 참고 계산 (2026-10-05 확인)

## 적용 규칙과 공식 근거

이 화면은 국내에 계속 5년 이상 주소·거소를 둔 거주자의 외국법인 상장주식, 국내 상장주식 장내거래 소액주주를 전제로 합니다. 대주주·장외·비상장·기타자산은 계산 범위에 포함하지 않습니다. [국세청 과세대상·손익통산 안내](https://b.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=8800&mi=12274).

- 귀속연도는 체결일이 아니라 대금청산(결제)일의 역년입니다. [소득세법 제98조](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1031191505), [2026-10-01 시행 소득세법 시행령 제162조](https://www.law.go.kr/lsLawLinkInfo.do?chrClsCd=010202&lsJoLnkSeq=1001063200).
- 양도차익은 원화 양도가액 − 취득가액 − 필요경비입니다. 각 취득분과 양도대금·수수료는 각각 수령/지출일의 기준환율 또는 재정환율로 환산합니다. 하나의 매도환율로 외화 손익 전체를 환산하면 환차손익이 누락됩니다. [2026-10-01 시행 소득세법 시행령 제178조의4·제178조의5](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lspttninfSeq=126688).
- 같은 해 과세대상 주식 손익을 통산하고 1인당 연 250만원을 한 번 공제합니다. 한국투자·토스에서 각각 공제하지 않습니다. 비과세 국내주식 손실은 해외 이익과 통산하지 않습니다. [국세청 통산·합산공제 안내](https://d.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=8800&mi=12274), [2026년 국세청 확정신고 사례](https://j.nts.go.kr/nts/na/ntt/selectNttInfo.do?mi=2201&nttSn=1350890).
- 외국법인 주식 세율은 소득세 20%, 개인지방소득세 2%로 합계 22%입니다. 과세표준은 통산손익에서 기본공제를 차감한 금액과 0 중 큰 값입니다. [국세청 국외주식 세율표](https://i.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=7711&mi=2312), [지방세법 제103조의3 제1항 제12호](https://www.law.go.kr/lsLawLinkInfo.do?chrClsCd=010202&lsJoLnkSeq=1000384946).
- 법령상 예외: 중소기업 내국법인이 국외에 상장한 주식에는 소득세 10%가 적용될 수 있습니다. 외국법인이 발행한 주식은 이 중소기업 예외에 해당하지 않습니다. 화면에 외국법인 가정을 명시했으며 예외 종목은 별도 법령/증권사 확인이 필요합니다. 모든 해외 상장주식에 일률적으로 22%를 적용한다는 일반화는 부정확합니다. [국세청 세율표 주석](https://i.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=7711&mi=2312).
- 국내 상장주식 소액주주의 장내 양도는 비과세입니다. 대주주 양도세를 계산하지 않으며 증권거래세는 매매결제 때 거래징수됩니다. [국세청 2026년 안내](https://www.nts.go.kr/nts/na/ntt/selectNttInfo.do?mi=2307&nttSn=1348384), [증권거래세법 제9조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?lsJoLnkSeq=1017571211).
- 국외주식은 예정신고가 없고 다음 해 5월 1~31일 확정신고·납부합니다. 휴일에 해당하는 기한은 다음 영업일입니다. [국세청 법정 신고기한](https://ems.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=7708&mi=2309), [2026년 국세청 안내: 2026년 국외주식 → 2027년 5월](https://sc.nts.go.kr/nts/na/ntt/selectNttInfo.do?bbsId=1028&mi=2201&nttSn=1353905).

## 데이터 계약과 참고 계산의 한계

`network/CapitalGainsTax.kt`의 `CapitalGainsBasis`는 매도 수량에 배분한 취득분, 매도대금, 별도 수수료의 원화/외화 금액과 환율을 분리합니다. 원가에 수수료를 넣었다면 별도 비용에 중복 입력하지 않습니다. JPY 환율은 1엔당 원화로 정규화합니다. 금액 계산은 BigDecimal이며 예상세액만 원 단위 절사합니다. 실제 신고·납부의 끝수처리는 증권사/홈택스가 결정합니다.

### Production 연결

`KisRepository.fetchCapitalGainsHistory`는 세금 전용 경로로 두 증권사의 해외 매도마다 계약을 채웁니다. 주문·인증·계좌 설정 경로는 변경하지 않습니다.

- **KIS:** TTTS3039R 외화 모드(`WCRC_FRCR_DVSN_CD=01`)의 `frcr_sll_amt_smtl1`, `stck_sll_tlex`, `frst_bltn_exrt`를 매도대금·매도비용 환산에 사용합니다. 취득 원가는 `pchs_avg_pric × slcl_qty`이며 평균단가가 없을 때만 독립적인 `frcr_pchs_amt1`를 사용합니다. KRW 모드와 `ovrs_rlzt_pfls_amt`를 세금 원가로 사용하지 않습니다. 매수 이력은 기존 TTTS3035R에서 전년도 1월부터 조회합니다. 그 이전 매수·미조회분은 아래 대체 규칙으로 계산합니다. 재사용되는 주문번호가 다른 날짜의 매수분을 합치지 않도록 날짜·체결정보도 키에 넣습니다.
- **Toss:** 기존 이동평균 계산의 외화 취득원가(`buyAmountNative` / proxy `buy_amount_native`)를 가져옵니다. 비용정보가 부족하거나 수량이 일부만 확인되면 확인 가능한 매수분의 외화 평균원가로 참고 계산합니다. 매수와 매도 시각 각각의 `TossHistoricalFx` midRate를 별도로 사용합니다. 거래일은 체결 timestamp를 미국 현지 시간으로 변환합니다.
- **FIFO 환율 배분:** 증권사별·종목별·통화별 FIFO 수량으로 취득 날짜를 선택하고, 증권사의 이동평균 외화 원가 총액을 해당 수량에 비례 배분하여 각 매수일 환율로 환산합니다. 매수가격 자체를 FIFO 원가로 바꾸지 않습니다. 전체 배분 합계는 증권사 원가와 일치합니다. 매도 순서를 모르는 KIS 일별 집계도 추정입니다.
- 매수 수량 부족 또는 매수 환율 누락분만 매도일 환율로 환산하고 `취득환율 미확인(환차 미반영)`을 표시합니다. 외화 원가 자체가 전혀 없을 때만 취득분 없이 계약을 전달하여 `미산출`로 남깁니다. 확인되지 않은 수수료는 제공된 금액만 사용하고 사유를 표시합니다. 매도 참고환율 조회 실패 시 기존 표시 참고환율로 대체하고 `매도환율 미확인(현재참고)`을 표시합니다. 이 경우 실제 환차를 보장하지 않습니다.

위 환율은 **법정 결제일 기준환율의 대체 참고값**입니다. KIS 최초고시 환율·Toss midRate, 평균원가 배분, 결제일 추정 때문에 production 결과는 모두 `추정`으로 표시합니다. `realizedProfitKrw`, 수익률, 현재환율로 취득원가를 역산하지 않으며 Toss 환차손익 제외 P&L은 세금 합계에 들어가지 않습니다.

### 결제일·귀속연도

매도대금의 귀속은 추정 결제연도로 정합니다. 미국은 2024-05-28부터 T+1(이전 T+2), 일본 T+2입니다. 주말을 건너뛰고 미국 연준은행 휴일 및 일본 2025~2027 공휴일·JPX 연말연시 휴장을 참고합니다. 알려지지 않은 과거/미래 휴장·임시휴장은 보장하지 않습니다. 미국 현금결제일은 연준은행 달력으로 추정하므로 Good Friday 등 거래소만의 휴장과 구분합니다. 토요일 공휴일의 전날 금요일은 연준은행 개장일이라는 규칙도 반영합니다. [DTCC T+1 전환](https://www.dtcc.com/initiatives/accelerated-settlement/US-T1), [연준은행 휴일](https://www.federalreserve.gov/aboutthefed/k8.htm), [JPX T+2](https://www.jpx.co.jp/english/equities/clearing-settlement/tplus2-settlement-cycle/index.html), [JPX 휴일](https://www.jpx.co.jp/english/corporate/about-jpx/calendar/), [일본 내각부 과거 공휴일 CSV](https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv).

조회 범위는 전년도 12월부터 다음 연도 1월까지 확장하여 연말 경계를 확보하고 현재일까지 제한합니다. 비용 미산출 거래도 체결일에서 추정한 결제연도로 분류합니다. 휴일 때문에 연말 매도가 다음 해에 결제되면 다음 해 합계에 들어갑니다.

확정·추정 중 원화 손익이 있는 거래를 모두 합산하고 기본공제 250만원을 한 번 적용합니다. 헤드라인의 `추정 n건 포함`은 실제 금액 합계에 들어간 추정 거래만 세며 `미산출 n건`은 별도로 표시합니다. 목록에는 미산출 거래도 남습니다. 모두 미산출일 때 세액은 0원이 아닌 `미산출`입니다. 일부 미산출/계좌 오류가 있으면 표시 금액은 부분 합계라는 안내를 표시합니다.

### 기존 Toss proxy 호환

기존 `/api/toss-proxy/trade-history`에 선택 입력 `tax_estimate: true`를 추가했습니다. 기본 조회는 그대로이며 tax 조회에만 `tax_executions`(같은 요청 계좌의 매수·매도 이력), 각 필요 거래의 `tax_reference_fx`를 전달합니다. 선택 매도에서 실제 소비한 매수 FIFO lot만 환율을 조회하고 기존 계좌별 캐시·429 재시도 제한을 재사용합니다. 새로운 서버나 키는 없습니다.

기존 배포 서버는 옵션을 무시할 수 있습니다. Android는 그때 기존 응답의 독립적인 외화 원가와 매도 참고환율로 계산하고 취득환율 대체 사유를 표시합니다. 과거 매수 환율까지 반영하려면 승인 후 이 저장소의 proxy 변경을 중앙 서버에 배포해야 합니다. 이번 작업에서는 배포하지 않습니다.

화면은 거래탭의 기간/계좌 필터와 독립적으로 현재 앱에 이미 등록된 두 허용 계좌를 합산합니다. 계좌 설정·인증·서버·주문 경로는 변경하지 않습니다. 안내: 참고용 추정이며, 실제 신고는 증권사 신고대행 또는 홈택스 기준.


### 독립 검토 수정: 조회 완전성과 환율 조회 제한

계좌별 `historyCompleteness`는 손익 계산 성공 여부와 별개입니다. Toss 응답 형식 오류·빈 미완료 응답·잘못된 cursor·100페이지 소진, KIS 연속 조회의 10페이지 한도·누락 cursor·응답 부재를 미완료로 전달합니다. 부분 거래는 유지하고 경고합니다. 매도가 없고 모든 이력이 완료된 경우에만 0원이며, 빈 미완료 이력은 미산출입니다. KIS 병렬 시장 조회의 상태는 계좌 조회 문맥 안에서 합칩니다.

서버와 직접 Android의 연간 FX hydration은 계좌별 총 30초·최대 40회로 제한하고, 연속 두 실패에서 중단합니다. 서버의 실제 HTTP 재시도·토큰 요청도 40회 예산을 사용합니다. 직접 Android tax FX 요청은 자동 재시도를 하지 않습니다. 서버 단일 HTTP는 연결·헤더·본문 전체의 deadline 5초를 적용하고 남은 hydration 예산으로 줄입니다. 요청은 async stream으로 수행하며 deadline 또는 취소 시 transport를 취소하고 닫힘을 기다립니다. HTTPX를 서버 의존성에 명시했습니다. 성공한 환율과 독립적인 외화 원가는 보존하고 나머지는 기존 표시 사유가 있는 참고환율 대체로 즉시 계산합니다.

Android 취소는 HTTP call을 취소합니다. 서버는 연결 종료/route 취소를 감지해 worker의 cancellation event를 설정하며, 다음 요청·재시도·throttling을 중단합니다. 20ms 간격의 watchdog이 진행 중 HTTP에도 취소를 전달합니다. 실 localhost slow-drip body·header·token 검사로 취소 지연과 전체 deadline을 검증합니다. OS DNS 조회를 기다리는 경우에도 transport 취소 후 늦은 HTTP 요청이 발생하지 않는지 검사합니다. 세금 조회 mutex·계좌 동시조회 quota는 일반 조회와 분리하고 FX cache lock은 메모리 접근만 보호하며 일반 거래 요청은 tax network 작업 뒤에서 기다리지 않습니다. 서버 일반 조회의 기존 인증·재시도 동작은 유지합니다. 토큰 network I/O는 전역 cache lock 밖에서 수행하며, 결과 저장 전 다시 cache를 확인해 다른 refresh의 최신 유효 토큰을 덮어쓰지 않습니다. 유효 cached token 조회는 진행 중 refresh를 기다리지 않습니다. [HTTPX async stream 정리](https://www.python-httpx.org/async/#streaming-responses), [asyncio task 취소](https://docs.python.org/3/library/asyncio-task.html#task-cancellation).

Toss 외화 취득원가는 환율/표시 손익 검증 전에 실행 키별로 보관합니다. 연속 매도 중 한 건의 환율 실패가 다른 원가를 선택하게 만들지 않습니다. 가짜 $100·$200 매수와 두 번의 매도에서 총 배분 원가는 $300으로 유지됩니다.

### v1.9.12 — 체결 전체 조회와 일별 환율 범위 캐시

- 세금 조회의 TTTS3035R·TTTS3039R·국내 체결만 최대 **100페이지**로 확장했습니다(일반 거래 탭은 기존 10페이지). 실제 계좌의 값 없는 probe는 NASD 20행/페이지, 50페이지/999행, TKSE 2페이지, 국내 2페이지, 연간 매도 250건입니다. 합성 서버는 50×20행과 EGW00201을 재현합니다. 거래 탭과 세금 조회가 공유하는 KIS 요청기는 시작 간격 250ms, 동시 요청 1개, EGW00201/429 재시도 최대 2회(0.8/1.6초)를 적용합니다. 한 페이지 실패 시 앞선 페이지를 보존하고 어떤 체결 부분인지 표시합니다.
- 최초 매도 연도의 1월부터 체결을 조회하고 선택 매도의 FIFO 수량이 부족한 경우에만 이전 연도를 추가 조회합니다. 과거 거래가 없는 연도를 최초 거래일로 오인하지 않습니다. 2000년 이전 또는 제공되지 않는 매수분은 기존 취득환율 대체 사유가 남습니다.
- 이미 연결한 한투 계좌의 [공식 기간별 환율 API](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/inquire_daily_chartprice/inquire_daily_chartprice.py), `FHKST03030100`, 시장 `X`, 주기 `D`를 사용합니다. [공식 해외지수 마스터 안내](https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/overseas_index_code.py)의 원/달러 `FX@KRWKFTC`와 엔/달러 `FX@JPY`를 사용하며 JPY는 원/달러 ÷ 엔/달러로 1엔당 원화로 환산합니다. 네트워크 조회는 최대 97일 구간(90일 + 직전 7일), USD 1년 최대 약 5회, JPY 약 10회입니다. 취득 FIFO 날짜 전체와 토스 매도 날짜를 일별 종가 참고환율에 연결하고, KIS 매도는 `frst_bltn_exrt`를 그대로 유지합니다. 법정 기준환율 또는 토스 체결 시점 midRate와 같다고 주장하지 않습니다.
- 일별 양수 환율만 공개 시세 캐시에 날짜·통화별로 영구 보관합니다. 계좌·토큰·주문 정보는 저장하지 않습니다. 휴일/누락 날짜는 최대 7일 전 참고값을 사용하고 `환율 일부 대체(직전 고시일)`을 표시합니다. 남은 누락은 기존 취득/매도환율 미확인 사유를 유지합니다. 한투 연결이 없다면 일별 원천이 없어 표시 참고환율 대체입니다. 환율 범위 hydration의 전체 deadline은 계좌당 30초, 구간당 5초, 연속 두 실패에서 중단하고 부모 취소를 전달합니다. 40회 시점 조회 제한은 새 앱 세금 경로에 적용되지 않습니다.
- 새 앱은 proxy에 `tax_estimate: true, hydrate_tax_fx: false`를 보내 독립 외화 원가·전체 실행 이력만 받습니다. 새 서버는 거래별 환율 요청을 생략합니다. 기존 앱/서버 호환을 위해 서버 기본 옵션은 기존 30초·40회 시점 조회를 유지합니다. 구 서버도 새 앱의 범위 환율 계산은 가능하나 해당 최적화에는 서버 갱신이 필요합니다.
- 연간 결과는 repository의 세션 캐시에 연도별로 보관합니다. 명시적 재시도는 거래를 다시 조회하되 성공한 날짜 환율은 바꾸지 않습니다. 화면 재진입 때 자동 갱신하지 않습니다. 세션 종료는 결과 캐시를 폐기합니다.
- `정보를 불러오지 못했습니다`는 예외 없이도 `DashboardErrorNotice`의 기본 제목 때문에 PAGE_LIMIT 경고에 표시됐습니다. 전체 계좌 실패 때의 `IllegalStateException("TAX_HISTORY_UNAVAILABLE")`도 해당 일반 UI로 갔습니다. 이제 전체 실패도 구조화된 미조회 결과로 반환하고, 세금 화면은 `한투 해외 체결 일부 미조회`, `토스 거래 이력 일부 미조회`, `환율 일부 대체`처럼 원인을 표시합니다. 예상 세액은 일부 조회라면 부분 합계, 빈 미완료라면 미산출로 남습니다. disclaimer와 세법 계산은 유지합니다.
