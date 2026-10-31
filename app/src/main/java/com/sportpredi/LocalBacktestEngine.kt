package com.sportpredi

import androidx.room.withTransaction
import com.google.gson.Gson
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

data class LocalModelMetric(
    val modelVersion: String,
    val sampleSize: Int,
    val logLoss: Double?,
    val brierScore: Double?,
    val accuracy: Double?,
    val meanAbsoluteGoalError: Double?,
    val scope: String = "all",
    val calibrationError: Double? = null,
)

data class LocalBacktestReport(
    val runId: Long,
    val createdAt: String,
    val sampleSize: Int,
    val models: List<LocalModelMetric>,
    val confidenceFilteredHoldout: List<ConfidenceFilteredHoldoutMetric>,
    val marketMixHoldout: List<MarketMixHoldoutMetric>,
    val trainEndDate: String?,
    val validationEndDate: String?,
    val selectedDixonColesRho: Double,
    val selectedHalfLifeDays: Double,
    val selectedEloKFactor: Double,
) {
    val holdoutComparisons: List<HoldoutAccuracyComparison>
        get() = compareHoldoutAccuracies(models)
}

data class ConfidenceFilteredHoldoutMetric(
    val minimumProbability: Double,
    val sampleSize: Int,
    val accuracy: Double?,
    val meanProbability: Double?,
    val meanFairOdds: Double?,
)

data class MarketMixHoldoutMetric(
    val minimumProbability: Double,
    val minimumFairOdds: Double?,
    val sampleSize: Int,
    val accuracy: Double?,
    val meanProbability: Double?,
    val meanFairOdds: Double?,
    val marketCounts: Map<String, Int>,
)

internal data class ConfidenceHoldoutSample(
    val probabilities: DoubleArray,
    val actual: Int,
)

internal data class MarketHoldoutCandidate(
    val market: String,
    val probability: Double,
    val won: Boolean,
)

internal data class MarketHoldoutMatch(
    val candidates: List<MarketHoldoutCandidate>,
)

internal const val MIN_MARKET_DIAGNOSTIC_FAIR_ODDS = 1.30

internal fun marketMixHoldoutMetrics(
    matches: List<MarketHoldoutMatch>,
    thresholds: List<Double> = listOf(0.50, 0.55, 0.60, 0.65, 0.70, 0.75),
    minimumFairOdds: Double? = MIN_MARKET_DIAGNOSTIC_FAIR_ODDS,
): List<MarketMixHoldoutMetric> = thresholds.map { threshold ->
    val maximumProbability = minimumFairOdds?.let { 1.0 / it } ?: 1.0
    val selected = matches.mapNotNull { match ->
        match.candidates
            .filter {
                it.probability.isFinite() &&
                    it.probability in threshold..maximumProbability
            }
            .maxByOrNull { it.probability }
    }
    val sampleSize = selected.size
    MarketMixHoldoutMetric(
        minimumProbability = threshold,
        minimumFairOdds = minimumFairOdds,
        sampleSize = sampleSize,
        accuracy = if (sampleSize == 0) null
        else selected.count { it.won }.toDouble() / sampleSize,
        meanProbability = if (sampleSize == 0) null
        else selected.sumOf { it.probability } / sampleSize,
        meanFairOdds = if (sampleSize == 0) null
        else selected.sumOf { 1.0 / it.probability } / sampleSize,
        marketCounts = selected.groupingBy { it.market }.eachCount(),
    )
}

internal fun marketHoldoutReportMetrics(
    matches: List<MarketHoldoutMatch>,
): List<MarketMixHoldoutMetric> =
    marketMixHoldoutMetrics(matches) +
        listOf(1.10, 1.15, 1.20).flatMap { minimumFairOdds ->
            marketMixHoldoutMetrics(
                matches = matches,
                thresholds = listOf(0.75, 0.80),
                minimumFairOdds = minimumFairOdds,
            )
        }

