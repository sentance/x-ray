"""Cash-based wallet audit: separate capital flows, incentives and marked holdings."""
from collections import Counter, defaultdict
from decimal import Decimal
from statistics import median

INCOME = {"REWARD", "MAKER_REBATE", "TAKER_REBATE", "YIELD", "REFERRAL_REWARD"}
OPERATIONS = {"CONVERSION", "MERGE", "REDEEM"}


def decimal(value):
    return Decimal(str(value or 0))


def cash_delta(row):
    kind = row["type"]
    value = decimal(row.get("usdc_size"))
    if value < 0:
        raise ValueError("negative cash field requires explicit handling")
    if kind == "TRADE":
        if row.get("side") not in ("BUY", "SELL"):
            raise ValueError("unknown trade side")
        return -value if row["side"] == "BUY" else value
    if kind in {"DEPOSIT", *INCOME, *OPERATIONS}:
        return value
    if kind in {"WITHDRAWAL", "SPLIT"}:
        return -value
    raise ValueError("unsupported cash activity type: " + kind)


def position_value(groups):
    by_token = {}
    for group in groups:
        if not group.get("complete"):
            raise ValueError("incomplete position walk")
        for position in group["data"]:
            token = position["token_id"]
            if token in by_token and decimal(by_token[token]["current_value"]) != decimal(position["current_value"]):
                raise ValueError("conflicting position snapshots")
            by_token[token] = position
    return sum((decimal(p["current_value"]) for p in by_token.values()), Decimal(0)), len(by_token)


def summarize(activity, groups, taker_feed, chain_cash, reported_pnl):
    if not activity.get("complete") or not taker_feed.get("complete"):
        raise ValueError("full activity and taker pagination required")
    rows = activity["data"]
    cash, totals = Decimal(0), defaultdict(Decimal)
    counts = Counter()
    cash_min, cash_peak = None, Decimal(0)
    trades, last_no, conversion_lags = [], {}, []
    conversions = Counter()
    for row in rows:
        value = cash_delta(row)
        cash += value
        counts[row["type"]] += 1
        key = row["type"] + ("/" + row["side"] if row["type"] == "TRADE" else "")
        totals[key] += value
        cash_min = cash if cash_min is None else min(cash_min, cash)
        cash_peak = max(cash_peak, cash)
        if row["type"] == "TRADE":
            trades.append(row)
            if row["side"] == "BUY" and row.get("outcome") == "No":
                last_no[row["event_slug"]] = row["timestamp"]
        if row["type"] == "CONVERSION":
            if row["event_slug"] in last_no:
                conversion_lags.append(row["timestamp"] - last_no[row["event_slug"]])
            size = decimal(row.get("size"))
            if size > 0:
                implied = decimal(row.get("usdc_size")) / size + 1
                if abs(implied - implied.to_integral_value()) < Decimal("0.00001"):
                    conversions[str(int(implied.to_integral_value()))] += 1
                else:
                    conversions["non_integer_or_fee_adjusted"] += 1
    marked, positions = position_value(groups)
    funding = totals["DEPOSIT"] + totals["WITHDRAWAL"]
    income = sum((totals[t] for t in INCOME), Decimal(0))
    balance_error = cash - decimal(chain_cash)
    if abs(balance_error) > Decimal("0.01"):
        raise ValueError("cash ledger does not reconcile to on-chain collateral")
    equity = cash + marked
    cash_surplus = cash - funding
    profit = equity - funding
    taker_keys = {(t["transaction_hash"], t["token_id"], t["side"]) for t in taker_feed["data"]}
    roles = defaultdict(lambda: {"trades": 0, "cash_turnover": Decimal(0), "buys": 0, "sells": 0})
    for row in trades:
        role = "taker" if (row["transaction_hash"], row["token_id"], row["side"]) in taker_keys else "not_in_complete_taker_feed"
        roles[role]["trades"] += 1
        roles[role]["cash_turnover"] += decimal(row["usdc_size"])
        roles[role]["buys" if row["side"] == "BUY" else "sells"] += 1
    buys = [t for t in trades if t["side"] == "BUY"]
    total_buy = sum((decimal(t["usdc_size"]) for t in buys), Decimal(0))
    no_buy = sum((decimal(t["usdc_size"]) for t in buys if t.get("outcome") == "No"), Decimal(0))
    return {"cash_ledger": {k: float(v) for k, v in totals.items()}, "activity_counts": dict(counts),
            "cash": float(cash), "onchain_cash": float(decimal(chain_cash)), "cash_reconciliation_error": float(balance_error),
            "net_funding": float(funding), "marked_position_value": float(marked), "unique_position_tokens": positions,
            "marked_equity": float(equity), "cash_surplus_after_net_funding": float(cash_surplus),
            "marked_profit_after_net_funding": float(profit), "incentives": float(income),
            "marked_profit_excluding_incentives": float(profit - income),
            "cash_result_excluding_incentives": float(cash_surplus - income),
            "reported_economic_pnl": float(decimal(reported_pnl)),
            "reported_pnl_minus_reconciled_marked_profit": float(decimal(reported_pnl) - profit),
            "min_running_cash": float(cash_min or 0), "max_running_cash": float(cash_peak),
            "no_fraction_of_buy_cash": float(no_buy / total_buy) if total_buy else None,
            "roles": {k: {key: float(v) if isinstance(v, Decimal) else v for key, v in value.items()} for k, value in roles.items()},
            "conversion_input_count_implied_by_cash": dict(conversions),
            "median_latest_no_fill_to_conversion_seconds": median(conversion_lags) if conversion_lags else None,
            "conversions_with_latest_no_fill_within_60_seconds": sum(t <= 60 for t in conversion_lags),
            "limitation": "Position marks are not liquidation proceeds. Classification of non-taker rows is inferred from complete API coverage. Public fills do not reveal resting orders, reward score or hedge policy. No extra fee subtraction: observed cash deltas already reconcile to collateral."}


