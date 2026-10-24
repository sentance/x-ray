"""Read-only, frozen-rule Polymarket weather research. No account or order APIs."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
import gzip
import heapq
from http.client import HTTPException
import json
from pathlib import Path
import random
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

GAMMA = "https://gamma-api.polymarket.com"
DATA = "https://data-api.polymarket.com"
UTC = timezone.utc


def epoch(value):
    if not value:
        return None
    return int(datetime.fromisoformat(value.replace(" ", "T").replace("Z", "+00:00")).timestamp())


def array(value):
    return json.loads(value) if isinstance(value, str) else value


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    tmp.replace(path)


def get(url):
    for attempt in range(3):
        try:
            request = Request(url, headers={"User-Agent": "xray-research/1.0", "Accept-Encoding": "gzip"})
            with urlopen(request, timeout=25) as response:
                raw = response.read()
                if response.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
                return json.loads(raw)
        except (HTTPError, URLError, TimeoutError, ConnectionError, HTTPException) as error:
            if isinstance(error, HTTPError) and error.code not in (429, 500, 502, 503, 504):
                raise
            if attempt == 2:
                raise
            time.sleep(0.5 * 2 ** attempt)


def cached(path, url):
    if path.exists():
        old = json.loads(path.read_text())
        if "error" not in old and old.get("url") == url:
            return old
    try:
        started = datetime.now(UTC).isoformat()
        response = get(url)
        result = {"url": url, "started_at": started, "received_at": datetime.now(UTC).isoformat(), "response": response}
    except Exception as error:
        result = {"url": url, "error": str(error)}
    save(path, result)
    return result


def universe():
    for day_number in range(1, 23):
        for city in ("london", "nyc"):
            yield f"highest-temperature-in-{city}-on-october-{day_number}-2026"


def no_token(market):
    names = array(market.get("outcomes", []))
    # Protocol V2 identifiers must come from positionIds, not legacy token IDs.
    ids = array(market.get("positionIds" if market.get("version") == "v2" else "clobTokenIds", []))
    if len(names) != 2 or len(ids) != 2 or "No" not in names or "Yes" not in names:
        raise ValueError("unsupported outcome identifiers")
    return ids[names.index("No")]


def collect(cache):
    slugs = list(universe())
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(cached, cache / "events" / f"{slug}.json",
                               GAMMA + "/events?" + urlencode({"slug": slug})): slug for slug in slugs}
        for count, future in enumerate(as_completed(futures), 1):
            result = future.result()
            print(f"events {count}/{len(slugs)} {futures[future]} {'ERROR' if 'error' in result else 'ok'}", flush=True)
    tasks = {}
    for slug in slugs:
        result = json.loads((cache / "events" / f"{slug}.json").read_text())
        for event in result.get("response", []):
            if event.get("slug") != slug:
                continue
            for market in event.get("markets", []):
                try:
                    decision = epoch(market["endDate"]) - 6 * 3600
                    token = no_token(market)
                    url = DATA + "/v2/prices-history?" + urlencode({"token_id": token, "as_of": decision})
                    tasks[market["id"]] = (cache / "prices" / f"{market['id']}.json", url)
                except (KeyError, ValueError, TypeError):
                    continue
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(cached, *task): mid for mid, task in tasks.items()}
        for count, future in enumerate(as_completed(futures), 1):
            result = future.result()
            if count % 25 == 0 or count == len(tasks) or "error" in result:
                print(f"prices {count}/{len(tasks)} {'ERROR ' + result['error'] if 'error' in result else 'ok'}", flush=True)


def price_before(response, decision):
    """Do not let a bucket's unobserved future portion leak into a decision."""
    points = response.get("data", [])
    if points is None:
        return None
    if isinstance(points, dict):
        points = [points]
    valid = []
    for point in points:
        ts = point["timestamp"]
        width = point["resolution_seconds"]
        price = point["price"]
        if 0 <= decision - ts <= 600 and width >= 0 and ts + width <= decision and 0 < price < 1:
            valid.append((ts, price))
    return max(valid)[1] if valid else None


def observation(market, response):
    decision = epoch(market["endDate"]) - 6 * 3600
    created = epoch(market.get("createdAt"))
    closed = epoch(market.get("closedTime"))
    if created is None or created > decision or (closed is not None and closed <= decision):
        return None, "not_open_at_decision"
    price = price_before(response, decision)
    if price is None:
        return None, "missing_or_stale_price"
    names = array(market.get("outcomes", []))
    payouts = array(market.get("outcomePrices", []))
    payout = None
    if market.get("closed") and market.get("umaResolutionStatus") == "resolved" and len(payouts) == 2:
        values = list(map(float, payouts))
        if all(0 <= p <= 1 for p in values) and abs(sum(values) - 1) < 1e-9 and values in ([0., 1.], [1., 0.], [.5, .5]):
            payout = values[names.index("No")]
    return {"id": str(market["id"]), "decision": decision, "closed": closed,
            "price": price, "payout": payout}, "observed"


