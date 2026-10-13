package com.davidvancl.espcontrol.audio

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Audio format of the coin-jar stream (DESIGN.md, `coin-jar` "Audio stream"):
 * PCM signed 16-bit little-endian, mono, 22050 Hz, sent in blocks of 100 ms.
 */
object StreamFormat {
    const val SAMPLE_RATE = 22050
    const val BLOCK_MS = 100
    const val BLOCK_SAMPLES = SAMPLE_RATE * BLOCK_MS / 1000
    const val BLOCK_BYTES = BLOCK_SAMPLES * 2
}

/** Pure PCM conversions used by [PcmDecoder], without Android dependencies. */
object Pcm {

    /** Float samples (−1…1) to signed 16 bit, clipped. */
    fun floatToS16(input: FloatArray, count: Int = input.size): ShortArray =
        ShortArray(count) { (input[it] * Short.MAX_VALUE).roundToInt().coerceIn(-32768, 32767).toShort() }

    /** Unsigned 8-bit samples to signed 16 bit. */
    fun u8ToS16(input: ByteArray, count: Int = input.size): ShortArray =
        ShortArray(count) { (((input[it].toInt() and 0xFF) - 128) shl 8).toShort() }

    /** Interleaved samples of [channels] channels to mono, the average of all channels. */
    fun downmix(input: ShortArray, channels: Int, count: Int = input.size): ShortArray {
        if (channels == 1) return if (count == input.size) input else input.copyOf(count)
        return ShortArray(count / channels) { frame ->
            var sum = 0
            val base = frame * channels
            for (c in 0 until channels) sum += input[base + c]
            (sum / channels).toShort()
        }
    }

    /** Writes [count] samples starting at [from] to [dest] at [offset] as 16-bit little-endian. */
    fun writeLe(samples: ShortArray, dest: ByteArray, offset: Int = 0, from: Int = 0, count: Int = samples.size - from) {
        for (i in 0 until count) {
            val s = samples[from + i].toInt()
            dest[offset + 2 * i] = s.toByte()
            dest[offset + 2 * i + 1] = (s shr 8).toByte()
        }
    }
}

/**
 * Converts a mono stream from [inRate] to [outRate] by linear interpolation. The stream is
 * passed in blocks of any size; the position and the last sample are kept between blocks,
 * so the output does not depend on where the input is split.
 */
class LinearResampler(private val inRate: Int, private val outRate: Int = StreamFormat.SAMPLE_RATE) {
    private val step = inRate.toDouble() / outRate

    /** Position of the next output sample, in input samples from the start of the next block; −1 is [previous]. */
    private var position = 0.0
    private var previous: Short = 0

    fun process(input: ShortArray, count: Int = input.size): ShortArray {
        if (count == 0) return ShortArray(0)
        if (inRate == outRate) return input.copyOf(count)
        val out = ShortArray(((count - position) / step).toInt() + 2)
        var n = 0
        while (position <= count - 1) {
            val index = floor(position).toInt()
            val frac = position - index
            val a = if (index < 0) previous.toInt() else input[index].toInt()
            val b = if (frac == 0.0) a else input[index + 1].toInt()
            out[n++] = (a + (b - a) * frac).roundToInt().toShort()
            position += step
        }
        position -= count
        previous = input[count - 1]
        return out.copyOf(n)
    }
}

/** Collects mono samples into [StreamFormat.BLOCK_BYTES] blocks of 16-bit little-endian PCM. */
class BlockWriter {
    private var block = ByteArray(StreamFormat.BLOCK_BYTES)
    private var filled = 0

    /** Adds samples and returns the blocks completed by them. */
    fun add(samples: ShortArray): List<ByteArray> {
        val done = mutableListOf<ByteArray>()
        var from = 0
        while (from < samples.size) {
            val n = minOf(samples.size - from, (block.size - filled) / 2)
            Pcm.writeLe(samples, block, filled, from, n)
            filled += 2 * n
            from += n
            if (filled == block.size) {
                done += block
                block = ByteArray(StreamFormat.BLOCK_BYTES)
                filled = 0
            }
        }
        return done
    }

    /** The last incomplete block filled with silence, or `null` if there is none. */
    fun flush(): ByteArray? {
        if (filled == 0) return null
        val last = block // the rest of a new array is already zero = silence
        block = ByteArray(StreamFormat.BLOCK_BYTES)
        filled = 0
        return last
    }
}
