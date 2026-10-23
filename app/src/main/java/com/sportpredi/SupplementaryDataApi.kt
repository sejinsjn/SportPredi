package com.sportpredi

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

data class OddsApiSportDto(
    val key: String,
    val group: String,
    val title: String,
    val active: Boolean,
)

data class OddsApiEventDto(
    val id: String,
    @SerializedName("sport_key") val sportKey: String,
    @SerializedName("commence_time") val commenceTime: String,
    @SerializedName("home_team") val homeTeam: String,
    @SerializedName("away_team") val awayTeam: String,
    val bookmakers: List<OddsApiBookmakerDto>?,
)

data class OddsApiBookmakerDto(
    val key: String,
    val title: String,
    val markets: List<OddsApiMarketDto>?,
)

data class OddsApiMarketDto(
    val key: String,
    val outcomes: List<OddsApiOutcomeDto>?,
)

data class OddsApiOutcomeDto(
    val name: String,
    val price: Double,
    val point: Double?,
)

data class ApiFootballFixtureResponse(
    val errors: JsonElement?,
    val response: List<ApiFootballFixtureDto>?,
)

data class ApiFootballFixtureDto(
    val fixture: ApiFootballFixtureInfoDto,
    val league: ApiFootballLeagueDto,
    val teams: ApiFootballTeamsDto,
    val goals: ApiFootballGoalsDto?,
)

data class ApiFootballFixtureInfoDto(
    val id: Int,
    val date: String,
    val status: ApiFootballStatusDto?,
)

data class ApiFootballStatusDto(
    val short: String?,
)

data class ApiFootballLeagueDto(
    val id: Int,
    val name: String,
    val country: String?,
    val season: Int?,
)

data class ApiFootballTeamsDto(
    val home: ApiFootballTeamDto?,
    val away: ApiFootballTeamDto?,
)

data class ApiFootballTeamDto(
    val id: Int?,
    val name: String?,
)

data class ApiFootballGoalsDto(
    val home: Int?,
    val away: Int?,
)

data class ApiFootballStatisticsResponse(
    val errors: JsonElement?,
    val response: List<ApiFootballTeamStatisticsDto>?,
)

data class ApiFootballTeamStatisticsDto(
    val team: ApiFootballTeamDto?,
    val statistics: List<ApiFootballStatisticDto>?,
)

data class ApiFootballStatisticDto(
    val type: String?,
    val value: JsonElement?,
)

data class ApiFootballLineupResponse(
    val errors: JsonElement?,
    val response: List<JsonElement>?,
)

data class ApiFootballInjuriesResponse(
    val errors: JsonElement?,
    val response: List<JsonElement>?,
)

interface TheOddsApi {
    @GET("sports")
    suspend fun sports(@Query("apiKey") apiKey: String): List<OddsApiSportDto>

    @GET("sports/{sportKey}/odds")
    suspend fun odds(
        @Path("sportKey") sportKey: String,
        @Query("apiKey") apiKey: String,
        @Query("regions") regions: String = "eu",
        @Query("markets") markets: String = "h2h,totals",
        @Query("oddsFormat") oddsFormat: String = "decimal",
    ): List<OddsApiEventDto>

    companion object {
        fun create(): TheOddsApi =
            Retrofit.Builder()
                .baseUrl("https://api.the-odds-api.com/v4/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TheOddsApi::class.java)
    }
}

interface ApiFootballApi {
    @GET("fixtures")
    suspend fun fixtures(
        @Header("x-apisports-key") apiKey: String,
        @Query("date") date: String,
    ): ApiFootballFixtureResponse

    @GET("fixtures/statistics")
    suspend fun statistics(
        @Header("x-apisports-key") apiKey: String,
        @Query("fixture") fixtureId: Int,
    ): ApiFootballStatisticsResponse

    @GET("fixtures/lineups")
    suspend fun lineups(
        @Header("x-apisports-key") apiKey: String,
        @Query("fixture") fixtureId: Int,
    ): ApiFootballLineupResponse

    @GET("injuries")
    suspend fun injuries(
        @Header("x-apisports-key") apiKey: String,
        @Query("fixture") fixtureId: Int,
    ): ApiFootballInjuriesResponse

    companion object {
        fun create(): ApiFootballApi =
            Retrofit.Builder()
                .baseUrl("https://v3.football.api-sports.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiFootballApi::class.java)
    }
}
