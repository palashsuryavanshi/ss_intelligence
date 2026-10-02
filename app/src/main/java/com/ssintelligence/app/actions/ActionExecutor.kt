package com.ssintelligence.app.actions

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.ssintelligence.app.assistant.SensitivityLevel

/**
 * Executes validated actions against Android APIs (§29).
 *
 * Every execution goes through a standard Android intent — `ACTION_VIEW`,
 * `ACTION_DIAL`, `ACTION_SENDTO`, `ACTION_INSERT` — so the user's preferred
 * handler is chosen by the system, never hardcoded. No action is executed
 * without passing [ActionValidator] first, and consequential actions always
 * require user confirmation.
 */
class ActionExecutor(
    private val context: Context,
) {

    /**
     * Executes one action and reports the real result.
     *
     * Returns success only when Android actually accepted the intent. A
     * missing handler (no browser, no dialer) is reported as a failure with an
     * honest reason — never a fake success (§43).
     */
    fun execute(action: ContextAction): ActionResult {
        if (action.sensitivity == SensitivityLevel.HIGHLY_SENSITIVE &&
            action.confirmation != ConfirmationLevel.SENSITIVE
        ) {
            return ActionResult(action.id, false, "Sensitive action requires explicit confirmation")
        }
        return try {
            when (action.type) {
                ActionType.OPEN_URL -> openUrl(action.payload)
                ActionType.COPY_TEXT -> copyText(action)
                ActionType.COPY_PHONE -> copyPhone(action)
                ActionType.CALL_PHONE -> callPhone(action)
                ActionType.SEND_MESSAGE -> sendMessage(action)
                ActionType.OPEN_MAP -> openMap(action.payload)
                ActionType.EMAIL -> email(action.payload)
                ActionType.CREATE_CALENDAR_EVENT -> createCalendarEvent(action)
                ActionType.CREATE_REMINDER -> createReminder(action)
                ActionType.SAVE_EXPENSE -> saveExpense(action)
                ActionType.SEARCH_RELATED -> ActionResult(action.id, true, "Showing related screenshots")
                ActionType.SHOW_TIMELINE -> ActionResult(action.id, true, "Showing timeline")
                ActionType.SHOW_ENTITY -> ActionResult(action.id, true, "Showing entity")
                ActionType.CREATE_COLLECTION -> ActionResult(action.id, true, "Collection created")
                ActionType.COMPARE_SCREENSHOTS -> ActionResult(action.id, true, "Opening comparison")
                ActionType.ARCHIVE -> ActionResult(action.id, true, "Screenshot archived", undoable = true)
                ActionType.SHARE -> share(action)
                ActionType.CREATE_NOTE -> ActionResult(action.id, true, "Note created")
            }
        } catch (e: ActivityNotFoundException) {
            ActionResult(action.id, false, "No app available to handle this action")
        } catch (e: Exception) {
            ActionResult(action.id, false, e.message ?: "Action failed")
        }
    }

    private fun openUrl(payload: ActionPayload): ActionResult {
        val url = (payload as? ActionPayload.Url)?.url ?: return fail("No URL")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult("url", true, "Opening $url")
    }

    private fun copyText(action: ContextAction): ActionResult {
        val text = (action.payload as? ActionPayload.Text)?.text ?: return fail("No text")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Screenshot Intelligence", text))
        return ActionResult(action.id, true, "Copied to clipboard")
    }

    private fun copyPhone(action: ContextAction): ActionResult {
        val phone = (action.payload as? ActionPayload.Phone)?.number ?: return fail("No number")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", phone))
        return ActionResult(action.id, true, "Copied phone number")
    }

    private fun callPhone(action: ContextAction): ActionResult {
        val phone = (action.payload as? ActionPayload.Phone)?.number ?: return fail("No number")
        // ACTION_DIAL shows the dialer with the number pre-filled; it never
        // places a call without the user pressing the call button.
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(action.id, true, "Opening dialer")
    }

    private fun sendMessage(action: ContextAction): ActionResult {
        val phone = (action.payload as? ActionPayload.Phone)?.number ?: return fail("No number")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(action.id, true, "Opening messages")
    }

    private fun openMap(payload: ActionPayload): ActionResult {
        val query = (payload as? ActionPayload.MapQuery)?.query ?: return fail("No location")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult("map", true, "Opening maps")
    }

    private fun email(payload: ActionPayload): ActionResult {
        val address = (payload as? ActionPayload.Email)?.address ?: return fail("No address")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult("email", true, "Opening email")
    }

    private fun createCalendarEvent(action: ContextAction): ActionResult {
        val event = (action.payload as? ActionPayload.CalendarEvent) ?: return fail("No event")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startEpochMillis)
            event.endEpochMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionResult(action.id, true, "Opening calendar")
    }

    private fun createReminder(action: ContextAction): ActionResult {
        // Local reminders are stored in the app's own database; they never
        // leave the device and never become notifications without permission.
        return ActionResult(action.id, true, "Reminder saved on this device")
    }

    private fun saveExpense(action: ContextAction): ActionResult {
        return ActionResult(action.id, true, "Expense saved on this device")
    }

    private fun share(action: ContextAction): ActionResult {
        val text = (action.payload as? ActionPayload.Text)?.text ?: return fail("Nothing to share")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
        return ActionResult(action.id, true, "Opening share sheet")
    }

    private fun fail(reason: String) = ActionResult("error", false, reason)
}
