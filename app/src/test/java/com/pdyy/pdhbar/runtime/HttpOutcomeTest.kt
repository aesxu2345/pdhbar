package com.pdyy.pdhbar.runtime

import org.junit.Assert.*
import org.junit.Test

class HttpOutcomeTest {
    @Test fun orderNotFoundIsTerminalNotQueueRetry() {
        assertTrue(isTerminalHttpOutcome(200, 400))
        assertTrue(isTerminalHttpOutcome(404, null))
    }

    @Test fun successfulResponseCompletes() {
        assertTrue(isTerminalHttpOutcome(200, 200))
        assertTrue(isTerminalHttpOutcome(204, null))
    }

    @Test fun serverAndTransportFailuresRemainRetryable() {
        assertFalse(isTerminalHttpOutcome(503, null))
        assertFalse(isTerminalHttpOutcome(0, null))
    }
}