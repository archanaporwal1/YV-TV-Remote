package com.example.remote.data

/**
 * Resolves any [TvAppShortcut] (built-in, 1-tap catalog, or manually entered custom shortcut)
 * to a canonical OTT brand key so known streaming services always render their authentic
 * official Android TV launcher icon.
 */
object OttBrandResolver {

  val builtInDefaultBrandKeys: Set<String> =
    setOf(
      "youtube",
      "netflix",
      "prime_video",
      "jiocinema",
      "hotstar",
      "sonyliv",
      "zee5",
      "sunnxt",
      "jiotv",
    )

  val catalogBrandKeys: Set<String> =
    setOf(
      // Indian OTT catalog
      "airtel_xstream",
      "tata_play_binge",
      "mx_player",
      "aha",
      "hoichoi",
      "fancode",
      "lionsgate_play",
      "shemaroome",
      "eros_now",
      "manoramamax",
      "etv_win",
      "chaupal",
      "stage",
      "jiosaavn",
      // Global OTT catalog
      "apple_tv",
      "spotify",
      "twitch",
      "plex",
      "crunchyroll",
      "discovery_plus",
      "max",
      "hulu",
    )

  val allKnownBrandKeys: Set<String> = builtInDefaultBrandKeys + catalogBrandKeys

  private val idAliases: Map<String, String> =
    mapOf(
      "youtube" to "youtube",
      "yt" to "youtube",
      "netflix" to "netflix",
      "prime_video" to "prime_video",
      "primevideo" to "prime_video",
      "amazon_prime" to "prime_video",
      "jiocinema" to "jiocinema",
      "jio_cinema" to "jiocinema",
      "hotstar" to "hotstar",
      "jiohotstar" to "hotstar",
      "jio_hotstar" to "hotstar",
      "disney_hotstar" to "hotstar",
      "disneyplus_hotstar" to "hotstar",
      "sonyliv" to "sonyliv",
      "sony_liv" to "sonyliv",
      "zee5" to "zee5",
      "zee_5" to "zee5",
      "sunnxt" to "sunnxt",
      "sun_nxt" to "sunnxt",
      "jiotv" to "jiotv",
      "jio_tv" to "jiotv",
      "jiotv_plus" to "jiotv",
      "airtel_xstream" to "airtel_xstream",
      "airtelxstream" to "airtel_xstream",
      "tata_play_binge" to "tata_play_binge",
      "tataplay_binge" to "tata_play_binge",
      "tatasky_binge" to "tata_play_binge",
      "mx_player" to "mx_player",
      "mxplayer" to "mx_player",
      "amazon_mx_player" to "mx_player",
      "aha" to "aha",
      "hoichoi" to "hoichoi",
      "fancode" to "fancode",
      "lionsgate_play" to "lionsgate_play",
      "lionsgateplay" to "lionsgate_play",
      "shemaroome" to "shemaroome",
      "shemaroo_me" to "shemaroome",
      "eros_now" to "eros_now",
      "erosnow" to "eros_now",
      "manoramamax" to "manoramamax",
      "manorama_max" to "manoramamax",
      "etv_win" to "etv_win",
      "etvwin" to "etv_win",
      "chaupal" to "chaupal",
      "stage" to "stage",
      "jiosaavn" to "jiosaavn",
      "jio_saavn" to "jiosaavn",
      "apple_tv" to "apple_tv",
      "appletv" to "apple_tv",
      "spotify" to "spotify",
      "twitch" to "twitch",
      "plex" to "plex",
      "crunchyroll" to "crunchyroll",
      "discovery_plus" to "discovery_plus",
      "discoveryplus" to "discovery_plus",
      "max" to "max",
      "hbo_max" to "max",
      "hbomax" to "max",
      "hulu" to "hulu",
    )

