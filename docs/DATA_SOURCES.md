# Football data source

API-Football is SportPredi's sole football-data provider. The Android app uses the API's league catalog, fixture date-range lookup, league/season fixture lookup, and league/season standings. Stable provider fixture, league, season, and team IDs are retained in Room and connect schedules, results, forms, and standings without name-based ID matching.

The sync is intentionally restricted to these canonical catalog IDs; a similarly named competition with another ID is never substituted:

| Competition set | API-Football IDs |
|---|---|
| England, Spain, Germany, Italy, France top leagues | `39`, `140`, `78`, `135`, `61` |
| Their domestic cups (same country order) | `45`, `143`, `81`, `137`, `66` |
| UEFA Champions League, Europa League, Conference League | `2`, `3`, `848` |

| API endpoint | Local use | Limits and notes |
|---|---|---|
| `GET /leagues?current=true` | Discovers requested competitions and current/previous season years. | Missing requested IDs are recorded and shown in app coverage; there is no name-based fallback. |
| `GET /fixtures?league=…&season=…` | Initial full current-season and previous-season result/schedule import. | A successfully imported season is marked complete in Room and is not fetched as history again. |
| `GET /fixtures?league=…&season=…&from=…&to=…` | Refreshes the last 14 days of results and next 14 days of fixtures. | Up to 13 current competitions per bounded sync; fixture IDs remain the local idempotency key. |
| `GET /standings?league=…&season=…` | Supplies season standings and API-Football's recent form for teams in those standings. | Up to 10 per synchronization; normalized rows are cached by API league/season for 24 hours. Empty provider responses are cached too, so cups or unavailable tables are shown explicitly without repeated calls. |
| `GET /fixtures?league=…&season=…` | Loads the latest available previous season once, to provide early-season team history. | Incomplete imports retry; successful results stay in the local Room database. |

Each synchronization uses batches of at most 32 quota-counted requests. A `/status` call (excluded from the daily quota) supplies current usage and the plan's daily limit; response headers supply the daily and per-minute limits/remaining counts. The batch prioritizes current schedule/results and missing full-season imports; stale standings refreshes use only remaining capacity. The default app budget is 6,500 daily requests with a configurable 1,000-request reserve, plus one per-minute request held in reserve. Every batch remains bounded by the live provider-reported limits. WorkManager runs every 15 minutes with a matching local cooldown; manual sync can bypass only the cooldown. Provider errors, plan restrictions, or missing catalog IDs are surfaced rather than hidden.

Fixture history and schedules persist in Room and are updated idempotently by provider fixture ID. Completed full-season imports are not downloaded again; subsequent requests touch only the recent-result/upcoming-fixture window. Team form and the latest five match details are computed from this locally stored history across all supported competitions. Standings/form cache rows are derived provider data stored in Room and can be safely rebuilt by synchronization; match details show an explicit not-yet-synchronized or provider-unavailable table state otherwise.

Statistics, odds, lineups, and injury endpoints are not called: those data are not currently inputs to the forecasting model, and fetching unused fields would consume quota without improving the implemented calculation. There are no bundled OpenFootball or StatsBomb assets and no OpenLigaDB or The Odds API requests.

## Prediction evidence

All upcoming games receive 1X2 probabilities and a most-likely outcome regardless of confidence or historical hit rate. Backtests split chronologically into training, validation, and a later untouched holdout. Parameter selection uses validation only. The holdout's overall 1X2 hit rate counts the most-probable outcome across all holdout games and is compared with the old model baseline, with the measured percentage-point change reported even when negative. 90% is an aspirational benchmark, not a prediction filter or guaranteed result; the UI marks it achieved only from actual holdout measurements with at least 30 cases.
