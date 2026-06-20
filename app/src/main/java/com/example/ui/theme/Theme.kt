package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SamsungDarkColorScheme = darkColorScheme(
    primary = Color(0xFF3b82f6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFD4D4D8),
    background = Color(0xFF000000), // Pure black for OLED
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E), // Slightly elevated card color
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA1A1AA)
)

private val SamsungLightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFFF4F4F5),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFE4E4E7),
    onSecondaryContainer = Color(0xFF27272A),
    background = Color(0xFFF9FAFB),
    onBackground = Color.Black,
    surface = Color.White, // Pure white for cards in light mode
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF52525B)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Enable Material You dynamic colors
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> SamsungDarkColorScheme
      else -> SamsungLightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
