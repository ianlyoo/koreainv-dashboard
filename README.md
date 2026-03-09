# Korea Investment Dashboard

Current release: `v1.3.6`

한국투자증권 API 기반 개인 계좌 대시보드입니다.  
설치 후 실행하면 로컬에서 대시보드가 열리고, 계좌/종목 데이터를 한 화면에서 확인할 수 있습니다.

## 주요 기능

- 포트폴리오 요약: `총 평가금액`, `총 평가손익`, `보유 현금(KRW/USD)`를 상단 카드로 표시
- 보유 자산 상세: 마켓/수량/평가금액/평균단가/현재가/수익률, 마지막 동기화 시각 표시
- 통화 토글: `$` 기준/`원` 기준 표시 전환
- 종목 검색: 국내/해외 종목 검색 후 즉시 선택
- 인사이트 패널(종목정보):
  - 핵심 지표: 선행 PER, ROE, 부채비율, Beta, 시총, 공매도비중, 목표가, 분석가 평가
  - 시세 컨텍스트: 52주 범위, 차트
  - 파생/뉴스: 옵션 체인 요약(PCR/IV/Max Pain 등), 최신 뉴스
- 시장 정보: 주요 지표 위젯 + 증시 캘린더
- 사용 편의:
  - 새로고침 버튼으로 즉시 동기화
  - 화면 잠금(PIN)
  - 로그아웃 시 기기 내 API/PIN 설정 초기화
- 자동 업데이트:
  - 실행 시 최신 릴리스 확인
  - 릴리스 본문에 `mandatory-update`(또는 `update_policy: mandatory`, `필수 업데이트`)를 포함하면 필수 업데이트로 동작
  - 필수 업데이트: 앱 시작 시 OS 관계없이 즉시 업데이트(건너뛰기 불가)
  - 권장 업데이트(기본값): 앱 시작 시 팝업 없음, 앱 메뉴 `업데이트 확인`에서만 선택 가능
  - 업데이트 완료 후 `업데이트 완료했습니다. 앱을 다시 시작합니다.` 안내
- 버전 확인:
  - Windows 트레이 메뉴 `버전 확인`
  - macOS Dock 앱 메뉴 `버전 확인`
- 수동 업데이트 확인:
  - Windows 트레이 메뉴 `업데이트 확인`
  - macOS Dock 앱 메뉴 `업데이트 확인`

## 다운로드 및 설치

릴리스 페이지: [GitHub Releases](https://github.com/ianlyoo/koreainv-dashboard/releases)

### Windows

1. `KISDashboard-win64.zip` 다운로드
2. 압축 해제 후 `KISDashboard.exe` 실행
3. 브라우저에서 `http://127.0.0.1:8000` 자동 오픈 확인

### macOS (Apple Silicon)

1. `KISDashboard-mac-arm64.zip` 다운로드
2. 압축 해제 후 `KISDashboard.app` 실행
3. 브라우저에서 `http://127.0.0.1:8000` 자동 오픈 확인

참고:
- 현재 macOS 앱은 ad-hoc 서명(Developer ID/Notarization 없음)입니다.
- 다운로드 앱 실행이 차단되면:

```bash
xattr -dr com.apple.quarantine /Applications/KISDashboard.app
```

## 첫 실행 설정

1. `APP_KEY`, `APP_SECRET` 입력
2. 계좌번호, PIN 설정
3. 이후 PIN으로 잠금 해제하여 사용

## 문제 해결 (로그 위치)

### Windows

- 설정: `%APPDATA%\KISDashboard\settings.json`
- 런처 로그: `%APPDATA%\KISDashboard\logs\launcher.log`
- 업데이트 로그: `%APPDATA%\KISDashboard\logs\updater.log`

### macOS

- 런처 로그: `~/Library/Logs/KISDashboard/launcher-mac.log`
- 업데이트 로그: `~/Library/Logs/KISDashboard/updater-mac.log`
- 업데이트 파일 저장: `~/Library/Application Support/KISDashboard/updates`

## 안내

- 본 프로젝트는 투자 보조 도구이며 투자 손실에 대한 책임을 지지 않습니다.
- API/계좌정보가 포함된 설정 파일은 외부로 공유하지 마세요.

## 개발/배포 (Maintainer)

- 릴리스 업데이트 정책 문서: `RELEASE_POLICY.md`

```bash
python -m app.main
```

```bat
build_windows.bat
```

```bash
./scripts/build_mac_app.sh
```

```bash
git tag v1.3.6
git push origin v1.3.6
```
