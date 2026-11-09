package org.futo.inputmethod.latin.diag

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.InputAttributes
import org.futo.inputmethod.latin.LastComposedWord
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.utils.BinaryDictionaryUtils
import org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting
import org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting
import org.futo.inputmethod.latin.xlm.MODEL_OPTION_KEY
import org.json.JSONArray
import org.json.JSONObject

/**
 * Event builders for the typing-side diagnostic hooks (contract: tools/diaglog/SPEC.md).
 *
 * Why this file exists: keeps the call sites in InputLogic, GeneralIME, LanguageModelFacilitator
 * and LatinIME to one or two guarded lines each, so the fork stays easy to rebase on upstream.
 * Call sites guard with `if (DiagLog.isEnabled())`, so with logging off none of this runs.
 *
 * Content (typed words, candidates) goes only into DiagLog.record(), never into logcat.
 * Every function swallows its own exceptions: a diagnostic must never break typing.
 *
 * Threading: suggestion hooks run on the dictionary coroutine scope, commit/revert/backspace
 * hooks on the main thread. Shared state is therefore @Volatile or synchronized on [lock].
 */
object DiagHooks {
    private const val MAX_CANDIDATES = 10
    private const val REVERT_WINDOW_MS = 10_000L      // spec: deleted "soon after commit"
    private const val RETYPE_WINDOW_MS = 15_000L      // spec: retyped word within 15 s
    private const val DAY_MS = 24L * 3600 * 1000

    private val lock = Any()

    // ---- session / editor ------------------------------------------------------------------

    private var lastSessionDay = -1L
    private var sessionBusy = false

    /**
     * Called from LatinIME.onStartInput AFTER latinIMELegacy.onStartInput (and from the
     * action-window override path). Until it runs the gate stays closed (DiagLog.onFinishInput
     * was called first), so whatever InputLogic.startInput does with the previous field's
     * composing word cannot be logged under the new field's state. Passes password, noLearning and the raw IME_FLAG_NO_PERSONALIZED_LEARNING
     * flag separately: for number-class password fields InputAttributes.mNoLearning is false
     * (InputAttributes only sets it in the TYPE_CLASS_TEXT branch), so the IME flag and the
     * password flag must be forwarded explicitly. A null EditorInfo fails closed (suppressed).
     */
    @JvmStatic
    fun onStartInput(context: Context, info: EditorInfo?, restarting: Boolean) {
        try {
            if (info == null) {
                DiagLog.onStartInput(true, true, true, 0, false, false)
                return
            }
            // Package name is deliberately not passed on and never logged.
            val attrs = InputAttributes(info, false, "")
            val imeNoLearning = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
            DiagLog.onStartInput(
                attrs.mIsPasswordField || attrs.mIsNumericalPasswordField,
                attrs.mNoLearning, imeNoLearning, inputClass,
                attrs.mIsEmailField, attrs.mIsUriField
            )
            if (!DiagLog.isEnabled()) return // suppressed field or logging off: log nothing
            maybeSession(context)
            DiagLog.record("editor", JSONObject().apply {
                put("inputTypeClass", inputClass)
                put("isEmail", attrs.mIsEmailField)
                put("isUrl", attrs.mIsUriField)
                put("noAutoCorrect", attrs.mInputTypeNoAutoCorrect)
                put("restarting", restarting)
                // Always false here: record() drops events while suppressed, so a suppressed
                // field is visible only in DiagStats.suppressedFields.
                put("suppressed", false)
            })
        } catch (e: Throwable) {
            // Fail closed if the flag computation itself broke.
            try { DiagLog.onStartInput(true, true, true, 0, false, false) } catch (ignored: Throwable) {}
        }
    }

