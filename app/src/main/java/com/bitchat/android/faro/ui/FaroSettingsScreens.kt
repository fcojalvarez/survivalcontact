package com.bitchat.android.faro.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroOfflineMap
import com.bitchat.android.faro.FaroSettings
import com.bitchat.android.faro.FaroShare
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.LanguagePreferenceManager
import com.bitchat.android.ui.VerificationSheet
import com.bitchat.android.update.FaroUpdater

/** Páginas de Ajustes. */
internal enum class SettingsPage { MAIN, NAME, SHARE, ALERTS, BATTERY, MAP, UPDATES, PRIVACY, LANGUAGE, SHARE_APP }

@Composable
internal fun SettingsScreen(nav: FaroNav, page: SettingsPage, chatViewModel: ChatViewModel, onWelcome: () -> Unit) {
    when (page) {
        SettingsPage.MAIN -> SettingsMain(nav, chatViewModel, onWelcome)
        SettingsPage.NAME -> NamePage(nav, chatViewModel)
        SettingsPage.SHARE -> SharePage(nav)
        SettingsPage.ALERTS -> AlertsPage(nav)
        SettingsPage.BATTERY -> BatteryPage(nav)
        SettingsPage.MAP -> MapPage(nav)
        SettingsPage.UPDATES -> UpdatesPage(nav)
        SettingsPage.PRIVACY -> PrivacyPage(nav, chatViewModel)
        SettingsPage.LANGUAGE -> LanguagePage(nav)
        SettingsPage.SHARE_APP -> ShareAppPage(nav)
    }
}

private fun FaroNav.page(p: SettingsPage) = go(Route.Settings(p))

