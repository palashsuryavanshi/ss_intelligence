package com.ssintelligence.app.data.database

/**
 * SQL fragments shared by the structured search queries.
 *
 * Every optional constraint is written as `:param IS NULL OR …`, so one
 * parameterized query serves every combination of filters instead of a query
 * per combination. SQLite folds a null parameter into a constant at prepare
 * time, so the unconstrained path stays on the same index it used in Phase 1.
 *
 * The date filter compares **epoch seconds** because that is the unit of
 * `screenshots.date_added`; converting in Kotlin rather than multiplying the
 * column in SQL is what keeps the `(date_added, id)` index usable.
 *
 * Domain matching is host equality or a subdomain of the queried host, and
 * deliberately *not* a substring match on the full URL: `LIKE '%amazon.in%'`
 * would also match `notamazon.in`, which is a different website.
 */
internal object SearchSql {

    /** Statuses that should never appear in search results. */
    const val ELIGIBLE = "AND s.status <> 'FAILED'"

    /**
     * Structured constraints: date window, price band, phone, domain, one-time
     * code, and the content-type chip.
     */
    const val STRUCTURED = """
        AND s.status <> 'FAILED'
        AND (:minDateSeconds IS NULL OR s.date_added >= :minDateSeconds)
        AND (:maxDateSeconds IS NULL OR s.date_added < :maxDateSeconds)
        AND (
            :priceMin IS NULL OR EXISTS (
                SELECT 1 FROM extracted_prices p
                WHERE p.screenshot_id = s.id
                  AND (:priceCurrency IS NULL OR p.currency = :priceCurrency)
                  AND p.amount >= :priceMin
                  AND p.amount <= :priceMax
            )
        )
        AND (
            :phone IS NULL OR EXISTS (
                SELECT 1 FROM extracted_phones ph
                WHERE ph.screenshot_id = s.id AND ph.normalized = :phone
            )
        )
        AND (
            :domain IS NULL OR EXISTS (
                SELECT 1 FROM extracted_urls u
                WHERE u.screenshot_id = s.id
                  AND (
                      u.host = :domain
                      OR u.host LIKE '%.' || :domain
                  )
            )
        )
        AND (:otp IS NULL OR EXISTS (
                SELECT 1 FROM extracted_otps o
                WHERE o.screenshot_id = s.id AND o.code = :otp
            )
        )
        AND (
            :filterTypes = '' OR
            (instr(:filterTypes, 'URLS') > 0 AND EXISTS (SELECT 1 FROM extracted_urls x WHERE x.screenshot_id = s.id)) OR
            (instr(:filterTypes, 'PRICES') > 0 AND EXISTS (SELECT 1 FROM extracted_prices x WHERE x.screenshot_id = s.id)) OR
            (instr(:filterTypes, 'DATES') > 0 AND EXISTS (SELECT 1 FROM extracted_dates x WHERE x.screenshot_id = s.id)) OR
            (instr(:filterTypes, 'PHONES') > 0 AND EXISTS (SELECT 1 FROM extracted_phones x WHERE x.screenshot_id = s.id)) OR
            (instr(:filterTypes, 'OTPS') > 0 AND EXISTS (SELECT 1 FROM extracted_otps x WHERE x.screenshot_id = s.id)) OR
            (instr(:filterTypes, 'DUPLICATES') > 0 AND s.duplicate_of_id IS NOT NULL)
        )
    """

    /**
     * Cheap SQL-side pre-order for the candidate window.
     *
     * A filename that starts with the query is almost always what the user
     * meant, so it is pulled into the bounded candidate set before the
     * recency-ordered remainder. Full relevance is still decided by
     * [com.ssintelligence.app.search.SearchRanker]; this only decides which rows
     * get scored at all.
     */
    const val CANDIDATE_ORDER = """
        ORDER BY
            CASE
                WHEN LOWER(s.filename) LIKE :prefixQuery ESCAPE '\' THEN 0
                WHEN LOWER(s.filename) LIKE :containsQuery ESCAPE '\' THEN 1
                ELSE 2
            END ASC,
            s.date_added DESC,
            s.id DESC
        LIMIT :limit
    """
}
