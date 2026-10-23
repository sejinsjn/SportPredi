import unittest
import json
from pathlib import Path

from prepare_statsbomb_dataset import summarize_match


class StatsBombDatasetTests(unittest.TestCase):
    def test_bundled_snapshot_is_pinned_and_has_transparent_sample_coverage(self):
        asset_path = (
            Path(__file__).resolve().parents[1]
            / "app"
            / "src"
            / "main"
            / "assets"
            / "statsbomb"
            / "bundesliga-2023-24.json"
        )
        bundle = json.loads(asset_path.read_text(encoding="utf-8"))

        self.assertEqual(1, bundle["formatVersion"])
        self.assertEqual("4b73468fc5b0f1950f9f66fada70ad3a4f9327cb", bundle["sourceRevision"])
        self.assertEqual("2023/2024", bundle["seasonName"])
        self.assertEqual(34, len(bundle["matches"]))
        self.assertTrue(all(match["sourceRevision"] == bundle["sourceRevision"] for match in bundle["matches"]))
        union_match = next(match for match in bundle["matches"] if match["providerMatchId"] == 3895292)
        self.assertAlmostEqual(0.5451, union_match["homeXg"])
        self.assertAlmostEqual(2.5635, union_match["awayXg"])

    def test_summarizes_shots_xg_cards_and_starting_lineups(self):
        match = {
            "match_id": 10,
            "competition": {"competition_id": 9, "competition_name": "1. Bundesliga", "country_name": "Germany"},
            "season": {"season_id": 281, "season_name": "2023/2024"},
            "match_date": "2024-01-01",
            "home_team": {"home_team_id": 1, "home_team_name": "Home"},
            "away_team": {"away_team_id": 2, "away_team_name": "Away"},
            "home_score": 1,
            "away_score": 0,
        }
        events = [
            {"type": {"name": "Shot"}, "team": {"id": 1}, "shot": {"statsbomb_xg": 0.25}},
            {"type": {"name": "Shot"}, "team": {"id": 1}, "shot": {"statsbomb_xg": 0.5}},
            {"type": {"name": "Pass"}, "team": {"id": 1}},
            {"type": {"name": "Shot"}, "team": {"id": 2}, "shot": {"statsbomb_xg": 0.1}},
        ]
        lineups = [
            {
                "team_id": 1,
                "lineup": [
                    {
                        "player_name": "Starter",
                        "jersey_number": 9,
                        "positions": [{"start_reason": "Starting XI", "position": "Forward"}],
                        "cards": [{"card_type": "Yellow Card"}],
                    },
                    {
                        "player_name": "Substitute",
                        "jersey_number": 18,
                        "positions": [],
                        "cards": [{"card_type": "Second Yellow"}],
                    },
                ],
            },
            {"team_id": 2, "lineup": []},
        ]

        result = summarize_match(match, events, lineups, "abc123")

        self.assertEqual((2, 1), (result["homeShots"], result["awayShots"]))
        self.assertAlmostEqual(0.75, result["homeXg"])
        self.assertAlmostEqual(0.1, result["awayXg"])
        self.assertEqual((2, 1), (result["homeYellowCards"], result["homeRedCards"]))
        self.assertEqual("abc123", result["sourceRevision"])
        self.assertIn('"starter":true', result["homeLineupJson"])
        self.assertIn('"starter":false', result["homeLineupJson"])

    def test_does_not_report_partial_xg_as_a_complete_total(self):
        match = {
            "match_id": 11,
            "competition": {"competition_id": 9, "competition_name": "1. Bundesliga", "country_name": "Germany"},
            "season": {"season_id": 281, "season_name": "2023/2024"},
            "match_date": "2024-01-01",
            "home_team": {"home_team_id": 1, "home_team_name": "Home"},
            "away_team": {"away_team_id": 2, "away_team_name": "Away"},
        }
        lineups = [{"team_id": 1, "lineup": []}, {"team_id": 2, "lineup": []}]
        events = [
            {"type": {"name": "Shot"}, "team": {"id": 1}, "shot": {"statsbomb_xg": 0.2}},
            {"type": {"name": "Shot"}, "team": {"id": 1}, "shot": {}},
        ]

        result = summarize_match(match, events, lineups)

        self.assertIsNone(result["homeXg"])
        self.assertEqual(2, result["homeShots"])


if __name__ == "__main__":
    unittest.main()
