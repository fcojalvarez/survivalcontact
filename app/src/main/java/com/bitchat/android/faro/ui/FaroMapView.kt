package com.bitchat.android.faro.ui

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bitchat.android.faro.FaroOfflineMap
import com.bitchat.android.faro.MemberPosition
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Un punto que pintar en el mapa. */
data class MapPoint(val id: String, val name: String, val lat: Double, val lon: Double, val colorHex: String, val isMe: Boolean)

/**
 * Mapa de MapLibre con estilo oscuro. Con internet usa OpenFreeMap; sin conexión, la zona
 * descargada por [FaroOfflineMap]. Pinta a los miembros y a uno mismo.
 */
@Composable
fun FaroMapView(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    handle: MapHandle? = null,
    onPointClick: ((String) -> Unit)? = null,
) {
    val clickState = androidx.compose.runtime.rememberUpdatedState(onPointClick)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var cameraSet by remember { mutableStateOf(false) }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setStyle(Style.Builder().fromUri(FaroOfflineMap.STYLE_URL)) { style ->
                    style.addSource(GeoJsonSource(SOURCE_ID, EMPTY))
                    style.addLayer(
                        CircleLayer("faro-puntos", SOURCE_ID).withProperties(
                            PropertyFactory.circleRadius(Expression.switchCase(Expression.get("me"), Expression.literal(7f), Expression.literal(9f))),
                            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                            PropertyFactory.circleStrokeColor("#000000"),
                            PropertyFactory.circleStrokeWidth(2f),
                        )
                    )
                    style.addLayer(
                        SymbolLayer("faro-nombres", SOURCE_ID).withProperties(
                            PropertyFactory.textField(Expression.get("name")),
                            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textOffset(arrayOf(0f, -1.6f)),
                            PropertyFactory.textColor("#F2F2F2"),
                            PropertyFactory.textHaloColor("#000000"),
                            PropertyFactory.textHaloWidth(1.5f),
                            PropertyFactory.textAllowOverlap(true),
                        )
                    )
                    styleReady = true
                }
                m.addOnMapClickListener { latLng ->
                    val pt = m.projection.toScreenLocation(latLng)
                    val r = 40f
                    val hit = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), "faro-puntos")
                        .firstOrNull()?.getStringProperty("id")
                    if (hit != null && hit != "yo") { clickState.value?.invoke(hit); true } else false
                }
                map = m
                handle?.map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(points, styleReady, map) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        m.style?.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(toGeoJson(points))
        if (!cameraSet && points.isNotEmpty()) {
            cameraSet = true
            if (points.size == 1) {
                m.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(points[0].lat, points[0].lon))
                    .zoom(14.0)
                    .build()
            } else {
                val b = LatLngBounds.Builder()
                points.forEach { b.include(LatLng(it.lat, it.lon)) }
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120)) }
                if (m.cameraPosition.zoom > 15.0) {
                    m.moveCamera(CameraUpdateFactory.zoomTo(15.0))
                }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val SOURCE_ID = "faro-familia"
private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

private fun toGeoJson(points: List<MapPoint>): String {
    val features = JSONArray()
    points.forEach { p ->
        features.put(JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
            put("properties", JSONObject().put("id", p.id).put("name", p.name).put("color", p.colorHex).put("me", p.isMe))
        })
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** Convierte miembros y mi posición en puntos del mapa. */
fun mapPoints(members: Collection<MemberPosition>, me: Location?, meName: String, colorFor: (String) -> String): List<MapPoint> {
    val list = members.map { MapPoint(it.authorId, it.name, it.position.lat, it.position.lon, colorFor(it.authorId), false) }
    return if (me != null) list + MapPoint("yo", meName, me.latitude, me.longitude, "#A7C7E7", true) else list
}

/** Acceso al mapa ya dibujado: centrar en alguien y leer el nombre de la calle cercana. */
class MapHandle {
    internal var map: MapLibreMap? = null

    /** Centro del mapa (para colocar un lugar). */
    fun center(): Pair<Double, Double>? = map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    fun focus(lat: Double, lon: Double, zoom: Double = 16.0, then: (() -> Unit)? = null) {
        val m = map ?: return
        m.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 500,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() { then?.invoke() }
                override fun onCancel() { then?.invoke() }
            }
        )
    }

    /**
     * Calle (y localidad) junto a un punto, sacada del propio mapa ya dibujado: no se pregunta
     * a ningún servicio. Sin mapa de esa zona devuelve null.
     */
    fun streetNear(lat: Double, lon: Double): String? {
        val m = map ?: return null
        val p = m.projection.toScreenLocation(LatLng(lat, lon))
        var street: String? = null
        for (r in listOf(30f, 70f, 140f)) {
            street = m.queryRenderedFeatures(android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r), "nombres-calles")
                .firstNotNullOfOrNull { f -> runCatching { f.getStringProperty("name") }.getOrNull()?.takeIf { it.isNotBlank() } }
            if (street != null) break
        }
        val big = 700f
        val town = m.queryRenderedFeatures(android.graphics.RectF(p.x - big, p.y - big, p.x + big, p.y + big), "ciudades", "pueblos")
            .firstNotNullOfOrNull { f -> runCatching { f.getStringProperty("name") }.getOrNull()?.takeIf { it.isNotBlank() } }
        return listOfNotNull(street, town).distinct().joinToString(", ").ifBlank { null }
    }
}
