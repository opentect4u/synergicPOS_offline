package com.example.synergic_pos_offline.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Observes device connectivity and reports online/offline changes on the main thread.
 * "Online" here means connected to any usable transport (Wi-Fi / cellular / ethernet) —
 * it does not require validated internet, so a LAN-only POS setup still counts as online.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Emits the current state immediately, then on every connectivity change. */
    fun register(onChange: (Boolean) -> Unit) {
        onChange(isOnline())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emit(onChange)
            override fun onLost(network: Network) = emit(onChange)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emit(onChange)
        }
        callback = cb
        connectivityManager.registerDefaultNetworkCallback(cb)
    }

    fun unregister() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        callback = null
    }

    private fun emit(onChange: (Boolean) -> Unit) {
        mainHandler.post { onChange(isOnline()) }
    }
}
