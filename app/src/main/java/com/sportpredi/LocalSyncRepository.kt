package com.sportpredi

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response

private val FINISHED_STATUSES = setOf("FT", "AET", "PEN")
private val CANCELLED_STATUSES = setOf("CANC", "ABD", "AWD", "WO")
private val POSTPONED_STATUSES = setOf("PST", "SUSP", "INT")

data class SyncProgress(val completedSeasons: Int, val totalSeasons: Int, val message: String)

data class LocalSyncReport(
    val competitionsChecked: Int,
    val fixturesImported: Int,
    val requestsUsed: Int,
    val errors: List<String>,
    val completedAt: String,
    val wasThrottled: Boolean = false,
    val missingTargetCompetitions: List<String> = emptyList(),
)

internal fun apiFootballStandingsCacheIsFresh(
    fetchedAt: String,
    now: Instant,
    ttl: Duration,
): Boolean {
    val fetched = runCatching { Instant.parse(fetchedAt) }.getOrNull() ?: return false
    return !fetched.isAfter(now) && fetched.plus(ttl).isAfter(now)
}

internal fun syncFailureMessage(exception: Throwable): String =
    when (exception) {
        is ApiFootballRequestLimitException -> exception.message.orEmpty()
        is ApiFootballProviderException -> exception.message.orEmpty()
        is HttpException -> {
            val details = httpProviderErrorDetails(exception)
            "API-Football-Anfrage fehlgeschlagen (HTTP ${exception.code()})" +
                if (details.isNullOrBlank()) "." else ": $details"
        }
        else -> {
            val details = exception.message?.let(::sanitizeProviderDetails)
            if (details.isNullOrBlank()) {
                "API-Football konnte nicht abgerufen werden (${exception.javaClass.simpleName})."
            } else {
                "API-Football konnte nicht abgerufen werden: $details"
            }
        }
    }

private class ApiFootballRequestLimitException(message: String) : Exception(message)
internal class ApiFootballProviderException(message: String) : Exception(message)

