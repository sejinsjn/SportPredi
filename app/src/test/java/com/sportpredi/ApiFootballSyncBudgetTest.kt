package com.sportpredi

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.Headers

class ApiFootballSyncBudgetTest {
    @Test
    fun defaultRequestBudgetUsesTheConfiguredHeadroomAndShortBatchLimit() {
        val defaults = ApiFootballSyncLimits()

        assertEquals(32, defaults.maxRequestsPerSync)
        assertEquals(6_500, defaults.maxRequestsPerUtcDay)
        assertEquals(1_000, defaults.dailyRequestReserve)
        assertEquals(1, defaults.perMinuteRequestReserve)
    }

    @Test
    fun dailyBudgetCanBeConfiguredWithoutIncreasingThePerSyncBatch() {
        val configured = ApiFootballSyncLimits(maxRequestsPerUtcDay = 6_500)

        assertEquals(6_500, configured.maxRequestsPerUtcDay)
        assertEquals(32, configured.maxRequestsPerSync)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dailyBudgetCannotExceedTheStatedSubscriptionQuota() {
        ApiFootballSyncLimits(maxRequestsPerUtcDay = 7_501)
    }

    @Test
    fun requestHeadersProvideDailyAndPerMinuteBudgetsWithConfiguredReserves() {
        val headers = Headers.Builder()
            .add("x-ratelimit-requests-limit", "7500")
            .add("x-ratelimit-requests-remaining", "6400")
            .add("X-RateLimit-Limit", "60")
            .add("X-RateLimit-Remaining", "8")
            .build()

        val limits = parseApiFootballRateLimitHeaders(headers)

        assertEquals(7_500, limits?.dailyLimit)
        assertEquals(6_400, limits?.dailyRemaining)
        assertEquals(60, limits?.perMinuteLimit)
        assertEquals(8, limits?.perMinuteRemaining)
        assertEquals(5_400, safeApiFootballRequestSlots(limits!!.dailyRemaining, 1_000))
        assertEquals(7, safeApiFootballRequestSlots(limits.perMinuteRemaining, 1))
    }

    @Test
    fun missingRateHeadersFailClosed() {
        assertNull(parseApiFootballRateLimitHeaders(Headers.Builder().build()))
    }

    @Test
    fun statusResponseReadsOnlyDailyUsageFields() {
        val status = Gson().fromJson(
            """{"response":{"account":{"email":"ignored@example.invalid"},"subscription":{"plan":"Pro"},"requests":{"current":123,"limit_day":7500}}}""",
            ApiFootballStatusEnvelope::class.java,
        )

        assertEquals(123, status.response?.requests?.current)
        assertEquals(7_500, status.response?.requests?.limitDay)
    }
}