def settled_summary(trades):
    settled = [t for t in trades if t["pnl"] is not None and t["closed"] is not None]
    pnl = sum(t["pnl"] for t in settled)
    stake = len(settled) * 10
    dates = sorted({datetime.fromtimestamp(t["decision"], UTC).date().isoformat() for t in settled})
    blocks = []
    for day in dates:
        selected = [t for t in settled if datetime.fromtimestamp(t["decision"], UTC).date().isoformat() == day]
        blocks.append((sum(t["pnl"] for t in selected), len(selected) * 10))
    interval = None
    if len(blocks) >= 2:
        rng = random.Random(20261024)
        boot = []
        for _ in range(5000):
            sample = rng.choices(blocks, k=len(blocks))
            boot.append(sum(b[0] for b in sample) / sum(b[1] for b in sample))
        boot.sort()
        interval = [boot[125], boot[4874]]
    return {"trades": len(trades), "settled": len(settled), "unsettled": len(trades) - len(settled),
            "wins": sum(t["pnl"] > 0 for t in settled), "stake_settled": stake, "pnl_settled": pnl,
            "roi_settled": pnl / stake if stake else None, "date_blocks": len(blocks),
            "descriptive_95pct_date_bootstrap": interval,
            "interval_warning": "Resampling observed dates cannot represent losses absent from this sample; this interval does not certify profitability.",
            "all_observed_trades_won": bool(settled) and all(t["pnl"] > 0 for t in settled)}


def portfolio(rows, slippage, entry_band=(.90, .98)):
    cash = 1000.0
    reserved = 0.0
    by_event = {}
    pending = []
    trades = []
    skipped = 0
    realized = 0.0
    peak = 0.0
    drawdown = 0.0
    max_reserved = 0.0

    def settle(until):
        nonlocal cash, reserved, realized, peak, drawdown
        while pending and pending[0][0] <= until:
            _, _, trade = heapq.heappop(pending)
            cash += trade["paid_out"]
            reserved -= 10
            by_event[trade["event"]] -= 10
            realized += trade["pnl"]
            peak = max(peak, realized)
            drawdown = max(drawdown, peak - realized)

    for row in sorted(rows, key=lambda r: (r["decision"], r["event"], r["id"])):
        if not entry_band[0] <= row["price"] <= entry_band[1]:
            continue
        settle(row["decision"])
        price = row["price"] + slippage
        if price >= 1 or cash < 10 - 1e-9 or reserved + 10 > 200 + 1e-9 or by_event.get(row["event"], 0) + 10 > 50 + 1e-9:
            skipped += 1
            continue
        all_in = price + 0.05 * price * (1 - price)
        paid_out = None if row["payout"] is None else 10 / all_in * row["payout"]
        trade = {**row, "stake": 10, "all_in_per_share": all_in, "paid_out": paid_out,
                 "pnl": None if paid_out is None else paid_out - 10}
        trades.append(trade)
        cash -= 10
        reserved += 10
        max_reserved = max(max_reserved, reserved)
        by_event[row["event"]] = by_event.get(row["event"], 0) + 10
        if paid_out is not None and row["closed"] is not None:
            heapq.heappush(pending, (row["closed"], row["id"], trade))
    settle(float("inf"))
    return {**settled_summary(trades), "max_reserved": max_reserved,
            "reserved_unsettled": reserved, "ending_cash": cash, "max_realized_drawdown": drawdown,
            "skipped_by_cost_or_capital": skipped, "trade_log": trades}


def analyze(cache, output):
    coverage = {"expected_events": 44, "found_events": 0, "event_errors": 0, "missing_events": [],
                "markets": 0, "price_errors": 0, "observed": 0, "missing_or_stale_price": 0,
                "not_open_at_decision": 0, "unsupported": 0, "unresolved_observations": 0}
    rows = []
    seen = set()
    for slug in universe():
        path = cache / "events" / f"{slug}.json"
        result = json.loads(path.read_text()) if path.exists() else {"error": "not collected"}
        if "error" in result:
            coverage["event_errors"] += 1
            continue
        events = [e for e in result.get("response", []) if e.get("slug") == slug]
        if not events:
            coverage["missing_events"].append(slug)
            continue
        coverage["found_events"] += 1
        for market in events[0].get("markets", []):
            if market["id"] in seen:
                continue
            seen.add(market["id"])
            coverage["markets"] += 1
            path = cache / "prices" / f"{market['id']}.json"
            result = json.loads(path.read_text()) if path.exists() else {"error": "not collected"}
            if "error" in result:
                coverage["price_errors"] += 1
                continue
            try:
                no_token(market)
                row, status = observation(market, result["response"])
            except (KeyError, ValueError, TypeError):
                row, status = None, "unsupported"
            coverage[status] += 1
            if row:
                row["event"] = slug
                row["day"] = date(2026, 10, int(slug.split("-october-")[1].split("-")[0])).isoformat()
                coverage["unresolved_observations"] += row["payout"] is None
                rows.append(row)
    scenarios = []
    for slip in (0.01, 0.02, 0.03):
        # One continuous capital ledger across the split; no artificial bankroll reset.
        combined = portfolio(rows, slip)
        periods = {}
        for label, predicate in [("discovery", lambda t: t["day"] <= "2026-10-11"),
                                 ("holdout", lambda t: t["day"] >= "2026-10-12")]:
            ts = [t for t in combined["trade_log"] if predicate(t)]
            periods[label] = settled_summary(ts)
        scenarios.append({"slippage_per_share": slip, "periods": periods, "portfolio": combined})
    report = {"protocol": "research/polymarket/PROTOCOL.md", "generated_at": datetime.now(UTC).isoformat(),
              "conclusion": "Exploratory screen only; no verified executable edge or live trading approval.",
              "coverage": coverage, "scenarios": scenarios}
    save(output, report)
    print(json.dumps({**report, "scenarios": [{**s, "portfolio": {k: v for k, v in s["portfolio"].items() if k != "trade_log"}} for s in scenarios]}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["collect", "analyze"])
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data"))
    parser.add_argument("--output", type=Path, default=Path("research/polymarket/results.json"))
    args = parser.parse_args()
    if args.command == "collect":
        collect(args.cache)
    else:
        analyze(args.cache, args.output)


if __name__ == "__main__":
    main()
