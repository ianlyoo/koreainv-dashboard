# Android 종목 인사이트 추가 계획

상세 계획을 보안 검토와 실제 Android 연결 검증 결과까지 반영한 [구현·디자인 승인안](android-stock-insight-implementation-proposal.md)으로 구체화했다.

- **방향:** PC 없이 독립 실행. 기존 보유 상세에서 전체 화면 종목 인사이트로 이동한다.
- **디자인:** 기존 Android 색상·Manrope·헤더·Liquid Glass 컨트롤을 유지한다. 가격 차트 다음에 개요·재무·애널리스트·옵션·내부자·뉴스를 한 열로 배치한다. 정확한 값과 전체 공급 행은 펼쳐서 확인한다.
- **연결 검증:** 실제 Android API 35의 Dalvik + OkHttp에서 로그인·7개 정보 endpoint·1년 일봉 모두 HTTP 200을 확인했다. 252개 OHLCV의 기본 수치·시각 검증을 통과했다. 수정주가·거래소 시간대·진행 중 봉과 다른 종목의 계약 확인은 구현 단계에서 계속한다.
- **보안:** Desktop의 인증 선행·세션 폐기, OS 비밀 저장소 이전, Android의 별도 Keystore 저장·백업 제외·PIN/lifecycle 경계를 함께 구현한다.
- **승인 후:** 보안 경계 → 저장소 → adapter/fixture → Compose 화면 → 기기·패키징 검증 → 일반 업데이트 릴리스.

[클릭 가능한 디자인 시안](../design/android-insight/index.html) · [디자인 규격](../design/android-insight/DESIGN.md)

사용자 승인에 따라 v1.9.6 구현을 진행했다. 실제 연결·보안·성능과 기기 검증 결과는 [구현·검증 기록](android-insight-validation.md)을 참고한다. HTML 시안은 설계 참고용 예시 데이터다.
