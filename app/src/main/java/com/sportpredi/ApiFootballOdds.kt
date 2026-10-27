package com.sportpredi

import com.google.gson.Gson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ApiFootballOddsQuote(
    val fixtureId: Long,
    val market: String,
    val outcome: String,
    val decimalOdds: Double,
    val bookmaker: String,
)

internal data class NormalizedApiFootballOdds(
    val fixtureFound: Boolean,
    val quotes: List<ApiFootballOddsQuote>,
)

internal data class ApiFootballValueTip(
    val market: String,
    val outcome: String,
    val decimalOdds: Double,
    val modelProbability: Double,
    val pushProbability: Double,
    val expectedValue: Double,
    val bookmaker: String,
    val fetchedAt: String,
)

private data class ModelMarketProbability(
    val win: Double,
    val loss: Double,
    val push: Double = 0.0,
)

private data class MarketOutcome(val market: String, val outcome: String)

internal object ApiFootballTipSelectionRules {
    const val MIN_MODEL_PROBABILITY = 0.55
    const val MIN_DECIMAL_ODDS = 1.30
    const val MAX_DECIMAL_ODDS = 2.50
    const val MIN_EXPECTED_VALUE = 0.03
}

internal fun apiFootballMarketLabel(market: String, outcome: String): String =
    when (market) {
        "1X2" -> when (outcome) {
            "HOME" -> "1 · Heimsieg"
            "DRAW" -> "X · Remis"
            "AWAY" -> "2 · Auswärtssieg"
            else -> outcome
        }
        "DOUBLE_CHANCE" -> when (outcome) {
            "HOME_DRAW" -> "Doppelte Chance · 1X"
            "HOME_AWAY" -> "Doppelte Chance · 12"
            "DRAW_AWAY" -> "Doppelte Chance · X2"
            else -> outcome
        }
        "TOTAL_GOALS" -> {
            val (side, line) = outcome.split("_", limit = 2).let {
                it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()
            }
            val sideLabel = if (side == "OVER") "Über" else "Unter"
            "Tore · $sideLabel ${line.replace('.', ',')}"
        }
        "BOTH_TEAMS_SCORE" -> when (outcome) {
            "YES" -> "Beide Teams treffen · Ja"
            "NO" -> "Beide Teams treffen · Nein"
            else -> outcome
        }
        "DRAW_NO_BET" -> when (outcome) {
            "HOME" -> "Ohne Remis · Heimsieg"
            "AWAY" -> "Ohne Remis · Auswärtssieg"
            else -> outcome
        }
        else -> "$market · $outcome"
    }

internal object ApiFootballOddsCacheStatus {
    const val AVAILABLE = "AVAILABLE"
    const val NO_ODDS = "NO_ODDS"
    const val NO_SUPPORTED_QUOTES = "NO_SUPPORTED_QUOTES"
    const val NO_1X2_QUOTES = "NO_1X2_QUOTES"
}

internal fun normalizeApiFootballOdds(
    response: List<ApiFootballOddsResponseDto>,
    requestedFixtureId: Long,
): NormalizedApiFootballOdds {
    val fixtureResponse = response.firstOrNull { it.fixture?.id?.toLong() == requestedFixtureId }
        ?: return NormalizedApiFootballOdds(fixtureFound = false, quotes = emptyList())
    val quotes = fixtureResponse.bookmakers.orEmpty().flatMap { bookmaker ->
        val bookmakerName = bookmaker.name?.trim()?.takeIf(String::isNotEmpty) ?: return@flatMap emptyList()
        bookmaker.bets.orEmpty().flatMap { bet ->
            val market = normalizeOddsMarket(bet.name) ?: return@flatMap emptyList()
            bet.values.orEmpty().mapNotNull { value ->
                val outcome = normalizeOddsOutcome(market, value.value) ?: return@mapNotNull null
                val odds = value.odd?.trim()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 1.0 }
                    ?: return@mapNotNull null
                ApiFootballOddsQuote(
                    fixtureId = requestedFixtureId,
                    market = market,
                    outcome = outcome,
                    decimalOdds = odds,
                    bookmaker = bookmakerName,
                )
            }
        }
    }
    return NormalizedApiFootballOdds(fixtureFound = true, quotes = quotes)
}

private fun normalizeOddsMarket(name: String?): String? =
    when (name.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]"), "")) {
        "matchwinner", "1x2" -> "1X2"
        "doublechance" -> "DOUBLE_CHANCE"
        "goalsoverunder", "overunder", "overundergoals", "totalgoals" -> "TOTAL_GOALS"
        "bothteamstoscore", "bothteamsscore", "bothteamscore", "btts" -> "BOTH_TEAMS_SCORE"
        "drawnobet" -> "DRAW_NO_BET"
        else -> null
    }

