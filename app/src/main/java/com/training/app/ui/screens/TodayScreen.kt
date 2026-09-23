package com.training.app.ui.screens

import android.app.NotificationManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.training.app.data.CURRENT_WEEK
import com.training.app.data.Pace
import com.training.app.data.PROGRAM_LENGTH_WEEKS
import com.training.app.data.RaceSessionType
import com.training.app.data.Seeds
import com.training.app.data.WorkoutSelector
import com.training.app.data.local.RacePlanWeekEntity
import com.training.app.data.local.SessionEntity
import com.training.app.data.local.UserGoalsEntity
import com.training.app.health.HealthConnectManager
import com.training.app.notifications.SleepReminderReceiver
import com.training.app.ui.BottomDockClearance
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.components.ElapsedTimePill
import com.training.app.ui.components.ExerciseImage
import com.training.app.ui.components.WeekStripCompact
import com.training.app.ui.components.pluralize
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Circuit timing: ~40s per exercise per round (work), 60s between rounds (not between every
// exercise — a circuit's whole point is minimal rest within a round), plus the 6min finisher
// block when it's included. Replaces the old straight-set estimate (sum of targetSets * 2.2min),
// which assumed rest between every single set of the same exercise.
fun estimatedMinutes(exerciseCount: Int, rounds: Int, includesFinisher: Boolean): Int {
    val workSeconds = exerciseCount * rounds * 40
    val restSeconds = (rounds - 1).coerceAtLeast(0) * 60
    val finisherSeconds = if (includesFinisher) 360 else 0
    val raw = (workSeconds + restSeconds + finisherSeconds) / 60.0
    return ((raw / 5).roundToInt() * 5).coerceAtLeast(10)
}

fun isInProgress(session: SessionEntity?): Boolean = session?.startedAt != null && session.completedAt == null

// "8h 20m" rather than "8.3h" — matches how the value is actually entered (separate hour/minute
// fields), so the summary reads back the same shape the user typed in.
fun formatSleepDuration(hours: Double): String {
    val totalMinutes = (hours * 60).roundToInt()
    val h = totalMinutes / 60; val m = totalMinutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

enum class SessionButtonState { START, COMPLETE, VIEW }
fun sessionButtonState(session: SessionEntity?): SessionButtonState = when {
    session?.completedAt != null -> SessionButtonState.VIEW
    session?.startedAt != null -> SessionButtonState.COMPLETE
    else -> SessionButtonState.START
}

@Composable
fun TodayScreen(
    vm: TrainingViewModel,
    onStartWorkout: () -> Unit,
    onExercise: (Int) -> Unit,
    onCompleteWorkout: () -> Unit,
    onViewWorkout: () -> Unit
) {
    val context = LocalContext.current
    val state by vm.today.collectAsState()
    val day = state.day
    val session = state.session
    val buttonState = sessionButtonState(session)
    val paused = session?.pausedAt != null

    // 5K plan: Wed/Fri/Sat carry a structured run alongside (Wed/Fri) or instead of (Sat, which
    // has no lifting day at all) the usual program. currentRaceWeekNumber is null until a baseline
    // pace exists — RaceBaselineDialog below is what sets one, either from Health Connect history
    // or directly from the user.
    val raceBaseline by vm.raceBaseline.collectAsState()
    val currentRaceWeekNumber by vm.currentRaceWeekNumber.collectAsState()
    val todayRaceSessionType = when (LocalDate.now().dayOfWeek) {
        java.time.DayOfWeek.WEDNESDAY -> RaceSessionType.TEMPO
        java.time.DayOfWeek.FRIDAY -> RaceSessionType.INTERVAL
        java.time.DayOfWeek.SATURDAY -> RaceSessionType.LONG_RUN
        else -> null
    }
    val racePlanWeekRows by (currentRaceWeekNumber?.let { vm.racePlanWeek(it) } ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val todayRaceTemplate = todayRaceSessionType?.let { type -> racePlanWeekRows.find { it.sessionType == type.key } }
    LaunchedEffect(currentRaceWeekNumber) { currentRaceWeekNumber?.let { vm.ensureWeekAdjustmentComputed(it) } }
    var showBaselineDialog by remember { mutableStateOf(false) }
    // Tries Health Connect's own recent run history first (a real baseline, not a guess); only
    // asks the user directly when there isn't enough of that to derive one from.
    LaunchedEffect(raceBaseline) {
        if (raceBaseline == null) {
            val hcPace = if (HealthConnectManager.isAvailable(context) && HealthConnectManager.hasAllPermissions(context))
                HealthConnectManager.recentRunPaceSecPerKm(context) else null
            if (hcPace != null) vm.setRaceBaseline(hcPace, "health_connect") else showBaselineDialog = true
        }
    }

    var showSleepDialog by remember { mutableStateOf(false) }
    var showSwitchSheet by remember { mutableStateOf(false) }
    val requestSleepEntry by vm.requestSleepEntry.collectAsState()
    LaunchedEffect(requestSleepEntry) {
        if (requestSleepEntry) { showSleepDialog = true; vm.clearSleepEntryRequest() }
    }
    val todaySleep by vm.todaySleep.collectAsState()

    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weekDates = (0..6).map { monday.plusDays(it.toLong()) }
    val summaries by vm.sessionSummaries.collectAsState()
    val loggedDoneDates = summaries.mapNotNull { runCatching { LocalDate.parse(it.session.sessionDate) }.getOrNull() }
        .filter { !it.isBefore(monday) && !it.isAfter(monday.plusDays(6)) }.toSet()
    // A day the watch caught a run/lift on counts as done here too, even when it was never
    // logged in Arisyn — this only ever adds days, never removes one Arisyn already has.
    var hcWeekDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    LaunchedEffect(monday) {
        if (HealthConnectManager.isAvailable(context) && HealthConnectManager.hasAllPermissions(context)) {
            hcWeekDates = HealthConnectManager.workoutDates(
                context,
                monday.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                monday.plusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant()
            )
        }
    }
    val doneDates = loggedDoneDates + hcWeekDates
    val trainingDaysPerWeek = Seeds.days.size

    // Week-to-date cumulative totals behind the Weekly Goals rings — same Monday-to-now window as
    // the "this week" strip above, so both sections agree on what "this week" means.
    val goals by vm.goals.collectAsState()
    var weekTotals by remember { mutableStateOf<HealthConnectManager.WeeklyTotals?>(null) }
    LaunchedEffect(monday) {
        weekTotals = if (HealthConnectManager.isAvailable(context) && HealthConnectManager.hasAllPermissions(context)) {
            HealthConnectManager.weeklyTotals(context, monday.atStartOfDay(ZoneId.systemDefault()).toInstant(), java.time.Instant.now())
        } else null
    }

    // Ambient background lives on the root, full bleed; every surface above it is translucent
    // (Theme.glass()) rather than opaque, so the gradient tints through differently per card.
    // Scrollable: wrapping exercise names (rather than truncating) can make the list taller than
    // one screen. A scrolling Column has unbounded height, so weighted Spacers would silently
    // collapse to zero here (confirmed in an earlier round) — fixed gaps instead.
    AmbientBackground { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = BottomDockClearance)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Week $CURRENT_WEEK of $PROGRAM_LENGTH_WEEKS", color = GoldAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            // Only offered before a session exists — once one's created it's pinned to whatever
            // day was showing (see effectiveDay in the ViewModel), so switching can't swap the
            // exercise list out from under sets already logged against it.
            if (buttonState == SessionButtonState.START) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).glassFloating(RoundedCornerShape(20.dp)).pressable { showSwitchSheet = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SwapHoriz, null, tint = Secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Switch", color = Secondary, fontSize = 12.sp)
                }
            }
            // Gated on isInProgress (not just session != null): once completedAt is set, the
            // session is done and this pill should stop ticking rather than keep climbing off
            // whatever startedAt was, regardless of whether the user picked an effort rating.
            if (isInProgress(session)) session?.startedAt?.let { ElapsedTimePill(it, session.pausedAt, session.pausedDurationMs) }
        }
        // Same treatment as a lifting day — today's focus, target, and a way to log the actual
        // result — shown above the lifting hero on Wed/Fri (both apply that day) and in place of
        // it on Sat, which has no lifting day in the program at all.
        if (todayRaceSessionType != null && todayRaceTemplate != null) {
            Spacer(Modifier.height(20.dp))
            RacePlanCard(vm, todayRaceSessionType, todayRaceTemplate)
        }
        if (day == null) {
            Spacer(Modifier.height(20.dp))
            if (todayRaceTemplate == null) Text(
                if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SATURDAY || LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY) "Rest day. Recover well." else "Loading your program...",
                color = Secondary
            )
        } else {
            Spacer(Modifier.height(20.dp))
            val dateLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())).uppercase()
            val muscles = state.exercises.map { it.exercise.muscleGroup.replaceFirstChar { c -> c.uppercase() } }.distinct()
            val selectedDuration by vm.selectedDuration.collectAsState()
            val activeDurationMinutes = session?.selectedDurationMinutes ?: selectedDuration
            val prescribedRounds = day.roundsPrescribed
            val rounds = WorkoutSelector.roundsForDuration(prescribedRounds, activeDurationMinutes)
            val includesFinisher = WorkoutSelector.includesFinisher(activeDurationMinutes)
            // Tapping an exercise row always jumps to wherever the circuit actually is, not to
            // "round 1 of that exercise" — the exercise index only doubles as a circuit step while
            // no round has been touched yet (i.e. before the first tap), so this resume point is
            // what every row uses once the session is actually under way.
            val allSetDetails by vm.allSetDetails.collectAsState()
            val sessionSets = session?.let { s -> allSetDetails.filter { it.log.sessionId == s.id } } ?: emptyList()
            fun resumeStep(): Int {
                val n = state.exercises.size
                if (n == 0) return 0
                for (step in 0 until rounds * n) {
                    val round = step / n + 1
                    val exId = state.exercises[step % n].exercise.id
                    if (sessionSets.none { it.log.exerciseId == exId && it.log.setNumber == round }) return step
                }
                return (rounds * n - 1).coerceAtLeast(0)
            }
            val minutes = estimatedMinutes(state.exercises.size, rounds, includesFinisher)
            val heroLine = when {
                buttonState == SessionButtonState.START -> "Ready when you are."
                buttonState == SessionButtonState.COMPLETE && paused -> "Paused. Jump back in anytime."
                buttonState == SessionButtonState.COMPLETE -> "You're mid-session. Keep going."
                else -> "That's the session."
            }
            Box(Modifier.fillMaxWidth().glassElevated(HeroShape)) {
                // A soft atmospheric wash rather than another image — the hero should feel like
                // the entrance into the workout, not just a card holding metadata. Confirmed by
                // isolation testing that this isn't the source of the rectangular shadow artifact
                // (Modifier.shadow was) — safe to keep.
                Box(Modifier.matchParentSize().clip(HeroShape).background(Brush.radialGradient(listOf(GradientCoral.copy(alpha = .14f), Color.Transparent))))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(dateLabel, color = GoldAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(day.focus, style = MaterialTheme.typography.displayLarge, color = Color.White)
                    Text(muscles.joinToString(" · "), color = Secondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    // Gold only while the timer is actually ticking — paused reverts to the same
                    // neutral color as never-started, since paused isn't "live" either.
                    val timerRunning = buttonState == SessionButtonState.COMPLETE && !paused
                    val metaColor = if (timerRunning) GoldAccent.copy(alpha = .75f) else Secondary
                    val heroLineColor = if (timerRunning) GoldAccent.copy(alpha = .6f) else TextTertiary
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${state.exercises.size} ${pluralize(state.exercises.size, "exercise")}", color = metaColor, style = MaterialTheme.typography.bodySmall)
                        Text("~$minutes min", color = metaColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(heroLine, color = heroLineColor, style = MaterialTheme.typography.bodySmall)
                    // The duration dial only matters before a session exists — it decides how many
                    // rounds ensureSession stamps onto the new SessionEntity. Once a session exists
                    // that stamped value is authoritative (see activeDurationMinutes above), so
                    // changing your mind mid-session can't quietly shrink the circuit under you.
                    if (buttonState == SessionButtonState.START) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45).forEach { mins ->
                                val isSelected = selectedDuration == mins
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) GoldAccent.copy(alpha = .18f) else Color.White.copy(alpha = .06f))
                                        .let { if (isSelected) it.border(1.dp, GoldAccent.copy(alpha = .5f), RoundedCornerShape(14.dp)) else it }
                                        .pressable { vm.selectDuration(mins) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("$mins min", color = if (isSelected) GoldAccent else Secondary, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val ctaInteraction = remember { MutableInteractionSource() }
                        val ctaPressed by ctaInteraction.collectIsPressedAsState()
                        val ctaScale by animateFloatAsState(if (ctaPressed) 0.97f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh), label = "ctaScale")
                        Box(
                            Modifier.weight(1f).height(48.dp).scale(ctaScale).clip(RoundedCornerShape(24.dp)).background(PrimaryGradient)
                                .clickable(interactionSource = ctaInteraction, indication = null) {
                                    Haptics.click(context)
                                    when (buttonState) {
                                        SessionButtonState.START -> vm.ensureSession(day) { onStartWorkout() }
                                        SessionButtonState.COMPLETE -> onCompleteWorkout()
                                        SessionButtonState.VIEW -> onViewWorkout()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // A brief white wash on press stands in for "the gradient brightens" —
                            // simpler and more reliable than recomputing brightened gradient stops.
                            if (ctaPressed) Box(Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.14f)))
                            Text(
                                when (buttonState) { SessionButtonState.START -> "Start workout"; SessionButtonState.COMPLETE -> "Complete workout"; SessionButtonState.VIEW -> "View workout" },
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                            )
                        }
                        if (buttonState == SessionButtonState.COMPLETE && session != null) {
                            IconButton(
                                onClick = { Haptics.click(context); if (paused) vm.resumeSession(session) else vm.pauseSession(session) },
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(PauseNavy)
                            ) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White) }
                            IconButton(
                                onClick = { Haptics.click(context); vm.resetSessionTimer(session) },
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(PauseNavy)
                            ) { Icon(Icons.Default.Replay, null, tint = Color.White) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Right under the hero, not buried at the bottom — the whole point is that this is
            // easy to log the moment the app opens. Health Connect isn't delivering sleep at all
            // right now (confirmed on-device: the watch/Samsung Health side never actually writes a
            // sleep session through), so this is a manual stand-in rather than a read-only display —
            // tapping it always opens the entry dialog, whether or not today already has a value,
            // so a wrong number is easy to fix.
            Row(
                Modifier.fillMaxWidth().glassEmbedded(RoundedCornerShape(16.dp)).pressable { showSleepDialog = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Bedtime, null, tint = SkyBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sleep", color = Secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    todaySleep?.let { "${formatSleepDuration(it.hours)} logged" } ?: "Log last night's sleep",
                    color = if (todaySleep != null) Color.White else GoldAccent, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
            }

            Spacer(Modifier.height(20.dp))

            // Order is still tracked via each row's index (used for navigation and, upstream,
            // orderIndex) — it just isn't shown as a visual badge any more.
            // One continuous embedded surface with barely-there dividers, not four separate
            // cards — reads as a single object, not rows inside a container.
            Box(Modifier.fillMaxWidth().glassEmbedded(CardShape)) {
                Column {
                    state.exercises.forEachIndexed { index, row ->
                        // A tiny asymmetry: the first exercise reads as the natural starting
                        // point (unless the day is already fully viewed/done), rather than every
                        // row lining up with identical weight.
                        val isFirst = index == 0 && buttonState != SessionButtonState.VIEW
                        Row(
                            Modifier.fillMaxWidth().pressable(scaleDown = 0.985f) { vm.ensureSession(day) { onExercise(if (buttonState == SessionButtonState.START) index else resumeStep()) } }.padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExerciseImage(row.exercise.imageUrl, modifier = Modifier.size(if (isFirst) 64.dp else 56.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.exercise.name, color = if (isFirst) TextPrimary else TextPrimary.copy(alpha = .92f), style = if (isFirst) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                                Text("$rounds rounds · ${row.item.targetReps}", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                        }
                        if (index != state.exercises.lastIndex) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.045f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }

            if (goals != null && (goals?.dailyCalories != null || goals?.dailyDistanceKm != null || goals?.dailySteps != null)) {
                Spacer(Modifier.height(20.dp))
                WeeklyGoalsSection(goals!!, weekTotals)
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth().glassEmbedded(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("This week", color = Secondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
                WeekStripCompact(weekDates, doneDates, modifier = Modifier.weight(1f))
            }
        }
    } }

    if (showSleepDialog) {
        SleepDialog(
            currentHours = todaySleep?.hours,
            onSave = { hours ->
                vm.logSleep(hours)
                // The 9am reminder is deliberately non-swipeable until acted on — once sleep is
                // actually logged there's nothing left for it to remind about, so it should clear
                // itself rather than linger for the rest of the day.
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(SleepReminderReceiver.NOTIFICATION_ID)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }
    if (showSwitchSheet) {
        SwitchWorkoutSheet(
            currentFocus = day?.focus,
            onSelect = { dayId -> vm.switchDay(dayId); showSwitchSheet = false },
            onDismiss = { showSwitchSheet = false }
        )
    }
    if (showBaselineDialog) {
        RaceBaselineDialog(
            onSave = { secPerKm -> vm.setRaceBaseline(secPerKm, "manual"); showBaselineDialog = false },
            onDismiss = { showBaselineDialog = false }
        )
    }
}

/** One weekly target the user configured in Profile, with this week's progress toward it — a
 * single struct so the rings row and the tap-to-expand detail below it stay in sync on exactly
 * the same numbers. `unitSuffix` is blank for steps (the bare count already reads fine). */
private data class WeeklyGoal(val key: String, val label: String, val color: Color, val current: Double, val target: Double, val unitSuffix: String, val format: (Double) -> String)

/** Compact by design: three small rings in one glass surface, not three cards — a natural
 * extension of the Today screen rather than a new dashboard. Only renders the rings for targets
 * the user has actually set in Profile; a target left unset simply doesn't get a ring, rather
 * than showing an empty/zero one that would imply a goal exists when it doesn't. */
@Composable
private fun WeeklyGoalsSection(goals: UserGoalsEntity, weekTotals: HealthConnectManager.WeeklyTotals?) {
    val context = LocalContext.current
    var expandedKey by remember { mutableStateOf<String?>(null) }

    val goalList = buildList {
        goals.dailyCalories?.let { daily ->
            add(WeeklyGoal("calories", "Calories", GoldAccent, (weekTotals?.calories ?: 0L).toDouble(), daily * 7.0, " kcal") { String.format(Locale.getDefault(), "%,d", it.roundToInt()) })
        }
        goals.dailyDistanceKm?.let { daily ->
            add(WeeklyGoal("distance", "Distance", SkyBlue, weekTotals?.distanceKm ?: 0.0, daily * 7.0, " km") { String.format(Locale.getDefault(), "%.1f", it) })
        }
        goals.dailySteps?.let { daily ->
            add(WeeklyGoal("steps", "Steps", TealAccent, (weekTotals?.steps ?: 0L).toDouble(), daily.toDouble() * 7.0, "") { String.format(Locale.getDefault(), "%,d", it.roundToInt()) })
        }
    }
    if (goalList.isEmpty()) return

    Box(Modifier.fillMaxWidth().glassEmbedded(RoundedCornerShape(20.dp))) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                goalList.forEach { g ->
                    val progress = if (g.target > 0) (g.current / g.target).toFloat() else 0f
                    WeeklyGoalRing(
                        label = g.label, color = g.color, progress = progress,
                        valueLine = "${g.format(g.current)} / ${g.format(g.target)}${g.unitSuffix}",
                        onClick = { Haptics.tick(context); expandedKey = if (expandedKey == g.key) null else g.key }
                    )
                }
            }
            val expanded = goalList.find { it.key == expandedKey }
            AnimatedVisibility(visible = expanded != null) {
                expanded?.let { g ->
                    val remaining = (g.target - g.current).coerceAtLeast(0.0)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 1.dp, modifier = Modifier.padding(bottom = 10.dp))
                        Text(
                            if (remaining > 0) "${g.format(remaining)}${g.unitSuffix} to go this week" else "Weekly ${g.label.lowercase()} goal reached",
                            color = g.color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                        Text("Target ${g.format(g.target)}${g.unitSuffix} per week", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** A single ring: an animated arc for progress, a checkmark-and-glow swap once it clears 100%
 * (values over 100% stay visually complete rather than wrapping the arc around again), and one
 * light success haptic the moment it first completes — `celebrated` keyed to this composition so
 * it fires once per time the ring appears completed, not on every recomposition while it stays there. */
@Composable
private fun WeeklyGoalRing(label: String, color: Color, progress: Float, valueLine: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val completed = progress >= 1f
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "goalRingProgress")
    var celebrated by remember { mutableStateOf(false) }
    LaunchedEffect(completed) { if (completed && !celebrated) { celebrated = true; Haptics.heavyClick(context) } }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressable(scaleDown = 0.95f) { onClick() }.padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            if (completed) Box(Modifier.matchParentSize().clip(CircleShape).background(Brush.radialGradient(listOf(color.copy(alpha = .30f), Color.Transparent))))
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.11f
                drawArc(color = Color.White.copy(alpha = .10f), startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(color = color, startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (completed) Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(20.dp))
            else Text("${(progress * 100).toInt().coerceIn(0, 999)}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Secondary, style = MaterialTheme.typography.bodySmall)
        Text(valueLine, color = TextTertiary, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

/** Manual sleep entry, styled like Profile's other dialogs (WeightDialog, GoalsDialog) — the same
 * family of popup across the app. Exists only because Health Connect isn't actually delivering
 * sleep on this setup; if that ever starts working reliably, this becomes redundant rather than
 * load-bearing. Hours and minutes are separate fields (same pattern as the race plan's distance/
 * duration entry) so something like 8h 20m is actually representable — storage is still a single
 * fractional-hours Double under the hood, so combine on save rather than changing the schema. */
@Composable
private fun SleepDialog(currentHours: Double?, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    val initialWholeHours = currentHours?.toInt()
    val initialMinutes = currentHours?.let { ((it - it.toInt()) * 60).roundToInt() }
    var hoursText by remember { mutableStateOf(initialWholeHours?.toString().orEmpty()) }
    var minutesText by remember { mutableStateOf(initialMinutes?.takeIf { it != 0 }?.toString().orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Log sleep", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("How long did you sleep last night?", color = Secondary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hoursText, onValueChange = { hoursText = it },
                    label = { Text("Hours") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minutesText, onValueChange = { minutesText = it },
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cancel", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PrimaryGradient)
                        .pressable {
                            val h = hoursText.toIntOrNull() ?: 0
                            val m = minutesText.toIntOrNull() ?: 0
                            if (h != 0 || m != 0) onSave(h + m / 60.0)
                        }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Lets the user pick a different focus than the natural weekday one before starting today's
 * session — Arisyn's split is Push/Pull/Legs rather than Upper/Lower, so the options are those
 * three rather than a literal Upper/Lower toggle. Deliberately just one representative day per
 * focus (Seeds defines Push twice, Pull twice, with identical exercises both times) rather than
 * all five program days, since picking "Push" reads as one choice, not "which Push." */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchWorkoutSheet(currentFocus: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = remember { Seeds.days.distinctBy { it.focus } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        dragHandle = {
            Box(Modifier.padding(top = 14.dp, bottom = 6.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f)))
        }
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .glassElevated(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .padding(horizontal = 22.dp).padding(bottom = 32.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Switch workout", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { opt ->
                    val selected = opt.focus == currentFocus
                    Row(
                        Modifier.fillMaxWidth()
                            .let { if (selected) it.background(GoldAccent.copy(alpha = .14f), CardShape).border(1.dp, GoldAccent.copy(alpha = .5f), CardShape) else it.glassEmbedded() }
                            .pressable { onSelect(opt.id) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.focus, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(opt.cue, color = Secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected) Icon(Icons.Default.Check, null, tint = GoldAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Today's structured run — same hero-card treatment as a lifting day, in a distinct (blue,
 * matching the app's existing "utility/informational" accent) tint so it reads as its own thing
 * alongside or instead of the lifting hero, not a lifting day pretending to be a run. */
@Composable
private fun RacePlanCard(vm: TrainingViewModel, type: RaceSessionType, template: RacePlanWeekEntity) {
    val today = remember { LocalDate.now().toString() }
    val loggedSession by vm.racePlanSessionForDate(today).collectAsState(initial = null)
    var showResultDialog by remember { mutableStateOf(false) }
    val typeLabel = when (type) { RaceSessionType.TEMPO -> "Tempo run"; RaceSessionType.INTERVAL -> "Interval run"; RaceSessionType.LONG_RUN -> "Long run" }
    val typeDesc = when (type) {
        RaceSessionType.TEMPO -> "Sustained effort, top of Zone 3."
        RaceSessionType.INTERVAL -> "Fast reps near 5K pace, easy jog to recover between."
        RaceSessionType.LONG_RUN -> "Steady Zone 2 effort. No lifting fatigue today."
    }

    Box(Modifier.fillMaxWidth().glassElevated(HeroShape)) {
        Box(Modifier.matchParentSize().clip(HeroShape).background(Brush.radialGradient(listOf(SkyBlue.copy(alpha = .16f), Color.Transparent))))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("5K PLAN · WEEK ${template.weekNumber}", color = SkyBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(typeLabel, style = MaterialTheme.typography.displayLarge, color = Color.White)
            Text(typeDesc, color = Secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                template.targetDistanceKm?.let { Text(String.format(Locale.getDefault(), "%.1f km", it), color = SkyBlue, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                template.targetPacePerKm?.let { Text("$it /km target", color = SkyBlue, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                template.targetDurationMin?.let { Text("~${it.toInt()} min", color = SkyBlue, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(12.dp))
            val logged = loggedSession
            if (logged?.completed == true) {
                Row(Modifier.fillMaxWidth().glassEmbedded(RoundedCornerShape(16.dp)).pressable { showResultDialog = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = Confirm, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    val parts = listOfNotNull(
                        logged.actualDistanceKm?.let { String.format(Locale.getDefault(), "%.1f km", it) },
                        logged.actualPacePerKm?.let { "${it}/km" }
                    )
                    Text(if (parts.isEmpty()) "Logged" else parts.joinToString(" · "), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(SkyBlue.copy(alpha = .22f)).border(1.dp, SkyBlue.copy(alpha = .5f), RoundedCornerShape(22.dp))
                        .pressable { showResultDialog = true },
                    contentAlignment = Alignment.Center
                ) { Text("Log result", color = SkyBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
        }
    }
    if (showResultDialog) {
        RaceResultDialog(
            template = template, current = loggedSession,
            onSave = { distanceKm, durationMin ->
                val pace = if (distanceKm != null && durationMin != null) Pace.secondsPerKmFromDistanceDuration(distanceKm, durationMin)?.let { Pace.formatFromSeconds(it) } else null
                vm.logRacePlanSession(template.id, today, pace, distanceKm, durationMin, true)
                showResultDialog = false
            },
            onDismiss = { showResultDialog = false }
        )
    }
}

@Composable
private fun RaceResultDialog(template: RacePlanWeekEntity, current: com.training.app.data.local.RacePlanSessionEntity?, onSave: (Double?, Double?) -> Unit, onDismiss: () -> Unit) {
    var distanceText by remember { mutableStateOf(current?.actualDistanceKm?.toString() ?: template.targetDistanceKm?.toString().orEmpty()) }
    var durationText by remember { mutableStateOf(current?.actualDurationMin?.toString() ?: template.targetDurationMin?.toString().orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Log your run", style = MaterialTheme.typography.titleLarge, color = Color.White)
            OutlinedTextField(
                value = distanceText, onValueChange = { distanceText = it },
                label = { Text("Distance (km)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = durationText, onValueChange = { durationText = it },
                label = { Text("Duration (min)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cancel", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PrimaryGradient)
                        .pressable { onSave(distanceText.toDoubleOrNull(), durationText.toDoubleOrNull()) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Only shown when Health Connect doesn't have enough recent run history to derive a baseline
 * from — asks for the one number (comfortable easy pace) the whole 12-week plan scales off of. */
@Composable
private fun RaceBaselineDialog(onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var paceText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Set your easy pace", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Not enough recent run history in Health Connect to set realistic 5K plan targets. What's your comfortable easy-run pace per km?",
                color = Secondary, style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = paceText, onValueChange = { paceText = it },
                label = { Text("e.g. 6:30") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Not now", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PrimaryGradient)
                        .pressable { Pace.parseToSeconds(paceText)?.let { onSave(it) } }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
