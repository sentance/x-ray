# Independent forecast signal — exploratory second experiment

Fixed on 2026-10-24 after the price-only test was analyzed. October 1–22 outcomes have already been inspected: **neither chronological slice is an unseen holdout for this experiment**. This test can reject an idea or nominate it for future paper observation, not establish an edge.

Use the same 44 events and 06:00 UTC decisions, with actual NO-token historical observations. Add archived NCEP GFS global forecasts at the actual resolution stations: London City (51.5053, 0.0553), and LaGuardia (40.7769, −73.8740). Read each event's resolution rules; the airport and the required local calendar day matter.

Use the latest six-hour model initialization **at least twelve hours before the decision**. Initialization is not publication: global forecasts take several hours to become available. Twelve hours is a conservative assumption, not a verified historical receipt time. Retrieve individual archived runs, not reanalysis or a stitched historical series containing later runs. Require all 24 hourly forecast values for the target local day.

Convert that day's forecast hourly maximum to a distribution of the reported daily high. This is an explicit, uncalibrated model assumption: normal errors of 2°C. Calculate coherent probabilities for rounded whole-degree resolution bins (including open-ended tails); convert NYC's Fahrenheit boundaries to Celsius. For entry, require the **minimum NO probability** across mean errors −1, 0, +1°C and standard deviations 1.5, 2, 2.5°C to exceed assumed all-in cost by at least 5 percentage points. These ranges do not guarantee a probability lower bound.

Choose at most one NO per city/day: the highest model probability minus all-in cost, breaking ties by market ID. Require the historical NO price in [0.10, 0.95]. Evaluate the original 1¢, 2¢ and 3¢ scenarios, with the same assumed fee and $1,000 cash ledger, $10 stakes, $50 event and $200 gross caps. Candidate ranking uses only the archived forecast and decision-time observation. No fitted thresholds, city exclusions, or winner-based choices.

Report trade results, date-block descriptive intervals, and the coherent central model's Brier score versus market NO observations. Evaluate all bins and both cities, including non-trades. This small sample cannot calibrate rare-event probabilities. NOAA station sampling, grid/station bias, hourly-versus-intrahour highs, rounding, forecast dissemination delay, revised rules/end dates, and absent historical books remain material limitations.

Source: https://open-meteo.com/en/docs/single-runs-api — archived individual runs; initialization vs publication distinction. The stitched Historical Forecast API is unsuitable for replaying one decision.

```sh
python3 research/polymarket/forecast.py collect
python3 research/polymarket/forecast.py analyze
```
