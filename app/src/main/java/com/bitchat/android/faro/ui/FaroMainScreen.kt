package com.bitchat.android.faro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroDeepLink
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroJoin
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroMessage
import com.bitchat.android.faro.JoinRequest
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.rememberIsOnline

/** Pantallas de Faro a las que se llega desde las pestañas. */
internal sealed class Route {
    object Tabs : Route()
    object Nearby : Route()
    object Create : Route()
    object Places : Route()
    object AddPlace : Route()
    object Trip : Route()
    data class History(val personId: String) : Route()
    object Scan : Route()
    data class Group(val id: String) : Route()
    data class Info(val id: String) : Route()
    data class Invite(val id: String) : Route()
    data class Guide(val personId: String) : Route()
    data class Settings(val page: SettingsPage) : Route()
}

/** Seguimiento en directo de una persona hasta [untilMs]. */
internal data class Following(val personId: String, val untilMs: Long)

internal enum class Tab { INICIO, CHATS, MAPA }

/** Navegación sencilla: una pila de pantallas encima de las tres pestañas. */
internal class FaroNav {
    val stack = mutableStateListOf<Route>(Route.Tabs)
    var tab by mutableStateOf(Tab.INICIO)
    var mapFilter by mutableStateOf<String?>(null)
    /** Persona cuya ficha está abierta en el mapa. */
    var selected by mutableStateOf<String?>(null)
    var following by mutableStateOf<Following?>(null)
    fun showPerson(id: String) { home(Tab.MAPA); mapFilter = null; selected = id }
    val top: Route get() = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home(t: Tab) { while (stack.size > 1) stack.removeAt(stack.lastIndex); tab = t }
}

/**
 * Pantalla principal de Faro: Inicio, Chats y Mapa, con los ajustes arriba. La sala «Gente cerca»
 * es el chat de malla original de bitchat.
 */
