package app.panaru.android.share

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.panaru.android.MainActivity
import app.panaru.android.R
import app.panaru.android.graph
import app.panaru.android.transfer.TransferService
import app.panaru.android.ui.ServerChoice
import app.panaru.android.ui.ServerPicker
import app.panaru.android.ui.UploadOptionsForm
import app.panaru.android.ui.Wordmark
import app.panaru.android.ui.errorText
import app.panaru.android.ui.formatSize
import app.panaru.android.ui.theme.Panaru
import app.panaru.android.ui.theme.PanaruTheme
import app.panaru.android.util.Links
import app.panaru.data.UploadEntity
import app.panaru.data.UploadState
import app.panaru.gokapi.UploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Destinazione dello Share Sheet: "Condividi da qualsiasi app → Panaru → upload → link pronto".
 * Il foglio può essere chiuso in qualsiasi momento: gli upload proseguono nel servizio.
 */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.init(sharedUris(intent))
        setContent {
            PanaruTheme {
                ShareSheet(
                    vm,
                    onStart = { uris, serverId, options -> vm.start(uris, serverId, options) { TransferService.start(this, uris) } },
                    onOpenApp = { startActivity(Intent(this, MainActivity::class.java)); finish() },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        /**
         * Estrae gli URI condivisi (EXTRA_STREAM singolo o multiplo e ClipData).
         * Sono accettati solo URI `content://`: un `file://` potrebbe puntare ai dati privati
         * di Panaru (database, preferenze) e farli caricare all'insaputa dell'utente.
         */
        fun sharedUris(intent: Intent): List<Uri> {
            val out = LinkedHashSet<Uri>()
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(out::addAll)
            }
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
            return out.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        }
    }
}

sealed interface ShareState {
    data object Loading : ShareState
    data object NoServer : ShareState
    data object NoFiles : ShareState
    data class Ready(val uris: List<Uri>, val servers: List<ServerChoice>, val defaultServer: Long, val defaults: UploadOptions) : ShareState
    data class Uploading(val ids: List<Long>) : ShareState
}

class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val _state = MutableStateFlow<ShareState>(ShareState.Loading)
    val state: StateFlow<ShareState> = _state.asStateFlow()
    val live = g.uploads.live

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<UploadEntity>> = _state.flatMapLatest { s ->
        if (s is ShareState.Uploading) g.database.uploads().observeByIds(s.ids) else emptyFlow()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** `true` se l'utente ha chiesto di caricare subito (preferenza predefinita). */
    var immediate = true
        private set

    fun init(uris: List<Uri>) = viewModelScope.launch {
        val servers = g.servers.all()
        val settings = g.settings.settings.value
        immediate = settings.uploadImmediatelyFromShare
        _state.value = when {
            servers.isEmpty() -> ShareState.NoServer
            uris.isEmpty() -> ShareState.NoFiles
            else -> ShareState.Ready(
                uris,
                servers.map { ServerChoice(it.id, it.name, it.isDefault) },
                servers.first().id, // l'elenco è ordinato con il predefinito in testa
                UploadOptions(settings.defaultExpiryDays, settings.defaultAllowedDownloads),
            )
        }
    }

    fun start(uris: List<Uri>, serverId: Long, options: UploadOptions, launchService: () -> Unit) = viewModelScope.launch {
        _state.value = ShareState.Loading
        val infos = withContext(Dispatchers.IO) {
            uris.mapNotNull { runCatching { g.sources.resolve(it.toString()) }.getOrNull() }
        }
        if (infos.isEmpty()) {
            _state.value = ShareState.NoFiles
            return@launch
        }
        val ids = g.uploads.enqueue(serverId, infos, options)
        launchService()
        _state.value = ShareState.Uploading(ids)
    }

    fun cancel(id: Long) = viewModelScope.launch { g.uploads.cancel(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    vm: ShareViewModel,
    onStart: (List<Uri>, Long, UploadOptions) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Wordmark(Modifier.padding(bottom = 16.dp))
            when (val s = state) {
                ShareState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ShareState.NoServer -> Message(stringResource(R.string.share_no_server)) {
                    Button(onClick = onOpenApp) { Text(stringResource(R.string.server_add)) }
                }
                ShareState.NoFiles -> Message(stringResource(R.string.share_no_files)) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
                is ShareState.Ready -> ReadyContent(s, vm.immediate, onStart)
                is ShareState.Uploading -> UploadingContent(vm, onClose)
            }
        }
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        action()
    }
}

