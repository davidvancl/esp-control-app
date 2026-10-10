package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.davidvancl.espcontrol.R
import kotlin.math.roundToInt

/** A titled card; [subtitle] explains what the section does. */
@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/**
 * Slider for an integer value. While the user drags it, incoming values are ignored,
 * so events from the device do not make it jump. A `null` [value] is shown as unknown.
 */
@Composable
fun IntSlider(
    label: String,
    value: Int?,
    range: IntRange,
    onChange: (Int) -> Unit,
    format: (Int) -> String = { it.toString() },
    color: Color? = null,
) {
    var local by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!dragging) local = null
    }
    val shown = local ?: value?.toFloat()
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                shown?.let { format(it.roundToInt()) } ?: stringResource(R.string.unknown_value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = shown ?: range.first.toFloat(),
            onValueChange = {
                dragging = true
                if (it.roundToInt() != local?.roundToInt()) onChange(it.roundToInt())
                local = it
            },
            onValueChangeFinished = { dragging = false },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 20) range.last - range.first - 1 else 0,
            colors = if (color != null) {
                SliderDefaults.colors(thumbColor = color, activeTrackColor = color, activeTickColor = color)
            } else {
                SliderDefaults.colors()
            },
        )
    }
}

/** Dialog for an integer in 0..[max]; [onBlank] adds a button that clears the value (ikarus displays). */
@Composable
fun NumberDialog(
    title: String,
    value: Int?,
    max: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onBlank: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 0..max }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(max.toString().length) },
                supportingText = { Text(stringResource(R.string.range_hint, 0, max)) },
                isError = text.isNotEmpty() && parsed == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            Row {
                if (onBlank != null) {
                    TextButton(onClick = onBlank) { Text(stringResource(R.string.ikarus_blank)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Asks before an action that cannot be undone. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
