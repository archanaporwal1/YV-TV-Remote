package com.example.remote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted device-level TLS client identity persisted in Jetpack DataStore.
 */
data class StoredDeviceSslIdentity(
  val deviceTag: String,
  val subjectCn: String,
  val clientCertFingerprintSha256: String,
  val pkcs12Bytes: ByteArray,
  val createdAtMs: Long,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StoredDeviceSslIdentity) return false
    return deviceTag == other.deviceTag &&
      subjectCn == other.subjectCn &&
      clientCertFingerprintSha256 == other.clientCertFingerprintSha256 &&
      pkcs12Bytes.contentEquals(other.pkcs12Bytes) &&
      createdAtMs == other.createdAtMs
  }

  override fun hashCode(): Int {
    var result = deviceTag.hashCode()
    result = 31 * result + subjectCn.hashCode()
    result = 31 * result + clientCertFingerprintSha256.hashCode()
    result = 31 * result + pkcs12Bytes.contentHashCode()
    result = 31 * result + createdAtMs.hashCode()
    return result
  }
}

/**
 * Per-TV paired SSL credential & pairing token record stored encrypted in Jetpack DataStore.
 * Enables automatic, zero-prompt TLS reconnection to previously paired TVs across app restarts,
 * multi-device handoffs, and LAN IP updates.
 */
data class PairedTvSslCredential(
  val tvKey: String,
  val ipAddress: String,
  val controlPort: Int,
  val serverName: String,
  val serverCertFingerprintSha256: String,
  val clientDeviceTag: String,
  val clientCertFingerprintSha256: String,
  val pairingTokenHash: String,
  val pkcs12Bytes: ByteArray,
  val pairedAtMs: Long,
  val lastConnectedAtMs: Long,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PairedTvSslCredential) return false
    return tvKey == other.tvKey &&
      ipAddress == other.ipAddress &&
      controlPort == other.controlPort &&
      serverName == other.serverName &&
      serverCertFingerprintSha256 == other.serverCertFingerprintSha256 &&
      clientDeviceTag == other.clientDeviceTag &&
      clientCertFingerprintSha256 == other.clientCertFingerprintSha256 &&
      pairingTokenHash == other.pairingTokenHash &&
      pkcs12Bytes.contentEquals(other.pkcs12Bytes) &&
      pairedAtMs == other.pairedAtMs &&
      lastConnectedAtMs == other.lastConnectedAtMs
  }

  override fun hashCode(): Int {
    var result = tvKey.hashCode()
    result = 31 * result + ipAddress.hashCode()
    result = 31 * result + controlPort
    result = 31 * result + serverName.hashCode()
    result = 31 * result + serverCertFingerprintSha256.hashCode()
    result = 31 * result + clientDeviceTag.hashCode()
    result = 31 * result + clientCertFingerprintSha256.hashCode()
    result = 31 * result + pairingTokenHash.hashCode()
    result = 31 * result + pkcs12Bytes.contentHashCode()
    result = 31 * result + pairedAtMs.hashCode()
    result = 31 * result + lastConnectedAtMs.hashCode()
    return result
  }
}

/**
 * Jetpack DataStore-backed secure storage for device-specific SSL identities and per-TV
 * pairing credentials/tokens.
 *
 * All sensitive payloads (PKCS#12 private keys/certificates and pairing tokens) are encrypted
 * at rest with AES-256-GCM using an Android Keystore master key before being written to DataStore.
 */
class SslCredentialsDataStore(context: Context) {

  private val storageDir: File = File(context.filesDir, DATASTORE_DIR_NAME).apply { mkdirs() }
  val dataStoreFile: File = File(storageDir, DATASTORE_FILE_NAME)
  private val cipher = SslCredentialCipher(storageDir)
  private val dataStore: DataStore<Preferences> = getOrCreateDataStore(dataStoreFile)

  private val safePreferencesFlow: Flow<Preferences> =
    dataStore.data.catch { throwable ->
      if (throwable is IOException) {
        emit(emptyPreferences())
      } else {
        throw throwable
      }
    }