@Composable
fun FaroMainScreen(chatViewModel: ChatViewModel) {
    val nav = remember { FaroNav() }
    var welcome by remember { mutableStateOf(!com.bitchat.android.faro.FaroSettings.welcomeDone) }
    var incoming by remember { mutableStateOf<String?>(null) }
    val sos by FaroLocation.sosActive.collectAsState()
    var sosHidden by remember { mutableStateOf(false) }
    LaunchedEffect(sos) { if (!sos) sosHidden = false }
    val deepLink by FaroDeepLink.pending.collectAsState()

    LaunchedEffect(deepLink) {
        deepLink?.let { incoming = it; FaroDeepLink.consume() }
    }

    // Atrás: primero lo que tenga abierto la sala cercana, luego la pila, luego a Inicio.
    BackHandler(enabled = nav.stack.size > 1 || nav.tab != Tab.INICIO) {
        if (!nav.back()) nav.tab = Tab.INICIO
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        when (val r = nav.top) {
            Route.Tabs -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (nav.tab) {
                        Tab.INICIO -> HomeTab(nav, onSettings = { nav.go(Route.Settings(SettingsPage.MAIN)) }, onShowSos = { sosHidden = false })
                        Tab.CHATS -> ChatsTab(nav, chatViewModel, onSettings = { nav.go(Route.Settings(SettingsPage.MAIN)) })
                        Tab.MAPA -> MapTab(nav)
                    }
                }
                BottomNav(nav)
            }
            Route.Nearby -> {
                // Gente cerca es solo la sala de la malla: nunca un canal público por zona.
                val ctx = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) {
                    runCatching {
                        com.bitchat.android.geohash.LocationChannelManager.getInstance(ctx.applicationContext)
                            .select(com.bitchat.android.geohash.ChannelID.Mesh)
                    }
                }
                Screen { NearbyRoomScreen(nav, chatViewModel) }
            }
            Route.Create -> Screen { CreateGroupScreen(nav) }
            Route.Scan -> Screen { ScanScreen(nav, onCode = { nav.back(); incoming = it }) }
            is Route.Group -> Screen { GroupChatScreen(nav, r.id) }
            is Route.Info -> Screen { GroupInfoScreen(nav, r.id) }
            is Route.Invite -> Screen { InviteScreen(nav, r.id) }
            is Route.Guide -> Screen { GuideScreen(nav, r.personId) }
            Route.Places -> Screen { PlacesScreen(nav) }
            Route.AddPlace -> Screen { AddPlaceScreen(nav) }
            Route.Trip -> Screen { TripScreen(nav) }
            is Route.History -> Screen { HistoryScreen(nav, r.personId) }
            is Route.Settings -> Screen { SettingsScreen(nav, r.page, chatViewModel, onWelcome = { welcome = true }) }
        }
        if (sos && !sosHidden) SosActiveScreen(onOpenChat = { id -> sosHidden = true; nav.home(Tab.INICIO); nav.go(Route.Group(id)) })
    }

    // «Pedir ayuda» desde el widget: siempre con confirmación.
    val askHelp by FaroDeepLink.askHelp.collectAsState()
    if (askHelp && !sos) {
        AlertDialog(
            onDismissRequest = { FaroDeepLink.consumeAskHelp() },
            containerColor = FaroColors.Card,
            title = { Text("¿Pedir ayuda?") },
            text = { Text("Se enviará tu ubicación y el aviso «Necesito ayuda» a todos tus grupos, aunque tengas la ubicación desactivada.") },
            confirmButton = {
                TextButton(onClick = { FaroDeepLink.consumeAskHelp(); FaroLocation.sendSos(true) }) {
                    Text("Sí, pedir ayuda", color = FaroColors.Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { FaroDeepLink.consumeAskHelp() }) { Text("Cancelar") } },
        )
    }

    // Versión nueva ya descargada: se ofrece con una ventana dentro de la app.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var update by remember { mutableStateOf<com.bitchat.android.update.FaroUpdater.ReadyUpdate?>(null) }
    var updateDismissed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            update = com.bitchat.android.update.FaroUpdater.readyUpdate(ctx)
            kotlinx.coroutines.delay(60_000)
        }
    }
    update?.takeIf { it.versionCode != updateDismissed && !welcome && !sos }?.let { u ->
        AlertDialog(
            onDismissRequest = { updateDismissed = u.versionCode },
            containerColor = FaroColors.Card,
            title = { Text("Hay una versión nueva de Faro") },
            text = { Text("La versión ${u.versionName} ya está descargada. Se instala en unos segundos y no perderás tus grupos ni tus mensajes.") },
            confirmButton = {
                TextButton(onClick = { updateDismissed = u.versionCode; com.bitchat.android.update.FaroUpdater.install(ctx, u.apk) }) {
                    Text("Actualizar ahora", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { updateDismissed = u.versionCode }) { Text("Más tarde") } },
        )
    }

    if (welcome) WelcomeScreen(chatViewModel, onDone = { com.bitchat.android.faro.FaroSettings.welcomeDone = true; welcome = false })
    incoming?.let { code -> JoinFlow(code, onDone = { incoming = null }) }
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) { content() }
}

// ---------------------------------------------------------------- Barra inferior

@Composable
private fun BottomNav(nav: FaroNav) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FaroColors.Line))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            listOf(Triple(Tab.INICIO, Icons.Outlined.Home, "Inicio"), Triple(Tab.CHATS, Icons.Outlined.ChatBubbleOutline, "Chats"), Triple(Tab.MAPA, Icons.Outlined.Map, "Mapa"))
                .forEach { (t, icon, label) ->
                    val on = nav.tab == t
                    val c = if (on) FaroColors.Accent else FaroColors.Dim
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable { nav.tab = t },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, contentDescription = label, tint = c, modifier = Modifier.size(22.dp))
                        Text(label, fontSize = 10.5.sp, color = c)
                    }
                }
        }
    }
}

// ---------------------------------------------------------------- Cabecera

@Composable
internal fun MainHeader(onSettings: () -> Unit) {
    val online = rememberIsOnline()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Faro", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.5.dp, FaroColors.Line2, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) FaroColors.Ok else FaroColors.Dim))
            Spacer(Modifier.width(7.dp))
            Text(if (online) "Con internet" else "Sin internet", fontSize = 13.sp, color = Color(0xFFD6D6D6))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, FaroColors.Line2, CircleShape).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Settings, contentDescription = "Ajustes", modifier = Modifier.size(20.dp)) }
    }
}

