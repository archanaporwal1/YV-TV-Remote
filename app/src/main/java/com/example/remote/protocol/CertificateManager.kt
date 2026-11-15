package com.example.remote.protocol

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CertificateManager(context: Context) {

  private val keystoreFile = File(context.filesDir, KEYSTORE_FILE_NAME)
  private val bcProvider = BouncyCastleProvider()

  @Synchronized
  fun ensureClientIdentity(): KeyStore {
    val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
    if (keystoreFile.exists()) {
      try {
        keystoreFile.inputStream().use { input ->
          keyStore.load(input, KEYSTORE_PASSWORD)
        }
        if (keyStore.containsAlias(KEY_ALIAS) && keyStore.getCertificate(KEY_ALIAS) != null) {
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

    val subject = X500Name("CN=YV-TV Remote, O=AndroidTV, C=US")
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

    keystoreFile.outputStream().use { output ->
      keyStore.store(output, KEYSTORE_PASSWORD)
    }
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

    val trustManager = ServerCapturingTrustManager()
    val sslContext =
      SSLContext.getInstance("TLS").apply {
        init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
      }

    return TlsContextResult(
      sslContext = sslContext,
      trustManager = trustManager,
      clientPublicKey = getClientRsaPublicKey(),
    )
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
    val trustManager: ServerCapturingTrustManager,
    val clientPublicKey: RSAPublicKey,
  )

  companion object {
    private const val KEYSTORE_FILE_NAME = "yvtv_client_identity.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEY_ALIAS = "androidtv_remote_client"
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
