package com.davidvancl.espcontrol.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A device announced over mDNS (`_espctl._tcp`), see "Discovery" in DESIGN.md. */
data class DiscoveredDevice(
    val id: String,
    val type: String,
    val name: String,
    val fw: String,
    val proto: Int?,
    /** IP address; `null` until the address record has been resolved. */
    val host: String?,
)

sealed interface DiscoveryEvent {
    data class Found(val serviceName: String, val device: DiscoveredDevice) : DiscoveryEvent
    data class Lost(val serviceName: String) : DiscoveryEvent
}

class Discovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Discovers devices while collected. */
    fun events(): Flow<DiscoveryEvent> = callbackFlow {
        val executor = Executors.newSingleThreadExecutor()
        val watchers = mutableMapOf<String, ServiceWatcher>()

        fun post(block: () -> Unit) {
            runCatching { executor.execute(block) }
        }

        fun found(service: NsdServiceInfo) {
            val device = service.toDevice()
            Log.d(TAG, "resolved ${service.serviceName}: $device")
            device?.let { trySendBlocking(DiscoveryEvent.Found(service.serviceName, it)) }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "found ${service.serviceName}")
                post {
                    if (service.serviceName in watchers) return@post
                    watchers[service.serviceName] = watch(service, executor, ::found)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "lost ${service.serviceName}")
                post {
                    watchers.remove(service.serviceName)?.stop()
                    trySendBlocking(DiscoveryEvent.Lost(service.serviceName))
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            executor.execute {
                watchers.values.forEach { it.stop() }
                watchers.clear()
            }
            executor.shutdown()
        }
    }

    private interface ServiceWatcher {
        fun stop()
    }

    private fun watch(
        service: NsdServiceInfo,
        executor: java.util.concurrent.Executor,
        onResolved: (NsdServiceInfo) -> Unit,
    ): ServiceWatcher {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) = onResolved(info)
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${service.serviceName}: $errorCode")
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(service, executor, callback)
            return object : ServiceWatcher {
                override fun stop() {
                    runCatching { nsd.unregisterServiceInfoCallback(callback) }
                }
            }
        }
        resolveLegacy(service, executor, onResolved, attempts = 3)
        return object : ServiceWatcher {
            override fun stop() = Unit
        }
    }

    /** Before Android 14 only one resolve may run at a time; retry when busy. */
    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        service: NsdServiceInfo,
        executor: java.util.concurrent.Executor,
        onResolved: (NsdServiceInfo) -> Unit,
        attempts: Int,
    ) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                runCatching { executor.execute { onResolved(info) } }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${service.serviceName}: $errorCode")
                if (attempts <= 1) return
                runCatching {
                    executor.execute {
                        Thread.sleep(300)
                        resolveLegacy(service, executor, onResolved, attempts - 1)
                    }
                }
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_espctl._tcp"
        private const val TAG = "EspDiscovery"

        private fun NsdServiceInfo.toDevice(): DiscoveredDevice? {
            val txt = attributes.mapValues { (_, bytes) -> bytes?.toString(Charsets.UTF_8).orEmpty() }
            // ESP8266 mDNS answers are sometimes incomplete: the id falls back to the instance name
            // (`<type>-<id>`), the address may arrive in a later update.
            val id = txt["id"]?.ifEmpty { null } ?: serviceName.substringAfterLast('-', "").ifEmpty { return null }
            val address = addresses().let { all -> all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() }
            return DiscoveredDevice(
                id = id,
                type = txt["type"] ?: serviceName.substringBeforeLast('-', ""),
                name = txt["name"].orEmpty().ifEmpty { serviceName },
                fw = txt["fw"].orEmpty(),
                proto = txt["proto"]?.toIntOrNull(),
                host = address?.hostAddress,
            )
        }

        @Suppress("DEPRECATION")
        private fun NsdServiceInfo.addresses(): List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hostAddresses
            else listOfNotNull(host)
    }
}
