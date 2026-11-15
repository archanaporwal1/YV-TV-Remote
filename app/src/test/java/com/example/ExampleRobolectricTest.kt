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
    val certManager = CertificateManager(context)
    val tlsContext = certManager.createTlsSessionContext()
    assertNotNull(tlsContext.sslContext)
    assertEquals(2048, tlsContext.clientPublicKey.modulus.bitLength())
  }
}
