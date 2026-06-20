package com.example.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppLockDatabase
import com.example.data.AppLockRepository
import com.example.data.LockedApp
import com.example.service.AppLockService.Companion.unlockedStates
import com.example.ui.AuthenticationActivity
import kotlinx.coroutines.*

class AppLockService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var repository: AppLockRepository

    private var activeMonitoringJob: Job? = null
    private var lastForegroundPackage: String? = null
    private var homePackageName: String = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off. Resetting all unlock states.")
                synchronized(unlockedStates) {
                    unlockedStates.clear()
                }
                synchronized(packageBackgroundTimes) {
                    packageBackgroundTimes.clear()
                }
                lastForegroundPackage = null // Force a transition check when screen turns back on
            }
        }
    }

    companion object {
        private const val TAG = "AppLockService"
        private const val NOTIFICATION_ID = 8522
        private const val CHANNEL_ID = "app_lock_service_channel"

        // Holds in-memory record of unlocked packages
        val unlockedStates = HashSet<String>()
        
        // Map: PackageName -> Time it went to the background
        val packageBackgroundTimes = HashMap<String, Long>()

        // Tracks which app is currently undergoing biometric authentication to avoid launching multiple prompts
        @Volatile
        var currentlyAuthenticatingPackage: String? = null

        /**
         * Helper to register package as successfully unlocked.
         */
        fun markAsUnlocked(packageName: String) {
            synchronized(unlockedStates) {
                unlockedStates.add(packageName)
            }
            synchronized(packageBackgroundTimes) {
                packageBackgroundTimes.remove(packageName)
            }
            Log.d(TAG, "Package unlocked in-memory: $packageName")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AppLockService onCreate")
        
        try {
            createNotificationChannel()
            startForegroundServiceNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service notification: ${e.message}")
        }

        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val database = AppLockDatabase.getDatabase(applicationContext)
        repository = AppLockRepository(database.appLockDao())

        homePackageName = getHomePackageName()

        // Register receiver for screen off events
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)

        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AppLockService onStartCommand")
        try {
            startForegroundServiceNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand foreground: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppLockService onDestroy")
        unregisterReceiver(screenReceiver)
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun getHomePackageName(): String {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        return resolveInfo?.activityInfo?.packageName ?: "com.sec.android.app.launcher"
    }

    private fun startForegroundServiceNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps App Lock secure background service running."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startMonitoring() {
        activeMonitoringJob?.cancel()
        activeMonitoringJob = serviceScope.launch {
            while (isActive) {
                delay(500) // Poll foreground app state every 500ms
                try {
                    val currentForeground = getForegroundPackageName() ?: continue
                    
                    if (currentForeground != lastForegroundPackage) {
                        Log.v(TAG, "App shift detected: $lastForegroundPackage -> $currentForeground")
                        
                        // We left the previous app, record its background time
                        if (lastForegroundPackage != null && 
                            lastForegroundPackage != packageName && 
                            lastForegroundPackage != homePackageName) {
                            synchronized(packageBackgroundTimes) {
                                packageBackgroundTimes[lastForegroundPackage!!] = System.currentTimeMillis()
                            }
                        }
                        
                        if (currentForeground == packageName || currentForeground == homePackageName) {
                            // User is in our app or on launcher home, update state but don't prompt
                            lastForegroundPackage = currentForeground
                            continue
                        }

                        checkAndLockPackage(currentForeground)
                    }

                    lastForegroundPackage = currentForeground
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background usage tracking: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkAndLockPackage(packageName: String) {
        if (com.example.service.AppLockTileService.isPaused) {
            return
        }
        val matchedApp = repository.getAppByPackageName(packageName)
        if (matchedApp != null && matchedApp.isLocked) {
            val isBypassed = shouldBypassLock(matchedApp)
            if (!isBypassed) {
                // Determine if we are already authenticating this application to avoid standard activity stacking
                if (currentlyAuthenticatingPackage != packageName) {
                    launchAuthOverlay(packageName)
                }
            }
        }
    }

    private fun shouldBypassLock(app: LockedApp): Boolean {
        synchronized(unlockedStates) {
            if (!unlockedStates.contains(app.packageName)) {
                return false // Never unlocked in this session
            }

            // It was unlocked previously. Check if the grace period has expired since it went to background.
            val bgTime = synchronized(packageBackgroundTimes) { packageBackgroundTimes[app.packageName] }
            
            if (bgTime == null) {
                // If there's no background time recorded, it implies it hasn't properly gone to background
                // or we just returned to it from Auth. It's considered "actively unlocked".
                return true
            }

            val elapsedSinceBg = System.currentTimeMillis() - bgTime

            if (app.reLockOption.startsWith("After") && app.reLockOption.endsWith("minutes")) {
                try {
                    val minutesStr = app.reLockOption.removePrefix("After ").removeSuffix(" minutes").trim()
                    val minutes = minutesStr.toInt()
                    if (elapsedSinceBg < (minutes * 60 * 1000L)) {
                        return true
                    }
                } catch (e: Exception) {
                    if (elapsedSinceBg < 2000) return true
                }
            } else {
                when (app.reLockOption) {
                    "Immediately" -> {
                        // Tiny grace period for system transition bounces
                        if (elapsedSinceBg < 1000) return true
                    }
                    "After 1 minute" -> {
                        if (elapsedSinceBg < 60000) return true
                    }
                    "Re-lock on screen off" -> {
                        return true
                    }
                    else -> {
                        if (elapsedSinceBg < 2000) return true
                    }
                }
            }
            
            // If we reach here, the grace period has expired.
            unlockedStates.remove(app.packageName)
            synchronized(packageBackgroundTimes) {
                packageBackgroundTimes.remove(app.packageName)
            }
            return false
        }
    }

    private fun launchAuthOverlay(targetPackageName: String) {
        Log.i(TAG, "Launching Auth Overlay Activity for secure application: $targetPackageName")
        currentlyAuthenticatingPackage = targetPackageName

        val authIntent = Intent(this, AuthenticationActivity::class.java).apply {
            putExtra(AuthenticationActivity.EXTRA_TARGET_PACKAGE, targetPackageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(authIntent)
    }

    /**
     * Standard implementation of queryEvents for PACKAGE_USAGE_STATS permission.
     */
    private fun getForegroundPackageName(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000 // Last 10 seconds matches standard latency buffer
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var lastResumedEvent: UsageEvents.Event? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedEvent = event
            }
        }

        return lastResumedEvent?.packageName
    }
}
