package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Personal fork: keeps one constructed-but-not-started AudioRecord ready so pressing the mic does
 * not pay for creating it (measured 45-100 ms of the ~190 ms to listening).
 *
 * An unstarted AudioRecord captures nothing and shows no mic indicator; it only holds the
 * recorder object and a 160 KB buffer. It is released after [IDLE_RELEASE_MS] without use, and
 * whenever the keyboard hides, so the mic resource is not held while the keyboard is idle.
 */
object RecorderPrewarm {
    private const val IDLE_RELEASE_MS = 90_000L
    private val handler = Handler(Looper.getMainLooper())
    private var warm: AudioRecord? = null
    private val releaseRunnable = Runnable { release() }

    // Same parameters as AudioRecognizer's own recorder, so the two are interchangeable.
    fun create(): AudioRecord {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            16000 * 2 * 5
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
        }
        return recorder
    }

    /** Call from a background thread. Does nothing without mic permission or if already warm. */
    fun prewarm(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        synchronized(this) {
            if (warm == null) {
                warm = try {
                    create().takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run { return }
                } catch (e: Exception) {
                    null
                }
            }
            handler.removeCallbacks(releaseRunnable)
            handler.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
        }
    }

    /** Hands over the warm recorder, if any. The caller owns it (and must release it) from here. */
    @Synchronized
    fun take(): AudioRecord? {
        handler.removeCallbacks(releaseRunnable)
        val r = warm
        warm = null
        VoiceTiming.mark(if (r != null) "using pre-warmed recorder" else "no pre-warmed recorder")
        return r
    }

    @Synchronized
    fun release() {
        handler.removeCallbacks(releaseRunnable)
        warm?.release()
        warm = null
    }
}