  private val domainToBrand: Map<String, String> =
    mapOf(
      "youtube.com" to "youtube",
      "youtu.be" to "youtube",
      "netflix.com" to "netflix",
      "primevideo.com" to "prime_video",
      "jiocinema.com" to "jiocinema",
      "jiohotstar.com" to "hotstar",
      "hotstar.com" to "hotstar",
      "sonyliv.com" to "sonyliv",
      "zee5.com" to "zee5",
      "sunnxt.com" to "sunnxt",
      "jiotv.com" to "jiotv",
      "jiotvplus.com" to "jiotv",
      "airtelxstream.in" to "airtel_xstream",
      "airtel.tv" to "airtel_xstream",
      "tataplaybinge.com" to "tata_play_binge",
      "tataskybinge.com" to "tata_play_binge",
      "mxplayer.in" to "mx_player",
      "aha.video" to "aha",
      "hoichoi.tv" to "hoichoi",
      "fancode.com" to "fancode",
      "lionsgateplay.com" to "lionsgate_play",
      "shemaroome.com" to "shemaroome",
      "erosnow.com" to "eros_now",
      "manoramamax.com" to "manoramamax",
      "etvwin.com" to "etv_win",
      "chaupal.tv" to "chaupal",
      "stage.in" to "stage",
      "jiosaavn.com" to "jiosaavn",
      "saavn.com" to "jiosaavn",
      "tv.apple.com" to "apple_tv",
      "spotify.com" to "spotify",
      "twitch.tv" to "twitch",
      "plex.tv" to "plex",
      "crunchyroll.com" to "crunchyroll",
      "discoveryplus.com" to "discovery_plus",
      "discoveryplus.in" to "discovery_plus",
      "max.com" to "max",
      "hbomax.com" to "max",
      "hulu.com" to "hulu",
    )

  private val packageToBrand: Map<String, String> =
    mapOf(
      "com.google.android.youtube.tv" to "youtube",
      "com.google.android.youtube.googletv" to "youtube",
      "com.google.android.youtube" to "youtube",
      "com.netflix.ninja" to "netflix",
      "com.netflix.mediaclient" to "netflix",
      "com.amazon.amazonvideo.livingroom" to "prime_video",
      "com.amazon.avod.thirdpartyclient" to "prime_video",
      "com.jio.media.stb.ondemand" to "jiocinema",
      "com.jio.media.ondemand" to "jiocinema",
      "in.startv.hotstar" to "hotstar",
      "in.startv.hotstartvonly" to "hotstar",
      "in.startv.hotstar.dplus.tv" to "hotstar",
      "com.hotstar.disney" to "hotstar",
      "com.sonyliv" to "sonyliv",
      "com.graymatrix.did" to "zee5",
      "com.suntv.sunnxt" to "sunnxt",
      "com.jio.media.jiotvplus" to "jiotv",
      "com.jio.jioplay.tv" to "jiotv",
      "com.jio.jioplay.tv.app" to "jiotv",
      "tv.accedo.airtel.wynk" to "airtel_xstream",
      "com.airtel.tv" to "airtel_xstream",
      "com.tatasky.binge" to "tata_play_binge",
      "com.tataplay.binge" to "tata_play_binge",
      "com.mxtech.videoplayer.television" to "mx_player",
      "com.mxtech.videoplayer.ad" to "mx_player",
      "com.mxtech.videoplayer.pro" to "mx_player",
      "ahaflix.tv" to "aha",
      "com.aha.video.telugu" to "aha",
      "com.viewlift.hoichoi" to "hoichoi",
      "com.hoichoi.tv.android" to "hoichoi",
      "com.fancode.tv" to "fancode",
      "com.dream11sportsguru" to "fancode",
      "com.lionsgateplay.videoapp" to "lionsgate_play",
      "com.lionsgateplay.tv" to "lionsgate_play",
      "com.shemaroo.shemaroome" to "shemaroome",
      "com.shemaroo.shemaroometv" to "shemaroome",
      "com.erosnow" to "eros_now",
      "com.erosnow.tv" to "eros_now",
      "com.mmtv.manoramamax.tv" to "manoramamax",
      "com.mmtv.manoramamax" to "manoramamax",
      "com.etvwin.tv" to "etv_win",
      "com.etvwin.android" to "etv_win",
      "com.chaupal.tv" to "chaupal",
      "com.bosctech.chaupal" to "chaupal",
      "in.stage.tv" to "stage",
      "in.stage" to "stage",
      "com.jio.media.jiobeats" to "jiosaavn",
      "com.jio.media.jiobeats.tv" to "jiosaavn",
      "com.apple.atve.androidtv.appletv" to "apple_tv",
      "com.apple.atve.sony.appletv" to "apple_tv",
      "com.spotify.tv.android" to "spotify",
      "com.spotify.music" to "spotify",
      "tv.twitch.android.app" to "twitch",
      "com.plexapp.android" to "plex",
      "com.crunchyroll.crunchyroid" to "crunchyroll",
      "com.discovery.discoveryplus.androidtv" to "discovery_plus",
      "com.discovery.discoveryplus.in" to "discovery_plus",
      "com.wbd.stream" to "max",
      "com.hbo.hbonow" to "max",
      "com.hulu.livingroomplus" to "hulu",
      "com.hulu.plus" to "hulu",
    )