// ---------------------------------------------------------------- Inicio

@Composable
private fun HomeTab(nav: FaroNav, onSettings: () -> Unit, onShowSos: () -> Unit) {
    val sosOn by FaroLocation.sosActive.collectAsStateWithLifecycle()
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val messages by FaroGroups.messages.collectAsStateWithLifecycle()
    val lastRead by FaroGroups.lastRead.collectAsStateWithLifecycle()
    val requests by FaroGroups.joinRequests.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    val places by FaroGroups.places.collectAsStateWithLifecycle()
    val trips by FaroGroups.trips.collectAsStateWithLifecycle()
    val asks by FaroGroups.asks.collectAsStateWithLifecycle()
    val events by FaroGroups.events.collectAsStateWithLifecycle()
    val myTrip by FaroLocation.trip.collectAsStateWithLifecycle()
    var okSent by remember { mutableStateOf(false) }
    LaunchedEffect(okSent) { if (okSent) { kotlinx.coroutines.delay(4000); okSent = false } }
    var confirmSos by remember { mutableStateOf(false) }
    val dismissed = remember { mutableStateListOf<String>() }
    val open = remember { mutableStateListOf<String>() }
    val myId = remember { FaroGroups.myId() }

    LaunchedEffect(Unit) { FaroLocation.refreshForDisplay() }

    val ppl = people(groups, positions, myId)

    var hints by remember { mutableStateOf(0) } // para refrescar al ocultar un aviso
    Column(Modifier.fillMaxSize()) {
        MainHeader(onSettings)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

        if (groups.isEmpty()) {
            if (hints >= 0 && !com.bitchat.android.faro.FaroSettings.isHidden("crear_grupo")) {
                Column(Modifier.padding(16.dp).card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Crea un grupo para tu familia", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Podréis escribiros y saber dónde estáis aunque no haya cobertura. Solo entra quien tú aceptes.",
                        fontSize = 14.sp, color = FaroColors.Muted, lineHeight = 20.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Crear un grupo", primary = true) { nav.go(Route.Create) }
                        Chip("Unirme con un código") { nav.go(Route.Scan) }
                        Chip("Más tarde") { com.bitchat.android.faro.FaroSettings.hide("crear_grupo"); hints++ }
                    }
                }
            } else {
                Hint("Cuando quieras, crea un grupo o únete a uno desde Chats.", Modifier.padding(vertical = 16.dp))
            }
        } else {
            // Fila de personas
            if (ppl.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ppl.forEach { p ->
                        val ring = when {
                            p.needsHelp -> FaroColors.Danger
                            p.pos == null -> FaroColors.Line2
                            p.silent -> FaroColors.Warn
                            else -> FaroColors.Ok
                        }
                        val sub = when {
                            p.needsHelp -> "ayuda"
                            p.pos == null -> "—"
                            trips[p.id] != null -> "llega " + hhmm(trips[p.id]!!.second.etaMs)
                            p.silent -> hoursText(p.silentFor ?: 0L)
                            placeOf(p, places) != null -> "en " + placeOf(p, places)!!.name
                            else -> me?.let { m -> p.location()?.let { l -> formatDistance(m.distanceTo(l)) } } ?: ago(p.pos.timestampMs).removePrefix("hace ")
                        }
                        Column(
                            Modifier.width(64.dp).clickable { nav.showPerson(p.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(p.name, colorFor(p.id), 56.dp, ring)
                            Gap(4.dp)
                            Text(p.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sub, fontSize = 11.5.sp, color = if (p.needsHelp) FaroColors.Danger else FaroColors.Muted, maxLines = 1)
                        }
                    }
                }
            }
            // Leyenda de grupos
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    groups.sortedBy { it.createdAtMs }.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(hex(groupColorHex(groups, g.id))))
                            Spacer(Modifier.width(6.dp))
                            Text(g.name, fontSize = 13.sp, color = Color(0xFFD6D6D6), maxLines = 1)
                        }
                    }
                }
                Text("Lugares", fontSize = 13.sp, color = FaroColors.Accent, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp).clickable { nav.go(Route.Places) })
                Text("Gestionar", fontSize = 13.sp, color = FaroColors.Accent, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp).clickable { nav.tab = Tab.CHATS })
            }

            // Me están siguiendo en directo
            val followers by FaroLocation.followers.collectAsStateWithLifecycle()
            followers.firstOrNull()?.let { f ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().card(FaroColors.Line2).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Visibility, null, tint = FaroColors.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    val mins = ((f.untilMs - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
                    Text("${f.name} te está siguiendo en el mapa · $mins min", fontSize = 13.5.sp, modifier = Modifier.weight(1f), color = Color(0xFFD6D6D6))
                    Text("Parar", color = FaroColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp).clickable { FaroLocation.stopFollowers() })
                }
            }

            // Grupos míos en los que aún no hay nadie: invitar, sin obligar.
            groups.filter { it.admin && it.members.isEmpty() && !com.bitchat.android.faro.FaroSettings.isHidden("invitar_" + it.id) && hints >= 0 }.forEach { g ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().card().padding(14.dp)) {
                    Text("Invita a tu familia a ${g.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Ahora solo estás tú. Puedes hacerlo cuando quieras.", fontSize = 13.sp, color = FaroColors.Muted)
                    Gap(10.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Invitar", primary = true) { nav.go(Route.Invite(g.id)) }
                        Chip("Más tarde") { com.bitchat.android.faro.FaroSettings.hide("invitar_" + g.id); hints++ }
                    }
                }
            }

            // Importantes
            val help = ppl.filter { it.needsHelp }
            val dying = ppl.filter { !it.needsHelp && it.batteryDying && "dying" + it.id !in dismissed }
            val silent = ppl.filter { !it.needsHelp && !it.batteryDying && it.silent && (it.silentFor ?: 0L) < 48 * HOUR && it.id !in dismissed }
            val myRequests = requests.filter { r -> groups.any { it.id == r.groupId && it.admin } }
            val lowBattery = ppl.filter { p -> p.pos != null && !p.batteryDying && p.pos.position.batteryPct in 1..14 && !p.silent && "bat" + p.id !in dismissed }
            val otherTrips = trips.filterKeys { k -> ppl.any { it.id == k } }
            val unanswered = asks.filter { (_, ts) -> System.currentTimeMillis() - ts > 30 * 60_000L }.keys.mapNotNull { k -> ppl.firstOrNull { it.id == k } }
            if (help.isNotEmpty() || dying.isNotEmpty() || silent.isNotEmpty() || myRequests.isNotEmpty() || lowBattery.isNotEmpty() ||
                otherTrips.isNotEmpty() || unanswered.isNotEmpty() || myTrip != null) {
                SectionTitle("Importantes")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    myTrip?.let { t ->
                        InfoCardRow(
                            key = "mytrip", open = open, border = FaroColors.Ok,
                            leading = { Avatar(FaroGroups.myName(), "#A7C7E7", 34.dp) },
                            title = bold("Vas a ${t.destName}", ""),
                            sub = "llegas a las ${hhmm(t.etaMs)} · tu familia lo ve",
                            subColor = FaroColors.Ok, alwaysOpen = true,
                        ) {
                            Chip("Ya he llegado", primary = true) { FaroLocation.stopTrip() }
                            Chip("Parar") { FaroLocation.stopTrip() }
                        }
                    }
                    otherTrips.forEach { (id, v) ->
                        val (name, t) = v
                        InfoCardRow(
                            key = "trip$id", open = open, border = FaroColors.Accent,
                            leading = { Avatar(name, colorFor(id), 34.dp) },
                            title = bold(name, " va para ${t.destName}"),
                            sub = "llega a las ${hhmm(t.etaMs)}",
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(id) }
                        }
                    }
                    unanswered.forEach { p ->
                        InfoCardRow(
                            key = "ask" + p.id, open = open, border = FaroColors.Warn,
                            leading = { Avatar(p.name, colorFor(p.id), 34.dp) },
                            title = bold(p.name, " no ha contestado si está bien"),
                            sub = (p.pos?.let { "última ubicación " + ago(it.timestampMs) } ?: "sin ubicación") + distanceSuffix(me, p),
                            subColor = FaroColors.Warn,
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(p.id) }
                            Chip("Preguntar otra vez") { FaroGroups.askIfOk(p.id) }
                        }
                    }
                    lowBattery.forEach { p ->
                        InfoCardRow(
                            key = "bat" + p.id, open = open, border = FaroColors.Warn,
                            leading = { Avatar(p.name, colorFor(p.id), 34.dp) },
                            title = bold("A ${p.name}", " le queda un ${p.pos?.position?.batteryPct} % de batería"),
                            sub = "puede que pronto no la veas",
                        ) {
                            Chip("Escribirle", primary = true) { nav.go(Route.Group(p.groupIds.first())) }
                            Chip("Entendido") { dismissed.add("bat" + p.id) }
                        }
                    }
                    help.forEach { p ->
                        val gid = p.groupIds.first()
                        InfoCardRow(
                            key = "help" + p.id, open = open, border = FaroColors.Danger,
                            leading = { Avatar(p.name, colorFor(p.id), 34.dp) },
                            title = bold(p.name, " necesita ayuda"),
                            sub = (p.pos?.let { ago(it.timestampMs) } ?: "") + distanceSuffix(me, p),
                            subColor = FaroColors.Danger, alwaysOpen = true,
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(p.id) }
                            Chip("Guiarme") { nav.go(Route.Guide(p.id)) }
                            Chip("Escribir al grupo") { nav.go(Route.Group(gid)) }
                        }
                    }
                    dying.forEach { p ->
                        InfoCardRow(
                            key = "dying" + p.id, open = open, border = FaroColors.Warn,
                            leading = { Avatar(p.name, colorFor(p.id), 34.dp) },
                            title = bold("A ${p.name}", " se le ha acabado la batería"),
                            sub = (p.pos?.let { "última ubicación " + ago(it.timestampMs) } ?: "") + distanceSuffix(me, p),
                            subColor = FaroColors.Warn,
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(p.id) }
                            Chip("Guiarme") { nav.go(Route.Guide(p.id)) }
                            Chip("Entendido") { dismissed.add("dying" + p.id) }
                        }
                    }
                    myRequests.forEach { r -> RequestCard(r, groups.firstOrNull { it.id == r.groupId }?.name ?: "tu grupo") }
                    silent.forEach { p ->
                        val gid = p.groupIds.first()
                        InfoCardRow(
                            key = "sil" + p.id, open = open, border = hex(groupColorHex(groups, gid)),
                            leading = { Avatar(p.name, colorFor(p.id), 34.dp) },
                            title = bold(p.name, " sin señales desde hace ${hoursText(p.silentFor ?: 0L)}"),
                            sub = groups.firstOrNull { it.id == gid }?.name.orEmpty() + distanceSuffix(me, p, "última vez a "),
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(p.id) }
                            Chip("Escribir al grupo") { nav.go(Route.Group(gid)) }
                            Chip("Ya sé que está bien") { dismissed.add(p.id) }
                        }
                    }
                }
            }

            // Novedades (últimas 24 h)
            val since = System.currentTimeMillis() - 24 * HOUR
            val news: List<FaroMessage> = messages.values.flatten()
                .filter { !it.mine && it.timestampMs > since && groups.any { g -> g.id == it.groupId } }
                .sortedByDescending { it.timestampMs }.take(8)
            SectionTitle("Novedades", "últimas 24 h")
            val seenList by FaroGroups.seenBy.collectAsStateWithLifecycle()
            val familyEvents = events.filter { it.timestampMs > since }.sortedByDescending { it.timestampMs }.take(8)
            if (news.isEmpty() && familyEvents.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeenCard(open, nav)
                    familyEvents.forEach { e ->
                        InfoCardRow(
                            key = "ev" + e.timestampMs + e.authorId, open = open, border = hex(groupColorHex(groups, e.groupId)),
                            leading = { Avatar(e.authorName, colorFor(e.authorId), 34.dp) },
                            title = bold(e.text, ""), sub = hhmm(e.timestampMs),
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(e.authorId) }
                        }
                    }
                }
            } else if (news.isEmpty() && seenList.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp)) { SeenCard(open, nav) }
            } else if (news.isEmpty()) {
                Hint("Aquí verás lo último de tus grupos. Lo que tenga más de 24 h desaparece solo.")
            } else {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeenCard(open, nav)
                    familyEvents.forEach { e ->
                        InfoCardRow(
                            key = "ev" + e.timestampMs + e.authorId, open = open, border = hex(groupColorHex(groups, e.groupId)),
                            leading = { Avatar(e.authorName, colorFor(e.authorId), 34.dp) },
                            title = bold(e.text, ""), sub = hhmm(e.timestampMs),
                        ) {
                            Chip("Ver en el mapa", primary = true) { nav.showPerson(e.authorId) }
                        }
                    }
                    news.forEach { m ->
                        val unread = m.timestampMs > (lastRead[m.groupId] ?: 0L)
                        InfoCardRow(
                            key = m.id, open = open, border = hex(groupColorHex(groups, m.groupId)),
                            leading = { Avatar(m.authorName, colorFor(m.authorId), 34.dp) },
                            title = bold(m.authorName, ": " + m.text),
                            sub = ago(m.timestampMs),
                            subColor = if (unread) FaroColors.Accent else FaroColors.Muted,
                            dim = !unread,
                        ) {
                            Chip("Responder", primary = true) { nav.go(Route.Group(m.groupId)) }
                            Chip("Ver en el mapa") { nav.showPerson(m.authorId) }
                        }
                    }
                }
            }
        }
        }

        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!sosOn && groups.isNotEmpty()) Text(
                if (okSent) "Enviado ✓" else "Estoy bien",
                color = FaroColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).border(1.5.dp, FaroColors.Accent, RoundedCornerShape(50))
                    .clickable(enabled = !okSent) { FaroGroups.sendImOk(); okSent = true }.padding(vertical = 15.dp),
            )
            Text(
                if (sosOn) "Has pedido ayuda · ver" else "Pedir ayuda",
                color = FaroColors.Danger,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(50)).border(1.5.dp, FaroColors.Danger, RoundedCornerShape(50))
                    .clickable { if (sosOn) onShowSos() else confirmSos = true }.padding(vertical = 15.dp),
            )
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            containerColor = FaroColors.Card,
            title = { Text("¿Pedir ayuda?") },
            text = { Text("Se enviará tu ubicación y el aviso «Necesito ayuda» a todos tus grupos, aunque tengas la ubicación desactivada.") },
            confirmButton = {
                TextButton(onClick = { confirmSos = false; FaroLocation.sendSos(true) }) {
                    Text("Sí, pedir ayuda", color = FaroColors.Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancelar") } },
        )
    }
}

