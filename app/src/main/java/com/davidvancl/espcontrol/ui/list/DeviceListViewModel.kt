package com.davidvancl.espcontrol.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.davidvancl.espcontrol.data.DeviceEntry
import com.davidvancl.espcontrol.data.DeviceRepository
import com.davidvancl.espcontrol.protocol.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DeviceListViewModel(private val repository: DeviceRepository) : ViewModel() {

    val devices: StateFlow<List<DeviceEntry>> = repository.devices

    private var discovery: Job? = null

    fun startDiscovery() {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch { repository.discover() }
    }

    fun stopDiscovery() {
        discovery?.cancel()
        discovery = null
    }

    /** Adds a device by IP address; [done] receives its identity, or `null` if it did not answer. */
    fun addManually(host: String, done: (Message.Info?) -> Unit) {
        viewModelScope.launch { done(repository.probe(host.trim())) }
    }

    fun forget(id: String) {
        viewModelScope.launch { repository.forget(id) }
    }
}
