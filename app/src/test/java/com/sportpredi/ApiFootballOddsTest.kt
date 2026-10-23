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
                                name = "Over/Under",
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

    private fun quote(outcome: String, decimalOdds: Double, bookmaker: String) =
        ApiFootballOddsQuote(123, "1X2", outcome, decimalOdds, bookmaker)

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
