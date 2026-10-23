package com.sportpredi

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteStatement
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class OpenFootballSyncReport(
    val filesProcessed: Int,
    val fixturesImported: Int,
    val pendingFiles: Int,
    val errors: List<String>,
)

data class HistoricalBundleReport(
    val filesProcessed: Int,
    val fixturesImported: Int,
    val filesSkipped: Int,
    val errors: List<String>,
)

private data class PendingOpenFootballFile(
    val path: String,
    val sha: String,
)

private data class HistoricalBundleManifest(
    val formatVersion: Int = 0,
    val sourceRevision: String = "",
    val files: List<HistoricalBundleFile> = emptyList(),
)

private data class HistoricalBundleFile(
    val path: String = "",
    val sha: String = "",
)

internal fun stableOpenFootballFixtureId(value: String): Long {
    val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val positive = hash.take(8).fold(0L) { result, byte ->
        (result shl 8) or (byte.toLong() and 0xff)
    } and Long.MAX_VALUE
    return -positive.coerceAtLeast(1L)
}

internal fun openFootballFixtureId(
    competitionCode: String,
    seasonYear: Int,
    round: String?,
    homeTeam: String?,
    awayTeam: String?,
    matchDate: String,
): Long {
    val fixtureKey = listOf(
        competitionCode,
        seasonYear.toString(),
        normalizeOpenFootballName(round.orEmpty()),
        normalizeOpenFootballName(homeTeam.orEmpty()),
        normalizeOpenFootballName(awayTeam.orEmpty()),
    ).joinToString("|") + if (homeTeam == null || awayTeam == null) "|$matchDate" else ""
    return stableOpenFootballFixtureId("fixture:$fixtureKey")
}

private fun normalizeOpenFootballName(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), "")