    /** Session snapshot: once per process per local day, so every day file is self-describing. */
    private fun maybeSession(context: Context) {
        val day = (System.currentTimeMillis() + java.util.TimeZone.getDefault().rawOffset) / DAY_MS
        synchronized(lock) {
            if (day == lastSessionDay || sessionBusy) return
            sessionBusy = true
        }
        val app = context.applicationContext ?: context
        // DataStore reads block, so build the snapshot off the input thread.
        Thread {
            try {
                val o = JSONObject()
                o.put("applicationId", app.packageName)
                o.put("debugBuild", BuildConfig.DEBUG)
                try {
                    val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                    o.put("versionName", pi.versionName)
                } catch (ignored: Throwable) {}
                o.put("sdk", Build.VERSION.SDK_INT)
                o.put("device", Build.MODEL)
                val s = Settings.getInstance().current
                if (s != null) o.put("settings", JSONObject().apply {
                    put("autoCorrect", s.mAutoCorrectionEnabledPerUserSettings)
                    put("autoCorrectThresholdSetting", s.mAutoCorrectionThreshold.toDouble())
                    put("backspaceUndoesAutocorrect", s.mBackspaceUndoesAutocorrect)
                    put("transformer", s.mTransformerPredictionEnabled)
                    put("gestureInput", s.mGestureInputEnabled)
                    put("numberRow", s.mIsNumberRowEnabled)
                    put("keyboardHeightScale", s.mKeyboardHeightScale.toDouble())
                    put("lmAutocorrectThreshold",
                        app.getSettingBlocking(AutocorrectThresholdSetting).toDouble())
                    put("binaryDictWeight",
                        app.getSettingBlocking(BinaryDictTransformerWeightSetting).toDouble())
                })
                DiagSwipeHooks.sessionFields(app, o) // swipe decoder settings, keyboard geometry
                // "lang:modelName" entries; file names only.
                o.put("models", JSONArray(app.getSettingBlocking(MODEL_OPTION_KEY).toList()))
                try {
                    o.put("locale", RichInputMethodManager.getInstance().currentSubtypeLocale.toString())
                } catch (ignored: Throwable) {}
                try {
                    o.put("layout",
                        KeyboardSwitcher.getInstance().keyboard?.mId?.mKeyboardLayoutSetName)
                } catch (ignored: Throwable) {}
                // Only a recorded session counts: record() drops it while suppressed or off.
                val accepted = DiagLog.record("session", o)
                synchronized(lock) { if (accepted) lastSessionDay = day }
            } catch (e: Throwable) {
                // lastSessionDay untouched: retry on the next field
            } finally {
                synchronized(lock) { sessionBusy = false }
            }
        }.start()
    }

    /**
     * LatinIME.init() runs before first unlock after boot and DiagLog stays off then
     * (credential-encrypted storage unavailable). DiagLog.init is idempotent, so on unlock
     * re-run its serialised state load via reload(), which re-checks lock state, key and the
     * persisted setting on the writer thread.
     */
    @JvmStatic
    fun onUnlocked(context: Context) {
        DiagLog.reload()
    }

    /**
     * Drops every piece of state that refers to typed text or to the previous field. Called on
     * field change, disable and clear so a pending backspaceWord, last commit or suggestion
     * signature cannot be emitted into (or deduplicated against) a different field or a log the
     * user just erased. [clearSession] also forgets that today's session snapshot was written.
     */
    @JvmStatic
    fun reset(clearSession: Boolean) {
        synchronized(lock) {
            deleted = null
            ctxs.clear()
            latest = null
            if (clearSession) lastSessionDay = -1L
        }
        lastCommit = null
        resumedStart = null
        lastSuggestSig = null
        lastDoubleSpaceAt = 0L
    }

    // ---- suggest -----------------------------------------------------------------------------

    /**
     * Per-update context: LM outcome and merge notes for one suggestion update. Created at
     * suggestBegin and looked up by sequence number (or handed over inside PredictionInputValues
     * for the LM coroutine), so a stale coroutine only ever writes into its own update's
     * context. Defaults describe an update where no LM was consulted. [gen] is the field
     * generation at begin: the final `suggest` event is dropped if the field has changed.
     */
    class UpdateCtx(val gen: Int) {
        @Volatile var lmOutcome = "skipped"
        @Volatile var lmWaitMs = 0L
        @Volatile var lmSkipReason: String? = null
        @Volatile var mergeLm: String? = null
        @Volatile var mergeDict: String? = null
        val mergeNotes = ArrayList<String>()
    }

