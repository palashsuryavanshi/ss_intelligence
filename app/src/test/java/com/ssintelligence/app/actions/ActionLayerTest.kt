package com.ssintelligence.app.actions

import com.ssintelligence.app.assistant.SensitivityLevel
import com.ssintelligence.app.domain.model.ExtractedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Action generation from detected content, and validation. */
class ActionLayerTest {

    private val generator = ActionCandidateGenerator()

    private fun generate(
        screenshotId: Long = 1L,
        ocrText: String = "sample text",
        urls: List<ExtractedUrl> = emptyList(),
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        addresses: List<String> = emptyList(),
        dates: List<Pair<Long, String?>> = emptyList(),
        prices: List<Pair<Double, String>> = emptyList(),
        hasOcrText: Boolean = true,
    ) = generator.generate(screenshotId, ocrText, urls, phones, emails, addresses, dates, prices, hasOcrText)

    @Test
    fun `a url generates open and copy actions`() {
        val actions = generate(urls = listOf(ExtractedUrl(1, 1, "https://example.com/p", "example.com")))
        assertTrue(actions.any { it.type == ActionType.OPEN_URL })
        assertTrue(actions.any { it.type == ActionType.COPY_TEXT })
        assertEquals("https://example.com/p", (actions.first { it.type == ActionType.OPEN_URL }.payload as ActionPayload.Url).url)
    }

    @Test
    fun `extracted text carries the ocr in its payload`() {
        val actions = generate(ocrText = "hello world", hasOcrText = true)
        val extract = actions.first { it.type == ActionType.COPY_TEXT && it.title == "Copy extracted text" }
        assertEquals("hello world", (extract.payload as ActionPayload.Text).text)
    }

    @Test
    fun `no url means no open action`() {
        val actions = generate()
        assertTrue(actions.none { it.type == ActionType.OPEN_URL })
    }

    @Test
    fun `a phone number generates call and copy actions`() {
        val actions = generate(phones = listOf("+919876543210"))
        val call = actions.first { it.type == ActionType.CALL_PHONE }
        assertEquals(ConfirmationLevel.SENSITIVE, call.confirmation)
        // Intent-delegated: the dialer owns the permission, so none is claimed.
        assertEquals(PermissionType.NONE, call.permission)
        assertTrue(actions.any { it.type == ActionType.COPY_PHONE })
    }

    @Test
    fun `an address generates a map action`() {
        val actions = generate(addresses = listOf("Connaught Place, New Delhi"))
        assertTrue(actions.any { it.type == ActionType.OPEN_MAP })
    }

    @Test
    fun `a date generates calendar and reminder actions`() {
        val actions = generate(dates = listOf(1_700_000_000_000L to "Flight"))
        assertTrue(actions.any { it.type == ActionType.CREATE_CALENDAR_EVENT })
        assertTrue(actions.any { it.type == ActionType.CREATE_REMINDER })
        val event = actions.first { it.type == ActionType.CREATE_CALENDAR_EVENT }
        assertEquals(ConfirmationLevel.CONFIRM, event.confirmation)
        // Intent-delegated: the calendar app owns the write, so none is claimed.
        assertEquals(PermissionType.NONE, event.permission)
    }

    @Test
    fun `a price generates a track action`() {
        val actions = generate(prices = listOf(39999.0 to "INR"))
        assertTrue(actions.any { it.type == ActionType.SAVE_EXPENSE })
    }

    @Test
    fun `actions rank by specificity`() {
        val actions = generate(
            urls = listOf(ExtractedUrl(1, 1, "https://example.com", "example.com")),
        )
        assertEquals(ActionType.OPEN_URL, actions.first().type)
    }

    // ---------------------------------------------------------- validator

    private fun validAction(type: ActionType = ActionType.OPEN_URL) = ContextAction(
        id = "a1", type = type, title = "Open", description = null,
        screenshotIds = listOf(1L), entityIds = emptyList(),
        confirmation = ConfirmationLevel.NONE, permission = PermissionType.NONE,
        sensitivity = SensitivityLevel.NORMAL,
        payload = ActionPayload.Url("https://example.com"),
    )

    @Test
    fun `a well formed action validates`() {
        val result = ActionValidator.validate(
            ActionValidator.Request(validAction(), permissionGranted = true, SensitivityLevel.NORMAL),
        )
        assertTrue(result.valid)
    }

    @Test
    fun `a blank title fails validation`() {
        val result = ActionValidator.validate(
            ActionValidator.Request(validAction().copy(title = ""), permissionGranted = true, SensitivityLevel.NORMAL),
        )
        assertFalse(result.valid)
    }

    @Test
    fun `a missing permission fails validation`() {
        val result = ActionValidator.validate(
            ActionValidator.Request(
                validAction(ActionType.CALL_PHONE).copy(permission = PermissionType.PHONE),
                permissionGranted = false,
                SensitivityLevel.NORMAL,
            ),
        )
        assertFalse(result.valid)
    }

    @Test
    fun `a highly sensitive action without confirmation fails`() {
        val result = ActionValidator.validate(
            ActionValidator.Request(
                validAction().copy(sensitivity = SensitivityLevel.HIGHLY_SENSITIVE),
                permissionGranted = true,
                SensitivityLevel.NORMAL,
            ),
        )
        assertFalse(result.valid)
    }

    @Test
    fun `calls are never automatic`() {
        assertFalse(ActionValidator.allowedInAutomation(ActionType.CALL_PHONE))
        assertFalse(ActionValidator.allowedInAutomation(ActionType.SEND_MESSAGE))
        assertFalse(ActionValidator.allowedInAutomation(ActionType.SHARE))
    }

    @Test
    fun `collections are allowed in automation`() {
        assertTrue(ActionValidator.allowedInAutomation(ActionType.CREATE_COLLECTION))
        assertTrue(ActionValidator.allowedInAutomation(ActionType.SAVE_EXPENSE))
    }
}