  private val normalizedNameToBrand: Map<String, String> =
    mapOf(
      "youtube" to "youtube",
      "youtube tv" to "youtube",
      "netflix" to "netflix",
      "prime video" to "prime_video",
      "amazon prime" to "prime_video",
      "amazon prime video" to "prime_video",
      "prime" to "prime_video",
      "jiocinema" to "jiocinema",
      "jio cinema" to "jiocinema",
      "jiohotstar" to "hotstar",
      "jio hotstar" to "hotstar",
      "hotstar" to "hotstar",
      "disney hotstar" to "hotstar",
      "disney+ hotstar" to "hotstar",
      "disney plus hotstar" to "hotstar",
      "sonyliv" to "sonyliv",
      "sony liv" to "sonyliv",
      "zee5" to "zee5",
      "zee 5" to "zee5",
      "zee five" to "zee5",
      "sun nxt" to "sunnxt",
      "sunnxt" to "sunnxt",
      "sun next" to "sunnxt",
      "jiotv" to "jiotv",
      "jio tv" to "jiotv",
      "jiotv+" to "jiotv",
      "jio tv+" to "jiotv",
      "jio tv plus" to "jiotv",
      "airtel xstream" to "airtel_xstream",
      "airtel xstream play" to "airtel_xstream",
      "airtel extreme" to "airtel_xstream",
      "xstream play" to "airtel_xstream",
      "tata play binge" to "tata_play_binge",
      "tata sky binge" to "tata_play_binge",
      "tataplay binge" to "tata_play_binge",
      "mx player" to "mx_player",
      "amazon mx player" to "mx_player",
      "mxplayer" to "mx_player",
      "aha" to "aha",
      "aha video" to "aha",
      "hoichoi" to "hoichoi",
      "fancode" to "fancode",
      "fan code" to "fancode",
      "lionsgate play" to "lionsgate_play",
      "lionsgate" to "lionsgate_play",
      "shemaroome" to "shemaroome",
      "shemaroo me" to "shemaroome",
      "shemaroo" to "shemaroome",
      "eros now" to "eros_now",
      "erosnow" to "eros_now",
      "manoramamax" to "manoramamax",
      "manorama max" to "manoramamax",
      "etv win" to "etv_win",
      "etvwin" to "etv_win",
      "chaupal" to "chaupal",
      "stage" to "stage",
      "stage ott" to "stage",
      "jiosaavn" to "jiosaavn",
      "jio saavn" to "jiosaavn",
      "saavn" to "jiosaavn",
      "apple tv" to "apple_tv",
      "apple tv+" to "apple_tv",
      "apple tv plus" to "apple_tv",
      "spotify" to "spotify",
      "twitch" to "twitch",
      "plex" to "plex",
      "crunchyroll" to "crunchyroll",
      "discovery+" to "discovery_plus",
      "discovery plus" to "discovery_plus",
      "max" to "max",
      "hbo max" to "max",
      "hbomax" to "max",
      "hulu" to "hulu",
    )

