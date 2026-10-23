package com.sportpredi

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson

data class StatsBombLineupPlayer(
    val name: String,
    val jerseyNumber: Int?,
    val starter: Boolean,
    val position: String?,
)

private data class StatsBombHistoryBundle(
    val formatVersion: Int = 0,
    val sourceRevision: String = "",
    val matches: List<HistoricalMatchStatisticsEntity> = emptyList(),
)

class StatsBombHistoryImporter(private val database: SportPrediDatabase) {
    private val gson = Gson()
    private val dao = database.sportPrediDao()

    suspend fun importBundledHistory(context: Context): Int {
        val bundle = context.assets.open(ASSET_PATH).bufferedReader().use {
            gson.fromJson(it, StatsBombHistoryBundle::class.java)
        } ?: error("Der StatsBomb-Historienbestand ist leer.")
        require(
            bundle.formatVersion == 1 &&
                bundle.sourceRevision.matches(Regex("^[a-f0-9]{40}$")) &&
                bundle.matches.isNotEmpty() &&
                bundle.matches.map { it.providerMatchId }.toSet().size == bundle.matches.size &&
                bundle.matches.all {
                    it.sourceRevision == bundle.sourceRevision &&
                        it.providerMatchId > 0 &&
                        it.matchDate.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) &&
                        it.homeTeam.isNotBlank() &&
                        it.awayTeam.isNotBlank() &&
                        (it.homeXg == null || it.homeXg.isFinite() && it.homeXg >= 0.0) &&
                        (it.awayXg == null || it.awayXg.isFinite() && it.awayXg >= 0.0) &&
                        (it.homeShots == null || it.homeShots >= 0) &&
                        (it.awayShots == null || it.awayShots >= 0) &&
                        it.homeLineupJson.isNotBlank() &&
                        it.awayLineupJson.isNotBlank()
                }
        ) {
            "Der StatsBomb-Historienbestand ist unvollständig oder inkonsistent."
        }
        if (dao.syncMetadata(IMPORT_REVISION_KEY)?.value == bundle.sourceRevision) return 0

        database.withTransaction {
            dao.clearHistoricalMatchStatistics()
            dao.upsertHistoricalMatchStatistics(bundle.matches)
            dao.upsertSyncMetadata(SyncMetadataEntity(IMPORT_REVISION_KEY, bundle.sourceRevision))
        }
        return bundle.matches.size
    }

    companion object {
        private const val ASSET_PATH = "statsbomb/bundesliga-2023-24.json"
        private const val IMPORT_REVISION_KEY = "statsbombBundledRevision"
    }
}
