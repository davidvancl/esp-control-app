package com.davidvancl.espcontrol.ui.device

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colors as the devices send them: `"#RRGGBB"`. */
object HexColor {
    private val pattern = Regex("#[0-9A-Fa-f]{6}")

    /** `null` for anything but `#RRGGBB` (either case). */
    fun parse(text: String?): Color? {
        if (text == null || !pattern.matches(text)) return null
        return Color(0xFF000000 or text.substring(1).toLong(16))
    }

    /** `#RRGGBB` in upper case; alpha is ignored. */
    fun format(color: Color): String = listOf(color.red, color.green, color.blue).joinToString("", prefix = "#") {
        (it * 255).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    }

    /** [hue] 0–360, [saturation] and [value] 0–1, to `#RRGGBB`. */
    fun fromHsv(hue: Float, saturation: Float, value: Float): String {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return format(Color(r + m, g + m, b + m))
    }

    /** Hue 0–360, saturation and value 0–1 of a `#RRGGBB` color, `null` if it is invalid. */
    fun toHsv(text: String?): FloatArray? {
        val color = parse(text) ?: return null
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
            max == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }
        return floatArrayOf((hue + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
    }
}

/**
 * The coin count from the device's values; `null` until the device has sent it (`state` after
 * connecting, or `event`). Never 0 by default: the jar stores the count, the app must not guess it.
 */
fun coinCount(values: Map<String, JsonElement>): Int? = (values["coins"] as? JsonPrimitive)?.intOrNull

/** `lightColors`: the 5 LED colors as `#RRGGBB`, `null` unless the device sent 5 valid colors. */
fun lightColors(value: JsonElement?): List<String>? {
    val colors = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    if (colors == null || colors.size != LED_COUNT || colors.any { HexColor.parse(it) == null }) return null
    return colors.map { it!!.uppercase() }
}

const val LED_COUNT = 5
