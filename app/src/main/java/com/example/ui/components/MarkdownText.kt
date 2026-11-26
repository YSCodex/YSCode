// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors

internal sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class NumberedItem(val number: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    codeFontSizeSp: Int = 13
) {
    val colors = LocalYSCodeColors.current
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = parseInlineMarkdown(block.text, colors.surface2, colors.accent, colors.info),
                            style = style,
                            color = colors.text
                        )
                    }
                    is MdBlock.Bullet -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("•", color = colors.accent, fontWeight = FontWeight.Bold)
                            Text(
                                text = parseInlineMarkdown(block.text, colors.surface2, colors.accent, colors.info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MdBlock.NumberedItem -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = block.number,
                                color = colors.accent,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = parseInlineMarkdown(block.text, colors.surface2, colors.accent, colors.info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface2.copy(alpha = 0.6f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(colors.accent.copy(alpha = 0.8f))
                            )
                            Text(
                                text = parseInlineMarkdown(block.text, colors.surface2, colors.accent, colors.info),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    is MdBlock.HorizontalRule -> {
                        HorizontalDivider(
                            color = colors.border.copy(alpha = 0.65f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    is MdBlock.Table -> {
                        MarkdownTable(
                            headers = block.headers,
                            rows = block.rows
                        )
                    }
                    is MdBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text, colors.surface2, colors.accent, colors.info),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text
                        )
                    }
                    is MdBlock.CodeBlock -> {
                        CodeBlockCard(
                            language = block.lang,
                            code = block.code,
                            codeFontSizeSp = codeFontSizeSp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>
) {
    val colors = LocalYSCodeColors.current
    val colWidths = remember(headers, rows) {
        headers.indices.map { col ->
            val maxChars = (listOf(headers[col]) + rows.map { it.getOrElse(col) { "" } })
                .maxOfOrNull { stripInlineSyntax(it).length } ?: 6
            (maxChars.coerceIn(6, 28) * 8.5f + 22f).dp
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .background(colors.surface2)
                    .padding(vertical = 8.dp)
            ) {
                headers.forEachIndexed { colIdx, cell ->
                    Text(
                        text = parseInlineMarkdown(cell, colors.surface, colors.accent, colors.info),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        modifier = Modifier
                            .width(colWidths[colIdx])
                            .padding(horizontal = 10.dp)
                    )
                }
            }
            HorizontalDivider(color = colors.border)

            // Body rows
            rows.forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier
                        .background(if (rowIdx % 2 == 1) colors.surface2.copy(alpha = 0.25f) else colors.surface)
                        .padding(vertical = 8.dp)
                ) {
                    headers.indices.forEach { colIdx ->
                        val cell = row.getOrElse(colIdx) { "" }
                        Text(
                            text = parseInlineMarkdown(cell, colors.surface2, colors.accent, colors.info),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text,
                            modifier = Modifier
                                .width(colWidths[colIdx])
                                .padding(horizontal = 10.dp)
                        )
                    }
                }
                if (rowIdx < rows.lastIndex) {
                    HorizontalDivider(color = colors.border.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    codeFontSizeSp: Int = 13
) {
    val colors = LocalYSCodeColors.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = colors.textDim
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = colors.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = highlightCode(code, colors.text, colors.accent, colors.info, colors.success, colors.textDim),
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = codeFontSizeSp.sp,
                lineHeight = (codeFontSizeSp + 5).sp
            )
        }
    }
}

private val tableSeparatorRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val horizontalRuleRegex = Regex("""^(?:-{3,}|\*{3,}|_{3,})\s*$""")
private val numberedListRegex = Regex("""^(\d+\.)\s+(.*)$""")

internal fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val paraBuf = mutableListOf<String>()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paraBuf.joinToString("\n"))
            paraBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim()
            i++
            val codeBuf = mutableListOf<String>()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeBuf += lines[i]
                i++
            }
            blocks += MdBlock.CodeBlock(lang, codeBuf.joinToString("\n"))
            i++
            continue
        }

        if (trimmed.isBlank()) {
            flushPara()
            i++
            continue
        }

        if (horizontalRuleRegex.matches(trimmed)) {
            flushPara()
            blocks += MdBlock.HorizontalRule
            i++
            continue
        }

        // Markdown Pipe Table: header line + separator line
        val nextLine = lines.getOrNull(i + 1)?.trim()
        if (trimmed.contains('|') && nextLine != null && tableSeparatorRegex.matches(nextLine)) {
            flushPara()
            val headers = splitTableCells(trimmed)
            if (headers.isNotEmpty()) {
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.isBlank() || !rowLine.contains('|')) break
                    val cells = splitTableCells(rowLine)
                    val normalized = headers.indices.map { idx -> cells.getOrElse(idx) { "" } }
                    rows += normalized
                    i++
                }
                blocks += MdBlock.Table(headers, rows)
                continue
            }
        }

        if (trimmed.startsWith("#")) {
            flushPara()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushPara()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines += lines[i].trimStart().removePrefix(">").trimStart()
                i++
            }
            blocks += MdBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            flushPara()
            blocks += MdBlock.Bullet(trimmed.drop(2))
            i++
            continue
        }

        val numMatch = numberedListRegex.matchEntire(trimmed)
        if (numMatch != null) {
            flushPara()
            blocks += MdBlock.NumberedItem(numMatch.groupValues[1], numMatch.groupValues[2])
            i++
            continue
        }

        paraBuf += line
        i++
    }
    flushPara()
    return blocks
}

