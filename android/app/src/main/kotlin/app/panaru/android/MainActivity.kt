package app.panaru.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.panaru.android.ui.HomeScreen
import app.panaru.android.ui.MainViewModel
import app.panaru.android.ui.ServerEditorScreen
import app.panaru.android.ui.ServerFilesScreen
import app.panaru.android.ui.ServersScreen
import app.panaru.android.ui.SettingsScreen
import app.panaru.android.ui.theme.PanaruTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val pickRequests = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { PanaruTheme { App(vm, pickRequests.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Scorciatoia "Carica file" dal launcher: apre direttamente il selettore. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_PICK_FILES) pickRequests.value++
    }

    companion object {
        const val ACTION_PICK_FILES = "app.panaru.action.PICK_FILES"
    }
}

/** Navigazione minima a stati: poche schermate, nessuna libreria aggiuntiva. */
private sealed interface Screen {
    data object Home : Screen
    data object Servers : Screen
    data class EditServer(val id: Long?) : Screen
    data class Files(val id: Long) : Screen
    data object Settings : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.Home -> "home"
    Screen.Servers -> "servers"
    is Screen.EditServer -> "edit:${id ?: -1}"
    is Screen.Files -> "files:$id"
    Screen.Settings -> "settings"
}

private fun decode(s: String): Screen = when {
    s == "servers" -> Screen.Servers
    s == "settings" -> Screen.Settings
    s.startsWith("edit:") -> Screen.EditServer(s.removePrefix("edit:").toLong().takeIf { it >= 0 })
    s.startsWith("files:") -> Screen.Files(s.removePrefix("files:").toLong())
    else -> Screen.Home
}

@Composable
private fun App(vm: MainViewModel, pickRequests: Int) {
    var route by rememberSaveable { mutableStateOf("home") }
    val screen = decode(route)
    var pending by rememberSaveable { mutableStateOf(listOf<String>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pending = uris.map(Uri::toString)
    }
    val pick = { picker.launch(arrayOf("*/*")) }
    // Le notifiche mostrano avanzamento e "Link pronto": il permesso si chiede una volta, all'avvio.
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && pickRequests == 0 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(pickRequests) { if (pickRequests > 0) { route = "home"; pick() } }

    BackHandler(enabled = screen != Screen.Home) {
        route = when (screen) {
            is Screen.EditServer, is Screen.Files -> "servers"
            else -> "home"
        }
    }
    when (screen) {
        Screen.Home -> HomeScreen(
            vm,
            pendingUris = pending.map(Uri::parse),
            onPendingConsumed = { pending = emptyList() },
            onPickFiles = pick,
            onServers = { route = if (vm.servers.value.isEmpty()) Screen.EditServer(null).encode() else "servers" },
            onSettings = { route = "settings" },
        )
        Screen.Servers -> ServersScreen(
            vm,
            onBack = { route = "home" },
            onEdit = { route = Screen.EditServer(it).encode() },
            onFiles = { route = Screen.Files(it).encode() },
        )
        is Screen.EditServer -> ServerEditorScreen(vm, screen.id) { route = "servers" }
        is Screen.Files -> ServerFilesScreen(vm, screen.id) { route = "servers" }
        Screen.Settings -> SettingsScreen(vm) { route = "home" }
    }
}
