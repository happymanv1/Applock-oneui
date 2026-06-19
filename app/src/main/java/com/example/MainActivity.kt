package com.example

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.AppLockService
import com.example.ui.AppLockViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.SamsungBatteryHelper
import kotlinx.coroutines.delay

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.util.concurrent.Executor

class MainActivity : androidx.fragment.app.FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                AppLockDashboard()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockDashboard(viewModel: AppLockViewModel = viewModel()) {
    val context = LocalContext.current
    val appsList by viewModel.appsList.collectAsStateWithLifecycle()
    val lockedCount by viewModel.lockedCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var isServiceActive by remember { mutableStateOf(false) }

    // Dynamic Permission Checks States
    var hasUsageStats by remember { mutableStateOf(false) }
    var hasOverlay by remember { mutableStateOf(false) }
    var hasNotification by remember { mutableStateOf(false) }

    // Dropdown Dialog state for selecting an app's timer option
    var selectedAppForTimer by remember { mutableStateOf<AppLockViewModel.MergedAppItem?>(null) }

    // Refresh checking function
    fun checkPermissionsAndService() {
        hasUsageStats = checkUsageStatsPermission(context)
        hasOverlay = Settings.canDrawOverlays(context)
        hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Check if our protection foreground service is running
        isServiceActive = checkServiceRunning(context, AppLockService::class.java)
    }

    // Checking on resume
    LaunchedEffect(Unit) {
        while (true) {
            checkPermissionsAndService()
            delay(1500) // check occasionally in background
        }
    }

    // Scroll offset detection for One UI 8.5 Collapsing giant header
    val lazyListState = rememberLazyListState()
    val collapsedProgress by remember {
        derivedStateOf {
            val firstIndex = lazyListState.firstVisibleItemIndex
            val firstOffset = lazyListState.firstVisibleItemScrollOffset
            if (firstIndex > 0) 1f else (firstOffset / 280f).coerceIn(0f, 1f)
        }
    }

