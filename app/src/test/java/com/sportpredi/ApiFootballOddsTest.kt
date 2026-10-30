package com.sportpredi

import com.google.gson.Gson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFootballOddsTest {
    @Test
    fun normalizesSupported1X2NamesAndIgnoresUnsupportedOrInvalidQuotes() {
        val response = listOf(
            ApiFootballOddsResponseDto(
                fixture = ApiFootballOddsFixtureDto(123),
                bookmakers = listOf(
                    ApiFootballOddsBookmakerDto(
                        id = 1,
                        name = "  Example Bookmaker ",
                        bets = listOf(
                            ApiFootballOddsBetDto(
                                id = 1,
                                name = "Match Winner",
                                values = listOf(
                                    ApiFootballOddsValueDto("Home", "2.10"),
                                    ApiFootballOddsValueDto("Draw", "3.40"),
                                    ApiFootballOddsValueDto("Away", "3.80"),
                                    ApiFootballOddsValueDto("Home", "1.0"),
                                    ApiFootballOddsValueDto("Unknown", "5.0"),
                                ),
                            ),
                            ApiFootballOddsBetDto(
                                id = 2,
                                name = "Player Props",
                                values = listOf(ApiFootballOddsValueDto("Over 2.5", "1.9")),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val normalized = normalizeApiFootballOdds(response, requestedFixtureId = 123)

        assertTrue(normalized.fixtureFound)
        assertEquals(3, normalized.quotes.size)
        assertEquals(setOf("HOME", "DRAW", "AWAY"), normalized.quotes.map { it.outcome }.toSet())
        assertTrue(normalized.quotes.all { it.market == "1X2" && it.bookmaker == "Example Bookmaker" })
        assertEquals(2.1, normalized.quotes.first { it.outcome == "HOME" }.decimalOdds, 0.0)
        assertEquals(
            normalized.quotes,
            parseApiFootballOddsQuotes(Gson().toJson(normalized.quotes)),
        )
    }

    @Test
    fun ignoresOtherFixtureRowsAndParsesDocumentedOddsJsonShape() {
        val json = """
            [{"fixture":{"id":123},"bookmakers":[{"id":1,"name":"Book A","bets":[
              {"id":1,"name":"1X2","values":[{"value":"1","odd":"2.25"}]}
            ]}]}]
        """.trimIndent()
        val response = Gson().fromJson(json, Array<ApiFootballOddsResponseDto>::class.java).toList()

        assertTrue(normalizeApiFootballOdds(response, 999).fixtureFound.not())
        assertEquals("HOME", normalizeApiFootballOdds(response, 123).quotes.single().outcome)
    }

    @Test
    fun ranksBestPriceByOutcomeAndPositiveExpectedValue() {
        val quotes = listOf(
            quote("HOME", 2.0, "Book A"),
            quote("HOME", 2.5, "Book B"),
            quote("DRAW", 4.5, "Book C"),
            quote("AWAY", 1.5, "Book D"),
        )

        val tips = rankApiFootballValueTips(
            quotes = quotes,
            homeProbability = 0.60,
            drawProbability = 0.25,
            awayProbability = 0.20,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(listOf("HOME", "DRAW"), tips.map { it.outcome })
        assertEquals("Book B", tips.first().bookmaker)
        assertEquals(0.5, tips.first().expectedValue, 0.000001)
        assertEquals(0.125, tips[1].expectedValue, 0.000001)
        assertEquals("2026-10-23T12:00:00Z", tips.first().fetchedAt)
    }

    @Test
    fun normalizesDoubleChanceTotalsBothTeamsToScoreAndDrawNoBetMarkets() {
        val response = listOf(
            ApiFootballOddsResponseDto(
                fixture = ApiFootballOddsFixtureDto(123),
                bookmakers = listOf(
                    ApiFootballOddsBookmakerDto(
                        id = 1,
                        name = "Book A",
                        bets = listOf(
                            ApiFootballOddsBetDto(
                                1,
                                "Double Chance",
                                listOf(
                                    ApiFootballOddsValueDto("Home/Draw", "1.4"),
                                    ApiFootballOddsValueDto("12", "1.5"),
                                    ApiFootballOddsValueDto("X2", "1.6"),
                                ),
                            ),
                            ApiFootballOddsBetDto(
                                2,
                                "Goals Over/Under",
                                listOf(
                                    ApiFootballOddsValueDto("Over 2.5", "2.1"),
                                    ApiFootballOddsValueDto("Under 2,5", "1.8"),
                                    ApiFootballOddsValueDto("Over 2.25", "1.9"),
                                ),
                            ),
                            ApiFootballOddsBetDto(
                                3,
                                "Both Teams Score",
                                listOf(
                                    ApiFootballOddsValueDto("Yes", "1.9"),
                                    ApiFootballOddsValueDto("No", "2.0"),
                                ),
                            ),
                            ApiFootballOddsBetDto(
                                4,
                                "Draw No Bet",
                                listOf(
                                    ApiFootballOddsValueDto("Home", "1.7"),
                                    ApiFootballOddsValueDto("Away", "2.2"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val quotes = normalizeApiFootballOdds(response, 123).quotes

        assertEquals(9, quotes.size)
        assertEquals(
            setOf("DOUBLE_CHANCE", "TOTAL_GOALS", "BOTH_TEAMS_SCORE", "DRAW_NO_BET"),
            quotes.map { it.market }.toSet(),
        )
        assertTrue(quotes.any { it.market == "TOTAL_GOALS" && it.outcome == "OVER_2.5" })
        assertTrue(quotes.any { it.market == "TOTAL_GOALS" && it.outcome == "UNDER_2.5" })
        assertFalse(quotes.any { it.outcome.contains("2.25") })
    }

    @Test
    fun ranksValueAcrossMarketsAndModelsDrawNoBetPushesCorrectly() {
        val analysis = sampleAnalysis()
        val quotes = listOf(
            ApiFootballOddsQuote(123, "TOTAL_GOALS", "OVER_2.5", 2.3, "Over Book"),
            ApiFootballOddsQuote(123, "TOTAL_GOALS", "UNDER_2.5", 1.7, "Under Book"),
            ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "YES", 2.0, "BTTS Book"),
            ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.5, "DC Book"),
            ApiFootballOddsQuote(123, "DRAW_NO_BET", "HOME", 1.9, "DNB Book"),
        )

        val tips = rankApiFootballValueTips(
            quotes = quotes,
            homeProbability = 0.45,
            drawProbability = 0.25,
            awayProbability = 0.30,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = analysis,
        )

        val byMarket = tips.associateBy { it.market }
        assertTrue(byMarket.keys.containsAll(setOf("TOTAL_GOALS", "BOTH_TEAMS_SCORE", "DOUBLE_CHANCE", "DRAW_NO_BET")))
        assertEquals(0.15, byMarket.getValue("TOTAL_GOALS").expectedValue, 0.000001)
        assertEquals(0.10, byMarket.getValue("BOTH_TEAMS_SCORE").expectedValue, 0.000001)
        assertEquals(0.05, byMarket.getValue("DOUBLE_CHANCE").expectedValue, 0.000001)
        assertEquals(0.105, byMarket.getValue("DRAW_NO_BET").expectedValue, 0.000001)
        assertEquals(0.25, byMarket.getValue("DRAW_NO_BET").pushProbability, 0.000001)
        assertEquals(0.45, byMarket.getValue("DRAW_NO_BET").modelProbability, 0.000001)
        assertEquals("Tore · Über 2,5", apiFootballMarketLabel("TOTAL_GOALS", "OVER_2.5"))
    }

    @Test
    fun selectsOneBestTipOnlyWithinRealisticProbabilityOddsAndValueLimits() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.8, "Best EV"),
                ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "YES", 1.8, "Lower EV"),
                ApiFootballOddsQuote(123, "TOTAL_GOALS", "OVER_2.5", 2.3, "Low chance"),
                ApiFootballOddsQuote(123, "1X2", "AWAY", 4.0, "Long shot"),
            ),
            homeProbability = 0.50,
            drawProbability = 0.25,
            awayProbability = 0.25,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = sampleAnalysis(),
        )

        assertEquals("DOUBLE_CHANCE", selected?.market)
        assertEquals("HOME_DRAW", selected?.outcome)
        assertEquals(0.75, selected?.modelProbability ?: 0.0, 0.000001)
        assertEquals(0.35, selected?.expectedValue ?: 0.0, 0.000001)
    }

    @Test
    fun choosesHighestProbabilityBeforeHigherExpectedValue() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.5, "Higher probability"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_AWAY", 1.8, "Higher EV"),
            ),
            homeProbability = 0.70,
            drawProbability = 0.20,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals("HOME_DRAW", selected?.outcome)
        assertEquals(0.90, selected?.modelProbability ?: 0.0, 0.000001)
        assertEquals(0.35, selected?.expectedValue ?: 0.0, 0.000001)
    }

    @Test
    fun acceptsOddsAtOnePointFiveAndOnePointEight() {
        val oddsAtOnePointFive = selectBestApiFootballValueTip(
            quotes = listOf(ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.5, "Book A")),
            homeProbability = 0.50,
            drawProbability = 0.25,
            awayProbability = 0.25,
            fetchedAt = "2026-10-23T12:00:00Z",
        )
        val oddsAtOnePointEight = selectBestApiFootballValueTip(
            quotes = listOf(ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.8, "Book B")),
            homeProbability = 0.55,
            drawProbability = 0.25,
            awayProbability = 0.20,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(1.5, oddsAtOnePointFive?.decimalOdds ?: 0.0, 0.0)
        assertEquals(1.8, oddsAtOnePointEight?.decimalOdds ?: 0.0, 0.0)
    }

    @Test
    fun rejectsOddsOutsideTheRecommendedBandIncludingOnePointTwo() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                quote("HOME", 1.2, "Too short"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 2.0, "Too long"),
            ),
            homeProbability = 0.90,
            drawProbability = 0.05,
            awayProbability = 0.05,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(null, selected)
    }

    @Test
    fun usesExpectedValueAsTieBreakerForEqualProbabilities() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                quote("HOME", 1.5, "Lower EV"),
                ApiFootballOddsQuote(123, "DRAW_NO_BET", "HOME", 1.8, "Higher EV"),
            ),
            homeProbability = 0.80,
            drawProbability = 0.0,
            awayProbability = 0.20,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals("DRAW_NO_BET", selected?.market)
        assertEquals(0.80, selected?.modelProbability ?: 0.0, 0.000001)
    }

    @Test
    fun rejectsTipsBelowSeventyFivePercentEvenWhenExpectedValueIsPositive() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.8, "High EV")),
            homeProbability = 0.50,
            drawProbability = 0.24,
            awayProbability = 0.26,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(null, selected)
    }

    @Test
    fun returnsNoTipWhenNoQuoteMeetsAllSelectionLimits() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                ApiFootballOddsQuote(123, "1X2", "HOME", 2.0, "Low probability"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.2, "Too short"),
                ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "YES", 1.5, "Low expected value"),
            ),
            homeProbability = 0.45,
            drawProbability = 0.25,
            awayProbability = 0.30,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = sampleAnalysis(),
        )

        assertEquals(null, selected)
    }

    @Test
    fun noOddsOrNoPositiveExpectedValueProducesNoTip() {
        assertTrue(
            rankApiFootballValueTips(
                quotes = emptyList(),
                homeProbability = 0.5,
                drawProbability = 0.3,
                awayProbability = 0.2,
                fetchedAt = "2026-10-23T12:00:00Z",
            ).isEmpty(),
        )
        assertTrue(
            rankApiFootballValueTips(
                quotes = listOf(quote("HOME", 1.8, "Book A")),
                homeProbability = 0.5,
                drawProbability = 0.3,
                awayProbability = 0.2,
                fetchedAt = "2026-10-23T12:00:00Z",
            ).isEmpty(),
        )
        assertTrue(
            rankApiFootballValueTips(
                quotes = listOf(quote("HOME", 2.0, "Book A")),
                homeProbability = Double.NaN,
                drawProbability = 0.3,
                awayProbability = 0.2,
                fetchedAt = "2026-10-23T12:00:00Z",
            ).isEmpty(),
        )
    }

    @Test
    fun cacheTtlAndCandidateSelectionAvoidRepeatedRequestsForFreshEmptyCoverage() {
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val fixtures = listOf(fixture(1, "2026-10-23"), fixture(2, "2026-10-24"))
        val cachedNoOdds = ApiFootballOddsCacheEntity(
            providerFixtureId = 1,
            quotesJson = "[]",
            status = ApiFootballOddsCacheStatus.NO_ODDS,
            fetchedAt = "2026-10-23T11:00:00Z",
        )
        val selected = selectApiFootballOddsCandidates(
            fixtures = fixtures,
            cachedByFixtureId = mapOf(1L to cachedNoOdds),
            today = LocalDate.parse("2026-10-23"),
            upcomingDays = 14,
            now = now,
            cacheTtl = Duration.ofHours(6),
            requestLimit = 10,
        )

        assertEquals(listOf(2L), selected.map { it.providerFixtureId })
        assertTrue(apiFootballOddsCacheIsFresh(cachedNoOdds.fetchedAt, now, Duration.ofHours(6)))
        assertFalse(apiFootballOddsCacheIsFresh(cachedNoOdds.fetchedAt, now, Duration.ofMinutes(30)))
    }

    @Test
    fun candidateSelectionExcludesCompletedAndOutOfWindowFixturesAndHonorsSlotLimits() {
        val candidates = selectApiFootballOddsCandidates(
            fixtures = listOf(
                fixture(1, "2026-10-23"),
                fixture(2, "2026-10-23").copy(status = "FT", fullTimeResult = true),
                fixture(3, "2026-11-20"),
            ),
            cachedByFixtureId = emptyMap(),
            today = LocalDate.parse("2026-10-23"),
            upcomingDays = 14,
            now = Instant.parse("2026-10-23T12:00:00Z"),
            cacheTtl = Duration.ofHours(6),
            requestLimit = 1,
        )

        assertEquals(listOf(1L), candidates.map { it.providerFixtureId })
        assertEquals(7, safeApiFootballOddsRequestSlots(32, 20, 8, 7, 10))
        assertEquals(0, safeApiFootballOddsRequestSlots(32, 20, 0, 7, 10))
    }

    @Test
    fun ranksTopTenDailyTipsByProbabilityThenExpectedValueAndExcludesOtherDates() {
        val today = LocalDate.parse("2026-10-23")
        val fixtures = (0..11).map { index ->
            dailyFixture(
                fixtureId = index + 1L,
                date = today,
                homeProbability = 0.75 + index * 0.01,
            )
        } + listOf(
            dailyFixture(100, today, homeProbability = 0.80, decimalOdds = 1.8),
            dailyFixture(101, today.plusDays(1), homeProbability = 0.99),
            dailyFixture(102, today, homeProbability = 0.85, decimalOdds = 2.0),
        )

        val tips = selectTopApiFootballValueTips(fixtures, today, today)

        assertEquals(10, tips.size)
        assertEquals(0.86, tips.first().tip.modelProbability, 0.000001)
        assertTrue(tips.all { it.fixture.matchDate == today.toString() })
        val tiedProbabilityTips = tips.filter {
            kotlin.math.abs(it.tip.modelProbability - 0.80) < 0.000001
        }
        assertEquals(2, tiedProbabilityTips.size)
        assertEquals(1.8, tiedProbabilityTips.first().tip.decimalOdds, 0.0)
        assertTrue(tips.zipWithNext().all { (left, right) ->
            left.tip.modelProbability + 0.000001 >= right.tip.modelProbability &&
                (left.tip.modelProbability - right.tip.modelProbability > 0.000001 ||
                    left.tip.expectedValue + 0.000001 >= right.tip.expectedValue)
        })
    }

    @Test
    fun filtersTopTipsToTheSelectedDateWindow() {
        val today = LocalDate.parse("2026-10-23")
        val fixtures = listOf(
            dailyFixture(1, today, homeProbability = 0.85),
            dailyFixture(2, today.plusDays(1), homeProbability = 0.80),
            dailyFixture(3, today.plusDays(2), homeProbability = 0.89),
        )

        val tomorrowTips = selectTopApiFootballValueTips(
            fixtures,
            startDate = today.plusDays(1),
            endDate = today.plusDays(1),
        )
        val todayAndTomorrowTips = selectTopApiFootballValueTips(
            fixtures,
            startDate = today,
            endDate = today.plusDays(1),
        )

        assertEquals(listOf(2L), tomorrowTips.map { it.fixture.providerFixtureId })
        assertEquals(listOf(1L, 2L), todayAndTomorrowTips.map { it.fixture.providerFixtureId })
    }

    private fun quote(outcome: String, decimalOdds: Double, bookmaker: String) =
        ApiFootballOddsQuote(123, "1X2", outcome, decimalOdds, bookmaker)

    private fun dailyFixture(
        fixtureId: Long,
        date: LocalDate,
        homeProbability: Double,
        decimalOdds: Double = 1.5,
    ) = FixtureDisplay(
        providerFixtureId = fixtureId,
        competitionCode = "39",
        kickoffAt = "${date}T19:00:00Z",
        matchDate = date.toString(),
        status = "SCHEDULED",
        postponedOrCancelled = false,
        rescheduled = false,
        venue = null,
        matchday = null,
        competitionName = "Test League",
        country = "Test",
        seasonYear = 2026,
        homeTeamId = 1,
        awayTeamId = 2,
        homeTeamName = "Home $fixtureId",
        awayTeamName = "Away $fixtureId",
        homeScore = null,
        awayScore = null,
        homeWinProbability = homeProbability,
        drawProbability = 0.10,
        awayWinProbability = 0.90 - homeProbability,
        expectedHomeGoals = null,
        expectedAwayGoals = null,
        analysisJson = null,
        oddsQuotesJson = Gson().toJson(
            listOf(ApiFootballOddsQuote(fixtureId, "1X2", "HOME", decimalOdds, "Test Book")),
        ),
        oddsStatus = ApiFootballOddsCacheStatus.AVAILABLE,
        oddsFetchedAt = "2026-10-23T12:00:00Z",
    )

    private fun sampleAnalysis() = PredictionAnalysis(
        scoreDistribution = listOf(
            ScorelineProbability(0, 0, 0.10),
            ScorelineProbability(1, 0, 0.15),
            ScorelineProbability(0, 1, 0.10),
            ScorelineProbability(1, 1, 0.15),
            ScorelineProbability(2, 1, 0.20),
            ScorelineProbability(1, 2, 0.10),
            ScorelineProbability(3, 1, 0.10),
            ScorelineProbability(0, 3, 0.10),
        ),
        expectedTotalGoals = 2.4,
        mostLikelyScore = "2:1",
        over15Probability = 0.65,
        over25Probability = 0.50,
        over35Probability = 0.20,
        bothTeamsToScoreProbability = 0.55,
        homeOrDrawProbability = 0.70,
        drawOrAwayProbability = 0.55,
        eitherTeamToWinProbability = 0.75,
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

    private fun fixture(fixtureId: Long, date: String) = FixtureEntity(
        providerFixtureId = fixtureId,
        competitionCode = "39",
        seasonId = "39:2026",
        homeTeamId = 1,
        awayTeamId = 2,
        kickoffAt = "${date}T20:00:00+00:00",
        matchDate = date,
        status = "SCHEDULED",
        matchday = null,
        stage = null,
        venue = null,
        homeScore = null,
        awayScore = null,
        halftimeHomeScore = null,
        halftimeAwayScore = null,
        fullTimeResult = false,
        postponedOrCancelled = false,
        updatedAt = "2026-10-23T12:00:00Z",
    )
}
