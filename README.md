# Korea Investment Dashboard

Current release: `v1.6.10`

한국투자증권 API 기반 개인 계좌 대시보드입니다.

이 저장소에는 아래 세 가지가 함께 포함되어 있습니다.

- 웹/데스크톱 대시보드 (`app/`)
- Android 앱 (`android-app/`)
- GitHub Actions 기반 릴리스 파이프라인 (`.github/workflows/release.yml`)

## 주요 기능

- 포트폴리오 요약
  - 총 평가금액, 평가손익, 수익률, 자산 현황 확인
- 자산 상세
  - 보유종목, 수량, 평가금액, 손익, 자산 분포 확인
- 거래내역
  - 국내/해외 거래내역 및 실현손익 확인
- 통화 전환
  - Android 앱에서 주요 금액 KRW/USD 표시 전환 지원
- 보안
  - Android 앱 PIN 잠금 및 로컬 자격정보 저장
- 업데이트
  - GitHub Releases 기반 최신 버전 확인
  - 릴리스 정책에 따라 권장/필수 업데이트 처리

## 다운로드

릴리스 페이지:

- https://github.com/ianlyoo/koreainv-dashboard/releases

릴리스 아티팩트 이름:

- Android: `KISDashboard-android.apk`
- Windows: `KISDashboard-win64.zip`
- macOS: `KISDashboard-mac-arm64.zip`

## 설치 및 실행

### Android

1. 릴리스 페이지에서 `KISDashboard-android.apk` 다운로드
2. 기기에서 APK 설치
3. 앱 첫 실행 후 계좌/앱 설정 진행

참고:

- Android APK는 GitHub Releases를 통해 배포됩니다.
- 기기/정책에 따라 설치 시 보안 경고 또는 Play Protect 경고가 표시될 수 있습니다.

### Windows

1. `KISDashboard-win64.zip` 다운로드
2. 압축 해제
3. 실행 파일 실행
4. 브라우저에서 `http://127.0.0.1:8000` 열림 확인

### macOS (Apple Silicon)

1. `KISDashboard-mac-arm64.zip` 다운로드
2. 압축 해제
3. `KISDashboard.app` 실행
4. 브라우저에서 `http://127.0.0.1:8000` 열림 확인

참고:

- 현재 macOS 앱은 notarization 없는 배포본일 수 있습니다.
- 실행 차단 시 아래 명령으로 quarantine 제거:

```bash
xattr -dr com.apple.quarantine /Applications/KISDashboard.app
```

## Android 첫 실행

1. 한국투자증권 API 정보 입력
2. 계좌번호 입력
3. PIN 설정
4. 이후 PIN으로 잠금 해제 후 사용

## 개발

### 웹/로컬 앱 실행

```bash
python -m app.main
```

### Windows 배포본 빌드

```bat
build_windows.bat
```

### macOS 배포본 빌드

```bash
./scripts/build_mac_app.sh
```

### Android 빌드

```bash
cd android-app
./gradlew assembleDebug
./gradlew assembleRelease
```

## 릴리스 방식

이 저장소는 GitHub Actions `Build And Release` 워크플로로 릴리스됩니다.

- 태그 푸시: `v*`
- 워크플로 파일: `.github/workflows/release.yml`
- 태그 버전은 아래 두 값과 일치해야 합니다.
  - `app/version.py`
  - `android-app/app/build.gradle.kts`

예시:

```bash
git tag -a v1.6.5 -m "Prepare v1.6.5 release"
git push origin v1.6.5
```

### 필수 업데이트 릴리스

아래 문자열이 annotated tag 메시지에 포함되면 릴리스가 필수 업데이트로 처리됩니다.

- `[mandatory-update]`
- `mandatory-update`
- `update_policy: mandatory`
- `필수 업데이트`

예시:

```bash
git tag -a v1.6.5 -m "[mandatory-update]
Prepare v1.6.5 mandatory release"
git push origin v1.6.5
```

## 유지보수 참고

- 릴리스 정책 문서: `RELEASE_POLICY.md`
- Android 버전: `android-app/app/build.gradle.kts`
- Desktop/Web 버전: `app/version.py`

## 문제 해결

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
- API 키, 계좌번호, PIN, 설정 파일은 외부에 공유하지 마세요.
