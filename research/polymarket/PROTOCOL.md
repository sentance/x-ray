# Weather tail test — frozen before collecting returns

Created 2026-10-24. This is an exploratory historical screen, not an approved live strategy.

Hypothesis: daily temperature YES tails are overpriced enough that buying their NO shares can cover costs.

- Universe: every binary bin in London and NYC daily highest-temperature events dated October 1–22, 2026. Discover events by their predetermined city/date slug, including missing and unresolved events in coverage counts. No volume, liquidity, winner, wallet, or realized-profit selection.
- Decision: six hours before each market's published `endDate`. This is a scheduled metadata timestamp, not actual resolution. Historical changes to that timestamp are not available and remain a limitation.
- Signal: the NO token's own historical observation in [0.90, 0.98], inclusive. Never infer its price from YES.
- Data: public Data API v2 `prices-history?as_of=...`. Reject observations newer than the decision, aggregate windows extending past it, observations older than 10 minutes, and markets created after the decision or closed before it.
- Discovery: October 1–11. Holdout: October 12–22. Both periods are exploratory because this historical family was chosen after prior Polymarket research. A future untouched paper test is still required.
- Costs: two explicit scenarios, +1¢ and +2¢ per share above the historical observation, plus a fixed 0.05 × price × (1 − price) fee per share. These are assumptions, not historical executable asks or verified historical fee schedules. Reject entries at or above $1. No price clipping, rebates, or rewards.
- Paper portfolio: $1,000 cash, $10 all-in stake per bin, at most $50 tied up per city/date event and $200 total. These are simulation settings, not the user's stated loss tolerance. Allocate deterministically by entry time, event slug, then market ID; no ranking by realized outcome. Release funds at reported closure; count open/unresolved selected positions as fully reserved with no booked payout.
- Inference: resample whole UTC decision dates, retaining all cities and bins together. Report a seeded 95% block-bootstrap interval of aggregate profit / aggregate stake. Few dates, shared weather systems, sparse losses, and selection bias limit the interval; it is descriptive and does not authorize trading.
- Report all scenarios and both periods. Do not tune bands, cities, dates, or offsets against the holdout. Missing snapshots or unresolved markets prevent an approval conclusion, regardless of apparent return.

Amendment before computing historical returns: add a +3¢ execution-cost stress scenario after inspecting current books. The two live tail-band books showed half-spreads of 0.45¢ and 2.5¢, respectively; this small diagnostic is not a historical cost estimate. All original scenarios remain in the report.

Promotion requires a new forward paper test recording both outcome books, available size, receipt timestamps, latency, actual market fees, outcomes, missed entries, cash, event exposure, and conservative execution. No orders are submitted by these tools.

Sources checked on 2026-10-24:

- https://data-api.polymarket.com/v2/openapi.json — `as_of` and per-point `resolution_seconds` semantics.
- https://docs.polymarket.com/market-data/prices-order-books — separate token books and depth.
- https://docs.polymarket.com/trading/fees — price-dependent fee formula.
- https://docs.polymarket.com/trading/market-making — batches evaluate each order independently; two legs are not atomic.
- https://docs.polymarket.com/migrate/data-api-v1-to-v2 — v1 retirement on October 24, 2026. Existing X-Ray uses some v1 endpoints; those need a separate migration before relying on its wallet screens.

Run from the repository root (Python 3.12+, standard library only):

```sh
python3 research/polymarket/weather.py collect --cache research/polymarket/data
python3 research/polymarket/weather.py analyze --cache research/polymarket/data --output research/polymarket/results.json
python3 -m unittest discover -s research/polymarket -p 'test_*.py'
```

Current spread/depth diagnostic (a new timestamped capture on each run):

```sh
python3 research/polymarket/books.py --date 2026-10-24
```

The second, independent-feature exploratory experiment has its own [forecast protocol](FORECAST_PROTOCOL.md).

The cache records metadata and exact API responses, including failures. Network errors are retried on a later collection run; successful historical responses are reused. Raw data is gitignored. `results.json` contains coverage and portfolio summaries; no credentials are used.
