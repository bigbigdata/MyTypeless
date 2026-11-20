package com.typeless.ime.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Monitors device network state in real-time.
 * Enables zero-delay switching to on-device models when entering offline environments (airplane mode, subway, etc.)
 */
class NetworkStateMonitor internal constructor(
    private val context: Context?,
    private val liveChecker: (() -> Boolean)?
) {

    constructor(context: Context) : this(context, null)

    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    private val connectivityManager =
        context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val networkSwitchListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var cachedOnline: Boolean = checkCurrentConnectivity()

    @Volatile
    private var lastNetworkHandle: Long = -1L

    /**
     * Always queries live connectivity from ConnectivityManager so stale/missed callbacks
     * after Doze mode or Wi-Fi/cellular handoffs can never leave the IME stuck offline.
     */
    val isOnline: Boolean
        get() = refreshConnectivity()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        if (context != null) {
            registerNetworkCallback()
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        liveChecker?.let { return it() }
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Actively probes current connectivity, updates cached state, and notifies listeners on transitions.
     */
    fun refreshConnectivity(): Boolean {
        val current = checkCurrentConnectivity()
        updateOnlineState(current)
        return current
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Network became available")
                val handle = network.networkHandle
                val switched = lastNetworkHandle != -1L && lastNetworkHandle != handle
                lastNetworkHandle = handle
                if (switched) {
                    Log.i(TAG, "Active network switched; notifying listeners to evict stale sockets")
                    notifyNetworkSwitch()
                }
                updateOnlineState(true)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost")
                // Check if any other validated network is available
                val stillOnline = checkCurrentConnectivity()
                updateOnlineState(stillOnline)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                updateOnlineState(hasInternet)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateOnlineState(online: Boolean) {
        if (cachedOnline != online) {
            cachedOnline = online
            Log.i(TAG, "Network state changed: isOnline = $online")
            listeners.forEach { callback ->
                try {
                    callback(online)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in network state listener: ${e.message}")
                }
            }
        }
    }

    private fun notifyNetworkSwitch() {
        networkSwitchListeners.forEach { callback ->
            try {
                callback()
            } catch (e: Exception) {
                Log.e(TAG, "Error in network switch listener: ${e.message}")
            }
        }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        // Immediately notify with live state
        listener(refreshConnectivity())
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun addNetworkSwitchListener(listener: () -> Unit) {
        networkSwitchListeners.add(listener)
    }

    fun removeNetworkSwitchListener(listener: () -> Unit) {
        networkSwitchListeners.remove(listener)
    }

    fun unregister() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }
}
