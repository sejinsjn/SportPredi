package com.sportpredi

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.time.LocalDate

data class ApiFootballResponse<T>(
    val errors: JsonElement? = null,
    val response: List<T>? = null,
)

data class ApiFootballStatusEnvelope(
    val errors: JsonElement? = null,
    val response: ApiFootballStatusData? = null,
)

data class ApiFootballStatusData(val requests: ApiFootballRequestUsageDto?)

data class ApiFootballRequestUsageDto(
    val current: Int?,
    @SerializedName("limit_day") val limitDay: Int?,
)

data class ApiFootballLeagueDto(
    val league: ApiFootballLeagueInfoDto,
    val country: ApiFootballCountryDto?,
    val seasons: List<ApiFootballSeasonDto>?,
)

data class ApiFootballLeagueInfoDto(
    val id: Int,
    val name: String,
    val type: String?,
)

data class ApiFootballCountryDto(val name: String?)

data class ApiFootballSeasonDto(val year: Int, val current: Boolean?)

data class ApiFootballFixtureLeagueDto(
    val id: Int,
    val name: String,
    val country: String?,
    val season: Int?,
)

data class ApiFootballFixtureDto(
    val fixture: ApiFootballFixtureInfoDto,
    val league: ApiFootballFixtureLeagueDto,
    val teams: ApiFootballTeamsDto,
    val goals: ApiFootballGoalsDto?,
    val score: ApiFootballScoreDto?,
)

data class ApiFootballStandingsResponseDto(val league: ApiFootballStandingsLeagueDto?)

data class ApiFootballStandingsLeagueDto(
    val id: Int?,
    val season: Int?,
    val standings: List<List<ApiFootballStandingDto>>?,
)

data class ApiFootballStandingDto(
    val rank: Int?,
    val team: ApiFootballTeamDto?,
    val points: Int?,
    val goalsDiff: Int?,
    val group: String?,
    val form: String?,
    val all: ApiFootballStandingStatsDto?,
)

data class ApiFootballStandingStatsDto(
    val played: Int?,
    val win: Int?,
    val draw: Int?,
    val lose: Int?,
    val goals: ApiFootballStandingGoalsDto?,
)

data class ApiFootballStandingGoalsDto(
    @SerializedName("for") val goalsFor: Int?,
    @SerializedName("against") val goalsAgainst: Int?,
)

data class ApiFootballStandingRow(
    val teamId: Int,
    val teamName: String,
    val rank: Int?,
    val points: Int?,
    val played: Int?,
    val wins: Int?,
    val draws: Int?,
    val losses: Int?,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    val form: String?,
    val group: String?,
)

data class ApiFootballOddsResponseDto(
    val fixture: ApiFootballOddsFixtureDto?,
    val bookmakers: List<ApiFootballOddsBookmakerDto>?,
)

data class ApiFootballOddsFixtureDto(val id: Int?)

data class ApiFootballOddsBookmakerDto(
    val id: Int?,
    val name: String?,
    val bets: List<ApiFootballOddsBetDto>?,
)

data class ApiFootballOddsBetDto(
    val id: Int?,
    val name: String?,
    val values: List<ApiFootballOddsValueDto>?,
)

data class ApiFootballOddsValueDto(
    val value: String?,
    val odd: String?,
)

internal fun apiFootballStandingsQueryParameters(
    leagueId: Int,
    seasonYear: Int,
): Map<String, String> {
    require(leagueId > 0)
    require(seasonYear > 0)
    return linkedMapOf("league" to leagueId.toString(), "season" to seasonYear.toString())
}

internal fun normalizeApiFootballStandings(
    response: List<ApiFootballStandingsResponseDto>,
    leagueId: Int,
    seasonYear: Int,
): List<ApiFootballStandingRow> =
    response.asSequence()
        .mapNotNull { it.league }
        .filter { it.id == leagueId && it.season == seasonYear }
        .flatMap { league ->
            league.standings.orEmpty().asSequence().flatMap { group ->
                group.asSequence().mapNotNull { standing ->
                    val teamId = standing.team?.id ?: return@mapNotNull null
                    val teamName = standing.team.name?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    ApiFootballStandingRow(
                        teamId = teamId,
                        teamName = teamName,
                        rank = standing.rank,
                        points = standing.points,
                        played = standing.all?.played,
                        wins = standing.all?.win,
                        draws = standing.all?.draw,
                        losses = standing.all?.lose,
                        goalsFor = standing.all?.goals?.goalsFor,
                        goalsAgainst = standing.all?.goals?.goalsAgainst,
                        form = standing.form?.filter { it.uppercaseChar() in "WDL" }
                            ?.takeLast(5)
                            ?.takeIf(String::isNotEmpty),
                        group = standing.group?.takeIf(String::isNotBlank),
                    )
                }
            }
        }
        .distinctBy { it.teamId }
        .sortedWith(compareBy<ApiFootballStandingRow> { it.group.orEmpty() }.thenBy { it.rank ?: Int.MAX_VALUE })
        .toList()

