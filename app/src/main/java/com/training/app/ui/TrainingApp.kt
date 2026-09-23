package com.training.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.training.app.ui.screens.*
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel

// Order matters — it's also the left-to-right order of the dock, and what a horizontal swipe
// steps through.
private val topLevelOrder = listOf("today", "progress", "profile")
private val topLevelRoutes = topLevelOrder.toSet()

// Height a top-level screen's scrollable content should reserve at the bottom so its last
// item never sits behind the floating dock (dock height + its floating gap + the system
// navigation bar inset the dock now sits above + a little air).
val BottomDockClearance = 180.dp

@Composable
fun TrainingApp(vm: TrainingViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    // Shared by the dock and by swipe, so both land on the same back-stack state.
    fun goToTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // No Scaffold here deliberately: Scaffold's bottomBar slot insets content above it with an
    // opaque bar. The dock needs to float on top of the screen's own full-bleed ambient
    // background instead, with the gradient visible around and behind it.
    Box(
        Modifier.fillMaxSize()
            // Horizontal swipe steps through the dock's tabs: swiping right moves forward
            // (Today -> Progress -> Profile), swiping left moves back. Sits on the parent, so any
            // child that actually wants a horizontal drag — the achievements strip, the calendar —
            // consumes it first and wins.
            .pointerInput(currentRoute) {
                if (currentRoute !in topLevelRoutes) return@pointerInput
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragCancel = { dragged = 0f },
                    onDragEnd = {
                        val from = topLevelOrder.indexOf(currentRoute)
                        if (from >= 0 && abs(dragged) > 120f) {
                            // Swiping right (finger moves left-to-right, dragged > 0) advances to
                            // the next tab; swiping left goes back — confirmed inverted from this
                            // on live testing, so the branches are deliberately swapped from what
                            // the raw drag-delta sign would suggest.
                            topLevelOrder.getOrNull(if (dragged > 0) from - 1 else from + 1)?.let { target ->
                                Haptics.tick(context)
                                goToTopLevel(target)
                            }
                        }
                        dragged = 0f
                    }
                ) { _, dragAmount -> dragged += dragAmount }
            }
    ) {
        // A small, route-aware motion language rather than the library default cross-fade: the
        // hero settles forward into a workout, exercises slide across like turning a page, and
        // finishing settles into the completion screen — echoing a shared-element transition
        // without the navigation-architecture rework a literal one would need.
        NavHost(
            navController, startDestination = "launch", modifier = Modifier.fillMaxSize(),
            enterTransition = {
                val from = initialState.destination.route; val to = targetState.destination.route
                when {
                    from == "today" && to?.startsWith("active/") == true -> fadeIn(tween(280)) + scaleIn(initialScale = 0.94f, animationSpec = tween(280))
                    from?.startsWith("active/") == true && to?.startsWith("active/") == true -> fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 4 }
                    from?.startsWith("active/") == true && (to?.startsWith("complete/") == true || to?.startsWith("finisher/") == true) -> fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320))
                    from?.startsWith("finisher/") == true && to?.startsWith("complete/") == true -> fadeIn(tween(320)) + scaleIn(initialScale = 0.96f, animationSpec = tween(320))
                    else -> fadeIn(tween(200))
                }
            },
            exitTransition = {
                val from = initialState.destination.route; val to = targetState.destination.route
                when {
                    from?.startsWith("active/") == true && to?.startsWith("active/") == true -> fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 4 }
                    else -> fadeOut(tween(180))
                }
            },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)) }
        ) {
            composable("launch") { LaunchScreen(onFinished = { navController.navigate("today") { popUpTo("launch") { inclusive = true } } }) }
            composable("today") {
                TodayScreen(vm,
                    onStartWorkout = { navController.navigate("active/0") },
                    onExercise = { idx -> navController.navigate("active/$idx") },
                    onCompleteWorkout = {
                        val session = vm.today.value.session
                        if (session != null) navController.navigate("complete/${session.id}")
                    },
                    onViewWorkout = {
                        val session = vm.today.value.session
                        if (session != null) navController.navigate("view/${session.id}")
                    }
                )
            }
            composable("active/{index}") { entry ->
                // "index" is a circuit step (round*exerciseCount + exerciseIndexInRound), not a
                // plain exercise index — tapping an exercise row from Today still lands correctly
                // on round 1's matching step, since step == exerciseIdx when round is 1.
                val step = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                ActiveWorkoutScreen(vm, step,
                    onBack = { navController.popBackStack() },
                    onNextStep = { next -> navController.navigate("active/$next") { popUpTo("today") } },
                    onFinisher = {
                        val session = vm.today.value.session
                        if (session != null) navController.navigate("finisher/${session.id}") { popUpTo("today") }
                    },
                    onFinish = {
                        val session = vm.today.value.session
                        if (session != null) navController.navigate("complete/${session.id}") { popUpTo("today") }
                    }
                )
            }
            composable("finisher/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val session = vm.today.value.session
                if (session != null && session.id == sessionId) {
                    FinisherScreen(vm, session, onFinish = {
                        navController.navigate("complete/${session.id}") { popUpTo("today") }
                    })
                }
            }
            composable("complete/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val state = vm.today.value
                val session = state.session
                if (session != null && session.id == sessionId) {
                    WorkoutCompleteScreen(vm, session, state.day?.focus ?: "Workout",
                        onCancel = { navController.popBackStack() },
                        onBackToToday = { navController.popBackStack("today", inclusive = false) }
                    )
                }
            }
            composable("view/{sessionId}") { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val state = vm.today.value
                val session = state.session
                if (session != null && session.id == sessionId) {
                    WorkoutCompleteScreen(vm, session, state.day?.focus ?: "Workout", readOnly = true,
                        onBackToToday = { navController.popBackStack() }
                    )
                }
            }
            composable("progress") { ProgressScreen(vm) }
            composable("profile") { ProfileScreen(vm) }
        }

        if (currentRoute in topLevelRoutes) {
            // Scrollable screens run their content straight under the dock without this — a hard
            // cutoff rather than a graceful disappearance. A transparent-to-background gradient
            // sitting just above (and behind) the dock fixes that; it has no pointer input of its
            // own, so scrolling/tapping the content underneath still works right through it.
            // Reaching full opacity by the 45% mark (not a plain top-to-bottom fade) matters:
            // the dock's own glass fill is deliberately translucent, so scrolled content sitting
            // anywhere behind the dock itself needs to already be fully hidden by solid background
            // before it gets there — a fade still in progress at the dock's own height was
            // letting bright content underneath show through and fight with the dock's labels.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BottomDockClearance + 30.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to AmbientDeepNavy, 1f to AmbientDeepNavy))
            )
            // Floating material: closest to the viewer, so it gets the crispest top highlight —
            // but a very translucent fill so it reads as a system control, not another card
            // competing with the content underneath it.
            // The no-op clickable on the whole Row (not just its three items) means the dock
            // claims every pixel of its own rectangle, including the small gaps between icons —
            // nothing behind it can ever steal a tap that visually landed on the dock itself.
            val dockInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .glassFloating(RoundedCornerShape(24.dp))
                    .clickable(interactionSource = dockInteraction, indication = null) {},
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(Triple("today", "Today", Icons.Default.Today), Triple("progress", "Progress", Icons.AutoMirrored.Filled.ShowChart), Triple("profile", "Profile", Icons.Default.Person)).forEach { (route, label, icon) ->
                    val selected = currentRoute == route
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .pressable(scaleDown = 0.9f) { Haptics.tick(context); goToTopLevel(route) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, null, tint = if (selected) GoldAccent else Secondary, modifier = Modifier.size(22.dp))
                        Text(label, color = if (selected) GoldAccent else Secondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
