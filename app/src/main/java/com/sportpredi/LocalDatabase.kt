package com.sportpredi

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "competitions",
    indices = [Index(value = ["providerCode"], unique = true)],
)
data class CompetitionEntity(
    @PrimaryKey val providerCode: String,
    val providerId: Int,
    val name: String,
    val country: String,
    val updatedAt: String,
)

@Entity(
    tableName = "seasons",
    indices = [Index(value = ["competitionCode", "seasonYear"])],
)
data class SeasonEntity(
    @PrimaryKey val id: String,
    val competitionCode: String,
    val providerLeagueId: Int,
    val seasonYear: Int,
    val isCurrent: Boolean,
    val lastSuccessfulFetch: String?,
    val lastProviderCheck: String?,
    val providerRevision: String?,
)

@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey val providerTeamId: Int,
    val name: String,
)

@Entity(
    tableName = "fixtures",
    indices = [
        Index(value = ["competitionCode", "seasonId", "matchDate"]),
        Index(value = ["homeTeamId"]),
        Index(value = ["awayTeamId"]),
    ],
)
data class FixtureEntity(
    @PrimaryKey val providerFixtureId: Long,
    val competitionCode: String,
    val seasonId: String,
    val homeTeamId: Int?,
    val awayTeamId: Int?,
    val kickoffAt: String?,
    val matchDate: String,
    val status: String,
    val matchday: Int?,
    val stage: String?,
    val venue: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val halftimeHomeScore: Int?,
    val halftimeAwayScore: Int?,
    val fullTimeResult: Boolean,
    val postponedOrCancelled: Boolean,
    @ColumnInfo(defaultValue = "0") val rescheduled: Boolean = false,
    val updatedAt: String,
)

@Entity(
    tableName = "prediction_snapshots",
    indices = [Index(value = ["providerFixtureId", "modelVersion", "inputFingerprint"], unique = true)],
)
data class PredictionSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerFixtureId: Long,
    val createdAt: String,
    val trigger: String,
    val inputFingerprint: String,
    val homeWinProbability: Double,
    val drawProbability: Double,
    val awayWinProbability: Double,
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val modelVersion: String,
    val analysisJson: String? = null,
)

@Entity(
    tableName = "historical_match_statistics",
    indices = [Index(value = ["competitionId", "seasonId", "matchDate"])],
)
data class HistoricalMatchStatisticsEntity(
    @PrimaryKey val providerMatchId: Int,
    val competitionId: Int,
    val seasonId: Int,
    val competitionName: String,
    val country: String,
    val seasonName: String,
    val matchDate: String,
    val kickoffAt: String?,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val homeXg: Double?,
    val awayXg: Double?,
    val homeShots: Int?,
    val awayShots: Int?,
    val homeYellowCards: Int?,
    val awayYellowCards: Int?,
    val homeRedCards: Int?,
    val awayRedCards: Int?,
    val homeLineupJson: String,
    val awayLineupJson: String,
    val sourceRevision: String,
)

@Entity(tableName = "additional_fixture_data")
data class AdditionalFixtureDataEntity(
    @PrimaryKey val localFixtureId: Long,
    val oddsEventId: String?,
    val oddsJson: String?,
    val apiFootballFixtureId: Int?,
    val homeXg: Double?,
    val awayXg: Double?,
    val homeShots: Int?,
    val awayShots: Int?,
    val homePossession: Double?,
    val awayPossession: Double?,
    val lineupsJson: String?,
    val injuriesJson: String?,
    val updatedAt: String,
)

@Entity(tableName = "prediction_evaluations")
data class PredictionEvaluationEntity(
    @PrimaryKey val predictionSnapshotId: Long,
    val actualOutcome: String,
    val evaluatedAt: String,
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "backtest_runs")
data class BacktestRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: String,
    val sampleSize: Int,
    val datasetFingerprint: String,
    val trainEndDate: String? = null,
    val validationEndDate: String? = null,
    val selectedDixonColesRho: Double? = null,
    val selectedHalfLifeDays: Double? = null,
)

