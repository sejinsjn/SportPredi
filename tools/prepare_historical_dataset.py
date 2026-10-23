#!/usr/bin/env python3
"""Build the versioned, redistributable OpenFootball asset shipped in the Android app."""

from __future__ import annotations

import concurrent.futures
import hashlib
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_REVISION = "e6744429ee395bc86f247348c6184bb08d4eb361"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "historical" / "football-history-2026-10-21.zip"
EUROPE_CODES = {
    "at",
    "be",
    "ch",
    "cz",
    "de",
    "en",
    "es",
    "fr",
    "gr",
    "hu",
    "it",
    "nl",
    "pt",
    "ru",
    "sco",
    "tr",
    "uefa",
}
SEASON_FILE = re.compile(r"^\d{4}-\d{2}/([^/]+)\.json$")
SEASON_DIR = re.compile(r"^(\d{4})-\d{2}$")
USER_AGENT = "SportPredi-historical-asset-builder/1.0"


def fetch(url: str, attempts: int = 4) -> bytes:
    for attempt in range(attempts):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except (OSError, urllib.error.URLError):
            if attempt + 1 == attempts:
                raise
            time.sleep(min(2**attempt, 8))
    raise RuntimeError(f"Could not fetch {url}")


def normalize(value: str | None) -> str:
    decomposed = unicodedata.normalize("NFD", (value or "").lower())
    return "".join(
        character
        for character in decomposed
        if not unicodedata.category(character).startswith("M") and character.isalnum()
    )


def stable_negative_long_id(value: str) -> int:
    digest = hashlib.sha256(value.encode("utf-8")).digest()
    positive = int.from_bytes(digest[:8], "big") & ((1 << 63) - 1)
    return -max(positive, 1)


def stable_negative_id(value: str) -> int:
    digest = hashlib.sha256(value.encode("utf-8")).digest()
    positive = (
        ((digest[0] & 0x7F) << 24)
        | ((digest[1] & 0xFF) << 16)
        | ((digest[2] & 0xFF) << 8)
        | (digest[3] & 0xFF)
    )
    return -max(positive, 1)


def country_for_league_code(code: str) -> str:
    return {
        "at": "Austria",
        "be": "Belgium",
        "ch": "Switzerland",
        "cz": "Czechia",
        "de": "Germany",
        "en": "England",
        "es": "Spain",
        "fr": "France",
        "gr": "Greece",
        "hu": "Hungary",
        "it": "Italy",
        "nl": "Netherlands",
        "pt": "Portugal",
        "ru": "Russia",
        "sco": "Scotland",
        "tr": "Turkey",
        "uefa": "International",
    }.get(code.split(".", 1)[0].lower(), "Unknown")


