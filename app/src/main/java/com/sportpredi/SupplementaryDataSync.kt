package com.sportpredi

import androidx.room.withTransaction
import com.google.gson.Gson
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import retrofit2.HttpException

data class BettingOddsSelection(
    val selection: String,
    val decimalOdds: Double,
    val bookmaker: String,
)

data class SupplementarySyncReport(
    val oddsEventsSaved: Int,
    val footballMatchesEnriched: Int,
    val errors: List<String>,
)

class SupplementaryDataSync(
    private val database: SportPrediDatabase,
    private val oddsApi: TheOddsApi = TheOddsApi.create(),
    private val footballApi: ApiFootballApi = ApiFootballApi.create(),
) {
    private val dao = database.sportPrediDao()
    private val gson = Gson()

    suspend fun synchronize(credentials: ApiCredentials): SupplementarySyncReport {
        val errors = mutableListOf<String>()
        var oddsEventsSaved = 0
        var footballMatchesEnriched = 0
        if (credentials.oddsApiKey.isNotBlank()) {
            try {
                oddsEventsSaved = synchronizeOdds(credentials.oddsApiKey)
            } catch (exception: Exception) {
                errors += safeProviderError("The Odds API", exception, credentials.oddsApiKey)
            }
        }
        if (credentials.apiFootballKey.isNotBlank()) {
            try {
                footballMatchesEnriched = synchronizeFootballData(credentials.apiFootballKey)
            } catch (exception: Exception) {
                errors += safeProviderError("API-Football", exception, credentials.apiFootballKey)
            }
        }
        return SupplementarySyncReport(oddsEventsSaved, footballMatchesEnriched, errors)
    }

    private suspend fun synchronizeOdds(apiKey: String): Int {
        if (!providerSyncDue(ODDS_SYNC_KEY)) return 0
        val today = LocalDate.now()
        val fixtures = dao.futureFixtures(today.toString())
            .filter { it.matchDate <= today.plusDays(7).toString() }
        if (fixtures.isEmpty()) return 0

        val competitions = dao.allCompetitions().associateBy { it.providerCode }
        val sports = oddsApi.sports(apiKey).filter { it.active && it.key.startsWith("soccer_") }
        val localSportKeys = fixtures.mapNotNull { fixture ->
            competitions[fixture.competitionCode]?.let { competition ->
                oddsSportKey(competition, sports)?.let { it to competition.providerCode }
            }
        }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.distinct() }
            .entries
            .take(MAX_ODDS_SPORTS_PER_SYNC)

        val teamNames = dao.allTeams().associate { it.providerTeamId to it.name }
        val stored = linkedMapOf<Long, AdditionalFixtureDataEntity>()
        for ((sportKey, competitionCodes) in localSportKeys) {
            val events = oddsApi.odds(sportKey, apiKey)
            events.forEach { event ->
                val eventDate = runCatching { LocalDate.parse(event.commenceTime.take(10)) }.getOrNull()
                    ?: return@forEach
                val fixture = fixtures.firstOrNull { local ->
                    local.competitionCode in competitionCodes &&
                        kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDate.parse(local.matchDate),
                            eventDate,
                        )) <= 1 &&
                            normalizeTeam(local.homeTeamId?.let(teamNames::get).orEmpty()) ==
                        normalizeTeam(event.homeTeam) &&
                            normalizeTeam(local.awayTeamId?.let(teamNames::get).orEmpty()) ==
                        normalizeTeam(event.awayTeam)
                } ?: return@forEach
                val selections = bestOdds(event)
                if (selections.isEmpty()) return@forEach
                val previous = stored[fixture.providerFixtureId]
                    ?: dao.additionalFixtureData(fixture.providerFixtureId)
                stored[fixture.providerFixtureId] = (previous ?: emptyAdditionalData(fixture.providerFixtureId))
                    .copy(
                        oddsEventId = event.id,
                        oddsJson = gson.toJson(selections),
                        updatedAt = Instant.now().toString(),
                    )
            }
        }
        if (stored.isNotEmpty()) {
            database.withTransaction { dao.upsertAdditionalFixtureData(stored.values.toList()) }
        }
        dao.upsertSyncMetadata(SyncMetadataEntity(ODDS_SYNC_KEY, Instant.now().toString()))
        return stored.size
    }

    private suspend fun synchronizeFootballData(apiKey: String): Int {
        if (!providerSyncDue(FOOTBALL_SYNC_KEY)) return 0
        val today = LocalDate.now()
        val from = today.minusDays(3)
        val to = today.plusDays(1)
        val fixtureDates = (0 until MAX_FIXTURE_DATES_PER_SYNC)
            .map { from.plusDays(it.toLong()) }
        val remoteFixtures = buildList {
            fixtureDates.forEach { date ->
                val response = footballApi.fixtures(apiKey, date.toString())
                checkProviderErrors("API-Football", response.errors, apiKey)
                addAll(response.response.orEmpty())
            }
        }
        val localFixtures = (
            dao.completedFixtures().filter { it.matchDate in from.toString()..today.toString() } +
                dao.futureFixtures(today.toString()).filter { it.matchDate <= to.toString() }
            ).distinctBy { it.providerFixtureId }
        val teamNames = dao.allTeams().associate { it.providerTeamId to it.name }
        val competitionNames = dao.allCompetitions().associate { it.providerCode to it.name }
        val matched = remoteFixtures.mapNotNull { remote ->
            val remoteDate = runCatching {
                LocalDate.parse(remote.fixture.date.take(10))
            }.getOrNull() ?: return@mapNotNull null
            val local = localFixtures.firstOrNull { fixture ->
                LocalDate.parse(fixture.matchDate) == remoteDate &&
                    normalizeTeam(teamNames[fixture.homeTeamId].orEmpty()) ==
                    normalizeTeam(remote.teams.home?.name.orEmpty()) &&
                    normalizeTeam(teamNames[fixture.awayTeamId].orEmpty()) ==
                    normalizeTeam(remote.teams.away?.name.orEmpty()) &&
                    competitionMatches(
                        competitionNames[fixture.competitionCode].orEmpty(),
                        remote.league.name,
                    )
            } ?: return@mapNotNull null
            local to remote
        }

        var enriched = 0
        val completedToEnrich = matched
            .filter { (local, remote) ->
                local.fullTimeResult &&
                    remote.fixture.status?.short in FINISHED_STATUSES &&
                    dao.additionalFixtureData(local.providerFixtureId)?.homeXg == null
            }
            .sortedByDescending { it.first.matchDate }
            .take(MAX_STATISTICS_REQUESTS)
        for ((local, remote) in completedToEnrich) {
            try {
                val stats = footballApi.statistics(apiKey, remote.fixture.id)
                checkProviderErrors("API-Football", stats.errors, apiKey)
                val data = parseStatistics(local, remote, stats.response.orEmpty())
                if (data.hasAnyStatistics()) {
                    val previous = dao.additionalFixtureData(local.providerFixtureId)
                    dao.upsertAdditionalFixtureData(
                        listOf(
                            (previous ?: emptyAdditionalData(local.providerFixtureId)).copy(
                                apiFootballFixtureId = remote.fixture.id,
                                homeXg = data.homeXg,
                                awayXg = data.awayXg,
                                homeShots = data.homeShots,
                                awayShots = data.awayShots,
                                homePossession = data.homePossession,
                                awayPossession = data.awayPossession,
                                updatedAt = Instant.now().toString(),
                            ),
                        ),
                    )
                    enriched += 1
                }
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                if (exception is HttpException && exception.code() == 429) break
            }
        }

        val upcomingToEnrich = matched
            .filter { (local, remote) ->
                !local.fullTimeResult &&
                    !local.postponedOrCancelled &&
                    LocalDate.parse(local.matchDate) in today..today.plusDays(1) &&
                    dao.additionalFixtureData(local.providerFixtureId)?.lineupsJson == null &&
                    remote.fixture.status?.short in UPCOMING_STATUSES
            }
            .take(MAX_LINEUP_FIXTURES)
        for ((local, remote) in upcomingToEnrich) {
            try {
                val lineups = footballApi.lineups(apiKey, remote.fixture.id)
                checkProviderErrors("API-Football", lineups.errors, apiKey)
                val injuries = footballApi.injuries(apiKey, remote.fixture.id)
                checkProviderErrors("API-Football", injuries.errors, apiKey)
                val previous = dao.additionalFixtureData(local.providerFixtureId)
                dao.upsertAdditionalFixtureData(
                    listOf(
                        (previous ?: emptyAdditionalData(local.providerFixtureId)).copy(
                            apiFootballFixtureId = remote.fixture.id,
                            lineupsJson = gson.toJson(lineups.response.orEmpty()),
                            injuriesJson = gson.toJson(injuries.response.orEmpty()),
                            updatedAt = Instant.now().toString(),
                        ),
                    ),
                )
                enriched += 1
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                if (exception is HttpException && exception.code() == 429) break
            }
        }
        dao.upsertSyncMetadata(SyncMetadataEntity(FOOTBALL_SYNC_KEY, Instant.now().toString()))
        return enriched
    }

    private suspend fun providerSyncDue(key: String): Boolean {
        val lastSync = dao.syncMetadata(key)?.value?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        return lastSync == null || Duration.between(lastSync, Instant.now()) >= PROVIDER_SYNC_INTERVAL
    }

    private fun oddsSportKey(
        competition: CompetitionEntity,
        available: List<OddsApiSportDto>,
    ): String? {
        val code = competition.providerCode.lowercase()
        val key = SPORT_KEY_BY_COMPETITION_CODE[code]
        if (key != null) return available.firstOrNull { it.key == key }?.key

        val competitionName = normalizeTeam(competition.name)
        val country = normalizeTeam(competition.country)
        return available.firstOrNull { sport ->
            val title = normalizeTeam(sport.title)
            val group = normalizeTeam(sport.group)
            (title == competitionName || competitionName.contains(title) || title.contains(competitionName)) &&
                (country.isBlank() || country == group || group.contains(country) || country.contains(group))
        }?.key
    }

    private fun bestOdds(event: OddsApiEventDto): List<BettingOddsSelection> {
        val best = mutableMapOf<String, BettingOddsSelection>()
        event.bookmakers.orEmpty().forEach { bookmaker ->
            bookmaker.markets.orEmpty().forEach { market ->
                market.outcomes.orEmpty().forEach { outcome ->
                    val selection = when (market.key) {
                        "h2h" -> when {
                            normalizeTeam(outcome.name) == normalizeTeam(event.homeTeam) -> "HOME"
                            normalizeTeam(outcome.name) == normalizeTeam(event.awayTeam) -> "AWAY"
                            outcome.name.equals("Draw", ignoreCase = true) -> "DRAW"
                            else -> null
                        }
                        "totals" -> if (outcome.point == 2.5) {
                            when (outcome.name.lowercase()) {
                                "over" -> "OVER_2.5"
                                "under" -> "UNDER_2.5"
                                else -> null
                            }
                        } else {
                            null
                        }
                        else -> null
                    } ?: return@forEach
                    if (!outcome.price.isFinite() || outcome.price < 1.01) return@forEach
                    val current = best[selection]
                    if (current == null || outcome.price > current.decimalOdds) {
                        best[selection] = BettingOddsSelection(
                            selection = selection,
                            decimalOdds = outcome.price,
                            bookmaker = bookmaker.title,
                        )
                    }
                }
            }
        }
        return best.values.sortedBy { it.selection }
    }

    private suspend fun parseStatistics(
        local: FixtureEntity,
        remote: ApiFootballFixtureDto,
        response: List<ApiFootballTeamStatisticsDto>,
    ): ParsedFootballStatistics {
        val localHomeName = local.homeTeamId?.let { dao.team(it) }
        val localAwayName = local.awayTeamId?.let { dao.team(it) }
        val home = response.firstOrNull {
            normalizeTeam(it.team?.name.orEmpty()) ==
                normalizeTeam(remote.teams.home?.name.orEmpty()) &&
                (localHomeName == null || normalizeTeam(it.team?.name.orEmpty()) ==
                    normalizeTeam(localHomeName.name))
        }
        val away = response.firstOrNull {
            normalizeTeam(it.team?.name.orEmpty()) ==
                normalizeTeam(remote.teams.away?.name.orEmpty()) &&
                (localAwayName == null || normalizeTeam(it.team?.name.orEmpty()) ==
                    normalizeTeam(localAwayName.name))
        }
        return ParsedFootballStatistics(
            homeXg = home?.value("expected_goals", "expected goals", "xg")?.toDoubleOrNull(),
            awayXg = away?.value("expected_goals", "expected goals", "xg")?.toDoubleOrNull(),
            homeShots = home?.value("total_shots", "total shots")?.toIntOrNull(),
            awayShots = away?.value("total_shots", "total shots")?.toIntOrNull(),
            homePossession = home?.value("ball_possession", "ball possession")?.removeSuffix("%")?.toDoubleOrNull(),
            awayPossession = away?.value("ball_possession", "ball possession")?.removeSuffix("%")?.toDoubleOrNull(),
        )
    }

    private fun ApiFootballTeamStatisticsDto.value(vararg names: String): String? =
        statistics.orEmpty()
            .firstOrNull { item -> names.any { normalizeStatistic(item.type.orEmpty()) == normalizeStatistic(it) } }
            ?.value
            ?.takeUnless { it.isJsonNull }
            ?.asString

    private fun ParsedFootballStatistics.hasAnyStatistics(): Boolean =
        homeXg != null || awayXg != null || homeShots != null || awayShots != null ||
            homePossession != null || awayPossession != null

    private fun checkProviderErrors(
        provider: String,
        errors: com.google.gson.JsonElement?,
        apiKey: String,
    ) {
        if (errors != null && !errors.isJsonNull && errors.toString() !in setOf("{}", "[]", "\"\"")) {
            val fields = if (errors.isJsonObject) {
                errors.asJsonObject.entrySet().associate { (key, value) ->
                    key.lowercase() to value.toString().trim('"')
                }
            } else {
                mapOf("response" to errors.toString().trim('"'))
            }
            val detail = fields.values.joinToString(" ").lowercase()
            val hint = when {
                fields.keys.any { it.contains("token") || it.contains("key") } ||
                    detail.contains("unauthorized") || detail.contains("invalid api key") ->
                    "API-Schlüssel ungültig oder nicht autorisiert."
                fields.keys.any { it.contains("request") || it.contains("limit") } ||
                    detail.contains("quota") || detail.contains("rate limit") ||
                    detail.contains("maximum number of requests") ->
                    "Anfragekontingent oder Rate-Limit erreicht."
                fields.keys.any { it.contains("plan") || it.contains("subscription") } ||
                    detail.contains("subscription") || detail.contains("not available on your plan") ->
                    "Dieser Endpunkt ist möglicherweise nicht im Tarif freigeschaltet."
                fields.keys.any { it == "from" || it == "to" || it.contains("parameter") } ->
                    "Ein Anfrageparameter oder Datumsbereich wurde abgelehnt."
                else -> "Der Anbieter hat die Anfrage abgelehnt; prüfe Schlüssel, Tarif und API-Zugriff."
            }
            val fieldNames = fields.keys.take(4).joinToString()
            val safeDetail = fields.entries
                .joinToString("; ") { (field, message) -> "$field: $message" }
                .let { if (apiKey.isBlank()) it else it.replace(apiKey, "[geschwärzt]", ignoreCase = true) }
                .replace(Regex("(?i)(api[-_ ]?key|token)\\s*[:=]\\s*[^,;\\s]+"), "$1=[geschwärzt]")
                .replace(Regex("\\s+"), " ")
                .take(180)
            error(
                "$provider API-Fehler${fieldNames.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}: " +
                    "$hint Anbietertext: $safeDetail",
            )
        }
    }

    private fun safeProviderError(provider: String, exception: Exception, apiKey: String): String {
        if (exception is HttpException) {
            return "$provider konnte nicht abgerufen werden (HTTP ${exception.code()})."
        }
        val detail = exception.message
            ?.replace(apiKey, "[geschwärzt]")
            ?.replace(Regex("(?i)(apiKey=)[^&\\s]+"), "$1[geschwärzt]")
            ?.replace(Regex("\\s+"), " ")
            ?.take(180)
            ?.takeIf(String::isNotBlank)
        return if (detail == null) {
            "$provider konnte nicht abgerufen werden (${exception.javaClass.simpleName})."
        } else {
            "$provider konnte nicht abgerufen werden (${exception.javaClass.simpleName}): $detail"
        }
    }

    private fun emptyAdditionalData(fixtureId: Long) = AdditionalFixtureDataEntity(
        localFixtureId = fixtureId,
        oddsEventId = null,
        oddsJson = null,
        apiFootballFixtureId = null,
        homeXg = null,
        awayXg = null,
        homeShots = null,
        awayShots = null,
        homePossession = null,
        awayPossession = null,
        lineupsJson = null,
        injuriesJson = null,
        updatedAt = Instant.now().toString(),
    )

    private fun competitionMatches(local: String, external: String): Boolean {
        val left = normalizeTeam(local)
        val right = normalizeTeam(external)
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun normalizeStatistic(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private fun normalizeTeam(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return ascii
            .replace(Regex("\\b(football club|fc|afc|cf|sc|club)\\b"), "")
            .filter(Char::isLetterOrDigit)
    }

    private data class ParsedFootballStatistics(
        val homeXg: Double?,
        val awayXg: Double?,
        val homeShots: Int?,
        val awayShots: Int?,
        val homePossession: Double?,
        val awayPossession: Double?,
    )

    private companion object {
        const val ODDS_SYNC_KEY = "lastOddsApiSyncAt"
        const val FOOTBALL_SYNC_KEY = "lastApiFootballSyncAt"
        const val MAX_ODDS_SPORTS_PER_SYNC = 8
        const val MAX_FIXTURE_DATES_PER_SYNC = 5
        const val MAX_STATISTICS_REQUESTS = 12
        const val MAX_LINEUP_FIXTURES = 2
        val PROVIDER_SYNC_INTERVAL: Duration = Duration.ofHours(24)
        val FINISHED_STATUSES = setOf("FT", "AET", "PEN")
        val UPCOMING_STATUSES = setOf("NS", "TBD")
        val SPORT_KEY_BY_COMPETITION_CODE = mapOf(
            "bl1" to "soccer_germany_bundesliga",
            "de.1" to "soccer_germany_bundesliga",
            "en.1" to "soccer_epl",
            "es.1" to "soccer_spain_la_liga",
            "it.1" to "soccer_italy_serie_a",
            "fr.1" to "soccer_france_ligue_one",
            "ucl" to "soccer_uefa_champs_league",
            "cl" to "soccer_uefa_champs_league",
            "uel" to "soccer_uefa_europa_league",
            "el" to "soccer_uefa_europa_league",
        )
    }
}