internal fun marketHoldoutCandidates(
    analysis: PredictionAnalysis,
    outcomeProbabilities: DoubleArray,
    actualHomeGoals: Int,
    actualAwayGoals: Int,
): MarketHoldoutMatch {
    val calibrated = applyOutcomeProbabilities(
        analysis = analysis,
        outcomeProbabilities = outcomeProbabilities,
        outcomeModelVersion = "backtest-market-holdout",
    )
    val actualOutcome = when {
        actualHomeGoals > actualAwayGoals -> 0
        actualHomeGoals == actualAwayGoals -> 1
        else -> 2
    }
    val candidates = mutableListOf<MarketHoldoutCandidate>()
    listOf("HOME", "DRAW", "AWAY").forEachIndexed { index, outcome ->
        candidates += MarketHoldoutCandidate(
            market = "1X2",
            probability = outcomeProbabilities[index],
            won = actualOutcome == index,
        )
    }
    listOf(
        Triple("HOME_DRAW", outcomeProbabilities[0] + outcomeProbabilities[1], actualHomeGoals >= actualAwayGoals),
        Triple("HOME_AWAY", outcomeProbabilities[0] + outcomeProbabilities[2], actualHomeGoals != actualAwayGoals),
        Triple("DRAW_AWAY", outcomeProbabilities[1] + outcomeProbabilities[2], actualAwayGoals >= actualHomeGoals),
    ).forEach { (outcome, probability, won) ->
        candidates += MarketHoldoutCandidate("DOUBLE_CHANCE", probability, won)
    }
    listOf(0.5, 1.5, 2.5, 3.5, 4.5).forEach { line ->
        val overProbability = calibrated.scoreDistribution
            .filter { it.homeGoals + it.awayGoals > line }
            .sumOf { it.probability }
        val underProbability = calibrated.scoreDistribution
            .filter { it.homeGoals + it.awayGoals < line }
            .sumOf { it.probability }
        val actualOver = actualHomeGoals + actualAwayGoals > line
        candidates += MarketHoldoutCandidate("TOTAL_GOALS", overProbability, actualOver)
        candidates += MarketHoldoutCandidate("TOTAL_GOALS", underProbability, !actualOver)
    }
    val bothTeamsScoreProbability = calibrated.scoreDistribution
        .filter { it.homeGoals > 0 && it.awayGoals > 0 }
        .sumOf { it.probability }
    val actualBothTeamsScored = actualHomeGoals > 0 && actualAwayGoals > 0
    candidates += MarketHoldoutCandidate("BOTH_TEAMS_SCORE", bothTeamsScoreProbability, actualBothTeamsScored)
    candidates += MarketHoldoutCandidate("BOTH_TEAMS_SCORE", 1.0 - bothTeamsScoreProbability, !actualBothTeamsScored)
    return MarketHoldoutMatch(candidates)
}

internal fun confidenceFilteredHoldoutMetrics(
    samples: List<ConfidenceHoldoutSample>,
    thresholds: List<Double> = listOf(0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90),
): List<ConfidenceFilteredHoldoutMetric> = thresholds.map { threshold ->
    val selected = samples.mapNotNull { sample ->
        val predictedOutcome = sample.probabilities.indices.maxBy { sample.probabilities[it] }
        val probability = sample.probabilities[predictedOutcome]
        if (probability < threshold) {
            null
        } else {
            probability to (predictedOutcome == sample.actual)
        }
    }
    val sampleSize = selected.size
    ConfidenceFilteredHoldoutMetric(
        minimumProbability = threshold,
        sampleSize = sampleSize,
        accuracy = if (sampleSize == 0) null
        else selected.count { it.second }.toDouble() / sampleSize,
        meanProbability = if (sampleSize == 0) null
        else selected.sumOf { it.first } / sampleSize,
        meanFairOdds = if (sampleSize == 0) null
        else selected.sumOf { 1.0 / it.first } / sampleSize,
    )
}

internal fun confidenceFilteredHoldoutMetrics(
    predictions: List<BacktestPredictionEntity>,
    selectedModelVersion: String,
    validationEndDate: String?,
): List<ConfidenceFilteredHoldoutMetric> {
    if (validationEndDate == null) return emptyList()
    val testStart = LocalDate.parse(validationEndDate).plusDays(1)
    val samples = predictions.asSequence()
        .filter {
            it.modelVersion == selectedModelVersion &&
                LocalDate.parse(it.forecastedAt.substringBefore('T')) >= testStart
        }
        .mapNotNull { prediction ->
            val actual = when (prediction.actualOutcome) {
                "HOME" -> 0
                "DRAW" -> 1
                "AWAY" -> 2
                else -> return@mapNotNull null
            }
            ConfidenceHoldoutSample(
                probabilities = doubleArrayOf(
                    prediction.homeWinProbability,
                    prediction.drawProbability,
                    prediction.awayWinProbability,
                ),
                actual = actual,
            )
        }
        .toList()
    return confidenceFilteredHoldoutMetrics(samples)
}

