package org.opencrow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.IntrinsicSize
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
 * [links](url), - bullet lists, # headings, and GFM tables.
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

    // Split into code blocks, tables, and inline segments (memoized)
    val segments = remember(text) { splitSegments(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in segments) {
            when (segment) {
                is Segment.Code -> {
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
                }
                is Segment.Table -> {
                    MarkdownTable(
                        headers = segment.headers,
                        rows = segment.rows,
                        color = color,
                        style = style
                    )
                }
                is Segment.Text -> {
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
}

@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    color: Color,
    style: TextStyle
) {
    val borderColor = color.copy(alpha = 0.15f)
    val headerBackground = color.copy(alpha = 0.07f)

    // Column-major layout: each column is a Column{} with width(IntrinsicSize.Max) so
    // all cells in a column share the same width (widest cell wins), making headers and
    // data cells align correctly. The outer Row has height(IntrinsicSize.Min) so the
    // vertical-divider Boxes can use fillMaxHeight().
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .height(IntrinsicSize.Min)
    ) {
        headers.forEachIndexed { colIndex, header ->
            // Vertical divider between columns
            if (colIndex > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(borderColor)
                )
            }
            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                // Header cell
                Box(
                    modifier = Modifier
                        .background(headerBackground)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = header,
                        style = style.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = color
                        ),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .widthIn(min = 60.dp)
                    )
                }
                HorizontalDivider(color = borderColor)
                // Data cells
                rows.forEachIndexed { rowIndex, row ->
                    val cell = row.getOrElse(colIndex) { "" }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (rowIndex % 2 == 1)
                                    Modifier.background(color.copy(alpha = 0.04f))
                                else Modifier
                            )
                    ) {
                        Text(
                            text = cell,
                            style = style.copy(color = color.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                .widthIn(min = 60.dp)
                        )
                    }
                    if (rowIndex < rows.lastIndex) {
                        HorizontalDivider(color = borderColor)
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
            contentScale = ContentScale.FillWidth
        )
    }
}

// ---------------------------------------------------------------------------
// Segment model
// ---------------------------------------------------------------------------

private sealed class Segment {
    data class Text(val content: String) : Segment()
    data class Code(val content: String) : Segment()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Segment()
}

// ---------------------------------------------------------------------------
// Splitting logic
// ---------------------------------------------------------------------------

/**
 * Splits raw markdown text into [Segment.Code], [Segment.Table], and [Segment.Text] segments.
 */
private fun splitSegments(text: String): List<Segment> {
    // First split by fenced code blocks
    val afterCodeSplit = splitCodeBlocks(text)

    // Then, for each text segment, extract GFM tables
    val result = mutableListOf<Segment>()
    for (seg in afterCodeSplit) {
        if (seg.isCodeBlock) {
            result += Segment.Code(seg.content)
        } else {
            result += extractTables(seg.content)
        }
    }
    return result
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

/**
 * Scans a plain-text segment for GFM table blocks and returns a mixed list of
 * [Segment.Text] and [Segment.Table] segments.
 *
 * A GFM table looks like:
 *   | Col1 | Col2 |
 *   |------|------|
 *   | val1 | val2 |
 */
private fun extractTables(text: String): List<Segment> {
    val lines = text.split("\n")
    val result = mutableListOf<Segment>()
    val pendingText = StringBuilder()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Detect a header row followed by a separator row
        if (isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            // Flush pending text
            val pending = pendingText.toString().trim()
            if (pending.isNotEmpty()) result += Segment.Text(pending)
            pendingText.clear()

            val headers = parseTableRow(line)
            i += 2 // skip header + separator

            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i])) {
                rows += parseTableRow(lines[i])
                i++
            }
            result += Segment.Table(headers, rows)
        } else {
            pendingText.appendLine(line)
            i++
        }
    }
    val pending = pendingText.toString().trim()
    if (pending.isNotEmpty()) result += Segment.Text(pending)
    return result
}

private fun isTableRow(line: String): Boolean =
    line.trim().startsWith("|") && line.trim().endsWith("|")

private fun isTableSeparator(line: String): Boolean =
    line.trim().matches(Regex("\\|[-|: ]+\\|"))

private fun parseTableRow(line: String): List<String> =
    line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }

// ---------------------------------------------------------------------------
// Inline markdown parsing
// ---------------------------------------------------------------------------

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

/**
 * Parses the structural tokens from a markdown line (expensive regex work).
 * This result is memoized independently of colors/styles.
 */
private fun parseInlineMarkdownStructure(line: String): InlineMarkdownStructure {
    val headingMatch = Regex("^(#{1,3})\\s+(.*)").matchEntire(line.trim())
    val bulletMatch = Regex("^[-*]\\s+(.*)").matchEntire(line.trim())

    val rawLine = when {
        headingMatch != null -> headingMatch.groupValues[2]
        bulletMatch != null -> "- ${bulletMatch.groupValues[1]}"
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
