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
            actions = actionsFor(query, evidence, relatedEntities),
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

    private fun actionsFor(
        query: QueryInterpreter.InterpretedQuery,
        evidence: List<Evidence>,
        entities: List<String>,
    ): List<AssistantAction> = buildList {
        if (evidence.isNotEmpty()) add(AssistantAction.ViewTimeline)
        if (evidence.size >= 2) add(AssistantAction.Compare)
        if (evidence.isNotEmpty()) add(AssistantAction.ViewSources)
        // Phase 7 action cards: the assistant proposes from the user's words,
        // grounded in the retrieved evidence. Nothing executes without the user.
        addAll(proposedActions(query, evidence))
    }

    /**
     * Proposes typed action commands from an action-seeking question (§26, §42).
     *
     * "Create a reminder for this" proposes a reminder bound to the evidence's
     * screenshots; "save this as an expense" proposes an expense from the
     * evidence's first price. A question with no action words proposes nothing.
     */
    private fun proposedActions(
        query: QueryInterpreter.InterpretedQuery,
        evidence: List<Evidence>,
    ): List<AssistantAction.ProposeAction> {
        if (evidence.isEmpty()) return emptyList()
        val lower = query.raw.lowercase()
        val out = mutableListOf<AssistantAction.ProposeAction>()
        val firstId = evidence.first().screenshotId
        if (lower.contains("remind")) {
            val title = query.entityText ?: "Check screenshot"
            out += AssistantAction.ProposeAction(
                label = "Create reminder",
                command = AssistantActionRequest.CreateReminder(title, null),
            )
        }
        if (lower.contains("expense") || lower.contains("save") && lower.contains("receipt")) {
            val price = evidence.flatMap { it.prices }.firstOrNull()
            if (price != null) {
                out += AssistantAction.ProposeAction(
                    label = "Save expense",
                    command = AssistantActionRequest.SaveExpense(null, price.second, price.first),
                )
            }
        }
        if (lower.contains("calendar") || lower.contains("add") && lower.contains("event")) {
            out += AssistantAction.ProposeAction(
                label = "Add to calendar",
                command = AssistantActionRequest.AddToCalendar(
                    query.entityText ?: "Event",
                    evidence.first().dateAdded * 1000,
                ),
            )
        }
        if (lower.contains("collection")) {
            val name = query.entityText ?: "Organized"
            out += AssistantAction.ProposeAction(
                label = "Create collection",
                command = AssistantActionRequest.CreateCollection(name),
            )
        }
        return out
    }

}