class OpenFootballJsonImporter(
    private val database: SportPrediDatabase,
    private val api: OpenFootballJsonApi = OpenFootballJsonApi.create(),
) {
    private val dao = database.sportPrediDao()
    private val gson = Gson()
    private val requestMutex = Mutex()
    private var lastRequestElapsed = 0L

    suspend fun importBundledHistoricalData(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): HistoricalBundleReport = requestMutex.withLock {
        migrateLegacyFixtureIds()
        val errors = mutableListOf<String>()
        var processed = 0
        var fixturesImported = 0
        var skipped = 0
        ZipInputStream(context.assets.open(HISTORICAL_ASSET_PATH).buffered()).use { archive ->
            val manifestEntry = archive.nextEntry
                ?: throw IllegalStateException("Das historische Datenarchiv ist leer.")
            require(manifestEntry.name == "manifest.json") {
                "Das historische Datenarchiv enthält kein Manifest an erster Stelle."
            }
            val manifest = gson.fromJson(
                archive.readBytes().toString(Charsets.UTF_8),
                HistoricalBundleManifest::class.java,
            ) ?: throw IllegalStateException("Das Manifest des historischen Datenarchivs ist leer.")
            require(
                manifest.formatVersion == 1 &&
                    manifest.sourceRevision.matches(Regex("^[a-f0-9]{40}$")) &&
                    manifest.files.isNotEmpty()
            ) {
                "Das Manifest des historischen Datenarchivs ist unvollständig."
            }
            if (dao.syncMetadata(HISTORICAL_BUNDLE_COMPLETE_KEY)?.value == manifest.sourceRevision) {
                return@withLock HistoricalBundleReport(0, 0, 0, emptyList())
            }
            archive.closeEntry()
            val filesByPath = manifest.files.associateBy { it.path }
            require(filesByPath.size == manifest.files.size) {
                "Das historische Manifest enthält doppelte Dateipfade."
            }
            require(manifest.files.all {
                SEASON_FILE_PATTERN.matches(it.path) && it.sha.matches(Regex("^[a-f0-9]{40}$"))
            }) {
                "Das historische Manifest enthält ungültige Dateimetadaten."
            }
            val seenPaths = mutableSetOf<String>()
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && entry.name.startsWith("football.json/")) {
                    val path = entry.name.removePrefix("football.json/")
                    if (!seenPaths.add(path)) {
                        errors += "Historisches Archiv enthält einen doppelten Dateipfad: $path"
                        archive.closeEntry()
                        continue
                    }
                    val metadata = filesByPath[path]
                    if (metadata == null) {
                        errors += "Historisches Archiv enthält eine nicht deklarierte Datei: $path"
                        archive.closeEntry()
                        continue
                    }
                    val alreadyImported = dao.syncMetadata(
                        "$HISTORICAL_BUNDLE_FILE_PREFIX${manifest.sourceRevision}:$path",
                    )?.value == metadata.sha
                    if (alreadyImported) {
                        markBundledFile(manifest.sourceRevision, path, metadata.sha)
                        skipped += 1
                        archive.closeEntry()
                        continue
                    }
                    val source = archive.readBytes()
                    archive.closeEntry()
                    processed += 1
                    try {
                        require(gitBlobSha(source) == metadata.sha) {
                            "Quellprüfsumme stimmt nicht überein."
                        }
                        val seasonYear = seasonYear(path)
                            ?: throw IllegalArgumentException("Ungültiger Spielzeitenpfad: $path")
                        val leagueCode = path.substringAfter('/').removeSuffix(".json")
                        if (
                            hasOpenLigaCoverage(leagueCode, seasonYear)
                        ) {
                            markBundledFile(manifest.sourceRevision, path, metadata.sha)
                            skipped += 1
                            continue
                        }
                        val file = try {
                            gson.fromJson(source.toString(Charsets.UTF_8), OpenFootballFileDto::class.java)
                        } catch (exception: JsonParseException) {
                            throw IllegalArgumentException("Ungültiges JSON in $path.", exception)
                        } ?: throw IllegalArgumentException("Leere Datendatei: $path")
                        fixturesImported += importFile(
                            path,
                            metadata.sha,
                            leagueCode,
                            seasonYear,
                            file,
                            preserveExistingFixtures = true,
                        )
                        markBundledFile(manifest.sourceRevision, path, metadata.sha)
                        onProgress("Historischer Import $path: ${file.matches.orEmpty().size} Spiele")
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        val message = "Historischer Import $path: ${exception.message ?: "Import fehlgeschlagen"}"
                        errors += message
                        Log.e(TAG, message, exception)
                        onProgress(message)
                    }
                } else {
                    archive.closeEntry()
                }
            }
            val missingPaths = filesByPath.keys - seenPaths
            if (missingPaths.isNotEmpty()) {
                errors += "Historisches Archiv ist unvollständig: ${missingPaths.size} Dateien fehlen."
            }
            if (errors.isEmpty()) {
                dao.upsertSyncMetadata(SyncMetadataEntity(HISTORICAL_BUNDLE_COMPLETE_KEY, manifest.sourceRevision))
            }
        }
        HistoricalBundleReport(processed, fixturesImported, skipped, errors)
    }

    suspend fun synchronize(
        openLigaSeasons: Set<Pair<String, Int>>,
        forceCatalogCheck: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): OpenFootballSyncReport = requestMutex.withLock {
        migrateLegacyFixtureIds()
        val errors = mutableListOf<String>()
        var pending = readPendingFiles()
        val lastCatalogCheck = dao.syncMetadata(CATALOG_CHECK_KEY)?.value
            ?.let(::parseInstant)
        val shouldRefreshCatalog = forceCatalogCheck || lastCatalogCheck == null ||
            java.time.Duration.between(lastCatalogCheck, Instant.now()) >= CATALOG_REFRESH_INTERVAL
        if (shouldRefreshCatalog) {
            try {
                val tree = requestWithRetryValue { api.tree() }
                require(!tree.truncated) { "GitHub hat den Football-JSON-Dateibaum gekürzt." }
                val importedShas = mutableMapOf<String, String>()
                dao.allSyncMetadata()
                    .filter { it.key.startsWith(IMPORT_SHA_PREFIX) }
                    .forEach { row -> importedShas[row.key.removePrefix(IMPORT_SHA_PREFIX)] = row.value }
                pending = tree.tree
                    .asSequence()
                    .filter { it.type == "blob" && SEASON_FILE_PATTERN.matches(it.path) }
                    .filter { entry -> importedShas[entry.path] != entry.sha }
                    .map { PendingOpenFootballFile(it.path, it.sha) }
                    .sortedWith(compareByDescending<PendingOpenFootballFile> { seasonYear(it.path) ?: 0 }.thenBy { it.path })
                    .toList()
                dao.upsertSyncMetadata(SyncMetadataEntity(CATALOG_CHECK_KEY, Instant.now().toString()))
                dao.upsertSyncMetadata(SyncMetadataEntity(TREE_SHA_KEY, tree.sha))
                savePendingFiles(pending)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val message = "OpenFootball-Katalog: ${syncFailureMessage(exception)}"
                errors += message
                Log.e(TAG, message, exception)
            }
        }

        var processed = 0
        var importedFixtures = 0
        val batch = pending.take(MAX_FILES_PER_SYNC)
        for (entry in batch) {
            processed += 1
            try {
                val seasonYear = seasonYear(entry.path)
                    ?: throw IllegalArgumentException("Ungültiger Spielzeitenpfad: ${entry.path}")
                val leagueCode = entry.path.substringAfter('/').removeSuffix(".json")
                if (
                    isCoveredByOpenLiga(leagueCode, seasonYear, openLigaSeasons) &&
                    dao.season("openfootball:$leagueCode:$seasonYear") == null
                ) {
                    markImported(entry)
                    pending = pending.filterNot { it.path == entry.path }
                    savePendingFiles(pending)
                    onProgress("OpenFootball ${entry.path}: bereits von OpenLigaDB abgedeckt")
                    continue
                }

                val source = requestWithRetryValue { api.file(entry.path) }.use { it.string() }
                val file = try {
                    gson.fromJson(source, OpenFootballFileDto::class.java)
                } catch (exception: JsonParseException) {
                    throw IllegalArgumentException("Ungültiges JSON in ${entry.path}.", exception)
                } ?: throw IllegalArgumentException("Leere Datendatei: ${entry.path}")
                val imported = importFile(entry.path, entry.sha, leagueCode, seasonYear, file)
                importedFixtures += imported
                markImported(entry)
                pending = pending.filterNot { it.path == entry.path }
                savePendingFiles(pending)
                onProgress("OpenFootball ${entry.path}: $imported Spiele")
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val message = "OpenFootball ${entry.path}: ${syncFailureMessage(exception)}"
                errors += message
                Log.e(TAG, message, exception)
                pending = pending.filterNot { it.path == entry.path } + entry
                savePendingFiles(pending)
                onProgress(message)
            }
        }
        OpenFootballSyncReport(processed, importedFixtures, pending.size, errors)
    }

    private suspend fun importFile(
        path: String,
        sha: String,
        leagueCode: String,
        seasonYear: Int,
        file: OpenFootballFileDto,
        preserveExistingFixtures: Boolean = false,
    ): Int {
        val now = Instant.now()
        val nowText = now.toString()
        val competitionCode = "openfootball:$leagueCode"
        val country = countryForLeagueCode(leagueCode)
        val competitionName = cleanCompetitionName(file.name ?: leagueCode)
        val competition = CompetitionEntity(
            providerCode = competitionCode,
            providerId = stableNegativeId("competition:$competitionCode"),
            name = competitionName,
            country = country,
            updatedAt = nowText,
        )
        val seasonId = "$competitionCode:$seasonYear"
        val season = SeasonEntity(
            id = seasonId,
            competitionCode = competitionCode,
            providerLeagueId = competition.providerId,
            seasonYear = seasonYear,
            isCurrent = seasonYear == now.atZone(ZoneOffset.UTC).year,
            lastSuccessfulFetch = nowText,
            lastProviderCheck = nowText,
            providerRevision = sha,
        )
        val matches = file.matches.orEmpty()
        val datedMatches = matches.mapNotNull { match ->
            val date = match.date?.let(::parseDate)
                ?: throw IllegalArgumentException("Ungültiges oder fehlendes Spieldatum.")
            val home = match.team1?.trim()?.takeIf { it.isNotEmpty() && !it.equals("TBD", true) }
            val away = match.team2?.trim()?.takeIf { it.isNotEmpty() && !it.equals("TBD", true) }
            val homeScore = match.scoreArray("ft")
            val halftime = match.scoreArray("ht")
            val round = match.round?.trim()?.takeIf(String::isNotEmpty)
            val matchday = round?.let { ROUND_NUMBER.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val fixtureId = openFootballFixtureId(
                competitionCode,
                seasonYear,
                round,
                home,
                away,
                date.toString(),
            )
            val homeTeamId = home?.let { stableNegativeId("team:$country:${normalize(it)}") }
            val awayTeamId = away?.let { stableNegativeId("team:$country:${normalize(it)}") }
            val isFinished = homeScore != null
            val localTime = match.time?.trim()?.takeIf(::isValidTime)
                ?.let { "$date $it (Zeitzone nicht angegeben)" }
            DatedOpenFootballMatch(
                date = date,
                fixture = FixtureEntity(
                    providerFixtureId = fixtureId,
                    competitionCode = competitionCode,
                    seasonId = seasonId,
                    homeTeamId = homeTeamId,
                    awayTeamId = awayTeamId,
                    kickoffAt = localTime,
                    matchDate = date.toString(),
                    status = when {
                        isFinished -> "FINISHED"
                        !date.isBefore(now.atZone(ZoneOffset.UTC).toLocalDate()) -> "SCHEDULED"
                        else -> "UNKNOWN"
                    },
                    matchday = matchday,
                    stage = round,
                    venue = match.ground?.trim()?.takeIf(String::isNotEmpty),
                    homeScore = homeScore?.get(0),
                    awayScore = homeScore?.get(1),
                    halftimeHomeScore = halftime?.get(0),
                    halftimeAwayScore = halftime?.get(1),
                    fullTimeResult = isFinished,
                    postponedOrCancelled = false,
                    rescheduled = false,
                    updatedAt = nowText,
                ),
                teams = listOfNotNull(
                    home?.let { TeamEntity(homeTeamId!!, it) },
                    away?.let { TeamEntity(awayTeamId!!, it) },
                ),
            )
        }
        val uniqueDatedMatches = datedMatches
            .groupBy { it.fixture.providerFixtureId }
            .map { (fixtureId, duplicates) ->
                require(duplicates.distinct().size == 1) {
                    "Widersprüchliche doppelte Begegnung mit ID $fixtureId in $path."
                }
                duplicates.first()
            }
        val existing = dao.fixturesForSeason(seasonId).associateBy { it.providerFixtureId }
        val existingSeason = dao.season(seasonId)
        val fixtures = uniqueDatedMatches.map { dated ->
            val old = existing[dated.fixture.providerFixtureId]
            val changedSchedule = old != null &&
                (
                    old.matchDate != dated.fixture.matchDate ||
                        (
                            old.kickoffAt != null &&
                                dated.fixture.kickoffAt != null &&
                                old.kickoffAt != dated.fixture.kickoffAt
                            )
                    )
            dated.fixture.copy(rescheduled = old?.rescheduled == true || changedSchedule)
        }
        database.withTransaction {
            dao.upsertCompetitions(listOf(competition))
            dao.upsertSeasons(
                listOf(if (preserveExistingFixtures) existingSeason ?: season else season),
            )
            dao.upsertTeams(uniqueDatedMatches.flatMap { it.teams }.distinctBy { it.providerTeamId })
            if (preserveExistingFixtures) {
                dao.insertFixturesIfAbsent(fixtures)
            } else {
                dao.upsertFixtures(fixtures)
            }
            dao.evaluateFinishedPredictionsForSeason(seasonId, nowText)
        }
        return fixtures.size
    }

    private suspend fun migrateLegacyFixtureIds() {
        if (dao.syncMetadata(FIXTURE_ID_MIGRATION_KEY)?.value == FIXTURE_ID_SCHEME) return
        val fixtures = dao.allFixtures().filter {
            it.competitionCode.startsWith("openfootball:") &&
                it.providerFixtureId < 0 &&
                it.providerFixtureId >= Int.MIN_VALUE.toLong()
        }
        val teamNames = dao.allTeams().associate { it.providerTeamId to it.name }
        val seasons = dao.allSeasons().associateBy { it.id }
        val migrations = fixtures.map { fixture ->
            val seasonYear = seasons[fixture.seasonId]?.seasonYear
                ?: throw IllegalStateException("Saison für OpenFootball-Spiel ${fixture.providerFixtureId} fehlt.")
            val homeName = fixture.homeTeamId?.let { teamNames[it] }
            val awayName = fixture.awayTeamId?.let { teamNames[it] }
            require(fixture.homeTeamId == null || homeName != null) {
                "Heimteam für OpenFootball-Spiel ${fixture.providerFixtureId} fehlt."
            }
            require(fixture.awayTeamId == null || awayName != null) {
                "Auswärtsteam für OpenFootball-Spiel ${fixture.providerFixtureId} fehlt."
            }
            fixture.providerFixtureId to openFootballFixtureId(
                fixture.competitionCode,
                seasonYear,
                fixture.stage,
                homeName,
                awayName,
                fixture.matchDate,
            )
        }
        require(migrations.map { it.second }.distinct().size == migrations.size) {
            "Die Umstellung der OpenFootball-Spiel-IDs würde neue ID-Kollisionen erzeugen."
        }
        val existingIds = dao.allFixtures().mapTo(mutableSetOf()) { it.providerFixtureId }
        require(migrations.none { (oldId, newId) -> newId in existingIds && newId != oldId }) {
            "Eine OpenFootball-Spiel-ID existiert bereits; die sichere Migration wurde abgebrochen."
        }
        database.withTransaction {
            val sqlDatabase = database.openHelper.writableDatabase
            val fixtureUpdate = sqlDatabase.compileStatement(
                "UPDATE fixtures SET providerFixtureId = ? WHERE providerFixtureId = ?",
            )
            val predictionUpdate = sqlDatabase.compileStatement(
                "UPDATE prediction_snapshots SET providerFixtureId = ? WHERE providerFixtureId = ?",
            )
            val backtestUpdate = sqlDatabase.compileStatement(
                "UPDATE backtest_predictions SET providerFixtureId = ? WHERE providerFixtureId = ?",
            )
            migrations.forEach { (oldId, newId) ->
                updateFixtureReference(predictionUpdate, oldId, newId)
                updateFixtureReference(backtestUpdate, oldId, newId)
                updateFixtureReference(fixtureUpdate, oldId, newId)
            }
            dao.upsertSyncMetadata(SyncMetadataEntity(FIXTURE_ID_MIGRATION_KEY, FIXTURE_ID_SCHEME))
        }
    }

    private fun updateFixtureReference(
        statement: SupportSQLiteStatement,
        oldId: Long,
        newId: Long,
    ) {
        statement.bindLong(1, newId)
        statement.bindLong(2, oldId)
        statement.executeUpdateDelete()
        statement.clearBindings()
    }

    private suspend fun markImported(entry: PendingOpenFootballFile) {
        dao.upsertSyncMetadata(SyncMetadataEntity(IMPORT_SHA_PREFIX + entry.path, entry.sha))
    }

    private suspend fun markBundledFile(revision: String, path: String, sha: String) {
        dao.upsertSyncMetadata(
            SyncMetadataEntity("$HISTORICAL_BUNDLE_FILE_PREFIX$revision:$path", sha),
        )
        dao.upsertSyncMetadata(SyncMetadataEntity(IMPORT_SHA_PREFIX + path, sha))
    }

    private suspend fun readPendingFiles(): List<PendingOpenFootballFile> {
        val value = dao.syncMetadata(PENDING_FILES_KEY)?.value ?: return emptyList()
        return try {
            gson.fromJson<List<PendingOpenFootballFile>>(value, PENDING_FILES_TYPE).orEmpty()
        } catch (exception: JsonParseException) {
            throw IllegalStateException("OpenFootball-Warteschlange in der lokalen Datenbank ist ungültig.", exception)
        }
    }

    private suspend fun savePendingFiles(files: List<PendingOpenFootballFile>) {
        dao.upsertSyncMetadata(SyncMetadataEntity(PENDING_FILES_KEY, gson.toJson(files)))
    }

    private suspend fun <T> requestWithRetryValue(request: suspend () -> T): T {
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

    private suspend fun waitForProviderSlot() {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestElapsed
        val waitMillis = MINIMUM_REQUEST_INTERVAL_MS - elapsed
        if (lastRequestElapsed != 0L && waitMillis > 0) delay(waitMillis)
        lastRequestElapsed = SystemClock.elapsedRealtime()
    }

    private fun isCoveredByOpenLiga(
        leagueCode: String,
        seasonYear: Int,
        openLigaSeasons: Set<Pair<String, Int>>,
    ): Boolean =
        OPENLIGA_SHORTCUTS[leagueCode].orEmpty().any { shortcut ->
            (shortcut.lowercase() to seasonYear) in openLigaSeasons
        }

    private suspend fun hasOpenLigaCoverage(leagueCode: String, seasonYear: Int): Boolean =
        OPENLIGA_SHORTCUTS[leagueCode].orEmpty().any { shortcut ->
            val season = dao.season("$shortcut:$seasonYear")
            season?.lastSuccessfulFetch != null ||
                (season != null && dao.fixturesForSeason(season.id).isNotEmpty())
        }

    private fun gitBlobSha(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }

    private fun countryForLeagueCode(code: String): String =
        when (code.substringBefore('.').lowercase()) {
            "at" -> "Austria"
            "be" -> "Belgium"
            "ba" -> "Bosnia and Herzegovina"
            "ch" -> "Switzerland"
            "de" -> "Germany"
            "en" -> "England"
            "es" -> "Spain"
            "fr" -> "France"
            "cz" -> "Czechia"
            "gr" -> "Greece"
            "hu" -> "Hungary"
            "hr" -> "Croatia"
            "it" -> "Italy"
            "nl" -> "Netherlands"
            "pt" -> "Portugal"
            "ru" -> "Russia"
            "rs" -> "Serbia"
            "sco" -> "Scotland"
            "tr" -> "Turkey"
            "uefa", "fifa" -> "International"
            else -> "Unknown"
        }

    private fun cleanCompetitionName(name: String): String =
        name.replace(Regex("\\s+\\d{4}(?:/\\d{2,4})?$"), "").trim().ifEmpty { name }

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun seasonYear(path: String): Int? =
        SEASON_PATH.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun normalize(value: String): String = normalizeOpenFootballName(value)

    private fun stableNegativeId(value: String): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val positive = ((hash[0].toInt() and 0x7f) shl 24) or
            ((hash[1].toInt() and 0xff) shl 16) or
            ((hash[2].toInt() and 0xff) shl 8) or
            (hash[3].toInt() and 0xff)
        return -positive.coerceAtLeast(1)
    }

    private fun isValidTime(value: String): Boolean = TIME_PATTERN.matches(value)

    private data class DatedOpenFootballMatch(
        val date: LocalDate,
        val fixture: FixtureEntity,
        val teams: List<TeamEntity>,
    )

    companion object {
        private const val TAG = "OpenFootballImporter"
        private const val HISTORICAL_ASSET_PATH = "historical/football-history-2026-10-21.zip"
        private const val HISTORICAL_BUNDLE_COMPLETE_KEY = "historicalBundleCompleteRevision"
        private const val HISTORICAL_BUNDLE_FILE_PREFIX = "historicalBundleImported:"
        private const val FIXTURE_ID_MIGRATION_KEY = "openFootballFixtureIdScheme"
        private const val FIXTURE_ID_SCHEME = "sha256-63-v2"
        private const val CATALOG_CHECK_KEY = "openFootballCatalogCheckedAt"
        private const val TREE_SHA_KEY = "openFootballTreeSha"
        private const val PENDING_FILES_KEY = "openFootballPendingFiles"
        private const val IMPORT_SHA_PREFIX = "openFootballImported:"
        private const val MAX_FILES_PER_SYNC = 20
        private const val MAX_RETRIES = 3
        private const val MINIMUM_REQUEST_INTERVAL_MS = 1_100L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private val CATALOG_REFRESH_INTERVAL = java.time.Duration.ofHours(24)
        private val SEASON_FILE_PATTERN = Regex("^\\d{4}-\\d{2}/[^/]+\\.json$")
        private val SEASON_PATH = Regex("^(\\d{4})-\\d{2}/")
        private val ROUND_NUMBER = Regex("(\\d+)\\D*$")
        private val TIME_PATTERN = Regex("^\\d{1,2}:\\d{2}$")
        private val PENDING_FILES_TYPE = object : TypeToken<List<PendingOpenFootballFile>>() {}.type
        private val OPENLIGA_SHORTCUTS = mapOf(
            "at.1" to setOf("öfb", "oefb", "obl", "atbl1"),
            "be.1" to setOf("be1"),
            "ch.1" to setOf("asl", "rsl", "ch1", "ssl"),
            "de.1" to setOf("bl1"),
            "de.2" to setOf("bl2"),
            "de.3" to setOf("bl3"),
            "en.1" to setOf("pl", "pl1", "enpl", "pl17/18"),
            "es.1" to setOf("la1", "la", "ll", "lali"),
            "fr.1" to setOf("fr1", "lg1"),
            "it.1" to setOf("sa", "it1", "seriea", "legaseriea"),
            "nl.1" to setOf("nl1", "eredivisie"),
            "pt.1" to setOf("pt1"),
            "tr.1" to setOf("tsl", "tr1"),
            "uefa.cl" to setOf("cl", "ucl", "ucl2025", "ucl2026", "champion1", "uefacl22"),
            "uefa.el" to setOf("el", "uel", "uel2022", "uel2023", "uel2026"),
        )

        fun openFootballCodesForOpenLigaShortcut(shortcut: String): Set<String> =
            OPENLIGA_SHORTCUTS
                .filterValues { shortcut.lowercase() in it }
                .keys
    }
}
