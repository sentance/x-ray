# Observed-temperature elimination — fixed forward screen

Started 2026-10-24 after the historical experiments in FINDINGS.md. This is a new live-data research screen, not a live trading strategy. No signed orders, account keys, or real funds are used.

Universe: all daily highest- and lowest-temperature events returned by Gamma's Weather tag for the requested date. No volume filter. Record exclusions and discovery pagination. Support only the exact routine-METAR Aviation Weather Center rules verified below; other sources fail closed. Each station uses a fixed, checked IANA timezone. Record the market's full rule text and hash on every capture.

Read the raw report from the named source. Exclude reports whose line starts with SPECI; accept routine reports with and without a METAR label. Use a complete T+8-digit temperature remark first, body whole-degree temperature only when that remark is absent. Do not use six-hour or 24-hour extrema remarks or the decoded JSON temperature as a substitute. Keep the latest received correction per station/observation time. Use only observations on the requested local calendar day with observation and provider receipt timestamps no later than the local response receipt.

Convert to the bin's stated unit, then round ties toward positive infinity exactly as specified by the current market. Highest-temperature bins strictly below the running high, and lowest-temperature bins strictly above the running low, become candidates. Require a further one-whole-degree gap between the observed rounded extremum and the candidate bin boundary as a fixed correction buffer. This buffer is not a guarantee against corrections or disputes. No forecast is needed.

Fetch current YES and NO books after receiving the observation response. Evaluate ten NO shares, consume actual ask depth, check minimum size, condition/asset IDs, and current market fee schedule (known exponent 1 only). A candidate needs at least $0.10 surplus on ten shares after fees: at least 1¢ per share. Do not infer NO from YES, clip prices, or add rewards/rebates. Record all eliminated bins, including no book, insufficient depth, missing fees, and small surplus.

Re-fetch both books for candidates crossing the margin. Treat the recheck as a new hypothetical decision, use its current full-depth cost, and require request duration <=2 seconds. Recheck rules, accepting-orders state, and fee parameters too. Otherwise log the candidate as unconfirmed. Even confirmed quotes are simulated opportunities, not verified fills or profits. Count unique market IDs rather than adding the same opportunity over repeated snapshots.

Paper budget for future simulations: $1,000, maximum $10 all-in per candidate, $50 per event, $200 gross reserved. The ten-share screen can cost less than $10. The user's actual loss tolerance is unspecified; these caps do not authorize real trades. Do not book conditional surplus as realized P&L. A forward fill/settlement ledger is separate from the quote screen.

Run as a bounded capture; each invocation saves its own raw responses and report. Repeated captures should span at least 60 calendar days without optimizing the rules. There is no continuously running service or scheduled job unless explicitly configured later.

```sh
python3 research/polymarket/elimination.py --date 2026-10-24
python3 -m unittest discover -s research/polymarket -p 'test_*.py'
```

For a bounded monitoring session, the watcher runs both this screen and the complete-basket screen. This command performs twelve captures, waiting at least five minutes between scheduled starts and at least one minute after any overrun; runtime depends on network delays. It creates no service or scheduled job:

```sh
python3 research/polymarket/watch.py --date 2026-10-24 --captures 12 --interval-seconds 300
```

Campaign records preserve failures and deduplicate confirmed quotes by market/event. They do not treat repeated quotes as additional trades or record hypothetical surplus as realized profit. Change the date explicitly for a new trading day.

Source semantics were checked against today's Gamma rule text and the [Aviation Weather Center product documentation](https://aviationweather.gov/help/data/). The [former NOAA viewer](https://www.weather.gov/wrh/timeseries?site=KLGA) has different hourly filtering and rounding; those rules must not be silently mixed with today's routine-METAR contract.
