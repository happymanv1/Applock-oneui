package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import com.example.R
import com.example.service.AppLockService
import com.example.ui.theme.MyApplicationTheme
import java.util.concurrent.Executor

class AuthenticationActivity : FragmentActivity() {

    companion object {
        const val TAG = "AuthenticationActivity"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_TOGGLE_PAUSE = "action_toggle_pause"
    }

    private var targetPackage: String? = null
    private var actionState: String? = null
    private var appLabel: String = "Secure Application"
    private var appIcon: Drawable? = null
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fast animation
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, com.example.R.anim.fast_fade_in, com.example.R.anim.fast_fade_out)
        } else {
            overridePendingTransition(com.example.R.anim.fast_fade_in, com.example.R.anim.fast_fade_out)
        }
        
        enableEdgeToEdge()

        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        actionState = intent.getStringExtra(EXTRA_ACTION)
        
        if (targetPackage == null && actionState != ACTION_TOGGLE_PAUSE) {
            Log.e(TAG, "Authentication launched without valid target. Exiting.")
            redirectHome()
            finish()
            return
        }

        if (actionState == ACTION_TOGGLE_PAUSE) {
            appLabel = "AppLock Settings"
        } else {
            loadAppMetadata()
        }
        
        setupBiometricPrompt()

        setContent {
            MyApplicationTheme {
                AuthenticationUi()
            }
        }

        // Instantly trigger biometric prompt on launch
        triggerAuthentication()
    }

    private fun loadAppMetadata() {
        targetPackage?.let { pkg ->
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                appLabel = pm.getApplicationLabel(appInfo).toString()
                appIcon = pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load info for package: $pkg, defaulting.")
                appLabel = "Locked App"
            }
        }
    }

    private fun setupBiometricPrompt() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.e(TAG, "Biometric Prompt Error ($errorCode): $errString")
                // Standard cancellations where the user chooses to exit, go home
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) {
                    onAuthFailure()
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.i(TAG, "Success biometric authentication!")
                onAuthSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Biometric authentication failed (Incorrect pattern/pin or mismatched finger)")
                // Note: The UI stays up so the user can try again automatically up to system limits,
                // no explicit exit is performed to allow retries.
            }
        })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_dialog_title))
            .setSubtitle("$appLabel is secured by App Lock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

    private fun triggerAuthentication() {
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed triggering biometric prompt: ${e.message}")
        }
    }

    private fun onAuthSuccess() {
        if (actionState == ACTION_TOGGLE_PAUSE) {
            com.example.service.AppLockTileService.isPaused = !com.example.service.AppLockTileService.isPaused
            
            // Trigger Widget Update
            val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
            val componentName = android.content.ComponentName(this, com.example.service.AppLockWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isNotEmpty()) {
                val updateIntent = Intent(this, com.example.service.AppLockWidgetProvider::class.java).apply {
                    action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                sendBroadcast(updateIntent)
            }
        } else {
            targetPackage?.let { pkg ->
                // Update in-memory service records so the user isn't prompted repeatedly during this active state
                AppLockService.markAsUnlocked(pkg)
            }
        }
        AppLockService.currentlyAuthenticatingPackage = null
        finish()
    }

    private fun onAuthFailure() {
        AppLockService.currentlyAuthenticatingPackage = null
        if (actionState != ACTION_TOGGLE_PAUSE) {
            redirectHome()
        }
        finish()
    }

    private fun redirectHome() {
        Log.d(TAG, "Authentication failed or cancelled: Redirecting to Launcher Home.")
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, com.example.R.anim.fast_fade_in, com.example.R.anim.fast_fade_out)
        } else {
            overridePendingTransition(com.example.R.anim.fast_fade_in, com.example.R.anim.fast_fade_out)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure authentication lock references are cleared if background killed
        if (AppLockService.currentlyAuthenticatingPackage == targetPackage) {
            AppLockService.currentlyAuthenticatingPackage = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intercepting hardware back gestures:
        // By calling onAuthFailure instead, we prevent the user from bypassing the screen to the host application.
        onAuthFailure()
    }

    @Composable
    fun AuthenticationUi() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background), // COMPLETELY OPAQUE ONE UI STYLE BACKGROUND
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(26.dp), // One UI 8.5 squircle dialog styling
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // App Icon Wrapper
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(20.dp)) // One UI Squircle Icon ratio
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon!!.toBitmap().asImageBitmap(),
                                contentDescription = "$appLabel Icon",
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked Emblem",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = appLabel,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "This application is secured",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Buttons block
                    Button(
                        onClick = { triggerAuthentication() },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Unlock action icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unlock Application",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = { onAuthFailure() },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Cancel and Return Home",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.textButtonColor()
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ColorScheme.textButtonColor(): Color {
        return MaterialTheme.colorScheme.primary
    }
}
