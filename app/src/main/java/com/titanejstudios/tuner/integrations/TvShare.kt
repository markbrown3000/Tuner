package com.titanejstudios.tuner.integrations

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.titanejstudios.tuner.data.AppLog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Copy Tuner's settings from one TV to another on the same Wi-Fi: the sending TV announces itself
 * and shows a 4-digit code; the receiving TV finds it and fetches the settings with that code.
 */
class TvShare(context: Context) {
    class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun advertise(name: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                AppLog.warn("Couldn't announce this TV on the network ($errorCode)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun discover(onFound: (Found) -> Unit) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.warn("Couldn't search the network for other TVs ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    toResolve.addLast(info)
                    resolveNext(onFound)
                }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** NsdManager only resolves one service at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext(onFound: (Found) -> Unit) {
        if (resolving || discovery == null) return
        val next = toResolve.removeFirstOrNull() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext(onFound)
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host)?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null && !host.contains(':')) onFound(Found(info.serviceName, host, info.port))
                        resolveNext(onFound)
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        toResolve.clear()
        resolving = false
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }

    companion object {
        const val SERVICE_TYPE = "_tunertv._tcp."

        /** Fetches the other TV's settings (a Tuner backup). Blocking – call off the main thread. */
        fun fetch(host: String, port: Int, code: String): String {
            val url = URL("http://$host:$port/share.json?t=" + URLEncoder.encode(code, "UTF-8"))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
            }
            try {
                return when (conn.responseCode) {
                    200 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    403 -> throw IllegalArgumentException("Wrong code – check the number on the other TV")
                    else -> throw IllegalStateException("The other TV said ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
