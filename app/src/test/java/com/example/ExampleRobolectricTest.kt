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
}
