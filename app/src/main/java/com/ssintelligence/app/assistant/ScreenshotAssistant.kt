package com.ssintelligence.app.assistant

/**
 * The Phase 5 orchestrator.
 *
 * Wire order is fixed by the design: interpret → retrieve → rank → build
 * evidence → generate → validate. Retrieval always runs before generation, so
 * the answer can never be produced from nothing, and the validator always runs
 * last, so an unsupported claim never reaches the user.
 */
class ScreenshotAssistant(
    private val retriever: AssistantRetriever,
    private val ranker: EvidenceRanker,
    private val contextBuilder: EvidenceContextBuilder,
    private val generator: LocalResponseGenerator.Generator = LocalResponseGenerator.NoModelGenerator,
    private val validator: ResponseValidator = ResponseValidator,
) {

    suspend fun ask(rawQuery: String, context: AssistantContext): AssistantTurn {
        val query = QueryInterpreter.interpret(rawQuery, context)
        val candidates = retriever.retrieve(query, context.lastEvidenceIds)
        val ranked = ranker.rank(candidates)
        val evidence = contextBuilder.build(ranked)
        val rawAnswer = if (evidence.isEmpty()) {
            LocalResponseGenerator.NO_EVIDENCE
        } else {
            generator.generate(query, evidence)
        }
        val validation = validator.validate(rawAnswer, evidence)
        val requiresReveal = evidence.any { it.sensitivity != SensitivityLevel.NORMAL }
        val confidence = when {
            evidence.isEmpty() -> ConfidenceType.NO_MATCH
            evidence.size == 1 -> ConfidenceType.FOUND_ONE
            else -> ConfidenceType.FOUND_MULTIPLE
        }
        val relatedEntities = evidence.flatMap { it.entities }.distinct().take(6)
        val sources = evidence.map {
            ScreenshotSource(it.screenshotId, it.filename, it.dateAdded)
        }
        val chain = EvidenceChain(
            parsedIntent = query.intent,
            entity = query.entityText,
            priceFilter = query.priceConstraint?.describe(),
            dateRange = query.dateRange?.label(),
            candidateCount = candidates.size,
            selectedCount = evidence.size,
            validationPassed = validation.passed,
            validationNotes = validation.notes,
            signalSummary = evidence.flatMap { it.signals }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { "${it.key} ×${it.value}" },
        )
        val response = AssistantResponse(
            answer = validation.validated,
            sources = sources,
            relatedEntities = relatedEntities,
            actions = actionsFor(evidence, relatedEntities),
            confidenceType = confidence,
            requiresReveal = requiresReveal,
            intent = query.intent,
            evidenceChain = chain,
        )
        val newContext = AssistantContext(
            lastEntity = query.entityText ?: context.lastEntity,
            lastPriceFilter = query.priceConstraint?.describe() ?: context.lastPriceFilter,
            lastDateRange = query.dateRange ?: context.lastDateRange,
            lastEvidenceIds = evidence.map { it.screenshotId },
            lastIntent = query.intent,
            lastRequiresReveal = requiresReveal,
        )
        return AssistantTurn(rawQuery, response, newContext)
    }

    data class AssistantTurn(
        val userText: String,
        val response: AssistantResponse,
        val context: AssistantContext,
    )

    private fun actionsFor(evidence: List<Evidence>, entities: List<String>): List<AssistantAction> =
        buildList {
            if (evidence.isNotEmpty()) add(AssistantAction.ViewTimeline)
            if (evidence.size >= 2) add(AssistantAction.Compare)
            if (evidence.isNotEmpty()) add(AssistantAction.ViewSources)
        }

}
