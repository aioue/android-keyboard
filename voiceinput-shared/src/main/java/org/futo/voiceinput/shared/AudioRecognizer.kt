package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean,
    val usePrewarmedRecorder: Boolean = false,
    val chunkLongRecordings: Boolean = false
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false
    private var recorder: AudioRecord? = null

    // Personal fork: while the user holds a finger on the voice panel, silence auto-stop (VAD) is
    // suspended. The silence counter is pinned to 0 while held, so after release the normal
    // silence timeout starts again from zero. Does not affect manual finish or the buffer cap.
    @Volatile private var held = false
    fun setHeld(value: Boolean) { held = value }

    private val modelRunner = MultiModelRunner(modelManager)

    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    // bluetoothAvailable comes from the same device list used to pick the device, so callers don't
    // need a second availableCommunicationDevices query.
    private data class CommunicationDeviceInfo(
        val bluetoothActive: Boolean,
        val name: String,
        val bluetoothAvailable: Boolean
    )

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): CommunicationDeviceInfo {
        var bluetoothAvailable = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                bluetoothAvailable = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    audioManager.clearCommunicationDevice()
                    return CommunicationDeviceInfo(false, "", bluetoothAvailable)
                } else {
                    return CommunicationDeviceInfo(
                        tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        tgtDevice.productName.toString(),
                        bluetoothAvailable
                    )
                }
            }
        } catch(_: Exception) {}
        return CommunicationDeviceInfo(false, "", bluetoothAvailable)
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        verifyModelsExist()
    }

    // ---- Personal fork: transcribe long recordings in chunks while still recording ----
    // When enough speech has built up and the speaker pauses, the finished stretch is transcribed
    // in the background (strictly in order, on the single model thread) so that at the end only the
    // last stretch is left to process. Falls back to one full pass if anything goes wrong.
    private val chunkingEnabled = settings.recordingConfiguration.chunkLongRecordings
    private val CHUNK_MIN_SAMPLES = 16000 * 12      // don't cut before 12 s have built up
    private val CHUNK_SOFT_MAX_SAMPLES = 16000 * 20 // after 20 s accept a shorter pause
    private val CHUNK_PAUSE_FRAMES = 23             // VAD frames are 30 ms: about 0.7 s of silence
    private val CHUNK_PAUSE_FRAMES_SHORT = 13       // about 0.4 s
    private val CHUNK_MIN_SPEECH_FRAMES = 20        // a chunk must contain at least ~0.6 s of speech
    private val TOO_SHORT_SAMPLES = 16000 * 4 / 10  // under 0.4 s in total: nothing worth transcribing
    private val TAIL_MIN_SPEECH_FRAMES = 5          // skip a final pass that is only silence

    private var chunkStart = 0                      // first sample not yet handed to a chunk
    @Volatile private var speechFramesSinceCut = 0
    private val chunkTexts = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var chunkChain: Job? = null
    @Volatile private var chunkFailed = false

    // Chunk runs are invisible to the UI: no "processing" circle, no partial text in the field.
    private val silentCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {}
        override fun languageDetected(language: Language) {}
        override fun partialResult(string: String) {}
    }

    private fun tailOf(text: String): String {
        if (text.length <= 200) return text
        val t = text.takeLast(200)
        val sp = t.indexOf(' ')
        return if (sp >= 0) t.substring(sp + 1) else t
    }

    private fun maybeCutChunk(nonSpeechFrames: Int) {
        if (!chunkingEnabled || chunkFailed) return
        val end = floatSamples.position()
        val unprocessed = end - chunkStart
        if (unprocessed < CHUNK_MIN_SAMPLES || speechFramesSinceCut < CHUNK_MIN_SPEECH_FRAMES) return
        val needed = if (unprocessed >= CHUNK_SOFT_MAX_SAMPLES) CHUNK_PAUSE_FRAMES_SHORT else CHUNK_PAUSE_FRAMES
        if (nonSpeechFrames < needed) return

        val audio = floatSamples.array().copyOfRange(chunkStart, end)
        chunkStart = end
        speechFramesSinceCut = 0
        val idx = chunkTexts.size
        chunkTexts.add("")
        VoiceTiming.mark("chunk $idx cut: ${audio.size / 16000}s of audio")

        val prev = chunkChain
        chunkChain = lifecycleScope.launch(Dispatchers.Default) {
            prev?.join()
            loadModelJob?.join()
            val before = synchronized(chunkTexts) { chunkTexts.subList(0, idx).filter { it.isNotBlank() }.joinToString(" ") }
            try {
                val text = modelRunner.run(
                    audio,
                    settings.modelRunConfiguration,
                    settings.decodingConfiguration.copy(previousText = tailOf(before)),
                    silentCallback
                ).trim()
                chunkTexts[idx] = if (isBlankResult(text)) "" else text
                VoiceTiming.mark("chunk $idx transcribed")
            } catch (e: InferenceCancelledException) {
                // Recording was cancelled; nothing to do.
            } catch (e: Exception) {
                chunkFailed = true
                VoiceTiming.mark("chunk $idx failed: ${e.javaClass.simpleName}")
            }
        }
    }

    fun reset() {
        chunkChain?.cancel()
        chunkChain = null
        chunkTexts.clear()
        chunkStart = 0
        speechFramesSinceCut = 0
        chunkFailed = false

        recorder?.stop()
        recorderJob?.cancel()

        recorder?.release()
        recorder = null

        modelJob?.cancel()
        isRecording = false

        modelRunner.cancelAll()

        unfocusAudio()

        clearCommunicationDevice()
    }

    fun finish() {
        if(!isRecording) return
        onFinishRecording()
    }

    fun cancel() {
        reset()
        listener.cancelled()
    }

    fun openPermissionSettings() {
        val packageName = context.packageName
        val micPermissionRequester = Intent()
        micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
        micPermissionRequester.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(micPermissionRequester)

        cancel()
    }

    fun start() {
        VoiceTiming.mark("AudioRecognizer.start")
        listener.loading()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        } else {
            startRecording()
        }
    }

    private fun requestPermission() {
        listener.needPermission { wasGranted ->
            if(wasGranted) {
                startRecording()
            }
        }
    }


    @Throws(SecurityException::class)
    private fun createAudioRecorder(): AudioRecord {
        // Personal fork: use the pre-created recorder when allowed. Not with a Bluetooth mic, since
        // the communication device is chosen before the recorder is created in that case.
        val warm = if (settings.recordingConfiguration.usePrewarmedRecorder &&
            !settings.recordingConfiguration.preferBluetoothMic) RecorderPrewarm.take() else null
        val recorder = warm ?: RecorderPrewarm.create()

        this.recorder = recorder

        return recorder
    }

    private suspend fun preloadModels() {
        modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)

        while (isRecording) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            if (floatSamples.position() == 0) VoiceTiming.mark("first audio read returned")
            yield()

            var isRunningOutOfSpace = (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > 66) && useVAD && !held
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                            speechFramesSinceCut++
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            floatSamples.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())

            if (useVAD && vad != null) maybeCutChunk(numConsecutiveNonSpeech)

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (floatSamples.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                    floatSamples.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    @Throws(SecurityException::class)
    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        recorder?.stop()

        VoiceTiming.mark("before setCommunicationDevice")
        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)
        VoiceTiming.mark("after setCommunicationDevice")

        val task = {
            recorder?.release()

            val recorder = createAudioRecorder()
            VoiceTiming.mark("AudioRecord created")

            recorder.startRecording()
            VoiceTiming.mark("AudioRecord.startRecording returned")
            this.recorder = recorder

            isRecording = true

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    if(useVAD) {
                        createVad().use { vad ->
                            recordingJob(recorder, vad)
                        }
                    } else {
                        recordingJob(recorder, null)
                    }
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                recorderJob?.cancelAndJoin()
                task()
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.bluetoothActive || bluetoothInfo.bluetoothAvailable,
            bluetoothActive = bluetoothInfo.bluetoothActive,
            deviceName = bluetoothInfo.name,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                listener.recordingStarted(createRecorderAndJob(it))
            }
        )
    }

    private fun startRecording() {
        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            clearCommunicationDevice()
            requestPermission()
            return
        }

        VoiceTiming.mark("recorder job launched")
        focusAudio()
        VoiceTiming.mark("audio focus done")

        listener.recordingStarted(device)
        VoiceTiming.mark("recordingStarted callback done")

        VoiceTiming.mark("bluetooth availability checked")

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                    VoiceTiming.mark("models preloaded")
                } catch(_: InvalidModelException) {
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed()
                    }
                }
            }
        }
    }

    private val runnerCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            listener.decodingStatus(state)
        }

        override fun languageDetected(language: Language) {
            listener.languageDetected(language)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            listener.partialResult(string)
        }
    }

    private suspend fun runModel() {
        loadModelJob?.let {
            if (it.isActive) {
                println("Model was not finished loading...")
                it.join()
            }
        }

        // Personal fork: make sure the recording loop has stopped (no more cuts), then wait for any
        // chunk still being transcribed.
        recorderJob?.join()
        chunkChain?.join()

        val end = floatSamples.position()
        val chunksOk = chunkTexts.isNotEmpty() && !chunkFailed
        val startIdx = if (chunksOk) chunkStart else 0
        val prefix = if (chunksOk) synchronized(chunkTexts) { chunkTexts.filter { it.isNotBlank() }.joinToString(" ") } else ""
        // A recording shorter than this is a mis-press (e.g. a volume-button tap right after a
        // dictation), not speech. Whisper is conditioned on the text already in the field, and given
        // a fraction of a second of near-silence it just repeats that text back, so the previous
        // dictation was typed twice. Skip the model run instead.
        val tooShort = !chunksOk && (end - startIdx) < TOO_SHORT_SAMPLES
        val skipTail = tooShort || (chunksOk && speechFramesSinceCut < TAIL_MIN_SPEECH_FRAMES)
        VoiceTiming.mark("final pass: ${(end - startIdx) / 16000}s of audio, ${chunkTexts.size} earlier chunks, ${if (skipTail) "tail skipped" else "tail run"}")

        val floatArray = floatSamples.array().copyOfRange(startIdx, end)

        val callback = if (prefix.isEmpty()) runnerCallback else object : ModelInferenceCallback {
            override fun updateStatus(state: InferenceState) = runnerCallback.updateStatus(state)
            override fun languageDetected(language: Language) = runnerCallback.languageDetected(language)
            override fun partialResult(string: String) = runnerCallback.partialResult("$prefix $string")
        }

        yield()
        val tailText = if (skipTail) "" else try {
             modelRunner.run(
                floatArray,
                settings.modelRunConfiguration,
                settings.decodingConfiguration.copy(previousText = tailOf(prefix)),
                callback
            ).trim().let { if (isBlankResult(it)) "" else it }
        }catch(e: InferenceCancelledException) {
            yield()
            return
        }
        val outputText = listOf(prefix, tailText).filter { it.isNotBlank() }.joinToString(" ")

        VoiceTiming.mark("transcription done")
        val text = when {
            isBlankResult(outputText) -> ""
            else -> outputText
        }

        yield()
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                yield()
                listener.finished(text)
            }
        }
    }

    private fun onFinishRecording() {
        recorderJob?.cancel()

        if (!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        isRecording = false
        recorder?.stop()

        VoiceTiming.mark("finish pressed; ${floatSamples.position() / 16000}s of audio")
        listener.processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runModel()
            }
        }
    }
}