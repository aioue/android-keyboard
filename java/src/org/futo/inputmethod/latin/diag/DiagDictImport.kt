package org.futo.inputmethod.latin.diag

import android.content.Context
import android.util.Log
import org.futo.inputmethod.latin.uix.PersonalWord
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import java.io.File

/**
 * Personal fork helper: bulk-adds words to the system-wide Android personal dictionary.
 *
 * The FUTO settings page only adds one word at a time and adb cannot write the dictionary (the
 * shell lacks WRITE_USER_DICTIONARY), but the keyboard holds that permission. Push a file with
 * one `word<TAB>frequency<TAB>locale` per line to `files/import_words.tsv` in the app's private
 * directory (`adb shell run-as <pkg> ...`); it is imported on the next keyboard start, then
 * deleted. Existing identical entries are skipped by UserDictionaryIO.put. Logs counts only.
 */
object DiagDictImport {
    private const val FILE = "import_words.tsv"

    fun runIfPresent(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        Thread {
            try {
                val words = f.readLines().mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.isEmpty() || p[0].isBlank()) null
                    else PersonalWord(
                        p[0].trim(),
                        p.getOrNull(1)?.trim()?.toIntOrNull() ?: 250,
                        p.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
                        0, null
                    )
                }
                UserDictionaryIO(context).put(words)
                Log.i("DiagDictImport", "imported ${words.size} words")
                f.delete()
            } catch (e: Exception) {
                Log.w("DiagDictImport", "import failed: ${e.javaClass.simpleName}")
            }
        }.start()
    }
}
