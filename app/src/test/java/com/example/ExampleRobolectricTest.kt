package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.remote.data.AppSettings
import com.example.remote.data.RemotePreferencesStore
import com.example.remote.data.UserProfile
import com.example.remote.protocol.CertificateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("YV-TV Remote", appName)
  }

  @Test
  fun `preferences store manages profiles saved tvs and feedback settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = RemotePreferencesStore(context)

    val profiles = store.loadProfiles()
    assertTrue(profiles.isNotEmpty())

    val bedroom = UserProfile(id = "prof_bedroom", name = "Bedroom")
    store.saveProfiles(profiles + bedroom)
    store.setActiveProfileId(bedroom.id)
    assertEquals("prof_bedroom", store.getActiveProfileId())

    val saved =
      store.upsertConnection(
        profileId = bedroom.id,
        customName = "Bedroom OLED",
        deviceName = "BRAVIA XR",
        ipAddress = "192.168.1.55",
        port = 6466,
      )
    assertEquals("Bedroom OLED", saved.customName)
    assertEquals(1, store.loadSavedConnections().size)

    val updatedSettings =
      AppSettings(
        hapticsEnabled = false,
        soundEnabled = true,
        soundVolume = 0.45f,
        autoReconnectEnabled = false,
      )
    store.saveSettings(updatedSettings)
    val loadedSettings = store.loadSettings()
    assertEquals(false, loadedSettings.hapticsEnabled)
    assertEquals(true, loadedSettings.soundEnabled)
    assertEquals(0.45f, loadedSettings.soundVolume, 0.01f)
    assertEquals(false, loadedSettings.autoReconnectEnabled)
  }

  @Test
  fun `certificate manager creates and persists 2048 bit RSA identity`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val firstManager = CertificateManager(context)
    val firstTls = firstManager.createTlsSessionContext()
    assertNotNull(firstTls.sslContext)
    assertTrue(firstTls.keyManagers.isNotEmpty())
    assertTrue(firstManager.keystoreFile.exists())
    assertEquals(2048, firstTls.clientPublicKey.modulus.bitLength())

    // Second instance must reuse the exact same persisted .p12 file in filesDir
    val secondManager = CertificateManager(context)
    val secondTls = secondManager.createTlsSessionContext()
    assertEquals(firstTls.clientPublicKey.modulus, secondTls.clientPublicKey.modulus)
    assertEquals(firstTls.clientPublicKey.publicExponent, secondTls.clientPublicKey.publicExponent)

    // KeyManager must still select our client cert even when server requests specific issuers
    val x509Km = secondTls.keyManagers.filterIsInstance<javax.net.ssl.X509KeyManager>().first()
    val alias =
      x509Km.chooseClientAlias(
        arrayOf("RSA"),
        arrayOf(javax.security.auth.x500.X500Principal("CN=Android TV")),
        null,
      )
    assertEquals(CertificateManager.KEY_ALIAS, alias)
    assertNotNull(x509Km.getCertificateChain(alias))
    assertNotNull(x509Km.getPrivateKey(alias))
  }

  @Test
  fun `remote control client falls back to PairingRequired when TV rejects unknown client cert`() {
    kotlinx.coroutines.runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val certManager = CertificateManager(context)

      // Build a local TLS server with an empty truststore so it rejects any unknown client cert
      val serverKeyStore = certManager.ensureClientIdentity()
      val kmf =
        javax.net.ssl.KeyManagerFactory.getInstance(
          javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        ).apply {
          init(serverKeyStore, "yvtv_remote_keystore_pass".toCharArray())
        }
      val emptyTrustManager =
        object : javax.net.ssl.X509TrustManager {
          override fun checkClientTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?,
          ) {
            throw java.security.cert.CertificateException("CERTIFICATE_UNKNOWN")
          }

          override fun checkServerTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?,
          ) {}

          override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
      val serverSslContext =
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
          init(kmf.keyManagers, arrayOf<javax.net.ssl.TrustManager>(emptyTrustManager), null)
        }
      val serverSocket =
        (serverSslContext.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket)
          .apply {
            needClientAuth = true
          }

      val serverThread =
        Thread {
          try {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            client.startHandshake()
            client.inputStream.read()
            client.close()
          } catch (_: Exception) {
          } finally {
            try {
              serverSocket.close()
            } catch (_: Exception) {
            }
          }
        }.apply { start() }

      val remoteClient = com.example.remote.protocol.RemoteControlClient(certManager)
      remoteClient.connect("127.0.0.1", serverSocket.localPort)

      var sawPairingRequired = false
      val deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline) {
        if (remoteClient.sessionEvent.value is com.example.remote.protocol.RemoteControlClient.SessionEvent.PairingRequired) {
          sawPairingRequired = true
          break
        }
        kotlinx.coroutines.delay(25L)
      }

      remoteClient.disconnect()
      serverThread.join(1_000L)
      assertTrue(
        "Expected PairingRequired event, got ${remoteClient.sessionEvent.value}",
        sawPairingRequired,
      )
    }
  }

  @Test
  fun `update manifest parsing, skipped tag persistence, and job scheduling work cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = RemotePreferencesStore(context)
    store.setSkippedUpdateTag("v1.2.12")
    store.setLastNotifiedUpdateTag("v1.2.12")
    assertEquals("v1.2.12", store.getSkippedUpdateTag())
    assertEquals("v1.2.12", store.getLastNotifiedUpdateTag())

    val json =
      org.json.JSONObject(
        """
        {
          "versionCode": 12,
          "versionName": "1.2.12",
          "tag": "v1.2.12",
          "title": "YV-TV Remote v1.2.12",
          "notes": "Auto update release",
          "apkUrl": "https://example.com/app.apk",
          "releaseUrl": "https://example.com/release"
        }
        """.trimIndent()
      )
    val parsed = com.example.remote.update.AppUpdateManager.parseManifestJson(json)
    assertNotNull(parsed)
    assertEquals(12, parsed?.versionCode)
    assertEquals("1.2.12", parsed?.versionName)
    assertEquals("v1.2.12", parsed?.tag)
    assertEquals("https://example.com/app.apk", parsed?.apkUrl)

    com.example.remote.update.UpdateCheckJobService.syncSchedule(context, enabled = true)
    com.example.remote.update.UpdateCheckJobService.syncSchedule(context, enabled = false)
  }

  @Test
  fun `ui preferences store saves and restores theme mode layout customization and OTT filters`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = RemotePreferencesStore(context)

    val customPrefs =
      com.example.remote.data.RemoteUiPreferences(
        themeMode = com.example.ui.theme.AppThemeMode.LIGHT,
        defaultPadMode = com.example.remote.data.PreferredPadMode.TOUCHPAD,
        accentTheme = com.example.ui.theme.RemoteAccentTheme.ROYAL_AMBER,
        compactMode = true,
        showQuickSearchBar = false,
        showNavigationRow = true,
        showVolumeAndChannel = true,
        showMediaTransport = false,
        showStreamingShortcuts = true,
        shortcutColumns = 4,
        enabledShortcutIds = setOf("youtube", "netflix"),
      )

    store.saveUiPreferences(customPrefs)
    val loaded = store.loadUiPreferences()
    assertEquals(com.example.ui.theme.AppThemeMode.LIGHT, loaded.themeMode)
    assertEquals(com.example.remote.data.PreferredPadMode.TOUCHPAD, loaded.defaultPadMode)
    assertEquals(com.example.ui.theme.RemoteAccentTheme.ROYAL_AMBER, loaded.accentTheme)
    assertEquals(true, loaded.compactMode)
    assertEquals(false, loaded.showQuickSearchBar)
    assertEquals(true, loaded.showNavigationRow)
    assertEquals(false, loaded.showMediaTransport)
    assertEquals(4, loaded.shortcutColumns)
    assertEquals(setOf("youtube", "netflix"), loaded.enabledShortcutIds)
  }

  @Test
  fun `custom OTT shortcuts normalize links, suggest badges, and persist across store reloads`() {
    assertEquals(
      "https://tubitv.com",
      com.example.remote.data.TvAppShortcuts.normalizeAppLink("tubitv.com"),
    )
    assertEquals(
      "https://tv.apple.com",
      com.example.remote.data.TvAppShortcuts.normalizeAppLink("https://tv.apple.com"),
    )
    assertEquals(
      "market://details?id=com.spotify.tv.android",
      com.example.remote.data.TvAppShortcuts.normalizeAppLink("com.spotify.tv.android"),
    )
    assertEquals("AT", com.example.remote.data.TvAppShortcuts.suggestBadge("Apple TV+"))
    assertEquals("SP", com.example.remote.data.TvAppShortcuts.suggestBadge("Spotify"))

    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = RemotePreferencesStore(context)
    val customTubi =
      com.example.remote.data.TvAppShortcut(
        id = "custom_tubi",
        name = "Tubi",
        badge = "TB",
        appLink = "https://tubitv.com",
        accentColor = androidx.compose.ui.graphics.Color(0xFFFA382F),
        isCustom = true,
        iconStyle = com.example.remote.data.CustomOttIconStyle.MOVIE,
      )
    val spotifyPreset =
      com.example.remote.data.TvAppShortcuts.popularAddableCatalog.first { it.id == "spotify" }

    val savedPrefs =
      com.example.remote.data.RemoteUiPreferences(
        customShortcuts = listOf(spotifyPreset, customTubi),
        enabledShortcutIds = com.example.remote.data.TvAppShortcuts.defaultEnabledIds + setOf("spotify", "custom_tubi"),
      )
    store.saveUiPreferences(savedPrefs)

    val loaded = store.loadUiPreferences()
    assertEquals(2, loaded.customShortcuts.size)
    assertTrue(loaded.enabledShortcutIds.contains("custom_tubi"))
    assertTrue(loaded.enabledShortcutIds.contains("spotify"))
    val reloadedTubi = loaded.customShortcuts.first { it.id == "custom_tubi" }
    assertEquals("Tubi", reloadedTubi.name)
    assertEquals("TB", reloadedTubi.badge)
    assertEquals("https://tubitv.com", reloadedTubi.appLink)
    assertEquals(com.example.remote.data.CustomOttIconStyle.MOVIE, reloadedTubi.iconStyle)
    assertTrue(loaded.enabledShortcuts().any { it.id == "custom_tubi" })
  }

  @Test
  fun `viewModel auto reconnects on foreground return after background socket drop unless manually disconnected`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val certManager = CertificateManager(app)
    val serverKeyStore = certManager.ensureClientIdentity()
    val kmf =
      javax.net.ssl.KeyManagerFactory.getInstance(
        javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
      ).apply {
        init(serverKeyStore, "yvtv_remote_keystore_pass".toCharArray())
      }
    val trustAll =
      object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(
          chain: Array<out java.security.cert.X509Certificate>?,
          authType: String?,
        ) {}

        override fun checkServerTrusted(
          chain: Array<out java.security.cert.X509Certificate>?,
          authType: String?,
        ) {}

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
      }
    val serverSslContext =
      javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(kmf.keyManagers, arrayOf<javax.net.ssl.TrustManager>(trustAll), null)
      }
    val serverSocket =
      (serverSslContext.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket)
        .apply {
          needClientAuth = false
        }

    val acceptCount = java.util.concurrent.atomic.AtomicInteger(0)
    val firstSocketClosedLatch = java.util.concurrent.CountDownLatch(1)
    val secondConnectedLatch = java.util.concurrent.CountDownLatch(1)
    val releaseFirstSocketLatch = java.util.concurrent.CountDownLatch(1)

    val serverThread =
      Thread {
        try {
          while (!serverSocket.isClosed && acceptCount.get() < 2) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            val idx = acceptCount.incrementAndGet()
            client.startHandshake()
            val configureMsg =
              com.example.proto.remote.RemoteMessage.newBuilder()
                .setRemoteConfigure(
                  com.example.proto.remote.RemoteConfigure.newBuilder()
                    .setCode1(622)
                    .build()
                )
                .build()
            com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)
            if (idx == 1) {
              releaseFirstSocketLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
              client.close()
              firstSocketClosedLatch.countDown()
            } else {
              secondConnectedLatch.countDown()
              Thread.sleep(800L)
              client.close()
            }
          }
        } catch (_: Throwable) {
        } finally {
          try {
            serverSocket.close()
          } catch (_: Exception) {
          }
        }
      }.apply { start() }

    fun pumpMainLooperUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
      val mainLooperShadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        mainLooperShadow.idle()
        if (condition()) return true
        Thread.sleep(25L)
        mainLooperShadow.idleFor(java.time.Duration.ofMillis(25))
      }
      mainLooperShadow.idle()
      return condition()
    }

    val viewModel = com.example.remote.ui.RemoteViewModel(app)
    viewModel.connectManualIp("127.0.0.1", serverSocket.localPort, "Living Room TV")

    assertTrue(
      "Expected initial Connected state, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )

    // Simulate switching to another app, then socket drops while backgrounded
    viewModel.onAppBackgrounded()
    releaseFirstSocketLatch.countDown()
    assertTrue(firstSocketClosedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
    pumpMainLooperUntil(timeoutMs = 250L) {
      viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Disconnected
    }
    assertTrue(
      "Background drop must not surface ConnectionStatus.Error",
      viewModel.uiState.value.connectionStatus !is com.example.remote.ui.ConnectionStatus.Error,
    )

    // Return to app -> should automatically reconnect cleanly
    viewModel.onAppForegrounded()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertTrue(secondConnectedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

    assertTrue(
      "Expected automatic reconnection on foreground return, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )

    // Explicit disconnect must prevent further foreground auto-reconnects
    viewModel.disconnect()
    viewModel.onAppBackgrounded()
    viewModel.onAppForegrounded()
    Thread.sleep(100L)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(
      com.example.remote.ui.ConnectionStatus.Disconnected,
      viewModel.uiState.value.connectionStatus,
    )

    serverThread.join(1_500L)
  }
}
