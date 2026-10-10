package com.davidvancl.espcontrol.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
