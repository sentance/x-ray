"""Read-only complete-basket quote screen across mutually exclusive weather bins."""
import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
from urllib.parse import urlencode

from books import walk_asks
from elimination import discover, fee_rate, read_books, rules
from weather import GAMMA, cached, save


def partition(event):
    if event.get("negRisk") is not True or event.get("negRiskAugmented"):
        raise ValueError("unsupported event settlement structure")
    members = []
    for market in event.get("markets", []):
        if market.get("negRiskOther") or market.get("negRisk") is not True:
            raise ValueError("unsupported other outcome or market structure")
        spec = rules(event, market)
        members.append((market, spec))
    if len(members) < 2:
        raise ValueError("too few bins")
    identity = {(s["station"], s["unit"], s["kind"], s["source"]) for _, s in members}
    if len(identity) != 1:
        raise ValueError("inconsistent source, station, unit or event family")
    ids = [str(m["id"]) for m, _ in members]
    tokens = [s["tokens"]["Yes"] for _, s in members]
    if len(set(ids)) != len(ids) or len(set(tokens)) != len(tokens):
        raise ValueError("duplicate market or token")
    members.sort(key=lambda pair: -float("inf") if pair[1]["lower"] is None else pair[1]["lower"])
    if members[0][1]["lower"] is not None or members[-1][1]["upper"] is not None:
        raise ValueError("missing exhaustive tails")
    for (_, left), (_, right) in zip(members, members[1:]):
        if left["upper"] is None or right["lower"] is None or left["upper"] + 1 != right["lower"]:
            raise ValueError("gap or overlap in partition")
    return members


def basket(event, books):
    try:
        members = partition(event)
    except (KeyError, ValueError, TypeError) as error:
        return {"status": "unsupported_partition", "reason": str(error)}
    costs = []
    for market, spec in members:
        if not market.get("acceptingOrders") or market.get("closed"):
            return {"status": "not_accepting_orders"}
        rate = fee_rate(market)
        if rate is None:
            return {"status": "unknown_fees"}
        token = spec["tokens"]["Yes"]
        book = books.get(token)
        if not book:
            return {"status": "missing_book"}
        if book.get("asset_id") != token or not market.get("conditionId") or book.get("market") != market["conditionId"]:
            return {"status": "book_identity_mismatch"}
        if float(book.get("min_order_size", "inf")) > 10:
            return {"status": "below_minimum_size"}
        cost = walk_asks(book, 10, rate)
        if cost is None:
            return {"status": "insufficient_depth"}
        costs.append({"market_id": str(market["id"]), "cost": cost})
    cost = sum(c["cost"] for c in costs)
    return {"status": "margin_met" if cost <= 9.9 + 1e-9 else "margin_too_small", "legs": len(costs),
            "basket_cost": cost, "conditional_payout": 10, "conditional_surplus": 10 - cost, "leg_costs": costs}


def scan(day, cache):
    root = cache / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    events = [e for e in discover(root, day) if e["slug"].startswith("highest-temperature-in-")]
    supported, excluded = [], []
    for event in events:
        try:
            members = partition(event)
            supported.append((event, members))
        except (KeyError, TypeError, ValueError) as error:
            excluded.append({"event": event["slug"], "reason": str(error)})
    print(f"Validated {len(supported)} complete events; fetching {sum(len(m) for _, m in supported)} YES books", flush=True)
    tokens = [s["tokens"]["Yes"] for _, members in supported for _, s in members]
    books, timings, errors = read_books(root, tokens, "books")
    rows = []
    for event, members in supported:
        row = {"event": event["slug"], **basket(event, books)}
        rows.append(row)
        if row["status"] != "margin_met":
            continue
        fresh = cached(root / "recheck-events" / f"{event['id']}.json", GAMMA + "/events?" + urlencode({"slug": event["slug"]}))
        try:
            current = next(e for e in fresh["response"] if e["slug"] == event["slug"])
            new_members = partition(current)
            before = {(str(m["id"]), s["tokens"]["Yes"], s["rules_hash"]) for m, s in members}
            after = {(str(m["id"]), s["tokens"]["Yes"], s["rules_hash"]) for m, s in new_members}
            if before != after:
                raise ValueError("basket membership or rules changed")
            rebooks, retimes, reerrors = read_books(root / "recheck" / str(event["id"]), [s["tokens"]["Yes"] for _, s in new_members], "books")
            errors.extend(reerrors)
            row["recheck"] = basket(current, rebooks)
            duration = max(datetime.fromisoformat(t["received_at"]).timestamp() for t in retimes) - min(datetime.fromisoformat(t["started_at"]).timestamp() for t in retimes) if retimes else None
            row["recheck"]["request_window_seconds"] = duration
            if duration is not None and duration > 2:
                row["recheck"]["status"] = "latency_limit_exceeded"
        except (KeyError, ValueError, TypeError, StopIteration) as error:
            row["recheck"] = {"status": "metadata_recheck_failed", "reason": str(error)}
    report = {"protocol": "research/polymarket/FULLSET_PROTOCOL.md", "date": day.isoformat(),
              "captured_at": datetime.now(timezone.utc).isoformat(), "events_discovered": len(events),
              "supported_events": len(supported), "excluded_events": excluded, "book_errors": errors,
              "status_counts": {status: sum(r["status"] == status for r in rows) for status in sorted({r["status"] for r in rows})},
              "first_quote_candidates": sum(r["status"] == "margin_met" for r in rows),
              "confirmed_quote_candidates": sum(r.get("recheck", {}).get("status") == "margin_met" for r in rows),
              "baskets": rows, "conclusion": "Conditional multi-leg quote screen only. No executed trades or realized profits; batch orders are not atomic."}
    save(root / "summary.json", report)
    save(Path("research/polymarket/fullset-results.json"), report)
    print(json.dumps({k: v for k, v in report.items() if k not in ("baskets", "excluded_events")}, indent=2))
    print(f"Saved {root / 'summary.json'}")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", type=date.fromisoformat, required=True)
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data/fullsets"))
    args = parser.parse_args()
    scan(args.date, args.cache)
