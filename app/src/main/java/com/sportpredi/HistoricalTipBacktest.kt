package com.sportpredi

import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal data class HistoricalTipBacktestEntry(
    val fixtureId: Long,
    val matchDate: String,
    val competitionCode: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val market: String,
    val outcome: String,
    val decimalOdds: Double,
    val predictedWinProbability: Double,
    val expectedValue: Double,
    val result: String,
    val profitUnits: Double,
)

internal data class HistoricalTipBacktestReport(
    val completedFixtures: Int,
    val fixturesWithPrematchOdds: Int,
    val eligibleFixtures: Int,
    val recommendations: List<HistoricalTipBacktestEntry>,
    val expandedPositiveEvRecommendations: List<HistoricalTipBacktestEntry>,
) {
    val wins: Int get() = recommendations.count { it.result == RESULT_WIN }
    val pushes: Int get() = recommendations.count { it.result == RESULT_PUSH }
    val losses: Int get() = recommendations.count { it.result == RESULT_LOSS }

    val hitRate: Double?
        get() = wins.toDoubleOrNullRatio(wins + losses)

    val returnOnInvestment: Double?
        get() = recommendations.sumOf { it.profitUnits }
            .toDoubleOrNullRatio(recommendations.size)

    val averageOdds: Double?
        get() = recommendations.map { it.decimalOdds }.averageOrNull()

    val averagePredictedWinProbability: Double?
        get() = recommendations
            .filter { it.result != RESULT_PUSH }
            .map { it.predictedWinProbability }
            .averageOrNull()

    val averageExpectedValue: Double?
        get() = recommendations.map { it.expectedValue }.averageOrNull()

    val brierScore: Double?
        get() = recommendations
            .filter { it.result != RESULT_PUSH }
            .map { (it.predictedWinProbability - if (it.result == RESULT_WIN) 1.0 else 0.0).let { error ->
                error * error
            } }
            .averageOrNull()

    val expandedWins: Int
        get() = expandedPositiveEvRecommendations.count { it.result == RESULT_WIN }

    val expandedPushes: Int
        get() = expandedPositiveEvRecommendations.count { it.result == RESULT_PUSH }

    val expandedLosses: Int
        get() = expandedPositiveEvRecommendations.count { it.result == RESULT_LOSS }

    val expandedHitRate: Double?
        get() = expandedWins.toDoubleOrNullRatio(expandedWins + expandedLosses)

    val expandedReturnOnInvestment: Double?
        get() = expandedPositiveEvRecommendations.sumOf { it.profitUnits }
            .toDoubleOrNullRatio(expandedPositiveEvRecommendations.size)

    val expandedAveragePredictedWinProbability: Double?
        get() = expandedPositiveEvRecommendations
            .filter { it.result != RESULT_PUSH }
            .map { it.predictedWinProbability }
            .averageOrNull()

    val expandedAverageExpectedValue: Double?
        get() = expandedPositiveEvRecommendations.map { it.expectedValue }.averageOrNull()

    val expandedBrierScore: Double?
        get() = expandedPositiveEvRecommendations
            .filter { it.result != RESULT_PUSH }
            .map { (it.predictedWinProbability - if (it.result == RESULT_WIN) 1.0 else 0.0).let { error ->
                error * error
            } }
            .averageOrNull()
}

internal class HistoricalTipBacktestEngine(private val dao: SportPrediDao) {
    suspend fun run(): HistoricalTipBacktestReport {
        val fixtures = dao.completedFixtures()
        val oddsCaches = dao.allApiFootballOdds()
        val oddsByFixtureId = oddsCaches.associateBy { it.providerFixtureId }
        val historicalOddsFixtureIds = fixtures.asSequence()
            .filter { it.fullTimeResult && !it.postponedOrCancelled }
            .filter { fixture ->
                val kickoff = fixtureKickoff(fixture) ?: return@filter false
                val oddsCache = oddsByFixtureId[fixture.providerFixtureId] ?: return@filter false
                val fetchedAt = parseInstant(oddsCache.fetchedAt) ?: return@filter false
                fetchedAt.isBefore(kickoff)
            }
            .map { it.providerFixtureId }
            .toList()
        val predictions = historicalOddsFixtureIds
            .chunked(MAX_FIXTURE_IDS_PER_QUERY)
            .flatMap { dao.predictionsForFixtures(it) }
        return evaluateHistoricalApiFootballTips(
            fixtures = fixtures,
            predictions = predictions,
            oddsCaches = oddsCaches,
            teamNames = dao.allTeams().associate { it.providerTeamId to it.name },
        )
    }

