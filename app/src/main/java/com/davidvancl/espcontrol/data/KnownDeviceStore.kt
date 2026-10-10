package com.davidvancl.espcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A device the app has seen or paired with, identified by [id] (chip id), never by IP. */
@Serializable
data class KnownDevice(
    val id: String,
    val name: String,
    val type: String,
    val fw: String = "",
    val lastHost: String? = null,
    val token: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "devices")

class KnownDeviceStore(private val context: Context) {

    private val key = stringPreferencesKey("known_devices")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(KnownDevice.serializer())

    val devices: Flow<Map<String, KnownDevice>> = context.dataStore.data.map { prefs ->
        val text = prefs[key] ?: return@map emptyMap()
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList()).associateBy { it.id }
    }

    /** Applies [change] to the device with [id]; returning `null` removes it. */
    suspend fun update(id: String, change: (KnownDevice?) -> KnownDevice?) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                .orEmpty()
                .associateBy { it.id }
                .toMutableMap()
            val updated = change(current[id])
            if (updated == null) current.remove(id) else current[id] = updated
            prefs[key] = json.encodeToString(serializer, current.values.toList())
        }
    }
}
