package com.bitchat.android.faro.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.faro.FaroGroup
import com.bitchat.android.faro.FaroProtocol
import com.bitchat.android.faro.MemberPosition
import kotlin.math.abs
import kotlin.math.roundToInt

// Piezas comunes del diseño de Faro: fondo negro, todo con borde y colores pastel.

internal object FaroColors {
    val Accent = Color(0xFFA7C7E7)
    val AccentInk = Color(0xFF0B1B2B)
    val Line = Color(0xFF26262A)
    val Line2 = Color(0xFF2E2E33)
    val Card = Color(0xFF0D0D0F)
    val Muted = Color(0xFF9A9A9A)
    val Dim = Color(0xFF6B6B6B)
    val Danger = Color(0xFFFF6B5E)
    val Warn = Color(0xFFFFB14A)
    val Ok = Color(0xFF8FE3A8)
}

/** Colores pastel para distinguir a cada persona. */
private val PASTELS = listOf("#FFB4A8", "#B4F08C", "#D7C2FF", "#FFE08A", "#8FE3E0", "#F5B8DA", "#C9E4A7", "#F7C59F")

/** Colores de los grupos, en el orden en que se crearon. */
private val GROUP_COLORS = listOf("#A7C7E7", "#C9A7E7", "#8FE3A8", "#FFE08A", "#FFB4A8", "#8FE3E0")

internal fun colorFor(id: String): String = PASTELS[abs(id.hashCode()) % PASTELS.size]

internal fun hex(c: String): Color = Color(android.graphics.Color.parseColor(c))

internal fun groupColorHex(groups: List<FaroGroup>, id: String): String {
    val i = groups.sortedBy { it.createdAtMs }.indexOfFirst { it.id == id }
    return GROUP_COLORS[(if (i < 0) abs(id.hashCode()) else i) % GROUP_COLORS.size]
}

/** Persona de mis grupos, con su última posición conocida (de cualquiera de los grupos). */
internal data class Person(
    val id: String,
    val name: String,
    val groupIds: List<String>,
    val pos: MemberPosition?,
) {
    val needsHelp: Boolean
        get() {
            val p = pos ?: return false
            return p.position.status == FaroProtocol.Position.Status.NEED_HELP &&
                System.currentTimeMillis() - p.timestampMs < 12 * HOUR
        }
    /** Avisó de que se quedaba sin batería (y no ha vuelto a dar señales con batería). */
    val batteryDying: Boolean
        get() {
            val p = pos ?: return false
            return p.position.status == FaroProtocol.Position.Status.BATTERY_DYING &&
                System.currentTimeMillis() - p.timestampMs < 24 * HOUR
        }
    val silentFor: Long? get() = pos?.let { System.currentTimeMillis() - it.timestampMs }
    val silent: Boolean get() = (silentFor ?: 0L) > 3 * HOUR
}

internal const val HOUR = 3_600_000L

internal fun people(groups: List<FaroGroup>, positions: Map<String, Map<String, MemberPosition>>, myId: String?): List<Person> {
    val ids = LinkedHashMap<String, MutableList<String>>()
    val names = HashMap<String, String>()
    groups.sortedBy { it.createdAtMs }.forEach { g ->
        g.members.forEach { (id, name) -> ids.getOrPut(id) { mutableListOf() }.add(g.id); names[id] = name }
        positions[g.id].orEmpty().forEach { (id, p) ->
            val list = ids.getOrPut(id) { mutableListOf() }
            if (g.id !in list) list.add(g.id)
            names[id] = p.name
        }
    }
    return ids.filterKeys { it != myId }.map { (id, gs) ->
        val pos = gs.mapNotNull { positions[it]?.get(id) }.maxByOrNull { it.timestampMs }
        Person(id, names[id] ?: "Alguien", gs.distinct(), pos)
    }
}

internal fun Person.location(): Location? = pos?.let {
    Location("faro").apply { latitude = it.position.lat; longitude = it.position.lon }
}

internal fun personas(n: Int) = if (n == 1) "1 persona" else "$n personas"

internal fun formatDistance(m: Float): String =
    if (m < 1000) "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m" else "%.1f km".format(m / 1000).replace('.', ',')

internal fun ago(ts: Long): String {
    val mins = ((System.currentTimeMillis() - ts) / 60_000).coerceAtLeast(0)
    return when {
        mins < 1 -> "ahora"
        mins < 60 -> "hace $mins min"
        mins < 24 * 60 -> "hace ${mins / 60} h"
        else -> "hace ${mins / (24 * 60)} d"
    }
}

internal fun hoursText(ms: Long): String {
    val h = ms / HOUR
    return if (h < 24) "$h h" else "${h / 24} d"
}

/** Rumbo en palabras: «al noreste». */
internal fun bearingWord(bearing: Float): String {
    val names = listOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")
    val i = (((bearing + 360) % 360 + 22.5f) / 45f).toInt() % 8
    return names[i]
}

@Composable
internal fun Avatar(name: String, colorHex: String, size: Dp = 40.dp, ring: Color? = null) {
    val base = Modifier.size(size)
    Box(
        (if (ring != null) base.border(2.dp, ring, CircleShape).padding(4.dp) else base)
            .clip(CircleShape)
            .background(hex(colorHex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color(0xFF141414),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

@Composable
internal fun GroupIcon(colorHex: String, size: Dp = 44.dp) {
    val c = hex(colorHex)
    Box(
        Modifier.size(size).border(1.5.dp, c, RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Groups, null, tint = c, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
internal fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        Text(
            title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Botón principal relleno de azul calma. */
@Composable
internal fun FilledPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = FaroColors.Accent, contentColor = FaroColors.AccentInk),
    ) { Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold) }
}

/** Botón secundario con borde. */
@Composable
internal fun OutlinePill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FaroColors.Accent,
    height: Dp = 50.dp,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, color),
    ) { Text(text, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Botón pequeño de acción dentro de una tarjeta. */
@Composable
internal fun Chip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = if (primary) FaroColors.Accent else FaroColors.Line2
    Text(
        text,
        fontSize = 13.5.sp,
        color = if (primary) FaroColors.Accent else Color(0xFFD6D6D6),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, c, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

@Composable
internal fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)) {
        Text(
            text.uppercase(), fontSize = 12.sp, letterSpacing = 1.4.sp, color = FaroColors.Muted,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = FaroColors.Muted)
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = FaroColors.Dim, modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
internal fun Gap(h: Dp) = Spacer(Modifier.height(h))

internal val CardShape = RoundedCornerShape(16.dp)

internal fun Modifier.card(border: Color = FaroColors.Line): Modifier =
    this.clip(CardShape).border(1.5.dp, border, CardShape)

@Suppress("unused")
internal val Arrange8 = Arrangement.spacedBy(8.dp)

/** Orientación del móvil en grados (0 = norte), solo mientras la pantalla está visible. */
@Composable
internal fun rememberHeading(): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                val prev = heading
                if (prev == null || abs(prev - deg) > 2f) heading = deg
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return heading
}
