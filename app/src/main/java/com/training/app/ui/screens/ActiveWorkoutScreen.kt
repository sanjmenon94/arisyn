package com.training.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.training.app.data.WorkoutSelector
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.components.ElapsedTimePill
import com.training.app.ui.components.ExerciseImage
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Rest only happens between full rounds now, not between every set — a circuit's whole point is
// minimal rest within a round, moving straight from one exercise into the next.
private const val BETWEEN_ROUND_REST_SECONDS = 60
// The beat between tapping complete and the next screen state arriving — long enough for the
// tapped row's own settle animation (checkmark, compression, dimming) to actually be seen,
// short enough that it never reads as a pause. Action -> confirmation -> transition -> next
// action, not all four at once.
private const val SETTLE_DELAY_MS = 260L
private val REST_TIPS = listOf(
    "Breathe. Recover. Stronger next round.",
    "Shake it out. You've earned this.",
    "Sip some water. Reset your grip.",
    "Steady breaths. The next round's waiting."
)

/** One screen per circuit step (one exercise, one round) — `step` is a linear index across the
 * whole circuit (round = step / exerciseCount, exerciseIdx = step % exerciseCount), which is what
 * lets this reuse the same simple forward-only navigation the old per-exercise flow had, without
 * a nested round/exercise route. Rest only shows after the last exercise in a round; every other
 * completion advances straight to the next exercise with no overlay at all. */
