"""Read-only observed-temperature screen using exact routine-METAR contract rules."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
from decimal import Decimal, ROUND_FLOOR
import gzip
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from books import walk_asks
from weather import GAMMA, array, cached, epoch, save

UTC = timezone.utc
# Exact station identifiers, independent of mutable market titles.
ZONES = {
    "MPMG": "America/Panama", "MMMX": "America/Mexico_City", "CYYZ": "America/Toronto",
    "SAEZ": "America/Argentina/Buenos_Aires", "SBGR": "America/Sao_Paulo",
    "KSFO": "America/Los_Angeles", "KLAX": "America/Los_Angeles", "KSEA": "America/Los_Angeles",
    "KHOU": "America/Chicago", "KDEN": "America/Denver", "KORD": "America/Chicago",
    "KMIA": "America/New_York", "KDAL": "America/Chicago", "KAUS": "America/Chicago",
    "KATL": "America/New_York", "KLGA": "America/New_York", "RPLL": "Asia/Manila",
    "OPKC": "Asia/Karachi", "ZSQD": "Asia/Shanghai", "ZGGG": "Asia/Shanghai",
    "FACT": "Africa/Johannesburg", "OEJN": "Asia/Riyadh", "WMKK": "Asia/Kuala_Lumpur",
    "EFHK": "Europe/Helsinki", "EHAM": "Europe/Amsterdam", "RKPK": "Asia/Seoul",
    "LTFM": "Europe/Istanbul", "UUWW": "Europe/Moscow", "ZGSZ": "Asia/Shanghai",
    "ZUUU": "Asia/Shanghai", "ZHHH": "Asia/Shanghai", "ZBAA": "Asia/Shanghai",
    "ZUCK": "Asia/Shanghai", "RCSS": "Asia/Taipei", "EPWA": "Europe/Warsaw",
    "LEMD": "Europe/Madrid", "LIMC": "Europe/Rome", "WSSS": "Asia/Singapore",
    "ZSPD": "Asia/Shanghai", "RJTT": "Asia/Tokyo", "LLBG": "Asia/Jerusalem",
    "EDDM": "Europe/Berlin", "VILK": "Asia/Kolkata", "NZWN": "Pacific/Auckland",
    "LTAC": "Europe/Istanbul", "RKSI": "Asia/Seoul", "LFPB": "Europe/Paris", "EGLC": "Europe/London",
}


def raw_temperature(raw):
    raw = raw.strip()
    if not raw or raw.startswith("SPECI "):
        return None
    # Only parse the remarks section for the precision group.
    body, _, remarks = raw.partition(" RMK ")
    match = re.search(r"(?:^|\s)T([01])(\d{3})[01]\d{3}(?=\s|$)", remarks)
    if match:
        value = Decimal(match[2]) / 10
        return -value if match[1] == "1" else value
    match = re.search(r"(?:^|\s)(M?\d{2})/(?:M?\d{2}|//)?(?=\s|$)", body)
    if match:
        return Decimal(match[1].replace("M", "-"))
    return None


def rounded_temperature(celsius, unit):
    value = celsius if unit == "C" else celsius * Decimal(9) / 5 + 32
    return int((value + Decimal("0.5")).to_integral_value(rounding=ROUND_FLOOR))


def bin_range(title):
    match = re.fullmatch(r"(-?\d+)(?:-(-?\d+))?°([CF])(?: or (below|higher))?", title)
    if not match:
        raise ValueError("unsupported bin title")
    first, last, unit, tail = match.groups()
    if int(last or first) < int(first):
        raise ValueError("reversed bin boundaries")
    return (None if tail == "below" else int(first),
            None if tail == "higher" else int(last or first), unit)


def rules(event, market):
    source = market.get("resolutionSource") or event.get("resolutionSource") or ""
    parsed = urlparse(source)
    if parsed.hostname != "aviationweather.gov" or parsed.path != "/api/data/metar":
        raise ValueError("unsupported resolution source")
    description = market.get("description") or event.get("description", "")
    required = ("exactly eight digits", "Every other report is routine", "station's local time",
                "rounded up to the next higher whole degree", "correction (COR)")
    if not all(text in description for text in required):
        raise ValueError("unrecognized resolution rules")
    if "highest-temperature-in-" in event["slug"]:
        kind = "highest"
    elif "lowest-temperature-in-" in event["slug"]:
        kind = "lowest"
    else:
        raise ValueError("unsupported event family")
    station = parse_qs(parsed.query).get("ids", [""])[0]
    if station not in ZONES:
        raise ValueError("unsupported station timezone")
    ids = array(market.get("positionIds" if market.get("version") == "v2" else "clobTokenIds", []))
    names = array(market.get("outcomes", []))
    if len(ids) != 2 or set(names) != {"Yes", "No"}:
        raise ValueError("unsupported binary token identifiers")
    lower, upper, unit = bin_range(market["groupItemTitle"])
    if f"qualifying value is the {kind} temperature" not in description:
        raise ValueError("rule text disagrees with high/low event family")
    if f"states its range in whole degrees {'Celsius' if unit == 'C' else 'Fahrenheit'}" not in description:
        raise ValueError("rule text disagrees with bin units")
    identity = {"source": source, "description": description, "unit": unit,
                "station": station, "kind": kind, "lower": lower, "upper": upper}
    return {**identity, "zone": ZONES[station], "tokens": {name: str(token) for name, token in zip(names, ids)},
            "rules_hash": hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()}


def extrema(observations, station, day, zone, received_at, unit):
    cutoff = epoch(received_at)
    selected = {}
    for obs in observations:
        if obs.get("icaoId") != station:
            continue
        ts, receipt = obs.get("obsTime"), epoch(obs.get("receiptTime"))
        if ts is None or receipt is None or ts > cutoff or receipt > cutoff:
            continue
        if datetime.fromtimestamp(ts, ZoneInfo(zone)).date() != day:
            continue
        # Corrections replace the same observation, including a lower corrected high.
        if ts not in selected or receipt > selected[ts][0]:
            selected[ts] = (receipt, obs)
    values = []
    for _, obs in selected.values():
        value = raw_temperature(obs.get("rawOb", ""))
        if value is not None:
            values.append(rounded_temperature(value, unit))
    return {"reports": len(values), "high": max(values) if values else None,
            "low": min(values) if values else None}


def eliminated(spec, observed):
    if not observed["reports"]:
        return False
    # At least one additional whole degree beyond merely clearing the bin.
    if spec["kind"] == "highest":
        return spec["upper"] is not None and observed["high"] - spec["upper"] >= 2
    return spec["lower"] is not None and spec["lower"] - observed["low"] >= 2


def fee_rate(market):
    if market.get("feesEnabled") is False:
        return 0.0
    schedule = market.get("feeSchedule") or {}
    rate = schedule.get("rate")
    if schedule.get("exponent") != 1 or not isinstance(rate, (int, float)) or not 0 <= rate <= 1:
        return None
    return float(rate)


def quote(market, spec, books):
    if not market.get("acceptingOrders") or market.get("closed"):
        return {"status": "not_accepting_orders"}
    rate = fee_rate(market)
    if rate is None:
        return {"status": "unknown_fees"}
    no = books.get(spec["tokens"]["No"])
    yes = books.get(spec["tokens"]["Yes"])
    if not no or not yes:
        return {"status": "missing_book"}
    if not market.get("conditionId") or any(b.get("market") != market["conditionId"] for b in (no, yes)):
        return {"status": "book_market_mismatch"}
    if no.get("asset_id") != spec["tokens"]["No"] or yes.get("asset_id") != spec["tokens"]["Yes"]:
        return {"status": "book_asset_mismatch"}
    if float(no.get("min_order_size", "inf")) > 10:
        return {"status": "below_minimum_size"}
    cost = walk_asks(no, 10, rate)
    if cost is None:
        return {"status": "insufficient_depth"}
    return {"status": "margin_met" if cost <= 9.9 + 1e-9 else "margin_too_small", "cost": cost,
            "conditional_surplus": 10 - cost, "fee_rate": rate,
            "no_best_ask": min((float(x["price"]) for x in no.get("asks", []) if float(x["size"]) > 0), default=None)}


def read_books(root, tokens, phase):
    tokens = sorted(set(tokens))
    combined, timings, errors = {}, [], []
    def batch_read(index):
        batch = tokens[index:index + 20]
        started = datetime.now(UTC).isoformat()
        try:
            request = Request("https://clob.polymarket.com/books", data=json.dumps([{"token_id": t} for t in batch]).encode(),
                              headers={"Content-Type": "application/json", "Accept-Encoding": "gzip", "User-Agent": "xray-research/1.0"})
            with urlopen(request, timeout=25) as response:
                raw = response.read()
                if response.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
                payload = json.loads(raw)
            received = datetime.now(UTC).isoformat()
            if not isinstance(payload, list):
                raise ValueError("unexpected books envelope")
            record = {"started_at": started, "received_at": received, "response": payload}
        except Exception as error:
            record = {"started_at": started, "error": str(error)}
        save(root / phase / f"{index // 20}.json", record)
        return batch, record
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(batch_read, i) for i in range(0, len(tokens), 20)]
        for future in as_completed(futures):
            batch, record = future.result()
            if "error" in record:
                errors.append(record)
                continue
            for book in record["response"]:
                if str(book.get("asset_id")) in batch:
                    combined[str(book["asset_id"])] = book
                    timings.append({"token": str(book["asset_id"]), "started_at": record["started_at"], "received_at": record["received_at"]})
    return combined, timings, errors


def discover(root, day):
    start = day.isoformat() + "T00:00:00Z"
    from datetime import timedelta
    end = (day + timedelta(days=1)).isoformat() + "T00:00:00Z"
    events = {}
    for offset in range(0, 2000, 100):
        params = {"tag_id": 84, "closed": "false", "end_date_min": start, "end_date_max": end,
                  "limit": 100, "offset": offset, "order": "id", "ascending": "false"}
        page = cached(root / "events" / f"{offset}.json", GAMMA + "/events?" + urlencode(params))
        if "error" in page:
            raise ValueError("event discovery failed: " + page["error"])
        for event in page["response"]:
            if event["slug"].startswith(("highest-temperature-in-", "lowest-temperature-in-")):
                events[event["id"]] = event
        if len(page["response"]) < 100:
            return list(events.values())
    raise ValueError("discovery pagination cap reached; refusing incomplete universe")


def scan(day, cache):
    root = cache / datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    events = discover(root, day)
    print(f"Discovered {len(events)} temperature events", flush=True)
    supported, exclusions = [], []
    for event in events:
        for market in event.get("markets", []):
            try:
                spec = rules(event, market)
                supported.append((event, market, spec))
            except (KeyError, ValueError, TypeError) as error:
                exclusions.append({"slug": market.get("slug"), "reason": str(error)})
    stations = sorted({s["station"] for _, _, s in supported})
    print(f"Validated rules for {len(supported)} bins at {len(stations)} stations", flush=True)
    batches = [stations[i:i + 3] for i in range(0, len(stations), 3)]
    observations, station_receipts, observation_errors = [], {}, []
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(cached, root / "observations" / f"{i}.json",
                               "https://aviationweather.gov/api/data/metar?" + urlencode({"ids": ",".join(batch), "format": "json", "hours": 30})): batch
                   for i, batch in enumerate(batches)}
        for future in as_completed(futures):
            batch, result = futures[future], future.result()
            payload = result.get("response")
            if "error" in result or not isinstance(payload, list) or len(payload) >= 400:
                observation_errors.append({"stations": batch, "reason": result.get("error", "possible truncation or unexpected response")})
                continue
            observations.extend(payload)
            for station in batch:
                station_receipts[station] = result["received_at"]
    candidates, station_coverage = [], {}
    for event, market, spec in supported:
        station = spec["station"]
        if station not in station_receipts:
            continue
        observed = extrema(observations, station, day, spec["zone"], station_receipts[station], spec["unit"])
        station_coverage[station] = {"zone": spec["zone"], "unit": spec["unit"], **observed}
        if eliminated(spec, observed):
            candidates.append((event, market, spec, observed))
    tokens = [token for _, _, spec, _ in candidates for token in spec["tokens"].values()]
    print(f"Observed extrema eliminated {len(candidates)} bins; fetching both books", flush=True)
    books, timings, book_errors = read_books(root, tokens, "books")
    rows, qualified = [], []
    for event, market, spec, observed in candidates:
        result = quote(market, spec, books)
        row = {"event": event["slug"], "slug": market["slug"], "market_id": str(market["id"]),
               "kind": spec["kind"], "station": spec["station"], "unit": spec["unit"],
               "rules_hash": spec["rules_hash"], "observed": observed, **result}
        rows.append(row)
        if result["status"] == "margin_met":
            qualified.append((event, market, spec, row))
    if qualified:
        unchanged = []
        # Read current metadata before the last quote, not after it has gone stale.
        for event, market, spec, row in qualified:
            fresh = cached(root / "recheck-market" / f"{market['id']}.json", GAMMA + "/markets/" + str(market["id"]))
            try:
                current = fresh["response"]
                current_spec = rules(event, current)
                if current_spec["rules_hash"] != spec["rules_hash"] or current_spec["tokens"] != spec["tokens"]:
                    raise ValueError("rules or tokens changed")
                unchanged.append((event, current, current_spec, row))
            except (KeyError, TypeError, ValueError) as error:
                row["recheck"] = {"status": "metadata_recheck_failed", "reason": str(error)}
        recheck_tokens = [t for _, _, spec, _ in unchanged for t in spec["tokens"].values()]
        rebooks, retimings, reerrors = read_books(root, recheck_tokens, "recheck-books")
        book_errors.extend(reerrors)
        for event, current, spec, row in unchanged:
            row["recheck"] = quote(current, spec, rebooks)
            durations = [datetime.fromisoformat(t["received_at"]).timestamp() - datetime.fromisoformat(t["started_at"]).timestamp()
                         for t in retimings if t["token"] == spec["tokens"]["No"]]
            duration = max(durations) if durations else None
            row["recheck"]["request_seconds"] = duration
            if duration is not None and duration > 2:
                row["recheck"]["status"] = "latency_limit_exceeded"
    report = {"protocol": "research/polymarket/ELIMINATION_PROTOCOL.md", "captured_at": datetime.now(UTC).isoformat(),
              "date": day.isoformat(), "events_discovered": len(events), "markets_supported": len(supported),
              "excluded_markets": exclusions, "stations": station_coverage, "observation_errors": observation_errors,
              "eliminated_bins": len(candidates), "quote_status_counts": {status: sum(r["status"] == status for r in rows) for status in sorted({r["status"] for r in rows})},
              "first_quote_margin_candidates": len(qualified), "confirmed_quote_candidates": sum(r.get("recheck", {}).get("status") == "margin_met" for r in rows),
              "book_errors": book_errors, "candidates": rows,
              "conclusion": "Conditional quote screen only; no orders, fills, or realized profits. Observation corrections remain possible."}
    save(root / "summary.json", report)
    save(Path("research/polymarket/elimination-results.json"), report)
    print(json.dumps({k: v for k, v in report.items() if k not in ("candidates", "excluded_markets", "stations")}, indent=2))
    print(f"Saved {root / 'summary.json'}", flush=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", type=date.fromisoformat, required=True)
    parser.add_argument("--cache", type=Path, default=Path("research/polymarket/data/elimination"))
    args = parser.parse_args()
    scan(args.date, args.cache)
