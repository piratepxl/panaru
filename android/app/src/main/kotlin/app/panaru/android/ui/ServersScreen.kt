package app.panaru.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.panaru.android.R
import app.panaru.android.ui.theme.Panaru
import app.panaru.data.ConnectionCheck
import app.panaru.data.ServerEntity
import app.panaru.gokapi.ServerUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(vm: MainViewModel, onBack: () -> Unit, onEdit: (Long?) -> Unit, onFiles: (Long) -> Unit) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.servers_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.server_add)) },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(stringResource(R.string.onboarding_title), stringResource(R.string.servers_empty_body))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(servers, key = { it.id }) { s -> ServerRow(s, vm, onEdit, onFiles) }
        }
    }
}

@Composable
private fun ServerRow(s: ServerEntity, vm: MainViewModel, onEdit: (Long?) -> Unit, onFiles: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { onEdit(s.id) },
        leadingContent = { Icon(painterResource(R.drawable.ic_server), null) },
        headlineContent = { Text(s.name) },
        supportingContent = {
            Column {
                Text(s.baseUrl)
                if (s.isDefault) Text(stringResource(R.string.server_default), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.server_files)) }, onClick = { menu = false; onFiles(s.id) })
                if (!s.isDefault) DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_make_default)) },
                    leadingIcon = { Icon(Icons.Default.Star, null) },
                    onClick = { menu = false; vm.setDefault(s.id) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onEdit(s.id) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_remove)) }, onClick = { menu = false; confirm = true })
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.server_remove_title, s.name)) },
            text = { Text(stringResource(R.string.server_remove_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteServer(s.id) }) { Text(stringResource(R.string.action_remove)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Aggiunta / modifica di un server. Il salvataggio richiede una verifica riuscita ogni volta
 * che indirizzo o chiave cambiano: così non si salvano configurazioni che non funzionano.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(vm: MainViewModel, serverId: Long?, onDone: () -> Unit) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val existing = servers.find { it.id == serverId }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var url by rememberSaveable(existing?.id) { mutableStateOf(existing?.baseUrl ?: "") }
    var key by rememberSaveable { mutableStateOf("") }
    var makeDefault by rememberSaveable(existing?.id) { mutableStateOf(existing?.isDefault ?: servers.isEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConnectionCheck?>(null) }
    var checkedFor by remember { mutableStateOf<Pair<String, String>?>(null) }

    val parsed = ServerUrl.parse(url)
    val urlError = (parsed as? ServerUrl.Result.Invalid)?.takeIf { url.isNotBlank() }?.let { urlError(ctx, it.reason) }
    val changedConnection = existing == null || url.trim() != existing.baseUrl || key.isNotBlank()
    val verified = result is ConnectionCheck.Ok && checkedFor == (url to key)
    val keyNeeded = existing == null
    val canCheck = parsed is ServerUrl.Result.Valid && (key.isNotBlank() || !keyNeeded) && !checking
    val canSave = parsed is ServerUrl.Result.Valid && (!changedConnection || verified)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.server_add else R.string.server_edit)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_name)) },
                placeholder = { Text(stringResource(R.string.server_name_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it; result = null },
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text("https://files.example.com") },
                isError = urlError != null,
                supportingText = {
                    Text(urlError ?: if ((parsed as? ServerUrl.Result.Valid)?.isInsecureLocal == true)
                        stringResource(R.string.server_http_local_warning) else stringResource(R.string.server_url_hint))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; result = null },
                label = { Text(stringResource(R.string.server_api_key)) },
                supportingText = {
                    Text(stringResource(if (existing == null) R.string.server_api_key_hint else R.string.server_api_key_keep))
                },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.server_make_default), Modifier.weight(1f))
                Switch(checked = makeDefault, onCheckedChange = { makeDefault = it })
            }
            OutlinedButton(
                onClick = {
                    val valid = parsed as? ServerUrl.Result.Valid ?: return@OutlinedButton
                    checking = true
                    scope.launch {
                        val effectiveKey = key.ifBlank { null }
                        result = if (effectiveKey == null && existing != null) {
                            // Verifica con la chiave salvata.
                            vm.checkSaved(existing.id, valid)
                        } else vm.checkServer(valid, key)
                        checkedFor = url to key
                        checking = false
                    }
                },
                enabled = canCheck,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.server_check))
            }
            result?.let { CheckResult(it) }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val valid = parsed as? ServerUrl.Result.Valid ?: return@Button
                    vm.saveServer(serverId, name, valid, key, makeDefault, onDone)
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.action_save)) }
            if (!canSave && parsed is ServerUrl.Result.Valid) {
                Text(
                    stringResource(R.string.server_verify_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.server_permissions_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun CheckResult(result: ConnectionCheck) {
    val ctx = LocalContext.current
    val status = Panaru.status
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (result) {
                is ConnectionCheck.Ok -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = status.success)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.check_ok, result.version.version), style = MaterialTheme.typography.titleSmall)
                    }
                    if (result.config.maxFileSizeMb > 0) Text(
                        stringResource(R.string.check_max_size, formatSize(ctx, result.config.maxFileSizeMb * 1024L * 1024L)),
                    )
                    if (result.versionTooOld) WarningLine(stringResource(R.string.check_old_version))
                    if (result.config.endToEndEncryptionEnabled) WarningLine(stringResource(R.string.check_e2e_warning))
                }
                is ConnectionCheck.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = status.error)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.check_failed), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(errorText(ctx, result.error))
                }
            }
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row {
        Icon(Icons.Default.Warning, null, tint = Panaru.status.warning, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