def file_summary(
    path: str,
    blob_sha: str,
    raw: bytes,
) -> tuple[dict, list[tuple[int, str, str]], list[tuple[int, str]]]:
    document = json.loads(raw)
    season_folder, filename = path.split("/", 1)
    season_year = int(SEASON_DIR.match(season_folder).group(1))
    league_code = filename.removesuffix(".json")
    competition_code = f"openfootball:{league_code}"
    matches = document.get("matches") or []
    completed = halftime = source_completed = source_halftime = undated = missing_teams = 0
    duplicate_records = 0
    dates: list[str] = []
    fixture_ids: list[tuple[int, str, str]] = []
    team_ids: list[tuple[int, str]] = []
    seen_file_keys: dict[str, str] = {}

    for match in matches:
        date = match.get("date")
        home = match.get("team1")
        away = match.get("team2")
        if not isinstance(date, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            undated += 1
        else:
            dates.append(date)
        if not home or not away:
            missing_teams += 1
        else:
            country = country_for_league_code(league_code)
            for team_name in (home, away):
                team_key = f"team:{country}:{normalize(team_name)}"
                team_ids.append((stable_negative_id(team_key), team_key))
        score = match.get("score") or {}
        full_time = score if isinstance(score, list) else score.get("ft", []) if isinstance(score, dict) else []
        halftime_score = score.get("ht", []) if isinstance(score, dict) else []
        if isinstance(full_time, list) and len(full_time) >= 2:
            source_completed += 1
        if isinstance(halftime_score, list) and len(halftime_score) >= 2:
            source_halftime += 1
        fixture_key = "|".join(
            (
                competition_code,
                str(season_year),
                normalize(match.get("round")),
                normalize(home),
                normalize(away),
            )
        )
        if not home or not away:
            fixture_key += f"|{date or ''}"
        payload = json.dumps(match, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        previous_payload = seen_file_keys.get(fixture_key)
        if previous_payload is not None:
            if previous_payload != payload:
                raise RuntimeError(f"Conflicting duplicate fixture key in {path}: {fixture_key}")
            duplicate_records += 1
            continue
        seen_file_keys[fixture_key] = payload
        if isinstance(full_time, list) and len(full_time) >= 2:
            completed += 1
        if isinstance(halftime_score, list) and len(halftime_score) >= 2:
            halftime += 1
        fixture_ids.append((stable_negative_long_id(f"fixture:{fixture_key}"), fixture_key, payload))

    return (
        {
            "path": path,
            "sha": blob_sha,
            "bytes": len(raw),
            "name": document.get("name"),
            "leagueCode": league_code,
            "seasonYear": season_year,
            "matchCount": len(matches),
            "completedCount": completed,
            "sourceCompletedCount": source_completed,
            "sourceDuplicateRecords": duplicate_records,
            "uniqueMatchCount": len(fixture_ids),
            "halftimeCount": halftime,
            "sourceHalftimeCount": source_halftime,
            "undatedCount": undated,
            "missingTeamCount": missing_teams,
            "firstDate": min(dates) if dates else None,
            "lastDate": max(dates) if dates else None,
        },
        fixture_ids,
        team_ids,
    )


def main() -> int:
    tree_url = (
        "https://api.github.com/repos/openfootball/football.json/git/trees/"
        f"{SOURCE_REVISION}?recursive=1"
    )
    tree = json.loads(fetch(tree_url))
    if tree.get("truncated"):
        raise RuntimeError("GitHub returned a truncated source tree; refusing to build a partial archive.")
    if tree.get("sha") != SOURCE_REVISION:
        raise RuntimeError("The source revision returned by GitHub does not match the pinned revision.")

    selected = []
    for item in tree.get("tree", []):
        path = item.get("path", "")
        match = SEASON_FILE.match(path)
        if item.get("type") != "blob" or not match:
            continue
        league_code = match.group(1)
        prefix = league_code.split(".", 1)[0]
        if prefix not in EUROPE_CODES or league_code.endswith("-full"):
            continue
        selected.append((path, item["sha"], item.get("size", 0)))
    selected.sort()
    if not selected:
        raise RuntimeError("The pinned source tree contains no selected European season files.")

    downloaded: dict[str, bytes] = {}

    def download(item: tuple[str, str, int]) -> tuple[str, str, bytes]:
        path, expected_sha, _ = item
        url = (
            "https://raw.githubusercontent.com/openfootball/football.json/"
            f"{SOURCE_REVISION}/{path}"
        )
        content = fetch(url)
        git_blob_sha = hashlib.sha1(
            b"blob " + str(len(content)).encode("ascii") + b"\0" + content
        ).hexdigest()
        if git_blob_sha != expected_sha:
            raise RuntimeError(f"Source checksum mismatch for {path}.")
        return path, expected_sha, content

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        for path, _, content in executor.map(download, selected):
            downloaded[path] = content

    manifest_files = []
    all_fixture_ids: dict[int, str] = {}
    all_team_ids: dict[int, str] = {}
    fixture_payloads: dict[str, str] = {}
    duplicate_fixture_ids: list[tuple[int, str, str]] = []
    duplicate_team_ids: list[tuple[int, str, str]] = []
    source_duplicate_records = 0
    unique_matches = 0
    raw_bytes = 0
    total_matches = completed = halftime = source_completed = source_halftime = undated = missing_teams = 0
    for path, blob_sha, _ in selected:
        raw = downloaded[path]
        record, fixture_ids, team_ids = file_summary(path, blob_sha, raw)
        manifest_files.append(record)
        raw_bytes += len(raw)
        total_matches += record["matchCount"]
        completed += record["completedCount"]
        halftime += record["halftimeCount"]
        source_completed += record["sourceCompletedCount"]
        source_halftime += record["sourceHalftimeCount"]
        undated += record["undatedCount"]
        missing_teams += record["missingTeamCount"]
        source_duplicate_records += record["sourceDuplicateRecords"]
        for fixture_id, key, payload in fixture_ids:
            previous_payload = fixture_payloads.get(key)
            if previous_payload is not None:
                if previous_payload != payload:
                    raise RuntimeError(f"Conflicting duplicate fixture key in source data: {key}")
                source_duplicate_records += 1
                continue
            fixture_payloads[key] = payload
            unique_matches += 1
            previous = all_fixture_ids.get(fixture_id)
            if previous is not None and previous != key:
                duplicate_fixture_ids.append((fixture_id, previous, key))
            all_fixture_ids[fixture_id] = key
        for team_id, team_key in team_ids:
            previous = all_team_ids.get(team_id)
            if previous is not None and previous != team_key:
                duplicate_team_ids.append((team_id, previous, team_key))
            all_team_ids[team_id] = team_key

    if duplicate_fixture_ids:
        examples = duplicate_fixture_ids[:5]
        raise RuntimeError(f"Generated fixture IDs collide: {examples}")
    if duplicate_team_ids:
        examples = duplicate_team_ids[:5]
        raise RuntimeError(f"Generated team IDs collide: {examples}")
    if undated or missing_teams:
        raise RuntimeError(
            f"Source contains records the Android importer cannot safely map: "
            f"{undated} undated and {missing_teams} missing a team."
        )

    manifest = {
        "formatVersion": 1,
        "sourceRepository": "https://github.com/openfootball/football.json",
        "sourceRevision": SOURCE_REVISION,
        "license": "Public domain as stated by the pinned source README.",
        "licenseUrl": (
            "https://raw.githubusercontent.com/openfootball/football.json/"
            f"{SOURCE_REVISION}/README.md"
        ),
        "selection": "European season JSON files, excluding duplicate *-full.json files.",
        "statistics": {
            "fileCount": len(manifest_files),
            "leagueCount": len({item["leagueCode"] for item in manifest_files}),
            "seasonCount": len({(item["leagueCode"], item["seasonYear"]) for item in manifest_files}),
            "matchCount": total_matches,
            "uniqueMatchCount": unique_matches,
            "sourceDuplicateRecords": source_duplicate_records,
            "completedCount": completed,
            "sourceCompletedCount": source_completed,
            "halftimeCount": halftime,
            "sourceHalftimeCount": source_halftime,
            "rawJsonBytes": raw_bytes,
            "earliestDate": min(item["firstDate"] for item in manifest_files if item["firstDate"]),
            "latestDate": max(item["lastDate"] for item in manifest_files if item["lastDate"]),
        },
        "files": manifest_files,
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(OUTPUT.suffix + ".tmp")
    with zipfile.ZipFile(
        temporary,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        archive.writestr(
            "manifest.json",
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
        )
        for path, _, _ in selected:
            archive.writestr(f"football.json/{path}", downloaded[path])
    temporary.replace(OUTPUT)

    print(f"Asset: {OUTPUT.relative_to(ROOT)}")
    print(f"Source revision: {SOURCE_REVISION}")
    print(f"Files: {len(manifest_files)}; leagues: {manifest['statistics']['leagueCount']}")
    print(f"Seasons: {manifest['statistics']['seasonCount']}; matches: {total_matches}")
    print(f"Completed: {completed} unique ({source_completed} source rows); halftime scores: {halftime} unique")
    print(f"Raw JSON: {raw_bytes:,} bytes; ZIP: {OUTPUT.stat().st_size:,} bytes")
    print(f"Dates: {manifest['statistics']['earliestDate']} through {manifest['statistics']['latestDate']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
