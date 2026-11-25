package app.panaru.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.panaru.android.R
import app.panaru.data.PanaruSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.settings_section_sharing))
            ToggleRow(stringResource(R.string.settings_auto_copy), stringResource(R.string.settings_auto_copy_desc), s.autoCopyLink) { v ->
                vm.updateSettings { it.copy(autoCopyLink = v) }
            }
            ToggleRow(stringResource(R.string.settings_share_immediate), stringResource(R.string.settings_share_immediate_desc), s.uploadImmediatelyFromShare) { v ->
                vm.updateSettings { it.copy(uploadImmediatelyFromShare = v) }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_concurrency, s.maxConcurrentUploads), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = s.maxConcurrentUploads.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(maxConcurrentUploads = v.toInt()) } },
                    valueRange = PanaruSettings.CONCURRENCY_RANGE.first.toFloat()..PanaruSettings.CONCURRENCY_RANGE.last.toFloat(),
                    steps = PanaruSettings.CONCURRENCY_RANGE.last - PanaruSettings.CONCURRENCY_RANGE.first - 1,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            SectionTitle(stringResource(R.string.settings_section_defaults))
            Column(Modifier.padding(horizontal = 20.dp)) {
                UploadOptionsFormDefaults(s, vm)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_section_about))
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(Modifier.width(72.dp))
                Wordmark()
                Text(stringResource(R.string.payoff), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun UploadOptionsFormDefaults(s: PanaruSettings, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.opt_expiry), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(EXPIRY_CHOICES, s.defaultExpiryDays, { v -> vm.updateSettings { it.copy(defaultExpiryDays = v) } }) { expiryLabel(it) }
        Text(stringResource(R.string.opt_downloads), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(DOWNLOAD_CHOICES, s.defaultAllowedDownloads, { v -> vm.updateSettings { it.copy(defaultAllowedDownloads = v) } }) { downloadsLabel(it) }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
