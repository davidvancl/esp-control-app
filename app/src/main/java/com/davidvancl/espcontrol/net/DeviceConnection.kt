package com.davidvancl.espcontrol.net

import com.davidvancl.espcontrol.protocol.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { CONNECTING, OPEN, CLOSED }

/**
 * One connection to a device carrying protocol messages. [WsConnection] is the v1 transport;
 * a BLE transport can implement the same interface without touching UI or logic.
 * A connection is used once: after [ConnectionState.CLOSED] a new one is created.
 */
interface DeviceConnection {
    val state: StateFlow<ConnectionState>
    val incoming: Flow<Message>

    /** Sends one encoded message, returns `false` if the connection is not open. */
    fun send(text: String): Boolean

    /**
     * Sends [length] bytes of [data] from [offset] as one binary frame (raw data stream, see
     * "Binary frames" in DESIGN.md). Returns `false` if the connection is not open or its send
     * queue is full; the frame is then not sent and the caller decides whether to retry.
     */
    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean

    fun close()
}
