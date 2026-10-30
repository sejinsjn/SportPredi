package com.sportpredi

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonParseException
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

private enum class AppPage {
    FIXTURES,
    PERFORMANCE,
    SETTINGS,
}

private data class TargetCatalogUiData(
    val coverageRows: List<SeasonCoverageRow>,
    val checkedAt: String?,
    val availableIds: String?,
    val missingIds: String?,
    val currentSeasonIds: String?,
    val catalogJson: String?,
    val selectedCompetitionIds: String?,
    val selectedTeamIds: String?,
    val teams: List<TeamEntity>,
    val teamCompetitionIds: Map<Int, Set<Int>>,
)

@Composable
private fun AppScreen(database: SportPrediDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupRepository = remember(database) { LocalBackupRepository(database) }
    val dao = remember(database) { database.sportPrediDao() }
    val apiCredentialsStore = remember(context) { ApiCredentialsStore(context) }
    val syncRepository = remember(database) {
        LocalSyncRepository(database)
    }
    var selectedPage by remember { mutableStateOf(AppPage.FIXTURES) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showApiCredentials by remember { mutableStateOf(false) }
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
    val appScrollState = rememberScrollState()

    fun synchronize(force: Boolean) {
        if (syncLoading) return
        scope.launch {
            syncLoading = true
            syncError = null
            try {
                val report = withContext(Dispatchers.IO) {
                    syncRepository.synchronize(
                        apiKey = apiCredentialsStore.load().apiFootballKey,
                        force = force,
                    ) { progress ->
                        Handler(Looper.getMainLooper()).post {
                            syncMessage = progress.message
                        }
                    }
                }
                val predictionsCreated = withContext(Dispatchers.IO) {
                    LocalPredictionEngine(dao).refreshUpcomingPredictions()
                }
                lastUpdated = report.completedAt
                syncMessage = if (report.wasThrottled) {
                    "API-Football-Anfrage wegen des lokalen Cooldowns ausgelassen."
                } else {
                    "${report.competitionsChecked} API-Football-Wettbewerbe; " +
                        "${report.fixturesImported} Spiele; ${report.requestsUsed}/" +
                        "${LocalSyncRepository.MAX_REQUESTS_PER_SYNC} kontingentpflichtige API-Aufrufe " +
                        "(Statuscheck ausgenommen); " +
                        "$predictionsCreated Prognose-Snapshots erstellt." +
                        missingTargetSummary(report)
                }
                val errors = report.errors
                if (errors.isNotEmpty()) syncError = errors.joinToString("\n")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                syncError = syncFailureMessage(exception)
                syncMessage = "Synchronisierung fehlgeschlagen."
            } finally {
                syncLoading = false
                lastUpdated = withContext(Dispatchers.IO) {
                    dao.syncMetadata("lastApiFootballSyncAt")?.value ?: lastUpdated
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
        syncMessage = "API-Football-Daten werden vorbereitet ..."
        try {
            val report = withContext(Dispatchers.IO) {
                syncRepository.synchronize(apiCredentialsStore.load().apiFootballKey)
            }
            val predictionsCreated = withContext(Dispatchers.IO) {
                LocalPredictionEngine(dao).refreshUpcomingPredictions()
            }
            syncMessage = if (report.wasThrottled) {
                "API-Football-Abfrage wegen des lokalen Cooldowns ausgelassen."
            } else if (report.requestsUsed == 0 && report.errors.isEmpty()) {
                "API-Football-Daten sind aktuell."
            } else {
                "${report.fixturesImported} Spiele synchronisiert; $predictionsCreated Prognose-Snapshots erstellt." +
                    missingTargetSummary(report)
            }
            if (report.errors.isNotEmpty()) syncError = report.errors.joinToString("\n")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            syncError = syncFailureMessage(exception)
            syncMessage = "API-Football-Synchronisierung fehlgeschlagen."
        } finally {
            syncLoading = false
            lastUpdated = withContext(Dispatchers.IO) {
                dao.syncMetadata("lastApiFootballSyncAt")?.value ?: lastUpdated
            }
            coverageRefresh += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(appScrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { selectedPage = AppPage.FIXTURES },
                modifier = Modifier.weight(1f),
            ) {
                Text("Spiele")
            }
            Box(
                modifier = Modifier.weight(1f),
            ) {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Menü")
                }
                DropdownMenu(
                    modifier = Modifier.width(220.dp),
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Prediction-Qualität") },
                        onClick = {
                            selectedPage = AppPage.PERFORMANCE
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Einstellungen") },
                        onClick = {
                            selectedPage = AppPage.SETTINGS
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        when (selectedPage) {
            AppPage.FIXTURES -> FixturesScreen(
                database = database,
                syncRepository = syncRepository,
                apiKey = apiCredentialsStore.load().apiFootballKey,
                lastUpdated = lastUpdated,
                coverageRefresh = coverageRefresh,
                syncMessage = syncMessage,
                syncError = syncError,
                loading = syncLoading,
                appScrollState = appScrollState,
                onSynchronize = { synchronize(force = true) },
            )
            AppPage.PERFORMANCE -> PerformanceScreen(database)
            AppPage.SETTINGS -> SettingsScreen(
                apiCredentialsMessage = apiCredentialsMessage,
                apiCredentialsError = apiCredentialsError,
                backupMessage = backupMessage,
                backupError = backupError,
                syncLoading = syncLoading,
                onConfigureProviders = {
                    scope.launch {
                        try {
                            val credentials = withContext(Dispatchers.IO) { apiCredentialsStore.load() }
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
                onExportBackup = { exportLauncher.launch("sportpredi-backup.json") },
                onImportBackup = { importLauncher.launch(arrayOf("application/json", "text/*")) },
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
                                    apiCredentialsStore.saveApiFootballKey(apiFootballKey)
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
                            apiFootballKey = ""
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        apiCredentialsStore.saveApiFootballKey("")
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
                                    dao.syncMetadata("lastApiFootballSyncAt")?.value
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
private fun SettingsScreen(
    apiCredentialsMessage: String?,
    apiCredentialsError: Boolean,
    backupMessage: String?,
    backupError: Boolean,
    syncLoading: Boolean,
    onConfigureProviders: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Datenanbieter", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optionale API-Schlüssel für ergänzende Quoten und Spieldaten verwalten.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onConfigureProviders,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("API-Schlüssel verwalten")
                }
                apiCredentialsMessage?.let {
                    Text(
                        it,
                        color = if (apiCredentialsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Lokale Daten", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sicherung exportieren oder eine vorhandene Sicherung wiederherstellen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onExportBackup,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Backup exportieren")
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        enabled = !syncLoading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Wiederherstellen")
                    }
                }
                backupMessage?.let {
                    Text(
                        it,
                        color = if (backupError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FixturesScreen(
    database: SportPrediDatabase,
    syncRepository: LocalSyncRepository,
    apiKey: String,
    lastUpdated: String?,
    coverageRefresh: Int,
    syncMessage: String,
    syncError: String?,
    loading: Boolean,
    appScrollState: ScrollState,
    onSynchronize: () -> Unit,
) {
    val dao = remember(database) { database.sportPrediDao() }
    var range by remember { mutableStateOf("7 Tage") }
    var countryFilter by remember { mutableStateOf("") }
    var leagueFilter by remember { mutableStateOf("") }
    var seasonFilter by remember { mutableStateOf("") }
    var teamFilter by remember { mutableStateOf("") }
    var filtersExpanded by remember { mutableStateOf(false) }
    val activeFilterCount = listOf(countryFilter, leagueFilter, seasonFilter, teamFilter)
        .count(String::isNotBlank)
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
    var targetCatalogChecked by remember { mutableStateOf(false) }
    var targetCatalogCheckedAt by remember { mutableStateOf<String?>(null) }
    var targetCatalogAvailableIds by remember { mutableStateOf(emptySet<Int>()) }
    var targetCatalogMissingIds by remember { mutableStateOf(emptySet<Int>()) }
    var targetCatalogCurrentSeasonIds by remember { mutableStateOf(emptySet<Int>()) }
    var catalogItems by remember { mutableStateOf(emptyList<ApiFootballLeagueDto>()) }
    var selectedCompetitionIds by remember { mutableStateOf(emptySet<Int>()) }
    var selectedTeamIds by remember { mutableStateOf<Set<Int>?>(null) }
    var teams by remember { mutableStateOf(emptyList<TeamEntity>()) }
    var teamCompetitionIds by remember { mutableStateOf(emptyMap<Int, Set<Int>>()) }
    var showDataSelection by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    var selectionSaving by remember { mutableStateOf(false) }
    var selectedFixture by remember { mutableStateOf<FixtureDisplay?>(null) }
    var showFixturesInfo by remember { mutableStateOf(false) }
    var restoreListScrollPosition by remember { mutableStateOf(false) }
    var listScrollPosition by remember { mutableStateOf(0) }
    val fixtureScreenScope = rememberCoroutineScope()
    BackHandler(enabled = selectedFixture != null) {
        selectedFixture = null
    }
    LaunchedEffect(database, lastUpdated, coverageRefresh) {
        val result = withContext(Dispatchers.IO) {
            TargetCatalogUiData(
                coverageRows = dao.seasonCoverage(),
                checkedAt = dao.syncMetadata(API_FOOTBALL_TARGET_CATALOG_CHECKED_KEY)?.value,
                availableIds = dao.syncMetadata(API_FOOTBALL_TARGET_CATALOG_AVAILABLE_KEY)?.value,
                missingIds = dao.syncMetadata(API_FOOTBALL_TARGET_CATALOG_MISSING_KEY)?.value,
                currentSeasonIds = dao.syncMetadata(API_FOOTBALL_TARGET_CURRENT_SEASONS_KEY)?.value,
                catalogJson = dao.syncMetadata(API_FOOTBALL_CATALOG_JSON_KEY)?.value,
                selectedCompetitionIds = dao.syncMetadata(API_FOOTBALL_SELECTED_COMPETITIONS_KEY)?.value,
                selectedTeamIds = dao.syncMetadata(API_FOOTBALL_SELECTED_TEAMS_KEY)?.value,
                teams = dao.allTeams(),
                teamCompetitionIds = dao.allFixtures()
                    .flatMap { fixture ->
                        val competitionId = fixture.competitionCode.substringAfterLast(':').toIntOrNull()
                            ?: return@flatMap emptyList()
                        listOfNotNull(
                            fixture.homeTeamId?.let { it to competitionId },
                            fixture.awayTeamId?.let { it to competitionId },
                        )
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.toSet() },
            )
        }
        coverageRows = result.coverageRows
        targetCatalogChecked = result.checkedAt != null
        targetCatalogCheckedAt = result.checkedAt
        targetCatalogAvailableIds = parseApiFootballTargetIds(result.availableIds)
        targetCatalogMissingIds = parseApiFootballTargetIds(result.missingIds)
        targetCatalogCurrentSeasonIds = parseApiFootballTargetIds(result.currentSeasonIds)
        catalogItems = result.catalogJson?.let { json ->
            runCatching {
                Gson().fromJson(json, Array<ApiFootballLeagueDto>::class.java)?.toList().orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
        selectedCompetitionIds = result.selectedCompetitionIds?.let(::parseApiFootballTargetIds)
            ?: defaultApiFootballCompetitionSelection(catalogItems)
        selectedTeamIds = parseApiFootballTeamSelection(result.selectedTeamIds)
        teams = result.teams
        teamCompetitionIds = result.teamCompetitionIds
    }

    val selectedTeams = selectedTeamIds
    val visibleFixtures = fixtures.filter { fixture ->
        fixture.competitionCode.substringAfterLast(':').toIntOrNull()?.let { competitionId ->
            isApiFootballFixtureSelected(
                competitionId = competitionId,
                homeTeamId = fixture.homeTeamId,
                awayTeamId = fixture.awayTeamId,
                selectedCompetitionIds = selectedCompetitionIds,
                selectedTeamIds = selectedTeams,
            )
        } == true &&
            (fixture.country ?: "").contains(countryFilter, ignoreCase = true) &&
            (fixture.competitionName ?: "").contains(leagueFilter, ignoreCase = true) &&
            (seasonFilter.isBlank() || fixture.seasonYear?.toString()?.contains(seasonFilter) == true) &&
            (
                (fixture.homeTeamName ?: "").contains(teamFilter, ignoreCase = true) ||
                    (fixture.awayTeamName ?: "").contains(teamFilter, ignoreCase = true)
                )
    }

    if (selectedFixture != null) {
        FixtureDetailsScreen(
            display = selectedFixture!!,
            dao = dao,
            syncRepository = syncRepository,
            apiKey = apiKey,
            onBack = { selectedFixture = null },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    if (restoreListScrollPosition) {
                        restoreListScrollPosition = false
                        val savedPosition = listScrollPosition
                        fixtureScreenScope.launch {
                            appScrollState.scrollTo(savedPosition.coerceAtMost(appScrollState.maxValue))
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Kommende Spiele", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { showFixturesInfo = true }) {
                Text("Info")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Aktualisiert: ${formatTimestamp(lastUpdated)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        syncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
                Button(
                    onClick = onSynchronize,
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Aktualisieren", maxLines = 1)
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("Heute", "Morgen", "7 Tage", "Saison").forEach { option ->
                FilterChip(
                    selected = range == option,
                    onClick = { range = option },
                    label = { Text(option, maxLines = 1) },
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filtersExpanded = !filtersExpanded }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (activeFilterCount == 0) "Filter"
                        else "Filter · $activeFilterCount aktiv",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeFilterCount > 0) {
                            TextButton(
                                onClick = {
                                    countryFilter = ""
                                    leagueFilter = ""
                                    seasonFilter = ""
                                    teamFilter = ""
                                },
                            ) {
                                Text("Zurücksetzen")
                            }
                        }
                        Text(if (filtersExpanded) "Ausblenden" else "Anzeigen")
                    }
                }
                if (filtersExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            countryFilter,
                            { countryFilter = it },
                            label = { Text("Land") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            leagueFilter,
                            { leagueFilter = it },
                            label = { Text("Liga") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            seasonFilter,
                            { seasonFilter = it },
                            label = { Text("Saison") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            teamFilter,
                            { teamFilter = it },
                            label = { Text("Mannschaft") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Meine Datenauswahl", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${selectedCompetitionIds.size} Wettbewerbe · " +
                            (selectedTeamIds?.size?.let { "$it Mannschaften" } ?: "alle Mannschaften"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = { showDataSelection = true },
                    enabled = catalogItems.isNotEmpty() && !loading && !selectionSaving,
                ) {
                    Text("Auswählen")
                }
            }
            if (catalogItems.isEmpty()) {
                Text(
                    "Der vollständige API-Football-Katalog wird beim nächsten Aktualisieren geladen.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            selectionError?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (showDataSelection) {
            ApiFootballSelectionDialog(
                catalog = catalogItems,
                teams = teams,
                teamCompetitionIds = teamCompetitionIds,
                selectedCompetitionIds = selectedCompetitionIds,
                selectedTeamIds = selectedTeamIds,
                saving = selectionSaving,
                onDismiss = { if (!selectionSaving) showDataSelection = false },
                onSave = { competitionIds, teamIds ->
                    fixtureScreenScope.launch {
                        selectionSaving = true
                        selectionError = null
                        try {
                            withContext(Dispatchers.IO) {
                                dao.upsertSyncMetadata(
                                    SyncMetadataEntity(
                                        API_FOOTBALL_SELECTED_COMPETITIONS_KEY,
                                        serializeApiFootballIds(competitionIds),
                                    ),
                                )
                                dao.upsertSyncMetadata(
                                    SyncMetadataEntity(
                                        API_FOOTBALL_SELECTED_TEAMS_KEY,
                                        teamIds?.let(::serializeApiFootballIds)
                                            ?: API_FOOTBALL_ALL_TEAMS_SENTINEL,
                                    ),
                                )
                            }
                            selectedCompetitionIds = competitionIds
                            selectedTeamIds = teamIds
                            showDataSelection = false
                            onSynchronize()
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            selectionError = "Auswahl konnte nicht gespeichert werden: " +
                                (exception.message ?: "Unbekannter Fehler")
                        } finally {
                            selectionSaving = false
                        }
                    }
                },
            )
        }
        if (loading) CircularProgressIndicator()
        syncError?.let { Text("Synchronisierungsfehler: $it", color = MaterialTheme.colorScheme.error) }
        if (visibleFixtures.isEmpty() && !loading) {
            Text("Keine Spiele für diesen Zeitraum in den lokal gespeicherten Daten gefunden.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleFixtures.forEach { fixture ->
                FixtureCard(fixture) {
                    listScrollPosition = appScrollState.value
                    fixtureScreenScope.launch {
                        appScrollState.scrollTo(0)
                        selectedFixture = fixture
                        restoreListScrollPosition = true
                    }
                }
            }
        }
        if (coverageRows.isNotEmpty() || targetCatalogChecked) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Datenbasis & Abdeckung", style = MaterialTheme.typography.titleLarge)
                    if (targetCatalogChecked) {
                        Text(
                            "API-Football-Zielkatalog (exakte Wettbewerbs-IDs), zuletzt geprüft " +
                                formatTimestamp(targetCatalogCheckedAt) + ":",
                        )
                        API_FOOTBALL_TARGET_COMPETITIONS.forEach { target ->
                            val status = when {
                                target.providerId in targetCatalogMissingIds -> "vom Katalog nicht geliefert"
                                target.providerId !in targetCatalogAvailableIds -> "nicht bestätigt"
                                target.providerId in targetCatalogCurrentSeasonIds -> "aktuelle Saison verfügbar"
                                else -> "im Katalog, aber keine aktuelle Saison"
                            }
                            Text("${target.name} (${target.providerId}) · $status")
                        }
                    }
                    Text(
                        "${coverageRows.map { it.competitionCode }.distinct().size} Wettbewerbe · " +
                            "${coverageRows.size} Saisons · " +
                            "${coverageRows.sumOf { it.fixtureCount }} Spiele lokal gespeichert",
                    )
                    Text(
                        "Der API-Football-Katalog wird dynamisch geladen. Nur deine gespeicherten Wettbewerbe " +
                            "und Mannschaften werden synchronisiert; nicht unterstützte Wettbewerbe werden " +
                            "nicht durch ähnlich benannte Ligen ersetzt.",
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
    }
    if (showFixturesInfo) {
        AlertDialog(
            onDismissRequest = { showFixturesInfo = false },
            title = { Text("Info zu kommenden Spielen") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Datenquelle: API-Football. Wettbewerbe, Spielzeiten, Mannschaften und Ergebnisse " +
                            "werden ausschliesslich von dort geladen. Der Sync ist auf " +
                            "${LocalSyncRepository.MAX_REQUESTS_PER_SYNC} kontingentpflichtige API-Aufrufe " +
                            "begrenzt; der Statuscheck ist davon ausgenommen.",
                    )
                    Text(
                        "Für alle kommenden Spiele werden 1X2-Wahrscheinlichkeiten und die wahrscheinlichste " +
                            "Auswahl angezeigt. 90 % ist ein Modellziel, keine Ausgabeschwelle; die tatsächliche " +
                            "Trefferquote und Verbesserung gegenüber dem alten Modell werden im zeitlichen " +
                            "Holdout-Backtest berichtet.",
                    )
                    Text(
                        "Die Standardauswahl umfasst die bisherigen Hauptligen, Pokale und UEFA-Wettbewerbe. " +
                            "Weitere Wettbewerbe kannst du oben im API-Football-Katalog auswählen. Saison " +
                            "$currentSeasonLabel bis $currentSeasonEnd.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFixturesInfo = false }) {
                    Text("Schließen")
                }
            },
        )
    }
}

@Composable
private fun ApiFootballSelectionDialog(
    catalog: List<ApiFootballLeagueDto>,
    teams: List<TeamEntity>,
    teamCompetitionIds: Map<Int, Set<Int>>,
    selectedCompetitionIds: Set<Int>,
    selectedTeamIds: Set<Int>?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<Int>, Set<Int>?) -> Unit,
) {
    var competitionIds by remember(selectedCompetitionIds) {
        mutableStateOf(selectedCompetitionIds)
    }
    var teamIds by remember(selectedTeamIds) { mutableStateOf(selectedTeamIds) }
    var countrySearch by remember { mutableStateOf("") }
    var competitionSearch by remember { mutableStateOf("") }
    var teamSearch by remember { mutableStateOf("") }

    val competitionsByCountry = catalog.groupBy { it.country?.name?.takeIf(String::isNotBlank) ?: "Land unbekannt" }
    val countries = competitionsByCountry.keys.sorted()
        .filter { it.contains(countrySearch, ignoreCase = true) }
    val competitionMatches = catalog.filter { competition ->
        val country = competition.country?.name.orEmpty()
        (countrySearch.isBlank() || country.contains(countrySearch, ignoreCase = true)) &&
            (
                competitionSearch.isBlank() ||
                    competition.league.name.contains(competitionSearch, ignoreCase = true) ||
                    country.contains(competitionSearch, ignoreCase = true)
                )
    }
    val selectableTeams = teams.filter { team ->
        teamCompetitionIds[team.providerTeamId].orEmpty().any(competitionIds::contains)
    }.filter { it.name.contains(teamSearch, ignoreCase = true) }
    val competitionsWithoutKnownTeams = competitionIds -
        teamCompetitionIds.values.flatten().toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Länder, Wettbewerbe und Teams") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Nur ausgewählte Wettbewerbe und Mannschaften werden synchronisiert und angezeigt. " +
                        "Der Katalog enthält alle von API-Football gemeldeten Ligen und Pokale.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = countrySearch,
                    onValueChange = { countrySearch = it },
                    label = { Text("Land suchen") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("Länder", style = MaterialTheme.typography.titleSmall)
                if (countries.isEmpty()) {
                    Text("Kein passendes Land.")
                } else {
                    countries.take(30).forEach { country ->
                        val ids = competitionsByCountry[country].orEmpty()
                            .map { it.league.id }
                            .toSet()
                        val allSelected = ids.isNotEmpty() && ids.all(competitionIds::contains)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { selected ->
                                    competitionIds = if (selected) competitionIds + ids
                                    else competitionIds - ids
                                },
                            )
                            Text("$country · ${ids.size} Wettbewerbe")
                        }
                    }
                    if (countries.size > 30) {
                        Text("Weitere Länder über die Suche eingrenzen.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = competitionSearch,
                    onValueChange = { competitionSearch = it },
                    label = { Text("Liga oder Wettbewerb suchen") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { competitionIds = catalog.map { it.league.id }.toSet() }) {
                        Text("Alle Wettbewerbe")
                    }
                    TextButton(onClick = { competitionIds = emptySet() }) {
                        Text("Keine")
                    }
                }
                Text(
                    "Wettbewerbe · ${competitionIds.size} ausgewählt",
                    style = MaterialTheme.typography.titleSmall,
                )
                competitionMatches.take(60).forEach { competition ->
                    val id = competition.league.id
                    val typeLabel = when (competition.league.type?.lowercase()) {
                        "league" -> "Liga"
                        "cup" -> "Pokal"
                        else -> "Wettbewerb"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in competitionIds,
                            onCheckedChange = { selected ->
                                competitionIds = if (selected) competitionIds + id
                                else competitionIds - id
                            },
                        )
                        Text(
                            "${competition.country?.name ?: "Land unbekannt"} · " +
                                "${competition.league.name} · $typeLabel",
                        )
                    }
                }
                if (competitionMatches.size > 60) {
                    Text("Weitere Wettbewerbe über die Suche eingrenzen.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Mannschaften", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (teamIds == null) {
                        "Alle Mannschaften der ausgewählten Wettbewerbe sind aktiv. " +
                            "Wähle ein Team aus, um die Synchronisierung darauf einzuschränken."
                    } else {
                        "${teamIds?.size ?: 0} Mannschaften ausgewählt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { teamIds = null }) { Text("Alle Mannschaften") }
                if (teamIds != null && competitionsWithoutKnownTeams.isNotEmpty()) {
                    Text(
                        "Für ${competitionsWithoutKnownTeams.size} ausgewählte Wettbewerbe sind noch keine " +
                            "Teams lokal importiert. Aktiviere zunächst „Alle Mannschaften“ und importiere " +
                            "die Spielpläne, bevor du dort Teams einzeln auswählst.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = teamSearch,
                    onValueChange = { teamSearch = it },
                    label = { Text("Mannschaft suchen") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (selectableTeams.isEmpty()) {
                    Text("Für diese Wettbewerbe sind noch keine Teams lokal bekannt.")
                } else {
                    selectableTeams.take(60).forEach { team ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = teamIds?.contains(team.providerTeamId) == true,
                                onCheckedChange = { selected ->
                                    val current = teamIds ?: emptySet()
                                    teamIds = if (selected) current + team.providerTeamId
                                    else current - team.providerTeamId
                                },
                            )
                            Text(team.name)
                        }
                    }
                    if (selectableTeams.size > 60) {
                        Text("Weitere Mannschaften über die Suche eingrenzen.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(competitionIds, teamIds) },
                enabled = !saving,
            ) {
                Text(if (saving) "Speichern …" else "Speichern und aktualisieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun FixtureCard(fixture: FixtureDisplay, onClick: () -> Unit) {
    val analysis = remember(fixture.analysisJson) {
        parsePredictionAnalysis(fixture.analysisJson)
    }
    val outcomePrediction = remember(
        fixture.homeWinProbability,
        fixture.drawProbability,
        fixture.awayWinProbability,
    ) {
        if (
            fixture.homeWinProbability != null &&
            fixture.drawProbability != null &&
            fixture.awayWinProbability != null
        ) {
            mostLikely1X2Prediction(
                doubleArrayOf(
                    fixture.homeWinProbability,
                    fixture.drawProbability,
                    fixture.awayWinProbability,
                ),
            )
        } else {
            null
        }
    }
    val statusLabel = when {
        fixture.status == "CANCELLED" -> "Abgesagt"
        fixture.postponedOrCancelled -> "Verschoben"
        fixture.rescheduled -> "Neu terminiert"
        fixture.homeScore != null && fixture.awayScore != null -> "Beendet"
        else -> "Anstehend"
    }
    val statusColor = when {
        fixture.status == "CANCELLED" -> MaterialTheme.colorScheme.error
        fixture.postponedOrCancelled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${fixture.country ?: "Land unbekannt"} · ${fixture.competitionName ?: "Wettbewerb unbekannt"}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLabel,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
            Text(
                fixture.kickoffAt?.let(::formatTimestamp) ?: fixture.matchDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    fixture.homeTeamName ?: "TBD",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("vs", style = MaterialTheme.typography.bodySmall)
                Text(
                    fixture.awayTeamName ?: "TBD",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (fixture.homeScore != null && fixture.awayScore != null) {
                Text(
                    "Ergebnis · ${fixture.homeScore} : ${fixture.awayScore}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (fixture.venue?.isNotBlank() == true || fixture.seasonYear != null) {
                Text(
                    listOfNotNull(
                        fixture.venue?.takeIf(String::isNotBlank),
                        fixture.seasonYear?.let { "Saison $it" },
                        fixture.matchday?.let { "Spieltag $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (
                fixture.homeWinProbability != null &&
                fixture.drawProbability != null &&
                fixture.awayWinProbability != null
            ) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProbabilitySummaryCell("1 · Heimsieg", fixture.homeWinProbability, Modifier.weight(1f))
                    ProbabilitySummaryCell("X · Remis", fixture.drawProbability, Modifier.weight(1f))
                    ProbabilitySummaryCell("2 · Auswärts", fixture.awayWinProbability, Modifier.weight(1f))
                }
                outcomePrediction?.let {
                    Text(
                        "Modellprognose: ${outcomeDisplayLabel(it.selection)} " +
                            "(${percent(it.probability)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (fixture.expectedHomeGoals != null && fixture.expectedAwayGoals != null) {
                    Text(
                        "Erwartete Tore (Heim : Auswärts): ${fixture.expectedHomeGoals.format(2)} : " +
                            fixture.expectedAwayGoals.format(2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Noch keine Prognose verfügbar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ApiFootballValueTipSummary(
                homeProbability = fixture.homeWinProbability,
                drawProbability = fixture.drawProbability,
                awayProbability = fixture.awayWinProbability,
                analysis = analysis,
                quotesJson = fixture.oddsQuotesJson,
                status = fixture.oddsStatus,
                fetchedAt = fixture.oddsFetchedAt,
            )
            analysis?.let {
                Text(
                    "${it.confidenceLabel} · Tippen für Details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(
                "Tippen für Spielanalyse",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProbabilitySummaryCell(
    label: String,
    probability: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            percent(probability),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
}

private fun parsePredictionAnalysis(json: String?): PredictionAnalysis? =
    try {
        json?.let { Gson().fromJson(it, PredictionAnalysis::class.java) }
    } catch (exception: JsonParseException) {
        null
    }

private data class FixtureDetailsData(
    val fixture: FixtureEntity,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeHistory: List<FixtureEntity>,
    val awayHistory: List<FixtureEntity>,
    val headToHead: List<FixtureEntity>,
    val headToHeadError: String?,
    val teamNames: Map<Int, String>,
    val competitionNames: Map<String, String>,
    val standingsCached: Boolean,
    val standings: List<ApiFootballStandingRow>,
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
private fun FixtureDetailsScreen(
    display: FixtureDisplay,
    dao: SportPrediDao,
    syncRepository: LocalSyncRepository,
    apiKey: String,
    onBack: () -> Unit,
) {
    var details by remember(display.providerFixtureId) { mutableStateOf<FixtureDetailsData?>(null) }
    var detailError by remember(display.providerFixtureId) { mutableStateOf<String?>(null) }
    val analysis = remember(display.analysisJson) { parsePredictionAnalysis(display.analysisJson) }
    val analysisInvalid = display.analysisJson != null && analysis == null
    val outcomePrediction = remember(
        display.homeWinProbability,
        display.drawProbability,
        display.awayWinProbability,
        analysis?.mostLikelyOutcome,
        analysis?.mostLikelyOutcomeProbability,
    ) {
        if (
            display.homeWinProbability != null &&
            display.drawProbability != null &&
            display.awayWinProbability != null
        ) {
            mostLikely1X2Prediction(
                doubleArrayOf(
                    display.homeWinProbability,
                    display.drawProbability,
                    display.awayWinProbability,
                ),
            )
        } else {
            val outcome = analysis?.mostLikelyOutcome
            val probability = analysis?.mostLikelyOutcomeProbability
            if (outcome != null && probability != null) OutcomePrediction(outcome, probability) else null
        }
    }

    LaunchedEffect(display.providerFixtureId) {
        try {
            details = withContext(Dispatchers.IO) {
                val match = dao.fixture(display.providerFixtureId)
                    ?: error("Spieldetails sind lokal nicht mehr vorhanden.")
                val homeId = match.homeTeamId
                    ?: error("Die Heimmannschaft ist noch nicht zugeordnet.")
                val awayId = match.awayTeamId
                    ?: error("Die Auswärtsmannschaft ist noch nicht zugeordnet.")
                val standingsCache = dao.apiFootballStandings(match.seasonId)
                val standings = standingsCache?.let { cache ->
                    runCatching {
                        Gson().fromJson(cache.rowsJson, Array<ApiFootballStandingRow>::class.java)
                            ?.toList()
                            .orEmpty()
                    }.getOrDefault(emptyList())
                }.orEmpty()
                val selectedCompetitionIds = dao.syncMetadata(API_FOOTBALL_SELECTED_COMPETITIONS_KEY)
                    ?.value
                    ?.let(::parseApiFootballTargetIds)
                    ?: API_FOOTBALL_TARGET_COMPETITIONS.map { it.providerId }.toSet()
                val competitionCodes = selectedCompetitionIds.map { "api-football:$it" }
                val localHeadToHead = if (competitionCodes.isEmpty()) {
                    emptyList()
                } else {
                    dao.headToHead(
                        homeId,
                        awayId,
                        match.matchDate,
                        HEAD_TO_HEAD_LOOKBACK,
                        competitionCodes,
                    )
                }
                var headToHeadError: String? = null
                val headToHead = try {
                    syncRepository.synchronizeHeadToHead(
                        apiKey = apiKey,
                        homeTeamId = homeId,
                        awayTeamId = awayId,
                        beforeDate = match.matchDate,
                    )
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    headToHeadError = syncFailureMessage(exception)
                    localHeadToHead
                }
                val teams = dao.allTeams().associate { it.providerTeamId to it.name }
                val competitions = dao.allCompetitions().associate { it.providerCode to it.name }
                FixtureDetailsData(
                    fixture = match,
                    homeTeamName = teams[homeId] ?: display.homeTeamName ?: "Heimteam",
                    awayTeamName = teams[awayId] ?: display.awayTeamName ?: "Auswärtsteam",
                    homeHistory = dao.teamHistory(
                        homeId,
                        match.matchDate,
                        DETAIL_HISTORY_LIMIT,
                    ),
                    awayHistory = dao.teamHistory(
                        awayId,
                        match.matchDate,
                        DETAIL_HISTORY_LIMIT,
                    ),
                    headToHead = headToHead,
                    headToHeadError = headToHeadError,
                    teamNames = teams,
                    competitionNames = competitions,
                    standingsCached = standingsCache != null,
                    standings = standings,
                )
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            detailError = exception.message ?: "Spieldetails konnten nicht geladen werden."
        }
    }


    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("← Zurück zu den Spielen") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${display.competitionName ?: "Wettbewerb"} · ${display.country ?: "Land unbekannt"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(display.homeTeamName ?: "TBD", style = MaterialTheme.typography.headlineSmall)
                Text("gegen", style = MaterialTheme.typography.bodySmall)
                Text(display.awayTeamName ?: "TBD", style = MaterialTheme.typography.headlineSmall)
                Text(display.kickoffAt?.let(::formatTimestamp) ?: display.matchDate)
                listOfNotNull(
                    display.seasonYear?.let { "Saison $it" },
                    display.matchday?.let { "Spieltag $it" },
                    display.venue?.takeIf(String::isNotBlank),
                ).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when {
                        display.status == "CANCELLED" -> "Abgesagt"
                        display.postponedOrCancelled -> "Verschoben"
                        display.rescheduled -> "Neu terminiert"
                        display.homeScore != null && display.awayScore != null -> "Beendet"
                        else -> "Anstehend"
                    },
                    color = if (display.status == "CANCELLED" || display.postponedOrCancelled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (display.homeScore != null && display.awayScore != null) {
                    Text(
                        "Endstand · ${display.homeScore} : ${display.awayScore}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }

        DetailSection("Prognose") {
            if (analysis == null) {
                Text("Für dieses Spiel liegt noch keine ausführliche Prognose vor.")
            } else {
                Text("1X2-Wahrscheinlichkeiten", style = MaterialTheme.typography.labelLarge)
                if (
                    display.homeWinProbability != null &&
                    display.drawProbability != null &&
                    display.awayWinProbability != null
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProbabilitySummaryCell("1 · Heimsieg", display.homeWinProbability, Modifier.weight(1f))
                        ProbabilitySummaryCell("X · Remis", display.drawProbability, Modifier.weight(1f))
                        ProbabilitySummaryCell("2 · Auswärts", display.awayWinProbability, Modifier.weight(1f))
                    }
                }
                Text(
                    "Erwartete Tore (Heim : Auswärts): " +
                        "${display.expectedHomeGoals?.format(2) ?: "—"} : " +
                        "${display.expectedAwayGoals?.format(2) ?: "—"}",
                )
                Text("Wahrscheinlichstes Ergebnis: ${analysis.mostLikelyScore}")
                Text("Modell: ${LocalForecastingState.MODEL_VERSION}", style = MaterialTheme.typography.bodySmall)
                outcomePrediction?.let {
                    Text(
                        "1X2-Modellprognose: ${outcomeDisplayLabel(it.selection)} " +
                            "(${percent(it.probability)}).",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        DetailSection("Value-Tipp · API-Football-Quoten") {
            ApiFootballValueTipSummary(
                homeProbability = display.homeWinProbability,
                drawProbability = display.drawProbability,
                awayProbability = display.awayWinProbability,
                analysis = analysis,
                quotesJson = display.oddsQuotesJson,
                status = display.oddsStatus,
                fetchedAt = display.oddsFetchedAt,
            )
            Text(
                "Aus passenden Tipps wird zuerst die höchste Modellchance gewählt (mindestens " +
                    "${percent(ApiFootballTipSelectionRules.MIN_MODEL_PROBABILITY)}); die Quote muss " +
                    "${ApiFootballTipSelectionRules.MIN_DECIMAL_ODDS.format(2)}–" +
                    "${ApiFootballTipSelectionRules.MAX_DECIMAL_ODDS.format(2)} betragen. Der EV von " +
                    "mindestens ${(ApiFootballTipSelectionRules.MIN_EXPECTED_VALUE * 100).format(0)} % ist " +
                    "Mindestpuffer und Tie-Breaker. Eine Schätzung, keine Garantie für Treffer oder Gewinn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (analysis != null) {
            DetailSection("Weitere Modellwerte") {
                Text("Beide Teams treffen: ${percent(analysis.bothTeamsToScoreProbability)}")
                Text(
                    "Doppelte Chance 1X / X2: ${percent(analysis.homeOrDrawProbability)} / " +
                        percent(analysis.drawOrAwayProbability),
                )
                Text(
                    "Über 1,5 / 2,5 / 3,5 Tore: ${percent(analysis.over15Probability)} / " +
                        "${percent(analysis.over25Probability)} / ${percent(analysis.over35Probability)}",
                )
                Text(
                    "Datenbasis: ${analysis.homeHistoryCount} Spiele für ${display.homeTeamName ?: "Heimteam"}, " +
                        "${analysis.awayHistoryCount} für ${display.awayTeamName ?: "Auswärtsteam"} · " +
                        analysis.confidenceLabel,
                )
                Text(
                    "Ruhetage Heim/Auswärts: ${analysis.homeRestDays?.toString() ?: "—"} / " +
                        (analysis.awayRestDays?.toString() ?: "—"),
                )
                Text(
                    "Elo-Differenz: ${(analysis.homeElo - analysis.awayElo).format(0)} · " +
                        "Heimvorteil ${analysis.estimatedHomeAdvantageElo.format(0)}",
                )
                Text("Modellerklärung: ${analysis.explanation}")
                Text(
                    "Häufigste Ergebnisse: " + analysis.scoreDistribution
                        .sortedByDescending { it.probability }
                        .take(5)
                        .joinToString(" · ") {
                            "${it.homeGoals}:${it.awayGoals} ${percent(it.probability)}"
                        },
                )
            }
        }

        if (analysisInvalid) {
            Text("Die gespeicherte Prognoseanalyse ist beschädigt.", color = MaterialTheme.colorScheme.error)
        }
        detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        details?.let { data ->
            DetailSection("Form und direkte Duelle") {
                val homeMatches = data.fixture.homeTeamId?.let {
                    localTeamMatchSummaries(data.homeHistory, it, DETAIL_HISTORY_LIMIT)
                }.orEmpty()
                val awayMatches = data.fixture.awayTeamId?.let {
                    localTeamMatchSummaries(data.awayHistory, it, DETAIL_HISTORY_LIMIT)
                }.orEmpty()
                val homeApiForm = data.standings.firstOrNull {
                    it.teamId == data.fixture.homeTeamId
                }?.form?.let(::apiFootballFormLabel)
                val awayApiForm = data.standings.firstOrNull {
                    it.teamId == data.fixture.awayTeamId
                }?.form?.let(::apiFootballFormLabel)
                val homeLocalForm = localTeamFormLabel(homeMatches)
                val awayLocalForm = localTeamFormLabel(awayMatches)
                Text("${data.homeTeamName}: ${homeLocalForm ?: homeApiForm ?: "Form derzeit nicht verfügbar"}")
                Text("${data.awayTeamName}: ${awayLocalForm ?: awayApiForm ?: "Form derzeit nicht verfügbar"}")
                if ((homeLocalForm == null && homeApiForm != null) ||
                    (awayLocalForm == null && awayApiForm != null)
                ) {
                    Text("API-Football-Saisonform als Fallback (Sieg/Remis/Niederlage).")
                }
                Text("Letzte Spiele ${data.homeTeamName}", style = MaterialTheme.typography.labelLarge)
                if (homeMatches.isEmpty()) {
                    Text("Keine abgeschlossenen Spiele in der lokalen Datenbank.")
                } else {
                    homeMatches.forEach { match ->
                        Text(localTeamMatchDisplay(match, data.teamNames[match.opponentTeamId]))
                    }
                }
                Text("Letzte Spiele ${data.awayTeamName}", style = MaterialTheme.typography.labelLarge)
                if (awayMatches.isEmpty()) {
                    Text("Keine abgeschlossenen Spiele in der lokalen Datenbank.")
                } else {
                    awayMatches.forEach { match ->
                        Text(localTeamMatchDisplay(match, data.teamNames[match.opponentTeamId]))
                    }
                }
                HorizontalDivider()
                Text("Direkte Duelle", style = MaterialTheme.typography.labelLarge)
                data.headToHeadError?.let {
                    Text(
                        "Direkte Duelle konnten nicht aktualisiert werden: $it",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (data.headToHead.isEmpty()) {
                    Text("Keine direkten Duelle in den verfügbaren API- oder lokalen Daten.")
                } else {
                    data.headToHead.forEach { match ->
                        Text(
                            "${match.matchDate} · ${data.teamNames[match.homeTeamId] ?: "TBD"} " +
                                "${match.homeScore}:${match.awayScore} " +
                                "${data.teamNames[match.awayTeamId] ?: "TBD"} · " +
                                (data.competitionNames[match.competitionCode] ?: "Wettbewerb unbekannt"),
                        )
                    }
                }
            }
            DetailSection("Saison-Tabelle") {
                when {
                    !data.standingsCached ->
                        Text("API-Football-Saisonwerte sind noch nicht synchronisiert.")
                    data.standings.isEmpty() ->
                        Text("API-Football stellt für diesen Wettbewerb oder diese Saison keine Tabelle bereit.")
                    else -> listOfNotNull(data.fixture.homeTeamId, data.fixture.awayTeamId)
                        .distinct()
                        .forEach { teamId ->
                            val row = data.standings.firstOrNull { it.teamId == teamId }
                            if (row == null) {
                                Text("${data.teamNames[teamId] ?: "Team"} ist in der API-Tabelle nicht enthalten.")
                            } else {
                                Text(
                                    "${row.rank?.let { "$it. " }.orEmpty()}${row.teamName}" +
                                        "${row.group?.let { " · $it" }.orEmpty()} · " +
                                        "${row.points?.let { "$it Pkt." } ?: "Punkte —"} · " +
                                        "${row.played?.let { "$it Spiele" } ?: "Spiele —"} · " +
                                        "${row.goalsFor ?: "—"}:${row.goalsAgainst ?: "—"} Tore",
                                )
                            }
                        }
                }
            }
        } ?: if (detailError == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
                    Text("Spieldetails und gespeicherte API-Football-Form werden geladen …")
                }
            }
        } else {
            Unit
        }

    }
}

@Composable
private fun ApiFootballValueTipSummary(
    homeProbability: Double?,
    drawProbability: Double?,
    awayProbability: Double?,
    analysis: PredictionAnalysis?,
    quotesJson: String?,
    status: String?,
    fetchedAt: String?,
) {
    val quotes = remember(quotesJson) { parseApiFootballOddsQuotes(quotesJson) }
    val tip = remember(quotes, homeProbability, drawProbability, awayProbability, analysis, fetchedAt) {
        if (
            quotes != null &&
            homeProbability != null &&
            drawProbability != null &&
            awayProbability != null &&
            fetchedAt != null
        ) {
            selectBestApiFootballValueTip(
                quotes = quotes,
                homeProbability = homeProbability,
                drawProbability = drawProbability,
                awayProbability = awayProbability,
                fetchedAt = fetchedAt,
                analysis = analysis,
            )
        } else {
            null
        }
    }
    if (tip == null) {
        Text(
            when {
                homeProbability == null || drawProbability == null || awayProbability == null ->
                    "Kein Value-Tipp verfügbar: Modellwahrscheinlichkeiten fehlen."
                else ->
                    "Kein Tipp erfüllt alle Grenzen: Chance mindestens " +
                        "${percent(ApiFootballTipSelectionRules.MIN_MODEL_PROBABILITY)}, " +
                        "Quote ${ApiFootballTipSelectionRules.MIN_DECIMAL_ODDS.format(2)}–" +
                        "${ApiFootballTipSelectionRules.MAX_DECIMAL_ODDS.format(2)} und Modell-EV mindestens " +
                        "${(ApiFootballTipSelectionRules.MIN_EXPECTED_VALUE * 100).format(0)} %. " +
                        "Aus passenden Tipps gewinnt die höchste Modellchance; EV entscheidet bei Gleichstand."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "${apiFootballMarketLabel(tip.market, tip.outcome)} · " +
                "Quote ${tip.decimalOdds.format(2)} · Modellchance ${percent(tip.modelProbability)}" +
                (if (tip.pushProbability > 0.0) {
                    " · Remis ${percent(tip.pushProbability)} = Rückzahlung"
                } else {
                    ""
                }) + " · " +
                "EV +${(tip.expectedValue * 100).format(1)}% · ${tip.bookmaker}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        when (status) {
            null -> "API-Football-Quoten für dieses Spiel noch nicht lokal gespeichert."
            ApiFootballOddsCacheStatus.NO_ODDS ->
                "API-Football hat für dieses Spiel keine Vorabquoten geliefert."
            ApiFootballOddsCacheStatus.NO_SUPPORTED_QUOTES,
            ApiFootballOddsCacheStatus.NO_1X2_QUOTES ->
                "Keine unterstützten Quotenmärkte verfügbar."
            ApiFootballOddsCacheStatus.AVAILABLE -> when {
                quotes == null -> "Gespeicherte Quoten konnten nicht gelesen werden."
                quotes.isEmpty() -> "Keine unterstützten Quotenmärkte gespeichert."
                tip == null && homeProbability != null ->
                    "Keine verfügbare Quote erfüllt die Chancen-, Quoten- und EV-Grenzen."
                else -> "Quotenquelle: API-Football"
            }
            else -> "Quotenstatus unbekannt."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    fetchedAt?.let {
        Text(
            "Quotenstand: ${formatTimestamp(it)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                content()
            },
        )
    }
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
private const val HEAD_TO_HEAD_LOOKBACK = 10

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
                "bleibt für diese Auswahl unangetastet. Die Holdout-1X2-Trefferquote zählt die " +
                "wahrscheinlichste Klasse über alle Testspiele, nicht nur ausgewählte Tipps. 90 % ist ein " +
                "Zielwert; angezeigt wird der gemessene Wert und die tatsächliche Änderung gegenüber dem " +
                "alten Modell. Bei weniger als 100 Validierungsprognosen gelten die Standardwerte.",
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
            Text("Holdout-Vergleich zur alten Baseline ($OLD_BACKTEST_BASELINE_MODEL_VERSION)")
            if (result.holdoutComparisons.isEmpty()) {
                Text("Kein unabhängiger Holdout-Vergleich verfügbar.")
            } else {
                result.holdoutComparisons.forEach { comparison ->
                    val change = comparison.improvement?.let {
                        val sign = if (it > 0.0) "+" else ""
                        "$sign${(it * 100).format(1)} Prozentpunkte"
                    } ?: "nicht direkt vergleichbar"
                    val targetStatus = when {
                        comparison.meetsAccuracyGoal -> "90%-Benchmark im Holdout erreicht."
                        comparison.candidateSamples < MIN_HOLDOUT_SAMPLES_FOR_GOAL ->
                            "90%-Benchmark nicht belegt (zu wenige Holdout-Spiele)."
                        else -> "90%-Benchmark im Holdout nicht erreicht."
                    }
                    Text(
                        "${comparison.candidateModelVersion} · n=${comparison.candidateSamples} " +
                            "gegen Baseline n=${comparison.baselineSamples} · " +
                            "Trefferquote ${comparison.candidateAccuracy?.let(::percent) ?: "n/a"} " +
                            "gegen ${comparison.baselineAccuracy?.let(::percent) ?: "n/a"} · " +
                            "Änderung $change · $targetStatus",
                    )
                }
            }
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
                                Text("1X2-Trefferquote: ${model.accuracy?.let(::percent) ?: "n/a"}")
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
    "test" -> "Chronologischer Holdout (Test)"
    else -> "Gesamter Zeitraum (deskriptiv)"
}

private fun Double.format(digits: Int): String = "%.${digits}f".format(this)

private fun percent(value: Double): String = "${(value * 100).format(1)}%"

private fun coveragePercent(part: Int, total: Int): String =
    if (total == 0) "—" else percent(part.toDouble() / total)

private fun missingTargetSummary(report: LocalSyncReport): String =
    report.missingTargetCompetitions.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = " · Im API-Katalog nicht geliefert: ", separator = ", ")
        .orEmpty()

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
