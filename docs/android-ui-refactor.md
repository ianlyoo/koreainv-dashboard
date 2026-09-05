# Android UI·사용성 리팩터링

2026-09-05. 기준 버전 v1.7.2, 작업 브랜치 `codex/android-ux-refactor`.

## 확인한 문제와 변경

API 35의 별도 에뮬레이터에서 실제 Compose 화면을 촬영하고 소스 흐름을 함께 검토했다. 화면 데이터는 가상 계좌·거래이며 실계좌 조회나 주문을 사용하지 않았다.

| 우선순위 | 확인한 문제 | 반영 내용 |
| --- | --- | --- |
| P1 | 기존 데이터가 있으면 새로고침 오류가 숨겨짐 | 이전 데이터 표시를 유지하면서 오류·재시도와 이전 조회 결과라는 안내를 함께 표시 |
| P1 | 늦게 끝난 조회가 최근 계좌·기간 선택을 덮어쓸 수 있음 | 요청별 순서와 취소를 확인하고 현재 요청만 결과·오류·로딩 상태를 변경 |
| P1 | 계좌 편집 중 이전 초안에 연결된 조회 결과, 반복 저장, 증권사 변경 상태가 섞일 수 있음 | 고정된 편집 ID, 현재 초안 기반 갱신, 조회/저장 중복 방지, 증권사 변경 시 연결 정보와 표시 상태 초기화 |
| P2 | 종목명과 고정 너비 금액이 잘리고 계좌를 구분하기 어려움 | 전체 종목명, 계좌·종목 코드·수량, 너비에 맞춰 줄을 바꾸는 금액/손익 배치 |
| P2 | 작은 화면·큰 글씨에서 제목과 버튼이 경쟁하고 하단 메뉴 아래에 내용이 비침 | 조건에 따른 상단 2행 배치, 불투명 하단 영역, 아이콘과 선택 상태, 실제 하단 높이를 반영한 목록 여백 |
| P2 | 설정 화면의 큰 장식 영역, API 매개변수 이름, 늦은 입력 오류 안내 | 간결한 설정 머리말, 한국어 항목 이름, 항목별 오류, 다음 입력 이동, 삭제 취소·변경 폐기 확인 |
| P2 | 합계와 목록 필터의 적용 범위가 불명확하고 화면마다 통화 선택이 초기화됨 | 전체 계좌 합계/선택된 목록 범위를 구분하고 통화 상태 공유, 탭 상태 저장·복원 |
| P2 | 일부 금액 표시에서 음수 부호·USD 소수점이 사라짐 | 음수 부호 보존 및 달러 현금 소수점 2자리 표시 |

## 구조

- `DashboardDataSource`: 화면에서 사용하는 읽기 인터페이스. 실제 `KisRepository`와 가상 화면 검증 데이터를 교체할 수 있다.
- `FeedbackUi`: 로딩, 오류/재시도, 빈 상태, 반응형 상세 값 표시를 공유한다. 오류 메시지에 원본 증권사 응답이나 자격정보를 넣지 않는다.
- `CurrencyPreference`: NavHost 상위에서 통화 표시 설정을 보관한다. 인증정보는 저장하지 않는다.
- `AccountFormState`: 계좌 초안, 검증, 증권사 전환, 조회 결과 적용, 삭제 복원 및 저장 중복 방지를 UI에서 분리한다.
- `UiPreviewActivity`: `src/debug`에만 있는 실제 화면 검증용 진입점. 정상·빈 결과·오류·로딩·부분 실패를 가상 데이터로 확인한다.

계좌 자격정보와 PIN은 화면의 메모리에만 두며 `rememberSaveable`에 넣지 않는다. 기존 HTTP/HTTPS 프록시 연결 방식과 v1.7.2 토큰 공유/갱신 로직은 유지한다. 공개 screen callback과 금융 계산 계약은 유지했다.

## 검증

```powershell
cd android-app
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

- Android 단위 테스트 127개 통과. 뒤늦은 요청/취소, 완료된 거래 스냅샷 보존, 계좌 범위 분리, 초안/프록시/저장/삭제 복원, 금액 및 오류 표시 회귀 검증을 포함한다.
- 디버그·릴리스 빌드와 Android Lint 통과. Lint 오류 0개, 경고 99개이며 경고 전체를 해소한 작업은 아니다.
- 릴리스 APK의 DEX/Manifest에서 `UiPreviewActivity`와 `SyntheticDashboardSource`가 제외된 것을 확인했다.
- 일반 너비 약 411dp와 360dp·글꼴 배율 1.3에서 주요 8개 화면을 확인했다. 화면별 원본 PNG와 UI 계층 XML은 로컬 `build/ui-review/`에 보관한다.
- 실제 조작으로 USD 선택 후 탭 이동 유지, 선택 상태와 확인한 버튼의 48dp 이상 영역, 빈 설정 제출 시 오류 항목 포커스, 계좌 삭제/복원, 수정 후 뒤로 가기 확인창, 캐시를 유지한 실패 안내와 재시도 요청, 계좌 필터 후 해당 종목 상세 진입을 확인했다.
- PIN 로딩 상태에서 보이는 숫자 키가 비활성화되고 탭해도 로딩 상태를 유지하는 것을 확인했다. 로딩창에 가려진 키까지 동시에 보여야 한다는 초기 스크립트의 조건과 드롭다운을 외부 창으로 취급한 조건은 검증 도구의 문제로 분리했다.
- 독립 소스 검토에서 발견한 증권사 변경 후 비밀키 표시 상태 유지 문제를 수정했다. 확대 글꼴에서 증권사 버튼이 어색하게 줄바꿈되는 배치도 수정했다.

화면 검증용 진입 예:

```powershell
adb shell am start -n com.koreainv.dashboard.debug/com.koreainv.dashboard.UiPreviewActivity --es screen portfolio --es fixture normal
```

`screen`: `portfolio`, `assets`, `trades`, `details`, `trade-details`, `setup`, `unlock`, `accounts`.
`fixture`: `normal`, `empty`, `error`, `loading`, `cached-error`, `partial`, `stale`.

가상 화면 호스트는 실제 잠금 해제·계좌 저장·증권사 연결 성공이나 NavHost 전체 백스택을 재현하지 않는다. 실기기, 실제 계좌, TalkBack 음성 탐색, 프로세스 종료 후 전체 인증/탭 복원 흐름은 이번 검증 범위에 포함하지 않았다. 현재 메뉴의 선택 항목 표시와 백그라운드 시세 폴링 수명은 추가 개선 후보다.

참고: [Compose 접근성 기본 동작](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [UI 상태 저장](https://developer.android.com/develop/ui/compose/state-saving).
