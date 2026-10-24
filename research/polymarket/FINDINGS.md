# Polymarket research findings — 2026-10-24

No strategy validated for live trading. Starting paper bankroll: $1,000. All work was local and read-only; no trading accounts, orders, production volumes, deployments, or trading-project files were changed.

## What was tested

Historical universe: all 11 binary temperature bins for each London and NYC daily event, October 1–22, 2026. All 44 events, 484 NO-token observations, and resolutions were obtained. No final-volume threshold was applied. Prices were queried six hours before each published end time with Data API v2 `as_of`. Observations were checked for staleness and forward-looking aggregate windows. Events were discovered from a fixed city/date list.

Every simulated trade stakes $10, including an assumed 0.05 × p × (1 − p) fee per share. The simulator uses one $1,000 cash ledger across chronological slices, reserves cash until reported closure, and caps open cost at $50 per event and $200 overall. The cost additions below are absolute cents per share, not percentages. Returns are on cumulative dollars staked, not on the full bankroll.

| Experiment | Cost above historical observation | Trades | Net simulated P&L | Return on stake |
|---|---:|---:|---:|---:|
| NO at 90–98¢ | 1¢ | 58 | −$1.58 | −0.27% |
| NO at 90–98¢ | 2¢ | 58 | −$7.22 | −1.25% |
| NO at 90–98¢ | 3¢ | 33 | −$11.43 | −3.46% |
| Forecast-value NO | 1¢ | 39 | −$22.34 | −5.73% |
| Forecast-value NO | 2¢ | 39 | −$29.91 | −7.67% |
| Forecast-value NO | 3¢ | 39 | −$37.15 | −9.53% |

At 3¢ cost some high-price entries become impossible and are rejected; prices are never clipped below $1 to manufacture a fill.

The price-only rule's October 12–22 holdout won all 30 trades at 1¢/2¢ costs. At 2¢ it made $6.88 there but lost $14.11 in the earlier slice. A bootstrap of an all-winning sample cannot discover an unobserved loss, so its narrow positive holdout interval is not a profitability certificate. The full-period 2¢ date-block interval was approximately −6.56% to +2.55% return on stake. There are 22 calendar dates, not 484 independent trials.

The forecast experiment was fixed after inspecting those outcomes, so neither slice is an unseen holdout for it. It used exact-airport archived GFS runs initialized at least 12 hours before the decision, explicit normal-error assumptions, and a conservative parameter stress grid. One candidate per event could be bought. Two October 22 forecast runs were unavailable: the API reported that the October 21 18:00 UTC GFS run did not exist. Coverage was 42 events / 462 bins. No replacement model/run was substituted to improve the result.

The uncalibrated central model was worse than market prices on matched observations: Brier error 0.07052 versus 0.04660 (lower is better). At 2¢ cost its earlier slice made $29.90 and its later slice lost $59.81. Its maximum realized drawdown was $116.59. This rejects this particular implementation; it does not establish that weather forecasting cannot work.

## Current books and observations

Captured 22 live temperature-market pairs on October 24. Fifteen had enough displayed ask depth to buy ten equal shares of both outcomes while meeting minimum size. None had total cost below the complete-set payout after current metadata fees. This was one unsynchronized REST snapshot and does not measure opportunity frequency or simulate fills.

The two markets whose NO midpoints were in the historical test band had half-spreads of 0.45¢ and 2.5¢, respectively. Those two examples do not estimate historical spreads, but they show why a fixed tiny cost assumption is unsafe. Historical fees were assumed, not reconstructed from a point-in-time fee archive.

A separate diagnostic used official Aviation Weather Center METAR reports, limited to the station's local day and reports publicly received before each captured book request. With a 0.5°C rounding guard, no captured lower bin was eliminated by an already-observed high. METAR is a proxy for the exact NOAA resolution table; this is not a validated settlement signal.

## Wallet leads

Captured the current monthly weather-P&L top ten and up to 500 recent mixed-category activity records per wallet. The selected-winner and truncated-history biases are explicit in the output.

- Poligarch: 349 sampled temperature trades, all YES buys; median price 45¢ and median individual fill $2.82. The full sample covered only 9.2 hours. This cannot establish that it never sells, reveal its forecast, or identify maker/taker status.
- badatmath.: 155 temperature buys and 201 sells, ten conditions with both outcomes bought, plus merges in its broader activity sample.
- Bilberry: 203 temperature buys and 99 sells, median buy price about 31.8¢.

These are research leads, not accounts to copy. A public fill is not a price available later to another trader. Monthly leaderboard P&L divided by monthly volume is not a bankroll return; redemptions, transfers, opening inventory, rewards, and other activity need reconciliation. One ranked wallet's sampled activity contained conversions/redemptions/merges and no trades, illustrating that problem.

## Next experiment

Prioritize a forward **observed-temperature elimination** test over further tuning of the rejected rules. For each chosen airport, ingest the exact market-specified observation table, preserve original receipt times, and maintain the day's running maximum in the correct local timezone. A lower bin becomes a candidate only after verified observations, rounding, sampling conventions, and a safety margin rule it out. Corrections and disputes remain possible, so do not describe this as risk-free.

