package com.davidvancl.espcontrol.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A music file chosen by the user. [durationMs] is `null` if the file does not tell. */
data class TrackInfo(val title: String, val durationMs: Long?)

class DecodeException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason {
        /** The file cannot be opened or read. */
        READ,

        /** The file contains no audio track. */
        NO_AUDIO,

        /** The phone has no decoder for the format, or the decoder failed. */
        UNSUPPORTED,
    }
}

/**
 * Decodes a music file with [MediaExtractor] and [MediaCodec] into the stream format
 * ([StreamFormat]): blocks of 100 ms PCM s16le, mono, 22050 Hz. Formats are what the phone
 * can decode (MP3, AAC/M4A, OGG, FLAC, WAV, …).
 */
class PcmDecoder(private val context: Context, private val uri: Uri) {

    /**
     * Blocks of [StreamFormat.BLOCK_BYTES]; the last one is padded with silence. Decodes on
     * [Dispatchers.IO] a few blocks ahead of the collector. Fails with [DecodeException].
     */
    fun blocks(): Flow<ByteArray> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw DecodeException(DecodeException.Reason.READ, e)
            } catch (e: SecurityException) {
                throw DecodeException(DecodeException.Reason.READ, e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException(DecodeException.Reason.NO_AUDIO)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: IOException) {
                throw DecodeException(DecodeException.Reason.UNSUPPORTED, e)
            } catch (e: IllegalArgumentException) {
                throw DecodeException(DecodeException.Reason.UNSUPPORTED, e)
            } catch (e: IllegalStateException) {
                throw DecodeException(DecodeException.Reason.UNSUPPORTED, e)
            }

            val writer = BlockWriter()
            var output = OutputFormat.of(format)
            var resampler = LinearResampler(output.sampleRate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                // emit() stays outside: its CancellationException is an IllegalStateException too.
                val samples = try {
                    if (!inputDone) inputDone = feed(extractor, codec)
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val changed = OutputFormat.of(codec.outputFormat)
                        if (changed.sampleRate != output.sampleRate) resampler = LinearResampler(changed.sampleRate)
                        output = changed
                    }
                    if (index < 0) continue
                    val buffer = codec.getOutputBuffer(index)
                    val mono = if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        output.toMono(buffer)
                    } else {
                        null
                    }
                    codec.releaseOutputBuffer(index, false)
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    mono
                } catch (e: MediaCodec.CodecException) {
                    throw DecodeException(DecodeException.Reason.UNSUPPORTED, e)
                } catch (e: IllegalStateException) {
                    throw DecodeException(DecodeException.Reason.UNSUPPORTED, e)
                }
                if (samples != null) writer.add(resampler.process(samples)).forEach { emit(it) }
            }
            writer.flush()?.let { emit(it) }
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }.buffer(DECODE_AHEAD_BLOCKS).flowOn(Dispatchers.IO)

    /** Queues the next compressed sample; returns `true` once the end of stream is queued. */
    private fun feed(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        return if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
            false
        }
    }

    /** Decoder output: interleaved PCM in [encoding] (`AudioFormat.ENCODING_PCM_*`). */
    private data class OutputFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {

        /** Converts one output buffer to mono 16 bit at [sampleRate]. */
        fun toMono(buffer: ByteBuffer): ShortArray {
            buffer.order(ByteOrder.nativeOrder())
            val interleaved = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val floats = buffer.asFloatBuffer()
                    Pcm.floatToS16(FloatArray(floats.remaining()).also { floats.get(it) })
                }
                AudioFormat.ENCODING_PCM_8BIT ->
                    Pcm.u8ToS16(ByteArray(buffer.remaining()).also { buffer.get(it) })
                else -> {
                    val shorts = buffer.asShortBuffer()
                    ShortArray(shorts.remaining()).also { shorts.get(it) }
                }
            }
            return Pcm.downmix(interleaved, channels)
        }

        companion object {
            fun of(format: MediaFormat) = OutputFormat(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                },
            )
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val DECODE_AHEAD_BLOCKS = 10

        /** Display name and duration of [uri] for the UI. */
        suspend fun probe(context: Context, uri: Uri): TrackInfo = withContext(Dispatchers.IO) {
            val title = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            val extractor = MediaExtractor()
            val duration = try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                    ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } finally {
                extractor.release()
            }
            TrackInfo(title.substringBeforeLast('.').ifEmpty { title }, duration)
        }
    }
}
