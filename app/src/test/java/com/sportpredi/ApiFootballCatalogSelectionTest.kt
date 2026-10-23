package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFootballCatalogSelectionTest {
    @Test
    fun fullCatalogKeepsAllSupportedCompetitionsAndDeduplicatesStableIds() {
        val catalog = listOf(
            league(39, "Premier League", "League", listOf(2025, 2026)),
            league(450, "National Cup", "Cup", listOf(2026)),
            league(451, "Friendly matches", "Friendly", listOf(2026)),
            league(39, "Duplicate", "League", listOf(2026)),
            league(0, "Invalid", "League", listOf(2026)),
        )

        val normalized = normalizeApiFootballCatalog(catalog)

        assertEquals(setOf(39, 450), normalized.map { it.league.id }.toSet())
    }

    @Test
    fun defaultSelectionKeepsOnlyAvailableLegacyTargets() {
        val catalog = listOf(
            league(39, "Premier League", "League", listOf(2026)),
            league(450, "National Cup", "Cup", listOf(2026)),
        )

        assertEquals(setOf(39), defaultApiFootballCompetitionSelection(catalog))
    }

    @Test
    fun fixtureSelectionAppliesBothCompetitionAndOptionalTeamFilters() {
        val competitions = setOf(39, 140)
        val teams = setOf(33, 44)

        assertTrue(isApiFootballFixtureSelected(39, 33, 40, competitions, teams))
        assertFalse(isApiFootballFixtureSelected(39, 40, 45, competitions, teams))
        assertFalse(isApiFootballFixtureSelected(78, 33, 40, competitions, teams))
        assertTrue(isApiFootballFixtureSelected(39, null, 40, competitions, null))
        assertFalse(isApiFootballFixtureSelected(39, null, null, competitions, emptySet()))
    }

    @Test
    fun teamSelectionUsesAllTeamsSentinelAndStableIdSerialization() {
        assertEquals(null, parseApiFootballTeamSelection(null))
        assertEquals(null, parseApiFootballTeamSelection(API_FOOTBALL_ALL_TEAMS_SENTINEL))
        assertEquals(setOf(33, 44), parseApiFootballTeamSelection("44,33,44"))
        assertEquals("33,44", serializeApiFootballIds(setOf(44, 33)))
    }

    @Test
    fun catalogUsesOnlyExactTargetIdsAndReportsTargetsNotReturnedByProvider() {
        val catalog = listOf(
            league(39, "Premier League", "League", listOf(2025, 2026)),
            league(140, "La Liga", "League", listOf(2025, 2026)),
            league(999, "Premier League", "League", listOf(2025, 2026)),
            league(78, "Bundesliga", "League", listOf(2025, 2026)),
        )

        val selection = selectApiFootballTargetCompetitions(catalog)

        assertEquals(listOf(39, 140, 78), selection.available.map { it.league.id })
        assertEquals(
            API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }.toSet() - setOf(39, 140, 78),
            selection.missing.map { it.providerId }.toSet(),
        )
        assertFalse(selection.available.any { it.league.id == 999 })
        assertTrue(targetCatalogAvailabilityMessage(selection.missing)?.contains("Ligue 1 (61)") == true)
    }

    @Test
    fun allTopLeaguesDomesticCupsAndUefaCompetitionsAreTargeted() {
        assertEquals(
            setOf(39, 140, 78, 135, 61, 45, 143, 81, 137, 66, 2, 3, 848),
            API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }.toSet(),
        )
    }

    @Test
    fun currentSeasonsRotateAcrossExactTargetCompetitions() {
        val catalog = API_FOOTBALL_TARGET_COMPETITIONS.take(4).map { target ->
            league(target.providerId, target.name, target.type, listOf(2025, 2026)).copy(
                seasons = listOf(
                    ApiFootballSeasonDto(2025, false),
                    ApiFootballSeasonDto(2026, true),
                ),
            )
        }
        val firstRun = selectApiFootballCurrentSeasons(catalog, currentYear = 2026, requestLimit = 2)
        val states = firstRun.associate {
            it.seasonId to ApiFootballSeasonSyncState("2026-10-23T00:00:00Z", "2026-10-23T00:00:00Z")
        }
        val secondRun = selectApiFootballCurrentSeasons(
            catalog,
            currentYear = 2026,
            requestLimit = 2,
            syncStates = states,
        )

        assertEquals(setOf(39, 140), firstRun.map { it.league.league.id }.toSet())
        assertEquals(setOf(78, 135), secondRun.map { it.league.league.id }.toSet())
        assertTrue((firstRun + secondRun).all { it.seasonYear == 2026 })
    }

    @Test
    fun completedHistoricalSeasonIsSkippedAndOnlyMissingSeasonIsQueued() {
        val catalog = API_FOOTBALL_TARGET_COMPETITIONS.take(3).map { target ->
            league(target.providerId, target.name, target.type, listOf(2025, 2026)).copy(
                seasons = listOf(
                    ApiFootballSeasonDto(2025, false),
                    ApiFootballSeasonDto(2026, true),
                ),
            )
        }
        val completedState = mapOf(
            "api-football:39:2025" to ApiFootballSeasonSyncState(
                lastSuccessfulFetch = "2026-10-22T00:00:00Z",
                lastProviderCheck = "2026-10-22T00:00:00Z",
            ),
        )

        val pending = selectApiFootballHistoricalSeasons(
            leagues = catalog,
            currentYear = 2026,
            historicalYears = 1,
            seasonsPerLeague = 1,
            requestLimit = 30,
            syncStates = completedState,
            includeCurrentSeason = false,
        )

        assertEquals(setOf(140, 78), pending.map { it.league.league.id }.toSet())
        assertTrue(pending.all { it.seasonYear == 2025 })
        val afterSuccessfulImport = completedState + pending.associate {
            it.seasonId to ApiFootballSeasonSyncState("2026-10-23T00:00:00Z", "2026-10-23T00:00:00Z")
        }
        assertTrue(
            selectApiFootballHistoricalSeasons(
                leagues = catalog,
                currentYear = 2026,
                historicalYears = 1,
                seasonsPerLeague = 1,
                requestLimit = 30,
                syncStates = afterSuccessfulImport,
                includeCurrentSeason = false,
            ).isEmpty(),
        )
    }

    @Test
    fun historicalSelectionIncludesCupTargetsButNeverSimilarlyNamedNonTargets() {
        val catalog = listOf(
            league(45, "FA Cup", "Cup", listOf(2025)),
            league(143, "Copa del Rey", "Cup", listOf(2025)),
            league(999, "FA Cup", "Cup", listOf(2025)),
            league(1, "Premier League", "League", listOf(2025)),
        )

        val selected = selectApiFootballHistoricalSeasons(
            leagues = catalog,
            currentYear = 2026,
            historicalYears = 1,
            seasonsPerLeague = 1,
            requestLimit = 30,
            includeCurrentSeason = false,
        )

        assertEquals(setOf(45, 143), selected.map { it.league.league.id }.toSet())
    }

    @Test
    fun previousSeasonIsChosenRelativeToProviderCurrentSeasonNotCalendarYear() {
        val catalog = listOf(
            league(39, "Premier League", "League", listOf(2024, 2025)).copy(
                seasons = listOf(
                    ApiFootballSeasonDto(2024, false),
                    ApiFootballSeasonDto(2025, true),
                ),
            ),
        )

        val previous = selectApiFootballHistoricalSeasons(
            leagues = catalog,
            currentYear = 2026,
            historicalYears = 1,
            seasonsPerLeague = 1,
            requestLimit = 1,
            includeCurrentSeason = false,
        )

        assertEquals(2024, previous.single().seasonYear)
    }

    private fun league(
        id: Int,
        name: String,
        type: String,
        seasons: List<Int>,
    ) = ApiFootballLeagueDto(
        league = ApiFootballLeagueInfoDto(id, name, type),
        country = ApiFootballCountryDto("Country"),
        seasons = seasons.map { ApiFootballSeasonDto(it, false) },
    )
}
