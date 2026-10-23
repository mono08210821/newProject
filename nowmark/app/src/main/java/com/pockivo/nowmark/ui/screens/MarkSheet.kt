package com.pockivo.nowmark.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AudioClipper
import com.pockivo.nowmark.core.MarkMath
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.data.MarkCategory
import com.pockivo.nowmark.data.MarkEntity
import com.pockivo.nowmark.data.RecordingEntity
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.launch

/** Edit one mark: category, memo, boundaries, review state, listen with context, share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkSheet(
    mark: MarkEntity,
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onListen: (TimeRange, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    val duration = recording.durationMs
    var category by remember { mutableStateOf(mark.category) }
    var memo by remember { mutableStateOf(mark.memo) }
    var start by remember { mutableLongStateOf(mark.startMs) }
    var end by remember { mutableLongStateOf(mark.endMs) }
    var reviewed by remember { mutableStateOf(mark.reviewed) }
    var done by remember { mutableStateOf(mark.done) }
    var sharing by remember { mutableStateOf(false) }

    fun save() {
        scope.launch {
            dao.updateMark(mark.copy(category = category, memo = memo.trim(), startMs = start, endMs = end, reviewed = reviewed, done = done))
        }
    }

    ModalBottomSheet(onDismissRequest = { save(); onDismiss() }, containerColor = Nm.c.panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.edit_mark), style = MaterialTheme.typography.titleLarge, color = Nm.c.ink)
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MarkCategory.entries) { c -> Pill(stringResource(Labels.category(c)), c == category, { category = c }) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                memo, { memo = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.memo_hint)) }, minLines = 2,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.section_range), style = MaterialTheme.typography.labelLarge, color = Nm.c.inkSoft)
            AdjustRow(stringResource(R.string.range_start), start,
                onMinus = { start = (start - 5_000).coerceAtLeast(0) },
                onPlus = { start = (start + 5_000).coerceAtMost(end - 1_000) })
            AdjustRow(stringResource(R.string.range_end), end,
                onMinus = { end = (end - 5_000).coerceAtLeast(start + 1_000) },
                onPlus = { end = (end + 5_000).coerceAtMost(duration) })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(stringResource(R.string.listen)) { onListen(TimeRange(start, end), false) }
                SmallChip(stringResource(R.string.repeat_section)) { onListen(TimeRange(start, end), true) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(stringResource(R.string.context_before)) {
                    onListen(MarkMath.widen(TimeRange(start, end), 15_000, 0, duration), false)
                }
                SmallChip(stringResource(R.string.context_after)) {
                    onListen(MarkMath.widen(TimeRange(start, end), 0, 15_000, duration), false)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.reviewed), reviewed, { reviewed = !reviewed })
                if (category == MarkCategory.TODO || category == MarkCategory.QUESTION) {
                    Pill(stringResource(Labels.doneAction(category)), done, { done = !done })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !sharing, onClick = {
                    sharing = true
                    scope.launch {
                        val uri = AudioClipper.shareClip(context, recording, TimeRange(start, end))
                        sharing = false
                        if (uri == null) { toast(context, R.string.share_failed); return@launch }
                        val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(uri) ?: "audio/*")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_section)))
                    }
                }) { Text(stringResource(if (sharing) R.string.preparing else R.string.share_section)) }
                TextButton(onClick = {
                    scope.launch { dao.deleteMark(mark.id) }
                    onDismiss()
                }) { Text(stringResource(R.string.delete_mark), color = Nm.c.accent) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { save(); onDismiss() }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.done)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdjustRow(label: String, value: Long, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Nm.c.ink, modifier = Modifier.weight(1f))
        SmallChip(stringResource(R.string.minus_5s), onClick = onMinus)
        Text(TimeFormat.duration(value), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, modifier = Modifier.padding(horizontal = 12.dp))
        SmallChip(stringResource(R.string.plus_5s), onClick = onPlus)
    }
}
