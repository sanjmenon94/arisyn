package com.training.app.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.training.app.data.Badges
import com.training.app.data.Milestones
import com.training.app.data.Seeds
import com.training.app.data.local.SessionEntity
import com.training.app.data.local.SessionSummaryRow
import com.training.app.data.local.SetLogDetail
import com.training.app.data.local.UserGoalsEntity
import com.training.app.health.HealthConnectManager
import com.training.app.ui.BottomDockClearance
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.components.DotRating
import com.training.app.ui.components.ExerciseImage
import com.training.app.ui.components.MonthCalendar
import com.training.app.ui.components.StatBlock
import com.training.app.ui.components.pluralize
import com.training.app.ui.components.rememberCountUpInt
import com.training.app.ui.theme.*
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Session-level startedAt/completedAt are the source of truth; the logged-set-timestamp
 * bounds (loggedStartedAt/loggedEndedAt) are only a fallback for sessions predating that field. */
private fun sessionDurationMinutes(session: SessionEntity, fallbackStart: Long?, fallbackEnd: Long?): Int {
    val start = session.startedAt ?: fallbackStart
    val end = session.completedAt ?: fallbackEnd
    return if (start != null && end != null && end > start) ((end - start) / 60000).toInt().coerceAtLeast(1) else 0
}

private fun formatDate(iso: String): String = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) }.getOrDefault(iso)
private fun formatFullDate(iso: String): String = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())) }.getOrDefault(iso)

/** One Push/Pull/Legs card's worth of data — sparkline is chronological (oldest first, so the
 * bars read left-to-right like a timeline), trendPct compares the most recent session of this
 * focus against the one before it, and volumeByMuscle is the tap-through breakdown by specific
 * muscle group (chest/shoulders/triceps under Push, etc.), keyed by ExerciseEntity.muscleGroup. */
private data class MuscleFocusStats(val focus: String, val totalVolume: Double, val sparkline: List<Double>, val trendPct: Int?, val volumeByMuscle: Map<String, Double>)

/** Push/Pull/Legs is Arisyn's actual training split (see Seeds.days) — every logged session
 * already carries a dayFocus of exactly one of those three, so this groups by that existing field
 * rather than inventing a separate muscle-group taxonomy. muscleGroupById comes from Seeds.exercises
 * directly (a compile-time list) rather than a DB join, since that mapping never changes at runtime. */