internal fun splitTableCells(row: String): List<String> {
    var clean = row.trim()
    if (clean.startsWith("|")) clean = clean.drop(1)
    if (clean.endsWith("|")) clean = clean.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < clean.length) {
        val ch = clean[i]
        if (ch == '\\' && i + 1 < clean.length && clean[i + 1] == '|') {
            cur.append('|')
            i += 2
        } else if (ch == '|') {
            cells += cur.toString().trim()
            cur.clear()
            i++
        } else {
            cur.append(ch)
            i++
        }
    }
    cells += cur.toString().trim()
    return cells
}

private fun stripInlineSyntax(text: String): String {
    return text
        .replace(Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
}

private fun parseInlineMarkdown(
    text: String,
    codeBg: Color,
    accent: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen).trim()
                        if (url.startsWith("file://", ignoreCase = true)) {
                            pushStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = linkColor))
                            append(label)
                            pop()
                            i = closeParen + 1
                            continue
                        }
                        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                            withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                )
                            ) {
                                append(label)
                            }
                            i = closeParen + 1
                            continue
                        }
                    }
                }
                append('[')
                i++
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append('*')
                    i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            background = codeBg,
                            color = accent,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" ${text.substring(i + 1, end)} ")
                    }
                    i = end + 1
                } else {
                    append('`')
                    i++
                }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('*')
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

fun highlightCode(
    code: String,
    baseColor: Color,
    keywordColor: Color,
    typeColor: Color,
    stringColor: Color,
    commentColor: Color
): AnnotatedString {
    val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for",
        "while", "import", "package", "suspend", "private", "override", "data", "sealed", "enum",
        "def", "async", "await", "from", "try", "except", "const", "let", "function", "export", "true", "false", "null"
    )
    return buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = baseColor), 0, code.length)

        // Comments
        Regex("//.*|#.*").findAll(code).forEach {
            addStyle(SpanStyle(color = commentColor), it.range.first, it.range.last + 1)
        }
        // Strings
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'").findAll(code).forEach {
            addStyle(SpanStyle(color = stringColor), it.range.first, it.range.last + 1)
        }
        // Keywords
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(code).forEach {
            val w = it.value
            when {
                w in keywords -> addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium), it.range.first, it.range.last + 1)
                w.firstOrNull()?.isUpperCase() == true -> addStyle(SpanStyle(color = typeColor), it.range.first, it.range.last + 1)
            }
        }
    }
}
