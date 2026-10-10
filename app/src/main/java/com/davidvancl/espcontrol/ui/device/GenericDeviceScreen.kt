package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.davidvancl.espcontrol.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Fallback for device types the app does not know: raw readable values and a JSON input per writable id. */
@Composable
fun GenericDeviceScreen(
    type: String,
    readable: List<String>,
    writable: List<String>,
    values: Map<String, JsonElement>,
    onSet: (String, JsonElement?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.generic_unknown_type, type), style = MaterialTheme.typography.bodyMedium)

        val shown = (readable + values.keys).distinct()
        if (shown.isNotEmpty()) {
            Section(stringResource(R.string.generic_values)) {
                for (id in shown) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(id)
                        Text(
                            values[id]?.toString() ?: stringResource(R.string.unknown_value),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        if (writable.isNotEmpty()) {
            Section(stringResource(R.string.generic_controls)) {
                for (id in writable) JsonInput(id, onSend = { onSet(id, it) })
            }
        }
    }
}

@Composable
private fun JsonInput(id: String, onSend: (JsonElement?) -> Unit) {
    var text by rememberSaveable(id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(id) },
            placeholder = { Text(stringResource(R.string.generic_value_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onSend(parse(text)) }) {
            Text(stringResource(R.string.send))
        }
    }
}

/** Empty input sends an action without a value; text that is not valid JSON is sent as a string. */
private fun parse(text: String): JsonElement? {
    if (text.isBlank()) return null
    return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}