    private const val MAX_CTX = 8
    private val ctxs = object : LinkedHashMap<Int, UpdateCtx>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, UpdateCtx>?) =
            size > MAX_CTX
    }
    @Volatile private var latest: UpdateCtx? = null
    @Volatile private var lastSuggestSig: String? = null

    /** Start of one suggestion update (GeneralIME.updateSuggestionsDictionaryInternal). */
    @JvmStatic
    fun suggestBegin(seq: Int) {
        val c = UpdateCtx(DiagLog.generation())
        synchronized(lock) { ctxs[seq] = c; latest = c }
    }

    /** Context of the update running now, for hooks that have no sequence number. */
    @JvmStatic
    fun current(): UpdateCtx? = latest

    private fun ctxFor(seq: Int): UpdateCtx? = synchronized(lock) { ctxs[seq] }

    /** Why the transformer was not (or could not be) used for this update. */
    @JvmStatic
    fun lmSkip(reason: String, ctx: UpdateCtx? = current()) { ctx?.lmSkipReason = reason }

    /**
     * Result of waiting for the LM (350 ms budget in GeneralIME). null means the wait timed out,
     * empty means the LM returned nothing (model missing, locale mismatch, error), else ok.
     */
    @JvmStatic
    fun lmDone(seq: Int, result: List<*>?, waitMs: Long) {
        val c = ctxFor(seq) ?: return
        c.lmWaitMs = waitMs
        c.lmOutcome = when {
            result == null -> "timeout"
            result.isEmpty() -> "empty"
            else -> "ok"
        }
    }

    /** A merge branch taken in LanguageModelFacilitator.processAndMergeSuggestions. */
    @JvmStatic
    fun mergeNote(branch: String, ctx: UpdateCtx? = current()) {
        ctx?.let { synchronized(it.mergeNotes) { it.mergeNotes.add(branch) } }
    }

    /** Top LM and dictionary candidates that fed the merge, to see who won and by how much. */
    @JvmStatic
    fun mergeTop(lmTop: SuggestedWordInfo?, dictTop: SuggestedWordInfo?, ctx: UpdateCtx? = current()) {
        ctx ?: return
        ctx.mergeLm = lmTop?.let { "${it.mWord}:${it.mScore}" }
        ctx.mergeDict = dictTop?.let { "${it.mWord}:${it.mScore}" }
    }

    private fun kindName(k: Int) = when (k) {
        SuggestedWordInfo.KIND_TYPED -> "typed"
        SuggestedWordInfo.KIND_CORRECTION -> "correction"
        SuggestedWordInfo.KIND_COMPLETION -> "completion"
        SuggestedWordInfo.KIND_WHITELIST -> "whitelist"
        SuggestedWordInfo.KIND_BLACKLIST -> "blacklist"
        SuggestedWordInfo.KIND_HARDCODED -> "hardcoded"
        SuggestedWordInfo.KIND_APP_DEFINED -> "appDefined"
        SuggestedWordInfo.KIND_SHORTCUT -> "shortcut"
        SuggestedWordInfo.KIND_PREDICTION -> "prediction"
        SuggestedWordInfo.KIND_RESUMED -> "resumed"
        SuggestedWordInfo.KIND_OOV_CORRECTION -> "oov"
        SuggestedWordInfo.KIND_EMOJI_SUGGESTION -> "emoji"
        SuggestedWordInfo.KIND_UNDO -> "undo"
        else -> "kind$k"
    }

    /** [word, score, kind, source]; source is "lm" for transformer-originated words. */
    private fun candidates(words: SuggestedWords): JSONArray {
        val arr = JSONArray()
        val list = words.mSuggestedWordInfoList
        for (i in 0 until minOf(MAX_CANDIDATES, list.size)) {
            val w = list[i]
            arr.put(JSONArray().apply {
                put(w.mWord); put(w.mScore); put(kindName(w.kind))
                put(if (w.mOriginatesFromTransformerLM) "lm" else (w.mSourceDict?.mDictType ?: "none"))
            })
        }
        return arr
    }

    /**
     * Final suggestion strip content for a composing word, from GeneralIME.onGetSuggestedWords.
     * This is the single event that carries the autocorrect decision: top candidates, whether
     * the first one will be auto-applied, the normalized score compared against the threshold,
     * and the LM context gathered by the other hooks. Consecutive identical states are dropped
     * (typing a letter that does not change the strip should not double the log).
     */
    @JvmStatic
    fun suggest(
        seq: Int, words: SuggestedWords, inputStyle: Int, composing: Boolean,
        threshold: Float, transformerDisabled: Boolean, consecutiveTimeouts: Int
    ) {
        try {
            if (!composing || inputStyle != SuggestedWords.INPUT_STYLE_TYPING) return
            val typed = words.mTypedWordInfo?.mWord ?: return
            if (typed.isEmpty()) return
            // No context: begun while logging was off, or dropped by reset() because the field
            // changed. Fail closed rather than attribute the event to the current field.
            val c = ctxFor(seq) ?: return
            val list = words.mSuggestedWordInfoList
            val first = list.getOrNull(1)?.takeIf { !it.isKindOf(SuggestedWordInfo.KIND_TYPED) }
            val sig = buildString {
                append(typed).append('|').append(words.mWillAutoCorrect).append('|')
                for (i in 0 until minOf(MAX_CANDIDATES, list.size)) append(list[i].mWord).append(',')
                append('|').append(c.lmOutcome)
            }
            if (sig == lastSuggestSig) return
            lastSuggestSig = sig

            val o = JSONObject()
            o.put("typed", typed)
            o.put("candidates", candidates(words))
            o.put("threshold", threshold.toDouble())
            if (first != null) {
                try {
                    // Same inputs as AutoCorrectionUtils.suggestionExceedsThreshold, recomputed
                    // here so the hook does not have to sit inside that hot path.
                    o.put("normalizedScore",
                        BinaryDictionaryUtils.calcNormalizedScore(typed, first.mWord, first.mScore).toDouble())
                } catch (ignored: Throwable) {}
            }
            o.put("willAutoCorrect", words.mWillAutoCorrect)
            o.put("lm", c.lmOutcome)
            o.put("lmWaitMs", c.lmWaitMs)
            c.lmSkipReason?.let { o.put("lmSkipReason", it) }
            val notes = synchronized(c.mergeNotes) { ArrayList(c.mergeNotes) }
            o.put("mergeBranch", when {
                notes.isNotEmpty() -> notes.joinToString("+")
                c.lmOutcome == "ok" -> "none"
                else -> "dictOnly"
            })
            c.mergeLm?.let { o.put("mergeLmTop", it) }
            c.mergeDict?.let { o.put("mergeDictTop", it) }
            o.put("consecutiveTimeouts", consecutiveTimeouts)
            o.put("transformerDisabled", transformerDisabled)
            DiagLog.record("suggest", o, c.gen)
        } catch (ignored: Throwable) {}
    }

    /** Transformer timeout counter changed or was reset (LanguageModelFacilitator). */
    @JvmStatic
    fun lmState(timeouts: Int, disabled: Boolean, reason: String) {
        try {
            DiagLog.record("lmState", JSONObject().apply {
                put("timeouts", timeouts); put("disabled", disabled); put("reason", reason)
            })
        } catch (ignored: Throwable) {}
    }

    // ---- commit / pick / revert / backspaceWord ---------------------------------------------

    private class Commit(val typed: String, val committed: String, val at: Long)
    @Volatile private var lastCommit: Commit? = null

    // Word the user deleted soon after committing it; reported when retyped or when it goes stale.
    private class Deleted(val word: String, val typed: String, val at: Long)
    private var deleted: Deleted? = null
    private var resumedStart: String? = null

    /** From InputLogic.commitChosenWord after the commit: every way a word gets committed. */
    @JvmStatic
    fun onCommit(
        typed: String?, committed: String, commitType: Int, isBatch: Boolean, ngram: NgramContext?
    ) {
        try {
            val typedS = typed ?: ""
            val type = when {
                isBatch -> "swipe"
                commitType == LastComposedWord.COMMIT_TYPE_MANUAL_PICK -> "manualPick"
                commitType == LastComposedWord.COMMIT_TYPE_DECIDED_WORD ->
                    if (typedS == committed) "typed" else "autocorrect"
                commitType == LastComposedWord.COMMIT_TYPE_CANCEL_AUTO_CORRECT -> "cancelAutoCorrect"
                else -> "typed"
            }
            val now = SystemClock.uptimeMillis()
            flushDeletedWord(committed)
            lastCommit = Commit(typedS, committed, now)
            val prev = JSONArray()
            ngram?.extractPrevWordsContextArray()?.let { a ->
                for (w in a.takeLast(5)) prev.put(w)
            }
            DiagLog.record("commit", JSONObject().apply {
                put("typed", typedS); put("committed", committed)
                put("commitType", type); put("prevWords", prev)
            })
        } catch (ignored: Throwable) {}
    }

    /** From InputLogic.onPickSuggestionManually: which strip slot the user chose. */
    @JvmStatic
    fun onPick(words: SuggestedWords, picked: SuggestedWordInfo, composing: Boolean) {
        try {
            DiagLog.record("pick", JSONObject().apply {
                put("rank", words.indexOf(picked))
                put("word", picked.mWord)
                put("kind", kindName(picked.kind))
                put("typed", words.mTypedWordInfo?.mWord ?: "")
                put("composing", composing)
                put("candidates", candidates(words))
            })
        } catch (ignored: Throwable) {}
    }

    /** Backspace right after a commit undid it (autocorrect revert; double space is separate). */
    @JvmStatic
    fun onRevert(typed: CharSequence?, corrected: CharSequence?, kind: String) {
        try {
            val now = SystemClock.uptimeMillis()
            val since = if (kind == "doubleSpace") now - lastDoubleSpaceAt else lastCommit?.let { now - it.at }
            DiagLog.record("revert", JSONObject().apply {
                put("typed", typed?.toString() ?: ""); put("corrected", corrected?.toString() ?: "")
                put("kind", kind)
                if (since != null) put("msSinceCommit", since)
            })
            // A revert is already reported; it must not also look like a deleted committed word.
            if (kind != "doubleSpace") lastCommit = null
        } catch (ignored: Throwable) {}
    }

    @Volatile private var lastDoubleSpaceAt = 0L

    @JvmStatic
    fun onDoubleSpacePeriod() {
        try {
            lastDoubleSpaceAt = SystemClock.uptimeMillis()
            DiagLog.record("doubleSpacePeriod", JSONObject())
        } catch (ignored: Throwable) {}
    }

    /**
     * Backspace on a composing word (InputLogic.handleBackspaceEvent), called before the
     * character is removed. A just-committed word that backspace pulled back into composition
     * and then deleted entirely is a sign the user rejected it without using the revert path.
     * [resumed] marks a composition re-opened from committed text (WordComposer.isResumed).
     */
    @JvmStatic
    fun onBackspaceComposing(typedBefore: String, resumed: Boolean) {
        try {
            if (!resumed) { resumedStart = null; return }
            if (resumedStart == null) resumedStart = typedBefore
            if (typedBefore.codePointCount(0, typedBefore.length) > 1) return
            val start = resumedStart ?: return
            resumedStart = null
            val c = lastCommit ?: return
            val now = SystemClock.uptimeMillis()
            if (now - c.at > REVERT_WINDOW_MS || !c.committed.equals(start, ignoreCase = true)) return
            lastCommit = null
            synchronized(lock) {
                flushDeletedWord(null)
                deleted = Deleted(c.committed, c.typed, now)
            }
        } catch (ignored: Throwable) {}
    }

    /**
     * Emits the pending backspaceWord. [nextCommitted] is the word committed next: it counts as
     * a retype only when it differs from the deleted word and arrived within 15 s. Called on the
     * next commit, next deletion and next field, so a stale pending entry is never lost.
     */
    private fun flushDeletedWord(nextCommitted: String?) {
        val d = synchronized(lock) { deleted.also { deleted = null } } ?: return
        val now = SystemClock.uptimeMillis()
        val retyped = nextCommitted?.takeIf {
            now - d.at <= RETYPE_WINDOW_MS && !it.equals(d.word, ignoreCase = true)
        }
        DiagLog.record("backspaceWord", JSONObject().apply {
            put("word", d.word); put("typed", d.typed)
            if (retyped != null) put("retyped", retyped)
            put("msToNext", if (nextCommitted != null) now - d.at else JSONObject.NULL)
        })
    }
}
