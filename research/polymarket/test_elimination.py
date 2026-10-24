from datetime import date, datetime, timezone
from decimal import Decimal
import unittest

from elimination import eliminated, extrema, quote, raw_temperature, rounded_temperature


class ContractParsingTest(unittest.TestCase):
    def test_precision_remark_overrides_body(self):
        self.assertEqual(raw_temperature("METAR KLGA 241251Z 18/12 RMK AO2 T01830122"), Decimal("18.3"))

    def test_negative_precision_and_missing_remark(self):
        self.assertEqual(raw_temperature("KLGA 241251Z M02/M03 RMK T10171022"), Decimal("-1.7"))
        self.assertEqual(raw_temperature("METAR COR KLGA 241251Z M11/M17 RMK AO2"), Decimal(-11))

    def test_speci_rejected_but_unlabelled_routine_used(self):
        self.assertIsNone(raw_temperature("SPECI KLGA 241251Z 25/12 RMK T02500122"))
        self.assertEqual(raw_temperature("KLGA 241252Z 25/12 RMK AO2"), Decimal(25))

    def test_extrema_remark_not_mistaken_for_instant_temperature(self):
        self.assertEqual(raw_temperature("METAR KLGA 241251Z 18/12 RMK 10250 20100 402501000"), Decimal(18))
        self.assertIsNone(raw_temperature("METAR KLGA 241251Z //// RMK AO2"))

    def test_half_rounds_toward_positive_infinity(self):
        self.assertEqual(rounded_temperature(Decimal("16.5"), "C"), 17)
        self.assertEqual(rounded_temperature(Decimal("-8.5"), "C"), -8)
        self.assertEqual(rounded_temperature(Decimal("-22.5"), "F"), -8)
        self.assertEqual(rounded_temperature(Decimal("18.3"), "F"), 65)


class ObservationStateTest(unittest.TestCase):
    def record(self, raw="METAR KLGA 240551Z 20/10 RMK T02000100", receipt="2026-10-24T05:55:00Z"):
        return {"icaoId": "KLGA", "obsTime": int(datetime(2026, 10, 24, 5, 51, tzinfo=timezone.utc).timestamp()),
                "receiptTime": receipt, "rawOb": raw}

    def test_latest_correction_can_reduce_running_high(self):
        records = [self.record(), self.record("METAR COR KLGA 240551Z 15/10 RMK T01500100", "2026-10-24T06:00:00Z")]
        result = extrema(records, "KLGA", date(2026, 10, 24), "America/New_York", "2026-10-24T06:01:00Z", "C")
        self.assertEqual(result, {"reports": 1, "high": 15, "low": 15})

    def test_future_receipt_and_wrong_local_day_are_not_known(self):
        result = extrema([self.record(receipt="2026-10-24T07:00:00Z")], "KLGA", date(2026, 10, 24),
                         "America/New_York", "2026-10-24T06:01:00Z", "C")
        self.assertEqual(result["reports"], 0)
        result = extrema([self.record()], "KLGA", date(2026, 10, 23), "America/New_York", "2026-10-24T06:01:00Z", "C")
        self.assertEqual(result["reports"], 0)

    def test_high_and_low_require_one_extra_degree_buffer(self):
        high = {"kind": "highest", "upper": 20}
        low = {"kind": "lowest", "lower": 20}
        self.assertFalse(eliminated(high, {"reports": 1, "high": 21}))
        self.assertTrue(eliminated(high, {"reports": 1, "high": 22}))
        self.assertFalse(eliminated(low, {"reports": 1, "low": 19}))
        self.assertTrue(eliminated(low, {"reports": 1, "low": 18}))


class ExecutionGuardTest(unittest.TestCase):
    def inputs(self):
        market = {"conditionId": "condition", "acceptingOrders": True, "closed": False,
                  "feeSchedule": {"rate": .05, "exponent": 1}}
        spec = {"tokens": {"No": "no", "Yes": "yes"}}
        books = {token: {"asset_id": token, "market": "condition", "min_order_size": "5",
                         "asks": [{"price": ".98", "size": "10"}]} for token in ("no", "yes")}
        return market, spec, books

    def test_current_fee_applied_before_margin_check(self):
        result = quote(*self.inputs())
        self.assertEqual(result["status"], "margin_met")
        self.assertAlmostEqual(result["cost"], 10 * (.98 + .05 * .98 * .02))

    def test_tiny_top_quote_does_not_support_full_size(self):
        market, spec, books = self.inputs()
        books["no"]["asks"] = [{"price": ".98", "size": "1"}, {"price": ".999", "size": "9"}]
        self.assertEqual(quote(market, spec, books)["status"], "margin_too_small")

    def test_missing_fees_wrong_market_and_closed_state_fail_closed(self):
        market, spec, books = self.inputs()
        market["feeSchedule"] = {}
        self.assertEqual(quote(market, spec, books)["status"], "unknown_fees")
        market["feesEnabled"] = False
        books["no"]["market"] = "other"
        self.assertEqual(quote(market, spec, books)["status"], "book_market_mismatch")
        market["closed"] = True
        self.assertEqual(quote(market, spec, books)["status"], "not_accepting_orders")


if __name__ == "__main__":
    unittest.main()