data class HoldoutAccuracyComparison(
    val candidateModelVersion: String,
    val baselineModelVersion: String,
    val candidateSamples: Int,
    val baselineSamples: Int,
    val candidateAccuracy: Double?,
    val baselineAccuracy: Double?,
    val improvement: Double?,
) {
    val meetsAccuracyGoal: Boolean
        get() = candidateSamples >= MIN_HOLDOUT_SAMPLES_FOR_GOAL &&
            candidateAccuracy?.let { it >= MODEL_ACCURACY_GOAL } == true
}

internal const val OLD_BACKTEST_BASELINE_MODEL_VERSION = "poisson-form-local-v1"
internal const val EMPIRICAL_MODEL_VERSION = "league-outcome-baseline-v1"
internal const val ELO_MODEL_VERSION = "elo-dynamic-local-v1"
internal const val POISSON_MODEL_VERSION = "poisson-time-decay-local-v2"
internal const val MINIMUM_VALIDATION_MODEL_SAMPLES = 100
internal const val MODEL_ACCURACY_GOAL = 0.90
internal const val MIN_HOLDOUT_SAMPLES_FOR_GOAL = 30

internal fun selectValidatedForecastModel(metrics: List<LocalModelMetric>): String {
    val supportedModels = setOf(
        OLD_BACKTEST_BASELINE_MODEL_VERSION,
        EMPIRICAL_MODEL_VERSION,
        ELO_MODEL_VERSION,
        POISSON_MODEL_VERSION,
        LocalForecastingState.MODEL_VERSION,
    )
    return metrics
        .filter {
            it.scope == "validation" &&
                it.modelVersion in supportedModels &&
                it.sampleSize >= MINIMUM_VALIDATION_MODEL_SAMPLES &&
                it.logLoss?.isFinite() == true &&
                it.brierScore?.isFinite() == true
        }
        .minWithOrNull(
            compareBy<LocalModelMetric> { it.logLoss ?: Double.POSITIVE_INFINITY }
                .thenBy { it.brierScore ?: Double.POSITIVE_INFINITY }
                .thenBy { it.modelVersion },
        )
        ?.modelVersion ?: LocalForecastingState.MODEL_VERSION
}

internal fun compareHoldoutAccuracies(
    metrics: List<LocalModelMetric>,
    baselineModelVersion: String = OLD_BACKTEST_BASELINE_MODEL_VERSION,
): List<HoldoutAccuracyComparison> {
    val testMetrics = metrics.filter { it.scope == "test" }
    val baseline = testMetrics.firstOrNull { it.modelVersion == baselineModelVersion }
        ?: return emptyList()
    return testMetrics
        .filter { it.modelVersion != baselineModelVersion }
        .map { candidate ->
            HoldoutAccuracyComparison(
                candidateModelVersion = candidate.modelVersion,
                baselineModelVersion = baselineModelVersion,
                candidateSamples = candidate.sampleSize,
                baselineSamples = baseline.sampleSize,
                candidateAccuracy = candidate.accuracy,
                baselineAccuracy = baseline.accuracy,
                improvement = if (
                    candidate.sampleSize == baseline.sampleSize &&
                    candidate.accuracy != null &&
                    baseline.accuracy != null
                ) {
                    candidate.accuracy - baseline.accuracy
                } else {
                    null
                },
            )
        }
}

private class MetricAccumulator {
    var count = 0
    var logLoss = 0.0
    var brier = 0.0
    var correct = 0
    var goalError = 0.0
    private val calibrationCount = Array(3) { IntArray(CALIBRATION_BINS) }
    private val calibrationPredicted = Array(3) { DoubleArray(CALIBRATION_BINS) }
    private val calibrationObserved = Array(3) { DoubleArray(CALIBRATION_BINS) }

    fun add(
        probabilities: DoubleArray,
        actual: Int,
        homeError: Double,
        awayError: Double,
    ) {
        count += 1
        logLoss -= ln(probabilities[actual].coerceAtLeast(1e-15))
        probabilities.forEachIndexed { index, probability ->
            val expected = if (index == actual) 1.0 else 0.0
            brier += (probability - expected).pow(2)
            val bin = (probability * CALIBRATION_BINS).toInt().coerceIn(0, CALIBRATION_BINS - 1)
            calibrationCount[index][bin] += 1
            calibrationPredicted[index][bin] += probability
            calibrationObserved[index][bin] += expected
        }
        if (probabilities.indices.maxBy { probabilities[it] } == actual) correct += 1
        goalError += abs(homeError) + abs(awayError)
    }

