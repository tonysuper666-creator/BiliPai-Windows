package com.bilipai.desktop.ui.kit

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * The player-only layer of the kit (spec 5.1 and 4.8): player surfaces use a fixed dark palette whatever the app theme
 * is, so the dock, the flyouts and the overlay read the same over bright and dark video. A nested MaterialTheme gives
 * every kit control inside it these colors without a second set of controls; typography, shapes and motion are the
 * app's. The side panel follows the app theme in v1 and does not use this wrapper.
 *
 * The palette is ARGB hex between the markers. test_ux_kit_guard.py reads it and checks the WCAG contrast of every
 * text, accent and outline pair, so a color cannot drift below readable.
 */

// UX-PLAYER-DARK-BEGIN
internal const val DESKTOP_PLAYER_DARK_BACKGROUND = 0xFF0F1114L
internal const val DESKTOP_PLAYER_DARK_SURFACE = 0xFF16181DL
internal const val DESKTOP_PLAYER_DARK_CONTAINER_LOW = 0xFF1B1E24L
internal const val DESKTOP_PLAYER_DARK_CONTAINER = 0xFF20242BL
internal const val DESKTOP_PLAYER_DARK_CONTAINER_HIGH = 0xFF272B33L
internal const val DESKTOP_PLAYER_DARK_CONTAINER_HIGHEST = 0xFF2F343DL
internal const val DESKTOP_PLAYER_DARK_ON_SURFACE = 0xFFE8EAEDL
internal const val DESKTOP_PLAYER_DARK_ON_SURFACE_VARIANT = 0xFFB4B9C2L
internal const val DESKTOP_PLAYER_DARK_OUTLINE = 0xFF7A808BL
internal const val DESKTOP_PLAYER_DARK_OUTLINE_VARIANT = 0xFF3A3F49L
internal const val DESKTOP_PLAYER_DARK_PRIMARY = 0xFFFB7299L
internal const val DESKTOP_PLAYER_DARK_ON_PRIMARY = 0xFF3A0A1DL
internal const val DESKTOP_PLAYER_DARK_PRIMARY_CONTAINER = 0xFF5C1A33L
internal const val DESKTOP_PLAYER_DARK_ON_PRIMARY_CONTAINER = 0xFFFFD9E3L
internal const val DESKTOP_PLAYER_DARK_SECONDARY = 0xFFC4C9D4L
internal const val DESKTOP_PLAYER_DARK_ON_SECONDARY = 0xFF1B1E24L
internal const val DESKTOP_PLAYER_DARK_SECONDARY_CONTAINER = 0xFF363B45L
internal const val DESKTOP_PLAYER_DARK_ON_SECONDARY_CONTAINER = 0xFFE1E4EAL
internal const val DESKTOP_PLAYER_DARK_ERROR = 0xFFFFB4ABL
internal const val DESKTOP_PLAYER_DARK_ON_ERROR = 0xFF690005L
internal const val DESKTOP_PLAYER_DARK_ERROR_CONTAINER = 0xFF93000AL
internal const val DESKTOP_PLAYER_DARK_ON_ERROR_CONTAINER = 0xFFFFDAD6L
// UX-PLAYER-DARK-END

private val desktopPlayerDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(DESKTOP_PLAYER_DARK_PRIMARY),
    onPrimary = Color(DESKTOP_PLAYER_DARK_ON_PRIMARY),
    primaryContainer = Color(DESKTOP_PLAYER_DARK_PRIMARY_CONTAINER),
    onPrimaryContainer = Color(DESKTOP_PLAYER_DARK_ON_PRIMARY_CONTAINER),
    secondary = Color(DESKTOP_PLAYER_DARK_SECONDARY),
    onSecondary = Color(DESKTOP_PLAYER_DARK_ON_SECONDARY),
    secondaryContainer = Color(DESKTOP_PLAYER_DARK_SECONDARY_CONTAINER),
    onSecondaryContainer = Color(DESKTOP_PLAYER_DARK_ON_SECONDARY_CONTAINER),
    background = Color(DESKTOP_PLAYER_DARK_BACKGROUND),
    onBackground = Color(DESKTOP_PLAYER_DARK_ON_SURFACE),
    surface = Color(DESKTOP_PLAYER_DARK_SURFACE),
    onSurface = Color(DESKTOP_PLAYER_DARK_ON_SURFACE),
    surfaceVariant = Color(DESKTOP_PLAYER_DARK_CONTAINER_HIGHEST),
    onSurfaceVariant = Color(DESKTOP_PLAYER_DARK_ON_SURFACE_VARIANT),
    surfaceTint = Color.Transparent,
    error = Color(DESKTOP_PLAYER_DARK_ERROR),
    onError = Color(DESKTOP_PLAYER_DARK_ON_ERROR),
    errorContainer = Color(DESKTOP_PLAYER_DARK_ERROR_CONTAINER),
    onErrorContainer = Color(DESKTOP_PLAYER_DARK_ON_ERROR_CONTAINER),
    outline = Color(DESKTOP_PLAYER_DARK_OUTLINE),
    outlineVariant = Color(DESKTOP_PLAYER_DARK_OUTLINE_VARIANT),
    surfaceContainerLowest = Color(DESKTOP_PLAYER_DARK_BACKGROUND),
    surfaceContainerLow = Color(DESKTOP_PLAYER_DARK_CONTAINER_LOW),
    surfaceContainer = Color(DESKTOP_PLAYER_DARK_CONTAINER),
    surfaceContainerHigh = Color(DESKTOP_PLAYER_DARK_CONTAINER_HIGH),
    surfaceContainerHighest = Color(DESKTOP_PLAYER_DARK_CONTAINER_HIGHEST),
)

/** The scheme the wrapper installs, for a caller that needs a color outside a composition. */
internal fun desktopPlayerDarkColorScheme(): ColorScheme = desktopPlayerDarkScheme

/** Everything inside reads the fixed dark palette through MaterialTheme. */
@Composable
internal fun DesktopPlayerDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = desktopPlayerDarkScheme, content = content)
}
