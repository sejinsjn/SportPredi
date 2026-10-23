package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamMatchHistoryTest {
    @Test
    fun recentFormAndLastMatchesUseCompletedLocalResultsAcrossCompetitions() {
        val history = listOf(
            fixture(1, "api-football:39", "2026-10-20", 10, 20, 2, 0),
            fixture(2, "api-football:2", "2026-10-21", 30, 10, 1, 2),
            fixture(3, "api-football:45", "2026-10-22", 10, 40, 0, 1),
            fixture(4, "api-football:39", "2026-10-23", 10, 50, null, null, finished = false),
        )

        val matches = localTeamMatchSummaries(history, teamId = 10, limit = 5)

        assertEquals(listOf(3L, 2L, 1L), matches.map { it.fixtureId })
        assertEquals(listOf(40, 30, 20), matches.map { it.opponentTeamId })
        assertEquals("S  S  N", localTeamFormLabel(matches))
        assertEquals("2026-10-22 · N 0:1 · City", localTeamMatchDisplay(matches.first(), "City"))
        assertTrue(matches.all { it.outcome in setOf('W', 'D', 'L') })
    }

    private fun fixture(
        id: Long,
        competitionCode: String,
        date: String,
        homeTeamId: Int,
        awayTeamId: Int,
        homeGoals: Int?,
        awayGoals: Int?,
        finished: Boolean = true,
    ) = FixtureEntity(
        providerFixtureId = id,
        competitionCode = competitionCode,
        seasonId = "$competitionCode:2026",
        homeTeamId = homeTeamId,
        awayTeamId = awayTeamId,
        kickoffAt = "$date" + "T18:00:00Z",
        matchDate = date,
        status = if (finished) "FINISHED" else "SCHEDULED",
        matchday = null,
        stage = null,
        venue = null,
        homeScore = homeGoals,
        awayScore = awayGoals,
        halftimeHomeScore = null,
        halftimeAwayScore = null,
        fullTimeResult = finished,
        postponedOrCancelled = false,
        updatedAt = "${date}T20:00:00Z",
    )
}
