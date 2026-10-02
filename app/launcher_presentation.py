"""Shared native-menu copy; no process, account or update-policy decisions."""

APP_TITLE = "KoreaInv Dashboard"
OPEN_LABEL = "내 투자 열기"
SETTINGS_LABEL = "설정 열기"
VERSION_LABEL = "앱 정보"
EXIT_LABEL = "대시보드 종료"
STARTUP_FAILED = (
    "대시보드를 시작하지 못했습니다.\n\n"
    "앱을 다시 열어 주세요. 문제가 계속되면 실행 로그를 확인해 주세요."
)
PORT_BUSY = (
    "대시보드 연결을 열지 못했습니다.\n\n"
    "이미 실행 중인 대시보드나 다른 프로그램이 있는지 확인한 뒤 다시 시도해 주세요."
)


def version_info(current: str, latest: str | None, policy: str) -> str:
    def display(value: str) -> str:
        value = value.strip()
        return value if value.lower().startswith("v") else f"v{value}"

    latest_text = display(latest) if latest else "확인할 수 없음"
    policy_text = {"mandatory": "필수", "recommended": "권장"}.get(policy, "확인할 수 없음")
    return (
        f"KoreaInv Dashboard · 조회 전용\n\n현재 버전: {display(current)}\n"
        f"최신 버전: {latest_text}\n업데이트 정책: {policy_text}"
    )
