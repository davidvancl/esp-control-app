package com.davidvancl.espcontrol.device

import com.davidvancl.espcontrol.data.DeviceRepository
import com.davidvancl.espcontrol.net.ConnectionState
import com.davidvancl.espcontrol.net.DeviceConnection
import com.davidvancl.espcontrol.protocol.ErrorCode
import com.davidvancl.espcontrol.protocol.Message
import com.davidvancl.espcontrol.protocol.Protocol
import com.davidvancl.espcontrol.protocol.Request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

sealed interface SessionStatus {
    data object Connecting : SessionStatus

    /** Connected and authorized: values can be read and set. */
    data object Online : SessionStatus

    /** The device does not know this phone (no token or token revoked). */
    data object NeedsPairing : SessionStatus

    /** Connection lost or device unreachable; reconnecting with backoff. */
    data object Offline : SessionStatus

    /** The device cannot be used by this app version, or has no known address. */
    data class Failed(val reason: FailReason) : SessionStatus
}

enum class FailReason { NO_ADDRESS, PROTOCOL }

sealed interface PairingState {
    data object Idle : PairingState
    data object Starting : PairingState

    /**
     * Device is waiting for the PIN (`pin`) or for a confirmation on the device (`confirm`).
     * The PIN has [length] symbols: digits, or indices into [symbols] when the device sends them.
     * [wrongAttempts] counts PINs rejected by the device, so the entry is cleared after every one.
     */
    data class Waiting(
        val method: String,
        val deadline: Long,
        val length: Int = DEFAULT_PIN_LENGTH,
        val symbols: List<String>? = null,
        val wrongAttempts: Int = 0,
    ) : PairingState {
        val wrongPin: Boolean get() = wrongAttempts > 0
    }

    data class Failed(val reason: PairingError) : PairingState

    companion object {
        const val DEFAULT_PIN_LENGTH = 4
    }
}

enum class PairingError { TIMEOUT, REJECTED, BUSY, UNSUPPORTED, NO_CONNECTION }

/** Messages for the user, shown as a snackbar. */
sealed interface Notice {
    data class Failed(val id: String?, val code: String, val message: String) : Notice
    data class Timeout(val id: String?) : Notice

    /** Older firmware does not know the request (`bad_request`). */
    data object RestartUnsupported : Notice
    data object Restarting : Notice
}

/**
 * Connection to one device while its screen is visible (see "Android app" in DESIGN.md):
 * hello with the stored token, request / reply matching by `seq` with a 3 s timeout,
 * events, pairing and reconnecting with backoff. Call [start] / [stop] with the app lifecycle.
 * All methods must be called from the main thread.
 */
