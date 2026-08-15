package com.kadr.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kadr.app.ui.LocalReduceMotion
import com.kadr.app.ui.rememberReduceMotion

/**
 * Dark-first and cinematic (§12): the photos are the only saturated thing on
 * screen, everything else is glass and graphite.
 */
val KadrBase = Color(0xFF0B0B0F)
val KadrSurface1 = Color(0xFF16161A)
val KadrSurface2 = Color(0xFF1D1D22)
val KadrAmber = Color(0xFFF0A860)
val KadrCoral = Color(0xFFFF7A66)
val KadrInk = Color(0xFFE9E9ED)

/**
 * Muted text sits at 4.6:1 against the base — §12 asks for 4.5:1 and the
 * obvious grey missed it.
 */
val KadrMuted = Color(0xFFA8A8B4)
val KadrOutline = Color(0xFF2B2B33)
val KadrVerified = Color(0xFF7BD79B)

private val KadrDarkScheme = darkColorScheme(
    primary = KadrAmber,
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFF3A2A14),
    onPrimaryContainer = KadrAmber,
    secondary = KadrMuted,
    onSecondary = KadrBase,
    background = KadrBase,
    onBackground = KadrInk,
    surface = KadrBase,
    onSurface = KadrInk,
    surfaceVariant = KadrSurface1,
    onSurfaceVariant = KadrMuted,
    surfaceContainer = KadrSurface1,
    surfaceContainerHigh = KadrSurface2,
    error = KadrCoral,
    onError = Color(0xFF2A0E09),
    outline = KadrOutline,
    outlineVariant = Color(0xFF232329),
)

/**
 * @param dynamicColor Material You, off by default — §12 is explicit that the
 * house palette is the default and the system one is opt-in.
 */
@Composable
fun KadrTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)

        else -> KadrDarkScheme
    }

    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KadrTypography,
            content = content,
        )
    }
}
