package org.opencrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders markdown-formatted text as Compose AnnotatedString.
 * Supports: **bold**, *italic*, `inline code`, ```code blocks```,
 * [links](url), - bullet lists, and # headings.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val uriHandler = LocalUriHandler.current
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary

    // Split into code blocks vs inline segments (memoized)
    val segments = remember(text) { splitCodeBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in segments) {
            if (segment.isCodeBlock) {
                // Fenced code block
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(codeBackground)
                        .padding(10.dp)
                ) {
                    Text(
                        text = segment.content,
                        style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (style.fontSize.value - 1).sp,
                            color = color
                        )
                    )
                }
            } else {
                // Parse inline markdown per line for list/heading support
                val lines = segment.content.split("\n")
                for (line in lines) {
                    if (line.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        continue
                    }
                    val (annotated, urlMap) = remember(line, color, linkColor, codeBackground) {
                        parseInlineMarkdown(
                            line = line,
                            baseStyle = style.copy(color = color),
                            linkColor = linkColor,
                            codeBackground = codeBackground
                        )
                    }
                    if (urlMap.isNotEmpty()) {
                        ClickableText(
                            text = annotated,
                            style = style.copy(color = color),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
                            }
                        )
                    } else {
                        Text(text = annotated)
                    }
                }
            }
        }
    }
}


private data class TextSegment(val content: String, val isCodeBlock: Boolean)

private fun splitCodeBlocks(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    val pattern = Regex("```(?:\\w*\\n)?([\\s\\S]*?)```")
    var lastEnd = 0

    for (match in pattern.findAll(text)) {
        val before = text.substring(lastEnd, match.range.first)
        if (before.isNotEmpty()) segments += TextSegment(before.trim(), false)
        segments += TextSegment(match.groupValues[1].trimEnd(), true)
        lastEnd = match.range.last + 1
    }
    val remaining = text.substring(lastEnd)
    if (remaining.isNotEmpty()) segments += TextSegment(remaining.trim(), false)
    return segments
}

private fun parseInlineMarkdown(
    line: String,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): Pair<AnnotatedString, Map<String, String>> {
    val urlMap = mutableMapOf<String, String>()

    // Detect heading
    val headingMatch = Regex("^(#{1,3})\\s+(.*)").matchEntire(line.trim())
    // Detect bullet list
    val bulletMatch = Regex("^[-*]\\s+(.*)").matchEntire(line.trim())

    val rawLine = when {
        headingMatch != null -> headingMatch.groupValues[2]
        bulletMatch != null -> "• ${bulletMatch.groupValues[1]}"
        else -> line
    }

    val headingLevel = headingMatch?.groupValues?.get(1)?.length

    val builder = AnnotatedString.Builder()

    // Tokenize inline markdown: **bold**, *italic*, `code`, [text](url)
    val inlinePattern = Regex(
        "\\*\\*(.+?)\\*\\*" +          // group 1: bold
        "|\\*(.+?)\\*" +               // group 2: italic
        "|`([^`]+)`" +                 // group 3: inline code
        "|\\[([^]]+)]\\(([^)]+)\\)"    // group 4: link text, group 5: url
    )

    var cursor = 0
    for (match in inlinePattern.findAll(rawLine)) {
        // Append plain text before this match
        if (match.range.first > cursor) {
            builder.appendPlain(rawLine.substring(cursor, match.range.first), headingLevel, baseStyle)
        }

        when {
            match.groupValues[1].isNotEmpty() -> {
                // Bold
                builder.withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold)
                ) { append(match.groupValues[1]) }
            }
            match.groupValues[2].isNotEmpty() -> {
                // Italic
                builder.withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic)
                ) { append(match.groupValues[2]) }
            }
            match.groupValues[3].isNotEmpty() -> {
                // Inline code
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        fontSize = (baseStyle.fontSize.value - 1).sp
                    )
                ) { append(" ${match.groupValues[3]} ") }
            }
            match.groupValues[4].isNotEmpty() -> {
                // Link
                val linkText = match.groupValues[4]
                val url = match.groupValues[5]
                urlMap[linkText] = url
                builder.pushStringAnnotation("URL", url)
                builder.withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) { append(linkText) }
                builder.pop()
            }
        }
        cursor = match.range.last + 1
    }

    // Remaining plain text
    if (cursor < rawLine.length) {
        builder.appendPlain(rawLine.substring(cursor), headingLevel, baseStyle)
    }

    // Apply heading style to entire string if needed
    if (headingLevel != null) {
        val full = builder.toAnnotatedString()
        val headingStyle = when (headingLevel) {
            1 -> SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
            2 -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        return buildAnnotatedString {
            withStyle(headingStyle) { append(full) }
        } to urlMap
    }

    return builder.toAnnotatedString() to urlMap
}

private fun AnnotatedString.Builder.appendPlain(
    text: String,
    @Suppress("UNUSED_PARAMETER") headingLevel: Int?,
    @Suppress("UNUSED_PARAMETER") baseStyle: TextStyle
) {
    append(text)
}
