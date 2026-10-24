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
