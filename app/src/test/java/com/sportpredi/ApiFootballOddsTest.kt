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
            quote("DRAW", 8.0, "Book C"),
            quote("AWAY", 10.0, "Book D"),
        )

        val tips = rankApiFootballValueTips(
            quotes = quotes,
            homeProbability = 0.80,
            drawProbability = 0.10,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(listOf("HOME"), tips.map { it.outcome })
        assertEquals("Book B", tips.first().bookmaker)
        assertEquals(0.60, tips.first().selectionProbability, 0.000001)
        assertEquals(0.50, tips.first().expectedValue, 0.000001)
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
        val analysis = sampleAnalysis().copy(
            scoreDistribution = listOf(
                ScorelineProbability(2, 1, 0.60),
                ScorelineProbability(3, 1, 0.20),
                ScorelineProbability(1, 0, 0.10),
                ScorelineProbability(0, 0, 0.05),
                ScorelineProbability(0, 1, 0.05),
            ),
            bothTeamsToScoreProbability = 0.80,
        )
        val quotes = listOf(
            ApiFootballOddsQuote(123, "TOTAL_GOALS", "OVER_2.5", 2.0, "Over Book"),
            ApiFootballOddsQuote(123, "TOTAL_GOALS", "UNDER_2.5", 1.7, "Under Book"),
            ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "YES", 2.0, "BTTS Book"),
            ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.5, "DC Book"),
            ApiFootballOddsQuote(123, "DRAW_NO_BET", "HOME", 1.9, "DNB Book"),
        )

        val tips = rankApiFootballValueTips(
            quotes = quotes,
            homeProbability = 0.80,
            drawProbability = 0.05,
            awayProbability = 0.15,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = analysis,
        )

        val byMarket = tips.associateBy { it.market }
        assertTrue(byMarket.keys.containsAll(setOf("TOTAL_GOALS", "BOTH_TEAMS_SCORE", "DOUBLE_CHANCE", "DRAW_NO_BET")))
        assertEquals(0.30, byMarket.getValue("TOTAL_GOALS").expectedValue, 0.000001)
        assertEquals(0.30, byMarket.getValue("BOTH_TEAMS_SCORE").expectedValue, 0.000001)
        assertEquals(0.1375, byMarket.getValue("DOUBLE_CHANCE").expectedValue, 0.000001)
        assertEquals(0.285, byMarket.getValue("DRAW_NO_BET").expectedValue, 0.000001)
        assertEquals(0.05, byMarket.getValue("DRAW_NO_BET").pushProbability, 0.000001)
        assertEquals(0.80, byMarket.getValue("DRAW_NO_BET").modelProbability, 0.000001)
        assertEquals("Tore · Über 2,5", apiFootballMarketLabel("TOTAL_GOALS", "OVER_2.5"))
    }

    @Test
    fun integerGoalLineCountsExactTotalAsPushAndHalfLineDoesNot() {
        val analysis = sampleAnalysis().copy(
            scoreDistribution = listOf(
                ScorelineProbability(0, 0, 0.40),
                ScorelineProbability(1, 0, 0.4075),
                ScorelineProbability(1, 1, 0.15),
                ScorelineProbability(3, 0, 0.0425),
            ),
        )
        val integerLineTip = rankApiFootballValueTips(
            quotes = listOf(
                ApiFootballOddsQuote(123, "TOTAL_GOALS", "UNDER_2.0", 2.0, "Under Book"),
            ),
            homeProbability = 0.50,
            drawProbability = 0.25,
            awayProbability = 0.25,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = analysis,
        ).single()
        val halfLineTip = rankApiFootballValueTips(
            quotes = listOf(
                ApiFootballOddsQuote(123, "TOTAL_GOALS", "UNDER_2.5", 2.2, "Under Book"),
            ),
            homeProbability = 0.50,
            drawProbability = 0.25,
            awayProbability = 0.25,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = analysis,
        ).single()

        assertEquals(0.8075, integerLineTip.modelProbability, 0.000001)
        assertEquals(0.15, integerLineTip.pushProbability, 0.000001)
        assertEquals(0.61625, integerLineTip.selectionProbability, 0.000001)
        assertEquals(0.3825, integerLineTip.expectedValue, 0.000001)
        assertEquals(0.9575, halfLineTip.modelProbability, 0.000001)
        assertEquals(0.0, halfLineTip.pushProbability, 0.000001)
    }

    @Test
    fun selectsOneBestTipByRiskAdjustedValue() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.9, "Best EV"),
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
        assertEquals(0.2125, selected?.expectedValue ?: 0.0, 0.000001)
    }

    @Test
    fun balanceScoreAvoidsBothVeryShortOddsAndLongshots() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.2, "Very short"),
                ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "YES", 2.0, "Balanced"),
                ApiFootballOddsQuote(123, "BOTH_TEAMS_SCORE", "NO", 5.0, "Longshot"),
            ),
            homeProbability = 0.70,
            drawProbability = 0.20,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
            analysis = sampleAnalysis().copy(bothTeamsToScoreProbability = 0.75),
        )

        assertEquals("BOTH_TEAMS_SCORE", selected?.market)
        assertEquals("YES", selected?.outcome)
        assertEquals(2.0, selected?.decimalOdds ?: 0.0, 0.0)
        assertTrue((selected?.expectedValue ?: 0.0) > 0.0)
    }

    @Test
    fun marketAndModelChanceCanQualifyWithoutAFixedOddsBand() {
        val oddsAtTwo = selectBestApiFootballValueTip(
            quotes = listOf(quote("HOME", 2.0, "Two is allowed")),
            homeProbability = 0.75,
            drawProbability = 0.15,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
        )
        val shortOdds = selectBestApiFootballValueTip(
            quotes = listOf(quote("HOME", 1.2, "Short but very likely")),
            homeProbability = 0.99,
            drawProbability = 0.005,
            awayProbability = 0.005,
            fetchedAt = "2026-10-23T12:00:00Z",
        )
        assertEquals(2.0, oddsAtTwo?.decimalOdds ?: 0.0, 0.0)
        assertEquals(1.2, shortOdds?.decimalOdds ?: 0.0, 0.0)
        assertTrue((oddsAtTwo?.qualityScore ?: 0.0) > (shortOdds?.qualityScore ?: 0.0))
    }

    @Test
    fun hasNoFixedOddsFloorWhenTheEstimatedReturnIsPositive() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(quote("HOME", 1.2, "Strong model probability")),
            homeProbability = 0.99,
            drawProbability = 0.005,
            awayProbability = 0.005,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(1.2, selected?.decimalOdds ?: 0.0, 0.0)
        assertTrue((selected?.expectedValue ?: 0.0) > 0.0)
    }

    @Test
    fun marketConsensusNaturallyRejectsLowOddsDoubleChanceAgainstClearFavorite() {
        val rankedTips = rankApiFootballValueTips(
            quotes = listOf(
                quote("HOME", 4.0, "Market Book"),
                quote("DRAW", 3.5, "Market Book"),
                quote("AWAY", 1.6, "Market Book"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.3, "Market Book"),
            ),
            homeProbability = 0.65,
            drawProbability = 0.25,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertFalse(rankedTips.any { it.market == "DOUBLE_CHANCE" })
    }

    @Test
    fun marketConsensusSupportsDoubleChanceIncludingClearFavorite() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                quote("HOME", 4.0, "Market Book"),
                quote("DRAW", 3.5, "Market Book"),
                quote("AWAY", 1.6, "Market Book"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 1.8, "Market Book"),
                ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "DRAW_AWAY", 1.8, "Market Book"),
            ),
            homeProbability = 0.20,
            drawProbability = 0.20,
            awayProbability = 0.60,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals("DRAW_AWAY", selected?.outcome)
        assertTrue((selected?.selectionProbability ?: 0.0) > 0.75)
    }

    @Test
    fun removesBookmakerOverroundBeforeBlendingMarketProbability() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                quote("HOME", 2.0, "Market Book"),
                quote("DRAW", 3.0, "Market Book"),
                quote("AWAY", 4.0, "Market Book"),
            ),
            homeProbability = 0.75,
            drawProbability = 0.15,
            awayProbability = 0.10,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals("HOME", selected?.outcome)
        assertEquals((0.75 + (0.5 / (0.5 + 1.0 / 3.0 + 0.25))) / 2.0,
            selected?.selectionProbability ?: 0.0, 0.000001)
    }

    @Test
    fun marketAdjustedForecastCorrectsStrongLocalDisagreementWithClearMarketFavorite() {
        val adjusted = marketAdjustedApiFootball1X2Probabilities(
            fixtureId = 123,
            homeProbability = 0.69,
            drawProbability = 0.20,
            awayProbability = 0.11,
            quotes = listOf(
                quote("HOME", 8.0, "Market Book"),
                quote("DRAW", 5.0, "Market Book"),
                quote("AWAY", 1.4, "Market Book"),
            ),
        ) ?: error("A complete 1X2 market should adjust the forecast.")

        assertTrue(adjusted[2] > adjusted[0])
        assertTrue(adjusted[0] < 0.69)
        assertEquals(1.0, adjusted.sum(), 0.000001)
    }

    @Test
    fun marketAdjustedForecastRequiresCompleteMatchingMarketAndValidModelProbabilities() {
        val incompleteQuotes = listOf(
            quote("HOME", 2.0, "Market Book"),
            quote("AWAY", 4.0, "Market Book"),
        )

        assertEquals(
            null,
            marketAdjustedApiFootball1X2Probabilities(
                fixtureId = 123,
                homeProbability = 0.55,
                drawProbability = 0.30,
                awayProbability = 0.15,
                quotes = incompleteQuotes,
            ),
        )
        assertEquals(
            null,
            marketAdjustedApiFootball1X2Probabilities(
                fixtureId = 123,
                homeProbability = 0.55,
                drawProbability = 0.30,
                awayProbability = 0.15,
                quotes = listOf(
                    quote("HOME", 2.0, "Market Book"),
                    quote("DRAW", 3.0, "Market Book"),
                    quote("AWAY", 4.0, "Market Book"),
                ).map { it.copy(fixtureId = 456) },
            ),
        )
        assertEquals(
            null,
            marketAdjustedApiFootball1X2Probabilities(
                fixtureId = 123,
                homeProbability = 0.55,
                drawProbability = 0.30,
                awayProbability = 0.30,
                quotes = listOf(
                    quote("HOME", 2.0, "Market Book"),
                    quote("DRAW", 3.0, "Market Book"),
                    quote("AWAY", 4.0, "Market Book"),
                ),
            ),
        )
    }

    @Test
    fun usesPayoutAsABoundedBonusWhenChancesAreSimilar() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(
                quote("HOME", 1.7, "Lower EV"),
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
    fun positiveValueTipStillQualifiesAtSixtyTwoPercentWinProbability() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(ApiFootballOddsQuote(123, "DOUBLE_CHANCE", "HOME_DRAW", 2.0, "High EV")),
            homeProbability = 0.50,
            drawProbability = 0.24,
            awayProbability = 0.26,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals("HOME_DRAW", selected?.outcome)
        assertEquals(0.62, selected?.selectionProbability ?: 0.0, 0.000001)
    }

    @Test
    fun excludesPositiveEvTipsBelowSixtyPercentWinProbability() {
        val assessedChance = (0.69 + 1.0 / 2.0) / 2.0
        val expectedValueAtAssessedChance = assessedChance * (2.0 - 1.0) - (1.0 - assessedChance)
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(quote("HOME", 2.0, "High payout")),
            homeProbability = 0.69,
            drawProbability = 0.16,
            awayProbability = 0.15,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(0.595, assessedChance, 0.000001)
        assertTrue(expectedValueAtAssessedChance > 0.0)
        assertEquals(null, selected)
    }

    @Test
    fun includesTipAtTheSixtyPercentProbabilityBoundary() {
        val selected = selectBestApiFootballValueTip(
            quotes = listOf(quote("HOME", 2.0, "Boundary")),
            homeProbability = 0.70,
            drawProbability = 0.15,
            awayProbability = 0.15,
            fetchedAt = "2026-10-23T12:00:00Z",
        )

        assertEquals(0.60, selected?.selectionProbability ?: 0.0, 0.000001)
    }

    @Test
    fun returnsNoTipWhenNoQuoteHasPositiveConservativeValue() {
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
    fun ranksTopTenDailyTipsByCombinedChanceThenValueAndExcludesOtherDates() {
        val today = LocalDate.parse("2026-10-23")
        val fixtures = (0..11).map { index ->
            dailyFixture(
                fixtureId = index + 1L,
                date = today,
                homeProbability = 0.75 + index * 0.01,
            )
        } + listOf(
            dailyFixture(100, today, homeProbability = 0.80, decimalOdds = 2.3),
            dailyFixture(101, today.plusDays(1), homeProbability = 0.99),
            dailyFixture(102, today, homeProbability = 0.85, decimalOdds = 2.0),
        )

        val tips = selectTopApiFootballValueTips(fixtures, today, today)

        assertEquals(10, tips.size)
        assertTrue(tips.all { it.fixture.matchDate == today.toString() })
        assertTrue(tips.zipWithNext().all { (left, right) ->
            left.tip.qualityScore + 0.000001 >= right.tip.qualityScore &&
                (left.tip.qualityScore - right.tip.qualityScore > 0.000001 ||
                    left.tip.selectionProbability + 0.000001 >= right.tip.selectionProbability)
        })
    }

    @Test
    fun topTenSoftlyDiversifiesRepeatedUnderTipsWhenOtherQualifiedMarketsExist() {
        val today = LocalDate.parse("2026-10-23")
        val candidates = (1L..10L).flatMap { fixtureId ->
            val fixture = dailyFixture(fixtureId, today, homeProbability = 0.75)
            listOf(
                TopValueTipEntry(fixture, testTip("TOTAL_GOALS", "UNDER_2.5", 0.80)),
                TopValueTipEntry(fixture, testTip("BOTH_TEAMS_SCORE", "YES", 0.72)),
                TopValueTipEntry(fixture, testTip("1X2", "HOME", 0.70)),
            )
        }

        val selected = selectDiversifiedTopValueTips(candidates, limit = 10)

        assertEquals(10, selected.size)
        assertEquals(10, selected.map { it.fixture.providerFixtureId }.toSet().size)
        assertTrue(selected.count {
            it.tip.market == "TOTAL_GOALS" && it.tip.outcome.startsWith("UNDER_")
        } < selected.size)
        assertTrue(selected.any { it.tip.market == "BOTH_TEAMS_SCORE" })
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

    private fun testTip(market: String, outcome: String, qualityScore: Double) =
        ApiFootballValueTip(
            market = market,
            outcome = outcome,
            decimalOdds = 2.0,
            modelProbability = 0.70,
            selectionProbability = 0.65,
            pushProbability = 0.0,
            expectedValue = 0.20,
            qualityScore = qualityScore,
            bookmaker = "Test Book",
            fetchedAt = "2026-10-23T12:00:00Z",
        )

    private fun dailyFixture(
        fixtureId: Long,
        date: LocalDate,
        homeProbability: Double,
        decimalOdds: Double = 2.0,
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
