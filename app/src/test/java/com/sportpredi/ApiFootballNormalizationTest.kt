package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFootballNormalizationTest {
    @Test
    fun normalizesApiFixtureIdsSeasonsTeamsAndFinishedScores() {
        val mapped = normalizeApiFootballFixtures(listOf(remoteFixture()), "2026-10-23T00:00:00Z")

        assertEquals(1, mapped.size)
        val fixture = mapped.single()
        assertEquals(345678L, fixture.providerFixtureId)
        assertEquals("api-football:39", fixture.competitionCode)
        assertEquals("api-football:39:2026", fixture.seasonId)
        assertEquals(33, fixture.homeTeamId)
        assertEquals(44, fixture.awayTeamId)
        assertEquals("FINISHED", fixture.status)
        assertTrue(fixture.fullTimeResult)
        assertEquals(2, fixture.homeScore)
        assertEquals(1, fixture.awayScore)
    }

    @Test
    fun deduplicatesByProviderFixtureIdAndPrefersTheCompletedRecord() {
        val scheduled = remoteFixture().copy(
            fixture = remoteFixture().fixture.copy(status = ApiFootballStatusDto("NS")),
            goals = null,
        )
        val finished = remoteFixture()

        val normalized = normalizeApiFootballFixtures(
            listOf(scheduled, finished),
            "2026-10-23T00:00:00Z",
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.single().fullTimeResult)
        assertEquals(2, normalized.single().homeScore)
    }

    @Test
    fun skipsFixturesWithoutStableProviderTeamIds() {
        val malformed = remoteFixture().copy(
            teams = ApiFootballTeamsDto(
                home = ApiFootballTeamDto(null, "Home"),
                away = ApiFootballTeamDto(44, "Away"),
            ),
        )
        assertFalse(normalizeApiFootballFixtures(listOf(malformed), "updated").isNotEmpty())
    }

    @Test
    fun requestedSeasonContextIsUsedWhenProviderFixtureOmitsItsSeason() {
        val fixture = remoteFixture().copy(
            league = remoteFixture().league.copy(season = null),
        ).withRequestedSeasonYear(2025)

        val normalized = normalizeApiFootballFixtures(listOf(fixture), "2026-10-23T00:00:00Z")

        assertEquals("api-football:39:2025", normalized.single().seasonId)
    }

    private fun remoteFixture() = ApiFootballFixtureDto(
        fixture = ApiFootballFixtureInfoDto(
            id = 345678,
            date = "2026-10-20T18:00:00+00:00",
            status = ApiFootballStatusDto("FT"),
        ),
        league = ApiFootballFixtureLeagueDto(39, "Premier League", "England", 2026),
        teams = ApiFootballTeamsDto(
            home = ApiFootballTeamDto(33, "Home"),
            away = ApiFootballTeamDto(44, "Away"),
        ),
        goals = ApiFootballGoalsDto(2, 1),
        score = ApiFootballScoreDto(ApiFootballGoalsDto(1, 0)),
    )
}
