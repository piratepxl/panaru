package app.panaru.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.panaru.android.R
import app.panaru.data.ServerEntity
import app.panaru.gokapi.UploadOptions

/** Wordmark "panaru" vettoriale, tinto con il colore del testo. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.panaru_wordmark),
        contentDescription = stringResource(R.string.app_name),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
        modifier = modifier.height(22.dp),
    )
}

/** Il paniere dell'icona: usato solo nei punti d'ingresso (schermata vuota, informazioni). */
@Composable
fun Symbol(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.panaru_basket),
        contentDescription = null,
        modifier = modifier,
    )
}

/** Server selezionabile come destinazione di un upload. */
data class ServerChoice(val id: Long, val name: String, val isDefault: Boolean)

fun List<ServerEntity>.choices() = map { ServerChoice(it.id, it.name, it.isDefault) }

/** Selettore del server di destinazione (chip con menu). */
@Composable
fun ServerPicker(servers: List<ServerChoice>, selectedId: Long?, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val selected = servers.find { it.id == selectedId } ?: servers.firstOrNull()
    Box(modifier) {
        AssistChip(
            onClick = { if (servers.size > 1) open = true },
            label = { Text(stringResource(R.string.upload_to, selected?.name ?: "—")) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_server), null, Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = if (servers.size > 1) ({ Icon(Icons.Default.ArrowDropDown, null) }) else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name) },
                    leadingIcon = if (s.isDefault) ({ Icon(Icons.Default.Star, stringResource(R.string.server_default)) }) else null,
                    onClick = { onSelect(s.id); open = false },
                )
            }
        }
    }
}

val EXPIRY_CHOICES = listOf(1, 3, 7, 14, 30, 0)
val DOWNLOAD_CHOICES = listOf(1, 3, 5, 10, 0)

@Composable
fun expiryLabel(days: Int): String =
    if (days == 0) stringResource(R.string.expiry_never) else pluralStringResource(R.plurals.days, days, days)

@Composable
fun downloadsLabel(n: Int): String =
    if (n == 0) stringResource(R.string.downloads_unlimited) else pluralStringResource(R.plurals.downloads, n, n)

/** Opzioni di condivisione (scadenza, download, password) per un gruppo di file. */
@Composable
fun UploadOptionsForm(
    expiry: Int,
    downloads: Int,
    password: String,
    onExpiry: (Int) -> Unit,
    onDownloads: (Int) -> Unit,
    onPassword: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.opt_expiry), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(EXPIRY_CHOICES, expiry, onExpiry) { expiryLabel(it) }
        Text(stringResource(R.string.opt_downloads), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(DOWNLOAD_CHOICES, downloads, onDownloads) { downloadsLabel(it) }
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.opt_password)) },
            supportingText = {
                Text(
                    if (UploadOptions.isValidPassword(password)) stringResource(R.string.opt_password_hint)
                    else stringResource(R.string.opt_password_ascii),
                )
            },
            isError = !UploadOptions.isValidPassword(password),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ChoiceRow(values: List<Int>, selected: Int, onSelect: (Int) -> Unit, label: @Composable (Int) -> String) {
    // Due righe al massimo: niente scroll orizzontale nascosto.
    val rows = values.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { v ->
                    FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) })
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(Modifier.width(96.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        action()
    }
}
