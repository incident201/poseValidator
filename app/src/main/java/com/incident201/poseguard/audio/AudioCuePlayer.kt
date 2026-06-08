package com.incident201.poseguard.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AudioCuePlayer(
    context: Context,
    private val settingsProvider: () -> AudioCuePlaybackSettings
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val pendingTtsMessages = ConcurrentLinkedQueue<String>()
    private val ttsReady = AtomicBoolean(false)
    private val ttsInitialized = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null
    private var closed = false
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    private val desiredLocale = AtomicReference(Locale.US)

    init {
        val engine = TextToSpeech(appContext) { status ->
            val initializedEngine = ttsRef.get() ?: return@TextToSpeech
            ttsInitialized.set(true)
            if (status == TextToSpeech.SUCCESS) {
                val ready = setTtsLanguage(initializedEngine, desiredLocale.get())
                ttsReady.set(ready)
                if (ready) flushPendingTts(initializedEngine)
            }
        }
        ttsRef.set(engine)
    }

    fun setLanguage(locale: Locale) {
        desiredLocale.set(locale)
        if (!ttsInitialized.get()) return
        val engine = ttsRef.get() ?: return
        val ready = setTtsLanguage(engine, locale)
        ttsReady.set(ready)
        if (ready) flushPendingTts(engine)
    }

    fun play(cue: AudioCue, ttsText: String) {
        if (closed) return
        val playbackSettings = settingsProvider()
        if (!playbackSettings.customizeAudioEnabled) {
            speak(ttsText)
            return
        }

        val settings = playbackSettings.cueSettings[cue] ?: AudioCueSettings()
        when (settings.mode) {
            AudioCueMode.UseTts -> speak(ttsText)
            AudioCueMode.AudioFile -> playAudioFile(settings.audioFileUri)
            AudioCueMode.Vibration -> vibrate()
            AudioCueMode.Off -> Unit
        }
    }

    private fun playAudioFile(uriValue: String?) {
        if (uriValue.isNullOrBlank()) return

        releaseMediaPlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(appContext, Uri.parse(uriValue))
            player.setOnPreparedListener { prepared ->
                runCatching { prepared.start() }.onFailure {
                    releaseMediaPlayer(prepared)
                }
            }
            player.setOnCompletionListener { completed ->
                releaseMediaPlayer(completed)
            }
            player.setOnErrorListener { failed, _, _ ->
                releaseMediaPlayer(failed)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releaseMediaPlayer(player)
        }
    }

    private fun releaseMediaPlayer(player: MediaPlayer) {
        if (mediaPlayer === player) mediaPlayer = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun vibrate() {
        val vibrator = appContext.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun speak(text: String) {
        if (text.isBlank() || closed) return
        val engine = ttsRef.get()
        if (engine != null && ttsReady.get()) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "audio_cue_${System.currentTimeMillis()}")
        } else {
            pendingTtsMessages.add(text)
        }
    }

    private fun flushPendingTts(engine: TextToSpeech) {
        while (true) {
            val message = pendingTtsMessages.poll() ?: break
            engine.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "audio_cue_${System.currentTimeMillis()}"
            )
        }
    }

    private fun setTtsLanguage(engine: TextToSpeech, locale: Locale): Boolean {
        val result = engine.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let(::releaseMediaPlayer)
    }

    override fun close() {
        closed = true
        releaseMediaPlayer()
        ttsRef.getAndSet(null)?.run {
            stop()
            shutdown()
        }
        ttsReady.set(false)
        ttsInitialized.set(false)
        pendingTtsMessages.clear()
    }

    private companion object {
        const val VIBRATION_DURATION_MS = 180L
    }
}
