package com.clipvault.app.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.clipvault.app.ClipVaultApp
import com.clipvault.app.data.ClipRepository
import com.clipvault.app.overlay.OverlayPanelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        var instance: ClipboardAccessibilityService? = null
            private set
    }

    fun setLastClipText(text: String) {
        lastClipText = text
    }

    private lateinit var repository: ClipRepository
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var overlayManager: OverlayPanelManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastClipText: String? = null
    private var lastClipTimestamp = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var captureInFlight = false

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardMayHaveChanged()
    }

    private val clipboardPoller = object : Runnable {
        override fun run() {
            try {
                val desc = clipboardManager.primaryClipDescription
                val ts = desc?.timestamp ?: 0L
                if (ts != 0L && ts != lastClipTimestamp) {
                    lastClipTimestamp = ts
                    onClipboardMayHaveChanged()
                }
            } catch (_: Exception) { }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val accessibilityButtonCallback =
        object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                toggleOverlay()
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val app = application as ClipVaultApp
        repository = app.repository

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener)

        try {
            lastClipTimestamp = clipboardManager.primaryClipDescription?.timestamp ?: 0L
        } catch (_: Exception) { }

        handler.postDelayed(clipboardPoller, POLL_INTERVAL_MS)

        overlayManager = OverlayPanelManager(this, repository, serviceScope) { text ->
            pasteText(text)
        }

        accessibilityButtonController.registerAccessibilityButtonCallback(
            accessibilityButtonCallback
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Dismiss overlay when any other window comes to foreground
        // (notification shade, recent apps, home screen, lock screen, other apps)
        // Grace period: ignore events within 1.5s of opening so the overlay's own
        // appearance (and the SystemUI accessibility button handler) don't kill it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (overlayManager.isShowing) {
                val elapsed = System.currentTimeMillis() - overlayManager.showTimestamp
                if (elapsed > 1500) {
                    val pkg = event.packageName?.toString()
                    if (pkg != null && pkg != packageName) {
                        overlayManager.dismiss()
                    }
                }
            }
        }

        // Detect system "Copied" toast for clipboard capture
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) {
            val text = event.text?.joinToString(" ")?.lowercase() ?: return
            if (text.contains("copied") || text.contains("clipboard")) {
                onClipboardMayHaveChanged()
            }
        }
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacks(clipboardPoller)
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener)
        accessibilityButtonController.unregisterAccessibilityButtonCallback(
            accessibilityButtonCallback
        )
        overlayManager.dismiss()
        serviceScope.cancel()
    }

    private fun onClipboardMayHaveChanged() {
        if (captureInFlight) return
        captureInFlight = true

        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank() && text != lastClipText) {
                    lastClipText = text
                    serviceScope.launch {
                        try { repository.addClip(text) }
                        catch (_: Exception) { }
                    }
                    captureInFlight = false
                    return
                }
            }
        } catch (_: Exception) { }

        launchCaptureActivity()
        handler.postDelayed({ captureInFlight = false }, 2000)
    }

    private fun launchCaptureActivity() {
        try {
            val intent = Intent(this, ClipboardCaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            captureInFlight = false
        }
    }

    private fun toggleOverlay() {
        if (overlayManager.isShowing) {
            overlayManager.dismiss()
        } else {
            overlayManager.show()
        }
    }

    private fun pasteText(text: String) {
        val clip = android.content.ClipData.newPlainText("ClipVault", text)
        clipboardManager.setPrimaryClip(clip)
        lastClipText = text
        // Update timestamp to prevent poll from re-capturing our own paste
        try {
            lastClipTimestamp = clipboardManager.primaryClipDescription?.timestamp ?: 0L
        } catch (_: Exception) { }

        overlayManager.dismiss()

        handler.postDelayed({
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val pasted = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) {
                    Toast.makeText(this, "Pasted!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Copied to clipboard — no text field focused", Toast.LENGTH_SHORT).show()
            }
        }, 350)
    }
}
