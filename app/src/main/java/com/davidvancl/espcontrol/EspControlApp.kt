package com.davidvancl.espcontrol

import android.app.Application
import com.davidvancl.espcontrol.data.DeviceRepository
import kotlinx.coroutines.MainScope

class EspControlApp : Application() {
    val repository: DeviceRepository by lazy { DeviceRepository(this, MainScope()) }
}
