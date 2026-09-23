package com.training.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.training.app.data.PROGRAM_LENGTH_WEEKS
import com.training.app.data.CURRENT_WEEK
import com.training.app.data.local.UserGoalsEntity
import com.training.app.health.HealthConnectManager
import com.training.app.ui.BottomDockClearance
import com.training.app.ui.components.AmbientBackground
import com.training.app.ui.components.pluralize
import com.training.app.ui.theme.*
import com.training.app.util.Haptics
import com.training.app.viewmodel.TrainingViewModel
import kotlinx.coroutines.launch

// Today = action, Progress = reflection, Profile = configuration — this screen intentionally
// carries less information and less visual energy than the other two. No hero imagery, no
// gradients beyond the one progress bar, no stat-block treatment: quiet embedded rows that
// answer who the user is, what program they're on, and what a handful of settings currently are.
@Composable
fun ProfileScreen(vm: TrainingViewModel) {
    val weight by vm.latestWeight.collectAsState()
    val goals by vm.goals.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // "Checking" only ever shows briefly while the first permission/read call is in flight —
    // distinct from "not connected," which is a settled state once that check completes.
    var hcState by remember { mutableStateOf<HcState>(HcState.Checking) }

    suspend fun refreshHealthConnect() {
        hcState = if (!HealthConnectManager.isAvailable(context)) HcState.Unavailable
        else if (!HealthConnectManager.hasAllPermissions(context)) HcState.NotConnected
        else HcState.Connected(HealthConnectManager.mostRecentSessionLabel(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(HealthConnectManager.requestPermissionContract()) { granted ->
        scope.launch {
            hcState = if (granted.containsAll(HealthConnectManager.PERMISSIONS)) {
                HcState.Connected(HealthConnectManager.mostRecentSessionLabel(context))
            } else HcState.NotConnected
        }
    }

    LaunchedEffect(Unit) { refreshHealthConnect() }

    // The disconnect flow (and permission grants generally) hand off to another app entirely —
    // without this, coming back from Health Connect's own settings screen would still show
    // whatever state was true when this screen last composed, not what's true now.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scope.launch { refreshHealthConnect() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showWeightDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showGoalsDialog by remember { mutableStateOf(false) }

    AmbientBackground { Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = BottomDockClearance), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Profile", style = MaterialTheme.typography.displayLarge, color = Color.White)

        Box(Modifier.fillMaxWidth().glassElevated()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A neutral glass circle, not a saturated color fill — an avatar placeholder
                    // should read as part of this dark material language, not as a bright accent
                    // competing with the one thing (progress) that's meant to draw the eye here.
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = .07f))
                            .border(1.dp, Color.White.copy(alpha = .12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Text("J", color = GoldAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Jay", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        // Day-of-week is dropped here — Today already names the exact session,
                        // this only needs to answer "what program am I on."
                        Text("$PROGRAM_LENGTH_WEEKS-week program · Week $CURRENT_WEEK", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .08f))) {
                        // Solid accent yellow, not the Today CTA's warm gradient — that gradient
                        // means "act now" elsewhere in the app; here it would borrow urgency this
                        // screen isn't meant to have.
                        Box(Modifier.fillMaxWidth(CURRENT_WEEK / PROGRAM_LENGTH_WEEKS.toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(GoldAccent))
                    }
                    Text("$CURRENT_WEEK / $PROGRAM_LENGTH_WEEKS", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionLabel("Body")
        // Height isn't tracked or used anywhere in the app — showing it would be collecting a
        // number with no product purpose. Weight alone, as a quiet row rather than a hero stat.
        ProfileRow(Icons.Default.MonitorWeight, "Weight", weight?.let { "${it.weightKg} kg" } ?: "Not logged", onClick = { showWeightDialog = true })

        SectionLabel("Connected data")
        // Elevated, not embedded — the one thing on this screen given more visual weight, since
        // Health Connect is the upcoming P0 integration and deserves to read as more than a
        // settings toggle among many. Tapping while disconnected launches the real system
        // permission flow; tapping while connected asks about disconnecting — a plain status
        // display shouldn't ALSO be the thing you tap to refresh it silently.
        val hcValue = when (hcState) {
            HcState.Checking -> "Checking..."
            HcState.Unavailable -> "Unavailable"
            HcState.NotConnected -> "Disconnected"
            is HcState.Connected -> "Connected"
        }
        val hcStatusColor = if (hcState is HcState.Connected) GoldAccent else TextTertiary
        ProfileRow(
            Icons.Default.MonitorHeart, "Health Connect", hcValue, elevated = true, statusColor = hcStatusColor,
            onClick = {
                when (hcState) {
                    HcState.NotConnected -> permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    is HcState.Connected -> showDisconnectConfirm = true
                    HcState.Checking -> scope.launch { refreshHealthConnect() }
                    HcState.Unavailable -> {}
                }
            }
        )

        SectionLabel("Preferences")
        ProfileRow(Icons.Default.Straighten, "Units", "kg, cm")
        // A short "how many are set" summary, not the numbers themselves — the row needs to stay
        // legible next to a fixed-width label, and the popup already shows the exact values.
        val goalsSetCount = listOfNotNull(goals?.dailyCalories, goals?.dailyDistanceKm, goals?.dailySteps).size
        val goalsValue = if (goalsSetCount == 0) "Not set" else "$goalsSetCount ${pluralize(goalsSetCount, "target")} set"
        ProfileRow(Icons.Default.TrackChanges, "Weekly goals", goalsValue, onClick = { showGoalsDialog = true })
    } }

    if (showWeightDialog) {
        WeightDialog(
            currentWeightKg = weight?.weightKg,
            onSave = { kg -> vm.logWeight(kg); showWeightDialog = false },
            onDismiss = { showWeightDialog = false }
        )
    }
    if (showDisconnectConfirm) {
        DisconnectConfirmDialog(
            onConfirm = { context.startActivity(HealthConnectManager.manageAppPermissionsIntent(context)); showDisconnectConfirm = false },
            onDismiss = { showDisconnectConfirm = false }
        )
    }
    if (showGoalsDialog) {
        GoalsDialog(
            current = goals,
            onSave = { calories, distanceKm, steps -> vm.saveGoals(calories, distanceKm, steps); showGoalsDialog = false },
            onDismiss = { showGoalsDialog = false }
        )
    }
}

/** Plain-language consequence, not just a yes/no — disconnecting is easy to reverse (reconnect
 * from the same row), but the user should know what stops before they tap it, not after.
 * "Disconnect" here hands off to Health Connect's own permissions screen rather than doing it
 * silently in-app — the client SDK's revoke call doesn't actually clear the OS grant on this
 * platform (confirmed on-device), so pretending to disconnect in-app would show a status that
 * doesn't match reality. */
@Composable
private fun DisconnectConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Disconnect Health Connect?", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Turning off access stops syncing workouts, steps, sleep, and heart rate from your watch. This opens Health Connect's permissions screen for Arisyn, where you can turn it off.",
                color = Secondary, style = MaterialTheme.typography.bodyMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cancel", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .12f))
                        .pressable { onConfirm() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Open settings", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Manual entry, or pull the current reading from Health Connect if the user's scale writes
 * there — a separate, opt-in permission from the main connect flow (see WEIGHT_PERMISSION),
 * requested here the first time it's actually needed rather than upfront. */
@Composable
private fun WeightDialog(currentWeightKg: Double?, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(currentWeightKg?.toString().orEmpty()) }
    var hasWeightPermission by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    suspend fun pullFromHealthConnect() {
        syncing = true
        val kg = HealthConnectManager.latestWeightKg(context)
        syncing = false
        if (kg != null) { text = kg.toString(); syncMessage = null } else syncMessage = "No weight found in Health Connect."
    }

    LaunchedEffect(Unit) {
        hasWeightPermission = HealthConnectManager.isAvailable(context) && HealthConnectManager.hasWeightPermission(context)
    }

    val weightPermissionLauncher = rememberLauncherForActivityResult(HealthConnectManager.requestPermissionContract()) { granted ->
        hasWeightPermission = granted.contains(HealthConnectManager.WEIGHT_PERMISSION)
        if (hasWeightPermission) scope.launch { pullFromHealthConnect() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Log weight", style = MaterialTheme.typography.titleLarge, color = Color.White)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Weight (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().glassEmbedded()
                    .pressable { if (hasWeightPermission) scope.launch { pullFromHealthConnect() } else weightPermissionLauncher.launch(setOf(HealthConnectManager.WEIGHT_PERMISSION)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MonitorHeart, null, tint = GoldAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (syncing) "Syncing..." else "Sync from Health Connect", color = Color.White, modifier = Modifier.weight(1f))
            }
            syncMessage?.let { Text(it, color = TextTertiary, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cancel", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PrimaryGradient)
                        .pressable { text.toDoubleOrNull()?.let { onSave(it) } }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Sets the three *daily* targets the Today screen's Weekly Goals rings turn into weekly ones
 * (daily x 7) — same styling as WeightDialog/DisconnectConfirmDialog so every popup on this
 * screen reads as one family. Any field left blank saves as null (no target, no ring for that
 * metric) rather than 0, which would render as an impossible always-complete ring. */
@Composable
private fun GoalsDialog(current: UserGoalsEntity?, onSave: (Int?, Double?, Int?) -> Unit, onDismiss: () -> Unit) {
    var calories by remember { mutableStateOf(current?.dailyCalories?.toString().orEmpty()) }
    var distance by remember { mutableStateOf(current?.dailyDistanceKm?.toString().orEmpty()) }
    var steps by remember { mutableStateOf(current?.dailySteps?.toString().orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(AmbientDeepNavy.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                .glassElevated(RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Weekly goals", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Set a daily target for each — the week's goal is just that x 7.", color = Secondary, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = calories, onValueChange = { calories = it },
                label = { Text("Calories / day (kcal)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = distance, onValueChange = { distance = it },
                label = { Text("Distance / day (km)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = steps, onValueChange = { steps = it },
                label = { Text("Steps / day") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Cancel", color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).pressable { onDismiss() }.padding(vertical = 12.dp)
                )
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PrimaryGradient)
                        .pressable { onSave(calories.toIntOrNull(), distance.toDoubleOrNull(), steps.toIntOrNull()) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Save", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text.uppercase(), color = Secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)

private sealed class HcState {
    object Checking : HcState()
    object Unavailable : HcState()
    object NotConnected : HcState()
    data class Connected(val lastSyncLabel: String?) : HcState()
}

/** A configuration row rather than a settings-dashboard row: quiet by default, elevated only
 * when a row (Health Connect) specifically needs more presence. Press feedback is its own thing
 * here rather than the shared pressable() — a scale plus a brief tonal lightening plus a light
 * haptic, matching the spec's row-interaction language exactly rather than reusing Today's. */
@Composable
private fun ProfileRow(icon: ImageVector, title: String, value: String? = null, elevated: Boolean = false, statusColor: Color? = null, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh), label = "rowScale")
    Box(
        Modifier.fillMaxWidth().scale(scale)
            .let { if (elevated) it.glassElevated() else it.glassEmbedded() }
            .background(Color.White.copy(alpha = if (pressed) .04f else 0f))
            .clickable(interactionSource = interactionSource, indication = null) { Haptics.tick(context); onClick() }
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            // The title owns the weight(1f) space and a long value (e.g. a multi-part goals
            // summary) sits outside it — without maxLines/ellipsis here, a long enough value
            // starves the title's measured width and wraps it letter-by-letter instead of the
            // value being the thing that truncates.
            Text(title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            statusColor?.let {
                Box(Modifier.size(7.dp).clip(CircleShape).background(it))
                Spacer(Modifier.width(7.dp))
            }
            value?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}
