package com.pdyy.pdhbar

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightMountStateTest {
    @Test fun lengthUpdatesDoNotRecreateBrowser() {
        val state = InsightMountState()
        val owner = Any()
        var mounts = 0
        val updates = mutableListOf<Int>()
        listOf(0, 1, 3000, 3000, 0).forEach { length ->
            state.update(owner, length, { mounts++ }, { updates.add(it); true })
        }
        assertEquals(1, mounts)
        assertEquals(listOf(0, 1, 3000, 0), updates)
    }

    @Test fun newOwnerMountsEvenWhenLengthIsUnchanged() {
        val state = InsightMountState()
        var mounts = 0
        repeat(2) { state.update(Any(), 4, { mounts++ }, { true }) }
        assertEquals(2, mounts)
    }

    @Test fun failedCorrectionCanRetryWithoutRemounting() {
        val state = InsightMountState()
        val owner = Any()
        var mounts = 0
        var updates = 0
        repeat(3) {
            state.update(owner, 4, { mounts++ }, { ++updates > 1 })
        }
        assertEquals(1, mounts)
        assertEquals(2, updates)
    }
}
