import unittest

from fullsets import basket, partition


def fixture():
    description = "exactly eight digits; Every other report is routine; station's local time; rounded up to the next higher whole degree; correction (COR); qualifying value is the highest temperature; states its range in whole degrees Celsius"
    event = {"slug": "highest-temperature-in-london-on-october-24-2026", "negRisk": True,
             "negRiskAugmented": False, "description": description,
             "resolutionSource": "https://aviationweather.gov/api/data/metar?ids=EGLC", "markets": []}
    books = {}
    for index, (title, price) in enumerate([("16°C or below", .1), ("17°C", .2), ("18°C or higher", .6)]):
        market = {"id": str(index), "negRisk": True, "groupItemTitle": title, "conditionId": f"condition-{index}",
                  "acceptingOrders": True, "closed": False, "feesEnabled": False,
                  "outcomes": ["Yes", "No"], "clobTokenIds": [f"yes-{index}", f"no-{index}"]}
        event["markets"].append(market)
        books[f"yes-{index}"] = {"asset_id": f"yes-{index}", "market": market["conditionId"],
                                "min_order_size": "5", "asks": [{"price": str(price), "size": "10"}]}
    return event, books


class BasketIntegrityTest(unittest.TestCase):
    def test_total_cost_is_for_equal_shares_across_all_bins(self):
        event, books = fixture()
        result = basket(event, books)
        self.assertEqual(result["status"], "margin_met")
        self.assertAlmostEqual(result["basket_cost"], 9)
        self.assertEqual(result["conditional_payout"], 10)

    def test_missing_tail_gap_and_overlap_rejected(self):
        for title in ["18°C", "19°C or higher", "17°C or higher"]:
            event, books = fixture()
            event["markets"][-1]["groupItemTitle"] = title
            self.assertEqual(basket(event, books)["status"], "unsupported_partition")

    def test_augmented_event_rejected(self):
        event, books = fixture()
        event["negRiskAugmented"] = True
        self.assertEqual(basket(event, books)["status"], "unsupported_partition")

    def test_duplicate_outcome_not_counted_as_two_legs(self):
        event, books = fixture()
        event["markets"][2]["clobTokenIds"] = event["markets"][1]["clobTokenIds"]
        self.assertEqual(basket(event, books)["status"], "unsupported_partition")

    def test_incomplete_depth_and_wrong_book_fail_closed(self):
        event, books = fixture()
        books["yes-0"]["asks"][0]["size"] = "9"
        self.assertEqual(basket(event, books)["status"], "insufficient_depth")
        books["yes-0"]["market"] = "wrong"
        self.assertEqual(basket(event, books)["status"], "book_identity_mismatch")

    def test_fees_can_remove_apparent_basket_surplus(self):
        event, books = fixture()
        books["yes-2"]["asks"][0]["price"] = ".685"
        for market in event["markets"]:
            market["feesEnabled"] = True
            market["feeSchedule"] = {"rate": .05, "exponent": 1}
        self.assertEqual(basket(event, books)["status"], "margin_too_small")


if __name__ == "__main__":
    unittest.main()