/** Aviso de quién ha visto dónde estás, juntando a varias personas en uno. */
@Composable
private fun SeenCard(open: MutableList<String>, nav: FaroNav) {
    val seen by FaroGroups.seenBy.collectAsStateWithLifecycle()
    val since = System.currentTimeMillis() - 24 * HOUR
    val list = seen.filter { it.timestampMs > since }.sortedByDescending { it.timestampMs }
    if (list.isEmpty()) return
    val names = list.map { it.viewerName }.distinct()
    val who = when (names.size) {
        1 -> names[0]
        2 -> names[0] + " y " + names[1]
        else -> names.take(2).joinToString(", ") + " y ${names.size - 2} más"
    }
    val key = "visto"
    val isOpen = key in open
    Column(
        Modifier.fillMaxWidth().card().clickable { if (isOpen) open.remove(key) else open.add(key) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).border(1.5.dp, FaroColors.Line2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Visibility, null, tint = FaroColors.Accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(bold(who, if (names.size == 1) " ha visto dónde estás" else " han visto dónde estás"), fontSize = 14.5.sp, lineHeight = 19.sp)
                Text(ago(list.first().timestampMs), fontSize = 12.sp, color = FaroColors.Accent, fontWeight = FontWeight.SemiBold)
            }
            Icon(if (isOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = FaroColors.Muted, modifier = Modifier.size(20.dp))
        }
        if (isOpen) {
            Gap(8.dp)
            list.forEach { e ->
                val how = when (e.how) {
                    com.bitchat.android.faro.FaroProtocol.SeenHow.MAP -> "en el mapa"
                    com.bitchat.android.faro.FaroProtocol.SeenHow.CARD -> "en su ficha"
                    com.bitchat.android.faro.FaroProtocol.SeenHow.LIVE -> "en directo"
                    com.bitchat.android.faro.FaroProtocol.SeenHow.GUIDE -> "con la brújula"
                }
                Text("${e.viewerName} · $how · ${ago(e.timestampMs)}", fontSize = 13.sp, color = Color(0xFFBDBDBD), modifier = Modifier.padding(start = 44.dp, top = 2.dp))
            }
            Gap(10.dp)
            Row(Modifier.padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val gid = list.first().groupId
                Chip("Dejar de compartir") { nav.go(Route.Info(gid)) }
                Chip("Abrir chat") { nav.go(Route.Group(gid)) }
            }
        }
    }
}

