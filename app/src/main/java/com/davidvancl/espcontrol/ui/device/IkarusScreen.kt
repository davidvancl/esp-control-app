package com.davidvancl.espcontrol.ui.device

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davidvancl.espcontrol.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Screen for device type `ikarus`, values as listed in DESIGN.md "Device types". */
@Composable
fun IkarusScreen(
    values: Map<String, JsonElement>,
    events: Flow<Map<String, JsonElement>>,
    onSet: (String, JsonElement) -> Unit,
    onSetContinuous: (String, JsonElement) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun int(id: String) = (values[id] as? JsonPrimitive)?.intOrNull
    val slide = { id: String -> { v: Int -> onSetContinuous(id, JsonPrimitive(v)) } }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InputsSection(keyOn = (values["key"] as? JsonPrimitive)?.booleanOrNull, events = events)

        Section(stringResource(R.string.ikarus_displays), stringResource(R.string.ikarus_displays_hint)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((id, label) in listOf("left2" to R.string.ikarus_left, "right2" to R.string.ikarus_right)) {
                    DisplayTile(stringResource(label), values[id], 2, Modifier.weight(1f)) { onSet(id, displayValue(it)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((id, label) in listOf("left4" to R.string.ikarus_left, "right4" to R.string.ikarus_right)) {
                    DisplayTile(stringResource(label), values[id], 4, Modifier.weight(1f)) { onSet(id, displayValue(it)) }
                }
            }
            OutlinedButton(
                onClick = { DISPLAYS.forEach { onSet(it, JsonNull) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ikarus_blank_all))
            }
            IntSlider(
                label = stringResource(R.string.ikarus_intensity),
                value = int("intensity"),
                range = 0..15,
                onChange = slide("intensity"),
                format = { "$it / 15" },
            )
        }

        Section(stringResource(R.string.ikarus_leds), stringResource(R.string.ikarus_leds_hint)) {
            IntSlider(stringResource(R.string.ikarus_green), int("green"), 0..100, slide("green"), { "$it %" }, GREEN)
            IntSlider(stringResource(R.string.ikarus_red), int("red"), 0..100, slide("red"), { "$it %" }, RED)
        }
    }
}

@Composable
private fun InputsSection(keyOn: Boolean?, events: Flow<Map<String, JsonElement>>) {
    val presses = remember { mutableStateMapOf<String, Int>() }
    var last by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(events) {
        events.collect { values ->
            for (button in BUTTONS) {
                if (button.id !in values) continue
                presses[button.id] = (presses[button.id] ?: 0) + 1
                last = button.id to System.currentTimeMillis()
            }
        }
    }

    Section(stringResource(R.string.ikarus_inputs), stringResource(R.string.ikarus_inputs_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ikarus_key), modifier = Modifier.weight(1f))
            KeyState(keyOn)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (button in BUTTONS) {
                key(button.id) {
                    PanelButton(stringResource(button.label), button.color, presses[button.id] ?: 0)
                }
            }
        }
        Text(
            last?.let { (id, time) ->
                stringResource(
                    R.string.ikarus_last_press,
                    stringResource(BUTTONS.first { it.id == id }.label),
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(time)),
                )
            } ?: stringResource(R.string.ikarus_no_press),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyState(on: Boolean?) {
    val (text, container, content) = when (on) {
        true -> Triple(R.string.ikarus_key_on, GREEN, Color.White)
        false -> Triple(R.string.ikarus_key_off, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        null -> Triple(R.string.unknown_value, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A round panel button that lights up for [FLASH_MS] whenever [count] grows. */
@Composable
private fun PanelButton(label: String, color: Color, count: Int) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        lit = true
        delay(FLASH_MS)
        lit = false
    }
    val fill by animateColorAsState(if (lit) color else color.copy(alpha = 0.18f), label = "button")
    val scale by animateFloatAsState(if (lit) 1.15f else 1f, label = "scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(scale)
                .background(fill, CircleShape)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (count > 0) {
                Text(
                    count.toString(),
                    color = if (lit) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/** `null` blanks the display. */
private fun displayValue(number: Int?): JsonElement = number?.let(::JsonPrimitive) ?: JsonNull

/**
 * Looks like the 7-segment display on the panel; tap to enter a new number or blank it.
 * [state] is `null` while unknown (write-only, nothing sent yet), [JsonNull] when blank.
 */
@Composable
private fun DisplayTile(label: String, state: JsonElement?, digits: Int, modifier: Modifier, onSet: (Int?) -> Unit) {
    val value = (state as? JsonPrimitive)?.intOrNull
    val blank = state is JsonNull
    var editing by remember { mutableStateOf(false) }
    val max = if (digits == 2) 99 else 9999
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DISPLAY_BACKGROUND, RoundedCornerShape(8.dp))
                .clickable { editing = true }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    value != null -> value.toString().padStart(digits, '0')
                    blank -> "8".repeat(digits)
                    else -> "-".repeat(digits)
                },
                color = DISPLAY_DIGIT.copy(
                    alpha = when {
                        value != null -> 1f
                        blank -> 0.08f // unlit segments
                        else -> 0.35f
                    }
                ),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 4.sp,
            )
        }
    }
    if (editing) {
        NumberDialog(label, value, max, onDismiss = { editing = false }) {
            editing = false
            onSet(it)
        }
    }
}

/** [onConfirm] receives the number, or `null` to blank the display. */
@Composable
private fun NumberDialog(title: String, value: Int?, max: Int, onDismiss: () -> Unit, onConfirm: (Int?) -> Unit) {
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
                TextButton(onClick = { onConfirm(null) }) { Text(stringResource(R.string.ikarus_blank)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private class PanelButtonSpec(val id: String, val label: Int, val color: Color)

private const val FLASH_MS = 1000L
private val GREEN = Color(0xFF2E9D46)
private val RED = Color(0xFFD32F2F)
private val NEUTRAL = Color(0xFF607D8B)
private val DISPLAY_BACKGROUND = Color(0xFF1A1A1A)
private val DISPLAY_DIGIT = Color(0xFFFF3B30)
private val DISPLAYS = listOf("left2", "right2", "left4", "right4")
private val BUTTONS = listOf(
    PanelButtonSpec("greenButton", R.string.ikarus_green_button, GREEN),
    PanelButtonSpec("redButton", R.string.ikarus_red_button, RED),
    PanelButtonSpec("button1", R.string.ikarus_button1, NEUTRAL),
    PanelButtonSpec("button2", R.string.ikarus_button2, NEUTRAL),
    PanelButtonSpec("button3", R.string.ikarus_button3, NEUTRAL),
)
