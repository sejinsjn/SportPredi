# SportPredi Android

SportPredi is an Android-only football fixtures and prediction app. The data pipeline runs on the device: no SportPredi server, PostgreSQL, Docker, API key, or account is required.

## Build and run

Open this repository's root folder in Android Studio. The included Gradle wrapper uses Gradle 8.9. Use JDK 17 and install Android SDK Platform 35. Select the `app` run configuration and launch it on an Android 8.0+ phone or emulator with internet access.

From PowerShell, you can also build and install the debug APK:

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Local data and synchronization

On launch, the app only imports versioned OpenFootball and StatsBomb data bundled in the APK and refreshes local predictions; it does not contact a network provider. The pinned OpenFootball snapshot contains 262 season files from 31 competition codes (88,616 unique matches; 83,536 with final scores; 12 exact duplicate source rows are deduplicated during import). The separate StatsBomb sample contains 34 selected Bundesliga 2023/24 games. Select **Aktualisieren** to check OpenLigaDB's revision endpoints and the OpenFootball catalogue; unchanged OpenFootball files are not downloaded again. Explicit source mapping prevents a season already stored from one provider being imported again from the other. Live updates are limited to 20 due OpenLigaDB seasons and 20 OpenFootball files per run.

The app also schedules a network-constrained WorkManager sync every 12 hours. Android may defer background work, and force-stopping the app suspends it until the app is opened again; this is device-local automation, not an always-on server. Selecting **Aktualisieren** requests an immediate sync.

Fixtures, source metadata, predictions, prediction evaluations, historical match statistics, and backtest results are kept in the app-private `sportpredi-local.db` Room database. They persist across normal app restarts, but uninstalling or clearing app data removes them. Use **Backup exportieren** and **Backup wiederherstellen** to transfer a versioned JSON backup through Android's document picker.

Optional API credentials can be entered under **API-Schlüssel**. They are encrypted with AES-GCM using a key held by Android Keystore and are not included in backups. The Odds API and API-Football supplement the existing fixture sources; they do not replace them. Their requests run only during a requested/background synchronization, never during the offline startup import. Provider access, field availability, and quotas depend on the account plan. Odds-based tips are shown only when a matching quote is available at 1.50 or higher and the model estimates positive expected value (`probability × decimal odds − 1`); this is an uncertain estimate, not a promise of profit. API-Football xG, shots, possession, lineups, and injury data are displayed when returned, but are not currently inputs to the prediction model. Missing fields remain unavailable.

## Predictions and limitations

Every upcoming fixture with linked teams receives a local model estimate, even when its history is sparse; the UI labels low-data forecasts clearly. The model uses time-decayed historical results, separate home/away attack and defence rates, opponent adjustment, shrinkage toward the competition average, a results-derived Elo home advantage, and a Dixon-Coles correction for low scores. Its goal distribution drives 1X2, exact-score, totals, both-teams-to-score, and double-chance probabilities. Each distinct input/schedule state is saved as an immutable snapshot. The probability ranking considers 1X2, both-teams-to-score, and over/under 1.5 or 2.5 goals; double chance and extreme goal lines are excluded. When Odds API prices are available for a supported market, a separate value tip requires odds of at least 1.50 and positive model-estimated expected value. Odds coverage is limited to the provider's returned markets/events and is not a guarantee of profit.

Tap a fixture for its stored prediction explanation, recent competition form, head-to-head results, and locally calculated season table position. The fixtures screen can show all upcoming competitions discovered from the selected providers, with filters for country, competition, season, and team, and a current-season date range. It includes a highest-probability model tip for each forecasted fixture. This is a probability ranking, not a guaranteed result or betting advice.

The **StatsBomb-Historie** screen contains a separate offline sample of 34 selected 2023/24 Bundesliga matches with event-derived xG and shot counts, plus lineup-file cards and lineups. It is not a complete season. API-Football can add available statistics and near-match lineups/injuries to fixture details; provider availability varies by plan, and this supplementary data is not currently used by the upcoming-game prediction model. Missing statistics remain unavailable rather than estimated.

The **Prediction-Qualität** screen compares the previous prediction baseline with empirical, Elo, Poisson, and Dixon-Coles models. It uses chronological walk-forward forecasts, selects the time-decay half-life (180/365/730 days) and Dixon-Coles parameter by validation accuracy (log loss breaks ties), and reports the later holdout test separately with log loss, Brier score, accuracy, calibration error, and goal MAE. The selected parameters are applied to upcoming predictions after a backtest completes. Results depend on the data stored on the device; no improvement is claimed until measured on the independent test period. Predictions are not betting advice.

OpenLigaDB is community-maintained and licensed under ODbL 1.0; attribution and database obligations may apply to redistributed data. The bundled OpenFootball snapshot is pinned to revision `e6744429ee395bc86f247348c6184bb08d4eb361`; its generated manifest records file hashes, season coverage, and source counts. The bundled StatsBomb sample is pinned to open-data revision `4b73468fc5b0f1950f9f66fada70ad3a4f9327cb` and is credited in the app. StatsBomb describes this data as available for research and asks that published/shared analysis cite StatsBomb and use its logo; check its current terms and add the official media-pack logo before redistributing analyses based on it. See [docs/DATA_SOURCES.md](docs/DATA_SOURCES.md) for source details.