private fun bold(name: String, rest: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
    append(rest)
}

private fun distanceSuffix(me: android.location.Location?, p: Person, prefix: String = "a "): String {
    val l = p.location() ?: return ""
    val m = me ?: return ""
    return " · " + prefix + formatDistance(m.distanceTo(l))
}

/** Tarjeta de Inicio: una línea con su hora y, al tocarla, sus acciones. */
@Composable
private fun InfoCardRow(
    key: String,
    open: MutableList<String>,
    border: Color,
    leading: @Composable () -> Unit,
    title: androidx.compose.ui.text.AnnotatedString,
    sub: String,
    subColor: Color = FaroColors.Muted,
    dim: Boolean = false,
    alwaysOpen: Boolean = false,
    actions: @Composable () -> Unit,
) {
    val isOpen = alwaysOpen || key in open
    Column(
        Modifier.fillMaxWidth().card(if (alwaysOpen) border else FaroColors.Line)
            .clickable(enabled = !alwaysOpen) { if (key in open) open.remove(key) else open.add(key) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(border))
            Spacer(Modifier.width(9.dp))
            leading()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 14.5.sp, lineHeight = 19.sp,
                    color = if (dim) Color(0xFFBDBDBD) else MaterialTheme.colorScheme.onBackground,
                    maxLines = if (isOpen) 6 else 1, overflow = TextOverflow.Ellipsis,
                )
                Text(sub, fontSize = 12.sp, color = subColor, fontWeight = if (subColor == FaroColors.Accent) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (!alwaysOpen) Icon(
                if (isOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null,
                tint = FaroColors.Muted, modifier = Modifier.size(20.dp),
            )
        }
        if (isOpen) {
            Gap(10.dp)
            Row(Modifier.padding(start = 56.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
private fun RequestCard(r: JoinRequest, groupName: String) {
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 12.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(FaroColors.Accent))
            Spacer(Modifier.width(9.dp))
            Avatar(r.memberName, colorFor(r.memberKeyHex), 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(bold(r.memberName, " quiere entrar en $groupName"), fontSize = 14.5.sp, lineHeight = 19.sp)
                Text(ago(r.timestampMs) + " · con tu invitación", fontSize = 12.sp, color = FaroColors.Muted)
            }
        }
        Gap(10.dp)
        Row(Modifier.padding(start = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Sí, dejarle entrar", primary = true) { FaroGroups.approve(r) }
            Chip("No") { FaroGroups.reject(r) }
        }
    }
}

// ---------------------------------------------------------------- Chats

@Composable
private fun ChatsTab(nav: FaroNav, chatViewModel: ChatViewModel, onSettings: () -> Unit) {
    var nearby by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        MainHeader(onSettings)
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(50)).border(1.5.dp, FaroColors.Line2, RoundedCornerShape(50)).padding(3.dp),
        ) {
            listOf(false to "Mis chats", true to "Cerca").forEach { (v, label) ->
                val on = nearby == v
                Text(
                    label, textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (on) FaroColors.AccentInk else FaroColors.Muted,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(if (on) FaroColors.Accent else Color.Transparent)
                        .clickable { nearby = v }.padding(vertical = 8.dp),
                )
            }
        }
        if (nearby) NearbyPanel(nav, chatViewModel) else MyChats(nav)
    }
}

