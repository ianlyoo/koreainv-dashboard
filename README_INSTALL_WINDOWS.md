# KISDashboard Windows 설치/실행 가이드

이 문서는 **Python이 설치되어 있지 않은 Windows PC**에서 KISDashboard를 실행하는 방법을 안내합니다.

## 1) 준비물

- 배포 파일: `KISDashboard-win64.zip`
- 운영체제: Windows 10/11 (64-bit)

## 2) 설치 (압축 해제)

1. `KISDashboard-win64.zip` 파일을 다운로드합니다.
2. 원하는 위치(예: `C:\KISDashboard`)에 압축을 풉니다.
3. 압축 해제 후 `KISDashboard` 폴더 안에 `KISDashboard.exe`가 있는지 확인합니다.

## 3) 실행

1. `KISDashboard.exe`를 더블클릭합니다.
2. 잠시 후 기본 브라우저가 열리며 `http://127.0.0.1:8000` 대시보드로 이동합니다.
3. 첫 실행 시 로그인/초기 설정 화면에서 한국투자증권 정보를 입력합니다.

## 4) 설정/로그 파일 위치

- 설정 파일: `%APPDATA%\KISDashboard\settings.json`
- 로그 파일: `%APPDATA%\KISDashboard\logs\launcher.log`

탐색기 주소창에 아래를 입력하면 바로 이동할 수 있습니다:

```text
%APPDATA%\KISDashboard
```

## 5) 종료 방법

- 브라우저를 닫아도 프로세스가 남을 수 있습니다.
- 완전 종료하려면 작업 관리자에서 `KISDashboard.exe`를 종료합니다.

## 6) 자동 업데이트

- 앱 시작 시 GitHub Releases 최신 버전을 확인합니다.
- 새 버전이 있으면 업데이트 확인 창이 표시됩니다.
- 승인하면 업데이트 파일을 다운로드한 뒤 앱이 자동 재시작됩니다.

## 7) 자주 발생하는 문제

### 브라우저가 자동으로 안 열릴 때

- 브라우저에서 수동 접속:

```text
http://127.0.0.1:8000
```

### "포트 8000 사용 중" 오류

- 이미 같은 포트를 사용하는 프로그램이 있는 상태입니다.
- 점유 프로세스를 종료한 뒤 다시 실행합니다.

### 방화벽 경고 팝업이 뜰 때

- 로컬 환경에서 사용할 것이므로 허용합니다.
- 외부 네트워크 공개 용도가 아닙니다.

### 실행 직후 종료될 때

- `%APPDATA%\KISDashboard\logs\launcher.log` 내용을 확인합니다.
- 로그를 첨부해 개발자에게 전달합니다.

### 업데이트 실패 시

- `%APPDATA%\KISDashboard\logs\updater.log` 내용을 확인합니다.
- 앱을 완전히 종료한 후 최신 `KISDashboard-win64.zip`을 수동으로 덮어씌웁니다.
