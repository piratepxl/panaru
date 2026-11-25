package app.panaru.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.panaru.android.R
import app.panaru.android.util.Links
import app.panaru.gokapi.GokapiException
import app.panaru.gokapi.GokapiFile

/** File presenti sul server (`/files/list`): download, copia link, eliminazione. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFilesScreen(vm: MainViewModel, serverId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDownload by remember { mutableStateOf<GokapiFile?>(null) }
    var confirmDelete by remember { mutableStateOf<GokapiFile?>(null) }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingDownload
        pendingDownload = null
        if (uri != null && file != null) {
            vm.download(serverId, file, uri)
            vm.say(ctx.getString(R.string.download_started, file.name))
        }
    }

    LaunchedEffect(serverId) { vm.loadRemote(serverId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(servers.find { it.id == serverId }?.name ?: stringResource(R.string.server_files)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = { vm.loadRemote(serverId) }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val r = remote) {
                RemoteFiles.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RemoteFiles.Error -> Text(
                    if (r.error is GokapiException.Unauthorized) stringResource(R.string.files_need_view)
                    else errorText(ctx, r.error),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is RemoteFiles.Loaded -> if (r.files.isEmpty()) {
                    Text(stringResource(R.string.files_empty), Modifier.align(Alignment.Center).padding(32.dp))
                } else LazyColumn {
                    items(r.files, key = { it.id }) { f ->
                        RemoteRow(
                            f,
                            onDownload = { pendingDownload = f; createDoc.launch(f.name) },
                            onDelete = { confirmDelete = f },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_on_server_title)) },
            text = { Text(stringResource(R.string.delete_on_server_body, f.name, servers.find { it.id == serverId }?.name ?: "")) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteRemote(serverId, f.id) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RemoteRow(f: GokapiFile, onDownload: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${formatSize(ctx, f.sizeBytes)} · ${formatDate(ctx, f.uploadDate * 1000)}")
                val parts = buildList {
                    if (f.unlimitedTime) add(ctx.getString(R.string.status_no_expiry))
                    else if (f.expireAt > 0) add(ctx.getString(R.string.status_expires, formatDate(ctx, f.expireAt * 1000)))
                    if (f.unlimitedDownloads) add(ctx.getString(R.string.status_unlimited_downloads))
                    else add(ctx.resources.getQuantityString(R.plurals.status_downloads_left, f.downloadsRemaining, f.downloadsRemaining))
                    if (f.isPasswordProtected) add(ctx.getString(R.string.status_password))
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = { Links.copy(ctx, listOf(f.urlDownload)) }) {
                    Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy_link))
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!f.isEndToEndEncrypted) DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_download)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                        onClick = { menu = false; onDownload() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_open_link)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_new), null) },
                        onClick = { menu = false; Links.open(ctx, f.urlDownload) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = {
                        menu = false; ctx.startActivity(Links.shareIntent(ctx, listOf(f.name to f.urlDownload)))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_on_server)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}
