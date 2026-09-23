package com.training.app.ui.theme
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Backgrounds — navy-tinted, not pure black.
val Bg=Color(0xFF0F1529); val Card=Color(0xFF10173A); val Secondary=Color(0xFF98989F)
// Confirm/success — the set-checkmark button.
val Confirm=Color(0xFFF87569)
// Progress & milestones — dots, day indicators, numbered badges.
val GoldAccent=Color(0xFFF8CA3B)
// Utility/informational icons (rest timer clock, etc).
val SkyBlue=Color(0xFF14A4FC)
// Profile/identity accent, used narrowly.
val RoyalBlue=Color(0xFF154A9B)
// Weekly Goals steps ring — a third accent distinct from gold (calories) and sky blue (distance).
val TealAccent=Color(0xFF3FD7B0)
// Rest-timer pause/resume button.
val PauseNavy=Color(0xFF261536)
// Primary gradient stops — Start workout, other primary CTAs.
val GradientGold=Color(0xFFFDCB49); val GradientCoral=Color(0xFFFB8B60); val GradientMagenta=Color(0xFFFD4B85)
val PrimaryGradient=Brush.linearGradient(listOf(GradientGold,GradientCoral,GradientMagenta))
// Rest-timer ring stroke gradient.
val TimerGold=Color(0xFFFDAB42); val TimerOrange=Color(0xFFFB8336)
// Ambient background glow. A linear gradient across the whole screen necessarily bands
// edge-to-edge — it can't produce a *confined* glow in one region, which is what the
// reference actually shows: near-black navy dominating the frame, with a muted warm glow
// tucked into one corner. That needs a radial gradient sized to the real screen, hence
// AmbientBackground below rather than a plain static Brush val.
val AmbientDeepNavy=Color(0xFF000319)
val AmbientGlowCore=Color(0xFF8A2A42)   // muted coral-red core — glows, doesn't blaze
val AmbientGlowMid=Color(0xFF4A1240)    // muted magenta midtone
val AmbientScrim=Color.Black.copy(alpha=0.30f)

private val colors=darkColorScheme(primary=GradientCoral,onPrimary=Color.White,background=Bg,onBackground=Color.White,surface=Card,onSurface=Color.White,surfaceVariant=Card,onSurfaceVariant=Secondary,secondary=SkyBlue,onSecondary=Color.White)
// Rough first pass at a type scale — final sizes pending screenshots.
private val type=Typography(displayLarge=TextStyle(fontSize=30.sp,fontWeight=FontWeight.Bold),headlineLarge=TextStyle(fontSize=23.sp,fontWeight=FontWeight.Bold),titleLarge=TextStyle(fontSize=19.sp,fontWeight=FontWeight.Bold),titleMedium=TextStyle(fontSize=16.sp,fontWeight=FontWeight.SemiBold),bodyLarge=TextStyle(fontSize=16.sp),bodyMedium=TextStyle(fontSize=14.sp),bodySmall=TextStyle(fontSize=12.sp))
val CardRadius=22.dp; val HeroRadius=28.dp
val CardShape=RoundedCornerShape(CardRadius); val HeroShape=RoundedCornerShape(HeroRadius)
@Composable fun TrainingTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=colors, typography=type, content=content)

// ===== Material levels =====
// One glass treatment everywhere reads as a UI kit — a real material language needs contrast
// between layers. Three levels, each with its own fill/border strength and (for the two that
// should feel lifted off the page) a soft ambient shadow:
//   Embedded  — list rows, grouped secondary content. Barely there; recedes into the page.
//   Elevated  — hero cards, primary content. The default "glass" most surfaces should use.
//   Floating  — the nav dock. Closest to the viewer: the crispest top highlight, but a very
//               translucent fill so it still reads as a system control, not another card.
// Compose has no public backdrop-blur API — Modifier.blur only blurs a composable's own content,
// not what's behind it — so depth comes from gradient fill/border strength and elevation shadow
// rather than a true blur.
// A soft light patch in the top-left corner of a surface — the "environmental lighting" that
// lets a border recede: the material reads as catching ambient light rather than being drawn
// with a visible outline.
private fun Modifier.ambientHighlight(alpha: Float = 0.09f): Modifier = this.drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.08f),
            radius = maxOf(size.width, size.height) * 0.55f
        )
    )
}

private val EmbeddedFill = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.045f), Color.White.copy(alpha = 0.020f)))
private val EmbeddedBorder = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.015f)))
fun Modifier.glassEmbedded(shape: Shape = CardShape): Modifier =
    this.clip(shape).background(EmbeddedFill).border(1.dp, EmbeddedBorder, shape)

private val ElevatedFill = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.055f)))
private val ElevatedBorder = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f)))
// No Modifier.shadow here — confirmed by direct on-device isolation (removing every other layer
// one at a time, shadow last) that Modifier.shadow(elevation, shape) on a RoundedCornerShape is
// what rendered as a hard-edged rectangle instead of a rounded shadow on this device, regardless
// of whether a custom ambientColor/spotColor was supplied. It's a platform/GPU-driver rendering
// bug, not a parameter to tune around — so depth here comes entirely from the fill/border
// gradients and the ambient highlight, not an elevation shadow.
fun Modifier.glassElevated(shape: Shape = CardShape): Modifier =
    this.clip(shape).background(ElevatedFill).ambientHighlight().border(1.dp, ElevatedBorder, shape)

private val FloatingFill = Color.White.copy(alpha = 0.065f)
private val FloatingBorder = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f)))
fun Modifier.glassFloating(shape: Shape = CardShape): Modifier =
    this.clip(shape).background(FloatingFill).ambientHighlight(0.12f).border(1.dp, FloatingBorder, shape)

/** Unretrofitted call sites default to the elevated look rather than failing to compile. */
fun Modifier.glass(shape: Shape = CardShape): Modifier = glassElevated(shape)

// ===== Typography opacity =====
// Three levels of white instead of a flat white/grey split — reads as more deliberate hierarchy
// without touching the palette itself.
val TextPrimary = Color.White
val TextSecondary = Color.White.copy(alpha = 0.65f)
val TextTertiary = Color.White.copy(alpha = 0.40f)

// ===== Spacing =====
// An 8pt rhythm — deviate from it only when something specifically needs emphasis.
object Spacing { val xs=8.dp; val sm=12.dp; val md=16.dp; val lg=24.dp; val xl=32.dp; val xxl=40.dp }

// ===== Motion =====
// One shared vocabulary rather than each screen inventing its own: a gentle, non-bouncy spring
// for content/layout changes (rows resizing, rest-ring depletion), and a snappier one purely for
// tactile press feedback so taps register as immediate.
val ContentSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
private val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)

/** Standard tactile press feedback for any tappable surface: scales down slightly while
 * pressed, springs back on release. No ripple — the scale itself is the feedback. */
@Composable
fun Modifier.pressable(scaleDown: Float = 0.97f, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleDown else 1f, PressSpring, label = "pressScale")
    return this.scale(scale).clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
