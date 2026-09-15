package com.pdyy.pdhbar.runtime

import java.util.TreeMap

/** One attempt per index per pass, including failures; retain at most [limit] candidates. */
internal fun selectIndexedRefillBatch(
    snapshots: List<IgnoredOrderSnapshot>,
    afterIndex: Long,
    limit: Int
): List<IgnoredOrderSnapshot> {
    if (limit <= 0) return emptyList()
    val selected = TreeMap<Long, IgnoredOrderSnapshot>()
    for (node in snapshots) {
        if (node.cacheNode != null || node.cacheIndex <= afterIndex) continue
        selected[node.cacheIndex] = node
        if (selected.size > limit) selected.pollLastEntry()
    }
    return selected.values.toList()
}