    // Animate dimensions for collapsible header
    val headerHeight = animateDpAsState(targetValue = if (collapsedProgress >= 0.9f) 56.dp else 240.dp) // Large viewing area
    val headerTitleSize = animateFloatAsState(targetValue = if (collapsedProgress >= 0.9f) 19f else 36f)
    val headerPaddingTop = animateDpAsState(targetValue = if (collapsedProgress >= 0.9f) 0.dp else 40.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // One UI Collapsible Header Title Block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(headerHeight.value)
                .background(Color.Transparent)
                .padding(horizontal = 24.dp),
            contentAlignment = if (collapsedProgress >= 0.9f) Alignment.Center else Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = headerPaddingTop.value),
                verticalArrangement = if (collapsedProgress >= 0.9f) Arrangement.Center else Arrangement.Bottom,
                horizontalAlignment = if (collapsedProgress >= 0.9f) Alignment.CenterHorizontally else Alignment.Start
            ) {
                Text(
                    text = "App Lock",
                    fontSize = headerTitleSize.value.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.testTag("app_title")
                )

                if (collapsedProgress < 0.9f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isServiceActive) "Protection active • $lockedCount apps secured" else "Protection offline • Grant permissions to active",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Scrollable content wrapper
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = headerHeight.value + 64.dp, // Buffer for header offset
                bottom = 32.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // SECTION 1: Permissions Onboarding Cards
            val needsAnyPermission = !hasUsageStats || !hasOverlay || (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            if (needsAnyPermission) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp), // One UI rounded cards standard
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Alert Symbol",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Action required",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Setup the following system configurations to allow background lock detection:",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Target Permission Items
                            if (!hasUsageStats) {
                                PermissionOnboardRow(
                                    title = "Usage Data Access",
                                    desc = "Allows App Lock to detect which app is starting.",
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            if (!hasUsageStats && !hasOverlay) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            if (!hasOverlay) {
                                PermissionOnboardRow(
                                    title = "Appear on Top",
                                    desc = "Allows launching the fast biometric prompt screen.",
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotification) {
                                Spacer(modifier = Modifier.height(12.dp))
                                PermissionOnboardRow(
                                    title = "Allow Notifications",
                                    desc = "Required to keep the App Lock background service alive.",
                                    onClick = {
                                        androidx.core.app.ActivityCompat.requestPermissions(
                                            context as androidx.fragment.app.FragmentActivity,
                                            arrayOf("android.permission.POST_NOTIFICATIONS"),
                                            101
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 2: General Protection Master Toggle Switch
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(1.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isServiceActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isServiceActive) {
                                    // Stop service
                                    val stopIntent = Intent(context, AppLockService::class.java)
                                    context.stopService(stopIntent)
                                    isServiceActive = false
                                } else {
                                    // Start service
                                    val startIntent = Intent(context, AppLockService::class.java)
                                    ContextCompat.startForegroundService(context, startIntent)
                                    isServiceActive = true
                                }
                            }
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isServiceActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Active Protection",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isServiceActive) "Service is active in background" else "Tap to startup background service protection",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isServiceActive,
                            onCheckedChange = { checked ->
                                val intent = Intent(context, AppLockService::class.java)
                                if (checked) {
                                    ContextCompat.startForegroundService(context, intent)
                                } else {
                                    context.stopService(intent)
                                }
                                isServiceActive = checked
                            },
                            modifier = Modifier.testTag("protection_master_switch")
                        )
                    }
                }
            }

            // Visually rich One UI Core Shield Security Banner Illustration
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(1.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_security_banner),
                        contentDescription = "Core Shield Protection",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }

            // SECTION 3: Search box
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search installed applications", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear text", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_field"),
                    shape = RoundedCornerShape(26.dp), // Pill shaped standard search in One UI 8.5
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }

            // SECTION 4: Roster list header
            item {
                Text(
                    text = "Applications list",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            // Loader State
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    }
                }
            } else if (appsList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "No items",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No apps found",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Check search parameters or install programs.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // SECTION 5: Dynamic App Rows
            items(appsList, key = { it.packageName }) { appItem ->
                AppLockRow(
                    appItem = appItem,
                    onToggleLock = { viewModel.toggleAppLock(appItem) },
                    onTimerClick = { selectedAppForTimer = appItem }
                )
            }

            // SECTION 6: Samsung Device Care Helper Banner Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(0.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(26.dp)
                        ),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Samsung Security Guide (One UI)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Samsung strict background limits can stop the App Lock engine from noticing when you switch applications. Follow these steps to ensure continuous protection:",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Guide steps
                        val steps = listOf(
                            "Tap 'Ignore Battery Rules' below and select 'All apps' -> allow App Lock.",
                            "Tap 'Device Care' below -> 'Battery' -> 'Background usage limits'.",
                            "Add App Lock to 'Never sleeping apps'."
                        )
                        
                        steps.forEachIndexed { index, step ->
                            Row(modifier = Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                                Text("${index + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.width(20.dp))
                                Text(step, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SamsungBatteryHelper.isBatteryOptimizingIgnored(context)) {
                                Button(
                                    onClick = {
                                        try {
                                            val intent = SamsungBatteryHelper.getExemptionIntent(context)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            SamsungBatteryHelper.launchSamsungDeviceCare(context)
                                        }
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Ignore Battery Rules", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Button(
                                onClick = {
                                    SamsungBatteryHelper.launchSamsungDeviceCare(context)
                                },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.height(38.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Device Care Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal dialog overlay for selecting app re-lock timers
    var showCustomTimerInput by remember { mutableStateOf(false) }
    var customTimerString by remember { mutableStateOf("") }

    selectedAppForTimer?.let { app ->
        if (showCustomTimerInput) {
            AlertDialog(
                onDismissRequest = { 
                    showCustomTimerInput = false
                    selectedAppForTimer = null 
                },
                title = { Text("Custom Timer (Minutes)") },
                text = {
                    OutlinedTextField(
                        value = customTimerString,
                        onValueChange = { customTimerString = it },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        label = { Text("Minutes") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val mins = customTimerString.toIntOrNull() ?: 0
                        if (mins > 0) {
                            viewModel.setAppReLockOption(app, "After $mins minutes")
                        }
                        showCustomTimerInput = false
                        selectedAppForTimer = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomTimerInput = false }) { Text("Cancel") }
                },
                shape = RoundedCornerShape(26.dp),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            )
        } else {
            AlertDialog(
                onDismissRequest = { selectedAppForTimer = null },
                title = {
                    Text(
                        text = "Timer for ${app.appName}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Customize when this specific application should trigger authentication locks:",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        val timerOptions = listOf("Immediately", "After 1 minute", "Re-lock on screen off", "Custom...")

                        timerOptions.forEach { option ->
                            val isSelected = app.reLockOption == option || (app.reLockOption.startsWith("After") && option == "Custom..." && app.reLockOption != "After 1 minute")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                                    .clickable {
                                        if (option == "Custom...") {
                                            customTimerString = ""
                                            showCustomTimerInput = true
                                        } else {
                                            viewModel.setAppReLockOption(app, option)
                                            selectedAppForTimer = null
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isSelected && option == "Custom...") app.reLockOption else option,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedAppForTimer = null }) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(26.dp), // Large rounded corners matching One UI 8.5 modals
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
fun PermissionOnboardRow(
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Button(
            onClick = { onClick() },
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppLockRow(
    appItem: AppLockViewModel.MergedAppItem,
    onToggleLock: () -> Unit,
    onTimerClick: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    // Dynamic icon extraction loaded asynchronously safely and efficiently
    var iconBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(appItem.packageName) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val appIconDrawable = pm.getApplicationIcon(appItem.packageName)
                iconBitmap = appIconDrawable.toBitmap().asImageBitmap()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

        Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(0.dp) // Removed heavy shadow for glass look
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                shape = RoundedCornerShape(26.dp)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (appItem.isLocked) MaterialTheme.colorScheme.surface.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.05f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // One UI Squircle App Icon Mask Frame
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp)) // squircle shape ratio
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val currentIcon = iconBitmap
                    if (currentIcon != null) {
                        Image(
                            bitmap = currentIcon,
                            contentDescription = appItem.appName,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Android App placeholder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = appItem.appName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = appItem.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Optional lock badge and timer configuration indicator on active items
                    if (appItem.isLocked) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .clickable { onTimerClick() }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = appItem.reLockOption,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Lock toggle switch
            Switch(
                checked = appItem.isLocked,
                onCheckedChange = { onToggleLock() },
                modifier = Modifier.testTag("app_lock_switch_${appItem.packageName}")
            )
        }
    }
}

/**
 * Validates check usage stats permission allowance.
 */
private fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Dynamic helper verifying if protection Foreground Service is running.
 */
private fun checkServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    activityManager?.let { am ->
        @Suppress("DEPRECATION")
        val runningServices = am.getRunningServices(Integer.MAX_VALUE)
        for (service in runningServices) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
    }
    return false
}
