package com.bitchat.android.faro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.faro.ui.colorFor
import com.bitchat.android.faro.ui.location
import com.bitchat.android.faro.ui.people
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widgets de la pantalla de inicio (opción C): uno pequeño con las caras de la familia y su estado
 * y otro con «Estoy bien» y «Pedir ayuda». No consultan nada por su cuenta: se redibujan solo
 * cuando llega una ubicación o un aviso nuevo, así que no gastan batería de más.
 */
object FaroWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(context: Context) {
        synchronized(this) { if (started) return; started = true }
        val ctx = context.applicationContext
        scope.launch {
            combine(FaroGroups.groups, FaroGroups.positions) { g, p -> g to p }
                .debounce(2_000)
                .collect { updateFamily(ctx) }
        }
    }

    fun updateFamily(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FaroFamilyWidget::class.java))
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val (w, h) = sizeDp(mgr, id, 150, 150)
            val views = RemoteViews(ctx.packageName, R.layout.faro_widget_familia)
            views.setImageViewBitmap(R.id.faro_widget_image, render(ctx, w - 20, h - 20))
            views.setOnClickPendingIntent(R.id.faro_widget_root, openApp(ctx, 0, false))
            mgr.updateAppWidget(id, views)
        }
    }

    /** Tamaño actual del widget en dp (el que el usuario le ha dado al redimensionarlo). */
    private fun sizeDp(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW).takeIf { it > 0 } ?: defW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defH).takeIf { it > 0 } ?: defH
        return w.coerceIn(100, 420) to h.coerceIn(50, 420)
    }

    internal fun updateButtons(ctx: Context, ids: IntArray) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        ids.forEach { id ->
            val (w, h) = sizeDp(mgr, id, 150, 150)
            // Ancho y bajo: los dos botones uno al lado del otro.
            val layout = if (w >= 200 && h < 110) R.layout.faro_widget_botones_h else R.layout.faro_widget_botones
            mgr.updateAppWidget(id, buttonViews(ctx, layout))
        }
    }

    private fun buttonViews(ctx: Context, layout: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, layout)
        val ok = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, FaroButtonsWidget::class.java).setAction(FaroButtonsWidget.ACTION_IM_OK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.faro_widget_ok, ok)
        // «Pedir ayuda» abre la app con la confirmación: nunca se envía sin querer.
        views.setOnClickPendingIntent(R.id.faro_widget_sos, openApp(ctx, 2, true))
        return views
    }

    private fun openApp(ctx: Context, code: Int, askHelp: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, 9100 + code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(FaroDeepLink.EXTRA_ASK_HELP, askHelp),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Dibuja el widget según su tamaño:
     * pequeño, solo caras; ancho, caras con nombre y estado; grande, lista con estado y distancia.
     */
    private fun render(ctx: Context, wDp: Int, hDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceAtLeast(1)
        val h = (hDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(t: String, x: Float, y: Float, sizeSp: Float, color: String, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            paint.style = Paint.Style.FILL
            paint.textSize = sizeSp * d
            paint.color = Color.parseColor(color)
            paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            paint.textAlign = align
            c.drawText(t, x, y, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        text("Familia", 4 * d, 16 * d, 14f, "#F2F2F2", bold = true)

        val me = FaroLocation.myLocation.value
        val places = FaroGroups.places.value
        val all = people(FaroGroups.groups.value, FaroGroups.positions.value, FaroGroups.myId())
        fun ringOf(p: com.bitchat.android.faro.ui.Person) = when {
            p.needsHelp -> "#FF6B5E"
            p.pos == null -> "#2E2E33"
            p.silent || p.batteryDying -> "#FFB14A"
            else -> "#8FE3A8"
        }
        fun statusOf(p: com.bitchat.android.faro.ui.Person): String = when {
            p.needsHelp -> "Necesita ayuda"
            p.batteryDying -> "Sin batería"
            p.pos == null -> "Sin ubicación"
            p.silent -> "${(p.silentFor ?: 0L) / 3_600_000L} h sin noticias"
            else -> com.bitchat.android.faro.ui.placeOf(p, places)?.let { "En " + it.name } ?: "Bien"
        }
        fun avatar(p: com.bitchat.android.faro.ui.Person, cx: Float, cy: Float, r: Float) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.color = Color.parseColor(ringOf(p))
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.parseColor(colorFor(p.id))
            c.drawCircle(cx, cy, r - 4 * d, paint)
            text(p.name.take(1).uppercase(), cx, cy + r * 0.28f, r / d * 0.75f, "#141414", bold = true, align = Paint.Align.CENTER)
        }

        val list = wDp >= 230 && hDp >= 170
        val row = !list && wDp >= 230
        when {
            list -> {
                // Grande: una fila por persona con su estado y la distancia.
                val rowH = 44 * d
                val fit = ((h - 26 * d) / rowH).toInt().coerceAtLeast(1)
                all.take(fit).forEachIndexed { i, p ->
                    val y = 26 * d + i * rowH + rowH / 2
                    avatar(p, 4 * d + 17 * d, y, 17 * d)
                    text(p.name, 46 * d, y - 2 * d, 14f, "#F2F2F2", bold = true)
                    text(statusOf(p), 46 * d, y + 14 * d, 11.5f, if (ringOf(p) == "#8FE3A8") "#9A9A9A" else ringOf(p))
                    val dist = me?.let { m -> p.location()?.let { com.bitchat.android.faro.ui.formatDistance(m.distanceTo(it)) } }
                    if (dist != null) text(dist, w - 4 * d, y + 4 * d, 12.5f, "#D6D6D6", align = Paint.Align.RIGHT)
                }
                if (all.isEmpty()) text("Abre Faro para empezar", 4 * d, 44 * d, 12f, "#9A9A9A")
            }
            row -> {
                // Ancho: caras en fila con nombre y estado debajo.
                val n = ((w - 4 * d) / (66 * d)).toInt().coerceIn(1, 6)
                val slot = (w - 4 * d) / n
                val r = 20 * d
                all.take(n).forEachIndexed { i, p ->
                    val cx = 2 * d + slot * i + slot / 2
                    val cy = 26 * d + r + 2 * d
                    avatar(p, cx, cy, r)
                    text(p.name.take(9), cx, cy + r + 15 * d, 12f, "#F2F2F2", bold = true, align = Paint.Align.CENTER)
                    if (hDp >= 120) text(statusOf(p).take(12), cx, cy + r + 29 * d, 10.5f, if (ringOf(p) == "#8FE3A8") "#9A9A9A" else ringOf(p), align = Paint.Align.CENTER)
                }
                if (all.isEmpty()) text("Abre Faro para empezar", 4 * d, 44 * d, 12f, "#9A9A9A")
            }
            else -> {
                // Pequeño: solo caras y una línea de aviso.
                val r = (minOf(wDp, hDp) / 8f).coerceIn(14f, 22f) * d
                val perRow = ((w - 4 * d) / (2 * r + 8 * d)).toInt().coerceAtLeast(1)
                val rows = ((h - 46 * d) / (2 * r + 8 * d)).toInt().coerceAtLeast(1)
                all.take(perRow * rows).forEachIndexed { i, p ->
                    val cx = 4 * d + r + (i % perRow) * (2 * r + 8 * d)
                    val cy = 26 * d + r + (i / perRow) * (2 * r + 8 * d)
                    avatar(p, cx, cy, r)
                }
                val alert = all.firstOrNull { it.needsHelp }?.let { "${it.name} necesita ayuda" to "#FF6B5E" }
                    ?: all.firstOrNull { it.batteryDying }?.let { "${it.name}: sin batería" to "#FFB14A" }
                    ?: all.firstOrNull { it.silent }?.let { "${it.name}: ${(it.silentFor ?: 0L) / 3_600_000L} h sin noticias" to "#FFB14A" }
                    ?: (if (all.isEmpty()) "Abre Faro para empezar" else "Todos bien") to "#9A9A9A"
                text(alert.first.take((wDp / 6).coerceAtLeast(14)), 4 * d, h - 6 * d, 11.5f, alert.second)
            }
        }
        return bmp
    }
}

class FaroFamilyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        FaroWidgets.updateFamily(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        FaroWidgets.updateFamily(context)
    }
}

class FaroButtonsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        FaroWidgets.updateButtons(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        FaroWidgets.updateButtons(context, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_IM_OK) return
        // «Estoy bien» sin abrir la app. Si estaba cerrada, se esperan los grupos un momento.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            FaroSettings.init(context)
            FaroGroups.init(context)
            withTimeoutOrNull(5_000) { FaroGroups.groups.first { it.isNotEmpty() } }
            FaroGroups.sendImOk()
            delay(1_000)
            pending.finish()
        }
    }

    companion object {
        const val ACTION_IM_OK = "com.bitchat.android.faro.WIDGET_IM_OK"
    }
}
