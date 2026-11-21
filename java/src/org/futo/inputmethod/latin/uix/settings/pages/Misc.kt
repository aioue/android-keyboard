package org.futo.inputmethod.latin.uix.settings.pages

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.diag.DIAG_LOG_ENABLED
import org.futo.inputmethod.latin.diag.DiagLog
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem

// Personal fork: switch for the diagnostic log. It shows the stored setting that also drives the red
// toolbar icon, and changes it through DiagLog.setEnabled, the same call the toolbar toggle makes
// (writing only the stored value would not start or stop the logger).
@Composable
private fun DiagLogSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on = useDataStoreValue(DIAG_LOG_ENABLED)
    SettingToggleRaw(
        title = stringResource(R.string.diag_log_setting_title),
        enabled = on,
        setValue = { enable ->
            scope.launch {
                // setEnabled waits for the log writer thread, so it must not run on the main thread.
                val ok = withContext(Dispatchers.IO) { DiagLog.setEnabled(context, enable) }
                if (!ok) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.diag_log_setting_failed, DiagLog.lastError()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        },
        subtitle = stringResource(R.string.diag_log_setting_subtitle),
    )
}

val MiscMenu = UserSettingsMenu(
    title = R.string.misc_settings_title,
    navPath = "misc", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            ScreenTitle(stringResource(R.string.settings_export_configuration_title))
        },

        userSettingNavigationItem(
            title = (R.string.settings_export_configuration),
            subtitle = (R.string.settings_export_configuration_subtitle),
            style = NavigationItemStyle.Misc,
            navigateTo = "exportingcfg"
        ).copy(searchTags = R.string.settings_import_export_tags),
        userSettingNavigationItem(
            title = (R.string.settings_import_configuration),
            subtitle = (R.string.settings_import_configuration_subtitle),
            style = NavigationItemStyle.Misc,
            navigate = { nav ->
                SettingsExporter.triggerImportSettings(nav.context)
            }
        ).copy(searchTags = R.string.settings_import_export_tags),

        userSettingDecorationOnly { DiagLogSetting() },
    )
)