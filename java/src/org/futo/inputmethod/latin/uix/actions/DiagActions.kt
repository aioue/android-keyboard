package org.futo.inputmethod.latin.uix.actions

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.diag.DIAG_LOG_ENABLED
import org.futo.inputmethod.latin.diag.DiagLog
import org.futo.inputmethod.latin.diag.DiagStats
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.ScrollableList

// Private-fork diagnostic log actions. The phone can write the log but never read the full thing:
// the on-disk files are RSA/AES encrypted and only the Mac holds the private key.

private fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

val DiagToggleAction = Action(
    icon = R.drawable.diag_record,
    name = R.string.action_diag_toggle_title,
    windowImpl = null,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        // Read the persisted setting, not DiagLog.isEnabled(): that is false while a password
        // field has suppressed logging, which would make the toggle turn on instead of off.
        manager.getLifecycleScope().launch {
            // setEnabled waits for the DiagLog writer thread (and its init load), so keep it off
            // the main thread; it reports whether the change really took effect.
            val enable = withContext(Dispatchers.IO) { !context.getSetting(DIAG_LOG_ENABLED) }
            val ok = withContext(Dispatchers.IO) { DiagLog.setEnabled(context, enable) }
            context.toast(
                when {
                    !ok -> "Diagnostic log can't start: ${DiagLog.lastError()}"
                    enable -> "Diagnostic log on"
                    else -> "Diagnostic log off"
                }
            )
        }
    },
)

val DiagViewAction = Action(
    icon = R.drawable.file_text,
    name = R.string.action_diag_view_title,
    windowImpl = { _, _ ->
        object : ActionWindow() {
            override val onlyShowAboveKeyboard: Boolean
                get() = true

            override val positionIsUserManagable: Boolean
                get() = true

            @Composable
            override fun windowName(): String = "Diagnostic log"

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                var stats by remember { mutableStateOf<DiagStats?>(null) }
                var recent by remember { mutableStateOf<List<String>>(emptyList()) }

                // Poll: events arrive continuously and the RAM ring has no change notification.
                LaunchedEffect(Unit) {
                    while (true) {
                        withContext(Dispatchers.Default) {
                            val s = DiagLog.stats()
                            val r = DiagLog.recent(50)
                            stats = s
                            recent = r
                        }
                        delay(1000)
                    }
                }

                ScrollableList {
                    Text(
                        "The full log on disk is encrypted and can only be read on the Mac. " +
                                "Below are the most recent events held in RAM (plaintext, never saved).",
                        style = DebugLabel
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Stats", style = DebugTitle)
                    val s = stats
                    if (s == null) {
                        Text("loading...", style = DebugLabel)
                    } else {
                        Text("enabled          = ${s.enabled}", style = DebugLabel)
                        Text("suppressed now   = ${s.suppressed}", style = DebugLabel)
                        Text("suppressed fields= ${s.suppressedFields}", style = DebugLabel)
                        Text("files on disk    = ${s.files}", style = DebugLabel)
                        Text("bytes on disk    = ${s.bytesOnDisk}", style = DebugLabel)
                        if (!s.keyLoaded) {
                            Text("WARNING: public key missing, nothing can be written", style = DebugLabel)
                        }
                        Text("events by type:", style = DebugLabel)
                        if (s.eventsByType.isEmpty()) {
                            Text("  (none)", style = DebugLabel)
                        }
                        s.eventsByType.entries.sortedBy { it.key }.forEach {
                            Text("  ${it.key} = ${it.value}", style = DebugLabel)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Recent events (${recent.size})", style = DebugTitle)
                    recent.asReversed().forEach {
                        Text(it, style = DebugLabel)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    },
    simplePressImpl = null,
    canShowKeyboard = true,
)

val DiagExportAction = Action(
    icon = R.drawable.diag_export,
    name = R.string.action_diag_export_title,
    windowImpl = null,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        manager.getLifecycleScope().launch {
            // Zipping touches disk, keep it off the main thread.
            val uri = withContext(Dispatchers.IO) { DiagLog.exportBundle(context) }
            if (uri == null) {
                context.toast("Nothing to export")
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // ClipData makes the read grant propagate to the app picked in the chooser.
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // The IME context is not an Activity, so the chooser needs a new task.
                context.startActivity(
                    Intent.createChooser(send, "Export diagnostic log")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    },
)

private const val CLEAR_CONFIRM_WINDOW_MS = 3000L
private var lastClearPressMs = 0L

val DiagClearAction = Action(
    icon = R.drawable.trash,
    name = R.string.action_diag_clear_title,
    windowImpl = null,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val now = android.os.SystemClock.elapsedRealtime()
        // Erasing is irreversible and the key sits next to harmless ones, so require a second press.
        if (lastClearPressMs != 0L && now - lastClearPressMs <= CLEAR_CONFIRM_WINDOW_MS) {
            lastClearPressMs = 0L
            DiagLog.clearAll(context)
            context.toast("Diagnostic log erased")
        } else {
            lastClearPressMs = now
            context.toast("Press again to erase the diagnostic log")
        }
    },
)

// One-time flag so users who later move diag_toggle elsewhere are not overridden again.
val DiagTogglePinMigrated = SettingsKey(booleanPreferencesKey("diag_toggle_pin_migrated"), false)

/**
 * Existing installs have a stored pinned list without diag_toggle (ensureWellFormed would drop it
 * into More). Insert it right after voice input and remove it from every other list. Runs once.
 */
suspend fun Context.migrateDiagTogglePin() {
    if (getSetting(DiagTogglePinMigrated)) return
    val ctx = this
    val stored = getSettingBlocking(ActionsSettings).toActionEditorItems().ensureWellFormed().toActionMap()
    val pinned = getSettingBlocking(PinnedActions).toActionList().filter { it != DiagToggleAction }.toMutableList()

    // The stored list holds either VoiceInputAction or SystemVoiceInputAction depending on the
    // setting; match through getActionOverride so both count as the anchor.
    val voice = ActionRegistry.getActionOverride(ctx, VoiceInputAction)
    val anchor = pinned.indexOfFirst { ActionRegistry.getActionOverride(ctx, it) == voice }
    pinned.add(if (anchor >= 0) anchor + 1 else pinned.size, DiagToggleAction)

    val newMap = stored.mapValues { (_, v) -> v.filter { it != DiagToggleAction } }.toMutableMap()
    newMap[ActionCategory.PinnedKey] = pinned
    updateSettingsWithNewActions(newMap)
    setSetting(DiagTogglePinMigrated, true)
}
