package org.futo.inputmethod.latin.diag

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.compat.UserManagerCompatUtils
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.PublicKey
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val DIAG_LOG_ENABLED = SettingsKey(
    key = booleanPreferencesKey("diagLogEnabled"),
    default = false
)

data class DiagStats(
    val enabled: Boolean,
    val keyLoaded: Boolean,
    val suppressed: Boolean,
    val eventsByType: Map<String, Int>,
    val bytesOnDisk: Long,
    val files: Int,
    val suppressedFields: Int,
)

/**
 * Encrypted on-device diagnostic log. Contract: tools/diaglog/SPEC.md.
 * Everything is best-effort: no method may throw into the IME or block the UI thread, and no
 * event content is ever written to logcat.
 */
object DiagLog {
    private const val BATCH_EVENTS = 50
    private const val BATCH_MS = 30_000L
    private const val RING = 200
    private const val RETENTION_MS = 30L * 24 * 3600 * 1000
    private const val PUB_ASSET = "diaglog_pub.der"
    private const val SETTING_WAIT_S = 5L

    // Read from the UI/input threads, written from the setup and writer threads.
    @Volatile private var enabledSetting = false
    @Volatile private var publicKey: PublicKey? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var lastError: String? = null

    internal val gate = FieldGate()

    // Everything below is touched on the writer thread only (except via ring/counters locks).
    private val pending = ArrayList<String>()
    private val ring = ArrayDeque<String>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val suppressedFields = AtomicInteger(0)
    private val flushRunnable = Runnable { flushNow() }
    // Handler.hasCallbacks needs API 29 (minSdk is 24), so track the scheduled flush ourselves.
    private var flushScheduled = false
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("HHmmss", Locale.US)
    // One file per process per day, see openFile(). Null again after a failed write.
    private var curFile: File? = null
    private var curDay: String? = null

