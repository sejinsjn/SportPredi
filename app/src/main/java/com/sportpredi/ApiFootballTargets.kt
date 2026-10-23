package com.sportpredi

internal data class ApiFootballTargetCompetition(
    val providerId: Int,
    val name: String,
    val type: String,
)

internal data class ApiFootballTargetCatalogSelection(
    val available: List<ApiFootballLeagueDto>,
    val missing: List<ApiFootballTargetCompetition>,
)

internal val API_FOOTBALL_TARGET_COMPETITIONS = listOf(
    ApiFootballTargetCompetition(39, "Premier League", "League"),
    ApiFootballTargetCompetition(140, "La Liga", "League"),
    ApiFootballTargetCompetition(78, "Bundesliga", "League"),
    ApiFootballTargetCompetition(135, "Serie A", "League"),
    ApiFootballTargetCompetition(61, "Ligue 1", "League"),
    ApiFootballTargetCompetition(45, "FA Cup", "Cup"),
    ApiFootballTargetCompetition(143, "Copa del Rey", "Cup"),
    ApiFootballTargetCompetition(81, "DFB-Pokal", "Cup"),
    ApiFootballTargetCompetition(137, "Coppa Italia", "Cup"),
    ApiFootballTargetCompetition(66, "Coupe de France", "Cup"),
    ApiFootballTargetCompetition(2, "UEFA Champions League", "Cup"),
    ApiFootballTargetCompetition(3, "UEFA Europa League", "Cup"),
    ApiFootballTargetCompetition(848, "UEFA Conference League", "Cup"),
)

internal const val API_FOOTBALL_TARGET_CATALOG_CHECKED_KEY = "apiFootballTargetCatalogCheckedAt"
internal const val API_FOOTBALL_TARGET_CATALOG_AVAILABLE_KEY = "apiFootballTargetCatalogAvailableIds"
internal const val API_FOOTBALL_TARGET_CATALOG_MISSING_KEY = "apiFootballTargetCatalogMissingIds"
internal const val API_FOOTBALL_TARGET_CURRENT_SEASONS_KEY = "apiFootballTargetCurrentSeasonIds"
internal const val API_FOOTBALL_CATALOG_JSON_KEY = "apiFootballFullCatalogJson"
internal const val API_FOOTBALL_SELECTED_COMPETITIONS_KEY = "apiFootballSelectedCompetitionIds"
internal const val API_FOOTBALL_SELECTED_TEAMS_KEY = "apiFootballSelectedTeamIds"
internal const val API_FOOTBALL_ALL_TEAMS_SENTINEL = "*"
internal const val API_FOOTBALL_SELECTION_SAVED_KEY = "apiFootballSelectionSaved"

private val apiFootballTargetCompetitionIds =
    API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }.toSet()

internal fun normalizeApiFootballCatalog(
    catalog: List<ApiFootballLeagueDto>,
): List<ApiFootballLeagueDto> =
    catalog.asSequence()
        .filter { it.league.id > 0 && it.league.name.isNotBlank() }
        .filter { isSupportedApiFootballCompetitionType(it.league.type) }
        .distinctBy { it.league.id }
        .sortedWith(
            compareBy<ApiFootballLeagueDto> { it.country?.name.orEmpty().lowercase() }
                .thenBy { it.league.name.lowercase() }
                .thenBy { it.league.id },
        )
        .toList()

internal fun defaultApiFootballCompetitionSelection(
    catalog: List<ApiFootballLeagueDto>,
): Set<Int> =
    API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }
        .filter { id -> catalog.any { it.league.id == id } }
        .toSet()

internal fun isApiFootballFixtureSelected(
    competitionId: Int,
    homeTeamId: Int?,
    awayTeamId: Int?,
    selectedCompetitionIds: Set<Int>,
    selectedTeamIds: Set<Int>?,
): Boolean =
    competitionId in selectedCompetitionIds &&
        (
            selectedTeamIds == null ||
                homeTeamId?.let(selectedTeamIds::contains) == true ||
                awayTeamId?.let(selectedTeamIds::contains) == true
            )

internal fun parseApiFootballTeamSelection(value: String?): Set<Int>? =
    when {
        value == null || value == API_FOOTBALL_ALL_TEAMS_SENTINEL -> null
        else -> value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

internal fun serializeApiFootballIds(ids: Set<Int>): String =
    ids.filter { it > 0 }.sorted().joinToString(",")

internal fun isApiFootballTargetCompetition(providerId: Int): Boolean =
    providerId in apiFootballTargetCompetitionIds

internal fun selectApiFootballTargetCompetitions(
    catalog: List<ApiFootballLeagueDto>,
): ApiFootballTargetCatalogSelection {
    val byId = catalog
        .filter { it.league.id > 0 }
        .groupBy { it.league.id }
    val available = mutableListOf<ApiFootballLeagueDto>()
    val missing = mutableListOf<ApiFootballTargetCompetition>()
    API_FOOTBALL_TARGET_COMPETITIONS.forEach { target ->
        val match = byId[target.providerId]
            ?.firstOrNull { it.league.type.equals(target.type, ignoreCase = true) }
        if (match == null) {
            missing += target
        } else {
            available += match
        }
    }
    return ApiFootballTargetCatalogSelection(available, missing)
}

internal fun targetCatalogAvailabilityMessage(
    missing: List<ApiFootballTargetCompetition>,
): String? = missing.takeIf { it.isNotEmpty() }?.joinToString(
    prefix = "API-Football-Katalog liefert nicht: ",
    separator = ", ",
) { "${it.name} (${it.providerId})" }

internal fun parseApiFootballTargetIds(value: String?): Set<Int> =
    value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
