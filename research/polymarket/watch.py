"""Bounded public-data research watcher. No orders, schedules, or background service."""
import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
import time

from elimination import scan as elimination_scan
from fullsets import scan as fullset_scan
from weather import save


def campaign_summary(records):
    unique = {"elimination": set(), "fullsets": set()}
    for record in records:
        if record.get("error"):
            continue
        report, kind = record["report"], record["kind"]
        rows = report.get("candidates", []) if kind == "elimination" else report.get("baskets", [])
        for row in rows:
            if row.get("recheck", {}).get("status") == "margin_met":
                unique[kind].add(row["market_id"] if kind == "elimination" else row["event"])
    return {"completed_screens": sum("error" not in r for r in records),
            "failed_screens": sum("error" in r for r in records),
            "unique_confirmed_quote_candidates": {k: sorted(v) for k, v in unique.items()},
            "limitation": "Repeated quotes are not independent opportunities. No fill model, signed orders, or realized P&L."}


def watch(day, captures, interval, cache):
    if not 1 <= captures <= 120 or interval < 60:
        raise ValueError("captures must be 1..120 and interval at least 60 seconds")
    root = cache / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    records = []
    for index in range(captures):
        started = time.monotonic()
        for kind, scanner in (("elimination", elimination_scan), ("fullsets", fullset_scan)):
            try:
                report = scanner(day, root / kind)
                records.append({"capture": index + 1, "kind": kind, "report": report})
            except Exception as error:
                records.append({"capture": index + 1, "kind": kind, "error": str(error)})
            save(root / "campaign.json", {"date": day.isoformat(), "planned_captures": captures,
                                          "interval_seconds": interval, "records": records,
                                          "summary": campaign_summary(records)})
        print(json.dumps(campaign_summary(records), indent=2), flush=True)
        if index + 1 < captures:
            # Enforce a minimum gap even when a slow capture exceeded the requested interval.
            time.sleep(max(60, interval - (time.monotonic() - started)))
    return campaign_summary(records)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", type=date.fromisoformat, required=True)
    parser.add_argument("--captures", type=int, default=1)
    parser.add_argument("--interval-seconds", type=int, default=300)
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data/watch"))
    args = parser.parse_args()
    watch(args.date, args.captures, args.interval_seconds, args.cache)