@Composable
private fun SettingsMain(nav: FaroNav, chatViewModel: ChatViewModel, onWelcome: () -> Unit) {
    val nickname by chatViewModel.nickname.collectAsStateWithLifecycle()
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val offline by FaroOfflineMap.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    BackBar("Ajustes", onBack = { nav.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().card().clickable { nav.page(SettingsPage.NAME) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(nickname, "#A7C7E7", 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(nickname.ifBlank { "Sin nombre" }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Así te ve tu familia · cambiar", fontSize = 13.sp, color = FaroColors.Muted)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = FaroColors.Dim)
        }
        Gap(14.dp)
        Group {
            Item(Icons.Outlined.Place, "Compartir mi ubicación", value = "${groups.count { it.shareLocation }} de ${groups.size}") { nav.page(SettingsPage.SHARE) }
            Item(Icons.Outlined.Notifications, "Avisos") { nav.page(SettingsPage.ALERTS) }
            Item(Icons.Outlined.BatteryStd, "Batería", value = batteryName(FaroSettings.battery)) { nav.page(SettingsPage.BATTERY) }
            Item(Icons.Outlined.Map, "Mapa sin conexión", value = if (offline is FaroOfflineMap.State.Ready) "Guardado" else "No guardado") { nav.page(SettingsPage.MAP) }
        }
        Gap(12.dp)
        Group {
            Item(Icons.Outlined.Share, "Compartir Faro", sub = "Pásala a quien no la tenga, sin internet") { nav.page(SettingsPage.SHARE_APP) }
            Item(Icons.Outlined.SystemUpdate, "Actualizaciones") { nav.page(SettingsPage.UPDATES) }
            Item(Icons.Outlined.Lock, "Privacidad y seguridad") { nav.page(SettingsPage.PRIVACY) }
            Item(Icons.Outlined.Language, "Idioma") { nav.page(SettingsPage.LANGUAGE) }
            Item(Icons.AutoMirrored.Outlined.HelpOutline, "Ver la bienvenida otra vez") { onWelcome() }
        }
        Gap(14.dp)
        Text(
            "Faro $version · software libre (GPL v3)\nSin cuentas, sin anuncios, sin servidores propios.",
            fontSize = 12.5.sp, color = FaroColors.Dim, lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun batteryName(b: FaroSettings.Battery) = when (b) {
    FaroSettings.Battery.AUTO -> "Automático"
    FaroSettings.Battery.SAVER -> "Ahorro siempre"
    FaroSettings.Battery.MAX -> "Máximo ahorro"
}

// ---------- Piezas ----------

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().card()) { content() }
}

@Composable
private fun Item(icon: ImageVector, title: String, value: String? = null, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = FaroColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = FaroColors.Muted)
        }
        if (value != null) Text(value, fontSize = 14.sp, color = FaroColors.Muted, modifier = Modifier.padding(end = 4.dp))
        Icon(Icons.Outlined.ChevronRight, null, tint = FaroColors.Dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Toggle(title: String, sub: String? = null, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().clickable { on = !on; onChange(on) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = FaroColors.Muted, lineHeight = 16.sp)
        }
        Switch(
            checked = on, onCheckedChange = { on = it; onChange(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = FaroColors.Accent, checkedThumbColor = FaroColors.AccentInk),
        )
    }
}

@Composable
private fun Radio(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
            .card(if (selected) FaroColors.Accent else FaroColors.Line)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape)
                .border(if (selected) 6.dp else 1.5.dp, if (selected) FaroColors.Accent else Color(0xFF555555), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = FaroColors.Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Explain(text: String) {
    Text(text, fontSize = 13.5.sp, color = FaroColors.Muted, lineHeight = 20.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
}

// ---------- Páginas ----------

@Composable
private fun NamePage(nav: FaroNav, chatViewModel: ChatViewModel) {
    val current by chatViewModel.nickname.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(current) }
    BackBar("Tu nombre", onBack = { nav.back() })
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
            placeholder = { Text("Tu nombre") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent),
        )
        Text("Es lo que verán tus grupos y la gente cerca.", fontSize = 13.5.sp, color = FaroColors.Muted)
        FilledPill("Guardar", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
            chatViewModel.setNickname(name.trim())
            nav.back()
        }
    }
}

@Composable
private fun SharePage(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    BackBar("Compartir mi ubicación", onBack = { nav.back() })
    Explain("Elige qué grupos pueden ver dónde estás. Si pides ayuda, se envía a todos aunque esté apagado.")
    if (groups.isEmpty()) Hint("Aún no tienes grupos.")
    else Group {
        groups.sortedBy { it.createdAtMs }.forEach { g ->
            androidx.compose.runtime.key(g.id) {
                Toggle(g.name, initial = g.shareLocation) { FaroGroups.setShareLocation(g.id, it) }
            }
        }
    }
    Gap(10.dp)
    Hint("Si alguien mira dónde estás teniendo internet, te llegará un aviso. Sin conexión o si alguien ha pedido ayuda no se avisa: es una emergencia y es normal mirar.")
}

@Composable
private fun AlertsPage(nav: FaroNav) {
    BackBar("Avisos", onBack = { nav.back() })
    Group {
        Toggle("Mensajes de mis grupos", initial = FaroSettings.notifyMessages) { FaroSettings.notifyMessages = it }
        Toggle("Quién quiere entrar en mis grupos", initial = FaroSettings.notifyRequests) { FaroSettings.notifyRequests = it }
        Toggle("Cuando alguien ve dónde estoy", sub = "Solo llega si esa persona tiene internet", initial = FaroSettings.notifySeen) { FaroSettings.notifySeen = it }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("Peticiones de ayuda", fontSize = 15.sp)
            Text("Siempre activadas, con sonido aunque tengas el móvil en silencio.", fontSize = 12.sp, color = FaroColors.Muted)
        }
    }
}

@Composable
private fun BatteryPage(nav: FaroNav) {
    var mode by remember { mutableStateOf(FaroSettings.battery) }
    fun set(m: FaroSettings.Battery) { mode = m; FaroSettings.battery = m }
    BackBar("Batería", onBack = { nav.back() })
    Explain("Faro cambia de modo solo según la batería que te quede.")
    Radio("Automático (recomendado)", "Envía tu ubicación cada 5 min con más del 50 %, cada 15 min con menos y solo si pides ayuda por debajo del 20 %.", mode == FaroSettings.Battery.AUTO) { set(FaroSettings.Battery.AUTO) }
    Radio("Ahorro siempre", "Envía tu ubicación cada 15 min.", mode == FaroSettings.Battery.SAVER) { set(FaroSettings.Battery.SAVER) }
    Radio("Máximo ahorro", "Solo recibe mensajes. Tu ubicación solo se envía si pides ayuda.", mode == FaroSettings.Battery.MAX) { set(FaroSettings.Battery.MAX) }
}

@Composable
private fun MapPage(nav: FaroNav) {
    val state by FaroOfflineMap.state.collectAsStateWithLifecycle()
    var askDelete by remember { mutableStateOf(false) }
    BackBar("Mapa sin conexión", onBack = { nav.back() })
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().card().padding(14.dp)) {
        when (val s = state) {
            is FaroOfflineMap.State.Ready -> {
                Text("Tu zona está guardada", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Ciudad 10 km · comarca 60 km · ${"%.0f".format(s.megabytes)} MB", fontSize = 13.sp, color = FaroColors.Muted)
            }
            is FaroOfflineMap.State.Downloading -> {
                Text("Guardando tu zona… ${s.percent} %", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Puedes seguir usando Faro mientras tanto.", fontSize = 13.sp, color = FaroColors.Muted)
            }
            FaroOfflineMap.State.Error -> {
                Text("No se pudo guardar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Se volverá a intentar cuando haya conexión.", fontSize = 13.sp, color = FaroColors.Muted)
            }
            FaroOfflineMap.State.None -> {
                Text("Sin mapa guardado", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Se guardará tu zona la próxima vez que tengas Wi‑Fi.", fontSize = 13.sp, color = FaroColors.Muted)
            }
        }
    }
    Gap(12.dp)
    Group { Toggle("Cambiar de zona solo con Wi‑Fi", sub = "Para no gastar datos del móvil", initial = FaroSettings.mapWifiOnly) { FaroSettings.mapWifiOnly = it } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinePill("Guardar mi zona ahora", Modifier.fillMaxWidth()) { FaroOfflineMap.updateNow() }
        if (state is FaroOfflineMap.State.Ready) TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Borrar mapa guardado", color = FaroColors.Danger)
        }
    }
    Hint("Si te vas a otra ciudad, Faro guarda la nueva zona solo.")
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false }, containerColor = FaroColors.Card,
        title = { Text("¿Borrar el mapa guardado?") },
        text = { Text("Sin internet no verás calles en el mapa hasta que se vuelva a guardar.") },
        confirmButton = { TextButton(onClick = { askDelete = false; FaroOfflineMap.deleteAll() }) { Text("Borrar", color = FaroColors.Danger) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun UpdatesPage(nav: FaroNav) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    BackBar("Actualizaciones", onBack = { nav.back() })
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().card().padding(14.dp)) {
        Text("Faro $version", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            if (asked) "Buscando… Si hay una versión nueva te avisaremos para instalarla con un toque."
            else "Faro busca versiones nuevas solo, una vez al día, con Wi‑Fi.",
            fontSize = 13.sp, color = FaroColors.Muted, lineHeight = 18.sp,
        )
    }
    Column(Modifier.padding(16.dp)) {
        OutlinePill("Buscar ahora", Modifier.fillMaxWidth()) { FaroUpdater.checkNow(context); asked = true }
    }
}

@Composable
private fun PrivacyPage(nav: FaroNav, chatViewModel: ChatViewModel) {
    val context = LocalContext.current
    var showCode by remember { mutableStateOf(false) }
    var askWipe by remember { mutableStateOf(false) }
    BackBar("Privacidad y seguridad", onBack = { nav.back() })
    Group { Item(Icons.Outlined.QrCode2, "Mi código de verificación", sub = "Para que otros comprueben que eres tú") { showCode = true } }
    Gap(12.dp)
    Explain("Faro no tiene servidores propios ni cuentas. Tus mensajes y ubicaciones van cifrados de extremo a extremo y solo los ven tus grupos.")
    Group {
        Row(Modifier.fillMaxWidth().clickable { askWipe = true }.padding(14.dp)) {
            Text("Borrar todos mis datos", color = FaroColors.Danger, fontSize = 15.sp)
        }
    }
    VerificationSheet(isPresented = showCode, onDismiss = { showCode = false }, viewModel = chatViewModel)
    if (askWipe) AlertDialog(
        onDismissRequest = { askWipe = false }, containerColor = FaroColors.Card,
        title = { Text("¿Borrar todos tus datos?") },
        text = { Text("Saldrás de todos tus grupos y se borrarán tus mensajes y tu identidad de este móvil. No se puede deshacer.") },
        confirmButton = {
            TextButton(onClick = {
                askWipe = false
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.clearApplicationUserData()
            }) { Text("Borrar todo", color = FaroColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { askWipe = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun LanguagePage(nav: FaroNav) {
    val context = LocalContext.current
    val langs = remember { LanguagePreferenceManager.supportedLanguages(context) }
    var current by remember { mutableStateOf(LanguagePreferenceManager.currentLanguageTag()) }
    BackBar("Idioma", onBack = { nav.back() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Radio("Igual que el móvil", null, current.isBlank()) { current = ""; LanguagePreferenceManager.setLanguage("") }
        val es = langs.filter { it.languageTag.startsWith("es") }
        (es + (langs - es.toSet())).forEach { l ->
            Radio(l.endonym, null, current.equals(l.languageTag, ignoreCase = true)) {
                current = l.languageTag; LanguagePreferenceManager.setLanguage(l.languageTag)
            }
        }
        Gap(16.dp)
    }
}

@Composable
private fun ShareAppPage(nav: FaroNav) {
    val context = LocalContext.current
    BackBar("Compartir Faro", onBack = { nav.back() })
    Explain("Cuantas más personas cerca tengan Faro, más lejos llegan los mensajes. Puedes pasarla sin internet.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShareOption(Icons.Outlined.QrCode2, "Por Wi‑Fi con un QR", "Tu móvil crea una red; la otra persona escanea y descarga Faro.") { FaroShare.shareByWifi(context) }
        ShareOption(Icons.Outlined.Share, "Con Quick Share u otra app", "Envía el instalador por Quick Share, Bluetooth o mensajería.") { FaroShare.shareWithApps(context) }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().card().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = FaroColors.Accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 13.sp, color = FaroColors.Muted, lineHeight = 18.sp)
        }
    }
}

@Suppress("unused")
private val keepHeight = Modifier.height(0.dp)
