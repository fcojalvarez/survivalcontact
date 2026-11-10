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
        val views = RemoteViews(ctx.packageName, R.layout.faro_widget_familia)
        views.setImageViewBitmap(R.id.faro_widget_image, render(ctx))
        views.setOnClickPendingIntent(R.id.faro_widget_root, openApp(ctx, 0, false))
        mgr.updateAppWidget(ids, views)
    }

    internal fun updateButtons(ctx: Context, ids: IntArray) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val views = RemoteViews(ctx.packageName, R.layout.faro_widget_botones)
        val ok = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, FaroButtonsWidget::class.java).setAction(FaroButtonsWidget.ACTION_IM_OK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.faro_widget_ok, ok)
        // «Pedir ayuda» abre la app con la confirmación: nunca se envía sin querer.
        views.setOnClickPendingIntent(R.id.faro_widget_sos, openApp(ctx, 2, true))
        mgr.updateAppWidget(ids, views)
    }

    private fun openApp(ctx: Context, code: Int, askHelp: Boolean): PendingIntent =
        PendingIntent.getActivity(
            ctx, 9100 + code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(FaroDeepLink.EXTRA_ASK_HELP, askHelp),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Dibuja las caras (hasta 4) con un borde de color según su estado y una línea de aviso. */
    private fun render(ctx: Context): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (150 * d).toInt()
        val h = (150 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#F2F2F2")
        paint.textSize = 14 * d
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText("Familia", 4 * d, 16 * d, paint)

        val ppl = people(FaroGroups.groups.value, FaroGroups.positions.value, FaroGroups.myId()).take(4)
        val r = 19 * d
        ppl.forEachIndexed { i, p ->
            val cx = (4 * d + r + 4 * d) + (i % 3) * (2 * r + 8 * d)
            val cy = 30 * d + r + (i / 3) * (2 * r + 8 * d)
            val ring = when {
                p.needsHelp -> "#FF6B5E"
                p.pos == null -> "#2E2E33"
                p.silent || p.batteryDying -> "#FFB14A"
                else -> "#8FE3A8"
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.color = Color.parseColor(ring)
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.parseColor(colorFor(p.id))
            c.drawCircle(cx, cy, r - 4 * d, paint)
            paint.color = Color.parseColor("#141414"); paint.textSize = 14 * d; paint.textAlign = Paint.Align.CENTER
            c.drawText(p.name.take(1).uppercase(), cx, cy + 5 * d, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        val alert = ppl.firstOrNull { it.needsHelp }?.let { "${it.name} necesita ayuda" to "#FF6B5E" }
            ?: ppl.firstOrNull { it.batteryDying }?.let { "${it.name}: sin batería" to "#FFB14A" }
            ?: ppl.firstOrNull { it.silent }?.let { "${it.name}: ${(it.silentFor ?: 0L) / 3_600_000L} h sin noticias" to "#FFB14A" }
            ?: (if (ppl.isEmpty()) "Abre Faro para empezar" else "Todos bien") to "#9A9A9A"
        paint.color = Color.parseColor(alert.second); paint.textSize = 11.5f * d
        paint.typeface = Typeface.DEFAULT
        c.drawText(alert.first.take(26), 4 * d, h - 6 * d, paint)
        return bmp
    }
}

class FaroFamilyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        FaroWidgets.updateFamily(context)
    }
}

class FaroButtonsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        FaroWidgets.updateButtons(context, ids)
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
