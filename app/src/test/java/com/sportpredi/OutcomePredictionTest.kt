package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomePredictionTest {
    @Test
    fun alwaysReturnsTheMostLikelyOutcomeWhenItsProbabilityIsBelowNinetyPercent() {
        val prediction = mostLikely1X2Prediction(doubleArrayOf(0.57, 0.28, 0.15))

        assertNotNull(prediction)
        assertEquals("HOME", prediction!!.selection)
        assertEquals(0.57, prediction.probability, 1e-12)
        assertTrue(prediction.probability < MODEL_ACCURACY_GOAL)
    }

    @Test
    fun invalidProbabilitiesAreNotPresentedAsPredictions() {
        assertEquals(null, mostLikely1X2Prediction(doubleArrayOf(0.4, Double.NaN, 0.6)))
        assertEquals(null, mostLikely1X2Prediction(doubleArrayOf(0.7, 0.3)))
    }
}
