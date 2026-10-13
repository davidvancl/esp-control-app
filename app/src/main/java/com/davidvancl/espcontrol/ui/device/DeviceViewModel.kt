package com.davidvancl.espcontrol.ui.device

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidvancl.espcontrol.audio.PcmDecoder
import com.davidvancl.espcontrol.audio.TrackInfo
import com.davidvancl.espcontrol.data.DeviceEntry
import com.davidvancl.espcontrol.data.DeviceRepository
import com.davidvancl.espcontrol.device.DeviceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** A song picked by the user; [track] is `null` while its title and duration are being read. */
data class MusicChoice(val uri: Uri, val track: TrackInfo?)

class DeviceViewModel(
    private val repository: DeviceRepository,
    val deviceId: String,
) : ViewModel() {

    val session = DeviceSession(deviceId, repository, viewModelScope)

    val entry: StateFlow<DeviceEntry?> =
        repository.device(deviceId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Song chosen for the coin-jar. Kept here because the device screen leaves the composition
     * while reconnecting, e.g. after returning from the file picker.
     */
    private val _musicChoice = MutableStateFlow<MusicChoice?>(null)
    val musicChoice: StateFlow<MusicChoice?> = _musicChoice.asStateFlow()

    private val lastSent = mutableMapOf<String, Long>()
    private val trailing = mutableMapOf<String, Job>()

    /**
     * Sets a value from a continuously changing control (slider): sends at most one `set`
     * per [THROTTLE_MS] for each id, and always the final value.
     */
    fun setThrottled(id: String, value: JsonElement) {
        trailing.remove(id)?.cancel()
        val wait = (lastSent[id] ?: 0L) + THROTTLE_MS - SystemClock.elapsedRealtime()
        if (wait <= 0) {
            send(id, value)
        } else {
            trailing[id] = viewModelScope.launch {
                delay(wait)
                trailing.remove(id)
                send(id, value)
            }
        }
    }

    fun set(id: String, value: JsonElement?, onAck: (() -> Unit)? = null) {
        trailing.remove(id)?.cancel()
        send(id, value, onAck)
    }

    private fun send(id: String, value: JsonElement?, onAck: (() -> Unit)? = null) {
        lastSent[id] = SystemClock.elapsedRealtime()
        session.set(id, value, onAck)
    }

    /** Remembers [uri] and reads its title and duration. */
    fun chooseMusic(context: Context, uri: Uri) {
        _musicChoice.value = MusicChoice(uri, null)
        val appContext = context.applicationContext
        viewModelScope.launch {
            val track = PcmDecoder.probe(appContext, uri)
            _musicChoice.update { if (it?.uri == uri) it.copy(track = track) else it }
        }
    }

    fun unpair() {
        viewModelScope.launch { session.unpair() }
    }

    fun forget(done: () -> Unit) {
        viewModelScope.launch {
            session.unpair()
            session.stop()
            repository.forget(deviceId)
            done()
        }
    }

    override fun onCleared() {
        session.stop()
    }

    private companion object {
        const val THROTTLE_MS = 100L
    }
}
