package org.futo.voiceinput.shared

import android.util.Log

/**
 * Personal fork: timing marks for the delay between pressing the mic and recording being ready.
 * Logs only labels and elapsed milliseconds to logcat (tag VoiceTiming), never any audio or text.
 */
object VoiceTiming {
    @Volatile private var t0 = 0L

    fun start(label: String) {
        t0 = System.nanoTime()
        Log.d("VoiceTiming", "---- $label t=0ms")
    }

    fun mark(label: String) {
        val t = if (t0 == 0L) -1 else (System.nanoTime() - t0) / 1_000_000
        Log.d("VoiceTiming", "$label +${t}ms [${Thread.currentThread().name}]")
    }
}