@Entity(
    tableName = "backtest_model_metrics",
    indices = [Index(value = ["runId", "modelVersion", "scope"], unique = true)],
)
data class BacktestModelMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val modelVersion: String,
    val sampleSize: Int,
    val logLoss: Double?,
    val brierScore: Double?,
    val accuracy: Double?,
    val meanAbsoluteGoalError: Double?,
    @ColumnInfo(defaultValue = "'all'") val scope: String = "all",
    val calibrationError: Double? = null,
)

@Entity(
    tableName = "backtest_predictions",
    indices = [Index(value = ["runId", "providerFixtureId", "modelVersion"], unique = true)],
)
data class BacktestPredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val providerFixtureId: Long,
    val modelVersion: String,
    val forecastedAt: String,
    val informationCutoff: String,
    val homeWinProbability: Double,
    val drawProbability: Double,
    val awayWinProbability: Double,
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val actualOutcome: String,
    val actualHomeGoals: Int,
    val actualAwayGoals: Int,
)

data class FixtureDisplay(
    val providerFixtureId: Long,
    val kickoffAt: String?,
    val matchDate: String,
    val status: String,
    val postponedOrCancelled: Boolean,
    val rescheduled: Boolean,
    val venue: String?,
    val matchday: Int?,
    val competitionName: String?,
    val country: String?,
    val seasonYear: Int?,
    val homeTeamName: String?,
    val awayTeamName: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val homeWinProbability: Double?,
    val drawProbability: Double?,
    val awayWinProbability: Double?,
    val expectedHomeGoals: Double?,
    val expectedAwayGoals: Double?,
    val analysisJson: String?,
    val oddsJson: String?,
    val homeXg: Double?,
    val awayXg: Double?,
    val homeShots: Int?,
    val awayShots: Int?,
    val homePossession: Double?,
    val awayPossession: Double?,
    val lineupsJson: String?,
    val injuriesJson: String?,
)

data class SeasonCoverageRow(
    val competitionCode: String,
    val competitionName: String,
    val country: String,
    val seasonYear: Int,
    val lastSuccessfulFetch: String?,
    val sourceRevision: String?,
    val fixtureCount: Int,
    val completedCount: Int,
    val linkedTeamCount: Int,
    val halftimeScoreCount: Int,
    val firstMatchDate: String?,
    val lastMatchDate: String?,
)

@Dao
interface SportPrediDao {
    @Upsert
    suspend fun upsertCompetitions(items: List<CompetitionEntity>)

    @Upsert
    suspend fun upsertSeasons(items: List<SeasonEntity>)

    @Upsert
    suspend fun upsertTeams(items: List<TeamEntity>)

