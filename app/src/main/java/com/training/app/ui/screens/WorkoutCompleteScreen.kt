package com.training.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.training.app.data.Badges
import com.training.app.data.local.SessionEntity
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.components.DotRating
import com.training.app.ui.components.pluralize
import com.training.app.ui.components.rememberCountUpInt
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Specific to what actually happened this session, not a generic quote. Straight quotes/periods
// only, no dashes, per the voice guide's mechanical rules.
private fun completionLine(dayFocus: String, duration: Int, hasPr: Boolean, streak: Int): String = when {
    hasPr -> "You pushed past your previous numbers on $dayFocus today. That is real progress."
    streak >= 3 -> "That is $streak days in a row now. You are building something real."
    duration >= 30 -> "You put in $duration minutes on $dayFocus today. That counts."
    else -> "You showed up for $dayFocus today. That is the work."
}

@Composable
fun WorkoutCompleteScreen(vm: TrainingViewModel, session: SessionEntity, dayFocus: String, readOnly: Boolean = false, onCancel: () -> Unit = {}, onBackToToday: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDetails by vm.sessionDetails(session.id).collectAsState(initial = emptyList())
    val allDetails by vm.allSetDetails.collectAsState()
    val summaries by vm.sessionSummaries.collectAsState()
    var rating by remember { mutableStateOf(session.burnRating ?: 0) }
    var saved by remember { mutableStateOf(readOnly) }
    var showEmptyWarning by remember { mutableStateOf(false) }

    // Duration is anchored to the session's own startedAt/completedAt, not derived from
    // scattered set-log timestamps (that broke down to "0 min" for single-set sessions).
    val completedAt = remember { session.completedAt ?: System.currentTimeMillis() }
    val sessionWithEnd = remember(completedAt) { session.copy(completedAt = completedAt) }
    val duration = remember(completedAt) {
        val start = session.startedAt
        if (start != null && completedAt > start) ((completedAt - start) / 60000).toInt().coerceAtLeast(1) else 0
    }

    val volume = setDetails.sumOf { (it.log.weightKg ?: 0.0) * it.log.reps }
    val exerciseCount = setDetails.map { it.log.exerciseId }.distinct().size
    val streak = Badges.streak(session.sessionDate, summaries.map { it.session.sessionDate }.toSet())
    val badges = listOfNotNull(Badges.streakBadge(streak)) + Badges.personalRecordBadges(session.id, allDetails)
    val hasPr = badges.any { it.startsWith("New PR") }

    // Compared against the latest OTHER completed session of the same focus — not an index
    // lookup into `summaries`, since this session itself may not be in that completed-only list
    // yet at the moment this screen first renders (completed_at is set a beat later, in
    // completeNow() below).
    val prevVolume = summaries.filter { it.dayFocus == dayFocus && it.session.id != session.id }.maxByOrNull { it.session.sessionDate }?.volume
    val volumeImproved = prevVolume != null && volume > prevVolume
    val percentGain = if (volumeImproved && prevVolume!! > 0) (((volume - prevVolume) / prevVolume) * 100).toInt() else null

    fun completeNow() {
        scope.launch {
            Haptics.heavyClick(context)
            vm.finishWorkout(sessionWithEnd, rating)
            if (badges.isNotEmpty()) { delay(350); Haptics.heavyClick(context) }
            saved = true
        }
    }

    LaunchedEffect(Unit) {
        if (!saved) {
            // completed_at (the only thing that ever marks a day "done") is set here, and only
            // here — never as a side effect of ticking a set. Zero sets logged blocks this with
            // a confirmation instead of silently recording an empty day as complete. Queried
            // directly against the DB rather than off a StateFlow snapshot, since allSetDetails
            // may not have emitted yet on every path that reaches this screen.
            if (!vm.hasLoggedSets(session.id)) showEmptyWarning = true else completeNow()
        }
    }

    if (showEmptyWarning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("No sets logged yet") },
            text = { Text("Are you sure you want to complete this workout?") },
            confirmButton = { TextButton(onClick = { showEmptyWarning = false; completeNow() }) { Text("Complete anyway") } },
            dismissButton = { TextButton(onClick = { showEmptyWarning = false; onCancel() }) { Text("Go back") } }
        )
    }

    // A short, restrained reveal sequence rather than everything appearing at once: the checkmark
    // settles in first, then the headline, then the numbers get their own beat to resolve, then
    // everything else. No confetti — just enough motion that the moment registers as an event.
    var showCheck by remember { mutableStateOf(readOnly) }
    var showText by remember { mutableStateOf(readOnly) }
    var showStats by remember { mutableStateOf(readOnly) }
    var showPercent by remember { mutableStateOf(readOnly) }
    var showExtras by remember { mutableStateOf(readOnly) }
    LaunchedEffect(Unit) {
        if (!readOnly) {
            showCheck = true; delay(250)
            showText = true; delay(200)
            showStats = true
            // The volume number gets its own beat to resolve before the comparison line lands —
            // the payoff is watching it count up, not reading two facts appearing at once.
            delay(700)
            showPercent = true
            delay(150)
            showExtras = true
        }
    }
    val checkScale by animateFloatAsState(if (showCheck) 1f else 0.7f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "checkScale")
    val checkAlpha by animateFloatAsState(if (showCheck) 1f else 0f, tween(300), label = "checkAlpha")
    val glowAlpha by animateFloatAsState(if (showCheck) 1f else 0f, tween(600), label = "glowAlpha")

    AmbientBackground { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(140.dp).alpha(glowAlpha * 0.5f).clip(CircleShape).background(Brush.radialGradient(listOf(GradientCoral.copy(alpha = .55f), Color.Transparent))))
            // A success orb, not a button: a radial (not linear) gradient so it reads as a glowing
            // sphere, plus a small offset highlight simulating light catching its top-left curve.
            Box(Modifier.size(92.dp).scale(checkScale).alpha(checkAlpha), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.radialGradient(listOf(GradientGold, GradientCoral, GradientMagenta))))
                Box(
                    Modifier.fillMaxSize(0.5f).align(Alignment.TopStart).offset(x = 12.dp, y = 10.dp)
                        .clip(CircleShape).background(Brush.radialGradient(listOf(Color.White.copy(alpha = .4f), Color.Transparent)))
                )
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        AnimatedVisibility(showText, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Workout complete.", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(dayFocus, color = Secondary, style = MaterialTheme.typography.bodyLarge)
                Text("$exerciseCount ${pluralize(exerciseCount, "exercise")} · $duration min", color = Secondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        // Volume is the hero achievement — the number that actually communicates effort — with
        // sets/duration as secondary context underneath, rather than three equal-weight stats.
        AnimatedVisibility(showStats, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }) {
            Box(Modifier.fillMaxWidth().glassElevated()) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${rememberCountUpInt(volume.toInt(), durationMillis = 700)} kg", style = MaterialTheme.typography.displayLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("TOTAL VOLUME", color = GoldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    percentGain?.let {
                        AnimatedVisibility(showPercent, enter = fadeIn(tween(350))) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(6.dp))
                                Text("↑ $it% vs your last $dayFocus", color = GoldAccent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        com.training.app.ui.components.StatBlock("${rememberCountUpInt(setDetails.size)}", "Sets")
                        com.training.app.ui.components.StatBlock("${rememberCountUpInt(duration)} min", "Duration")
                    }
                }
            }
        }
        AnimatedVisibility(showExtras, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                session.finisherDurationSec?.let { sec ->
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.glassEmbedded(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, null, tint = GoldAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        val roundsText = session.finisherRoundsCompleted?.let { " · $it ${pluralize(it, "round")}" } ?: ""
                        Text("Finisher: ${sec / 60}:${(sec % 60).toString().padStart(2, '0')}$roundsText", color = Secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A plain Box, not Material3's Card — Card carries a non-zero default
                        // elevation, which applies Modifier.shadow() internally. That's the exact
                        // combination (shadow + rounded shape) that renders as a hard-edged
                        // rectangle instead of a rounded chip on this device.
                        badges.forEach { b -> Box(Modifier.clip(RoundedCornerShape(14.dp)).background(GoldAccent.copy(alpha = .16f))) { Text(b, color = GoldAccent, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold) } }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // The workout itself already resolved the moment this screen appeared (completeNow()
                // in the LaunchedEffect above set completed_at before any rating existed) — rating is
                // purely optional extra detail on top of that, so it gets its own explicit skip
                // rather than making "Back to today" the only way past it.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("How did it feel?", color = Secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!readOnly) {
                        Text("Skip", color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.pressable { onBackToToday() }.padding(4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                DotRating(rating, editable = !readOnly, onSelect = { n -> rating = n; scope.launch { vm.finishWorkout(sessionWithEnd, n) } })
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().glassEmbedded()) {
                    Text(completionLine(dayFocus, duration, hasPr, streak), color = Secondary, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(PrimaryGradient).pressable { onBackToToday() },
                    contentAlignment = Alignment.Center
                ) { Text("Back to today", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    } }
}