@Composable
fun ActiveWorkoutScreen(vm: TrainingViewModel, step: Int, onBack: () -> Unit, onNextStep: (Int) -> Unit, onFinisher: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state by vm.today.collectAsState()
    val session = state.session ?: return
    val exercises = state.exercises
    if (exercises.isEmpty()) return
    val day = state.day
    val prescribedRounds = day?.roundsPrescribed ?: 4
    val durationMinutes = session.selectedDurationMinutes ?: 45
    val rounds = WorkoutSelector.roundsForDuration(prescribedRounds, durationMinutes)
    val totalSteps = rounds * exercises.size
    if (step !in 0 until totalSteps) return
    val round = step / exercises.size + 1
    val exerciseIdx = step % exercises.size
    val row = exercises[exerciseIdx]
    val isLastExerciseInRound = exerciseIdx == exercises.lastIndex
    val isLastStep = step == totalSteps - 1
    val inProgress = isInProgress(state.session)

    val existing by vm.setsFor(session.id, row.exercise.id).collectAsState(initial = emptyList())
    val lastPerf by vm.lastPerformance(row.exercise.id, session.id).collectAsState(initial = null)
    val alreadyThisRound = existing.find { it.setNumber == round }
    // Defaults to this exercise's most recent logged round this session (so the weight you just
    // used carries forward), falling back to the prescribed weight on the very first round.
    val defaultWeight = alreadyThisRound?.weightKg?.toString() ?: existing.lastOrNull()?.weightKg?.toString() ?: row.item.prescribedWeightKg?.toString().orEmpty()
    val defaultReps = alreadyThisRound?.reps?.toString() ?: existing.lastOrNull()?.reps?.toString() ?: row.item.prescribedReps.toString()
    var weightText by remember(step) { mutableStateOf(defaultWeight) }
    var repsText by remember(step) { mutableStateOf(defaultReps) }
    var restActive by remember(step) { mutableStateOf(false) }
    var restTip by remember(step) { mutableStateOf(REST_TIPS.random()) }

    val unit = if (row.exercise.isTimeBased) "sec" else "reps"

    fun completeAndAdvance() {
        Haptics.click(context)
        val job = vm.logSet(session.id, row.exercise.id, round, weightText.toDoubleOrNull(), repsText.toIntOrNull() ?: row.item.prescribedReps)
        when {
            isLastStep -> {
                // Same reasoning as the old straight-set flow: logSet only launches the write, so
                // wait for it before navigating, or the next screen's "any sets logged?" check can
                // race ahead of the very last write.
                scope.launch {
                    job.join(); delay(SETTLE_DELAY_MS)
                    if (WorkoutSelector.includesFinisher(durationMinutes)) onFinisher() else onFinish()
                }
            }
            isLastExerciseInRound -> {
                restTip = REST_TIPS.random()
                scope.launch { delay(SETTLE_DELAY_MS); restActive = true }
            }
            else -> scope.launch { delay(SETTLE_DELAY_MS); onNextStep(step + 1) }
        }
    }

    AmbientBackground { Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 160.dp)) {
            // clipToBounds matters here: the image layers below are scaled 1.35x to crop in
            // tighter, and a scale transform overflows the composable's own layout bounds by
            // design (it doesn't resize the layout, just enlarges what's drawn). Without a clip,
            // that overflow — roughly 17% of the box's height past its bottom edge — rendered
            // as leaked image content underneath the hero, showing up as a rectangular band right
            // where the exercise name sits.
            Box(Modifier.fillMaxWidth().height(340.dp).clipToBounds()) {
                // Full-bleed, edge to edge and behind the status bar — a small padded photo card
                // read as "pasted on" no matter how good its own internal fade was, since the card
                // boundary itself was the problem. A large hero image with no border, fading
                // straight into the screen's background, doesn't have that edge to read.
                // CompositingStrategy.Offscreen is what makes the DstIn fade actually a fade.
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    ExerciseImage(row.exercise.imageUrl, modifier = Modifier.matchParentSize().scale(1.35f))
                }
                // A blurred copy bridging the dissolve — fades in as the sharp image starts to go,
                // peaks, then fades back out, so the photo softens into the background rather than
                // simply thinning out.
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(0f to Color.Transparent, 0.40f to Color.Transparent, 0.70f to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    ExerciseImage(row.exercise.imageUrl, modifier = Modifier.matchParentSize().scale(1.35f).blur(28.dp))
                }
                Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                        if (inProgress) session.startedAt?.let { ElapsedTimePill(it, session.pausedAt, session.pausedDurationMs) }
                    }
                    // One segment per round (not per exercise/step) — each fills in proportion to
                    // how many of this round's exercises are done, so "Round 2 of 4" and the bar
                    // both tell the same story at a glance.
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        (1..rounds).forEach { r ->
                            val fill = when {
                                r < round -> 1f
                                r > round -> 0f
                                // exerciseIdx itself (not +1) — the exercise currently on screen
                                // hasn't been completed yet, so it shouldn't count toward this
                                // round's fill until its own "Complete set" tap advances past it.
                                else -> exerciseIdx.toFloat() / exercises.size
                            }
                            val segWidth by animateDpAsState(if (r == round) 24.dp else 8.dp, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow), label = "segWidth")
                            Box(Modifier.height(4.dp).width(segWidth).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(2.dp)).background(GoldAccent))
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(GoldAccent.copy(alpha = .14f))) {
                    Text("ROUND $round OF $rounds", color = GoldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.exercise.name, style = MaterialTheme.typography.headlineLarge, color = Color.White, modifier = Modifier.weight(1f))
                    row.exercise.musclewikiSlug?.let { slug ->
                        Text("Details", color = SkyBlue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://musclewiki.com/exercise/$slug"))) })
                    }
                }
                Text("Target ${row.item.targetReps} $unit", color = Secondary)
                val todayWeight = existing.maxByOrNull { it.setNumber }?.weightKg ?: row.item.prescribedWeightKg
                if (lastPerf != null || todayWeight != null) {
                    val parts = mutableListOf<String>()
                    todayWeight?.let { parts += "${it.toInt()} kg today" }
                    lastPerf?.weightKg?.let { parts += "${it.toInt()} kg last time" }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = GoldAccent, style = MaterialTheme.typography.bodyMedium)
                }

                // The active input for this exercise's round — the glow/breathe treatment carries
                // over from the straight-set screen since it's still "the one thing happening now."
                val breatheTransition = rememberInfiniteTransition(label = "activeSetBreathe")
                val breathe by breatheTransition.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
                    label = "activeSetBreatheValue"
                )
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.matchParentSize().padding(10.dp)
                            .blur(22.dp + (breathe * 8).dp, BlurredEdgeTreatment.Unbounded)
                            .clip(CardShape).background(Confirm.copy(alpha = .34f + breathe * .12f))
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(CardShape).background(Color.White.copy(alpha = .12f))
                            .padding(start = 14.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SET", modifier = Modifier.width(48.dp), color = GoldAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        GlassField(value = weightText, onValueChange = { weightText = it }, label = "kg")
                        Spacer(Modifier.width(8.dp))
                        GlassField(value = repsText, onValueChange = { repsText = it }, label = unit)
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { completeAndAdvance() },
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Confirm)
                        ) { Icon(Icons.Default.Check, null, tint = Color.White) }
                    }
                }

                // A compact strip of the round's other exercises — done ones checked, the current
                // one highlighted, the rest dim — so the circuit's shape (not just this one
                // exercise) stays visible without leaving the screen. This is the explicit
                // "Round X of Y" state the spec calls for, made concrete rather than just a label.
                Row(Modifier.fillMaxWidth().glassEmbedded(CardShape).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    exercises.forEachIndexed { i, r ->
                        val doneThisRound = vm.setsFor(session.id, r.exercise.id).collectAsState(initial = emptyList()).value.any { it.setNumber == round }
                        val isCurrent = i == exerciseIdx
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .background(if (doneThisRound) Confirm.copy(alpha = .22f) else if (isCurrent) GoldAccent.copy(alpha = .18f) else Color.White.copy(alpha = .06f))
                                    .let { if (isCurrent) it.border(1.dp, GoldAccent.copy(alpha = .6f), CircleShape) else it },
                                contentAlignment = Alignment.Center
                            ) {
                                if (doneThisRound) Icon(Icons.Default.Check, null, tint = Confirm, modifier = Modifier.size(14.dp))
                                else Text("${i + 1}", color = if (isCurrent) GoldAccent else TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.exercise.name, color = if (isCurrent) Color.White else TextTertiary, fontSize = 10.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        // A persistent, contextual CTA rather than one that only appears once everything's
        // done: it always names the next real action, so the interface reads as understanding
        // the workout instead of being a plain data-entry list.
        val ctaLabel = when {
            isLastStep && WorkoutSelector.includesFinisher(durationMinutes) -> "Finish circuit"
            isLastStep -> "Finish workout"
            isLastExerciseInRound -> "Finish round $round"
            else -> "Complete set"
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Bg).navigationBarsPadding().padding(20.dp)) {
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(PrimaryGradient)
                    .pressable { completeAndAdvance() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = ctaLabel,
                    transitionSpec = {
                        (fadeIn(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))) togetherWith
                            (fadeOut(tween(120)) + scaleOut(targetScale = 1.1f, animationSpec = tween(120)))
                    },
                    label = "ctaLabel"
                ) { text -> Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
        // A fade+settle arrival/departure rather than an instant cut to a full-screen overlay —
        // Rest is its own moment, not a hard screen swap. Only ever shown between rounds now.
        AnimatedVisibility(
            visible = restActive,
            enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.97f, animationSpec = tween(260)),
            exit = fadeOut(tween(200))
        ) {
            RestTimerOverlay(
                totalSeconds = BETWEEN_ROUND_REST_SECONDS, tip = restTip, nextSetLabel = "Round ${round + 1} · ${exercises.first().exercise.name}",
                onSkip = { restActive = false; onNextStep(step + 1) },
                onFinished = { restActive = false; onNextStep(step + 1) }
            )
        }
    } }
}