@Composable
private fun MyChats(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val messages by FaroGroups.messages.collectAsStateWithLifecycle()
    val lastRead by FaroGroups.lastRead.collectAsStateWithLifecycle()
    val waiting by FaroJoin.waiting.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        groups.sortedByDescending { g -> messages[g.id]?.lastOrNull()?.timestampMs ?: g.createdAtMs }.forEach { g ->
            val last = messages[g.id]?.lastOrNull()
            val unread = last != null && !last.mine && last.timestampMs > (lastRead[g.id] ?: 0L)
            Row(
                Modifier.fillMaxWidth().clickable { nav.go(Route.Group(g.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupIcon(groupColorHex(groups, g.id))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        last?.let { (if (it.mine) "Tú: " else it.authorName + ": ") + it.text } ?: personas(g.members.size + 1),
                        fontSize = 13.sp, color = if (unread) MaterialTheme.colorScheme.onBackground else FaroColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (last != null) Text(
                    ago(last.timestampMs).removePrefix("hace "), fontSize = 12.sp,
                    color = if (unread) FaroColors.Accent else FaroColors.Muted,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Top).padding(top = 3.dp, start = 8.dp),
                )
            }
        }
        waiting.forEach { name ->
            Hint("Esperando a que te dejen entrar en «$name»…", Modifier.padding(vertical = 8.dp))
        }
        Gap(8.dp)
        Hint("Tus grupos son privados: solo entra quien el administrador acepte. Los mensajes van cifrados.")
        Gap(16.dp)
    }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinePill("Crear un grupo", Modifier.weight(1f)) { nav.go(Route.Create) }
            OutlinePill("Unirme", Modifier.weight(1f), color = Color(0xFFBDBDBD)) { nav.go(Route.Scan) }
        }
    }
}