    private companion object {
        const val MAX_FIXTURE_IDS_PER_QUERY = 400
    }
}

internal fun evaluateHistoricalApiFootballTips(
    fixtures: List<FixtureEntity>,
    predictions: List<PredictionSnapshotEntity>,
    oddsCaches: List<ApiFootballOddsCacheEntity>,
    teamNames: Map<Int, String> = emptyMap(),
): HistoricalTipBacktestReport {
    val completedFixtures = fixtures.filter {
        it.fullTimeResult &&
            !it.postponedOrCancelled &&
            it.homeScore != null &&
            it.awayScore != null
    }
    val predictionsByFixtureId = predictions.groupBy { it.providerFixtureId }
    val oddsByFixtureId = oddsCaches.associateBy { it.providerFixtureId }
    val evaluatedTips = mutableListOf<HistoricalTipBacktestEntry>()
    val expandedPositiveEvTips = mutableListOf<HistoricalTipBacktestEntry>()
    var fixturesWithPrematchOdds = 0
    var eligibleFixtures = 0

    completedFixtures.forEach { fixture ->
        val kickoff = fixtureKickoff(fixture) ?: return@forEach
        val prediction = predictionsByFixtureId[fixture.providerFixtureId]
            .orEmpty()
            .mapNotNull { item ->
                parseInstant(item.createdAt)?.let { instant -> item to instant }
            }
            .filter { (_, instant) -> instant.isBefore(kickoff) }
            .maxByOrNull { (_, instant) -> instant }
            ?.first

        val oddsCache = oddsByFixtureId[fixture.providerFixtureId]
        val fetchedAt = oddsCache?.fetchedAt?.let(::parseInstant)
        val quotes = if (fetchedAt != null && fetchedAt.isBefore(kickoff)) {
            parseApiFootballOddsQuotes(oddsCache.quotesJson)
                .orEmpty()
                .filter { it.fixtureId == fixture.providerFixtureId }
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (quotes != null) fixturesWithPrematchOdds += 1
        if (prediction == null || quotes == null) return@forEach
        eligibleFixtures += 1
        val savedOddsFetchedAt = oddsCache?.fetchedAt ?: return@forEach

        val analysis = prediction.analysisJson?.let { json ->
            runCatching { Gson().fromJson(json, PredictionAnalysis::class.java) }.getOrNull()
        }
        val selectedTip = selectBestApiFootballValueTip(
            quotes = quotes,
            homeProbability = prediction.homeWinProbability,
            drawProbability = prediction.drawProbability,
            awayProbability = prediction.awayWinProbability,
            fetchedAt = savedOddsFetchedAt,
            analysis = analysis,
        )
        val broaderTip = selectBestApiFootballValueTip(
            quotes = quotes,
            homeProbability = prediction.homeWinProbability,
            drawProbability = prediction.drawProbability,
            awayProbability = prediction.awayWinProbability,
            fetchedAt = savedOddsFetchedAt,
            analysis = analysis,
            minimumSelectionProbability = 0.0,
        )

        fun toEntry(tip: ApiFootballValueTip): HistoricalTipBacktestEntry? {
            val result = evaluateHistoricalTipOutcome(
                tip = tip,
                homeScore = fixture.homeScore!!,
                awayScore = fixture.awayScore!!,
            ) ?: return null
            val conditionalWinProbability = if (tip.pushProbability < 1.0) {
                (tip.selectionProbability / (1.0 - tip.pushProbability)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val profit = when (result) {
                RESULT_WIN -> tip.decimalOdds - 1.0
                RESULT_PUSH -> 0.0
                else -> -1.0
            }
            return HistoricalTipBacktestEntry(
                fixtureId = fixture.providerFixtureId,
                matchDate = fixture.matchDate,
                competitionCode = fixture.competitionCode,
                homeTeam = fixture.homeTeamId?.let(teamNames::get) ?: "Heim ${fixture.homeTeamId ?: "?"}",
                awayTeam = fixture.awayTeamId?.let(teamNames::get) ?: "Auswärts ${fixture.awayTeamId ?: "?"}",
                homeScore = fixture.homeScore,
                awayScore = fixture.awayScore,
                market = tip.market,
                outcome = tip.outcome,
                decimalOdds = tip.decimalOdds,
                predictedWinProbability = conditionalWinProbability,
                expectedValue = tip.expectedValue,
                result = result,
                profitUnits = profit,
            )
        }

        selectedTip?.let { toEntry(it)?.let(evaluatedTips::add) }
        broaderTip?.let { toEntry(it)?.let(expandedPositiveEvTips::add) }
    }

    return HistoricalTipBacktestReport(
        completedFixtures = completedFixtures.size,
        fixturesWithPrematchOdds = fixturesWithPrematchOdds,
        eligibleFixtures = eligibleFixtures,
        recommendations = evaluatedTips.sortedByDescending { it.matchDate },
        expandedPositiveEvRecommendations = expandedPositiveEvTips.sortedByDescending { it.matchDate },
    )
}

private fun evaluateHistoricalTipOutcome(
    tip: ApiFootballValueTip,
    homeScore: Int,
    awayScore: Int,
): String? {
    val homeWon = homeScore > awayScore
    val awayWon = homeScore < awayScore
    val draw = homeScore == awayScore
    return when (tip.market) {
        "1X2" -> when (tip.outcome) {
            "HOME" -> if (homeWon) RESULT_WIN else RESULT_LOSS
            "DRAW" -> if (draw) RESULT_WIN else RESULT_LOSS
            "AWAY" -> if (awayWon) RESULT_WIN else RESULT_LOSS
            else -> null
        }
        "DOUBLE_CHANCE" -> {
            val wins = when (tip.outcome) {
                "HOME_DRAW" -> homeWon || draw
                "HOME_AWAY" -> homeWon || awayWon
                "DRAW_AWAY" -> draw || awayWon
                else -> return null
            }
            if (wins) RESULT_WIN else RESULT_LOSS
        }
        "TOTAL_GOALS" -> {
            val (side, lineText) = tip.outcome.split("_", limit = 2).let {
                it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()
            }
            val line = lineText.toDoubleOrNull() ?: return null
            val totalGoals = (homeScore + awayScore).toDouble()
            when {
                totalGoals == line -> RESULT_PUSH
                side == "OVER" -> if (totalGoals > line) RESULT_WIN else RESULT_LOSS
                side == "UNDER" -> if (totalGoals < line) RESULT_WIN else RESULT_LOSS
                else -> null
            }
        }
        "BOTH_TEAMS_SCORE" -> {
            val bothScored = homeScore > 0 && awayScore > 0
            when (tip.outcome) {
                "YES" -> if (bothScored) RESULT_WIN else RESULT_LOSS
                "NO" -> if (!bothScored) RESULT_WIN else RESULT_LOSS
                else -> null
            }
        }
        "DRAW_NO_BET" -> when {
            draw -> RESULT_PUSH
            tip.outcome == "HOME" -> if (homeWon) RESULT_WIN else RESULT_LOSS
            tip.outcome == "AWAY" -> if (awayWon) RESULT_WIN else RESULT_LOSS
            else -> null
        }
        else -> null
    }
}

private fun fixtureKickoff(fixture: FixtureEntity): Instant? =
    fixture.kickoffAt?.let(::parseInstant)
        ?: runCatching {
            LocalDate.parse(fixture.matchDate)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
        }.getOrNull()

private fun parseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()

private fun List<Double>.averageOrNull(): Double? =
    takeIf { it.isNotEmpty() }?.average()

private fun Int.toDoubleOrNullRatio(denominator: Int): Double? =
    if (denominator == 0) null else toDouble() / denominator

private fun Double.toDoubleOrNullRatio(denominator: Int): Double? =
    if (denominator == 0) null else this / denominator

private const val RESULT_WIN = "WIN"
private const val RESULT_PUSH = "PUSH"
private const val RESULT_LOSS = "LOSS"
