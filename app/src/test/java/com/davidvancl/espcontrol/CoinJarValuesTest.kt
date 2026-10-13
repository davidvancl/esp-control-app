package com.davidvancl.espcontrol

import androidx.compose.ui.graphics.Color
import com.davidvancl.espcontrol.ui.device.HexColor
import com.davidvancl.espcontrol.ui.device.coinCount
import com.davidvancl.espcontrol.ui.device.lightColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** coin-jar values from DESIGN.md: `coins`, `lightColors`, `flashColor`. */
class CoinJarValuesTest {

    private fun values(json: String): Map<String, JsonElement> = Json.parseToJsonElement(json).jsonObject

    @Test
    fun parseHex() {
        assertEquals(Color(0xFFFFA040), HexColor.parse("#FFA040"))
        assertEquals(Color(0xFFFFA040), HexColor.parse("#ffa040"))
        assertEquals(Color.Black, HexColor.parse("#000000"))
    }

    @Test
    fun invalidHex() {
        listOf(null, "", "FFA040", "#FFA04", "#FFA0400", "#GGGGGG", "#FFA040 ", "red", "#FFFFFFFF").forEach {
            assertNull("'$it'", HexColor.parse(it))
        }
    }

    @Test
    fun formatHexUpperCase() {
        assertEquals("#FFA040", HexColor.format(Color(0xFFFFA040)))
        assertEquals("#0A0B0C", HexColor.format(Color(0x800A0B0C))) // alpha ignored
        assertEquals("#FFFFFF", HexColor.format(Color.White))
    }

    @Test
    fun hexRoundTrip() {
        for (hex in listOf("#000000", "#FFFFFF", "#FFA040", "#FFA000", "#123456", "#ABCDEF", "#010203")) {
            assertEquals(hex, HexColor.format(HexColor.parse(hex)!!))
        }
    }

    @Test
    fun hsvToHex() {
        assertEquals("#FF0000", HexColor.fromHsv(0f, 1f, 1f))
        assertEquals("#FFFF00", HexColor.fromHsv(60f, 1f, 1f))
        assertEquals("#00FF00", HexColor.fromHsv(120f, 1f, 1f))
        assertEquals("#00FFFF", HexColor.fromHsv(180f, 1f, 1f))
        assertEquals("#0000FF", HexColor.fromHsv(240f, 1f, 1f))
        assertEquals("#FF00FF", HexColor.fromHsv(300f, 1f, 1f))
        assertEquals("#FF0000", HexColor.fromHsv(360f, 1f, 1f))
        assertEquals("#FFFFFF", HexColor.fromHsv(200f, 0f, 1f))
        assertEquals("#000000", HexColor.fromHsv(200f, 1f, 0f))
        assertEquals("#808080", HexColor.fromHsv(0f, 0f, 0.5f))
    }

    @Test
    fun hsvClampsOutOfRange() {
        assertEquals("#FF0000", HexColor.fromHsv(-360f, 2f, 5f))
        assertEquals("#FFFFFF", HexColor.fromHsv(0f, -1f, 1f))
    }

    @Test
    fun hexToHsvAndBack() {
        for (hex in listOf("#FFA040", "#FFA000", "#00FFC0", "#8000FF", "#FF40A0", "#FFFFFF", "#202020", "#123456")) {
            val hsv = HexColor.toHsv(hex)!!
            assertEquals(hex, HexColor.fromHsv(hsv[0], hsv[1], hsv[2]))
        }
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), HexColor.toHsv("#FF0000"), 0.001f)
        assertNull(HexColor.toHsv("nope"))
    }

    @Test
    fun coinCountNotLoadedIsNotZero() {
        assertNull(coinCount(emptyMap()))
        assertNotEquals(0, coinCount(emptyMap()))
        assertNull(coinCount(values("""{"coins":null}""")))
        assertNull(coinCount(values("""{"coins":"x"}""")))
        assertEquals(0, coinCount(values("""{"coins":0}""")))
        assertEquals(142, coinCount(values("""{"coins":142,"volume":2000}""")))
    }

    @Test
    fun parseLightColors() {
        val five = values("""{"lightColors":["#FFA040","#ff0000","#00FF00","#0000FF","#FFFFFF"]}""")
        assertEquals(listOf("#FFA040", "#FF0000", "#00FF00", "#0000FF", "#FFFFFF"), lightColors(five["lightColors"]))
        assertNull(lightColors(null))
        assertNull(lightColors(JsonPrimitive("#FFA040")))
        assertNull(lightColors(values("""{"c":["#FFA040","#FFA040"]}""")["c"]))
        assertNull(lightColors(values("""{"c":["#FFA040","#FFA040","#FFA040","#FFA040","bad"]}""")["c"]))
        assertNull(lightColors(values("""{"c":["#FFA040","#FFA040","#FFA040","#FFA040",5]}""")["c"]))
    }
}
