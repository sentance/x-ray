"""Public leaderboard snapshot for generating hypotheses, never a copy-trading signal."""
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
from statistics import median
from urllib.parse import urlencode

from weather import DATA, cached, save


def main():
    root = Path("research/polymarket/data/wallets") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    board = cached(root / "leaderboard.json", DATA + "/v2/leaderboard?" + urlencode(
        {"time_period": "month", "category": "weather", "sort_by": "PNL", "limit": 10}))
    if "error" in board:
        raise RuntimeError(board["error"])
    wallets = board["response"]["data"]
    samples = {}
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(cached, root / f"{w['user_id']}.json", DATA + "/v2/activity?" + urlencode(
            {"user": w["user_id"], "limit": 500})): w for w in wallets}
        for future in as_completed(futures):
            wallet = futures[future]
            samples[wallet["user_id"]] = future.result()
    rows = []
    for wallet in wallets:
        sample = samples[wallet["user_id"]]
        if "error" in sample:
            rows.append({"wallet": wallet["user_id"], "error": sample["error"]})
            continue
        activities = sample["response"]["data"]
        trades = [a for a in activities if a["type"] == "TRADE"]
        weather = [a for a in trades if a.get("event_slug", "").startswith("highest-temperature-in-")]
        buys = [t for t in weather if t.get("side") == "BUY"]
        sells = [t for t in weather if t.get("side") == "SELL"]
        by_condition = defaultdict(set)
        for t in buys:
            by_condition[t["condition_id"]].add(t["outcome"])
        buy_cash = sum(t["usdc_size"] for t in buys)
        sell_cash = sum(t["usdc_size"] for t in sells)
        types = Counter(a["type"] for a in activities)
        ts = [a["timestamp"] for a in activities]
        cities = Counter(t["event_slug"].split("-on-")[0].removeprefix("highest-temperature-in-") for t in weather)
        rows.append({"name": wallet["user_name"], "wallet": wallet["user_id"], "rank": wallet["rank"],
                     "weather_board_month_pnl": wallet["pnl"], "weather_board_month_volume": wallet["volume"],
                     "sample_rows": len(activities), "sample_has_more": sample["response"].get("pagination", {}).get("has_more"),
                     "sample_span_hours": (max(ts) - min(ts)) / 3600 if ts else 0,
                     "temperature_trades": len(weather), "temperature_buys": len(buys), "temperature_sells": len(sells),
                     "temperature_buy_usd": buy_cash, "temperature_sell_usd": sell_cash,
                     "median_temperature_buy_ticket_usd": median(t["usdc_size"] for t in buys) if buys else None,
                     "median_temperature_buy_price": median(t["price"] for t in buys) if buys else None,
                     "yes_fraction_of_temperature_buys": sum(t["outcome"] == "Yes" for t in buys) / len(buys) if buys else None,
                     "no_90plus_fraction_of_temperature_buys": sum(t["outcome"] == "No" and t["price"] >= .9 for t in buys) / len(buys) if buys else None,
                     "conditions_with_both_outcomes_bought": sum(len(s) == 2 for s in by_condition.values()),
                     "cities": dict(cities), "all_sample_activity_types": dict(types)})
    report = {"captured_at": datetime.now(timezone.utc).isoformat(), "rows": rows,
              "limitation": "Selected current winners, truncated mixed-category activity samples, and leaderboard accounting. Not evidence of reproducibility, maker status, capital ROI, or future performance. Public fills are not executable copy prices."}
    save(root / "summary.json", report)
    save(Path("research/polymarket/wallet-results.json"), report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
