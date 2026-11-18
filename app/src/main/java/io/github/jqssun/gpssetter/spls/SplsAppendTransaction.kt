package io.github.jqssun.gpssetter.spls

import io.github.jqssun.gpssetter.utils.SplsRow

/**
 * Persists an observation before making it visible in the in-memory cache.
 * If persistence throws, [commit] is not called and cache revisions/counters stay unchanged.
 */
internal class SplsAppendTransaction(
    private val persist: (SplsRow) -> Unit,
    private val commit: (SplsRow) -> Unit
) {
    fun append(row: SplsRow) {
        persist(row)
        commit(row)
    }
}
