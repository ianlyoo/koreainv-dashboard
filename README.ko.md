# koreainv-dashboard

Korea Investment Securities dashboard — track portfolio and market monitoring with Google Sheets ops integration in live trading workflows.

[English](README.md) · [![CI](https://github.com/ianlyoo/koreainv-dashboard/actions/workflows/ci.yml/badge.svg)](https://github.com/ianlyoo/koreainv-dashboard/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/ianlyoo/koreainv-dashboard)](https://github.com/ianlyoo/koreainv-dashboard/releases) [![Pages](https://img.shields.io/badge/Pages-live-brightgreen)](https://ianlyoo.github.io/koreainv-dashboard/)

> **소셜 프리뷰:** `https://ianlyoo.github.io/koreainv-dashboard/assets/social-preview.png` (1280×640) — GitHub Settings 수동 업로드는 `docs/OWNER_ACTIONS.md` 참조.

## 빠른 시작 — Google Sheets와 연결된 Korea Investment 대시보드

koreainv-dashboard는 KIS와 Toss 계좌를 집계해 포트폴리오와 마켓 데이터를 보여주고 Google Sheets로 운영을 연결한다.

### Tarball 설치

```bash
gh release download v1.7.1 --repo ianlyoo/koreainv-dashboard --pattern "koreainv-dashboard-*.tgz" --dir /tmp
npm install /tmp/koreainv-dashboard-1.7.1.tgz
```

### 소스 빌드

```bash
git clone https://github.com/ianlyoo/koreainv-dashboard.git
cd koreainv-dashboard
bun install --frozen-lockfile
bun run build
python3 -m app.main
```

KIS와 Google Sheets 인증은 env로 주입한다. 인증 없이는 데모 모드로 동작하며 실제 주문은 실행되지 않는다.

## 포트폴리오 추적과 주식 대시보드 사용 사례

- KIS와 Toss 보유를 portfolio-tracking 뷰로 추적한다.
- stock-dashboard에서 KRW/USD 전환과 300초 캐시로 finance 현황을 본다.
- Google Sheets를 watchlist와 배분 노트의 기준으로 ops 워크플로를 운영한다.

중앙 예약 서버는 기본 off이며 `CENTRAL_ORDER_EXECUTION_ENABLED=true`일 때만 동작한다.

## 아키텍처: kis-api와 모니터링 파이프라인

```mermaid
flowchart LR
    A[Desktop / Web] --> C[중앙 예약 서버 - gated]
    B[Android] --> C
    C -- "CENTRAL_ORDER_EXECUTION_ENABLED=true" --> D[KIS Open API]
    A --> D
    B --> D
```

흐름은 `집계 → 정규화 → Google Sheets ops → 모니터링 insight`이며 모든 계산은 결정론적이다.

## 벤치마크: 측정된 집계와 트레이딩 인사이트 지연

2026-08-19 측정 (seed 42, 조건당 1회, 12 holdings, 3 accounts). 집계 중앙값 210 ms, insight miss 480 ms, hit 18 ms, Google Sheets read 620 ms.

**제한사항:** 1회성 합성 데이터이며 네트워크와 쿼터에 따라 다르다. 캐시 TTL 300초로 실시간 트레이딩 중 stale이 가능하고 결과는 provider 보고 시간이다.

## Developer-tools와 TypeScript, ops

TypeScript 프론트와 Google Sheets ops 헬퍼를 포함한다. `pytest -q`, `ruff`, `bun run build`로 검증한다.

## 라이선스

MIT — [LICENSE](LICENSE) 참조.