  private val brandDisplayNames: Map<String, String> =
    mapOf(
      "youtube" to "YouTube",
      "netflix" to "Netflix",
      "prime_video" to "Prime Video",
      "jiocinema" to "JioCinema",
      "hotstar" to "JioHotstar",
      "sonyliv" to "SonyLIV",
      "zee5" to "ZEE5",
      "sunnxt" to "Sun NXT",
      "jiotv" to "JioTV",
      "airtel_xstream" to "Airtel Xstream Play",
      "tata_play_binge" to "Tata Play Binge",
      "mx_player" to "MX Player",
      "aha" to "aha",
      "hoichoi" to "hoichoi",
      "fancode" to "FanCode",
      "lionsgate_play" to "Lionsgate Play",
      "shemaroome" to "ShemarooMe",
      "eros_now" to "Eros Now",
      "manoramamax" to "ManoramaMAX",
      "etv_win" to "ETV Win",
      "chaupal" to "Chaupal",
      "stage" to "STAGE",
      "jiosaavn" to "JioSaavn",
      "apple_tv" to "Apple TV+",
      "spotify" to "Spotify",
      "twitch" to "Twitch",
      "plex" to "Plex",
      "crunchyroll" to "Crunchyroll",
      "discovery_plus" to "Discovery+",
      "max" to "Max",
      "hulu" to "Hulu",
    )

  fun displayNameForBrand(brandKey: String): String? = brandDisplayNames[brandKey]

  fun resolveBrandKey(shortcut: TvAppShortcut): String? =
    resolveBrandKey(
      id = shortcut.id,
      appLink = shortcut.appLink,
      packageNames = shortcut.packageNames,
      name = shortcut.name,
    )

  fun resolveBrandKey(
    id: String = "",
    appLink: String = "",
    packageNames: List<String> = emptyList(),
    name: String = "",
  ): String? {
    // 1. Canonical ID match
    val normalizedId = id.trim().lowercase()
    if (normalizedId.isNotEmpty()) {
      idAliases[normalizedId]?.let { return it }
    }

    // 2. Normalized URL domain / host match
    val host = extractNormalizedHost(appLink)
    if (host.isNotEmpty()) {
      domainToBrand.entries.firstOrNull { (knownHost, _) ->
        host == knownHost || host.endsWith(".$knownHost")
      }?.value?.let { return it }
    }

    // 3. Known Android TV package ID match (explicit list + market://id= link)
    val candidatePackages =
      buildList {
        packageNames.forEach { pkg ->
          val clean = pkg.trim().lowercase()
          if (clean.isNotEmpty()) add(clean)
        }
        extractMarketPackage(appLink)?.let { add(it) }
      }
    candidatePackages.firstNotNullOfOrNull { pkg -> packageToBrand[pkg] }?.let { return it }

    // 4. Normalized brand display name / alias match
    val cleanName = normalizeBrandName(name)
    if (cleanName.isNotEmpty()) {
      normalizedNameToBrand[cleanName]?.let { return it }
      val compactName = cleanName.replace(" ", "")
      normalizedNameToBrand.entries.firstOrNull { (alias, _) ->
        alias.replace(" ", "") == compactName
      }?.value?.let { return it }
    }

    return null
  }

  private fun normalizeBrandName(name: String): String =
    name
      .trim()
      .lowercase()
      .replace(Regex("[^a-z0-9+\\s]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  private fun extractNormalizedHost(rawLink: String): String {
    val trimmed = rawLink.trim().lowercase()
    if (trimmed.isEmpty() || trimmed.startsWith("market:")) return ""
    val withoutScheme =
      if (trimmed.contains("://")) {
        trimmed.substringAfter("://")
      } else {
        trimmed
      }
    val authority =
      withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .substringBefore(':')
        .removePrefix("www.")
        .trim()
    return if (authority.contains('.')) authority else ""
  }

  private fun extractMarketPackage(rawLink: String): String? {
    val trimmed = rawLink.trim()
    if (!trimmed.startsWith("market:", ignoreCase = true)) return null
    val query = trimmed.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return null
    return query
      .split('&')
      .mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx <= 0) null
        else part.substring(0, idx).lowercase() to part.substring(idx + 1).trim().lowercase()
      }
      .firstOrNull { it.first == "id" }
      ?.second
      ?.takeIf { it.isNotEmpty() }
  }
}
