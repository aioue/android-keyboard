package org.futo.inputmethod.latin.diag

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Personal fork. A keyboard cannot see which web page it is typing into, only the app. The autofill
 * failure report button needs the page address, so this service reads the browser's address bar on
 * demand. It is a separate service from DiagSubmitService so each keeps a narrow scope: this one is
 * limited to the browsers in url_reader_service.xml, listens to no events and reads nothing until
 * the report button asks. It is off until enabled in Accessibility settings.
 */
class UrlReaderService : AccessibilityService() {
    companion object {
        // Chromium browsers name their address bar "<package>:id/url_bar". Add a package here and
        // in url_reader_service.xml to support another browser.
        val BROWSER_PACKAGES = setOf(
            "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
            "com.android.chrome",
        )

        @Volatile
        var instance: UrlReaderService? = null
            private set

        /**
         * Drops the query string and fragment: they often carry session tokens or search terms, and
         * Bitwarden only needs the site and page to reproduce a fill failure.
         */
        fun withoutQuery(url: String): String {
            val cut = url.indexOfAny(charArrayOf('?', '#'))
            return if (cut >= 0) url.substring(0, cut) else url
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** The address-bar text of the browser in front, without query or fragment; null if unavailable. */
    fun currentUrl(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg !in BROWSER_PACKAGES) return null
        val bar = root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar").firstOrNull() ?: return null
        val text = bar.text?.toString()?.trim().orEmpty()
        return if (text.isEmpty()) null else withoutQuery(text)
    }
}
