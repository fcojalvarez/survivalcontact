package com.bitchat.android.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Faro: indica si el móvil tiene internet de verdad (red validada).
 *
 * Usa el aviso del sistema cuando cambia la red, sin sondeos periódicos, así que no gasta
 * batería. Sirve para avisar en la interfaz de que se está en modo «solo red cercana».
 */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember(cm) { mutableStateOf(cm?.let(::hasValidatedInternet) ?: false) }

    DisposableEffect(cm) {
        if (cm == null) return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                online = hasValidatedInternet(cm)
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
    return online
}

private fun hasValidatedInternet(cm: ConnectivityManager): Boolean {
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
