# Polymarket X-Ray

Reverse-engineer the strategy behind any public Polymarket wallet: lifetime P&L, what it trades and how,
whether the edge is skill or luck, whether it still works, a Binance-lag test for crypto bots, a leaderboard
of top earners tagged by strategy, and a Claude-written strategy explanation you can question.

Read-only: uses Polymarket's public Data/Gamma APIs and Binance public market data. No keys needed
(except an optional Anthropic API key for the "Ask Claude" tab).

## Stack
- `backend/` — Java 25, Spring Boot 4, PostgreSQL + Flyway
- `frontend/` — Vue 3, Vite, TypeScript, Pinia, ECharts, Tailwind

## Run with Docker
```bash
cp .env.example .env   # optionally set ANTHROPIC_API_KEY
docker compose up --build
```
Open http://localhost:5173.

## Develop locally
```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun
cd frontend && npm install && npm run dev   # proxies /api to :8080
```
