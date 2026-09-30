package com.ssintelligence.app.data.repository

import com.ssintelligence.app.data.database.SearchHistoryDao
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room + DataStore backed search history.
 *
 * Two stores, one policy: the *flag* lives in DataStore next to the other
 * settings, the *queries* live in the database next to the other local data.
 * Nothing is written until the flag is on, and clearing is a single DELETE.
 */
class SearchHistoryRepositoryImpl(
    private val dao: SearchHistoryDao,
    private val settings: SettingsRepository,
) : SearchHistoryRepository {

    override suspend fun isEnabled(): Boolean = settings.isSearchHistoryEnabled()

    override fun observeRecent(limit: Int): Flow<List<String>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.query } }

    override suspend fun record(query: String) {
        if (!isEnabled()) return
        if (isSensitive(query)) return
        dao.record(query, System.currentTimeMillis(), SearchHistoryDao.DEFAULT_LIMIT)
    }

    override suspend fun clear() = dao.clear()

    override suspend fun count(): Int = dao.count()

    /**
     * Refuses anything that looks like it carries a one-time code.
     *
     * Checked on the raw text rather than the parsed query so a code cannot
     * slip through by being phrased unusually. Kept deliberately blunt: it is
     * better to lose a legitimate history entry than to persist a secret.
     */
    private fun isSensitive(query: String): Boolean {
        val digits = query.count { it.isDigit() }
        if (digits < 4) return false
        val mentionsCode = CODE_CONTEXT.containsMatchIn(query)
        if (!mentionsCode) return false
        // "otp" plus any run of digits is treated as sensitive.
        return DIGIT_RUN.containsMatchIn(query)
    }

    private companion object {
        val CODE_CONTEXT = Regex(
            """(?i)\b(?:otp|o\.t\.p|one[\s-]?time|verification|verify|security|login|auth|2fa|mfa)\b""",
        )
        val DIGIT_RUN = Regex("""\d{4,}""")
    }
}
