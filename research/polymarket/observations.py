"""Retrospective book diagnostic for bins below an already observed station high.

Uses only official observation receipts preceding the captured book request.
METAR is a proxy for the market's resolution table, so this is not riskless arbitrage.
"""
from datetime import datetime
import json
from pathlib import Path
from zoneinfo import ZoneInfo

from books import walk_asks
from forecast import bounds
from weather import array, cached, save


def available_high(observations, station, day, zone, cutoff):
    valid = []
    for obs in observations:
        observed = obs["obsTime"]
        received = datetime.fromisoformat(obs["receiptTime"].replace("Z", "+00:00")).timestamp()
        local_day = datetime.fromtimestamp(observed, ZoneInfo(zone)).date().isoformat()
        if (obs["icaoId"] == station and obs.get("temp") is not None and obs.get("metarType") == "METAR"
                and local_day == day and observed <= cutoff and received <= cutoff):
            valid.append(float(obs["temp"]))
    return max(valid) if valid else None


def main():
    roots = sorted(Path("research/polymarket/data/live").glob("*/summary.json"))
    if not roots:
        raise ValueError("Capture books first with books.py")
    root = roots[-1].parent
    snapshot = json.loads(roots[-1].read_text())
    raw = cached(root / "metar.json", "https://aviationweather.gov/api/data/metar?ids=EGLC%2CKLGA&format=json&hours=24")
    if "error" in raw:
        raise ValueError(raw["error"])
    rows = []
    for path in (root / "events").glob("*.json"):
        events = json.loads(path.read_text()).get("response", [])
        for event in events:
            london = "-london-" in event["slug"]
            station, zone = ("EGLC", "Europe/London") if london else ("KLGA", "America/New_York")
            for market in event["markets"]:
                names, ids = array(market["outcomes"]), array(market.get("positionIds" if market.get("version") == "v2" else "clobTokenIds", []))
                if "No" not in names or len(ids) != 2:
                    continue
                book_path = root / "books" / f"{ids[names.index('No')]}.json"
                if not book_path.exists():
                    continue
                book = json.loads(book_path.read_text())
                if "error" in book:
                    continue
                cutoff = datetime.fromisoformat(book["started_at"]).timestamp()
                high = available_high(raw["response"], station, snapshot["event_date"], zone, cutoff)
                if high is None:
                    continue
                _, upper = bounds(market["groupItemTitle"])
                # Half a Celsius degree guard accounts conservatively for METAR rounding.
                if upper >= high - .5:
                    continue
                schedule = market.get("feeSchedule", {})
                rate = 0 if market.get("feesEnabled") is False else schedule.get("rate") if schedule.get("exponent") == 1 else None
                cost = walk_asks(book["response"], 10, rate) if rate is not None else None
                rows.append({"slug": market["slug"], "station_high_c": high, "bin_upper_c": upper,
                             "no_cost_10_shares_including_fees": cost,
                             "surplus_assuming_no_wins": 10 - cost if cost is not None else None,
                             "at_least_one_cent_per_share_margin": cost is not None and cost <= 9.9})
    report = {"book_capture": str(root), "diagnostic_only": True, "eliminated_bin_proxies": len(rows),
              "at_least_one_cent_per_share_margin": sum(r["at_least_one_cent_per_share_margin"] for r in rows),
              "rows": rows,
              "limitation": "Retrospective availability check, not paper fills. METAR observations are a proxy for NOAA's specified table; source corrections, time conventions, and quote latency remain unresolved. One snapshot cannot estimate opportunity frequency."}
    save(Path("research/polymarket/observed-results.json"), report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