    fun toMetric(model: String, scope: String) = LocalModelMetric(
        modelVersion = model,
        sampleSize = count,
        logLoss = if (count == 0) null else logLoss / count,
        brierScore = if (count == 0) null else brier / count,
        accuracy = if (count == 0) null else correct.toDouble() / count,
        meanAbsoluteGoalError = if (count == 0) null else goalError / (2 * count),
        scope = scope,
        calibrationError = if (count == 0) null else calibrationError(),
    )

    private fun calibrationError(): Double {
        var totalError = 0.0
        for (outcome in 0..2) {
            for (bin in 0 until CALIBRATION_BINS) {
                val binCount = calibrationCount[outcome][bin]
                if (binCount == 0) continue
                val averagePrediction = calibrationPredicted[outcome][bin] / binCount
                val observedFrequency = calibrationObserved[outcome][bin] / binCount
                totalError += binCount * abs(averagePrediction - observedFrequency)
            }
        }
        return totalError / (3 * count)
    }

    companion object {
        private const val CALIBRATION_BINS = 10
    }
}

private data class BacktestCase(
    val fixture: FixtureEntity,
    val actual: Int,
    val forecasts: Map<String, ModelPrediction>,
    val analysis: PredictionAnalysis,
)

private data class TemporalSplit(
    val validationStart: LocalDate?,
    val testStart: LocalDate?,
) {
    fun scope(date: LocalDate): String = when {
        validationStart == null || date < validationStart -> "train"
        testStart == null || date < testStart -> "validation"
        else -> "test"
    }

    val trainEndDate: String?
        get() = validationStart?.minusDays(1)?.toString()

    val validationEndDate: String?
        get() = testStart?.minusDays(1)?.toString()
}

internal data class ValidatedForecastParameters(
    val parameters: LocalForecastParameters,
    val sampleSize: Int,
    val logLoss: Double,
    val brierScore: Double,
    val marketPickSampleSize: Int = 0,
    val marketPickAccuracy: Double? = null,
)

internal fun selectForecastParameters(
    candidates: List<ValidatedForecastParameters>,
): LocalForecastParameters {
    val validCandidates = candidates.filter {
        it.sampleSize >= MINIMUM_VALIDATION_MODEL_SAMPLES &&
            it.logLoss.isFinite() &&
            it.brierScore.isFinite()
    }
    val marketQualifiedCandidates = validCandidates.filter {
        it.marketPickSampleSize >= MINIMUM_VALIDATION_MODEL_SAMPLES &&
            it.marketPickAccuracy?.isFinite() == true
    }
    return marketQualifiedCandidates
        .sortedWith(
            compareByDescending<ValidatedForecastParameters> { it.marketPickAccuracy }
                .thenBy { it.logLoss }
                .thenBy { it.brierScore },
        )
        .firstOrNull()
        ?.parameters
        ?: validCandidates
            .minWithOrNull(
                compareBy<ValidatedForecastParameters> { it.logLoss }
                    .thenBy { it.brierScore },
            )
            ?.parameters
        ?: LocalForecastParameters()
}

class LocalBacktestEngine(private val database: SportPrediDatabase) {
    private val dao = database.sportPrediDao()

    suspend fun refreshMarketHoldoutMetrics(
        runId: Long,
        expectedDatasetFingerprint: String,
        selectedModelVersion: String,
        trainEndDate: String?,
        validationEndDate: String?,
        selectedHalfLifeDays: Double,
        selectedDixonColesRho: Double,
        selectedEloKFactor: Double,
    ): List<MarketMixHoldoutMetric>? {
        if (validationEndDate == null) return null
        val fixtures = forecastableFixtures()
        if (datasetFingerprint(fixtures) != expectedDatasetFingerprint) return null
        val split = TemporalSplit(
            validationStart = trainEndDate?.let { LocalDate.parse(it).plusDays(1) },
            testStart = LocalDate.parse(validationEndDate).plusDays(1),
        )
        val holdoutMatches = mutableListOf<MarketHoldoutMatch>()
        walkForward(
            fixtures = fixtures,
            parameters = LocalForecastParameters(
                halfLifeDays = selectedHalfLifeDays,
                dixonColesRho = selectedDixonColesRho,
                eloKFactor = selectedEloKFactor,
            ),
            split = split,
            onlyScope = "test",
        ) { case, _ ->
            holdoutMatches += marketHoldoutCandidates(
                analysis = case.analysis,
                outcomeProbabilities = case.forecasts.getValue(selectedModelVersion).probabilities,
                actualHomeGoals = case.fixture.homeScore!!,
                actualAwayGoals = case.fixture.awayScore!!,
            )
        }
        val metrics = marketHoldoutReportMetrics(holdoutMatches)
        dao.updateBacktestRunMarketMetrics(runId, Gson().toJson(metrics))
        return metrics
    }

