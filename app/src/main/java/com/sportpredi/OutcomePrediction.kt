package com.sportpredi

data class OutcomePrediction(
    val selection: String,
    val probability: Double,
)

internal fun mostLikely1X2Prediction(probabilities: DoubleArray): OutcomePrediction? {
    if (probabilities.size != 3 || probabilities.any { !it.isFinite() || it !in 0.0..1.0 }) return null
    val index = probabilities.indices.maxByOrNull { probabilities[it] } ?: return null
    return OutcomePrediction(
        selection = listOf("HOME", "DRAW", "AWAY")[index],
        probability = probabilities[index],
    )
}

internal fun outcomeDisplayLabel(selection: String): String =
    when (selection) {
        "HOME" -> "1 · Heimsieg"
        "DRAW" -> "X · Remis"
        "AWAY" -> "2 · Auswärtssieg"
        else -> selection
    }
