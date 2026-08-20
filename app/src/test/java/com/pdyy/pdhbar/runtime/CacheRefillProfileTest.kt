package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheRefillProfileTest {
    @Test
    fun lowRamDeviceUsesSmallestBoundedQueue() {
        assertEquals(CacheRefillProfile(64, 1), cacheRefillProfile(512, true, 8))
    }

    @Test
    fun memoryClassControlsBufferAndCapsWorkers() {
        assertEquals(CacheRefillProfile(128, 1), cacheRefillProfile(256, false, 8))
        assertEquals(CacheRefillProfile(256, 1), cacheRefillProfile(512, false, 1))
        assertEquals(CacheRefillProfile(512, 2), cacheRefillProfile(1024, false, 8))
    }
}
