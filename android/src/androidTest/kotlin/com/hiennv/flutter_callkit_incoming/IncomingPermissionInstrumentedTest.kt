package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class IncomingPermissionInstrumentedTest {
    @Test
    fun explicitPermissionRequestToleratesMissingSettingsActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun appOp(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} USE_FULL_SCREEN_INTENT $mode"
            )).use { it.readBytes() }
        }
        appOp("deny")
        try {
            var attempts = 0
            instrumentation.runOnMainSync {
                val activity = object : Activity() {
                    override fun getPackageName() = context.packageName
                    override fun startActivity(intent: Intent) {
                        attempts++
                        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
                        assertEquals("package:${context.packageName}", intent.data.toString())
                        throw ActivityNotFoundException("Test device has no settings handler")
                    }
                }
                CallkitNotificationManager(context, null).requestFullIntentPermission(activity)
            }
            assertEquals(1, attempts)
        } finally {
            appOp("default")
        }
    }

    @Test
    fun deniedFullScreenPermissionStillShowsAnswerControlsWithoutOpeningSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun appOp(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} USE_FULL_SCREEN_INTENT $mode"
            )).use { it.readBytes() }
        }
        appOp("deny")
        val monitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT),
            Instrumentation.ActivityResult(0, null), true
        )
        try {
            assertFalse((context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent())
            val data = Bundle().apply {
                putString(CallkitConstants.EXTRA_CALLKIT_ID, "permission-regression")
                putLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 30000L)
                putString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "Test caller")
            }
            val activityMonitor = instrumentation.addMonitor(CallkitIncomingActivity::class.java.name, null, false)
            context.startActivity(CallkitIncomingActivity.getIntent(context, data))
            val activity = instrumentation.waitForMonitorWithTimeout(activityMonitor, 5000)
            instrumentation.removeMonitor(activityMonitor)
            assertNotNull("Incoming activity must open", activity)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var shown = false
            while (!shown && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    shown = activity.findViewById<View>(R.id.ivAcceptCall)?.isShown == true &&
                        activity.findViewById<View>(R.id.ivDeclineCall)?.isShown == true
                }
                if (!shown) Thread.sleep(50)
            }
            try {
                assertEquals("Incoming calls must not redirect to Settings", 0, monitor.hits)
                assertTrue("Answer and decline controls must be visible", shown)
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            appOp("default")
        }
    }
}