@Composable
private fun ReadyContent(s: ShareState.Ready, immediate: Boolean, onStart: (List<Uri>, Long, UploadOptions) -> Unit) {
    if (immediate) {
        LaunchedEffect(s) { onStart(s.uris, s.defaultServer, s.defaults) }
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    var expiry by rememberSaveable { mutableStateOf(s.defaults.expiryDays) }
    var downloads by rememberSaveable { mutableStateOf(s.defaults.allowedDownloads) }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(s.defaultServer) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(pluralStringResource(R.plurals.upload_n_files, s.uris.size, s.uris.size), style = MaterialTheme.typography.titleLarge)
        ServerPicker(s.servers, server, { server = it })
        UploadOptionsForm(expiry, downloads, password, { expiry = it }, { downloads = it }, { password = it })
        Button(
            onClick = { onStart(s.uris, server, UploadOptions(expiry, downloads, password.ifEmpty { null })) },
            enabled = UploadOptions.isValidPassword(password),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_upload)) }
    }
}

@Composable
private fun UploadingContent(vm: ShareViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val items by vm.items.collectAsState()
    val live by vm.live.collectAsState()
    val done = items.isNotEmpty() && items.none { it.isActive }
    val links = items.filter { it.state == UploadState.COMPLETED && it.url != null }.map { it.fileName to it.url!! }
    var copied by remember { mutableStateOf(false) }
    val settings = ctx.graph.settings.settings.collectAsState().value

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when {
                !done -> pluralStringResource(R.plurals.share_uploading, items.size, items.size)
                links.isEmpty() -> stringResource(R.string.share_failed)
                else -> pluralStringResource(R.plurals.share_links_ready, links.size, links.size)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (done && links.isNotEmpty() && settings.autoCopyLink) {
            Text(stringResource(R.string.notif_link_copied), color = Panaru.status.success, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(items, key = { it.id }) { u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (u.state) {
                            UploadState.QUEUED, UploadState.UPLOADING -> {
                                val p = live[u.id]
                                if (p != null) LinearProgressIndicator({ p.fraction }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Panaru.status.transfer)
                                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = Panaru.status.transfer)
                                Text(
                                    if (p != null) "${formatSize(ctx, p.sent)} / ${formatSize(ctx, p.total)}" else stringResource(R.string.state_queued),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            UploadState.COMPLETED -> Text(u.url ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            UploadState.FAILED -> Text(errorText(ctx, u.errorKind, u.errorDetail), style = MaterialTheme.typography.bodySmall, color = Panaru.status.error)
                            UploadState.CANCELLED -> Text(stringResource(R.string.state_cancelled), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when {
                        u.isActive -> IconButton(onClick = { vm.cancel(u.id) }) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel_upload)) }
                        u.state == UploadState.COMPLETED -> Icon(Icons.Default.CheckCircle, null, tint = Panaru.status.success, modifier = Modifier.padding(12.dp))
                        u.state == UploadState.FAILED -> Icon(Icons.Default.Warning, null, tint = Panaru.status.error, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        if (done && links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { Links.copy(ctx, links.map { it.second }); copied = true }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_copy), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (copied) R.string.link_copied else R.string.action_copy_link))
                }
                Button(onClick = { ctx.startActivity(Links.shareIntent(ctx, links)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_share))
                }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(if (done) R.string.action_done else R.string.share_continue_background))
        }
    }
}
