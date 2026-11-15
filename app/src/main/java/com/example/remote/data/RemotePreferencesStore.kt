package com.example.remote.data

import android.content.Context
import android.content.SharedPreferences
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
)

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
    )
  }

  fun saveSettings(settings: AppSettings) {
    prefs.edit()
      .putBoolean(KEY_HAPTICS_ENABLED, settings.hapticsEnabled)
      .putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
      .putFloat(KEY_SOUND_VOLUME, settings.soundVolume.coerceIn(0f, 1f))
      .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnectEnabled)
      .apply()
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
    const val DEFAULT_PROFILE_ID = "profile_living_room"
  }
}
