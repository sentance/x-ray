import unittest

from audit_math import cash_delta, position_value, summarize


def activity(kind, cash, side=None):
    row = {"type": kind, "usdc_size": cash, "timestamp": 1, "transaction_hash": kind, "token_id": "token", "event_slug": "event"}
    if side:
        row["side"] = side
    return row


class WalletReconciliationTest(unittest.TestCase):
    def test_conversion_collateral_is_cash_return_not_all_profit(self):
        rows = [activity("DEPOSIT", 100), activity("TRADE", 99, "BUY"), activity("CONVERSION", 98), activity("REWARD", 5)]
        result = summarize({"complete": True, "data": rows}, [{"complete": True, "data": []}],
                           {"complete": True, "data": []}, 104, 9999)
        self.assertEqual(result["marked_profit_after_net_funding"], 4)
        self.assertEqual(result["marked_profit_excluding_incentives"], -1)
        self.assertEqual(result["reported_pnl_minus_reconciled_marked_profit"], 9995)

    def test_zero_cash_conversion_creates_no_cash_credit(self):
        self.assertEqual(cash_delta(activity("CONVERSION", 0)), 0)

    def test_duplicate_position_statuses_do_not_double_count(self):
        p = {"token_id": "a", "current_value": 12}
        value, count = position_value([{"complete": True, "data": [p]}, {"complete": True, "data": [p]}])
        self.assertEqual(value, 12)
        self.assertEqual(count, 1)

    def test_missing_pages_and_conflicting_marks_fail_closed(self):
        with self.assertRaises(ValueError):
            position_value([{"complete": False, "data": []}])
        with self.assertRaises(ValueError):
            position_value([{"complete": True, "data": [{"token_id": "a", "current_value": 12}, {"token_id": "a", "current_value": 15}]}])

    def test_missing_cash_prevents_profit_claim(self):
        with self.assertRaises(ValueError):
            summarize({"complete": True, "data": [activity("DEPOSIT", 100)]}, [], {"complete": True, "data": []}, 99, 1)

    def test_unknown_cash_type_does_not_silently_count_as_income(self):
        with self.assertRaises(ValueError):
            cash_delta(activity("UNKNOWN", 500))

    def test_withdrawals_are_removed_from_net_funding(self):
        result = summarize({"complete": True, "data": [activity("DEPOSIT", 100), activity("REWARD", 10), activity("WITHDRAWAL", 60)]},
                           [], {"complete": True, "data": []}, 50, 10)
        self.assertEqual(result["net_funding"], 40)
        self.assertEqual(result["marked_profit_after_net_funding"], 10)


if __name__ == "__main__":
    unittest.main()
