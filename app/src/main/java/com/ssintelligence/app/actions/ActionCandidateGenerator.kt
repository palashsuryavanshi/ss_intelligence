package com.ssintelligence.app.actions

import com.ssintelligence.app.assistant.SensitivityLevel
import com.ssintelligence.app.domain.model.ExtractedUrl

/**
 * Generates action candidates from a screenshot's detected content (§4, §5).
 *
 * Actions are built only from data the pipeline actually extracted — a URL
 * action exists because a URL was found, a phone action because a phone number
 * was found. An action that cannot apply is never generated. Candidates are
 * ranked by relevance: the most specific, most useful action first.
 */
class ActionCandidateGenerator {

    /** Builds the ranked action list for one screenshot's detail page. */
    fun generate(
        screenshotId: Long,
        ocrText: String,
        urls: List<ExtractedUrl>,
        phones: List<String>,
        emails: List<String>,
        addresses: List<String>,
        dates: List<Pair<Long, String?>>, // epoch millis to label
        prices: List<Pair<Double, String>>, // amount to currency
        hasOcrText: Boolean,
    ): List<ContextAction> {
        val out = mutableListOf<ContextAction>()

        // URL actions — one per detected URL, most specific first.
        for (url in urls.take(3)) {
            out += ContextAction(
                id = "url-$screenshotId-${url.url.hashCode()}",
                type = ActionType.OPEN_URL,
                title = "Open ${url.host}",
                description = url.url,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Url(url.url),
            )
            out += ContextAction(
                id = "copy-url-$screenshotId-${url.url.hashCode()}",
                type = ActionType.COPY_TEXT,
                title = "Copy URL",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Text(url.url),
            )
        }

        // Phone actions. The dialer and messaging intents need no permission:
        // ACTION_DIAL shows the dialer with the number pre-filled and never
        // places a call by itself; safety comes from confirmation, not a grant.
        for (phone in phones.take(2)) {
            out += ContextAction(
                id = "call-$screenshotId-${phone.hashCode()}",
                type = ActionType.CALL_PHONE,
                title = "Call $phone",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.SENSITIVE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.SENSITIVE,
                payload = ActionPayload.Phone(phone),
            )
            out += ContextAction(
                id = "copy-phone-$screenshotId-${phone.hashCode()}",
                type = ActionType.COPY_PHONE,
                title = "Copy phone number",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Text(phone),
            )
        }

        // Email actions.
        for (email in emails.take(2)) {
            out += ContextAction(
                id = "email-$screenshotId-${email.hashCode()}",
                type = ActionType.EMAIL,
                title = "Email $email",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.CONFIRM,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Email(email),
            )
        }

        // Address / map actions.
        for (address in addresses.take(2)) {
            out += ContextAction(
                id = "map-$screenshotId-${address.hashCode()}",
                type = ActionType.OPEN_MAP,
                title = "Open in Maps",
                description = address,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.MapQuery(address),
            )
        }

        // Date actions — calendar and reminder, from dates visible in the screenshot.
        // The calendar intent needs no permission: the system calendar app owns
        // the write, and the user confirms inside it.
        for ((millis, label) in dates.take(2)) {
            out += ContextAction(
                id = "cal-$screenshotId-$millis",
                type = ActionType.CREATE_CALENDAR_EVENT,
                title = "Add ${label ?: "date"} to Calendar",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.CONFIRM,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.CalendarEvent(
                    title = label ?: "Event",
                    startEpochMillis = millis,
                    endEpochMillis = null,
                    location = null,
                    description = null,
                ),
            )
            out += ContextAction(
                id = "remind-$screenshotId-$millis",
                type = ActionType.CREATE_REMINDER,
                title = "Create reminder",
                description = label,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.CONFIRM,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Reminder(
                    title = label ?: "Reminder",
                    dueEpochMillis = millis,
                    sourceScreenshotId = screenshotId,
                ),
            )
        }

        // Price actions — track in screenshots.
        for ((amount, currency) in prices.take(2)) {
            out += ContextAction(
                id = "track-$screenshotId-$amount",
                type = ActionType.SAVE_EXPENSE,
                title = "Track this price",
                description = "$currency $amount",
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.CONFIRM,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Expense(
                    merchant = null,
                    amount = amount,
                    currency = currency,
                    dateEpochDay = 0,
                    category = null,
                ),
            )
        }

        // Document actions — always available when OCR text exists. The text
        // itself travels in the payload: an empty placeholder would fail
        // validation, and rightly so.
        if (hasOcrText) {
            out += ContextAction(
                id = "extract-$screenshotId",
                type = ActionType.COPY_TEXT,
                title = "Copy extracted text",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.Text(ocrText),
            )
            out += ContextAction(
                id = "ask-$screenshotId",
                type = ActionType.SEARCH_RELATED,
                title = "Ask about this screenshot",
                description = null,
                screenshotIds = listOf(screenshotId),
                entityIds = emptyList(),
                confirmation = ConfirmationLevel.NONE,
                permission = PermissionType.NONE,
                sensitivity = SensitivityLevel.NORMAL,
                payload = ActionPayload.ScreenshotIds(listOf(screenshotId)),
            )
        }

        return out.sortedByDescending { specificity(it.type) }
    }

    /**
     * Ranks actions by how specifically they match the screenshot's content.
     * A URL action outranks a generic "copy text" because it is more useful.
     */
    private fun specificity(type: ActionType): Int = when (type) {
        ActionType.OPEN_URL, ActionType.CALL_PHONE, ActionType.OPEN_MAP -> 100
        ActionType.CREATE_CALENDAR_EVENT, ActionType.SAVE_EXPENSE -> 90
        ActionType.EMAIL, ActionType.CREATE_REMINDER -> 80
        ActionType.COPY_PHONE, ActionType.COPY_TEXT -> 60
        ActionType.SEARCH_RELATED, ActionType.SHOW_ENTITY -> 50
        else -> 40
    }
}
