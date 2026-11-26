// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.data.DailyUsage
import com.example.ui.theme.LocalYSCodeColors
import java.util.TimeZone
import kotlin.math.ceil

private val HeatLevels = listOf(
    Color(0xFF0E4429),
    Color(0xFF006D32),
    Color(0xFF26A641),
    Color(0xFF39D353)
)

private fun heatPalette(dark: Boolean): List<Color> =
    listOf(if (dark) Color(0xFF1B2028) else Color(0xFFE4E7EB)) + HeatLevels

private fun fmtCompact(n: Long): String = com.example.util.formatTokens(n)

/** GitHub-contributions style activity heatmap + lifetime usage stats (all computed locally). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsCard(daily: List<DailyUsage>, modifier: Modifier = Modifier) {
    val colors = LocalYSCodeColors.current
    val heat = heatPalette(colors.surface.luminance() < 0.5f)
    var metric by remember { mutableIntStateOf(0) } // 0 tokens, 1 messages, 2 sessions
    val labels = listOf("Tokens", "Messages", "Sessions")
    var expanded by remember { mutableStateOf(true) }

    val tz = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }
    val today = (System.currentTimeMillis() + tz) / 86_400_000L
    val weeks = 18

    val valueByDay = remember(daily, metric) {
        daily.associate { d ->
            d.day to when (metric) {
                0 -> d.tokens
                1 -> d.messages
                else -> d.sessions
            }
        }
    }
    val activeDays = remember(daily) { daily.filter { it.tokens > 0 || it.messages > 0 }.map { it.day }.toSet() }
    val maxValue = (valueByDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)

    val currentStreak = remember(activeDays, today) {
        var d = if (today in activeDays) today else today - 1
        var c = 0
        while (d in activeDays) { c++; d-- }
        c
    }
    val longestStreak = remember(activeDays) {
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (d in activeDays.sorted()) {
            run = if (d == prev + 1) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        best
    }
    val totalTokens = daily.sumOf { it.tokens }
    val totalMessages = daily.sumOf { it.messages }
    val totalCost = daily.sumOf { it.cost }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Your activity", style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text(
                    if (expanded) "Hide" else "${fmtCompact(totalTokens)} tokens - ${currentStreak}d streak",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textDim
                )
            }
            if (expanded) {

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    FilterChip(
                        selected = metric == i,
                        onClick = { metric = i },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            val dim = colors.textDim
            val todayDow = ((today + 4) % 7).toInt() // epoch day 0 was a Thursday; Sunday = 0
            Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
                val gap = 3.dp.toPx()
                val cell = ((size.width - gap * (weeks - 1)) / weeks).coerceAtMost((size.height - gap * 6) / 7)
                for (w in 0 until weeks) {
                    for (r in 0 until 7) {
                        val day = today - ((weeks - 1 - w) * 7 + (todayDow - r))
                        if (day > today) continue
                        val v = valueByDay[day] ?: 0L
                        val level = if (v <= 0L) 0 else ceil(4.0 * v / maxValue).toInt().coerceIn(1, 4)
                        drawRoundRect(
                            color = heat[level],
                            topLeft = Offset(w * (cell + gap), r * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Less", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                Canvas(modifier = Modifier.padding(top = 3.dp).height(10.dp).width(86.dp)) {
                    val c = 10.dp.toPx()
                    heat.forEachIndexed { i, col ->
                        drawRoundRect(col, Offset(i * (c + 4.dp.toPx()), 0f), Size(c, c), CornerRadius(2.dp.toPx()))
                    }
                }
                Text("More", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
            }

            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("Tokens", fmtCompact(totalTokens), Modifier.weight(1f))
                StatCell("Messages", fmtCompact(totalMessages), Modifier.weight(1f))
                StatCell("Est. cost", "$" + String.format("%.2f", totalCost), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("Active days", activeDays.size.toString(), Modifier.weight(1f))
                StatCell("Streak", "$currentStreak d", Modifier.weight(1f))
                StatCell("Longest", "$longestStreak d", Modifier.weight(1f))
            }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalYSCodeColors.current
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 1)
    }
}
