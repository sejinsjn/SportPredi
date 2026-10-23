package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafestModelTipTest {
    @Test
    fun selectsTheHighestProbabilityMarketAfterExcludingTrivialMarkets() {
        val scores = listOf(
            ScorelineProbability(0, 0, 0.05),
            ScorelineProbability(1, 0, 0.15),
            ScorelineProbability(0, 1, 0.1),
            ScorelineProbability(1, 1, 0.15),
            ScorelineProbability(2, 0, 0.15),
            ScorelineProbability(0, 2, 0.1),
            ScorelineProbability(2, 1, 0.15),
            ScorelineProbability(1, 2, 0.1),
            ScorelineProbability(4, 0, 0.05),
        )

        val tip = highestProbabilityEligibleTip(scores)

        assertEquals("Über 1,5 Tore", tip?.market)
        assertEquals(0.7, tip?.probability ?: 0.0, 1e-12)
    }

    @Test
    fun rejectsInvalidProbabilityDistributions() {
        assertNull(highestProbabilityEligibleTip(emptyList()))
        assertNull(highestProbabilityEligibleTip(listOf(ScorelineProbability(0, 0, Double.NaN))))
        assertNull(highestProbabilityEligibleTip(listOf(ScorelineProbability(0, 0, -0.2))))
    }

    @Test
    fun selectsOnlyQuotedMarketsWithPositiveExpectedValueAndMinimumOdds() {
        val scores = listOf(
            ScorelineProbability(0, 0, 0.2),
            ScorelineProbability(1, 0, 0.4),
            ScorelineProbability(0, 1, 0.1),
            ScorelineProbability(1, 1, 0.3),
        )
        val odds = listOf(
            BettingOddsSelection("HOME", 1.49, "Low-odds book"),
            BettingOddsSelection("DRAW", 4.0, "Value book"),
            BettingOddsSelection("OVER_2.5", 5.0, "Unpriced market"),
        )

        val tip = highestProbabilityPositiveValueTip(scores, odds)

        assertEquals("Unentschieden", tip?.market)
        assertEquals(0.5, tip?.probability ?: 0.0, 1e-12)
        assertEquals(4.0, tip?.decimalOdds ?: 0.0, 1e-12)
        assertEquals(1.0, tip?.expectedValue ?: 0.0, 1e-12)
        assertEquals("Value book", tip?.bookmaker)
    }

    @Test
    fun returnsNoValueTipWhenNoQuotedMarketHasPositiveExpectedValue() {
        val scores = listOf(
            ScorelineProbability(0, 0, 0.2),
            ScorelineProbability(1, 0, 0.4),
            ScorelineProbability(0, 1, 0.1),
            ScorelineProbability(1, 1, 0.3),
        )

        assertNull(
            highestProbabilityPositiveValueTip(
                scores,
                listOf(BettingOddsSelection("HOME", 2.0, "Book")),
            ),
        )
    }
}
