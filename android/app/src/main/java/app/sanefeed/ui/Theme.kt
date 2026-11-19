package app.sanefeed.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One neutral palette and one muted accent. Every role is set so no Material default hue leaks in.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7E2),
    onPrimaryContainer = Color(0xFF13302A),
    secondary = Color(0xFF5C605E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8E5),
    onSecondaryContainer = Color(0xFF1B1C1B),
    tertiary = Color(0xFF5C605E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E8E5),
    onTertiaryContainer = Color(0xFF1B1C1B),
    background = Color(0xFFF5F5F3),
    onBackground = Color(0xFF1B1C1B),
    surface = Color(0xFFF5F5F3),
    onSurface = Color(0xFF1B1C1B),
    surfaceVariant = Color(0xFFE6E8E5),
    onSurfaceVariant = Color(0xFF5C605E),
    surfaceTint = Color(0xFF2F5D50),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFFAFAF9),
    surfaceContainerHighest = Color(0xFFEDEEEC),
    outline = Color(0xFFBFC3C0),
    outlineVariant = Color(0xFFD5D8D5),
    error = Color(0xFFA3362B),
    onError = Color.White,
    errorContainer = Color(0xFFF6DEDA),
    onErrorContainer = Color(0xFF410E09),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DB5A6),
    onPrimary = Color(0xFF0F2A22),
    primaryContainer = Color(0xFF244A3F),
    onPrimaryContainer = Color(0xFFDCE7E2),
    secondary = Color(0xFFA9ADAA),
    onSecondary = Color(0xFF1B1D1C),
    secondaryContainer = Color(0xFF2C302E),
    onSecondaryContainer = Color(0xFFE4E5E3),
    tertiary = Color(0xFFA9ADAA),
    onTertiary = Color(0xFF1B1D1C),
    tertiaryContainer = Color(0xFF2C302E),
    onTertiaryContainer = Color(0xFFE4E5E3),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE4E5E3),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE4E5E3),
    surfaceVariant = Color(0xFF2C302E),
    onSurfaceVariant = Color(0xFFA9ADAA),
    surfaceTint = Color(0xFF8DB5A6),
    surfaceContainerLowest = Color(0xFF0E100F),
    surfaceContainerLow = Color(0xFF1B1D1C),
    surfaceContainer = Color(0xFF1B1D1C),
    surfaceContainerHigh = Color(0xFF232625),
    surfaceContainerHighest = Color(0xFF2C302E),
    outline = Color(0xFF5A5F5C),
    outlineVariant = Color(0xFF3A3E3C),
    error = Color(0xFFE8A097),
    onError = Color(0xFF410E09),
    errorContainer = Color(0xFF6B2219),
    onErrorContainer = Color(0xFFF6DEDA),
)

@Composable
fun SaneFeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
