package com.davidvancl.espcontrol.ui.device

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.davidvancl.espcontrol.R
import com.davidvancl.espcontrol.audio.MusicState
import com.davidvancl.espcontrol.audio.TrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/** Screen for device type `coin-jar`, values as listed in DESIGN.md "Device types". */
@Composable
fun CoinJarScreen(
    values: Map<String, JsonElement>,
    events: Flow<Map<String, JsonElement>>,
    onSet: (String, JsonElement?) -> Unit,
    onSetContinuous: (String, JsonElement) -> Unit,
    onRefresh: () -> Unit,
    /** Sets `coins` to 0; the caller confirms the `ack` to the user. */
    onResetCoins: () -> Unit,
    music: MusicState,
    musicChoice: MusicChoice?,
    onPickMusic: () -> Unit,
    onPlayMusic: (Uri, TrackInfo) -> Unit,
    onStopMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun int(id: String) = (values[id] as? JsonPrimitive)?.intOrNull
    val streaming = music is MusicState.Connecting || music is MusicState.Playing
    val musicPlaying = streaming || (values["music"] as? JsonPrimitive)?.booleanOrNull == true

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoinsSection(
            coins = coinCount(values),
            events = events,
            onSetCoins = { onSet("coins", JsonPrimitive(it)) },
            onReset = onResetCoins,
        )

        LightSection(
            light = (values["light"] as? JsonPrimitive)?.booleanOrNull,
            colors = lightColors(values["lightColors"]),
            brightness = int("brightness"),
            flashColor = (values["flashColor"] as? JsonPrimitive)?.contentOrNull?.takeIf { HexColor.parse(it) != null },
            onSet = onSet,
            onSetContinuous = onSetContinuous,
        )

        Section(stringResource(R.string.coin_jar_sound), stringResource(R.string.coin_jar_sound_hint)) {
            IntSlider(
                label = stringResource(R.string.coin_jar_volume),
                value = int("volume"),
                range = 0..MAX_VOLUME,
                onChange = { onSetContinuous("volume", JsonPrimitive(it)) },
            )
            // While music plays the jar only flashes the LEDs.
            Button(onClick = { onSet("chime", null) }, enabled = !musicPlaying, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.coin_jar_chime))
            }
        }

        MusicSection(
            music = music,
            choice = musicChoice,
            deviceMusic = musicPlaying,
            bufferMs = int("musicBuffer"),
            onPick = onPickMusic,
            onPlay = onPlayMusic,
            // Music from another phone is stopped on the jar directly.
            onStop = { if (streaming) onStopMusic() else onSet("music", JsonPrimitive(false)) },
        )

        DiagnosticsSection(
            barrier = (values["barrier"] as? JsonPrimitive)?.booleanOrNull,
            sensor = int("sensor"),
            baseline = int("baseline"),
            onRefresh = onRefresh,
        )
    }
}

@Composable
private fun CoinsSection(
    coins: Int?,
    events: Flow<Map<String, JsonElement>>,
    onSetCoins: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val current by rememberUpdatedState(coins)

    // A new coin: the number grows briefly and flashes gold. Lower values (reset, correction) are not animated.
    LaunchedEffect(events) {
        var last = current
        events.collect { values ->
            val count = coinCount(values) ?: return@collect
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
                if (coins == null) {
                    // The jar stores the count; until it has sent it, show loading, never 0.
                    LoadingCount()
                } else {
                    Text(
                        coins.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = lerp(MaterialTheme.colorScheme.onSurface, GOLD, glow.value),
                        modifier = Modifier.scale(scale.value),
                    )
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
            message = stringResource(R.string.coin_jar_edit_message),
            onDismiss = { editing = false },
            onConfirm = {
                editing = false
                onSetCoins(it)
            },
        )
    }
    if (confirmReset && coins != null) {
        ConfirmDialog(
            title = stringResource(R.string.coin_jar_reset_title),
            text = pluralStringResource(R.plurals.coin_jar_reset_message, coins, coins),
            confirm = stringResource(R.string.coin_jar_reset_confirm),
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                onReset()
            },
        )
    }
}

