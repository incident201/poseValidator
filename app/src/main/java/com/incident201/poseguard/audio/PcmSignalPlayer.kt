package com.incident201.poseguard.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class PcmSignalPlayer : AutoCloseable {
    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var closed = false

    fun play(settings: PcmSignalSettings) {
        val normalized = settings.normalized()
        val samples = generateStereoBuffer(normalized)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .build()
            )
            .setTransferMode(TRANSFER_MODE)
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .build()

        synchronized(lock) {
            if (closed) {
                track.release()
                return
            }
            releaseCurrentTrackLocked()
            audioTrack = track
            try {
                val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (written == samples.size) {
                    track.play()
                } else {
                    releaseCurrentTrackLocked()
                }
            } catch (error: Exception) {
                releaseCurrentTrackLocked()
                throw error
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            releaseCurrentTrackLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            releaseCurrentTrackLocked()
        }
    }

    private fun releaseCurrentTrackLocked() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private fun generateStereoBuffer(settings: PcmSignalSettings): ShortArray {
        val totalFrames = (settings.durationSeconds * SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        val monoSamples = when (settings.pattern) {
            PcmPattern.SingleTone -> generateTone(totalFrames, settings)
            PcmPattern.DoubleBeep -> generateDoubleBeep(totalFrames, settings)
        }
        return ShortArray(totalFrames * STEREO_CHANNEL_COUNT).also { stereo ->
            monoSamples.forEachIndexed { frame, sample ->
                val stereoIndex = frame * STEREO_CHANNEL_COUNT
                when (settings.channel) {
                    PcmChannel.Left -> stereo[stereoIndex] = sample
                    PcmChannel.Right -> stereo[stereoIndex + 1] = sample
                    PcmChannel.Both -> {
                        stereo[stereoIndex] = sample
                        stereo[stereoIndex + 1] = sample
                    }
                }
            }
        }
    }

    private fun generateDoubleBeep(totalFrames: Int, settings: PcmSignalSettings): ShortArray {
        val requestedPauseFrames = (DOUBLE_BEEP_PAUSE_SECONDS * SAMPLE_RATE).roundToInt()
        val maximumPauseFrames = (totalFrames * MAX_PAUSE_FRACTION).roundToInt()
        val pauseFrames = requestedPauseFrames.coerceAtMost(maximumPauseFrames).coerceAtLeast(0)
        val signalFrames = (totalFrames - pauseFrames).coerceAtLeast(1)
        val firstBeepFrames = signalFrames / 2
        val secondBeepFrames = signalFrames - firstBeepFrames
        val result = ShortArray(totalFrames)
        if (firstBeepFrames > 0) {
            generateTone(firstBeepFrames, settings).copyInto(result, destinationOffset = 0)
        }
        if (secondBeepFrames > 0) {
            val secondStart = firstBeepFrames + pauseFrames
            generateTone(secondBeepFrames, settings).copyInto(
                result,
                destinationOffset = secondStart,
                endIndex = minOf(secondBeepFrames, totalFrames - secondStart)
            )
        }
        return result
    }

    private fun generateTone(frameCount: Int, settings: PcmSignalSettings): ShortArray {
        if (frameCount <= 0) return ShortArray(0)
        val peakAmplitude = Short.MAX_VALUE * (settings.amplitudePercent / 100.0)
        val requestedFadeInFrames = settings.fadeInMs * SAMPLE_RATE / MILLIS_PER_SECOND
        val requestedFadeOutFrames = settings.fadeOutMs * SAMPLE_RATE / MILLIS_PER_SECOND
        val fadeScale = if (requestedFadeInFrames + requestedFadeOutFrames > frameCount) {
            frameCount.toDouble() / (requestedFadeInFrames + requestedFadeOutFrames).coerceAtLeast(1)
        } else {
            1.0
        }
        val fadeInFrames = (requestedFadeInFrames * fadeScale).roundToInt().coerceIn(0, frameCount)
        val fadeOutFrames = (requestedFadeOutFrames * fadeScale).roundToInt().coerceIn(0, frameCount)
        val angularStep = 2.0 * PI * settings.frequencyHz / SAMPLE_RATE

        return ShortArray(frameCount) { frame ->
            val fadeInEnvelope = if (fadeInFrames > 0 && frame < fadeInFrames) {
                frame.toDouble() / fadeInFrames
            } else {
                1.0
            }
            val framesFromEnd = frameCount - 1 - frame
            val fadeOutEnvelope = if (fadeOutFrames > 0 && framesFromEnd < fadeOutFrames) {
                framesFromEnd.toDouble() / fadeOutFrames
            } else {
                1.0
            }
            (sin(frame * angularStep) * peakAmplitude * minOf(fadeInEnvelope, fadeOutEnvelope))
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun PcmSignalSettings.normalized(): PcmSignalSettings = copy(
        frequencyHz = frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
        durationSeconds = durationSeconds.takeIf(Float::isFinite)
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: PcmSignalSettings().durationSeconds,
        amplitudePercent = amplitudePercent.coerceIn(MIN_AMPLITUDE_PERCENT, MAX_AMPLITUDE_PERCENT),
        fadeInMs = fadeInMs.coerceIn(MIN_FADE_MS, MAX_FADE_MS),
        fadeOutMs = fadeOutMs.coerceIn(MIN_FADE_MS, MAX_FADE_MS)
    )

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
        const val TRANSFER_MODE = AudioTrack.MODE_STATIC
        const val STEREO_CHANNEL_COUNT = 2
        const val MILLIS_PER_SECOND = 1_000
        const val DOUBLE_BEEP_PAUSE_SECONDS = 0.1f
        const val MAX_PAUSE_FRACTION = 0.2f
        const val MIN_FREQUENCY_HZ = 20
        const val MAX_FREQUENCY_HZ = 20_000
        const val MIN_DURATION_SECONDS = 0.05f
        const val MAX_DURATION_SECONDS = 10.0f
        const val MIN_AMPLITUDE_PERCENT = 0
        const val MAX_AMPLITUDE_PERCENT = 100
        const val MIN_FADE_MS = 0
        const val MAX_FADE_MS = 5_000
    }
}
