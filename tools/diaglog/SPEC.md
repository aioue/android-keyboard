# Diagnostic log: shared contract

Private fork feature. Nothing here is for upstream. The phone can write the log but cannot read it.

## Kotlin API (package `org.futo.inputmethod.latin.diag`, file `DiagLog.kt`)

```kotlin
object DiagLog {
    @JvmStatic fun init(context: Context)                 // call once from LatinIME.onCreate; loads public key asset, starts writer thread
    @JvmStatic fun isEnabled(): Boolean                   // volatile cached flag, cheap; false while suppressed
    @JvmStatic fun record(type: String, fields: org.json.JSONObject)  // non-blocking enqueue; no-op if !isEnabled()
    @JvmStatic fun onFinishInput()                        // fail closed: suppress, bump field generation, reset hook state; only onStartInput reopens
    @JvmStatic fun onStartInput(isPassword: Boolean, noLearning: Boolean, imeNoPersonalizedLearning: Boolean, inputTypeClass: Int, isEmail: Boolean, isUrl: Boolean)
        // sets suppression: password (incl. numeric password), noLearning, IME_FLAG_NO_PERSONALIZED_LEARNING => suppressed until next onStartInput
    @JvmStatic fun setEnabled(context: Context, enabled: Boolean): Boolean   // blocking (not main thread); false + lastError() if key missing/locked/uninitialised; persists to DataStore key DIAG_LOG_ENABLED, flushes on disable
    @JvmStatic fun flush()
    @JvmStatic fun clearAll(context: Context)             // flush, delete noBackupFilesDir/diaglog/*, clear RAM ring, counters, hook state and cacheDir/diag-export/*
    fun exportBundle(context: Context): android.net.Uri?  // zip (STORED) of day files into cacheDir/diag-export via FileProvider; null if nothing
    fun stats(): DiagStats                                // counters since last clear: events by type, bytes on disk, files, suppressed fields, enabled
    fun recent(n: Int): List<String>                      // last n events as plaintext JSON lines, RAM ring only, never persisted
}
val DIAG_LOG_ENABLED: SettingsKey<Boolean>   // default false
```

Hooks must call `if (DiagLog.isEnabled()) { ... build JSONObject ... DiagLog.record(...) }`. No hook may ever write log content to logcat.
Every record gets `"t"` (epoch ms) and `"type"` added by `record`. Event field names are lowerCamelCase.

## On-disk format

Directory `context.getNoBackupFilesDir()/diaglog/`, one file per process per local day: `diag-YYYYMMDD-HHMMSS.fdl` (HHMMSS = first write of that process on that day; `-N` suffix on a name clash). A new process never appends to an old file, because a torn final frame would make later frames unreachable. Files older than 30 days are deleted at init and on write. Append-only sequence of frames, each self-contained:

```
u32be  N            length of everything after this field
u16be  W            wrapped key length
W      wrappedKey   RSA-OAEP (SHA-256, MGF1-SHA-256, empty label) of the 32-byte AES key, under the RSA-3072 public key
12     iv           random per frame
N-2-W-12 ct         AES-256-GCM(key, iv, plaintext, aad=b"FDL1") ciphertext || 16-byte tag
```
plaintext = gzip(UTF-8 JSON Lines batch, one JSON object per line). A batch is flushed at 50 events, 30 s, setEnabled(false), or flush(). A fresh AES key and IV per frame.
Android gotcha: the default OAEP MGF1 digest on Android is SHA-1. Use `OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT)` explicitly.

Public key: X.509 SubjectPublicKeyInfo DER at `java/assets/diaglog_pub.der` (tracked, public). Private key: macOS Keychain generic password, service `futo-diaglog-private-key`, account `tom`, value = PKCS#8 DER base64. The private key never goes in the repo, a file, or any output.

## Export bundle
`futo-diag-YYYYMMDD-HHMMSS.zip` (streamed, not read whole into memory) containing the raw `.fdl` files (already compressed then encrypted; zip with STORED). Mac side: `tools/diaglog/decrypt.py <zip-or-fdl...>` writes JSONL to stdout.

## Event types (core set; the hook agents may add fields, not rename)
session (build, settings snapshot, models, locale, layout), editor (flags only, no text), suggest (typed word, candidates [word, score, kind, source], threshold, willAutoCorrect, lm outcome ok|timeout|empty|skipped, lmWaitMs, lmSkipReason, merge branch), commit (typed, committed, commitType autocorrect|manualPick|typed|swipe, prevWords), pick (rank, word, candidates), revert (typed, corrected, kind autocorrect|swipe|doubleSpace, msSinceCommit), backspaceWord (word deleted soon after commit, retyped word if any), swipe (points [x,y,t], layout id, keyboard geometry, decoder legacy|new, model, decoder score/lm/ctc, raw candidates, filtered candidates, latencyMs), lmState (timeouts, disabled), doubleSpacePeriod.

## Privacy gating
Events built asynchronously (suggest, swipe) capture a field generation at begin and are dropped at emit time (and again on the writer thread) if the generation changed or the current field is suppressed. Disabling logging also clears the RAM ring and hook state.
