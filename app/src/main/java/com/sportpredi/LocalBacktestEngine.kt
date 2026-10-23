package com.sportpredi

import androidx.room.withTransaction
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
    val trainEndDate: String?,
    val validationEndDate: String?,
    val selectedDixonColesRho: Double,
    val selectedHalfLifeDays: Double,
)

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
    val accuracy: Double,
    val logLoss: Double,
)

internal fun selectForecastParameters(
    candidates: List<ValidatedForecastParameters>,
): LocalForecastParameters =
    candidates
        .filter { it.sampleSize >= 100 }
        .maxWithOrNull(
            compareBy<ValidatedForecastParameters> { it.accuracy }
                .thenBy { -it.logLoss },
        )
        ?.parameters ?: LocalForecastParameters()

class LocalBacktestEngine(private val database: SportPrediDatabase) {
    private val dao = database.sportPrediDao()

    suspend fun run(): LocalBacktestReport {
        val fixtures = dao.completedFixtures()
            .filter {
                it.fullTimeResult &&
                    it.homeTeamId != null &&
                    it.awayTeamId != null &&
                    it.homeScore != null &&
                    it.awayScore != null &&
                    it.matchDate.isNotBlank()
            }
            .sortedWith(compareBy<FixtureEntity>({ it.matchDate }, { it.kickoffAt.orEmpty() }))
        val split = temporalSplit(fixtures)
        val validationScores = mutableMapOf<LocalForecastParameters, MetricAccumulator>()
        PARAMETER_CANDIDATES.forEach { parameters ->
            val validation = MetricAccumulator()
            walkForward(fixtures, parameters, split, "validation") { case, _ ->
                val forecast = case.forecasts.getValue(LocalForecastingState.MODEL_VERSION)
                validation.add(
                    forecast.probabilities,
                    case.actual,
                    forecast.expectedHomeGoals - case.fixture.homeScore!!,
                    forecast.expectedAwayGoals - case.fixture.awayScore!!,
                )
            }
            validationScores[parameters] = validation
        }
        val selectedParameters = selectForecastParameters(
            validationScores.map { (parameters, metric) ->
                ValidatedForecastParameters(
                    parameters = parameters,
                    sampleSize = metric.count,
                    accuracy = if (metric.count == 0) 0.0 else metric.correct.toDouble() / metric.count,
                    logLoss = if (metric.count == 0) Double.POSITIVE_INFINITY
                    else metric.logLoss / metric.count,
                )
            },
        )

        val createdAt = Instant.now().toString()
        val datasetHash = datasetFingerprint(fixtures)
        val accumulators = (MODEL_VERSIONS.flatMap { model ->
            listOf("all", "train", "validation", "test").map { scope ->
                (model to scope) to MetricAccumulator()
            }
        }).toMap().toMutableMap()
        val predictionRows = mutableListOf<BacktestPredictionEntity>()
        var forecastableFixtures = 0
        var createdRunId = 0L

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
                ),
            )
            createdRunId = runId
            walkForward(fixtures, selectedParameters, split, null) { case, scope ->
                forecastableFixtures += 1
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
            dao.updateBacktestRunSampleSize(runId, forecastableFixtures)
        }

        return LocalBacktestReport(
            runId = createdRunId,
            createdAt = createdAt,
            sampleSize = forecastableFixtures,
            models = accumulators.map { (key, accumulator) ->
                accumulator.toMetric(key.first, key.second)
            },
            trainEndDate = split.trainEndDate,
            validationEndDate = split.validationEndDate,
            selectedDixonColesRho = selectedParameters.dixonColesRho,
            selectedHalfLifeDays = selectedParameters.halfLifeDays,
        )
    }

    private suspend fun walkForward(
        fixtures: List<FixtureEntity>,
        parameters: LocalForecastParameters,
        split: TemporalSplit,
        onlyScope: String?,
        onCase: suspend (BacktestCase, String) -> Unit,
    ) {
        fixtures.groupBy { it.competitionCode }.values.forEach { competitionFixtures ->
            val state = LocalForecastingState(parameters)
            competitionFixtures.groupBy { it.matchDate }.toSortedMap().forEach { (day, matches) ->
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
        private const val MODEL_EXISTING = "poisson-form-local-v1"
        private const val MODEL_EMPIRICAL = "league-outcome-baseline-v1"
        private const val MODEL_ELO = "elo-dynamic-local-v1"
        private const val MODEL_POISSON = "poisson-time-decay-local-v2"
        private val MODEL_VERSIONS = listOf(
            MODEL_EXISTING,
            MODEL_EMPIRICAL,
            MODEL_ELO,
            MODEL_POISSON,
            LocalForecastingState.MODEL_VERSION,
        )
        private val PARAMETER_CANDIDATES = listOf(180.0, 365.0, 730.0).flatMap { halfLife ->
            listOf(0.0, -0.05, -0.10, -0.15).map { rho ->
                LocalForecastParameters(halfLifeDays = halfLife, dixonColesRho = rho)
            }
        }
    }
}