    suspend fun run(): LocalBacktestReport {
        val fixtures = forecastableFixtures()
        val split = temporalSplit(fixtures)
        val validationScores = mutableListOf<ValidatedForecastParameters>()
        PARAMETER_CANDIDATES.forEach { parameters ->
            val validationAccumulators = MODEL_VERSIONS.associateWith { MetricAccumulator() }
            val validationMarketMatches = MODEL_VERSIONS.associateWith {
                mutableListOf<MarketHoldoutMatch>()
            }
            walkForward(fixtures, parameters, split, "validation") { case, _ ->
                val actualHomeGoals = case.fixture.homeScore!!
                val actualAwayGoals = case.fixture.awayScore!!
                MODEL_VERSIONS.forEach { model ->
                    val forecast = case.forecasts.getValue(model)
                    validationAccumulators.getValue(model).add(
                        forecast.probabilities,
                        case.actual,
                        forecast.expectedHomeGoals - actualHomeGoals,
                        forecast.expectedAwayGoals - actualAwayGoals,
                    )
                    validationMarketMatches.getValue(model) += marketHoldoutCandidates(
                        analysis = case.analysis,
                        outcomeProbabilities = forecast.probabilities,
                        actualHomeGoals = actualHomeGoals,
                        actualAwayGoals = actualAwayGoals,
                    )
                }
            }
            val modelMetrics = MODEL_VERSIONS.map { model ->
                validationAccumulators.getValue(model).toMetric(model, "validation")
            }
            val selectedModel = selectValidatedForecastModel(modelMetrics)
            val selectedMetric = modelMetrics.first { it.modelVersion == selectedModel }
            val marketMetric = marketMixHoldoutMetrics(
                matches = validationMarketMatches.getValue(selectedModel),
                thresholds = listOf(0.50),
                minimumFairOdds = MIN_MARKET_DIAGNOSTIC_FAIR_ODDS,
            ).single()
            validationScores += ValidatedForecastParameters(
                parameters = parameters,
                sampleSize = selectedMetric.sampleSize,
                logLoss = selectedMetric.logLoss ?: Double.POSITIVE_INFINITY,
                brierScore = selectedMetric.brierScore ?: Double.POSITIVE_INFINITY,
                marketPickSampleSize = marketMetric.sampleSize,
                marketPickAccuracy = marketMetric.accuracy,
            )
        }
        val selectedParameters = selectForecastParameters(validationScores)

        val createdAt = Instant.now().toString()
        val datasetHash = datasetFingerprint(fixtures)
        val accumulators = (MODEL_VERSIONS.flatMap { model ->
            listOf("all", "train", "validation", "test").map { scope ->
                (model to scope) to MetricAccumulator()
            }
        }).toMap().toMutableMap()
        val predictionRows = mutableListOf<BacktestPredictionEntity>()
        val holdoutCases = mutableListOf<BacktestCase>()
        var forecastableFixtures = 0
        var createdRunId = 0L
        var models = emptyList<LocalModelMetric>()
        var confidenceFilteredHoldout = emptyList<ConfidenceFilteredHoldoutMetric>()
        var marketMixHoldout = emptyList<MarketMixHoldoutMetric>()

        database.withTransaction {
            val runId = dao.insertBacktestRun(
                BacktestRunEntity(
                    createdAt = createdAt,
                    sampleSize = 0,
                    datasetFingerprint = datasetHash,
                    trainEndDate = split.trainEndDate,
                    validationEndDate = split.validationEndDate,
                    selectedDixonColesRho = selectedParameters.dixonColesRho,
                    selectedHalfLifeDays = selectedParameters.halfLifeDays,
                    selectedEloKFactor = selectedParameters.eloKFactor,
                ),
            )
            createdRunId = runId
            walkForward(fixtures, selectedParameters, split, null) { case, scope ->
                forecastableFixtures += 1
                if (scope == "test") holdoutCases += case
                case.forecasts.forEach { (model, forecast) ->
                    val errorHome = forecast.expectedHomeGoals - case.fixture.homeScore!!
                    val errorAway = forecast.expectedAwayGoals - case.fixture.awayScore!!
                    accumulators.getValue(model to "all").add(
                        forecast.probabilities,
                        case.actual,
                        errorHome,
                        errorAway,
                    )
                    accumulators.getValue(model to scope).add(
                        forecast.probabilities,
                        case.actual,
                        errorHome,
                        errorAway,
                    )
                    predictionRows += BacktestPredictionEntity(
                        runId = runId,
                        providerFixtureId = case.fixture.providerFixtureId,
                        modelVersion = model,
                        forecastedAt = "${case.fixture.matchDate}T00:00:00Z",
                        informationCutoff = LocalDate.parse(case.fixture.matchDate).minusDays(1).toString(),
                        homeWinProbability = forecast.probabilities[0],
                        drawProbability = forecast.probabilities[1],
                        awayWinProbability = forecast.probabilities[2],
                        expectedHomeGoals = forecast.expectedHomeGoals,
                        expectedAwayGoals = forecast.expectedAwayGoals,
                        actualOutcome = outcomeName(case.actual),
                        actualHomeGoals = case.fixture.homeScore!!,
                        actualAwayGoals = case.fixture.awayScore!!,
                    )
                    if (predictionRows.size >= PREDICTION_BATCH_SIZE) {
                        dao.insertBacktestPredictions(predictionRows.toList())
                        predictionRows.clear()
                    }
                }
            }
            if (predictionRows.isNotEmpty()) dao.insertBacktestPredictions(predictionRows)
            dao.insertBacktestMetrics(
                accumulators.map { (key, accumulator) ->
                    val (model, scope) = key
                    val metric = accumulator.toMetric(model, scope)
                    BacktestModelMetricEntity(
                        runId = runId,
                        modelVersion = model,
                        sampleSize = metric.sampleSize,
                        logLoss = metric.logLoss,
                        brierScore = metric.brierScore,
                        accuracy = metric.accuracy,
                        meanAbsoluteGoalError = metric.meanAbsoluteGoalError,
                        scope = scope,
                        calibrationError = metric.calibrationError,
                    )
                },
            )
            models = accumulators.map { (key, accumulator) ->
                accumulator.toMetric(key.first, key.second)
            }
            val selectedModelVersion = selectValidatedForecastModel(models)
            confidenceFilteredHoldout = confidenceFilteredHoldoutMetrics(
                holdoutCases.map { case ->
                    ConfidenceHoldoutSample(
                        probabilities = case.forecasts.getValue(selectedModelVersion).probabilities,
                        actual = case.actual,
                    )
                },
            )
            val marketHoldoutMatches = holdoutCases.map { case ->
                    marketHoldoutCandidates(
                        analysis = case.analysis,
                        outcomeProbabilities = case.forecasts.getValue(selectedModelVersion).probabilities,
                        actualHomeGoals = case.fixture.homeScore!!,
                        actualAwayGoals = case.fixture.awayScore!!,
                    )
                }
            marketMixHoldout = marketHoldoutReportMetrics(marketHoldoutMatches)
            dao.updateBacktestRunSampleSize(runId, forecastableFixtures)
            dao.updateBacktestRunMarketMetrics(runId, Gson().toJson(marketMixHoldout))
        }

        return LocalBacktestReport(
            runId = createdRunId,
            createdAt = createdAt,
            sampleSize = forecastableFixtures,
            models = models,
            confidenceFilteredHoldout = confidenceFilteredHoldout,
            marketMixHoldout = marketMixHoldout,
            trainEndDate = split.trainEndDate,
            validationEndDate = split.validationEndDate,
            selectedDixonColesRho = selectedParameters.dixonColesRho,
            selectedHalfLifeDays = selectedParameters.halfLifeDays,
            selectedEloKFactor = selectedParameters.eloKFactor,
        )
    }

