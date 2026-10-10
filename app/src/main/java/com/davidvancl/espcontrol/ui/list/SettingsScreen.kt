package com.davidvancl.espcontrol.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davidvancl.espcontrol.BuildConfig
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.protocol.PROTOCOL_VERSION

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: DeviceListViewModel, onBack: () -> Unit) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val known = devices.filter { it.known }
    val resources = LocalResources.current
    var host by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }

    val add = {
        val address = host.trim()
        if (address.isNotEmpty() && !busy) {
            busy = true
            result = null
            viewModel.addManually(address) { info ->
                busy = false
                result = if (info != null) {
                    host = ""
                    resources.getString(R.string.added, info.name.ifEmpty { info.id })
                } else {
                    resources.getString(R.string.add_failed, address)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.add_by_ip), style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.ip_address)) },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = add, enabled = !busy && host.isNotBlank()) {
                        Text(stringResource(if (busy) R.string.adding else R.string.add))
                    }
                }
            }
            result?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }

            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.known_devices),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (known.isEmpty()) {
                item { Text(stringResource(R.string.no_known_devices)) }
            }
            items(known, key = { it.id }) { device ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name.ifEmpty { device.id })
                        Text(
                            listOfNotNull(device.type, device.host, device.id).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { viewModel.forget(device.id) }) {
                        Text(stringResource(R.string.remove))
                    }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.about, BuildConfig.VERSION_NAME, PROTOCOL_VERSION),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
