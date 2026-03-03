# Korea Investment Dashboard

한국투자증권 API 기반 계좌 대시보드입니다.

## Quick Start (개발 환경)

```bash
python main.py
```

브라우저에서 `http://127.0.0.1:8000` 접속.

## Windows 배포 사용법 (Python 미설치 PC)

상세 설치 문서: `README_INSTALL_WINDOWS.md`

1. 배포 받은 `KISDashboard-win64.zip` 압축 해제
2. `KISDashboard.exe` 실행
3. 기본 브라우저에서 대시보드 자동 오픈 확인

### 설정/로그 저장 위치

- 설정 파일: `%APPDATA%\KISDashboard\settings.json`
- 런처 로그: `%APPDATA%\KISDashboard\logs\launcher.log`

### 참고

- 최초 실행 시 Windows 방화벽 경고가 뜨면 로컬 실행 허용
- 앱 종료는 작업 관리자에서 `KISDashboard.exe` 종료

## Windows 빌드 (배포 제작자용)

```bat
build_windows.bat
```

결과물:
- `dist\KISDashboard\`
- `dist\KISDashboard-win64.zip`
