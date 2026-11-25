package app.panaru.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Token del brand (brand/colors/tokens.json). */
object PanaruTokens {
    val Arancio = Color(0xFFEC5F36)
    val ArancioIntenso = Color(0xFFCD3D13)
    val ArancioProfondo = Color(0xFF952D0E)
    val ArancioVivo = Color(0xFFEE724E)
    val ArancioTenue = Color(0xFFFCDDD5)
    val Sabbia = Color(0xFFE6D5B8)
    val SabbiaSoft = Color(0xFFF1E7D6)
    val Antracite = Color(0xFF24211E)
    val AntraciteSoft = Color(0xFF2F2B27)
    val AntraciteRaised = Color(0xFF3A3530)
    val BiancoCaldo = Color(0xFFFAF6EF)
    val Pietra = Color(0xFF6E665D)
    val PietraChiara = Color(0xFFB5AA9C)
}

/** Colori di stato (successo, errore, trasferimento, avviso) per tema chiaro/scuro. */
@Immutable
data class StatusColors(val success: Color, val error: Color, val transfer: Color, val warning: Color)

private val LightStatus = StatusColors(Color(0xFF4F7A52), Color(0xFFA3263A), Color(0xFF2F6E7E), Color(0xFF8C600F))
private val DarkStatus = StatusColors(Color(0xFF8DBB8F), Color(0xFFF08C99), Color(0xFF7EC0CF), Color(0xFFE2B65A))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val Light = lightColorScheme(
    primary = PanaruTokens.ArancioIntenso,
    onPrimary = PanaruTokens.BiancoCaldo,
    primaryContainer = PanaruTokens.ArancioTenue,
    onPrimaryContainer = PanaruTokens.ArancioProfondo,
    secondary = PanaruTokens.Pietra,
    onSecondary = PanaruTokens.BiancoCaldo,
    secondaryContainer = PanaruTokens.SabbiaSoft,
    onSecondaryContainer = PanaruTokens.Antracite,
    background = PanaruTokens.BiancoCaldo,
    onBackground = PanaruTokens.Antracite,
    surface = PanaruTokens.BiancoCaldo,
    onSurface = PanaruTokens.Antracite,
    surfaceVariant = PanaruTokens.SabbiaSoft,
    onSurfaceVariant = PanaruTokens.Pietra,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFCF7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = PanaruTokens.SabbiaSoft,
    surfaceContainerHighest = PanaruTokens.Sabbia,
    outline = PanaruTokens.Sabbia,
    outlineVariant = PanaruTokens.SabbiaSoft,
    error = LightStatus.error,
)

private val Dark = darkColorScheme(
    primary = PanaruTokens.ArancioVivo,
    onPrimary = PanaruTokens.Antracite,
    primaryContainer = Color(0xFF5A2616),
    onPrimaryContainer = Color(0xFFFFCCBD),
    secondary = PanaruTokens.PietraChiara,
    onSecondary = PanaruTokens.Antracite,
    secondaryContainer = PanaruTokens.AntraciteRaised,
    onSecondaryContainer = PanaruTokens.BiancoCaldo,
    background = PanaruTokens.Antracite,
    onBackground = PanaruTokens.BiancoCaldo,
    surface = PanaruTokens.Antracite,
    onSurface = PanaruTokens.BiancoCaldo,
    surfaceVariant = PanaruTokens.AntraciteRaised,
    onSurfaceVariant = PanaruTokens.PietraChiara,
    surfaceContainerLowest = Color(0xFF1C1A17),
    surfaceContainerLow = PanaruTokens.AntraciteSoft,
    surfaceContainer = PanaruTokens.AntraciteSoft,
    surfaceContainerHigh = PanaruTokens.AntraciteRaised,
    surfaceContainerHighest = Color(0xFF45403A),
    outline = Color(0xFF4A443D),
    outlineVariant = PanaruTokens.AntraciteRaised,
    error = DarkStatus.error,
)

private val PanaruShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun PanaruTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = PanaruShapes, content = content)
    }
}

object Panaru {
    val status: StatusColors @Composable get() = LocalStatusColors.current
}
