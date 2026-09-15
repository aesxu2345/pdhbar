package com.pdyy.pdhbar.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-order immutable publication, independent of the ring and its persistence.
 * The actor (or construction-time restore) is the sole publisher; readers never take a lock,
 * send an actor command, prune TTLs or touch storage. close() may race with that writer.
 */
internal class IgnoredOrderReadIndex {
    private val snapshots = ConcurrentHashMap<String, IgnoredOrderSnapshot>()
    @Volatile private var closed = false

    fun get(orderCode: String, nowEpochMs: Long = System.currentTimeMillis()): IgnoredOrderSnapshot? {
        if (closed) return null
        val snapshot = snapshots[orderCode] ?: return null
        if (isIgnoredNodeExpired(
                snapshot.createdAtEpochMs,
                snapshot.cacheNode?.cachedAtEpochMs,
                nowEpochMs
            )
        ) return null
        return snapshot.takeUnless { closed }
    }

    fun publish(snapshot: IgnoredOrderSnapshot) {
        if (closed) return
        require(snapshot.cacheNode == null ||
            (snapshot.cacheNode.orderCode == snapshot.orderCode &&
                snapshot.cacheNode.cacheIndex == snapshot.cacheIndex)) {
            "ignored cache identity must match its published node"
        }
        snapshots[snapshot.orderCode] = snapshot
        // A writer already past the first check must not repopulate a stopped instance.
        if (closed) snapshots.remove(snapshot.orderCode, snapshot)
    }

    fun matches(orderCode: String, nodeId: String, cacheIndex: Long): Boolean {
        if (closed) return false
        val snapshot = snapshots[orderCode] ?: return false
        return snapshot.nodeId == nodeId && snapshot.cacheIndex == cacheIndex
    }

    fun remove(orderCode: String, nodeId: String, cacheIndex: Long): Boolean {
        val snapshot = snapshots[orderCode] ?: return false
        if (snapshot.nodeId != nodeId || snapshot.cacheIndex != cacheIndex) return false
        return snapshots.remove(orderCode, snapshot)
    }

    /** Discard a partially restored view without disabling subsequent actor publications. */
    fun clear() = snapshots.clear()

    /** Permanently withdraw this instance, including publications racing with shutdown. */
    fun close() {
        closed = true
        snapshots.clear()
    }
}
