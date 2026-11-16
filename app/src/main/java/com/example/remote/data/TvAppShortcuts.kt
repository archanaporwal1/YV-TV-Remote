package com.example.remote.data

import androidx.compose.ui.graphics.Color

enum class CustomOttIconStyle(
  val key: String,
  val label: String,
) {
  BADGE("badge", "Monogram"),
  PLAY("play", "Play"),
  TV("tv", "Live TV"),
  MOVIE("movie", "Cinema"),
  SPORTS("sports", "Sports"),
  MUSIC("music", "Music"),
  LIVE("live", "Stream");

  companion object {
    fun fromKey(key: String?): CustomOttIconStyle =
      entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BADGE
  }
}

data class TvAppShortcut(
  val id: String,
  val name: String,
  val badge: String,
  val appLink: String,
  val accentColor: Color,
  val isCustom: Boolean = false,
  val iconStyle: CustomOttIconStyle = CustomOttIconStyle.BADGE,
)

object TvAppShortcuts {
  val all: List<TvAppShortcut> =
    listOf(
      TvAppShortcut(
        id = "youtube",
        name = "YouTube",
        badge = "YT",
        appLink = "https://www.youtube.com",
        accentColor = Color(0xFFFF3B30),
      ),
      TvAppShortcut(
        id = "netflix",
        name = "Netflix",
        badge = "NF",
        appLink = "https://www.netflix.com",
        accentColor = Color(0xFFE50914),
      ),
      TvAppShortcut(
        id = "prime_video",
        name = "Prime Video",
        badge = "PV",
        appLink = "https://app.primevideo.com",
        accentColor = Color(0xFF00A8E1),
      ),
      TvAppShortcut(
        id = "jiocinema",
        name = "JioCinema",
        badge = "JC",
        appLink = "https://www.jiocinema.com",
        accentColor = Color(0xFFD9008D),
      ),
      TvAppShortcut(
        id = "hotstar",
        name = "Disney+ Hotstar",
        badge = "HS",
        appLink = "https://www.hotstar.com",
        accentColor = Color(0xFF1F80E0),
      ),
      TvAppShortcut(
        id = "sonyliv",
        name = "SonyLIV",
        badge = "SL",
        appLink = "https://www.sonyliv.com",
        accentColor = Color(0xFFF5A623),
      ),
      TvAppShortcut(
        id = "zee5",
        name = "ZEE5",
        badge = "Z5",
        appLink = "https://www.zee5.com",
        accentColor = Color(0xFFA259FF),
      ),
      TvAppShortcut(
        id = "sunnxt",
        name = "Sun NXT",
        badge = "SN",
        appLink = "https://www.sunnxt.com",
        accentColor = Color(0xFFFF7A00),
      ),
      TvAppShortcut(
        id = "jiotv",
        name = "JioTV",
        badge = "JT",
        appLink = "https://jiotv.com",
        accentColor = Color(0xFF0A66C2),
      ),
    )

  val defaultEnabledIds: Set<String> = all.map { it.id }.toSet()

  val customColorPalette: List<Color> =
    listOf(
      Color(0xFF1DB954),
      Color(0xFF9146FF),
      Color(0xFFF47521),
      Color(0xFFE5A00D),
      Color(0xFF00B4D8),
      Color(0xFFE11D48),
      Color(0xFF2563EB),
      Color(0xFF10B981),
    )

  val popularAddableCatalog: List<TvAppShortcut> =
    listOf(
      TvAppShortcut(
        id = "apple_tv",
        name = "Apple TV+",
        badge = "ATV",
        appLink = "https://tv.apple.com",
        accentColor = Color(0xFF4B5563),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
      ),
      TvAppShortcut(
        id = "spotify",
        name = "Spotify",
        badge = "SP",
        appLink = "https://open.spotify.com",
        accentColor = Color(0xFF1DB954),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MUSIC,
      ),
      TvAppShortcut(
        id = "twitch",
        name = "Twitch",
        badge = "TW",
        appLink = "https://www.twitch.tv",
        accentColor = Color(0xFF9146FF),
        isCustom = true,
        iconStyle = CustomOttIconStyle.LIVE,
      ),
      TvAppShortcut(
        id = "plex",
        name = "Plex",
        badge = "PX",
        appLink = "https://app.plex.tv",
        accentColor = Color(0xFFE5A00D),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
      ),
      TvAppShortcut(
        id = "crunchyroll",
        name = "Crunchyroll",
        badge = "CR",
        appLink = "https://www.crunchyroll.com",
        accentColor = Color(0xFFF47521),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
      ),
      TvAppShortcut(
        id = "discovery_plus",
        name = "Discovery+",
        badge = "D+",
        appLink = "https://www.discoveryplus.com",
        accentColor = Color(0xFF0077B6),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
      ),
      TvAppShortcut(
        id = "fancode",
        name = "FanCode",
        badge = "FC",
        appLink = "https://www.fancode.com",
        accentColor = Color(0xFFEF4444),
        isCustom = true,
        iconStyle = CustomOttIconStyle.SPORTS,
      ),
      TvAppShortcut(
        id = "mx_player",
        name = "MX Player",
        badge = "MX",
        appLink = "https://www.mxplayer.in",
        accentColor = Color(0xFF2563EB),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
      ),
      TvAppShortcut(
        id = "aha",
        name = "aha",
        badge = "AHA",
        appLink = "https://www.aha.video",
        accentColor = Color(0xFFFF6D00),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
      ),
      TvAppShortcut(
        id = "hoichoi",
        name = "hoichoi",
        badge = "HC",
        appLink = "https://www.hoichoi.tv",
        accentColor = Color(0xFFE11D48),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
      ),
      TvAppShortcut(
        id = "max",
        name = "Max",
        badge = "MAX",
        appLink = "https://play.max.com",
        accentColor = Color(0xFF002BE7),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
      ),
      TvAppShortcut(
        id = "hulu",
        name = "Hulu",
        badge = "HU",
        appLink = "https://www.hulu.com",
        accentColor = Color(0xFF1CE783),
        isCustom = true,
        iconStyle = CustomOttIconStyle.LIVE,
      ),
    )

  private val reverseDnsPrefixes = setOf("com", "org", "net", "tv", "in", "de", "uk", "jp", "io")
  private val webTlds = setOf("com", "org", "net", "tv", "in", "io", "co", "app", "video", "live", "watch", "me")

  fun normalizeAppLink(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(trimmed)) {
      return trimmed
    }
    val packagePattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,4}$")
    if (packagePattern.matches(trimmed)) {
      val segments = trimmed.split('.')
      if (segments.first() in reverseDnsPrefixes && segments.last() !in webTlds) {
        return "market://details?id=$trimmed"
      }
    }
    return "https://$trimmed"
  }

  fun suggestBadge(name: String): String {
    val words =
      name
        .trim()
        .split(Regex("\\s+"))
        .map { it.filter(Char::isLetterOrDigit) }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return "TV"
    return when (words.size) {
      1 -> words[0].take(2).uppercase()
      else -> words.take(3).map { it.first() }.joinToString("").uppercase()
    }
  }
}
