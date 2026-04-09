package com.clipvault.app.service

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.clipvault.app.ClipVaultApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Invisible activity that briefly comes to the foreground to read the clipboard.
 * Android 10+ restricts clipboard reads to foreground apps. The accessibility service's
 * OnPrimaryClipChangedListener fires but can't read the content. This activity
 * launches, waits briefly for the clipboard to be readable, saves it, and finishes.
 */
class ClipboardCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Small delay to ensure we're fully in the foreground before reading clipboard
        Handler(Looper.getMainLooper()).postDelayed({ readAndFinish() }, 120)
    }

    private fun readAndFinish() {
        try {
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) {
                    val app = application as ClipVaultApp
                    ClipboardAccessibilityService.instance?.setLastClipText(text)
                    CoroutineScope(Dispatchers.IO).launch {
                        try { app.repository.addClip(text) }
                        catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }

        finishAndRemoveTask()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
