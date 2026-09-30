package com.ssintelligence.app.search

import com.ssintelligence.app.data.database.ScreenshotDao
import com.ssintelligence.app.data.database.ScreenshotEntity
import com.ssintelligence.app.data.database.toDomain
import com.ssintelligence.app.domain.model.ExtractedPhone
import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import com.ssintelligence.app.domain.repository.SearchHistoryRepository
import com.ssintelligence.app.domain.repository.SettingsRepository
import com.ssintelligence.app.search.parser.PriceQueryParser
import com.ssintelligence.app.search.parser.QueryParser
import com.ssintelligence.app.semantic.ConceptQueryExpander
import com.ssintelligence.app.semantic.HybridRanker
import com.ssintelligence.app.semantic.HybridSignals
import com.ssintelligence.app.semantic.QueryExpansionProvider
import com.ssintelligence.app.semantic.SemanticRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The deterministic, fully local search engine (§2, §17, §29, §49).
 *
 * ```
 * query → QueryParser → relaxation ladder → bounded SQL retrieval
 *       → metadata load → SearchRanker → SearchResult
 * ```
 *
 * Everything is on-device: no model, no network call, no cloud fallback. If the
 * app runs at all, search runs.
 *
 * Relaxation (§29)
 * ----------------
 * A query is tried at full strictness first. Only when that returns nothing is
 * one constraint dropped at a time, and the rung that produced the answer is
 * returned so the UI can say so out loud. Weakening a query silently would
 * misrepresent what the user asked for.
 */
