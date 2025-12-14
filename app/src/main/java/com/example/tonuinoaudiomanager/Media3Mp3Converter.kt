package com.example.tonuinoaudiomanager

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.github.axet.lamejni.Lame
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Media3Mp3Converter(private val context: Context) {

    suspend fun convertToMp3(sourceUri: Uri, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting conversion for uri=$sourceUri")
            val sink = Mp3EncodingAudioSink(outputStream)
            val renderersFactory = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf(
                    MediaCodecAudioRenderer(
                        context,
                        MediaCodecSelector.DEFAULT,
                        handler,
                        audioListener,
                        sink
                    )
                )
            }
            val player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context, renderersFactory).build()
            }
            try {
                prepareAndPlay(player, sourceUri)
                awaitCompletion(player, sourceUri)
                sink.finishIfNeeded()
                Log.i(TAG, "Conversion completed for uri=$sourceUri")
            } finally {
                withContext(Dispatchers.Main) {
                    player.release()
                }
                sink.release()
            }
        }
    }

    private suspend fun prepareAndPlay(player: ExoPlayer, uri: Uri) {
        withContext(Dispatchers.Main) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        }
    }

    private suspend fun awaitCompletion(player: ExoPlayer, uri: Uri) {
        val completion = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error during conversion", error)
                if (!completion.isCompleted) {
                    completion.completeExceptionally(
                        AudioConversionException("Failed to decode audio", error)
                    )
                }
            }
        }

        withContext(Dispatchers.Main) {
            player.addListener(listener)
        }
        try {
            completion.await()
        } finally {
            withContext(Dispatchers.Main) {
                player.removeListener(listener)
            }
        }
    }

    companion object {
        private const val TAG = "Media3Mp3Converter"
    }
}

