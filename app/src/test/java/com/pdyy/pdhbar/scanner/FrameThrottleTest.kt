package com.pdyy.pdhbar.scanner

import org.junit.Assert.*
import org.junit.Test

class FrameThrottleTest {
    @Test fun firstFrameAndExactBoundaryAreAccepted() {
        val throttle = FrameThrottle()
        assertTrue(throttle.accept(0))
        assertFalse(throttle.accept(0))
        assertFalse(throttle.accept(149))
        assertTrue(throttle.accept(150))
        assertTrue(throttle.accept(300))
    }

    @Test fun rejectedFramesDoNotExtendWindow() {
        val throttle = FrameThrottle()
        assertTrue(throttle.accept(1_000))
        for (time in 1_001L..1_149L) assertFalse(throttle.accept(time))
        assertTrue(throttle.accept(1_150))
    }

    @Test fun clockResetDoesNotPermanentlyBlockScanning() {
        val throttle = FrameThrottle()
        assertTrue(throttle.accept(10_000))
        assertTrue(throttle.accept(0))
        assertTrue(throttle.accept(150))
    }
}
