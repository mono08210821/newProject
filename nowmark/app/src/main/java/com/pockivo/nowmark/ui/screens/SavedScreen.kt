package com.pockivo.nowmark.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.ads.AdsManager
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.data.VerifyStatus
import com.pockivo.nowmark.player.PlayerHub
import com.pockivo.nowmark.player.QueueItem
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.BigButton
import com.pockivo.nowmark.ui.components.InfoRow
import com.pockivo.nowmark.ui.components.OutlineButton
import com.pockivo.nowmark.ui.components.Panel
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.components.SectionTitle
import com.pockivo.nowmark.ui.goTab
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Head(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val fg: androidx.compose.ui.graphics.Color,
    val bg: androidx.compose.ui.graphics.Color,
    val title: Int,
)

/** F. Saved: what was captured, what the saved file measures, and how they compare. */
@Composable
fun SavedScreen(nav: NavHostController, id: String, fresh: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    val rec by remember(id) { dao.observeRecording(id) }.collectAsState(initial = null)
    val marks by remember(id) { dao.observeMarks(id) }.collectAsState(initial = emptyList())
    val notes by remember(id) { dao.observeNotes(id) }.collectAsState(initial = emptyList())
    val events by remember(id) { dao.observeEvents(id) }.collectAsState(initial = emptyList())
    val folders by remember { dao.observeFolders() }.collectAsState(initial = emptyList())
    val knownTags by remember {
        dao.observeTagBlobs().map { b -> b.flatMap { it.split('\n') }.filter { it.isNotBlank() }.distinct().sorted() }
    }.collectAsState(initial = emptyList())
    var title by remember(rec?.id) { mutableStateOf(rec?.title.orEmpty()) }
    var newFolder by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (fresh) AdsManager.preloadInterstitialIfDue(context) }

    val r = rec ?: run { BackBar(nav); return }
    val folder = r.folder
    val tags = r.tagList

    fun saveTitle() {
        val t = title.trim()
        if (t.isNotEmpty() && t != r.title) scope.launch { dao.rename(id, t) }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val head = when (r.verifyStatus) {
                VerifyStatus.MATCH -> Head(Icons.Outlined.Check, Nm.c.ok, Nm.c.okTint, R.string.saved_title_match)
                VerifyStatus.MISMATCH -> Head(Icons.Outlined.WarningAmber, Nm.c.warn, Nm.c.warnTint, R.string.saved_title_mismatch)
                VerifyStatus.UNVERIFIED -> Head(Icons.Outlined.QuestionMark, Nm.c.inkSoft, Nm.c.line, R.string.saved_title_unverified)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(92.dp).clip(CircleShape).background(head.bg), contentAlignment = Alignment.Center) {
                    Icon(head.icon, null, tint = head.fg, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(head.title), style = MaterialTheme.typography.headlineMedium, color = Nm.c.ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.saved_sub), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(R.string.field_title))
            OutlinedTextField(
                title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Nm.c.panel, unfocusedContainerColor = Nm.c.panel),
            )

            Spacer(Modifier.height(16.dp))
            Panel {
                Column {
                    InfoRow(stringResource(R.string.row_recorded), TimeFormat.duration(r.capturedMs))
                    val fileValue = when (r.verifyStatus) {
                        VerifyStatus.MATCH -> stringResource(R.string.file_len_match, TimeFormat.duration(r.fileMs))
                        VerifyStatus.MISMATCH -> stringResource(R.string.file_len_mismatch, TimeFormat.duration(r.fileMs))
                        VerifyStatus.UNVERIFIED -> stringResource(R.string.file_len_unverified)
                    }
                    InfoRow(
                        stringResource(R.string.row_file_length), fileValue,
                        valueColor = when (r.verifyStatus) {
                            VerifyStatus.MATCH -> Nm.c.ok
                            VerifyStatus.MISMATCH -> Nm.c.warn
                            VerifyStatus.UNVERIFIED -> Nm.c.inkSoft
                        },
                    )
                    val silenced = events.filter { it.type == HealthType.INPUT_SILENCED }
                    if (silenced.isNotEmpty()) {
                        // Shown separately: a matching length does not mean no input was lost.
                        val total = silenced.sumOf { (it.endMs ?: r.capturedMs) - it.startMs }
                        InfoRow(stringResource(R.string.row_interruptions), stringResource(R.string.interruptions_value, silenced.size, TimeFormat.duration(total)), valueColor = Nm.c.warn)
                    }
                    if (events.any { it.type == HealthType.UNFINISHED_SESSION }) {
                        InfoRow(stringResource(R.string.row_recovered), stringResource(R.string.yes), valueColor = Nm.c.warn)
                    }
                    InfoRow(stringResource(R.string.row_marks_notes), "${marks.size} · ${notes.size}")
                    InfoRow(stringResource(R.string.row_location), r.displayPath.substringBeforeLast('/'), divider = false)
                }
            }
            if (!AudioStore.isPublic) {
                Text(stringResource(R.string.location_private_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.warn, modifier = Modifier.padding(top = 8.dp))
            }

            SectionTitle(stringResource(R.string.field_folder))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((listOfNotNull(folder) + folders).distinct()) { f ->
                    Pill(f, f == folder, { scope.launch { dao.setFolderTags(id, if (f == folder) null else f, r.tags) } })
                }
                item { Pill(stringResource(R.string.new_folder), false, { newFolder = true }, dashed = true) }
            }
            SectionTitle(stringResource(R.string.field_tags))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((tags + knownTags).distinct()) { t ->
                    val on = t in tags
                    Pill(t, on, {
                        val next = if (on) tags - t else tags + t
                        scope.launch { dao.setFolderTags(id, folder, next.joinToString("\n")) }
                    })
                }
                item { Pill(stringResource(R.string.new_tag), false, { newTag = true }, dashed = true) }
            }
            Spacer(Modifier.height(24.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            val merged = MarkMath.union(marks.mapNotNull { MarkMath.clamp(TimeRange(it.startMs, it.endMs), r.durationMs) })
            if (merged.isNotEmpty()) {
                BigButton(
                    text = stringResource(R.string.listen_highlights, merged.size, TimeFormat.duration(merged.sumOf { it.lengthMs })),
                    icon = Icons.Filled.PlayArrow,
                    onClick = {
                        saveTitle()
                        PlayerHub.play(context, merged.map { QueueItem(id, title.ifBlank { r.title }, r.createdAt, r.uri, it) })
                        nav.navigate(Routes.detail(id)) { popUpTo(Routes.SAVED) { inclusive = true } }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlineButton(stringResource(R.string.to_library), onClick = {
                saveTitle()
                val go = { nav.goTab(Routes.LIBRARY) }
                val act = context.findActivity()
                // Interstitial only at the natural "just saved -> library" transition.
                if (fresh && act != null) AdsManager.showInterstitialOr(act) { go() } else go()
            })
        }
    }

    if (newFolder || newTag) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false; newTag = false },
            title = { Text(stringResource(if (newFolder) R.string.new_folder else R.string.new_tag)) },
            text = { OutlinedTextField(text, { text = it.replace("\n", "") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val v = text.trim()
                    scope.launch {
                        if (newFolder) dao.setFolderTags(id, v, r.tags)
                        else dao.setFolderTags(id, folder, (tags + v).distinct().joinToString("\n"))
                        newFolder = false; newTag = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false; newTag = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