  /**
   * Reactive stream of all paired TV SSL credentials stored for this device.
   */
  val pairedCredentialsFlow: Flow<List<PairedTvSslCredential>> =
    safePreferencesFlow.map { prefs -> decodePairedCredentials(prefs) }

  suspend fun getDeviceIdentity(): StoredDeviceSslIdentity? {
    val prefs = safePreferencesFlow.first()
    val encryptedPayload = prefs[KEY_DEVICE_IDENTITY] ?: return null
    val decryptedJson = cipher.decryptString(encryptedPayload) ?: return null
    return parseDeviceIdentityJson(decryptedJson)
  }

  fun getDeviceIdentityBlocking(): StoredDeviceSslIdentity? =
    runBlocking(Dispatchers.IO) { getDeviceIdentity() }

  suspend fun saveDeviceIdentity(identity: StoredDeviceSslIdentity) {
    val json = serializeDeviceIdentityJson(identity)
    val encryptedPayload = cipher.encryptString(json)
    dataStore.edit { prefs ->
      prefs[KEY_DEVICE_IDENTITY] = encryptedPayload
    }
  }

  fun saveDeviceIdentityBlocking(identity: StoredDeviceSslIdentity) {
    runBlocking(Dispatchers.IO) { saveDeviceIdentity(identity) }
  }

  suspend fun getAllPairedCredentials(): List<PairedTvSslCredential> =
    decodePairedCredentials(safePreferencesFlow.first())

  suspend fun getPairedCredentialByHost(ipAddress: String): PairedTvSslCredential? {
    val normalized = normalizeHost(ipAddress)
    if (normalized.isEmpty()) return null
    return getAllPairedCredentials().firstOrNull {
      normalizeHost(it.ipAddress) == normalized || normalizeHost(it.tvKey) == normalized
    }
  }

  fun getPairedCredentialByHostBlocking(ipAddress: String): PairedTvSslCredential? =
    runBlocking(Dispatchers.IO) { getPairedCredentialByHost(ipAddress) }

  suspend fun getPairedCredentialByServerFingerprint(fingerprintSha256: String): PairedTvSslCredential? {
    val normalizedFp = normalizeFingerprint(fingerprintSha256)
    if (normalizedFp.isEmpty()) return null
    return getAllPairedCredentials().firstOrNull {
      normalizeFingerprint(it.serverCertFingerprintSha256) == normalizedFp
    }
  }

  suspend fun hasPairedCredential(
    ipAddress: String,
    serverFingerprintSha256: String? = null,
  ): Boolean {
    val all = getAllPairedCredentials()
    val normalizedHost = normalizeHost(ipAddress)
    if (normalizedHost.isNotEmpty() && all.any {
        normalizeHost(it.ipAddress) == normalizedHost || normalizeHost(it.tvKey) == normalizedHost
      }
    ) {
      return true
    }
    val normalizedFp = normalizeFingerprint(serverFingerprintSha256.orEmpty())
    return normalizedFp.isNotEmpty() && all.any {
      normalizeFingerprint(it.serverCertFingerprintSha256) == normalizedFp
    }
  }

  suspend fun savePairedCredential(credential: PairedTvSslCredential) {
    val normalizedIp = normalizeHost(credential.ipAddress)
    val normalizedFp = normalizeFingerprint(credential.serverCertFingerprintSha256)
    val sanitized =
      credential.copy(
        tvKey = credential.tvKey.trim().ifEmpty { normalizedIp },
        ipAddress = normalizedIp,
        serverCertFingerprintSha256 = normalizedFp,
      )
    dataStore.edit { prefs ->
      val current = decodePairedCredentials(prefs)
      val filtered =
        current.filterNot { existing ->
          (normalizedIp.isNotEmpty() && normalizeHost(existing.ipAddress) == normalizedIp) ||
            (normalizedFp.isNotEmpty() &&
              normalizeFingerprint(existing.serverCertFingerprintSha256) == normalizedFp)
        }
      val updated = (listOf(sanitized) + filtered).take(MAX_STORED_TV_CREDENTIALS)
      prefs[KEY_PAIRED_TV_CREDENTIALS] = cipher.encryptString(serializePairedCredentialsJson(updated))
    }
  }

