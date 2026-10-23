package com.sportpredi

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class OpenLigaLeagueDto(
    @SerializedName("leagueId") val leagueId: Int,
    @SerializedName("leagueName") val leagueName: String,
    @SerializedName("leagueShortcut") val leagueShortcut: String,
    @SerializedName("leagueSeason") val leagueSeason: Int,
    val sport: OpenLigaSportDto?,
)

data class OpenLigaSportDto(
    @SerializedName("sportName") val sportName: String?,
)

data class OpenLigaMatchDto(
    @SerializedName("matchID") val matchId: Int,
    @SerializedName("matchDateTimeUTC") val matchDateTimeUtc: String?,
    @SerializedName("matchDateTime") val matchDateTime: String?,
    @SerializedName("matchIsFinished") val matchIsFinished: Boolean,
    @SerializedName("matchResults") val matchResults: List<OpenLigaResultDto>?,
    @SerializedName("team1") val team1: OpenLigaTeamDto?,
    @SerializedName("team2") val team2: OpenLigaTeamDto?,
    @SerializedName("group") val group: OpenLigaGroupDto?,
    @SerializedName("location") val location: OpenLigaLocationDto?,
)

data class OpenLigaResultDto(
    @SerializedName("resultTypeKind") val resultTypeKind: String?,
    @SerializedName("resultOrderID") val resultOrderId: Int?,
    @SerializedName("pointsTeam1") val pointsTeam1: Int?,
    @SerializedName("pointsTeam2") val pointsTeam2: Int?,
)

data class OpenLigaTeamDto(
    @SerializedName("teamId") val teamId: Int?,
    @SerializedName("teamName") val teamName: String?,
)

data class OpenLigaGroupDto(
    @SerializedName("groupOrderID") val groupOrderId: Int?,
    @SerializedName("groupName") val groupName: String?,
)

data class OpenLigaLocationDto(
    @SerializedName("locationName") val locationName: String?,
)

interface OpenLigaDbApi {
    @GET("getavailableleagues")
    suspend fun availableLeagues(): List<OpenLigaLeagueDto>

    @GET("getlastchangedate/{leagueShortcut}/{leagueSeason}/0")
    suspend fun lastChanged(
        @Path("leagueShortcut") shortcut: String,
        @Path("leagueSeason") season: Int,
    ): String?

    @GET("getmatchdata/{leagueShortcut}/{leagueSeason}")
    suspend fun matches(
        @Path("leagueShortcut") shortcut: String,
        @Path("leagueSeason") season: Int,
    ): List<OpenLigaMatchDto>

    companion object {
        fun create(): OpenLigaDbApi =
            Retrofit.Builder()
                .baseUrl("https://api.openligadb.de/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenLigaDbApi::class.java)
    }
}
