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

    @Test
    fun headToHeadNormalizationKeepsOnlyCompletedMeetingsBeforeTheUpcomingMatch() {
        val expected = remoteFixture().copy(
            fixture = remoteFixture().fixture.copy(date = "2026-09-20T18:00:00+00:00"),
        )
        val wrongOpponent = remoteFixture().copy(
            fixture = remoteFixture().fixture.copy(id = 345679, date = "2026-09-21T18:00:00+00:00"),
            teams = ApiFootballTeamsDto(
                home = ApiFootballTeamDto(33, "Home"),
                away = ApiFootballTeamDto(55, "Other"),
            ),
        )
        val upcoming = remoteFixture().copy(
            fixture = remoteFixture().fixture.copy(id = 345680, date = "2026-10-25T18:00:00+00:00"),
        )
        val unfinished = remoteFixture().copy(
            fixture = remoteFixture().fixture.copy(
                id = 345681,
                date = "2026-09-22T18:00:00+00:00",
                status = ApiFootballStatusDto("NS"),
            ),
            goals = null,
        )

        val matches = normalizeApiFootballHeadToHead(
            listOf(expected, wrongOpponent, upcoming, unfinished),
            homeTeamId = 33,
            awayTeamId = 44,
            beforeDate = "2026-10-24",
            updatedAt = "2026-10-23T00:00:00Z",
        )

        assertEquals(listOf(345678L), matches.map { it.providerFixtureId })
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
