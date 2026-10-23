package com.sportpredi

import com.google.gson.JsonParser
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiFootballSyncFailureTest {
    @Test
    fun providerErrorsKeepActionableDetailsAndRedactSensitiveFields() {
        val errors = JsonParser.parseString(
            """{"requests":"Daily request limit reached","api_key":"not-a-real-key"}""",
        )
        val message = apiFootballProviderErrorMessage(errors).orEmpty()
        val surfaced = syncFailureMessage(ApiFootballProviderException(message))

        assertTrue(surfaced.contains("Anfragelimit erreicht"))
        assertTrue(surfaced.contains("Daily request limit reached"))
        assertTrue(surfaced.contains("[redacted]"))
        assertFalse(surfaced.contains("not-a-real-key"))
    }

    @Test
    fun httpFailuresIncludeStatusAndProviderErrorDetailsWithoutSecrets() {
        val body = ResponseBody.create(
            MediaType.parse("application/json"),
            """{"errors":{"plan":"This endpoint is not available","token":"not-a-real-token"}}""",
        )
        val exception = HttpException(Response.error<Any>(403, body))

        val message = syncFailureMessage(exception)

        assertTrue(message.contains("HTTP 403"))
        assertTrue(message.contains("This endpoint is not available"))
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("not-a-real-token"))
    }

    @Test
    fun genericIllegalStateMessageIsPreservedButLongTokensAreRedacted() {
        val message = syncFailureMessage(
            IllegalStateException(
                """Invalid response near abcdef0123456789abcdef0123456789 {"api_key":"not-a-real-key"}""",
            ),
        )

        assertTrue(message.contains("Invalid response"))
        assertTrue(message.contains("[redacted]"))
        assertFalse(message.contains("abcdef0123456789"))
        assertFalse(message.contains("not-a-real-key"))
    }
}