  fun savePairedCredentialBlocking(credential: PairedTvSslCredential) {
    runBlocking(Dispatchers.IO) { savePairedCredential(credential) }
  }

  suspend fun updateLastConnected(
    ipAddress: String,
    controlPort: Int,
    serverName: String? = null,
    serverFingerprintSha256: String? = null,
  ) {
    val normalizedIp = normalizeHost(ipAddress)
    val normalizedFp = normalizeFingerprint(serverFingerprintSha256.orEmpty())
    if (normalizedIp.isEmpty() && normalizedFp.isEmpty()) return

    val now = System.currentTimeMillis()
    dataStore.edit { prefs ->
      val current = decodePairedCredentials(prefs)
      var matched = false
      val updated =
        current.map { existing ->
          val hostMatch = normalizedIp.isNotEmpty() && normalizeHost(existing.ipAddress) == normalizedIp
          val fpMatch =
            normalizedFp.isNotEmpty() &&
              normalizeFingerprint(existing.serverCertFingerprintSha256) == normalizedFp
          if (hostMatch || fpMatch) {
            matched = true
            existing.copy(
              ipAddress = normalizedIp.ifEmpty { existing.ipAddress },
              controlPort = controlPort.takeIf { it in 1..65535 } ?: existing.controlPort,
              serverName = serverName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.serverName,
              serverCertFingerprintSha256 = normalizedFp.ifEmpty { existing.serverCertFingerprintSha256 },
              lastConnectedAtMs = now,
            )
          } else {
            existing
          }
        }
      if (matched) {
        prefs[KEY_PAIRED_TV_CREDENTIALS] = cipher.encryptString(serializePairedCredentialsJson(updated))
      }
    }
  }

  suspend fun updatePairedHostAddress(
    oldIpAddress: String,
    newIpAddress: String,
    newPort: Int,
  ) {
    val oldNorm = normalizeHost(oldIpAddress)
    val newNorm = normalizeHost(newIpAddress)
    if (oldNorm.isEmpty() || newNorm.isEmpty()) return
    dataStore.edit { prefs ->
      val current = decodePairedCredentials(prefs)
      var changed = false
      val updated =
        current.map { existing ->
          if (normalizeHost(existing.ipAddress) == oldNorm || normalizeHost(existing.tvKey) == oldNorm) {
            changed = true
            val nextTvKey =
              if (existing.tvKey.isBlank() || normalizeHost(existing.tvKey) == oldNorm) newNorm
              else existing.tvKey
            existing.copy(
              tvKey = nextTvKey,
              ipAddress = newNorm,
              controlPort = newPort.takeIf { it in 1..65535 } ?: existing.controlPort,
            )
          } else {
            existing
          }
        }
      if (changed) {
        prefs[KEY_PAIRED_TV_CREDENTIALS] = cipher.encryptString(serializePairedCredentialsJson(updated))
      }
    }
  }

  suspend fun removePairedCredential(ipAddress: String) {
    val normalizedIp = normalizeHost(ipAddress)
    if (normalizedIp.isEmpty()) return
    dataStore.edit { prefs ->
      val current = decodePairedCredentials(prefs)
      val remaining =
        current.filterNot {
          normalizeHost(it.ipAddress) == normalizedIp || normalizeHost(it.tvKey) == normalizedIp
        }
      if (remaining.size != current.size) {
        if (remaining.isEmpty()) {
          prefs.remove(KEY_PAIRED_TV_CREDENTIALS)
        } else {
          prefs[KEY_PAIRED_TV_CREDENTIALS] = cipher.encryptString(serializePairedCredentialsJson(remaining))
        }
      }
    }
  }

  suspend fun clearAll() {
    dataStore.edit { prefs ->
      prefs.clear()
    }
  }

  private fun decodePairedCredentials(prefs: Preferences): List<PairedTvSslCredential> {
    val encryptedPayload = prefs[KEY_PAIRED_TV_CREDENTIALS] ?: return emptyList()
    val decryptedJson = cipher.decryptString(encryptedPayload) ?: return emptyList()
    return parsePairedCredentialsJson(decryptedJson)
  }