    private suspend fun forecastableFixtures(): List<FixtureEntity> =
        dao.completedFixtures()
            .filter {
                it.fullTimeResult &&
                    it.homeTeamId != null &&
                    it.awayTeamId != null &&
                    it.homeScore != null &&
                    it.awayScore != null &&
                    it.matchDate.isNotBlank()
            }
            .sortedWith(compareBy<FixtureEntity>({ it.matchDate }, { it.kickoffAt.orEmpty() }))

    private suspend fun walkForward(
        fixtures: List<FixtureEntity>,
        parameters: LocalForecastParameters,
        split: TemporalSplit,
        onlyScope: String?,
        onCase: suspend (BacktestCase, String) -> Unit,
    ) {
        val state = LocalForecastingState(parameters)
        fixtures.groupBy { it.matchDate }.toSortedMap().forEach { (day, matches) ->
            val date = LocalDate.parse(day)
            val forecasts = matches.mapNotNull { fixture ->
                if (!state.hasMinimumHistory(fixture)) return@mapNotNull null
                fixture.toBacktestCase(state.forecast(fixture))
            }
            val scope = split.scope(date)
            if (onlyScope == null || onlyScope == scope) {
                forecasts.forEach { onCase(it, scope) }
            }
            matches.forEach(state::addCompletedMatch)
        }
    }

