package com.pockivo.nowmark.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.BuildConfig
import com.pockivo.nowmark.R
import com.pockivo.nowmark.ads.AdsManager
import com.pockivo.nowmark.audio.AudioStore
import com.pockivo.nowmark.backup.ZipBackup
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.core.AudioFormatKind
import com.pockivo.nowmark.data.AppSettings
import com.pockivo.nowmark.data.Preset
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.Panel
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.components.SectionTitle
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Supported UI languages (BCP-47). UI language is not a transcription language. */
val SUPPORTED_LANGUAGES = listOf("en", "ko", "ja", "zh-CN", "es", "pt-BR", "de", "fr", "hi", "ar")

@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by AppGraph.settings.settings.collectAsState(initial = AppSettings())
    val adFree by BillingManager.adFree.collectAsState()
    val privacyRequired by AdsManager.privacyOptionsRequired.collectAsState()
    var busy by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var languageOpen by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = R.string.zip_export
        scope.launch {
            result = try {
                val r = ZipBackup.export(context, uri)
                context.getString(R.string.export_done, r.recordings) +
                    if (r.missingAudio > 0) "\n" + context.getString(R.string.export_missing, r.missingAudio) else ""
            } catch (e: Exception) {
                context.getString(R.string.export_failed)
            }
            busy = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = R.string.zip_import
        scope.launch {
            val r = ZipBackup.import(context, uri)
            result = when (r.error) {
                ZipBackup.ImportError.NOT_A_BACKUP -> context.getString(R.string.import_not_backup)
                ZipBackup.ImportError.NEWER_VERSION -> context.getString(R.string.import_newer)
                ZipBackup.ImportError.TOO_LARGE -> context.getString(R.string.import_too_large)
                ZipBackup.ImportError.CORRUPT, ZipBackup.ImportError.STORAGE ->
                    context.getString(R.string.import_corrupt, r.imported)
                null -> context.getString(R.string.import_done, r.imported, r.duplicates, r.failed)
            }
            busy = null
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = R.string.import_audio
        scope.launch {
            val r = ZipBackup.importAudioFiles(context, uris)
            result = context.getString(R.string.import_audio_done, r.imported, r.failed)
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(nav, stringResource(R.string.settings))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionTitle(stringResource(R.string.set_recording))
            Panel {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.set_preset), style = MaterialTheme.typography.labelLarge, color = Nm.c.ink)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Preset.entries) { p -> Pill(stringResource(Labels.preset(p)), s.preset == p, { scope.launch { AppGraph.settings.setPreset(p) } }) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.set_format), style = MaterialTheme.typography.labelLarge, color = Nm.c.ink)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFormatKind.entries.forEach { f -> Pill(Labels.format(f), s.format == f, { scope.launch { AppGraph.settings.setFormat(f) } }) }
                    }
                    Text(stringResource(R.string.set_format_desc), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.set_mark_window), style = MaterialTheme.typography.labelLarge, color = Nm.c.ink)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.set_before), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15_000L, 30_000L, 60_000L).forEach { v ->
                            Pill(stringResource(R.string.seconds_short, (v / 1000).toInt()), s.markBeforeMs == v, { scope.launch { AppGraph.settings.setMarkWindow(v, s.markAfterMs) } })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.set_after), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5_000L, 10_000L, 20_000L).forEach { v ->
                            Pill(stringResource(R.string.seconds_short, (v / 1000).toInt()), s.markAfterMs == v, { scope.launch { AppGraph.settings.setMarkWindow(s.markBeforeMs, v) } })
                        }
                    }
                    Text(stringResource(R.string.set_mark_window_desc), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(top = 6.dp))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Nm.c.line)
                    ToggleRow(stringResource(R.string.set_vibrate), s.vibrate) { scope.launch { AppGraph.settings.setVibrate(it) } }
                    ToggleRow(stringResource(R.string.set_skip_silence), s.skipSilence) { scope.launch { AppGraph.settings.setSkipSilence(it) } }
                }
            }

            SectionTitle(stringResource(R.string.set_storage))
            Panel {
                Column {
                    LinkRow(stringResource(R.string.set_location), AudioStore.relativeDir) {}
                    LinkRow(stringResource(R.string.trash), null) { nav.navigate(Routes.TRASH) }
                    LinkRow(stringResource(R.string.recovery_title), null) { nav.navigate(Routes.RECOVERY) }
                    LinkRow(stringResource(R.string.zip_export), stringResource(R.string.zip_export_desc)) {
                        exportLauncher.launch("Nowmark_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date()) + ".zip")
                    }
                    LinkRow(stringResource(R.string.zip_import), stringResource(R.string.zip_import_desc)) {
                        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                    LinkRow(stringResource(R.string.import_audio), stringResource(R.string.import_audio_desc)) {
                        audioLauncher.launch(arrayOf("audio/*"))
                    }
                }
            }
            Text(stringResource(R.string.storage_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(top = 8.dp))

            SectionTitle(stringResource(R.string.set_ads))
            Panel {
                Column {
                    LinkRow(
                        stringResource(R.string.remove_ads_title),
                        stringResource(if (adFree) R.string.ads_removed else R.string.remove_ads_short),
                    ) { nav.navigate(Routes.REMOVE_ADS) }
                    LinkRow(stringResource(R.string.restore_purchase), null) {
                        BillingManager.restore()
                        nav.navigate(Routes.REMOVE_ADS)
                    }
                    if (privacyRequired && !adFree) {
                        LinkRow(stringResource(R.string.ad_privacy), null) {
                            context.findActivity()?.let { AdsManager.showPrivacyOptions(it) {} }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.set_language))
            Panel {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                LinkRow(
                    stringResource(R.string.set_language),
                    if (current.isBlank()) stringResource(R.string.lang_system) else Locale.forLanguageTag(current).let { it.getDisplayName(it) },
                ) { languageOpen = true }
            }
            Text(stringResource(R.string.language_note), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft, modifier = Modifier.padding(top = 8.dp))

            SectionTitle(stringResource(R.string.set_privacy))
            Panel {
                Column {
                    Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyMedium, color = Nm.c.ink, modifier = Modifier.padding(16.dp))
                    LinkRow(stringResource(R.string.privacy_policy), null) { open(context, BuildConfig.PRIVACY_URL) }
                }
            }

            SectionTitle(stringResource(R.string.set_about))
            Panel {
                Column {
                    LinkRow(stringResource(R.string.developer), "Pockivo Studio") { open(context, BuildConfig.HOMEPAGE_URL) }
                    LinkRow(stringResource(R.string.support), BuildConfig.SUPPORT_EMAIL) {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + BuildConfig.SUPPORT_EMAIL))
                            .putExtra(Intent.EXTRA_SUBJECT, "Nowmark " + BuildConfig.VERSION_NAME)
                        try { context.startActivity(i) } catch (_: Exception) { }
                    }
                    LinkRow(stringResource(R.string.version), BuildConfig.VERSION_NAME) {}
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    busy?.let { b ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(b)) }, text = { Text(stringResource(R.string.working)) }, confirmButton = {})
    }
    result?.let { msg ->
        AlertDialog(onDismissRequest = { result = null }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (languageOpen) {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        AlertDialog(
            onDismissRequest = { languageOpen = false },
            title = { Text(stringResource(R.string.set_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf("") + SUPPORTED_LANGUAGES).forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) {
                                AppCompatDelegate.setApplicationLocales(
                                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                                )
                                languageOpen = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == tag || (tag.isEmpty() && current.isBlank()), onClick = null)
                            Text(
                                if (tag.isEmpty()) stringResource(R.string.lang_system) else Locale.forLanguageTag(tag).let { it.getDisplayName(it) },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { languageOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun open(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Switch) { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Nm.c.ink, modifier = Modifier.weight(1f))
        Switch(value, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Nm.c.accent))
    }
}

@Composable
fun LinkRow(title: String, value: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Nm.c.ink)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
    }
}
