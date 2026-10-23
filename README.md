# SportPredi Android

SportPredi is an Android football fixtures and prediction app. It stores data locally in Room and uses API-Football as its only football-data provider.

## Build and run

Open the repository in Android Studio with JDK 17 and Android SDK Platform 35, or run:

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

On first launch, enter the API-Football key under **Einstellungen → API-Schlüssel**. It is encrypted with AES-GCM using Android Keystore and is never written to logs or backups. Synchronization targets only API-Football IDs for the top five domestic leagues, their domestic cups, and the Champions/Europa/Conference leagues; unavailable IDs are listed in coverage instead of being replaced by similar names. Current and previous-season results are imported once and retained in Room; subsequent syncs update the last 14 days and next 14 days, and incomplete full-season imports resume on later runs. Match form and last games use local completed results across those competitions. Standings cache rows use league/season IDs and refresh every 24 hours. Each batch remains capped at 32 quota-counted requests with live `/status` and daily/per-minute headers enforcing budgets; the default local daily budget is 6,500 with a 1,000-request reserve under the stated 7,500/day subscription.

The app stores fixtures, results, predictions, evaluations, and backtest data in the private `sportpredi-local.db` database. Android migration 8→9 deliberately clears only the old football, auxiliary-provider, prediction, and backtest tables so the new API-Football IDs and model semantics start clean. It leaves encrypted credentials and other app preferences untouched. JSON backup/restore remains available; backups containing non-API-Football competition IDs are rejected.

## Predictions and limitations

The local forecasting model uses API-Football fixture results and chronological, time-decayed team/competition history. It provides 1X2 probabilities and score analysis; it does not make API calls for unused lineups, injuries, statistics, or external odds. Provider plan access, historical coverage, and league availability vary. Missing data is not fabricated.

Every upcoming fixture receives the model's 1X2 probabilities and most-likely outcome; predictions are not hidden when confidence or past accuracy is below 90%. The 90% figure is an aspirational overall 1X2 hit-rate benchmark, not a promise or an output gate. Backtests choose parameters using chronological validation data, then report overall 1X2 accuracy on a later untouched holdout and compare each candidate with the old model baseline, including the measured percentage-point difference. The UI marks the benchmark only when the holdout actually measures at least 90% on a sufficient sample. Historical backtests can still fall short or have too few holdout results.

Prediction snapshots are immutable. The API-only data and model migration uses a new model version; previous local predictions/backtests are cleared by the explicit database migration, while exported backup files remain user-controlled. Predictions are not betting advice.
