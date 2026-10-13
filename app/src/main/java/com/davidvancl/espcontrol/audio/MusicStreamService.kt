package com.davidvancl.espcontrol.audio

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.davidvancl.espcontrol.EspControlApp
import com.davidvancl.espcontrol.MainActivity
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.net.ConnectionState
import com.davidvancl.espcontrol.net.DeviceConnection
import com.davidvancl.espcontrol.protocol.Message
import com.davidvancl.espcontrol.protocol.Protocol
import com.davidvancl.espcontrol.protocol.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** State of the music stream, for the coin-jar screen and the notification. */
sealed interface MusicState {
    /** The coin-jar the music goes to, `null` when idle. */
    val deviceId: String? get() = null
    val title: String? get() = null

    data object Idle : MusicState

    /** Connecting, or reconnecting after the connection was lost. */
    data class Connecting(override val deviceId: String, override val title: String) : MusicState

    data class Playing(
        override val deviceId: String,
        override val title: String,
        val positionMs: Long,
        val durationMs: Long?,
    ) : MusicState

    /** The stream ended with an error; stays until the next song. */
    data class Error(override val deviceId: String, override val title: String, val reason: MusicError) : MusicState
}

enum class MusicError(@param:StringRes val text: Int) {
    READ(R.string.music_error_read),
    NO_AUDIO(R.string.music_error_no_audio),
    UNSUPPORTED(R.string.music_error_unsupported),
    CONNECTION(R.string.music_error_connection),
    NOT_PAIRED(R.string.music_error_not_paired),
    REJECTED(R.string.music_error_rejected),
}

/**
 * Streams a music file to a coin-jar (DESIGN.md, `coin-jar` "Audio stream"). A foreground service
 * with its own WebSocket connection, because the connection of the device screen is closed when
 * the app goes to the background; the jar accepts 3 clients and sends its events to all of them.
 */
class MusicStreamService : Service() {

