package com.pockivo.nowmark.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.data.Preset
import com.pockivo.nowmark.recorder.FinalizeResult
import com.pockivo.nowmark.recorder.Finalizer
import com.pockivo.nowmark.recorder.Journal
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.InfoRow
import com.pockivo.nowmark.ui.components.Panel
import com.pockivo.nowmark.ui.components.EmptyState
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sessions left after the app was killed, force-stopped, or the device restarted. Shows only
 * what is on disk: the last confirmed position and the audio actually found. Nothing after
 * that is estimated or invented.
 */
@Composable
fun RecoveryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<File, Journal>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(reload) { items = withContext(Dispatchers.IO) { Finalizer.findUnfinished(context) } }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, stringResource(R.string.recovery_title))
        Text(stringResource(R.string.recovery_intro), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        if (items?.isEmpty() == true) {
            EmptyState(Icons.Outlined.TaskAlt, stringResource(R.string.recovery_empty_title), stringResource(R.string.recovery_empty_body))
        }
        LazyColumn(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items.orEmpty(), key = { it.first.name }) { (dir, j) ->
                val found = Finalizer.framesFound(dir, j) * 1000L / j.sampleRate
                val confirmed = j.confirmedFrames * 1000L / j.sampleRate
                Panel {
                    Column {
                        InfoRow(stringResource(R.string.row_started), Labels.dateTime(context, j.startedAt))
                        InfoRow(stringResource(R.string.set_preset), stringResource(Labels.preset(Preset.from(j.preset))))
                        InfoRow(stringResource(R.string.row_last_written), TimeFormat.duration(confirmed))
                        InfoRow(stringResource(R.string.row_found_audio), TimeFormat.duration(found))
                        InfoRow(stringResource(R.string.row_marks_notes), "${j.marks.size} · ${j.notes.size}")
                        if (j.state == Journal.STATE_FAILED) {
                            Text(stringResource(R.string.recovery_failed_before), style = MaterialTheme.typography.bodyMedium, color = Nm.c.warn, modifier = Modifier.padding(16.dp))
                        }
                        Row(Modifier.padding(8.dp)) {
                            TextButton(enabled = !working, onClick = {
                                working = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { Finalizer.finalize(context, dir, j, recovered = true) }
                                    working = false
                                    when (r) {
                                        is FinalizeResult.Saved -> nav.navigate(Routes.saved(r.recordingId))
                                        is FinalizeResult.Empty -> toast(context, R.string.recovery_nothing)
                                        is FinalizeResult.Failed -> toast(context, R.string.failed_encode)
                                    }
                                    reload++
                                }
                            }) { Text(stringResource(R.string.recover_save)) }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !working, onClick = { confirmDiscard = dir }) {
                                Text(stringResource(R.string.recover_discard), color = Nm.c.accent)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    confirmDiscard?.let { dir ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(stringResource(R.string.recover_discard_q)) },
            text = { Text(stringResource(R.string.recover_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    scope.launch { withContext(Dispatchers.IO) { dir.deleteRecursively() }; reload++ }
                }) { Text(stringResource(R.string.recover_discard), color = Nm.c.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
