package com.training.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import com.training.app.ui.theme.AmbientDeepNavy
import com.training.app.ui.theme.AmbientGlowCore
import com.training.app.ui.theme.AmbientGlowMid
import com.training.app.ui.theme.AmbientScrim

/**
 * Dark navy field with a warm glow confined to one corner, heavily feathered, plus a dark
 * scrim so foreground content always has consistent contrast regardless of where it lands
 * on the gradient. A radial gradient (sized to the real screen via BoxWithConstraints) is
 * used deliberately instead of a linear one — a linear gradient bands edge-to-edge and can't
 * produce a glow confined to a region the way the reference does.
 *
 * The glow's center drifts a few percent of the screen over a ~26s cycle, and its reach breathes
 * in and out slightly over an unrelated ~18s cycle — two independent periods so the motion never
 * lines up into an obvious, mechanical loop. Both are read inside drawBehind (a draw-phase State
 * read) rather than in the composable body, so the continuous animation only invalidates this one
 * draw call, not a recomposition of the screen content sitting on top of it.
 *
 * The root Box has a no-op clickable so it consumes every touch within its bounds. Without
 * that, a tap on an empty area (no interactive element directly there) hit-tests straight
 * through to whatever sits behind this composable in an ancestor's z-stack — found by
 * on-device testing: a stray tap on the Rest Timer overlay (which uses this) landed on an
 * exercise-list row on the screen underneath it.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val infiniteTransition = rememberInfiniteTransition(label = "ambientDrift")
        val drift by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse),
            label = "ambientDriftValue"
        )
        val breathe by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
            label = "ambientBreatheValue"
        )
        val interactionSource = remember { MutableInteractionSource() }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    if (widthPx > 0f && heightPx > 0f) {
                        val cx = widthPx * (0.78f - drift * 0.045f)
                        val cy = heightPx * (0.18f - drift * 0.035f)
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(0f to AmbientGlowCore, 0.35f to AmbientGlowMid, 0.65f to AmbientDeepNavy, 1f to AmbientDeepNavy),
                                center = Offset(cx, cy),
                                radius = maxOf(widthPx, heightPx) * (0.62f + breathe * 0.06f)
                            )
                        )
                    } else {
                        drawRect(color = AmbientDeepNavy)
                    }
                }
                .background(AmbientScrim)
                .clickable(interactionSource = interactionSource, indication = null) {}
        ) { content() }
    }
}