def assemble(cache):
    import json
    load = lambda name: json.loads((cache / name).read_text())
    chain = load("chain-balance.json")
    activity = load("activity-all.json")
    report = summarize(activity, [load("positions-" + status + "-all.json") for status in ("open", "redeemable", "mergeable")],
                       load("taker-trades-all.json"), chain["balance"], load("pnl.json")["response"]["data"]["points"][-1]["economic_pnl"])
    report["wallet"] = load("manifest.json")["wallet"]
    report["source_manifest"] = str(cache / "manifest.json")
    report["chain_balance"] = chain
    report["chain_samples"] = load("chain-proof-summary.json")
    conditions = {t.get("condition_id") for t in activity["data"]}
    report["current_top_25_reward_markets_related_to_wallet"] = [
        {"question": m["question"], "condition_id": m["condition_id"], "market_id": m["market_id"],
         "minimum_reward_shares": m["rewards_min_size"], "maximum_reward_spread_cents": m["rewards_max_spread"],
         "reward_pools": m["rewards_config"],
         "midpoint_pair_notional_for_minimum_size": m["rewards_min_size"] * sum(t["price"] for t in m["tokens"]),
         "note": "Pool allocation, not wallet income; midpoint notional is not a fill or a reserved-capital estimate."}
        for m in load("reward-markets.json")["data"] if m["condition_id"] in conditions]
    report["incentive_sensitivity_not_forecast"] = [
        {"fraction_of_observed_incentives": f,
         "marked_profit_if_same_trades_and_marks": report["marked_profit_excluding_incentives"] + f * report["incentives"]}
        for f in (0, .25, .5, 1)]
    report["incentives_break_even_fraction_if_same_trades_and_marks"] = -report["marked_profit_excluding_incentives"] / report["incentives"] if report["incentives"] else None
    return report


if __name__ == "__main__":
    import argparse
    from pathlib import Path
    from weather import save
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("research/polymarket/akroll-audit.json"))
    args = parser.parse_args()
    report = assemble(args.cache)
    save(args.output, report)
    print(f"Cash: {report['cash']:.6f}; marked profit: {report['marked_profit_after_net_funding']:.6f}; incentives: {report['incentives']:.6f}")
