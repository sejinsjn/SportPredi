package com.sportpredi

data class ModelTipRecommendation(
    val market: String,
    val probability: Double,
)

data class ValueTipRecommendation(
    val market: String,
    val probability: Double,
    val decimalOdds: Double,
    val bookmaker: String,
    val expectedValue: Double,
)

internal fun highestProbabilityEligibleTip(
    scores: List<ScorelineProbability>,
): ModelTipRecommendation? {
    return eligibleTipProbabilities(scores)?.maxByOrNull { it.probability }
}

internal fun highestProbabilityPositiveValueTip(
    scores: List<ScorelineProbability>,
    odds: List<BettingOddsSelection>,
): ValueTipRecommendation? {
    val probabilities = eligibleTipProbabilities(scores)
        ?.associateBy { it.market }
        ?: return null

    return odds.mapNotNull { quote ->
        val market = marketForOddsSelection(quote.selection) ?: return@mapNotNull null
        val probability = probabilities[market]?.probability ?: return@mapNotNull null
        if (!quote.decimalOdds.isFinite() || quote.decimalOdds < MIN_VALUE_TIP_ODDS) {
            return@mapNotNull null
        }
        val expectedValue = probability * quote.decimalOdds - 1.0
        if (expectedValue <= 0.0) return@mapNotNull null
        ValueTipRecommendation(
            market = market,
            probability = probability,
            decimalOdds = quote.decimalOdds,
            bookmaker = quote.bookmaker,
            expectedValue = expectedValue,
        )
    }.maxWithOrNull(compareBy<ValueTipRecommendation> { it.probability }.thenBy { it.expectedValue })
}

private fun marketForOddsSelection(selection: String): String? =
    when (selection) {
        "HOME" -> "Heimsieg"
        "DRAW" -> "Unentschieden"
        "AWAY" -> "Auswärtssieg"
        "OVER_2.5" -> "Über 2,5 Tore"
        "UNDER_2.5" -> "Unter 2,5 Tore"
        else -> null
    }

private const val MIN_VALUE_TIP_ODDS = 1.5

private fun eligibleTipProbabilities(
    scores: List<ScorelineProbability>,
): List<ModelTipRecommendation>? {
    if (scores.isEmpty() || scores.any {
            it.homeGoals < 0 || it.awayGoals < 0 ||
                !it.probability.isFinite() || it.probability < 0.0
        }
    ) {
        return null
    }
    val total = scores.sumOf { it.probability }
    if (!total.isFinite() || total <= 0.0) return null

    val normalized = scores.map { it.copy(probability = it.probability / total) }
    val homeWin = normalized.filter { it.homeGoals > it.awayGoals }.sumOf { it.probability }
    val draw = normalized.filter { it.homeGoals == it.awayGoals }.sumOf { it.probability }
    val awayWin = normalized.filter { it.homeGoals < it.awayGoals }.sumOf { it.probability }
    val bothScore = normalized
        .filter { it.homeGoals > 0 && it.awayGoals > 0 }
        .sumOf { it.probability }
    val candidates = mutableListOf(
        ModelTipRecommendation("Heimsieg", homeWin),
        ModelTipRecommendation("Unentschieden", draw),
        ModelTipRecommendation("Auswärtssieg", awayWin),
        ModelTipRecommendation("Beide Teams treffen: Ja", bothScore),
        ModelTipRecommendation("Beide Teams treffen: Nein", 1.0 - bothScore),
    )
    candidates.indices.forEach { index ->
        candidates[index] = candidates[index].copy(
            probability = candidates[index].probability.coerceIn(0.0, 1.0),
        )
    }

    listOf(1, 2).forEach { line ->
        val over = normalized
            .filter { it.homeGoals + it.awayGoals > line }
            .sumOf { it.probability }
        val lineLabel = "${line},5 Tore"
        candidates += ModelTipRecommendation("Über $lineLabel", over.coerceIn(0.0, 1.0))
        candidates += ModelTipRecommendation("Unter $lineLabel", (1.0 - over).coerceIn(0.0, 1.0))
    }

    return candidates
}