@Composable
private fun NearbyPanel(nav: FaroNav, chatViewModel: ChatViewModel) {
    val peers by chatViewModel.connectedPeers.collectAsStateWithLifecycle()
    val n = peers.count { it != chatViewModel.myPeerID }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(84.dp).border(1.5.dp, FaroColors.Line2, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.WifiTethering, null, tint = FaroColors.Accent, modifier = Modifier.size(40.dp)) }
            Gap(14.dp)
            Text("Gente cerca", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Gap(8.dp)
            Text(
                when (n) {
                    0 -> "Ahora no hay nadie con Faro a tu alrededor. Es una sala abierta para pedir o dar ayuda a quien esté cerca."
                    1 -> "Hay 1 persona con Faro a tu alrededor. Es una sala abierta para pedir o dar ayuda a quien esté cerca."
                    else -> "Hay $n personas con Faro a tu alrededor. Es una sala abierta para pedir o dar ayuda a quien esté cerca."
                },
                fontSize = 14.5.sp, lineHeight = 21.sp, color = Color(0xFFBDBDBD), textAlign = TextAlign.Center,
            )
        }
        FilledPill("Entrar en la sala", Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { nav.go(Route.Nearby) }
        Row(
            Modifier.padding(16.dp).fillMaxWidth().card(FaroColors.Line2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, null, tint = FaroColors.Warn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Lo que escribas aquí lo puede leer cualquiera que esté cerca. Úsala solo en emergencias.",
                fontSize = 13.5.sp, lineHeight = 19.sp, color = Color(0xFFD6D6D6),
            )
        }
    }
}

// ---------------------------------------------------------------- Pedir ayuda activo

@Composable
private fun SosActiveScreen(onOpenChat: (String) -> Unit) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(FaroColors.Danger.copy(alpha = 0.18f), Color.Transparent), radius = 900f,
                )
            )
            .statusBarsPadding().navigationBarsPadding()
            .clickable(enabled = false) {},
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.WarningAmber, null, tint = FaroColors.Danger, modifier = Modifier.size(52.dp))
            Gap(14.dp)
            Text("Has pedido ayuda", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = FaroColors.Danger)
            Gap(10.dp)
            Text(
                "Tu familia ve dónde estás. Tu ubicación se envía cada 2 minutos hasta que avises de que estás bien.",
                fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFBDBDBD), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            OutlinePill("Ya estoy bien", Modifier.fillMaxWidth(), color = FaroColors.Danger, height = 54.dp) {
                FaroLocation.sendSos(false)
            }
            groups.sortedBy { it.createdAtMs }.firstOrNull()?.let { g ->
                Gap(6.dp)
                TextButton(onClick = { onOpenChat(g.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir chat de ${g.name}", color = Color(0xFFBDBDBD), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
