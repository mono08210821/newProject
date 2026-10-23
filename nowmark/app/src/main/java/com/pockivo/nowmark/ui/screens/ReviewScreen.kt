package com.pockivo.nowmark.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AudioClipper
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.data.MarkCategory
import com.pockivo.nowmark.data.ReviewRow
import com.pockivo.nowmark.player.PlayerHub
import com.pockivo.nowmark.player.QueueItem
import com.pockivo.nowmark.recorder.RecorderState
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.BrandMark
import com.pockivo.nowmark.ui.components.EmptyState
import com.pockivo.nowmark.ui.components.IconTile
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.launch

private data class Group(
    val recordingId: String,
    val title: String,
    val createdAt: Long,
    val uri: String,
    val recordingMs: Long,
    val rows: List<ReviewRow>,
    /** Union of this recording's selected ranges: overlaps are played once. */
    val merged: List<TimeRange>,
)

/** E. Review: unconfirmed sections grouped by recording, played as a queue. */
@Composable
fun ReviewScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    val all by remember { dao.observeReview() }.collectAsState(initial = null)
    val player by PlayerHub.ui.collectAsState()
    val live by RecorderState.state.collectAsState()
    var onlyOpen by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableStateOf<MarkCategory?>(null) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    val groups = remember(all, onlyOpen, category) {
        all.orEmpty()
            .filter { r -> (!onlyOpen || !(r.reviewed || r.done)) && (category == null || r.category == category) }
            .groupBy { it.recordingId }
            .map { (rid, rows) ->
                val f = rows.first()
                val ranges = rows.mapNotNull { MarkMath.clamp(TimeRange(it.startMs, it.endMs), f.recordingMs) }
                Group(rid, f.recordingTitle, f.recordingCreatedAt, f.recordingUri, f.recordingMs, rows, MarkMath.union(ranges))
            }
    }
    val sectionCount = groups.sumOf { it.merged.size }
    val totalMs = groups.sumOf { g -> g.merged.sumOf { it.lengthMs } }
    val sourceMs = groups.sumOf { it.recordingMs }
    val queue = remember(groups) {
        groups.flatMap { g -> g.merged.map { QueueItem(g.recordingId, g.title, g.createdAt, g.uri, it) } }
    }
    val playingQueue = player.queue.isNotEmpty() && player.queue == queue

    fun start(index: Int = 0) {
        if (live.isActive) { toast(context, R.string.no_playback_while_recording); return }
        PlayerHub.play(context, queue, index)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(Modifier.weight(1f))
                }
                Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineLarge, color = Nm.c.ink)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nm.c.dark).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pluralStringResource(R.plurals.review_sections, sectionCount, sectionCount), style = MaterialTheme.typography.titleMedium, color = Nm.c.onDark)
                        Text(TimeFormat.duration(totalMs), style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize * 1.4f), color = Nm.c.onDark)
                        Text(stringResource(R.string.review_of_total, TimeFormat.duration(sourceMs)), style = MaterialTheme.typography.bodyMedium, color = Nm.c.onDark.copy(alpha = 0.75f))
                    }
                    val label = stringResource(if (playingQueue && player.isPlaying) R.string.pause else R.string.play)
                    IconButton(
                        onClick = { if (playingQueue) PlayerHub.toggle(context) else start() },
                        enabled = queue.isNotEmpty(),
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White),
                    ) {
                        Icon(if (playingQueue && player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, label, tint = Nm.c.accent, modifier = Modifier.size(34.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill(stringResource(R.string.filter_open), onlyOpen, { onlyOpen = true }) }
                    item { Pill(stringResource(R.string.filter_all), !onlyOpen, { onlyOpen = false }) }
                    items(MarkCategory.entries) { c ->
                        Pill(stringResource(Labels.category(c)), category == c, { category = if (category == c) null else c })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (all != null && groups.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.Headphones, stringResource(R.string.review_empty_title), stringResource(R.string.review_empty_body))
                }
            }

            groups.forEach { g ->
                item(key = "h" + g.recordingId) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            collapsed = if (g.recordingId in collapsed) collapsed - g.recordingId else collapsed + g.recordingId
                        }.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${g.title} · ${g.rows.size}", style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(com.pockivo.nowmark.ui.Labels.dateTime(context, g.createdAt), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                        }
                        Icon(if (g.recordingId in collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = Nm.c.inkSoft)
                    }
                    HorizontalDivider(color = Nm.c.line)
                }
                if (g.recordingId !in collapsed) {
                    items(g.rows, key = { it.id }) { row ->
                        ReviewItem(
                            row,
                            active = playingQueue && player.current?.recordingId == row.recordingId &&
                                player.current?.range?.let { row.startMs >= it.startMs && row.startMs < it.endMs } == true,
                            onPlay = {
                                val idx = queue.indexOfFirst { it.recordingId == row.recordingId && row.startMs >= (it.range?.startMs ?: 0) && row.startMs < (it.range?.endMs ?: 0) }
                                start(idx.coerceAtLeast(0))
                            },
                            onOpen = { nav.navigate(Routes.detail(row.recordingId)) },
                            onToggleReviewed = { scope.launch { dao.marks(row.recordingId).firstOrNull { it.id == row.id }?.let { dao.updateMark(it.copy(reviewed = !it.reviewed)) } } },
                            onToggleDone = { scope.launch { dao.marks(row.recordingId).firstOrNull { it.id == row.id }?.let { dao.updateMark(it.copy(done = !it.done)) } } },
                            onShare = {
                                scope.launch {
                                    val rec = dao.recording(row.recordingId) ?: return@launch
                                    val uri = AudioClipper.shareClip(context, rec, TimeRange(row.startMs, row.endMs))
                                    if (uri == null) { toast(context, R.string.share_failed); return@launch }
                                    val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(uri) ?: "audio/*")
                                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_section)))
                                }
                            },
                        )
                        HorizontalDivider(color = Nm.c.line)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        // Now-playing bar: always names the source recording, time and section boundaries so
        // separate remarks are never presented as one continuous statement.
        val cur = player.current
        if (playingQueue && cur != null) {
            Row(
                Modifier.fillMaxWidth().background(Nm.c.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { PlayerHub.previous() }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), tint = Nm.c.ink) }
                Column(Modifier.weight(1f)) {
                    Text(cur.title, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${player.index + 1}/${player.queue.size} · " + com.pockivo.nowmark.ui.Labels.dateTime(context, cur.createdAt) +
                            (cur.range?.let { " · ${TimeFormat.duration(it.startMs)}–${TimeFormat.duration(it.endMs)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayerHub.toggle(context) }) {
                    Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (player.isPlaying) R.string.pause else R.string.play), tint = Nm.c.accent)
                }
                IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = Nm.c.ink) }
            }
        }
    }
}