private fun computeMuscleFocusStats(summaries: List<SessionSummaryRow>, allDetails: List<SetLogDetail>): List<MuscleFocusStats> {
    val muscleGroupById = Seeds.exercises.associate { it.id to it.muscleGroup }
    val sessionFocusById = summaries.associate { it.session.id to (it.dayFocus ?: "") }
    return listOf("Push", "Pull", "Legs").map { focus ->
        val focusSessions = summaries.filter { it.dayFocus == focus }.sortedBy { it.session.sessionDate }
        val totalVolume = focusSessions.sumOf { it.volume }
        val sparkline = focusSessions.takeLast(6).map { it.volume }
        val trendPct = if (focusSessions.size >= 2) {
            val prev = focusSessions[focusSessions.size - 2].volume
            val last = focusSessions.last().volume
            if (prev > 0) (((last - prev) / prev) * 100).toInt() else null
        } else null
        val volumeByMuscle = allDetails.filter { sessionFocusById[it.log.sessionId] == focus }
            .groupBy { muscleGroupById[it.log.exerciseId] ?: "other" }
            .mapValues { (_, logs) -> logs.sumOf { (it.log.weightKg ?: 0.0) * it.log.reps } }
        MuscleFocusStats(focus, totalVolume, sparkline, trendPct, volumeByMuscle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(vm: TrainingViewModel) {
    val summaries by vm.sessionSummaries.collectAsState()
    val allDetails by vm.allSetDetails.collectAsState()
    var openSession by remember { mutableStateOf<SessionSummaryRow?>(null) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var selectedFocus by remember { mutableStateOf<MuscleFocusStats?>(null) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }

    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val loggedDates = summaries.mapNotNull { runCatching { LocalDate.parse(it.session.sessionDate) }.getOrNull() }.toSet()

    // Health Connect doesn't push updates — the app only knows what it last asked for. This
    // re-asks once when the screen first opens, and again on a manual pull-to-refresh; there's no
    // background sync, so activity added on the watch after that won't appear until one of those
    // two moments happens again.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Full days (a logged workout or run) vs. half days (steps only, no training) — 90 days back
    // is plenty for the calendar/streak purpose without pretending to import real history; deep
    // backfill was explicitly descoped.
    var hcFullDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var hcHalfDates by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun refreshHealthConnect() {
        if (HealthConnectManager.isAvailable(context) && HealthConnectManager.hasAllPermissions(context)) {
            val rangeStart = LocalDate.now().minusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val rangeEnd = Instant.now()
            hcFullDates = HealthConnectManager.workoutDates(context, rangeStart, rangeEnd)
            hcHalfDates = HealthConnectManager.stepsOnlyDates(context, rangeStart, rangeEnd, excluding = loggedDates + hcFullDates)
        } else {
            hcFullDates = emptySet()
            hcHalfDates = emptySet()
        }
    }
    LaunchedEffect(Unit) { refreshHealthConnect() }

    val allDates = loggedDates + hcFullDates
    // Half days are a real signal but not a training day — they don't count toward the streak.
    val currentStreak = Badges.currentStreak(summaries.map { it.session.sessionDate }.toSet() + hcFullDates.map { it.toString() })
    val milestones = remember(summaries, allDetails) { Milestones.compute(summaries, allDetails) }
    // Push/Pull/Legs is Arisyn's actual split (see Seeds.days), so grouping by session.dayFocus
    // reuses that existing categorization rather than inventing a new one.
    val muscleFocusStats = remember(summaries, allDetails) { computeMuscleFocusStats(summaries, allDetails) }
    val goals by vm.goals.collectAsState()
    val currentRaceWeekNumber by vm.currentRaceWeekNumber.collectAsState()
    val racePlanWeekRows by (currentRaceWeekNumber?.let { vm.racePlanWeek(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    // "Past activity data" pulls in every full-workout day Health Connect knows about, not just
    // the ones Arisyn itself logged — a run the watch caught on its own gets its own row here too,
    // fetched once per day (not per recomposition) since each read is its own Health Connect call.
    var hcOnlyDetails by remember { mutableStateOf<Map<LocalDate, HealthConnectManager.DailyActivity?>>(emptyMap()) }
    LaunchedEffect(hcFullDates, loggedDates) {
        val hcOnlyDates = hcFullDates - loggedDates
        hcOnlyDetails = hcOnlyDates.associateWith { HealthConnectManager.dailyActivity(context, it) }
    }
    val pastActivity = remember(summaries, hcOnlyDetails) {
        val fromSessions = summaries.mapNotNull { s -> runCatching { LocalDate.parse(s.session.sessionDate) }.getOrNull()?.let { PastActivityEntry(it, s, null) } }
        val fromHc = hcOnlyDetails.map { (date, daily) -> PastActivityEntry(date, null, daily) }
        (fromSessions + fromHc).sortedByDescending { it.date }
    }

    // The primary message here should be "I am improving," not a pile of stats — so week-over-
    // week volume trend (when there's a prior week to compare against) leads over the generic
    // story line, with its own accent color and icon rather than blending in as plain metadata.
    val lastWeekMonday = monday.minusDays(7)
    val thisWeekVolume = summaries.filter { s -> runCatching { LocalDate.parse(s.session.sessionDate) }.getOrNull()?.let { !it.isBefore(monday) && !it.isAfter(monday.plusDays(6)) } == true }.sumOf { it.volume }
    val lastWeekVolume = summaries.filter { s -> runCatching { LocalDate.parse(s.session.sessionDate) }.getOrNull()?.let { !it.isBefore(lastWeekMonday) && it.isBefore(monday) } == true }.sumOf { it.volume }
    val trendPct = if (lastWeekVolume > 0) (((thisWeekVolume - lastWeekVolume) / lastWeekVolume) * 100).toInt() else null

    val storyLine = when {
        currentStreak >= 2 -> "You're building momentum."
        summaries.isEmpty() -> "Your story starts here."
        else -> "Every session adds up."
    }

    // No horizontal padding on the scroll column itself — each section applies its own, so the
    // achievements strip can run full-bleed. Inside a padded column it was clipped at the padding
    // edge, cutting a badge's label in half and reading as a layout bug rather than "there's more
    // to scroll."
    val sidePadding = Modifier.padding(horizontal = 20.dp)
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { scope.launch { isRefreshing = true; refreshHealthConnect(); isRefreshing = false } },
        modifier = Modifier.fillMaxSize()
    ) {
    AmbientBackground { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = BottomDockClearance), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(sidePadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Progress", style = MaterialTheme.typography.displayLarge, color = Color.White)
            if (trendPct != null && trendPct > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = GoldAccent, modifier = Modifier.size(16.dp))
                    Text("Up $trendPct% in volume this week. You're improving.", color = GoldAccent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(storyLine, color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // The push/hold/pull-back label in plain language, not the raw word — computed once at
        // the start of the week (see ensureWeekAdjustmentComputed), so this is stable all week.
        if (currentRaceWeekNumber != null && racePlanWeekRows.isNotEmpty()) {
            Column(sidePadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("5K PLAN · WEEK $currentRaceWeekNumber", color = SkyBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val adjustment = com.training.app.data.Adjustment.fromKey(racePlanWeekRows.first().adjustment)
                Row(Modifier.fillMaxWidth().glassEmbedded(RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (adjustment) {
                            com.training.app.data.Adjustment.PUSH -> Icons.AutoMirrored.Filled.TrendingUp
                            com.training.app.data.Adjustment.PULL_BACK -> Icons.AutoMirrored.Filled.TrendingDown
                            com.training.app.data.Adjustment.HOLD -> Icons.Default.Timer
                        },
                        null, tint = SkyBlue, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(com.training.app.data.RacePlan.adjustmentSummary(adjustment), color = Secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // padding *after* horizontalScroll, so it scrolls with the content — the first and last
        // badge line up with every other section at rest, and anything mid-scroll runs off the
        // real screen edge instead of a padding boundary.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            milestones.forEach { m ->
                // Locked achievements fade as one dormant unit rather than each layer (icon,
                // background, label) getting its own separately-tuned opacity — earned ones sit
                // at full strength so the contrast between "have" and "haven't" reads instantly.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).alpha(if (m.earned) 1f else 0.35f)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (m.earned) GoldAccent.copy(alpha = .18f) else Color.White.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                        Icon(m.icon, null, tint = if (m.earned) GoldAccent else Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(m.label, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }

        Column(sidePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Collapsed to 2 weeks by default — the current week plus the one before it is what's
            // actually relevant most of the time. Rows 3+ are simply never composed while
            // collapsed (not rendered-then-clipped), which is what actually guarantees they can't
            // bleed through — see MonthCalendar's maxRows param.
            var calendarExpanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().glassEmbedded()) {
                Box {
                    MonthCalendar(
                        month = displayedMonth, doneDates = allDates, halfDates = hcHalfDates,
                        onPrevMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                        onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                        onDayClick = { selectedDay = it },
                        maxRows = if (calendarExpanded) null else 2,
                        modifier = Modifier.padding(16.dp)
                    )
                    // A purely decorative soft fade along the bottom edge of the visible rows
                    // themselves (not exposing any part of a hidden row) — echoes "blurred" without
                    // depending on any height estimate.
                    if (!calendarExpanded) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp)
                                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to AmbientDeepNavy.copy(alpha = .5f)))
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.offset(y = (-14).dp).size(30.dp).clip(CircleShape).glassFloating(CircleShape)
                        .pressable { calendarExpanded = !calendarExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (calendarExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Secondary, modifier = Modifier.size(18.dp))
                }
            }
            // Says once, plainly, what the dots mean and that they're tappable — instead of a
            // legend, or making the reader guess from the color alone.
            Text(
                "Gold means a workout or run that day. Tap any date for the details.",
                color = TextTertiary, style = MaterialTheme.typography.bodySmall
            )
        }

        if (muscleFocusStats.any { it.totalVolume > 0 }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                muscleFocusStats.forEach { stats -> MuscleFocusCard(stats, onClick = { selectedFocus = stats }) }
            }
        }

        Column(sidePadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Past activity data", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
            if (pastActivity.isEmpty()) Text("Your runs and workouts will live here, safely, even offline.", color = Secondary)
            else pastActivity.forEachIndexed { i, entry ->
                PastActivityRow(
                    vm = vm, entry = entry, isLast = i == pastActivity.lastIndex,
                    onClickSession = { openSession = it }, onClickHcDay = { selectedDay = it }
                )
            }
        }
    } } }

    openSession?.let { s ->
        SessionDetailSheet(vm, s, summaries, allDetails, onDismiss = { openSession = null })
    }
    selectedDay?.let { day ->
        DayDetailSheet(
            vm = vm,
            date = day,
            arisynSession = summaries.find { it.session.sessionDate == day.toString() },
            goals = goals,
            onOpenArisynSession = { s -> selectedDay = null; openSession = s },
            onDismiss = { selectedDay = null }
        )
    }
    selectedFocus?.let { stats ->
        MuscleFocusSheet(stats, onDismiss = { selectedFocus = null })
    }
}

/** What the calendar's day tap opens — the watch-sourced picture for one day (workouts by type,
 * steps, sleep), plus a link into the existing session sheet if Arisyn itself has a logged
 * workout that day. Only ever reads live from Health Connect for the tapped date; there's no
 * historical import to show here for days before the integration existed. Sleep falls back to a
 * manually-logged value (vm.sleepFor) whenever Health Connect doesn't have one for that date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailSheet(
    vm: TrainingViewModel,
    date: LocalDate,
    arisynSession: SessionSummaryRow?,
    goals: UserGoalsEntity?,
    onOpenArisynSession: (SessionSummaryRow) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var daily by remember(date) { mutableStateOf<HealthConnectManager.DailyActivity?>(null) }
    var loading by remember(date) { mutableStateOf(true) }
    var hcAvailable by remember(date) { mutableStateOf(true) }
    LaunchedEffect(date) {
        loading = true
        hcAvailable = HealthConnectManager.isAvailable(context) && HealthConnectManager.hasAllPermissions(context)
        daily = if (hcAvailable) HealthConnectManager.dailyActivity(context, date) else null
        loading = false
    }
    val manualSleep by vm.sleepFor(date.toString()).collectAsState(initial = null)
    val d = daily
    val sleepHours = d?.sleepHours ?: manualSleep?.hours
    val hasAnyGoal = goals != null && (goals.dailyCalories != null || goals.dailyDistanceKm != null || goals.dailySteps != null)

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 32.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())), style = MaterialTheme.typography.headlineLarge, color = Color.White)

            arisynSession?.let { s ->
                Row(
                    Modifier.fillMaxWidth().glassEmbedded().pressable { onOpenArisynSession(s) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.dayFocus ?: "Workout", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("${s.exerciseCount} ${pluralize(s.exerciseCount, "exercise")} · ${s.volume.toInt()} kg", color = Secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Secondary)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // The icon alone says "this came off the watch" — spelling it out in a label
                // repeats what the badges/rings strips elsewhere never bother to say either.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Watch, null, tint = Secondary, modifier = Modifier.size(16.dp))
                    Text("ACTIVITY", color = Secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                when {
                    !hcAvailable -> Text("Health Connect isn't connected.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    loading -> Text("Loading...", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    d == null -> Text("Couldn't read Health Connect right now.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    d.workouts.isEmpty() && d.steps == 0L && d.distanceKm == 0.0 && d.calories == 0L && sleepHours == null ->
                        Text("Nothing recorded for this day.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    d.workouts.isEmpty() -> Text("No run or workout logged — just background activity below.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        // Same icon-in-a-circle language as the achievements strip and the Weekly
                        // Goals rings, rather than a plain text line. Running gets the full spread
                        // (time/distance/steps/calories) since a run's pace and distance both
                        // matter; a strength session only ever shows time and calories — its own
                        // step count is usually just walking between the rack and the bench, not a
                        // meaningful number for that workout.
                        d.workouts.forEach { w ->
                            val isRunning = w.typeLabel == "Run"
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(w.typeLabel, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    ActivityStatChip(Icons.Default.Timer, "${w.durationMinutes}", "min", GoldAccent)
                                    if (isRunning) {
                                        w.distanceKm?.let { ActivityStatChip(Icons.Default.Route, String.format(Locale.getDefault(), "%.2f", it), "km", SkyBlue) }
                                        w.steps?.let { ActivityStatChip(Icons.Default.DirectionsWalk, String.format(Locale.getDefault(), "%,d", it), "steps", TealAccent) }
                                    }
                                    w.calories?.let { ActivityStatChip(Icons.Default.LocalFireDepartment, String.format(Locale.getDefault(), "%,d", it), "kcal", GoldAccent) }
                                }
                            }
                        }
                    }
                }
            }

            // Whole-day totals vs. the daily targets set in Profile — the same ring language as
            // Today's Weekly Goals, just scoped to one day instead of the week. Only shows up for
            // whichever metrics actually have a target, and only once Health Connect has actually
            // answered for this date.
            if (d != null && hasAnyGoal) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("VS DAILY TARGET", color = Secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        goals?.dailyCalories?.let { target -> DayGoalRing("Calories", GoldAccent, d.calories.toDouble(), target.toDouble(), "kcal") }
                        goals?.dailyDistanceKm?.let { target -> DayGoalRing("Distance", SkyBlue, d.distanceKm, target, "km") }
                        goals?.dailySteps?.let { target -> DayGoalRing("Steps", TealAccent, d.steps.toDouble(), target.toDouble(), "") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ActivityStatChip(Icons.Default.Bedtime, sleepHours?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "--", "sleep hrs", SkyBlue)
            }
        }
    }
}

/** A day's total for one metric against its daily target — the same arc-drawing approach as
 * Today's WeeklyGoalRing, just without the tap-to-expand or completion haptic, since a past day's
 * numbers are read-only history, not something still in progress to celebrate reaching. */
@Composable
private fun DayGoalRing(label: String, color: Color, current: Double, target: Double, unitSuffix: String) {
    val progress = if (target > 0) (current / target).toFloat() else 0f
    val completed = progress >= 1f
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "dayGoalRing")
    val format: (Double) -> String = if (unitSuffix == "km")
        { v -> String.format(Locale.getDefault(), "%.1f", v) }
    else
        { v -> String.format(Locale.getDefault(), "%,d", v.roundToInt()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
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
        Text("${format(current)} / ${format(target)}${if (unitSuffix.isNotEmpty()) " $unitSuffix" else ""}", color = TextTertiary, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

/** One Push/Pull/Legs card — total volume, an up/down trend badge against the previous session of
 * the same focus, and a mini bar sparkline of recent sessions. One consistent accent color across
 * all three cards (rather than a color per muscle group) — the trend and the number are the point,
 * not a color-coded taxonomy. */
@Composable
private fun MuscleFocusCard(stats: MuscleFocusStats, onClick: () -> Unit) {
    Column(
        Modifier.width(150.dp).glassEmbedded(CardShape).pressable(scaleDown = 0.98f) { onClick() }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stats.focus, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${stats.totalVolume.toInt()}", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("kg", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
            stats.trendPct?.let { pct ->
                val up = pct >= 0
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(GoldAccent.copy(alpha = .16f))) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            null, tint = GoldAccent, modifier = Modifier.size(12.dp)
                        )
                        Text("${abs(pct)}%", color = GoldAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // A tiny bar sparkline — recent focus-sessions' volumes, oldest to newest, normalized to
        // the tallest bar in this card only (each card's own scale, not a shared one across cards).
        val maxVal = (stats.sparkline.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            stats.sparkline.forEach { v ->
                Box(
                    Modifier.weight(1f).fillMaxHeight((v / maxVal).toFloat().coerceIn(0.08f, 1f))
                        .clip(RoundedCornerShape(2.dp)).background(GoldAccent.copy(alpha = .55f))
                )
            }
            if (stats.sparkline.isEmpty()) Text("No sessions yet", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The tap-through from a MuscleFocusCard — which specific muscle groups (not just the broad
 * Push/Pull/Legs bucket) the logged sets actually trained, ranked by volume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuscleFocusSheet(stats: MuscleFocusStats, onDismiss: () -> Unit) {
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${stats.focus} muscles", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text("${stats.totalVolume.toInt()} kg total volume", color = Secondary, style = MaterialTheme.typography.bodyMedium)
            }
            val ranked = stats.volumeByMuscle.entries.sortedByDescending { it.value }
            if (ranked.isEmpty()) {
                Text("Log a ${stats.focus.lowercase()} session to see which muscles you're working.", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
            } else {
                val maxVal = ranked.first().value.coerceAtLeast(1.0)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ranked.forEach { (muscle, volume) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(muscle.replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                Text("${volume.toInt()} kg", color = Secondary, style = MaterialTheme.typography.bodyMedium)
                            }
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .08f))) {
                                Box(Modifier.fillMaxWidth((volume / maxVal).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(GoldAccent))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An icon-in-a-circle badge, matching the achievements strip's visual language — used here so a
 * day's watch-sourced stats read as part of the same design system as the badges and Weekly Goals
 * rings, not as a plain list of numbers. */
@Composable
private fun ActivityStatChip(icon: ImageVector, value: String, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(label, color = TextTertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailSheet(vm: TrainingViewModel, s: SessionSummaryRow, summaries: List<SessionSummaryRow>, allDetails: List<SetLogDetail>, onDismiss: () -> Unit) {
    val setDetails by vm.sessionDetails(s.session.id).collectAsState(initial = emptyList())
    val streak = Badges.streak(s.session.sessionDate, summaries.map { it.session.sessionDate }.toSet())
    val badges = listOfNotNull(Badges.streakBadge(streak)) + Badges.personalRecordBadges(s.session.id, allDetails)
    val duration = sessionDurationMinutes(s.session, s.loggedStartedAt, s.loggedEndedAt)

    // The one place historical data gets to say something: did this beat the last session of
    // the same day type?
    val sameFocus = summaries.filter { it.dayFocus == s.dayFocus }.sortedBy { it.session.sessionDate }
    val prevSameFocus = sameFocus.indexOfFirst { it.session.id == s.session.id }.let { idx -> if (idx > 0) sameFocus[idx - 1] else null }
    val prevVolume = prevSameFocus?.volume
    val improved = prevVolume != null && s.volume > prevVolume
    val percentGain = if (improved && prevVolume!! > 0) (((s.volume - prevVolume) / prevVolume) * 100).toInt() else null

    // Darker than the default scrim — the sheet should feel like it's emerging from the app, with
    // everything behind it visibly receding, not like a popover casually covering the content.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        dragHandle = {
            var grabberIn by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { grabberIn = true }
            val grabberWidth by animateDpAsState(if (grabberIn) 64.dp else 32.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "grabberWidth")
            Box(Modifier.padding(top = 14.dp, bottom = 6.dp).size(width = grabberWidth, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .3f)))
        }
    ) {
        Column(
            // A modal sheet can sit on top of arbitrary bright content behind its scrim, unlike
            // the main screens' glass() cards which only ever sit on the app's own dark ambient
            // background — so this needs a solid backing, not just the usual thin frosted fill.
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .glassElevated(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                // The badge and per-set chips can push this past screen height for a longer
                // session — scrollable so the last section (Effort) is always reachable rather
                // than silently clipped once the sheet hits its own max expansion.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 32.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.dayFocus ?: "Workout", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(formatFullDate(s.session.sessionDate), color = Secondary, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatBlock("${rememberCountUpInt(s.exerciseCount)}", pluralize(s.exerciseCount, "exercise")); StatBlock("${rememberCountUpInt(s.volume.toInt())}kg", "volume"); StatBlock("${rememberCountUpInt(duration)}min", "duration")
            }

            if (badges.isNotEmpty() || improved) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (badges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        badges.forEach { b -> Box(Modifier.clip(RoundedCornerShape(14.dp)).background(GoldAccent.copy(alpha = .16f))) { Text(b, color = GoldAccent, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold) } }
                    }
                    // A number you can feel, not a sentence you have to read — the whole point of
                    // "you beat your last day" is that it should register instantly. A comparison
                    // bar makes the gap itself visible, not just the two numbers next to it.
                    if (improved) {
                        val maxVol = maxOf(s.volume, prevVolume!!).coerceAtLeast(1.0)
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(GoldAccent.copy(alpha = .22f), GoldAccent.copy(alpha = .07f))))) {
                            Column(Modifier.padding(16.dp)) {
                                Text((s.dayFocus ?: "Workout").uppercase(), color = GoldAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(percentGain?.let { "+$it%" } ?: "New volume best", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                Text("Volume vs last session", color = Secondary, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${prevVolume.toInt()} kg", color = Secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .08f))) {
                                            Box(Modifier.fillMaxWidth((prevVolume / maxVol).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .35f)))
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${rememberCountUpInt(s.volume.toInt())} kg", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .08f))) {
                                            Box(Modifier.fillMaxWidth((s.volume / maxVol).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(GoldAccent))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                setDetails.groupBy { Triple(it.log.exerciseId, it.exerciseName, it.exerciseImageUrl) }.forEach { (key, logs) ->
                    val (_, name, imageUrl) = key
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExerciseImage(imageUrl, modifier = Modifier.size(44.dp), shape = CircleShape)
                            Spacer(Modifier.width(12.dp))
                            // Wraps rather than truncating — these names run long ("Triceps Pushdown /
                            // DB Overhead Extension") and an ellipsis hides which exercise it was.
                            Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            logs.sortedBy { it.log.setNumber }.forEach { d ->
                                Box(
                                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .06f))
                                        .let { if (d.log.isExtra) it.border(1.dp, GoldAccent.copy(alpha = .6f), RoundedCornerShape(10.dp)) else it }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        "${d.log.weightKg?.let { w -> "${w.toInt()}kg" } ?: "BW"} x ${d.log.reps}",
                                        color = if (d.log.isExtra) GoldAccent else Color.White, style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (s.session.cardioDone) {
                Row(Modifier.fillMaxWidth().clip(CardShape).background(Color.White.copy(alpha = .04f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsRun, null, tint = SkyBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Cardio logged this day", color = Secondary)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Effort", color = Secondary, style = MaterialTheme.typography.bodyMedium)
                DotRating(s.session.burnRating, editable = false)
            }
        }
    }
}

/** One row of "Past activity data" — either a day Arisyn itself logged (session non-null) or a
 * day Health Connect caught on its own with no matching Arisyn session (hcOnly non-null). Exactly
 * one of the two is ever set. */
private data class PastActivityEntry(val date: LocalDate, val session: SessionSummaryRow?, val hcOnly: HealthConnectManager.DailyActivity?)

/** A timeline entry, not a card in a list — a dot and a connecting line down to the next one, so
 * "past activity data" reads as a continuous history rather than a stack of separate objects.
 * No card chrome around the entry itself either: the connecting line, typography, thumbnails
 * and spacing carry it, sitting straight on the ambient background like the rest of the page. */
@Composable
private fun PastActivityRow(vm: TrainingViewModel, entry: PastActivityEntry, isLast: Boolean, onClickSession: (SessionSummaryRow) -> Unit, onClickHcDay: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight().width(20.dp)) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(GoldAccent))
            if (!isLast) Box(Modifier.width(1.dp).weight(1f).background(Color.White.copy(alpha = .14f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatFullDate(entry.date.toString()), color = Secondary, style = MaterialTheme.typography.bodyMedium)
            val s = entry.session
            if (s != null) {
                val setDetails by vm.sessionDetails(s.session.id).collectAsState(initial = emptyList())
                val duration = sessionDurationMinutes(s.session, s.loggedStartedAt, s.loggedEndedAt)
                val thumbnails = setDetails.distinctBy { it.log.exerciseId }.mapNotNull { it.exerciseImageUrl }.take(3)
                Row(
                    Modifier.fillMaxWidth().pressable(scaleDown = 0.98f) { onClickSession(s) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (thumbnails.isNotEmpty()) {
                        Row {
                            thumbnails.forEachIndexed { i, url ->
                                ExerciseImage(url, modifier = Modifier.size(40.dp), shape = CircleShape)
                                if (i != thumbnails.lastIndex) Spacer(Modifier.width(4.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.dayFocus ?: "Workout", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.exerciseCount} ${pluralize(s.exerciseCount, "exercise")} · ${s.volume.toInt()} kg · $duration min", color = Secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                // A day Health Connect caught without a matching Arisyn session — a run or workout
                // logged straight from the watch. Opens the same day-detail sheet the calendar
                // itself opens, rather than a separate view, since that sheet already knows how to
                // show exactly this.
                val d = entry.hcOnly
                Row(
                    Modifier.fillMaxWidth().glassEmbedded().pressable(scaleDown = 0.98f) { onClickHcDay(entry.date) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Watch, null, tint = Secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d?.workouts?.joinToString(" + ") { it.typeLabel } ?: "Workout",
                            color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val details = listOfNotNull(
                            d?.workouts?.sumOf { it.durationMinutes }?.takeIf { it > 0 }?.let { "$it min" },
                            d?.distanceKm?.takeIf { it > 0.0 }?.let { String.format(Locale.getDefault(), "%.2f km", it) },
                            d?.calories?.takeIf { it > 0 }?.let { "$it kcal" }
                        )
                        Text(details.joinToString(" · ").ifEmpty { "From Health Connect" }, color = Secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
