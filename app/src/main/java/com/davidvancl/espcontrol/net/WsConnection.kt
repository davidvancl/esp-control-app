package com.davidvancl.espcontrol.net

import com.davidvancl.espcontrol.protocol.Message
import com.davidvancl.espcontrol.protocol.Protocol
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** WebSocket at `ws://<host>/ws`, one JSON object per text frame. */
class WsConnection(client: OkHttpClient, host: String) : DeviceConnection {

    private val _state = MutableStateFlow(ConnectionState.CONNECTING)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<Message> = _incoming.asSharedFlow()

    private val socket: WebSocket = client.newWebSocket(
        Request.Builder().url("ws://${formatHost(host)}/ws").build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.OPEN
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Protocol.decode(text)?.let { _incoming.tryEmit(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _state.value = ConnectionState.CLOSED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.CLOSED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnectionState.CLOSED
            }
        },
    )

    override fun send(text: String): Boolean =
        _state.value == ConnectionState.OPEN && socket.send(text)

    override fun close() {
        socket.close(1000, null)
        _state.value = ConnectionState.CLOSED
    }

    private companion object {
        fun formatHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host
    }
}
