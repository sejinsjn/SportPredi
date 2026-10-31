package com.sportpredi

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.time.Instant

private data class LocalBackupDocument(
    val formatVersion: Int,
    val exportedAt: String,
    val competitions: List<CompetitionEntity>?,
    val seasons: List<SeasonEntity>?,
    val teams: List<TeamEntity>?,
    val fixtures: List<FixtureEntity>?,
    val predictions: List<PredictionSnapshotEntity>?,
    val predictionEvaluations: List<PredictionEvaluationEntity>?,
    val syncMetadata: List<SyncMetadataEntity>?,
    val backtestRuns: List<BacktestRunEntity>?,
    val backtestMetrics: List<BacktestModelMetricEntity>?,
    val backtestPredictions: List<BacktestPredictionEntity>?,
    val apiFootballOddsCache: List<ApiFootballOddsCacheEntity>?,
    val apiFootballOddsSnapshots: List<ApiFootballOddsSnapshotEntity>?,
)

class LocalBackupRepository(private val database: SportPrediDatabase) {
    private val dao = database.sportPrediDao()
    private val gson = Gson()

    suspend fun exportJson(): String = database.withTransaction {
        gson.toJson(
            LocalBackupDocument(
                formatVersion = FORMAT_VERSION,
                exportedAt = Instant.now().toString(),
                competitions = dao.allCompetitions(),
                seasons = dao.allSeasons(),
                teams = dao.allTeams(),
                fixtures = dao.allFixtures(),
                predictions = dao.allPredictions(),
                predictionEvaluations = dao.allPredictionEvaluations(),
                syncMetadata = dao.allSyncMetadata(),
                backtestRuns = dao.allBacktestRuns(),
                backtestMetrics = dao.allBacktestMetrics(),
                backtestPredictions = dao.allBacktestPredictions(),
                apiFootballOddsCache = dao.allApiFootballOdds(),
                apiFootballOddsSnapshots = dao.allApiFootballOddsSnapshots(),
            ),
        )
    }

