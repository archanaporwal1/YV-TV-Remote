package com.example.remote.data

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

data class DetectedTvApp(
  val tvKey: String,
  val packageName: String,
  val label: String,
  val lastSeenAt: Long = System.currentTimeMillis(),
)

/**
 * Zero-setup TV app discovery filter & shortcut builder.
 * Converts apps reported by the Android TV Remote v2 protocol (`RemoteAppInfo`)
 * or confirmed via native app launches into ready-to-launch shortcuts with official brand icons.
 */
object TvDetectedAppSync {

  private val ignoredExactPackages: Set<String> =
    setOf(
      "android",
      "com.android.systemui",
      "com.android.settings",
      "com.android.tv.settings",
      "com.android.vending",
      "com.android.packageinstaller",
      "com.google.android.packageinstaller",
      "com.google.android.tvlauncher",
      "com.google.android.apps.tv.launcherx",
      "com.google.android.leanbacklauncher",
      "com.google.android.tvrecommendations",
      "com.google.android.katniss",
      "com.google.android.gms",
      "com.google.android.gsf",
      "com.google.android.inputmethod.latin",
      "com.google.android.marvin.talkback",
      "com.google.android.tts",
      "com.google.android.backdrop",
      "com.google.android.tungsten.setupwraith",
      "com.google.android.tv.remote.service",
      "com.amazon.tv.launcher",
      "com.xiaomi.mitv.tvhome",
      "com.miui.tv.analytics",
      "com.sony.dtv.homelauncher",
      "com.tcl.launcher",
    )

  private val ignoredPackageSubstrings: List<String> =
    listOf(
      ".launcher",
      "tvlauncher",
      "launcherx",
      "inputmethod",
      ".keyboard",
      ".ime",
      "systemui",
      "setupwizard",
      "setupwraith",
      "packageinstaller",
      "permissioncontroller",
      "providers.",
      "screensaver",
      "dreams.",
      "remote.service",
      ".settings",
    )

  fun isSyncableUserApp(packageName: String): Boolean {
    val pkg = packageName.trim().lowercase()
    if (pkg.isEmpty() || !pkg.contains('.')) return false

    // Always allow any known OTT brand package (e.g., YouTube TV, JioHotstar, Airtel Xstream)
    if (OttBrandResolver.resolveBrandKey(packageNames = listOf(pkg)) != null) {
      return true
    }

    if (pkg in ignoredExactPackages) return false
    if (ignoredPackageSubstrings.any { pkg.contains(it) }) return false
    return true
  }

  fun toShortcut(packageName: String, label: String = ""): TvAppShortcut {
    val cleanPkg = packageName.trim()
    val cleanLabel = label.trim()

    // 1. Direct package match against existing defaults or catalog presets
    val existingCatalog = TvAppShortcuts.all + TvAppShortcuts.popularAddableCatalog
    existingCatalog.firstOrNull { preset ->
      preset.packageNames.any { it.equals(cleanPkg, ignoreCase = true) }
    }?.let { return it }

    // 2. Canonical OTT brand match (e.g., variant TV package or known brand label)
    val resolvedBrand =
      OttBrandResolver.resolveBrandKey(
        packageNames = listOf(cleanPkg),
        name = cleanLabel,
      )
    if (resolvedBrand != null) {
      existingCatalog.firstOrNull { it.id == resolvedBrand }?.let { preset ->
        return preset.copy(
          packageNames = (listOf(cleanPkg) + preset.packageNames).distinct(),
        )
      }
    }

    // 3. Third-party installed TV app -> generate clean launchable shortcut
    val displayName = cleanLabel.ifEmpty { prettifyPackageName(cleanPkg) }
    val safeId =
      "tvpkg_" +
        cleanPkg
          .lowercase()
          .replace(Regex("[^a-z0-9]+"), "_")
          .trim('_')
          .take(40)
    val palette = TvAppShortcuts.customColorPalette
    val accent: Color = palette[abs(cleanPkg.hashCode()) % palette.size]

    return TvAppShortcut(
      id = safeId,
      name = displayName,
      badge = TvAppShortcuts.suggestBadge(displayName),
      appLink = "market://details?id=$cleanPkg",
      accentColor = accent,
      isCustom = true,
      iconStyle = CustomOttIconStyle.BADGE,
      packageNames = listOf(cleanPkg),
    )
  }

  internal fun prettifyPackageName(packageName: String): String {
    val ignoredTokens = setOf("com", "org", "net", "in", "tv", "android", "app", "apps", "video", "media", "stb", "client", "leanback")
    val segments = packageName.lowercase().split('.').filter { it.isNotBlank() }
    val meaningful =
      segments.lastOrNull { it !in ignoredTokens }
        ?: segments.lastOrNull()
        ?: "TV App"
    return meaningful
      .split('_', '-')
      .filter { it.isNotBlank() }
      .joinToString(" ") { token ->
        token.replaceFirstChar { ch -> ch.uppercaseChar() }
      }
      .ifBlank { "TV App" }
  }
}
