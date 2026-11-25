package org.futo.inputmethod.latin.uix.actions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.diag.UrlReaderService
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.pages.InlineAutofillSetting

// Fork-only action: opens Bitwarden's "Report mobile autofill failure" Google Form with every field
// it can answer already filled in, so a report from a page that failed to fill takes a few taps.
// Fill assist / Map the Web is browser-extension only, so this form is the only mobile route.
//
// The entry.NNN ids are Google Forms field ids read from the form's own page source. If Bitwarden
// recreates the form, the prefill silently stops filling in (the form still opens), so re-read
// FB_PUBLIC_LOAD_DATA_ from the viewform page and update the ids.
private const val FORM_URL =
    "https://docs.google.com/forms/d/e/1FAIpQLScMopHyN7KGJs8hW562VTzbIGL4KcFnx0wJcsW0GYE1BnPiGA/viewform"
private const val ENTRY_OS = "entry.514549959"
private const val ENTRY_DEVICE = "entry.1375801761"
private const val ENTRY_OS_VERSION = "entry.1046505633"
private const val ENTRY_AUTOFILL_SERVICE = "entry.381019869"
private const val ENTRY_INLINE = "entry.1070016766"
private const val ENTRY_ACCESSIBILITY = "entry.1213033606"
private const val ENTRY_DRAW_OVER = "entry.677437117"
private const val ENTRY_BITWARDEN_VERSION = "entry.1976035970"
private const val ENTRY_APP_OR_URL = "entry.646332475"
private const val ENTRY_EXTRA = "entry.571090144"

private val BITWARDEN_PACKAGES = listOf("com.x8bit.bitwarden", "com.x8bit.bitwarden.beta")

private fun Context.packageVersion(pkg: String): String? = try {
    packageManager.getPackageInfo(pkg, 0).versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
}

private fun Context.drawOverGranted(pkg: String): Boolean? = try {
    val uid = packageManager.getApplicationInfo(pkg, 0).uid
    val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    ops.unsafeCheckOpNoThrow("android:system_alert_window", uid, pkg) == AppOpsManager.MODE_ALLOWED
} catch (e: Exception) {
    null
}

private fun yesNo(v: Boolean?) = when (v) { true -> "Yes"; false -> "No"; null -> null }

private fun Context.buildReportUri(editorPackage: String?): Uri {
    val bitwardenPkg = BITWARDEN_PACKAGES.firstOrNull { packageVersion(it) != null }

    val enabledAutofill = try {
        Settings.Secure.getString(contentResolver, "autofill_service")
    } catch (e: Exception) { null }
    val enabledAccessibility = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    } catch (e: Exception) { null }

    val autofillOn = bitwardenPkg?.let { enabledAutofill?.startsWith("$it/") == true }
    val accessibilityOn = bitwardenPkg?.let { enabledAccessibility?.contains("$it/") == true }
    val drawOver = bitwardenPkg?.let { drawOverGranted(it) }

    // The keyboard sees which app has the text field focused, but not the page address; that comes
    // from UrlReaderService (an accessibility service, off until enabled). Without it the field
    // ends with "URL: " for the user to finish.
    val editorVersion = editorPackage?.let { packageVersion(it) }
    val url = if (editorPackage in UrlReaderService.BROWSER_PACKAGES) UrlReaderService.instance?.currentUrl() else null
    val appOrUrl = if (editorPackage == null) "" else buildString {
        append(editorPackage)
        if (editorVersion != null) append(" $editorVersion")
        append(" - URL: ")
        if (url != null) append(url)
    }

    val extra = buildString {
        append("Keyboard: FUTO Keyboard ${BuildConfig.VERSION_NAME} (inline autofill setting ")
        append(if (getSettingBlocking(InlineAutofillSetting)) "on" else "off")
        append("). Sent from the keyboard's report button.")
    }

    val params = linkedMapOf<String, String?>(
        "usp" to "pp_url",
        ENTRY_OS to "Android",
        ENTRY_DEVICE to "${Build.MANUFACTURER} ${Build.MODEL}",
        ENTRY_OS_VERSION to Build.VERSION.RELEASE,
        ENTRY_AUTOFILL_SERVICE to yesNo(autofillOn),
        // Hardcoded: Bitwarden's display mode (inline vs overlay) is not readable from outside the
        // app. Change this if the Bitwarden setting is ever switched away from "Inline".
        ENTRY_INLINE to "Yes",
        ENTRY_ACCESSIBILITY to yesNo(accessibilityOn),
        ENTRY_DRAW_OVER to yesNo(drawOver),
        ENTRY_BITWARDEN_VERSION to bitwardenPkg?.let { packageVersion(it) },
        ENTRY_APP_OR_URL to appOrUrl,
        ENTRY_EXTRA to extra,
    )

    return Uri.parse(FORM_URL).buildUpon().apply {
        params.forEach { (k, v) -> if (v != null) appendQueryParameter(k, v) }
    }.build()
}

val AutofillReportAction = Action(
    icon = R.drawable.life_buoy,
    name = R.string.action_autofill_report_title,
    windowImpl = null,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val editorPackage = manager.getLatinIMEForDebug().currentInputEditorInfo?.packageName
        if (editorPackage in UrlReaderService.BROWSER_PACKAGES && UrlReaderService.instance == null) {
            Toast.makeText(context, "Enable \"FUTO diag: read browser URL\" in Accessibility to prefill the URL", Toast.LENGTH_LONG).show()
        }
        val intent = Intent(Intent.ACTION_VIEW, context.buildReportUri(editorPackage))
            // The IME context is not an Activity, so the browser needs a new task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app can open the report form", Toast.LENGTH_SHORT).show()
        }
    },
)

// Fork-only toolbar buttons are drawn yellow so they stand out from the upstream ones, including
// where the toolbar draws no circle behind the icon. The diagnostic toggle still turns red while
// logging is on (the caller passes that as an explicit override).
val ForkActionTint = Color(0xFFFFD60A)

fun forkActionTint(action: Action): Color? = when (action) {
    DiagToggleAction, DiagViewAction, DiagExportAction, DiagClearAction, AutofillReportAction -> ForkActionTint
    else -> null
}

// One-time flag so a user who later moves the button elsewhere is not overridden again.
val AutofillReportPinMigrated = SettingsKey(booleanPreferencesKey("autofill_report_pin_migrated"), false)

/**
 * Existing installs have a stored favorites list without autofill_report (ensureWellFormed would
 * drop it into More). Put it at the front of the scrolling toolbar once.
 */
suspend fun Context.migrateAutofillReportFavorite() {
    if (getSetting(AutofillReportPinMigrated)) return
    val stored = getSettingBlocking(ActionsSettings).toActionEditorItems().ensureWellFormed().toActionMap()
    val favorites = getSettingBlocking(FavoriteActions).toActionList()
        .filter { it != AutofillReportAction }.toMutableList()
    favorites.add(0, AutofillReportAction)

    val newMap = stored.mapValues { (_, v) -> v.filter { it != AutofillReportAction } }.toMutableMap()
    newMap[ActionCategory.Favorites] = favorites
    updateSettingsWithNewActions(newMap)
    setSetting(AutofillReportPinMigrated, true)
}
