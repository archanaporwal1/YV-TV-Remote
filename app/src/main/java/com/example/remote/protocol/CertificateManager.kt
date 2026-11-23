package com.example.remote.protocol

import android.content.Context
import com.example.remote.data.PairedTvSslCredential
import com.example.remote.data.SslCredentialsDataStore
import com.example.remote.data.StoredDeviceSslIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class CertificateManager(
  context: Context,
  val sslCredentialsStore: SslCredentialsDataStore = SslCredentialsDataStore(context),
) {

  /**
   * Points to the encrypted Jetpack DataStore backing file where this device's SSL credentials
   * and per-TV pairing tokens are persisted.
   */
  val keystoreFile: File = sslCredentialsStore.dataStoreFile

  private val legacyKeystoreFile: File = File(context.filesDir, KEYSTORE_FILE_NAME)
  private val bcProvider = BouncyCastleProvider()
  private var cachedKeyStore: KeyStore? = null
  private var cachedDeviceTag: String? = null
  private val cachedHostKeyStores = ConcurrentHashMap<String, KeyStore>()

  @Synchronized
  fun ensureClientIdentity(): KeyStore = ensureClientIdentityInternal(forceRegenerate = false)

  /**
   * Ensures this device has a unique per-device certificate Subject CN before pairing.
   * Multiple phones sharing the same Android TV must never share the identical Subject DN
   * ("CN=YV-TV Remote"), otherwise the TV's KeyStore overwrites the previous device's
   * trusted certificate and triggers repeated pairing prompts.
   */
  @Synchronized
  fun ensureUniqueIdentityForPairing(): KeyStore {
    val existing = ensureClientIdentityInternal(forceRegenerate = false)
    val cert = existing.getCertificate(KEY_ALIAS) as? X509Certificate
    val subjectDn = cert?.subjectX500Principal?.name.orEmpty()
    val hasUniqueSuffix = Regex("CN=YV-TV Remote-[0-9A-Fa-f]{8}\\b").containsMatchIn(subjectDn)
    return if (hasUniqueSuffix) {
      existing
    } else {
      ensureClientIdentityInternal(forceRegenerate = true)
    }
  }

  /**
   * Returns the short 8-hex unique tag embedded in this device's (or a specific paired TV's)
   * client certificate CN, or an empty string for legacy pre-upgrade certificates.
   */
  @Synchronized
  fun getClientDeviceTag(tvHost: String? = null): String {
    val normalizedHost = tvHost?.let { SslCredentialsDataStore.normalizeHost(it) }.orEmpty()
    if (normalizedHost.isNotEmpty()) {
      val hostKs = resolveKeyStoreForHost(normalizedHost)
      if (hostKs != null) {
        val cert = hostKs.getCertificate(KEY_ALIAS) as? X509Certificate
        val tag = extractDeviceTag(cert)
        if (tag.isNotEmpty()) return tag
      }
    }
    cachedDeviceTag?.let { return it }
    val ks = ensureClientIdentityInternal(forceRegenerate = false)
    val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return ""
    val tag = extractDeviceTag(cert)
    cachedDeviceTag = tag
    return tag
  }

  /**
   * Persists the paired SSL credentials & pairing token for a specific TV into encrypted
   * Jetpack DataStore so future connections reuse the exact paired identity automatically.
   */
  @Synchronized
  fun savePairedTvCredential(
    ipAddress: String,
    controlPort: Int,
    serverName: String,
    serverCertFingerprintSha256: String,
    pairingTokenHash: String,
  ): PairedTvSslCredential {
    val keyStore = ensureClientIdentityInternal(forceRegenerate = false)
    val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    val pkcs12Bytes = exportKeyStoreBytes(keyStore)
    val now = System.currentTimeMillis()
    val normalizedIp = SslCredentialsDataStore.normalizeHost(ipAddress)
    val credential =
      PairedTvSslCredential(
        tvKey = normalizedIp,
        ipAddress = normalizedIp,
        controlPort = controlPort.takeIf { it in 1..65535 } ?: RemoteControlClient.DEFAULT_REMOTE_PORT,
        serverName = serverName.trim().ifEmpty { normalizedIp },
        serverCertFingerprintSha256 =
          SslCredentialsDataStore.normalizeFingerprint(serverCertFingerprintSha256),
        clientDeviceTag = extractDeviceTag(cert),
        clientCertFingerprintSha256 = computeCertFingerprintSha256(cert),
        pairingTokenHash = pairingTokenHash.trim(),
        pkcs12Bytes = pkcs12Bytes,
        pairedAtMs = now,
        lastConnectedAtMs = now,
      )
    sslCredentialsStore.savePairedCredentialBlocking(credential)
    if (normalizedIp.isNotEmpty()) {
      cachedHostKeyStores[normalizedIp] = keyStore
    }
    return credential
  }

  @Synchronized
  fun clearCachedHostCredential(ipAddress: String) {
    val normalizedIp = SslCredentialsDataStore.normalizeHost(ipAddress)
    if (normalizedIp.isNotEmpty()) {
      cachedHostKeyStores.remove(normalizedIp)
    }
  }

  @Synchronized
  fun hasStoredCredentialForHost(ipAddress: String): Boolean {
    val normalizedIp = SslCredentialsDataStore.normalizeHost(ipAddress)
    if (normalizedIp.isEmpty()) return false
    if (cachedHostKeyStores.containsKey(normalizedIp)) return true
    return sslCredentialsStore.getPairedCredentialByHostBlocking(normalizedIp) != null
  }

  private fun resolveKeyStoreForHost(normalizedHost: String): KeyStore? {
    cachedHostKeyStores[normalizedHost]?.let { return it }
    val paired = sslCredentialsStore.getPairedCredentialByHostBlocking(normalizedHost) ?: return null
    val loaded = loadKeyStoreFromBytes(paired.pkcs12Bytes) ?: return null
    cachedHostKeyStores[normalizedHost] = loaded
    return loaded
  }

  private fun ensureClientIdentityInternal(forceRegenerate: Boolean): KeyStore {
    if (!forceRegenerate && cachedKeyStore != null) {
      return cachedKeyStore!!
    }
    if (forceRegenerate) {
      cachedKeyStore = null
      cachedDeviceTag = null
    }

    // 1) Primary source: encrypted Jetpack DataStore
    if (!forceRegenerate) {
      val stored = sslCredentialsStore.getDeviceIdentityBlocking()
      if (stored != null) {
        val loaded = loadKeyStoreFromBytes(stored.pkcs12Bytes)
        if (loaded != null) {
          cachedKeyStore = loaded
          cachedDeviceTag = stored.deviceTag.ifBlank { extractDeviceTag(loaded.getCertificate(KEY_ALIAS) as? X509Certificate) }
          return loaded
        }
      }
    }

    // 2) Seamless zero-re-pair migration from legacy plaintext-stored .p12 file into encrypted DataStore
    if (!forceRegenerate && legacyKeystoreFile.exists() && legacyKeystoreFile.length() > 0L) {
      val legacyBytes = runCatching { legacyKeystoreFile.readBytes() }.getOrNull()
      val loadedLegacy = legacyBytes?.let { loadKeyStoreFromBytes(it) }
      if (loadedLegacy != null) {
        val cert = loadedLegacy.getCertificate(KEY_ALIAS) as X509Certificate
        val tag = extractDeviceTag(cert)
        val migratedIdentity =
          StoredDeviceSslIdentity(
            deviceTag = tag,
            subjectCn = cert.subjectX500Principal?.name.orEmpty(),
            clientCertFingerprintSha256 = computeCertFingerprintSha256(cert),
            pkcs12Bytes = legacyBytes,
            createdAtMs = System.currentTimeMillis(),
          )
        sslCredentialsStore.saveDeviceIdentityBlocking(migratedIdentity)
        runCatching { legacyKeystoreFile.delete() }
        cachedKeyStore = loadedLegacy
        cachedDeviceTag = tag
        return loadedLegacy
      }
    }

    // 3) Generate fresh 2048-bit RSA identity with unique per-device Subject CN and store in DataStore
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048, SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()

    val now = System.currentTimeMillis()
    val notBefore = Date(now - 86_400_000L) // -1 day for clock skew tolerance
    val notAfter = Date(now + 20L * 365L * 86_400_000L) // 20 years validity
    val serial = BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE)

    val deviceTagBytes = ByteArray(4)
    SecureRandom().nextBytes(deviceTagBytes)
    val deviceTag = deviceTagBytes.joinToString("") { "%02X".format(it) }
    val subjectCn = "CN=YV-TV Remote-$deviceTag, O=YourView, OU=yourview.in"
    val subject = X500Name(subjectCn)
    val certBuilder =
      JcaX509v3CertificateBuilder(
        subject,
        serial,
        notBefore,
        notAfter,
        subject,
        keyPair.public,
      )

    val signer =
      JcaContentSignerBuilder("SHA256withRSA")
        .setProvider(bcProvider)
        .build(keyPair.private)

    val certificate: X509Certificate =
      JcaX509CertificateConverter()
        .setProvider(bcProvider)
        .getCertificate(certBuilder.build(signer))

    val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KEY_ALIAS,
      keyPair.private,
      KEYSTORE_PASSWORD,
      arrayOf(certificate),
    )

    val pkcs12Bytes = exportKeyStoreBytes(keyStore)
    val newIdentity =
      StoredDeviceSslIdentity(
        deviceTag = deviceTag,
        subjectCn = subjectCn,
        clientCertFingerprintSha256 = computeCertFingerprintSha256(certificate),
        pkcs12Bytes = pkcs12Bytes,
        createdAtMs = now,
      )
    sslCredentialsStore.saveDeviceIdentityBlocking(newIdentity)
    cachedKeyStore = keyStore
    cachedDeviceTag = deviceTag
    return keyStore
  }

  private fun loadKeyStoreFromBytes(pkcs12Bytes: ByteArray): KeyStore? =
    runCatching {
      val ks = KeyStore.getInstance(KEYSTORE_TYPE)
      ByteArrayInputStream(pkcs12Bytes).use { input ->
        ks.load(input, KEYSTORE_PASSWORD)
      }
      val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate
      val key = ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD) as? PrivateKey
      if (cert != null && key != null) ks else null
    }.getOrNull()

  private fun exportKeyStoreBytes(keyStore: KeyStore): ByteArray =
    ByteArrayOutputStream().use { out ->
      keyStore.store(out, KEYSTORE_PASSWORD)
      out.toByteArray()
    }

  private fun extractDeviceTag(cert: X509Certificate?): String {
    val subjectDn = cert?.subjectX500Principal?.name.orEmpty()
    val match = Regex("CN=YV-TV Remote-([0-9A-Fa-f]{8})\\b").find(subjectDn)
    return match?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
  }

  fun getClientRsaPublicKey(tvHost: String? = null): RSAPublicKey {
    val normalizedHost = tvHost?.let { SslCredentialsDataStore.normalizeHost(it) }.orEmpty()
    val keyStore =
      if (normalizedHost.isNotEmpty()) {
        resolveKeyStoreForHost(normalizedHost) ?: ensureClientIdentity()
      } else {
        ensureClientIdentity()
      }
    val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    return cert.publicKey as RSAPublicKey
  }

  fun createTlsSessionContext(tvHost: String? = null): TlsContextResult {
    val normalizedHost = tvHost?.let { SslCredentialsDataStore.normalizeHost(it) }.orEmpty()
    val keyStore =
      if (normalizedHost.isNotEmpty()) {
        resolveKeyStoreForHost(normalizedHost) ?: ensureClientIdentity()
      } else {
        ensureClientIdentity()
      }
    val keyManagerFactory =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(keyStore, KEYSTORE_PASSWORD)
      }

    val rawKeyManagers = keyManagerFactory.keyManagers
    check(!rawKeyManagers.isNullOrEmpty()) {
      "KeyManagerFactory did not produce any KeyManagers for client certificate."
    }

    val keyManagers: Array<KeyManager> =
      rawKeyManagers
        .map { km ->
          when (km) {
            is X509ExtendedKeyManager -> ClientAliasFallbackExtendedKeyManager(km)
            is X509KeyManager -> ClientAliasFallbackKeyManager(km)
            else -> km
          }
        }
        .toTypedArray()

    val trustManager = ServerCapturingTrustManager()
    val sslContext =
      SSLContext.getInstance("TLS").apply {
        init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
      }

    val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    return TlsContextResult(
      sslContext = sslContext,
      keyManagers = keyManagers,
      trustManager = trustManager,
      clientPublicKey = cert.publicKey as RSAPublicKey,
    )
  }

  /**
   * Ensures Conscrypt/OpenSSL always sends our self-signed client certificate from DataStore
   * even if the TV sends a non-empty issuer list in its TLS CertificateRequest.
   */
  private class ClientAliasFallbackExtendedKeyManager(
    private val delegate: X509ExtendedKeyManager,
  ) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
      return delegate.getClientAliases(keyType, issuers)
        ?: delegate.getClientAliases(keyType, null)
    }

    override fun chooseClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      socket: Socket?,
    ): String? {
      return delegate.chooseClientAlias(keyType, issuers, socket)
        ?: delegate.chooseClientAlias(keyType, null, socket)
        ?: KEY_ALIAS.takeIf { delegate.getCertificateChain(it) != null }
    }

    override fun chooseEngineClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      engine: SSLEngine?,
    ): String? {
      return delegate.chooseEngineClientAlias(keyType, issuers, engine)
        ?: delegate.chooseEngineClientAlias(keyType, null, engine)
        ?: KEY_ALIAS.takeIf { delegate.getCertificateChain(it) != null }
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
      delegate.getServerAliases(keyType, issuers)

    override fun chooseServerAlias(
      keyType: String?,
      issuers: Array<out Principal>?,
      socket: Socket?,
    ): String? = delegate.chooseServerAlias(keyType, issuers, socket)

    override fun chooseEngineServerAlias(
      keyType: String?,
      issuers: Array<out Principal>?,
      engine: SSLEngine?,
    ): String? = delegate.chooseEngineServerAlias(keyType, issuers, engine)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
      delegate.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?): PrivateKey? = delegate.getPrivateKey(alias)
  }

  private class ClientAliasFallbackKeyManager(
    private val delegate: X509KeyManager,
  ) : X509KeyManager by delegate {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
      return delegate.getClientAliases(keyType, issuers)
        ?: delegate.getClientAliases(keyType, null)
    }

    override fun chooseClientAlias(
      keyType: Array<out String>?,
      issuers: Array<out Principal>?,
      socket: Socket?,
    ): String? {
      return delegate.chooseClientAlias(keyType, issuers, socket)
        ?: delegate.chooseClientAlias(keyType, null, socket)
        ?: KEY_ALIAS.takeIf { delegate.getCertificateChain(it) != null }
    }
  }

  class ServerCapturingTrustManager : X509TrustManager {
    @Volatile
    var capturedServerPublicKey: RSAPublicKey? = null
      private set

    @Volatile
    var capturedServerCertFingerprintSha256: String? = null
      private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      // Client verification is not performed by the remote client
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      val leafCert = chain?.firstOrNull() ?: return
      val rsaKey = leafCert.publicKey as? RSAPublicKey
      if (rsaKey != null) {
        capturedServerPublicKey = rsaKey
      }
      capturedServerCertFingerprintSha256 =
        runCatching { computeCertFingerprintSha256(leafCert) }.getOrNull()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  data class TlsContextResult(
    val sslContext: SSLContext,
    val keyManagers: Array<KeyManager>,
    val trustManager: ServerCapturingTrustManager,
    val clientPublicKey: RSAPublicKey,
  )

  companion object {
    const val KEYSTORE_FILE_NAME = "yvtv_client_identity.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
    const val KEY_ALIAS = "androidtv_remote_client"
    private val KEYSTORE_PASSWORD = "yvtv_remote_keystore_pass".toCharArray()

    fun computeCertFingerprintSha256(cert: X509Certificate): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * Strips the leading 0x00 sign byte that BigInteger.toByteArray() prepends
     * for positive numbers whose most significant bit is 1.
     */
    fun stripLeadingZeroByte(value: BigInteger): ByteArray {
      val raw = value.toByteArray()
      return if (raw.size > 1 && raw[0] == 0.toByte()) {
        raw.copyOfRange(1, raw.size)
      } else {
        raw
      }
    }
  }
}
