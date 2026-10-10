package com.davidvancl.espcontrol.ui.list

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidvancl.espcontrol.data.DeviceEntry
import com.davidvancl.espcontrol.data.DeviceRepository
import com.davidvancl.espcontrol.protocol.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceListViewModel(private val repository: DeviceRepository) : ViewModel() {

    val devices: StateFlow<List<DeviceEntry>> = repository.devices

    private var discovery: Job? = null

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun startDiscovery() {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch { repository.discover() }
    }

    fun stopDiscovery() {
        discovery?.cancel()
        discovery = null
    }

    /** Pull to refresh: restarts mDNS discovery and checks every known device right away. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                discovery?.let { running ->
                    discovery = null
                    running.cancelAndJoin()
                    startDiscovery()
                }
                val started = SystemClock.elapsedRealtime()
                repository.checkReachabilityNow()
                // Keep the indicator visible for a moment, a fast check would only flash it.
                delay((MIN_REFRESH_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Adds a device by IP address; [done] receives its identity, or `null` if it did not answer. */
    fun addManually(host: String, done: (Message.Info?) -> Unit) {
        viewModelScope.launch { done(repository.probe(host.trim())) }
    }

    fun forget(id: String) {
        viewModelScope.launch { repository.forget(id) }
    }

    private companion object {
        const val MIN_REFRESH_MS = 600L
    }
}
