package com.sportpredi

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalSyncWorker.schedule(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(SportPrediDatabase.get(applicationContext))
                }
            }
        }
    }
}

@Composable
private fun AppScreen(database: SportPrediDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupRepository = remember(database) { LocalBackupRepository(database) }
    val dao = remember(database) { database.sportPrediDao() }
    val apiCredentialsStore = remember(context) { ApiCredentialsStore(context) }
    val syncRepository = remember(database) {
        LocalSyncRepository(context.applicationContext, database, OpenLigaDbApi.create())
    }
    var showPerformance by remember { mutableStateOf(false) }
    var showStatsBomb by remember { mutableStateOf(false) }
    var showApiCredentials by remember { mutableStateOf(false) }
    var oddsApiKey by remember { mutableStateOf("") }
    var apiFootballKey by remember { mutableStateOf("") }
    var apiCredentialsMessage by remember { mutableStateOf<String?>(null) }
    var apiCredentialsError by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var syncLoading by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("Synchronisierung wird vorbereitet …") }
    var syncError by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var coverageRefresh by remember { mutableStateOf(0) }

    fun synchronize(force: Boolean) {
        if (syncLoading) return
        scope.launch {
            syncLoading = true
            syncError = null
            try {
                val report = withContext(Dispatchers.IO) {
                    syncRepository.synchronize(force = force) { progress ->
                        Handler(Looper.getMainLooper()).post {
                            syncMessage = progress.message
                        }
                    }
                }
                val supplementaryReport = withContext(Dispatchers.IO) {
                    SupplementaryDataSync(database).synchronize(apiCredentialsStore.load())
                }
                val predictionsCreated = withContext(Dispatchers.IO) {
                    LocalPredictionEngine(dao).refreshUpcomingPredictions()
                }
                lastUpdated = report.completedAt
                syncMessage =
                    "${report.seasonsChecked} OpenLigaDB-Spielzeiten geprüft; " +
                        "${report.openFootballFilesProcessed} OpenFootball-Dateien verarbeitet " +
                        "(${report.openFootballPendingFiles} ausstehend); " +
                        "${report.fixturesImported} Spiele importiert; " +
                        "$predictionsCreated Prognose-Snapshots neu erstellt; " +
                        "${supplementaryReport.oddsEventsSaved} Quoten-Sätze und " +
                        "${supplementaryReport.footballMatchesEnriched} API-Football-Spiele ergänzt."
                val errors = report.errors + supplementaryReport.errors
                if (errors.isNotEmpty()) syncError = errors.joinToString("\n")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                syncError = syncFailureMessage(exception)
                syncMessage = "Synchronisierung fehlgeschlagen."
            } finally {
                syncLoading = false
                lastUpdated = withContext(Dispatchers.IO) {
                    dao.syncMetadata("lastSyncAt")?.value ?: lastUpdated
                }
                coverageRefresh += 1
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = backupRepository.exportJson()
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Die Sicherungsdatei kann nicht geschrieben werden.")
                        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    backupError = false
                    backupMessage = "Lokales Backup gespeichert."
                } catch (exception: Exception) {
                    backupError = true
                    backupMessage = "Backup fehlgeschlagen: ${exception.message ?: "Unbekannter Fehler"}"
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        restoreUri = uri
    }

    LaunchedEffect(Unit) {
        syncLoading = true
        syncMessage = "Vorbereitete historische Datenbasis wird lokal geladen …"
        val startupErrors = mutableListOf<String>()
        lastUpdated = withContext(Dispatchers.IO) {
            dao.syncMetadata("lastSyncAt")?.value
        }
        try {
            val report = withContext(Dispatchers.IO) {
                syncRepository.initializeBundledHistory { message ->
                    Handler(Looper.getMainLooper()).post { syncMessage = message }
                }
            }
            val statsBombImported = try {
                withContext(Dispatchers.IO) {
                    StatsBombHistoryImporter(database).importBundledHistory(context.applicationContext)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                startupErrors += "StatsBomb-Historie konnte nicht geladen werden: " +
                    (exception.message ?: "Unbekannter Fehler")
                0
            }
            val predictionsCreated = withContext(Dispatchers.IO) {
                LocalPredictionEngine(dao).refreshUpcomingPredictions()
            }
            syncMessage = if (
                report.filesProcessed == 0 &&
                report.errors.isEmpty() &&
                statsBombImported == 0
            ) {
                "Lokale Datenbasis bereit. Beim App-Start wurde keine Internetverbindung verwendet."
            } else {
                "${report.fixturesImported} historische Spiele aus ${report.filesProcessed} Dateien " +
                    "importiert; $statsBombImported StatsBomb-Spiele ergänzt; " +
                    "$predictionsCreated Prognose-Snapshots erstellt."
            }
            startupErrors += report.errors
            if (startupErrors.isNotEmpty()) syncError = startupErrors.joinToString("\n")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            syncError = syncFailureMessage(exception)
            syncMessage = "Lokaler Import der vorbereiteten Datenbasis fehlgeschlagen."
        } finally {
            syncLoading = false
            coverageRefresh += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    showPerformance = false
                    showStatsBomb = false
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Spiele")
            }
            Button(
                onClick = {
                    showPerformance = true
                    showStatsBomb = false
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Prediction-Qualität")
            }
        }
        OutlinedButton(
            onClick = {
                showStatsBomb = true
                showPerformance = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("StatsBomb-Historie · ausgewählte Spiele")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val credentials = withContext(Dispatchers.IO) { apiCredentialsStore.load() }
                        oddsApiKey = credentials.oddsApiKey
                        apiFootballKey = credentials.apiFootballKey
                        showApiCredentials = true
                    } catch (exception: Exception) {
                        apiCredentialsError = true
                        apiCredentialsMessage =
                            "API-Schlüssel konnten nicht gelesen werden: " +
                                (exception.message ?: "Unbekannter Fehler")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Zusätzliche Datenanbieter konfigurieren")
        }
        apiCredentialsMessage?.let {
            Text(
                it,
                color = if (apiCredentialsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { exportLauncher.launch("sportpredi-backup.json") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Backup exportieren")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                enabled = !syncLoading,
                modifier = Modifier.weight(1f),
            ) {
                Text("Backup wiederherstellen")
            }
        }
        backupMessage?.let {
            Text(
                it,
                color = if (backupError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        if (showStatsBomb) {
            StatsBombHistoryScreen(database)
        } else if (showPerformance) {
            PerformanceScreen(database)
        } else {
            FixturesScreen(
                database = database,
                lastUpdated = lastUpdated,
                coverageRefresh = coverageRefresh,
                syncMessage = syncMessage,
                syncError = syncError,
                loading = syncLoading,
                onSynchronize = { synchronize(force = true) },
            )
        }
    }

    if (showApiCredentials) {
        AlertDialog(
            onDismissRequest = { showApiCredentials = false },
            title = { Text("API-Schlüssel auf diesem Gerät") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Die Schlüssel werden mit dem Android Keystore verschlüsselt gespeichert. " +
                            "Da die App Anbieter direkt aufruft, können Schlüssel auf einem kompromittierten " +
                            "Gerät trotzdem ausgelesen werden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = oddsApiKey,
                        onValueChange = { oddsApiKey = it },
                        label = { Text("The Odds API") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiFootballKey,
                        onValueChange = { apiFootballKey = it },
                        label = { Text("API-Football") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    apiCredentialsStore.save(
                                        ApiCredentials(
                                            oddsApiKey = oddsApiKey,
                                            apiFootballKey = apiFootballKey,
                                        ),
                                    )
                                }
                                showApiCredentials = false
                                apiCredentialsError = false
                                apiCredentialsMessage = "API-Schlüssel lokal verschlüsselt gespeichert."
                            } catch (exception: Exception) {
                                apiCredentialsError = true
                                apiCredentialsMessage =
                                    "API-Schlüssel konnten nicht gespeichert werden: " +
                                        (exception.message ?: "Unbekannter Fehler")
                            }
                        }
                    },
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            oddsApiKey = ""
                            apiFootballKey = ""
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        apiCredentialsStore.save(ApiCredentials("", ""))
                                    }
                                    showApiCredentials = false
                                    apiCredentialsError = false
                                    apiCredentialsMessage = "Gespeicherte API-Schlüssel wurden gelöscht."
                                } catch (exception: Exception) {
                                    apiCredentialsError = true
                                    apiCredentialsMessage =
                                        "API-Schlüssel konnten nicht gelöscht werden: " +
                                            (exception.message ?: "Unbekannter Fehler")
                                }
                            }
                        },
                    ) {
                        Text("Schlüssel löschen")
                    }
                    TextButton(onClick = { showApiCredentials = false }) {
                        Text("Abbrechen")
                    }
                }
            },
        )
    }

    if (restoreUri != null) {
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Lokale Daten ersetzen?") },
            text = {
                Text(
                    "Das Wiederherstellen ersetzt alle Spiele, Prognosen und Backtest-Daten auf diesem Gerät. " +
                        "Exportiere zuerst ein Backup, falls du den aktuellen Stand behalten möchtest.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = restoreUri ?: return@TextButton
                        restoreUri = null
                        scope.launch {
                            try {
                                val json = withContext(Dispatchers.IO) {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: error("Die Sicherungsdatei kann nicht gelesen werden.")
                                    input.use { it.readBytes().toString(Charsets.UTF_8) }
                                }
                                withContext(Dispatchers.IO) { backupRepository.importJson(json) }
                                lastUpdated = withContext(Dispatchers.IO) {
                                    dao.syncMetadata("lastSyncAt")?.value
                                }
                                coverageRefresh += 1
                                syncMessage = "Lokale Sicherung wiederhergestellt."
                                syncError = null
                                backupError = false
                                backupMessage = "Backup wiederhergestellt."
                            } catch (exception: Exception) {
                                backupError = true
                                backupMessage =
                                    "Wiederherstellung fehlgeschlagen: ${exception.message ?: "Unbekannter Fehler"}"
                            }
                        }
                    },
                    enabled = !syncLoading,
                ) { Text("Wiederherstellen") }
            },
            dismissButton = {
                TextButton(onClick = { restoreUri = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun FixturesScreen(
    database: SportPrediDatabase,
    lastUpdated: String?,
    coverageRefresh: Int,
    syncMessage: String,
    syncError: String?,
    loading: Boolean,
    onSynchronize: () -> Unit,
) {
    val dao = remember(database) { database.sportPrediDao() }
    var range by remember { mutableStateOf("7 Tage") }
    var countryFilter by remember { mutableStateOf("") }
    var leagueFilter by remember { mutableStateOf("") }
    var seasonFilter by remember { mutableStateOf("") }
    var teamFilter by remember { mutableStateOf("") }
    val today = LocalDate.now(ZoneOffset.UTC)
    val seasonEndYear = if (today.monthValue >= 7) today.year + 1 else today.year
    val currentSeasonEnd = LocalDate.of(seasonEndYear, 7, 1).minusDays(1)
    val currentSeasonLabel = "${seasonEndYear - 1}/${seasonEndYear.toString().takeLast(2)}"
    val endDate = when (range) {
        "Heute" -> today
        "Morgen" -> today.plusDays(1)
        "Saison" -> currentSeasonEnd
        else -> today.plusDays(6)
    }
    val fixtures by dao.observeUpcoming(today.toString(), endDate.toString())
        .collectAsState(initial = emptyList())
    var coverageRows by remember { mutableStateOf(emptyList<SeasonCoverageRow>()) }
    var selectedFixture by remember { mutableStateOf<FixtureDisplay?>(null) }
    LaunchedEffect(database, lastUpdated, coverageRefresh) {
        coverageRows = withContext(Dispatchers.IO) { dao.seasonCoverage() }
    }

    val visibleFixtures = fixtures.filter { fixture ->
        (fixture.country ?: "").contains(countryFilter, ignoreCase = true) &&
            (fixture.competitionName ?: "").contains(leagueFilter, ignoreCase = true) &&
            (seasonFilter.isBlank() || fixture.seasonYear?.toString()?.contains(seasonFilter) == true) &&
            (
                (fixture.homeTeamName ?: "").contains(teamFilter, ignoreCase = true) ||
                    (fixture.awayTeamName ?: "").contains(teamFilter, ignoreCase = true)
                )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Kommende Spiele", style = MaterialTheme.typography.headlineMedium)
        Text("Letzte lokale Aktualisierung: ${formatTimestamp(lastUpdated)}")
        Text(
            "Quellen: OpenLigaDB, OpenFootball football.json (CC0) und ein ausgewählter historischer " +
                "StatsBomb-Datensatz. Nur tatsächlich verfügbare Wettbewerbe werden angezeigt. " +
                "Der versionierte Bestand wird offline importiert; Live-Aktualisierungen bleiben auf " +
                "20 Spielzeiten bzw. Dateien je Sync begrenzt.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Für kommende Spiele sind xG, Schüsse, Ballbesitz, bestätigte Aufstellungen und Verletzungen " +
                "weiterhin nicht verfügbar. StatsBomb enthält Werte nur für ausgewählte historische Spiele.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Standardansicht: alle aktuell verfügbaren Wettbewerbe; im Saisonfilter ${currentSeasonLabel} " +
                "bis ${currentSeasonEnd}. Die Abdeckung hängt von den Datenquellen ab.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Modelltipps vergleichen 1X2, Beide Teams treffen und Über/Unter 1,5 bzw. 2,5 Tore. " +
                "Doppelte Chance und extreme Torlinien werden ausgeschlossen. Quoten und erwarteter " +
                "Gewinn werden nicht berechnet; daraus folgt keine Gewinngarantie.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(syncMessage, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Heute", "Morgen", "7 Tage", "Saison").forEach { option ->
                OutlinedButton(
                    onClick = { range = option },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(option)
                }
            }
        }
        Button(
            onClick = onSynchronize,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Aktualisieren")
        }
        OutlinedTextField(
            countryFilter,
            { countryFilter = it },
            label = { Text("Land filtern") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            leagueFilter,
            { leagueFilter = it },
            label = { Text("Liga filtern") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            seasonFilter,
            { seasonFilter = it },
            label = { Text("Saison filtern") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            teamFilter,
            { teamFilter = it },
            label = { Text("Mannschaft filtern") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (loading) CircularProgressIndicator()
        syncError?.let { Text("Synchronisierungsfehler: $it", color = MaterialTheme.colorScheme.error) }
        if (visibleFixtures.isEmpty() && !loading) {
            Text("Keine Spiele für diesen Zeitraum in den lokal gespeicherten Daten gefunden.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleFixtures.forEach { fixture ->
                FixtureCard(fixture) { selectedFixture = fixture }
            }
        }
        if (coverageRows.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Datenbasis & Abdeckung", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${coverageRows.map { it.competitionCode }.distinct().size} Wettbewerbe · " +
                            "${coverageRows.size} Saisons · " +
                            "${coverageRows.sumOf { it.fixtureCount }} Spiele lokal gespeichert",
                    )
                    Text(
                        "Das ist die tatsächlich importierte Abdeckung, keine Vollständigkeitszusage für Europa. " +
                            "Fehlende Wettbewerbe werden von den Quellen nicht vollständig katalogisiert.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    coverageRows.groupBy { it.competitionCode }.forEach { (code, seasons) ->
                        val fixtureCount = seasons.sumOf { it.fixtureCount }
                        val completedCount = seasons.sumOf { it.completedCount }
                        val linkedCount = seasons.sumOf { it.linkedTeamCount }
                        val halftimeCount = seasons.sumOf { it.halftimeScoreCount }
                        val lastFetch = seasons.mapNotNull { it.lastSuccessfulFetch }.maxOrNull()
                        val firstYear = seasons.minOf { it.seasonYear }
                        val lastYear = seasons.maxOf { it.seasonYear }
                        val firstDate = seasons.mapNotNull { it.firstMatchDate }.minOrNull()
                        val lastDate = seasons.mapNotNull { it.lastMatchDate }.maxOrNull()
                        val revision = seasons.mapNotNull { it.sourceRevision }.maxOrNull()
                        Text(
                            "${seasons.first().competitionName} · ${seasons.first().country}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "$code · $firstYear–$lastYear · $fixtureCount Spiele in ${seasons.size} Saisons",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Spieldaten: ${firstDate ?: "—"} bis ${lastDate ?: "—"}" +
                                (revision?.let { " · Revision ${it.take(12)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Endstände ${coveragePercent(completedCount, fixtureCount)} · " +
                                "Teams zugeordnet ${coveragePercent(linkedCount, fixtureCount)} · " +
                                "Halbzeitstände ${coveragePercent(halftimeCount, fixtureCount)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Letzter erfolgreicher Import/Abruf: ${formatTimestamp(lastFetch)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    selectedFixture?.let { fixture ->
        FixtureDetailsDialog(fixture, dao) { selectedFixture = null }
    }
}

@Composable
private fun StatsBombHistoryScreen(database: SportPrediDatabase) {
    val dao = remember(database) { database.sportPrediDao() }
    var matches by remember { mutableStateOf(emptyList<HistoricalMatchStatisticsEntity>()) }
    var selectedMatch by remember { mutableStateOf<HistoricalMatchStatisticsEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(database) {
        try {
            matches = withContext(Dispatchers.IO) { dao.allHistoricalMatchStatistics() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            loadError = exception.message ?: "StatsBomb-Spiele konnten nicht geladen werden."
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("StatsBomb Open Data", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (loading) {
                "Offline-Datensatz der 1. Bundesliga 2023/24 wird geladen …"
            } else {
                "Offline-Datensatz: ${matches.size} ausgewählte Spiele der 1. Bundesliga 2023/24. " +
                    "Das ist keine vollständige Saisonabdeckung und enthält keine kommenden Spiele."
            },
        )
        Text(
            "xG, Schüsse, Karten und Aufstellungen stammen aus Ereignis- und Aufstellungsdaten. " +
                "Ballbesitz und Verletzungen werden von diesem offenen Datensatz nicht geliefert. " +
                "Wegen der lückenhaften Auswahl fließen diese Werte nicht in kommende Prognosen ein.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Datenquelle: StatsBomb Open Data · Quellrevision ${matches.firstOrNull()?.sourceRevision?.take(12) ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) CircularProgressIndicator()
        if (!loading && matches.isEmpty() && loadError == null) {
            Text("Der lokale StatsBomb-Datensatz enthält keine Spiele.")
        }
        matches.forEach { match ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedMatch = match },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${match.matchDate} · ${match.homeTeam} ${match.homeScore ?: "—"}:" +
                            "${match.awayScore ?: "—"} ${match.awayTeam}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "xG ${match.homeXg?.format(2) ?: "—"} : ${match.awayXg?.format(2) ?: "—"} · " +
                            "Schüsse ${match.homeShots ?: "—"} : ${match.awayShots ?: "—"}",
                    )
                    Text(
                        "Gelb ${match.homeYellowCards ?: "—"} : ${match.awayYellowCards ?: "—"} · " +
                            "Rot ${match.homeRedCards ?: "—"} : ${match.awayRedCards ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    selectedMatch?.let { match ->
        StatsBombMatchDialog(match) { selectedMatch = null }
    }
}

@Composable
private fun StatsBombMatchDialog(
    match: HistoricalMatchStatisticsEntity,
    onDismiss: () -> Unit,
) {
    val lineupType = remember {
        object : TypeToken<List<StatsBombLineupPlayer>>() {}.type
    }
    val lineups = remember(match.providerMatchId) {
        try {
            Gson().let { gson ->
                gson.fromJson<List<StatsBombLineupPlayer>>(match.homeLineupJson, lineupType).orEmpty() to
                    gson.fromJson<List<StatsBombLineupPlayer>>(match.awayLineupJson, lineupType).orEmpty()
            }
        } catch (exception: JsonParseException) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${match.homeTeam} – ${match.awayTeam}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${match.competitionName} · ${match.seasonName} · ${match.matchDate}")
                Text("Endstand: ${match.homeScore ?: "—"} : ${match.awayScore ?: "—"}")
                Text("xG: ${match.homeXg?.format(2) ?: "—"} : ${match.awayXg?.format(2) ?: "—"}")
                Text("Schüsse: ${match.homeShots ?: "—"} : ${match.awayShots ?: "—"}")
                Text(
                    "Gelbe Karten: ${match.homeYellowCards ?: "—"} : ${match.awayYellowCards ?: "—"} · " +
                        "Rote Karten: ${match.homeRedCards ?: "—"} : ${match.awayRedCards ?: "—"}",
                )
                Text("Ballbesitz und Verletzungsdaten: nicht enthalten.")
                if (lineups == null) {
                    Text("Aufstellungen konnten nicht gelesen werden.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Aufstellungen", style = MaterialTheme.typography.titleSmall)
                    StatsBombLineup(match.homeTeam, lineups.first)
                    StatsBombLineup(match.awayTeam, lineups.second)
                }
                Text(
                    "Quelle: StatsBomb Open Data · Revision ${match.sourceRevision}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun StatsBombLineup(teamName: String, players: List<StatsBombLineupPlayer>) {
    Text(teamName, style = MaterialTheme.typography.titleSmall)
    players.forEach { player ->
        val shirt = player.jerseyNumber?.let { "#$it " }.orEmpty()
        val role = if (player.starter) "Startelf" else "Ersatz"
        val position = player.position?.let { " · $it" }.orEmpty()
        Text("$shirt${player.name} · $role$position", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FixtureCard(fixture: FixtureDisplay, onClick: () -> Unit) {
    val analysis = remember(fixture.analysisJson) {
        parsePredictionAnalysis(fixture.analysisJson)
    }
    val odds = remember(fixture.oddsJson) { parseBettingOdds(fixture.oddsJson) }
    val tip = remember(analysis?.scoreDistribution) {
        analysis?.let { highestProbabilityEligibleTip(it.scoreDistribution) }
    }
    val valueTip = remember(analysis?.scoreDistribution, odds) {
        analysis?.let { highestProbabilityPositiveValueTip(it.scoreDistribution, odds) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${fixture.country ?: "Land unbekannt"} · ${fixture.competitionName ?: "Wettbewerb unbekannt"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${fixture.homeTeamName ?: "TBD"} – ${fixture.awayTeamName ?: "TBD"}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(fixture.kickoffAt?.let(::formatTimestamp) ?: fixture.matchDate)
            fixture.seasonYear?.let { Text("Saison $it") }
            fixture.matchday?.let { Text("Spieltag $it") }
            fixture.venue?.takeIf(String::isNotBlank)?.let { Text(it) }
            Text("Tippen für Statistiken und Prognoseanalyse", style = MaterialTheme.typography.bodySmall)
            if (fixture.postponedOrCancelled) {
                Text(if (fixture.status == "CANCELLED") "Abgesagt" else "Verschoben")
            } else if (fixture.rescheduled) {
                Text("Neu terminiert")
            } else if (fixture.homeScore != null && fixture.awayScore != null) {
                Text("Ergebnis: ${fixture.homeScore} : ${fixture.awayScore}")
            } else {
                Text("Status: ${fixture.status}")
            }
            if (
                fixture.homeWinProbability != null &&
                fixture.drawProbability != null &&
                fixture.awayWinProbability != null
            ) {
                Text(
                    "Prognose: Heimsieg ${percent(fixture.homeWinProbability)} · " +
                        "Unentschieden ${percent(fixture.drawProbability)} · " +
                        "Auswärtssieg ${percent(fixture.awayWinProbability)}",
                )
                if (fixture.expectedHomeGoals != null && fixture.expectedAwayGoals != null) {
                    Text(
                        "Erwartete Tore: ${fixture.expectedHomeGoals.format(2)} : " +
                            fixture.expectedAwayGoals.format(2),
                    )
                }
            } else {
                Text("Noch keine Prognose (zu wenige historische Daten).")
            }
            tip?.let {
                Text(
                    "Wahrscheinlichster Markt: ${it.market} · ${percent(it.probability)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            valueTip?.let {
                Text(
                    "Quoten-Tipp: ${it.market} · Quote ${it.decimalOdds.format(2)} · " +
                        "Modell-Value +${percent(it.expectedValue)} · ${it.bookmaker}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } ?: if (odds.isNotEmpty()) {
                Text("Kein Quoten-Tipp mit positiver Modell-Value (Quote mindestens 1,50).")
            } else {
                Text("Keine passenden Buchmacherquoten gespeichert.")
            }
            analysis?.let {
                Text(it.confidenceLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun parsePredictionAnalysis(json: String?): PredictionAnalysis? =
    try {
        json?.let { Gson().fromJson(it, PredictionAnalysis::class.java) }
    } catch (exception: JsonParseException) {
        null
    }

private fun parseBettingOdds(json: String?): List<BettingOddsSelection> =
    try {
        json?.let { Gson().fromJson(it, Array<BettingOddsSelection>::class.java)?.toList() }.orEmpty()
    } catch (exception: JsonParseException) {
        emptyList()
    }

private fun parseLineupSummary(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        JsonParser.parseString(json).asJsonArray.flatMap { entry ->
                val group = entry.asJsonObject
                val team = group.getAsJsonObject("team")?.get("name")?.asString ?: "Team"
                val formation = group.get("formation")?.takeUnless { it.isJsonNull }?.asString
                val starters = group.getAsJsonArray("startXI")
                    ?.mapNotNull { it.asJsonObject.getAsJsonObject("player")?.get("name")?.asString }
                    .orEmpty()
                buildList {
                    add(if (formation.isNullOrBlank()) team else "$team · $formation")
                    if (starters.isNotEmpty()) add("Startelf: ${starters.joinToString(", ")}")
                }
            }
    } catch (exception: Exception) {
        listOf("Aufstellungsdaten konnten nicht gelesen werden.")
    }
}

private fun parseInjurySummary(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        JsonParser.parseString(json).asJsonArray.mapNotNull { entry ->
                val injury = entry.asJsonObject
                val player = injury.getAsJsonObject("player")?.get("name")?.asString
                val team = injury.getAsJsonObject("team")?.get("name")?.asString
                val reason = injury.getAsJsonObject("player")
                    ?.get("reason")?.takeUnless { it.isJsonNull }?.asString
                player?.let { listOfNotNull(team, it, reason).joinToString(" · ") }
            }
    } catch (exception: Exception) {
        listOf("Verletzungsdaten konnten nicht gelesen werden.")
    }
}

private data class FixtureDetailsData(
    val fixture: FixtureEntity,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeHistory: List<FixtureEntity>,
    val awayHistory: List<FixtureEntity>,
    val headToHead: List<FixtureEntity>,
    val seasonFixtures: List<FixtureEntity>,
    val teamNames: Map<Int, String>,
)

private data class LocalStanding(
    val teamId: Int,
    val played: Int,
    val points: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
) {
    val goalDifference: Int get() = goalsFor - goalsAgainst
}

@Composable
private fun FixtureDetailsDialog(
    display: FixtureDisplay,
    dao: SportPrediDao,
    onDismiss: () -> Unit,
) {
    var details by remember(display.providerFixtureId) { mutableStateOf<FixtureDetailsData?>(null) }
    var detailError by remember(display.providerFixtureId) { mutableStateOf<String?>(null) }
    val analysis = remember(display.analysisJson) { parsePredictionAnalysis(display.analysisJson) }
    val odds = remember(display.oddsJson) { parseBettingOdds(display.oddsJson) }
    val modelTip = remember(analysis?.scoreDistribution) {
        analysis?.let { highestProbabilityEligibleTip(it.scoreDistribution) }
    }
    val valueTip = remember(analysis?.scoreDistribution, odds) {
        analysis?.let { highestProbabilityPositiveValueTip(it.scoreDistribution, odds) }
    }
    val analysisInvalid = display.analysisJson != null && analysis == null

    LaunchedEffect(display.providerFixtureId) {
        try {
            details = withContext(Dispatchers.IO) {
                val match = dao.fixture(display.providerFixtureId)
                    ?: error("Spieldetails sind lokal nicht mehr vorhanden.")
                val homeId = match.homeTeamId
                    ?: error("Die Heimmannschaft ist noch nicht zugeordnet.")
                val awayId = match.awayTeamId
                    ?: error("Die Auswärtsmannschaft ist noch nicht zugeordnet.")
                val teams = dao.allTeams().associate { it.providerTeamId to it.name }
                FixtureDetailsData(
                    fixture = match,
                    homeTeamName = teams[homeId] ?: display.homeTeamName ?: "Heimteam",
                    awayTeamName = teams[awayId] ?: display.awayTeamName ?: "Auswärtsteam",
                    homeHistory = dao.teamHistory(
                        match.competitionCode,
                        homeId,
                        match.matchDate,
                        DETAIL_HISTORY_LIMIT,
                    ),
                    awayHistory = dao.teamHistory(
                        match.competitionCode,
                        awayId,
                        match.matchDate,
                        DETAIL_HISTORY_LIMIT,
                    ),
                    headToHead = dao.headToHead(
                        match.competitionCode,
                        homeId,
                        awayId,
                        match.matchDate,
                        DETAIL_HISTORY_LIMIT,
                    ),
                    seasonFixtures = dao.fixturesForSeason(match.seasonId),
                    teamNames = teams,
                )
            }
        } catch (exception: Exception) {
            detailError = exception.message ?: "Spieldetails konnten nicht geladen werden."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${display.homeTeamName ?: "TBD"} – ${display.awayTeamName ?: "TBD"}")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${display.competitionName ?: "Wettbewerb"} · ${display.country ?: "Land unbekannt"}")
                Text("Datum: ${display.kickoffAt?.let(::formatTimestamp) ?: display.matchDate}")
                display.seasonYear?.let { Text("Saison: $it") }
                display.matchday?.let { Text("Spieltag: $it") }
                display.venue?.takeIf(String::isNotBlank)?.let { Text("Spielort: $it") }
                Text("Status: ${display.status}")
                if (display.postponedOrCancelled || display.rescheduled) {
                    Text(
                        when {
                            display.status == "CANCELLED" -> "Dieses Spiel wurde abgesagt."
                            display.rescheduled -> "Dieses Spiel wurde neu terminiert."
                            else -> "Dieses Spiel wurde verschoben."
                        },
                    )
                }
                if (display.homeScore != null && display.awayScore != null) {
                    Text("Endstand: ${display.homeScore} : ${display.awayScore}")
                }
                if (analysis != null) {
                    Text("Prognosemodell: ${LocalForecastingState.MODEL_VERSION}")
                    Text(
                        "1X2: ${display.homeWinProbability?.let(::percent) ?: "n/a"} / " +
                            "${display.drawProbability?.let(::percent) ?: "n/a"} / " +
                            "${display.awayWinProbability?.let(::percent) ?: "n/a"}",
                    )
                    Text("Erwartete Tore: ${display.expectedHomeGoals?.format(2) ?: "n/a"} : ${display.expectedAwayGoals?.format(2) ?: "n/a"}")
                    Text("Wahrscheinlichstes Ergebnis: ${analysis.mostLikelyScore}")
                    Text(
                        "Über 1,5 / 2,5 / 3,5 Tore: ${percent(analysis.over15Probability)} / " +
                            "${percent(analysis.over25Probability)} / ${percent(analysis.over35Probability)}",
                    )
                    modelTip?.let {
                        Text(
                            "Wahrscheinlichster Modellmarkt: ${it.market} · " +
                                "${percent(it.probability)}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } ?: Text("Kein Tipp in den berücksichtigten Märkten.")
                    valueTip?.let {
                        Text(
                            "Quoten-Tipp: ${it.market} · Quote ${it.decimalOdds.format(2)} · " +
                                "Modell-Value +${percent(it.expectedValue)} · ${it.bookmaker}",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } ?: Text(
                        if (odds.isEmpty()) {
                            "Keine passenden Quoten gespeichert; daher kein Quoten-Tipp."
                        } else {
                            "Keine Quote ab 1,50 mit positivem modelliertem Erwartungswert."
                        },
                    )
                    Text(
                        "Der geschätzte Erwartungswert je eingesetzter Einheit ist Wahrscheinlichkeit × Quote − 1. " +
                            "Nur Märkte mit Quote ab 1,50 und positivem Modellwert werden als Quoten-Tipp gezeigt. " +
                            "Das ist eine unsichere Modellschätzung, keine Gewinnzusage oder Wettberatung.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Beide Teams treffen: ${percent(analysis.bothTeamsToScoreProbability)}")
                    Text("Doppelte Chance 1X / X2: ${percent(analysis.homeOrDrawProbability)} / ${percent(analysis.drawOrAwayProbability)}")
                    Text(
                        "Datenbasis: ${analysis.homeHistoryCount} vorherige Spiele für " +
                            "${display.homeTeamName ?: "Heimteam"}, ${analysis.awayHistoryCount} für " +
                            "${display.awayTeamName ?: "Auswärtsteam"}; " +
                            analysis.confidenceLabel,
                    )
                    Text(
                        "Ruhetage: ${analysis.homeRestDays?.toString() ?: "n/a"} / " +
                            "${analysis.awayRestDays?.toString() ?: "n/a"}",
                    )
                    Text(
                        "Elo: ${(analysis.homeElo - analysis.awayElo).format(0)} Punkte Differenz; " +
                            "geschätzter Heimvorteil ${analysis.estimatedHomeAdvantageElo.format(0)} Elo",
                    )
                    Text(
                        "Letzte Datenquelle: ${analysis.latestSourceUpdate ?: "Zeitpunkt nicht verfügbar"}; " +
                            "Zeit-Halbwertszeit ${analysis.halfLifeDays.format(0)} Tage; " +
                            "Dixon-Coles-ρ ${analysis.dixonColesRho.format(2)}",
                    )
                    Text(analysis.explanation)
                    Text(
                        "Top-Ergebnisse: " + analysis.scoreDistribution
                            .sortedByDescending { it.probability }
                            .take(5)
                            .joinToString(" · ") { "${it.homeGoals}:${it.awayGoals} ${percent(it.probability)}" },
                    )
                } else {
                    Text("Für dieses Spiel liegt noch keine ausführliche Prognose vor.")
                }
                if (analysisInvalid) {
                    Text(
                        "Die gespeicherte Prognoseanalyse ist beschädigt.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                details?.let { data ->
                    Text("Form in diesem Wettbewerb", style = MaterialTheme.typography.titleSmall)
                    Text("${data.homeTeamName}: ${formatRecentForm(data.homeHistory, data.fixture.homeTeamId)}")
                    Text("${data.awayTeamName}: ${formatRecentForm(data.awayHistory, data.fixture.awayTeamId)}")
                    Text("Direkte Duelle vor diesem Spiel", style = MaterialTheme.typography.titleSmall)
                    if (data.headToHead.isEmpty()) {
                        Text("Keine direkten Duelle in den lokal gespeicherten Daten.")
                    } else {
                        data.headToHead.forEach { match ->
                            Text(
                                "${match.matchDate}: ${data.teamNames[match.homeTeamId] ?: "TBD"} " +
                                    "${match.homeScore}:${match.awayScore} ${data.teamNames[match.awayTeamId] ?: "TBD"}",
                            )
                        }
                    }
                    Text("Tabellenstand der lokal gespeicherten Saison", style = MaterialTheme.typography.titleSmall)
                    val standings = calculateStandings(data.seasonFixtures)
                    listOf(data.fixture.homeTeamId, data.fixture.awayTeamId)
                        .filterNotNull()
                        .distinct()
                        .forEach { teamId ->
                            val rank = standings.indexOfFirst { it.teamId == teamId } + 1
                            standings.getOrNull(rank - 1)?.let { row ->
                                Text(
                                    "$rank. ${data.teamNames[teamId] ?: "Team"} · ${row.points} Pkt. · " +
                                        "${row.played} Spiele · ${row.goalsFor}:${row.goalsAgainst} Tore",
                                )
                            } ?: Text("${data.teamNames[teamId] ?: "Team"}: noch keine Saisonwerte.")
                        }
                } ?: if (detailError == null) {
                    Text("Lokale Teamform und Saisondaten werden geladen …")
                } else {
                    Unit
                }
                Text("Zusatzdaten von API-Football", style = MaterialTheme.typography.titleSmall)
                Text(
                    "xG: ${display.homeXg?.format(2) ?: "—"} : ${display.awayXg?.format(2) ?: "—"} · " +
                        "Schüsse: ${display.homeShots?.toString() ?: "—"} : ${display.awayShots?.toString() ?: "—"} · " +
                        "Ballbesitz: ${display.homePossession?.format(1)?.plus("%") ?: "—"} : " +
                        "${display.awayPossession?.format(1)?.plus("%") ?: "—"}",
                )
                val lineupSummary = remember(display.lineupsJson) { parseLineupSummary(display.lineupsJson) }
                val injurySummary = remember(display.injuriesJson) { parseInjurySummary(display.injuriesJson) }
                if (lineupSummary.isEmpty()) {
                    Text("Aufstellungen: keine Daten gespeichert.")
                } else {
                    Text("Aufstellungen:")
                    lineupSummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (injurySummary.isEmpty()) {
                    Text("Verletzungen: keine Daten gespeichert.")
                } else {
                    Text("Verletzungen:")
                    injurySummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text(
                    "Kartenfelder werden von der verwendeten API-Football-Statistikantwort nicht zuverlässig " +
                        "bereitgestellt und deshalb nicht geschätzt. Historische StatsBomb-Daten bleiben separat.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

private fun formatRecentForm(history: List<FixtureEntity>, teamId: Int?): String {
    if (history.isEmpty() || teamId == null) return "keine gespeicherten Ergebnisse"
    return history.asReversed().takeLast(5).joinToString("  ") { match ->
        val isHome = match.homeTeamId == teamId
        val goalsFor = if (isHome) match.homeScore else match.awayScore
        val goalsAgainst = if (isHome) match.awayScore else match.homeScore
        val outcome = when {
            goalsFor == null || goalsAgainst == null -> "?"
            goalsFor > goalsAgainst -> "S"
            goalsFor == goalsAgainst -> "U"
            else -> "N"
        }
        "$outcome $goalsFor:$goalsAgainst"
    }
}

private fun calculateStandings(fixtures: List<FixtureEntity>): List<LocalStanding> {
    val standings = mutableMapOf<Int, LocalStanding>()
    fixtures.filter {
        it.fullTimeResult &&
            it.homeTeamId != null &&
            it.awayTeamId != null &&
            it.homeScore != null &&
            it.awayScore != null
    }.forEach { match ->
        val homeId = match.homeTeamId!!
        val awayId = match.awayTeamId!!
        val homeGoals = match.homeScore!!
        val awayGoals = match.awayScore!!
        val homePoints = when {
            homeGoals > awayGoals -> 3
            homeGoals == awayGoals -> 1
            else -> 0
        }
        val awayPoints = when {
            awayGoals > homeGoals -> 3
            homeGoals == awayGoals -> 1
            else -> 0
        }
        val home = standings[homeId] ?: LocalStanding(homeId, 0, 0, 0, 0)
        val away = standings[awayId] ?: LocalStanding(awayId, 0, 0, 0, 0)
        standings[homeId] = home.copy(
            played = home.played + 1,
            points = home.points + homePoints,
            goalsFor = home.goalsFor + homeGoals,
            goalsAgainst = home.goalsAgainst + awayGoals,
        )
        standings[awayId] = away.copy(
            played = away.played + 1,
            points = away.points + awayPoints,
            goalsFor = away.goalsFor + awayGoals,
            goalsAgainst = away.goalsAgainst + homeGoals,
        )
    }
    return standings.values.sortedWith(
        compareByDescending<LocalStanding> { it.points }
            .thenByDescending { it.goalDifference }
            .thenByDescending { it.goalsFor },
    )
}

private const val DETAIL_HISTORY_LIMIT = 10

@Composable
private fun PerformanceScreen(database: SportPrediDatabase) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LocalBacktestReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadOrRun(startNewRun: Boolean) {
        scope.launch {
            loading = true
            error = null
            try {
                report = withContext(Dispatchers.IO) {
                    if (startNewRun) {
                        val run = LocalBacktestEngine(database).run()
                        LocalPredictionEngine(database.sportPrediDao()).refreshUpcomingPredictions()
                        run
                    } else {
                        val dao = database.sportPrediDao()
                        val latest = dao.latestBacktest()
                        if (latest == null) {
                            null
                        } else {
                            LocalBacktestReport(
                                runId = latest.id,
                                createdAt = latest.createdAt,
                                sampleSize = latest.sampleSize,
                                models = dao.backtestMetrics(latest.id).map {
                                    LocalModelMetric(
                                        modelVersion = it.modelVersion,
                                        sampleSize = it.sampleSize,
                                        logLoss = it.logLoss,
                                        brierScore = it.brierScore,
                                        accuracy = it.accuracy,
                                        meanAbsoluteGoalError = it.meanAbsoluteGoalError,
                                        scope = it.scope,
                                        calibrationError = it.calibrationError,
                                    )
                                },
                                trainEndDate = latest.trainEndDate,
                                validationEndDate = latest.validationEndDate,
                                selectedDixonColesRho = latest.selectedDixonColesRho ?: 0.0,
                                selectedHalfLifeDays = latest.selectedHalfLifeDays ?: 365.0,
                            )
                        }
                    }
                }
            } catch (exception: Exception) {
                error = exception.message ?: "Backtest-Daten konnten nicht geladen werden."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadOrRun(startNewRun = false)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Prediction-Qualität", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Chronologischer Walk-forward-Vergleich lokaler Modelle. Alle Spiele eines Datums werden " +
                "prognostiziert, bevor Ergebnisse dieses Tages einfließen. Halbwertszeit (180/365/730 Tage) " +
                "und ρ werden auf der Validierung anhand der Accuracy ausgewählt; der spätere Testzeitraum " +
                "bleibt für diese Auswahl unangetastet. Bei weniger als 100 Validierungsprognosen gelten " +
                "die Standardwerte.",
        )
        Button(onClick = { loadOrRun(startNewRun = true) }, enabled = !loading) {
            Text("Backtest lokal starten")
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
        if (report == null && !loading && error == null) {
            Text("Noch keine gespeicherten Backtest-Ergebnisse vorhanden.")
        }
        report?.let { result ->
            Text(
                "Lauf #${result.runId} · ${formatTimestamp(result.createdAt)} · " +
                    "Prognostizierbare Spiele: ${result.sampleSize}",
            )
            Text(
                "Training bis ${result.trainEndDate ?: "gesamter Zeitraum"} · " +
                    "Validierung bis ${result.validationEndDate ?: "nicht separat verfügbar"} · " +
                    "Test ab ${
                        result.validationEndDate?.let {
                            LocalDate.parse(it).plusDays(1).toString()
                        } ?: "nicht separat verfügbar"
                    } · " +
                    "Halbwertszeit=${result.selectedHalfLifeDays.format(0)} Tage · " +
                        "ρ=${result.selectedDixonColesRho.format(2)}",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.models.groupBy { it.scope }.forEach { (scope, metrics) ->
                    Text(scopeLabel(scope), style = MaterialTheme.typography.titleMedium)
                    metrics.forEach { model ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(model.modelVersion, style = MaterialTheme.typography.titleSmall)
                                Text("Stichprobe: ${model.sampleSize}")
                                Text("Accuracy: ${model.accuracy?.let(::percent) ?: "n/a"}")
                                Text("Log Loss: ${model.logLoss?.format(4) ?: "n/a"}")
                                Text("Brier Score: ${model.brierScore?.format(4) ?: "n/a"}")
                                Text("Kalibrierungsfehler (ECE): ${model.calibrationError?.let { it.format(4) } ?: "n/a"}")
                                Text("Tor-MAE: ${model.meanAbsoluteGoalError?.format(3) ?: "n/a"}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun scopeLabel(scope: String): String = when (scope) {
    "train" -> "Training"
    "validation" -> "Validierung"
    "test" -> "Unabhängiger Test"
    else -> "Gesamter Zeitraum"
}

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)

private fun percent(value: Double): String = "${(value * 100).format(1)}%"

private fun coveragePercent(part: Int, total: Int): String =
    if (total == 0) "—" else percent(part.toDouble() / total)

private fun formatTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "Noch keine Synchronisierung"
    return try {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneOffset.UTC)
            .format(Instant.parse(value)) + " UTC"
    } catch (_: Exception) {
        value
    }
}
