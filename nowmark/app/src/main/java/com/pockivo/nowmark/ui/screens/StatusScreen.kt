package com.pockivo.nowmark.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.R
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.data.HealthType
import com.pockivo.nowmark.recorder.InputStatus
import com.pockivo.nowmark.recorder.RecorderService
import com.pockivo.nowmark.recorder.RecorderState
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.components.BigButton
import com.pockivo.nowmark.ui.components.IconTile
import com.pockivo.nowmark.ui.components.InfoRow
import com.pockivo.nowmark.ui.components.Panel
import com.pockivo.nowmark.ui.components.SoftButton
import com.pockivo.nowmark.ui.components.WaveformBars
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** G. Recording status / input interruption. Only reports what was observed. */
@Composable
fun StatusScreen(nav: NavHostController) {
    val context = LocalContext.current
    val live by RecorderState.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    if (!live.isActive) {
        Column(Modifier.fillMaxSize()) {
            BackBar(nav, stringResource(R.string.status_title))
            Text(stringResource(R.string.status_idle), style = MaterialTheme.typography.bodyLarge, color = Nm.c.inkSoft, modifier = Modifier.padding(20.dp))
        }
        return
    }
    val open = live.openInterruption

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, stringResource(R.string.status_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.status_elapsed), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(TimeFormat.duration(live.audioMs), style = MaterialTheme.typography.displayLarge, color = Nm.c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(Labels.preset(live.preset)) + " · " + Labels.format(live.format),
                style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            val (title, body, warn) = when {
                open != null -> Triple(R.string.status_silenced_title, R.string.status_silenced_body, true)
                live.inputStatus == InputStatus.LOW -> Triple(R.string.status_low_title, R.string.status_low_body, true)
                live.inputStatus == InputStatus.DETECTED -> Triple(R.string.status_ok_title, R.string.status_ok_body, false)
                else -> Triple(R.string.input_unknown, R.string.status_unknown_body, false)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (warn) Nm.c.warnTint else Nm.c.okTint).padding(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(if (warn) Icons.Outlined.WarningAmber else Icons.Outlined.GraphicEq, null, tint = if (warn) Nm.c.warn else Nm.c.ok, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Column {
                    InfoRow(stringResource(R.string.row_last_written), TimeFormat.duration(live.confirmedMs))
                    if (open != null) {
                        // Wall-clock time: while silenced, frames may keep arriving as zeros.
                        InfoRow(stringResource(R.string.row_interruption_time), TimeFormat.duration(now - open.wallTime))
                    }
                    InfoRow(stringResource(R.string.row_wall_elapsed), TimeFormat.duration(now - live.startedAtWall))
                    InfoRow(stringResource(R.string.row_input_device), stringResource(Labels.device(live.inputDeviceType)), divider = false)
                }
            }
            Spacer(Modifier.height(16.dp))
            WaveformBars(live.levels.map { kotlin.math.sqrt(it) }.toFloatArray(), Modifier.fillMaxWidth().height(70.dp))
            if (!live.silenceObservable) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = Nm.c.inkSoft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.silence_not_observable), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))
            live.events.asReversed().forEach { e ->
                val type = HealthType.entries.firstOrNull { it.name == e.type } ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (type == HealthType.DEVICE_CHANGED) Icons.Outlined.SwapHoriz else Icons.Outlined.WarningAmber, Nm.c.warn, Nm.c.warnTint, 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Labels.health(type)), style = MaterialTheme.typography.titleMedium, color = Nm.c.ink)
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.wallTime)) + " · " +
                                TimeFormat.duration(e.startMs) + (e.endMs?.let { " – " + TimeFormat.duration(it) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft,
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftButton(stringResource(R.string.keep_watching), Icons.Outlined.GraphicEq, { nav.popBackStack() })
            }
            Spacer(Modifier.height(10.dp))
            BigButton(stringResource(R.string.save_and_stop), onClick = {
                RecorderService.send(context, RecorderService.ACTION_STOP)
                nav.popBackStack()
            })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.status_footer), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, textAlign = TextAlign.Center)
            }
        }
    }
}