@Composable
private fun ReviewItem(
    row: ReviewRow,
    active: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onToggleReviewed: () -> Unit,
    onToggleDone: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) Nm.c.accentTint else Color.Transparent)
            .clickable(role = Role.Button, onClick = onPlay).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Labels.categoryIcon(row.category), Nm.c.accent, Nm.c.accentTint, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.memo.ifBlank { stringResource(Labels.category(row.category)) }, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = when {
                row.done -> " · " + stringResource(Labels.doneAction(row.category))
                row.reviewed -> " · " + stringResource(R.string.reviewed)
                else -> ""
            }
            Text(TimeFormat.duration(row.startMs) + state, style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        }
        Text(TimeFormat.duration(row.endMs.coerceAtMost(row.recordingMs) - row.startMs), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.more), tint = Nm.c.inkSoft) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(if (row.reviewed) R.string.mark_unreviewed else R.string.action_mark_reviewed)) }, onClick = { menu = false; onToggleReviewed() })
                if (row.category == MarkCategory.TODO || row.category == MarkCategory.QUESTION) {
                    DropdownMenuItem(text = { Text(stringResource(Labels.doneAction(row.category))) }, onClick = { menu = false; onToggleDone() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.share_section)) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_recording)) }, onClick = { menu = false; onOpen() })
            }
        }
    }
}