    suspend fun importJson(json: String) {
        val document = try {
            gson.fromJson(json, LocalBackupDocument::class.java)
        } catch (exception: JsonParseException) {
            throw IllegalArgumentException("Die Sicherungsdatei ist kein gültiges SportPredi-JSON.", exception)
        } ?: throw IllegalArgumentException("Die Sicherungsdatei ist leer.")

        require(document.formatVersion in MINIMUM_SUPPORTED_FORMAT..FORMAT_VERSION) {
            "Nicht unterstützte Sicherungsversion: ${document.formatVersion}."
        }
        val competitions = document.competitions
            ?: throw IllegalArgumentException("Wettbewerbsdaten fehlen in der Sicherung.")
        val seasons = document.seasons
            ?: throw IllegalArgumentException("Saisondaten fehlen in der Sicherung.")
        val teams = document.teams
            ?: throw IllegalArgumentException("Mannschaftsdaten fehlen in der Sicherung.")
        val fixtures = document.fixtures
            ?: throw IllegalArgumentException("Spieldaten fehlen in der Sicherung.")
        val predictions = document.predictions
            ?: throw IllegalArgumentException("Prognosedaten fehlen in der Sicherung.")
        val predictionEvaluations = document.predictionEvaluations
            ?: throw IllegalArgumentException("Prognoseauswertungen fehlen in der Sicherung.")
        val syncMetadata = document.syncMetadata
            ?: throw IllegalArgumentException("Synchronisierungsdaten fehlen in der Sicherung.")
        val backtestRuns = document.backtestRuns
            ?: throw IllegalArgumentException("Backtest-Läufe fehlen in der Sicherung.")
        val backtestMetrics = document.backtestMetrics
            ?: throw IllegalArgumentException("Backtest-Metriken fehlen in der Sicherung.")
        val backtestPredictions = document.backtestPredictions
            ?: throw IllegalArgumentException("Backtest-Prognosen fehlen in der Sicherung.")
        if (document.formatVersion >= ODDS_BACKUP_FORMAT_VERSION) {
            require(document.apiFootballOddsCache != null && document.apiFootballOddsSnapshots != null) {
                "Quotendaten fehlen in der Sicherung."
            }
        }
        val apiFootballOddsCache = document.apiFootballOddsCache.orEmpty()
        val apiFootballOddsSnapshots = document.apiFootballOddsSnapshots.orEmpty()
        validate(
            competitions,
            seasons,
            teams,
            fixtures,
            predictions,
            predictionEvaluations,
            backtestRuns,
            backtestMetrics.map { metric ->
                if (metric.scope.isNullOrBlank()) metric.copy(scope = "all") else metric
            },
            backtestPredictions,
            apiFootballOddsCache,
            apiFootballOddsSnapshots,
        )

        database.withTransaction {
            val localRequestBudget = dao.syncMetadata(API_FOOTBALL_DAILY_REQUESTS_KEY)
            dao.clearBacktestPredictions()
            dao.clearBacktestMetrics()
            dao.clearBacktestRuns()
            dao.clearApiFootballOddsSnapshots()
            dao.clearApiFootballOddsCache()
            dao.clearPredictionEvaluations()
            dao.clearPredictions()
            dao.clearFixtures()
            dao.clearTeams()
            dao.clearSeasons()
            dao.clearCompetitions()
            dao.clearSyncMetadata()
            dao.upsertCompetitions(competitions)
            dao.upsertSeasons(seasons)
            dao.upsertTeams(teams)
            dao.upsertFixtures(fixtures)
            dao.insertPredictions(predictions)
            dao.insertPredictionEvaluations(predictionEvaluations)
            syncMetadata.filterNot { it.key == API_FOOTBALL_DAILY_REQUESTS_KEY }
                .forEach { dao.upsertSyncMetadata(it) }
            localRequestBudget?.let { dao.upsertSyncMetadata(it) }
            backtestRuns.forEach { dao.insertBacktestRun(it) }
            dao.insertBacktestMetrics(
                backtestMetrics.map { metric ->
                    if (metric.scope.isNullOrBlank()) metric.copy(scope = "all") else metric
                },
            )
            dao.insertBacktestPredictions(backtestPredictions)
            dao.upsertApiFootballOddsCache(apiFootballOddsCache)
            dao.insertApiFootballOddsSnapshots(apiFootballOddsSnapshots)
        }
    }

