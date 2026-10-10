package com.davidvancl.espcontrol.data

import android.content.Context
import com.davidvancl.espcontrol.net.DeviceConnection
import com.davidvancl.espcontrol.net.Discovery
import com.davidvancl.espcontrol.net.DiscoveryEvent
import com.davidvancl.espcontrol.net.DiscoveredDevice
import com.davidvancl.espcontrol.net.ConnectionState
import com.davidvancl.espcontrol.net.WsConnection
import com.davidvancl.espcontrol.protocol.Message
import com.davidvancl.espcontrol.protocol.Protocol
import com.davidvancl.espcontrol.protocol.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** A device for the UI: stored devices merged with devices currently announced over mDNS. */
data class DeviceEntry(
    val id: String,
    val name: String,
    val type: String,
    val fw: String,
    val host: String?,
    val online: Boolean,
    val known: Boolean,
    val paired: Boolean,
    val token: String?,
)

class DeviceRepository(context: Context, scope: CoroutineScope) {

    private val store = KnownDeviceStore(context)
    private val discovery = Discovery(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** Devices announced right now, by mDNS service name. */
    private val announced = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    /**
     * Ids of devices that answered recently (TCP check or an open connection). mDNS on ESP8266
     * is unreliable, announcements expire while the device is still reachable.
     */
    private val reachable = MutableStateFlow<Set<String>>(emptySet())

    val devices: StateFlow<List<DeviceEntry>> = combine(store.devices, announced, reachable) { known, found, answering ->
        val online = found.values.associateBy { it.id }
        (known.keys + online.keys).map { id ->
            val stored = known[id]
            val seen = online[id]
            DeviceEntry(
                id = id,
                name = seen?.name ?: stored?.name.orEmpty(),
                type = seen?.type ?: stored?.type.orEmpty(),
                fw = seen?.fw ?: stored?.fw.orEmpty(),
                host = seen?.host ?: stored?.lastHost,
                online = seen?.host != null || id in answering,
                known = stored != null,
                paired = stored?.token != null,
                token = stored?.token,
            )
        }.sortedWith(compareByDescending<DeviceEntry> { it.online }.thenBy { it.name.lowercase() })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun device(id: String): Flow<DeviceEntry?> =
        devices.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    /** Runs mDNS discovery and reachability checks until cancelled. Known devices get their last IP updated. */
    suspend fun discover() = coroutineScope {
        launch { checkReachability() }
        try {
            discovery.events().collect { event ->
                when (event) {
                    is DiscoveryEvent.Found -> {
                        announced.update { it + (event.serviceName to event.device) }
                        store.update(event.device.id) { known ->
                            known?.copy(
                                name = event.device.name,
                                type = event.device.type,
                                fw = event.device.fw,
                                lastHost = event.device.host ?: known.lastHost,
                            )
                        }
                    }
                    is DiscoveryEvent.Lost -> announced.update { it - event.serviceName }
                }
            }
        } finally {
            announced.value = emptyMap()
            reachable.value = emptySet()
        }
    }

    /** Called by an open device connection, so the list matches what the device screen shows. */
    fun setReachable(id: String, value: Boolean) {
        reachable.update { if (value) it + id else it - id }
    }

    /** Tries a TCP connection to every device with a known address. */
    private suspend fun checkReachability() {
        while (true) {
            val targets = devices.value.mapNotNull { device -> device.host?.let { device.id to it } }
            coroutineScope {
                for ((id, host) in targets) {
                    launch { setReachable(id, isReachable(host)) }
                }
            }
            delay(REACHABILITY_INTERVAL_MS)
        }
    }

    private suspend fun isReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, PORT), REACHABILITY_TIMEOUT_MS) }
        }.isSuccess
    }

    fun connect(host: String): DeviceConnection = WsConnection(client, host)

    /** Stores the identity of a device the app talked to, keeping its token. */
    suspend fun remember(info: Message.Info, host: String) {
        store.update(info.id) { known ->
            (known ?: KnownDevice(id = info.id, name = info.name, type = info.type)).copy(
                name = info.name,
                type = info.type,
                fw = info.fw,
                lastHost = host,
            )
        }
    }

    suspend fun saveToken(id: String, token: String?) {
        store.update(id) { it?.copy(token = token) }
    }

    suspend fun forget(id: String) {
        store.update(id) { null }
    }

    /** Connects to [host], asks for its identity and stores it. Used for manually entered addresses. */
    suspend fun probe(host: String): Message.Info? {
        val connection = connect(host)
        try {
            return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                coroutineScope {
                    val reply = async(start = CoroutineStart.UNDISPATCHED) {
                        connection.incoming.filterIsInstance<Message.Info>().first()
                    }
                    if (connection.state.first { it != ConnectionState.CONNECTING } != ConnectionState.OPEN) {
                        reply.cancel()
                        return@coroutineScope null
                    }
                    connection.send(Protocol.encode(Request.Hello(), 1))
                    reply.await()
                }
            }?.also { remember(it, host) }
        } finally {
            connection.close()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5000L
        const val PORT = 80
        const val REACHABILITY_INTERVAL_MS = 10_000L
        const val REACHABILITY_TIMEOUT_MS = 1500
    }
}