class LocalSearchEngine(
    private val dao: ScreenshotDao,
    private val history: SearchHistoryRepository,
    private val parser: QueryParser = QueryParser(),
    private val ranker: SearchRanker = SearchRanker(),
    private val semantic: SemanticSearchProvider = SemanticSearchProvider.Disabled,
    private val clock: () -> Long = System::currentTimeMillis,
    private val candidateLimit: Int = CANDIDATE_LIMIT,
    /** Single source of truth for the "around" tolerance, shared with the parser. */
    private val priceTolerance: Double = PriceQueryParser().toleranceFraction,
    /**
     * Local semantic index. Null in tests that only exercise the lexical path;
     * wired in production through the service locator.
     */
    private val semanticRepository: SemanticRepository? = null,
    private val semanticSettings: SettingsRepository? = null,
    private val expander: QueryExpansionProvider = ConceptQueryExpander(),
    private val hybridRanker: HybridRanker = HybridRanker(),
) : ScreenshotSearchEngine {

    private val suggestionBuilder = SearchSuggestionBuilder(dao, history)

    // ------------------------------------------------------------- contract

    override suspend fun search(query: String): List<SearchResult> =
        search(SearchRequest(query)).results

    override suspend fun parse(query: String): SearchQuery = parser.parse(query)

    override suspend fun search(request: SearchRequest): SearchResponse {
        val startedAt = clock()
        val parsed = parser.parse(request.query)
        val effective = mergeManualFilters(parsed, request)

        var lastLevel = RelaxationLevel.EXACT
        for (level in ladder(effective)) {
            lastLevel = level
            val plan = planFor(effective, request, level) ?: continue
            if (level == RelaxationLevel.FILTERS_ONLY && plan.isUnfilteredBrowse() &&
                effective.ftsTerms.isNotEmpty()
            ) {
                // The ladder has nothing left to offer a text query: the only
                // remaining rung would list the whole library, which answers
                // nothing. Meaning gets the last word instead — and if it has
                // nothing either, the honest answer is no results, not a
                // browse disguised as search.
                val semanticOnly = semanticFallback(effective, request)
                if (semanticOnly != null) {
                    return semanticOnly.copy(elapsedMillis = clock() - startedAt)
                }
                break
            }
            val rows = retrieve(plan)
            if (rows.isEmpty()) continue

            val candidates = hydrate(rows)
            if (candidates.isEmpty()) continue

            val ranked = ranker.rank(effective, candidates, clock())
            if (ranked.isEmpty()) continue

            val hybrid = applySemanticFusion(effective, ranked)
            if (hybrid.results.isEmpty()) continue

            val ordered = applySortResults(hybrid.results, request.sortMode)
            return SearchResponse(
                query = effective,
                results = ordered.take(request.limit),
                relaxation = level,
                candidateCount = candidates.size,
                elapsedMillis = clock() - startedAt,
                semanticUsed = hybrid.semanticUsed,
            )
        }

        // The loop only exits without returning via the break above (text
        // query, nothing left) or an empty library. Either way the honest
        // answer is no results — never a whole-library browse.
        return SearchResponse(
            query = effective,
            results = emptyList(),
            relaxation = lastLevel,
            candidateCount = 0,
            elapsedMillis = clock() - startedAt,
        )
    }

    /**
     * Re-runs the search when the index changes.
     *
     * Keyed on the total row count rather than on invalidation queries: the
     * count changes on every insert, update and delete, and a search over a few
     * hundred candidates is cheap enough to repeat.
     */
    override fun observe(request: SearchRequest): Flow<SearchResponse> =
        dao.observeTotalCount().map { search(request) }

    override fun suggestions(prefix: String): Flow<List<SearchSuggestion>> =
        suggestionBuilder.observe(prefix)

    // ---------------------------------------------------------- result fusion

    /**
     * Applies the user's sort choice (§31).
     *
     * Relevance is the default because a ranker exists to be used; Newest and
     * Oldest are for when the user already knows roughly when they took the
     * screenshot and wants a straight chronological list. `date_added` is the
     * same timestamp the date filter uses, so the order and the filter agree.
     */
    private fun applySort(ranked: List<RankedResult>, sort: SortMode): List<RankedResult> =
        when (sort) {
            SortMode.RELEVANCE -> ranked
            SortMode.NEWEST -> ranked.sortedWith(
                compareByDescending<RankedResult> { it.candidate.screenshot.dateAdded }
                    .thenBy { it.candidate.screenshot.id },
            )

            SortMode.OLDEST -> ranked.sortedWith(
                compareBy<RankedResult> { it.candidate.screenshot.dateAdded }
                    .thenBy { it.candidate.screenshot.id },
            )
        }

    // ------------------------------------------------- Phase 3 hybrid fusion

    private data class FusedResults(
        val results: List<SearchResult>,
        val semanticUsed: Boolean,
    )

    /**
     * Applies the user's sort choice to finished results (§31).
     *
     * Sorting applies after duplicate collapsing, so a group keeps the position
     * its best member earned.
     */
    private fun applySortResults(results: List<SearchResult>, sort: SortMode): List<SearchResult> =
        when (sort) {
            SortMode.RELEVANCE -> results
            SortMode.NEWEST -> results.sortedWith(
                compareByDescending<SearchResult> { it.screenshot.dateAdded }
                    .thenBy { it.screenshot.id },
            )

            SortMode.OLDEST -> results.sortedWith(
                compareBy<SearchResult> { it.screenshot.dateAdded }
                    .thenBy { it.screenshot.id },
            )
        }

    /**
     * Hybrid lexical + semantic fusion (§4, §13, §33).
     *
     * The Phase 2 ranking is the base: every candidate keeps its lexical score
     * and reasons. The semantic index then contributes a similarity signal per
     * candidate, plus additional candidates the lexical pass never saw. The
     * hybrid ranker combines the three signals by weight, and exact matches
     * stay on top by arithmetic rather than by special-casing (§14).
     *
     * Any failure in the semantic half degrades to the lexical ranking, never
     * to an error (§56).
     */
    private suspend fun applySemanticFusion(
        query: SearchQuery,
        ranked: List<RankedResult>,
    ): FusedResults {
        val semantic = semanticRepository
        val enabled = semantic != null && semantic.isAvailable &&
            (semanticSettings?.isSemanticSearchEnabled() ?: true)
        if (!enabled || ranked.isEmpty()) {
            return FusedResults(collapseDuplicates(ranked.map { it.toResult() }), false)
        }
        return try {
            fuseWithSemantics(query, ranked, semantic!!)
        } catch (error: Exception) {
            FusedResults(collapseDuplicates(ranked.map { it.toResult() }), false)
        }
    }

    private suspend fun fuseWithSemantics(
        query: SearchQuery,
        ranked: List<RankedResult>,
        semantic: SemanticRepository,
    ): FusedResults {
        val expansionTerms = expander.expandTerms(query.ftsTerms)
        val semanticMatches = semantic.searchSimilar(
            queryText = query.originalQuery,
            expansionTerms = expansionTerms,
            limit = SemanticRepository.DEFAULT_SEARCH_LIMIT,
        )
        if (semanticMatches.isEmpty()) {
            return FusedResults(collapseDuplicates(ranked.map { it.toResult() }), false)
        }
        val similarityById = semanticMatches.associate { it.screenshotId to it.similarity }

        val conceptLabel = expander.conceptsForQuery(query.ftsTerms).firstOrNull()
        val relatedLabel = conceptLabel ?: query.phrases.firstOrNull() ?: query.originalQuery.trim()

        val scored = ranked.map { result ->
            val lexical = hybridRanker.normalizeLexical(result.score)
            val similarity = similarityById[result.candidate.screenshot.id] ?: 0.0
            val metadata = metadataShare(query, result)
            val hybrid = hybridRanker.score(HybridSignals(lexical, similarity, metadata))
            val reasons = if (similarity > 0 &&
                result.reasons.none { it.kind == MatchKind.SEMANTIC }
            ) {
                result.reasons + MatchReason("Related to \"$relatedLabel\"", MatchKind.SEMANTIC)
            } else {
                result.reasons
            }
            ScoredResult(result, hybrid, reasons, similarity > 0)
        }

        // Semantic-only candidates: rows the lexical pass never saw. They load
        // by id, get a snippet, and join the ranking with no lexical score —
        // which is exactly why they can never outrank an exact match.
        val knownIds = ranked.map { it.candidate.screenshot.id }.toSet()
        val extraIds = semanticMatches
            .filter { it.screenshotId !in knownIds }
            .take(SEMANTIC_EXTRA_LIMIT)
            .map { it.screenshotId }
        val extras = if (extraIds.isEmpty()) {
            emptyList()
        } else {
            dao.getByIds(extraIds).mapNotNull { row ->
                val similarity = similarityById[row.id] ?: return@mapNotNull null
                val screenshot = row.toDomain()
                val needles = (query.phrases + query.textTerms).distinct()
                val reason = MatchReason("Related to \"$relatedLabel\"", MatchKind.SEMANTIC)
                ScoredResult(
                    result = RankedResult(
                        candidate = RankCandidate(screenshot),
                        score = 0,
                        reasons = listOf(reason),
                        snippet = SnippetBuilder.build(screenshot.ocrText, needles),
                    ),
                    hybrid = hybridRanker.score(HybridSignals(0.0, similarity, 0.0)),
                    reasons = listOf(reason),
                    semantic = true,
                )
            }
        }

        val combined = (scored + extras).sortedWith(
            compareByDescending<ScoredResult> { it.hybrid }
                .thenByDescending { it.result.score }
                .thenByDescending { it.result.candidate.screenshot.dateAdded }
                .thenBy { it.result.candidate.screenshot.id },
        )
        return FusedResults(
            collapseDuplicates(combined.map { it.toResult() }),
            combined.any { it.semantic },
        )
    }

    private data class ScoredResult(
        val result: RankedResult,
        val hybrid: Double,
        val reasons: List<MatchReason>,
        val semantic: Boolean,
    ) {
        fun toResult(): SearchResult = SearchResult(
            screenshot = result.candidate.screenshot,
            score = result.score,
            snippet = result.snippet,
            matches = reasons,
        )
    }

    /**
     * Share of the query's structured constraints this candidate satisfies.
     *
     * Counted from the match reasons the Phase 2 ranker already computed, so
     * the definition of "satisfies" stays in one place.
     */
    private fun metadataShare(query: SearchQuery, result: RankedResult): Double {
        var total = 0
        var satisfied = 0
        val kinds = result.reasons.map { it.kind }.toSet()
        if (query.prices.isNotEmpty()) {
            total++
            if (MatchKind.PRICE in kinds) satisfied++
        }
        if (query.urls.isNotEmpty()) {
            total++
            if (MatchKind.URL in kinds) satisfied++
        }
        if (query.phoneNumbers.isNotEmpty()) {
            total++
            if (MatchKind.PHONE in kinds) satisfied++
        }
        if (query.otpCodes.isNotEmpty()) {
            total++
            if (MatchKind.OTP in kinds) satisfied++
        }
        if (query.timeRange != null) {
            total++
            if (MatchKind.DATE in kinds) satisfied++
        }
        if (total == 0) return 0.0
        return satisfied.toDouble() / total
    }

    /**
     * Collapses byte-identical screenshots into one result (§34).
     *
     * The best-ranked member represents the group; the rest hide behind
     * [SearchResult.collapsedIds] for the UI's "N identical screenshots"
     * expander. Identical OCR text gets the same treatment: a second row with
     * the same text adds no information, so it joins the first rather than
     * occupying its own rank (§35).
     */
    private fun collapseDuplicates(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return results
        val out = mutableListOf<SearchResult>()
        val seenHashes = mutableMapOf<String, Int>()
        val seenTexts = mutableMapOf<String, Int>()
        for (result in results) {
            val hash = result.screenshot.contentHash
            val textKey = result.screenshot.ocrText.take(SNIPPET_DEDUP_CHARS)
            val index = if (hash != null) {
                seenHashes[hash]
            } else if (result.screenshot.ocrText.isNotBlank()) {
                seenTexts[textKey]
            } else {
                null
            }
            if (index == null) {
                if (hash != null) seenHashes[hash] = out.size
                if (result.screenshot.ocrText.isNotBlank()) seenTexts[textKey] = out.size
                out += result
            } else {
                val first = out[index]
                out[index] = first.copy(
                    collapsedCount = first.collapsedCount + 1,
                    collapsedIds = first.collapsedIds + result.screenshot.id,
                )
            }
        }
        return out
    }

    /**
     * Last resort: the lexical ladder found nothing at any rung, but meaning
     * alone may answer. Runs the semantic index standalone and returns whatever
     * it finds, flagged as semantic so the UI can say "Meaning-based results".
     */
    private suspend fun semanticFallback(
        query: SearchQuery,
        request: SearchRequest,
    ): SearchResponse? {
        val semantic = semanticRepository ?: return null
        if (!semantic.isAvailable) return null
        if (semanticSettings?.isSemanticSearchEnabled() == false) return null
        if (query.originalQuery.isBlank()) return null
        return try {
            val expansionTerms = expander.expandTerms(query.ftsTerms)
            val matches = semantic.searchSimilar(
                queryText = query.originalQuery,
                expansionTerms = expansionTerms,
                limit = SemanticRepository.DEFAULT_SEARCH_LIMIT,
            )
            if (matches.isEmpty()) return null
            val conceptLabel = expander.conceptsForQuery(query.ftsTerms).firstOrNull()
                ?: query.originalQuery.trim()
            val rows = dao.getByIds(matches.map { it.screenshotId })
            val byId = rows.associateBy { it.id }
            val results = matches.mapNotNull { match ->
                val row = byId[match.screenshotId] ?: return@mapNotNull null
                val screenshot = row.toDomain()
                val needles = (query.phrases + query.textTerms).distinct()
                SearchResult(
                    screenshot = screenshot,
                    score = 0,
                    snippet = SnippetBuilder.build(screenshot.ocrText, needles),
                    matches = listOf(
                        MatchReason("Related to \"$conceptLabel\"", MatchKind.SEMANTIC),
                    ),
                )
            }.take(request.limit)
            if (results.isEmpty()) return null
            SearchResponse(
                query = query,
                results = collapseDuplicates(applySortResults(results, request.sortMode)),
                relaxation = RelaxationLevel.FILTERS_ONLY,
                candidateCount = matches.size,
                elapsedMillis = 0,
                semanticUsed = true,
            )
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Where a local semantic provider would join in (§51 Phase 2).
     *
     * With [SemanticSearchProvider.Disabled] this is the identity function: the
     * deterministic ranking is the product, and semantic search is an addition
     * to it, never a replacement (§35). The seam is here so Phase 3 does not
     * have to restructure retrieval.
     */
    private suspend fun fuse(query: SearchQuery, ranked: List<RankedResult>): List<RankedResult> {
        if (!semantic.isEnabled || ranked.isEmpty()) return ranked
        val boosted = semantic.search(query.originalQuery, ranked.map { it.candidate.screenshot.id })
            .toSet()
        if (boosted.isEmpty()) return ranked
        return ranked.sortedWith(
            compareByDescending<RankedResult> { it.candidate.screenshot.id in boosted }
                .thenByDescending { it.score }
                .thenByDescending { it.candidate.screenshot.dateAdded }
                .thenBy { it.candidate.screenshot.id },
        )
    }

    // ------------------------------------------------------------ relaxation

    /**
     * The ladder of attempts, strictest to loosest. Levels that would change
     * nothing are skipped, so a query with no price does not burn a pass on
     * price relaxation.
     */
    private fun ladder(query: SearchQuery): List<RelaxationLevel> {
        val levels = mutableListOf(RelaxationLevel.EXACT)
        if (query.prices.isNotEmpty()) {
            levels += RelaxationLevel.PRICE_APPROXIMATE
            levels += RelaxationLevel.TEXT_ONLY
        }
        if (query.textTerms.isNotEmpty()) levels += RelaxationLevel.ANY_TERM
        levels += RelaxationLevel.FILTERS_ONLY
        return levels.distinct()
    }

    /** The concrete SQL shape for one rung, or null when the rung is a no-op. */
    private fun planFor(
        query: SearchQuery,
        request: SearchRequest,
        level: RelaxationLevel,
    ): RetrievalPlan? {
        val keepsPrice = level == RelaxationLevel.EXACT ||
            level == RelaxationLevel.PRICE_APPROXIMATE
        val band = if (keepsPrice) bandFor(query, request, level) else null
        if (keepsPrice && query.prices.isNotEmpty() && band == null) return null

        val useText = level != RelaxationLevel.FILTERS_ONLY
        val conjunction = if (level == RelaxationLevel.ANY_TERM) {
            FtsQueryBuilder.Conjunction.OR
        } else {
            FtsQueryBuilder.Conjunction.AND
        }
        val seconds = query.timeRange?.toEpochSeconds()

        return RetrievalPlan(
            ftsQuery = if (useText) {
                FtsQueryBuilder.buildFromTerms(query.ftsTerms, conjunction)
            } else {
                null
            },
            phraseQuery = if (useText && conjunction == FtsQueryBuilder.Conjunction.AND) {
                buildPhraseQuery(query.phrases)
            } else {
                null
            },
            rawQuery = query.originalQuery,
            band = band,
            minDateSeconds = seconds?.first,
            maxDateSeconds = seconds?.last?.plus(1),
            phone = query.phoneNumbers.firstOrNull() ?: request.manualFilters.phone,
            domain = query.urls.firstOrNull() ?: request.manualFilters.domain,
            otp = query.otpCodes.firstOrNull(),
            filterTypes = filterTypesFor(query, request),
            candidateLimit = candidateLimit,
        )
    }

    /**
     * The price band for one rung.
     *
     * At [RelaxationLevel.EXACT] an exact price is matched exactly. At
     * [RelaxationLevel.PRICE_APPROXIMATE] it widens to the documented ±5% band
     * — the same tolerance the parser applies to "around", so the relaxed
     * result is never a wider surprise than the user was already shown.
     */
    private fun bandFor(
        query: SearchQuery,
        request: SearchRequest,
        level: RelaxationLevel,
    ): PriceBand? {
        val manual = request.manualFilters
        val parsed = query.prices.firstOrNull()
        val manualBand = if (manual.minPrice != null || manual.maxPrice != null) {
            PriceBand(
                currency = manual.currency,
                min = manual.minPrice ?: 0.0,
                max = manual.maxPrice ?: Double.MAX_VALUE,
            )
        } else {
            null
        }
        val base = parsed?.toBand() ?: manualBand ?: return null

        val widen = level == RelaxationLevel.PRICE_APPROXIMATE && parsed is PriceFilter.Exact
        val min = when {
            manual.minPrice != null -> manual.minPrice
            widen -> (base.min * (1 - priceTolerance)).coerceAtLeast(0.0)
            else -> base.min
        }
        val max = when {
            manual.maxPrice != null -> manual.maxPrice
            widen -> base.max * (1 + priceTolerance)
            else -> base.max
        }
        return PriceBand(currency = base.currency ?: manual.currency, min = min, max = max)
    }

    // ------------------------------------------------------------- retrieval

    private suspend fun retrieve(plan: RetrievalPlan): List<ScreenshotEntity> {
        val fts = plan.ftsQuery
        if (fts == null) return plan.filteredOnlySearch()

        val like = likeArguments(plan.rawQuery)
        val byTerms = dao.searchByText(
            ftsQuery = fts,
            prefixQuery = like.first,
            containsQuery = like.second,
            minDateSeconds = plan.minDateSeconds,
            maxDateSeconds = plan.maxDateSeconds,
            priceMin = plan.band?.min,
            priceMax = plan.band?.max,
            priceCurrency = plan.band?.currency,
            phone = plan.phone,
            domain = plan.domain,
            otp = plan.otp,
            filterTypes = plan.filterTypes,
            limit = plan.candidateLimit,
        )

        // Union the exact-phrase hits, so an old but precisely matching
        // screenshot cannot be crowded out of the window by newer partial
        // matches (§19).
        val byPhrase = plan.phraseQuery?.let { phrase ->
            dao.searchByPhrase(
                ftsQuery = phrase,
                prefixQuery = like.first,
                containsQuery = like.second,
                minDateSeconds = plan.minDateSeconds,
                maxDateSeconds = plan.maxDateSeconds,
                priceMin = plan.band?.min,
                priceMax = plan.band?.max,
                priceCurrency = plan.band?.currency,
                phone = plan.phone,
                domain = plan.domain,
                otp = plan.otp,
                filterTypes = plan.filterTypes,
                limit = plan.candidateLimit,
            )
        }.orEmpty()

        return (byPhrase + byTerms).distinctBy { it.id }
    }

    private suspend fun RetrievalPlan.filteredOnlySearch(): List<ScreenshotEntity> =
        dao.searchByFilters(
            minDateSeconds = minDateSeconds,
            maxDateSeconds = maxDateSeconds,
            priceMin = band?.min,
            priceMax = band?.max,
            priceCurrency = band?.currency,
            phone = phone,
            domain = domain,
            otp = otp,
            filterTypes = filterTypes,
            limit = candidateLimit,
        )

    /**
     * Loads the bounded candidate set's metadata in four indexed lookups.
     *
     * OCR text already came back with the candidate rows; nothing here reads a
     * table wholesale, which is what keeps a 10k+ library responsive (§32).
     */
    private suspend fun hydrate(rows: List<ScreenshotEntity>): List<RankCandidate> {
        val ids = rows.map { it.id }
        val prices = dao.pricesFor(ids).groupBy { it.screenshotId }
        val urls = dao.urlsFor(ids).groupBy { it.screenshotId }
        val phones = dao.phonesFor(ids).groupBy { it.screenshotId }
        val otps = dao.otpsFor(ids).groupBy { it.screenshotId }

        return rows.map { row ->
            RankCandidate(
                screenshot = row.toDomain(),
                prices = prices[row.id].orEmpty().map {
                    ExtractedPrice(it.id, it.screenshotId, it.rawText, it.currency, it.amount)
                },
                urls = urls[row.id].orEmpty().map {
                    ExtractedUrl(it.id, it.screenshotId, it.url, it.host)
                },
                phones = phones[row.id].orEmpty().map {
                    ExtractedPhone(it.id, it.screenshotId, it.rawText, it.normalized, it.country)
                },
                otpCodes = otps[row.id].orEmpty().map { it.code },
            )
        }
    }

    private fun RankedResult.toResult() = SearchResult(
        screenshot = candidate.screenshot,
        score = score,
        snippet = snippet,
        matches = reasons,
    )

    // ---------------------------------------------------------------- merging

    /**
     * Combines the parsed query with refinements the user applied by hand.
     *
     * A manual filter wins over a parsed guess: it is a deliberate tap on a
     * control, whereas the parser is inferring intent from a sentence.
     */
    private fun mergeManualFilters(parsed: SearchQuery, request: SearchRequest): SearchQuery {
        val manual = request.manualFilters
        var types = parsed.contentTypes + request.contentTypes
        if (manual.duplicatesOnly == true) types += ContentType.DUPLICATES
        if (manual.hasOtp == true) types += ContentType.OTPS
        return parsed.copy(
            contentTypes = types,
            timeRange = manual.timeRange ?: parsed.timeRange,
            sortMode = request.sortMode,
        )
    }

    /**
     * The content-type tokens, formatted for the DAO's `instr` test.
     *
     * Delimiters keep `DATES` from matching inside `DATES|PRICES`, and an
     * empty selection must serialize to `""` — not to the bare `"|"` that
     * `joinToString` would emit for an empty sequence, which would then match no
     * filter at all and silently hide every row.
     */
    private fun filterTypesFor(query: SearchQuery, request: SearchRequest): String {
        val types = query.contentTypes + request.contentTypes
        if (types.isEmpty()) return ""
        return types.joinToString(separator = "", prefix = "|", postfix = "|") {
            it.filterName
        }
    }

    /** `(prefix LIKE pattern, contains LIKE pattern)` for the filename pre-order. */
    private fun likeArguments(raw: String): Pair<String, String> {
        val escaped = FtsQueryBuilder.escapeLike(raw.trim().lowercase())
        return "$escaped%" to "%$escaped%"
    }

    /**
     * An FTS4 expression matching a phrase verbatim.
     *
     * Deliberately no trailing `*`: a prefix would also match "Pixel 9a5",
     * which is not the phrase the user typed.
     */
    private fun buildPhraseQuery(phrases: List<String>): String? {
        val cleaned = phrases
            .map { it.lowercase().trim() }
            .filter { it.contains(' ') }
            .distinct()
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
    }

    private data class RetrievalPlan(
        val ftsQuery: String?,
        val phraseQuery: String?,
        val rawQuery: String,
        val band: PriceBand?,
        val minDateSeconds: Long?,
        val maxDateSeconds: Long?,
        val phone: String?,
        val domain: String?,
        val otp: String?,
        val filterTypes: String,
        val candidateLimit: Int,
    ) {
        /**
         * True when this plan constrains nothing: no text (null ftsQuery at
         * FILTERS_ONLY), no band, no phone/domain/code, no date, no content
         * types. Running it lists the library, which is browsing, not
         * searching — the caller treats it as such.
         */
        fun isUnfilteredBrowse(): Boolean =
            ftsQuery == null && band == null && phone == null && domain == null &&
                otp == null && minDateSeconds == null && filterTypes.isEmpty()
    }

    companion object {
        /**
         * Rows scored per search: large enough to cover a realistic library's
         * useful slice, small enough that ranking stays in the low tens of
         * milliseconds. See `ScreenshotDao.searchByText` for the trade-off this
         * makes at larger sizes.
         */
        const val CANDIDATE_LIMIT = 400

        /** Semantic-only rows admitted per search, beyond the lexical set. */
        const val SEMANTIC_EXTRA_LIMIT = 20

        /** OCR prefix compared for near-duplicate collapsing in results. */
        const val SNIPPET_DEDUP_CHARS = 200
    }
}
