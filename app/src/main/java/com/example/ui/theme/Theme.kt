package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val YvTvDarkColorScheme =
  darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OledBlack,
    primaryContainer = CyanDeepContainer,
    onPrimaryContainer = CyanSoftTint,
    secondary = TealAccent,
    onSecondary = OledBlack,
    secondaryContainer = RaisedPod,
    onSecondaryContainer = CoolWhite,
    tertiary = EmeraldLive,
    onTertiary = OledBlack,
    background = OledBlack,
    onBackground = CoolWhite,
    surface = MidnightSlate,
    onSurface = CoolWhite,
    surfaceVariant = RaisedPod,
    onSurfaceVariant = MutedSteel,
    surfaceContainer = MidnightSlate,
    surfaceContainerHigh = RaisedPod,
    surfaceContainerHighest = TactileKeySurface,
    outline = SubtleOutline,
    outlineVariant = FaintOutline,
    error = CrimsonPower,
    onError = CoolWhite,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonPower,
  )

@Composable
fun YvTvRemoteTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = YvTvDarkColorScheme,
    typography = Typography,
    content = content,
  )
}
