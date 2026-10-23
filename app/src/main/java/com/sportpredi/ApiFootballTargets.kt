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

private val apiFootballTargetCompetitionIds =
    API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }.toSet()

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
