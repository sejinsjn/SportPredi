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
        val selectedOutcomeModelVersion = latestBacktest?.let { run ->
            selectValidatedForecastModel(
                dao.backtestMetrics(run.id).map { metric ->
                    LocalModelMetric(
                        modelVersion = metric.modelVersion,
                        sampleSize = metric.sampleSize,
                        logLoss = metric.logLoss,
                        brierScore = metric.brierScore,
                        accuracy = metric.accuracy,
                        meanAbsoluteGoalError = metric.meanAbsoluteGoalError,
                        scope = metric.scope,
                        calibrationError = metric.calibrationError,
                    )
                },
            )
        } ?: LocalForecastingState.MODEL_VERSION
        val parameters = LocalForecastParameters(
            halfLifeDays = latestBacktest?.selectedHalfLifeDays ?: 365.0,
            dixonColesRho = latestBacktest?.selectedDixonColesRho ?: 0.0,
            eloKFactor = latestBacktest?.selectedEloKFactor ?: 20.0,
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
                val selectedForecast = when (selectedOutcomeModelVersion) {
                    OLD_BACKTEST_BASELINE_MODEL_VERSION -> forecast.existingModel
                    EMPIRICAL_MODEL_VERSION -> forecast.empiricalBaseline
                    ELO_MODEL_VERSION -> forecast.eloModel
                    POISSON_MODEL_VERSION -> forecast.poissonModel
                    else -> forecast.dixonColesModel
                }
                val analysis = applyOutcomeProbabilities(
                    analysis = forecast.analysis,
                    outcomeProbabilities = selectedForecast.probabilities,
                    outcomeModelVersion = selectedOutcomeModelVersion,
                )
                val row = PredictionSnapshotEntity(
                    providerFixtureId = fixture.providerFixtureId,
                    createdAt = Instant.now().toString(),
                    trigger = "local_sync",
                    inputFingerprint = buildString {
                        append(forecast.inputFingerprint)
                        append("|prediction-scope:all-1x2-outcomes")
                        append("|selected-outcome-model:")
                        append(selectedOutcomeModelVersion)
                    },
                    homeWinProbability = selectedForecast.probabilities[0],
                    drawProbability = selectedForecast.probabilities[1],
                    awayWinProbability = selectedForecast.probabilities[2],
                    expectedHomeGoals = analysis.scoreDistribution.sumOf {
                        it.homeGoals * it.probability
                    },
                    expectedAwayGoals = analysis.scoreDistribution.sumOf {
                        it.awayGoals * it.probability
                    },
                    modelVersion = MODEL_VERSION,
                    analysisJson = gson.toJson(analysis),
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
        const val MODEL_VERSION = "validation-selected-scoreline-v1"
    }
}