class DeviceSession(
    private val deviceId: String,
    private val repository: DeviceRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Connecting)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _info = MutableStateFlow<Message.Info?>(null)
    val info: StateFlow<Message.Info?> = _info.asStateFlow()

    /** Last known values: from `state`, `event` and values the app has set. Cleared on reconnect. */
    private val _values = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    val values: StateFlow<Map<String, JsonElement>> = _values.asStateFlow()

    /** Every `event` as it arrives, e.g. button presses. */
    private val _events = MutableSharedFlow<Map<String, JsonElement>>(extraBufferCapacity = 16)
    val events: SharedFlow<Map<String, JsonElement>> = _events.asSharedFlow()

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private var job: Job? = null
    private var connection: DeviceConnection? = null
    private var seq = 0
    private val pending = mutableMapOf<Int, CompletableDeferred<Message>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pairOnConnect = false

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        connection?.close()
        connection = null
        if (_pairing.value !is PairingState.Failed) _pairing.value = PairingState.Idle
    }

    fun set(id: String, value: JsonElement?) {
        if (value != null && id !in _info.value?.get.orEmpty()) {
            // Write-only values cannot be read back: show the last value sent.
            _values.update { it + (id to value) }
        }
        scope.launch {
            when (val reply = request(Request.Set(id, value))) {
                null -> _notices.emit(Notice.Timeout(id))
                is Message.Err -> _notices.emit(Notice.Failed(id, reply.code, reply.msg))
                else -> Unit
            }
        }
    }

    fun refresh() {
        scope.launch { loadState() }
    }

    fun pair() {
        _pairing.value = PairingState.Starting
        if (connection?.state?.value == ConnectionState.OPEN && _info.value != null) {
            scope.launch { requestPairing() }
        } else {
            pairOnConnect = true
            wake.trySend(Unit)
        }
    }

    fun submitPin(pin: String) {
        scope.launch {
            when (val reply = request(Request.Pin(pin))) {
                null -> _notices.emit(Notice.Timeout(null))
                is Message.Err -> _pairing.update { state ->
                    if (state is PairingState.Waiting && reply.code == ErrorCode.AUTH) {
                        state.copy(wrongAttempts = state.wrongAttempts + 1)
                    }
                    else state
                }
                else -> Unit
            }
        }
    }

    fun cancelPairing() {
        _pairing.value = PairingState.Idle
    }

    /** Asks the device to restart; the connection drops and reconnects with backoff. */
    fun restart() {
        scope.launch {
            when (val reply = request(Request.Restart)) {
                null -> _notices.emit(Notice.Timeout(null))
                is Message.Err -> _notices.emit(
                    if (reply.code == ErrorCode.BAD_REQUEST) Notice.RestartUnsupported
                    else Notice.Failed(null, reply.code, reply.msg)
                )
                else -> _notices.emit(Notice.Restarting)
            }
        }
    }

    /** Removes this phone's token on the device and locally. */
    suspend fun unpair() {
        request(Request.Unpair)
        repository.saveToken(deviceId, null)
        connection?.close()
    }

    private suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive) {
            val host = repository.device(deviceId).first()?.host
            if (host == null) {
                _status.value = SessionStatus.Failed(FailReason.NO_ADDRESS)
                repository.device(deviceId).first { it?.host != null }
                continue
            }
            _status.value = SessionStatus.Connecting
            when (val outcome = connectOnce(host)) {
                Outcome.PROTOCOL -> {
                    _status.value = SessionStatus.Failed(FailReason.PROTOCOL)
                    return
                }
                Outcome.NOT_PAIRED -> {
                    _status.value = SessionStatus.NeedsPairing
                    // The device closes unauthenticated connections; wait until the user starts pairing.
                    wake.receive()
                    backoff = MIN_BACKOFF_MS
                }
                Outcome.AUTHED, Outcome.UNREACHABLE -> {
                    _status.value = SessionStatus.Offline
                    if (outcome == Outcome.UNREACHABLE) repository.setReachable(deviceId, false)
                    if (outcome == Outcome.AUTHED) backoff = MIN_BACKOFF_MS
                    if (pairOnConnect) {
                        pairOnConnect = false
                        _pairing.value = PairingState.Failed(PairingError.NO_CONNECTION)
                    }
                    withTimeoutOrNull(backoff) { wake.receive() }
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private enum class Outcome { AUTHED, NOT_PAIRED, UNREACHABLE, PROTOCOL }

    /** Runs one connection until it closes. */
    private suspend fun connectOnce(host: String): Outcome {
        val conn = repository.connect(host)
        connection = conn
        try {
            return coroutineScope {
                val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                    conn.incoming.collect { handle(it) }
                }
                try {
                    if (conn.state.first { it != ConnectionState.CONNECTING } != ConnectionState.OPEN) {
                        return@coroutineScope Outcome.UNREACHABLE
                    }
                    val token = repository.device(deviceId).first()?.token
                    when (val reply = request(Request.Hello(token = token))) {
                        is Message.Info -> if (!onInfo(reply, host, token)) {
                            return@coroutineScope Outcome.UNREACHABLE
                        }
                        is Message.Err -> if (reply.code == ErrorCode.PROTO) {
                            return@coroutineScope Outcome.PROTOCOL
                        }
                        else -> return@coroutineScope Outcome.UNREACHABLE
                    }
                    if (_status.value != SessionStatus.Online && pairOnConnect) {
                        pairOnConnect = false
                        requestPairing()
                    }
                    conn.state.first { it == ConnectionState.CLOSED }
                    if (_info.value?.authed == true) Outcome.AUTHED else Outcome.NOT_PAIRED
                } finally {
                    reader.cancel()
                }
            }
        } finally {
            conn.close()
            if (connection === conn) connection = null
            pending.values.forEach { it.cancel() }
            pending.clear()
            if (_pairing.value is PairingState.Waiting || _pairing.value == PairingState.Starting) {
                _pairing.value = PairingState.Failed(PairingError.NO_CONNECTION)
            }
        }
    }

    /** Returns `false` if another device answers on the stored IP (e.g. DHCP reassigned it). */
    private suspend fun onInfo(info: Message.Info, host: String, token: String?): Boolean {
        if (info.id != deviceId) return false
        _info.value = info
        _values.value = emptyMap()
        repository.setReachable(deviceId, true)
        repository.remember(info, host)
        if (info.authed) {
            _status.value = SessionStatus.Online
            loadState()
        } else {
            if (token != null) repository.saveToken(deviceId, null)
            _status.value = SessionStatus.NeedsPairing
        }
        return true
    }

    private suspend fun loadState() {
        when (val reply = request(Request.Get)) {
            is Message.State -> _values.update { it + reply.values }
            is Message.Err -> _notices.emit(Notice.Failed(null, reply.code, reply.msg))
            null -> _notices.emit(Notice.Timeout(null))
            else -> Unit
        }
    }

    private suspend fun requestPairing() {
        _pairing.value = PairingState.Starting
        when (val reply = request(Request.Pair)) {
            null -> _pairing.value = PairingState.Failed(PairingError.NO_CONNECTION)
            is Message.Err -> _pairing.value = PairingState.Failed(
                when (reply.code) {
                    ErrorCode.BUSY -> PairingError.BUSY
                    else -> PairingError.UNSUPPORTED
                }
            )
            else -> Unit // the `pairing` message follows the ack
        }
    }

    private fun handle(message: Message) {
        val seq = message.seq
        if (seq != null) {
            pending.remove(seq)?.complete(message)
            return
        }
        when (message) {
            is Message.Event -> {
                _values.update { it + message.values }
                _events.tryEmit(message.values)
            }
            is Message.Pairing -> _pairing.value = when (message.state) {
                "waiting" -> PairingState.Waiting(
                    method = message.method.orEmpty(),
                    deadline = System.currentTimeMillis() + (message.timeout ?: 60) * 1000L,
                    length = message.length?.takeIf { it > 0 } ?: PairingState.DEFAULT_PIN_LENGTH,
                    symbols = message.symbols?.takeIf { it.isNotEmpty() },
                )
                "timeout" -> PairingState.Failed(PairingError.TIMEOUT)
                "rejected" -> PairingState.Failed(PairingError.REJECTED)
                else -> _pairing.value
            }
            is Message.Paired -> scope.launch { onPaired(message.token) }
            else -> Unit
        }
    }

    private suspend fun onPaired(token: String) {
        repository.saveToken(deviceId, token)
        _pairing.value = PairingState.Idle
        // Hello again to receive the lists of readable and writable values.
        val host = repository.device(deviceId).first()?.host ?: return
        val reply = request(Request.Hello(token = token))
        if (reply is Message.Info) onInfo(reply, host, token)
    }

    /** Sends [request] and waits for the reply with the same `seq`; `null` on timeout or closed connection. */
    private suspend fun request(request: Request): Message? {
        val conn = connection ?: return null
        val id = ++seq
        val reply = CompletableDeferred<Message>()
        pending[id] = reply
        try {
            if (!conn.send(Protocol.encode(request, id))) return null
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { reply.await() }.getOrNull() }
        } finally {
            pending.remove(id)
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 3000L
        const val MIN_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
