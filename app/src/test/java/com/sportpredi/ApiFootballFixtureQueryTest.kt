package com.sportpredi

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiFootballFixtureQueryTest {
    @Test
    fun dateRangeIncludesRequiredLeagueAndSeasonContext() {
        val query = apiFootballFixtureQueryParameters(
            leagueId = 61,
            seasonYear = 2019,
            from = "2026-10-23",
            to = "2026-11-06",
        )

        assertEquals(
            linkedMapOf(
                "league" to "61",
                "season" to "2019",
                "from" to "2026-10-23",
                "to" to "2026-11-06",
            ),
            query,
        )
    }

    @Test
    fun historicalQueryUsesLeagueAndSeasonWithoutDateRange() {
        assertEquals(
            linkedMapOf("league" to "61", "season" to "2019"),
            apiFootballFixtureQueryParameters(leagueId = 61, seasonYear = 2019),
        )
    }

    @Test
    fun headToHeadQueryUsesStableTeamPairAndLookbackWithoutLeagueFilters() {
        assertEquals(
            linkedMapOf("h2h" to "33-44", "last" to "10"),
            apiFootballHeadToHeadQueryParameters(33, 44),
        )
    }

    @Test
    fun firstSeasonBackfillRequestsLeagueSeasonOnlyForFullLocalHistory() {
        assertEquals(
            linkedMapOf("league" to "39", "season" to "2026"),
            apiFootballCurrentFixtureQueryParameters(
                leagueId = 39,
                seasonYear = 2026,
                today = LocalDate.parse("2026-10-23"),
                historyDays = 14,
                upcomingDays = 14,
                fullSeasonBackfill = true,
            ),
        )
    }

    @Test
    fun subsequentCurrentSeasonRefreshIncludesRecentResultsAndUpcomingFixtures() {
        assertEquals(
            linkedMapOf(
                "league" to "39",
                "season" to "2026",
                "from" to "2026-10-09",
                "to" to "2026-11-06",
            ),
            apiFootballCurrentFixtureQueryParameters(
                leagueId = 39,
                seasonYear = 2026,
                today = LocalDate.parse("2026-10-23"),
                historyDays = 14,
                upcomingDays = 14,
                fullSeasonBackfill = false,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialDateRange() {
        apiFootballFixtureQueryParameters(
            leagueId = 61,
            seasonYear = 2019,
            from = "2026-10-23",
        )
    }
}
