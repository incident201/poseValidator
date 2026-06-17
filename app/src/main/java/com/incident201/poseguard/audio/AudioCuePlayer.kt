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
import java.util.concurrent.atomic.AtomicLong
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
    private val pcmSignalPlayer = PcmSignalPlayer()
    private var closed = false
    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    private val desiredLocale = AtomicReference(Locale.US)
    private val desiredVoiceMode = AtomicReference(TtsVoiceMode.DefaultVoice)
    private val currentSystemTtsEngine = AtomicReference<String?>(null)
    private val ttsGeneration = AtomicLong(0)

    init {
        createTtsEngine()
    }

    fun setTtsConfig(locale: Locale, voiceMode: TtsVoiceMode) {
        desiredLocale.set(locale)
        desiredVoiceMode.set(voiceMode)
        if (!ttsInitialized.get()) return
        val engine = ttsRef.get() ?: return
        val ready = applyTtsConfiguration(engine, locale, voiceMode)
        ttsReady.set(ready)
        if (ready) flushPendingTts(engine)
    }

    fun refreshTtsEngineIfSystemDefaultChanged() {
        if (closed) return

        val engine = ttsRef.get()
        val latestSystemEngine = readDefaultTtsEngine(engine)
        val knownSystemEngine = currentSystemTtsEngine.get()

        if (latestSystemEngine != knownSystemEngine) {
            recreateTtsEngine()
            return
        }

        if (engine == null || !ttsInitialized.get()) return

        val ready = applyTtsConfiguration(
            engine,
            desiredLocale.get(),
            desiredVoiceMode.get()
        )
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
            AudioCueMode.AudioFile -> {
                pcmSignalPlayer.stop()
                playAudioFile(settings.audioFileUri)
            }
            AudioCueMode.Pcm -> {
                releaseMediaPlayer()
                runCatching { pcmSignalPlayer.play(settings.pcmSettings) }
            }
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
        var queueMode = TextToSpeech.QUEUE_FLUSH
        while (true) {
            val message = pendingTtsMessages.poll() ?: break
            engine.speak(
                message,
                queueMode,
                null,
                "audio_cue_${System.currentTimeMillis()}"
            )
            queueMode = TextToSpeech.QUEUE_ADD
        }
    }

    private fun readDefaultTtsEngine(engine: TextToSpeech?): String? =
        runCatching { engine?.defaultEngine }.getOrNull()

    private fun createTtsEngine() {
        val generation = ttsGeneration.incrementAndGet()

        val engine = TextToSpeech(appContext) { status ->
            if (generation != ttsGeneration.get()) return@TextToSpeech

            val initializedEngine = ttsRef.get() ?: return@TextToSpeech
            ttsInitialized.set(true)

            if (status == TextToSpeech.SUCCESS) {
                currentSystemTtsEngine.set(readDefaultTtsEngine(initializedEngine))

                val ready = applyTtsConfiguration(
                    initializedEngine,
                    desiredLocale.get(),
                    desiredVoiceMode.get()
                )
                ttsReady.set(ready)
                if (ready) flushPendingTts(initializedEngine)
            } else {
                currentSystemTtsEngine.set(readDefaultTtsEngine(initializedEngine))
                ttsReady.set(false)
            }
        }

        ttsRef.set(engine)
    }

    private fun recreateTtsEngine() {
        val oldEngine = ttsRef.getAndSet(null)

        ttsReady.set(false)
        ttsInitialized.set(false)

        oldEngine?.let { engine ->
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }

        createTtsEngine()
    }

    private fun applyTtsConfiguration(
        engine: TextToSpeech,
        locale: Locale,
        voiceMode: TtsVoiceMode
    ): Boolean = when (voiceMode) {
        TtsVoiceMode.DefaultVoice -> setTtsLanguage(engine, locale)
        TtsVoiceMode.SystemVoice -> setSystemTtsVoice(engine)
    }

    private fun setSystemTtsVoice(engine: TextToSpeech): Boolean {
        val defaultVoice = runCatching { engine.defaultVoice }.getOrNull()
        if (defaultVoice != null) {
            val applied = runCatching {
                engine.setVoice(defaultVoice) == TextToSpeech.SUCCESS
            }.getOrDefault(false)

            if (applied) return true
        }

        val fallbackLocale = defaultVoice?.locale
        return fallbackLocale != null && setTtsLanguage(engine, fallbackLocale)
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
        ttsGeneration.incrementAndGet()
        releaseMediaPlayer()
        pcmSignalPlayer.close()
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
