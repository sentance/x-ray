"""Exploratory model-value screen using genuinely archived forecast runs."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
from urllib.parse import urlencode

from weather import cached, epoch, observation, portfolio, save, settled_summary, universe

STATIONS = {"london": (51.5053, .0553, "Europe/London"),
            "nyc": (40.7769, -73.8740, "America/New_York")}


def event_rows(cache):
    for slug in universe():
        path = cache / "events" / f"{slug}.json"
        saved = json.loads(path.read_text()) if path.exists() else {}
        for event in saved.get("response", []):
            if event.get("slug") == slug:
                yield slug, event


def forecast_request(slug, event):
    city = "london" if "-london-" in slug else "nyc"
    lat, lon, tz = STATIONS[city]
    decisions = {epoch(m["endDate"]) - 6 * 3600 for m in event["markets"]}
    if len(decisions) != 1:
        raise ValueError("event bins have different decision times")
    decision = decisions.pop()
    run_ts = (decision - 12 * 3600) // (6 * 3600) * (6 * 3600)
    run = datetime.fromtimestamp(run_ts, timezone.utc).strftime("%Y-%m-%dT%H:%M")
    params = {"latitude": lat, "longitude": lon, "timezone": tz, "run": run,
              "models": "gfs_global", "hourly": "temperature_2m", "forecast_days": 3}
    return "https://single-runs-api.open-meteo.com/v1/forecast?" + urlencode(params)


def collect(cache):
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(cached, cache / "forecasts" / f"{slug}.json", forecast_request(slug, event)): slug
                   for slug, event in event_rows(cache)}
        for number, future in enumerate(as_completed(futures), 1):
            result = future.result()
            print(f"forecasts {number}/{len(futures)} {'ERROR ' + result['error'] if 'error' in result else 'ok'}", flush=True)


def bounds(title):
    """Whole-degree rounded resolution boundaries, expressed in Celsius."""
    match = re.fullmatch(r"(-?\d+)(?:-(-?\d+))?°([CF])(?: or (below|higher))?", title)
    if not match:
        raise ValueError(f"unknown bin {title}")
    first, last, unit, tail = match.groups()
    lower, upper = float(first) - .5, float(last or first) + .5
    if tail == "below":
        lower = -math.inf
    if tail == "higher":
        upper = math.inf
    if unit == "F":
        lower, upper = (lower - 32) / 1.8, (upper - 32) / 1.8
    return lower, upper


def no_probability(title, mean, sigma):
    lower, upper = bounds(title)
    cdf = lambda t: .5 * (1 + math.erf((t - mean) / (sigma * math.sqrt(2))))
    return 1 - (cdf(upper) - cdf(lower))


def daily_max(response, day):
    hourly = response["hourly"]
    pairs = [(ts, temp) for ts, temp in zip(hourly["time"], hourly["temperature_2m"])
             if ts.startswith(day) and temp is not None]
    if len(pairs) != 24 or len({ts for ts, _ in pairs}) != 24:
        raise ValueError("target local day does not have 24 forecast hours")
    return max(temp for _, temp in pairs)


def analyze(cache, output):
    rows = []
    coverage = {"events": 0, "forecast_errors": [], "observations": 0, "price_or_metadata_errors": 0}
    for slug, event in event_rows(cache):
        coverage["events"] += 1
        day = f"2026-10-{int(slug.split('-october-')[1].split('-')[0]):02d}"
        try:
            result = json.loads((cache / "forecasts" / f"{slug}.json").read_text())
            if result.get("url") != forecast_request(slug, event):
                raise ValueError("forecast cache URL mismatch")
            if "error" in result or result["response"].get("error"):
                raise ValueError(result.get("error") or result["response"].get("reason"))
            mean = daily_max(result["response"], day)
        except (OSError, KeyError, ValueError) as error:
            coverage["forecast_errors"].append({"event": slug, "error": str(error)})
            continue
        for market in event["markets"]:
            try:
                result = json.loads((cache / "prices" / f"{market['id']}.json").read_text())
                row, _ = observation(market, result["response"])
                if row is None:
                    raise ValueError("unavailable decision price")
                title = market["groupItemTitle"]
                central = no_probability(title, mean, 2)
                conservative = min(no_probability(title, mean + bias, sigma)
                                   for bias in (-1, 0, 1) for sigma in (1.5, 2, 2.5))
                rows.append({**row, "day": day, "event": slug, "forecast_high_c": mean,
                             "model_no": central, "stress_model_no": conservative})
                coverage["observations"] += 1
            except (OSError, KeyError, ValueError, TypeError):
                coverage["price_or_metadata_errors"] += 1
    calibration = {}
    for label, subset in [("all", rows), ("london", [r for r in rows if "-london-" in r["event"]]),
                          ("nyc", [r for r in rows if "-nyc-" in r["event"]])]:
        resolved = [r for r in subset if r["payout"] is not None]
        calibration[label] = {"bins": len(resolved),
                              "model_brier": sum((r["model_no"] - r["payout"]) ** 2 for r in resolved) / len(resolved) if resolved else None,
                              "market_brier": sum((r["price"] - r["payout"]) ** 2 for r in resolved) / len(resolved) if resolved else None}
    scenarios = []
    for slippage in (.01, .02, .03):
        candidates = {}
        for row in rows:
            price = row["price"] + slippage
            cost = price + .05 * price * (1 - price)
            edge = row["stress_model_no"] - cost
            if not .1 <= row["price"] <= .95 or price >= 1 or edge < .05:
                continue
            old = candidates.get(row["event"])
            if old is None or (-edge, row["id"]) < (-(old["stress_model_no"] - old["assumed_cost"]), old["id"]):
                candidates[row["event"]] = {**row, "assumed_cost": cost}
        # The common ledger's original entry-band filter is overridden explicitly here.
        result = portfolio(list(candidates.values()), slippage, entry_band=(.1, .95))
        periods = {}
        for label, predicate in [("earlier_already_seen", lambda t: t["day"] <= "2026-10-11"),
                                 ("later_already_seen", lambda t: t["day"] >= "2026-10-12")]:
            periods[label] = settled_summary([t for t in result["trade_log"] if predicate(t)])
        scenarios.append({"slippage_per_share": slippage, "periods": periods, "portfolio": result})
    report = {"protocol": "research/polymarket/FORECAST_PROTOCOL.md", "generated_at": datetime.now(timezone.utc).isoformat(),
              "conclusion": "Exploratory uncalibrated model; outcomes already inspected. No unseen holdout or executable edge established.",
              "coverage": coverage, "calibration": calibration, "scenarios": scenarios}
    save(output, report)
    print(json.dumps({**report, "scenarios": [{**s, "portfolio": {k: v for k, v in s["portfolio"].items() if k != "trade_log"}} for s in scenarios]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["collect", "analyze"])
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data"))
    parser.add_argument("--output", type=Path, default=Path("research/polymarket/forecast-results.json"))
    args = parser.parse_args()
    collect(args.cache) if args.command == "collect" else analyze(args.cache, args.output)
