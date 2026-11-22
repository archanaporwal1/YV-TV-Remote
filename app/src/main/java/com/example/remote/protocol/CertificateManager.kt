package com.example.remote.protocol

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class CertificateManager(context: Context) {

  val keystoreFile: File = File(context.filesDir, KEYSTORE_FILE_NAME)
  private val bcProvider = BouncyCastleProvider()
  private var cachedKeyStore: KeyStore? = null
  private var cachedDeviceTag: String? = null

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
   * Returns the short 8-hex unique tag embedded in this device's client certificate CN,
   * or an empty string for legacy pre-upgrade certificates.
   */
  @Synchronized
  fun getClientDeviceTag(): String {
    cachedDeviceTag?.let { return it }
    val ks = ensureClientIdentityInternal(forceRegenerate = false)
    val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return ""
    val subjectDn = cert.subjectX500Principal?.name.orEmpty()
    val match = Regex("CN=YV-TV Remote-([0-9A-Fa-f]{8})\\b").find(subjectDn)
    val tag = match?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
    cachedDeviceTag = tag
    return tag
  }

  private fun ensureClientIdentityInternal(forceRegenerate: Boolean): KeyStore {
    if (!forceRegenerate && cachedKeyStore != null && keystoreFile.exists() && keystoreFile.length() > 0L) {
      return cachedKeyStore!!
    }
    if (forceRegenerate) {
      cachedKeyStore = null
      cachedDeviceTag = null
    }
    val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
    if (!forceRegenerate && keystoreFile.exists() && keystoreFile.length() > 0L) {
      try {
        keystoreFile.inputStream().use { input ->
          keyStore.load(input, KEYSTORE_PASSWORD)
        }
        val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
        val key = keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD) as? PrivateKey
        if (cert != null && key != null) {
          cachedKeyStore = keyStore
          return keyStore
        }
      } catch (_: Exception) {
        // Regenerate corrupted or unreadable keystore
      }
    }

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
    val subject = X500Name("CN=YV-TV Remote-$deviceTag, O=YourView, OU=yourview.in")
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

    keyStore.load(null, null)
    keyStore.setKeyEntry(
      KEY_ALIAS,
      keyPair.private,
      KEYSTORE_PASSWORD,
      arrayOf(certificate),
    )

    keystoreFile.parentFile?.mkdirs()
    val tempFile = File(keystoreFile.parentFile, "$KEYSTORE_FILE_NAME.tmp")
    tempFile.outputStream().use { output ->
      keyStore.store(output, KEYSTORE_PASSWORD)
    }
    if (!tempFile.renameTo(keystoreFile)) {
      keystoreFile.outputStream().use { output ->
        keyStore.store(output, KEYSTORE_PASSWORD)
      }
      tempFile.delete()
    }
    cachedKeyStore = keyStore
    cachedDeviceTag = deviceTag
    return keyStore
  }

  fun getClientRsaPublicKey(): RSAPublicKey {
    val keyStore = ensureClientIdentity()
    val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    return cert.publicKey as RSAPublicKey
  }

  fun createTlsSessionContext(): TlsContextResult {
    val keyStore = ensureClientIdentity()
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

    return TlsContextResult(
      sslContext = sslContext,
      keyManagers = keyManagers,
      trustManager = trustManager,
      clientPublicKey = getClientRsaPublicKey(),
    )
  }

  /**
   * Ensures Conscrypt/OpenSSL always sends our self-signed client certificate from the saved .p12
   * keystore even if the TV sends a non-empty issuer list in its TLS CertificateRequest.
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

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      // Client verification is not performed by the remote client
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
      val leafCert = chain?.firstOrNull() ?: return
      val rsaKey = leafCert.publicKey as? RSAPublicKey
      if (rsaKey != null) {
        capturedServerPublicKey = rsaKey
      }
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
