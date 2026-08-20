package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoredCacheExpiryTest {
    @Test
    fun cacheIsNotExpiredAtExactlyOneDay() {
        val cachedAt = 1_000L

        assertFalse(isIgnoredCacheExpired(cachedAt, cachedAt + IGNORED_CACHE_MAX_AGE_MS))
    }

    @Test
    fun cacheExpiresAfterOneDay() {
        val cachedAt = 1_000L

        assertTrue(isIgnoredCacheExpired(cachedAt, cachedAt + IGNORED_CACHE_MAX_AGE_MS + 1L))
    }

    @Test
    fun futureOrInvalidTimestampDoesNotExpire() {
        assertFalse(isIgnoredCacheExpired(0L, IGNORED_CACHE_MAX_AGE_MS + 1L))
        assertFalse(isIgnoredCacheExpired(10_000L, 9_999L))
    }
}
