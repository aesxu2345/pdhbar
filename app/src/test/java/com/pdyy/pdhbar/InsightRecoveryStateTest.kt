package com.pdyy.pdhbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightRecoveryStateTest {
    @Test fun permitsTwoAutomaticReopensThenPauses() {
        var state = InsightRecoveryState()
        repeat(2) { index ->
            state = state.restartAutomatically(state.generation, (index + 1) * 1_000L)
            assertEquals((index + 1).toLong(), state.generation)
            assertFalse(state.paused)
        }
        state = state.restartAutomatically(state.generation, 3_000L)
        assertEquals(2L, state.generation)
        assertTrue(state.paused)
    }

    @Test fun duplicateAndOldGenerationRequestsDoNotConsumeBudget() {
        val first = InsightRecoveryState().restartAutomatically(0L, 1_000L)
        assertSame(first, first.restartAutomatically(0L, 2_000L))
        assertSame(first, first.restartAutomatically(99L, 2_000L))
        val second = first.restartAutomatically(1L, 3_000L)
        assertEquals(2L, second.generation)
        assertFalse(second.paused)
    }

    @Test fun occasionalFailuresCanRecoverAfterWindowExpires() {
        var state = InsightRecoveryState().restartAutomatically(0L, 1_000L)
        state = state.restartAutomatically(1L, 2_000L)
        state = state.restartAutomatically(2L, 61_000L)
        assertEquals(3L, state.generation)
        assertFalse(state.paused)
        // Only the oldest attempt expired; the other two still count.
        assertTrue(state.restartAutomatically(3L, 61_001L).paused)
    }

    @Test fun windowExpiryDoesNotAutomaticallyLeavePausedState() {
        val paused = pausedState()
        assertSame(paused, paused.restartAutomatically(paused.generation, 120_000L))
    }

    @Test fun manualReopenAllowsOneNewInstanceWithoutResettingBudget() {
        val paused = pausedState()
        val reopened = paused.restartManually(paused.generation)
        assertEquals(3L, reopened.generation)
        assertFalse(reopened.paused)
        assertSame(reopened, reopened.restartManually(paused.generation))
        assertTrue(reopened.restartAutomatically(reopened.generation, 4_000L).paused)
    }

    @Test fun manualReopenRejectsStaleAndHealthyGenerationRequests() {
        val initial = InsightRecoveryState()
        assertSame(initial, initial.restartManually(initial.generation))
        val paused = pausedState()
        assertSame(paused, paused.restartManually(0L))
    }

    @Test fun manualReopenAfterWindowExpiryCanRecoverFromAnotherFailure() {
        val paused = pausedState()
        val reopened = paused.restartManually(paused.generation)
        val recovered = reopened.restartAutomatically(reopened.generation, 120_000L)
        assertEquals(4L, recovered.generation)
        assertFalse(recovered.paused)
    }

    private fun pausedState(): InsightRecoveryState = InsightRecoveryState()
        .restartAutomatically(0L, 1_000L)
        .restartAutomatically(1L, 2_000L)
        .restartAutomatically(2L, 3_000L)
}
