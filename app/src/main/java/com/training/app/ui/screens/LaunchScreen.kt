package com.training.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.training.app.ui.components.AmbientBackground
import kotlinx.coroutines.delay

@Composable
fun LaunchScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { delay(900); onFinished() }
    AmbientBackground {
        Column(Modifier.fillMaxSize().padding(bottom = 60.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Arisyn", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("STRONGER YOU", color = Color.White.copy(alpha = .8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
        }
    }
}
