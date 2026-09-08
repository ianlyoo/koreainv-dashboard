# Android 모션 조사 — 2026-09-08

## 결론

현재 Jetpack Compose 기반에서 Apple 계열의 즉각적인 눌림, 탄성 복원, 연속적인 선택 이동을 구현할 수 있다. 기본 기능은 기존 Compose animation API로 충분하다. 우선순위는 공통 버튼의 입력 피드백, 탭 선택 캡슐, 상태 전환, 화면 전환 순서다.

Apple은 위치뿐 아니라 속도의 연속성을 자연스러운 모션의 핵심으로 설명한다. Compose의 spring도 진행 중 목표가 바뀔 때 속도를 이어갈 수 있다. 따라서 연타와 방향 변경을 자연스럽게 처리하는 기반이 이미 있다. [Apple: Animate with springs](https://developer.apple.com/videos/play/wwdc2023/10158/), [Compose spring](https://developer.android.com/develop/ui/compose/animation/customize)

## 현재 앱에서 확인한 사실

| 위치 | 현재 구현 | 개선 지점 |
| --- | --- | --- |
| `LiquidGlass.kt:120` | 눌림 시 1 → 0.96, spring dampingRatio 0.8 / stiffness 500 | 눌림과 복원의 성격 구분, 빠른 탭 피드백 보장 |
| `HeaderUi.kt:328` | 상단 버튼이 공통 눌림 효과 사용 | 유리 하이라이트와 크기 변화를 함께 조율 |
| `HeaderUi.kt:577` | 하단 선택 캡슐이 spring 0.82 / 420으로 이동 | 이동 중 미세한 형태 변화, 아이콘·라벨과 동기화 |
| `HeaderUi.kt:459,789` | 통화 선택 배경·색·굵기가 즉시 변경 | 움직이는 선택 배경과 눌림 효과 |
| `Navigation.kt:233` | enter/exit/pop 전환 모두 None | 짧고 연속적인 화면 전환 |
| `PortfolioScreen.kt:166` 등 | 새로고침 버튼을 로딩 표시로 교체 | 외곽 버튼을 유지하고 내부 아이콘을 전환 |

위 파일들의 루트는 `android-app/app/src/main/java/com/koreainv/dashboard/ui/`이며, Navigation 외 파일은 `screens/` 아래에 있다. 하단 바는 주요 탭 간 이동에서 유지되지만 각 화면의 헤더는 목적지에 속한다.

현재 애니메이션이 전혀 없는 것은 아니다. 화면 교체와 로딩 분기에서 기존 버튼의 움직임이 중단되어 느껴질 수 있다는 것이 코드에 근거한 가설이다. 이번 조사에서는 실제 기기에서 해당 현상을 재현하거나 프레임 성능을 측정하지 않았다.

공통 눌림 처리는 `collectIsPressedAsState`를 사용한다. Compose 공식 문서에 따르면 같은 프레임 안에 press/release가 끝나면 상태 관찰자가 눌림을 보지 못할 수 있다. 직접 interaction을 수집하고 짧은 피드백을 관리하는 방식이 대안이다. 앱에서 이 문제가 발생하는지는 빠른 탭 테스트로 확인해야 한다. [Interaction 처리](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions)

## 사용자가 제공한 저장소에서 참고할 부분

- `InboundMessageView.swift`: 배경과 아이콘을 서로 다른 스프링·타이밍으로 움직이는 follow-through. 탭 캡슐과 아이콘에 적용할 원리이며, 예제의 큰 반동과 긴 순차 지연은 금융 앱의 조작부에 맞게 줄인다. [예제 코드](https://github.com/GetStream/purposeful-ios-animations/blob/main/PurposefulSwiftUIAnimations/AnimationPrinciples/FollowThroughAnimation/StreamChatReactions/InboundMessageView.swift)
- `ThumbnailToFullscreen.swift`: 같은 요소를 화면 사이에서 연결하는 matched geometry. 추후 종목 행에서 상세로 이동하는 모션의 참고 자료다. Compose에는 shared element/bounds API가 있다. [예제 코드](https://github.com/GetStream/purposeful-ios-animations/blob/main/PurposefulSwiftUIAnimations/AnimationPrinciples/AnticipationAnimation/ThumbnailToFullscreen.swift), [Compose shared transitions](https://developer.android.com/develop/ui/compose/animation/shared-elements)
- `ReduceMotionSpring.swift`: 사용자 설정에 따라 스프링을 단순화한다. Android에서도 시스템 모션 설정을 존중해야 한다. Compose의 MotionDurationScale이 0이면 다음 프레임에 모션이 완료된다. [예제 코드](https://github.com/GetStream/purposeful-ios-animations/blob/main/PurposefulSwiftUIAnimations/AnimationBestPractices/ReduceMotion/ReduceMotionSpring.swift), [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)

이 저장소는 SwiftUI 예제 모음이다. Android에 직접 의존성으로 넣는 방식이 아니라 모션 원리를 Compose로 구현하는 참고 자료로 사용한다.

## 제안하는 적용안

다음 수치는 Apple의 공식 상수가 아닌 초기 조율 범위다. 실제 기기에서 조정한다.

| 대상 | 제안 |
| --- | --- |
| 상단 버튼 | 손이 닿으면 약 80–100ms 안에 눌림이 보이고, 손을 떼면 200–300ms 정도의 짧은 탄성 복원. 입력 동작은 애니메이션 완료를 기다리지 않음 |
| 새로고침 | 버튼 외곽은 유지. 눌림에서 아이콘 회전/로딩으로 연결하고, 완료 시 자연스럽게 정상 아이콘으로 복귀 |
| 하단 탭 | 현재 이동 스프링을 유지·조율하면서 캡슐이 진행 방향으로 살짝 늘었다 정착. 아이콘과 색상도 같은 선택 상태에 맞춰 전환 |
| 통화 선택 | 선택 배경이 두 항목 사이를 이동하고 글자 색상도 짧게 전환 |
| 주요 탭 화면 | 짧은 페이드와 작은 이동량부터 적용. 전체 화면을 크게 튕기지 않고 읽을 내용의 안정성 유지 |
| 상세 진입 | 주요 탭 전환이 안정된 뒤, 진입·뒤로가기 방향이 연결되는 전환 검토 |

공통 motion spec을 두어 버튼별로 수치가 달라지지 않게 한다. 위치·크기는 spring, 짧은 색/투명도 변화는 상황에 맞는 tween을 사용한다. 연타 시 현재 위치와 속도에서 새 목표로 향하고, 취소된 터치는 선택을 실행하지 않는다. 시스템 설정을 따르는 약한 햅틱은 선택적 보완이며 기기별 촉감은 별도 확인한다. [Android haptic feedback](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)

## 성능과 적용 순서

현재 `graphicsLayer` 기반 눌림과 lambda `offset` 기반 선택 이동은 활용하기 좋은 기반이다. 크기·위치·투명도 변경은 가능한 한 그리기 단계에서 처리한다. Compose 공식 가이드는 이를 통해 불필요한 재구성과 레이아웃 작업을 줄이도록 권장한다. [Animation performance](https://developer.android.com/develop/ui/compose/animation/quick-guide#optimize-animation-performance)

화면 전환에는 별도 검토가 필요하다. 현재 각 `DashboardScaffold`가 공유 `LayerBackdrop`에 본문을 기록한다. 이전/다음 화면이 동시에 살아 있는 전환을 도입하면 유리가 어느 본문을 참조해야 하는지 기록 소유권을 정해야 한다. 이는 코드에서 파악한 설계상 주의점이며, 재현된 결함은 아니다. 유리 블러·굴절까지 매 프레임 과하게 바꾸는 것은 초기 범위에서 제외한다.

1. 공통 버튼 피드백과 새로고침 상태 연결.
2. 하단 캡슐·아이콘·통화 선택 모션 조율.
3. 배경 기록 처리 확인 후 화면 전환.
4. 실기기에서 빠른 연타·방향 변경·터치 취소·모션 끄기·글자 확대를 검증. 기존 터치 영역과 접근성 역할 유지.
5. 60/120Hz 기기에서 release/profileable 빌드로 프레임 지연을 측정. 필요하면 Macrobenchmark의 FrameTimingMetric으로 P95/P99를 비교. 이번 조사에서는 성능 수치를 측정하지 않았다. [FrameTimingMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)

현재 의존성은 Compose BOM 2026.02.00, Backdrop 1.0.6, Navigation Compose 2.7.7이다. 기본 버튼·탭 모션을 위해 신규 애니메이션 라이브러리를 도입할 필요는 없다고 판단한다. 본 조사에서는 앱 모션 코드를 변경하지 않았다.

## 구현 후속 — 2026-09-08

위 적용안을 개발 브랜치에 구현했다. 공통 눌림·탄성 복원, 하단 캡슐의 연속적인 늘어남과 정착, 아이콘 반응, 통화 선택 이동, 고정된 새로고침 버튼의 내부 상태 전환, 탭·상세 화면 전환을 적용했다.

유리 효과는 `DashboardBackdrop.kt`의 수명 관리로 보완했다. 각 화면이 하나의 고정된 배경 기록을 갖고, 하단 바는 현재 화면의 기록만 참조한다. 원본과 소비자의 좌표가 화면에 연결되어 있는지 그리기 직전에 확인한다. 배경 기록 객체를 화면 사이에서 재할당하지 않으며 중첩 기록도 하지 않는다. 빠른 탭 전환으로 발견된 Backdrop 1.0.6의 좌표 수명 문제를 이 구조로 해결했다.

검증 내용과 녹화 결과는 프로젝트 루트의 `design-qa.md`와 로컬 `build/motion-review/`에 기록한다. 실기기 고주사율 성능과 햅틱은 이번 구현 검증 범위에 포함하지 않았다.
