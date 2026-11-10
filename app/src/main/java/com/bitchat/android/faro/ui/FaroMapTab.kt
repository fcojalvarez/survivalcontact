package com.bitchat.android.faro.ui

import android.Manifest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.faro.FaroGroups
import com.bitchat.android.faro.FaroLocation
import com.bitchat.android.faro.FaroOfflineMap
import com.bitchat.android.faro.FaroProtocol
import com.bitchat.android.ui.rememberIsOnline
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/** Pestaña Mapa: la familia en el mapa, filtro por grupo y la lista con distancia y dirección. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun MapTab(nav: FaroNav) {
    val groups by FaroGroups.groups.collectAsStateWithLifecycle()
    val positions by FaroGroups.positions.collectAsStateWithLifecycle()
    val me by FaroLocation.myLocation.collectAsStateWithLifecycle()
    val offline by FaroOfflineMap.state.collectAsStateWithLifecycle()
    val online = rememberIsOnline()
    val perms = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) { FaroLocation.refreshForDisplay() }
    val handle = remember { MapHandle() }
    val myTrip by FaroLocation.trip.collectAsStateWithLifecycle()

    val filter = nav.mapFilter?.takeIf { f -> groups.any { it.id == f } }
    val shown = groups.filter { filter == null || it.id == filter }
    val ppl = people(shown, positions, FaroGroups.myId())
    val located = ppl.filter { it.pos != null }
    val points = located.map { p ->
        MapPoint(p.id, p.name, p.pos!!.position.lat, p.pos.position.lon, colorFor(p.id), false)
    } + listOfNotNull(me?.let { MapPoint("yo", "Tú", it.latitude, it.longitude, "#A7C7E7", true) })

    // Al mirar el mapa con internet, se avisa a quien aparece (como mucho cada 15 min por persona).
    val locatedIds = located.map { it.id }
    LaunchedEffect(locatedIds) { if (locatedIds.isNotEmpty()) FaroGroups.notifySeen(locatedIds, FaroProtocol.SeenHow.MAP) }

    val following = nav.following
    if (following != null) {
        FollowView(nav, following, ppl.firstOrNull { it.id == following.personId }, points, handle)
        return
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FaroMapView(points = points, modifier = Modifier.fillMaxSize(), handle = handle, onPointClick = { nav.selected = it })
            if (myTrip == null && groups.isNotEmpty()) Text(
                "Voy para allá", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FaroColors.AccentInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(50))
                    .background(FaroColors.Accent).clickable { nav.go(Route.Trip) }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapChip("Todos", null, filter == null) { nav.mapFilter = null }
                    groups.sortedBy { it.createdAtMs }.forEach { g ->
                        MapChip(g.name, groupColorHex(groups, g.id), filter == g.id) { nav.mapFilter = g.id }
                    }
                }
                val status = when {
                    online -> "● Mapa en línea"
                    offline is FaroOfflineMap.State.Ready -> "● Mapa guardado · sin internet"
                    else -> "● Sin internet · sin mapa guardado"
                }
                myTrip?.let { t ->
                    Text(
                        "Vas a ${t.destName} · llegas ${hhmm(t.etaMs)} · Parar", fontSize = 12.5.sp, color = FaroColors.Ok,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(FaroColors.Card)
                            .border(1.5.dp, FaroColors.Ok, RoundedCornerShape(50)).clickable { FaroLocation.stopTrip() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
                Text(
                    status, fontSize = 12.sp, color = Color(0xFFBDBDBD),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(FaroColors.Card)
                        .border(1.5.dp, FaroColors.Line2, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().background(FaroColors.Line).padding(top = 1.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            if (!perms.allPermissionsGranted) {
                Row(
                    Modifier.fillMaxWidth().clickable { perms.launchMultiplePermissionRequest() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Permite la ubicación para verte en el mapa y saber a qué distancia están.", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("Permitir", color = FaroColors.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
            }
            if (ppl.isEmpty()) {
                Hint(
                    if (groups.isEmpty()) "Crea un grupo o únete a uno para ver aquí a tu familia."
                    else "Aún no ha llegado la ubicación de nadie. Aparecerán cuando la compartan y estéis conectados.",
                    Modifier.padding(vertical = 12.dp),
                )
            }
            ppl.sortedWith(compareBy({ it.pos == null }, { it.name })).forEach { p ->
                val loc = p.location()
                val dist = me?.let { m -> loc?.let { m.distanceTo(it) } }
                val bearing = me?.let { m -> loc?.let { m.bearingTo(it) } }
                Row(Modifier.fillMaxWidth().clickable { nav.selected = p.id }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.name, colorFor(p.id), 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val gname = groups.firstOrNull { it.id == p.groupIds.first() }?.name.orEmpty()
                        val (txt, c) = when {
                            p.needsHelp -> "Necesita ayuda · ${ago(p.pos!!.timestampMs)}" to FaroColors.Danger
                            p.pos == null -> "$gname · sin ubicación" to FaroColors.Muted
                            p.silent -> "$gname · sin noticias · ${hoursText(p.silentFor ?: 0L)}" to FaroColors.Warn
                            else -> "$gname · ${ago(p.pos.timestampMs)}" to FaroColors.Muted
                        }
                        Text(txt, fontSize = 12.5.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (dist != null && bearing != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatDistance(dist), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            // Sin brújula aquí (ahorra batería): solo la dirección en palabras.
                            Text("al " + bearingWord(bearing), fontSize = 12.sp, color = FaroColors.Muted)
                        }
                    }
                }
            }
        }
    }
    val sel = nav.selected?.let { id -> ppl.firstOrNull { it.id == id } ?: people(groups, positions, FaroGroups.myId()).firstOrNull { it.id == id } }
    if (sel != null) PersonSheet(nav, sel, handle, onClose = { nav.selected = null })
    }
}

@Composable
private fun MapChip(text: String, colorHex: String?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(FaroColors.Card)
            .border(1.5.dp, if (on) FaroColors.Accent else FaroColors.Line2, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (colorHex != null) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(hex(colorHex)))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, color = if (on) FaroColors.Accent else Color(0xFFD6D6D6), maxLines = 1)
    }
}
