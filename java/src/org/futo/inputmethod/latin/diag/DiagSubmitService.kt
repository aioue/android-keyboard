package org.futo.inputmethod.latin.diag

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Personal fork. T3 Code's reply box is a plain multi-line input with no submit key handler, so no
 * keyboard key can send a message; only its button can. This service (limited to com.t3tools.t3code
 * by diag_submit_service.xml) finds that button and clicks it. Its accessibility label changes with
 * state (apps/mobile composerSendPresentation.ts): Send, Queue (offline/queued), Steer/Restart
 * (agent running), or "Update queued message". A blocked-send reason replaces the label, in which
 * case nothing matches and we deliberately tap nothing.
 */
class DiagSubmitService : AccessibilityService() {
    companion object {
        const val T3_PACKAGE = "com.t3tools.t3code"
        private val SEND_LABELS = setOf("Send", "Queue", "Steer", "Restart", "Update queued message")

        @Volatile
        var instance: DiagSubmitService? = null
            private set
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

    /** Clicks T3's send button. Returns false if it isn't on screen, is disabled, or isn't labelled as a send action. */
    fun clickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName != T3_PACKAGE) return false
        val button = findSendButton(root) ?: run {
            Log.d("DiagSubmit", "no send button found")
            return false
        }
        var node: AccessibilityNodeInfo? = button
        while (node != null && !node.isClickable) node = node.parent
        val ok = node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d("DiagSubmit", "clicked=$ok label=${button.contentDescription}")
        return ok
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = node.contentDescription?.toString() ?: node.text?.toString()
        if (label != null && label in SEND_LABELS) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findSendButton(it) }
            if (found != null) return found
        }
        return null
    }
}
