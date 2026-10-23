package com.pockivo.nowmark.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.pockivo.nowmark.AppGraph
import com.pockivo.nowmark.EntryRequests
import com.pockivo.nowmark.R
import com.pockivo.nowmark.ads.AdsManager
import com.pockivo.nowmark.ads.LibraryBanner
import com.pockivo.nowmark.billing.BillingManager
import com.pockivo.nowmark.billing.PriceState
import com.pockivo.nowmark.core.TimeFormat
import com.pockivo.nowmark.data.AppSettings
import com.pockivo.nowmark.data.RecordingRow
import com.pockivo.nowmark.data.SortOrder
import com.pockivo.nowmark.data.VerifyStatus
import com.pockivo.nowmark.ui.Labels
import com.pockivo.nowmark.ui.Routes
import com.pockivo.nowmark.ui.components.BrandMark
import com.pockivo.nowmark.ui.components.EmptyState
import com.pockivo.nowmark.ui.components.IconTile
import com.pockivo.nowmark.ui.components.Pill
import com.pockivo.nowmark.ui.components.Tag
import com.pockivo.nowmark.ui.goTab
import com.pockivo.nowmark.ui.theme.Nm
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = AppGraph.db.dao()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val settings by AppGraph.settings.settings.collectAsState(initial = AppSettings())
    val rows by remember(query) { dao.observeLibrary(query.trim()) }.collectAsState(initial = null)
    val folders by remember { dao.observeFolders() }.collectAsState(initial = emptyList())
    val tags by remember {
        dao.observeTagBlobs().map { blobs -> blobs.flatMap { it.split('\n') }.filter { it.isNotBlank() }.distinct().sorted() }
    }.collectAsState(initial = emptyList())
    val adFree by BillingManager.adFree.collectAsState()
    val price by BillingManager.price.collectAsState()

    LaunchedEffect(Unit) {
        context.findActivity()?.let { AdsManager.gatherConsent(it) }
    }

    val filtered = remember(rows, filter, settings.sort) {
        val base = rows.orEmpty().filter { r -> filter == null || r.folder == filter || filter in r.tags.split('\n') }
        when (settings.sort) {
            SortOrder.NEWEST -> base.sortedByDescending { it.createdAt }
            SortOrder.OLDEST -> base.sortedBy { it.createdAt }
            SortOrder.LONGEST -> base.sortedByDescending { it.durationMs }
            SortOrder.TITLE -> base.sortedBy { it.title.lowercase() }
        }
    }
    var renameTarget by remember { mutableStateOf<RecordingRow?>(null) }
    var sortOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(Modifier.weight(1f))
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.search), tint = Nm.c.ink)
                    }
                    IconButton(
                        onClick = { EntryRequests.startRecording.value = true; nav.goTab(Routes.RECORD) },
                        modifier = Modifier.clip(CircleShape).background(Nm.c.accentTint),
                    ) { Icon(Icons.Outlined.Add, stringResource(R.string.start_recording), tint = Nm.c.accent) }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = Nm.c.ink)
                    }
                }
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge, color = Nm.c.ink)
                Text(stringResource(R.string.library_sub), style = MaterialTheme.typography.bodyLarge, color = Nm.c.inkSoft)
                Spacer(Modifier.height(16.dp))
                if (searchOpen || query.isNotEmpty()) {
                    TextField(
                        value = query, onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Nm.c.ground, unfocusedIndicatorColor = Nm.c.ground,
                            focusedContainerColor = Nm.c.line, unfocusedContainerColor = Nm.c.line,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Pill(stringResource(R.string.filter_all), filter == null, { filter = null }) }
                        items((folders + tags).distinct()) { f -> Pill(f, filter == f, { filter = if (filter == f) null else f }) }
                    }
                    Box {
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort), tint = Nm.c.ink)
                        }
                        DropdownMenu(sortOpen, { sortOpen = false }) {
                            listOf(
                                SortOrder.NEWEST to R.string.sort_newest, SortOrder.OLDEST to R.string.sort_oldest,
                                SortOrder.LONGEST to R.string.sort_longest, SortOrder.TITLE to R.string.sort_title,
                            ).forEach { (o, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { if (settings.sort == o) Icon(Icons.Outlined.Check, null) },
                                    onClick = { scope.launch { AppGraph.settings.setSort(o) }; sortOpen = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (rows != null && filtered.isEmpty()) {
                item {
                    if (query.isNotBlank() || filter != null) {
                        EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body))
                    } else {
                        EmptyState(Icons.Outlined.GraphicEq, stringResource(R.string.empty_library_title), stringResource(R.string.empty_library_body)) {
                            TextButton(onClick = { EntryRequests.startRecording.value = true; nav.goTab(Routes.RECORD) }) {
                                Text(stringResource(R.string.start_recording))
                            }
                        }
                    }
                }
            }

            items(filtered, key = { it.id }) { r ->
                RecordingItem(
                    r,
                    onOpen = { nav.navigate(Routes.detail(r.id)) },
                    onRename = { renameTarget = r },
                    onTrash = { scope.launch { dao.setDeletedAt(r.id, System.currentTimeMillis()) } },
                )
                HorizontalDivider(color = Nm.c.line)
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        if (!adFree) {
            LibraryBanner()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.navigate(Routes.REMOVE_ADS) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                val p = (price as? PriceState.Ready)?.formatted
                Text(
                    if (p != null) stringResource(R.string.remove_ads_link_price, p) else stringResource(R.string.remove_ads_link),
                    style = MaterialTheme.typography.labelMedium, color = Nm.c.accent,
                )
            }
        }
    }

    renameTarget?.let { r ->
        RenameDialog(r.title, onDismiss = { renameTarget = null }) { t ->
            scope.launch { dao.rename(r.id, t) }
            renameTarget = null
        }
    }
}

@Composable
private fun RecordingItem(r: RecordingRow, onOpen: () -> Unit, onRename: () -> Unit, onTrash: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val ok = r.verifyStatus == VerifyStatus.MATCH && r.inputInterruptions == 0
        IconTile(Icons.Outlined.GraphicEq, if (ok) Nm.c.ok else Nm.c.accent, if (ok) Nm.c.okTint else Nm.c.accentTint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.title, style = MaterialTheme.typography.titleMedium, color = Nm.c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(TimeFormat.duration(r.durationMs), style = MaterialTheme.typography.bodyLarge, color = Nm.c.ink)
            }
            Text(Labels.whenShort(context, r.createdAt), style = MaterialTheme.typography.bodyMedium, color = Nm.c.inkSoft)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.markCount > 0) Tag(pluralStringResource(R.plurals.marks_count, r.markCount, r.markCount), Nm.c.accent, Nm.c.accentTint)
                    when (r.verifyStatus) {
                        VerifyStatus.MATCH -> Tag(stringResource(R.string.length_checked), Nm.c.ok, Nm.c.okTint, Icons.Outlined.Check)
                        VerifyStatus.MISMATCH -> Tag(stringResource(R.string.length_differs), Nm.c.warn, Nm.c.warnTint, Icons.Outlined.WarningAmber)
                        VerifyStatus.UNVERIFIED -> Tag(stringResource(R.string.length_unverified), Nm.c.inkSoft, Nm.c.line)
                    }
                    if (r.inputInterruptions > 0) {
                        Tag(pluralStringResource(R.plurals.interruptions_count, r.inputInterruptions, r.inputInterruptions), Nm.c.warn, Nm.c.warnTint)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.more), tint = Nm.c.inkSoft)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = { menu = false; onTrash() })
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
