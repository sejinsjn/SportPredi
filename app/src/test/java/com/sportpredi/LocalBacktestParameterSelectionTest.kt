package com.sportpredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBacktestParameterSelectionTest {
    @Test
    fun selectsHighestValidationMarketAccuracyThenLogLossAndBrier() {
        val highestMarketAccuracy = LocalForecastParameters(halfLifeDays = 730.0, dixonColesRho = -0.10)
        val lowLogLoss = LocalForecastParameters(halfLifeDays = 180.0, dixonColesRho = -0.05)
        val sameMarketLowerLoss = LocalForecastParameters(halfLifeDays = 365.0, dixonColesRho = 0.0)
        val sameMarketSameLossLowerBrier = LocalForecastParameters(halfLifeDays = 365.0, dixonColesRho = -0.10)

        val selected = selectForecastParameters(
            listOf(
                ValidatedForecastParameters(
                    highestMarketAccuracy,
                    200,
                    0.95,
                    0.55,
                    marketPickSampleSize = 200,
                    marketPickAccuracy = 0.82,
                ),
                ValidatedForecastParameters(
                    lowLogLoss,
                    200,
                    0.80,
                    0.60,
                    marketPickSampleSize = 200,
                    marketPickAccuracy = 0.78,
                ),
                ValidatedForecastParameters(
                    sameMarketLowerLoss,
                    200,
                    0.79,
                    0.50,
                    marketPickSampleSize = 200,
                    marketPickAccuracy = 0.82,
                ),
                ValidatedForecastParameters(
                    sameMarketSameLossLowerBrier,
                    200,
                    0.79,
                    0.30,
                    marketPickSampleSize = 200,
                    marketPickAccuracy = 0.82,
                ),
            ),
        )

        assertEquals(sameMarketSameLossLowerBrier, selected)
    }

    @Test
    fun fallsBackToValidationLogLossWhenMarketSampleIsTooSmall() {
        val marketAccuracyOnly = LocalForecastParameters(halfLifeDays = 180.0, dixonColesRho = -0.10)
        val bestLogLoss = LocalForecastParameters(halfLifeDays = 365.0, dixonColesRho = 0.0)

        val selected = selectForecastParameters(
            listOf(
                ValidatedForecastParameters(
                    marketAccuracyOnly,
                    200,
                    0.95,
                    0.55,
                    marketPickSampleSize = 99,
                    marketPickAccuracy = 0.99,
                ),
                ValidatedForecastParameters(bestLogLoss, 200, 0.80, 0.50),
            ),
        )

        assertEquals(bestLogLoss, selected)
    }

    @Test
    fun usesDefaultParametersWhenValidationSampleIsTooSmall() {
        val selected = selectForecastParameters(
            listOf(
                ValidatedForecastParameters(
                    parameters = LocalForecastParameters(halfLifeDays = 180.0, dixonColesRho = -0.10),
                    sampleSize = 99,
                    logLoss = 0.0,
                    brierScore = 0.0,
                ),
            ),
        )

        assertEquals(LocalForecastParameters(), selected)
    }

    @Test
    fun selectsOutcomeModelByValidationLogLossWithoutUsingHoldoutMetrics() {
        val selected = selectValidatedForecastModel(
            listOf(
                metric(
                    ELO_MODEL_VERSION,
                    200,
                    0.60,
                    scope = "validation",
                    logLoss = 0.82,
                    brierScore = 0.55,
                ),
                metric(
                    OLD_BACKTEST_BASELINE_MODEL_VERSION,
                    200,
                    0.58,
                    scope = "validation",
                    logLoss = 0.78,
                    brierScore = 0.60,
                ),
                metric(
                    POISSON_MODEL_VERSION,
                    200,
                    0.70,
                    scope = "test",
                    logLoss = 0.10,
                    brierScore = 0.10,
                ),
            ),
        )

        assertEquals(OLD_BACKTEST_BASELINE_MODEL_VERSION, selected)
    }

    @Test
    fun usesBrierAsTieBreakerAndFallsBackWhenValidationIsTooSmall() {
        val tiedSelection = selectValidatedForecastModel(
            listOf(
                metric(
                    ELO_MODEL_VERSION,
                    200,
                    0.60,
                    scope = "validation",
                    logLoss = 0.80,
                    brierScore = 0.52,
                ),
                metric(
                    POISSON_MODEL_VERSION,
                    200,
                    0.65,
                    scope = "validation",
                    logLoss = 0.80,
                    brierScore = 0.49,
                ),
            ),
        )
        val fallback = selectValidatedForecastModel(
            listOf(
                metric(
                    ELO_MODEL_VERSION,
                    99,
                    0.60,
                    scope = "validation",
                    logLoss = 0.10,
                    brierScore = 0.10,
                ),
            ),
        )

        assertEquals(POISSON_MODEL_VERSION, tiedSelection)
        assertEquals(LocalForecastingState.MODEL_VERSION, fallback)
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

    @Test
    fun reportsHitRateAndFairOddsForConfidenceFilteredHoldoutPicks() {
        val metrics = confidenceFilteredHoldoutMetrics(
            samples = listOf(
                ConfidenceHoldoutSample(doubleArrayOf(0.70, 0.20, 0.10), actual = 0),
                ConfidenceHoldoutSample(doubleArrayOf(0.70, 0.20, 0.10), actual = 1),
                ConfidenceHoldoutSample(doubleArrayOf(0.50, 0.30, 0.20), actual = 0),
                ConfidenceHoldoutSample(doubleArrayOf(0.80, 0.10, 0.10), actual = 0),
            ),
            thresholds = listOf(0.60, 0.75),
        )

        assertEquals(3, metrics[0].sampleSize)
        assertEquals(2.0 / 3.0, metrics[0].accuracy!!, 1e-12)
        assertEquals((0.70 + 0.70 + 0.80) / 3.0, metrics[0].meanProbability!!, 1e-12)
        assertEquals((1.0 / 0.70 + 1.0 / 0.70 + 1.0 / 0.80) / 3.0, metrics[0].meanFairOdds!!, 1e-12)

        assertEquals(1, metrics[1].sampleSize)
        assertEquals(1.0, metrics[1].accuracy!!, 1e-12)
        assertEquals(1.25, metrics[1].meanFairOdds!!, 1e-12)
    }

    @Test
    fun reportsNoConfidenceFilteredPicksWhenNoOutcomeMeetsTheThreshold() {
        val metric = confidenceFilteredHoldoutMetrics(
            samples = listOf(ConfidenceHoldoutSample(doubleArrayOf(0.50, 0.30, 0.20), actual = 0)),
            thresholds = listOf(0.80),
        ).single()

        assertEquals(0, metric.sampleSize)
        assertEquals(null, metric.accuracy)
        assertEquals(null, metric.meanProbability)
        assertEquals(null, metric.meanFairOdds)
    }

    @Test
    fun selectsOneBestMarketPerMatchWithoutUsingTinyFairOdds() {
        val metrics = marketMixHoldoutMetrics(
            matches = listOf(
                MarketHoldoutMatch(
                    listOf(
                        MarketHoldoutCandidate("1X2", 0.70, won = true),
                        MarketHoldoutCandidate("TOTAL_GOALS", 0.72, won = false),
                        MarketHoldoutCandidate("DOUBLE_CHANCE", 0.80, won = true),
                    ),
                ),
                MarketHoldoutMatch(
                    listOf(
                        MarketHoldoutCandidate("BOTH_TEAMS_SCORE", 0.75, won = false),
                        MarketHoldoutCandidate("1X2", 0.40, won = true),
                    ),
                ),
                MarketHoldoutMatch(
                    listOf(MarketHoldoutCandidate("1X2", 0.80, won = true)),
                ),
            ),
            thresholds = listOf(0.60, 0.75),
        )

        assertEquals(2, metrics[0].sampleSize)
        assertEquals(0.0, metrics[0].accuracy!!, 1e-12)
        assertEquals((1.0 / 0.72 + 1.0 / 0.75) / 2.0, metrics[0].meanFairOdds!!, 1e-12)
        assertEquals(mapOf("TOTAL_GOALS" to 1, "BOTH_TEAMS_SCORE" to 1), metrics[0].marketCounts)
        assertEquals(1, metrics[1].sampleSize)
        assertEquals(0.75, metrics[1].meanProbability!!, 1e-12)
        assertEquals(MIN_MARKET_DIAGNOSTIC_FAIR_ODDS, metrics[0].minimumFairOdds)
    }

    @Test
    fun reportsPriceTradeoffWithoutSelectingVeryShortFairOdds() {
        val matches = listOf(
            MarketHoldoutMatch(
                listOf(
                    MarketHoldoutCandidate("DOUBLE_CHANCE", 0.92, won = true),
                    MarketHoldoutCandidate("TOTAL_GOALS", 0.89, won = false),
                    MarketHoldoutCandidate("TOTAL_GOALS", 0.86, won = false),
                    MarketHoldoutCandidate("TOTAL_GOALS", 0.82, won = true),
                ),
            ),
        )
        val metrics = listOf(1.10, 1.15, 1.20).map { minimumFairOdds ->
            marketMixHoldoutMetrics(
                matches = matches,
                thresholds = listOf(0.80),
                minimumFairOdds = minimumFairOdds,
            ).single()
        }

        assertEquals(1, metrics[0].sampleSize)
        assertEquals(0.89, metrics[0].meanProbability!!, 1e-12)
        assertEquals(1.0 / 0.89, metrics[0].meanFairOdds!!, 1e-12)
        assertEquals(1.10, metrics[0].minimumFairOdds)
        assertEquals(1, metrics[1].sampleSize)
        assertEquals(0.86, metrics[1].meanProbability!!, 1e-12)
        assertEquals(1.15, metrics[1].minimumFairOdds)
        assertEquals(1, metrics[2].sampleSize)
        assertEquals(0.82, metrics[2].meanProbability!!, 1e-12)
        assertEquals(1.0, metrics[2].accuracy!!, 1e-12)
        assertEquals(1.20, metrics[2].minimumFairOdds)
    }

    @Test
    fun reportsNoPickWhenProbabilityAndFairOddsLimitsDoNotOverlap() {
        val metric = marketMixHoldoutMetrics(
            matches = listOf(
                MarketHoldoutMatch(
                    listOf(
                        MarketHoldoutCandidate("TOTAL_GOALS", 0.92, won = true),
                        MarketHoldoutCandidate("DOUBLE_CHANCE", 0.88, won = false),
                    ),
                ),
            ),
            thresholds = listOf(0.90),
            minimumFairOdds = 1.15,
        ).single()

        assertEquals(0, metric.sampleSize)
        assertEquals(null, metric.accuracy)
        assertEquals(1.15, metric.minimumFairOdds)
    }

    @Test
    fun buildsPriceBoundedMarketHoldoutReportWithoutUnboundedRows() {
        val metrics = marketHoldoutReportMetrics(
            listOf(
                MarketHoldoutMatch(
                    listOf(MarketHoldoutCandidate("TOTAL_GOALS", 0.82, won = true)),
                ),
            ),
        )
        val priceSweep = metrics.filter { it.minimumFairOdds in listOf(1.10, 1.15, 1.20) }

        assertEquals(6, priceSweep.size)
        assertTrue(priceSweep.all { it.sampleSize == 1 && it.accuracy == 1.0 })
        assertTrue(metrics.all { it.minimumFairOdds != null })
    }

    @Test
    fun buildsMarketCandidatesFromTheCalibratedScoreDistribution() {
        val analysis = PredictionAnalysis(
            scoreDistribution = listOf(
                ScorelineProbability(0, 0, 0.10),
                ScorelineProbability(1, 0, 0.20),
                ScorelineProbability(0, 1, 0.15),
                ScorelineProbability(1, 1, 0.25),
                ScorelineProbability(2, 0, 0.15),
                ScorelineProbability(0, 2, 0.10),
                ScorelineProbability(2, 2, 0.05),
            ),
            expectedTotalGoals = 1.5,
            mostLikelyScore = "1:1",
            over15Probability = 0.55,
            over25Probability = 0.20,
            over35Probability = 0.05,
            bothTeamsToScoreProbability = 0.30,
            homeOrDrawProbability = 0.75,
            drawOrAwayProbability = 0.65,
            eitherTeamToWinProbability = 0.60,
            homeHistoryCount = 10,
            awayHistoryCount = 10,
            homeElo = 1500.0,
            awayElo = 1500.0,
            estimatedHomeAdvantageElo = 50.0,
            halfLifeDays = 365.0,
            dixonColesRho = 0.0,
            homeRestDays = 7,
            awayRestDays = 7,
            confidenceLabel = "test",
            latestSourceUpdate = null,
            explanation = "test",
        )

        val match = marketHoldoutCandidates(
            analysis = analysis,
            outcomeProbabilities = doubleArrayOf(0.60, 0.25, 0.15),
            actualHomeGoals = 2,
            actualAwayGoals = 0,
        )

        assertEquals(18, match.candidates.size)
        assertTrue(match.candidates.any {
            it.market == "1X2" && it.probability == 0.60 && it.won
        })
        assertEquals(10, match.candidates.count { it.market == "TOTAL_GOALS" })
        assertEquals(2, match.candidates.count { it.market == "BOTH_TEAMS_SCORE" })
    }

    @Test
    fun persistedConfidenceReportUsesOnlySelectedModelPredictionsAfterValidation() {
        val predictions = listOf(
            prediction(model = ELO_MODEL_VERSION, date = "2026-07-28", outcome = "HOME"),
            prediction(model = ELO_MODEL_VERSION, date = "2026-07-29", outcome = "HOME"),
            prediction(model = ELO_MODEL_VERSION, date = "2026-07-30", outcome = "HOME"),
            prediction(model = POISSON_MODEL_VERSION, date = "2026-07-30", outcome = "AWAY"),
        )

        val metrics = confidenceFilteredHoldoutMetrics(
            predictions = predictions,
            selectedModelVersion = ELO_MODEL_VERSION,
            validationEndDate = "2026-07-29",
        )

        assertEquals(1, metrics.first().sampleSize)
        assertEquals(1.0, metrics.first().accuracy!!, 1e-12)
        assertTrue(confidenceFilteredHoldoutMetrics(predictions, ELO_MODEL_VERSION, null).isEmpty())
    }

    private fun prediction(model: String, date: String, outcome: String) =
        BacktestPredictionEntity(
            runId = 1L,
            providerFixtureId = date.hashCode().toLong(),
            modelVersion = model,
            forecastedAt = "${date}T00:00:00Z",
            informationCutoff = date,
            homeWinProbability = 0.70,
            drawProbability = 0.20,
            awayWinProbability = 0.10,
            expectedHomeGoals = 1.0,
            expectedAwayGoals = 0.5,
            actualOutcome = outcome,
            actualHomeGoals = 1,
            actualAwayGoals = 0,
        )

    private fun metric(
        modelVersion: String,
        sampleSize: Int,
        accuracy: Double,
        scope: String = "test",
        logLoss: Double? = null,
        brierScore: Double? = null,
    ) =
        LocalModelMetric(
            modelVersion = modelVersion,
            sampleSize = sampleSize,
            logLoss = logLoss,
            brierScore = brierScore,
            accuracy = accuracy,
            meanAbsoluteGoalError = null,
            scope = scope,
        )
}
