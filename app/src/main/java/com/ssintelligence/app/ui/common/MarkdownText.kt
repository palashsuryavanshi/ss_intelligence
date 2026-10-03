package com.ssintelligence.app.ui.common

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.ssintelligence.app.ui.theme.OcrTextStyle
import com.ssintelligence.app.ui.theme.SsColors

/**
 * Lightweight markdown rendering for OCR text boxes.
 *
 * No external dependency: OCR text is rarely real markdown, so a small
 * deterministic subset is enough — headers, bold, italic, strikethrough,
 * inline code, fenced code blocks, lists, blockquotes, links and rules.
 * Plain lines pass through unchanged, so non-markdown OCR renders exactly
 * as before, only inside a selectable container.
 */
object OcrMarkdown {

    fun toAnnotatedString(
        markdown: String,
        bodyColor: Color,
        accentColor: Color,
        mutedColor: Color,
        codeBackground: Color,
    ): AnnotatedString = buildAnnotatedString {
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                i++
                val code = StringBuilder()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // skip closing fence
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = bodyColor, fontSize = 13.sp)) {
                    append(code.toString().trimEnd())
                }
                append("\n")
                continue
            }
            appendInline(line.trimStart(' '), bodyColor, accentColor, mutedColor, isListItem = line.trimStart().let {
                it.startsWith("- ") || it.startsWith("* ") || it.startsWith("+ ") || orderedListPrefix(it) != null
            }, isQuote = line.trimStart().startsWith(">"))
            if (i < lines.size - 1) append("\n")
            i++
        }
    }

    private fun orderedListPrefix(text: String): String? {
        val match = Regex("""^(\d+[.)]\s+)""").find(text) ?: return null
        return match.groupValues[1]
    }

    private fun AnnotatedString.Builder.appendInline(
        line: String,
        bodyColor: Color,
        accentColor: Color,
        mutedColor: Color,
        isListItem: Boolean,
        isQuote: Boolean,
    ) {
        var text = line
        // Blockquote marker
        if (isQuote) {
            withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
                append("▌ ")
            }
            text = text.trimStart('>').trimStart()
        }
        // List marker
        val ordered = orderedListPrefix(text)
        val bullet = when {
            ordered != null -> ordered
            text.startsWith("- ") || text.startsWith("* ") || text.startsWith("+ ") -> "• "
            else -> null
        }
        if (bullet != null) {
            withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
                append(if (ordered != null) ordered.trim() + " " else bullet)
            }
            text = if (ordered != null) text.removePrefix(ordered) else text.drop(2)
        }
        // ATX header
        val headerMatch = Regex("""^(#{1,6})\s+(.*)$""").find(text)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val size = when (level) {
                1 -> 20.sp
                2 -> 18.sp
                else -> 16.sp
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size, color = bodyColor)) {
                appendInlineSpans(headerMatch.groupValues[2], bodyColor, accentColor, mutedColor)
            }
            return
        }
        // Horizontal rule
        if (Regex("""^(-{3,}|\*{3,}|_{3,})\s*$""").matches(text)) {
            withStyle(SpanStyle(color = mutedColor)) {
                append("─".repeat(24))
            }
            return
        }
        // Quote body styling
        if (isQuote) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = mutedColor)) {
                appendInlineSpans(text, mutedColor, accentColor, mutedColor)
            }
            return
        }
        appendInlineSpans(text, bodyColor, accentColor, mutedColor)
    }

    private fun AnnotatedString.Builder.appendInlineSpans(
        text: String,
        bodyColor: Color,
        accentColor: Color,
        mutedColor: Color,
    ) {
        // Order matters: code, then bold+italic, bold, italic, strike, links.
        val token = Regex("(`[^`]+`|\\*\\*\\*.+?\\*\\*\\*|\\*\\*.+?\\*\\*|\\*[^*]+\\*|_[^_]+_|~~.+?~~|\\[[^\\]]+]\\([^)]+\\))")
        var pos = 0
        for (match in token.findAll(text)) {
            if (match.range.first > pos) {
                withStyle(SpanStyle(color = bodyColor)) {
                    append(text.substring(pos, match.range.first))
                }
            }
            val raw = match.value
            when {
                raw.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = mutedColor.copy(alpha = 0.18f), color = bodyColor),
                ) { append(raw.trim('`')) }
                raw.startsWith("***") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = bodyColor),
                ) { appendInlineSpans(raw.drop(3).dropLast(3), bodyColor, accentColor, mutedColor) }
                raw.startsWith("**") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = bodyColor),
                ) { appendInlineSpans(raw.drop(2).dropLast(2), bodyColor, accentColor, mutedColor) }
                raw.startsWith("~~") -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = mutedColor),
                ) { append(raw.drop(2).dropLast(2)) }
                raw.startsWith("[") -> {
                    val link = Regex("""\[(.+)]\((.+)\)""").find(raw)
                    if (link != null) {
                        pushStringAnnotation(tag = "URL", annotation = link.groupValues[2])
                        withStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)) {
                            append(link.groupValues[1])
                        }
                        pop()
                    } else {
                        withStyle(SpanStyle(color = bodyColor)) { append(raw) }
                    }
                }
                else -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic, color = bodyColor),
                ) { append(raw.trim('*', '_')) }
            }
            pos = match.range.last + 1
        }
        if (pos < text.length) {
            withStyle(SpanStyle(color = bodyColor)) {
                append(text.substring(pos))
            }
        }
    }
}

/**
 * Selectable, markdown-rendered text box for OCR output.
 *
 * Users can long-press to select any portion and copy it; the full raw text
 * is available to the caller's Copy button.
 */
@Composable
fun SelectableMarkdownOcrText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val accentColor = SsColors.NavyAccent
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBackground = SsColors.SurfaceVariant
    val annotated = remember(text) {
        OcrMarkdown.toAnnotatedString(text, bodyColor, accentColor, mutedColor, codeBackground)
    }
    SelectionContainer(modifier = modifier) {
        androidx.compose.material3.Text(
            text = annotated,
            style = OcrTextStyle.copy(color = bodyColor),
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
