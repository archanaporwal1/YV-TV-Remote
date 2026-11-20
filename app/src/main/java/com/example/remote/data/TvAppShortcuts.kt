package com.example.remote.data

import android.net.Uri
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
  val packageNames: List<String> = emptyList(),
  val nativeSchemes: List<String> = emptyList(),
)

data class OttLaunchPlan(
  val nativeCandidates: List<String>,
  val browserFallback: String?,
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
        packageNames =
          listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube",
          ),
        nativeSchemes =
          listOf(
            "vnd.youtube.launch://www.youtube.com",
            "vnd.youtube://www.youtube.com",
            "vnd.youtube://",
          ),
      ),
      TvAppShortcut(
        id = "netflix",
        name = "Netflix",
        badge = "NF",
        appLink = "https://www.netflix.com",
        accentColor = Color(0xFFE50914),
        packageNames =
          listOf(
            "com.netflix.ninja",
            "com.netflix.mediaclient",
          ),
        nativeSchemes = listOf("nflx://www.netflix.com/", "netflix://"),
      ),
      TvAppShortcut(
        id = "prime_video",
        name = "Prime Video",
        badge = "PV",
        appLink = "https://app.primevideo.com",
        accentColor = Color(0xFF0578FF),
        packageNames =
          listOf(
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.avod.thirdpartyclient",
          ),
        nativeSchemes = listOf("primevideo://", "aiv://"),
      ),
      TvAppShortcut(
        id = "jiocinema",
        name = "JioCinema",
        badge = "JC",
        appLink = "https://www.jiocinema.com",
        accentColor = Color(0xFFD9008D),
        packageNames =
          listOf(
            "com.jio.media.stb.ondemand",
            "com.jio.media.ondemand",
          ),
        nativeSchemes = listOf("jiocinema://home", "jiocinema://"),
      ),
      TvAppShortcut(
        id = "hotstar",
        name = "JioHotstar",
        badge = "JH",
        appLink = "https://www.jiohotstar.com",
        accentColor = Color(0xFFE1007A),
        packageNames =
          listOf(
            "in.startv.hotstar",
            "in.startv.hotstartvonly",
            "in.startv.hotstar.dplus.tv",
            "com.hotstar.disney",
          ),
        nativeSchemes = listOf("hotstar://home", "hotstar://"),
      ),
      TvAppShortcut(
        id = "sonyliv",
        name = "SonyLIV",
        badge = "SL",
        appLink = "https://www.sonyliv.com",
        accentColor = Color(0xFFF5A623),
        packageNames = listOf("com.sonyliv"),
        nativeSchemes = listOf("sonyliv://home", "sonyliv://"),
      ),
      TvAppShortcut(
        id = "zee5",
        name = "ZEE5",
        badge = "Z5",
        appLink = "https://www.zee5.com",
        accentColor = Color(0xFFA259FF),
        packageNames = listOf("com.graymatrix.did"),
        nativeSchemes = listOf("zee5://home", "zee5://"),
      ),
      TvAppShortcut(
        id = "sunnxt",
        name = "Sun NXT",
        badge = "SN",
        appLink = "https://www.sunnxt.com",
        accentColor = Color(0xFFFF5700),
        packageNames = listOf("com.suntv.sunnxt"),
        nativeSchemes = listOf("sunnxt://home", "sunnxt://"),
      ),
      TvAppShortcut(
        id = "jiotv",
        name = "JioTV",
        badge = "JT",
        appLink = "https://jiotv.com",
        accentColor = Color(0xFFE60023),
        packageNames =
          listOf(
            "com.jio.media.jiotvplus",
            "com.jio.jioplay.tv",
          ),
        nativeSchemes = listOf("jiotv://"),
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
      // Popular Indian OTT Platforms
      TvAppShortcut(
        id = "airtel_xstream",
        name = "Airtel Xstream",
        badge = "AX",
        appLink = "https://www.airtelxstream.in",
        accentColor = Color(0xFFE40000),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "tv.accedo.airtel.wynk",
            "com.airtel.tv",
          ),
        nativeSchemes = listOf("airtelxstream://"),
      ),
      TvAppShortcut(
        id = "tata_play_binge",
        name = "Tata Play Binge",
        badge = "TPB",
        appLink = "https://www.tataplaybinge.com",
        accentColor = Color(0xFFD91E7A),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "com.tatasky.binge",
            "com.tataplay.binge",
          ),
        nativeSchemes = listOf("tataplaybinge://"),
      ),
      TvAppShortcut(
        id = "mx_player",
        name = "MX Player",
        badge = "MX",
        appLink = "https://www.mxplayer.in",
        accentColor = Color(0xFF0066FF),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
        packageNames =
          listOf(
            "com.mxtech.videoplayer.television",
            "com.mxtech.videoplayer.ad",
          ),
        nativeSchemes = listOf("mxplayer://"),
      ),
      TvAppShortcut(
        id = "aha",
        name = "aha",
        badge = "AHA",
        appLink = "https://www.aha.video",
        accentColor = Color(0xFFFF6D00),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "ahaflix.tv",
            "com.aha.video.telugu",
          ),
        nativeSchemes = listOf("aha://"),
      ),
      TvAppShortcut(
        id = "hoichoi",
        name = "hoichoi",
        badge = "HC",
        appLink = "https://www.hoichoi.tv",
        accentColor = Color(0xFFE50914),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "com.viewlift.hoichoi",
            "com.hoichoi.tv.android",
          ),
        nativeSchemes = listOf("hoichoi://"),
      ),
      TvAppShortcut(
        id = "fancode",
        name = "FanCode",
        badge = "FC",
        appLink = "https://www.fancode.com",
        accentColor = Color(0xFFFF5000),
        isCustom = true,
        iconStyle = CustomOttIconStyle.SPORTS,
        packageNames =
          listOf(
            "com.fancode.tv",
            "com.dream11sportsguru",
          ),
        nativeSchemes = listOf("fancode://"),
      ),
      TvAppShortcut(
        id = "lionsgate_play",
        name = "Lionsgate Play",
        badge = "LP",
        appLink = "https://lionsgateplay.com",
        accentColor = Color(0xFFF5B82E),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "com.lionsgateplay.videoapp",
            "com.lionsgateplay.tv",
          ),
        nativeSchemes = listOf("lionsgateplay://"),
      ),
      TvAppShortcut(
        id = "shemaroome",
        name = "ShemarooMe",
        badge = "SM",
        appLink = "https://www.shemaroome.com",
        accentColor = Color(0xFFFF4136),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "com.shemaroo.shemaroome",
            "com.shemaroo.shemaroometv",
          ),
        nativeSchemes = listOf("shemaroome://"),
      ),
      TvAppShortcut(
        id = "eros_now",
        name = "Eros Now",
        badge = "EN",
        appLink = "https://erosnow.com",
        accentColor = Color(0xFF00A88F),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "com.erosnow",
            "com.erosnow.tv",
          ),
        nativeSchemes = listOf("erosnow://"),
      ),
      TvAppShortcut(
        id = "manoramamax",
        name = "ManoramaMAX",
        badge = "MM",
        appLink = "https://www.manoramamax.com",
        accentColor = Color(0xFFC6182F),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "com.mmtv.manoramamax.tv",
            "com.mmtv.manoramamax",
          ),
        nativeSchemes = listOf("manoramamax://"),
      ),
      TvAppShortcut(
        id = "etv_win",
        name = "ETV Win",
        badge = "ETV",
        appLink = "https://www.etvwin.com",
        accentColor = Color(0xFFFFD000),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "com.etvwin.tv",
            "com.etvwin.android",
          ),
        nativeSchemes = listOf("etvwin://"),
      ),
      TvAppShortcut(
        id = "chaupal",
        name = "Chaupal",
        badge = "CH",
        appLink = "https://chaupal.tv",
        accentColor = Color(0xFFFFB800),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "com.chaupal.tv",
            "com.bosctech.chaupal",
          ),
        nativeSchemes = listOf("chaupal://"),
      ),
      TvAppShortcut(
        id = "stage",
        name = "STAGE",
        badge = "STG",
        appLink = "https://www.stage.in",
        accentColor = Color(0xFFE11D48),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames =
          listOf(
            "in.stage.tv",
            "in.stage",
          ),
        nativeSchemes = listOf("stage://"),
      ),
      TvAppShortcut(
        id = "jiosaavn",
        name = "JioSaavn",
        badge = "JS",
        appLink = "https://www.jiosaavn.com",
        accentColor = Color(0xFF2BC5B4),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MUSIC,
        packageNames =
          listOf(
            "com.jio.media.jiobeats",
            "com.jio.media.jiobeats.tv",
          ),
        nativeSchemes = listOf("jiosaavn://"),
      ),
      // Global Streaming Platforms
      TvAppShortcut(
        id = "apple_tv",
        name = "Apple TV+",
        badge = "ATV",
        appLink = "https://tv.apple.com",
        accentColor = Color(0xFF4B5563),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "com.apple.atve.androidtv.appletv",
            "com.apple.atve.sony.appletv",
          ),
        nativeSchemes = listOf("appletv://"),
      ),
      TvAppShortcut(
        id = "spotify",
        name = "Spotify",
        badge = "SP",
        appLink = "https://open.spotify.com",
        accentColor = Color(0xFF1DB954),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MUSIC,
        packageNames =
          listOf(
            "com.spotify.tv.android",
            "com.spotify.music",
          ),
        nativeSchemes = listOf("spotify://"),
      ),
      TvAppShortcut(
        id = "twitch",
        name = "Twitch",
        badge = "TW",
        appLink = "https://www.twitch.tv",
        accentColor = Color(0xFF9146FF),
        isCustom = true,
        iconStyle = CustomOttIconStyle.LIVE,
        packageNames = listOf("tv.twitch.android.app"),
        nativeSchemes = listOf("twitch://home"),
      ),
      TvAppShortcut(
        id = "plex",
        name = "Plex",
        badge = "PX",
        appLink = "https://app.plex.tv",
        accentColor = Color(0xFFE5A00D),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
        packageNames = listOf("com.plexapp.android"),
        nativeSchemes = listOf("plex://"),
      ),
      TvAppShortcut(
        id = "crunchyroll",
        name = "Crunchyroll",
        badge = "CR",
        appLink = "https://www.crunchyroll.com",
        accentColor = Color(0xFFF47521),
        isCustom = true,
        iconStyle = CustomOttIconStyle.PLAY,
        packageNames = listOf("com.crunchyroll.crunchyroid"),
        nativeSchemes = listOf("crunchyroll://"),
      ),
      TvAppShortcut(
        id = "discovery_plus",
        name = "Discovery+",
        badge = "D+",
        appLink = "https://www.discoveryplus.com",
        accentColor = Color(0xFF0077B6),
        isCustom = true,
        iconStyle = CustomOttIconStyle.TV,
        packageNames =
          listOf(
            "com.discovery.discoveryplus.androidtv",
            "com.discovery.discoveryplus.in",
          ),
        nativeSchemes = listOf("discoveryplus://"),
      ),
      TvAppShortcut(
        id = "max",
        name = "Max",
        badge = "MAX",
        appLink = "https://play.max.com",
        accentColor = Color(0xFF002BE7),
        isCustom = true,
        iconStyle = CustomOttIconStyle.MOVIE,
        packageNames = listOf("com.wbd.stream"),
        nativeSchemes = listOf("max://"),
      ),
      TvAppShortcut(
        id = "hulu",
        name = "Hulu",
        badge = "HU",
        appLink = "https://www.hulu.com",
        accentColor = Color(0xFF1CE783),
        isCustom = true,
        iconStyle = CustomOttIconStyle.LIVE,
        packageNames =
          listOf(
            "com.hulu.livingroomplus",
            "com.hulu.plus",
          ),
        nativeSchemes = listOf("hulu://"),
      ),
    )

  private val domainToKnownPackages: Map<String, Pair<List<String>, List<String>>> =
    buildMap {
      (all + popularAddableCatalog).forEach { shortcut ->
        val host = extractHost(shortcut.appLink)
        if (host.isNotEmpty()) {
          put(host, shortcut.packageNames to shortcut.nativeSchemes)
        }
      }
      put("tubitv.com", listOf("com.tubitv") to listOf("tubitv://"))
      put("pluto.tv", listOf("tv.pluto.android") to listOf("plutotv://"))
      put("vimeo.com", listOf("com.vimeo.android.videoapp") to listOf("vimeo://"))
      put("dailymotion.com", listOf("com.dailymotion.dailymotion") to listOf("dailymotion://"))
      put("viki.com", listOf("com.viki.android") to listOf("viki://"))
      put("altbalaji.com", listOf("com.balaji.alt") to listOf("altbalaji://"))
      put("kodi.tv", listOf("org.xbmc.kodi") to listOf("kodi://"))
      put("tataskybinge.com", listOf("com.tatasky.binge", "com.tataplay.binge") to listOf("tataplaybinge://"))
      put("discoveryplus.in", listOf("com.discovery.discoveryplus.in", "com.discovery.discoveryplus.androidtv") to listOf("discoveryplus://"))
      put(
        "hotstar.com",
        listOf("in.startv.hotstar", "in.startv.hotstartvonly", "in.startv.hotstar.dplus.tv", "com.hotstar.disney") to
          listOf("hotstar://home", "hotstar://"),
      )
      put(
        "jiohotstar.com",
        listOf("in.startv.hotstar", "in.startv.hotstartvonly", "in.startv.hotstar.dplus.tv", "com.hotstar.disney") to
          listOf("hotstar://home", "hotstar://"),
      )
    }

  private val reverseDnsPrefixes = setOf("com", "org", "net", "tv", "in", "de", "uk", "jp", "io")
  private val webTlds = setOf("com", "org", "net", "tv", "in", "io", "co", "app", "video", "live", "watch", "me")

  fun findMetadataPreset(id: String): TvAppShortcut? =
    all.firstOrNull { it.id == id } ?: popularAddableCatalog.firstOrNull { it.id == id }

  fun isPackageIdentifier(input: String): Boolean {
    val trimmed = input.trim()
    val packagePattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,4}$")
    if (!packagePattern.matches(trimmed)) return false
    val segments = trimmed.split('.')
    return segments.first() in reverseDnsPrefixes && segments.last() !in webTlds
  }

  fun normalizeAppLink(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(trimmed)) {
      return trimmed
    }
    if (isPackageIdentifier(trimmed)) {
      return "market://details?id=$trimmed"
    }
    return "https://$trimmed"
  }

  fun buildLaunchPlan(shortcut: TvAppShortcut): OttLaunchPlan {
    val presetMeta = findMetadataPreset(shortcut.id)
    val link = shortcut.appLink.trim()

    val uri = runCatching { Uri.parse(link) }.getOrNull()
    val scheme = uri?.scheme?.lowercase().orEmpty()
    val marketPackage =
      if (scheme == "market") uri?.getQueryParameter("id")?.trim().orEmpty()
      else ""

    val host = extractHost(link)
    val domainMatch =
      domainToKnownPackages.entries.firstOrNull { (knownHost, _) ->
        host == knownHost || host.endsWith(".$knownHost")
      }?.value

    val resolvedPackages =
      buildList {
        addAll(shortcut.packageNames)
        if (presetMeta != null) addAll(presetMeta.packageNames)
        if (marketPackage.isNotEmpty()) add(marketPackage)
        if (domainMatch != null) addAll(domainMatch.first)
      }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val resolvedSchemes =
      buildList {
        addAll(shortcut.nativeSchemes)
        if (presetMeta != null) addAll(presetMeta.nativeSchemes)
        if (domainMatch != null) addAll(domainMatch.second)
      }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val isWebFallback = scheme == "http" || scheme == "https"
    val hostAndPath =
      if (isWebFallback && host.isNotEmpty()) {
        val rawHost = uri?.host.orEmpty()
        val path = uri?.encodedPath.orEmpty()
        val query = uri?.encodedQuery?.let { "?$it" }.orEmpty()
        "$rawHost$path$query"
      } else {
        ""
      }

    // Native-first candidates (never hit TV browsers):
    // 1. Verified app-only custom URI schemes (highest compatibility with Android TV ACTION_VIEW)
    // 2. Direct Google Play package launcher URI (market://launch?id=<pkg>) so installed apps
    //    (e.g., Netflix, Prime Video, Hotstar, SonyLIV, ZEE5) open natively without raw intent: parsing errors
    // 3. Package-scoped Leanback/View/Launcher Intent URIs as secondary native fallbacks
    // 4. Explicit non-http user link if provided
    val nativeCandidates =
      buildList {
        addAll(resolvedSchemes)
        resolvedPackages.forEach { pkg ->
          add("market://launch?id=$pkg")
        }
        resolvedPackages.forEach { pkg ->
          add(
            "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;package=$pkg;end"
          )
        }
        if (isWebFallback && hostAndPath.isNotEmpty()) {
          resolvedPackages.forEach { pkg ->
            add(
              "intent://$hostAndPath#Intent;scheme=$scheme;action=android.intent.action.VIEW;package=$pkg;end"
            )
          }
        }
        resolvedPackages.forEach { pkg ->
          add(
            "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=$pkg;end"
          )
        }
        if (!isWebFallback && scheme != "market" && link.isNotEmpty()) {
          add(link)
        }
      }.distinct()

    val browserFallback =
      when {
        isWebFallback && link.isNotEmpty() -> link
        marketPackage.isNotEmpty() -> "https://play.google.com/store/apps/details?id=$marketPackage"
        scheme == "market" && link.isNotEmpty() -> link
        else -> null
      }

    return OttLaunchPlan(
      nativeCandidates = nativeCandidates,
      browserFallback = browserFallback,
    )
  }

  private fun extractHost(url: String): String {
    val parsed = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return ""
    return parsed.host?.lowercase()?.removePrefix("www.").orEmpty()
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
