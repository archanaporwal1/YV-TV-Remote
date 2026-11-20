package com.example.remote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.RemoteAccentTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserProfile(
  val id: String,
  val name: String,
  val badgeIcon: String = "home",
  val createdAt: Long = System.currentTimeMillis(),
)

data class SavedTvConnection(
  val id: String,
  val profileId: String,
  val customName: String,
  val deviceName: String,
  val ipAddress: String,
  val port: Int = 6466,
  val lastConnectedAt: Long = System.currentTimeMillis(),
)

data class AppSettings(
  val hapticsEnabled: Boolean = true,
  val soundEnabled: Boolean = true,
  val soundVolume: Float = 0.7f,
  val autoReconnectEnabled: Boolean = true,
  val autoCheckUpdates: Boolean = true,
  val releaseRepoOverride: String = "",
)

enum class PreferredPadMode(val storageKey: String) {
  DPAD("DPAD"),
  TOUCHPAD("TOUCHPAD");

  companion object {
    fun fromKey(key: String?): PreferredPadMode =
      entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: DPAD
  }
}

enum class UiLayoutPreset(val label: String) {
  FULL("Full Remote"),
  MINIMAL("Minimalist"),
  STREAMING("Media + OTT"),
}

data class RemoteUiPreferences(
  val defaultPadMode: PreferredPadMode = PreferredPadMode.DPAD,
  val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
  val accentTheme: RemoteAccentTheme = RemoteAccentTheme.ELECTRIC_CYAN,
  val compactMode: Boolean = false,
  val showQuickSearchBar: Boolean = true,
  val showNavigationRow: Boolean = true,
  val showVolumeAndChannel: Boolean = true,
  val showMediaTransport: Boolean = true,
  val showStreamingShortcuts: Boolean = true,
  val autoSyncTvApps: Boolean = true,
  val shortcutColumns: Int = 3,
  val enabledShortcutIds: Set<String> = TvAppShortcuts.defaultEnabledIds,
  val customShortcuts: List<TvAppShortcut> = emptyList(),
) {
  fun allAvailableShortcuts(): List<TvAppShortcut> = TvAppShortcuts.all + customShortcuts

  fun enabledShortcuts(): List<TvAppShortcut> =
    allAvailableShortcuts().filter { it.id in enabledShortcutIds }

  fun matchingPresetOrNull(): UiLayoutPreset? =
    when {
      !compactMode &&
        showQuickSearchBar &&
        showNavigationRow &&
        showVolumeAndChannel &&
        showMediaTransport &&
        showStreamingShortcuts -> UiLayoutPreset.FULL

      compactMode &&
        !showQuickSearchBar &&
        showNavigationRow &&
        showVolumeAndChannel &&
        !showMediaTransport &&
        !showStreamingShortcuts -> UiLayoutPreset.MINIMAL

      compactMode &&
        showQuickSearchBar &&
        showNavigationRow &&
        showVolumeAndChannel &&
        showMediaTransport &&
        showStreamingShortcuts -> UiLayoutPreset.STREAMING

      else -> null
    }
}

class RemotePreferencesStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun loadProfiles(): List<UserProfile> {
    val raw = prefs.getString(KEY_PROFILES, null)
    val parsed =
      if (raw.isNullOrBlank()) {
        emptyList()
      } else {
        try {
          val array = JSONArray(raw)
          buildList {
            for (i in 0 until array.length()) {
              val obj = array.getJSONObject(i)
              add(
                UserProfile(
                  id = obj.getString("id"),
                  name = obj.getString("name"),
                  badgeIcon = obj.optString("badgeIcon", "home"),
                  createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                )
              )
            }
          }
        } catch (_: Exception) {
          emptyList()
        }
      }

    if (parsed.isNotEmpty()) return parsed

