package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Elevated Accessibility Service for VeloStream.
 * Under Android's Concurrent Audio Recording policy (Android 10/11+),
 * an active accessibility service holding RECORD_AUDIO permission has
 * elevated audio privilege, allowing live streaming and game voice chat (e.g. BGMI)
 * to access the microphone concurrently without muting each other.
 */
class VeloStreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VeloStreamA11y"
        @Volatile var isRunning: Boolean = false
            private set

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${VeloStreamAccessibilityService::class.java.name}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                    componentName.contains("VeloStreamAccessibilityService", ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun createSettingsIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "VeloStream Accessibility Service connected - elevated audio recording privilege active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener; exists to provide privileged accessibility context for audio capture
    }

    override fun onInterrupt() {
        isRunning = false
        Log.i(TAG, "VeloStream Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "VeloStream Accessibility Service destroyed")
    }
}
