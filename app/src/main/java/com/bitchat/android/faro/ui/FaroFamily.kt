package com.bitchat.android.faro.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroProtocol

// ---------------------------------------------------------------- Estado de cada persona

/** Lugar de sus grupos en el que está ahora una persona, si está en alguno. */
internal fun placeOf(p: Person, places: Map<String, List<FaroProtocol.Place>>): FaroProtocol.Place? {
    val l = p.location() ?: return null
    return p.groupIds.flatMap { places[it].orEmpty() }.firstOrNull { pl ->
        l.distanceTo(Location("faro").apply { latitude = pl.lat; longitude = pl.lon }) <= pl.radiusM + (p.pos?.position?.accuracyM ?: 0).coerceAtMost(100)
    }
}

internal fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

// ---------------------------------------------------------------- Lugares

@Composable
internal fun PlacesScreen(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val places by FaroGroups.places.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Pair<String, FaroProtocol.Place>?>(null) }
    val ppl = people(groups, positions, FaroGroups.myId())
    BackBar("Lugares", onBack = { nav.back() })
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Os avisaremos cuando alguien llegue o salga de estos sitios.",
                fontSize = 13.5.sp, color = FaroColors.Muted, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val all = groups.sortedBy { it.createdAtMs }.flatMap { g -> places[g.id].orEmpty().map { g to it } }
            if (all.isEmpty()) Hint("Aún no hay lugares. Añade Casa, el cole o el trabajo.", Modifier.padding(vertical = 16.dp))
            all.forEach { (g, pl) ->
                val here = ppl.filter { g.id in it.groupIds && placeOf(it, mapOf(g.id to listOf(pl))) != null }.map { it.name }
                Row(
                    Modifier.fillMaxWidth().clickable { toDelete = g.id to pl }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).border(1.5.dp, FaroColors.Line2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (pl.name.contains("casa", true)) Icons.Outlined.Home else Icons.Outlined.Place, null, tint = FaroColors.Accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (groups.size > 1) g.name + " · " else "") +
                                when (here.size) { 0 -> "Nadie ahora"; 1 -> "${here[0]} está aquí"; else -> here.joinToString(" y ") + " están aquí" },
                            fontSize = 12.5.sp, color = FaroColors.Muted,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = FaroColors.Dim)
                }
            }
            Gap(10.dp)
            Hint("Tu móvil comprueba si llegas o sales con la ubicación que ya usa para tu familia, sin gastar batería de más. Nadie fuera del grupo ve vuestros lugares.")
        }
        OutlinePill("Añadir un lugar", Modifier.padding(16.dp).fillMaxWidth(), height = 50.dp) { nav.go(Route.AddPlace) }
    }
    toDelete?.let { (gid, pl) ->
        var enter by remember(pl.id) { mutableStateOf(com.bitchat.android.faro.FaroSettings.placeNotify(pl.id, true)) }
        var leave by remember(pl.id) { mutableStateOf(com.bitchat.android.faro.FaroSettings.placeNotify(pl.id, false)) }
        AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = FaroColors.Card,
            title = { Text(pl.name) },
            text = {
                Column {
                    SwitchRow("Avisarme cuando alguien llegue", enter) { enter = it; com.bitchat.android.faro.FaroSettings.setPlaceNotify(pl.id, true, it) }
                    SwitchRow("Avisarme cuando alguien salga", leave) { leave = it; com.bitchat.android.faro.FaroSettings.setPlaceNotify(pl.id, false, it) }
                }
            },
            confirmButton = { TextButton(onClick = { toDelete = null }) { Text("Listo") } },
            dismissButton = { TextButton(onClick = { FaroGroups.deletePlace(gid, pl); toDelete = null }) { Text("Borrar lugar", color = FaroColors.Danger) } },
        )
    }
}

@Composable
internal fun AddPlaceScreen(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    val handle = remember { MapHandle() }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(200) }
    var groupId by remember { mutableStateOf(groups.minByOrNull { it.createdAtMs }?.id) }
    var onEnter by remember { mutableStateOf(true) }
    var onLeave by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { FaroLocation.refreshForDisplay() }
    BackBar("Añadir un lugar", onBack = { nav.back() })
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            FaroMapView(
                points = listOfNotNull(me?.let { MapPoint("yo", "Tú", it.latitude, it.longitude, "#A7C7E7", true) }),
                modifier = Modifier.fillMaxSize(), handle = handle,
            )
            // El lugar es el centro del mapa: se mueve el mapa para colocarlo.
            Box(Modifier.align(Alignment.Center).size(18.dp).clip(CircleShape).background(FaroColors.Accent).border(3.dp, Color.Black, CircleShape))
            Text(
                "Mueve el mapa para colocar el punto", fontSize = 12.sp, color = Color(0xFFBDBDBD),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                    .background(FaroColors.Card).padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nombre", fontSize = 13.5.sp, color = FaroColors.Muted)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                placeholder = { Text("Por ejemplo, Casa") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = FaroColors.Line2, focusedBorderColor = FaroColors.Accent),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Casa", "Cole", "Trabajo").forEach { s -> Chip(s, primary = name == s) { name = s } }
            }
            Text("Tamaño", fontSize = 13.5.sp, color = FaroColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 500).forEach { r -> Chip("$r m", primary = radius == r) { radius = r } }
            }
            if (groups.size > 1) {
                Text("Para el grupo", fontSize = 13.5.sp, color = FaroColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.sortedBy { it.createdAtMs }.forEach { g -> Chip(g.name, primary = groupId == g.id) { groupId = g.id } }
                }
            }
            Column(Modifier.fillMaxWidth().card()) {
                SwitchRow("Avisarme cuando alguien llegue", onEnter) { onEnter = it }
                SwitchRow("Avisarme cuando alguien salga", onLeave) { onLeave = it }
            }
        }
        FilledPill("Guardar lugar", Modifier.padding(16.dp).fillMaxWidth(), enabled = name.isNotBlank() && groupId != null) {
            val c = handle.center() ?: me?.let { it.latitude to it.longitude } ?: return@FilledPill
            val place = FaroProtocol.Place(FaroProtocol.newPlaceId(), name.trim(), c.first, c.second, radius)
            com.bitchat.android.faro.FaroSettings.setPlaceNotify(place.id, true, onEnter)
            com.bitchat.android.faro.FaroSettings.setPlaceNotify(place.id, false, onLeave)
            FaroGroups.savePlace(groupId!!, place)
            nav.back()
        }
    }
}