    /**
     * Idempotent. All state (key, lock state, persisted setting) is loaded by one job on the
     * writer thread, so a setEnabled() or reload() issued afterwards is ordered after it and the
     * load can never overwrite a newer toggle.
     */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        try {
            if (handler != null) return
            val app = context.applicationContext ?: context
            appContext = app
            val thread = HandlerThread("DiagLog").apply { start() }
            val h = Handler(thread.looper)
            handler = h
            h.post { loadState(app) }
        } catch (e: Throwable) {
            Log.w("DiagLog", "init failed")
        }
    }

    /** After first unlock: storage is now readable, so re-run the serialised state load. */
    @JvmStatic
    fun reload() {
        val app = appContext ?: return
        try { handler?.post { loadState(app) } } catch (e: Throwable) { }
    }

    private fun isUnlocked(app: Context) = UserManagerCompatUtils.getUserLockState(app) ==
            UserManagerCompatUtils.LOCK_STATE_UNLOCKED

    private fun loadKey(app: Context) {
        if (publicKey != null) return
        publicKey = try {
            app.assets.open(PUB_ASSET).use { DiagCrypto.parsePublicKey(it.readBytes()) }
        } catch (e: Exception) { null } // missing asset => stay disabled
    }

    /** Writer thread only. */
    private fun loadState(app: Context) {
        try {
            loadKey(app)
            // Direct boot: credential-encrypted storage (DataStore, noBackupFilesDir)
            // is unavailable until first unlock, so remain disabled until reload().
            val unlocked = isUnlocked(app)
            if (unlocked) retention(app)
            enabledSetting = unlocked && publicKey != null &&
                    app.getSettingBlocking(DIAG_LOG_ENABLED)
            if (!enabledSetting) dropBuffered()
        } catch (e: Throwable) {
            enabledSetting = false
            Log.w("DiagLog", "init failed")
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabledSetting && !gate.suppressed

    /** Current field generation; events built asynchronously capture it at begin. */
    @JvmStatic
    fun generation(): Int = gate.gen

    private const val ANY_GEN = Int.MIN_VALUE

    /** Returns true if the event was accepted (enqueued), false if dropped. */
    @JvmStatic
    fun record(type: String, fields: JSONObject): Boolean = record(type, fields, ANY_GEN)

    /**
     * [gen] is the field generation captured when the event began. It is checked here and again
     * on the writer thread, so an event that was built for a field that has since finished is
     * dropped even if the next field is not suppressed.
     */
    @JvmStatic
    fun record(type: String, fields: JSONObject, gen: Int): Boolean {
        try {
            if (!isEnabled()) return false
            if (gen != ANY_GEN && !gate.allows(gen)) return false
            val h = handler ?: return false
            fields.put("t", System.currentTimeMillis())
            fields.put("type", type)
            val line = fields.toString() // snapshot now; caller may mutate the object afterwards
            h.post {
                try {
                    // Disabled or cleared between enqueue and now: keep plaintext out of the ring.
                    if (!enabledSetting) return@post
                    if (gen != ANY_GEN && !gate.allows(gen)) return@post
                    pending.add(line)
                    synchronized(ring) {
                        ring.addLast(line)
                        while (ring.size > RING) ring.removeFirst()
                    }
                    counters.getOrPut(type) { AtomicInteger() }.incrementAndGet()
                    if (pending.size >= BATCH_EVENTS) {
                        flushNow()
                    } else if (!flushScheduled) {
                        flushScheduled = true
                        h.postDelayed(flushRunnable, BATCH_MS)
                    }
                } catch (e: Throwable) { Log.w("DiagLog", "record failed") }
            }
            return true
        } catch (e: Throwable) {
            Log.w("DiagLog", "record failed")
            return false
        }
    }

    @JvmStatic
    fun onStartInput(
        isPassword: Boolean, noLearning: Boolean, imeNoPersonalizedLearning: Boolean,
        inputTypeClass: Int, isEmail: Boolean, isUrl: Boolean
    ) {
        try {
            // Numeric password is TYPE_CLASS_NUMBER with a password variation; callers pass
            // isPassword for text variants, so the class is checked here too only by flag.
            val numericPassword = inputTypeClass == InputType.TYPE_CLASS_NUMBER && isPassword
            val suppress = FieldGate.shouldSuppress(
                isPassword || numericPassword, noLearning, imeNoPersonalizedLearning)
            if (suppress && enabledSetting) suppressedFields.incrementAndGet()
            gate.start(suppress)
        } catch (e: Throwable) {
            gate.start(true) // fail closed
        }
    }

    /**
     * Fail closed at the start of LatinIME.onFinishInput (and before the legacy startInput and
     * on an action-window field switch): the old field's composing word may still be committed,
     * reset or swiped out by the code that runs next, and it must not be logged under the next
     * field's suppression state. Only onStartInput reopens the gate. Drops hook state that
     * refers to the old field.
     */
    @JvmStatic
    fun onFinishInput() {
        try { gate.finish() } catch (e: Throwable) { }
        resetHooks(false)
    }

    private fun resetHooks(clearSession: Boolean) {
        try { DiagHooks.reset(clearSession) } catch (e: Throwable) { }
        try { DiagSwipeHooks.reset() } catch (e: Throwable) { }
    }

    /** Writer thread only: nothing of the buffered plaintext survives. */
    private fun dropBuffered() {
        pending.clear()
        synchronized(ring) { ring.clear() }
    }

    /** Why the last setEnabled(true) failed, for the toggle's toast. */
    @JvmStatic
    fun lastError(): String = lastError ?: "unknown"

    /**
     * Persists the setting and applies it. Returns false (see [lastError]) if enabling is not
     * possible: key missing, device locked, not initialised, or the writer thread did not answer.
     * Runs on the writer thread, behind the init load, so an early toggle cannot fail spuriously
     * and cannot be overwritten by it. Blocks the caller for that: do not call on the main thread.
     */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        try {
            val app = context.applicationContext ?: context
            val h = handler ?: run { lastError = "not initialised"; return false }
            val result = java.util.concurrent.atomic.AtomicBoolean(false)
            val job = Runnable { result.set(applyEnabled(app, enabled)) }
            if (Looper.myLooper() == h.looper) {
                job.run()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                h.post { try { job.run() } finally { latch.countDown() } }
                if (!latch.await(SETTING_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) {
                    lastError = "writer thread busy"
                    return false
                }
            }
            return result.get()
        } catch (e: Throwable) {
            Log.w("DiagLog", "setEnabled failed")
            lastError = "error"
            return false
        }
    }

    /** Writer thread only. */
    private fun applyEnabled(app: Context, enabled: Boolean): Boolean {
        if (enabled) {
            loadKey(app)
            when {
                publicKey == null -> { lastError = "public key missing"; enabledSetting = false; return false }
                !isUnlocked(app) -> { lastError = "device locked"; enabledSetting = false; return false }
            }
            retention(app)
        }
        enabledSetting = enabled
        if (!enabled) {
            flushNow()
            dropBuffered() // the View action must not show plaintext while logging is off
            resetHooks(false)
        }
        try {
            app.setSettingBlocking(DIAG_LOG_ENABLED.key, enabled)
        } catch (e: Throwable) { Log.w("DiagLog", "persist failed") }
        return true
    }

    @JvmStatic
    fun flush() {
        try { handler?.post { flushNow() } } catch (e: Throwable) { }
    }

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "diaglog")

    /** Writer thread only. Retention: drop day files older than 30 days. */
    private fun retention(app: Context) {
        try {
            val now = System.currentTimeMillis()
            dir(app).listFiles()?.forEach { if (now - it.lastModified() > RETENTION_MS) it.delete() }
        } catch (e: Throwable) { }
    }

    /**
     * Writer thread only. One file per process per local day: diag-YYYYMMDD-HHMMSS.fdl. A
     * process killed mid-write leaves a torn final frame, and appending after it would make
     * every later frame unreachable for the frame reader, so a new process never appends to an
     * old file.
     */
    private fun openFile(d: File, now: Long): File {
        val day = dayFormat.format(Date(now))
        curFile?.let { if (curDay == day) return it }
        var f = File(d, "diag-$day-${timeFormat.format(Date(now))}.fdl")
        var n = 1
        while (f.exists()) f = File(d, "diag-$day-${timeFormat.format(Date(now))}-${n++}.fdl")
        curFile = f; curDay = day
        return f
    }

    /** Writer thread only. */
    private fun flushNow() {
        try {
            flushScheduled = false
            handler?.removeCallbacks(flushRunnable)
            val ctx = appContext ?: return
            val key = publicKey ?: run { pending.clear(); return }
            if (pending.isEmpty()) return
            val data = pending.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
            pending.clear()
            val frame = DiagCrypto.encodeFrame(key, data)
            val d = dir(ctx).apply { mkdirs() }
            val now = System.currentTimeMillis()
            retention(ctx)
            try {
                // Append mode: frames are self-contained so concatenation is valid.
                FileOutputStream(openFile(d, now), true).use { it.write(frame) }
            } catch (e: Throwable) {
                curFile = null // possibly torn: continue in a fresh file
                throw e
            }
        } catch (e: Throwable) {
            Log.w("DiagLog", "flush failed")
        }
    }

    @JvmStatic
    fun clearAll(context: Context) {
        try {
            val app = context.applicationContext ?: context
            val h = handler
            val job = Runnable {
                try {
                    pending.clear()
                    synchronized(ring) { ring.clear() }
                    counters.clear()
                    suppressedFields.set(0)
                    dir(app).listFiles()?.forEach { it.delete() }
                    curFile = null
                    // The encrypted export zip is a copy of the log, so it goes too.
                    File(app.cacheDir, "diag-export").listFiles()?.forEach { it.delete() }
                    // Session snapshot lived in the deleted files; the next event must re-emit it.
                    resetHooks(true)
                } catch (e: Throwable) { Log.w("DiagLog", "clear failed") }
            }
            if (h != null) {
                // Flush first as the spec says, then delete, on the writer thread to avoid races.
                h.post { flushNow(); job.run() }
            } else job.run()
        } catch (e: Throwable) {
            Log.w("DiagLog", "clear failed")
        }
    }

    /**
     * Builds futo-diag-YYYYMMDD-HHMMSS.zip (STORED) of the day files in cacheDir/diag-export/,
     * deleting older exports first, and returns a content Uri.
     *
     * Contract with the ACTIONS agent: the FileProvider must be declared in the manifest with
     * authority "${applicationId}.diagexport" and a <cache-path name="diag-export" path="diag-export/"/>.
     * Blocking file IO: call from a background thread. Returns null if there is nothing to export.
     */
    fun exportBundle(context: Context): Uri? {
        return try {
            val app = context.applicationContext ?: context
            // Make sure buffered events are on disk; the writer thread is serial so this
            // runnable orders after any queued record.
            val h = handler
            if (h != null) {
                val latch = java.util.concurrent.CountDownLatch(1)
                h.post { flushNow(); latch.countDown() }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            val files = dir(app).listFiles { f -> f.name.endsWith(".fdl") && f.length() > 0 }
                ?.sortedBy { it.name }
            if (files.isNullOrEmpty()) return null
            val out = File(app.cacheDir, "diag-export").apply { mkdirs() }
            out.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val zip = File(out, "futo-diag-$stamp.zip")
            val buf = ByteArray(64 * 1024)
            ZipOutputStream(FileOutputStream(zip)).use { z ->
                z.setMethod(ZipOutputStream.STORED)
                for (f in files) {
                    // STORED needs size and CRC before the data: one streaming pass for the CRC,
                    // one for the copy, both capped at the size seen now because the writer
                    // thread may append meanwhile.
                    val len = f.length()
                    val crc = CRC32()
                    FileInputStream(f).use { ins ->
                        var left = len
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            crc.update(buf, 0, n); left -= n
                        }
                        if (left > 0) throw java.io.IOException("file shrank")
                    }
                    z.putNextEntry(ZipEntry(f.name).apply {
                        method = ZipEntry.STORED
                        size = len; compressedSize = len; this.crc = crc.value
                    })
                    FileInputStream(f).use { ins ->
                        var left = len
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw java.io.IOException("file shrank")
                            z.write(buf, 0, n); left -= n
                        }
                    }
                    z.closeEntry()
                }
            }
            FileProvider.getUriForFile(app, "${app.packageName}.diagexport", zip)
        } catch (e: Throwable) {
            Log.w("DiagLog", "export failed")
            null
        }
    }

    fun stats(): DiagStats {
        var bytes = 0L
        var n = 0
        try {
            appContext?.let { c ->
                dir(c).listFiles()?.forEach { bytes += it.length(); n++ }
            }
        } catch (e: Throwable) { }
        return DiagStats(
            enabled = isEnabled(),
            keyLoaded = publicKey != null,
            suppressed = gate.suppressed,
            eventsByType = counters.mapValues { it.value.get() },
            bytesOnDisk = bytes,
            files = n,
            suppressedFields = suppressedFields.get(),
        )
    }

    fun recent(n: Int): List<String> = try {
        synchronized(ring) { ring.toList().takeLast(n.coerceAtLeast(0)) }
    } catch (e: Throwable) { emptyList() }
}
