# Korea Investment Dashboard

Current release: `v1.2.5`

한국투자증권 API 기반 개인 계좌 대시보드입니다.

## 사용자용 설치 (Windows, Python 불필요)

1. [Releases](https://github.com/ianlyoo/koreainv-dashboard/releases)에서 최신 `KISDashboard-win64.zip` 다운로드
2. 압축 해제 후 `KISDashboard.exe` 실행
3. 브라우저에서 `http://127.0.0.1:8000` 자동 오픈 확인
4. 첫 화면에서 `APP_KEY`, `APP_SECRET`, 계좌번호, PIN 설정

자세한 설치 문서:
- `README_INSTALL_WINDOWS.md`

## 자동 업데이트

- 앱 시작 시 GitHub Releases 최신 버전을 확인합니다.
- 새 버전이 있으면 업데이트 확인 창이 표시됩니다.
- 승인하면 업데이트 파일을 다운로드한 뒤 앱이 자동 재시작됩니다.

## 데이터 경로

- 설정 파일: `%APPDATA%\\KISDashboard\\settings.json`
- 런처 로그: `%APPDATA%\\KISDashboard\\logs\\launcher.log`
- 업데이트 로그: `%APPDATA%\\KISDashboard\\logs\\updater.log`

## 개발 실행

```bash
python -m app.main
```

브라우저에서 `http://127.0.0.1:8000` 접속.

## Windows 배포 빌드

```bat
build_windows.bat
```

결과물:
- `dist\\KISDashboard\\`
- `dist\\KISDashboard-win64.zip`

## 태그 기반 자동 릴리스

```bash
git tag v1.2.5
git push origin v1.2.5
```

GitHub Actions가 Windows 빌드를 수행하고 Releases에 zip을 자동 업로드합니다.

## 주의사항

- 본 프로젝트는 투자 보조 도구이며 투자 손실에 대한 책임을 지지 않습니다.
- API/계좌정보가 포함된 설정 파일은 외부로 공유하지 마세요.
