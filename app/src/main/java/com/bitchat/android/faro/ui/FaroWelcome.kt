package com.bitchat.android.faro.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.ChatViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Bienvenida de Faro: qué es, cómo te llamas y los permisos de uno en uno, explicando para qué
 * sirve cada uno. Se puede volver a ver desde Ajustes.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun WelcomeScreen(chatViewModel: ChatViewModel, onDone: () -> Unit) {
    val nickname by chatViewModel.nickname.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(nickname) }
    val location = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val notifications = if (Build.VERSION.SDK_INT >= 33) rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) else null

    fun next() {
        step++
        if (step == 2 && location.allPermissionsGranted) step++
        if (step == 3 && (notifications == null || notifications.status.isGranted)) step++
        if (step >= 4) onDone()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = if (step == 1) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = if (step == 1) Arrangement.Top else Arrangement.Center,
            ) {
                when (step) {
                    0 -> {
                        Text("Faro", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Gap(12.dp)
                        Text(
                            "Mensajes y ubicación para tu familia, aunque no haya cobertura.",
                            fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, color = Color(0xFFD6D6D6),
                        )
                        Gap(10.dp)
                        Text(
                            "Funciona por Bluetooth entre móviles cercanos. Gratis, sin cuentas y sin anuncios.",
                            fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, color = FaroColors.Muted,
                        )
                    }
                    1 -> {
                        Gap(60.dp)
                        Text("¿Cómo te llamas?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Gap(8.dp)
                        Text("Así te verá tu familia. Puedes cambiarlo después.", fontSize = 14.5.sp, color = FaroColors.Muted)
                        Gap(16.dp)
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
                            placeholder = { Text("Por ejemplo, Carmen") }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent),
                        )
                    }
                    2 -> PermissionStep(Icons.Outlined.Place, "Tu ubicación", "Solo la ven los grupos que tú elijas, y solo cuando lo actives. Nunca se envía a ningún servidor.")
                    else -> PermissionStep(Icons.Outlined.Notifications, "Avisos", "Para avisarte si alguien de tu familia pide ayuda, aunque tengas el móvil en silencio.")
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                (0..3).forEach { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(7.dp).width(if (i == step) 20.dp else 7.dp)
                            .clip(RoundedCornerShape(4.dp)).background(if (i == step) FaroColors.Accent else Color(0xFF333333))
                    )
                }
            }
            when (step) {
                0 -> FilledPill("Empezar", Modifier.fillMaxWidth()) { next() }
                1 -> FilledPill("Continuar", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                    chatViewModel.setNickname(name.trim()); next()
                }
                2 -> {
                    FilledPill("Permitir", Modifier.fillMaxWidth()) { location.launchMultiplePermissionRequest(); next() }
                    TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text("Ahora no", color = Color(0xFFBDBDBD)) }
                }
                else -> {
                    FilledPill("Permitir", Modifier.fillMaxWidth()) { notifications?.launchPermissionRequest(); next() }
                    TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text("Ahora no", color = Color(0xFFBDBDBD)) }
                }
            }
            Gap(18.dp)
        }
    }
}

@Composable
private fun PermissionStep(icon: ImageVector, title: String, text: String) {
    Box(Modifier.size(84.dp).border(1.5.dp, FaroColors.Line2, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = FaroColors.Accent, modifier = Modifier.size(40.dp))
    }
    Gap(16.dp)
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Gap(10.dp)
    Text(text, fontSize = 15.5.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, color = Color(0xFFBDBDBD))
}

@Suppress("unused")
private val unusedIcon = Icons.Outlined.WifiTethering
