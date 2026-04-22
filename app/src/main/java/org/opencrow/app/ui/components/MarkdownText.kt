package org.opencrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
                val lines = remember(segment.content) { segment.content.split("\n") }
                for (line in lines) {
                    if (line.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        continue
                    }
                    // Memoize the structural parse separately from colors
                    val parsed = remember(line) {
                        parseInlineMarkdownStructure(line)
                    }
                    val (annotated, urlMap) = remember(parsed, color, linkColor, codeBackground) {
                        applyInlineMarkdownStyle(
                            parsed = parsed,
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

                    // Render images and files found in this line
                    val images = parsed.tokens.filterIsInstance<InlineToken.Image>()
                    val files = parsed.tokens.filterIsInstance<InlineToken.FileAttachment>()
                    
                    if (images.isNotEmpty() || files.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (img in images) {
                                ImageAttachment(alt = img.alt, url = img.url)
                            }
                            for (file in files) {
                                FileAttachmentRow(name = file.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageAttachment(alt: String, url: String) {
    val context = LocalContext.current
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(context)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = alt,
            modifier = Modifier.fillMaxWidth(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
        )
    }
}


private data class TextSegment(val content: String, val isCodeBlock: Boolean)

/**
 * Intermediate structural representation of parsed inline markdown.
 * Separates the expensive regex parsing from the cheap style application.
 */
private data class InlineMarkdownStructure(
    val rawLine: String,
    val headingLevel: Int?,
    val tokens: List<InlineToken>
)

private sealed class InlineToken {
    data class Plain(val text: String) : InlineToken()
    data class Bold(val text: String) : InlineToken()
    data class Italic(val text: String) : InlineToken()
    data class Code(val text: String) : InlineToken()
    data class Link(val text: String, val url: String) : InlineToken()
    data class Image(val alt: String, val url: String) : InlineToken()
    data class FileAttachment(val name: String) : InlineToken()
}

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

/**
 * Parses the structural tokens from a markdown line (expensive regex work).
 * This result is memoized independently of colors/styles.
 */
private fun parseInlineMarkdownStructure(line: String): InlineMarkdownStructure {
    val headingMatch = Regex("^(#{1,3})\\s+(.*)").matchEntire(line.trim())
    val bulletMatch = Regex("^[-*]\\s+(.*)").matchEntire(line.trim())

    val rawLine = when {
        headingMatch != null -> headingMatch.groupValues[2]
        bulletMatch != null -> "• ${bulletMatch.groupValues[1]}"
        else -> line
    }

    val headingLevel = headingMatch?.groupValues?.get(1)?.length

    val inlinePattern = Regex(
        "\\*\\*(.+?)\\*\\*" +
        "|\\*(.+?)\\*" +
        "|`([^`]+)`" +
        "|(!\\[.*?\\]\\(.*?\\))" +
        "|(\\[[^]]+]\\([^)]+\\))" +
        "|\\[Attached file: ([^]]+)]" +
        "|📎\\s*(.+)"
    )

    val tokens = mutableListOf<InlineToken>()
    var cursor = 0
    for (match in inlinePattern.findAll(rawLine)) {
        if (match.range.first > cursor) {
            tokens.add(InlineToken.Plain(rawLine.substring(cursor, match.range.first)))
        }
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> tokens.add(InlineToken.Bold(g[1]))
            g[2].isNotEmpty() -> tokens.add(InlineToken.Italic(g[2]))
            g[3].isNotEmpty() -> tokens.add(InlineToken.Code(g[3]))
            g[4].isNotEmpty() -> {
                // Image tag: ![alt](url)
                val full = g[4]
                val alt = full.substringAfter("[").substringBefore("]")
                val url = full.substringAfter("(").substringBeforeLast(")")
                tokens.add(InlineToken.Image(alt, url))
            }
            g[5].isNotEmpty() -> {
                // Link tag: [text](url)
                val m = Regex("\\[([^]]+)]\\(([^)]+)\\)").matchEntire(g[5])
                if (m != null) tokens.add(InlineToken.Link(m.groupValues[1], m.groupValues[2]))
            }
            g[6].isNotEmpty() -> tokens.add(InlineToken.FileAttachment(g[6]))
            g[7].isNotEmpty() -> tokens.add(InlineToken.FileAttachment(g[7]))
        }
        cursor = match.range.last + 1
    }
    if (cursor < rawLine.length) {
        tokens.add(InlineToken.Plain(rawLine.substring(cursor)))
    }

    return InlineMarkdownStructure(rawLine, headingLevel, tokens)
}

/**
 * Applies colors/styles to pre-parsed structural tokens (cheap).
 */
private fun applyInlineMarkdownStyle(
    parsed: InlineMarkdownStructure,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): Pair<AnnotatedString, Map<String, String>> {
    val urlMap = mutableMapOf<String, String>()
    val builder = AnnotatedString.Builder()

    for (token in parsed.tokens) {
        when (token) {
            is InlineToken.Plain -> builder.append(token.text)
            is InlineToken.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.text) }
            is InlineToken.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.text) }
            is InlineToken.Code -> builder.withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    fontSize = (baseStyle.fontSize.value - 1).sp
                )
            ) { append(" ${token.text} ") }
            is InlineToken.Link -> {
                urlMap[token.text] = token.url
                builder.pushStringAnnotation("URL", token.url)
                builder.withStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                ) { append(token.text) }
                builder.pop()
            }
            is InlineToken.Image -> {
                // Handled visually as separate Composables, but must be exhaustive
            }
            is InlineToken.FileAttachment -> {
                // Handled visually as separate Composables, but must be exhaustive
            }
        }
    }

    if (parsed.headingLevel != null) {
        val full = builder.toAnnotatedString()
        val headingStyle = when (parsed.headingLevel) {
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

@Composable
private fun FileAttachmentRow(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
