package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.device.FailReason
import com.davidvancl.espcontrol.device.PairingState
import com.davidvancl.espcontrol.device.Notice
import com.davidvancl.espcontrol.device.SessionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(viewModel: DeviceViewModel, onBack: () -> Unit) {
    val session = viewModel.session
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val info by session.info.collectAsStateWithLifecycle()
    val values by session.values.collectAsStateWithLifecycle()
    val pairing by session.pairing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmRestart by remember { mutableStateOf(false) }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.restart_title, info?.name ?: entry?.name.orEmpty())) },
            text = { Text(stringResource(R.string.restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    session.restart()
                }) { Text(stringResource(R.string.restart)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Connected only while the app is in the foreground.
    LifecycleStartEffect(session) {
        session.start()
        onStopOrDispose { session.stop() }
    }

    LaunchedEffect(session, resources) {
        session.notices.collect { notice ->
            val text = when (notice) {
                is Notice.Timeout -> resources.getString(R.string.error_timeout)
                is Notice.Failed -> resources.getString(R.string.error_request, notice.id ?: notice.code, notice.message)
                Notice.Restarting -> resources.getString(R.string.restarting)
                Notice.RestartUnsupported -> resources.getString(R.string.restart_unsupported)
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(info?.name ?: entry?.name ?: viewModel.deviceId)
                        Text(
                            listOfNotNull(
                                stringResource(
                                    when (status) {
                                        SessionStatus.Online -> R.string.status_online_short
                                        SessionStatus.NeedsPairing -> R.string.status_pairing_short
                                        SessionStatus.Connecting -> R.string.status_connecting
                                        else -> R.string.status_offline_short
                                    }
                                ),
                                (info?.fw ?: entry?.fw)?.ifEmpty { null }?.let { stringResource(R.string.firmware, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == SessionStatus.Online) ONLINE else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (status == SessionStatus.Online) {
                        IconButton(onClick = session::refresh) {
                            Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh))
                        }
                    }
                    DeviceMenu(
                        online = status == SessionStatus.Online,
                        onRestart = { confirmRestart = true },
                        onUnpair = viewModel::unpair,
                        onForget = { viewModel.forget(onBack) },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            status == SessionStatus.NeedsPairing || pairing != PairingState.Idle && status != SessionStatus.Online ->
                PairingContent(
                    state = pairing,
                    onPair = session::pair,
                    onPin = session::submitPin,
                    onCancel = session::cancelPairing,
                    modifier = modifier,
                )
            status == SessionStatus.Online -> {
                val current = info
                when (current?.type) {
                    "ikarus" -> IkarusScreen(
                        values = values,
                        events = session.events,
                        onSet = viewModel::set,
                        onSetContinuous = viewModel::setThrottled,
                        modifier = modifier,
                    )
                    else -> GenericDeviceScreen(
                        type = current?.type.orEmpty(),
                        readable = current?.get.orEmpty(),
                        writable = current?.set.orEmpty(),
                        values = values,
                        onSet = viewModel::set,
                        modifier = modifier,
                    )
                }
            }
            else -> StatusContent(status, entry?.host, modifier)
        }
    }
}

@Composable
private fun StatusContent(status: SessionStatus, host: String?, modifier: Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status == SessionStatus.Connecting || status == SessionStatus.Offline) CircularProgressIndicator()
            Text(
                stringResource(
                    when (status) {
                        SessionStatus.Offline -> R.string.status_offline
                        is SessionStatus.Failed -> when (status.reason) {
                            FailReason.NO_ADDRESS -> R.string.status_no_address
                            FailReason.PROTOCOL -> R.string.status_protocol
                        }
                        else -> R.string.status_connecting
                    }
                ),
                textAlign = TextAlign.Center,
            )
            if (host != null) Text(host)
        }
    }
}

@Composable
private fun DeviceMenu(online: Boolean, onRestart: () -> Unit, onUnpair: () -> Unit, onForget: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (online) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restart_menu)) },
                onClick = {
                    open = false
                    onRestart()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.unpair)) },
                onClick = {
                    open = false
                    onUnpair()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.forget)) },
            onClick = {
                open = false
                onForget()
            },
        )
    }
}
private val ONLINE = Color(0xFF2E9D46)
