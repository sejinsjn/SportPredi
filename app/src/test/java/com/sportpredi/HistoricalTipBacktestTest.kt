package com.sportpredi

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalTipBacktestTest {
    @Test
    fun evaluatesStoredTipsOnlyWithPredictionAndOddsCapturedBeforeKickoff() {
        val fixtures = listOf(
            fixture(1, "2025-01-02T18:00:00Z", homeScore = 2, awayScore = 1),
            fixture(2, "2025-01-03T18:00:00Z", homeScore = 0, awayScore = 1),
            fixture(3, "2025-01-04T18:00:00Z", homeScore = 1, awayScore = 0),
        )
        val report = evaluateHistoricalApiFootballTips(
            fixtures = fixtures,
            predictions = listOf(
                prediction(1, "2025-01-01T10:00:00Z"),
                prediction(2, "2025-01-01T10:00:00Z"),
                prediction(3, "2025-01-04T19:00:00Z"),
            ),
            oddsSnapshots = listOf(
                oddsSnapshot(1, "2025-01-01T11:00:00Z", listOf(quote(1, "HOME", 2.0))),
                oddsSnapshot(2, "2025-01-03T19:00:00Z", listOf(quote(2, "HOME", 2.0))),
                oddsSnapshot(3, "2025-01-04T10:00:00Z", listOf(quote(3, "HOME", 2.0))),
            ),
            teamNames = mapOf(1 to "Home", 2 to "Away"),
        )

        assertEquals(3, report.completedFixtures)
        assertEquals(2, report.fixturesWithPrematchOdds)
        assertEquals(1, report.eligibleFixtures)
        assertEquals(1, report.recommendations.size)
        assertEquals("Home", report.recommendations.single().homeTeam)
        assertEquals("WIN", report.recommendations.single().result)
        assertEquals(1.0, report.recommendations.single().profitUnits, 0.0)
        assertEquals(1.0, report.hitRate!!, 0.0)
        assertEquals(1.0, report.returnOnInvestment!!, 0.0)
        assertEquals(0.20, report.averageExpectedValue!!, 0.000001)
        assertEquals(0.60, report.averagePredictedWinProbability!!, 0.000001)
        assertEquals(0.16, report.brierScore!!, 0.000001)
    }

    @Test
    fun usesTheLatestPrematchOddsSnapshotAndIgnoresLaterSnapshots() {
        val report = evaluateHistoricalApiFootballTips(
            fixtures = listOf(fixture(8, "2025-01-02T18:00:00Z", homeScore = 2, awayScore = 1)),
            predictions = listOf(prediction(8, "2025-01-01T10:00:00Z")),
            oddsSnapshots = listOf(
                oddsSnapshot(8, "2025-01-01T10:30:00Z", listOf(quote(8, "HOME", 1.8))),
                oddsSnapshot(8, "2025-01-01T11:30:00Z", listOf(quote(8, "HOME", 2.0))),
                oddsSnapshot(8, "2025-01-02T19:00:00Z", listOf(quote(8, "HOME", 9.0))),
            ),
        )

        assertEquals(2.0, report.recommendations.single().decimalOdds, 0.0)
        assertEquals(0.20, report.recommendations.single().expectedValue, 0.000001)
    }

    @Test
    fun treatsDrawNoBetDrawAsPushAndDoesNotCountItAsAWinOrLoss() {
        val fixture = fixture(4, "2025-02-02T18:00:00Z", homeScore = 1, awayScore = 1)
        val quotes = listOf(
            ApiFootballOddsQuote(4, "DRAW_NO_BET", "HOME", 1.8, "Book A"),
            ApiFootballOddsQuote(4, "DRAW_NO_BET", "AWAY", 2.0, "Book A"),
        )

        val report = evaluateHistoricalApiFootballTips(
            fixtures = listOf(fixture),
            predictions = listOf(prediction(4, "2025-02-01T10:00:00Z").copy(
                homeWinProbability = 0.80,
                drawProbability = 0.10,
                awayWinProbability = 0.10,
            )),
            oddsSnapshots = listOf(oddsSnapshot(4, "2025-02-01T11:00:00Z", quotes)),
        )

        assertEquals(1, report.pushes)
        assertEquals(0, report.wins)
        assertEquals(0, report.losses)
        assertNull(report.hitRate)
        assertEquals(0.0, report.returnOnInvestment!!, 0.0)
        assertEquals("PUSH", report.recommendations.single().result)
    }

    @Test
    fun treatsExactIntegerGoalLineAsPushInHistoricalResults() {
        val fixture = fixture(6, "2025-02-02T18:00:00Z", homeScore = 2, awayScore = 2)
        val report = evaluateHistoricalApiFootballTips(
            fixtures = listOf(fixture),
            predictions = listOf(
                prediction(6, "2025-02-01T10:00:00Z").copy(
                    analysisJson = Gson().toJson(goalAnalysis()),
                ),
            ),
            oddsSnapshots = listOf(
                oddsSnapshot(
                    6,
                    "2025-02-01T11:00:00Z",
                    listOf(ApiFootballOddsQuote(6, "TOTAL_GOALS", "UNDER_4.0", 2.4, "Book A")),
                ),
            ),
        )

        assertEquals(1, report.pushes)
        assertEquals(0, report.wins)
        assertEquals(0, report.losses)
        assertEquals("PUSH", report.recommendations.single().result)
        assertEquals(0.0, report.returnOnInvestment!!, 0.0)
    }

    @Test
    fun expandedDiagnosticIncludesPositiveEvTipsBelowTheActiveChanceFloor() {
        val report = evaluateHistoricalApiFootballTips(
            fixtures = listOf(fixture(7, "2025-02-02T18:00:00Z", homeScore = 2, awayScore = 1)),
            predictions = listOf(
                prediction(7, "2025-02-01T10:00:00Z").copy(
                    homeWinProbability = 0.55,
                    drawProbability = 0.25,
                    awayWinProbability = 0.20,
                ),
            ),
            oddsSnapshots = listOf(
                oddsSnapshot(7, "2025-02-01T11:00:00Z", listOf(quote(7, "HOME", 2.0))),
            ),
        )

        assertTrue(report.recommendations.isEmpty())
        assertEquals(1, report.expandedPositiveEvRecommendations.size)
        assertEquals(1.0, report.expandedHitRate!!, 0.0)
    }

    @Test
    fun reportsNoTipsWhenNoSavedPrematchDataExists() {
        val report = evaluateHistoricalApiFootballTips(
            fixtures = listOf(fixture(5, "2025-03-02T18:00:00Z", homeScore = 0, awayScore = 0)),
            predictions = emptyList(),
            oddsSnapshots = emptyList(),
        )

        assertEquals(1, report.completedFixtures)
        assertEquals(0, report.eligibleFixtures)
        assertTrue(report.recommendations.isEmpty())
        assertNull(report.returnOnInvestment)
    }

    private fun fixture(
        id: Long,
        kickoffAt: String,
        homeScore: Int,
        awayScore: Int,
    ) = FixtureEntity(
        providerFixtureId = id,
        competitionCode = "test-league",
        seasonId = "2024",
        homeTeamId = 1,
        awayTeamId = 2,
        kickoffAt = kickoffAt,
        matchDate = kickoffAt.substringBefore('T'),
        status = "FINISHED",
        matchday = null,
        stage = null,
        venue = null,
        homeScore = homeScore,
        awayScore = awayScore,
        halftimeHomeScore = null,
        halftimeAwayScore = null,
        fullTimeResult = true,
        postponedOrCancelled = false,
        updatedAt = kickoffAt,
    )

    private fun prediction(fixtureId: Long, createdAt: String) = PredictionSnapshotEntity(
        providerFixtureId = fixtureId,
        createdAt = createdAt,
        trigger = "local_sync",
        inputFingerprint = "fixture-$fixtureId",
        homeWinProbability = 0.70,
        drawProbability = 0.20,
        awayWinProbability = 0.10,
        expectedHomeGoals = 1.8,
        expectedAwayGoals = 0.9,
        modelVersion = LocalForecastingState.MODEL_VERSION,
    )

    private fun oddsSnapshot(
        fixtureId: Long,
        fetchedAt: String,
        quotes: List<ApiFootballOddsQuote>,
    ) = ApiFootballOddsSnapshotEntity(
        providerFixtureId = fixtureId,
        fetchedAt = fetchedAt,
        quotesJson = Gson().toJson(quotes),
        status = ApiFootballOddsCacheStatus.AVAILABLE,
    )

    private fun quote(fixtureId: Long, outcome: String, odds: Double) =
        ApiFootballOddsQuote(fixtureId, "1X2", outcome, odds, "Test Book")

    private fun goalAnalysis() = PredictionAnalysis(
        scoreDistribution = listOf(
            ScorelineProbability(0, 0, 0.15),
            ScorelineProbability(1, 0, 0.18),
            ScorelineProbability(2, 0, 0.20),
            ScorelineProbability(2, 1, 0.20),
            ScorelineProbability(3, 0, 0.10),
            ScorelineProbability(2, 2, 0.10),
            ScorelineProbability(3, 2, 0.07),
        ),
        expectedTotalGoals = 2.8,
        mostLikelyScore = "2:1",
        over15Probability = 0.75,
        over25Probability = 0.70,
        over35Probability = 0.50,
        bothTeamsToScoreProbability = 0.70,
        homeOrDrawProbability = 0.80,
        drawOrAwayProbability = 0.30,
        eitherTeamToWinProbability = 0.70,
        homeHistoryCount = 10,
        awayHistoryCount = 10,
        homeElo = 1500.0,
        awayElo = 1500.0,
        estimatedHomeAdvantageElo = 60.0,
        halfLifeDays = 365.0,
        dixonColesRho = 0.0,
        homeRestDays = null,
        awayRestDays = null,
        confidenceLabel = "Test",
        latestSourceUpdate = null,
        explanation = "Test analysis",
    )
}
