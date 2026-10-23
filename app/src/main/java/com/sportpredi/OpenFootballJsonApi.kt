package com.sportpredi

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

data class GitHubTreeDto(
    val sha: String,
    val truncated: Boolean,
    val tree: List<GitHubTreeEntryDto>,
)

data class GitHubTreeEntryDto(
    val path: String,
    val type: String,
    val sha: String,
    val size: Long?,
)

data class OpenFootballFileDto(
    val name: String?,
    val matches: List<OpenFootballMatchDto>?,
)

data class OpenFootballMatchDto(
    val round: String?,
    val date: String?,
    val time: String?,
    val team1: String?,
    val team2: String?,
    val score: JsonElement?,
    val ground: String?,
)

internal fun OpenFootballMatchDto.scoreArray(kind: String): List<Int>? {
    val scoreValue = score ?: return null
    val values: JsonArray = when {
        scoreValue.isJsonArray && kind == "ft" -> scoreValue.asJsonArray
        scoreValue.isJsonObject -> scoreValue.asJsonObject.get(kind)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        else -> return null
    }
    if (values.size() < 2) return null
    val goals = (0..1).map { index ->
        val value: JsonElement = values[index]
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        value.asInt
    }
    return goals.takeIf { it.all { value -> value >= 0 } }
}

interface OpenFootballJsonApi {
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
    )
    @GET("repos/openfootball/football.json/git/trees/master")
    suspend fun tree(@Query("recursive") recursive: Int = 1): GitHubTreeDto

    @Streaming
    @GET("{path}")
    suspend fun file(@Path(value = "path", encoded = true) path: String): ResponseBody

    companion object {
        fun create(): OpenFootballJsonApi {
            val api = Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenFootballJsonApi::class.java)
            val raw = Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/openfootball/football.json/master/")
                .build()
                .create(OpenFootballJsonApi::class.java)
            return object : OpenFootballJsonApi {
                override suspend fun tree(recursive: Int): GitHubTreeDto = api.tree(recursive)

                override suspend fun file(path: String): ResponseBody = raw.file(path)
            }
        }
    }
}
