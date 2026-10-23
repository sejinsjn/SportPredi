package com.sportpredi

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalForecastingModelTest {
    @Test
    fun scoreMarketsAreDerivedFromNormalizedDixonColesDistribution() {
        val state = trainedState()
        val forecast = state.forecast(fixture(100, "2025-02-01", homeId = 1, awayId = 2))
        val analysis = forecast.analysis

        assertEquals(1.0, analysis.scoreDistribution.sumOf { it.probability }, 1e-12)
        assertEquals(
            forecast.dixonColesModel.probabilities.sum(),
            1.0,
            1e-12,
        )
        assertEquals(
            forecast.dixonColesModel.probabilities[0],
            analysis.scoreDistribution.filter { it.homeGoals > it.awayGoals }.sumOf { it.probability },
            1e-12,
        )
        assertEquals(
            analysis.scoreDistribution
                .filter { it.homeGoals + it.awayGoals > 2 }
                .sumOf { it.probability },
            analysis.over25Probability,
            1e-12,
        )
        assertTrue(analysis.over15Probability >= analysis.over25Probability)
        assertTrue(analysis.over25Probability >= analysis.over35Probability)
        assertTrue(analysis.bothTeamsToScoreProbability in 0.0..1.0)
    }

    @Test
    fun allMatchesFromOneDateMustBeForecastBeforeTheirResultsAreAdded() {
        val state = LocalForecastingState()
        val sameDay = fixture(10, "2025-01-01", homeId = 1, awayId = 2, homeScore = 2, awayScore = 0)
        state.advanceTo(LocalDate.parse(sameDay.matchDate))
        state.addCompletedMatch(sameDay)

        assertThrows(IllegalStateException::class.java) {
            state.forecast(fixture(11, sameDay.matchDate, homeId = 1, awayId = 3))
        }
    }

    @Test
    fun newResultsChangeFutureFingerprintWithoutMutatingEarlierSnapshot() {
        val state = trainedState()
        val firstForecast = state.forecast(fixture(100, "2025-02-01", homeId = 1, awayId = 2))
        val oldFingerprint = firstForecast.inputFingerprint
        val oldHomeProbability = firstForecast.dixonColesModel.probabilities[0]
        state.addCompletedMatch(
            fixture(90, "2025-02-01", homeId = 3, awayId = 4, homeScore = 4, awayScore = 0),
        )
        val nextForecast = state.forecast(fixture(101, "2025-02-02", homeId = 1, awayId = 2))

        assertFalse(oldFingerprint == nextForecast.inputFingerprint)
        assertEquals(oldHomeProbability, firstForecast.dixonColesModel.probabilities[0], 0.0)
    }

    @Test
    fun matchesFromOtherCompetitionsContributeToTeamFormAndPrediction() {
        val baseline = LocalForecastingState()
            .forecast(
                fixture(
                    providerId = 100,
                    date = "2025-01-02",
                    homeId = 1,
                    awayId = 2,
                    competitionCode = "cup",
                ),
            )
        val state = LocalForecastingState()
        state.addCompletedMatch(
            fixture(
                providerId = 1,
                date = "2025-01-01",
                homeId = 1,
                awayId = 2,
                homeScore = 4,
                awayScore = 0,
                competitionCode = "league",
            ),
        )

        val forecast = state.forecast(
            fixture(
                providerId = 100,
                date = "2025-01-02",
                homeId = 1,
                awayId = 2,
                competitionCode = "cup",
            ),
        )

        assertEquals(1, forecast.analysis.homeHistoryCount)
        assertEquals(1, forecast.analysis.awayHistoryCount)
        assertTrue(forecast.analysis.homeElo > baseline.analysis.homeElo)
        assertTrue(
            forecast.dixonColesModel.expectedHomeGoals >
                baseline.dixonColesModel.expectedHomeGoals,
        )
        assertTrue(
            forecast.empiricalBaseline.expectedHomeGoals >
                baseline.empiricalBaseline.expectedHomeGoals,
        )
        assertTrue(
            forecast.analysis.estimatedHomeAdvantageElo >
                baseline.analysis.estimatedHomeAdvantageElo,
        )
        assertFalse(forecast.inputFingerprint == baseline.inputFingerprint)
    }

    @Test
    fun oldResultsLoseInfluenceAndDoNotKeepConfidenceHigh() {
        val state = LocalForecastingState(LocalForecastParameters(halfLifeDays = 1.0))
        state.addCompletedMatch(
            fixture(
                providerId = 1,
                date = "2025-01-01",
                homeId = 1,
                awayId = 2,
                homeScore = 4,
                awayScore = 0,
                competitionCode = "league",
            ),
        )
        val recent = state.forecast(
            fixture(100, "2025-01-02", homeId = 1, awayId = 2, competitionCode = "cup"),
        )
        val stale = state.forecast(
            fixture(101, "2025-01-11", homeId = 1, awayId = 2, competitionCode = "cup"),
        )

        assertEquals(1, stale.analysis.homeHistoryCount)
        assertEquals("Sehr begrenzte Datenbasis", stale.analysis.confidenceLabel)
        assertTrue(
            kotlin.math.abs(stale.analysis.homeElo - 1500.0) <
                kotlin.math.abs(recent.analysis.homeElo - 1500.0),
        )
    }

    @Test
    fun estimatedHomeAdvantageRespondsToObservedHomeResults() {
        val state = LocalForecastingState()
        val before = state.forecast(fixture(1, "2025-01-01", homeId = 1, awayId = 2))
            .analysis.estimatedHomeAdvantageElo
        state.addCompletedMatch(
            fixture(2, "2025-01-01", homeId = 1, awayId = 2, homeScore = 3, awayScore = 0),
        )
        val after = state.forecast(fixture(3, "2025-01-02", homeId = 1, awayId = 2))
            .analysis.estimatedHomeAdvantageElo

        assertTrue(after > before)
    }

    @Test
    fun teamRatesAreShrunkAndRemainWithinSafeGoalBounds() {
        val state = LocalForecastingState()
        state.addCompletedMatch(
            fixture(1, "2025-01-01", homeId = 1, awayId = 2, homeScore = 10, awayScore = 0),
        )
        val forecast = state.forecast(fixture(2, "2025-01-02", homeId = 1, awayId = 2))

        assertTrue(forecast.analysis.scoreDistribution.all { it.probability >= 0.0 })
        assertTrue(forecast.dixonColesModel.expectedHomeGoals in 0.1..5.0)
        assertTrue(forecast.dixonColesModel.expectedAwayGoals in 0.1..5.0)
    }

    @Test
    fun predictionAnalysisCanBeStoredAndRestoredAsSnapshotJson() {
        val analysis = trainedState()
            .forecast(fixture(100, "2025-02-01", homeId = 1, awayId = 2))
            .analysis
        val restored = Gson().fromJson(Gson().toJson(analysis), PredictionAnalysis::class.java)

        assertEquals(analysis.mostLikelyScore, restored.mostLikelyScore)
        assertEquals(analysis.over25Probability, restored.over25Probability, 1e-12)
        assertEquals(analysis.mostLikelyOutcome, restored.mostLikelyOutcome)
        assertEquals(
            analysis.mostLikelyOutcomeProbability!!,
            restored.mostLikelyOutcomeProbability!!,
            1e-12,
        )
        assertEquals(
            analysis.scoreDistribution.size,
            restored.scoreDistribution.size,
        )
    }

    @Test
    fun previousLiveModelIsPreservedAsTheBacktestBaseline() {
        val forecast = trainedState()
            .forecast(fixture(100, "2025-02-01", homeId = 1, awayId = 2))

        assertEquals(1.65, forecast.existingModel.expectedHomeGoals, 1e-12)
        assertEquals(0.5 / 1.1, forecast.existingModel.expectedAwayGoals, 1e-12)
    }

    @Test
    fun sparseHistoryStillProducesAnUnfilteredOutcomePrediction() {
        val analysis = LocalForecastingState()
            .forecast(fixture(100, "2025-02-01", homeId = 1, awayId = 2))
            .analysis

        assertEquals("Sehr begrenzte Datenbasis", analysis.confidenceLabel)
        assertEquals(0, analysis.homeHistoryCount)
        assertEquals(0, analysis.awayHistoryCount)
        assertEquals(1.0, analysis.scoreDistribution.sumOf { it.probability }, 1e-12)
        assertTrue(analysis.mostLikelyOutcome in setOf("HOME", "DRAW", "AWAY"))
        assertTrue(analysis.mostLikelyOutcomeProbability != null)
        assertTrue(analysis.mostLikelyOutcomeProbability!! < MODEL_ACCURACY_GOAL)
    }

    private fun trainedState(): LocalForecastingState {
        val state = LocalForecastingState()
        repeat(8) { index ->
            state.addCompletedMatch(
                fixture(
                    providerId = index + 1L,
                    date = LocalDate.of(2025, 1, 1).plusDays(index.toLong()).toString(),
                    homeId = if (index % 2 == 0) 1 else 3,
                    awayId = if (index % 2 == 0) 4 else 2,
                    homeScore = if (index % 2 == 0) 2 else 1,
                    awayScore = if (index % 2 == 0) 0 else 1,
                ),
            )
        }
        return state
    }

    private fun fixture(
        providerId: Long,
        date: String,
        homeId: Int,
        awayId: Int,
        homeScore: Int? = null,
        awayScore: Int? = null,
        competitionCode: String = "test-league",
    ) = FixtureEntity(
        providerFixtureId = providerId,
        competitionCode = competitionCode,
        seasonId = "test-season",
        homeTeamId = homeId,
        awayTeamId = awayId,
        kickoffAt = null,
        matchDate = date,
        status = if (homeScore != null && awayScore != null) "FINISHED" else "SCHEDULED",
        matchday = null,
        stage = null,
        venue = null,
        homeScore = homeScore,
        awayScore = awayScore,
        halftimeHomeScore = null,
        halftimeAwayScore = null,
        fullTimeResult = homeScore != null && awayScore != null,
        postponedOrCancelled = false,
        updatedAt = "${date}T00:00:00Z",
    )
}
