package com.training.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

fun pluralize(n: Int, singular: String): String = if (n == 1) singular else "${singular}s"

/** Animates a stat from 0 up to its real value once, on first composition — gives the user a
 * beat to watch their number "resolve" instead of it just appearing. Re-fires only if the target
 * itself changes (e.g. a different session), not on every recomposition. */
@Composable
fun rememberCountUpInt(target: Int, durationMillis: Int = 900): Int {
    val anim = remember(target) { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(durationMillis, easing = FastOutSlowInEasing)) }
    return anim.value.toInt()
}

@Composable
fun ExerciseImage(url: String?, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = CardShape) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(Card),
        error = ColorPainter(Card),
        modifier = modifier.clip(shape).background(Card)
    )
}

@Composable
fun StatBlock(value: String, label: String) = Column {
    // Explicit white: MaterialTheme.typography styles carry no color of their own, so this was
    // falling back to the theme's (near-black) default content color against the dark background.
    Text(value, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
    Text(label, color = Secondary, style = MaterialTheme.typography.bodySmall)
}

private fun effortLabel(n: Int): String = when (n) { 1 -> "Very light"; 2 -> "Easy"; 3 -> "Good"; 4 -> "Hard"; 5 -> "Very hard"; else -> "" }

/** Apple-style 5-dot effort scale — no emoji, per the app-wide rule. Unfilled dots get a
 * visible outline rather than a flat dark fill, which read as "invisible" (not "empty")
 * against the dark ambient background. Filled dots grow slightly so a selection reads as
 * tactile, and a label spells out what the number means rather than leaving it to guesswork. */
@Composable
fun DotRating(value: Int?, editable: Boolean, onSelect: ((Int) -> Unit)? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..5).forEach { n ->
                val filled = (value ?: 0) >= n
                val scale by animateFloatAsState(if (filled) 1.15f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "dotScale")
                Box(
                    Modifier.size(16.dp).scale(scale).clip(RoundedCornerShape(50))
                        .background(if (filled) GoldAccent else Color.White.copy(alpha = .08f))
                        .let { if (!filled) it.border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(50)) else it }
                        .clickable(enabled = editable) { Haptics.tick(context); onSelect?.invoke(n) }
                )
            }
        }
        val current = value ?: 0
        if (current > 0) {
            Spacer(Modifier.height(6.dp))
            AnimatedContent(current, label = "effortLabel", transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 }) togetherWith fadeOut(tween(120)) }) { n ->
                Text(effortLabel(n), color = Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Small dot progress row, e.g. "Week 1 of 12". */
@Composable
fun DotProgress(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..total).forEach { n ->
            Box(Modifier.size(if (n == current) 8.dp else 6.dp).clip(RoundedCornerShape(50)).background(if (n == current) GoldAccent else Color.White.copy(alpha = .2f)))
        }
    }
}

/** Mon–Sun day chips with a checkmark on completed training days — shared by Today and Progress. */
@Composable
fun WeekStrip(weekDates: List<LocalDate>, doneDates: Set<LocalDate>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        weekDates.forEach { d ->
            val done = doneDates.contains(d)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(if (done) GoldAccent else Card), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Default.Check, null, tint = Bg, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(d.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()).take(3), color = Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Slim single-row variant of WeekStrip — a day letter over each dot, for tight layouts.
 * Four states, not two: completed (filled gold + check), today-not-yet-done (outlined), a future
 * day (faint, still ahead of you), and a missed day (smaller and dimmer — it already happened and
 * didn't get filled, so it recedes rather than looking the same as a day still to come). */
@Composable
fun WeekStripCompact(weekDates: List<LocalDate>, doneDates: Set<LocalDate>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        weekDates.forEach { d ->
            val done = doneDates.contains(d)
            var wasDone by remember(d) { mutableStateOf(done) }
            LaunchedEffect(done) { if (done && !wasDone) Haptics.tick(context); wasDone = done }
            val isToday = d == today
            val missed = d.isBefore(today) && !done
            val targetSize = if (done) 18.dp else if (missed) 8.dp else 14.dp
            val size by animateDpAsState(targetSize, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "weekDotSize")
            // A day letter over each dot — the dots alone didn't say which day was which.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    WEEK_DAY_LETTERS[d.dayOfWeek.value - 1],
                    color = if (isToday) GoldAccent else TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                )
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(size).clip(RoundedCornerShape(50))
                            .background(if (done) GoldAccent else Color.White.copy(alpha = if (missed) .12f else .16f))
                            .let { if (!done && isToday) it.border(1.dp, GoldAccent, RoundedCornerShape(50)) else it },
                        contentAlignment = Alignment.Center
                    ) { if (done) Icon(Icons.Default.Check, null, tint = Bg, modifier = Modifier.size(11.dp)) }
                }
            }
        }
    }
}

