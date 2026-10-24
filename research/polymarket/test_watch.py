import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from watch import campaign_summary, watch


class CampaignAccountingTest(unittest.TestCase):
    def test_repeated_quote_is_one_candidate_not_multiple_trades(self):
        report = {"candidates": [{"market_id": "1", "recheck": {"status": "margin_met"}}]}
        records = [{"kind": "elimination", "report": report}, {"kind": "elimination", "report": report},
                   {"kind": "fullsets", "error": "network timeout"}]
        summary = campaign_summary(records)
        self.assertEqual(summary["unique_confirmed_quote_candidates"]["elimination"], ["1"])
        self.assertEqual(summary["completed_screens"], 2)
        self.assertEqual(summary["failed_screens"], 1)

    def test_early_quote_and_failed_recheck_are_not_confirmed(self):
        records = [{"kind": "fullsets", "report": {"baskets": [{"event": "city", "status": "margin_met",
                    "recheck": {"status": "latency_limit_exceeded"}}]}}]
        self.assertEqual(campaign_summary(records)["unique_confirmed_quote_candidates"]["fullsets"], [])

    def test_unbounded_or_fast_polling_rejected_before_network(self):
        for captures, interval in [(0, 300), (121, 300), (1, 0)]:
            with self.assertRaises(ValueError):
                watch(None, captures, interval, None)

    def test_bounded_capture_preserves_failure_and_continues_other_screen(self):
        from datetime import date
        import json
        with TemporaryDirectory() as directory:
            with patch("watch.elimination_scan", side_effect=RuntimeError("timeout")), \
                 patch("watch.fullset_scan", return_value={"baskets": []}), \
                 patch("builtins.print"):
                summary = watch(date(2026, 10, 24), 1, 300, Path(directory))
            self.assertEqual(summary["failed_screens"], 1)
            self.assertEqual(summary["completed_screens"], 1)
            record = json.loads(next(Path(directory).glob("*/campaign.json")).read_text())
            self.assertEqual(record["records"][0]["error"], "timeout")
            self.assertEqual(record["planned_captures"], 1)


if __name__ == "__main__":
    unittest.main()
