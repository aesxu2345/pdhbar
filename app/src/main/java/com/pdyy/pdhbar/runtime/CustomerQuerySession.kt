package com.pdyy.pdhbar.runtime

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class CustomerQuerySession(
    val id: Long,
    val orderCode: String,
    val cacheNode: BreakfastRightCacheNode? = null,
    val ignoredSourceNodeId: String? = null,
    val failed: Boolean = false
)

/** Only the current scan owns temporary customer data; IDs also isolate same-code rescans. */
internal class CustomerQuerySessions {
    private val sequence = AtomicLong()
    private val active = AtomicReference<CustomerQuerySession?>()

    fun begin(orderCode: String): CustomerQuerySession =
        CustomerQuerySession(sequence.incrementAndGet(), orderCode).also(active::set)

    fun current(): CustomerQuerySession? = active.get()
    fun isCurrent(id: Long): Boolean = active.get()?.id == id

    fun update(id: Long, transform: (CustomerQuerySession) -> CustomerQuerySession): Boolean {
        while (true) {
            val previous = active.get()?.takeIf { it.id == id && !it.failed } ?: return false
            val next = transform(previous)
            require(next.id == id && next.orderCode == previous.orderCode)
            if (active.compareAndSet(previous, next)) return true
        }
    }

    fun fail(id: Long): Boolean = update(id) {
        it.copy(cacheNode = null, ignoredSourceNodeId = null, failed = true)
    }

    fun clear() { active.set(null) }
}