    private val scope = MainScope()
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.music_channel))
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val track = intent?.takeIf { it.action == ACTION_PLAY }?.let(Track::from)
        if (track == null) {
            stopStreaming()
            return START_NOT_STICKY
        }
        startInForeground(notification(track, getString(R.string.music_connecting), ongoing = true))
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val error = stream(track)
            _state.value = if (error == null) MusicState.Idle else MusicState.Error(track.deviceId, track.title, error)
            if (job === currentCoroutineContext().job) {
                job = null
                finish(error?.let { notification(track, getString(it.text), ongoing = false) })
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        if (_state.value !is MusicState.Error) _state.value = MusicState.Idle
        super.onDestroy()
    }

    private fun stopStreaming() {
        val running = job
        job = null
        scope.launch {
            running?.cancelAndJoin()
            _state.value = MusicState.Idle
            if (job == null) finish(null)
        }
    }

    /** Leaves the foreground; an error notification stays visible. */
    private fun finish(errorNotification: Notification?) {
        if (errorNotification != null) {
            stopForeground(STOP_FOREGROUND_DETACH)
            notify(errorNotification)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /**
     * Connects, starts the stream and sends the file paced by the clock and `musicBuffer`.
     * Returns `null` when the song was played to the end or the jar stopped the music.
     */
    private suspend fun stream(track: Track): MusicError? {
        _state.value = MusicState.Connecting(track.deviceId, track.title)
        acquireLocks(track.durationMs)
        var link: MusicLink? = null
        try {
            link = connect(track, attempts = 1)
            val pacer = Pacer()
            var sentMs = 0L
            onPlaying(track)
            PcmDecoder(this, track.uri).blocks().collect { block ->
                while (true) {
                    val current = link ?: return@collect
                    if (current.stoppedByDevice) throw StreamEnd(null)
                    pacer.awaitSlot(current)
                    if (current.sendBinary(block)) break
                    if (!current.isOpen) {
                        // Continue from the current block on a new connection.
                        current.close()
                        link = null
                        _state.value = MusicState.Connecting(track.deviceId, track.title)
                        notify(notification(track, getString(R.string.music_reconnecting), ongoing = true))
                        link = connect(track, attempts = RECONNECT_ATTEMPTS)
                        pacer.restart()
                        onPlaying(track)
                    }
                    // Otherwise the send queue is full (slow WiFi): try again on the next tick.
                }
                sentMs += StreamFormat.BLOCK_MS
                val buffered = link?.bufferMs?.value ?: 0
                _state.value = MusicState.Playing(
                    track.deviceId,
                    track.title,
                    (sentMs - buffered).coerceIn(0, track.durationMs ?: Long.MAX_VALUE),
                    track.durationMs,
                )
            }
            // End of file: let the jar play what it has buffered.
            val last = link
            if (last != null) {
                val buffered = last.bufferMs.value ?: MAX_DEVICE_BUFFER_MS
                withTimeoutOrNull(buffered + DRAIN_EXTRA_MS) {
                    last.bufferMs.first { it == 0 || !last.isOpen || last.stoppedByDevice }
                }
            }
            return null
        } catch (e: StreamEnd) {
            return e.error
        } catch (e: DecodeException) {
            return when (e.reason) {
                DecodeException.Reason.READ -> MusicError.READ
                DecodeException.Reason.NO_AUDIO -> MusicError.NO_AUDIO
                DecodeException.Reason.UNSUPPORTED -> MusicError.UNSUPPORTED
            }
        } finally {
            link?.let {
                it.stopMusic()
                it.close()
            }
            releaseLocks()
        }
    }

    private fun onPlaying(track: Track) {
        _state.value = MusicState.Playing(track.deviceId, track.title, 0, track.durationMs)
        notify(notification(track, getString(R.string.music_playing_on, track.deviceName), ongoing = true))
    }

    /** Connects and starts the stream; tries [attempts] times, 1 s apart. Throws [StreamEnd] on failure. */
    private suspend fun connect(track: Track, attempts: Int): MusicLink {
        var error = MusicError.CONNECTION
        repeat(attempts) { attempt ->
            if (attempt > 0 || attempts > 1) delay(RECONNECT_PAUSE_MS)
            val link = MusicLink(repository().connect(track.host), scope)
            error = link.start(track.deviceId, track.token) ?: return link
            link.close()
            if (error != MusicError.CONNECTION) throw StreamEnd(error)
        }
        throw StreamEnd(error)
    }

    private fun repository() = (application as EspControlApp).repository

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(track: Track, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track.title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_TRANSPORT else NotificationCompat.CATEGORY_ERROR)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (ongoing) {
                    val stop = PendingIntent.getService(
                        this@MusicStreamService,
                        0,
                        Intent(this@MusicStreamService, MusicStreamService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, getString(R.string.music_stop), stop)
                }
            }
            .build()
    }

    /** Without the notification permission (Android 13+) the music plays anyway, just without updates. */
    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun acquireLocks(durationMs: Long?) {
        releaseLocks()
        val timeout = (durationMs ?: DEFAULT_LOCK_MS) + LOCK_EXTRA_MS
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EspControl:music")
            .apply { acquire(timeout) }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "EspControl:music").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Ends [stream] early: [error], or `null` when the jar stopped the music. */
    private class StreamEnd(val error: MusicError?) : Exception()

    /** What to play where; passed in the start intent. */
    private data class Track(
        val deviceId: String,
        val deviceName: String,
        val host: String,
        val token: String,
        val uri: Uri,
        val title: String,
        val durationMs: Long?,
    ) {
        companion object {
            fun from(intent: Intent): Track? {
                return Track(
                    deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return null,
                    deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty(),
                    host = intent.getStringExtra(EXTRA_HOST) ?: return null,
                    token = intent.getStringExtra(EXTRA_TOKEN) ?: return null,
                    uri = intent.data ?: return null,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    durationMs = intent.getLongExtra(EXTRA_DURATION, -1).takeIf { it >= 0 },
                )
            }
        }
    }

    /**
     * Sends one block per 100 ms tick after a start of [PREBUFFER_BLOCKS] at once. Every new
     * `musicBuffer` report below [LOW_BUFFER_MS] adds a block, above [HIGH_BUFFER_MS] skips a tick.
     */
    private class Pacer {
        private var credit = PREBUFFER_BLOCKS
        private var nextTick = SystemClock.elapsedRealtime() + StreamFormat.BLOCK_MS

        fun restart() {
            credit = PREBUFFER_BLOCKS
            nextTick = SystemClock.elapsedRealtime() + StreamFormat.BLOCK_MS
        }

        suspend fun awaitSlot(link: MusicLink) {
            while (credit <= 0) {
                delay(nextTick - SystemClock.elapsedRealtime())
                // Catch up small delays, but do not burst after a long stall.
                nextTick = maxOf(nextTick + StreamFormat.BLOCK_MS, SystemClock.elapsedRealtime() - MAX_LAG_MS)
                credit++
                val reported = link.takeBufferReport() ?: continue
                if (reported < LOW_BUFFER_MS) credit++ else if (reported > HIGH_BUFFER_MS) credit--
            }
            credit--
        }
    }

    /** The service's own connection: hello, `set music` and the `music` / `musicBuffer` events. */
    private class MusicLink(private val connection: DeviceConnection, scope: CoroutineScope) {
        private var seq = 0
        private val replies = Channel<Message>(Channel.UNLIMITED)
        private val bufferReports = Channel<Int>(Channel.CONFLATED)
        private var streaming = false

        private val _bufferMs = MutableStateFlow<Int?>(null)

        /** Last `musicBuffer`, `null` until the jar reports it. */
        val bufferMs: StateFlow<Int?> = _bufferMs.asStateFlow()

        /** The jar sent `music: false` while this link was streaming. */
        var stoppedByDevice = false
            private set

        val isOpen: Boolean get() = connection.state.value == ConnectionState.OPEN

        private val reader = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.incoming.collect { message ->
                if (message.seq != null) {
                    replies.trySend(message)
                } else if (message is Message.Event) {
                    (message.values["musicBuffer"] as? JsonPrimitive)?.intOrNull?.let {
                        _bufferMs.value = it
                        bufferReports.trySend(it)
                    }
                    if ((message.values["music"] as? JsonPrimitive)?.booleanOrNull == false && streaming) {
                        stoppedByDevice = true
                    }
                }
            }
        }

        /** Hello and `set music true`; returns the error or `null` when the stream runs. */
        suspend fun start(deviceId: String, token: String): MusicError? {
            if (connection.state.first { it != ConnectionState.CONNECTING } != ConnectionState.OPEN) {
                return MusicError.CONNECTION
            }
            when (val info = request(Request.Hello(token = token))) {
                is Message.Info -> when {
                    info.id != deviceId -> return MusicError.CONNECTION
                    !info.authed -> return MusicError.NOT_PAIRED
                }
                else -> return MusicError.CONNECTION
            }
            return when (request(Request.Set("music", JsonPrimitive(true)))) {
                is Message.Ack -> {
                    streaming = true
                    null
                }
                is Message.Err -> MusicError.REJECTED
                else -> MusicError.CONNECTION
            }
        }

        fun takeBufferReport(): Int? = bufferReports.tryReceive().getOrNull()

        fun sendBinary(block: ByteArray) = connection.sendBinary(block)

        /** Stops the music on the jar; the buffered audio is discarded. */
        fun stopMusic() {
            if (streaming && isOpen) connection.send(Protocol.encode(Request.Set("music", JsonPrimitive(false)), ++seq))
            streaming = false
        }

        fun close() {
            reader.cancel()
            connection.close()
        }

        private suspend fun request(request: Request): Message? {
            val id = ++seq
            if (!connection.send(Protocol.encode(request, id))) return null
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                var reply: Message
                do reply = replies.receive() while (reply.seq != id)
                reply
            }
        }
    }

    companion object {
        private const val ACTION_PLAY = "com.davidvancl.espcontrol.music.PLAY"
        private const val ACTION_STOP = "com.davidvancl.espcontrol.music.STOP"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_DEVICE_NAME = "deviceName"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "music"
        private const val NOTIFICATION_ID = 1

        private const val PREBUFFER_BLOCKS = 5
        private const val LOW_BUFFER_MS = 300
        private const val HIGH_BUFFER_MS = 600
        private const val MAX_LAG_MS = 500L
        private const val MAX_DEVICE_BUFFER_MS = 1100
        private const val DRAIN_EXTRA_MS = 1500L
        private const val RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_PAUSE_MS = 1000L
        private const val REQUEST_TIMEOUT_MS = 3000L
        private const val DEFAULT_LOCK_MS = 6 * 60 * 60 * 1000L
        private const val LOCK_EXTRA_MS = 10 * 60 * 1000L

        private val _state = MutableStateFlow<MusicState>(MusicState.Idle)
        val state: StateFlow<MusicState> = _state.asStateFlow()

        /** Starts streaming [uri] to the coin-jar; replaces the song that is playing. */
        fun play(
            context: Context,
            deviceId: String,
            deviceName: String,
            host: String,
            token: String,
            uri: Uri,
            track: TrackInfo,
        ) {
            val intent = Intent(context, MusicStreamService::class.java)
                .setAction(ACTION_PLAY)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_TITLE, track.title)
                .putExtra(EXTRA_DURATION, track.durationMs ?: -1L)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (_state.value is MusicState.Connecting || _state.value is MusicState.Playing) {
                context.startService(Intent(context, MusicStreamService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
