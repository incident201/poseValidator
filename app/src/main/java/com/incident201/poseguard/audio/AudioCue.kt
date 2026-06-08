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
    Vibration,
    Off
}

data class AudioCueSettings(
    val mode: AudioCueMode = AudioCueMode.UseTts,
    val audioFileUri: String? = null
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
