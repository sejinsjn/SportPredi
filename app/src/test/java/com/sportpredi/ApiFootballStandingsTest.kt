package com.sportpredi

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFootballStandingsTest {
    @Test
    fun standingsQueryUsesStableLeagueAndSeasonContext() {
        assertEquals(
            linkedMapOf("league" to "39", "season" to "2025"),
            apiFootballStandingsQueryParameters(39, 2025),
        )
    }

    @Test
    fun normalizesStandingsFormAndStatisticsAndRejectsOtherSeasonRows() {
        val fixture = ApiFootballStandingsResponseDto(
            league = ApiFootballStandingsLeagueDto(
                id = 39,
                season = 2025,
                standings = listOf(
                    listOf(
                        ApiFootballStandingDto(
                            rank = 1,
                            team = ApiFootballTeamDto(33, "Manchester City"),
                            points = 70,
                            goalsDiff = 42,
                            group = "Premier League",
                            form = "WWDLW",
                            all = ApiFootballStandingStatsDto(
                                played = 30,
                                win = 22,
                                draw = 4,
                                lose = 4,
                                goals = ApiFootballStandingGoalsDto(72, 30),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val otherSeason = fixture.copy(
            league = fixture.league?.copy(season = 2024),
        )

        val normalized = normalizeApiFootballStandings(
            listOf(fixture, otherSeason),
            leagueId = 39,
            seasonYear = 2025,
        )

        assertEquals(
            listOf(
                ApiFootballStandingRow(
                    teamId = 33,
                    teamName = "Manchester City",
                    rank = 1,
                    points = 70,
                    played = 30,
                    wins = 22,
                    draws = 4,
                    losses = 4,
                    goalsFor = 72,
                    goalsAgainst = 30,
                    form = "WWDLW",
                    group = "Premier League",
                ),
            ),
            normalized,
        )
        assertEquals("S  S  U  N  S", apiFootballFormLabel(normalized.single().form))
    }

    @Test
    fun emptyOrUnparseableProviderFormIsUnavailable() {
        assertNull(apiFootballFormLabel(null))
        assertNull(apiFootballFormLabel("—"))
    }

    @Test
    fun standingsCacheFreshnessHonorsTtlAndRejectsFutureOrInvalidDates() {
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val ttl = Duration.ofHours(24)

        assertTrue(apiFootballStandingsCacheIsFresh("2026-10-23T00:00:00Z", now, ttl))
        assertFalse(apiFootballStandingsCacheIsFresh("2026-10-22T11:59:00Z", now, ttl))
        assertFalse(apiFootballStandingsCacheIsFresh("2026-10-24T00:00:00Z", now, ttl))
        assertFalse(apiFootballStandingsCacheIsFresh("not-a-date", now, ttl))
    }
}
