package com.example.remote.data

import androidx.compose.ui.graphics.Color

data class TvAppShortcut(
  val id: String,
  val name: String,
  val badge: String,
  val appLink: String,
  val accentColor: Color,
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
}
