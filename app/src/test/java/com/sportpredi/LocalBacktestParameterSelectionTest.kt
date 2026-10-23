package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBacktestParameterSelectionTest {
    @Test
    fun selectsTheHighestValidationAccuracyAndUsesLogLossToBreakTies() {
        val lowerAccuracy = LocalForecastParameters(halfLifeDays = 180.0, dixonColesRho = -0.05)
        val higherAccuracy = LocalForecastParameters(halfLifeDays = 730.0, dixonColesRho = -0.10)
        val sameAccuracyLowerLoss = LocalForecastParameters(halfLifeDays = 365.0, dixonColesRho = 0.0)

        val selected = selectForecastParameters(
            listOf(
                ValidatedForecastParameters(lowerAccuracy, 200, 0.45, 0.98),
                ValidatedForecastParameters(higherAccuracy, 200, 0.48, 1.02),
                ValidatedForecastParameters(sameAccuracyLowerLoss, 200, 0.48, 0.95),
            ),
        )

        assertEquals(sameAccuracyLowerLoss, selected)
    }

    @Test
    fun usesDefaultParametersWhenValidationSampleIsTooSmall() {
        val selected = selectForecastParameters(
            listOf(
                ValidatedForecastParameters(
                    parameters = LocalForecastParameters(halfLifeDays = 180.0, dixonColesRho = -0.10),
                    sampleSize = 99,
                    accuracy = 1.0,
                    logLoss = 0.0,
                ),
            ),
        )

        assertEquals(LocalForecastParameters(), selected)
    }

    @Test
    fun reportsNegativeHoldoutImprovementInsteadOfFilteringOutTheCandidate() {
        val comparisons = compareHoldoutAccuracies(
            listOf(
                metric(OLD_BACKTEST_BASELINE_MODEL_VERSION, 100, 0.62),
                metric(LocalForecastingState.MODEL_VERSION, 100, 0.58),
                metric(LocalForecastingState.MODEL_VERSION, 200, 0.99, scope = "validation"),
            ),
        )

        assertEquals(1, comparisons.size)
        assertEquals(-0.04, comparisons.single().improvement!!, 1e-12)
        assertFalse(comparisons.single().meetsAccuracyGoal)
    }

    @Test
    fun ninetyPercentBenchmarkIsReportedOnlyFromMeasuredHoldout() {
        val comparison = compareHoldoutAccuracies(
            listOf(
                metric(OLD_BACKTEST_BASELINE_MODEL_VERSION, 40, 0.87),
                metric(LocalForecastingState.MODEL_VERSION, 40, 0.925),
                metric(LocalForecastingState.MODEL_VERSION, 200, 1.0, scope = "validation"),
            ),
        ).single()

        assertEquals(0.925, comparison.candidateAccuracy!!, 1e-12)
        assertEquals(0.055, comparison.improvement!!, 1e-12)
        assertTrue(comparison.meetsAccuracyGoal)
    }

    private fun metric(modelVersion: String, sampleSize: Int, accuracy: Double, scope: String = "test") =
        LocalModelMetric(
            modelVersion = modelVersion,
            sampleSize = sampleSize,
            logLoss = null,
            brierScore = null,
            accuracy = accuracy,
            meanAbsoluteGoalError = null,
            scope = scope,
        )
}
