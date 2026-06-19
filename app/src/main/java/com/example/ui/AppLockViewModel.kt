package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppLockDatabase
import com.example.data.AppLockRepository
import com.example.data.LockedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppLockViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppLockRepository
    private val packageManager: PackageManager = application.packageManager

    // Holds raw scanned launchable apps from the device package manager
    private val _scannedApps = MutableStateFlow<List<ScannedAppInfo>>(emptyList())

    // Tracks user search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Loading indicator state
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        val database = AppLockDatabase.getDatabase(application)
        repository = AppLockRepository(database.appLockDao())

        // Load applications on initialization on a background dispatcher
        loadInstalledApplications()
    }

    /**
     * Holds lightweight references to scanned system applications.
     */
    data class ScannedAppInfo(
        val packageName: String,
        val label: String
    )

    /**
     * Merged model containing both the local user preferences (from Room) and PM resources.
     */
    data class MergedAppItem(
        val packageName: String,
        val appName: String,
        val isLocked: Boolean,
        val reLockOption: String
    )

    // Master stream: Combines Room database flows with scanned launcher applications
    val appsList: StateFlow<List<MergedAppItem>> = combine(
        _scannedApps,
        repository.allAppsFlow,
        _searchQuery
    ) { scannedList, dbList, query ->
        val dbMap = dbList.associateBy { it.packageName }

        // Core merge logic: Align scan info, fallback to Room properties if scanned app has settings
        val mergedList = scannedList.map { scanned ->
            val dbApp = dbMap[scanned.packageName]
            MergedAppItem(
                packageName = scanned.packageName,
                appName = scanned.label,
                isLocked = dbApp?.isLocked ?: false,
                reLockOption = dbApp?.reLockOption ?: "Immediately"
            )
        }

        // Apply real-time search filtering
        val filtered = if (query.isBlank()) {
            mergedList
        } else {
            mergedList.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }

        filtered.sortedWith(compareBy<MergedAppItem> { !it.isLocked }.thenBy { it.appName.lowercase() })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Derived StateFlow holding the statistics for locked apps
    val lockedCount: StateFlow<Int> = appsList
        .map { list -> list.count { it.isLocked } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Asynchronously scans device applications using launcher categories to build a realistic app roster.
     */
    private fun loadInstalledApplications() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }

                    val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    } else {
                        @Suppress("DEPRECATION")
                        PackageManager.MATCH_ALL
                    }

                    val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        packageManager.queryIntentActivities(mainIntent, flags as PackageManager.ResolveInfoFlags)
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.queryIntentActivities(mainIntent, flags as Int)
                    }
                    resolveInfos.mapNotNull { info ->
                        val pkg = info.activityInfo.packageName
                        // Avoid locking ourselves
                        if (pkg == getApplication<Application>().packageName) return@mapNotNull null

                        val label = info.loadLabel(packageManager).toString()
                        ScannedAppInfo(packageName = pkg, label = label)
                    }.distinctBy { it.packageName }
                     .sortedBy { it.label }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _scannedApps.value = apps
            _isLoading.value = false
        }
    }

    /**
     * Toggles an app's locked status and commits/updates its entry in Room.
     */
    fun toggleAppLock(app: MergedAppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val newLockState = !app.isLocked
            repository.updateAppLockState(
                packageName = app.packageName,
                appName = app.appName,
                isLocked = newLockState,
                reLockOption = app.reLockOption
            )
        }
    }

    /**
     * Changes an app's re-lock timer and persists the modifications in Room.
     */
    fun setAppReLockOption(app: MergedAppItem, option: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAppLockState(
                packageName = app.packageName,
                appName = app.appName,
                isLocked = app.isLocked,
                reLockOption = option
            )
        }
    }
}
