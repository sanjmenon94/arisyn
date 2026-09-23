package com.training.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.training.app.data.local.SessionEntity
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.delay

private const val FINISHER_SECONDS = 360 // 6 minutes — the middle of the 5-8min conditioning window.
private val FINISHER_MOVEMENTS = listOf("Mountain Climbers", "Squat-to-Press", "Burpees", "Jumping Jacks")

/** The end-of-session conditioning block — a timed AMRAP through a small bodyweight circuit,
 * logged as one block on the session (duration + rounds completed) rather than individual
 * weighted sets, since it's conditioning to finish, not a lift to progress numbers on. Only ever
 * reached at the full (45min) duration — see WorkoutSelector.includesFinisher. */
@Composable
fun FinisherScreen(vm: TrainingViewModel, session: SessionEntity, onFinish: () -> Unit) {
    val context = LocalContext.current
    var remainingSeconds by remember { mutableIntStateOf(FINISHER_SECONDS) }
    var paused by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    var timeUp by remember { mutableStateOf(false) }
    var roundsText by remember { mutableStateOf("") }
    val progress = remember { Animatable(1f) }
    val elapsedSeconds = FINISHER_SECONDS - remainingSeconds

    LaunchedEffect(paused, started) {
        if (started && !paused && !timeUp) {
            val remainMs = (progress.value * FINISHER_SECONDS * 1000).toInt()
            if (remainMs > 0) {
                progress.animateTo(0f, tween(durationMillis = remainMs, easing = LinearEasing))
                if (progress.value <= 0f) { Haptics.heavyClick(context); timeUp = true }
            }
        }
    }
    LaunchedEffect(paused, started) {
        while (started && !paused && !timeUp && remainingSeconds > 0) { delay(1000); remainingSeconds-- }
    }

    val ringTransition = rememberInfiniteTransition(label = "finisherBreathe")
    val ringBreathe by ringTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "finisherBreatheValue"
    )

    AmbientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocalFireDepartment, null, tint = GoldAccent, modifier = Modifier.size(20.dp))
                Text("Finisher", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            }
            Text("AMRAP · as many rounds as possible", color = Secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))

            // A static reference list — the user moves through it at their own pace; the app only
            // times the block and asks how many rounds they got through, not each individual rep.
            Box(Modifier.fillMaxWidth().glassEmbedded(CardShape)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FINISHER_MOVEMENTS.forEachIndexed { i, move ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(GoldAccent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = GoldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(move, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.size(240.dp).scale(1f + ringBreathe * 0.015f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(240.dp).clip(CircleShape).background(Brush.radialGradient(listOf(GoldAccent.copy(alpha = .16f + ringBreathe * .05f), Color.Transparent))))
                Canvas(Modifier.size(200.dp)) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = Color.White.copy(alpha = .08f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(TimerGold, TimerOrange, TimerGold)),
                        startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                AnimatedContent(
                    targetState = timeUp,
                    transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) },
                    label = "finisherReady"
                ) { done ->
                    if (done) Text("Time!", color = GoldAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    else Text(String.format("%01d:%02d", remainingSeconds / 60, remainingSeconds % 60), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))

            if (!timeUp) {
                if (!started) {
                    Box(
                        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(PrimaryGradient)
                            .pressable { Haptics.click(context); started = true; paused = false },
                        contentAlignment = Alignment.Center
                    ) { Text("Start", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                } else {
                    IconButton(onClick = { paused = !paused }, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(PauseNavy)) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Skip finisher", color = SkyBlue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.pressable { Haptics.tick(context); onFinish() }.padding(8.dp)
                )
            } else {
                // Rounds completed is optional — a number to track progression against next time,
                // not a requirement to finish the session.
                OutlinedTextField(
                    value = roundsText, onValueChange = { roundsText = it },
                    label = { Text("Rounds completed (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(PrimaryGradient)
                        .pressable {
                            Haptics.click(context)
                            vm.saveFinisher(session, elapsedSeconds, roundsText.toIntOrNull())
                            onFinish()
                        },
                    contentAlignment = Alignment.Center
                ) { Text("Finish workout", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
