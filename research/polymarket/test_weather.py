import unittest

from weather import no_token, observation, portfolio, price_before
from books import walk_asks


def row(mid="1", event="london", decision=1000, closed=2000, payout=1, price=.95):
    return {"id": mid, "event": event, "decision": decision, "closed": closed,
            "price": price, "payout": payout}


class HistoricalIntegrityTest(unittest.TestCase):
    def test_future_bucket_is_not_available_at_decision(self):
        response = {"data": [{"timestamp": 950, "price": .95, "resolution_seconds": 300}]}
        self.assertIsNone(price_before(response, 1000))

    def test_stale_or_future_ticks_rejected(self):
        for ts in [399, 1001]:
            self.assertIsNone(price_before({"data": [{"timestamp": ts, "price": .95, "resolution_seconds": 0}]}, 1000))

    def test_actual_no_token_and_protocol_version(self):
        self.assertEqual(no_token({"outcomes": '["No","Yes"]', "clobTokenIds": '["no","yes"]'}), "no")
        self.assertEqual(no_token({"version": "v2", "outcomes": ["Yes", "No"],
                                   "positionIds": ["yes-v2", "no-v2"], "clobTokenIds": ["wrong", "wrong"]}), "no-v2")

    def test_unresolved_is_not_silently_a_loser(self):
        m = {"id": "1", "endDate": "2026-10-01T12:00:00Z", "createdAt": "2026-09-29T12:00:00Z",
             "outcomes": '["Yes","No"]', "outcomePrices": '["0.01","0.99"]', "closed": False}
        point = {"data": [{"timestamp": 1790834340, "price": .95, "resolution_seconds": 0}]}
        # Set the observation exactly one minute before the frozen 06:00 UTC decision.
        from weather import epoch
        point["data"][0]["timestamp"] = epoch("2026-10-01T06:00:00Z") - 60
        observed, status = observation(m, point)
        self.assertEqual(status, "observed")
        self.assertIsNone(observed["payout"])


class CapitalAndCostsTest(unittest.TestCase):
    def test_event_bins_share_one_cap(self):
        result = portfolio([row(mid=str(i)) for i in range(11)], .01)
        self.assertEqual(result["trades"], 5)
        self.assertEqual(result["max_reserved"], 50)

    def test_total_cap_shared_across_events(self):
        result = portfolio([row(mid=str(i), event=str(i)) for i in range(30)], .01)
        self.assertEqual(result["trades"], 20)
        self.assertEqual(result["max_reserved"], 200)

    def test_cash_reused_only_after_closure(self):
        rows = [row(mid=str(i), event=str(i), closed=2000) for i in range(20)]
        rows += [row(mid="later", event="later", decision=1999)]
        rows += [row(mid="after", event="later", decision=2000, closed=3000)]
        result = portfolio(rows, .01)
        self.assertNotIn("later", [t["id"] for t in result["trade_log"]])
        self.assertIn("after", [t["id"] for t in result["trade_log"]])

    def test_unresolved_capital_stays_locked(self):
        result = portfolio([row(payout=None, closed=None)], .01)
        self.assertEqual(result["ending_cash"], 990)
        self.assertEqual(result["reserved_unsettled"], 10)
        self.assertIsNone(result["roi_settled"])

    def test_fees_and_losing_settlement(self):
        result = portfolio([row(payout=0)], .01)
        self.assertEqual(result["pnl_settled"], -10)
        self.assertAlmostEqual(result["trade_log"][0]["all_in_per_share"], .96 + .05 * .96 * .04)
        self.assertEqual(result["max_realized_drawdown"], 10)

    def test_impossible_price_is_rejected_not_clipped(self):
        result = portfolio([row(price=.98)], .02)
        self.assertEqual(result["trades"], 0)

    def test_book_depth_is_consumed_in_price_order(self):
        book = {"asks": [{"price": ".98", "size": "9"}, {"price": ".90", "size": "1"}]}
        self.assertAlmostEqual(walk_asks(book, 10, 0), 9.72)
        self.assertIsNone(walk_asks(book, 11, 0))


if __name__ == "__main__":
    unittest.main()
