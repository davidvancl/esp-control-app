package com.davidvancl.espcontrol.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.data.DeviceEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    localNetworkDenied: Boolean,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        // Pull down to search again and check which devices are reachable.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (localNetworkDenied) {
                    item {
                        Text(stringResource(R.string.local_network_denied), color = MaterialTheme.colorScheme.error)
                    }
                }
                items(devices, key = { it.id }) { device ->
                    DeviceCard(device, onClick = { onOpen(device.id) })
                }
                if (devices.none { it.online }) {
                    item { Searching() }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceEntry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TypeBadge(device.type)
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name.ifEmpty { device.id }, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        device.type.ifEmpty { null },
                        device.fw.ifEmpty { null }?.let { stringResource(R.string.firmware, it) },
                        stringResource(if (device.paired) R.string.paired else R.string.not_paired),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(10.dp).background(
                        if (device.online) ONLINE else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                )
                Text(
                    stringResource(if (device.online) R.string.online else R.string.offline),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** First letter of the device type in a circle. */
@Composable
private fun TypeBadge(type: String) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            type.take(1).uppercase().ifEmpty { "?" },
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Searching() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.searching), textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.searching_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

private val ONLINE = Color(0xFF2E7D32)
