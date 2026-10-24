import unittest

from observations import available_high


class ObservationAvailabilityTest(unittest.TestCase):
    def test_available_local_day_report_is_used(self):
        obs = {"icaoId": "KLGA", "obsTime": 1792821600, "receiptTime": "2026-10-24T06:01:00Z",
               "temp": 25, "metarType": "METAR"}
        self.assertEqual(available_high([obs], "KLGA", "2026-10-24", "America/New_York", 1792832400), 25)

    def test_delayed_report_not_available_at_book_time(self):
        obs = {"icaoId": "KLGA", "obsTime": 1792821600, "receiptTime": "2026-10-24T10:00:00Z",
               "temp": 25, "metarType": "METAR"}
        self.assertIsNone(available_high([obs], "KLGA", "2026-10-24", "America/New_York", 1792825200))

    def test_previous_local_day_does_not_count(self):
        obs = {"icaoId": "KLGA", "obsTime": 1792807200, "receiptTime": "2026-10-24T02:01:00Z",
               "temp": 25, "metarType": "METAR"}
        self.assertIsNone(available_high([obs], "KLGA", "2026-10-24", "America/New_York", 1792832400))


if __name__ == "__main__":
    unittest.main()
