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
        val futureByCompetition = dao.futureFixtures(today)
            .groupBy { it.competitionCode }
        val completedByCompetition = dao.completedFixtures()
            .filter { it.fullTimeResult && it.homeScore != null && it.awayScore != null }
            .groupBy { it.competitionCode }
        val latestBacktest = dao.latestBacktest()
        val parameters = LocalForecastParameters(
            halfLifeDays = latestBacktest?.selectedHalfLifeDays ?: 365.0,
            dixonColesRho = latestBacktest?.selectedDixonColesRho ?: 0.0,
        )
        var created = 0

        futureByCompetition.forEach { (competitionCode, upcoming) ->
            val state = LocalForecastingState(parameters)
            val completed = completedByCompetition[competitionCode]
                .orEmpty()
                .sortedWith(compareBy<FixtureEntity>({ it.matchDate }, { it.kickoffAt.orEmpty() }))
            val upcomingDays = upcoming.groupBy { it.matchDate }.toSortedMap()
            var completedIndex = 0

            upcomingDays.forEach { (day, fixtures) ->
                while (
                    completedIndex < completed.size &&
                    completed[completedIndex].matchDate < day
                ) {
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
                        inputFingerprint = forecast.inputFingerprint,
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
                while (
                    completedIndex < completed.size &&
                    completed[completedIndex].matchDate == day
                ) {
                    state.addCompletedMatch(completed[completedIndex])
                    completedIndex += 1
                }
            }
        }
        return created
    }

    companion object {
        const val MODEL_VERSION = LocalForecastingState.MODEL_VERSION
    }
}
