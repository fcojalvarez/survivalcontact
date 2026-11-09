package com.bitchat.android.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Faro: Tor no está incluido, así que el modo es siempre OFF. */
object TorPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(TorMode.OFF)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _modeFlow.value = TorMode.OFF
    }

    fun set(context: Context, mode: TorMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOR_MODE, mode.name).apply()
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode {
        return TorMode.OFF
    }
}

