package com.sportpredi

import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LocalPredictionEngine(private val dao: SportPrediDao) {
    private val gson = Gson()

    suspend fun refreshUpcomingPredictions(
        today: String = LocalDate.now(ZoneOffset.UTC).toString(),
    ): Int {
        val upcomingByDate = dao.futureFixtures(today)
            .groupBy { it.matchDate }
            .toSortedMap()
        val completed = dao.completedFixtures()
            .filter { it.fullTimeResult && it.homeScore != null && it.awayScore != null }
            .sortedWith(compareBy<FixtureEntity>({ it.matchDate }, { it.kickoffAt.orEmpty() }))
        val latestBacktest = dao.latestBacktest()
        val parameters = LocalForecastParameters(
            halfLifeDays = latestBacktest?.selectedHalfLifeDays ?: 365.0,
            dixonColesRho = latestBacktest?.selectedDixonColesRho ?: 0.0,
        )
        val state = LocalForecastingState(parameters)
        var created = 0

        var completedIndex = 0
        upcomingByDate.forEach { (day, fixtures) ->
            while (completedIndex < completed.size && completed[completedIndex].matchDate < day) {
                state.addCompletedMatch(completed[completedIndex])
                completedIndex += 1
            }
            fixtures.forEach { fixture ->
                val forecast = state.forecast(fixture)
                val probabilities = forecast.dixonColesModel.probabilities
                val row = PredictionSnapshotEntity(
                    providerFixtureId = fixture.providerFixtureId,
                    createdAt = Instant.now().toString(),
                    trigger = "local_sync",
                    inputFingerprint = buildString {
                        append(forecast.inputFingerprint)
                        append("|prediction-scope:all-1x2-outcomes")
                    },
                    homeWinProbability = probabilities[0],
                    drawProbability = probabilities[1],
                    awayWinProbability = probabilities[2],
                    expectedHomeGoals = forecast.dixonColesModel.expectedHomeGoals,
                    expectedAwayGoals = forecast.dixonColesModel.expectedAwayGoals,
                    modelVersion = LocalForecastingState.MODEL_VERSION,
                    analysisJson = gson.toJson(forecast.analysis),
                )
                if (dao.insertPrediction(row) != -1L) created += 1
            }
            while (completedIndex < completed.size && completed[completedIndex].matchDate == day) {
                state.addCompletedMatch(completed[completedIndex])
                completedIndex += 1
            }
        }
        return created
    }

    companion object {
        const val MODEL_VERSION = LocalForecastingState.MODEL_VERSION
    }
}
