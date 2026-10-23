package com.pockivo.nowmark.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AudioClipper
import com.pockivo.nowmark.audio.Waveforms
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.core.TimeRange
import com.pockivo.nowmark.player.PlayerHub
import com.pockivo.nowmark.player.QueueItem
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.BigButton
import com.pockivo.nowmark.ui.components.OutlineButton
import com.pockivo.nowmark.ui.components.WaveformBars
import com.pockivo.nowmark.ui.components.rangeFractions
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.launch

/** H. Trim: the original stays untouched; the selection is saved as a new linked recording. */
@Composable
fun TrimScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by remember(id) { AppGraph.db.dao().observeRecording(id) }.collectAsState(initial = null)
    val r = rec ?: run { BackBar(nav); return }
    val duration = r.durationMs.coerceAtLeast(1)
    var range by remember(r.id) { mutableStateOf(0f..duration.toFloat()) }
    var title by remember(r.id) { mutableStateOf(context.getString(R.string.trim_title_default, r.title)) }
    var saving by remember { mutableStateOf(false) }
    var peaks by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(r.uri) { peaks = Waveforms.load(context, id, Uri.parse(r.uri)) }
    val sel = TimeRange(range.start.toLong(), range.endInclusive.toLong())

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, stringResource(R.string.trim_copy))
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.trim_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
            Spacer(Modifier.height(16.dp))
            WaveformBars(
                Waveforms.bars(peaks ?: ByteArray(0), 72), Modifier.fillMaxWidth().height(90.dp),
                highlights = rangeFractions(listOf(sel), duration),
            )
            RangeSlider(
                value = range, onValueChange = { range = it }, valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Nm.c.accent, activeTrackColor = Nm.c.accent),
            )
            Row {
                Text(TimeFormat.duration(sel.startMs), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, modifier = Modifier.weight(1f))
                Text(TimeFormat.duration(sel.lengthMs), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.weight(1f))
                Text(TimeFormat.duration(sel.endMs), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
            }
            Spacer(Modifier.height(16.dp))
            OutlineButton(stringResource(R.string.preview), icon = Icons.Filled.PlayArrow, onClick = {
                if (!PlayerHub.play(context, listOf(QueueItem(id, r.title, r.createdAt, r.uri, sel)))) toast(context, R.string.no_playback_while_recording)
            })
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_title)) })
        }
        Column(Modifier.padding(20.dp)) {
            BigButton(stringResource(if (saving) R.string.preparing else R.string.save_copy), enabled = !saving && sel.lengthMs >= 1000, onClick = {
                saving = true
                PlayerHub.pause()
                scope.launch {
                    val newId = AudioClipper.trimCopy(context, r, sel, title.trim().ifBlank { r.title })
                    saving = false
                    if (newId == null) toast(context, R.string.trim_failed)
                    else nav.navigate(Routes.detail(newId)) { popUpTo(Routes.TRIM) { inclusive = true } }
                }
            })
        }
    }
}