@OptIn(UnstableApi::class)
private class Mp3EncodingAudioSink(
    private val outputStream: OutputStream,
    private val bitRateKbps: Int = DEFAULT_BITRATE,
    private val quality: Int = DEFAULT_QUALITY
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false
    private var sampleRate = 0
    private var inputChannelCount = 0
    private var outputChannelCount = 0
    private var pcmEncoding = C.ENCODING_INVALID
    private var channelMap: IntArray? = null
    private var encoder: Lame? = null
    private var framesWritten = 0L
    private var ended = false
    private var lastConfiguredFormat: Format? = null

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        return format.sampleMimeType == MimeTypes.AUDIO_RAW
    }

    override fun getFormatSupport(format: Format): Int {
        return if (supportsFormat(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        return if (sampleRate > 0) {
            (framesWritten * C.MICROS_PER_SECOND) / sampleRate
        } else {
            AudioSink.CURRENT_POSITION_NOT_SET
        }
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        finishEncoding()

        val targetSampleRate = inputFormat.sampleRate.takeUnless { it == Format.NO_VALUE }
            ?: throw AudioSink.ConfigurationException("Missing sample rate", inputFormat)
        val targetInputChannels = inputFormat.channelCount.takeUnless { it == Format.NO_VALUE }
            ?: throw AudioSink.ConfigurationException("Missing channel count", inputFormat)
        val desiredOutputChannels = outputChannels?.size ?: targetInputChannels
        val targetOutputChannels = desiredOutputChannels.coerceIn(1, MAX_OUTPUT_CHANNELS)

        val encoding = when (val pcm = inputFormat.pcmEncoding) {
            Format.NO_VALUE, C.ENCODING_PCM_16BIT -> C.ENCODING_PCM_16BIT
            C.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
            else -> throw AudioSink.ConfigurationException(
                "Unsupported PCM encoding: $pcm",
                inputFormat
            )
        }

        sampleRate = targetSampleRate
        inputChannelCount = targetInputChannels
        outputChannelCount = targetOutputChannels
        pcmEncoding = encoding
        channelMap = buildChannelMap(outputChannels, targetInputChannels, targetOutputChannels)
        framesWritten = 0
        ended = false
        lastConfiguredFormat = inputFormat
        encoder = Lame().apply {
            open(outputChannelCount, sampleRate, bitRateKbps, quality)
        }
    }

    override fun play() {
        ended = false
    }

    override fun handleDiscontinuity() {
        listener?.onPositionDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        val format = lastConfiguredFormat
        if (format == null || encoder == null || sampleRate == 0 || outputChannelCount == 0) {
            throw AudioSink.WriteException(-1, format ?: Format.Builder().build(), false)
        }

        try {
            when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> processPcm16(buffer)
                C.ENCODING_PCM_FLOAT -> processPcmFloat(buffer)
                else -> throw AudioSink.WriteException(-1, format, false)
            }
        } finally {
            buffer.position(buffer.limit())
        }

        return true
    }

    override fun playToEndOfStream() {
        finishEncoding()
        ended = true
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = !ended

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit

    override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null

    override fun setAudioSessionId(audioSessionId: Int) = Unit

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit

    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) = Unit

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = Unit

    override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET

    override fun enableTunnelingV21() = Unit

    override fun disableTunneling() = Unit

    override fun setOffloadMode(offloadMode: Int) = Unit

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setVolume(volume: Float) = Unit

    override fun pause() = Unit

    override fun flush() {
        framesWritten = 0
        ended = false
    }

    override fun reset() {
        finishEncoding()
        framesWritten = 0
        ended = false
    }

    override fun release() {
        finishEncoding()
    }

    fun finishIfNeeded() {
        if (!ended) {
            playToEndOfStream()
        }
    }

    private fun processPcm16(buffer: ByteBuffer) {
        val shorts = ShortArray(buffer.remaining() / java.lang.Short.BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        encodeShorts(shorts)
    }

    private fun processPcmFloat(buffer: ByteBuffer) {
        val floats = FloatArray(buffer.remaining() / java.lang.Float.BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        encodeFloats(floats)
    }

    private fun buildChannelMap(
        providedMap: IntArray?,
        inputChannels: Int,
        outputChannels: Int
    ): IntArray {
        if (outputChannels == 1) {
            return intArrayOf(0)
        }

        val baseMap = providedMap?.take(outputChannels)?.toIntArray()
        if (baseMap != null && baseMap.isNotEmpty()) {
            return baseMap
        }

        // Prefer the first two channels; duplicate if source is mono.
        val firstChannel = 0
        val secondChannel = if (inputChannels > 1) 1 else 0
        return intArrayOf(firstChannel, secondChannel)
    }

    private fun encodeShorts(samples: ShortArray) {
        val encoderInstance = encoder ?: return
        val mapped = mapShortChannels(samples)
        val encoded = encoderInstance.encode(mapped, 0, mapped.size)
        if (encoded.isNotEmpty()) {
            outputStream.write(encoded)
        }
        framesWritten += mapped.size / outputChannelCount
    }

    private fun encodeFloats(samples: FloatArray) {
        val encoderInstance = encoder ?: return
        val mapped = mapFloatChannels(samples)
        val encoded = encoderInstance.encode_float(mapped, 0, mapped.size)
        if (encoded.isNotEmpty()) {
            outputStream.write(encoded)
        }
        framesWritten += mapped.size / outputChannelCount
    }

    private fun mapShortChannels(samples: ShortArray): ShortArray {
        val map = channelMap ?: return samples
        val frames = samples.size / inputChannelCount
        val result = ShortArray(frames * outputChannelCount)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannelCount
            val outputBase = frame * outputChannelCount
            for (channel in 0 until outputChannelCount) {
                val sourceIndex = map[channel]
                val safeIndex = min(sourceIndex, inputChannelCount - 1)
                result[outputBase + channel] = samples[inputBase + safeIndex]
            }
        }
        return result
    }

    private fun mapFloatChannels(samples: FloatArray): FloatArray {
        val map = channelMap ?: return samples
        val frames = samples.size / inputChannelCount
        val result = FloatArray(frames * outputChannelCount)
        for (frame in 0 until frames) {
            val inputBase = frame * inputChannelCount
            val outputBase = frame * outputChannelCount
            for (channel in 0 until outputChannelCount) {
                val sourceIndex = map[channel]
                val safeIndex = min(sourceIndex, inputChannelCount - 1)
                result[outputBase + channel] = samples[inputBase + safeIndex]
            }
        }
        return result
    }

    private fun finishEncoding() {
        val encoderInstance = encoder ?: return
        runCatching {
            val flushBytes = encoderInstance.encode(ShortArray(0), 0, 0)
            if (flushBytes.isNotEmpty()) {
                outputStream.write(flushBytes)
            }
        }
        runCatching {
            val tail = encoderInstance.close()
            if (tail.isNotEmpty()) {
                outputStream.write(tail)
            }
        }
        encoder = null
        ended = true
    }

    companion object {
        private const val DEFAULT_BITRATE = 192
        private const val DEFAULT_QUALITY = 2
        private const val MAX_OUTPUT_CHANNELS = 2
    }
}
