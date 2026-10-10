package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
                when {
                    state.method != "pin" -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.pairing_confirm))
                    }
                    state.symbols != null ->
                        SymbolPinEntry(state.length, state.symbols, state.wrongAttempts, onPin)
                    else -> PinEntry(state.length, state.wrongAttempts, onPin)
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

/** Numeric PIN shown on the device display (ikarus). */
@Composable
private fun PinEntry(length: Int, wrongAttempts: Int, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(wrongAttempts) {
        pin = ""
    }
    val submit = { if (pin.length == length) onPin(pin) }
    Text(stringResource(R.string.pairing_pin))
    OutlinedTextField(
        value = pin,
        onValueChange = { new ->
            pin = new.filter(Char::isDigit).take(length)
            if (pin.length == length) onPin(pin)
        },
        label = { Text(stringResource(R.string.pairing_pin_label)) },
        isError = wrongAttempts > 0,
        supportingText = if (wrongAttempts > 0) {
            { Text(stringResource(R.string.pairing_wrong_pin)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
}

/**
 * PIN made of [symbols] shown by the device its own way (coin-jar: blinking colors). The user taps
 * the symbols in the same order; the PIN sent is their indices as digits, e.g. blue, red, red, yellow → "2003".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPinEntry(length: Int, symbols: List<String>, wrongAttempts: Int, onPin: (String) -> Unit) {
    val entered = remember { mutableStateListOf<Int>() }
    LaunchedEffect(wrongAttempts) {
        entered.clear()
    }

    Text(stringResource(R.string.pairing_symbols), textAlign = TextAlign.Center)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (slot in 0 until length) {
            val symbol = entered.getOrNull(slot)?.let(symbols::get)
            SymbolSlot(symbol)
        }
    }
    if (wrongAttempts > 0 && entered.isEmpty()) {
        Text(stringResource(R.string.pairing_wrong_sequence), color = MaterialTheme.colorScheme.error)
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        symbols.forEachIndexed { index, symbol ->
            SymbolButton(symbol, enabled = entered.size < length) {
                entered.add(index)
                if (entered.size == length) onPin(entered.joinToString("") { it.toString() })
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { entered.removeLastOrNull() }, enabled = entered.isNotEmpty()) {
            Text(stringResource(R.string.pairing_delete))
        }
        OutlinedButton(onClick = { entered.clear() }, enabled = entered.isNotEmpty()) {
            Text(stringResource(R.string.pairing_restart_entry))
        }
    }
}

@Composable
private fun SymbolSlot(symbol: String?) {
    val color = symbol?.let(::symbolColor)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (symbol != null && color == null) Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SymbolButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val color = symbolColor(symbol)
    val label = symbolLabel(symbol)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(64.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color ?: MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) {
                Text(symbol, style = MaterialTheme.typography.titleLarge)
            }
        }
        // Name under every color for color-blind users.
        if (color != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/** `#RRGGBB` symbols are colors; anything else is shown as text. */
private fun symbolColor(symbol: String): Color? {
    if (!symbol.startsWith("#") || symbol.length != 7) return null
    val rgb = symbol.substring(1).toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}

@Composable
private fun symbolLabel(symbol: String): String = when (symbol.uppercase()) {
    "#FF0000" -> stringResource(R.string.color_red)
    "#00FF00" -> stringResource(R.string.color_green)
    "#0000FF" -> stringResource(R.string.color_blue)
    "#FFFF00" -> stringResource(R.string.color_yellow)
    "#FF00FF" -> stringResource(R.string.color_purple)
    else -> symbol
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
