package com.pockivo.nowmark.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.audio.Waveforms
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.data.AppSettings
import com.pockivo.nowmark.data.HealthEventEntity
import com.pockivo.nowmark.data.MarkEntity
import com.pockivo.nowmark.data.MetadataSource
import com.pockivo.nowmark.data.NoteEntity
import com.pockivo.nowmark.data.RecordingEntity
import com.pockivo.nowmark.data.VerifyStatus
import com.pockivo.nowmark.player.PlayerHub
import com.pockivo.nowmark.player.QueueItem
import com.pockivo.nowmark.recorder.RecorderState
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.IconTile
import com.pockivo.nowmark.ui.components.WaveformBars
import com.pockivo.nowmark.ui.components.rangeFractions
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface TimelineEntry { val at: Long }
private data class TMark(val m: MarkEntity) : TimelineEntry { override val at get() = m.startMs }
private data class TNote(val n: NoteEntity) : TimelineEntry { override val at get() = n.tMs }
private data class TEvent(val e: HealthEventEntity) : TimelineEntry { override val at get() = e.startMs }

@Composable
fun DetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    val rec by remember(id) { dao.observeRecording(id) }.collectAsState(initial = null)
    val marks by remember(id) { dao.observeMarks(id) }.collectAsState(initial = emptyList())
    val notes by remember(id) { dao.observeNotes(id) }.collectAsState(initial = emptyList())
    val events by remember(id) { dao.observeEvents(id) }.collectAsState(initial = emptyList())
    val settings by AppGraph.settings.settings.collectAsState(initial = AppSettings())
    val player by PlayerHub.ui.collectAsState()
    val live by RecorderState.state.collectAsState()
    var highlights by remember { mutableStateOf(false) }
    var peaks by remember { mutableStateOf<ByteArray?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var sheetMark by remember { mutableStateOf<MarkEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }

    val r = rec ?: run {
        Box(Modifier.fillMaxSize()) { BackBar(nav) }
        return
    }
    val duration = r.durationMs
    val ranges = marks.mapNotNull { MarkMath.clamp(TimeRange(it.startMs, it.endMs), duration) }
    val merged = MarkMath.union(ranges)
    val highlightTotal = merged.sumOf { it.lengthMs }
    val isOurs = player.current?.recordingId == id

    LaunchedEffect(r.uri) {
        missing = !AudioStore.exists(context, Uri.parse(r.uri))
        peaks = Waveforms.load(context, id, Uri.parse(r.uri))
    }
    LaunchedEffect(isOurs, player.isPlaying) {
        while (isOurs) {
            position = PlayerHub.originalPositionMs()
            delay(200)
        }
    }

    fun queueFor(h: Boolean): List<QueueItem> =
        if (h) merged.map { QueueItem(id, r.title, r.createdAt, r.uri, it) }
        else listOf(QueueItem(id, r.title, r.createdAt, r.uri, null))

    fun playFrom(originalMs: Long, h: Boolean = highlights) {
        if (live.isActive) { toast(context, R.string.no_playback_while_recording); return }
        val q = queueFor(h)
        if (h) {
            val idx = merged.indexOfFirst { originalMs < it.endMs }.coerceAtLeast(0)
            val off = (originalMs - (merged.getOrNull(idx)?.startMs ?: 0)).coerceAtLeast(0)
            PlayerHub.play(context, q, idx, off)
        } else {
            PlayerHub.play(context, q, 0, originalMs)
        }
        PlayerHub.setSkipSilence(context, settings.skipSilence || player.skipSilence)
    }

    fun playRange(range: TimeRange, repeat: Boolean) {
        if (live.isActive) { toast(context, R.string.no_playback_while_recording); return }
        PlayerHub.play(context, listOf(QueueItem(id, r.title, r.createdAt, r.uri, range)))
        PlayerHub.setRepeatOne(repeat)
    }

    val timeline: List<TimelineEntry> = remember(marks, notes, events) {
        (marks.map { TMark(it) } + notes.map { TNote(it) } + events.map { TEvent(it) }).sortedBy { it.at }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more), tint = Nm.c.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.folder_tags)) }, onClick = { menu = false; nav.navigate(Routes.saved(id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.trim_copy)) }, onClick = { menu = false; nav.navigate(Routes.trim(id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_full)) }, onClick = {
                        menu = false
                        val send = Intent(Intent.ACTION_SEND).setType(com.pockivo.nowmark.core.AudioFormatKind.from(r.format).mime)
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_full)))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = {
                        menu = false
                        scope.launch { dao.setDeletedAt(id, System.currentTimeMillis()) }
                        PlayerHub.stop()
                        nav.popBackStack()
                    })
                }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item {
                Text(r.title, style = MaterialTheme.typography.headlineMedium, color = Nm.c.ink)
                Text(
                    TimeFormat.duration(duration) + " · " + stringResource(verifyLabel(r)),
                    style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
                )
                if (r.metadataSource == MetadataSource.AUDIO_ONLY) {
                    Text(stringResource(R.string.audio_only_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.warn)
                }
                if (missing) {
                    Text(stringResource(R.string.audio_missing), style = MaterialTheme.typography.bodyMedium, color = Nm.c.warn)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nm.c.line.copy(alpha = 0.6f)).padding(4.dp),
                ) {
                    Segment(stringResource(R.string.seg_full, TimeFormat.duration(duration)), !highlights, Modifier.weight(1f)) {
                        highlights = false
                        if (isOurs) playFrom(position, false)
                    }
                    Segment(stringResource(R.string.seg_highlights, TimeFormat.duration(highlightTotal)), highlights, Modifier.weight(1f)) {
                        if (merged.isEmpty()) { toast(context, R.string.no_marks_yet); return@Segment }
                        highlights = true
                        if (isOurs) playFrom(position, true)
                    }
                }
                Spacer(Modifier.height(14.dp))
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                WaveformBars(
                    Waveforms.bars(peaks ?: ByteArray(0), 72),
                    Modifier.fillMaxWidth().height(96.dp).pointerInput(duration, rtl, isOurs, highlights, merged) {
                        detectTapGestures { o ->
                            var f = o.x / size.width
                            if (rtl) f = 1f - f
                            val ms = (f * duration).toLong()
                            if (isOurs && !highlights) PlayerHub.seekOriginal(ms) else playFrom(ms, false).also { highlights = false }
                        }
                    },
                    highlights = rangeFractions(merged, duration),
                    progress = if (isOurs && duration > 0) position.toFloat() / duration else null,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(TimeFormat.duration(if (isOurs) position else 0), style = MaterialTheme.typography.labelMedium, color = Nm.c.inkSoft, modifier = Modifier.weight(1f))
                    Text(TimeFormat.duration(duration), style = MaterialTheme.typography.labelMedium, color = Nm.c.inkSoft)
                }
                if (highlights && isOurs) {
                    val cur = player.current?.range
                    if (cur != null) {
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nm.c.accentTint).padding(16.dp),
                        ) {
                            Text(
                                "${player.index + 1} / ${player.queue.size} · ${TimeFormat.duration(cur.startMs)} – ${TimeFormat.duration(cur.endMs)}",
                                style = MaterialTheme.typography.labelMedium, color = Nm.c.accent,
                            )
                            val label = marks.firstOrNull { it.startMs >= cur.startMs && it.startMs < cur.endMs }
                            Text(
                                label?.memo?.takeIf { it.isNotBlank() } ?: stringResource(Labels.category(label?.category ?: com.pockivo.nowmark.data.MarkCategory.IMPORTANT)),
                                style = MaterialTheme.typography.titleMedium, color = Nm.c.ink,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                            val t = if (isOurs) position else 0L
                            val reserved = MarkMath.clamp(MarkMath.reserve(t, settings.markBeforeMs, settings.markAfterMs), duration)
                            if (reserved != null) scope.launch {
                                dao.upsertMarks(listOf(MarkEntity(UUID.randomUUID().toString(), id, t, reserved.startMs, reserved.endMs, createdAt = System.currentTimeMillis())))
                            }
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, null, tint = Nm.c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_mark_here), style = MaterialTheme.typography.labelLarge, color = Nm.c.accent)
                    }
                }
                HorizontalDivider(color = Nm.c.line)
            }
            items(timeline, key = {
                when (it) { is TMark -> "m" + it.m.id; is TNote -> "n" + it.n.id; is TEvent -> "e" + it.e.rowId }
            }) { entry ->
                when (entry) {
                    is TMark -> MarkRow(entry.m, active = isOurs && position in entry.m.startMs until entry.m.endMs,
                        onPlay = { playRange(TimeRange(entry.m.startMs, entry.m.endMs), false); highlights = false },
                        onMore = { sheetMark = entry.m })
                    is TNote -> TimelineRow(Icons.AutoMirrored.Outlined.Notes, entry.n.text, TimeFormat.duration(entry.n.tMs), Nm.c.accent, Nm.c.accentTint) {
                        playFrom(entry.n.tMs, false); highlights = false
                    }
                    is TEvent -> TimelineRow(
                        Icons.Outlined.WarningAmber, stringResource(Labels.health(entry.e.type)),
                        TimeFormat.duration(entry.e.startMs) + (entry.e.endMs?.let { " – " + TimeFormat.duration(it) } ?: ""),
                        Nm.c.warn, Nm.c.warnTint,
                    ) { playFrom((entry.e.startMs - 5_000).coerceAtLeast(0), false); highlights = false }
                }
                HorizontalDivider(color = Nm.c.line)
            }
            if (timeline.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.timeline_empty), style = MaterialTheme.typography.bodyMedium,
                        color = Nm.c.inkSoft, modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }
        }

        // Transport. No ads anywhere on this screen.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SmallChip(speedLabel(player.speed)) {
                val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                val next = speeds[(speeds.indexOfFirst { it == player.speed } + 1).mod(speeds.size)]
                PlayerHub.setSpeed(next)
            }
            IconButton(onClick = { PlayerHub.skipBy(-10_000) }) { Icon(Icons.Outlined.Replay10, stringResource(R.string.back_10), tint = Nm.c.ink) }
            val playLabel = stringResource(if (isOurs && player.isPlaying) R.string.pause else R.string.play)
            IconButton(
                onClick = { if (isOurs) PlayerHub.toggle(context) else playFrom(0) },
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Nm.c.accent),
            ) {
                Icon(if (isOurs && player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, playLabel, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { PlayerHub.skipBy(10_000) }) { Icon(Icons.Outlined.Forward10, stringResource(R.string.forward_10), tint = Nm.c.ink) }
            SmallChip(stringResource(R.string.skip_silence), selected = player.skipSilence) {
                PlayerHub.setSkipSilence(context, !player.skipSilence)
            }
        }
        if (isOurs && player.repeatOne) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Repeat, null, tint = Nm.c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.repeating_section), style = MaterialTheme.typography.labelMedium, color = Nm.c.accent,
                    modifier = Modifier.clickable { PlayerHub.setRepeatOne(false) })
            }
        }
    }

    sheetMark?.let { m ->
        MarkSheet(
            mark = m, recording = r,
            onDismiss = { sheetMark = null },
            onListen = { range, repeat -> playRange(range, repeat); highlights = false },
        )
    }
    if (renaming) {
        RenameDialog(r.title, onDismiss = { renaming = false }) { t -> scope.launch { dao.rename(id, t) }; renaming = false }
    }
}