    @Upsert
    suspend fun upsertFixtures(items: List<FixtureEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFixturesIfAbsent(items: List<FixtureEntity>)

    @Upsert
    suspend fun upsertSeason(item: SeasonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrediction(item: PredictionSnapshotEntity): Long

    @Insert
    suspend fun insertPredictions(items: List<PredictionSnapshotEntity>)

    @Upsert
    suspend fun insertPredictionEvaluations(items: List<PredictionEvaluationEntity>)

    @Upsert
    suspend fun upsertHistoricalMatchStatistics(items: List<HistoricalMatchStatisticsEntity>)

    @Upsert
    suspend fun upsertAdditionalFixtureData(items: List<AdditionalFixtureDataEntity>)

    @Query("SELECT * FROM additional_fixture_data WHERE localFixtureId = :fixtureId LIMIT 1")
    suspend fun additionalFixtureData(fixtureId: Long): AdditionalFixtureDataEntity?

    @Query("SELECT * FROM additional_fixture_data ORDER BY localFixtureId")
    suspend fun allAdditionalFixtureData(): List<AdditionalFixtureDataEntity>

    @Query("DELETE FROM additional_fixture_data")
    suspend fun clearAdditionalFixtureData()

    @Query("DELETE FROM historical_match_statistics")
    suspend fun clearHistoricalMatchStatistics()

    @Query(
        """
        SELECT * FROM historical_match_statistics
        ORDER BY matchDate DESC, kickoffAt DESC
        """
    )
    suspend fun allHistoricalMatchStatistics(): List<HistoricalMatchStatisticsEntity>

    @Insert
    suspend fun insertBacktestRun(item: BacktestRunEntity): Long

    @Insert
    suspend fun insertBacktestMetrics(items: List<BacktestModelMetricEntity>)

    @Insert
    suspend fun insertBacktestPredictions(items: List<BacktestPredictionEntity>)

    @Query("SELECT * FROM competitions ORDER BY country, name")
    suspend fun allCompetitions(): List<CompetitionEntity>

    @Query("SELECT * FROM seasons ORDER BY competitionCode, seasonYear DESC")
    suspend fun allSeasons(): List<SeasonEntity>

    @Query(
        """
        SELECT s.competitionCode AS competitionCode, c.name AS competitionName,
               c.country AS country, s.seasonYear AS seasonYear,
               s.lastSuccessfulFetch AS lastSuccessfulFetch,
               s.providerRevision AS sourceRevision,
               COUNT(f.providerFixtureId) AS fixtureCount,
               COALESCE(SUM(CASE WHEN f.fullTimeResult = 1
                   AND f.homeScore IS NOT NULL AND f.awayScore IS NOT NULL
                   THEN 1 ELSE 0 END), 0) AS completedCount,
               COALESCE(SUM(CASE WHEN f.homeTeamId IS NOT NULL AND f.awayTeamId IS NOT NULL
                   THEN 1 ELSE 0 END), 0) AS linkedTeamCount,
               COALESCE(SUM(CASE WHEN f.halftimeHomeScore IS NOT NULL
                   AND f.halftimeAwayScore IS NOT NULL THEN 1 ELSE 0 END), 0) AS halftimeScoreCount,
               MIN(f.matchDate) AS firstMatchDate, MAX(f.matchDate) AS lastMatchDate
        FROM seasons s
        JOIN competitions c ON c.providerCode = s.competitionCode
        LEFT JOIN fixtures f ON f.seasonId = s.id
        GROUP BY s.id
        ORDER BY c.country, c.name, s.seasonYear DESC
        """
    )
    suspend fun seasonCoverage(): List<SeasonCoverageRow>

    @Query("SELECT * FROM seasons WHERE id = :id LIMIT 1")
    suspend fun season(id: String): SeasonEntity?

    @Query("SELECT * FROM teams ORDER BY name")
    suspend fun allTeams(): List<TeamEntity>

    @Query("SELECT * FROM fixtures WHERE seasonId = :seasonId")
    suspend fun fixturesForSeason(seasonId: String): List<FixtureEntity>

    @Query("SELECT * FROM fixtures WHERE providerFixtureId = :fixtureId LIMIT 1")
    suspend fun fixture(fixtureId: Long): FixtureEntity?

    @Query("SELECT * FROM teams WHERE providerTeamId = :teamId LIMIT 1")
    suspend fun team(teamId: Int): TeamEntity?

    @Query("SELECT * FROM competitions WHERE providerCode = :code LIMIT 1")
    suspend fun competition(code: String): CompetitionEntity?

    @Query(
        """
        SELECT * FROM fixtures
        WHERE competitionCode = :competitionCode AND matchDate < :beforeDate
          AND fullTimeResult = 1 AND homeScore IS NOT NULL AND awayScore IS NOT NULL
          AND (
            (homeTeamId = :homeTeamId AND awayTeamId = :awayTeamId)
            OR (homeTeamId = :awayTeamId AND awayTeamId = :homeTeamId)
          )
        ORDER BY matchDate DESC, kickoffAt DESC
        LIMIT :limit
        """
    )
    suspend fun headToHead(
        competitionCode: String,
        homeTeamId: Int,
        awayTeamId: Int,
        beforeDate: String,
        limit: Int,
    ): List<FixtureEntity>

    @Query("SELECT * FROM fixtures ORDER BY matchDate, kickoffAt")
    suspend fun allFixtures(): List<FixtureEntity>

    @Query("SELECT * FROM prediction_snapshots ORDER BY createdAt")
    suspend fun allPredictions(): List<PredictionSnapshotEntity>

    @Query("SELECT * FROM prediction_evaluations ORDER BY predictionSnapshotId")
    suspend fun allPredictionEvaluations(): List<PredictionEvaluationEntity>

    @Query("SELECT * FROM sync_metadata ORDER BY `key`")
    suspend fun allSyncMetadata(): List<SyncMetadataEntity>

    @Query("SELECT * FROM backtest_runs ORDER BY createdAt DESC")
    suspend fun allBacktestRuns(): List<BacktestRunEntity>

    @Query("SELECT * FROM backtest_model_metrics ORDER BY runId, modelVersion")
    suspend fun allBacktestMetrics(): List<BacktestModelMetricEntity>

    @Query("SELECT * FROM backtest_predictions ORDER BY runId, providerFixtureId, modelVersion")
    suspend fun allBacktestPredictions(): List<BacktestPredictionEntity>

    @Query("SELECT * FROM sync_metadata WHERE `key` = :key LIMIT 1")
    suspend fun syncMetadata(key: String): SyncMetadataEntity?

    @Upsert
    suspend fun upsertSyncMetadata(item: SyncMetadataEntity)

    @Query(
        """
        SELECT f.providerFixtureId, f.kickoffAt, f.matchDate, f.status,
               f.postponedOrCancelled, f.rescheduled, f.venue, f.matchday,
               c.name AS competitionName, c.country,
               s.seasonYear,
               home.name AS homeTeamName, away.name AS awayTeamName,
               f.homeScore, f.awayScore,
               p.homeWinProbability, p.drawProbability, p.awayWinProbability,
               p.expectedHomeGoals, p.expectedAwayGoals, p.analysisJson,
               a.oddsJson, a.homeXg, a.awayXg, a.homeShots, a.awayShots,
               a.homePossession, a.awayPossession, a.lineupsJson, a.injuriesJson
        FROM fixtures f
        LEFT JOIN competitions c ON c.providerCode = f.competitionCode
        LEFT JOIN seasons s ON s.id = f.seasonId
        LEFT JOIN teams home ON home.providerTeamId = f.homeTeamId
        LEFT JOIN teams away ON away.providerTeamId = f.awayTeamId
        LEFT JOIN additional_fixture_data a ON a.localFixtureId = f.providerFixtureId
        LEFT JOIN prediction_snapshots p
          ON p.id = (
            SELECT p2.id FROM prediction_snapshots p2
            WHERE p2.providerFixtureId = f.providerFixtureId
            ORDER BY p2.createdAt DESC LIMIT 1
          )
        WHERE f.matchDate BETWEEN :fromDate AND :toDate
          AND (f.fullTimeResult = 0 OR f.postponedOrCancelled = 1)
        ORDER BY f.matchDate, f.kickoffAt, c.name
        """
    )
    fun observeUpcoming(fromDate: String, toDate: String): Flow<List<FixtureDisplay>>

    @Query(
        """
        SELECT * FROM fixtures
        WHERE matchDate >= :fromDate AND fullTimeResult = 0
          AND postponedOrCancelled = 0
          AND status IN ('SCHEDULED', 'TIMED')
          AND homeTeamId IS NOT NULL AND awayTeamId IS NOT NULL
        ORDER BY matchDate, kickoffAt
        """
    )
    suspend fun futureFixtures(fromDate: String): List<FixtureEntity>

    @Query(
        """
        SELECT * FROM fixtures
        WHERE competitionCode = :competitionCode
          AND fullTimeResult = 1 AND matchDate < :beforeDate
          AND homeScore IS NOT NULL AND awayScore IS NOT NULL
          AND (homeTeamId = :teamId OR awayTeamId = :teamId)
        ORDER BY matchDate DESC, kickoffAt DESC
        LIMIT :limit
        """
    )
    suspend fun teamHistory(
        competitionCode: String,
        teamId: Int,
        beforeDate: String,
        limit: Int,
    ): List<FixtureEntity>

    @Query("SELECT * FROM fixtures WHERE fullTimeResult = 1 AND homeScore IS NOT NULL AND awayScore IS NOT NULL ORDER BY competitionCode, matchDate, providerFixtureId")
    suspend fun completedFixtures(): List<FixtureEntity>

    @Query(
        """
        INSERT OR REPLACE INTO prediction_evaluations
            (predictionSnapshotId, actualOutcome, evaluatedAt)
        SELECT p.id,
               CASE WHEN f.homeScore > f.awayScore THEN 'HOME'
                    WHEN f.homeScore < f.awayScore THEN 'AWAY'
                    ELSE 'DRAW' END,
               :evaluatedAt
        FROM prediction_snapshots p
        JOIN fixtures f ON f.providerFixtureId = p.providerFixtureId
        WHERE f.seasonId = :seasonId AND f.fullTimeResult = 1
          AND f.homeScore IS NOT NULL AND f.awayScore IS NOT NULL
        """
    )
    suspend fun evaluateFinishedPredictionsForSeason(seasonId: String, evaluatedAt: String)

    @Query("SELECT * FROM prediction_snapshots WHERE providerFixtureId = :fixtureId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestPrediction(fixtureId: Long): PredictionSnapshotEntity?

    @Query("SELECT * FROM backtest_runs ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestBacktest(): BacktestRunEntity?

    @Query("UPDATE backtest_runs SET sampleSize = :sampleSize WHERE id = :runId")
    suspend fun updateBacktestRunSampleSize(runId: Long, sampleSize: Int)

    @Query("SELECT * FROM backtest_model_metrics WHERE runId = :runId ORDER BY modelVersion")
    suspend fun backtestMetrics(runId: Long): List<BacktestModelMetricEntity>

    @Query(
        """
        DELETE FROM backtest_predictions
        WHERE providerFixtureId NOT IN (SELECT providerFixtureId FROM fixtures)
        """
    )
    suspend fun removeOrphanedBacktestPredictions()

    @Query("DELETE FROM backtest_predictions")
    suspend fun clearBacktestPredictions()

    @Query("DELETE FROM backtest_model_metrics")
    suspend fun clearBacktestMetrics()

    @Query("DELETE FROM backtest_runs")
    suspend fun clearBacktestRuns()

    @Query("DELETE FROM prediction_snapshots")
    suspend fun clearPredictions()

    @Query("DELETE FROM prediction_evaluations")
    suspend fun clearPredictionEvaluations()

    @Query("DELETE FROM fixtures")
    suspend fun clearFixtures()

    @Query("DELETE FROM teams")
    suspend fun clearTeams()

    @Query("DELETE FROM seasons")
    suspend fun clearSeasons()

    @Query("DELETE FROM competitions")
    suspend fun clearCompetitions()

    @Query("DELETE FROM sync_metadata")
    suspend fun clearSyncMetadata()
}

@Database(
    entities = [
        CompetitionEntity::class,
        SeasonEntity::class,
        TeamEntity::class,
        FixtureEntity::class,
        PredictionSnapshotEntity::class,
        PredictionEvaluationEntity::class,
        SyncMetadataEntity::class,
        HistoricalMatchStatisticsEntity::class,
        BacktestRunEntity::class,
        BacktestModelMetricEntity::class,
        BacktestPredictionEntity::class,
        AdditionalFixtureDataEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class SportPrediDatabase : RoomDatabase() {
    abstract fun sportPrediDao(): SportPrediDao

    companion object {
        @Volatile
        private var instance: SportPrediDatabase? = null

        fun get(context: Context): SportPrediDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SportPrediDatabase::class.java,
                    "sportpredi-local.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                ).build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prediction_evaluations (
                        predictionSnapshotId INTEGER NOT NULL,
                        actualOutcome TEXT NOT NULL,
                        evaluatedAt TEXT NOT NULL,
                        PRIMARY KEY(predictionSnapshotId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO prediction_evaluations
                        (predictionSnapshotId, actualOutcome, evaluatedAt)
                    SELECT id, actualOutcome, evaluatedAt
                    FROM prediction_snapshots
                    WHERE actualOutcome IS NOT NULL AND evaluatedAt IS NOT NULL
                    """.trimIndent(),
                )
                database.execSQL("DROP INDEX IF EXISTS index_prediction_snapshots_providerFixtureId_inputFingerprint")
                database.execSQL("ALTER TABLE prediction_snapshots RENAME TO prediction_snapshots_v1")
                database.execSQL(
                    """
                    CREATE TABLE prediction_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        providerFixtureId INTEGER NOT NULL,
                        createdAt TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        inputFingerprint TEXT NOT NULL,
                        homeWinProbability REAL NOT NULL,
                        drawProbability REAL NOT NULL,
                        awayWinProbability REAL NOT NULL,
                        expectedHomeGoals REAL NOT NULL,
                        expectedAwayGoals REAL NOT NULL,
                        modelVersion TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO prediction_snapshots (
                        id, providerFixtureId, createdAt, trigger, inputFingerprint,
                        homeWinProbability, drawProbability, awayWinProbability,
                        expectedHomeGoals, expectedAwayGoals, modelVersion
                    )
                    SELECT id, providerFixtureId, createdAt, trigger, inputFingerprint,
                        homeWinProbability, drawProbability, awayWinProbability,
                        expectedHomeGoals, expectedAwayGoals, modelVersion
                    FROM prediction_snapshots_v1
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE prediction_snapshots_v1")
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX index_prediction_snapshots_providerFixtureId_modelVersion_inputFingerprint
                    ON prediction_snapshots(providerFixtureId, modelVersion, inputFingerprint)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE fixtures ADD COLUMN rescheduled INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE prediction_snapshots ADD COLUMN analysisJson TEXT")
                database.execSQL(
                    "ALTER TABLE backtest_model_metrics ADD COLUMN scope TEXT NOT NULL DEFAULT 'all'",
                )
                database.execSQL("ALTER TABLE backtest_model_metrics ADD COLUMN calibrationError REAL")
                database.execSQL(
                    "DROP INDEX IF EXISTS index_backtest_model_metrics_runId_modelVersion",
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX index_backtest_model_metrics_runId_modelVersion_scope
                    ON backtest_model_metrics(runId, modelVersion, scope)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE backtest_runs ADD COLUMN trainEndDate TEXT")
                database.execSQL("ALTER TABLE backtest_runs ADD COLUMN validationEndDate TEXT")
                database.execSQL("ALTER TABLE backtest_runs ADD COLUMN selectedDixonColesRho REAL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS historical_match_statistics (
                        providerMatchId INTEGER NOT NULL,
                        competitionId INTEGER NOT NULL,
                        seasonId INTEGER NOT NULL,
                        competitionName TEXT NOT NULL,
                        country TEXT NOT NULL,
                        seasonName TEXT NOT NULL,
                        matchDate TEXT NOT NULL,
                        kickoffAt TEXT,
                        homeTeam TEXT NOT NULL,
                        awayTeam TEXT NOT NULL,
                        homeScore INTEGER,
                        awayScore INTEGER,
                        homeXg REAL,
                        awayXg REAL,
                        homeShots INTEGER,
                        awayShots INTEGER,
                        homeYellowCards INTEGER,
                        awayYellowCards INTEGER,
                        homeRedCards INTEGER,
                        awayRedCards INTEGER,
                        homeLineupJson TEXT NOT NULL,
                        awayLineupJson TEXT NOT NULL,
                        sourceRevision TEXT NOT NULL,
                        PRIMARY KEY(providerMatchId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_historical_match_statistics_competitionId_seasonId_matchDate
                    ON historical_match_statistics(competitionId, seasonId, matchDate)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE backtest_runs ADD COLUMN selectedHalfLifeDays REAL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS additional_fixture_data (
                        localFixtureId INTEGER NOT NULL,
                        oddsEventId TEXT,
                        oddsJson TEXT,
                        apiFootballFixtureId INTEGER,
                        homeXg REAL,
                        awayXg REAL,
                        homeShots INTEGER,
                        awayShots INTEGER,
                        homePossession REAL,
                        awayPossession REAL,
                        lineupsJson TEXT,
                        injuriesJson TEXT,
                        updatedAt TEXT NOT NULL,
                        PRIMARY KEY(localFixtureId)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