  private fun serializeDeviceIdentityJson(identity: StoredDeviceSslIdentity): String =
    JSONObject()
      .put("deviceTag", identity.deviceTag)
      .put("subjectCn", identity.subjectCn)
      .put("clientCertFingerprintSha256", identity.clientCertFingerprintSha256)
      .put("pkcs12Base64", Base64.encodeToString(identity.pkcs12Bytes, Base64.NO_WRAP))
      .put("createdAtMs", identity.createdAtMs)
      .toString()

  private fun parseDeviceIdentityJson(raw: String): StoredDeviceSslIdentity? =
    runCatching {
      val obj = JSONObject(raw)
      val pkcs12Base64 = obj.optString("pkcs12Base64", "")
      if (pkcs12Base64.isBlank()) return@runCatching null
      val bytes = Base64.decode(pkcs12Base64, Base64.NO_WRAP)
      if (bytes.isEmpty()) return@runCatching null
      StoredDeviceSslIdentity(
        deviceTag = obj.optString("deviceTag", ""),
        subjectCn = obj.optString("subjectCn", ""),
        clientCertFingerprintSha256 = obj.optString("clientCertFingerprintSha256", ""),
        pkcs12Bytes = bytes,
        createdAtMs = obj.optLong("createdAtMs", 0L),
      )
    }.getOrNull()

  private fun serializePairedCredentialsJson(credentials: List<PairedTvSslCredential>): String {
    val arr = JSONArray()
    credentials.forEach { cred ->
      arr.put(
        JSONObject()
          .put("tvKey", cred.tvKey)
          .put("ipAddress", cred.ipAddress)
          .put("controlPort", cred.controlPort)
          .put("serverName", cred.serverName)
          .put("serverCertFingerprintSha256", cred.serverCertFingerprintSha256)
          .put("clientDeviceTag", cred.clientDeviceTag)
          .put("clientCertFingerprintSha256", cred.clientCertFingerprintSha256)
          .put("pairingTokenHash", cred.pairingTokenHash)
          .put("pkcs12Base64", Base64.encodeToString(cred.pkcs12Bytes, Base64.NO_WRAP))
          .put("pairedAtMs", cred.pairedAtMs)
          .put("lastConnectedAtMs", cred.lastConnectedAtMs)
      )
    }
    return arr.toString()
  }