Before the first forward result, fix station coverage, observation-to-order latency, entry margin, and stopping rules. Capture both outcome books and actual fee parameters, require full-size ask depth, and record missed signals. Keep $10 stakes and the paper capital caps as simulation assumptions until the user's loss tolerance is specified. Use at least 60 calendar days without tuning; no minimum duration guarantees significance or profitability. Approve no live deployment from the present research.

The current book and observation scripts provide diagnostics; they are **not** a deployed, continuously running forward paper trader. No forward observation campaign or schedule was started.

## Project artifacts and validation

- [Price-only frozen protocol](PROTOCOL.md), [results](results.json)
- [Forecast protocol](FORECAST_PROTOCOL.md), [results](forecast-results.json)
- [Live book diagnostic](live-results.json), [observed-high diagnostic](observed-results.json)
- [Wallet samples](wallet-results.json)
- Raw API responses remain under gitignored `data/` in this checkout.
- Forty targeted tests pass, including timestamp/aggregate leakage, delayed reports, local dates, corrections, exact METAR parsing and rounding, versioned token IDs, exhaustive basket partitions, forecast availability, actual depth, fees, unresolved capital, event/gross caps, settlement reuse, and bounded monitoring failure handling. `git diff --check` passes.

X-Ray's existing Java wallet client still calls some v1 activity/position/leaderboard routes. The official [Data API migration guide](https://docs.polymarket.com/migrate/data-api-v1-to-v2) says v1 retired on October 24, 2026. The research tools use v2. Migrating the existing application is separate remaining integration work; its wallet screens should not yet be treated as verified against the current API.

Primary mechanics sources: [order books](https://docs.polymarket.com/market-data/prices-order-books), [fees](https://docs.polymarket.com/trading/fees), [archived forecast runs](https://open-meteo.com/en/docs/single-runs-api). API snapshots, not promotional profitability claims, underpin the numerical results above.

## Follow-up: exact-source elimination and complete baskets

The follow-up inspected today's actual rule text. Most October 24 daily temperature events name Aviation Weather Center routine METARs, unlike the NOAA-viewer contracts in the earlier historical sample. The earlier generic observation diagnostic did not implement those exact current rules; it should remain a proxy diagnostic, not a contract-exact test.

The new parser implements the current contract: routine reports with or without a METAR label count; SPECI reports do not. A complete eight-digit T remark overrides body whole-degree temperature. Conversion precedes rounding, and exact halves round toward positive infinity, including negative Fahrenheit values. Local-day filtering uses fixed station-specific IANA zones. Later corrections replace earlier observations at the same timestamp, so a corrected high can fall. It does not substitute six-hour or 24-hour maximum remarks for the qualifying temperature.

At approximately **12:15 Prague time on October 24**, a complete capture covered 94 temperature events. It supported 1,012 bins at 48 airports; 22 Hong Kong bins used a different source and were excluded. All supported stations had qualifying reports. No final-volume filter was applied.

- 312 bins were already eliminated by a recorded high or low plus the fixed extra one-degree buffer.
- 293 lacked enough displayed NO ask depth for ten shares.
- 19 had depth, but none met the 1¢-per-share margin after current fees.
- The best ten-share quote cost $9.99, leaving only $0.01 total conditional surplus before settlement costs and correction risk. That is 0.1¢ per share, not 1¢. One complete loss would erase roughly 999 such winning fills; no correction probability was estimated.
- No source or book requests failed in this completed capture. An earlier incomplete pass had two book timeouts; its failures remain in the raw cache and were not reported as absent opportunities.

At approximately **12:17 Prague time**, a separate complete-basket screen inspected all 49 highest-temperature events, supporting 48 ordinary negative-risk events and excluding Hong Kong's unsupported source. It checked that integer bins form one exhaustive, non-overlapping partition with consistent source/station/unit, and priced ten YES shares per bin using real ask depth and fees.

- 43 baskets had full displayed depth; 5 did not.
- No basket cost less than the $10 conditional payout.
- Cheapest: Manila, $10.09 cost for a $10 payout (−$0.09 conditional surplus).
- Median among baskets with depth: $10.84 cost for a $10 payout.
- No book request errors occurred. No first-quote candidate qualified, so no positive candidate required rechecking.

These captures reject immediate entry at the tested quotes. They cannot measure how often a brief opportunity might occur. Book batches are not synchronized, a basket's legs would not execute atomically, and no fills or realized profits were recorded.

Artifacts: [elimination protocol](ELIMINATION_PROTOCOL.md), [elimination results](elimination-results.json), [basket protocol](FULLSET_PROTOCOL.md), [basket results](fullset-results.json).

The new [bounded watcher](watch.py) can run repeated captures of both tests, save failures, and deduplicate confirmed candidates. A metadata/fee/rule check and a <=2-second book recheck are mandatory before labeling a quote confirmed. It cannot place orders. It is implemented and tested, but no unattended monitoring campaign or scheduled service was started. A long forward sample and an execution model remain necessary before evaluating this as a money-making strategy.
