package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRefillAbaTest {
    @Test
    fun sameIndexWithNewGenerationRejectsStaleWorker() {
        val staleWorker = CacheRefillWorkIdentity(cacheIndex = 7L, generation = 11L, leaseId = 3L)

        assertFalse(cacheRefillIdentityMatches(staleWorker, 7L, 12L, 3L))
    }

    @Test
    fun sameIndexAndGenerationWithNewLeaseRejectsStaleWorker() {
        val staleWorker = CacheRefillWorkIdentity(cacheIndex = 7L, generation = 11L, leaseId = 3L)

        assertFalse(cacheRefillIdentityMatches(staleWorker, 7L, 11L, 4L))
    }

    @Test
    fun exactGenerationAndLeaseMayCommit() {
        val worker = CacheRefillWorkIdentity(cacheIndex = 7L, generation = 11L, leaseId = 3L)

        assertTrue(cacheRefillIdentityMatches(worker, 7L, 11L, 3L))
    }
}
