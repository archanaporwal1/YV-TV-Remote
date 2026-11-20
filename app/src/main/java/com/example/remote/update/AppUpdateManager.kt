package com.example.remote.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
  val versionCode: Int?,
  val versionName: String,
  val tag: String,
  val title: String,
  val notes: String,
  val apkUrl: String,
  val releaseUrl: String,
)

sealed class UpdateCheckResult {
  data class UpdateAvailable(val release: AppReleaseInfo) : UpdateCheckResult()
  data class UpToDate(val currentVersion: String) : UpdateCheckResult()
  data object NotConfigured : UpdateCheckResult()
  data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
  private const val CHANNEL_ID = "yvtv_app_updates"
  private const val NOTIFICATION_ID = 6468

  fun resolveSource(overrideSource: String): String {
    val cleanOverride = overrideSource.trim()
    if (cleanOverride.isNotEmpty()) return cleanOverride
    return BuildConfig.RELEASE_REPO.trim()
  }

  suspend fun checkForUpdate(sourceOverride: String): UpdateCheckResult = withContext(Dispatchers.IO) {
    val source = resolveSource(sourceOverride)
    if (source.isBlank()) {
      return@withContext UpdateCheckResult.NotConfigured
    }

    try {
      val info =
        if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
          val json = fetchString(source)
          parseManifestJson(JSONObject(json))
        } else {
          val cleanRepo = source.trim('/').removePrefix("https://github.com/").removePrefix("http://github.com/")
          if (!cleanRepo.contains('/')) {
            return@withContext UpdateCheckResult.Error("Invalid release source format. Use owner/repo or a manifest URL.")
          }
          fetchLatestFromGitHub(cleanRepo)
        }

      if (info == null) {
        return@withContext UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
      }

      val isNewer = isNewerVersion(
        remoteVersionName = info.versionName,
        remoteVersionCode = info.versionCode,
        localVersionName = BuildConfig.VERSION_NAME,
        localVersionCode = BuildConfig.VERSION_CODE,
      )
      if (isNewer) {
        UpdateCheckResult.UpdateAvailable(info)
      } else {
        UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
      }
    } catch (e: Exception) {
      UpdateCheckResult.Error(e.message ?: "Unable to check for updates.")
    }
  }

  private fun fetchLatestFromGitHub(repo: String): AppReleaseInfo? {
    // Scan recent releases for an Android APK or update-manifest.json asset
    val listText = runCatching { fetchString("https://api.github.com/repos/$repo/releases?per_page=10") }.getOrNull()
    if (!listText.isNullOrBlank()) {
      val array = runCatching { org.json.JSONArray(listText) }.getOrNull()
      if (array != null) {
        for (i in 0 until array.length()) {
          val rel = array.optJSONObject(i) ?: continue
          if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
          val parsed = parseGitHubReleaseObject(repo, rel)
          if (parsed != null) return parsed
        }
      }
    }

    // Fallback to latest release object
    val apiText = fetchString("https://api.github.com/repos/$repo/releases/latest")
    return parseGitHubReleaseObject(repo, JSONObject(apiText))
  }

  private fun parseGitHubReleaseObject(repo: String, obj: JSONObject): AppReleaseInfo? {
    val assets = obj.optJSONArray("assets") ?: return null
    var manifestDownloadUrl: String? = null
    var apkUrl: String? = null

    for (i in 0 until assets.length()) {
      val asset = assets.optJSONObject(i) ?: continue
      val name = asset.optString("name")
      val download = asset.optString("browser_download_url")
      if (name == "update-manifest.json" && download.isNotBlank()) {
        manifestDownloadUrl = download
      } else if (download.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
        apkUrl = download
      }
    }

    if (manifestDownloadUrl == null && apkUrl == null) {
      return null
    }

    if (manifestDownloadUrl != null) {
      val manifestText = runCatching { fetchString(manifestDownloadUrl) }.getOrNull()
      if (!manifestText.isNullOrBlank()) {
        val parsed = runCatching { parseManifestJson(JSONObject(manifestText)) }.getOrNull()
        if (parsed != null) return parsed
      }
    }

    val tag = obj.optString("tag_name").ifBlank { return null }
    val versionName = tag.trim().removePrefix("v").removePrefix("V")
    val title = obj.optString("name").ifBlank { "YV-TV Remote $tag" }
    val notes = obj.optString("body").ifBlank { "New update available." }
    val releaseUrl = obj.optString("html_url").ifBlank { "https://github.com/$repo/releases/latest" }
    return AppReleaseInfo(
      versionCode = parseTrailingBuildNumber(versionName),
      versionName = versionName,
      tag = tag,
      title = title,
      notes = notes,
      apkUrl = apkUrl ?: releaseUrl,
      releaseUrl = releaseUrl,
    )
  }

  internal fun parseManifestJson(obj: JSONObject): AppReleaseInfo? {
    val versionName = obj.optString("versionName").trim()
    val tag = obj.optString("tag").trim().ifBlank { "v$versionName" }
    val normalizedVersion = versionName.ifBlank { tag.removePrefix("v").removePrefix("V") }
    if (normalizedVersion.isBlank()) return null

    val versionCode = if (obj.has("versionCode")) obj.optInt("versionCode") else parseTrailingBuildNumber(normalizedVersion)
    val releaseUrl = obj.optString("releaseUrl").trim()
    val apkUrl = obj.optString("apkUrl").trim().ifBlank { obj.optString("downloadUrl").trim() }.ifBlank { releaseUrl }
    if (apkUrl.isBlank()) return null

    return AppReleaseInfo(
      versionCode = versionCode,
      versionName = normalizedVersion,
      tag = tag,
      title = obj.optString("title").ifBlank { "YV-TV Remote v$normalizedVersion" },
      notes = obj.optString("notes").ifBlank { "New release available." },
      apkUrl = apkUrl,
      releaseUrl = releaseUrl.ifBlank { apkUrl },
    )
  }

  private fun fetchString(urlStr: String): String {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 8_000
      readTimeout = 8_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json, application/vnd.github+json")
      setRequestProperty("User-Agent", "YV-TV-Remote-Android/${BuildConfig.VERSION_NAME}")
    }
    try {
      val code = conn.responseCode
      if (code !in 200..299) {
        throw IllegalStateException("HTTP $code")
      }
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  internal fun isNewerVersion(
    remoteVersionName: String,
    remoteVersionCode: Int?,
    localVersionName: String,
    localVersionCode: Int,
  ): Boolean {
    val nameComparison = compareVersionStrings(remoteVersionName, localVersionName)
    if (nameComparison != 0) {
      return nameComparison > 0
    }
    return remoteVersionCode != null && remoteVersionCode > localVersionCode
  }

  internal fun compareVersionStrings(remote: String, local: String): Int {
    val rParts = parseVersionParts(remote)
    val lParts = parseVersionParts(local)
    val maxLen = maxOf(rParts.size, lParts.size)
    for (i in 0 until maxLen) {
      val r = rParts.getOrElse(i) { 0 }
      val l = lParts.getOrElse(i) { 0 }
      if (r != l) return r.compareTo(l)
    }
    return 0
  }

  private fun parseVersionParts(version: String): List<Int> {
    val cleaned = version.trim().removePrefix("v").removePrefix("V")
    return cleaned.split('.', '-', '+').mapNotNull { segment ->
      segment.takeWhile { it.isDigit() }.toIntOrNull()
    }
  }

  private fun parseTrailingBuildNumber(version: String): Int? {
    val parts = parseVersionParts(version)
    return if (parts.size >= 3) parts.last() else null
  }

  fun openUpdateDownload(context: Context, release: AppReleaseInfo) {
    val targetUrl = release.apkUrl.ifBlank { release.releaseUrl }.trim()
    if (!isHttpUrl(targetUrl)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
  }

  private fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

  fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun showUpdateNotification(context: Context, release: AppReleaseInfo) {
    if (!hasNotificationPermission(context)) return

    ensureNotificationChannel(context)

    val downloadUrl = release.apkUrl.ifBlank { release.releaseUrl }.trim()
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val tapPendingIntent = PendingIntent.getActivity(context, 1, openAppIntent, pendingFlags)

    val summaryLine =
      release.notes.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
        ?: "Tap to update to the latest version."

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_yvtv_update)
      .setContentTitle("YV-TV Remote v${release.versionName} Available")
      .setContentText(summaryLine)
      .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.ifBlank { summaryLine }))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(tapPendingIntent)

    if (isHttpUrl(downloadUrl)) {
      val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val updatePendingIntent = PendingIntent.getActivity(context, 2, browserIntent, pendingFlags)
      builder.addAction(0, "Update Now", updatePendingIntent)
    }

    runCatching {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }
  }

  private fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      val existing = manager.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "App Updates",
          NotificationManager.IMPORTANCE_HIGH,
        ).apply {
          description = "Notifications when a new version of YV-TV Remote is released."
        }
        manager.createNotificationChannel(channel)
      }
    }
  }
}
