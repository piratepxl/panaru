package app.panaru.android.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.panaru.android.R
import app.panaru.android.ui.theme.Panaru
import app.panaru.android.util.Links
import app.panaru.data.LiveProgress
import app.panaru.data.UploadEntity
import app.panaru.data.UploadState
import app.panaru.gokapi.UploadOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    pendingUris: List<Uri>,
    onPendingConsumed: () -> Unit,
    onPickFiles: () -> Unit,
    onServers: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var targetServer by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val serverId = targetServer?.takeIf { id -> servers.any { it.id == id } } ?: servers.firstOrNull()?.id

    // Sincronizza con i server ogni volta che l'app torna in primo piano (al massimo ogni 30 s).
    LifecycleResumeEffect(Unit) {
        vm.sync()
        onPauseOrDispose { }
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val active = history.filter { it.isActive }
    val past = history.filterNot { it.isActive }
    val nowSec = System.currentTimeMillis() / 1000

    Scaffold(
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    title = { Wordmark() },
                    actions = {
                        IconButton(onClick = { vm.sync(manual = true) }, enabled = !refreshing) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                        }
                        IconButton(onClick = onServers) { Icon(painterResource(R.drawable.ic_server), stringResource(R.string.servers_title)) }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings_title)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                val links = past.filter { it.id in selection && it.url != null }.map { it.fileName to it.url!! }
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected, selection.size, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
                    },
                    actions = {
                        IconButton(onClick = {
                            Links.copy(context, links.map { it.second }); selection = emptySet()
                            vm.say(context.resources.getQuantityString(R.plurals.links_copied, links.size, links.size))
                        }, enabled = links.isNotEmpty()) { Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy_link)) }
                        IconButton(onClick = {
                            context.startActivity(Links.shareIntent(context, links)); selection = emptySet()
                        }, enabled = links.isNotEmpty()) { Icon(Icons.Default.Share, stringResource(R.string.action_share)) }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(
                    title = stringResource(R.string.onboarding_title),
                    body = stringResource(R.string.onboarding_body),
                ) { Button(onClick = onServers) { Text(stringResource(R.string.server_add)) } }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    ServerPicker(servers.choices(), serverId, { targetServer = it })
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onPickFiles, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(painterResource(R.drawable.ic_upload), null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_pick_files), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.home_hint_share),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (active.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_in_progress)) }
                if (active.size > 1) item { OverallProgress(active, live) }
                items(active, key = { it.id }) { u -> ActiveRow(u, live[u.id]) { vm.cancel(u.id) } }
            }
            if (past.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.section_history), Modifier.weight(1f))
                        TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(end = 8.dp)) {
                            Text(stringResource(R.string.action_clear_history))
                        }
                    }
                }
                items(past, key = { it.id }) { u ->
                    HistoryRow(
                        u, nowSec, selected = u.id in selection, selecting = selection.isNotEmpty(),
                        onToggle = { selection = if (u.id in selection) selection - u.id else selection + u.id },
                        vm = vm,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else if (active.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_body)) },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text(stringResource(R.string.action_clear_history)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // Opzioni prima del caricamento dei file scelti dal selettore.
    if (pendingUris.isNotEmpty() && serverId != null) {
        UploadOptionsSheet(
            count = pendingUris.size,
            defaults = UploadOptions(settings.defaultExpiryDays, settings.defaultAllowedDownloads),
            servers = servers.choices(),
            serverId = serverId,
            onDismiss = onPendingConsumed,
            onConfirm = { sid, options -> vm.upload(pendingUris, sid, options); onPendingConsumed() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadOptionsSheet(
    count: Int,
    defaults: UploadOptions,
    servers: List<ServerChoice>,
    serverId: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long, UploadOptions) -> Unit,
) {
    var expiry by rememberSaveable { mutableStateOf(defaults.expiryDays) }
    var downloads by rememberSaveable { mutableStateOf(defaults.allowedDownloads) }
    var password by rememberSaveable { mutableStateOf("") }
    var sid by rememberSaveable { mutableStateOf(serverId) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(pluralStringResource(R.plurals.upload_n_files, count, count), style = MaterialTheme.typography.titleLarge)
            if (servers.size > 1) {
                ServerPicker(servers, sid, { sid = it })
            }
            UploadOptionsForm(expiry, downloads, password, { expiry = it }, { downloads = it }, { password = it })
            Button(
                onClick = { onConfirm(sid, UploadOptions(expiry, downloads, password.ifEmpty { null })) },
                enabled = UploadOptions.isValidPassword(password),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.action_upload)) }
        }
    }
}

