package com.sportpredi

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import java.io.IOException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class SyncProgress(
    val completedSeasons: Int,
    val totalSeasons: Int,
    val message: String,
)

data class LocalSyncReport(
    val seasonsChecked: Int,
    val fixturesImported: Int,
    val errors: List<String>,
    val completedAt: String,
    val openFootballFilesProcessed: Int,
    val openFootballPendingFiles: Int,
)

internal fun syncFailureMessage(exception: Throwable): String {
    val seen = mutableSetOf<Throwable>()
    var cause: Throwable? = exception
    while (cause != null && seen.add(cause)) {
        if (cause is UnknownHostException) {
            return "DNS-Auflösung zum Datenanbieter fehlgeschlagen. Prüfe Internetverbindung oder DNS; " +
                "lokal gespeicherte Daten bleiben verfügbar."
        }
        cause = cause.cause
    }
    return exception.message ?: "Daten konnten nicht abgerufen werden."
}

class LocalSyncRepository(
    private val context: Context,
    private val database: SportPrediDatabase,
    private val api: OpenLigaDbApi = OpenLigaDbApi.create(),
    private val openFootballImporter: OpenFootballJsonImporter = OpenFootballJsonImporter(database),
) {
    private val dao = database.sportPrediDao()
    private var lastRequestElapsed = 0L

    suspend fun initializeBundledHistory(
        onProgress: (String) -> Unit = {},
    ): HistoricalBundleReport = syncMutex.withLock {
        openFootballImporter.importBundledHistoricalData(context, onProgress)
    }

    suspend fun synchronize(
        force: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): LocalSyncReport = syncMutex.withLock {
        val now = Instant.now()
        val nowText = now.toString()
        val errors = mutableListOf<String>()
        dao.upsertSyncMetadata(SyncMetadataEntity("syncStatus", "Synchronisiere Wettbewerbskatalog …"))
        try {
            val available = requestWithRetry { api.availableLeagues() }
            val footballLeagues = available.filter { league ->
                league.leagueShortcut.isNotBlank() &&
                    league.leagueName.isNotBlank() &&
                    league.sport?.sportName?.lowercase() in setOf("football", "soccer", "fußball", "fussball")
            }
            val latestSeasonByShortcut = footballLeagues
                .groupBy { it.leagueShortcut }
                .mapValues { (_, seasons) ->
                    seasons.filter { it.leagueSeason <= now.atZone(ZoneOffset.UTC).year }
                        .maxByOrNull { it.leagueSeason }
                        ?: seasons.minBy { it.leagueSeason }
                }
            val competitions = latestSeasonByShortcut.map { (shortcut, season) ->
                CompetitionEntity(
                    providerCode = shortcut,
                    providerId = season.leagueId,
                    name = cleanCompetitionName(season.leagueName),
                    country = countryForShortcut(shortcut, season.leagueName),
                    updatedAt = nowText,
                )
            }
            val distinctSeasons = footballLeagues
                .groupBy { it.leagueShortcut to it.leagueSeason }
                .map { (_, sameSeason) -> sameSeason.maxBy { it.leagueId } }
            val currentYears = latestSeasonByShortcut.mapValues { it.value.leagueSeason }
            val seasons = distinctSeasons.map { league ->
                val isCurrent = currentYears[league.leagueShortcut] == league.leagueSeason
                SeasonEntity(
                    id = seasonKey(league.leagueShortcut, league.leagueSeason),
                    competitionCode = league.leagueShortcut,
                    providerLeagueId = league.leagueId,
                    seasonYear = league.leagueSeason,
                    isCurrent = isCurrent,
                    lastSuccessfulFetch = dao.season(
                        seasonKey(league.leagueShortcut, league.leagueSeason),
                    )?.lastSuccessfulFetch,
                    lastProviderCheck = dao.season(
                        seasonKey(league.leagueShortcut, league.leagueSeason),
                    )?.lastProviderCheck,
                    providerRevision = dao.season(
                        seasonKey(league.leagueShortcut, league.leagueSeason),
                    )?.providerRevision,
                )
            }
            database.withTransaction {
                dao.upsertCompetitions(competitions)
                dao.upsertSeasons(seasons)
            }
            val dueSeasons = seasons.mapNotNull { season ->
                val prior = dao.season(season.id)
                val lastCheck = prior?.lastProviderCheck?.let(::parseInstant)
                val recent = season.isCurrent || season.seasonYear >= now.atZone(ZoneOffset.UTC).year
                val interval = if (recent) Duration.ofMinutes(15) else Duration.ofDays(30)
                val openFootballSourceExists = OpenFootballJsonImporter
                    .openFootballCodesForOpenLigaShortcut(season.competitionCode)
                    .any { leagueCode ->
                        dao.season("openfootball:$leagueCode:${season.seasonYear}") != null
                    }
                val due = force ||
                    prior?.lastSuccessfulFetch == null ||
                    lastCheck == null ||
                    Duration.between(lastCheck, now) >= interval
                if (due && !openFootballSourceExists) season.copy(
                    lastSuccessfulFetch = prior?.lastSuccessfulFetch,
                    lastProviderCheck = prior?.lastProviderCheck,
                    providerRevision = prior?.providerRevision,
                ) else null
            }.sortedWith(
                compareByDescending<SeasonEntity> {
                    it.isCurrent || it.seasonYear >= now.atZone(ZoneOffset.UTC).year
                }.thenBy { it.lastSuccessfulFetch != null }
                    .thenByDescending { it.seasonYear },
            ).take(MAX_SEASONS_PER_OPEN)

            var importedFixtures = 0
            var checkedSeasons = 0
            onProgress(SyncProgress(0, dueSeasons.size, "Wettbewerbe gefunden: ${competitions.size}"))
            for (season in dueSeasons) {
                try {
                    val previous = dao.season(season.id)
                    val revision = try {
                        requestWithRetry {
                            api.lastChanged(season.competitionCode, season.seasonYear)
                        }
                    } catch (exception: HttpException) {
                        if (exception.code() != 404) throw exception
                        Log.w(
                            TAG,
                            "No change-revision endpoint for ${season.competitionCode} ${season.seasonYear}; " +
                                "fetching its schedule directly.",
                        )
                        null
                    }
                    val checkedAt = Instant.now().toString()
                    if (
                        previous?.lastSuccessfulFetch != null &&
                        revision != null &&
                        revision == previous.providerRevision
                    ) {
                        dao.upsertSeason(
                            season.copy(
                                lastSuccessfulFetch = previous.lastSuccessfulFetch,
                                lastProviderCheck = checkedAt,
                                providerRevision = revision,
                            ),
                        )
                        checkedSeasons += 1
                        onProgress(
                            SyncProgress(
                                checkedSeasons,
                                dueSeasons.size,
                                "${season.competitionCode} ${season.seasonYear}: unverändert",
                            ),
                        )
                        continue
                    }
                    val matches = requestWithRetry {
                        api.matches(season.competitionCode, season.seasonYear)
                    }
                    val teams = matches.flatMap { match ->
                        listOfNotNull(
                            match.team1?.toEntity(),
                            match.team2?.toEntity(),
                        )
                    }.distinctBy { it.providerTeamId }
                    val fixtures = matches.mapNotNull { match ->
                        match.toEntity(season, checkedAt)
                    }
                    val existingFixtures = dao.fixturesForSeason(season.id)
                        .associateBy { it.providerFixtureId }
                    val scheduleUpdated = fixtures.map { fixture ->
                        val previousFixture = existingFixtures[fixture.providerFixtureId]
                        val moved = previousFixture != null &&
                            (
                                previousFixture.matchDate != fixture.matchDate ||
                                    (
                                        previousFixture.kickoffAt != null &&
                                            fixture.kickoffAt != null &&
                                            previousFixture.kickoffAt != fixture.kickoffAt
                                        )
                                )
                        fixture.copy(rescheduled = previousFixture?.rescheduled == true || moved)
                    }
                    database.withTransaction {
                        dao.upsertTeams(teams)
                        dao.upsertFixtures(scheduleUpdated)
                        dao.upsertSeason(
                            season.copy(
                                lastSuccessfulFetch = checkedAt,
                                lastProviderCheck = checkedAt,
                                providerRevision = revision,
                            ),
                        )
                        dao.evaluateFinishedPredictionsForSeason(season.id, checkedAt)
                    }
                    importedFixtures += fixtures.size
                    checkedSeasons += 1
                    onProgress(
                        SyncProgress(
                            checkedSeasons,
                            dueSeasons.size,
                            "${season.competitionCode} ${season.seasonYear}: ${fixtures.size} Spiele",
                        ),
                    )
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    val message = "${season.competitionCode} ${season.seasonYear}: ${syncFailureMessage(exception)}"
                    errors += message
                    Log.e(TAG, "Season import failed: $message", exception)
                    checkedSeasons += 1
                    onProgress(SyncProgress(checkedSeasons, dueSeasons.size, message))
                }
            }
            val openFootballReport = try {
                openFootballImporter.synchronize(
                    openLigaSeasons = footballLeagues
                        .map { it.leagueShortcut.lowercase() to it.leagueSeason }
                        .toSet(),
                    forceCatalogCheck = force,
                ) { message ->
                    onProgress(SyncProgress(checkedSeasons, dueSeasons.size, message))
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val message = "OpenFootball: ${syncFailureMessage(exception)}"
                Log.e(TAG, message, exception)
                OpenFootballSyncReport(0, 0, 0, listOf(message))
            }
            errors += openFootballReport.errors
            importedFixtures += openFootballReport.fixturesImported
            if (openFootballReport.pendingFiles > 0) {
                onProgress(
                    SyncProgress(
                        checkedSeasons,
                        dueSeasons.size,
                        "OpenFootball: ${openFootballReport.pendingFiles} weitere Dateien warten auf Import",
                    ),
                )
            }
            val completedAt = Instant.now().toString()
            val status = if (errors.isEmpty()) "Aktualisiert: $completedAt" else "Teilweise aktualisiert: ${errors.size} Fehler"
            dao.upsertSyncMetadata(SyncMetadataEntity("lastSyncAt", completedAt))
            dao.upsertSyncMetadata(SyncMetadataEntity("lastSyncError", errors.joinToString("\n")))
            dao.upsertSyncMetadata(SyncMetadataEntity("syncStatus", status))
            val report = LocalSyncReport(
                seasonsChecked = checkedSeasons,
                fixturesImported = importedFixtures,
                errors = errors,
                completedAt = completedAt,
                openFootballFilesProcessed = openFootballReport.filesProcessed,
                openFootballPendingFiles = openFootballReport.pendingFiles,
            )
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    "lastSyncReport",
                    "$checkedSeasons Spielzeiten geprüft, $importedFixtures Spiele importiert",
                ),
            )
            report
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            val message = syncFailureMessage(exception)
            Log.e(TAG, "OpenLigaDB discovery failed", exception)
            val openFootballReport = try {
                openFootballImporter.synchronize(
                    emptySet(),
                    forceCatalogCheck = force,
                ) { progress ->
                    onProgress(SyncProgress(0, 0, progress))
                }
            } catch (fallbackException: Exception) {
                if (fallbackException is CancellationException) throw fallbackException
                val fallbackMessage =
                    "OpenFootball: ${syncFailureMessage(fallbackException)}"
                Log.e(TAG, fallbackMessage, fallbackException)
                OpenFootballSyncReport(0, 0, 0, listOf(fallbackMessage))
            }
            val errors = listOf("OpenLigaDB: $message") + openFootballReport.errors
            val completedAt = Instant.now().toString()
            val status = if (openFootballReport.fixturesImported > 0) {
                "OpenLigaDB nicht erreichbar; OpenFootball teilweise aktualisiert"
            } else {
                "Synchronisierung fehlgeschlagen"
            }
            dao.upsertSyncMetadata(SyncMetadataEntity("lastSyncAt", completedAt))
            dao.upsertSyncMetadata(SyncMetadataEntity("lastSyncError", errors.joinToString("\n")))
            dao.upsertSyncMetadata(SyncMetadataEntity("syncStatus", status))
            LocalSyncReport(
                seasonsChecked = 0,
                fixturesImported = openFootballReport.fixturesImported,
                errors = errors,
                completedAt = completedAt,
                openFootballFilesProcessed = openFootballReport.filesProcessed,
                openFootballPendingFiles = openFootballReport.pendingFiles,
            )
        }
    }

    private suspend fun waitForProviderSlot() {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestElapsed
        val waitMillis = MINIMUM_REQUEST_INTERVAL_MS - elapsed
        if (lastRequestElapsed != 0L && waitMillis > 0) delay(waitMillis)
        lastRequestElapsed = SystemClock.elapsedRealtime()
    }

    private suspend fun <T> requestWithRetry(request: suspend () -> T): T {
        repeat(MAX_RETRIES + 1) { attempt ->
            waitForProviderSlot()
            try {
                return request()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val retryable = exception is IOException ||
                    (exception is HttpException && (exception.code() == 429 || exception.code() >= 500))
                if (!retryable || attempt == MAX_RETRIES) throw exception
                val retryAfter = (exception as? HttpException)
                    ?.response()
                    ?.headers()
                    ?.get("Retry-After")
                    ?.toLongOrNull()
                    ?.times(1_000L)
                delay(retryAfter ?: (INITIAL_RETRY_DELAY_MS * (1L shl attempt)).coerceAtMost(MAX_RETRY_DELAY_MS))
            }
        }
        error("Provider request retry loop exited unexpectedly.")
    }

    private fun cleanCompetitionName(value: String): String =
        value.replace(Regex("\\s+\\d{4}(?:/\\d{2,4})?$"), "").trim().ifEmpty { value }

    private fun seasonKey(shortcut: String, year: Int): String = "$shortcut:$year"

    private fun countryForShortcut(shortcut: String, leagueName: String): String {
        val prefix = shortcut.lowercase()
        val fromShortcut = when {
            prefix.startsWith("bl") || prefix.startsWith("dfb") -> "Germany"
            prefix.startsWith("pl") -> "England"
            prefix.startsWith("la") || prefix == "ll" || prefix.startsWith("lali") -> "Spain"
            prefix.startsWith("it") || prefix == "sa" || prefix.startsWith("seriea") -> "Italy"
            prefix.startsWith("fr") || prefix == "lg1" -> "France"
            prefix.startsWith("nl") || prefix == "eredivisie" -> "Netherlands"
            prefix.startsWith("pt") -> "Portugal"
            prefix.startsWith("be") -> "Belgium"
            prefix.startsWith("at") || prefix.startsWith("oefb") || prefix.startsWith("öfb") ||
                prefix.startsWith("obl") || prefix.startsWith("öbl") -> "Austria"
            prefix.startsWith("ch") || prefix == "asl" || prefix.startsWith("rsl") -> "Switzerland"
            prefix.startsWith("tr") || prefix == "tsl" -> "Turkey"
            else -> null
        }
        if (fromShortcut != null) return fromShortcut
        val name = leagueName.lowercase()
        return when {
            name.contains("premier league") -> "England"
            name.contains("serie a") -> "Italy"
            name.contains("la liga") -> "Spain"
            name.contains("ligue 1") -> "France"
            name.contains("eredivisie") -> "Netherlands"
            name.contains("primeira liga") -> "Portugal"
            name.contains("bundesliga") && (name.contains("österreich") || name.contains("öst.")) -> "Austria"
            name.contains("bundesliga") -> "Germany"
            name.contains("super league") && (name.contains("schweiz") || name.contains("axpo")) -> "Switzerland"
            name.contains("super lig") -> "Turkey"
            name.contains("champions league") || name.contains("europa league") -> "International"
            else -> "Unknown"
        }
    }

    private fun OpenLigaTeamDto.toEntity(): TeamEntity? {
        val id = teamId ?: return null
        val teamName = teamName?.takeIf(String::isNotBlank) ?: return null
        return TeamEntity(providerTeamId = id, name = teamName)
    }

    private fun OpenLigaMatchDto.toEntity(season: SeasonEntity, updatedAt: String): FixtureEntity? {
        if (matchId <= 0) return null
        val instant = matchDateTimeUtc?.let(::parseInstant)
        val matchDate = instant?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString()
            ?: matchDateTime?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?: return null
        val results = matchResults.orEmpty()
        val fullTime = results.firstOrNull {
            it.resultTypeKind == "After90Minutes" &&
                it.pointsTeam1 != null && it.pointsTeam2 != null
        } ?: results.filter {
            it.pointsTeam1 != null && it.pointsTeam2 != null
        }.maxByOrNull { it.resultOrderId ?: 0 }
        val halftime = results.firstOrNull { it.resultTypeKind == "HalfTime" }
        val complete = matchIsFinished && fullTime?.pointsTeam1 != null && fullTime.pointsTeam2 != null
        return FixtureEntity(
            providerFixtureId = matchId.toLong(),
            competitionCode = season.competitionCode,
            seasonId = season.id,
            homeTeamId = team1?.teamId,
            awayTeamId = team2?.teamId,
            kickoffAt = instant?.toString(),
            matchDate = matchDate,
            status = if (matchIsFinished) "FINISHED" else "SCHEDULED",
            matchday = group?.groupOrderId,
            stage = group?.groupName,
            venue = location?.locationName,
            homeScore = if (complete) fullTime?.pointsTeam1 else null,
            awayScore = if (complete) fullTime?.pointsTeam2 else null,
            halftimeHomeScore = halftime?.pointsTeam1,
            halftimeAwayScore = halftime?.pointsTeam2,
            fullTimeResult = complete,
            postponedOrCancelled = false,
            rescheduled = false,
            updatedAt = updatedAt,
        )
    }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private val syncMutex = Mutex()
        private const val TAG = "LocalSyncRepository"
        private const val MAX_SEASONS_PER_OPEN = 20
        private const val MINIMUM_REQUEST_INTERVAL_MS = 1_100L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        fun outcomeOf(home: Int, away: Int): String =
            when {
                home > away -> "HOME"
                home < away -> "AWAY"
                else -> "DRAW"
            }
    }
}
