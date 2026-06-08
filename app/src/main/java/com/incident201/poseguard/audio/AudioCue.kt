package com.incident201.poseguard.audio

enum class AudioCue {
    PlaceDeviceStill,
    TakePosition,
    TimeStartedHoldPosition,
    TimeIsUp,
    DefeatTryAgain,
    MotionViolation,
    DriftViolation,
    ViolationRecorded,
    FaceTurnedAway,
    FaceLookedAtCamera
}

enum class AudioCueMode {
    UseTts,
    AudioFile,
    Pcm,
    Vibration,
    Off
}

enum class PcmChannel {
    Left,
    Right,
    Both
}

enum class PcmPattern {
    SingleTone,
    DoubleBeep
}

data class PcmSignalSettings(
    val frequencyHz: Int = 300,
    val durationSeconds: Float = 1.0f,
    val channel: PcmChannel = PcmChannel.Both,
    val amplitudePercent: Int = 10,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val pattern: PcmPattern = PcmPattern.SingleTone
)

data class AudioCueSettings(
    val mode: AudioCueMode = AudioCueMode.UseTts,
    val audioFileUri: String? = null,
    val pcmSettings: PcmSignalSettings = PcmSignalSettings()
)

data class AudioCuePlaybackSettings(
    val customizeAudioEnabled: Boolean = false,
    val cueSettings: Map<AudioCue, AudioCueSettings> =
        AudioCue.entries.associateWith { AudioCueSettings() }
)

data class AudioCueEvent(
    val cue: AudioCue,
    val ttsText: String
)
