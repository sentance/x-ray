"""Capture public books for a spread diagnostic, without submitting orders."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
from pathlib import Path
import json
from statistics import median
from urllib.parse import urlencode

from weather import GAMMA, array, cached, save


def walk_asks(book, shares, rate):
    cost = 0.0
    remaining = shares
    for level in sorted(book.get("asks", []), key=lambda x: float(x["price"])):
        price, size = float(level["price"]), float(level["size"])
        if not 0 < price < 1 or size <= 0:
            continue
        take = min(remaining, size)
        cost += take * (price + rate * price * (1 - price))
        remaining -= take
        if remaining < 1e-9:
            return cost
    return None


def capture(day, cache):
    root = cache / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    jobs = []
    for city in ("london", "nyc"):
        slug = f"highest-temperature-in-{city}-on-{day.strftime('%B').lower()}-{day.day}-{day.year}"
        result = cached(root / "events" / f"{slug}.json", GAMMA + "/events?" + urlencode({"slug": slug}))
        for event in result.get("response", []):
            if event.get("slug") != slug:
                continue
            for market in event.get("markets", []):
                if not market.get("acceptingOrders") or market.get("closed"):
                    continue
                ids = array(market.get("positionIds" if market.get("version") == "v2" else "clobTokenIds", []))
                names = array(market.get("outcomes", []))
                if len(ids) != 2 or set(names) != {"Yes", "No"}:
                    continue
                for token in ids:
                    jobs.append((market, names, ids, str(token)))
    results = {}
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(cached, root / "books" / f"{token}.json",
                               "https://clob.polymarket.com/book?" + urlencode({"token_id": token})): token
                   for _, _, _, token in jobs}
        for future in as_completed(futures):
            results[futures[future]] = future.result()
    markets = {m["id"]: (m, names, ids) for m, names, ids, _ in jobs}
    diagnostics = []
    for market, names, ids in markets.values():
        books = [results[str(token)] for token in ids]
        if any("error" in b for b in books):
            diagnostics.append({"slug": market["slug"], "error": "book fetch failed"})
            continue
        no_book = books[names.index("No")]["response"]
        asks = [float(x["price"]) for x in no_book.get("asks", []) if float(x["size"]) > 0]
        bids = [float(x["price"]) for x in no_book.get("bids", []) if float(x["size"]) > 0]
        ask, bid = min(asks, default=None), max(bids, default=None)
        fee = market.get("feeSchedule", {})
        rate = 0.0 if market.get("feesEnabled") is False else fee.get("rate") if fee.get("exponent") == 1 else None
        # Ten equal shares per leg; never estimate a full trade from a tiny top quote.
        size = 10
        costs = [walk_asks(b["response"], size, rate) for b in books] if rate is not None else [None, None]
        meets_min = all(float(b["response"].get("min_order_size", "inf")) <= size for b in books)
        pair_cost = sum(costs) if all(c is not None for c in costs) and meets_min else None
        received = [datetime.fromisoformat(b["received_at"]).timestamp() for b in books]
        durations = [datetime.fromisoformat(b["received_at"]).timestamp() - datetime.fromisoformat(b["started_at"]).timestamp() for b in books]
        diagnostics.append({"slug": market["slug"], "no_bid": bid, "no_ask": ask,
                            "half_spread": (ask - bid) / 2 if ask is not None and bid is not None else None,
                            "fee_rate_current_metadata": rate, "pair_shares": size,
                            "pair_cost_including_fees": pair_cost,
                            "pair_surplus_before_gas_and_leg_risk": size - pair_cost if pair_cost is not None else None,
                            "receipt_skew_seconds": max(received) - min(received),
                            "slowest_request_seconds": max(durations)})
    spreads = sorted(d["half_spread"] for d in diagnostics if d.get("half_spread") is not None)
    tail_spreads = sorted(d["half_spread"] for d in diagnostics
                          if d.get("half_spread") is not None and .90 <= (d["no_bid"] + d["no_ask"]) / 2 <= .98)
    pairs = [d for d in diagnostics if d.get("pair_cost_including_fees") is not None]
    summary = {"event_date": day.isoformat(), "markets": len(markets), "usable_pairs": len(pairs),
               "errors": sum("error" in d for d in diagnostics),
               "median_half_spread": median(spreads) if spreads else None,
               "tail_band_markets": len(tail_spreads),
               "tail_band_median_half_spread": median(tail_spreads) if tail_spreads else None,
               "positive_pair_snapshots": sum(d["pair_surplus_before_gas_and_leg_risk"] > 0 for d in pairs),
               "limitation": "Current spread diagnostic only. Different time from historical entries; REST books are not synchronized. No fills or profit verified.",
               "diagnostics": diagnostics}
    save(root / "summary.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "diagnostics"}, indent=2))
    print(f"Saved {root / 'summary.json'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", type=date.fromisoformat, required=True)
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data/live"))
    args = parser.parse_args()
    capture(args.date, args.cache)
