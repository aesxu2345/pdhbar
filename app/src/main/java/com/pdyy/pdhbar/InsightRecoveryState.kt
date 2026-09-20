package com.pdyy.pdhbar

/** Host-scoped, UI-thread confined; replacing a UIKit instance must not reset its retry budget. */
internal data class InsightRecoveryState(
    val generation: Long = 0L,
    val paused: Boolean = false,
    private val automaticRestarts: List<Long> = emptyList()
) {
    fun restartAutomatically(expectedGeneration: Long, nowMs: Long): InsightRecoveryState {
        if (paused || expectedGeneration != generation) return this
        val recentRestarts = automaticRestarts.filter { nowMs - it < WindowMs }
        return if (recentRestarts.size >= MaxAutomaticRestarts) {
            // Stay paused even after the window expires; only an explicit user action reopens it.
            copy(paused = true)
        } else {
            copy(generation = generation + 1, automaticRestarts = recentRestarts + nowMs)
        }
    }

    fun restartManually(expectedGeneration: Long): InsightRecoveryState {
        if (!paused || expectedGeneration != generation) return this
        // Permit one new instance without replenishing the recent automatic-retry budget.
        return copy(generation = generation + 1, paused = false)
    }

    private companion object {
        const val MaxAutomaticRestarts = 2
        const val WindowMs = 60_000L
    }
}
