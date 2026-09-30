package com.ssintelligence.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Opt-in local search history (§24, §36, §37).
 *
 * Search history is off by default. "Off" means nothing is written, not
 * "written and hidden", because the string a person types into a search box is
 * often the one thing on their device they would least want retained.
 */
interface SearchHistoryRepository {

    /** False when the user has not enabled history; [record] then does nothing. */
    suspend fun isEnabled(): Boolean

    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<String>>

    /**
     * Stores one query. The implementation drops the query entirely when it
     * looks like a one-time code: a history row is displayed in a list, and a
     * list is exactly where a code would leak.
     */
    suspend fun record(query: String)

    /** Deletes every stored query. Always available from Settings. */
    suspend fun clear()

    suspend fun count(): Int

    companion object {
        const val DEFAULT_LIMIT = 25
    }
}
