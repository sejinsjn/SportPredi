package com.sportpredi

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiOnlyMigrationTest {
    @Test
    fun migrationClearsOnlyRetiredFootballAndPredictionTables() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            if (method.name == "execSQL") statements += arguments?.get(0) as String
            null
        } as SupportSQLiteDatabase

        SportPrediDatabase.MIGRATION_8_9.migrate(database)

        assertEquals(
            listOf(
                "DELETE FROM prediction_evaluations",
                "DELETE FROM prediction_snapshots",
                "DELETE FROM backtest_predictions",
                "DELETE FROM backtest_model_metrics",
                "DELETE FROM backtest_runs",
                "DROP TABLE IF EXISTS additional_fixture_data",
                "DROP TABLE IF EXISTS historical_match_statistics",
                "DELETE FROM fixtures",
                "DELETE FROM teams",
                "DELETE FROM seasons",
                "DELETE FROM competitions",
                "DELETE FROM sync_metadata",
            ),
            statements,
        )
        assertTrue(statements.none { it.contains("preferences", ignoreCase = true) })
        assertTrue(statements.none { it.contains("credentials", ignoreCase = true) })
    }
}
