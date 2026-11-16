package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(
  val storageKey: String,
  val displayName: String,
) {
  SYSTEM("system", "System"),
  DARK("dark", "Dark"),
  LIGHT("light", "Light");

  companion object {
    fun fromKey(key: String?): AppThemeMode =
      entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: SYSTEM
  }
}

enum class RemoteAccentTheme(
  val storageKey: String,
  val displayName: String,
  val swatchColor: Color,
  val containerColor: Color,
  val softTintColor: Color,
  val secondaryColor: Color,
  val lightPrimaryColor: Color,
  val lightContainerColor: Color,
  val lightOnContainerColor: Color,
) {
  ELECTRIC_CYAN(
    storageKey = "cyan",
    displayName = "Cyan",
    swatchColor = ElectricCyan,
    containerColor = CyanDeepContainer,
    softTintColor = CyanSoftTint,
    secondaryColor = TealAccent,
    lightPrimaryColor = LightCyanPrimary,
    lightContainerColor = LightCyanContainer,
    lightOnContainerColor = LightCyanOnContainer,
  ),
  EMERALD_PULSE(
    storageKey = "emerald",
    displayName = "Emerald",
    swatchColor = EmeraldAccent,
    containerColor = EmeraldDeepContainer,
    softTintColor = EmeraldSoftTint,
    secondaryColor = EmeraldAccent,
    lightPrimaryColor = LightEmeraldPrimary,
    lightContainerColor = LightEmeraldContainer,
    lightOnContainerColor = LightEmeraldOnContainer,
  ),
  ROYAL_AMBER(
    storageKey = "amber",
    displayName = "Amber",
    swatchColor = AmberAccent,
    containerColor = AmberDeepContainer,
    softTintColor = AmberSoftTint,
    secondaryColor = AmberAccent,
    lightPrimaryColor = LightAmberPrimary,
    lightContainerColor = LightAmberContainer,
    lightOnContainerColor = LightAmberOnContainer,
  ),
  VIOLET_NEON(
    storageKey = "violet",
    displayName = "Violet",
    swatchColor = VioletAccent,
    containerColor = VioletDeepContainer,
    softTintColor = VioletSoftTint,
    secondaryColor = VioletAccent,
    lightPrimaryColor = LightVioletPrimary,
    lightContainerColor = LightVioletContainer,
    lightOnContainerColor = LightVioletOnContainer,
  ),
  CORAL_RED(
    storageKey = "coral",
    displayName = "Coral",
    swatchColor = CoralAccent,
    containerColor = CoralDeepContainer,
    softTintColor = CoralSoftTint,
    secondaryColor = CoralAccent,
    lightPrimaryColor = LightCoralPrimary,
    lightContainerColor = LightCoralContainer,
    lightOnContainerColor = LightCoralOnContainer,
  );

  companion object {
    fun fromKey(key: String?): RemoteAccentTheme =
      entries.firstOrNull { it.storageKey == key } ?: ELECTRIC_CYAN
  }
}

private fun buildDarkColorScheme(accent: RemoteAccentTheme) =
  darkColorScheme(
    primary = accent.swatchColor,
    onPrimary = OledBlack,
    primaryContainer = accent.containerColor,
    onPrimaryContainer = accent.softTintColor,
    secondary = accent.secondaryColor,
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

private fun buildLightColorScheme(accent: RemoteAccentTheme) =
  lightColorScheme(
    primary = accent.lightPrimaryColor,
    onPrimary = LightSurface,
    primaryContainer = accent.lightContainerColor,
    onPrimaryContainer = accent.lightOnContainerColor,
    secondary = accent.lightPrimaryColor,
    onSecondary = LightSurface,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightEmeraldPrimary,
    onTertiary = LightSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightCrimsonPower,
    onError = LightSurface,
    errorContainer = LightCrimsonContainer,
    onErrorContainer = LightCrimsonPower,
  )

@Composable
fun isThemeDark(themeMode: AppThemeMode): Boolean =
  when (themeMode) {
    AppThemeMode.DARK -> true
    AppThemeMode.LIGHT -> false
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
  }

@Composable
fun YvTvRemoteTheme(
  themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  accentTheme: RemoteAccentTheme = RemoteAccentTheme.ELECTRIC_CYAN,
  content: @Composable () -> Unit,
) {
  val useDark = isThemeDark(themeMode)
  MaterialTheme(
    colorScheme = if (useDark) buildDarkColorScheme(accentTheme) else buildLightColorScheme(accentTheme),
    typography = Typography,
    content = content,
  )
}
