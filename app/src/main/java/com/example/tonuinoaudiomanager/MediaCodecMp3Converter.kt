package com.example.tonuinoaudiomanager

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.github.axet.lamejni.Lame
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MediaCodecMp3Converter(private val context: Context) {

    suspend fun convertToMp3(sourceUri: Uri, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting conversion for uri=$sourceUri")
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            val encoder = LamePcmEncoder(outputStream)
            try {
                extractor.setDataSource(context, sourceUri, null)
                val trackIndex = selectAudioTrack(extractor)
                    ?: throw AudioConversionException("No audio track found")
                extractor.selectTrack(trackIndex)

                val inputFormat = extractor.getTrackFormat(trackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw AudioConversionException("Missing MIME type")
                if (!inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                pumpCodec(extractor, codec, encoder)
                encoder.finish()
                Log.i(TAG, "Conversion completed for uri=$sourceUri")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to convert audio", t)
                throw if (t is AudioConversionException) t
                else AudioConversionException("Failed to convert audio", t)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                encoder.release()
            }
        }
    }

    private fun pumpCodec(
        extractor: MediaExtractor,
        codec: MediaCodec,
        encoder: LamePcmEncoder
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var pendingFormat = codec.outputFormat

        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        val timeUs = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        codec.queueInputBuffer(inputIndex, 0, size, timeUs, flags)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet.
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    pendingFormat = codec.outputFormat
                    encoder.configureFromFormat(pendingFormat)
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated; handled implicitly by getOutputBuffer.
                }
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (!encoder.isConfigured) {
                                encoder.configureFromFormat(pendingFormat)
                            }
                            encoder.encodeBuffer(outputBuffer)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                    }
                }
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private companion object {
        private const val TAG = "MediaCodecMp3Converter"
        private const val TIMEOUT_US = 10_000L
    }
}

private class LamePcmEncoder(
    private val output: OutputStream,
    private val bitRateKbps: Int = DEFAULT_BITRATE,
    private val quality: Int = DEFAULT_QUALITY
) {
    private var lame: Lame? = null
    private var configured = false
    private var inputChannels = 0
    private var outputChannels = 0
    private var pcmEncoding = AudioFormat.ENCODING_INVALID
    private var sampleRate = 0
    private var channelMap: IntArray? = null

    val isConfigured: Boolean
        get() = configured

    fun configureFromFormat(format: MediaFormat) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        configure(sampleRate, channels, encoding)
    }

    fun configure(sampleRate: Int, channels: Int, encoding: Int) {
        if (configured) {
            if (sampleRate == this.sampleRate &&
                channels == inputChannels &&
                encoding == pcmEncoding
            ) {
                return
            } else {
                throw AudioConversionException("Decoder output format changed unexpectedly.")
            }
        }
        if (sampleRate <= 0) throw AudioConversionException("Missing sample rate.")
        if (channels <= 0) throw AudioConversionException("Missing channel count.")

        inputChannels = channels
        outputChannels = channels.coerceIn(1, MAX_OUTPUT_CHANNELS)
        pcmEncoding = encoding
        this.sampleRate = sampleRate
        channelMap = buildChannelMap(inputChannels, outputChannels)
        lame = Lame().apply {
            open(outputChannels, sampleRate, bitRateKbps, quality)
        }
        configured = true
    }

    fun encodeBuffer(buffer: ByteBuffer) {
        val encoding = pcmEncoding
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> encodeShorts(buffer)
            AudioFormat.ENCODING_PCM_FLOAT -> encodeFloats(buffer)
            else -> throw AudioConversionException("Unsupported PCM encoding: $encoding")
        }
    }

    private fun encodeShorts(buffer: ByteBuffer) {
        val shortBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)
        val mapped = mapShortChannels(samples)
        val encoded = lame?.encode(mapped, 0, mapped.size)
        if (encoded != null && encoded.isNotEmpty()) output.write(encoded)
    }

    private fun encodeFloats(buffer: ByteBuffer) {
        val floatBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floatBuffer.remaining())
        floatBuffer.get(samples)
        val mapped = mapFloatChannels(samples)
        val encoded = lame?.encode_float(mapped, 0, mapped.size)
        if (encoded != null && encoded.isNotEmpty()) output.write(encoded)
    }

    private fun mapShortChannels(samples: ShortArray): ShortArray {
        val map = channelMap ?: return samples
        val frames = samples.size / inputChannels
        val result = ShortArray(frames * outputChannels)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannels
            val outputBase = frame * outputChannels
            for (channel in 0 until outputChannels) {
                val sourceIndex = min(map[channel], inputChannels - 1)
                result[outputBase + channel] = samples[inputBase + sourceIndex]
            }
        }
        return result
    }

    private fun mapFloatChannels(samples: FloatArray): FloatArray {
        val map = channelMap ?: return samples
        val frames = samples.size / inputChannels
        val result = FloatArray(frames * outputChannels)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannels
            val outputBase = frame * outputChannels
            for (channel in 0 until outputChannels) {
                val sourceIndex = min(map[channel], inputChannels - 1)
                result[outputBase + channel] = samples[inputBase + sourceIndex]
            }
        }
        return result
    }

    fun finish() {
        val encoder = lame ?: return
        runCatching {
            val flushBytes = encoder.encode(ShortArray(0), 0, 0)
            if (flushBytes.isNotEmpty()) output.write(flushBytes)
        }
        runCatching {
            val tail = encoder.close()
            if (tail.isNotEmpty()) output.write(tail)
        }
        configured = false
        lame = null
    }

    fun release() {
        finish()
    }

    private fun buildChannelMap(inputChannels: Int, outputChannels: Int): IntArray {
        if (outputChannels == 1) return intArrayOf(0)
        val first = 0
        val second = if (inputChannels > 1) 1 else 0
        return intArrayOf(first, second)
    }

    companion object {
        private const val DEFAULT_BITRATE = 192
        private const val DEFAULT_QUALITY = 2
        private const val MAX_OUTPUT_CHANNELS = 2
    }
}
