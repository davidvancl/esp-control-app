package com.davidvancl.espcontrol.ui.device

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidvancl.espcontrol.data.DeviceEntry
import com.davidvancl.espcontrol.data.DeviceRepository
import com.davidvancl.espcontrol.device.DeviceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class DeviceViewModel(
    private val repository: DeviceRepository,
    val deviceId: String,
) : ViewModel() {

    val session = DeviceSession(deviceId, repository, viewModelScope)

    val entry: StateFlow<DeviceEntry?> =
        repository.device(deviceId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    fun set(id: String, value: JsonElement?) {
        trailing.remove(id)?.cancel()
        send(id, value)
    }

    private fun send(id: String, value: JsonElement?) {
        lastSent[id] = SystemClock.elapsedRealtime()
        session.set(id, value)
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
