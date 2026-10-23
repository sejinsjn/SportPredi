package com.sportpredi

import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

data class LocalForecastParameters(
    val halfLifeDays: Double = 365.0,
    val dixonColesRho: Double = 0.0,
)

data class ScorelineProbability(
    val homeGoals: Int,
    val awayGoals: Int,
    val probability: Double,
)

data class PredictionAnalysis(
    val scoreDistribution: List<ScorelineProbability>,
    val expectedTotalGoals: Double,
    val mostLikelyScore: String,
    val over15Probability: Double,
    val over25Probability: Double,
    val over35Probability: Double,
    val bothTeamsToScoreProbability: Double,
    val homeOrDrawProbability: Double,
    val drawOrAwayProbability: Double,
    val eitherTeamToWinProbability: Double,
    val homeHistoryCount: Int,
    val awayHistoryCount: Int,
    val homeElo: Double,
    val awayElo: Double,
    val estimatedHomeAdvantageElo: Double,
    val halfLifeDays: Double,
    val dixonColesRho: Double,
    val homeRestDays: Long?,
    val awayRestDays: Long?,
    val confidenceLabel: String,
    val latestSourceUpdate: String?,
    val explanation: String,
)

data class ModelPrediction(
    val probabilities: DoubleArray,
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
)

data class LocalForecastResult(
    val analysis: PredictionAnalysis,
    val existingModel: ModelPrediction,
    val empiricalBaseline: ModelPrediction,
    val eloModel: ModelPrediction,
    val poissonModel: ModelPrediction,
    val dixonColesModel: ModelPrediction,
    val inputFingerprint: String,
)

private data class RecentTeamMatch(
    val date: LocalDate,
    val goalsFor: Int,
    val goalsAgainst: Int,
)

private class TeamForecastState {
    var homeWeight = 0.0
    var homeGoalsFor = 0.0
    var homeGoalsAgainst = 0.0
    var awayWeight = 0.0
    var awayGoalsFor = 0.0
    var awayGoalsAgainst = 0.0
    var matchCount = 0
    var rating = INITIAL_ELO
    var lastMatchDate: LocalDate? = null
    val recent = ArrayDeque<RecentTeamMatch>()

    fun decay(factor: Double) {
        homeWeight *= factor
        homeGoalsFor *= factor
        homeGoalsAgainst *= factor
        awayWeight *= factor
        awayGoalsFor *= factor
        awayGoalsAgainst *= factor
    }

    companion object {
        const val INITIAL_ELO = 1500.0
    }
}