internal fun apiFootballFormLabel(form: String?): String? =
    form.orEmpty().map { result ->
        when (result.uppercaseChar()) {
            'W' -> "S"
            'D' -> "U"
            'L' -> "N"
            else -> return@map null
        }
    }.filterNotNull().takeLast(5).takeIf { it.isNotEmpty() }?.joinToString("  ")

data class ApiFootballFixtureInfoDto(
    val id: Int,
    val date: String,
    val status: ApiFootballStatusDto?,
    val venue: ApiFootballVenueDto? = null,
)

data class ApiFootballStatusDto(val short: String?)
data class ApiFootballVenueDto(val name: String?)
data class ApiFootballScoreDto(val halftime: ApiFootballGoalsDto?)
data class ApiFootballGoalsDto(val home: Int?, val away: Int?)

data class ApiFootballTeamsDto(val home: ApiFootballTeamDto?, val away: ApiFootballTeamDto?)
data class ApiFootballTeamDto(val id: Int?, val name: String?)

internal fun apiFootballFixtureQueryParameters(
    leagueId: Int,
    seasonYear: Int,
    from: String? = null,
    to: String? = null,
): Map<String, String> {
    require(leagueId > 0)
    require((from == null) == (to == null)) { "Date range requires both from and to." }
    return linkedMapOf(
        "league" to leagueId.toString(),
        "season" to seasonYear.toString(),
    ).apply {
        if (from != null && to != null) {
            put("from", from)
            put("to", to)
        }
    }
}

internal fun apiFootballHeadToHeadQueryParameters(
    homeTeamId: Int,
    awayTeamId: Int,
    last: Int = 10,
): Map<String, String> {
    require(homeTeamId > 0 && awayTeamId > 0 && homeTeamId != awayTeamId)
    require(last > 0)
    return linkedMapOf(
        "h2h" to "$homeTeamId-$awayTeamId",
        "last" to last.toString(),
    )
}

internal fun apiFootballCurrentFixtureQueryParameters(
    leagueId: Int,
    seasonYear: Int,
    today: LocalDate,
    historyDays: Long,
    upcomingDays: Long,
    fullSeasonBackfill: Boolean,
): Map<String, String> =
    if (fullSeasonBackfill) {
        apiFootballFixtureQueryParameters(leagueId, seasonYear)
    } else {
        apiFootballFixtureQueryParameters(
            leagueId = leagueId,
            seasonYear = seasonYear,
            from = today.minusDays(historyDays).toString(),
            to = today.plusDays(upcomingDays).toString(),
        )
    }

interface ApiFootballApi {
    @GET("status")
    suspend fun status(
        @Header("x-apisports-key") apiKey: String,
    ): Response<ApiFootballStatusEnvelope>

    @GET("leagues")
    suspend fun leagues(
        @Header("x-apisports-key") apiKey: String,
        @Query("current") current: String = "true",
    ): Response<ApiFootballResponse<ApiFootballLeagueDto>>

    @GET("fixtures")
    suspend fun fixtures(
        @Header("x-apisports-key") apiKey: String,
        @QueryMap query: Map<String, String>,
    ): Response<ApiFootballResponse<ApiFootballFixtureDto>>

    @GET("fixtures/headtohead")
    suspend fun headToHead(
        @Header("x-apisports-key") apiKey: String,
        @QueryMap query: Map<String, String>,
    ): Response<ApiFootballResponse<ApiFootballFixtureDto>>

    @GET("standings")
    suspend fun standings(
        @Header("x-apisports-key") apiKey: String,
        @QueryMap query: Map<String, String>,
    ): Response<ApiFootballResponse<ApiFootballStandingsResponseDto>>

    @GET("odds")
    suspend fun odds(
        @Header("x-apisports-key") apiKey: String,
        @Query("fixture") fixtureId: Long,
    ): Response<ApiFootballResponse<ApiFootballOddsResponseDto>>

    companion object {
        fun create(): ApiFootballApi =
            Retrofit.Builder()
                .baseUrl("https://v3.football.api-sports.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiFootballApi::class.java)
    }
}