    val defaultProfile =
      UserProfile(
        id = DEFAULT_PROFILE_ID,
        name = "Living Room",
        badgeIcon = "weekend",
      )
    saveProfiles(listOf(defaultProfile))
    setActiveProfileId(defaultProfile.id)
    return listOf(defaultProfile)
  }

  fun saveProfiles(profiles: List<UserProfile>) {
    val array = JSONArray()
    profiles.forEach { profile ->
      array.put(
        JSONObject().apply {
          put("id", profile.id)
          put("name", profile.name)
          put("badgeIcon", profile.badgeIcon)
          put("createdAt", profile.createdAt)
        }
      )
    }
    prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
  }

  fun getActiveProfileId(): String {
    val id = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
    if (!id.isNullOrBlank()) return id
    val profiles = loadProfiles()
    val fallback = profiles.first().id
    setActiveProfileId(fallback)
    return fallback
  }

  fun setActiveProfileId(profileId: String) {
    prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
  }

  fun loadSavedConnections(): List<SavedTvConnection> {
    val raw = prefs.getString(KEY_SAVED_TVS, null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.getJSONObject(i)
          add(
            SavedTvConnection(
              id = obj.getString("id"),
              profileId = obj.optString("profileId", DEFAULT_PROFILE_ID),
              customName = obj.getString("customName"),
              deviceName = obj.optString("deviceName", obj.getString("customName")),
              ipAddress = obj.getString("ipAddress"),
              port = obj.optInt("port", 6466),
              lastConnectedAt = obj.optLong("lastConnectedAt", System.currentTimeMillis()),
            )
          )
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun saveConnections(connections: List<SavedTvConnection>) {
    val array = JSONArray()
    connections.forEach { tv ->
      array.put(
        JSONObject().apply {
          put("id", tv.id)
          put("profileId", tv.profileId)
          put("customName", tv.customName)
          put("deviceName", tv.deviceName)
          put("ipAddress", tv.ipAddress)
          put("port", tv.port)
          put("lastConnectedAt", tv.lastConnectedAt)
        }
      )
    }
    prefs.edit().putString(KEY_SAVED_TVS, array.toString()).apply()
  }

  fun upsertConnection(
    profileId: String,
    customName: String,
    deviceName: String,
    ipAddress: String,
    port: Int,
  ): SavedTvConnection {
    val current = loadSavedConnections().toMutableList()
    val existingIndex = current.indexOfFirst { it.ipAddress == ipAddress && it.profileId == profileId }
    val updated =
      if (existingIndex >= 0) {
        current[existingIndex].copy(
          customName = customName.trim().ifEmpty { deviceName },
          deviceName = deviceName,
          port = port,
          lastConnectedAt = System.currentTimeMillis(),
        ).also { current[existingIndex] = it }
      } else {
        SavedTvConnection(
          id = UUID.randomUUID().toString(),
          profileId = profileId,
          customName = customName.trim().ifEmpty { deviceName },
          deviceName = deviceName,
          ipAddress = ipAddress,
          port = port,
          lastConnectedAt = System.currentTimeMillis(),
        ).also { current.add(0, it) }
      }
    saveConnections(current)
    return updated
  }

  fun getLastConnectedTvId(): String? = prefs.getString(KEY_LAST_CONNECTED_TV_ID, null)

  fun setLastConnectedTvId(tvId: String?) {
    prefs.edit().apply {
      if (tvId == null) remove(KEY_LAST_CONNECTED_TV_ID)
      else putString(KEY_LAST_CONNECTED_TV_ID, tvId)
    }.apply()
  }

  fun loadSettings(): AppSettings {
    return AppSettings(
      hapticsEnabled = prefs.getBoolean(KEY_HAPTICS_ENABLED, true),
      soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true),
      soundVolume = prefs.getFloat(KEY_SOUND_VOLUME, 0.7f).coerceIn(0f, 1f),
      autoReconnectEnabled = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
      autoCheckUpdates = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true),
      releaseRepoOverride = prefs.getString(KEY_RELEASE_REPO_OVERRIDE, "").orEmpty(),
    )
  }

  fun saveSettings(settings: AppSettings) {
    prefs.edit()
      .putBoolean(KEY_HAPTICS_ENABLED, settings.hapticsEnabled)
      .putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
      .putFloat(KEY_SOUND_VOLUME, settings.soundVolume.coerceIn(0f, 1f))
      .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnectEnabled)
      .putBoolean(KEY_AUTO_CHECK_UPDATES, settings.autoCheckUpdates)
      .putString(KEY_RELEASE_REPO_OVERRIDE, settings.releaseRepoOverride.trim())
      .apply()
  }

  fun getSkippedUpdateTag(): String = prefs.getString(KEY_SKIPPED_UPDATE_TAG, "").orEmpty()

  fun setSkippedUpdateTag(tag: String) {
    prefs.edit().putString(KEY_SKIPPED_UPDATE_TAG, tag).apply()
  }

  fun getLastNotifiedUpdateTag(): String = prefs.getString(KEY_LAST_NOTIFIED_UPDATE_TAG, "").orEmpty()

  fun setLastNotifiedUpdateTag(tag: String) {
    prefs.edit().putString(KEY_LAST_NOTIFIED_UPDATE_TAG, tag).apply()
  }

  fun loadUiPreferences(): RemoteUiPreferences {
    val raw = prefs.getString(KEY_UI_PREFERENCES_JSON, null)
    if (raw.isNullOrBlank()) return RemoteUiPreferences()
    return try {
      val obj = JSONObject(raw)
      val builtInIds = TvAppShortcuts.all.map { it.id }.toSet()
      val customShortcuts =
        if (obj.has("customShortcuts")) {
          val arr = obj.getJSONArray("customShortcuts")
          buildList {
            for (i in 0 until arr.length()) {
              val item = arr.optJSONObject(i) ?: continue
              val id = item.optString("id").trim()
              val name = item.optString("name").trim()
              val appLink = item.optString("appLink").trim()
              if (id.isNotEmpty() && id !in builtInIds && name.isNotEmpty() && appLink.isNotEmpty()) {
                val badge =
                  item.optString("badge").trim().ifEmpty { TvAppShortcuts.suggestBadge(name) }.take(3)
                val colorArgb = item.optLong("accentColorArgb", 0xFF00B4D8L).toInt()
                val iconStyle = CustomOttIconStyle.fromKey(item.optString("iconStyle", "badge"))
                val storedPackages =
                  item.optJSONArray("packageNames")?.let { pkgArr ->
                    buildList {
                      for (j in 0 until pkgArr.length()) {
                        val pkg = pkgArr.optString(j).trim()
                        if (pkg.isNotEmpty()) add(pkg)
                      }
                    }
                  }.orEmpty()
                val storedSchemes =
                  item.optJSONArray("nativeSchemes")?.let { schemeArr ->
                    buildList {
                      for (j in 0 until schemeArr.length()) {
                        val scheme = schemeArr.optString(j).trim()
                        if (scheme.isNotEmpty()) add(scheme)
                      }
                    }
                  }.orEmpty()
                val presetMeta = TvAppShortcuts.findMetadataPreset(id)
                add(
                  TvAppShortcut(
                    id = id,
                    name = name,
                    badge = badge,
                    appLink = appLink,
                    accentColor = Color(colorArgb),
                    isCustom = true,
                    iconStyle = iconStyle,
                    packageNames = storedPackages.ifEmpty { presetMeta?.packageNames.orEmpty() },
                    nativeSchemes = storedSchemes.ifEmpty { presetMeta?.nativeSchemes.orEmpty() },
                  )
                )
              }
            }
          }
        } else {
          emptyList()
        }
      val allValidIds = builtInIds + customShortcuts.map { it.id }.toSet()
      val enabledIds =
        if (obj.has("enabledShortcutIds")) {
          val arr = obj.getJSONArray("enabledShortcutIds")
          buildSet {
            for (i in 0 until arr.length()) {
              val id = arr.optString(i).trim()
              if (id.isNotEmpty() && id in allValidIds) {
                add(id)
              }
            }
          }
        } else {
          allValidIds
        }
      RemoteUiPreferences(
        defaultPadMode = PreferredPadMode.fromKey(obj.optString("defaultPadMode", "DPAD")),
        themeMode = AppThemeMode.fromKey(obj.optString("themeMode", "system")),
        accentTheme = RemoteAccentTheme.fromKey(obj.optString("accentTheme", "cyan")),
        compactMode = obj.optBoolean("compactMode", false),
        showQuickSearchBar = obj.optBoolean("showQuickSearchBar", true),
        showNavigationRow = obj.optBoolean("showNavigationRow", true),
        showVolumeAndChannel = obj.optBoolean("showVolumeAndChannel", true),
        showMediaTransport = obj.optBoolean("showMediaTransport", true),
        showStreamingShortcuts = obj.optBoolean("showStreamingShortcuts", true),
        autoSyncTvApps = obj.optBoolean("autoSyncTvApps", true),
        shortcutColumns = obj.optInt("shortcutColumns", 3).coerceIn(2, 4),
        enabledShortcutIds = enabledIds,
        customShortcuts = customShortcuts,
      )
    } catch (_: Exception) {
      RemoteUiPreferences()
    }
  }

  fun saveUiPreferences(uiPrefs: RemoteUiPreferences) {
    val obj =
      JSONObject().apply {
        put("defaultPadMode", uiPrefs.defaultPadMode.storageKey)
        put("themeMode", uiPrefs.themeMode.storageKey)
        put("accentTheme", uiPrefs.accentTheme.storageKey)
        put("compactMode", uiPrefs.compactMode)
        put("showQuickSearchBar", uiPrefs.showQuickSearchBar)
        put("showNavigationRow", uiPrefs.showNavigationRow)
        put("showVolumeAndChannel", uiPrefs.showVolumeAndChannel)
        put("showMediaTransport", uiPrefs.showMediaTransport)
        put("showStreamingShortcuts", uiPrefs.showStreamingShortcuts)
        put("autoSyncTvApps", uiPrefs.autoSyncTvApps)
        put("shortcutColumns", uiPrefs.shortcutColumns.coerceIn(2, 4))
        val idsArray = JSONArray()
        uiPrefs.enabledShortcutIds.forEach { idsArray.put(it) }
        put("enabledShortcutIds", idsArray)
        val customArray = JSONArray()
        uiPrefs.customShortcuts.forEach { shortcut ->
          customArray.put(
            JSONObject().apply {
              put("id", shortcut.id)
              put("name", shortcut.name)
              put("badge", shortcut.badge)
              put("appLink", shortcut.appLink)
              put("accentColorArgb", shortcut.accentColor.toArgb().toLong())
              put("iconStyle", shortcut.iconStyle.key)
              if (shortcut.packageNames.isNotEmpty()) {
                val pkgArr = JSONArray()
                shortcut.packageNames.forEach { pkgArr.put(it) }
                put("packageNames", pkgArr)
              }
              if (shortcut.nativeSchemes.isNotEmpty()) {
                val schemeArr = JSONArray()
                shortcut.nativeSchemes.forEach { schemeArr.put(it) }
                put("nativeSchemes", schemeArr)
              }
            }
          )
        }
        put("customShortcuts", customArray)
      }
    prefs.edit().putString(KEY_UI_PREFERENCES_JSON, obj.toString()).apply()
  }

  fun loadDetectedTvApps(): List<DetectedTvApp> {
    val raw = prefs.getString(KEY_DETECTED_TV_APPS_JSON, null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i) ?: continue
          val tvKey = obj.optString("tvKey").trim()
          val pkg = obj.optString("packageName").trim()
          if (tvKey.isNotEmpty() && pkg.isNotEmpty()) {
            add(
              DetectedTvApp(
                tvKey = tvKey,
                packageName = pkg,
                label = obj.optString("label", "").trim(),
                lastSeenAt = obj.optLong("lastSeenAt", System.currentTimeMillis()),
              )
            )
          }
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun saveDetectedTvApps(apps: List<DetectedTvApp>) {
    val array = JSONArray()
    apps.forEach { app ->
      array.put(
        JSONObject().apply {
          put("tvKey", app.tvKey)
          put("packageName", app.packageName)
          put("label", app.label)
          put("lastSeenAt", app.lastSeenAt)
        }
      )
    }
    prefs.edit().putString(KEY_DETECTED_TV_APPS_JSON, array.toString()).apply()
  }

  fun loadDismissedAutoSyncIds(): Set<String> =
    prefs.getStringSet(KEY_DISMISSED_AUTO_SYNC_IDS, emptySet())?.toSet().orEmpty()

  fun saveDismissedAutoSyncIds(ids: Set<String>) {
    prefs.edit().putStringSet(KEY_DISMISSED_AUTO_SYNC_IDS, ids.toSet()).apply()
  }

  companion object {
    private const val PREFS_NAME = "yvtv_remote_prefs"
    private const val KEY_PROFILES = "user_profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_SAVED_TVS = "saved_tv_connections_json"
    private const val KEY_LAST_CONNECTED_TV_ID = "last_connected_tv_id"
    private const val KEY_HAPTICS_ENABLED = "settings_haptics_enabled"
    private const val KEY_SOUND_ENABLED = "settings_sound_enabled"
    private const val KEY_SOUND_VOLUME = "settings_sound_volume"
    private const val KEY_AUTO_RECONNECT = "settings_auto_reconnect"
    private const val KEY_AUTO_CHECK_UPDATES = "settings_auto_check_updates"
    private const val KEY_RELEASE_REPO_OVERRIDE = "settings_release_repo_override"
    private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
    private const val KEY_LAST_NOTIFIED_UPDATE_TAG = "last_notified_update_tag"
    private const val KEY_UI_PREFERENCES_JSON = "ui_preferences_json"
    private const val KEY_DETECTED_TV_APPS_JSON = "detected_tv_apps_json"
    private const val KEY_DISMISSED_AUTO_SYNC_IDS = "dismissed_auto_sync_ids"
    const val DEFAULT_PROFILE_ID = "profile_living_room"
  }
}