private fun normalizeOddsOutcome(market: String, value: String?): String? {
    val normalized = value.orEmpty().trim().lowercase()
    return when (market) {
        "1X2" -> when (normalized) {
            "home", "1" -> "HOME"
            "draw", "x" -> "DRAW"
            "away", "2" -> "AWAY"
            else -> null
        }
        "DOUBLE_CHANCE" -> when (normalized.replace(Regex("[^a-z0-9]"), "")) {
            "homedraw", "homeordraw", "1x" -> "HOME_DRAW"
            "homeaway", "homeoraway", "12" -> "HOME_AWAY"
            "drawaway", "draworaway", "x2" -> "DRAW_AWAY"
            else -> null
        }
        "TOTAL_GOALS" -> {
            val match = Regex("^(over|under)\\s+(\\d+(?:[.,]\\d+)?)$").matchEntire(normalized)
                ?: return null
            val line = match.groupValues[2].replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return null
            if (abs(line * 2.0 - (line * 2.0).roundToInt()) > 1e-9) return null
            "${match.groupValues[1].uppercase()}_$line"
        }
        "BOTH_TEAMS_SCORE" -> when (normalized) {
            "yes" -> "YES"
            "no" -> "NO"
            else -> null
        }
        "DRAW_NO_BET" -> when (normalized) {
            "home", "1" -> "HOME"
            "away", "2" -> "AWAY"
            else -> null
        }
        else -> null
    }
}

internal fun rankApiFootballValueTips(
    quotes: List<ApiFootballOddsQuote>,
    homeProbability: Double,
    drawProbability: Double,
    awayProbability: Double,
    fetchedAt: String,
    analysis: PredictionAnalysis? = null,
): List<ApiFootballValueTip> {
    val bestPriceByOutcome = quotes.asSequence()
        .filter { it.fixtureId > 0 && it.decimalOdds.isFinite() && it.decimalOdds > 1.0 }
        .filter { it.bookmaker.isNotBlank() }
        .groupBy { MarketOutcome(it.market, it.outcome) }
        .mapValues { (_, candidates) ->
            candidates.sortedWith(
                compareByDescending<ApiFootballOddsQuote> { it.decimalOdds }
                    .thenBy { it.bookmaker.lowercase() },
            ).first()
        }
    return bestPriceByOutcome.values.mapNotNull { quote ->
        val probability = modelProbabilityForQuote(
            quote = quote,
            homeProbability = homeProbability,
            drawProbability = drawProbability,
            awayProbability = awayProbability,
            analysis = analysis,
        ) ?: return@mapNotNull null
        if (
            !probability.win.isFinite() || !probability.loss.isFinite() ||
            !probability.push.isFinite() ||
            probability.win !in 0.0..1.0 || probability.loss !in 0.0..1.0 ||
            probability.push !in 0.0..1.0 ||
            probability.win + probability.loss + probability.push > 1.000001
        ) return@mapNotNull null
        val expectedValue = probability.win * (quote.decimalOdds - 1.0) - probability.loss
        if (!expectedValue.isFinite() || expectedValue <= 0.0) return@mapNotNull null
        val nonPushProbability = probability.win + probability.loss
        if (nonPushProbability <= 0.0) return@mapNotNull null
        ApiFootballValueTip(
            market = quote.market,
            outcome = quote.outcome,
            decimalOdds = quote.decimalOdds,
            modelProbability = probability.win,
            pushProbability = probability.push,
            expectedValue = expectedValue,
            bookmaker = quote.bookmaker,
            fetchedAt = fetchedAt,
        )
    }.sortedWith(
        compareByDescending<ApiFootballValueTip> { it.expectedValue }
            .thenByDescending { it.decimalOdds }
            .thenBy { it.outcome },
    )
}

internal fun parseApiFootballOddsQuotes(json: String?): List<ApiFootballOddsQuote>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        Gson().fromJson(json, Array<ApiFootballOddsQuote>::class.java)?.toList()
    }.getOrNull()
}

internal fun apiFootballOddsCacheIsFresh(
    fetchedAt: String,
    now: Instant,
    ttl: Duration,
): Boolean {
    val fetched = runCatching { Instant.parse(fetchedAt) }.getOrNull() ?: return false
    return !fetched.isAfter(now) && fetched.plus(ttl).isAfter(now)
}

internal fun apiFootballOddsEligibleFixtures(
    fixtures: List<FixtureEntity>,
    today: LocalDate,
    upcomingDays: Long,
): List<FixtureEntity> =
    fixtures.asSequence()
        .filter {
            !it.fullTimeResult &&
                !it.postponedOrCancelled &&
                it.status in setOf("SCHEDULED", "TIMED") &&
                it.matchDate >= today.toString() &&
                it.matchDate <= today.plusDays(upcomingDays).toString()
        }
        .distinctBy { it.providerFixtureId }
        .sortedWith(compareBy<FixtureEntity> { it.matchDate }.thenBy { it.kickoffAt.orEmpty() }.thenBy { it.providerFixtureId })
        .toList()

