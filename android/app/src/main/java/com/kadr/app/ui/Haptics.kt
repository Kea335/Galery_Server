package com.kadr.app.ui

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * §12 asks for haptics on selection, long-press preview and backup completion —
 * and for reduce-motion to be respected. Both live here so no screen has to
 * remember the details.
 */
class Haptics(private val view: View) {
    /** A selection landing, or a long-press taking hold. */
    fun select() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    /** Something finished well — a batch draining, a restore landing. */
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

    /** A refusal: nothing to do, or an action that cannot proceed. */
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** A light tick as a value passes a step. */
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * True when the user has turned animations off in developer options or
 * accessibility settings. Compose has no built-in signal for this, so it comes
 * from the same global the platform uses.
 */
@Composable
@ReadOnlyComposable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}

/**
 * The house spring (§12: dampingRatio 0.8, no linear interpolators anywhere),
 * collapsing to an instant cut when the user has asked for less motion.
 */
@Composable
fun <T> kadrSpring(
    dampingRatio: Float = 0.8f,
    stiffness: Float = Spring.StiffnessMediumLow,
): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio, stiffness)