    private fun FixtureEntity.toBacktestCase(result: LocalForecastResult): BacktestCase {
        val actual = when {
            homeScore!! > awayScore!! -> 0
            homeScore == awayScore -> 1
            else -> 2
        }
        return BacktestCase(
            fixture = this,
            actual = actual,
            forecasts = mapOf(
                MODEL_EXISTING to result.existingModel,
                MODEL_EMPIRICAL to result.empiricalBaseline,
                MODEL_ELO to result.eloModel,
                MODEL_POISSON to result.poissonModel,
                LocalForecastingState.MODEL_VERSION to result.dixonColesModel,
            ),
            analysis = result.analysis,
        )
    }

    private fun temporalSplit(fixtures: List<FixtureEntity>): TemporalSplit {
        val dates = fixtures.map { LocalDate.parse(it.matchDate) }.distinct().sorted()
        if (dates.size < MINIMUM_SPLIT_DATES) return TemporalSplit(null, null)
        val validationIndex = (dates.size * TRAIN_FRACTION).toInt().coerceIn(1, dates.lastIndex - 1)
        val testIndex = (dates.size * VALIDATION_FRACTION).toInt()
            .coerceIn(validationIndex + 1, dates.lastIndex)
        return TemporalSplit(dates[validationIndex], dates[testIndex])
    }

    private fun datasetFingerprint(fixtures: List<FixtureEntity>): String {
        val input = fixtures.joinToString("|") {
            "${it.providerFixtureId}:${it.competitionCode}:${it.seasonId}:${it.matchDate}:" +
                "${it.homeTeamId}-${it.awayTeamId}:${it.homeScore}-${it.awayScore}:${it.updatedAt}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun outcomeName(index: Int): String =
        when (index) {
            0 -> "HOME"
            1 -> "DRAW"
            else -> "AWAY"
        }

    companion object {
        private const val PREDICTION_BATCH_SIZE = 1_000
        private const val MINIMUM_SPLIT_DATES = 5
        private const val TRAIN_FRACTION = 0.60
        private const val VALIDATION_FRACTION = 0.80
        private const val MODEL_EXISTING = OLD_BACKTEST_BASELINE_MODEL_VERSION
        private const val MODEL_EMPIRICAL = EMPIRICAL_MODEL_VERSION
        private const val MODEL_ELO = ELO_MODEL_VERSION
        private const val MODEL_POISSON = POISSON_MODEL_VERSION
        private val MODEL_VERSIONS = listOf(
            MODEL_EXISTING,
            MODEL_EMPIRICAL,
            MODEL_ELO,
            MODEL_POISSON,
            LocalForecastingState.MODEL_VERSION,
        )
        private val PARAMETER_CANDIDATES = listOf(180.0, 365.0, 730.0).flatMap { halfLife ->
            listOf(0.0, -0.05, -0.10, -0.15).flatMap { rho ->
                listOf(10.0, 20.0, 30.0).map { eloKFactor ->
                    LocalForecastParameters(
                        halfLifeDays = halfLife,
                        dixonColesRho = rho,
                        eloKFactor = eloKFactor,
                    )
                }
            }
        }
    }
}
