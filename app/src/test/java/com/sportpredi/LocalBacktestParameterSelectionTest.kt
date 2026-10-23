package com.sportpredi

import org.junit.Assert.assertEquals
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
}
