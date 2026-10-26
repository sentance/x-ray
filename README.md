# Polymarket X-Ray

Reverse-engineer the strategy behind any public Polymarket wallet: lifetime P&L, what it trades and how,
whether the edge is skill or luck, whether it still works, a Binance-lag test for crypto bots, a leaderboard
of top earners tagged by strategy, and an AI-written strategy explanation you can question (OpenAI).

Read-only: uses Polymarket's public Data/Gamma APIs and Binance public market data. No keys needed
(except an optional OpenAI API key for "Ask AI").

## Stack
- `backend/` — Java 25, Spring Boot 4, PostgreSQL + Flyway
- `frontend/` — Vue 3, Vite, TypeScript, Pinia, ECharts, Tailwind

## Run with Docker
```bash
cp .env.example .env   # optionally set OPENAI_API_KEY
docker compose up --build
```
Open http://localhost:5173.

## Develop locally
```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun
cd frontend && npm install && npm run dev   # proxies /api to :8080
```

## Strategy research

[Frozen weather-tail experiment](research/polymarket/PROTOCOL.md): a read-only Python collector and
capital-constrained historical screen using the current Data API v2. It saves raw responses locally,
uses actual NO-token observations, and reports chronological discovery/holdout results and missing data.
This is exploratory research; historical observations do not establish executable profit.
See the [2026-10-24 findings](research/polymarket/FINDINGS.md) for tested rules, results, and the next experiment.
The [observed-temperature protocol](research/polymarket/ELIMINATION_PROTOCOL.md) includes a bounded,
public-data watcher for eliminated bins and complete temperature-bin baskets. It never places orders.

[akroll wallet audit](research/polymarket/AKROLL_AUDIT.md) reconciles public activity to on-chain cash,
separates incentive income, and outlines a paper replica of its negative-risk quoting and hedging mechanism.
The app's **Paper Trading** page (`/paper`) runs a persistent, public-data simulation of a two-NO-leg
maker/hedger with a default $1,000 paper bankroll. Review the frozen limits, then start a test.
It models queues, partial fills, hedge latency, conversion fees and retained inventory; it never places real orders.
Earned incentives remain zero and optional scenarios stay separate from cash.
See the [paper runner guide](research/polymarket/PAPER_TRADING.md) for its exact policy, evidence export and limitations.
