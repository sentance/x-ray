from datetime import datetime, timezone
import math
import unittest
from urllib.parse import parse_qs, urlparse

from forecast import bounds, daily_max, forecast_request, no_probability
from weather import epoch


class ForecastIntegrityTest(unittest.TestCase):
    def test_rounded_celsius_and_fahrenheit_bin_boundaries(self):
        self.assertEqual(bounds("20°C"), (19.5, 20.5))
        low, high = bounds("70-71°F")
        self.assertAlmostEqual(low, (69.5 - 32) / 1.8)
        self.assertAlmostEqual(high, (71.5 - 32) / 1.8)

    def test_tails_cover_extremes(self):
        self.assertEqual(bounds("16°C or below"), (-math.inf, 16.5))
        self.assertEqual(bounds("26°C or higher"), (25.5, math.inf))

    def test_partition_probabilities_sum_to_one(self):
        titles = ["16°C or below"] + [f"{i}°C" for i in range(17, 26)] + ["26°C or higher"]
        self.assertAlmostEqual(sum(1 - no_probability(t, 20.1, 2) for t in titles), 1)

    def test_run_predates_decision_by_at_least_twelve_hours(self):
        event = {"markets": [{"endDate": "2026-10-01T12:00:00Z"}]}
        url = forecast_request("highest-temperature-in-london-on-october-1-2026", event)
        query = parse_qs(urlparse(url).query)
        run = datetime.fromisoformat(query["run"][0]).replace(tzinfo=timezone.utc).timestamp()
        self.assertGreaterEqual(epoch(event["markets"][0]["endDate"]) - 6 * 3600 - run, 12 * 3600)

    def test_incomplete_forecast_day_rejected(self):
        response = {"hourly": {"time": ["2026-10-01T01:00"], "temperature_2m": [20]}}
        with self.assertRaises(ValueError):
            daily_max(response, "2026-10-01")


if __name__ == "__main__":
    unittest.main()