private val sensitiveAssignmentPattern = Regex(
    """(?i)\b(api[-_ ]?key|authorization|token|secret|password)(["']?\s*[:=]\s*["']?)([^\s,;}"']+)""",
)
private val bearerTokenPattern = Regex("""(?i)\bbearer\s+[^\s,;}"']+""")
private val longTokenPattern = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9])""")

internal fun apiFootballProviderErrorMessage(errors: JsonElement?): String? {
    if (errors == null || errors.isJsonNull) return null
    val details = renderProviderError(errors).joinToString("; ")
        .let(::sanitizeProviderDetails)
        .takeIf { it.isNotBlank() && it !in setOf("{}", "[]", "\"\"") }
        ?: return null
    val normalized = details.lowercase()
    val category = when {
        normalized.contains("limit") || normalized.contains("quota") -> "Anfragelimit erreicht."
        normalized.contains("subscription") || normalized.contains("plan") ->
            "Endpunkt laut Tarif nicht verfügbar."
        normalized.contains("unauthorized") || normalized.contains("api key") ||
            normalized.contains("api_key") || normalized.contains("key invalid") ->
            "API-Schlüssel nicht autorisiert."
        else -> "API-Football hat die Anfrage abgelehnt."
    }
    return "$category Details: $details"
}

private fun renderProviderError(element: JsonElement, path: String = ""): List<String> =
    when {
        element.isJsonObject -> element.asJsonObject.entrySet().flatMap { (name, value) ->
            val keyPath = listOf(path, name).filter { it.isNotBlank() }.joinToString(".")
            if (isSensitiveProviderField(name)) {
                listOf("$keyPath: [redacted]")
            } else {
                renderProviderError(value, keyPath)
            }
        }
        element.isJsonArray -> element.asJsonArray.flatMap { renderProviderError(it, path) }
        element.isJsonPrimitive -> {
            val value = element.asJsonPrimitive.asString
            listOf(if (path.isBlank()) value else "$path: $value")
        }
        else -> emptyList()
    }

private fun isSensitiveProviderField(name: String): Boolean =
    name.contains("key", ignoreCase = true) ||
        name.contains("token", ignoreCase = true) ||
        name.contains("secret", ignoreCase = true) ||
        name.contains("password", ignoreCase = true) ||
        name.contains("authorization", ignoreCase = true)

private fun sanitizeProviderDetails(value: String): String =
    value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(sensitiveAssignmentPattern, "$1$2[redacted]")
        .replace(bearerTokenPattern, "Bearer [redacted]")
        .replace(longTokenPattern, "[redacted]")
        .take(240)

private fun httpProviderErrorDetails(exception: HttpException): String? {
    val body = runCatching { exception.response()?.errorBody()?.string() }.getOrNull()
        ?: return null
    val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
        ?: return sanitizeProviderDetails(body).takeIf { it.isNotBlank() }
    if (parsed.isJsonObject) {
        val responseObject = parsed.asJsonObject
        val errors = responseObject.get("errors")
        if (errors != null && !errors.isJsonNull) {
            return renderProviderError(errors).joinToString("; ")
                .let(::sanitizeProviderDetails)
                .takeIf { it.isNotBlank() }
        }
        val messageFields = listOf("message", "error", "detail")
            .mapNotNull { field -> responseObject.get(field)?.takeIf { !it.isJsonNull } }
        if (messageFields.isNotEmpty()) {
            return messageFields.flatMap(::renderProviderError)
                .joinToString("; ")
                .let(::sanitizeProviderDetails)
                .takeIf { it.isNotBlank() }
        }
        return null
    }
    return renderProviderError(parsed).joinToString("; ")
        .let(::sanitizeProviderDetails)
        .takeIf { it.isNotBlank() }
}

internal fun ApiFootballFixtureDto.toLocalFixture(
    updatedAt: String,
): FixtureEntity? {
    val homeId = teams.home?.id ?: return null
    val awayId = teams.away?.id ?: return null
    if (homeId == awayId || fixture.id <= 0 || league.id <= 0) return null
    val date = runCatching { LocalDate.parse(fixture.date.take(10)) }.getOrNull() ?: return null
    val providerStatus = fixture.status?.short?.uppercase().orEmpty()
    val isFinished = providerStatus in FINISHED_STATUSES && goals?.home != null && goals.away != null
    val isCancelled = providerStatus in CANCELLED_STATUSES
    val dbStatus = when {
        isFinished -> "FINISHED"
        isCancelled -> "CANCELLED"
        providerStatus in POSTPONED_STATUSES -> "POSTPONED"
        providerStatus in setOf("TBD", "NS") -> "SCHEDULED"
        else -> providerStatus.ifBlank { "SCHEDULED" }
    }
    return FixtureEntity(
        providerFixtureId = fixture.id.toLong(),
        competitionCode = "api-football:${league.id}",
        seasonId = "api-football:${league.id}:${league.season ?: date.year}",
        homeTeamId = homeId,
        awayTeamId = awayId,
        kickoffAt = fixture.date,
        matchDate = date.toString(),
        status = dbStatus,
        matchday = null,
        stage = league.name,
        venue = fixture.venue?.name,
        homeScore = goals?.home,
        awayScore = goals?.away,
        halftimeHomeScore = score?.halftime?.home,
        halftimeAwayScore = score?.halftime?.away,
        fullTimeResult = isFinished,
        postponedOrCancelled = isCancelled || providerStatus in POSTPONED_STATUSES,
        updatedAt = updatedAt,
    )
}

internal fun ApiFootballFixtureDto.withRequestedSeasonYear(seasonYear: Int): ApiFootballFixtureDto =
    if (league.season != null) {
        this
    } else {
        copy(league = league.copy(season = seasonYear))
    }

internal fun normalizeApiFootballFixtures(
    items: List<ApiFootballFixtureDto>,
    updatedAt: String,
): List<FixtureEntity> =
    items.mapNotNull { it.toLocalFixture(updatedAt) }
        .groupBy { it.providerFixtureId }
        .values
        .map { duplicates ->
            duplicates.maxWith(
                compareBy<FixtureEntity> { it.fullTimeResult }
                    .thenBy { it.updatedAt },
            )
        }

internal fun normalizeApiFootballHeadToHead(
    items: List<ApiFootballFixtureDto>,
    homeTeamId: Int,
    awayTeamId: Int,
    beforeDate: String,
    updatedAt: String,
): List<FixtureEntity> =
    normalizeApiFootballFixtures(items, updatedAt)
        .filter { fixture ->
            fixture.matchDate < beforeDate &&
                    fixture.fullTimeResult &&
                    fixture.homeScore != null &&
                    fixture.awayScore != null &&
                    (
                        (fixture.homeTeamId == homeTeamId && fixture.awayTeamId == awayTeamId) ||
                            (fixture.homeTeamId == awayTeamId && fixture.awayTeamId == homeTeamId)
                        )
        }
        .sortedWith(compareByDescending<FixtureEntity> { it.matchDate }.thenByDescending { it.kickoffAt.orEmpty() })
        .take(10)

object ApiFootballSyncDefaults {
    const val MAX_REQUESTS_PER_SYNC = 32
    const val MAX_REQUESTS_PER_UTC_DAY = 6_500
    const val MAX_AUTHORIZED_REQUESTS_PER_UTC_DAY = 7_500
    const val DAILY_REQUEST_RESERVE = 1_000
    const val PER_MINUTE_REQUEST_RESERVE = 1
    const val UPCOMING_DAYS = 14L
    const val HISTORICAL_YEARS = 3
    const val SEASONS_PER_LEAGUE = 3
    const val MAX_UPCOMING_SEASON_REQUESTS_PER_SYNC = 13
    const val MAX_STANDINGS_REQUESTS_PER_SYNC = 10
    const val MAX_ODDS_REQUESTS_PER_SYNC = 10
    const val MAX_HISTORICAL_SEASON_REQUESTS_PER_SYNC = 30
}

data class ApiFootballSyncLimits(
    val maxRequestsPerSync: Int = ApiFootballSyncDefaults.MAX_REQUESTS_PER_SYNC,
    val maxRequestsPerUtcDay: Int = ApiFootballSyncDefaults.MAX_REQUESTS_PER_UTC_DAY,
    val dailyRequestReserve: Int = ApiFootballSyncDefaults.DAILY_REQUEST_RESERVE,
    val perMinuteRequestReserve: Int = ApiFootballSyncDefaults.PER_MINUTE_REQUEST_RESERVE,
    val upcomingDays: Long = ApiFootballSyncDefaults.UPCOMING_DAYS,
    val historicalYears: Int = ApiFootballSyncDefaults.HISTORICAL_YEARS,
    val seasonsPerLeague: Int = ApiFootballSyncDefaults.SEASONS_PER_LEAGUE,
    val maxUpcomingSeasonRequestsPerSync: Int =
        ApiFootballSyncDefaults.MAX_UPCOMING_SEASON_REQUESTS_PER_SYNC,
    val maxStandingsRequestsPerSync: Int =
        ApiFootballSyncDefaults.MAX_STANDINGS_REQUESTS_PER_SYNC,
    val maxOddsRequestsPerSync: Int = ApiFootballSyncDefaults.MAX_ODDS_REQUESTS_PER_SYNC,
    val maxHistoricalSeasonRequestsPerSync: Int =
        ApiFootballSyncDefaults.MAX_HISTORICAL_SEASON_REQUESTS_PER_SYNC,
    val standingsCacheTtl: Duration = Duration.ofHours(24),
    val oddsCacheTtl: Duration = Duration.ofHours(6),
    val cooldown: Duration = Duration.ofMinutes(15),
) {
    init {
        require(maxRequestsPerSync >= 2)
        require(maxRequestsPerUtcDay >= maxRequestsPerSync)
        require(dailyRequestReserve >= 0)
        require(perMinuteRequestReserve >= 0)
        require(
            maxRequestsPerUtcDay + dailyRequestReserve <=
                ApiFootballSyncDefaults.MAX_AUTHORIZED_REQUESTS_PER_UTC_DAY,
        )
        require(upcomingDays >= 0)
        require(historicalYears >= 0)
        require(seasonsPerLeague > 0)
        require(maxUpcomingSeasonRequestsPerSync >= 0)
        require(maxStandingsRequestsPerSync >= 0)
        require(maxOddsRequestsPerSync >= 0)
        require(maxHistoricalSeasonRequestsPerSync >= 0)
        require(!standingsCacheTtl.isNegative && !standingsCacheTtl.isZero)
        require(!oddsCacheTtl.isNegative && !oddsCacheTtl.isZero)
        require(!cooldown.isNegative)
    }
}

internal data class ApiFootballRateLimitHeaders(
    val dailyLimit: Int,
    val dailyRemaining: Int,
    val perMinuteLimit: Int,
    val perMinuteRemaining: Int,
)

internal fun parseApiFootballRateLimitHeaders(
    headers: okhttp3.Headers,
): ApiFootballRateLimitHeaders? {
    val dailyLimit = headers["x-ratelimit-requests-limit"]?.toIntOrNull() ?: return null
    val dailyRemaining = headers["x-ratelimit-requests-remaining"]?.toIntOrNull() ?: return null
    val perMinuteLimit = headers["X-RateLimit-Limit"]?.toIntOrNull() ?: return null
    val perMinuteRemaining = headers["X-RateLimit-Remaining"]?.toIntOrNull() ?: return null
    if (dailyLimit <= 0 || dailyRemaining < 0 || perMinuteLimit <= 0 || perMinuteRemaining < 0) {
        return null
    }
    return ApiFootballRateLimitHeaders(
        dailyLimit = dailyLimit,
        dailyRemaining = dailyRemaining.coerceAtMost(dailyLimit),
        perMinuteLimit = perMinuteLimit,
        perMinuteRemaining = perMinuteRemaining.coerceAtMost(perMinuteLimit),
    )
}

internal fun safeApiFootballRequestSlots(
    reportedRemaining: Int,
    configuredReserve: Int,
): Int = (reportedRemaining - configuredReserve).coerceAtLeast(0)

internal data class ApiFootballSeasonSyncState(
    val lastSuccessfulFetch: String?,
    val lastProviderCheck: String?,
)

private fun leaguePriority(providerId: Int): Int =
    API_FOOTBALL_TARGET_COMPETITIONS.indexOfFirst { it.providerId == providerId }
        .let { if (it < 0) API_FOOTBALL_TARGET_COMPETITIONS.size else it }

private fun latestSeasonSyncTime(state: ApiFootballSeasonSyncState?): Long =
    listOfNotNull(state?.lastSuccessfulFetch, state?.lastProviderCheck)
        .mapNotNull { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() }
        .maxOrNull() ?: Long.MIN_VALUE

internal data class ApiFootballHistoricalSeasonRequest(
    val league: ApiFootballLeagueDto,
    val seasonYear: Int,
) {
    val seasonId: String
        get() = "api-football:${league.league.id}:$seasonYear"
}

internal fun isSupportedApiFootballCompetitionType(type: String?): Boolean =
    type.equals("League", ignoreCase = true) || type.equals("Cup", ignoreCase = true)

internal fun selectApiFootballHistoricalSeasons(
    leagues: List<ApiFootballLeagueDto>,
    currentYear: Int,
    historicalYears: Int,
    seasonsPerLeague: Int,
    requestLimit: Int,
    syncStates: Map<String, ApiFootballSeasonSyncState> = emptyMap(),
    includeCurrentSeason: Boolean = true,
): List<ApiFootballHistoricalSeasonRequest> {
    if (requestLimit <= 0) return emptyList()
    return leagues.asSequence()
        .filter { it.league.id > 0 && it.league.name.isNotBlank() }
        .filter { isApiFootballTargetCompetition(it.league.id) }
        .filter { isSupportedApiFootballCompetitionType(it.league.type) }
        .distinctBy { it.league.id }
        .flatMap { league ->
            val activeSeasonYear = league.seasons.orEmpty()
                .filter { it.current == true || (it.current == null && it.year == currentYear) }
                .maxOfOrNull { it.year }
                ?: currentYear
            league.seasons.orEmpty()
                .asSequence()
                .map { it.year }
                .filter { it in (activeSeasonYear - historicalYears)..activeSeasonYear }
                .filter { includeCurrentSeason || it < activeSeasonYear }
                .distinct()
                .sortedDescending()
                .take(seasonsPerLeague)
                .map { year -> ApiFootballHistoricalSeasonRequest(league, year) }
        }
        .filter { syncStates[it.seasonId]?.lastSuccessfulFetch == null }
        .sortedWith(
            compareBy<ApiFootballHistoricalSeasonRequest> {
                leaguePriority(it.league.league.id)
            }.thenBy { latestSeasonSyncTime(syncStates[it.seasonId]) }
                .thenByDescending { it.seasonYear }
                .thenBy { it.league.league.id },
        )
        .take(requestLimit)
        .toList()
}

internal fun selectApiFootballCurrentSeasons(
    leagues: List<ApiFootballLeagueDto>,
    currentYear: Int,
    requestLimit: Int,
    syncStates: Map<String, ApiFootballSeasonSyncState> = emptyMap(),
): List<ApiFootballHistoricalSeasonRequest> {
    if (requestLimit <= 0) return emptyList()
    return leagues.asSequence()
        .filter { it.league.id > 0 && it.league.name.isNotBlank() }
        .filter { isApiFootballTargetCompetition(it.league.id) }
        .filter { isSupportedApiFootballCompetitionType(it.league.type) }
        .distinctBy { it.league.id }
        .mapNotNull { league ->
            val seasonYear = league.seasons.orEmpty()
                .filter { it.current == true || (it.current == null && it.year == currentYear) }
                .map { it.year }
                .maxOrNull()
                ?: return@mapNotNull null
            ApiFootballHistoricalSeasonRequest(league, seasonYear)
        }
        .sortedWith(
            compareBy<ApiFootballHistoricalSeasonRequest> {
                latestSeasonSyncTime(syncStates[it.seasonId])
            }.thenBy { leaguePriority(it.league.league.id) }
                .thenBy { it.league.league.id },
        )
        .take(requestLimit)
        .toList()
}

class LocalSyncRepository(
    private val database: SportPrediDatabase,
    private val api: ApiFootballApi = ApiFootballApi.create(),
    private val limits: ApiFootballSyncLimits = ApiFootballSyncLimits(),
) {
    private val dao = database.sportPrediDao()

    suspend fun synchronizeHeadToHead(
        apiKey: String,
        homeTeamId: Int,
        awayTeamId: Int,
        beforeDate: String,
    ): List<FixtureEntity> = syncMutex.withLock {
        val existing = dao.headToHead(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            beforeDate = beforeDate,
            limit = HEAD_TO_HEAD_LOOKBACK,
        )
        if (existing.size >= HEAD_TO_HEAD_LOOKBACK) return@withLock existing

        val now = Instant.now()
        val pairKey = listOf(homeTeamId, awayTeamId).sorted().joinToString("-")
        val cacheKey = "$HEAD_TO_HEAD_CACHE_PREFIX$pairKey"
        val cachedAt = dao.syncMetadata(cacheKey)?.value
        if (
            cachedAt != null &&
            apiFootballStandingsCacheIsFresh(cachedAt, now, HEAD_TO_HEAD_CACHE_TTL)
        ) {
            return@withLock existing
        }
        if (apiKey.isBlank()) {
            throw ApiFootballProviderException("API-Football-Schlüssel fehlt. Bitte unter Einstellungen eintragen.")
        }

        val statusResponse = api.status(apiKey)
        if (!statusResponse.isSuccessful) throw HttpException(statusResponse)
        val statusHeaders = parseApiFootballRateLimitHeaders(statusResponse.headers())
            ?: throw ApiFootballProviderException(
                "API-Football-Status enthält keine vollständigen Rate-Limit-Header; " +
                    "die Anfrage nach direkten Duellen wurde sicherheitshalber angehalten.",
            )
        val statusBody = statusResponse.body()
            ?: throw ApiFootballProviderException("API-Football-Status lieferte eine leere Antwort.")
        validateProviderResponse(statusBody.errors)
        val usage = statusBody.response?.requests
            ?: throw ApiFootballProviderException("API-Football-Status enthält keine Tagesnutzung.")
        val current = usage.current
            ?: throw ApiFootballProviderException("API-Football-Status enthält keine aktuelle Tagesnutzung.")
        val providerDailyLimit = usage.limitDay
            ?: throw ApiFootballProviderException("API-Football-Status enthält kein Tageslimit.")
        val dailyRemaining = minOf(
            statusHeaders.dailyRemaining,
            (minOf(providerDailyLimit, statusHeaders.dailyLimit) - current).coerceAtLeast(0),
        )
        if (safeApiFootballRequestSlots(dailyRemaining, limits.dailyRequestReserve) <= 0) {
            throw ApiFootballRequestLimitException(
                "API-Football-Tageslimit erreicht oder Sicherheitsabstand von " +
                    "${limits.dailyRequestReserve} Aufrufen erreicht.",
            )
        }
        if (
            safeApiFootballRequestSlots(
                statusHeaders.perMinuteRemaining,
                limits.perMinuteRequestReserve,
            ) <= 0
        ) {
            throw ApiFootballRequestLimitException(
                "API-Football-Minutenlimit erreicht oder Sicherheitsabstand erreicht.",
            )
        }

        val requestDay = LocalDate.now(ZoneOffset.UTC).toString()
        val previousUsage = dao.syncMetadata(DAILY_REQUESTS_KEY)?.value.orEmpty().split('|')
        val usedToday = if (previousUsage.size == 2 && previousUsage[0] == requestDay) {
            previousUsage[1].toIntOrNull() ?: 0
        } else {
            0
        }
        if (usedToday >= limits.maxRequestsPerUtcDay) {
            throw ApiFootballRequestLimitException(
                "Das lokale Tageslimit von ${limits.maxRequestsPerUtcDay} API-Aufrufen wurde erreicht.",
            )
        }
        dao.upsertSyncMetadata(
            SyncMetadataEntity(DAILY_REQUESTS_KEY, "$requestDay|${usedToday + 1}"),
        )

        val response = api.headToHead(
            apiKey,
            apiFootballHeadToHeadQueryParameters(homeTeamId, awayTeamId, HEAD_TO_HEAD_LOOKBACK),
        )
        if (!response.isSuccessful) throw HttpException(response)
        parseApiFootballRateLimitHeaders(response.headers())
            ?: throw ApiFootballProviderException(
                "API-Football-Antwort enthält keine vollständigen Rate-Limit-Header.",
            )
        val body = response.body()
            ?: throw ApiFootballProviderException("API-Football lieferte eine leere H2H-Antwort.")
        validateProviderResponse(body.errors)

        val fetchedAt = Instant.now().toString()
        val matchingFixtures = normalizeApiFootballHeadToHead(
            items = body.response.orEmpty(),
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            beforeDate = beforeDate,
            updatedAt = fetchedAt,
        )
        val matchingFixtureIds = matchingFixtures.map { it.providerFixtureId }.toSet()
        val matchingDtos = body.response.orEmpty()
            .filter { dto -> dto.fixture.id.toLong() in matchingFixtureIds }
        val competitions = matchingDtos
            .map { dto ->
                CompetitionEntity(
                    providerCode = "api-football:${dto.league.id}",
                    providerId = dto.league.id,
                    name = dto.league.name,
                    country = dto.league.country.orEmpty(),
                    updatedAt = fetchedAt,
                )
            }
            .distinctBy { it.providerCode }
        val teams = matchingDtos.flatMap { dto ->
            listOfNotNull(
                dto.teams.home?.id?.let { TeamEntity(it, dto.teams.home.name.orEmpty()) },
                dto.teams.away?.id?.let { TeamEntity(it, dto.teams.away.name.orEmpty()) },
            )
        }.distinctBy { it.providerTeamId }

        database.withTransaction {
            if (competitions.isNotEmpty()) dao.upsertCompetitions(competitions)
            if (teams.isNotEmpty()) dao.upsertTeams(teams)
            if (matchingFixtures.isNotEmpty()) dao.upsertFixtures(matchingFixtures)
            dao.upsertSyncMetadata(SyncMetadataEntity(cacheKey, fetchedAt))
        }
        dao.headToHead(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            beforeDate = beforeDate,
            limit = HEAD_TO_HEAD_LOOKBACK,
        )
    }

    suspend fun synchronize(
        apiKey: String,
        force: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): LocalSyncReport = syncMutex.withLock {
        synchronizeLocked(apiKey, force, onProgress)
    }

    private suspend fun synchronizeLocked(
        apiKey: String,
        force: Boolean,
        onProgress: (SyncProgress) -> Unit,
    ): LocalSyncReport {
        val now = Instant.now()
        val errors = mutableListOf<String>()
        if (apiKey.isBlank()) {
            return LocalSyncReport(0, 0, 0, listOf("API-Football-Schlüssel fehlt. Bitte unter Einstellungen eintragen."), now.toString())
        }
        val lastAttempt = dao.syncMetadata(ATTEMPT_KEY)?.value?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        if (!force && lastAttempt != null && now.minus(limits.cooldown).isBefore(lastAttempt)) {
            return LocalSyncReport(
                competitionsChecked = dao.allCompetitions().size,
                fixturesImported = 0,
                requestsUsed = 0,
                errors = emptyList(),
                completedAt = lastAttempt.toString(),
                wasThrottled = true,
            )
        }
        dao.upsertSyncMetadata(SyncMetadataEntity(ATTEMPT_KEY, now.toString()))

        var requests = 0
        var providerDailyRemaining: Int? = null
        var providerMinuteRemaining: Int? = null
        var missingTargetCompetitions = emptyList<String>()
        val requestDay = LocalDate.now(ZoneOffset.UTC).toString()
        fun updateProviderRateLimits(headers: okhttp3.Headers): ApiFootballRateLimitHeaders {
            val parsed = parseApiFootballRateLimitHeaders(headers)
                ?: throw ApiFootballProviderException(
                    "API-Football-Antwort enthält keine vollständigen Rate-Limit-Header; " +
                        "der Sync wurde sicherheitshalber angehalten.",
                )
            providerDailyRemaining = safeApiFootballRequestSlots(
                minOf(
                    parsed.dailyRemaining,
                    parsed.dailyLimit.coerceAtMost(
                        ApiFootballSyncDefaults.MAX_AUTHORIZED_REQUESTS_PER_UTC_DAY,
                    ),
                ),
                limits.dailyRequestReserve,
            )
            providerMinuteRemaining = safeApiFootballRequestSlots(
                parsed.perMinuteRemaining,
                limits.perMinuteRequestReserve,
            )
            return parsed
        }

        suspend fun countRequest() {
            if (requests >= limits.maxRequestsPerSync) {
                throw ApiFootballRequestLimitException("Das lokale Limit von ${limits.maxRequestsPerSync} API-Aufrufen pro Sync wurde erreicht.")
            }
            val dailyRemaining = providerDailyRemaining
                ?: throw ApiFootballProviderException("Tageslimit von API-Football konnte nicht verifiziert werden.")
            if (dailyRemaining <= 0) {
                throw ApiFootballRequestLimitException(
                    "Das API-Football-Tageslimit ist erreicht oder der Sicherheitsabstand von " +
                        "${limits.dailyRequestReserve} Aufrufen ist erreicht.",
                )
            }
            val minuteRemaining = providerMinuteRemaining
                ?: throw ApiFootballProviderException("Minutenlimit von API-Football konnte nicht verifiziert werden.")
            if (minuteRemaining <= 0) {
                throw ApiFootballRequestLimitException(
                    "Das API-Football-Minutenlimit ist erreicht oder der Sicherheitsabstand von " +
                        "${limits.perMinuteRequestReserve} Aufrufen ist erreicht.",
                )
            }
            val previous = dao.syncMetadata(DAILY_REQUESTS_KEY)?.value.orEmpty().split('|')
            val usedToday = if (previous.size == 2 && previous[0] == requestDay) {
                previous[1].toIntOrNull() ?: 0
            } else {
                0
            }
            if (usedToday >= limits.maxRequestsPerUtcDay) {
                throw ApiFootballRequestLimitException("Das lokale Tageslimit von ${limits.maxRequestsPerUtcDay} API-Aufrufen wurde erreicht.")
            }
            dao.upsertSyncMetadata(
                SyncMetadataEntity(DAILY_REQUESTS_KEY, "$requestDay|${usedToday + 1}"),
            )
            requests += 1
        }

        fun <T> validatedBody(response: Response<ApiFootballResponse<T>>): ApiFootballResponse<T> {
            if (!response.isSuccessful) throw HttpException(response)
            updateProviderRateLimits(response.headers())
            val body = response.body()
                ?: throw ApiFootballProviderException("API-Football lieferte eine leere Antwort.")
            validateProviderResponse(body.errors)
            return body
        }

        val timestamp = now.toString()
        val fixtureRows = mutableListOf<ApiFootballFixtureDto>()
        var competitionsChecked = 0
        try {
            val statusResponse = api.status(apiKey)
            if (!statusResponse.isSuccessful) throw HttpException(statusResponse)
            val statusHeaders = updateProviderRateLimits(statusResponse.headers())
            val statusBody = statusResponse.body()
                ?: throw ApiFootballProviderException("API-Football-Status lieferte eine leere Antwort.")
            validateProviderResponse(statusBody.errors)
            val requestUsage = statusBody.response?.requests
                ?: throw ApiFootballProviderException(
                    "API-Football-Status enthält keine Tagesnutzung; der Sync wurde angehalten.",
                )
            val providerCurrent = requestUsage.current
                ?: throw ApiFootballProviderException("API-Football-Status enthält keine aktuelle Tagesnutzung.")
            val providerLimit = requestUsage.limitDay
                ?: throw ApiFootballProviderException("API-Football-Status enthält kein Tageslimit.")
            providerDailyRemaining = safeApiFootballRequestSlots(
                minOf(
                    statusHeaders.dailyRemaining,
                    (minOf(providerLimit, statusHeaders.dailyLimit) - providerCurrent).coerceAtLeast(0),
                    ApiFootballSyncDefaults.MAX_AUTHORIZED_REQUESTS_PER_UTC_DAY,
                ),
                limits.dailyRequestReserve,
            )

            onProgress(SyncProgress(0, 2, "API-Football-Wettbewerbe werden geladen …"))
            countRequest()
            val catalog = validatedBody(api.leagues(apiKey))
            val targetSelection = selectApiFootballTargetCompetitions(catalog.response.orEmpty())
            val leagues = targetSelection.available
            missingTargetCompetitions = targetSelection.missing.map { "${it.name} (${it.providerId})" }
            competitionsChecked = leagues.size
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    API_FOOTBALL_TARGET_CATALOG_CHECKED_KEY,
                    timestamp,
                ),
            )
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    API_FOOTBALL_TARGET_CATALOG_AVAILABLE_KEY,
                    leagues.map { it.league.id }.sorted().joinToString(","),
                ),
            )
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    API_FOOTBALL_TARGET_CATALOG_MISSING_KEY,
                    targetSelection.missing.map { it.providerId }.sorted().joinToString(","),
                ),
            )

            val today = LocalDate.now(ZoneOffset.UTC)
            val previousSeasonStates = dao.allSeasons().associate {
                it.id to ApiFootballSeasonSyncState(it.lastSuccessfulFetch, it.lastProviderCheck)
            }
            val availableAfterCatalog = minOf(
                limits.maxRequestsPerSync - requests,
                providerDailyRemaining ?: 0,
                providerMinuteRemaining ?: 0,
            )
            val currentSeasons = selectApiFootballCurrentSeasons(
                leagues = leagues,
                currentYear = today.year,
                requestLimit = minOf(
                    limits.maxUpcomingSeasonRequestsPerSync,
                    (availableAfterCatalog + 1) / 2,
                ),
                syncStates = previousSeasonStates,
            )
            val catalogCurrentSeasonIds = selectApiFootballCurrentSeasons(
                leagues = leagues,
                currentYear = today.year,
                requestLimit = API_FOOTBALL_TARGET_COMPETITIONS.size,
            ).map { it.league.league.id }.toSet()
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    API_FOOTBALL_TARGET_CURRENT_SEASONS_KEY,
                    catalogCurrentSeasonIds.sorted().joinToString(","),
                ),
            )
            val attemptedSeasonRequests = mutableListOf<ApiFootballHistoricalSeasonRequest>()
            val fullyFetchedSeasonIds = mutableSetOf<String>()

            onProgress(SyncProgress(1, 2, "Zielwettbewerbe und aktuelle Spielzeiten werden geladen …"))
            for ((index, request) in currentSeasons.withIndex()) {
                if (requests >= limits.maxRequestsPerSync) break
                try {
                    countRequest()
                    attemptedSeasonRequests += request
                    val isFullSeasonBackfill =
                        previousSeasonStates[request.seasonId]?.lastSuccessfulFetch == null
                    val response = api.fixtures(
                        apiKey,
                        apiFootballCurrentFixtureQueryParameters(
                            leagueId = request.league.league.id,
                            seasonYear = request.seasonYear,
                            today = today,
                            historyDays = limits.upcomingDays,
                            upcomingDays = limits.upcomingDays,
                            fullSeasonBackfill = isFullSeasonBackfill,
                        ),
                    )
                    fixtureRows += validatedBody(response).response.orEmpty()
                        .map { it.withRequestedSeasonYear(request.seasonYear) }
                    if (isFullSeasonBackfill) fullyFetchedSeasonIds += request.seasonId
                } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    errors += syncFailureMessage(exception)
                    break
                }
                onProgress(
                    SyncProgress(index + 2, currentSeasons.size + 2, "Anstehende Spielzeiten ${index + 1}/${currentSeasons.size}"),
                )
            }

            val historySeasons = selectApiFootballHistoricalSeasons(
                leagues = leagues,
                currentYear = today.year,
                historicalYears = minOf(limits.historicalYears, 1),
                seasonsPerLeague = 1,
                requestLimit = minOf(
                    limits.maxHistoricalSeasonRequestsPerSync,
                    limits.maxRequestsPerSync - requests,
                    providerDailyRemaining ?: 0,
                    providerMinuteRemaining ?: 0,
                ),
                syncStates = previousSeasonStates,
                includeCurrentSeason = false,
            )

            for ((index, request) in historySeasons.withIndex()) {
                if (errors.isNotEmpty()) break
                if (requests >= limits.maxRequestsPerSync) break
                try {
                    countRequest()
                    attemptedSeasonRequests += request
                    val response = api.fixtures(
                        apiKey,
                        apiFootballFixtureQueryParameters(
                            leagueId = request.league.league.id,
                            seasonYear = request.seasonYear,
                        ),
                    )
                    val responseBody = validatedBody(response)
                    fixtureRows += responseBody.response.orEmpty()
                        .map { it.withRequestedSeasonYear(request.seasonYear) }
                    fullyFetchedSeasonIds += request.seasonId
                } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    errors += syncFailureMessage(exception)
                    break
                }
                onProgress(
                    SyncProgress(index + 2, historySeasons.size + 2, "Historische Spielzeiten ${index + 1}/${historySeasons.size}"),
                )
            }
            if (errors.isEmpty()) {
                val standingsToRefresh = currentSeasons.filter { request ->
                    val cached = dao.apiFootballStandings(request.seasonId)
                    cached == null ||
                        !apiFootballStandingsCacheIsFresh(
                            fetchedAt = cached.fetchedAt,
                            now = Instant.now(),
                            ttl = limits.standingsCacheTtl,
                        )
                }.take(limits.maxStandingsRequestsPerSync)
                for (request in standingsToRefresh) {
                    if (requests >= limits.maxRequestsPerSync) break
                    try {
                        countRequest()
                        val response = api.standings(
                            apiKey,
                            apiFootballStandingsQueryParameters(
                                leagueId = request.league.league.id,
                                seasonYear = request.seasonYear,
                            ),
                        )
                        val rows = normalizeApiFootballStandings(
                            response = validatedBody(response).response.orEmpty(),
                            leagueId = request.league.league.id,
                            seasonYear = request.seasonYear,
                        )
                        dao.upsertApiFootballStandingsCache(
                            ApiFootballStandingsCacheEntity(
                                seasonId = request.seasonId,
                                rowsJson = Gson().toJson(rows),
                                fetchedAt = Instant.now().toString(),
                            ),
                        )
                    } catch (exception: Exception) {
                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                        errors += syncFailureMessage(exception)
                        break
                    }
                }
            }
            if (errors.isEmpty()) {
                val fixtureCandidates = apiFootballOddsEligibleFixtures(
                    fixtures = normalizeApiFootballFixtures(fixtureRows, timestamp),
                    today = today,
                    upcomingDays = limits.upcomingDays,
                )
                val cachedOdds = fixtureCandidates.mapNotNull { fixture ->
                    dao.apiFootballOdds(fixture.providerFixtureId)
                        ?.let { fixture.providerFixtureId to it }
                }.toMap()
                val oddsLimit = safeApiFootballOddsRequestSlots(
                    maxRequestsPerSync = limits.maxRequestsPerSync,
                    requestsUsed = requests,
                    dailyRemainingAfterReserve = providerDailyRemaining ?: 0,
                    minuteRemainingAfterReserve = providerMinuteRemaining ?: 0,
                    maxOddsRequestsPerSync = limits.maxOddsRequestsPerSync,
                )
                val oddsCandidates = selectApiFootballOddsCandidates(
                    fixtures = fixtureCandidates,
                    cachedByFixtureId = cachedOdds,
                    today = today,
                    upcomingDays = limits.upcomingDays,
                    now = Instant.now(),
                    cacheTtl = limits.oddsCacheTtl,
                    requestLimit = oddsLimit,
                )
                for (fixture in oddsCandidates) {
                    if (requests >= limits.maxRequestsPerSync) break
                    try {
                        countRequest()
                        val response = api.odds(apiKey, fixture.providerFixtureId)
                        val normalized = normalizeApiFootballOdds(
                            response = validatedBody(response).response.orEmpty(),
                            requestedFixtureId = fixture.providerFixtureId,
                        )
                        val status = when {
                            !normalized.fixtureFound -> ApiFootballOddsCacheStatus.NO_ODDS
                            normalized.quotes.isEmpty() -> ApiFootballOddsCacheStatus.NO_1X2_QUOTES
                            else -> ApiFootballOddsCacheStatus.AVAILABLE
                        }
                        dao.upsertApiFootballOddsCache(
                            ApiFootballOddsCacheEntity(
                                providerFixtureId = fixture.providerFixtureId,
                                quotesJson = Gson().toJson(normalized.quotes),
                                status = status,
                                fetchedAt = Instant.now().toString(),
                            ),
                        )
                    } catch (exception: Exception) {
                        if (exception is kotlinx.coroutines.CancellationException) throw exception
                        errors += syncFailureMessage(exception)
                        break
                    }
                }
            }
            persistFixtures(
                remoteFixtures = fixtureRows,
                attemptedSeasonRequests = attemptedSeasonRequests,
                fullyFetchedSeasonIds = fullyFetchedSeasonIds,
                currentSeasonIds = currentSeasons.map { it.seasonId }.toSet(),
                previousSeasonStates = previousSeasonStates,
                updatedAt = timestamp,
            )
            if (errors.isEmpty()) dao.upsertSyncMetadata(SyncMetadataEntity(SYNC_KEY, timestamp))
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            errors += syncFailureMessage(exception)
        }
        return LocalSyncReport(
            competitionsChecked = competitionsChecked,
            fixturesImported = normalizeApiFootballFixtures(fixtureRows, timestamp).size,
            requestsUsed = requests,
            errors = errors.distinct(),
            completedAt = timestamp,
            missingTargetCompetitions = missingTargetCompetitions,
        )
    }

    private suspend fun persistFixtures(
        remoteFixtures: List<ApiFootballFixtureDto>,
        attemptedSeasonRequests: List<ApiFootballHistoricalSeasonRequest>,
        fullyFetchedSeasonIds: Set<String>,
        currentSeasonIds: Set<String>,
        previousSeasonStates: Map<String, ApiFootballSeasonSyncState>,
        updatedAt: String,
    ) {
        val fixtures = normalizeApiFootballFixtures(remoteFixtures, updatedAt)
        val leaguesById = (remoteFixtures.map { it.league }.map { league ->
            league.id to Triple(league.name, league.country.orEmpty(), league.season)
        } + attemptedSeasonRequests.map { request ->
            request.league.league.id to Triple(
                request.league.league.name,
                request.league.country?.name.orEmpty(),
                request.seasonYear,
            )
        }).toMap()
        if (leaguesById.isEmpty() && fixtures.isEmpty()) return
        val observedSeasons = mutableMapOf<String, Pair<Int, Int>>()
        fixtures.forEach { fixture ->
            val leagueId = fixture.competitionCode.substringAfterLast(':').toIntOrNull()
            val year = fixture.seasonId.substringAfterLast(':').toIntOrNull()
            if (leagueId != null && year != null) {
                observedSeasons[fixture.seasonId] = leagueId to year
            }
        }
        attemptedSeasonRequests.forEach { request ->
            observedSeasons[request.seasonId] = request.league.league.id to request.seasonYear
        }
        database.withTransaction {
            dao.upsertCompetitions(
                leaguesById.map { (leagueId, values) ->
                    CompetitionEntity(
                        providerCode = "api-football:$leagueId",
                        providerId = leagueId,
                        name = values.first,
                        country = values.second,
                        updatedAt = updatedAt,
                    )
                },
            )
            dao.upsertSeasons(
                observedSeasons.map { (seasonId, leagueAndYear) ->
                    val (leagueId, seasonYear) = leagueAndYear
                    val previous = previousSeasonStates[seasonId]
                    SeasonEntity(
                        id = seasonId,
                        competitionCode = "api-football:$leagueId",
                        providerLeagueId = leagueId,
                        seasonYear = seasonYear,
                        isCurrent = seasonId in currentSeasonIds,
                        lastSuccessfulFetch = if (seasonId in fullyFetchedSeasonIds) {
                            updatedAt
                        } else {
                            previous?.lastSuccessfulFetch
                        },
                        lastProviderCheck = if (attemptedSeasonRequests.any { it.seasonId == seasonId }) {
                            updatedAt
                        } else {
                            previous?.lastProviderCheck
                        },
                        providerRevision = "API-Football",
                    )
                },
            )
            dao.upsertTeams(
                remoteFixtures.flatMap { listOfNotNull(it.teams.home, it.teams.away) }
                    .filter { it.id != null && !it.name.isNullOrBlank() }
                    .distinctBy { it.id }
                    .map { TeamEntity(it.id!!, it.name!!) },
            )
            dao.upsertFixtures(fixtures)
        }
    }

    private fun validateProviderResponse(errors: JsonElement?) {
        apiFootballProviderErrorMessage(errors)?.let { throw ApiFootballProviderException(it) }
    }

    companion object {
        internal const val MAX_REQUESTS_PER_SYNC = ApiFootballSyncDefaults.MAX_REQUESTS_PER_SYNC
        internal const val MAX_REQUESTS_PER_UTC_DAY = ApiFootballSyncDefaults.MAX_REQUESTS_PER_UTC_DAY
        private const val SYNC_KEY = "lastApiFootballSyncAt"
        private const val ATTEMPT_KEY = "lastApiFootballAttemptAt"
        private const val DAILY_REQUESTS_KEY = "apiFootballRequestsToday"
        private const val HEAD_TO_HEAD_CACHE_PREFIX = "apiFootballHeadToHead:"
        private const val HEAD_TO_HEAD_LOOKBACK = 10
        private val HEAD_TO_HEAD_CACHE_TTL = Duration.ofDays(7)
        private val syncMutex = Mutex()
    }
}