    private fun validate(
        competitions: List<CompetitionEntity>,
        seasons: List<SeasonEntity>,
        teams: List<TeamEntity>,
        fixtures: List<FixtureEntity>,
        predictions: List<PredictionSnapshotEntity>,
        predictionEvaluations: List<PredictionEvaluationEntity>,
        runs: List<BacktestRunEntity>,
        metrics: List<BacktestModelMetricEntity>,
        backtestPredictions: List<BacktestPredictionEntity>,
        apiFootballOddsCache: List<ApiFootballOddsCacheEntity>,
        apiFootballOddsSnapshots: List<ApiFootballOddsSnapshotEntity>,
    ) {
        require(competitions.map { it.providerCode }.toSet().size == competitions.size) {
            "Doppelte Wettbewerbe in der Sicherung."
        }
        require(seasons.map { it.id }.toSet().size == seasons.size) {
            "Doppelte Spielzeiten in der Sicherung."
        }
        require(teams.map { it.providerTeamId }.toSet().size == teams.size) {
            "Doppelte Mannschaften in der Sicherung."
        }
        require(fixtures.map { it.providerFixtureId }.toSet().size == fixtures.size) {
            "Doppelte Spiele in der Sicherung."
        }
        require(predictions.map { it.id }.toSet().size == predictions.size) {
            "Doppelte Prognose-Snapshots in der Sicherung."
        }
        require(predictions.all { it.id > 0 }) {
            "Ungültige Snapshot-ID in der Sicherung."
        }
        require(predictionEvaluations.map { it.predictionSnapshotId }.toSet().size == predictionEvaluations.size) {
            "Doppelte Prognoseauswertungen in der Sicherung."
        }
        require(runs.map { it.id }.toSet().size == runs.size && runs.all { it.id > 0 }) {
            "Ungültige oder doppelte Backtest-Läufe in der Sicherung."
        }
        require(metrics.all { it.id > 0 } &&
            metrics.map { Triple(it.runId, it.modelVersion, it.scope) }.toSet().size == metrics.size
        ) {
            "Ungültige oder doppelte Backtest-Metriken in der Sicherung."
        }
        require(backtestPredictions.all { it.id > 0 } &&
            backtestPredictions.map { Triple(it.runId, it.providerFixtureId, it.modelVersion) }
                .toSet().size == backtestPredictions.size
        ) {
            "Ungültige oder doppelte Backtest-Prognosen in der Sicherung."
        }
        require(competitions.all { it.providerCode.startsWith("api-football:") }) {
            "Die Sicherung enthaelt Daten aus einer nicht mehr verwendeten Quelle; " +
                "API-Football ist die einzige zulaessige Quelle."
        }
        val competitionIds = competitions.map { it.providerCode }.toSet()
        val seasonIds = seasons.map { it.id }.toSet()
        val teamIds = teams.map { it.providerTeamId }.toSet()
        val fixtureIds = fixtures.map { it.providerFixtureId }.toSet()
        val runIds = runs.map { it.id }.toSet()
        require(seasons.all { it.competitionCode in competitionIds }) {
            "Eine Spielzeit verweist auf einen unbekannten Wettbewerb."
        }
        require(fixtures.all {
            it.competitionCode in competitionIds &&
                it.seasonId in seasonIds &&
                (it.homeTeamId == null || it.homeTeamId in teamIds) &&
                (it.awayTeamId == null || it.awayTeamId in teamIds)
        }) {
            "Ein Spiel verweist auf einen unbekannten Wettbewerb, eine Saison oder Mannschaft."
        }
        require(predictions.all { it.providerFixtureId in fixtureIds }) {
            "Ein Prognose-Snapshot verweist auf ein unbekanntes Spiel."
        }
        require(apiFootballOddsCache.map { it.providerFixtureId }.toSet().size == apiFootballOddsCache.size) {
            "Doppelte aktuelle Quoten in der Sicherung."
        }
        require(
            apiFootballOddsSnapshots.map { it.providerFixtureId to it.fetchedAt }.toSet().size ==
                apiFootballOddsSnapshots.size,
        ) {
            "Doppelte Quoten-Snapshots in der Sicherung."
        }
        require(apiFootballOddsCache.all { it.providerFixtureId in fixtureIds } &&
            apiFootballOddsSnapshots.all { it.providerFixtureId in fixtureIds }
        ) {
            "Quotendaten verweisen auf ein unbekanntes Spiel."
        }
        val predictionIds = predictions.map { it.id }.toSet()
        require(predictionEvaluations.all { it.predictionSnapshotId in predictionIds }) {
            "Eine Prognoseauswertung verweist auf einen unbekannten Snapshot."
        }
        require(
            metrics.all { it.runId in runIds } &&
                backtestPredictions.all {
                    it.runId in runIds && it.providerFixtureId in fixtureIds
                },
        ) {
            "Backtest-Daten verweisen auf einen unbekannten Lauf."
        }
    }

    companion object {
        private const val MINIMUM_SUPPORTED_FORMAT = 2
        private const val FORMAT_VERSION = 7
        private const val ODDS_BACKUP_FORMAT_VERSION = 7
        private const val API_FOOTBALL_DAILY_REQUESTS_KEY = "apiFootballRequestsToday"
    }
}
