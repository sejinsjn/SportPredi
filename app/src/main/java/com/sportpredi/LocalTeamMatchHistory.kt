package com.sportpredi

internal data class LocalTeamMatchSummary(
    val fixtureId: Long,
    val matchDate: String,
    val opponentTeamId: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val outcome: Char,
)

internal fun localTeamMatchSummaries(
    fixtures: List<FixtureEntity>,
    teamId: Int,
    limit: Int = 5,
): List<LocalTeamMatchSummary> {
    if (limit <= 0) return emptyList()
    return fixtures.asSequence()
        .filter {
            it.fullTimeResult &&
            it.homeTeamId != null &&
            it.awayTeamId != null &&
            it.homeScore != null &&
                it.awayScore != null &&
                (it.homeTeamId == teamId || it.awayTeamId == teamId)
        }
        .sortedWith(compareByDescending<FixtureEntity> { it.matchDate }.thenByDescending { it.kickoffAt.orEmpty() })
        .take(limit)
        .map { fixture ->
            val isHome = fixture.homeTeamId == teamId
            val goalsFor = if (isHome) fixture.homeScore!! else fixture.awayScore!!
            val goalsAgainst = if (isHome) fixture.awayScore!! else fixture.homeScore!!
            LocalTeamMatchSummary(
                fixtureId = fixture.providerFixtureId,
                matchDate = fixture.matchDate,
                opponentTeamId = if (isHome) fixture.awayTeamId!! else fixture.homeTeamId!!,
                goalsFor = goalsFor,
                goalsAgainst = goalsAgainst,
                outcome = when {
                    goalsFor > goalsAgainst -> 'W'
                    goalsFor == goalsAgainst -> 'D'
                    else -> 'L'
                },
            )
        }
        .toList()
}

internal fun localTeamFormLabel(matches: List<LocalTeamMatchSummary>): String? =
    matches.asReversed()
        .map {
            when (it.outcome) {
                'W' -> "S"
                'D' -> "U"
                'L' -> "N"
                else -> null
            }
        }
        .filterNotNull()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("  ")

internal fun localTeamMatchDisplay(
    match: LocalTeamMatchSummary,
    opponentName: String?,
): String {
    val result = when (match.outcome) {
        'W' -> "S"
        'D' -> "U"
        'L' -> "N"
        else -> "—"
    }
    return "${match.matchDate} · $result ${match.goalsFor}:${match.goalsAgainst} · " +
        (opponentName?.takeIf(String::isNotBlank) ?: "Gegner unbekannt")
}
