package com.bitchat.android.faro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroProtocol
import kotlinx.coroutines.delay

private const val FOLLOW_MINUTES = 10

// ---------------------------------------------------------------- Ficha de una persona

@Composable
internal fun PersonSheet(nav: FaroNav, p: Person, handle: MapHandle, onClose: () -> Unit) {
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    var street by remember(p.id) { mutableStateOf<String?>(null) }
    var looked by remember(p.id) { mutableStateOf(false) }
    val pos = p.pos

    LaunchedEffect(p.id) {
        FaroGroups.notifySeen(listOf(p.id), FaroProtocol.SeenHow.CARD)
        if (pos != null) {
            handle.focus(pos.position.lat, pos.position.lon) {
                street = handle.streetNear(pos.position.lat, pos.position.lon)
                looked = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(FaroColors.Card)
                .border(1.dp, FaroColors.Line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        ) {
            Box(Modifier.padding(vertical = 10.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF333333)).align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name, colorFor(p.id), 56.dp, if (p.needsHelp) FaroColors.Danger else if (p.silent) FaroColors.Warn else FaroColors.Accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    if (pos != null) StreetLine(street ?: if (looked) "Calle no disponible en el mapa" else "Buscando la calle…")
                }
            }
            if (pos == null) {
                Gap(14.dp)
                Text("Todavía no ha compartido su ubicación.", fontSize = 14.sp, color = FaroColors.Muted)
            } else {
                val loc = p.location()
                val dist = me?.let { m -> loc?.let { m.distanceTo(it) } }
                val bearing = me?.let { m -> loc?.let { m.bearingTo(it) } }
                Gap(14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(dist?.let(::formatDistance) ?: "—", bearing?.let { "al " + bearingWord(it) } ?: "distancia", Modifier.weight(1f))
                    Stat(ago(pos.timestampMs).removePrefix("hace "), "última posición", Modifier.weight(1f))
                    Stat("${pos.position.batteryPct} %", "batería", Modifier.weight(1f), warn = pos.position.batteryPct < 20)
                }
            }
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinePill("Abrir chat", Modifier.weight(1f)) { onClose(); nav.go(Route.Group(p.groupIds.first())) }
                if (pos != null) FilledPill("Guiarme", Modifier.weight(1f)) { onClose(); nav.go(Route.Guide(p.id)) }
            }
            val asks by FaroGroups.asks.collectAsStateWithLifecycle()
            Gap(10.dp)
            OutlinePill(
                if (p.id in asks) "Preguntado · esperando respuesta" else "Preguntar si está bien",
                Modifier.fillMaxWidth(),
            ) { FaroGroups.askIfOk(p.id) }
            if (pos != null) {
                if (pos.position.batteryPct >= 20) {
                    Gap(4.dp)
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(50))
                            .clickable {
                                FaroGroups.sendFollow(p.id, FOLLOW_MINUTES)
                                FaroGroups.notifySeen(listOf(p.id), FaroProtocol.SeenHow.LIVE)
                                nav.following = Following(p.id, System.currentTimeMillis() + FOLLOW_MINUTES * 60_000L)
                                onClose()
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Visibility, null, tint = FaroColors.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Seguir en directo", color = FaroColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Gap(12.dp)
                    Text(
                        "No se puede seguir en directo: le queda poca batería. Verás su ubicación cuando la envíe.",
                        fontSize = 12.5.sp, lineHeight = 18.sp, color = FaroColors.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreetLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Place, null, tint = FaroColors.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 13.5.sp, color = Color(0xFFD6D6D6), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, warn: Boolean = false) {
    Column(modifier.card(if (warn) FaroColors.Warn else FaroColors.Line).padding(10.dp)) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (warn) FaroColors.Warn else Color(0xFFF2F2F2), maxLines = 1)
        Text(label, fontSize = 12.sp, color = FaroColors.Muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Seguir en directo

@Composable
internal fun FollowView(nav: FaroNav, f: Following, p: Person?, points: List<MapPoint>, handle: MapHandle) {
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var street by remember(f.personId) { mutableStateOf<String?>(null) }
    val pos = p?.pos
    // Recorrido mientras se le sigue: cada posición nueva se añade a la línea.
    val trail = remember(f.personId) { androidx.compose.runtime.mutableStateListOf<Pair<Double, Double>>() }
    LaunchedEffect(pos?.timestampMs) {
        if (pos != null) {
            val pt = pos.position.lat to pos.position.lon
            if (trail.lastOrNull() != pt) trail.add(pt)
        }
    }

    fun stop() {
        FaroGroups.sendFollow(f.personId, 0)
        nav.following = null
    }

    LaunchedEffect(f) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= f.untilMs) { nav.following = null; break }
            delay(1000)
        }
    }
    LaunchedEffect(pos?.timestampMs) {
        if (pos != null) handle.focus(pos.position.lat, pos.position.lon, 16.5) {
            street = handle.streetNear(pos.position.lat, pos.position.lon)
        }
    }

    Box(Modifier.fillMaxSize()) {
        FaroMapView(points = points, modifier = Modifier.fillMaxSize(), handle = handle, trail = trail.toList())
        val left = ((f.untilMs - now) / 60_000 + 1).coerceIn(1, FOLLOW_MINUTES.toLong())
        Row(
            Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(FaroColors.Card)
                .border(1.5.dp, FaroColors.Line2, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(FaroColors.Ok))
            Spacer(Modifier.width(6.dp))
            Text("En directo", color = FaroColors.Ok, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            Text(" · quedan $left min", color = Color(0xFFBDBDBD), fontSize = 12.5.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(FaroColors.Card)
                .border(1.5.dp, FaroColors.Line2, RoundedCornerShape(20.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p?.name ?: "?", colorFor(f.personId), 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.name ?: "", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val secs = pos?.let { ((now - it.timestampMs) / 1000).coerceAtLeast(0) }
                    val whenTxt = when {
                        secs == null -> "esperando su ubicación…"
                        secs < 60 -> "hace $secs s"
                        else -> ago(pos?.timestampMs ?: now)
                    }
                    StreetLine(listOfNotNull(street?.substringBefore(","), whenTxt).joinToString(" · "))
                }
                val loc = p?.location()
                val dist = me?.let { m -> loc?.let { m.distanceTo(it) } }
                val bearing = me?.let { m -> loc?.let { m.bearingTo(it) } }
                if (dist != null && bearing != null) Column(horizontalAlignment = Alignment.End) {
                    Text(formatDistance(dist), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(bearingWord(bearing), fontSize = 12.sp, color = FaroColors.Muted)
                }
            }
            Gap(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinePill("Parar", Modifier.weight(1f)) { stop() }
                FilledPill("Guiarme", Modifier.weight(1f)) { nav.go(Route.Guide(f.personId)) }
            }
        }
    }
}

// ---------------------------------------------------------------- Guiarme (brújula)

/**
 * Brújula a pantalla completa hacia una persona. Mientras está abierta usa el sensor de
 * orientación y pide mi posición cada 15 s; al cerrarla se apaga todo.
 */
@Composable
internal fun GuideScreen(nav: FaroNav, personId: String) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    val heading = rememberHeading()
    val p = people(groups, positions, FaroGroups.myId()).firstOrNull { it.id == personId }

    LaunchedEffect(personId) {
        FaroGroups.notifySeen(listOf(personId), FaroProtocol.SeenHow.GUIDE)
        while (true) {
            FaroLocation.refreshForDisplay()
            delay(15_000)
        }
    }

    BackBar("Hacia ${p?.name ?: ""}", onBack = { nav.back() })
    val loc = p?.location()
    val dist = me?.let { m -> loc?.let { m.distanceTo(it) } }
    val bearing = me?.let { m -> loc?.let { m.bearingTo(it) } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(250.dp).border(1.5.dp, FaroColors.Line, CircleShape), contentAlignment = Alignment.Center) {
            Text("N", color = FaroColors.Dim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            if (bearing != null) {
                Icon(
                    Icons.Filled.Navigation, contentDescription = "Dirección", tint = FaroColors.Accent,
                    modifier = Modifier.size(130.dp).rotate(bearing - (heading ?: 0f)),
                )
            }
        }
        Gap(26.dp)
        Text(dist?.let(::formatDistance) ?: "—", fontSize = 52.sp, fontWeight = FontWeight.Bold)
        Text(
            when {
                p?.pos == null -> "Aún no sabemos dónde está"
                me == null -> "Buscando tu posición…"
                bearing != null -> "sigue la flecha · al ${bearingWord(bearing)}"
                else -> ""
            },
            fontSize = 15.sp, color = FaroColors.Muted,
        )
        Gap(30.dp)
        p?.pos?.let {
            Text("Su posición es de ${ago(it.timestampMs)}. Gira el móvil: la flecha apunta hacia ${p.name}.",
                fontSize = 12.5.sp, color = FaroColors.Dim, textAlign = TextAlign.Center)
        }
    }
}
