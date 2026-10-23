package com.pockivo.nowmark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.ui.theme.Nm

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text("N", color = Nm.c.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Nm.c.ink)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Nm.c.panel)
    ) { content() }
}

/** Label/value row used in the saved & status panels. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Nm.c.ink, divider: Boolean = true) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Nm.c.inkSoft, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        if (divider) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = Nm.c.line)
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
) {
    val bg = if (selected) Nm.c.ink else Nm.c.panel
    val fg = if (selected) Nm.c.panel else Nm.c.ink
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) bg else Nm.c.line, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (dashed) "+ $text" else text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Small status tag such as "표시 5" or "✓ 길이 확인". */
@Composable
fun Tag(text: String, fg: Color, bg: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, bg: Color, size: Int = 52) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size / 2).dp)) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Nm.c.inkSoft,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * Bars from real peaks. [highlights] are fractions (0..1) of the width drawn in accent color,
 * [progress] draws the playhead. Mirrors for RTL.
 */
@Composable
fun WaveformBars(
    bars: FloatArray,
    modifier: Modifier = Modifier,
    highlights: List<ClosedFloatingPointRange<Float>> = emptyList(),
    progress: Float? = null,
    base: Color = Nm.c.inkSoft.copy(alpha = 0.45f),
    accent: Color = Nm.c.accent,
    highlightBg: Color? = null,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val n = bars.size
        if (n == 0) return@Canvas
        fun x(f: Float) = if (rtl) size.width * (1f - f) else size.width * f
        highlightBg?.let { bg ->
            highlights.forEach { r ->
                val a = x(r.start); val b = x(r.endInclusive)
                drawRoundRect(bg, Offset(minOf(a, b), 0f), Size(kotlin.math.abs(b - a), size.height), CornerRadius(8f, 8f))
            }
        }
        val step = size.width / n
        val w = (step * 0.55f).coerceAtLeast(1.5f)
        for (i in 0 until n) {
            val f = (i + 0.5f) / n
            val h = (bars[i].coerceIn(0.02f, 1f)) * size.height
            val inHl = highlights.any { f in it }
            val color = if (inHl) accent else base
            val cx = x(f)
            drawRoundRect(color, Offset(cx - w / 2, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2))
        }
        progress?.let {
            val px = x(it.coerceIn(0f, 1f))
            drawLine(Color(0xFF161314), Offset(px, 0f), Offset(px, size.height), strokeWidth = 4f)
        }
    }
}

fun rangeFractions(ranges: List<TimeRange>, durationMs: Long): List<ClosedFloatingPointRange<Float>> =
    if (durationMs <= 0) emptyList()
    else ranges.map { (it.startMs.toFloat() / durationMs)..(it.endMs.toFloat() / durationMs) }

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = Nm.c.accent,
    fg: Color = Color.White,
    icon: ImageVector? = null,
    sub: String? = null,
    enabled: Boolean = true,
    height: Int = 64,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(horizontalAlignment = if (sub != null) Alignment.Start else Alignment.CenterHorizontally) {
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nm.c.inkSoft.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Nm.c.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Nm.c.ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RowScope.SoftButton(text: String, icon: ImageVector, onClick: () -> Unit, dark: Boolean = false) {
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Nm.c.ink else Nm.c.line.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (dark) Nm.c.panel else Nm.c.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (dark) Nm.c.panel else Nm.c.ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(icon, Nm.c.accent, Nm.c.accentTint, 64)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
