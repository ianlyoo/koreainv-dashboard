# Multi-Broker Investment Dashboard

> 한국투자증권(KIS)·토스증권 Open API 기반 개인 계좌 대시보드 — 데스크톱·웹·Android

[English](README.en.md) · [MIT License](LICENSE) · Python · Android · ![Release](https://github.com/ianlyoo/koreainv-dashboard/actions/workflows/release.yml/badge.svg)

KIS와 토스증권 계좌의 포트폴리오·자산을 한 화면에서 보는 개인용 대시보드다.
KIS 계좌는 기존 거래내역·실현손익·예약주문 기능도 그대로 제공한다.
데스크톱/웹 앱과 Android 앱, 그리고 GitHub Releases 기반 업데이트 파이프라인을 함께 담고 있다.

**이 프로젝트는 조회 전용이 아니다.** 소규모(1~2인) 운영을 위한 중앙 예약주문 서버
슬라이스가 포함되어 있고, 실제 KIS 주문 실행은 `CENTRAL_ORDER_EXECUTION_ENABLED`로
명시적으로 켜야만 동작한다. 켜지 않으면 실행되지 않는다.

## 아키텍처

```mermaid
flowchart LR
    A[데스크톱 / 웹 앱<br/>app/] --> C[중앙 예약주문 서버<br/>선택 · 게이트됨]
    B[Android 앱<br/>android-app/] --> C
    C -- "CENTRAL_ORDER_EXECUTION_ENABLED=true<br/>일 때만" --> D[KIS Open API]
    A --> D
    B --> D
    A --> E[토스증권 Open API<br/>자산 조회]
    B --> E
```

## 빠른 시작

릴리스 페이지에서 플랫폼별 아티팩트를 받는다: https://github.com/ianlyoo/koreainv-dashboard/releases

| 플랫폼 | 아티팩트 |
|---|---|
| Android | `KISDashboard-android.apk` |
| Windows | `KISDashboard-win64.zip` |
| macOS | `KISDashboard-mac-arm64.zip` |

최초 실행 시 증권사를 선택하고 API 자격증명과 계좌 정보를 입력한다. Android는 PIN을 설정한 뒤 이후 PIN으로 잠금 해제한다.

## 기능

| 기능 | 설명 |
|------|------|
| 포트폴리오 요약 | 총 평가금액, 평가손익, 수익률, 자산 현황 |
| 자산 상세 | 보유종목·수량·평가금액·손익·자산 분포 |
| 멀티 브로커·계좌 | KIS와 토스증권 계좌를 원하는 수만큼 등록하고 병렬 조회·합산 |
| 거래내역 | 국내/해외 거래내역 및 실현손익 |
| 통화 전환 | Android에서 주요 금액 KRW/USD 표시 전환 |
| 보안 | Android PIN 잠금 및 로컬 자격정보 저장 |
| 업데이트 | GitHub Releases 기반 최신 버전 확인, 권장/필수 업데이트 처리 |

## 증권사별 지원 범위

| 기능 | KIS | 토스증권 |
|---|---:|---:|
| 국내·미국 보유주식 조회 및 합산 | O | O |
| 현금·주문가능금액 | O | - |
| 거래내역·실현손익 | O (첫 KIS 계좌) | - |
| 예약주문 | O (첫 KIS 계좌) | - |

- 기존 저장 데이터에는 `broker=kis`가 자동 적용되어 별도 재설정이 필요 없다.
- 토스증권은 WTS의 Open API 메뉴에서 발급한 `client_id`, `client_secret`을 입력하면 웹과 Android 앱이 `GET /api/v1/accounts`로 계좌를 자동 조회한다. 계좌가 하나면 자동 선택하고 여러 개면 선택 목록을 표시하며, 내부적으로 선택된 `accountSeq`를 저장한다.
- 토스증권의 현재 보유자산 API는 주식 잔고를 제공하지만 현금 예수금은 제공하지 않으므로 토스 현금은 합계에 포함하지 않는다.
- 계좌 목록에서 가장 먼저 등록된 KIS 계좌가 거래내역·예약주문·KIS 실시간 시세용 대표 계좌가 된다. 토스 계좌가 목록 앞에 있어도 KIS 주문 경로로 사용되지 않는다.

## 중앙 예약주문 서버 (선택)

1~2인 규모를 위한 중앙 예약주문 서버 슬라이스가 포함되어 있다.

- `CENTRAL_ORDER_SERVER_MODE=true`로 서버 모드를 켠다.
- 원격 클라이언트는 `CENTRAL_ORDER_SERVER_TOKEN`으로 인증한다.
- 저장되는 실행 자격증명은 `CENTRAL_ORDER_MASTER_KEY`(Fernet)로 암호화된다.
- 만기된 주문은 `CENTRAL_ORDER_POLL_INTERVAL_SECONDS`마다 인프로세스 워커가 폴링한다.
- **실제 KIS 실행은 `CENTRAL_ORDER_EXECUTION_ENABLED=true`로만 게이트된다.**
- 예약주문은 쓰기 가능한 user-data 디렉토리의 `scheduled_orders.json`에 저장된다.
- 시작 systemd 유닛 예시: `scripts/koreainv-dashboard-central.service.example`

## 토스 개인 조회 프록시 (선택)

고정 공인 IP가 없는 Android 모바일 데이터 환경에서는 개인 Oracle/VPS 서버를 토스 조회 전용 프록시로 사용할 수 있다. 프록시는 계좌 목록·보유자산·환율만 중계하며 주문 API를 제공하지 않고, 토스 자격증명을 서버 디스크에 저장하지 않는다.

Oracle 서버의 비공개 `.env`:

```env
TOSS_PROXY_SERVER_ENABLED=true
TOSS_PROXY_SERVER_TOKEN=<충분히 긴 랜덤 토큰>
CENTRAL_ORDER_EXECUTION_ENABLED=false
```

1. Oracle 서버를 HTTPS 리버스 프록시 뒤에서 실행한다.
2. Oracle 서버의 고정 공인 IPv4를 토스 WTS Open API 허용 IP로 등록한다.
3. Android 토스 계좌 설정에서 `개인 서버`를 선택하고 HTTPS 주소와 토큰을 입력한다. 이 값은 API 자격증명과 함께 기기에서 암호화된다.
4. 웹/데스크톱도 프록시를 사용할 경우 해당 클라이언트의 비공개 `.env`에 아래 값을 설정한다.

```env
TOSS_PROXY_REMOTE_URL=https://your-private-server.example
TOSS_PROXY_REMOTE_TOKEN=<서버와 동일한 토큰>
```

서버 자체에는 `TOSS_PROXY_REMOTE_URL`을 설정하지 않는다. 실제 서버 주소·토큰·API 자격증명은 저장소나 APK에 하드코딩하지 않는다.

## 설정 레퍼런스

| 환경변수 | 필수 | 설명 |
|---|---|---|
| `CENTRAL_ORDER_SERVER_MODE` |  | 중앙 서버 모드 활성화 |
| `CENTRAL_ORDER_SERVER_TOKEN` | 서버 모드 시 | 원격 클라이언트 인증 토큰 |
| `CENTRAL_ORDER_MASTER_KEY` | 서버 모드 시 | 저장 자격증명 암호화용 Fernet 키 |
| `CENTRAL_ORDER_EXECUTION_ENABLED` |  | 실제 KIS 주문 실행 게이트 (기본 꺼짐) |
| `CENTRAL_ORDER_POLL_INTERVAL_SECONDS` |  | 만기 주문 폴링 주기 |
| `CENTRAL_ORDER_REMOTE_URL` |  | 데스크톱 클라이언트가 주문을 넘길 중앙 서버 URL |
| `CENTRAL_ORDER_REMOTE_TOKEN` |  | 원격 전달용 토큰 |
| `TOSS_PROXY_SERVER_ENABLED` |  | 읽기 전용 토스 프록시 서버 활성화 |
| `TOSS_PROXY_SERVER_TOKEN` | 프록시 서버 시 | 프록시 요청 Bearer 토큰 |
| `TOSS_PROXY_REMOTE_URL` | 프록시 클라이언트 시 | 개인 프록시 HTTPS URL |
| `TOSS_PROXY_REMOTE_TOKEN` | 프록시 클라이언트 시 | 개인 프록시 Bearer 토큰 |
| `COOKIE_SECURE` |  | HTTPS 뒤 배포 시 `true` |

### Oracle Ubuntu 배포 참고

1. `CENTRAL_ORDER_MASTER_KEY`용 Fernet 키를 생성한다.
2. `COOKIE_SECURE=true`로 둔다.
3. HTTPS 리버스 프록시(Nginx/Caddy) 뒤에서 돌리고 `CENTRAL_ORDER_SERVER_TOKEN`을 비공개로 유지한다.
4. 주문을 중앙 서버로 전달할 데스크톱 클라이언트에 `CENTRAL_ORDER_REMOTE_URL`/`CENTRAL_ORDER_REMOTE_TOKEN`을 설정한다.

## 보안

- 주문 실행 경로가 존재하므로 `CENTRAL_ORDER_EXECUTION_ENABLED`는 의도적으로 켤 때만 활성화한다.
- 저장 실행 자격증명은 `CENTRAL_ORDER_MASTER_KEY`로 암호화되어 저장된다.
- API 키·계좌번호·PIN·설정 파일은 외부에 공유하지 않는다.
- 중앙 서버는 HTTPS 뒤에서만 노출하고 서버 토큰을 비공개로 유지한다.

## 릴리스 방식

GitHub Actions `Build And Release` 워크플로(`.github/workflows/release.yml`)로 릴리스한다.

- 태그 푸시: `v*`
- 태그 버전은 `app/version.py`(`APP_VERSION`)와 `android-app/app/build.gradle.kts`와 일치해야 한다.

```bash
git tag -a v1.6.5 -m "Prepare v1.6.5 release"
git push origin v1.6.5
```

annotated tag 메시지에 아래 문자열이 포함되면 필수 업데이트로 처리된다:
`[mandatory-update]`, `mandatory-update`, `update_policy: mandatory`, `필수 업데이트`.

## 개발

```bash
python -m app.main            # 웹/로컬 앱 실행
build_windows.bat             # Windows 배포본
./scripts/build_mac_app.sh    # macOS 배포본
cd android-app && ./gradlew assembleRelease   # Android
```

버전 소스: 데스크톱/웹은 `app/version.py`, Android는 `android-app/app/build.gradle.kts`. 정책 문서는 `RELEASE_POLICY.md`.

## 문제 해결

| OS | 경로 |
|---|---|
| Windows | 설정 `%APPDATA%\KISDashboard\settings.json`, 로그 `%APPDATA%\KISDashboard\logs\` |
| macOS | 로그 `~/Library/Logs/KISDashboard/`, 업데이트 `~/Library/Application Support/KISDashboard/updates` |

## Disclaimer

투자 보조 도구이며 투자 손실에 대한 책임을 지지 않는다. API 키·계좌번호·PIN·설정 파일을 외부에 공유하지 않는다.

## License

[MIT](LICENSE) © 2026 AhnRyu
