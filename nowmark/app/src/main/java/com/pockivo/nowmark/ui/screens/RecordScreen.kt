package com.pockivo.nowmark.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.EntryRequests
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.data.AppSettings
import com.pockivo.nowmark.data.Preset
import com.pockivo.nowmark.recorder.FailureKind
import com.pockivo.nowmark.recorder.Finalizer
import com.pockivo.nowmark.recorder.InputStatus
import com.pockivo.nowmark.recorder.LiveState
import com.pockivo.nowmark.recorder.Phase
import com.pockivo.nowmark.recorder.RecorderService
import com.pockivo.nowmark.recorder.RecorderState
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.BigButton
import com.pockivo.nowmark.ui.components.BrandMark
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.components.SoftButton
import com.pockivo.nowmark.ui.components.WaveformBars
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun RecordScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by RecorderState.state.collectAsState()
    val settings by AppGraph.settings.settings.collectAsState(initial = AppSettings())
    val startRequested by EntryRequests.startRecording.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var unfinished by remember { mutableStateOf(0) }

    LaunchedEffect(live.phase) {
        if (!live.isActive) unfinished = withContext(Dispatchers.IO) { Finalizer.findUnfinished(context).size }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionDenied = false
            if (!RecorderState.state.value.isActive) scope.launch { RecorderService.start(context) }
        } else {
            permissionDenied = true
            val act = context.findActivity()
            permanentlyDenied = act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startRecording() {
        if (RecorderState.state.value.isActive) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch { RecorderService.start(context) }
            // Notification permission is optional; ask once alongside, never block recording on it.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(perms.toTypedArray())
        }
    }

    LaunchedEffect(startRequested) {
        if (startRequested) {
            EntryRequests.startRecording.value = false
            startRecording()
        }
    }

    when {
        live.phase == Phase.RECORDING || live.phase == Phase.PAUSED -> RecordingContent(nav, live, settings)
        live.phase == Phase.FINALIZING -> FinalizingContent()
        else -> IdleContent(
            nav = nav,
            settings = settings,
            permissionDenied = permissionDenied,
            permanentlyDenied = permanentlyDenied,
            unfinished = unfinished,
            onPreset = { p -> scope.launch { AppGraph.settings.setPreset(p) } },
            onRecord = { startRecording() },
        )
    }

    if (live.phase == Phase.FAILED) {
        AlertDialog(
            onDismissRequest = { RecorderState.consumeSaved() },
            title = { Text(stringResource(R.string.failed_title)) },
            text = {
                Text(
                    stringResource(
                        when (live.failure) {
                            FailureKind.START_FAILED -> R.string.failed_start
                            FailureKind.STORAGE_FULL -> R.string.failed_storage
                            FailureKind.WRITE_FAILED -> R.string.failed_write
                            else -> R.string.failed_encode
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val start = live.failure == FailureKind.START_FAILED
                    RecorderState.consumeSaved()
                    if (!start) nav.navigate(Routes.RECOVERY)
                }) { Text(stringResource(if (live.failure == FailureKind.START_FAILED) R.string.ok else R.string.open_recovery)) }
            },
        )
    }
}