internal fun selectApiFootballOddsCandidates(
    fixtures: List<FixtureEntity>,
    cachedByFixtureId: Map<Long, ApiFootballOddsCacheEntity>,
    today: LocalDate,
    upcomingDays: Long,
    now: Instant,
    cacheTtl: Duration,
    requestLimit: Int,
): List<FixtureEntity> {
    if (requestLimit <= 0) return emptyList()
    return apiFootballOddsEligibleFixtures(fixtures, today, upcomingDays)
        .asSequence()
        .filter { fixture ->
            val cached = cachedByFixtureId[fixture.providerFixtureId]
            cached == null || !apiFootballOddsCacheIsFresh(cached.fetchedAt, now, cacheTtl)
        }
        .take(requestLimit)
        .toList()
}

private fun modelProbabilityForQuote(
    quote: ApiFootballOddsQuote,
    homeProbability: Double,
    drawProbability: Double,
    awayProbability: Double,
    analysis: PredictionAnalysis?,
): ModelMarketProbability? {
    if (
        listOf(homeProbability, drawProbability, awayProbability)
            .any { !it.isFinite() || it !in 0.0..1.0 }
    ) return null
    val oneXTwo = mapOf(
        "HOME" to homeProbability,
        "DRAW" to drawProbability,
        "AWAY" to awayProbability,
    )
    return when (quote.market) {
        "1X2" -> oneXTwo[quote.outcome]?.let { ModelMarketProbability(it, 1.0 - it) }
        "DOUBLE_CHANCE" -> {
            val winProbability = when (quote.outcome) {
                "HOME_DRAW" -> homeProbability + drawProbability
                "HOME_AWAY" -> homeProbability + awayProbability
                "DRAW_AWAY" -> drawProbability + awayProbability
                else -> return null
            }.coerceIn(0.0, 1.0)
            ModelMarketProbability(winProbability, 1.0 - winProbability)
        }
        "TOTAL_GOALS" -> {
            val (side, lineText) = quote.outcome.split("_", limit = 2).let {
                it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()
            }
            val line = lineText.toDoubleOrNull() ?: return null
            val distribution = analysis?.scoreDistribution ?: return null
            val overProbability = distribution
                .filter { it.homeGoals + it.awayGoals > line }
                .sumOf { it.probability }
                .coerceIn(0.0, 1.0)
            val winProbability = when (side) {
                "OVER" -> overProbability
                "UNDER" -> 1.0 - overProbability
                else -> return null
            }
            ModelMarketProbability(winProbability, 1.0 - winProbability)
        }
        "BOTH_TEAMS_SCORE" -> {
            val yesProbability = analysis?.bothTeamsToScoreProbability ?: return null
            val winProbability = when (quote.outcome) {
                "YES" -> yesProbability
                "NO" -> 1.0 - yesProbability
                else -> return null
            }.coerceIn(0.0, 1.0)
            ModelMarketProbability(winProbability, 1.0 - winProbability)
        }
        "DRAW_NO_BET" -> when (quote.outcome) {
            "HOME" -> ModelMarketProbability(homeProbability, awayProbability, drawProbability)
            "AWAY" -> ModelMarketProbability(awayProbability, homeProbability, drawProbability)
            else -> null
        }
        else -> null
    }
}

internal fun selectBestApiFootballValueTip(
    quotes: List<ApiFootballOddsQuote>,
    homeProbability: Double,
    drawProbability: Double,
    awayProbability: Double,
    fetchedAt: String,
    analysis: PredictionAnalysis? = null,
): ApiFootballValueTip? =
    rankApiFootballValueTips(
        quotes = quotes,
        homeProbability = homeProbability,
        drawProbability = drawProbability,
        awayProbability = awayProbability,
        fetchedAt = fetchedAt,
        analysis = analysis,
    ).firstOrNull { tip ->
        tip.modelProbability >= ApiFootballTipSelectionRules.MIN_MODEL_PROBABILITY &&
            tip.decimalOdds in ApiFootballTipSelectionRules.MIN_DECIMAL_ODDS..ApiFootballTipSelectionRules.MAX_DECIMAL_ODDS &&
            tip.expectedValue >= ApiFootballTipSelectionRules.MIN_EXPECTED_VALUE
    }

internal fun safeApiFootballOddsRequestSlots(
    maxRequestsPerSync: Int,
    requestsUsed: Int,
    dailyRemainingAfterReserve: Int,
    minuteRemainingAfterReserve: Int,
    maxOddsRequestsPerSync: Int,
): Int = minOf(
    (maxRequestsPerSync - requestsUsed).coerceAtLeast(0),
    dailyRemainingAfterReserve.coerceAtLeast(0),
    minuteRemainingAfterReserve.coerceAtLeast(0),
    maxOddsRequestsPerSync.coerceAtLeast(0),
)
