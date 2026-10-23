# Local Android data and backups

The Android app stores its data in a Room/SQLite database named `sportpredi-local.db` inside the app's private storage. There is no server process, Docker volume, or PostgreSQL database required for normal app use. The database survives app restarts and app updates; uninstalling the app or clearing its storage deletes it.

## Create a backup

In the app, select **Backup exportieren**. Android's document picker lets you choose a local folder or a storage provider. The app exports competitions, seasons, teams, fixtures, prediction snapshots and evaluations, synchronization metadata, and backtest results as a versioned JSON file. Store a copy separately from the phone, such as on a computer or removable storage.

## Restore

Select **Backup wiederherstellen**, choose the JSON file, and confirm the replacement prompt. SportPredi validates the file format and its internal references before changing the database. The restore then replaces the local dataset in a single Room transaction; an invalid backup does not partially overwrite the database. Export a backup first if you need to preserve the current device data.

## Synchronization and recovery

- The APK contains a pinned OpenFootball history archive. Its first import runs locally/offline on app launch, is committed season by season, and checkpoints each imported file so work can resume after interruption. Launching the app does not contact OpenLigaDB or GitHub.
- The APK also contains a compact, revision-pinned StatsBomb sample of selected historical matches. It is imported locally and does not trigger network access. Its xG, shot, card, and lineup data are separate from the fixture database and prediction inputs.
- Network synchronization runs only after the user selects **Aktualisieren** or when the network-constrained WorkManager job runs every 12 hours (with a 12-hour initial delay). Android can defer it, and force-stopping the app prevents background runs until it is opened again.
- Optional The Odds API and API-Football credentials are entered in **API-Schlüssel**, encrypted with an AES-GCM key held by Android Keystore, stored only in app-private preferences, and excluded from backups. Do not put credentials in source control or logs. Supplementary API calls are not part of the offline startup import.
- Supplementary provider requests are throttled locally to at most once per 24 hours. The Odds API synchronization is limited to eight mapped competitions per run and stores returned 1X2 and 2.5-goal totals prices. API-Football fixtures are queried by five individual `date=YYYY-MM-DD` requests (three days back through tomorrow), because the provider rejected the combined `from`/`to` request; up to 12 statistics and two fixtures each for lineups and injuries are then requested. This bounds API-Football to at most 21 requests per successful daily run. Fixture details show available xG, shots, possession, lineups, and injury information; field access depends on provider coverage and subscription limits. These statistics are informational and are not yet model inputs.
- A separate odds tip is displayed only for a locally matched, supported market with decimal odds of at least 1.50 and positive model-estimated expected value. It is calculated as `probability × decimal odds − 1`; a positive estimate is not a guaranteed profit or validated betting edge. Missing odds do not produce a value tip.
- OpenLigaDB supplies its available live catalogue and schedules. OpenFootball football.json supplies the bundled history and supplements it with newer files. Explicit league/season mappings preserve the already-selected source for overlapping seasons rather than merging provider IDs heuristically.
- Live synchronization is paced and limited to at most 20 due OpenLigaDB seasons and 20 changed/new OpenFootball files per run.
- Current/recent OpenLigaDB seasons are eligible for checks every 15 minutes while syncing; historical seasons every 30 days. A provider revision endpoint avoids downloading an unchanged season's full schedule.
- The live OpenFootball catalogue is refreshed at most daily. Per-file revisions are checkpointed locally so interrupted imports resume without reimporting successfully processed files.
- **Datenbasis & Abdeckung** in the fixtures screen reports the competitions and seasons actually stored, match/result/team-link/halftime coverage, and the last successful fetch. It does not claim coverage for countries or leagues absent from the providers' catalogues.
- Network/API failures are shown in the app and saved in local synchronization metadata. A later sync retries due seasons. Already committed season imports remain available after a later season fails.
- The local prediction backtest tunes the time-decay half-life and Dixon-Coles parameter on its chronological validation window only. After a run, those selected parameters are applied to upcoming predictions; the later holdout window is not used for selection.
- Neither source guarantees full European coverage. Missing or unavailable competitions and fields are not fabricated. Refer to [DATA_SOURCES.md](DATA_SOURCES.md).
- Android's system app backup is disabled for this app; use the in-app export to protect data from uninstall, device loss, or storage failure.

## Rebuilding the bundled history

The archive is generated from the pinned OpenFootball revision in `tools/prepare_historical_dataset.py`. Run `python tools/prepare_historical_dataset.py` from the repository root to download and checksum the source files, detect exact/conflicting duplicate rows, validate stable fixture/team IDs, and recreate the APK asset. Android deduplicates identical fixtures during import. Run `python -m unittest discover -s tools -p "test_*.py"` to test the generator's duplicate and ID handling before building the APK.

The StatsBomb summary asset is generated from the pinned open-data revision with `python tools/prepare_statsbomb_dataset.py`. The app intentionally bundles only summarized match values and lineups, not raw event streams. Run the same Python test discovery command to validate the summary parser.
