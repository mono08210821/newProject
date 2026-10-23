package com.pockivo.nowmark.ui.screens

import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.R
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.audio.Waveforms
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.data.RecordingRow
import com.pockivo.nowmark.ui.components.EmptyState
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trash: restore or explicitly delete forever (removes the audio file too). */
@Composable
fun TrashScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    val rows by remember { dao.observeTrash() }.collectAsState(initial = null)
    var confirm by remember { mutableStateOf<RecordingRow?>(null) }
    var pendingSystemDelete by remember { mutableStateOf<String?>(null) }

    // When Android asks the user to confirm deleting a file we no longer own (e.g. after reinstall).
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val id = pendingSystemDelete ?: return@rememberLauncherForActivityResult
        pendingSystemDelete = null
        if (res.resultCode == android.app.Activity.RESULT_OK) scope.launch {
            dao.deleteRecording(id); Waveforms.delete(context, id)
        }
    }

    fun deleteForever(r: RecordingRow) {
        scope.launch {
            val rec = dao.recording(r.id) ?: return@launch
            val uri = Uri.parse(rec.uri)
            val outcome = withContext(Dispatchers.IO) {
                try {
                    AudioStore.delete(context, uri)
                    null
                } catch (e: SecurityException) {
                    e
                }
            }
            if (outcome == null) {
                dao.deleteRecording(r.id); Waveforms.delete(context, r.id)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingSystemDelete = r.id
                val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                systemDelete.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outcome is RecoverableSecurityException) {
                pendingSystemDelete = r.id
                systemDelete.launch(IntentSenderRequest.Builder(outcome.userAction.actionIntent.intentSender).build())
            } else {
                toast(context, R.string.delete_failed)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, stringResource(R.string.trash))
        Text(stringResource(R.string.trash_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        if (rows?.isEmpty() == true) {
            EmptyState(Icons.Outlined.DeleteOutline, stringResource(R.string.trash_empty_title), stringResource(R.string.trash_empty_body))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            items(rows.orEmpty(), key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TimeFormat.duration(r.durationMs), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                    }
                    TextButton(onClick = { scope.launch { dao.setDeletedAt(r.id, null) } }) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { confirm = r }) { Text(stringResource(R.string.delete_forever), color = Nm.c.accent) }
                }
                HorizontalDivider(color = Nm.c.line)
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.delete_forever_q)) },
            text = { Text(stringResource(R.string.delete_forever_body, r.title)) },
            confirmButton = { TextButton(onClick = { confirm = null; deleteForever(r) }) { Text(stringResource(R.string.delete_forever), color = Nm.c.accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
