package com.example.tonuinoaudiomanager

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.axet.lamejni.Lame
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MediaCodecMp3Converter(private val context: Context) {

    suspend fun convertToMp3(
        sourceUri: Uri,
        targetUri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val mainHandler = onProgress?.let { Handler(Looper.getMainLooper()) }
            val progressUpdater: ((Float) -> Unit)? = onProgress?.let { callback ->
                { value -> mainHandler?.post { callback(value.coerceIn(0f, 1f)) } }
            }

            progressUpdater?.invoke(0f)
            Log.i(TAG, "Starting conversion for uri=$sourceUri")
            try {
                val metadata = extractMetadata(sourceUri)
                context.contentResolver.withSyncedOutputStream(
                    targetUri,
                    onUnavailable = { AudioConversionException("Stream unavailable") }
                ) { output ->
                    val tagBytes = buildId3v23Tag(metadata)
                    if (tagBytes.isNotEmpty()) {
                        output.write(tagBytes)
                    }
                    decodeToMp3(sourceUri, output, progressUpdater)
                }
                progressUpdater?.invoke(1f)
                Log.i(TAG, "Conversion completed for uri=$sourceUri")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to convert audio", t)
                throw if (t is AudioConversionException) t
                else AudioConversionException("Failed to convert audio", t)
            }
        }
    }

    private fun decodeToMp3(
        sourceUri: Uri,
        outputStream: OutputStream,
        onProgress: ((Float) -> Unit)?
    ) {
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
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                null
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            pumpCodec(extractor, codec, encoder, durationUs, onProgress)
            encoder.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            encoder.release()
        }
    }

    private fun pumpCodec(
        extractor: MediaExtractor,
        codec: MediaCodec,
        encoder: LamePcmEncoder,
        durationUs: Long?,
        onProgress: ((Float) -> Unit)?
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var pendingFormat = codec.outputFormat
        var lastProgress = -1

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

                        if (durationUs != null && durationUs > 0 && timeUs >= 0) {
                            val percent = ((timeUs.toDouble() / durationUs.toDouble()) * 100).toInt()
                                .coerceIn(0, 100)
                            if (percent != lastProgress) {
                                onProgress?.invoke(percent / 100f)
                                lastProgress = percent
                            }
                        }
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
        onProgress?.invoke(1f)
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun extractMetadata(uri: Uri): RawAudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val art = retriever.embeddedPicture
            RawAudioMetadata(
                title = title,
                album = album,
                artist = artist,
                track = track,
                artwork = art,
                artworkMimeType = art?.let { detectMimeType(it) }
            )
        } catch (_: Throwable) {
            RawAudioMetadata(null, null, null, null, null, null)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        private const val TAG = "MediaCodecMp3Converter"
        private const val TIMEOUT_US = 10_000L
    }
}

private data class RawAudioMetadata(
    val title: String?,
    val album: String?,
    val artist: String?,
    val track: String?,
    val artwork: ByteArray?,
    val artworkMimeType: String?
)

