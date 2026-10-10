package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.device.PairingError
import com.davidvancl.espcontrol.device.PairingState
import kotlinx.coroutines.delay

@Composable
fun PairingContent(
    state: PairingState,
    onPair: () -> Unit,
    onPin: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)
        when (state) {
            PairingState.Idle -> {
                Text(stringResource(R.string.pairing_intro))
                Button(onClick = onPair) { Text(stringResource(R.string.pair)) }
            }
            PairingState.Starting -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_starting))
            }
            is PairingState.Waiting -> {
                if (state.method == "pin") {
                    PinEntry(wrongPin = state.wrongPin, onPin = onPin)
                } else {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.pairing_confirm))
                }
                Countdown(state.deadline)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            is PairingState.Failed -> {
                Text(
                    stringResource(
                        when (state.reason) {
                            PairingError.TIMEOUT -> R.string.pairing_timeout
                            PairingError.REJECTED -> R.string.pairing_rejected
                            PairingError.BUSY -> R.string.pairing_busy
                            PairingError.UNSUPPORTED -> R.string.pairing_unsupported
                            PairingError.NO_CONNECTION -> R.string.pairing_no_connection
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onPair) { Text(stringResource(R.string.try_again)) }
            }
        }
    }
}

@Composable
private fun PinEntry(wrongPin: Boolean, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(wrongPin) {
        if (wrongPin) pin = ""
    }
    val submit = { if (pin.length == PIN_LENGTH) onPin(pin) }
    Text(stringResource(R.string.pairing_pin))
    OutlinedTextField(
        value = pin,
        onValueChange = { new ->
            pin = new.filter(Char::isDigit).take(PIN_LENGTH)
            if (pin.length == PIN_LENGTH) onPin(pin)
        },
        label = { Text(stringResource(R.string.pairing_pin_label)) },
        isError = wrongPin,
        supportingText = if (wrongPin) {
            { Text(stringResource(R.string.pairing_wrong_pin)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
}

@Composable
private fun Countdown(deadline: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (now < deadline) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((deadline - now) / 1000).coerceAtLeast(0).toInt()
    Text(stringResource(R.string.pairing_remaining, seconds), style = MaterialTheme.typography.bodySmall)
}

private const val PIN_LENGTH = 4
