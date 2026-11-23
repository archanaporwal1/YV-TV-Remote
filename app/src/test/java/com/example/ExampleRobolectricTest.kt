package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.remote.data.AppSettings
import com.example.remote.data.RemotePreferencesStore
import com.example.remote.data.UserProfile
import com.example.remote.protocol.CertificateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        packageNames = listOf("com.tubitv"),
        nativeSchemes = listOf("tubitv://"),
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
    assertEquals(listOf("com.tubitv"), reloadedTubi.packageNames)
    assertEquals(listOf("tubitv://"), reloadedTubi.nativeSchemes)
    assertEquals(com.example.remote.data.CustomOttIconStyle.MOVIE, reloadedTubi.iconStyle)
    assertTrue(loaded.enabledShortcuts().any { it.id == "custom_tubi" })
  }

  @Test
  fun `built-in and custom OTT launch plans try native TV app schemes and package launchers before browser fallback`() {
    com.example.remote.data.TvAppShortcuts.all.forEach { shortcut ->
      val plan = com.example.remote.data.TvAppShortcuts.buildLaunchPlan(shortcut)
      assertTrue(
        "Expected native launch candidates for ${shortcut.name}",
        plan.nativeCandidates.isNotEmpty(),
      )
      assertTrue(
        "Expected verified scheme or package intent for ${shortcut.name}",
        plan.nativeCandidates.any { it.contains("LEANBACK_LAUNCHER") } &&
          plan.nativeCandidates.any { it.startsWith("market://launch?id=${shortcut.packageNames.first()}") },
      )
      assertTrue(
        "Native candidates must never trigger bare http/https/market-details links for ${shortcut.name}",
        plan.nativeCandidates.none {
          it.startsWith("http://") ||
            it.startsWith("https://") ||
            it.startsWith("market://details")
        },
      )
      assertEquals(shortcut.appLink, plan.browserFallback)
    }

    val youtubePlan =
      com.example.remote.data.TvAppShortcuts.buildLaunchPlan(
        com.example.remote.data.TvAppShortcuts.all.first { it.id == "youtube" }
      )
    assertEquals("vnd.youtube.launch://www.youtube.com", youtubePlan.nativeCandidates.first())

    val hotstarPlan =
      com.example.remote.data.TvAppShortcuts.buildLaunchPlan(
        com.example.remote.data.TvAppShortcuts.all.first { it.id == "hotstar" }
      )
    assertTrue(hotstarPlan.nativeCandidates.contains("market://launch?id=in.startv.hotstar"))

    val customNoBrowserOnlyPkg =
      com.example.remote.data.TvAppShortcut(
        id = "custom_app",
        name = "VLC",
        badge = "VLC",
        appLink = "market://details?id=org.videolan.vlc",
        accentColor = androidx.compose.ui.graphics.Color(0xFFFF8800),
        isCustom = true,
        packageNames = listOf("org.videolan.vlc"),
      )
    val customPlan = com.example.remote.data.TvAppShortcuts.buildLaunchPlan(customNoBrowserOnlyPkg)
    assertTrue(customPlan.nativeCandidates.any { it.contains("package=org.videolan.vlc") })
    assertTrue(customPlan.nativeCandidates.contains("market://launch?id=org.videolan.vlc"))
    assertEquals("https://play.google.com/store/apps/details?id=org.videolan.vlc", customPlan.browserFallback)
  }

  @Test
  fun `launchAppWithPlan opens installed native app without browser fallback, and falls back to browser only on RemoteError`() {
    kotlinx.coroutines.runBlocking {
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

      val installedReceivedLinks = java.util.Collections.synchronizedList(mutableListOf<String>())
      val missingReceivedLinks = java.util.Collections.synchronizedList(mutableListOf<String>())
      val browserFallbackReceivedLatch = java.util.concurrent.CountDownLatch(1)
      val acceptIndex = java.util.concurrent.atomic.AtomicInteger(0)

      val youtube = com.example.remote.data.TvAppShortcuts.all.first { it.id == "youtube" }
      val plan = com.example.remote.data.TvAppShortcuts.buildLaunchPlan(youtube)

      val serverThread =
        Thread {
          try {
            while (!serverSocket.isClosed && acceptIndex.get() < 2) {
              val client = serverSocket.accept() as javax.net.ssl.SSLSocket
              val connectionNum = acceptIndex.incrementAndGet()
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

              val input = client.inputStream
              while (!client.isClosed) {
                val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
                val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
                if (msg.hasRemoteAppLinkLaunchRequest()) {
                  val requestedLink = msg.remoteAppLinkLaunchRequest.appLink
                  if (connectionNum == 1) {
                    // Simulate app installed: TV accepts native candidate silently
                    installedReceivedLinks.add(requestedLink)
                  } else {
                    // Simulate app missing: reject every non-http native candidate with RemoteError
                    missingReceivedLinks.add(requestedLink)
                    if (!requestedLink.startsWith("https://")) {
                      val errorReply =
                        com.example.proto.remote.RemoteMessage.newBuilder()
                          .setRemoteError(
                            com.example.proto.remote.RemoteError.newBuilder()
                              .setValue(true)
                              .setMessage(msg)
                              .build()
                          )
                          .build()
                      com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, errorReply)
                    } else {
                      browserFallbackReceivedLatch.countDown()
                    }
                  }
                }
              }
              client.close()
            }
          } catch (_: Throwable) {
          } finally {
            try {
              serverSocket.close()
            } catch (_: Exception) {
            }
          }
        }.apply { start() }

      val client = com.example.remote.protocol.RemoteControlClient(certManager)

      // Connection 1: app IS installed -> must launch natively and NEVER send browser fallback
      client.connect("127.0.0.1", serverSocket.localPort)
      var deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline && !client.isConnectionHealthy()) {
        kotlinx.coroutines.delay(20L)
      }
      assertTrue("Expected first connection to be healthy", client.isConnectionHealthy())

      val installedOutcome = client.launchAppWithPlan(plan, errorWaitMs = 150L)
      assertEquals(com.example.remote.protocol.OttLaunchOutcome.LAUNCHED_NATIVE_APP, installedOutcome)
      assertEquals(1, installedReceivedLinks.size)
      assertEquals("vnd.youtube.launch://www.youtube.com", installedReceivedLinks.first())
      assertTrue(installedReceivedLinks.none { it.startsWith("https://") })
      assertTrue("Session must stay healthy after native launch", client.isConnectionHealthy())
      client.disconnect()

      // Connection 2: app is NOT installed -> must survive RemoteErrors and fall back to TV browser
      client.connect("127.0.0.1", serverSocket.localPort)
      deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline && !client.isConnectionHealthy()) {
        kotlinx.coroutines.delay(20L)
      }
      assertTrue("Expected second connection to be healthy", client.isConnectionHealthy())

      val fallbackOutcome = client.launchAppWithPlan(plan, errorWaitMs = 150L)
      assertEquals(com.example.remote.protocol.OttLaunchOutcome.OPENED_IN_BROWSER, fallbackOutcome)
      assertTrue(
        "Expected server to receive browser fallback URL",
        browserFallbackReceivedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
      )
      assertEquals(plan.nativeCandidates.size + 1, missingReceivedLinks.size)
      assertEquals(plan.browserFallback, missingReceivedLinks.last())
      assertTrue("Non-fatal RemoteError for missing app must keep session connected", client.isConnectionHealthy())

      client.disconnect()
      serverThread.join(1_500L)
    }
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

  @Test
  fun `keepalive ping keeps live connection healthy past stale threshold and detects unresponsive half-open TV`() {
    kotlinx.coroutines.runBlocking {
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

      val pingCount = java.util.concurrent.atomic.AtomicInteger(0)
      val stopAnsweringPings = java.util.concurrent.atomic.AtomicBoolean(false)

      val serverThread =
        Thread {
          try {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

            val input = client.inputStream
            while (!client.isClosed) {
              val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
              val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
              if (msg.hasRemotePingRequest()) {
                pingCount.incrementAndGet()
                if (!stopAnsweringPings.get()) {
                  val pong =
                    com.example.proto.remote.RemoteMessage.newBuilder()
                      .setRemotePingResponse(
                        com.example.proto.remote.RemotePingResponse.newBuilder()
                          .setVal1(msg.remotePingRequest.val1)
                          .build()
                      )
                      .build()
                  com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, pong)
                }
              }
            }
            client.close()
          } catch (_: Throwable) {
          } finally {
            try {
              serverSocket.close()
            } catch (_: Exception) {
            }
          }
        }.apply { start() }

      val client =
        com.example.remote.protocol.RemoteControlClient(
          certificateManager = certManager,
          keepaliveIntervalMs = 80L,
          socketReadTimeoutMs = 180,
          staleConnectionTimeoutMs = 350L,
        )

      client.connect("127.0.0.1", serverSocket.localPort)
      val connectDeadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < connectDeadline && !client.isConnectionHealthy()) {
        kotlinx.coroutines.delay(20L)
      }
      assertTrue("Expected Connected state", client.isConnectionHealthy())

      // Wait longer than staleConnectionTimeoutMs (350ms) while pings are answered -> must stay healthy
      kotlinx.coroutines.delay(550L)
      assertTrue("Ping-pong keepalive should keep connection alive", client.isConnectionHealthy())
      assertTrue("Expected multiple keepalive pings sent, got ${pingCount.get()}", pingCount.get() >= 3)

      // Now simulate a silent half-open TV that stops responding -> client must detect ConnectionLost
      stopAnsweringPings.set(true)
      val staleDeadline = System.currentTimeMillis() + 3_000L
      var detectedConnectionLost = false
      while (System.currentTimeMillis() < staleDeadline) {
        if (client.sessionEvent.value is com.example.remote.protocol.RemoteControlClient.SessionEvent.ConnectionLost) {
          detectedConnectionLost = true
          break
        }
        kotlinx.coroutines.delay(25L)
      }
      assertTrue(
        "Expected ConnectionLost when TV stops answering heartbeats, got ${client.sessionEvent.value}",
        detectedConnectionLost,
      )

      client.disconnect()
      serverThread.join(1_500L)
    }
  }

  @Test
  fun `viewModel automatically retries and recovers after foreground connection loss without stalling on Error`() {
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
    val recoveredLatch = java.util.concurrent.CountDownLatch(1)

    val serverThread =
      Thread {
        try {
          while (!serverSocket.isClosed && acceptCount.get() < 3) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            val attempt = acceptCount.incrementAndGet()
            client.startHandshake()
            if (attempt == 1) {
              // Initial connect succeeds then drops unexpectedly while user is still in foreground
              val configureMsg =
                com.example.proto.remote.RemoteMessage.newBuilder()
                  .setRemoteConfigure(
                    com.example.proto.remote.RemoteConfigure.newBuilder()
                      .setCode1(622)
                      .build()
                  )
                  .build()
              com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)
              Thread.sleep(250L)
              client.close()
            } else if (attempt == 2) {
              // First reconnect attempt fails with a transient RemoteError -> verifies Error retry path
              val errMsg =
                com.example.proto.remote.RemoteMessage.newBuilder()
                  .setRemoteError(
                    com.example.proto.remote.RemoteError.newBuilder()
                      .setValue(true)
                      .build()
                  )
                  .build()
              com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, errMsg)
              Thread.sleep(50L)
              client.close()
            } else {
              // Subsequent reconnect attempt succeeds
              val configureMsg =
                com.example.proto.remote.RemoteMessage.newBuilder()
                  .setRemoteConfigure(
                    com.example.proto.remote.RemoteConfigure.newBuilder()
                      .setCode1(622)
                      .build()
                  )
                  .build()
              com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)
              recoveredLatch.countDown()
              Thread.sleep(600L)
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

    fun pumpMainLooperUntil(timeoutMs: Long = 8_000L, condition: () -> Boolean): Boolean {
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
      "Expected recovery after foreground disconnect & transient failure (attempts=${acceptCount.get()}, status=${viewModel.uiState.value.connectionStatus})",
      pumpMainLooperUntil {
        recoveredLatch.count == 0L &&
          viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )

    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `handshake sends RemoteConfigure once on TV configure and RemoteSetActive once on TV setActive`() {
    kotlinx.coroutines.runBlocking {
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

      val configureCount = java.util.concurrent.atomic.AtomicInteger(0)
      val configureFeatureCode = java.util.concurrent.atomic.AtomicInteger(0)
      val setActiveBeforeServerSetActive = java.util.concurrent.atomic.AtomicInteger(0)
      val totalSetActiveCount = java.util.concurrent.atomic.AtomicInteger(0)
      val firstConfigureLatch = java.util.concurrent.CountDownLatch(1)
      val setActiveLatch = java.util.concurrent.CountDownLatch(1)

      val serverThread =
        Thread {
          try {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

            val input = client.inputStream
            var sentServerSetActive = false
            while (!client.isClosed) {
              val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
              val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
              when {
                msg.hasRemoteConfigure() -> {
                  configureCount.incrementAndGet()
                  configureFeatureCode.set(msg.remoteConfigure.code1)
                  firstConfigureLatch.countDown()
                  if (!sentServerSetActive) {
                    sentServerSetActive = true
                    Thread.sleep(60L)
                    val setActiveMsg =
                      com.example.proto.remote.RemoteMessage.newBuilder()
                        .setRemoteSetActive(
                          com.example.proto.remote.RemoteSetActive.newBuilder()
                            .setActive(622)
                            .build()
                        )
                        .build()
                    com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, setActiveMsg)
                  }
                }
                msg.hasRemoteSetActive() -> {
                  if (!sentServerSetActive) {
                    setActiveBeforeServerSetActive.incrementAndGet()
                  }
                  totalSetActiveCount.incrementAndGet()
                  setActiveLatch.countDown()
                }
              }
            }
            client.close()
          } catch (_: Throwable) {
          } finally {
            try {
              serverSocket.close()
            } catch (_: Exception) {
            }
          }
        }.apply { start() }

      val client = com.example.remote.protocol.RemoteControlClient(certManager)
      client.connect("127.0.0.1", serverSocket.localPort)

      assertTrue(firstConfigureLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue(setActiveLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
      kotlinx.coroutines.delay(100L)

      assertEquals(1, configureCount.get())
      assertEquals(622, configureFeatureCode.get())
      assertEquals(0, setActiveBeforeServerSetActive.get())
      assertEquals(1, totalSetActiveCount.get())
      assertTrue(client.isConnectionHealthy())

      client.disconnect()
      serverThread.join(1_500L)
    }
  }

  @Test
  fun `session stays connected with server-initiated pings and un-echoed RemoteError during OTT launch`() {
    kotlinx.coroutines.runBlocking {
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

      val serverPongsReceived = java.util.concurrent.atomic.AtomicInteger(0)
      val unsolicitedClientPings = java.util.concurrent.atomic.AtomicInteger(0)
      val receivedAppLinks = java.util.Collections.synchronizedList(mutableListOf<String>())
      val browserFallbackReceivedLatch = java.util.concurrent.CountDownLatch(1)
      val netflix = com.example.remote.data.TvAppShortcuts.all.first { it.id == "netflix" }
      val netflixPlan = com.example.remote.data.TvAppShortcuts.buildLaunchPlan(netflix)

      val serverThread =
        Thread {
          try {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

            val pinger =
              Thread {
                try {
                  var seq = 1
                  while (!client.isClosed) {
                    Thread.sleep(60L)
                    val serverPing =
                      com.example.proto.remote.RemoteMessage.newBuilder()
                        .setRemotePingRequest(
                          com.example.proto.remote.RemotePingRequest.newBuilder()
                            .setVal1(seq++)
                            .build()
                        )
                        .build()
                    com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, serverPing)
                  }
                } catch (_: Throwable) {
                }
              }.apply { start() }

            val input = client.inputStream
            while (!client.isClosed) {
              val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
              val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
              when {
                msg.hasRemotePingResponse() -> {
                  serverPongsReceived.incrementAndGet()
                }
                msg.hasRemotePingRequest() -> {
                  unsolicitedClientPings.incrementAndGet()
                }
                msg.hasRemoteAppLinkLaunchRequest() -> {
                  val link = msg.remoteAppLinkLaunchRequest.appLink
                  receivedAppLinks.add(link)
                  if (!link.startsWith("https://")) {
                    // Reject without echoing remoteAppLinkLaunchRequest in error.message
                    val unEchoedErr =
                      com.example.proto.remote.RemoteMessage.newBuilder()
                        .setRemoteError(
                          com.example.proto.remote.RemoteError.newBuilder()
                            .setValue(true)
                            .build()
                        )
                        .build()
                    com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, unEchoedErr)
                  } else {
                    browserFallbackReceivedLatch.countDown()
                  }
                }
              }
            }
            client.close()
            pinger.join(500L)
          } catch (_: Throwable) {
          } finally {
            try {
              serverSocket.close()
            } catch (_: Exception) {
            }
          }
        }.apply { start() }

      val client =
        com.example.remote.protocol.RemoteControlClient(
          certificateManager = certManager,
          keepaliveIntervalMs = 180L,
          socketReadTimeoutMs = 150,
          staleConnectionTimeoutMs = 300L,
        )
      client.connect("127.0.0.1", serverSocket.localPort)
      val deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline && !client.isConnectionHealthy()) {
        kotlinx.coroutines.delay(20L)
      }
      assertTrue(client.isConnectionHealthy())

      // Wait past staleConnectionTimeoutMs while TV drives pings; client must echo pongs and send 0 unsolicited pings
      kotlinx.coroutines.delay(500L)
      assertTrue(
        "Server-initiated ping TVs must stay connected without unsolicited client pings",
        client.isConnectionHealthy(),
      )
      assertTrue("Expected server ping responses, got ${serverPongsReceived.get()}", serverPongsReceived.get() >= 2)
      assertEquals(
        "Client must not send unsolicited RemotePingRequest after TV initiates pings",
        0,
        unsolicitedClientPings.get(),
      )

      // Now simulate clicking Netflix when TV rejects every native probe with an UN-ECHOED RemoteError
      val outcome = client.launchAppWithPlan(netflixPlan, errorWaitMs = 120L)
      assertEquals(com.example.remote.protocol.OttLaunchOutcome.OPENED_IN_BROWSER, outcome)
      assertTrue(
        "Expected server to receive browser fallback URL",
        browserFallbackReceivedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
      )
      assertEquals(netflixPlan.nativeCandidates.size + 1, receivedAppLinks.size)
      assertEquals(netflixPlan.browserFallback, receivedAppLinks.last())
      assertTrue("Un-echoed RemoteError must NOT tear down TLS session", client.isConnectionHealthy())

      client.disconnect()
      serverThread.join(1_500L)
    }
  }

  @Test
  fun `viewModel pauses auto-reconnect after 3 rapid drops and recovers when user taps retry`() {
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
    val keepFourthAlive = java.util.concurrent.CountDownLatch(1)

    val serverThread =
      Thread {
        try {
          while (!serverSocket.isClosed && acceptCount.get() < 4) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            val num = acceptCount.incrementAndGet()
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
            if (num <= 3) {
              // Simulate rapid drop immediately after connecting
              Thread.sleep(70L)
              client.close()
            } else {
              // Manual Retry succeeds and stays open
              keepFourthAlive.await(4, java.util.concurrent.TimeUnit.SECONDS)
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

    fun pumpMainLooperUntil(timeoutMs: Long = 8_000L, condition: () -> Boolean): Boolean {
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
    viewModel.connectManualIp("127.0.0.1", serverSocket.localPort, "Bedroom TV")

    assertTrue(
      "Expected rapid-drop circuit breaker Error after 3 drops, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        val status = viewModel.uiState.value.connectionStatus
        status is com.example.remote.ui.ConnectionStatus.Error && status.isRapidDropLoop
      },
    )
    assertEquals(3, acceptCount.get())

    // Foreground event must respect paused state until user acts
    viewModel.onAppForegrounded()
    Thread.sleep(150L)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertEquals(3, acceptCount.get())

    // Explicit Retry clears the circuit breaker and reconnects cleanly
    viewModel.retryCurrentTarget()
    assertTrue(
      "Expected Connected after retryCurrentTarget(), got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )
    assertEquals(4, acceptCount.get())

    keepFourthAlive.countDown()
    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `viewModel onKeyHoldStart and onKeyHoldEnd emit START_LONG and END_LONG key events`() {
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

    val receivedKeys =
      java.util.Collections.synchronizedList(
        mutableListOf<Pair<com.example.proto.remote.RemoteKeyCode, com.example.proto.remote.RemoteDirection>>()
      )
    val bothReceivedLatch = java.util.concurrent.CountDownLatch(2)

    val serverThread =
      Thread {
        try {
          val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

          val input = client.inputStream
          while (!client.isClosed) {
            val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
            val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
            if (msg.hasRemoteKeyInject()) {
              val inject = msg.remoteKeyInject
              receivedKeys.add(inject.keyCode to inject.direction)
              bothReceivedLatch.countDown()
            }
          }
          client.close()
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
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      }
    )

    viewModel.onKeyHoldStart(com.example.proto.remote.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD)
    Thread.sleep(60L)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    viewModel.onKeyHoldEnd(com.example.proto.remote.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD)

    assertTrue(
      "Expected START_LONG and END_LONG key events received, got $receivedKeys",
      bothReceivedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS),
    )
    assertEquals(
      com.example.proto.remote.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD to com.example.proto.remote.RemoteDirection.START_LONG,
      receivedKeys[0],
    )
    assertEquals(
      com.example.proto.remote.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD to com.example.proto.remote.RemoteDirection.END_LONG,
      receivedKeys[1],
    )

    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `viewModel executes spoken TV key, TV assistant, and voice protobuf streaming cleanly`() {
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

    val receivedKeyCodes =
      java.util.Collections.synchronizedList(mutableListOf<com.example.proto.remote.RemoteKeyCode>())
    val assistantAndKeysLatch = java.util.concurrent.CountDownLatch(3)

    val serverThread =
      Thread {
        try {
          val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

          val input = client.inputStream
          while (!client.isClosed) {
            val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
            val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
            if (msg.hasRemoteKeyInject()) {
              receivedKeyCodes.add(msg.remoteKeyInject.keyCode)
              assistantAndKeysLatch.countDown()
            }
          }
          client.close()
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
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      }
    )

    viewModel.onVoiceFinalResult("volume up")
    viewModel.triggerTvVoiceAssistant()
    viewModel.onVoiceFinalResult("search up")
    assertTrue(
      "Expected voice key and assistant commands to arrive, got $receivedKeyCodes",
      assistantAndKeysLatch.await(4, java.util.concurrent.TimeUnit.SECONDS),
    )
    assertTrue(receivedKeyCodes.contains(com.example.proto.remote.RemoteKeyCode.KEYCODE_VOLUME_UP))
    assertTrue(receivedKeyCodes.contains(com.example.proto.remote.RemoteKeyCode.KEYCODE_VOICE_ASSIST))
    assertTrue(receivedKeyCodes.contains(com.example.proto.remote.RemoteKeyCode.KEYCODE_SEARCH))
    assertEquals("search up", viewModel.uiState.value.voiceState.lastTranscript)

    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `fallbackToTvVoiceAssistant sends KEYCODE_VOICE_ASSIST and speech controller and feedback manager never throw`() {
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

    val voiceAssistLatch = java.util.concurrent.CountDownLatch(1)
    val serverThread =
      Thread {
        try {
          val client = serverSocket.accept() as javax.net.ssl.SSLSocket
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

          val input = client.inputStream
          while (!client.isClosed) {
            val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
            val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
            if (msg.hasRemoteKeyInject() &&
              msg.remoteKeyInject.keyCode == com.example.proto.remote.RemoteKeyCode.KEYCODE_VOICE_ASSIST
            ) {
              voiceAssistLatch.countDown()
            }
          }
          client.close()
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
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      }
    )

    viewModel.fallbackToTvVoiceAssistant("Speech recognition service unavailable")
    assertTrue(
      "Expected KEYCODE_VOICE_ASSIST dispatched on automatic fallback",
      voiceAssistLatch.await(3, java.util.concurrent.TimeUnit.SECONDS),
    )
    assertEquals(
      "Sent directly to TV Assistant",
      viewModel.uiState.value.voiceState.executedActionLabel,
    )
    assertEquals(false, viewModel.uiState.value.voiceState.isListening)
    assertEquals(
      "Speech recognition service unavailable",
      viewModel.uiState.value.voiceState.errorMessage,
    )

    // FeedbackManager rapid triggers + release + post-release trigger must never crash
    val feedback = com.example.remote.feedback.FeedbackManager(app)
    val feedbackSettings = AppSettings(hapticsEnabled = true, soundEnabled = true, soundVolume = 0.8f)
    repeat(10) {
      feedback.trigger(com.example.remote.feedback.FeedbackCue.NAV_TICK, feedbackSettings)
    }
    feedback.release()
    feedback.trigger(com.example.remote.feedback.FeedbackCue.CONFIRM_CHIME, feedbackSettings)

    // AndroidSpeechRecognizerController safely reports error without crashing when unavailable
    var reportedError: String? = null
    val controller =
      com.example.remote.voice.AndroidSpeechRecognizerController(
        context = app,
        onReady = {},
        onRmsChanged = {},
        onPartialResult = {},
        onFinalResult = {},
        onError = { reportedError = it },
      )
    controller.startListening()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    controller.stopListening()
    controller.destroy()
    assertTrue("Expected safe error message or unavailable notice", reportedError == null || reportedError!!.isNotBlank())

    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `viewModel proactively heals stale background socket on foreground wake without showing error`() {
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
    val secondSessionLatch = java.util.concurrent.CountDownLatch(1)
    val shutdownLatch = java.util.concurrent.CountDownLatch(1)
    val acceptedSockets = java.util.Collections.synchronizedList(mutableListOf<javax.net.ssl.SSLSocket>())

    val serverThread =
      Thread {
        try {
          while (!serverSocket.isClosed && acceptCount.get() < 2) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            acceptedSockets.add(client)
            val num = acceptCount.incrementAndGet()
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
            if (num == 2) {
              secondSessionLatch.countDown()
            }
          }
          shutdownLatch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
          acceptedSockets.forEach { sock ->
            try {
              sock.close()
            } catch (_: Exception) {
            }
          }
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
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      }
    )
    assertEquals(1, acceptCount.get())

    // Simulate switching apps / screen lock while first socket stays silently open (half-open zombie)
    viewModel.onAppBackgrounded()
    viewModel.markBackgroundedAtForTest(
      System.currentTimeMillis() - com.example.remote.ui.RemoteViewModel.BACKGROUND_PROACTIVE_RECONNECT_MS - 1_000L
    )

    // Returning to foreground must proactively heal into a fresh TLS session cleanly without Error state
    viewModel.onAppForegrounded()
    assertTrue(
      "Expected proactive foreground wake heal to establish a fresh session (status=${viewModel.uiState.value.connectionStatus}, accepts=${acceptCount.get()})",
      pumpMainLooperUntil { secondSessionLatch.count == 0L },
    )
    assertTrue(
      "Expected Connected after proactive wake heal, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      }
    )
    assertEquals(2, acceptCount.get())

    shutdownLatch.countDown()
    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `OttBrandResolver resolves all default and Indian plus global catalog OTT apps and auto-matches custom entries`() {
    val allCatalogShortcuts =
      com.example.remote.data.TvAppShortcuts.all + com.example.remote.data.TvAppShortcuts.popularAddableCatalog

    // All 9 default apps + all 22 catalog apps must resolve to a known official brand key
    assertEquals(9, com.example.remote.data.TvAppShortcuts.all.size)
    assertTrue(com.example.remote.data.TvAppShortcuts.popularAddableCatalog.size >= 22)

    allCatalogShortcuts.forEach { shortcut ->
      val resolved = com.example.remote.data.OttBrandResolver.resolveBrandKey(shortcut)
      assertTrue(
        "Expected official brand key for ${shortcut.name} (${shortcut.id}), got null",
        resolved != null && resolved in com.example.remote.data.OttBrandResolver.allKnownBrandKeys,
      )
      val plan = com.example.remote.data.TvAppShortcuts.buildLaunchPlan(shortcut)
      assertTrue("Expected native launch candidates for ${shortcut.name}", plan.nativeCandidates.isNotEmpty())
      assertTrue(
        "Expected market://launch for ${shortcut.name}",
        plan.nativeCandidates.any { it.startsWith("market://launch?id=${shortcut.packageNames.first()}") },
      )
    }

    // Updated JioHotstar default keeps backward-compatible id="hotstar"
    val hotstar = com.example.remote.data.TvAppShortcuts.all.first { it.id == "hotstar" }
    assertEquals("JioHotstar", hotstar.name)
    assertEquals("hotstar", com.example.remote.data.OttBrandResolver.resolveBrandKey(hotstar))

    // Auto-match by URL domain for custom shortcuts
    assertEquals(
      "hotstar",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_1",
        appLink = "https://www.jiohotstar.com/in",
        name = "My Cricket App",
      ),
    )
    assertEquals(
      "airtel_xstream",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_2",
        appLink = "https://airtelxstream.in",
        name = "Custom Stream",
      ),
    )
    assertEquals(
      "tata_play_binge",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_3",
        appLink = "https://www.tataskybinge.com",
        name = "Binge Box",
      ),
    )

    // Auto-match by Android TV package ID (explicit list and market://details link)
    assertEquals(
      "sonyliv",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_4",
        packageNames = listOf("com.sonyliv"),
        name = "Sports TV",
      ),
    )
    assertEquals(
      "tata_play_binge",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_5",
        appLink = "market://details?id=com.tatasky.binge",
        name = "Binge",
      ),
    )

    // Auto-match by normalized display name aliases
    assertEquals(
      "hotstar",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(id = "custom_6", name = "Disney+ Hotstar"),
    )
    assertEquals(
      "prime_video",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(id = "custom_7", name = "Amazon Prime Video"),
    )
    assertEquals(
      "max",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(id = "custom_8", name = "HBO Max"),
    )
    assertEquals(
      "manoramamax",
      com.example.remote.data.OttBrandResolver.resolveBrandKey(id = "custom_9", name = "Manorama Max"),
    )

    // Unknown custom app gracefully falls back to null (uses custom monogram/symbol)
    assertEquals(
      null,
      com.example.remote.data.OttBrandResolver.resolveBrandKey(
        id = "custom_local_media",
        appLink = "https://my-local-cinema.example",
        packageNames = listOf("com.example.unknownplayer"),
        name = "Living Room Cinema",
      ),
    )
  }

  @Test
  fun `VoiceCommandParser resolves JioHotstar and expanded Indian OTT commands`() {
    val jioHotstarCmd = com.example.remote.voice.VoiceCommandParser.parse("open jiohotstar")
    assertTrue(jioHotstarCmd is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals("hotstar", (jioHotstarCmd as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id)

    val legacyHotstarCmd = com.example.remote.voice.VoiceCommandParser.parse("open disney+ hotstar")
    assertTrue(legacyHotstarCmd is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals("hotstar", (legacyHotstarCmd as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id)

    val airtelCmd = com.example.remote.voice.VoiceCommandParser.parse("launch airtel xstream")
    assertTrue(airtelCmd is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals("airtel_xstream", (airtelCmd as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id)

    val tataBingeCmd = com.example.remote.voice.VoiceCommandParser.parse("open tata play binge")
    assertTrue(tataBingeCmd is com.example.remote.voice.ParsedVoiceCommand.OpenOttApp)
    assertEquals("tata_play_binge", (tataBingeCmd as com.example.remote.voice.ParsedVoiceCommand.OpenOttApp).shortcut.id)
  }

  @Test
  fun `zero-setup TV app auto-sync filters system apps, promotes known OTT brands, and respects user removal`() {
    // System / launcher / keyboard packages must never pollute the remote
    assertFalse(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.google.android.tvlauncher"))
    assertFalse(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.google.android.apps.tv.launcherx"))
    assertFalse(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.android.tv.settings"))
    assertFalse(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.google.android.inputmethod.latin"))
    assertFalse(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.google.android.katniss"))

    // Known OTT packages and third-party TV apps must be allowed
    assertTrue(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("com.google.android.youtube.tv"))
    assertTrue(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("tv.accedo.airtel.wynk"))
    assertTrue(com.example.remote.data.TvDetectedAppSync.isSyncableUserApp("org.videolan.vlc"))

    // Catalog promotion: Airtel Xstream package becomes full official preset
    val airtel = com.example.remote.data.TvDetectedAppSync.toShortcut("tv.accedo.airtel.wynk", "Airtel Xstream")
    assertEquals("airtel_xstream", airtel.id)
    assertEquals("Airtel Xstream", airtel.name)
    assertEquals("airtel_xstream", com.example.remote.data.OttBrandResolver.resolveBrandKey(airtel))

    // Unknown third-party TV app gets clean launchable shortcut
    val vlc = com.example.remote.data.TvDetectedAppSync.toShortcut("org.videolan.vlc", "VLC")
    assertEquals("VLC", vlc.name)
    assertEquals("market://details?id=org.videolan.vlc", vlc.appLink)
    assertEquals(listOf("org.videolan.vlc"), vlc.packageNames)

    // End-to-end ViewModel zero-setup auto-sync & dismissal respect
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.remote.ui.RemoteViewModel(app)

    // Detecting Airtel Xstream on TV automatically adds it and marks it verified on TV
    viewModel.handleDetectedTvApp("tv.accedo.airtel.wynk", "Airtel Xstream", tvKeyOverride = "192.168.1.50")
    assertTrue(viewModel.uiState.value.uiPreferences.enabledShortcutIds.contains("airtel_xstream"))
    assertTrue(viewModel.uiState.value.verifiedOnTvShortcutIds.contains("airtel_xstream"))
    assertTrue(viewModel.uiState.value.detectedTvShortcuts.any { it.id == "airtel_xstream" })

    // System launcher event is silently ignored
    val countBefore = viewModel.uiState.value.detectedTvShortcuts.size
    viewModel.handleDetectedTvApp("com.google.android.tvlauncher", "Android TV Home", tvKeyOverride = "192.168.1.50")
    assertEquals(countBefore, viewModel.uiState.value.detectedTvShortcuts.size)

    // If user removes an auto-synced app, re-detecting it must NOT forcefully re-add it
    viewModel.deleteCustomOttShortcut("airtel_xstream")
    assertFalse(viewModel.uiState.value.uiPreferences.enabledShortcutIds.contains("airtel_xstream"))
    viewModel.handleDetectedTvApp("tv.accedo.airtel.wynk", "Airtel Xstream", tvKeyOverride = "192.168.1.50")
    assertFalse(viewModel.uiState.value.uiPreferences.enabledShortcutIds.contains("airtel_xstream"))
    // Still remains available in detectedTvShortcuts for 1-tap re-add in Add OTT dialog
    assertTrue(viewModel.uiState.value.detectedTvShortcuts.any { it.id == "airtel_xstream" })
  }

  @Test
  fun `multiple devices generate unique client certificate CNs so TV never overwrites paired certs`() {
    val baseApp = ApplicationProvider.getApplicationContext<android.app.Application>()
    val dirA = java.io.File(baseApp.cacheDir, "device_a_${System.nanoTime()}").apply { mkdirs() }
    val dirB = java.io.File(baseApp.cacheDir, "device_b_${System.nanoTime()}").apply { mkdirs() }
    val ctxA = object : android.content.ContextWrapper(baseApp) {
      override fun getFilesDir(): java.io.File = dirA
    }
    val ctxB = object : android.content.ContextWrapper(baseApp) {
      override fun getFilesDir(): java.io.File = dirB
    }
    try {
      val mgrA = CertificateManager(ctxA)
      val mgrB = CertificateManager(ctxB)
      val certA = mgrA.ensureUniqueIdentityForPairing().getCertificate(CertificateManager.KEY_ALIAS) as java.security.cert.X509Certificate
      val certB = mgrB.ensureUniqueIdentityForPairing().getCertificate(CertificateManager.KEY_ALIAS) as java.security.cert.X509Certificate

      val tagA = mgrA.getClientDeviceTag()
      val tagB = mgrB.getClientDeviceTag()
      assertEquals(8, tagA.length)
      assertEquals(8, tagB.length)
      assertTrue("Expected distinct per-device certificate tags, got $tagA vs $tagB", tagA != tagB)
      assertTrue(certA.subjectX500Principal.name.contains("YV-TV Remote-$tagA"))
      assertTrue(certB.subjectX500Principal.name.contains("YV-TV Remote-$tagB"))
      assertTrue(certA.subjectX500Principal.name != certB.subjectX500Principal.name)

      // Calling ensureUniqueIdentityForPairing again must keep the same identity (no forced re-pair)
      val certA2 = mgrA.ensureUniqueIdentityForPairing().getCertificate(CertificateManager.KEY_ALIAS) as java.security.cert.X509Certificate
      assertEquals(certA.serialNumber, certA2.serialNumber)
    } finally {
      dirA.deleteRecursively()
      dirB.deleteRecursively()
    }
  }

  @Test
  fun `pressing any remote key after multi-device pause auto-reconnects silently without pairing prompt and delivers command`() {
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
        .apply { needClientAuth = false }

    val acceptCount = java.util.concurrent.atomic.AtomicInteger(0)
    val keyReceivedLatch = java.util.concurrent.CountDownLatch(1)
    val receivedKey = java.util.concurrent.atomic.AtomicReference<com.example.proto.remote.RemoteKeyCode?>()

    val serverThread =
      Thread {
        try {
          // Simulate 3 rapid multi-device evictions to trigger background auto-reconnect pause
          repeat(3) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            acceptCount.incrementAndGet()
            client.startHandshake()
            val configureMsg =
              com.example.proto.remote.RemoteMessage.newBuilder()
                .setRemoteConfigure(
                  com.example.proto.remote.RemoteConfigure.newBuilder().setCode1(622).build()
                )
                .build()
            com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)
            Thread.sleep(60L)
            client.close()
          }
          // 4th connection stays alive and expects the key press command immediately
          val client = serverSocket.accept() as javax.net.ssl.SSLSocket
          acceptCount.incrementAndGet()
          client.startHandshake()
          val configureMsg =
            com.example.proto.remote.RemoteMessage.newBuilder()
              .setRemoteConfigure(
                com.example.proto.remote.RemoteConfigure.newBuilder().setCode1(622).build()
              )
              .build()
          com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)
          val input = client.inputStream
          while (!client.isClosed) {
            val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
            val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
            if (msg.hasRemoteKeyInject()) {
              receivedKey.set(msg.remoteKeyInject.keyCode)
              keyReceivedLatch.countDown()
              break
            }
          }
          client.close()
        } catch (_: Throwable) {
        } finally {
          try {
            serverSocket.close()
          } catch (_: Exception) {
          }
        }
      }.apply { start() }

    fun pumpMainLooperUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean): Boolean {
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
    viewModel.connectManualIp("127.0.0.1", serverSocket.localPort, "Shared Family TV")

    assertTrue(
      "Expected background pause after 3 rapid drops, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        val status = viewModel.uiState.value.connectionStatus
        status is com.example.remote.ui.ConnectionStatus.Error && status.isRapidDropLoop
      },
    )

    // User presses Volume Up directly while paused — must silently reconnect & execute key WITHOUT pairing!
    viewModel.sendKey(com.example.proto.remote.RemoteKeyCode.KEYCODE_VOLUME_UP)

    assertTrue(
      "Expected Connected after pressing key from paused state, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )
    assertFalse(
      "Must never ask for pairing code again on an already-paired TV",
      viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Pairing,
    )
    assertTrue(
      "Expected KEYCODE_VOLUME_UP delivered to TV after auto-reconnect",
      keyReceivedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS),
    )
    assertEquals(com.example.proto.remote.RemoteKeyCode.KEYCODE_VOLUME_UP, receivedKey.get())
    assertEquals(4, acceptCount.get())

    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `in-app search button and TV IME search request both automatically show keyboard sheet`() {
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
        .apply { needClientAuth = false }

    val sendImeRequestLatch = java.util.concurrent.CountDownLatch(1)
    val searchKeyReceivedLatch = java.util.concurrent.CountDownLatch(1)
    val keepAliveLatch = java.util.concurrent.CountDownLatch(1)

    val serverThread =
      Thread {
        try {
          val client = serverSocket.accept() as javax.net.ssl.SSLSocket
          client.startHandshake()
          val configureMsg =
            com.example.proto.remote.RemoteMessage.newBuilder()
              .setRemoteConfigure(
                com.example.proto.remote.RemoteConfigure.newBuilder().setCode1(622).build()
              )
              .build()
          com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, configureMsg)

          val reader =
            Thread {
              try {
                val input = client.inputStream
                while (!client.isClosed) {
                  val frame = com.example.remote.protocol.DelimitedStreamHelper.readDelimitedBytes(input) ?: break
                  val msg = com.example.proto.remote.RemoteMessage.parseFrom(frame)
                  if (msg.hasRemoteKeyInject() &&
                    msg.remoteKeyInject.keyCode == com.example.proto.remote.RemoteKeyCode.KEYCODE_SEARCH
                  ) {
                    searchKeyReceivedLatch.countDown()
                  }
                }
              } catch (_: Throwable) {
              }
            }.apply { start() }

          if (sendImeRequestLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            val imeAppMsg =
              com.example.proto.remote.RemoteMessage.newBuilder()
                .setRemoteImeKeyInject(
                  com.example.proto.remote.RemoteImeKeyInject.newBuilder()
                    .setAppInfo(
                      com.example.proto.remote.RemoteAppInfo.newBuilder()
                        .setCounter(1)
                        .setAppPackage("com.google.android.youtube.tv")
                        .setLabel("YouTube")
                        .build()
                    )
                    .setTextFieldStatus(
                      com.example.proto.remote.RemoteTextFieldStatus.newBuilder()
                        .setCounterField(1)
                        .setValue("")
                        .setLabel("Search YouTube")
                        .build()
                    )
                    .build()
                )
                .build()
            com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, imeAppMsg)

            val imeShowMsg =
              com.example.proto.remote.RemoteMessage.newBuilder()
                .setRemoteImeShowRequest(
                  com.example.proto.remote.RemoteImeShowRequest.newBuilder()
                    .setRemoteTextFieldStatus(
                      com.example.proto.remote.RemoteTextFieldStatus.newBuilder()
                        .setCounterField(1)
                        .setValue("")
                        .setLabel("Search YouTube")
                        .build()
                    )
                    .build()
                )
                .build()
            com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, imeShowMsg)
          }

          keepAliveLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
          client.close()
          reader.join(500L)
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
      pumpMainLooperUntil {
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected &&
          !viewModel.uiState.value.showConnectionHub
      },
    )

    // 1) Clicking the Search icon in YV-TV Remote opens keyboard immediately and sends KEYCODE_SEARCH
    assertFalse(viewModel.uiState.value.showKeyboardSheet)
    viewModel.openSearchWithKeyboard()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertTrue("Expected keyboard sheet open on search icon click", viewModel.uiState.value.showKeyboardSheet)
    assertTrue(
      "Expected KEYCODE_SEARCH sent to TV",
      searchKeyReceivedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
    )

    // Dismiss keyboard sheet
    viewModel.openKeyboardSheet(false)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertFalse(viewModel.uiState.value.showKeyboardSheet)

    // 2) Clicking a search box inside a TV app triggers RemoteImeShowRequest -> auto-opens keyboard sheet!
    sendImeRequestLatch.countDown()
    assertTrue(
      "Expected keyboard sheet to auto-open on TV RemoteImeShowRequest",
      pumpMainLooperUntil {
        viewModel.uiState.value.tvImeState.isImeRequested && viewModel.uiState.value.showKeyboardSheet
      },
    )
    assertEquals("YouTube", viewModel.uiState.value.tvImeState.appLabel)

    keepAliveLatch.countDown()
    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }

  @Test
  fun `ssl credentials DataStore encrypts payloads at rest, persists paired TV credentials across reloads, and updates DHCP IP`() {
    kotlinx.coroutines.runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val store1 = com.example.remote.data.SslCredentialsDataStore(context)
      store1.clearAll()

      val fakePkcs12 = "PRIVATE_KEY_MATERIAL_BYTES_0123456789".toByteArray()
      val identity =
        com.example.remote.data.StoredDeviceSslIdentity(
          deviceTag = "A1B2C3D4",
          subjectCn = "CN=YV-TV Remote-A1B2C3D4, O=YourView, OU=yourview.in",
          clientCertFingerprintSha256 = "11223344556677889900AABBCCDDEEFF11223344556677889900AABBCCDDEEFF",
          pkcs12Bytes = fakePkcs12,
          createdAtMs = 1_700_000_000_000L,
        )
      store1.saveDeviceIdentity(identity)

      val pairedCred =
        com.example.remote.data.PairedTvSslCredential(
          tvKey = "192.168.1.42",
          ipAddress = "192.168.1.42",
          controlPort = 6466,
          serverName = "Living Room OLED TV",
          serverCertFingerprintSha256 = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
          clientDeviceTag = "A1B2C3D4",
          clientCertFingerprintSha256 = identity.clientCertFingerprintSha256,
          pairingTokenHash = "SECRET_PAIRING_TOKEN_HASH_XYZ987",
          pkcs12Bytes = fakePkcs12,
          pairedAtMs = 1_700_000_001_000L,
          lastConnectedAtMs = 1_700_000_001_000L,
        )
      store1.savePairedCredential(pairedCred)

      // Raw DataStore file on disk MUST be AES-GCM encrypted (no plaintext secrets or IP/names)
      assertTrue("Expected DataStore file to exist on disk", store1.dataStoreFile.exists())
      val rawDiskText = store1.dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
      assertFalse("Raw DataStore must NOT contain plaintext private key bytes", rawDiskText.contains("PRIVATE_KEY_MATERIAL"))
      assertFalse("Raw DataStore must NOT contain plaintext pairing token", rawDiskText.contains("SECRET_PAIRING_TOKEN_HASH_XYZ987"))
      assertFalse("Raw DataStore must NOT contain plaintext TV name", rawDiskText.contains("Living Room OLED TV"))

      // Fresh instance reloads exact encrypted identity & paired TV credentials
      val store2 = com.example.remote.data.SslCredentialsDataStore(context)
      val loadedIdentity = store2.getDeviceIdentity()
      assertNotNull(loadedIdentity)
      assertEquals("A1B2C3D4", loadedIdentity!!.deviceTag)
      assertTrue(fakePkcs12.contentEquals(loadedIdentity.pkcs12Bytes))

      val byHost = store2.getPairedCredentialByHost("192.168.1.42")
      assertNotNull(byHost)
      assertEquals("Living Room OLED TV", byHost!!.serverName)
      assertEquals("SECRET_PAIRING_TOKEN_HASH_XYZ987", byHost.pairingTokenHash)
      assertTrue(fakePkcs12.contentEquals(byHost.pkcs12Bytes))

      val byFingerprint =
        store2.getPairedCredentialByServerFingerprint(pairedCred.serverCertFingerprintSha256)
      assertNotNull(byFingerprint)
      assertEquals("192.168.1.42", byFingerprint!!.ipAddress)

      // DHCP IP migration preserves paired credentials under the TV's new IP
      store2.updatePairedHostAddress("192.168.1.42", "192.168.1.99", 6466)
      org.junit.Assert.assertNull(store2.getPairedCredentialByHost("192.168.1.42"))
      val migrated = store2.getPairedCredentialByHost("192.168.1.99")
      assertNotNull(migrated)
      assertEquals("SECRET_PAIRING_TOKEN_HASH_XYZ987", migrated!!.pairingTokenHash)
      assertTrue(store2.hasPairedCredential("192.168.1.99", pairedCred.serverCertFingerprintSha256))
    }
  }

  @Test
  fun `certificate manager migrates legacy pkcs12 file into encrypted DataStore without losing identity`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = com.example.remote.data.SslCredentialsDataStore(context)
    kotlinx.coroutines.runBlocking { store.clearAll() }

    // Create a valid identity, export it to the legacy .p12 path, then clear DataStore
    val seedManager = CertificateManager(context, store)
    val seedKeyStore = seedManager.ensureClientIdentity()
    val expectedPubKey = seedManager.getClientRsaPublicKey()
    val legacyFile = java.io.File(context.filesDir, CertificateManager.KEYSTORE_FILE_NAME)
    java.io.FileOutputStream(legacyFile).use { out ->
      seedKeyStore.store(out, "yvtv_remote_keystore_pass".toCharArray())
    }
    assertTrue(legacyFile.exists())
    kotlinx.coroutines.runBlocking { store.clearAll() }

    // Fresh CertificateManager must migrate legacy .p12 into encrypted DataStore and delete legacy file
    val migratedManager = CertificateManager(context, store)
    val migratedPubKey = migratedManager.getClientRsaPublicKey()
    assertEquals(expectedPubKey.modulus, migratedPubKey.modulus)
    assertEquals(expectedPubKey.publicExponent, migratedPubKey.publicExponent)
    assertFalse("Legacy plaintext .p12 file should be deleted after migration", legacyFile.exists())
    assertNotNull(
      "Migrated identity must now live in encrypted DataStore",
      kotlinx.coroutines.runBlocking { store.getDeviceIdentity() },
    )
  }

  @Test
  fun `previously paired TV with stored DataStore SSL credential reconnects automatically without asking for pairing code`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val store = com.example.remote.data.SslCredentialsDataStore(app)
    kotlinx.coroutines.runBlocking { store.clearAll() }

    // Pre-populate encrypted DataStore as if this TV was paired in a previous session
    val certManager = CertificateManager(app, store)
    val serverKeyStore = certManager.ensureClientIdentity()
    val savedCred =
      certManager.savePairedTvCredential(
        ipAddress = "127.0.0.1",
        controlPort = 6466,
        serverName = "Bedroom Android TV",
        serverCertFingerprintSha256 = "DEADBEAF00112233445566778899AABBCCDDEEFF00112233445566778899AABB",
        pairingTokenHash = "PAIRED_SESSION_TOKEN_HASH",
      )
    assertTrue(certManager.hasStoredCredentialForHost("127.0.0.1"))
    assertEquals("PAIRED_SESSION_TOKEN_HASH", savedCred.pairingTokenHash)

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
        .apply { needClientAuth = false }

    val attemptCount = java.util.concurrent.atomic.AtomicInteger(0)
    val keepAliveLatch = java.util.concurrent.CountDownLatch(1)
    val serverThread =
      Thread {
        try {
          while (!serverSocket.isClosed && attemptCount.get() < 2) {
            val client = serverSocket.accept() as javax.net.ssl.SSLSocket
            val idx = attemptCount.incrementAndGet()
            client.startHandshake()
            if (idx == 1) {
              // Simulate transient early drop (e.g. TV busy or another device briefly active)
              client.close()
            } else {
              val cfg =
                com.example.proto.remote.RemoteMessage.newBuilder()
                  .setRemoteConfigure(
                    com.example.proto.remote.RemoteConfigure.newBuilder().setCode1(622).build()
                  )
                  .build()
              com.example.remote.protocol.DelimitedStreamHelper.writeMessage(client.outputStream, cfg)
              keepAliveLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
              client.close()
            }
          }
        } catch (_: Throwable) {
        } finally {
          try { serverSocket.close() } catch (_: Exception) {}
        }
      }.apply { start() }

    fun pumpMainLooperUntil(timeoutMs: Long = 6_000L, condition: () -> Boolean): Boolean {
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

    // Notice: SavedConnections SharedPreferences is EMPTY, ONLY encrypted DataStore has the TV credential!
    val viewModel = com.example.remote.ui.RemoteViewModel(app)
    viewModel.connectManualIp("127.0.0.1", serverSocket.localPort, "Bedroom Android TV")

    assertTrue(
      "Expected previously paired TV (from DataStore) to auto-reconnect without pairing prompt, got ${viewModel.uiState.value.connectionStatus}",
      pumpMainLooperUntil {
        assertFalse(
          "Must NEVER show pairing prompt for TV with stored DataStore SSL credentials",
          viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Pairing,
        )
        viewModel.uiState.value.connectionStatus is com.example.remote.ui.ConnectionStatus.Connected
      },
    )

    keepAliveLatch.countDown()
    viewModel.disconnect()
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    serverThread.join(1_500L)
  }
}



