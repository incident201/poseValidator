package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TimelapseRecorder(
    private val context: Context
) {
    @Volatile
    var isRecording: Boolean = false
        private set

    private val lock = Any()
    private val stopMutex = Mutex()
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var recordingStartTimestampMs: Long = 0L
    private var timerStartTimestampMs: Long? = null
    private var nextCaptureTimestampMs: Long = 0L
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var outputFile: File? = null

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var lastPresentationTimeUs = -1L
    private var hadEncodingError = false
    private val pendingPresentationTimesUs = ArrayDeque<Long>()

    fun start(startTimestampMs: Long) {
        synchronized(lock) {
            discardInternalLocked()
            this.recordingStartTimestampMs = startTimestampMs
            this.timerStartTimestampMs = null
            nextCaptureTimestampMs = startTimestampMs
            videoWidth = 0
            videoHeight = 0
            outputFile = null
            muxerTrackIndex = -1
            muxerStarted = false
            lastPresentationTimeUs = -1L
            hadEncodingError = false
            isRecording = true
        }
    }

    fun startTimer(startTimestampMs: Long) {
        synchronized(lock) {
            if (!isRecording) return
            if (timerStartTimestampMs == null) {
                timerStartTimestampMs = startTimestampMs
            }
        }
    }

    fun offerFrame(bitmap: Bitmap, timestampMs: Long) {
        if (!isRecording) return
        synchronized(lock) {
            if (!isRecording) return
            if (timestampMs < nextCaptureTimestampMs) return
            nextCaptureTimestampMs = timestampMs + CAPTURE_INTERVAL_MS
        }

        frameExecutor.execute {
            try {
                if (!isRecording) return@execute
                val recordingElapsedMs = (timestampMs - recordingStartTimestampMs).coerceAtLeast(0L)
                val timerElapsedMs = synchronized(lock) {
                    timerStartTimestampMs
                }?.let { timerStart ->
                    (timestampMs - timerStart).coerceAtLeast(0L)
                } ?: 0L
                ensureEncoder(bitmap)
                synchronized(lock) {
                    pendingPresentationTimesUs.addLast((recordingElapsedMs * 1000L) / SPEED_FACTOR)
                }
                val frame = drawElapsedTimer(bitmap, timerElapsedMs)
                renderFrameToSurface(frame)
                frame.recycle()
                drainEncoder(endOfStream = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to encode timelapse frame", t)
                synchronized(lock) {
                    hadEncodingError = true
                    isRecording = false
                }
                releaseCodecResources(deleteTempFile = true)
            }
        }
    }

    suspend fun stop(): File? = stopMutex.withLock {
        withContext(Dispatchers.IO) {
            val wasRecording = synchronized(lock) {
                val value = isRecording
                isRecording = false
                value
            }

            if (!wasRecording) {
                return@withContext outputFile?.takeIf { it.exists() && it.length() > 0L }
            }

            try {
                frameExecutor.submit {
                    try {
                        if (inputSurface != null) {
                            encoder?.signalEndOfInputStream()
                            drainEncoder(endOfStream = true)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to finalize timelapse", t)
                        hadEncodingError = true
                    } finally {
                        releaseCodecResources(deleteTempFile = hadEncodingError)
                    }
                }.get()
                outputFile?.takeIf { !hadEncodingError && it.exists() && it.length() > 0L }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop timelapse recorder", t)
                hadEncodingError = true
                releaseCodecResources(deleteTempFile = true)
                null
            }
        }
    }

    fun discard() {
        synchronized(lock) { isRecording = false }
        frameExecutor.execute { releaseCodecResources(deleteTempFile = true) }
    }

    fun release() {
        synchronized(lock) { isRecording = false }
        runCatching {
            frameExecutor.submit {
                releaseCodecResources(deleteTempFile = true)
            }.get()
        }.onFailure {
            Log.e(TAG, "Failed to release on executor thread", it)
            releaseCodecResources(deleteTempFile = true)
        }
        frameExecutor.shutdown()
        runCatching { frameExecutor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun ensureEncoder(firstFrame: Bitmap) {
        if (encoder != null && inputSurface != null && muxer != null) return

        val width = (firstFrame.width - (firstFrame.width % 2)).coerceAtLeast(2)
        val height = (firstFrame.height - (firstFrame.height % 2)).coerceAtLeast(2)
        videoWidth = width
        videoHeight = height

        val tempFile = File(context.cacheDir, "pose_timelapse_${UUID.randomUUID()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }

        val newEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = newEncoder.createInputSurface()
        val newMuxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        newEncoder.start()

        encoder = newEncoder
        inputSurface = surface
        muxer = newMuxer
        outputFile = tempFile
    }

    private fun drawElapsedTimer(source: Bitmap, elapsedMs: Long): Bitmap {
        val target = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)

        val scale = maxOf(videoWidth / source.width.toFloat(), videoHeight / source.height.toFloat())
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val left = (videoWidth - scaledWidth) / 2f
        val top = (videoHeight - scaledHeight) / 2f
        val dstRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
        canvas.drawBitmap(source, null, dstRect, null)

        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        val textSize = (videoWidth * 0.06f).coerceIn(28f, 72f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val paddingH = textSize * 0.35f
        val paddingV = textSize * 0.25f
        val margin = textSize * 0.35f

        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(text)
        val textHeight = metrics.descent - metrics.ascent
        val box = RectF(
            margin,
            margin,
            margin + textWidth + paddingH * 2f,
            margin + textHeight + paddingV * 2f
        )

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        val radius = textSize * 0.3f
        canvas.drawRoundRect(box, radius, radius, bgPaint)

        val textX = box.left + paddingH
        val baseline = box.top + paddingV - metrics.ascent
        canvas.drawText(text, textX, baseline, textPaint)

        return target
    }

    private fun renderFrameToSurface(frame: Bitmap) {
        val surface = inputSurface ?: return
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(frame, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = encoder ?: return
        val muxer = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("Format changed twice")
                    val newFormat = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        ?: throw IllegalStateException("Encoder output buffer is null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) throw IllegalStateException("Muxer has not started")
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val minFrameStepUs = 1_000_000L / OUTPUT_FPS
                        val queuedPtsUs = synchronized(lock) {
                            if (pendingPresentationTimesUs.isEmpty()) null else pendingPresentationTimesUs.removeFirst()
                        } ?: bufferInfo.presentationTimeUs
                        val adjustedPtsUs = when {
                            lastPresentationTimeUs < 0L -> queuedPtsUs
                            queuedPtsUs <= lastPresentationTimeUs -> lastPresentationTimeUs + minFrameStepUs
                            else -> queuedPtsUs
                        }
                        bufferInfo.presentationTimeUs = adjustedPtsUs
                        lastPresentationTimeUs = adjustedPtsUs

                        muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun discardInternalLocked() {
        isRecording = false
        hadEncodingError = false
        releaseCodecResources(deleteTempFile = true)
    }

    private fun releaseCodecResources(deleteTempFile: Boolean) {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }

        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }

        encoder = null
        inputSurface = null
        muxer = null
        muxerTrackIndex = -1
        muxerStarted = false
        lastPresentationTimeUs = -1L
        synchronized(lock) { pendingPresentationTimesUs.clear() }

        if (deleteTempFile) {
            outputFile?.delete()
            outputFile = null
        }
    }

    private fun calculateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return (pixels * 6).coerceIn(4_000_000, 8_000_000)
    }

    private companion object {
        private const val TAG = "TimelapseRecorder"
        private const val OUTPUT_FPS = 30
        private const val CAPTURE_INTERVAL_MS = 333L
        private const val SPEED_FACTOR = 10L
        private const val I_FRAME_INTERVAL_SEC = 1
        private const val DRAIN_TIMEOUT_US = 10_000L
    }
}