private fun buildId3v23Tag(metadata: RawAudioMetadata): ByteArray {
    val frames = ByteArrayOutputStream()

    fun writeFrame(id: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val idBytes = id.toByteArray(Charsets.ISO_8859_1)
        if (idBytes.size != 4) return
        val sizeBytes = intToBytes(payload.size)
        frames.write(idBytes)
        frames.write(sizeBytes)
        frames.write(byteArrayOf(0x00, 0x00)) // flags
        frames.write(payload)
    }

    fun writeTextFrame(id: String, text: String?) {
        if (text.isNullOrBlank()) return
        val data = ByteArrayOutputStream().apply {
            write(0x01) // UTF-16 with BOM
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write(text.toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        writeFrame(id, data)
    }

    metadata.title?.let { writeTextFrame("TIT2", it) }
    metadata.album?.let { writeTextFrame("TALB", it) }
    metadata.artist?.let { writeTextFrame("TPE1", it) }
    metadata.track?.let { writeTextFrame("TRCK", it) }

    if (metadata.artwork != null && metadata.artworkMimeType != null) {
        val pictureData = ByteArrayOutputStream().apply {
            write(0x00) // ISO-8859-1 encoding for MIME and description
            write(metadata.artworkMimeType.toByteArray(Charsets.ISO_8859_1))
            write(0x00)
            write(0x03) // Front cover
            write(0x00) // Empty description (NUL-terminated)
            write(metadata.artwork)
        }.toByteArray()
        writeFrame("APIC", pictureData)
    }

    val frameBytes = frames.toByteArray()
    if (frameBytes.isEmpty()) return ByteArray(0)

    val tagSize = frameBytes.size
    val header = ByteArrayOutputStream().apply {
        write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
        write(byteArrayOf(0x03, 0x00)) // ID3v2.3
        write(0x00) // flags
        write(toSynchSafe(tagSize))
    }.toByteArray()

    return header + frameBytes
}

private fun detectMimeType(data: ByteArray): String {
    return when {
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
        data.size >= 8 &&
                data[0] == 0x89.toByte() &&
                data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() &&
                data[3] == 0x47.toByte() -> "image/png"

        else -> "image/*"
    }
}

private fun intToBytes(value: Int): ByteArray {
    return byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun toSynchSafe(value: Int): ByteArray {
    val b1 = (value shr 21) and 0x7F
    val b2 = (value shr 14) and 0x7F
    val b3 = (value shr 7) and 0x7F
    val b4 = value and 0x7F
    return byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte())
}

private class LamePcmEncoder(
    private val output: OutputStream,
    private val bitRateKbps: Int = DEFAULT_BITRATE,
    private val quality: Int = DEFAULT_QUALITY
) {
    private val force16BitPcm = true
    private var lame: Lame? = null
    private var configured = false
    private var inputChannels = 0
    private var outputChannels = 0
    private var pcmEncoding = AudioFormat.ENCODING_INVALID
    private var sampleRate = 0
    private var channelMap: IntArray? = null
    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)
    private var mappedShortScratch = ShortArray(0)
    private var mappedFloatScratch = FloatArray(0)
    private var mappedCount = 0

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
        // Force mono output.
        outputChannels = 1
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
            AudioFormat.ENCODING_PCM_FLOAT -> {
                if (force16BitPcm) {
                    encodeFloatsAsShorts(buffer)
                } else {
                    encodeFloats(buffer)
                }
            }
            else -> throw AudioConversionException("Unsupported PCM encoding: $encoding")
        }
    }

    private fun encodeShorts(buffer: ByteBuffer) {
        val shortBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = shortBuffer.remaining()
        val samples = ensureShortCapacity(sampleCount)
        shortBuffer.get(samples, 0, sampleCount)
        val mapped = mapShortChannels(samples, sampleCount)
        val encoded = lame?.encode(mapped, 0, mappedCount)
        if (encoded != null && encoded.isNotEmpty()) output.write(encoded)
    }

    private fun encodeFloats(buffer: ByteBuffer) {
        val floatBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val sampleCount = floatBuffer.remaining()
        val samples = ensureFloatCapacity(sampleCount)
        floatBuffer.get(samples, 0, sampleCount)
        val mapped = mapFloatChannels(samples, sampleCount)
        val encoded = lame?.encode_float(mapped, 0, mappedCount)
        if (encoded != null && encoded.isNotEmpty()) output.write(encoded)
    }

    private fun encodeFloatsAsShorts(buffer: ByteBuffer) {
        val floatBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val sampleCount = floatBuffer.remaining()
        val floatSamples = ensureFloatCapacity(sampleCount)
        floatBuffer.get(floatSamples, 0, sampleCount)
        val shortSamples = ensureShortCapacity(sampleCount)
        for (i in 0 until sampleCount) {
            val value = floatSamples[i]
            val clamped = when {
                value > 1f -> 1f
                value < -1f -> -1f
                else -> value
            }
            shortSamples[i] = (clamped * 32767f).toInt().toShort()
        }
        val mapped = mapShortChannels(shortSamples, sampleCount)
        val encoded = lame?.encode(mapped, 0, mappedCount)
        if (encoded != null && encoded.isNotEmpty()) output.write(encoded)
    }

    private fun mapShortChannels(samples: ShortArray, sampleCount: Int): ShortArray {
        if (outputChannels == 1) {
            val frames = sampleCount / inputChannels
            val result = ensureMappedShortCapacity(frames)
            for (frame in 0 until frames) {
                val inputBase = frame * inputChannels
                var sum = 0
                for (channel in 0 until inputChannels) {
                    sum += samples[inputBase + channel].toInt()
                }
                result[frame] = (sum / inputChannels).toShort()
            }
            mappedCount = frames
            return result
        }
        val map = channelMap ?: run {
            mappedCount = sampleCount
            return samples
        }
        val frames = sampleCount / inputChannels
        val outputCount = frames * outputChannels
        val result = ensureMappedShortCapacity(outputCount)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannels
            val outputBase = frame * outputChannels
            for (channel in 0 until outputChannels) {
                val sourceIndex = min(map[channel], inputChannels - 1)
                result[outputBase + channel] = samples[inputBase + sourceIndex]
            }
        }
        mappedCount = outputCount
        return result
    }

    private fun mapFloatChannels(samples: FloatArray, sampleCount: Int): FloatArray {
        if (outputChannels == 1) {
            val frames = sampleCount / inputChannels
            val result = ensureMappedFloatCapacity(frames)
            for (frame in 0 until frames) {
                val inputBase = frame * inputChannels
                var sum = 0f
                for (channel in 0 until inputChannels) {
                    sum += samples[inputBase + channel]
                }
                result[frame] = sum / inputChannels
            }
            mappedCount = frames
            return result
        }
        val map = channelMap ?: run {
            mappedCount = sampleCount
            return samples
        }
        val frames = sampleCount / inputChannels
        val outputCount = frames * outputChannels
        val result = ensureMappedFloatCapacity(outputCount)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannels
            val outputBase = frame * outputChannels
            for (channel in 0 until outputChannels) {
                val sourceIndex = min(map[channel], inputChannels - 1)
                result[outputBase + channel] = samples[inputBase + sourceIndex]
            }
        }
        mappedCount = outputCount
        return result
    }

    private fun ensureShortCapacity(size: Int): ShortArray {
        if (shortScratch.size < size) {
            shortScratch = ShortArray(size)
        }
        return shortScratch
    }

    private fun ensureFloatCapacity(size: Int): FloatArray {
        if (floatScratch.size < size) {
            floatScratch = FloatArray(size)
        }
        return floatScratch
    }

    private fun ensureMappedShortCapacity(size: Int): ShortArray {
        if (mappedShortScratch.size < size) {
            mappedShortScratch = ShortArray(size)
        }
        return mappedShortScratch
    }

    private fun ensureMappedFloatCapacity(size: Int): FloatArray {
        if (mappedFloatScratch.size < size) {
            mappedFloatScratch = FloatArray(size)
        }
        return mappedFloatScratch
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
        private const val DEFAULT_BITRATE = 128
        private const val DEFAULT_QUALITY = 6
        private const val MAX_OUTPUT_CHANNELS = 1
    }
}
