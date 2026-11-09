package org.futo.inputmethod.latin.diag

import android.content.Context
import android.os.SystemClock
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.LastComposedWord
import org.futo.inputmethod.latin.LayoutInfoForModel
import org.futo.inputmethod.latin.DisplayTop4Setting
import org.futo.inputmethod.latin.LegacySwipeSetting
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SwipeDecoderDictionary
import org.futo.inputmethod.latin.SwipeLanguageModelSetting
import org.futo.inputmethod.latin.SwipeSpecialDecoderSetting
import org.futo.inputmethod.latin.WordComposer
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Event builders for the swipe-side diagnostic hooks (contract: tools/diaglog/SPEC.md).
 *
 * Why this file exists: same reason as DiagHooks, keep the call sites in Suggest, InputLogic,
 * GeneralIME and SwipeDecoderDictionary to one or two guarded lines each so rebases stay cheap.
 * Call sites guard with `if (DiagLog.isEnabled())`; every function here swallows its own
 * exceptions because a diagnostic must never break swiping. Content (words, coordinates) goes
 * only into DiagLog.record(), never into logcat.
 *
 * Data flow for one gesture (stash keyed by GeneralIME's suggestion sequence number, which is the
 * value Suggest and tailCompleted see; InputLogic's own auto-commit counter is a different one):
 *   fingerUp(seq)                      UI thread, finger lifted (single latest timestamp)
 *   begin() / decoder() / raw() / finish(seq, ...)
 *                                      suggestion thread, one synchronous Suggest call, so the
 *                                      decoder's raw output is handed over in a ThreadLocal
 *   tailCompleted(seq, words)          UI thread, composing text set: emits the `swipe` event
 *   onCommit / onRejected              UI thread, later: emits `swipeOutcome`
 */
object DiagSwipeHooks {
    private const val MAX_CANDIDATES = 10
    private const val MAX_POINTS = 120
    private const val RETYPE_WINDOW_MS = 15_000L   // matches DiagHooks' backspaceWord window
    private const val STALE_MS = 120_000L          // a swipe word unresolved this long is "expired"
    private const val MAX_PENDING = 8              // gestures kept in flight; a leak guard

    private val lock = Any()

    // ---- helpers -----------------------------------------------------------------------------

    /** JSONObject.put(double) throws on NaN/Infinity, and decoder scores can be either. */
    private fun num(f: Float): Any =
        if (f.isNaN() || f.isInfinite()) JSONObject.NULL else Math.round(f * 1000.0) / 1000.0

    private fun <V> boundedMap(): MutableMap<Int, V> =
        object : LinkedHashMap<Int, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, V>?) =
                size > MAX_PENDING
        }

    /** Set by LatinIME.onCreate; lets the hooks read the computed keyboard size mode. */
    @Volatile @JvmStatic var latinIme: java.lang.ref.WeakReference<org.futo.inputmethod.latin.LatinIME>? = null

    /** Computed size class, e.g. RegularKeyboardSize / OneHandedKeyboardSize:left (no geometry). */
    private fun sizeKind(): String? = try {
        when (val sz = latinIme?.get()?.size?.value) {
            null -> null
            is org.futo.inputmethod.v2keyboard.OneHandedKeyboardSize -> "OneHanded:" + sz.direction.name
            else -> sz.javaClass.simpleName.removeSuffix("KeyboardSize")
        }
    } catch (e: Throwable) { null }

    // ---- session -----------------------------------------------------------------------------

    /**
     * Extra fields for the session snapshot (DiagHooks.maybeSession, off the input thread, so the
     * blocking DataStore reads are fine). Which decoder is configured decides how every later
     * `swipe` event must be read.
     */
    @JvmStatic
    fun sessionFields(app: Context, o: JSONObject) {
        try {
            o.put("swipe", JSONObject().apply {
                put("legacySwipe", app.getSettingBlocking(LegacySwipeSetting))
                put("specialDecoder", app.getSettingBlocking(SwipeSpecialDecoderSetting))
                put("swipeLm", app.getSettingBlocking(SwipeLanguageModelSetting))
                put("top4", app.getSettingBlocking(DisplayTop4Setting))
                // SwipeDecoderDictionary: topK = 4 for the final (high-beam) pass, beam width 300.
                put("decoderTopK", 4)
                put("decoderBeamWidth", SwipeDecoderDictionary.BeamValues.highBeam)
                Settings.getInstance().current?.let { put("keyboardHeightScale", it.mKeyboardHeightScale.toDouble()) }
                try {
                    // Saved sizing mode (Regular / Split / OneHanded / Floating) and hand side.
                    latinIme?.get()?.sizingCalculator?.getSavedSettings()?.let {
                        put("sizeMode", it.currentMode.name)
                        put("oneHandedDirection", it.oneHandedDirection.name)
                        put("prefersSplit", it.prefersSplit)
                    }
                } catch (ignored: Throwable) {}
                try {
                    KeyboardSwitcher.getInstance().keyboard?.let {
                        put("split", it.mId.mIsSplitLayout)
                        put("baseWidth", it.mBaseWidth)
                        put("baseHeight", it.mBaseHeight)
                    }
                } catch (ignored: Throwable) {}
            })
        } catch (ignored: Throwable) {}
    }

    // ---- one suggestion pass (suggestion thread) ---------------------------------------------

    private class Pass {
        val gen = DiagLog.generation()          // field generation at begin; see FieldGate
        var decoder: JSONArray? = null          // [word, score, lm, ctc] per decoder result
        var decoderCount = 0
        var layout: JSONObject? = null
        var fellThrough: String? = null
        var rawCount = 0
        var rawTop = JSONArray()
        var rawFirst: String? = null
        var belowThreshold = 0
        var demotedWord: String? = null
    }

    private val pass = ThreadLocal<Pass>()

    /**
     * Start of Suggest.getSuggestedWordsForBatchInput. Clears the hand-over from the previous
     * pass: when the decoder is skipped or returns null (legacy path) nothing would overwrite it.
     */
    @JvmStatic
    fun begin() { pass.set(Pass()) }

    /** The new decoder gave up before decoding (no keyboard, tries not built yet). */
    @JvmStatic
    fun fellThrough(reason: String) { pass.get()?.fellThrough = reason }

    /**
     * Raw decoder output, called from SwipeDecoderDictionary.getSuggestions on the tail pass.
     * [results] rows are [word, score, lmScore, ctcScore]; the score on SuggestedWordInfo is
     * a transform of the decoder score, so the originals would otherwise be lost.
     */
    @JvmStatic
    fun decoder(
        results: List<Array<Any>>, layout: LayoutInfoForModel, beamWidth: Int,
        multiSwipe: Boolean, segments: Int, topK: Int
    ) {
        try {
            val p = pass.get() ?: return
            val arr = JSONArray()
            for (r in results.take(MAX_CANDIDATES)) {
                arr.put(JSONArray().apply {
                    put(r[0]); put(num((r[1] as Number).toFloat()))
                    put(num((r[2] as Number).toFloat())); put(num((r[3] as Number).toFloat()))
                })
            }
            p.decoder = arr
            p.decoderCount = results.size
            p.layout = JSONObject().apply {
                // layoutMatched=false: SpecialDecoder.matchLayout fell through to the generic
                // layout built from the key positions (no special decoder asset).
                put("layoutMatched", layout.decoder.isNotEmpty())
                // Asset directories only (e.g. futo-swipe/magic_macaw), never file contents.
                put("decoderModel", layout.decoder.substringBeforeLast('/', "generic"))
                put("lmModel", layout.lm.substringBeforeLast('/', "none"))
                put("letters", layout.letters.length)
                put("sx", num(layout.sx)); put("sy", num(layout.sy))
                put("ox", num(layout.ox)); put("oy", num(layout.oy))
                put("beamWidth", beamWidth)
                // Hardcoded 4 in SwipeDecoderDictionary (useHighBeam ? 4 : 1): the decoder never
                // returns more results than this, whatever the beam width.
                put("topK", topK)
                put("multiSwipe", multiSwipe)
                put("segments", segments)
            }
        } catch (ignored: Throwable) {}
    }

    /** Candidates as they entered Suggest, before dedupe/threshold/demotion. */
    @JvmStatic
    fun raw(candidates: List<SuggestedWordInfo>, suppressThreshold: Int) {
        try {
            val p = pass.get() ?: return
            p.rawCount = candidates.size
            p.rawFirst = candidates.firstOrNull()?.mWord
            p.belowThreshold = candidates.count { it.mScore < suppressThreshold }
            p.rawTop = wordScores(candidates)
        } catch (ignored: Throwable) {}
    }

    /** Suggest swapped the previously rejected top word down one slot. */
    @JvmStatic
    fun demoted(word: String) { pass.get()?.demotedWord = word }

    private fun wordScores(list: List<SuggestedWordInfo>): JSONArray {
        val arr = JSONArray()
        for (i in 0 until minOf(MAX_CANDIDATES, list.size)) {
            val w = list[i]
            arr.put(JSONArray().apply {
                put(w.mWord); put(w.mScore); put(w.mOriginatesFromSwipeModel)
            })
        }
        return arr
    }

    private class Stash(val json: JSONObject, val words: List<String>, val gen: Int)
    private val stashes = boundedMap<Stash>()

    /**
     * End of Suggest.getSuggestedWordsForBatchInput, tail pass only. Builds the whole `swipe`
     * payload now (the pointers and keyboard are only valid here), stored until tailCompleted.
     * [filtered] is the final list after demotion, dedupe and SUPPRESS_SUGGEST_THRESHOLD.
     */
    @JvmStatic
    fun finish(
        seq: Int, inputStyle: Int, composer: WordComposer, keyboard: Keyboard,
        filtered: List<SuggestedWordInfo>, suppressThreshold: Int
    ) {
        try {
            if (inputStyle != SuggestedWords.INPUT_STYLE_TAIL_BATCH) return
            val p = pass.get() ?: return
            pass.remove()
            val o = JSONObject()
            o.put("seq", seq)
            // Decoder: "new" when SwipeDecoderDictionary produced results, else the legacy
            // BinaryDictionary gesture path (setting on, decoder unavailable, or fell through).
            o.put("decoder", if (p.decoder != null) "new" else "legacy")
            p.fellThrough?.let { o.put("fellThrough", it) }
            p.layout?.let { o.put("decoderInfo", it) }
            p.decoder?.let { o.put("decoderCandidates", it); o.put("decoderCount", p.decoderCount) }

            // Geometry in the same pixel space as the points.
            o.put("keyboard", JSONObject().apply {
                put("layout", keyboard.mId.mKeyboardLayoutSetName)
                put("elementId", keyboard.mId.mElementId)
                put("locale", keyboard.mId.mLocale.toString())
                put("split", keyboard.mId.mIsSplitLayout)
                sizeKind()?.let { put("sizeMode", it) }
                put("width", keyboard.mBaseWidth); put("height", keyboard.mBaseHeight)
                put("occupiedWidth", keyboard.mOccupiedWidth)
                put("occupiedHeight", keyboard.mOccupiedHeight)
                put("keyWidth", keyboard.mMostCommonKeyWidth)
                put("keyHeight", keyboard.mMostCommonKeyHeight)
            })

            // Points as [x, y, t] with t relative to the first point; downsampled evenly
            // (first and last kept) so one swipe stays a few KB while pointCount keeps the truth.
            val ip = composer.inputPointers
            val n = ip.pointerSize
            val xs = ip.xCoordinates; val ys = ip.yCoordinates; val ts = ip.times
            val m = minOf(n, MAX_POINTS)
            val pts = JSONArray()
            if (n > 0) {
                val t0 = ts[0]
                for (k in 0 until m) {
                    val i = if (m == 1) 0 else (k.toLong() * (n - 1) / (m - 1)).toInt()
                    pts.put(JSONArray().apply { put(xs[i]); put(ys[i]); put(ts[i] - t0) })
                }
            }
            o.put("points", pts)
            o.put("pointCount", n)
            o.put("durationMs", if (n > 0) ts[n - 1] - ts[0] else 0)

            // Candidate funnel: raw -> (demote, dedupe, threshold) -> filtered.
            o.put("rawCount", p.rawCount)
            o.put("raw", p.rawTop)
            o.put("filtered", wordScores(filtered))
            o.put("filteredCount", filtered.size)
            // Threshold drops are counted exactly on the raw list; dedupe is the remainder
            // (removeDups keeps the first occurrence, so a duplicate never outranks its twin).
            o.put("droppedThreshold", p.belowThreshold)
            o.put("droppedDedupe", maxOf(0, p.rawCount - filtered.size - p.belowThreshold))
            o.put("suppressThreshold", suppressThreshold)
            o.put("demotedRejected", p.demotedWord != null)
            p.demotedWord?.let { o.put("demotedWord", it) }
            o.put("topChanged",
                filtered.firstOrNull()?.mWord != p.rawFirst)

            val words = filtered.map { it.mWord }
            synchronized(lock) { stashes[seq] = Stash(o, words, p.gen) }
        } catch (ignored: Throwable) {}
    }

    // ---- gesture end (UI thread) -------------------------------------------------------------

    // Latest finger-up time (uptime ms, 0 = none). Deliberately NOT keyed by sequence number:
    // InputLogic.onEndBatchInput passes its own mAutoCommitSequenceNumber, while the tail pass
    // reaches tailCompleted with GeneralIME's sequenceId (a different counter), so a map keyed by
    // seq never matched and every latencyMs was null. A gesture ends on the UI thread, its tail
    // pass completes well within FINGER_UP_MAX_MS, and the next gesture overwrites this value.
    private var fingerUpAt = 0L
    private const val FINGER_UP_MAX_MS = 10_000L

    /** InputLogic.onEndBatchInput (UI thread). [seq] is only informational, see above. */
    @JvmStatic
    fun fingerUp(@Suppress("UNUSED_PARAMETER") seq: Int) {
        synchronized(lock) { fingerUpAt = SystemClock.uptimeMillis() }
    }

    private class Pending(
        val seq: Int, val word: String, val words: List<String>, val at: Long,
        val gen: Int = DiagLog.generation()
    )
    private var pending: Pending? = null

    // Set when the swipe word was rejected with backspace; the next commit decides "retyped".
    private var rejectedWord: String? = null
    private var rejectedAt = 0L

    /**
     * GeneralIME tail-batch completion, after InputLogic set the composing text. Emits `swipe`
     * and makes the word the one pending an outcome.
     */
    @JvmStatic
    fun tailCompleted(seq: Int, shown: SuggestedWords, distinct: Boolean) {
        try {
            val now = SystemClock.uptimeMillis()
            val (st, up) = synchronized(lock) {
                val u = fingerUpAt.takeIf { it != 0L && now - it in 0..FINGER_UP_MAX_MS }
                fingerUpAt = 0L
                Pair(stashes.remove(seq), u)
            }
            val o = st?.json ?: JSONObject().apply {
                // No Suggest pass recorded (cancelled, stale, or logging turned on mid-gesture).
                put("seq", seq); put("decoder", "unknown")
            }
            val top = if (shown.isEmpty) null else shown.getWord(0)
            o.put("latencyMs", if (up != null) now - up else JSONObject.NULL)
            o.put("distinct", distinct)
            o.put("shownCount", shown.size())
            // The strip after the suggestion blacklist; differs from `filtered` when words were removed.
            val shownWords = JSONArray()
            for (i in 0 until minOf(MAX_CANDIDATES, shown.size())) shownWords.put(shown.getWord(i))
            o.put("shown", shownWords)
            // No stash means no Suggest pass was seen: nothing ties it to an older field.
            DiagLog.record("swipe", o, st?.gen ?: DiagLog.generation())

            // A still-pending previous swipe was implicitly committed by this one.
            expirePending("superseded", now)
            if (top != null) {
                synchronized(lock) {
                    pending = Pending(seq, top, st?.words ?: shownWords.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    }, now, st?.gen ?: DiagLog.generation())
                }
            }
        } catch (ignored: Throwable) {}
    }

    /**
     * Forget every in-flight gesture, rejection and pass: they hold swiped words of the previous
     * field (or of data the user just cleared). Called on field change, disable and clear.
     */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            stashes.clear(); fingerUpAt = 0L
            pending = null
            rejectedWord = null; rejectedAt = 0L
        }
        pass.remove()
    }

    // ---- outcomes (UI thread) ----------------------------------------------------------------

    private fun expirePending(kind: String, now: Long) {
        val p = synchronized(lock) { pending.also { pending = null } } ?: return
        outcome(p, kind, now, null)
    }

    private fun outcome(p: Pending, kind: String, now: Long, extra: ((JSONObject) -> Unit)?) {
        DiagLog.record("swipeOutcome", JSONObject().apply {
            put("seq", p.seq); put("outcome", kind)
            put("word", p.word)
            put("msSinceSwipe", now - p.at)
            extra?.invoke(this)
        }, p.gen)
    }

    /**
     * From InputLogic.commitChosenWord, next to DiagHooks.onCommit. [wasBatch] is read before
     * commitWord() resets batch mode. Resolves the pending swipe word (accepted / pickedAlternate)
     * and, after a backspace rejection, reports what the user did instead (retyped).
     */
    @JvmStatic
    fun onCommit(committedIn: String?, commitType: Int, wasBatch: Boolean) {
        try {
            val committed = committedIn ?: ""
            val now = SystemClock.uptimeMillis()
            val (rej, rejAge) = synchronized(lock) {
                Pair(rejectedWord, now - rejectedAt).also { rejectedWord = null }
            }
            if (rej != null && rejAge <= RETYPE_WINDOW_MS) {
                DiagLog.record("swipeOutcome", JSONObject().apply {
                    put("outcome", "retyped")
                    put("word", rej); put("retyped", committed)
                    put("sameWord", committed.equals(rej, ignoreCase = true))
                    put("by", if (wasBatch) "swipe" else "typed")
                    put("msSinceReject", rejAge)
                })
            }
            if (!wasBatch) return
            val p = synchronized(lock) { pending.also { pending = null } } ?: return
            if (now - p.at > STALE_MS) { outcome(p, "expired", now, null); return }
            when {
                commitType == LastComposedWord.COMMIT_TYPE_MANUAL_PICK && committed != p.word ->
                    outcome(p, "pickedAlternate", now) {
                        // Rank in the filtered list; -1 when the pick came from beyond it.
                        it.put("rank", p.words.indexOf(committed))
                        it.put("picked", committed)
                    }
                else -> outcome(p, "accepted", now) {
                    it.put("manualPick", commitType == LastComposedWord.COMMIT_TYPE_MANUAL_PICK)
                    it.put("changed", committed != p.word)
                    if (committed != p.word) it.put("committed", committed)
                }
            }
        } catch (ignored: Throwable) {}
    }

    /** InputLogic.handleBackspaceEvent, batch-mode branch: the swipe word was rejected. */
    @JvmStatic
    fun onRejected(word: String?) {
        try {
            val now = SystemClock.uptimeMillis()
            val p = synchronized(lock) {
                pending.also { pending = null }.also {
                    rejectedWord = word; rejectedAt = now
                }
            }
            if (p != null) outcome(p, "rejectedByBackspace", now, null)
            else DiagLog.record("swipeOutcome", JSONObject().apply {
                // Rejection of a swipe word whose `swipe` event was not seen (logging was off).
                put("outcome", "rejectedByBackspace"); put("word", word ?: "")
            })
        } catch (ignored: Throwable) {}
    }
}