@Composable
private fun OverallProgress(active: List<UploadEntity>, live: Map<Long, LiveProgress>) {
    val total = active.sumOf { live[it.id]?.total ?: it.sizeBytes }
    val sent = active.sumOf { live[it.id]?.sent ?: 0 }
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        LinearProgressIndicator(
            progress = { if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = Panaru.status.transfer,
        )
        Text(
            stringResource(R.string.overall_progress, formatSize(ctx, sent), formatSize(ctx, total)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ActiveRow(u: UploadEntity, progress: LiveProgress?, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    ListItem(
        headlineContent = { Text(u.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = Panaru.status.transfer,
                    )
                    Text("${formatSize(ctx, progress.sent)} / ${formatSize(ctx, progress.total)} · ${u.serverName}")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Panaru.status.transfer)
                    Text(stringResource(if (u.state == UploadState.QUEUED) R.string.state_queued else R.string.state_starting) + " · " + u.serverName)
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel_upload)) }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    u: UploadEntity,
    nowSec: Long,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    vm: MainViewModel,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val status = Panaru.status
    val ok = u.state == UploadState.COMPLETED
    val usable = ok && !u.isExpired(nowSec) && u.url != null
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = {
                    when {
                        selecting && u.url != null -> onToggle()
                        usable -> { Links.copy(ctx, listOf(u.url!!)); vm.say(ctx.getString(R.string.link_copied)) }
                    }
                },
                onLongClick = { if (u.url != null) onToggle() },
            ),
            leadingContent = {
                when (u.state) {
                    UploadState.COMPLETED -> Icon(
                        Icons.Default.CheckCircle, null,
                        tint = if (usable) status.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UploadState.FAILED -> Icon(Icons.Default.Warning, null, tint = status.error)
                    else -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            headlineContent = { Text(u.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(
                        "${formatSize(ctx, u.sizeBytes)} · ${u.serverName} · ${formatDate(ctx, u.completedAt ?: u.createdAt)}",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (u.fromOtherDevice) Text(
                        stringResource(R.string.history_other_device),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val line = when (u.state) {
                        UploadState.COMPLETED -> statusLine(ctx, u, nowSec)
                        UploadState.FAILED -> errorText(ctx, u.errorKind, u.errorDetail)
                        else -> stringResource(R.string.state_cancelled)
                    }
                    if (line.isNotEmpty()) Text(
                        line,
                        color = if (u.state == UploadState.FAILED) status.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = {
                Row {
                    if (usable && !selecting) IconButton(onClick = {
                        Links.copy(ctx, listOf(u.url!!)); vm.say(ctx.getString(R.string.link_copied))
                    }) { Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy_link)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (usable) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                                menu = false; ctx.startActivity(Links.shareIntent(ctx, listOf(u.fileName to u.url!!)))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_open_link)) }, onClick = {
                                menu = false; Links.open(ctx, u.url!!)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_on_server)) }, onClick = {
                                menu = false; confirmDelete = true
                            })
                        }
                        if (u.state == UploadState.FAILED || u.state == UploadState.CANCELLED) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_retry)) }, onClick = {
                                menu = false
                                vm.retry(u.id) { vm.say(ctx.getString(R.string.retry_password_lost)) }
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_history)) }, onClick = {
                            menu = false; vm.removeFromHistory(u.id)
                        })
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_on_server_title)) },
            text = { Text(stringResource(R.string.delete_on_server_body, u.fileName, u.serverName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteFromServer(u.id) { vm.say(errorText(ctx, it)) }
                }) { Text(stringResource(R.string.action_delete), color = status.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
