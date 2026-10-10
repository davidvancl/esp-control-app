package com.davidvancl.espcontrol.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davidvancl.espcontrol.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Screen for device type `coin-jar`, values as listed in DESIGN.md "Device types". */
@Composable
fun CoinJarScreen(
    values: Map<String, JsonElement>,
    events: Flow<Map<String, JsonElement>>,
    onSet: (String, JsonElement?) -> Unit,
    onSetContinuous: (String, JsonElement) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun int(id: String) = (values[id] as? JsonPrimitive)?.intOrNull

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoinsSection(
            coins = int("coins"),
            events = events,
            onSetCoins = { onSet("coins", JsonPrimitive(it)) },
        )

        Section(stringResource(R.string.coin_jar_sound), stringResource(R.string.coin_jar_sound_hint)) {
            IntSlider(
                label = stringResource(R.string.coin_jar_volume),
                value = int("volume"),
                range = 0..MAX_VOLUME,
                onChange = { onSetContinuous("volume", JsonPrimitive(it)) },
            )
            Button(onClick = { onSet("chime", null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.coin_jar_chime))
            }
        }

        DiagnosticsSection(
            barrier = (values["barrier"] as? JsonPrimitive)?.booleanOrNull,
            sensor = int("sensor"),
            baseline = int("baseline"),
            onRefresh = onRefresh,
        )
    }
}

@Composable
private fun CoinsSection(coins: Int?, events: Flow<Map<String, JsonElement>>, onSetCoins: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val current by rememberUpdatedState(coins)

    // A new coin: the number grows briefly and flashes gold. Lower values (reset, correction) are not animated.
    LaunchedEffect(events) {
        var last = current
        events.collect { values ->
            val count = (values["coins"] as? JsonPrimitive)?.intOrNull ?: return@collect
            if (last == null || count > (last ?: 0)) {
                launch {
                    scale.snapTo(1.3f)
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
                launch {
                    glow.snapTo(1f)
                    glow.animateTo(0f, tween(durationMillis = 900))
                }
            }
            last = count
        }
    }

    Section(stringResource(R.string.coin_jar_coins), stringResource(R.string.coin_jar_coins_hint)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    coins?.toString() ?: stringResource(R.string.unknown_value),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = lerp(MaterialTheme.colorScheme.onSurface, GOLD, glow.value),
                    modifier = Modifier.scale(scale.value),
                )
                if (coins != null) {
                    Text(
                        pluralStringResource(R.plurals.coin_jar_coins_unit, coins),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { editing = true },
                enabled = coins != null,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.coin_jar_edit))
            }
        }
        OutlinedButton(
            onClick = { confirmReset = true },
            enabled = coins != null && coins > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coin_jar_reset))
        }
    }

    if (editing) {
        NumberDialog(
            title = stringResource(R.string.coin_jar_edit),
            value = coins,
            max = MAX_COINS,
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onSetCoins(it)
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.coin_jar_reset_title),
            text = stringResource(R.string.coin_jar_reset_message),
            confirm = stringResource(R.string.coin_jar_reset_confirm),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                onSetCoins(0)
            },
        )
    }
}

/** Collapsible; while open the values are polled every second because `sensor` has no events. */
@Composable
private fun DiagnosticsSection(barrier: Boolean?, sensor: Int?, baseline: Int?, onRefresh: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")

    LaunchedEffect(expanded) {
        while (expanded) {
            onRefresh()
            delay(DIAGNOSTICS_POLL_MS)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.coin_jar_diagnostics), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.coin_jar_diagnostics_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BarrierState(barrier)
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).rotate(arrow),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagnosticRow(stringResource(R.string.coin_jar_sensor), sensor)
                DiagnosticRow(stringResource(R.string.coin_jar_baseline), baseline)
            }
        }
    }
}

@Composable
private fun BarrierState(ok: Boolean?) {
    val (text, container, content) = when (ok) {
        true -> Triple(R.string.coin_jar_barrier_ok, OK, Color.White)
        false -> Triple(R.string.coin_jar_barrier_failed, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        null -> Triple(R.string.unknown_value, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            stringResource(text),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: Int?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value?.let { stringResource(R.string.coin_jar_adc_value, it, MAX_ADC) } ?: stringResource(R.string.unknown_value),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val MAX_COINS = 1_000_000
private const val MAX_VOLUME = 32767
private const val MAX_ADC = 4095
private const val DIAGNOSTICS_POLL_MS = 1000L
private val GOLD = Color(0xFFE0A800)
private val OK = Color(0xFF2E9D46)