// ---------------------------------------------------------------- Voy para allá

@Composable
internal fun TripScreen(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val places by FaroGroups.places.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(FaroProtocol.TravelMode.WALK) }
    var chosen by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { FaroLocation.refreshForDisplay() }

    data class Dest(val name: String, val sub: String, val lat: Double, val lon: Double)
    fun dist(lat: Double, lon: Double): String =
        me?.let { formatDistance(it.distanceTo(Location("faro").apply { latitude = lat; longitude = lon })) } ?: ""
    val dests = groups.flatMap { places[it.id].orEmpty() }.distinctBy { it.id }.map { Dest(it.name, dist(it.lat, it.lon), it.lat, it.lon) } +
        people(groups, positions, FaroGroups.myId()).mapNotNull { p ->
            val pos = p.pos ?: return@mapNotNull null
            Dest("Donde está ${p.name}", dist(pos.position.lat, pos.position.lon), pos.position.lat, pos.position.lon)
        }

    BackBar("Voy para allá", onBack = { nav.back() })
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Tu familia verá por dónde vas y a qué hora llegas. Se apaga solo al llegar.",
                fontSize = 13.5.sp, color = FaroColors.Muted, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (dests.isEmpty()) Hint("Añade un lugar (Casa, el cole…) o espera a tener la ubicación de tu familia.", Modifier.padding(vertical = 12.dp))
            dests.forEachIndexed { i, d ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
                        .card(if (i == chosen) FaroColors.Accent else FaroColors.Line).clickable { chosen = i }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).border(if (i == chosen) 6.dp else 1.5.dp, if (i == chosen) FaroColors.Accent else Color(0xFF555555), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (d.sub.isNotBlank()) Text(d.sub, fontSize = 12.5.sp, color = FaroColors.Muted)
                    }
                }
            }
            SectionTitle("¿Cómo vas?")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FaroProtocol.TravelMode.WALK to "Andando", FaroProtocol.TravelMode.CAR to "En coche", FaroProtocol.TravelMode.BUS to "En bus")
                    .forEach { (m, t) -> Chip(t, primary = mode == m) { mode = m } }
            }
            Gap(14.dp)
            Hint("Mientras vas, tu ubicación se envía cada 2 minutos; al llegar vuelve a su ritmo normal.")
        }
        FilledPill("Avisar a mi familia", Modifier.padding(16.dp).fillMaxWidth(), enabled = dests.isNotEmpty()) {
            val d = dests.getOrNull(chosen) ?: return@FilledPill
            FaroLocation.startTrip(d.name.removePrefix("Donde está "), d.lat, d.lon, mode)
            nav.back()
        }
    }
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = on, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = FaroColors.Accent, checkedThumbColor = FaroColors.AccentInk),
        )
    }
}

// ---------------------------------------------------------------- Recorrido de hoy

@Composable
internal fun HistoryScreen(nav: FaroNav, personId: String) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val history by FaroGroups.history.collectAsStateWithLifecycle()
    val p = people(groups, positions, FaroGroups.myId()).firstOrNull { it.id == personId }
    val since = System.currentTimeMillis() - 24 * HOUR
    val pts = history[personId].orEmpty().filter { it.first > since }
    val trail = pts.map { it.second to it.third }
    var km = 0f
    pts.zipWithNext().forEach { (a, b) ->
        val r = FloatArray(1)
        Location.distanceBetween(a.second, a.third, b.second, b.third, r)
        km += r[0] / 1000f
    }
    BackBar("Recorrido de ${p?.name ?: ""}", onBack = { nav.back() })
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FaroMapView(
                points = listOfNotNull(pts.lastOrNull()?.let { MapPoint(personId, p?.name ?: "", it.second, it.third, colorFor(personId), false) }),
                modifier = Modifier.fillMaxSize(), trail = trail,
            )
        }
        Column(Modifier.padding(16.dp)) {
            if (pts.size < 2) {
                Text("Aún no hay recorrido de hoy.", fontSize = 15.sp)
                Text("Se va guardando con cada ubicación que llega y se borra a las 24 h.", fontSize = 13.sp, color = FaroColors.Muted)
            } else {
                Text("Desde las ${hhmm(pts.first().first)} hasta las ${hhmm(pts.last().first)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Unos ${"%.1f".format(km).replace('.', ',')} km · ${pts.size} ubicaciones. Solo se guarda en tu móvil y se borra a las 24 h.",
                    fontSize = 13.sp, color = FaroColors.Muted, lineHeight = 18.sp,
                )
            }
        }
    }
}
