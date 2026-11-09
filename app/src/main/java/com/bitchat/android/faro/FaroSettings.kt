package com.bitchat.android.faro

import android.content.Context
import android.content.SharedPreferences

/** Preferencias de Faro que el usuario cambia en Ajustes. No guardan nada sensible. */
object FaroSettings {
    private var prefs: SharedPreferences? = null

    enum class Battery { AUTO, SAVER, MAX }

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("faro_ajustes", Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean) = prefs?.getBoolean(key, def) ?: def
    private fun setBool(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }

    var notifyMessages: Boolean
        get() = bool("avisos_mensajes", true)
        set(v) = setBool("avisos_mensajes", v)

    var notifyRequests: Boolean
        get() = bool("avisos_solicitudes", true)
        set(v) = setBool("avisos_solicitudes", v)

    var notifySeen: Boolean
        get() = bool("avisos_visto", true)
        set(v) = setBool("avisos_visto", v)

    var battery: Battery
        get() = runCatching { Battery.valueOf(prefs?.getString("bateria", null) ?: "AUTO") }.getOrDefault(Battery.AUTO)
        set(v) { prefs?.edit()?.putString("bateria", v.name)?.apply(); FaroLocation.requestSoon() }

    /** Cambiar de zona del mapa guardado solo con Wi‑Fi (por defecto sí, para no gastar datos). */
    var mapWifiOnly: Boolean
        get() = bool("mapa_wifi", true)
        set(v) = setBool("mapa_wifi", v)

    var welcomeDone: Boolean
        get() = bool("bienvenida_hecha", false)
        set(v) = setBool("bienvenida_hecha", v)
}