/** Pulsing placeholder in place of the count. */
@Composable
private fun LoadingCount() {
    val pulse = rememberInfiniteTransition(label = "loading")
    val alpha by pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "alpha",
    )
    Text(
        "…",
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(alpha),
    )
    Text(
        stringResource(R.string.coin_jar_coins_loading),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Permanent light, LED colors, brightness and the coin flash color; the jar resets them on reboot. */
@Composable
private fun LightSection(
    light: Boolean?,
    colors: List<String>?,
    brightness: Int?,
    flashColor: String?,
    onSet: (String, JsonElement?) -> Unit,
    onSetContinuous: (String, JsonElement) -> Unit,
) {
    // What the color picker is open for: an LED index, ALL_LEDS or FLASH.
    var picking by remember { mutableStateOf<Int?>(null) }

    Section(stringResource(R.string.coin_jar_light), stringResource(R.string.coin_jar_light_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.coin_jar_light_on), modifier = Modifier.weight(1f))
            Switch(
                checked = light == true,
                onCheckedChange = { onSet("light", JsonPrimitive(it)) },
                enabled = light != null,
            )
        }

        Text(stringResource(R.string.coin_jar_light_colors), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (led in 0 until LED_COUNT) {
                val hex = colors?.get(led)
                ColorDot(
                    color = HexColor.parse(hex),
                    contentDescription = stringResource(R.string.coin_jar_led, led + 1, hex.orEmpty()),
                    size = 48.dp,
                    onClick = if (colors != null) ({ picking = led }) else null,
                )
            }
        }
        OutlinedButton(onClick = { picking = ALL_LEDS }, enabled = colors != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.coin_jar_light_all))
        }

        IntSlider(
            label = stringResource(R.string.coin_jar_brightness),
            value = brightness,
            range = 1..MAX_BRIGHTNESS,
            onChange = { onSetContinuous("brightness", JsonPrimitive(it)) },
            format = { brightnessText(it) },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.coin_jar_flash_color), modifier = Modifier.weight(1f))
            ColorDot(
                color = HexColor.parse(flashColor),
                contentDescription = stringResource(R.string.coin_jar_flash_color_value, flashColor.orEmpty()),
                onClick = if (flashColor != null) ({ picking = FLASH }) else null,
            )
        }

        Text(
            stringResource(R.string.coin_jar_light_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val target = picking ?: return
    ColorPickerDialog(
        title = when (target) {
            ALL_LEDS -> stringResource(R.string.coin_jar_light_all)
            FLASH -> stringResource(R.string.coin_jar_flash_color)
            else -> stringResource(R.string.coin_jar_led_title, target + 1)
        },
        initial = when (target) {
            ALL_LEDS -> colors?.distinct()?.singleOrNull()
            FLASH -> flashColor
            else -> colors?.get(target)
        },
        onDismiss = { picking = null },
        onConfirm = { hex ->
            picking = null
            when (target) {
                ALL_LEDS -> onSet("lightColors", JsonPrimitive(hex)) // one color: the jar sets all 5 LEDs
                FLASH -> onSet("flashColor", JsonPrimitive(hex))
                else -> colors?.let { current ->
                    val updated = current.toMutableList().also { it[target] = hex }
                    onSet("lightColors", JsonArray(updated.map(::JsonPrimitive)))
                }
            }
        },
    )
}

/** Brightness 1–255 with its percentage, e.g. "80 · 31 %". */
private fun brightnessText(value: Int) = "$value · ${(value * 100f / MAX_BRIGHTNESS).roundToInt()} %"

/**
 * Song choice and playback. [music] is the stream of [MusicStreamService] to this jar,
 * [deviceMusic] whether the jar plays music from any phone.
 */
@Composable
private fun MusicSection(
    music: MusicState,
    choice: MusicChoice?,
    deviceMusic: Boolean,
    bufferMs: Int?,
    onPick: () -> Unit,
    onPlay: (Uri, TrackInfo) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val track = choice?.track
    fun play() {
        val chosen = choice ?: return
        onPlay(chosen.uri, chosen.track ?: return)
    }
    // Without the notification permission the music plays anyway.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { play() }

    val title = track?.title ?: music.title

    Section(stringResource(R.string.coin_jar_music), stringResource(R.string.coin_jar_music_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title ?: stringResource(R.string.coin_jar_music_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (title == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                track?.durationMs?.let {
                    Text(
                        formatTime(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onPick) {
                Text(stringResource(R.string.coin_jar_music_pick))
            }
        }

        if (deviceMusic) {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.coin_jar_music_stop))
            }
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        play()
                    }
                },
                enabled = track != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.coin_jar_music_play))
            }
        }

        when {
            music is MusicState.Playing -> {
                val duration = music.durationMs
                if (duration != null && duration > 0) {
                    LinearProgressIndicator(
                        progress = { (music.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (duration != null) {
                            stringResource(R.string.coin_jar_music_progress, formatTime(music.positionMs), formatTime(duration))
                        } else {
                            formatTime(music.positionMs)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    BufferState(bufferMs)
                }
            }
            music is MusicState.Connecting -> MusicStatus(stringResource(R.string.music_connecting))
            music is MusicState.Error -> MusicStatus(stringResource(music.reason.text), MaterialTheme.colorScheme.error)
            deviceMusic -> Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.coin_jar_music_elsewhere),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                BufferState(bufferMs)
            }
            title != null -> MusicStatus(stringResource(R.string.coin_jar_music_stopped))
        }

        Text(
            stringResource(R.string.coin_jar_music_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MusicStatus(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun BufferState(bufferMs: Int?) {
    if (bufferMs == null) return
    Text(
        if (bufferMs > 0) {
            stringResource(R.string.coin_jar_music_buffer, bufferMs)
        } else {
            stringResource(R.string.coin_jar_music_waiting)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
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
private const val MAX_BRIGHTNESS = 255
private const val ALL_LEDS = -1
private const val FLASH = -2
private const val DIAGNOSTICS_POLL_MS = 1000L
private val GOLD = Color(0xFFE0A800)
private val OK = Color(0xFF2E9D46)