class LocalForecastingState(
    private val parameters: LocalForecastParameters = LocalForecastParameters(),
) {
    private val teams = mutableMapOf<Int, TeamForecastState>()
    private var currentDate: LocalDate? = null
    private var lastCompletedMatchDate: LocalDate? = null
    private var seasonId: String? = null
    private var leagueMatchWeight = 0.0
    private var leagueHomeGoals = 0.0
    private var leagueAwayGoals = 0.0
    private var leagueHomePoints = 0.0
    private var leagueHomeWins = 0.0
    private var leagueAwayWins = 0.0
    private var leagueDraws = 0.0
    private var sourceUpdate: String? = null
    private var historyRevision = "empty"

    fun advanceTo(date: LocalDate) {
        val priorDate = currentDate
        if (priorDate != null && date.isBefore(priorDate)) {
            error("Forecast dates must be processed chronologically.")
        }
        if (priorDate != null && date != priorDate) {
            val elapsedDays = ChronoUnit.DAYS.between(priorDate, date).coerceAtLeast(0)
            val decay = exp(-ln(2.0) * elapsedDays / parameters.halfLifeDays.coerceAtLeast(1.0))
            teams.values.forEach { it.decay(decay) }
            leagueMatchWeight *= decay
            leagueHomeGoals *= decay
            leagueAwayGoals *= decay
            leagueHomePoints *= decay
            leagueHomeWins *= decay
            leagueAwayWins *= decay
            leagueDraws *= decay
        }
        currentDate = date
    }

    fun forecast(fixture: FixtureEntity): LocalForecastResult {
        val date = LocalDate.parse(fixture.matchDate)
        check(lastCompletedMatchDate != date) {
            "All matches on a date must be forecast before that date's results are added."
        }
        advanceTo(date)
        val homeId = fixture.homeTeamId ?: error("Home team is missing.")
        val awayId = fixture.awayTeamId ?: error("Away team is missing.")
        val home = teams[homeId] ?: TeamForecastState()
        val away = teams[awayId] ?: TeamForecastState()
        val leagueHomeAverage = (
            leagueHomeGoals + DEFAULT_HOME_GOALS * LEAGUE_PRIOR_WEIGHT
            ) / (leagueMatchWeight + LEAGUE_PRIOR_WEIGHT)
        val leagueAwayAverage = (
            leagueAwayGoals + DEFAULT_AWAY_GOALS * LEAGUE_PRIOR_WEIGHT
            ) / (leagueMatchWeight + LEAGUE_PRIOR_WEIGHT)
        val homeAttack = relativeRate(
            home.homeGoalsFor,
            home.homeWeight,
            leagueHomeAverage,
        )
        val homeDefence = relativeRate(
            home.homeGoalsAgainst,
            home.homeWeight,
            leagueAwayAverage,
        )
        val awayAttack = relativeRate(
            away.awayGoalsFor,
            away.awayWeight,
            leagueAwayAverage,
        )
        val awayDefence = relativeRate(
            away.awayGoalsAgainst,
            away.awayWeight,
            leagueHomeAverage,
        )
        val expectedHome = (
            leagueHomeAverage * homeAttack * awayDefence
            ).coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val expectedAway = (
            leagueAwayAverage * awayAttack * homeDefence
            ).coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val homeAdvantage = estimatedHomeAdvantage()
        val drawProbability = (
            (leagueDraws + DEFAULT_DRAW_RATE * LEAGUE_PRIOR_WEIGHT) /
                (leagueMatchWeight + LEAGUE_PRIOR_WEIGHT)
            ).coerceIn(MIN_DRAW_RATE, MAX_DRAW_RATE)
        val homeRating = home.rating
        val awayRating = away.rating
        val eloExpectedHome = eloExpected(homeRating, awayRating, homeAdvantage)
        val eloWinProbability = (eloExpectedHome - drawProbability / 2.0)
            .coerceIn(0.0, 1.0 - drawProbability)
        val eloProbabilities = doubleArrayOf(
            eloWinProbability,
            drawProbability,
            1.0 - drawProbability - eloWinProbability,
        )
        val empirical = doubleArrayOf(
            (leagueHomeWins + DEFAULT_HOME_WIN_RATE * LEAGUE_PRIOR_WEIGHT).coerceAtLeast(0.0),
            (leagueDraws + DEFAULT_DRAW_RATE * LEAGUE_PRIOR_WEIGHT).coerceAtLeast(0.0),
            (leagueAwayWins + DEFAULT_AWAY_WIN_RATE * LEAGUE_PRIOR_WEIGHT).coerceAtLeast(0.0),
        ).let(::normalize)
        val independent = scoreDistribution(expectedHome, expectedAway, rho = 0.0)
        val corrected = scoreDistribution(expectedHome, expectedAway, parameters.dixonColesRho)
        val independentHomeGoals = expectedGoals(independent, home = true)
        val independentAwayGoals = expectedGoals(independent, home = false)
        val correctedHomeGoals = expectedGoals(corrected, home = true)
        val correctedAwayGoals = expectedGoals(corrected, home = false)
        val legacyHome = legacyGoals(home, homeAttack = true, default = 1.35)
        val legacyHomeAgainst = legacyGoals(home, homeAttack = false, default = 1.35)
        val legacyAway = legacyGoals(away, homeAttack = true, default = 1.15)
        val legacyAwayAgainst = legacyGoals(away, homeAttack = false, default = 1.15)
        val legacyExpectedHome = (
            (legacyHome + legacyAwayAgainst) / 2.0 * 1.1
            ).coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val legacyExpectedAway = (
            (legacyAway + legacyHomeAgainst) / 2.0 / 1.1
            ).coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val legacyProbabilities = probabilitiesFromDistribution(
            scoreDistribution(legacyExpectedHome, legacyExpectedAway, 0.0),
        )
        val latestScore = corrected.maxBy { it.probability }
        val homeWin = corrected.filter { it.homeGoals > it.awayGoals }.sumOf { it.probability }
        val draw = corrected.filter { it.homeGoals == it.awayGoals }.sumOf { it.probability }
        val awayWin = corrected.filter { it.homeGoals < it.awayGoals }.sumOf { it.probability }
        val homeCount = home.matchCount
        val awayCount = away.matchCount
        val minCount = minOf(homeCount, awayCount)
        val confidence = when {
            minCount < MINIMUM_HISTORY_MATCHES -> "Sehr begrenzte Datenbasis"
            minCount < 10 -> "Begrenzte Datenbasis"
            else -> "Historische Datenbasis: mindestens 10 Spiele je Team"
        }
        val restDaysHome = home.lastMatchDate?.let { ChronoUnit.DAYS.between(it, date) }
        val restDaysAway = away.lastMatchDate?.let { ChronoUnit.DAYS.between(it, date) }
        val analysis = PredictionAnalysis(
            scoreDistribution = corrected,
            expectedTotalGoals = correctedHomeGoals + correctedAwayGoals,
            mostLikelyScore = "${latestScore.homeGoals}:${latestScore.awayGoals}",
            over15Probability = corrected.filter { it.homeGoals + it.awayGoals > 1 }.sumOf { it.probability },
            over25Probability = corrected.filter { it.homeGoals + it.awayGoals > 2 }.sumOf { it.probability },
            over35Probability = corrected.filter { it.homeGoals + it.awayGoals > 3 }.sumOf { it.probability },
            bothTeamsToScoreProbability = corrected
                .filter { it.homeGoals > 0 && it.awayGoals > 0 }
                .sumOf { it.probability },
            homeOrDrawProbability = homeWin + draw,
            drawOrAwayProbability = draw + awayWin,
            eitherTeamToWinProbability = homeWin + awayWin,
            homeHistoryCount = homeCount,
            awayHistoryCount = awayCount,
            homeElo = homeRating,
            awayElo = awayRating,
            estimatedHomeAdvantageElo = homeAdvantage,
            halfLifeDays = parameters.halfLifeDays,
            dixonColesRho = parameters.dixonColesRho,
            homeRestDays = restDaysHome,
            awayRestDays = restDaysAway,
            confidenceLabel = confidence,
            latestSourceUpdate = sourceUpdate,
            explanation = MODEL_EXPLANATION,
        )
        return LocalForecastResult(
            analysis = analysis,
            existingModel = ModelPrediction(
                legacyProbabilities,
                legacyExpectedHome,
                legacyExpectedAway,
            ),
            empiricalBaseline = ModelPrediction(
                empirical,
                leagueHomeAverage,
                leagueAwayAverage,
            ),
            eloModel = ModelPrediction(
                eloProbabilities,
                expectedHome,
                expectedAway,
            ),
            poissonModel = ModelPrediction(
                probabilitiesFromDistribution(independent),
                independentHomeGoals,
                independentAwayGoals,
            ),
            dixonColesModel = ModelPrediction(
                doubleArrayOf(homeWin, draw, awayWin),
                correctedHomeGoals,
                correctedAwayGoals,
            ),
            inputFingerprint = fingerprint(fixture),
        )
    }

    fun addCompletedMatch(fixture: FixtureEntity) {
        if (
            !fixture.fullTimeResult ||
            fixture.homeScore == null ||
            fixture.awayScore == null ||
            fixture.homeTeamId == null ||
            fixture.awayTeamId == null
        ) {
            return
        }
        val date = LocalDate.parse(fixture.matchDate)
        advanceTo(date)
        if (seasonId != null && seasonId != fixture.seasonId) {
            teams.values.forEach {
                it.rating = TeamForecastState.INITIAL_ELO +
                    (it.rating - TeamForecastState.INITIAL_ELO) * SEASON_RATING_RETENTION
            }
        }
        seasonId = fixture.seasonId
        val homeId = fixture.homeTeamId
        val awayId = fixture.awayTeamId
        val home = teams.getOrPut(homeId) { TeamForecastState() }
        val away = teams.getOrPut(awayId) { TeamForecastState() }
        val homeGoals = fixture.homeScore
        val awayGoals = fixture.awayScore
        val weight = 1.0
        home.homeWeight += weight
        home.homeGoalsFor += homeGoals * weight
        home.homeGoalsAgainst += awayGoals * weight
        away.awayWeight += weight
        away.awayGoalsFor += awayGoals * weight
        away.awayGoalsAgainst += homeGoals * weight
        home.matchCount += 1
        away.matchCount += 1
        home.lastMatchDate = date
        away.lastMatchDate = date
        home.recent.addLast(RecentTeamMatch(date, homeGoals, awayGoals))
        away.recent.addLast(RecentTeamMatch(date, awayGoals, homeGoals))
        while (home.recent.size > RECENT_MATCH_LIMIT) home.recent.removeFirst()
        while (away.recent.size > RECENT_MATCH_LIMIT) away.recent.removeFirst()

        leagueMatchWeight += weight
        leagueHomeGoals += homeGoals * weight
        leagueAwayGoals += awayGoals * weight
        val actualHomePoints = when {
            homeGoals > awayGoals -> 1.0
            homeGoals == awayGoals -> 0.5
            else -> 0.0
        }
        leagueHomePoints += actualHomePoints * weight
        when {
            homeGoals > awayGoals -> leagueHomeWins += weight
            homeGoals == awayGoals -> leagueDraws += weight
            else -> leagueAwayWins += weight
        }

        val homeExpected = eloExpected(home.rating, away.rating, estimatedHomeAdvantage())
        val ratingChange = ELO_K_FACTOR * (actualHomePoints - homeExpected)
        home.rating += ratingChange
        away.rating -= ratingChange
        sourceUpdate = maxOf(sourceUpdate.orEmpty(), fixture.updatedAt).ifEmpty { null }
        historyRevision = hash(
            "$historyRevision|${fixture.providerFixtureId}|${fixture.competitionCode}|" +
                "${fixture.seasonId}|${fixture.matchDate}|${fixture.homeTeamId}-${fixture.awayTeamId}|" +
                "${fixture.homeScore}-${fixture.awayScore}",
        )
        lastCompletedMatchDate = date
    }

    fun hasMinimumHistory(fixture: FixtureEntity): Boolean {
        val homeId = fixture.homeTeamId ?: return false
        val awayId = fixture.awayTeamId ?: return false
        return (teams[homeId]?.matchCount ?: 0) >= MINIMUM_HISTORY_MATCHES &&
            (teams[awayId]?.matchCount ?: 0) >= MINIMUM_HISTORY_MATCHES
    }

    private fun estimatedHomeAdvantage(): Double {
        val priorPoints = DEFAULT_HOME_POINTS * LEAGUE_PRIOR_WEIGHT
        val rate = (
            (leagueHomePoints + priorPoints) /
                (leagueMatchWeight + LEAGUE_PRIOR_WEIGHT)
            ).coerceIn(0.35, 0.75)
        return (400.0 * log10(rate / (1.0 - rate))).coerceIn(-75.0, 100.0)
    }

    private fun relativeRate(goals: Double, matches: Double, leagueAverage: Double): Double {
        val average = (
            (goals + leagueAverage * TEAM_PRIOR_WEIGHT) /
                (matches + TEAM_PRIOR_WEIGHT)
            )
        return (average / leagueAverage.coerceAtLeast(0.1)).coerceIn(0.35, 2.5)
    }

    private fun legacyGoals(state: TeamForecastState, homeAttack: Boolean, default: Double): Double {
        if (state.recent.isEmpty()) return default
        return state.recent.map { match ->
            if (homeAttack) match.goalsFor else match.goalsAgainst
        }.average()
    }

    private fun scoreDistribution(
        homeRate: Double,
        awayRate: Double,
        rho: Double,
    ): List<ScorelineProbability> {
        val home = poisson(homeRate)
        val away = poisson(awayRate)
        val scores = mutableListOf<ScorelineProbability>()
        for (homeGoals in home.indices) {
            for (awayGoals in away.indices) {
                val correction = when {
                    homeGoals == 0 && awayGoals == 0 -> 1.0 - homeRate * awayRate * rho
                    homeGoals == 0 && awayGoals == 1 -> 1.0 + homeRate * rho
                    homeGoals == 1 && awayGoals == 0 -> 1.0 + awayRate * rho
                    homeGoals == 1 && awayGoals == 1 -> 1.0 - rho
                    else -> 1.0
                }.coerceAtLeast(0.0)
                scores += ScorelineProbability(
                    homeGoals = homeGoals,
                    awayGoals = awayGoals,
                    probability = home[homeGoals] * away[awayGoals] * correction,
                )
            }
        }
        val total = scores.sumOf { it.probability }.coerceAtLeast(1e-15)
        return scores.map { it.copy(probability = it.probability / total) }
    }

    private fun poisson(rate: Double): DoubleArray {
        val safeRate = rate.coerceIn(MIN_EXPECTED_GOALS, MAX_EXPECTED_GOALS)
        val result = DoubleArray(MAX_GOALS + 1)
        result[0] = exp(-safeRate)
        for (goals in 1..MAX_GOALS) result[goals] = result[goals - 1] * safeRate / goals
        val total = result.sum().coerceAtLeast(1e-15)
        return DoubleArray(result.size) { result[it] / total }
    }

    private fun probabilitiesFromDistribution(scores: List<ScorelineProbability>): DoubleArray {
        val result = DoubleArray(3)
        scores.forEach { score ->
            val index = when {
                score.homeGoals > score.awayGoals -> 0
                score.homeGoals == score.awayGoals -> 1
                else -> 2
            }
            result[index] += score.probability
        }
        return normalize(result)
    }

    private fun expectedGoals(scores: List<ScorelineProbability>, home: Boolean): Double =
        scores.sumOf { score ->
            (if (home) score.homeGoals else score.awayGoals) * score.probability
        }

    private fun eloExpected(homeRating: Double, awayRating: Double, homeAdvantage: Double): Double =
        1.0 / (1.0 + 10.0.pow(-(homeRating - awayRating + homeAdvantage) / 400.0))

    private fun normalize(values: DoubleArray): DoubleArray {
        val sum = values.sum().coerceAtLeast(1e-15)
        return DoubleArray(values.size) { values[it] / sum }
    }

    private fun fingerprint(fixture: FixtureEntity): String = hash(
        "$historyRevision|${fixture.providerFixtureId}|${fixture.matchDate}|${fixture.kickoffAt}|" +
            "${fixture.venue}|${fixture.matchday}|${fixture.stage}|${fixture.homeTeamId}-${fixture.awayTeamId}|" +
            "${parameters.halfLifeDays}|${parameters.dixonColesRho}",
    )

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL_VERSION = "dixon-coles-time-decay-local-v3"
        const val MINIMUM_HISTORY_MATCHES = 3
        private const val DEFAULT_HOME_GOALS = 1.45
        private const val DEFAULT_AWAY_GOALS = 1.15
        private const val DEFAULT_HOME_POINTS = 0.55
        private const val DEFAULT_HOME_WIN_RATE = 0.45
        private const val DEFAULT_DRAW_RATE = 0.26
        private const val DEFAULT_AWAY_WIN_RATE = 0.29
        private const val LEAGUE_PRIOR_WEIGHT = 10.0
        private const val TEAM_PRIOR_WEIGHT = 8.0
        private const val MIN_DRAW_RATE = 0.10
        private const val MAX_DRAW_RATE = 0.45
        private const val MIN_EXPECTED_GOALS = 0.1
        private const val MAX_EXPECTED_GOALS = 5.0
        private const val MAX_GOALS = 10
        private const val ELO_K_FACTOR = 20.0
        private const val SEASON_RATING_RETENTION = 0.80
        private const val RECENT_MATCH_LIMIT = 10
        private const val MODEL_EXPLANATION =
            "Zeitgewichtetes Heim-/Auswärts-Poisson-Modell mit gegnerischer Defensivstärke, " +
                "Ligamittelwert-Shrinkage und datenbasiertem Elo-Heimvorteil. " +
                "Dixon-Coles-Korrektur für niedrige Ergebnisse."
    }
}
