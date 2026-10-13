package com.davidvancl.espcontrol

import com.davidvancl.espcontrol.audio.BlockWriter
import com.davidvancl.espcontrol.audio.LinearResampler
import com.davidvancl.espcontrol.audio.Pcm
import com.davidvancl.espcontrol.audio.StreamFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Conversion to the coin-jar stream format: PCM s16le, mono, 22050 Hz (DESIGN.md, `coin-jar`). */
class PcmTest {

    @Test
    fun streamFormat() {
        assertEquals(2205, StreamFormat.BLOCK_SAMPLES)
        assertEquals(4410, StreamFormat.BLOCK_BYTES)
    }

    @Test
    fun downmixAveragesChannels() {
        val stereo = shortArrayOf(100, 300, -1000, 1000, 32767, 32767, -32768, -32768)
        assertArrayEquals(shortArrayOf(200, 0, 32767, -32768), Pcm.downmix(stereo, 2))
    }

    @Test
    fun downmixSixChannels() {
        val frame = shortArrayOf(6, 6, 6, 0, 0, 0)
        assertArrayEquals(shortArrayOf(3, 3), Pcm.downmix(frame + frame, 6))
    }

    @Test
    fun downmixMonoIsUnchanged() {
        val mono = shortArrayOf(1, 2, 3)
        assertArrayEquals(mono, Pcm.downmix(mono, 1))
    }

    @Test
    fun floatToS16ScalesAndClips() {
        val out = Pcm.floatToS16(floatArrayOf(0f, 1f, -1f, 0.5f, 1.5f, -2f))
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 16384, 32767, -32768), out)
    }

    @Test
    fun u8ToS16() {
        assertArrayEquals(shortArrayOf(-32768, 0, 32512), Pcm.u8ToS16(byteArrayOf(0, 128.toByte(), 255.toByte())))
    }

    @Test
    fun writeLittleEndian() {
        val bytes = ByteArray(8)
        Pcm.writeLe(shortArrayOf(0x1234, -2, 0x7FFF, -32768), bytes)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()),
            bytes,
        )
    }

    @Test
    fun resample44100HalvesLength() {
        val out = resampleInBlocks(sine(440.0, 44100, 44100), 44100, blockSize = 1152)
        assertEquals(22050, out.size)
    }

    @Test
    fun resample48000Length() {
        val out = resampleInBlocks(sine(440.0, 48000, 48000), 48000, blockSize = 1024)
        assertTrue("length ${out.size}", abs(out.size - 22050) <= 1)
    }

    @Test
    fun resampleKeepsFrequency() {
        for (rate in listOf(44100, 48000)) {
            val out = resampleInBlocks(sine(1000.0, rate, rate), rate, blockSize = 777)
            assertEquals("rate $rate", 1000.0, frequency(out, StreamFormat.SAMPLE_RATE), 5.0)
            // Linear interpolation of a smooth signal stays close to the ideal sine.
            val error = out.indices.maxOf { abs(out[it] - IDEAL_AMPLITUDE * sin(2 * PI * 1000.0 * it / 22050)) }
            assertTrue("rate $rate error $error", error < IDEAL_AMPLITUDE * 0.02)
        }
    }

    @Test
    fun resampleDoesNotDependOnBlockSize() {
        val input = sine(440.0, 48000, 10_000)
        assertArrayEquals(resampleInBlocks(input, 48000, 10_000), resampleInBlocks(input, 48000, 333))
    }

    @Test
    fun sameRatePassesThrough() {
        val input = shortArrayOf(1, 2, 3, 4)
        assertArrayEquals(input, LinearResampler(22050).process(input))
    }

    @Test
    fun blocksArePaddedWithSilence() {
        val writer = BlockWriter()
        val blocks = writer.add(ShortArray(StreamFormat.BLOCK_SAMPLES + 10) { 0x0101 })
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].all { it == 1.toByte() })
        val last = writer.flush()!!
        assertEquals(StreamFormat.BLOCK_BYTES, last.size)
        assertTrue(last.take(20).all { it == 1.toByte() })
        assertTrue(last.drop(20).all { it == 0.toByte() })
        assertNull(writer.flush())
    }

    private fun sine(freq: Double, rate: Int, count: Int) =
        ShortArray(count) { (IDEAL_AMPLITUDE * sin(2 * PI * freq * it / rate)).roundToInt().toShort() }

    private fun resampleInBlocks(input: ShortArray, rate: Int, blockSize: Int): ShortArray {
        val resampler = LinearResampler(rate)
        return input.toList().chunked(blockSize)
            .map { resampler.process(it.toShortArray()) }
            .fold(ShortArray(0)) { acc, block -> acc + block }
    }

    /** Frequency from the number of rising zero crossings. */
    private fun frequency(samples: ShortArray, rate: Int): Double {
        val crossings = (1 until samples.size).count { samples[it - 1] < 0 && samples[it] >= 0 }
        return crossings * rate.toDouble() / samples.size
    }

    private companion object {
        const val IDEAL_AMPLITUDE = 10_000.0
    }
}
