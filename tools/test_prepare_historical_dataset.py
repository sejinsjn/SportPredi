import json
import unittest

from prepare_historical_dataset import file_summary, stable_negative_long_id


class HistoricalDatasetTest(unittest.TestCase):
    def test_identical_source_rows_are_deduplicated(self):
        match = {
            "date": "2025-08-01",
            "team1": "Alpha FC",
            "team2": "Beta FC",
            "round": "Matchday 1",
            "score": {"ft": [2, 1]},
        }
        payload = json.dumps({"name": "League 2025", "matches": [match, match]}).encode()

        record, fixture_ids, _ = file_summary("2025-26/de.1.json", "a" * 40, payload)

        self.assertEqual(2, record["matchCount"])
        self.assertEqual(1, record["sourceDuplicateRecords"])
        self.assertEqual(1, record["uniqueMatchCount"])
        self.assertEqual(1, record["completedCount"])
        self.assertEqual(2, record["sourceCompletedCount"])
        self.assertEqual(1, len(fixture_ids))

    def test_conflicting_rows_with_same_fixture_key_are_rejected(self):
        first = {
            "date": "2025-08-01",
            "team1": "Alpha FC",
            "team2": "Beta FC",
            "round": "Matchday 1",
            "score": {"ft": [2, 1]},
        }
        second = dict(first, score={"ft": [3, 1]})
        payload = json.dumps({"matches": [first, second]}).encode()

        with self.assertRaisesRegex(RuntimeError, "Conflicting duplicate fixture key"):
            file_summary("2025-26/de.1.json", "a" * 40, payload)

    def test_wide_fixture_ids_separate_previously_colliding_matches(self):
        greek = "fixture:openfootball:gr.1|2020|matchday24|apollonsmyrnis|ofiheraklion"
        english = "fixture:openfootball:en.2|2021|matchday30|barnsleyfc|cardiffcityfc"

        self.assertEqual(-5414043580544583115, stable_negative_long_id(greek))
        self.assertEqual(-5414043579561668037, stable_negative_long_id(english))
        self.assertNotEqual(stable_negative_long_id(greek), stable_negative_long_id(english))


if __name__ == "__main__":
    unittest.main()