fun speedLabel(speed: Float): String =
    java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(speed) + "×"

fun verifyLabel(r: RecordingEntity): Int = when (r.verifyStatus) {
    VerifyStatus.MATCH -> R.string.length_checked
    VerifyStatus.MISMATCH -> R.string.length_differs
    VerifyStatus.UNVERIFIED -> R.string.length_unverified
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Nm.c.panel else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Nm.c.accent else Nm.c.ink, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SmallChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
            .background(if (selected) Nm.c.accentTint else Nm.c.panel)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Nm.c.accent else Nm.c.ink, maxLines = 1) }
}

@Composable
private fun MarkRow(m: MarkEntity, active: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) Nm.c.accentTint else Color.Transparent)
            .clickable(onClick = onPlay).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Labels.categoryIcon(m.category), Nm.c.accent, Nm.c.accentTint, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.memo.ifBlank { stringResource(Labels.category(m.category)) },
                style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val state = when {
                m.done -> stringResource(Labels.doneAction(m.category))
                m.reviewed -> stringResource(R.string.reviewed)
                else -> null
            }
            Text(
                TimeFormat.duration(m.startMs) + (state?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
            )
        }
        Text(TimeFormat.duration(m.endMs - m.startMs), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.edit_mark), tint = Nm.c.inkSoft) }
    }
}

@Composable
private fun TimelineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String,
    tint: Color, bg: Color, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, bg, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        }
    }
}
