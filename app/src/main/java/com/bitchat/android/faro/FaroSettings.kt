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

    /** Avisos de Inicio que el usuario ha dejado para más tarde («crea un grupo», «invita a tu familia»). */
    fun isHidden(key: String): Boolean = prefs?.getStringSet("ocultos", emptySet())?.contains(key) == true

    fun hide(key: String) {
        val set = prefs?.getStringSet("ocultos", emptySet()).orEmpty() + key
        prefs?.edit()?.putStringSet("ocultos", set)?.apply()
    }

    /** Red cercana con la app cerrada: automática (sin internet), siempre o solo con la app abierta. */
    enum class MeshMode { AUTO, ALWAYS, APP_OPEN }

    var meshMode: MeshMode
        get() = runCatching { MeshMode.valueOf(prefs?.getString("red_cercana", null) ?: "AUTO") }.getOrDefault(MeshMode.AUTO)
        set(v) { prefs?.edit()?.putString("red_cercana", v.name)?.apply(); FaroMeshPolicy.evaluate() }

    /** Si quiero aviso cuando alguien llega a un lugar ([enter]) o sale de él. Por defecto, sí. */
    fun placeNotify(placeId: String, enter: Boolean): Boolean = bool("lugar_${placeId}_" + (if (enter) "llega" else "sale"), true)

    fun setPlaceNotify(placeId: String, enter: Boolean, on: Boolean) = setBool("lugar_${placeId}_" + (if (enter) "llega" else "sale"), on)

    var welcomeDone: Boolean
        get() = bool("bienvenida_hecha", false)
        set(v) = setBool("bienvenida_hecha", v)
}