// A soft glass well rather than a conventional Material outlined field — quiet uppercase label,
// strong value, translucent fill instead of a drawn rectangle outline. Lives in the active set
// row only, so it can assume it's always in a Row and claim its share of the width.
@Composable
private fun RowScope.GlassField(value: String, onValueChange: (String) -> Unit, label: String) {
    Column(
        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .07f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label.uppercase(), color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            singleLine = true,
            cursorBrush = SolidColor(Color.White)
        )
    }
}

@Composable
internal fun RestTimerOverlay(totalSeconds: Int, tip: String, nextSetLabel: String?, onSkip: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    var remainingSeconds by remember { mutableIntStateOf(totalSeconds) }
    var paused by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(paused) {
        if (!paused) {
            val remainMs = (progress.value * totalSeconds * 1000).toInt()
            if (remainMs > 0) {
                progress.animateTo(0f, tween(durationMillis = remainMs, easing = LinearEasing))
                if (progress.value <= 0f) {
                    // Rest ending is its own small event — a success-tier pulse and a beat of
                    // "Ready" before moving on, not an instant cut to the next screen.
                    Haptics.heavyClick(context)
                    ready = true
                    delay(500)
                    onFinished()
                }
            }
        }
    }
    LaunchedEffect(paused) {
        while (!paused && remainingSeconds > 0) { delay(1000); remainingSeconds-- }
    }

    val ringTransition = rememberInfiniteTransition(label = "restRingBreathe")
    val ringBreathe by ringTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "restRingBreatheValue"
    )
    val readyScale by animateFloatAsState(if (ready) 1.08f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "readyScale")

    AmbientBackground {
        // Rest gets its own, slightly darker mood than the rest of the app — a first-class
        // moment rather than just another overlay screen.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text("Rest", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Schedule, null, tint = SkyBlue, modifier = Modifier.size(14.dp))
                Text("Next round in", color = Secondary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(260.dp).scale(1f + ringBreathe * 0.015f).scale(readyScale), contentAlignment = Alignment.Center) {
                // A soft glow behind the ring rather than the ring floating on flat black.
                Box(Modifier.size(260.dp).clip(CircleShape).background(Brush.radialGradient(listOf(TimerOrange.copy(alpha = .18f + ringBreathe * .06f), Color.Transparent))))
                Canvas(Modifier.size(220.dp)) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = Color.White.copy(alpha = .08f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(TimerGold, TimerOrange, TimerGold)),
                        startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
                AnimatedContent(
                    targetState = ready,
                    transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) },
                    label = "restReady"
                ) { isReady ->
                    if (isReady) Text("Ready", color = GoldAccent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    else Text(String.format("%01d:%02d", remainingSeconds / 60, remainingSeconds % 60), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
            nextSetLabel?.let {
                Spacer(Modifier.height(12.dp))
                Text("Next: $it", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(28.dp))
            IconButton(onClick = { paused = !paused }, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(PauseNavy)) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().glassEmbedded()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Spa, null, tint = GoldAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tip, color = Secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Skip", color = SkyBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable { Haptics.tick(context); onSkip() }.padding(8.dp))
        }
    }
}
