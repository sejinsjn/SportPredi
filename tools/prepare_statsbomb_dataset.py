#!/usr/bin/env python3
"""Build the compact, offline StatsBomb sample included in the Android app."""

from __future__ import annotations

import json
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


REVISION = "4b73468fc5b0f1950f9f66fada70ad3a4f9327cb"
COMPETITION_ID = 9
SEASON_ID = 281
SEASON_PATH = f"data/matches/{COMPETITION_ID}/{SEASON_ID}.json"
BASE_URL = f"https://raw.githubusercontent.com/statsbomb/open-data/{REVISION}"
OUTPUT_PATH = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "statsbomb" / "bundesliga-2023-24.json"


def read_json(path: str) -> object:
    request = urllib.request.Request(
        f"{BASE_URL}/{path}",
        headers={"User-Agent": "SportPredi StatsBomb offline dataset builder"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def _team_id(item: dict) -> int | None:
    return (item.get("team") or {}).get("id")


def _lineup(team: dict) -> list[dict]:
    result = []
    for player in team.get("lineup", []):
        positions = player.get("positions") or []
        result.append({
            "name": player.get("player_name", "Unbekannt"),
            "jerseyNumber": player.get("jersey_number"),
            "starter": any(
                position.get("start_reason") == "Starting XI"
                or (position.get("from") == "00:00" and position.get("from_period") == 1)
                for position in positions
            ),
            "position": positions[0].get("position") if positions else None,
        })
    return sorted(result, key=lambda player: (not player["starter"], player["jerseyNumber"] or 999))


def summarize_match(match: dict, events: list[dict], lineups: list[dict], revision: str = REVISION) -> dict:
    home_id = match["home_team"]["home_team_id"]
    away_id = match["away_team"]["away_team_id"]
    totals = {
        home_id: {"xg": 0.0, "xgCount": 0, "shots": 0},
        away_id: {"xg": 0.0, "xgCount": 0, "shots": 0},
    }
    for event in events:
        team_id = _team_id(event)
        if event.get("type", {}).get("name") != "Shot" or team_id not in totals:
            continue
        shot = event.get("shot") or {}
        totals[team_id]["shots"] += 1
        xg = shot.get("statsbomb_xg")
        if isinstance(xg, (int, float)):
            totals[team_id]["xg"] += float(xg)
            totals[team_id]["xgCount"] += 1

    lineup_by_id = {team.get("team_id"): team for team in lineups}
    if home_id not in lineup_by_id or away_id not in lineup_by_id:
        raise ValueError(f"Match {match['match_id']} has no lineup data for both teams")
    cards = {home_id: {"yellow": 0, "red": 0}, away_id: {"yellow": 0, "red": 0}}
    for team in lineups:
        team_id = team.get("team_id")
        if team_id not in cards:
            continue
        for player in team.get("lineup", []):
            for card in player.get("cards", []):
                card_type = card.get("card_type", "").lower()
                if "yellow" in card_type:
                    cards[team_id]["yellow"] += 1
                if "red" in card_type or "second yellow" in card_type:
                    cards[team_id]["red"] += 1

    home = match["home_team"]
    away = match["away_team"]
    home_stats = totals[home_id]
    away_stats = totals[away_id]
    return {
        "providerMatchId": match["match_id"],
        "competitionId": match["competition"]["competition_id"],
        "seasonId": match["season"]["season_id"],
        "competitionName": match["competition"]["competition_name"],
        "country": match["competition"]["country_name"],
        "seasonName": match["season"]["season_name"],
        "matchDate": match["match_date"],
        "kickoffAt": match.get("kick_off"),
        "homeTeam": home["home_team_name"],
        "awayTeam": away["away_team_name"],
        "homeScore": match.get("home_score"),
        "awayScore": match.get("away_score"),
        "homeXg": round(home_stats["xg"], 4) if home_stats["xgCount"] == home_stats["shots"] else None,
        "awayXg": round(away_stats["xg"], 4) if away_stats["xgCount"] == away_stats["shots"] else None,
        "homeShots": home_stats["shots"],
        "awayShots": away_stats["shots"],
        "homeYellowCards": cards[home_id]["yellow"],
        "awayYellowCards": cards[away_id]["yellow"],
        "homeRedCards": cards[home_id]["red"],
        "awayRedCards": cards[away_id]["red"],
        "homeLineupJson": json.dumps(_lineup(lineup_by_id.get(home_id, {})), ensure_ascii=False, separators=(",", ":")),
        "awayLineupJson": json.dumps(_lineup(lineup_by_id.get(away_id, {})), ensure_ascii=False, separators=(",", ":")),
        "sourceRevision": revision,
    }


def build_dataset() -> dict:
    matches = read_json(SEASON_PATH)
    paths = [
        f"data/{kind}/{match['match_id']}.json"
        for match in matches
        for kind in ("events", "lineups")
    ]
    with ThreadPoolExecutor(max_workers=8) as pool:
        fetched = dict(zip(paths, pool.map(read_json, paths)))

    available = []
    for match in matches:
        match_id = match["match_id"]
        events = fetched[f"data/events/{match_id}.json"]
        lineups = fetched[f"data/lineups/{match_id}.json"]
        if match.get("match_status") != "available" or not events or not lineups:
            continue
        available.append(summarize_match(match, events, lineups))
    return {
        "formatVersion": 1,
        "sourceRevision": REVISION,
        "competitionName": "1. Bundesliga",
        "seasonName": "2023/2024",
        "matches": available,
    }


def main() -> None:
    dataset = build_dataset()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(
        json.dumps(dataset, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {len(dataset['matches'])} sample matches to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
