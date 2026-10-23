package com.sportpredi

import com.google.gson.Gson
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

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
    val expectedValue: Double,
    val bookmaker: String,
    val fetchedAt: String,
)

internal fun apiFootballOutcomeLabel(outcome: String): String = when (outcome) {
    "HOME" -> "Heimsieg"
    "DRAW" -> "Remis"
    "AWAY" -> "Auswärtssieg"
    else -> outcome
}

internal object ApiFootballOddsCacheStatus {
    const val AVAILABLE = "AVAILABLE"
    const val NO_ODDS = "NO_ODDS"
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
            if (!isSupported1X2Market(bet.name)) return@flatMap emptyList()
            bet.values.orEmpty().mapNotNull { value ->
                val outcome = normalize1X2Outcome(value.value) ?: return@mapNotNull null
                val odds = value.odd?.trim()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 1.0 }
                    ?: return@mapNotNull null
                ApiFootballOddsQuote(
                    fixtureId = requestedFixtureId,
                    market = "1X2",
                    outcome = outcome,
                    decimalOdds = odds,
                    bookmaker = bookmakerName,
                )
            }
        }
    }
    return NormalizedApiFootballOdds(fixtureFound = true, quotes = quotes)
}

private fun isSupported1X2Market(name: String?): Boolean =
    name.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]"), "") in
        setOf("matchwinner", "1x2")

private fun normalize1X2Outcome(value: String?): String? =
    when (value.orEmpty().trim().lowercase()) {
        "home", "1" -> "HOME"
        "draw", "x" -> "DRAW"
        "away", "2" -> "AWAY"
        else -> null
    }

internal fun rankApiFootballValueTips(
    quotes: List<ApiFootballOddsQuote>,
    homeProbability: Double,
    drawProbability: Double,
    awayProbability: Double,
    fetchedAt: String,
): List<ApiFootballValueTip> {
    val probabilities = mapOf(
        "HOME" to homeProbability,
        "DRAW" to drawProbability,
        "AWAY" to awayProbability,
    )
    val bestPriceByOutcome = quotes.asSequence()
        .filter { it.market == "1X2" && it.outcome in probabilities }
        .filter { it.fixtureId > 0 && it.decimalOdds.isFinite() && it.decimalOdds > 1.0 }
        .filter { it.bookmaker.isNotBlank() }
        .groupBy { it.outcome }
        .mapValues { (_, candidates) ->
            candidates.sortedWith(
                compareByDescending<ApiFootballOddsQuote> { it.decimalOdds }
                    .thenBy { it.bookmaker.lowercase() },
            ).first()
        }
    return bestPriceByOutcome.values.mapNotNull { quote ->
        val probability = probabilities[quote.outcome] ?: return@mapNotNull null
        if (!probability.isFinite() || probability !in 0.0..1.0) return@mapNotNull null
        val expectedValue = probability * quote.decimalOdds - 1.0
        if (!expectedValue.isFinite() || expectedValue <= 0.0) return@mapNotNull null
        ApiFootballValueTip(
            market = quote.market,
            outcome = quote.outcome,
            decimalOdds = quote.decimalOdds,
            modelProbability = probability,
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
