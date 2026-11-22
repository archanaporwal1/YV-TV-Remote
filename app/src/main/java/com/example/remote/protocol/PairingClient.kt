package com.example.remote.protocol

import android.content.Context
import android.os.Build
import com.example.proto.pairing.PairingConfiguration
import com.example.proto.pairing.PairingEncoding
import com.example.proto.pairing.PairingMessage
import com.example.proto.pairing.PairingOption
import com.example.proto.pairing.PairingRequest
import com.example.proto.pairing.PairingSecret
import com.example.proto.pairing.RoleType
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingClient(
  private val certificateManager: CertificateManager,
  private val appContext: Context? = null,
) {

  sealed class PairingStartResult {
    data class AwaitingPin(val session: ActivePairingSession, val serverName: String) :
      PairingStartResult()

    data class Failed(val reason: String) : PairingStartResult()
  }

  sealed class PinSubmitResult {
    data object Success : PinSubmitResult()
    data class InvalidPinFormatOrCheck(val reason: String) : PinSubmitResult()
    data class RejectedByTv(val reason: String) : PinSubmitResult()
    data class ConnectionLost(val reason: String) : PinSubmitResult()
  }

  class ActivePairingSession(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream,
    private val clientPublicKey: RSAPublicKey,
    private val serverPublicKey: RSAPublicKey,
  ) {
    suspend fun submitPin(rawPin: String): PinSubmitResult = withContext(Dispatchers.IO) {
      val pin = rawPin.trim().uppercase()
      if (!HEX_PIN_REGEX.matches(pin)) {
        return@withContext PinSubmitResult.InvalidPinFormatOrCheck(
          "Enter the exact 6-character hexadecimal code shown on your TV."
        )
      }

      val expectedFirstByte = pin.substring(0, 2).toInt(16).toByte()
      val nonceBytes = hexToBytes(pin.substring(2, 6))

      val digest = MessageDigest.getInstance("SHA-256")
      digest.update(CertificateManager.stripLeadingZeroByte(clientPublicKey.modulus))
      digest.update(CertificateManager.stripLeadingZeroByte(clientPublicKey.publicExponent))
      digest.update(CertificateManager.stripLeadingZeroByte(serverPublicKey.modulus))
      digest.update(CertificateManager.stripLeadingZeroByte(serverPublicKey.publicExponent))
      digest.update(nonceBytes)
      val computedSecret = digest.digest()

      if (computedSecret[0] != expectedFirstByte) {
        return@withContext PinSubmitResult.InvalidPinFormatOrCheck(
          "PIN verification mismatch. Please double-check the 6 characters on your TV."
        )
      }

      try {
        val secretMessage =
          PairingMessage.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingSecret(
              PairingSecret.newBuilder()
                .setSecret(ByteString.copyFrom(computedSecret))
                .build()
            )
            .build()

        DelimitedStreamHelper.writeMessage(output, secretMessage)

        val responseBytes =
          DelimitedStreamHelper.readDelimitedBytes(input)
            ?: return@withContext PinSubmitResult.ConnectionLost(
              "TV closed the pairing connection before acknowledging the PIN."
            )

        val response = PairingMessage.parseFrom(responseBytes)
        if (response.status == PairingMessage.Status.STATUS_BAD_SECRET) {
          close()
          return@withContext PinSubmitResult.RejectedByTv(
            "TV rejected the pairing PIN (STATUS_BAD_SECRET). Please start pairing again."
          )
        }
        if (response.status != PairingMessage.Status.STATUS_OK || !response.hasPairingSecretAck()) {
          close()
          return@withContext PinSubmitResult.RejectedByTv(
            "Unexpected pairing secret response from TV: ${response.status}"
          )
        }

        close()
        PinSubmitResult.Success
      } catch (ce: kotlinx.coroutines.CancellationException) {
        close()
        throw ce
      } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        close()
        PinSubmitResult.ConnectionLost(t.message ?: "Pairing connection interrupted.")
      }
    }

    fun close() {
      try {
        socket.close()
      } catch (_: Exception) {
      }
    }
  }

  suspend fun startPairing(host: String, port: Int = DEFAULT_PAIRING_PORT): PairingStartResult =
    withContext(Dispatchers.IO) {
      var plainSocket: java.net.Socket? = null
      var socket: SSLSocket? = null
      try {
        certificateManager.ensureUniqueIdentityForPairing()
        val tls = certificateManager.createTlsSessionContext()
        plainSocket =
          WifiNetworkBinder.createPlainSocket(appContext, host).apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = CONNECT_TIMEOUT_MS
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
          }
        socket =
          (tls.sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket).apply {
            useClientMode = true
            soTimeout = HANDSHAKE_TIMEOUT_MS
            startHandshake()
          }

        val serverKey =
          tls.trustManager.capturedServerPublicKey
            ?: (socket.session.peerCertificates.firstOrNull()?.publicKey as? RSAPublicKey)
            ?: throw IOException("Unable to extract TV RSA public key from TLS certificate.")

        val input = socket.inputStream
        val output = socket.outputStream

        // Step 1: PairingRequest -> PairingRequestAck
        val baseDeviceName =
          listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "YV-TV Remote" }
        val deviceTag = certificateManager.getClientDeviceTag().take(4)
        val deviceName =
          if (deviceTag.isNotEmpty()) "$baseDeviceName ($deviceTag)"
          else baseDeviceName

        val pairingRequest =
          PairingMessage.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingRequest(
              PairingRequest.newBuilder()
                .setServiceName(CLIENT_SERVICE_NAME)
                .setClientName(deviceName)
                .build()
            )
            .build()
        DelimitedStreamHelper.writeMessage(output, pairingRequest)

        val reqAck = readPairingMessage(input)
        if (reqAck.status != PairingMessage.Status.STATUS_OK || !reqAck.hasPairingRequestAck()) {
          throw IOException("TV rejected PairingRequest (${reqAck.status}).")
        }
        val serverName = reqAck.pairingRequestAck.serverName.ifBlank { host }

        // Step 2: PairingOption (HEXADECIMAL length 6) -> TV PairingOption
        val hexEncoding =
          PairingEncoding.newBuilder()
            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()

        val pairingOption =
          PairingMessage.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingOption(
              PairingOption.newBuilder()
                .addInputEncodings(hexEncoding)
                .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                .build()
            )
            .build()
        DelimitedStreamHelper.writeMessage(output, pairingOption)

        val tvOption = readPairingMessage(input)
        if (tvOption.status != PairingMessage.Status.STATUS_OK || !tvOption.hasPairingOption()) {
          throw IOException("TV rejected PairingOption (${tvOption.status}).")
        }

        // Step 3: PairingConfiguration -> PairingConfigurationAck (TV displays 6-char PIN now)
        val pairingConfig =
          PairingMessage.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingConfiguration(
              PairingConfiguration.newBuilder()
                .setEncoding(hexEncoding)
                .setClientRole(RoleType.ROLE_TYPE_INPUT)
                .build()
            )
            .build()
        DelimitedStreamHelper.writeMessage(output, pairingConfig)

        val configAck = readPairingMessage(input)
        if (configAck.status != PairingMessage.Status.STATUS_OK || !configAck.hasPairingConfigurationAck()) {
          throw IOException("TV rejected PairingConfiguration (${configAck.status}).")
        }

        // Extend socket timeout while user reads the 6-hex PIN off the TV screen
        socket.soTimeout = PIN_ENTRY_TIMEOUT_MS

        PairingStartResult.AwaitingPin(
          session =
            ActivePairingSession(
              socket = socket,
              input = input,
              output = output,
              clientPublicKey = tls.clientPublicKey,
              serverPublicKey = serverKey,
            ),
          serverName = serverName,
        )
      } catch (ce: kotlinx.coroutines.CancellationException) {
        runCatching { socket?.close() }
        runCatching { plainSocket?.close() }
        throw ce
      } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        runCatching { socket?.close() }
        runCatching { plainSocket?.close() }
        PairingStartResult.Failed(t.message ?: "Failed to initiate pairing with $host:$port.")
      }
    }

  private fun readPairingMessage(input: InputStream): PairingMessage {
    val bytes =
      DelimitedStreamHelper.readDelimitedBytes(input)
        ?: throw IOException("TV closed the pairing socket unexpectedly.")
    return PairingMessage.parseFrom(bytes)
  }

  companion object {
    const val DEFAULT_PAIRING_PORT = 6467
    private const val PROTOCOL_VERSION = 2
    private const val CLIENT_SERVICE_NAME = "in.yourview.yvtvremote"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    private const val PIN_ENTRY_TIMEOUT_MS = 120_000
    private val HEX_PIN_REGEX = Regex("^[0-9A-F]{6}$")

    fun hexToBytes(hex: String): ByteArray {
      require(hex.length % 2 == 0) { "Hex string length must be even." }
      return ByteArray(hex.length / 2) { index ->
        val i = index * 2
        hex.substring(i, i + 2).toInt(16).toByte()
      }
    }
  }
}