  private fun parsePairedCredentialsJson(raw: String): List<PairedTvSslCredential> =
    runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val ip = obj.optString("ipAddress", "").trim()
          val pkcs12Base64 = obj.optString("pkcs12Base64", "")
          if (ip.isEmpty() || pkcs12Base64.isBlank()) continue
          val pkcs12Bytes = runCatching { Base64.decode(pkcs12Base64, Base64.NO_WRAP) }.getOrNull()
          if (pkcs12Bytes == null || pkcs12Bytes.isEmpty()) continue
          add(
            PairedTvSslCredential(
              tvKey = obj.optString("tvKey", ip).ifBlank { ip },
              ipAddress = ip,
              controlPort = obj.optInt("controlPort", 6466),
              serverName = obj.optString("serverName", ip),
              serverCertFingerprintSha256 = obj.optString("serverCertFingerprintSha256", ""),
              clientDeviceTag = obj.optString("clientDeviceTag", ""),
              clientCertFingerprintSha256 = obj.optString("clientCertFingerprintSha256", ""),
              pairingTokenHash = obj.optString("pairingTokenHash", ""),
              pkcs12Bytes = pkcs12Bytes,
              pairedAtMs = obj.optLong("pairedAtMs", 0L),
              lastConnectedAtMs = obj.optLong("lastConnectedAtMs", 0L),
            )
          )
        }
      }
    }.getOrDefault(emptyList())

  /**
   * AES-256-GCM authenticated encryption backed by Android Keystore on Android devices,
   * with a per-directory random 256-bit key fallback solely for host JVM/Robolectric tests
   * where the AndroidKeyStore provider is not available.
   */
  private class SslCredentialCipher(private val storageDir: File) {
    private val secureRandom = SecureRandom()

    @Synchronized
    fun encryptString(plaintext: String): String {
      val (versionByte, key) = resolveMasterKey()
      val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
      val iv: ByteArray
      if (versionByte == VERSION_ANDROID_KEYSTORE) {
        cipher.init(Cipher.ENCRYPT_MODE, key)
        iv = cipher.iv
      } else {
        iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
      }
      val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

      val combined = ByteArray(1 + iv.size + ciphertext.size)
      combined[0] = versionByte
      System.arraycopy(iv, 0, combined, 1, iv.size)
      System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
      return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    @Synchronized
    fun decryptString(encoded: String): String? =
      runCatching {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= 1 + GCM_IV_LENGTH_BYTES + 16) return@runCatching null
        val versionByte = combined[0]
        val iv = combined.copyOfRange(1, 1 + GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(1 + GCM_IV_LENGTH_BYTES, combined.size)
        val key =
          when (versionByte) {
            VERSION_ANDROID_KEYSTORE -> getAndroidKeystoreKey(createIfMissing = false)
            VERSION_LOCAL_FALLBACK_KEY -> getLocalFallbackKey(createIfMissing = false)
            else -> null
          } ?: return@runCatching null
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        String(plaintextBytes, Charsets.UTF_8)
      }.getOrNull()

    private fun resolveMasterKey(): Pair<Byte, SecretKey> {
      val keystoreKey = getAndroidKeystoreKey(createIfMissing = true)
      if (keystoreKey != null) {
        return VERSION_ANDROID_KEYSTORE to keystoreKey
      }
      val fallbackKey =
        getLocalFallbackKey(createIfMissing = true)
          ?: throw IllegalStateException("Unable to initialize SSL credential encryption key.")
      return VERSION_LOCAL_FALLBACK_KEY to fallbackKey
    }

    private fun getAndroidKeystoreKey(createIfMissing: Boolean): SecretKey? =
      runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
        ks.load(null)
        val existing = ks.getKey(KEYSTORE_MASTER_ALIAS, null) as? SecretKey
        if (existing != null) return@runCatching existing
        if (!createIfMissing) return@runCatching null

        val generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
        val spec =
          KeyGenParameterSpec.Builder(
            KEYSTORE_MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
      }.getOrNull()

    private fun getLocalFallbackKey(createIfMissing: Boolean): SecretKey? =
      runCatching {
        val keyFile = File(storageDir, LOCAL_FALLBACK_KEY_FILE)
        if (keyFile.exists() && keyFile.length() == 32L) {
          val keyBytes = keyFile.readBytes()
          if (keyBytes.size == 32) {
            return@runCatching SecretKeySpec(keyBytes, "AES")
          }
        }
        if (!createIfMissing) return@runCatching null
        storageDir.mkdirs()
        val keyBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        keyFile.writeBytes(keyBytes)
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)
        SecretKeySpec(keyBytes, "AES")
      }.getOrNull()
  }

  companion object {
    private const val DATASTORE_DIR_NAME = "datastore"
    private const val DATASTORE_FILE_NAME = "ssl_credentials_store.preferences_pb"
    private const val LOCAL_FALLBACK_KEY_FILE = ".ssl_ds_fallback.key"
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_MASTER_ALIAS = "yvtv_ssl_datastore_master_key_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val VERSION_ANDROID_KEYSTORE: Byte = 0x01
    private const val VERSION_LOCAL_FALLBACK_KEY: Byte = 0x02
    private const val MAX_STORED_TV_CREDENTIALS = 32

    private val KEY_DEVICE_IDENTITY = stringPreferencesKey("encrypted_device_ssl_identity_v1")
    private val KEY_PAIRED_TV_CREDENTIALS = stringPreferencesKey("encrypted_paired_tv_credentials_v1")

    private val dataStoreRegistry = ConcurrentHashMap<String, DataStore<Preferences>>()

    private fun getOrCreateDataStore(file: File): DataStore<Preferences> {
      val canonicalKey = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
      return dataStoreRegistry.computeIfAbsent(canonicalKey) {
        PreferenceDataStoreFactory.create(
          corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
          scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
          produceFile = { file },
        )
      }
    }

    fun normalizeHost(host: String): String = host.trim().lowercase()

    fun normalizeFingerprint(fingerprint: String): String =
      fingerprint.trim().replace(":", "").uppercase()
  }
}
