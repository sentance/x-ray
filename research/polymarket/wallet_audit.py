"""Public wallet evidence collector; no keys, signatures, orders, or copy trading."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.parse import urlencode

from weather import DATA, GAMMA, cached, save


def paged(root, name, path, params, max_pages=40):
    rows, cursor, complete, error = [], None, False, None
    for page in range(max_pages):
        query = dict(params)
        if cursor:
            query["cursor"] = cursor
        result = cached(root / name / f"{page}.json", DATA + path + "?" + urlencode(query))
        if "error" in result:
            error = result["error"]
            break
        payload = result["response"]
        rows.extend(payload["data"])
        cursor = payload.get("pagination", {}).get("next_cursor")
        print(f"{name} page {page + 1}: {len(rows)} rows", flush=True)
        if not cursor:
            complete = True
            break
    save(root / f"{name}-all.json", {"data": rows, "complete": complete, "error": error,
                                    "next_cursor": cursor})
    return {"rows": len(rows), "complete": complete, "error": error}


def collect(wallet, root):
    root.mkdir(parents=True, exist_ok=True)
    manifest_path = root / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest["wallet"] != wallet:
            raise ValueError("cache belongs to a different wallet")
    else:
        manifest = {"wallet": wallet, "captured_at": datetime.now(timezone.utc).isoformat(),
                    "activity_end": int(datetime.now(timezone.utc).timestamp())}
        save(manifest_path, manifest)
    resources = {"pnl": DATA + "/v2/user-pnl?" + urlencode({"user": wallet, "interval": "all", "fidelity": "1d"}),
                 "profile": GAMMA + "/public-profile?" + urlencode({"address": wallet})}
    for name in ("overview", "strategy", "pnl", "skill", "decay"):
        resources["xray-" + name] = "https://x-ray.aragorn.fyi/api/wallets/" + wallet + "/" + name
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(cached, root / f"{name}.json", url): name for name, url in resources.items()}
        for future in as_completed(futures):
            result = future.result()
            print(f"{futures[future]}: {'ERROR ' + result['error'] if 'error' in result else 'ok'}", flush=True)
    tasks = [("activity", "/v2/activity", {"user": wallet, "limit": 1000, "start": 1,
              "end": manifest["activity_end"], "sort_direction": "ASC", "exclude_deposits_withdrawals": "false"})]
    for status in ("OPEN", "REDEEMABLE", "MERGEABLE"):
        tasks.append(("positions-" + status.lower(), "/v2/positions", {"user": wallet, "status": status,
                      "limit": 200, "filter_amount": 0, "include_archived": "true", "sort_by": "CURRENT_VALUE"}))
    tasks.append(("taker-trades", "/v2/trades", {"user": wallet, "taker_only": "true", "limit": 250,
                  "start": 1, "end": manifest["activity_end"]}))
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(paged, root, *task): task[0] for task in tasks}
        for future in as_completed(futures):
            name = futures[future]
            manifest[name] = future.result()
    save(manifest_path, manifest)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wallet")
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    if len(args.wallet) != 42 or not args.wallet.startswith("0x"):
        parser.error("expected a wallet address")
    int(args.wallet[2:], 16)
    collect(args.wallet.lower(), args.cache)