private val WEEK_DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Month grid with training days marked, plus a prev/next month selector at top right.
 * halfDates (steps recorded but no logged workout/run) render as a half-filled circle — a day
 * the watch saw the user moving, distinct from a day they actually trained on. A date in both
 * sets renders as fully done; doneDates always wins. */
@Composable
fun MonthCalendar(month: YearMonth, doneDates: Set<LocalDate>, onPrevMonth: () -> Unit, onNextMonth: () -> Unit, onDayClick: (LocalDate) -> Unit = {}, halfDates: Set<LocalDate> = emptySet(), maxRows: Int? = null, modifier: Modifier = Modifier) {
    val today = LocalDate.now()

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.ChevronLeft, null, tint = Secondary) }
            IconButton(onClick = onNextMonth, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.ChevronRight, null, tint = Secondary) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d -> Text(d, color = Secondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        // A quiet crossfade+slide rather than the grid just snapping to the new month — the same
        // spring-flavored motion language used everywhere else in the app.
        AnimatedContent(
            targetState = month,
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 6 }) },
            label = "calendarMonth"
        ) { m ->
            val daysInMonth = m.lengthOfMonth()
            val leadingBlanks = m.atDay(1).dayOfWeek.value - 1
            val rows = (leadingBlanks + daysInMonth + 6) / 7
            // Composing only the requested row count — rather than rendering every row and
            // clipping to an estimated pixel height — is what actually guarantees rows 3+ never
            // bleed through: a height/clip approach depends on correctly predicting each cell's
            // rendered size, and on-device testing showed that estimate drifting enough to let a
            // later row's text show through, overlapping row 2. Not composing the row at all has
            // no such failure mode.
            val visibleRows = maxRows?.coerceAtMost(rows) ?: rows
            Column {
                for (r in 0 until visibleRows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val dayNum = r * 7 + c - leadingBlanks + 1
                            Box(
                                Modifier.weight(1f).aspectRatio(1f)
                                    .let { if (dayNum in 1..daysInMonth) it.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDayClick(m.atDay(dayNum)) } else it },
                                contentAlignment = Alignment.Center
                            ) {
                                if (dayNum in 1..daysInMonth) {
                                    val date = m.atDay(dayNum)
                                    val done = doneDates.contains(date)
                                    val half = !done && halfDates.contains(date)
                                    val isToday = date == today
                                    // The point of this grid is "here's your training consistency," not
                                    // "here's a calendar" — so an ordinary day carries almost no visual
                                    // weight at all, and a completed one is the only thing that pops.
                                    // A half-filled circle for a day with only step activity (no logged
                                    // workout or run) — a real but partial signal, not a full day.
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(14.dp))
                                            .background(
                                                when {
                                                    done -> Brush.linearGradient(listOf(GoldAccent, GoldAccent))
                                                    half -> Brush.horizontalGradient(0f to GoldAccent, 0.5f to GoldAccent, 0.5f to Color.Transparent, 1f to Color.Transparent)
                                                    else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                                                }
                                            )
                                            .let { if (isToday && !done) it.border(1.dp, SkyBlue.copy(alpha = .6f), RoundedCornerShape(14.dp)) else it },
                                        contentAlignment = Alignment.Center
                                    ) { Text("$dayNum", color = if (done) Bg else if (isToday) SkyBlue else Color.White.copy(alpha = .30f), fontSize = 12.sp, fontWeight = if (done) FontWeight.Bold else FontWeight.Normal) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live "MM:SS" (or "H:MM:SS") elapsed-time pill. Elapsed = now - startedAt - pausedDurationMs,
 * minus the still-open gap while pausedAtMillis is set. Freezes (stops ticking) while paused.
 */
@Composable
fun ElapsedTimePill(startedAtMillis: Long, pausedAtMillis: Long? = null, pausedDurationMs: Long = 0, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis, pausedAtMillis) {
        if (pausedAtMillis == null) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    }
    val effectiveNow = pausedAtMillis ?: now
    val elapsedSec = ((effectiveNow - startedAtMillis - pausedDurationMs) / 1000).coerceAtLeast(0)
    val h = elapsedSec / 3600; val m = (elapsedSec % 3600) / 60; val s = elapsedSec % 60
    val label = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Card).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Schedule, null, tint = SkyBlue, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (pausedAtMillis != null) Secondary else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