@Composable
private fun IdleContent(
    nav: NavHostController,
    settings: AppSettings,
    permissionDenied: Boolean,
    permanentlyDenied: Boolean,
    unfinished: Int,
    onPreset: (Preset) -> Unit,
    onRecord: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.weight(1f))
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = Nm.c.ink)
            }
        }
        Text(stringResource(R.string.record_idle_title), style = MaterialTheme.typography.headlineLarge, color = Nm.c.ink)
        Text(stringResource(R.string.record_idle_sub), style = MaterialTheme.typography.bodyLarge, color = Nm.c.inkSoft)

        if (unfinished > 0) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nm.c.warnTint)
                    .clickable(role = Role.Button) { nav.navigate(Routes.RECOVERY) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Restore, null, tint = Nm.c.warn)
                Spacer(Modifier.width(12.dp))
                Text(
                    pluralStringResource(R.plurals.recovery_banner, unfinished, unfinished),
                    color = Nm.c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset.entries.forEach { p ->
                Pill(stringResource(Labels.preset(p)), settings.preset == p, { onPreset(p) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Labels.presetDesc(settings.preset)) + " · " + Labels.format(settings.format),
            style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
        )

        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val label = stringResource(R.string.start_recording)
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(Nm.c.accent)
                    .clickable(role = Role.Button, onClick = onRecord)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Mic, null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.start_recording), style = MaterialTheme.typography.titleMedium,
            color = Nm.c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )

        if (permissionDenied) {
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Nm.c.panel).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MicOff, null, tint = Nm.c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.perm_denied_title), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (permanentlyDenied) R.string.perm_denied_forever else R.string.perm_denied_body),
                    style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
                )
                if (permanentlyDenied) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }) { Text(stringResource(R.string.open_app_settings)) }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.consent_notice), style = MaterialTheme.typography.bodyMedium,
            color = Nm.c.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FinalizingContent() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Nm.c.accent)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.finalizing), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.finalizing_sub), style = MaterialTheme.typography.bodyMedium,
            color = Nm.c.inkSoft, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingContent(nav: NavHostController, live: LiveState, settings: AppSettings) {
    val context = LocalContext.current
    var noteOpen by remember { mutableStateOf(false) }
    var lastMarkFeedback by remember { mutableStateOf(false) }
    val paused = live.phase == Phase.PAUSED

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Circle, null, tint = if (paused) Nm.c.inkSoft else Nm.c.accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (paused) R.string.state_paused else R.string.state_recording) + " · " +
                    stringResource(Labels.preset(live.preset)),
                style = MaterialTheme.typography.titleMedium, color = Nm.c.ink, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { noteOpen = true }) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.add_note), tint = Nm.c.ink)
            }
        }

        // The timer is excluded from TalkBack live announcements so it does not speak every second.
        Text(
            TimeFormat.duration(live.audioMs),
            style = MaterialTheme.typography.displayLarge, color = Nm.c.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics {
                contentDescription = context.getString(R.string.a11y_elapsed)
            },
        )

        Spacer(Modifier.height(10.dp))
        InputStatusPill(live) { nav.navigate(Routes.STATUS) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Mic, null, tint = Nm.c.inkSoft, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(Labels.device(live.inputDeviceType)) + " · " + Labels.format(live.format),
                style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
            )
        }

        Spacer(Modifier.height(18.dp))
        val windowMs = live.levels.size * 100L
        val windowStart = live.audioMs - windowMs
        val hl = live.marks.mapNotNull { m ->
            val s = ((m.startMs - windowStart).toFloat() / windowMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            val e = ((minOf(m.endMs, live.audioMs) - windowStart).toFloat() / windowMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            if (e > s) s..e else null
        }
        WaveformBars(
            live.levels.map { kotlin.math.sqrt(it) }.toFloatArray(),
            Modifier.fillMaxWidth().height(90.dp),
            highlights = hl,
            highlightBg = Nm.c.accentTint,
        )
        live.remainingMinutesEstimate?.let { mins ->
            Text(
                stringResource(R.string.storage_estimate, TimeFormat.duration(mins * 60_000L)),
                style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                pluralStringResource(R.plurals.marks_count, live.marks.size, live.marks.size) + "   ·   " +
                    pluralStringResource(R.plurals.notes_count, live.noteCount, live.noteCount),
                style = MaterialTheme.typography.labelLarge, color = Nm.c.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(14.dp))
        BigButton(
            text = stringResource(R.string.mark_now),
            sub = if (lastMarkFeedback) stringResource(R.string.mark_added) else stringResource(R.string.mark_now_sub, (settings.markBeforeMs / 1000).toInt(), (settings.markAfterMs / 1000).toInt()),
            icon = if (lastMarkFeedback) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
            onClick = {
                RecorderService.send(context, RecorderService.ACTION_MARK)
                lastMarkFeedback = true
            },
            height = 84,
        )
        LaunchedEffect(live.marks.size) {
            kotlinx.coroutines.delay(1500)
            lastMarkFeedback = false
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton(stringResource(R.string.memo), Icons.AutoMirrored.Outlined.NoteAdd, { noteOpen = true })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton(
                stringResource(if (paused) R.string.action_resume else R.string.action_pause),
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                { RecorderService.send(context, if (paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE) },
            )
            SoftButton(stringResource(R.string.action_save), Icons.Filled.Stop, { RecorderService.send(context, RecorderService.ACTION_STOP) }, dark = true)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (noteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text(stringResource(R.string.add_note)) },
            text = {
                OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.note_hint)) }, minLines = 2)
            },
            confirmButton = {
                TextButton(onClick = {
                    RecorderService.send(context, RecorderService.ACTION_NOTE, text.trim())
                    noteOpen = false
                }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun InputStatusPill(live: LiveState, onClick: () -> Unit) {
    val (fg, bg, title) = when (live.inputStatus) {
        InputStatus.SILENCED_BY_SYSTEM -> Triple(Nm.c.warn, Nm.c.warnTint, R.string.input_silenced)
        InputStatus.LOW -> Triple(Nm.c.warn, Nm.c.warnTint, R.string.input_low)
        InputStatus.DETECTED -> Triple(Nm.c.ok, Nm.c.okTint, R.string.input_detected)
        InputStatus.UNKNOWN -> Triple(Nm.c.inkSoft, Nm.c.line, R.string.input_unknown)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (live.inputStatus == InputStatus.DETECTED) Icons.Outlined.GraphicEq else Icons.Outlined.WarningAmber,
                null, tint = fg, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(title), color = fg, style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.file_written_until, TimeFormat.duration(live.confirmedMs)),
                    color = fg, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
