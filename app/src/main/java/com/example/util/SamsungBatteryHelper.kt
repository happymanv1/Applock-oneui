package com.example.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object SamsungBatteryHelper {
    private const val TAG = "SamsungBatteryHelper"

    /**
     * Check if the application is currently exempted from Android OS level battery optimizations.
     */
    fun isBatteryOptimizingIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    /**
     * Checks if the device is specifically a Samsung model running One UI / Android.
     */
    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("samsung")
    }

    /**
     * Request standard system dialog to exempt App Lock from battery optimizations.
     * Requires permission: android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     */
    @SuppressLint("BatteryLife")
    fun getExemptionIntent(context: Context): Intent {
        return Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Attempts to open the general Battery Optimization settings page.
     */
    fun getBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Returns a list of Intent targets mapping to Samsung's historical and current One UI Device Care
     * and Smart Manager pages. These settings control automatic application sleeping and background killing.
     */
    fun getSamsungDeviceCareIntents(context: Context): List<Intent> {
        val packageAndClasses = listOf(
            // One UI 5.0+ Device Care (Battery)
            Pair("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            // One UI Smart Manager Main Settings
            Pair("com.samsung.android.lool", "com.samsung.android.sm.ui.dashboard.SmartManagerDashBoardActivity"),
            // Samsung App Auto Run / Battery Management settings pages
            Pair("com.samsung.android.lool", "com.samsung.android.sm.power.AppPowerManagementActivity"),
            // Samsung historical smart manager optimizations
            Pair("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Pair("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Pair("com.samsung.android.sm", "com.samsung.android.sm.ui.dashboard.SmartManagerDashBoardActivity"),
            Pair("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.dashboard.SmartManagerDashBoardActivity")
        )

        val intents = mutableListOf<Intent>()
        for ((pkg, cls) in packageAndClasses) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            intents.add(intent)
        }

        // Direct to Samsung specific ignore optimizations if possible
        intents.add(getBatteryOptimizationSettingsIntent().apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })

        return intents
    }

    /**
     * Intelligently launches the most appropriate Samsung Device Care screen
     * or standard optimization settings as fallback.
     */
    fun launchSamsungDeviceCare(context: Context): Boolean {
        if (isSamsungDevice()) {
            val intents = getSamsungDeviceCareIntents(context)
            val packageManager = context.packageManager

            for (intent in intents) {
                // Determine if this intent can be resolved by the device
                val resolveInfo = packageManager.resolveActivity(intent, 0)
                if (resolveInfo != null) {
                    try {
                        context.startActivity(intent)
                        Log.d(TAG, "Successfully started Samsung intent: ${intent.component?.className}")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching Samsung care activity, trying next fallback: ${e.message}")
                    }
                }
            }
        }

        // If not a Samsung device, or all Samsung targets failed, launch standard battery settings.
        try {
            val standardIntent = getBatteryOptimizationSettingsIntent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(standardIntent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching standard option: ${e.message}")
        }
        return false
    }
}
