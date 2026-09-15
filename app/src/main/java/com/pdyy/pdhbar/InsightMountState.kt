package com.pdyy.pdhbar

/** UI-thread confined. A data update must not recreate the browser/native renderer. */
internal class InsightMountState {
    private var owner: Any? = null
    private var appliedLength: Int? = null

    fun update(nextOwner: Any, length: Int, mount: () -> Unit, correct: (Int) -> Boolean) {
        if (owner !== nextOwner) {
            mount()
            owner = nextOwner
            appliedLength = null
        }
        val value = length.coerceAtLeast(0)
        if (appliedLength != value && correct(value)) appliedLength = value
    }
}
