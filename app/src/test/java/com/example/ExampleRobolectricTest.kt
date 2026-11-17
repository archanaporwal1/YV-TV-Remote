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
      assertEquals(639, configureFeatureCode.get())
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
                    Thread.sleep(90L)
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
          keepaliveIntervalMs = 70L,
          socketReadTimeoutMs = 150,
          staleConnectionTimeoutMs = 280L,
        )
      client.connect("127.0.0.1", serverSocket.localPort)
      val deadline = System.currentTimeMillis() + 5_000L
      while (System.currentTimeMillis() < deadline && !client.isConnectionHealthy()) {
        kotlinx.coroutines.delay(20L)
      }
      assertTrue(client.isConnectionHealthy())

      // Wait past staleConnectionTimeoutMs while TV does NOT answer client pings (only sends server pings)
      kotlinx.coroutines.delay(500L)
      assertTrue(
        "Server-initiated ping TVs must stay connected even if client pings go unanswered",
        client.isConnectionHealthy(),
      )
      assertTrue("Expected server ping responses, got ${serverPongsReceived.get()}", serverPongsReceived.get() >= 2)

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
}
