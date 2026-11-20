package org.futo.voiceinput.shared.types

import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

enum class MagnitudeState {
    NOT_TALKED_YET, MIC_MAY_BE_BLOCKED, TALKING
}

interface AudioRecognizerListener {
    fun cancelled()
    fun finished(result: String)
    fun languageDetected(language: Language)
    fun partialResult(result: String)
    fun decodingStatus(status: InferenceState)
    fun modelLoadingFailed()

    fun loading()
    fun needPermission(onResult: (Boolean) -> Unit)

    fun recordingStarted(device: MicrophoneDeviceState)
    // Personal fork: sent shortly after recordingStarted once Bluetooth availability is known.
    fun deviceUpdated(device: MicrophoneDeviceState) {}
    fun updateMagnitude(magnitude: Float, state: MagnitudeState)

    fun processing()
}