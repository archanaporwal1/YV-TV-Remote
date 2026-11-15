package com.example.remote.protocol

import android.os.Build
import com.example.proto.remote.RemoteAppLinkLaunchRequest
import com.example.proto.remote.RemoteConfigure
import com.example.proto.remote.RemoteDeviceInfo
import com.example.proto.remote.RemoteDirection
import com.example.proto.remote.RemoteEditInfo
import com.example.proto.remote.RemoteImeBatchEdit
import com.example.proto.remote.RemoteImeObject
import com.example.proto.remote.RemoteKeyCode
import com.example.proto.remote.RemoteKeyInject
import com.example.proto.remote.RemoteMessage
import com.example.proto.remote.RemotePingResponse
import com.example.proto.remote.RemoteSetActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

data class TvImeState(
  val imeCounter: Int = 0,
  val fieldCounter: Int = 0,
  val currentText: String = "",
  val fieldLabel: String = "",
  val appLabel: String = "",
  val isImeRequested: Boolean = false,
)

class RemoteControlClient(private val certificateManager: CertificateManager) {

  sealed class SessionEvent {
    data object Disconnected : SessionEvent()
    data object Connecting : SessionEvent()
    data object Connected : SessionEvent()
    data class PairingRequired(val reason: String) : SessionEvent()
    data class Error(val message: String) : SessionEvent()
  }

  private val _sessionEvent = MutableStateFlow<SessionEvent>(SessionEvent.Disconnected)
  val sessionEvent: StateFlow<SessionEvent> = _sessionEvent.asStateFlow()

  private val _imeState = MutableStateFlow(TvImeState())
  val imeState: StateFlow<TvImeState> = _imeState.asStateFlow()

  private val writeMutex = Mutex()
  private var scope: CoroutineScope? = null
  private var readerJob: Job? = null
  private var socket: SSLSocket? = null
  private var outputStream: OutputStream? = null

  suspend fun connect(host: String, port: Int = DEFAULT_REMOTE_PORT) {
    disconnect()
    _sessionEvent.value = SessionEvent.Connecting
    _imeState.value = TvImeState()

    val connectSuccess =
      withContext(Dispatchers.IO) {
        try {
          val tls = certificateManager.createTlsSessionContext()
          val sslSocket =
            (tls.sslContext.socketFactory.createSocket() as SSLSocket).apply {
              useClientMode = true
              keepAlive = true
              tcpNoDelay = true
              connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
              startHandshake()
            }
          socket = sslSocket
          outputStream = sslSocket.outputStream

          val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          scope = sessionScope
          readerJob =
            sessionScope.launch {
              runReaderLoop(sslSocket)
            }
          true
        } catch (e: SSLException) {
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "TV requires pairing before remote control access (${e.message ?: "TLS rejected"})."
            )
          false
        } catch (e: Exception) {
          _sessionEvent.value =
            SessionEvent.Error(e.message ?: "Unable to connect to $host:$port.")
          false
        }
      }

    if (!connectSuccess) {
      cleanupSocketOnly()
    }
  }

  private suspend fun runReaderLoop(sslSocket: SSLSocket) {
    val input = sslSocket.inputStream
    var hasReceivedMessage = false
    try {
      while (scope?.isActive == true && !sslSocket.isClosed) {
        val frameBytes = DelimitedStreamHelper.readDelimitedBytes(input) ?: break
        hasReceivedMessage = true
        val message = RemoteMessage.parseFrom(frameBytes)

        when {
          message.hasRemoteConfigure() -> {
            sendConfigureAndActive()
            _sessionEvent.value = SessionEvent.Connected
          }
          message.hasRemoteSetActive() -> {
            if (_sessionEvent.value != SessionEvent.Connected) {
              _sessionEvent.value = SessionEvent.Connected
            }
          }
          message.hasRemotePingRequest() -> {
            val pingVal = message.remotePingRequest.val1
            val pong =
              RemoteMessage.newBuilder()
                .setRemotePingResponse(
                  RemotePingResponse.newBuilder()
                    .setVal1(pingVal)
                    .build()
                )
                .build()
            sendRemoteMessage(pong)
          }
          message.hasRemoteImeKeyInject() -> {
            val imeInject = message.remoteImeKeyInject
            val status = imeInject.textFieldStatus
            _imeState.update { current ->
              current.copy(
                imeCounter = imeInject.appInfo.counter,
                fieldCounter = status.counterField,
                currentText = status.value,
                fieldLabel = status.label.ifBlank { current.fieldLabel },
                appLabel = imeInject.appInfo.label.ifBlank { current.appLabel },
              )
            }
          }
          message.hasRemoteImeShowRequest() -> {
            val status = message.remoteImeShowRequest.remoteTextFieldStatus
            _imeState.update { current ->
              current.copy(
                fieldCounter = status.counterField,
                currentText = status.value,
                fieldLabel = status.label.ifBlank { current.fieldLabel },
                isImeRequested = true,
              )
            }
          }
          message.hasRemoteImeBatchEdit() -> {
            val batch = message.remoteImeBatchEdit
            _imeState.update { current ->
              current.copy(
                imeCounter = batch.imeCounter,
                fieldCounter = batch.fieldCounter,
              )
            }
          }
          message.hasRemoteStart() -> {
            _sessionEvent.value = SessionEvent.Connected
          }
          message.hasRemoteError() -> {
            _sessionEvent.value = SessionEvent.Error("Remote session error reported by TV.")
            break
          }
        }
      }
      if (scope?.isActive == true) {
        if (!hasReceivedMessage && _sessionEvent.value == SessionEvent.Connecting) {
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "TV closed the remote channel before handshake completed. Starting pairing..."
            )
        } else if (
          _sessionEvent.value == SessionEvent.Connected ||
          _sessionEvent.value == SessionEvent.Connecting
        ) {
          _sessionEvent.value = SessionEvent.Disconnected
        }
      }
    } catch (e: SSLException) {
      if (scope?.isActive == true) {
        if (isCertificateOrHandshakeError(e) || !hasReceivedMessage) {
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "Certificate not trusted by TV (${e.message ?: "SSLException"}). Starting pairing..."
            )
        } else {
          _sessionEvent.value = SessionEvent.Error(e.message ?: "TLS connection to TV was lost.")
        }
      }
    } catch (e: Exception) {
      if (scope?.isActive == true) {
        _sessionEvent.value = SessionEvent.Error(e.message ?: "Connection to TV was lost.")
      }
    } finally {
      cleanupSocketOnly()
    }
  }

  private suspend fun sendConfigureAndActive() {
    val deviceInfo =
      RemoteDeviceInfo.newBuilder()
        .setModel(Build.MODEL.ifBlank { "Android" })
        .setVendor(Build.MANUFACTURER.ifBlank { "Android" })
        .setUnknown1(1)
        .setUnknown2("1")
        .setPackageName("com.example.yvtvremote")
        .setAppVersion("1.0")
        .build()

    val configureReply =
      RemoteMessage.newBuilder()
        .setRemoteConfigure(
          RemoteConfigure.newBuilder()
            .setCode1(FEATURE_CODE_FULL)
            .setDeviceInfo(deviceInfo)
            .build()
        )
        .build()
    sendRemoteMessage(configureReply)

    val setActiveReply =
      RemoteMessage.newBuilder()
        .setRemoteSetActive(
          RemoteSetActive.newBuilder()
            .setActive(FEATURE_CODE_FULL)
            .build()
        )
        .build()
    sendRemoteMessage(setActiveReply)
  }

  suspend fun sendKey(
    keyCode: RemoteKeyCode,
    direction: RemoteDirection = RemoteDirection.SHORT,
  ): Boolean {
    val message =
      RemoteMessage.newBuilder()
        .setRemoteKeyInject(
          RemoteKeyInject.newBuilder()
            .setKeyCode(keyCode)
            .setDirection(direction)
            .build()
        )
        .build()
    return sendRemoteMessage(message)
  }

  suspend fun sendImeText(newText: String, previousLength: Int): Boolean {
    val state = _imeState.value
    val imeObject =
      RemoteImeObject.newBuilder()
        .setStart(0)
        .setEnd(previousLength.coerceAtLeast(0))
        .setValue(newText)
        .build()

    val editInfo =
      RemoteEditInfo.newBuilder()
        .setInsert(1)
        .setTextFieldStatus(imeObject)
        .build()

    val batchEdit =
      RemoteImeBatchEdit.newBuilder()
        .setImeCounter(state.imeCounter)
        .setFieldCounter(state.fieldCounter)
        .addEditInfo(editInfo)
        .build()

    val message =
      RemoteMessage.newBuilder()
        .setRemoteImeBatchEdit(batchEdit)
        .build()

    val sent = sendRemoteMessage(message)
    if (sent) {
      _imeState.update { it.copy(currentText = newText) }
    }
    return sent
  }

  suspend fun sendTextAsKeyEvents(text: String): Boolean {
    if (text.isEmpty()) return true
    for (ch in text) {
      val code = charToRemoteKeyCode(ch) ?: continue
      val sent = sendKey(code, RemoteDirection.SHORT)
      if (!sent) return false
      delay(18L)
    }
    return true
  }

  fun clearImeRequested() {
    _imeState.update { it.copy(isImeRequested = false) }
  }

  suspend fun launchApp(appLink: String): Boolean {
    val message =
      RemoteMessage.newBuilder()
        .setRemoteAppLinkLaunchRequest(
          RemoteAppLinkLaunchRequest.newBuilder()
            .setAppLink(appLink)
            .build()
        )
        .build()
    return sendRemoteMessage(message)
  }

  private suspend fun sendRemoteMessage(message: RemoteMessage): Boolean =
    withContext(Dispatchers.IO) {
      writeMutex.withLock {
        val out = outputStream ?: return@withLock false
        try {
          DelimitedStreamHelper.writeMessage(out, message)
          true
        } catch (e: SSLException) {
          _sessionEvent.value =
            if (isCertificateOrHandshakeError(e)) {
              SessionEvent.PairingRequired(
                "Certificate rejected by TV (${e.message ?: "SSLException"}). Starting pairing..."
              )
            } else {
              SessionEvent.Error(e.message ?: "Failed to send command to TV.")
            }
          cleanupSocketOnly()
          false
        } catch (e: IOException) {
          _sessionEvent.value = SessionEvent.Error(e.message ?: "Failed to send command to TV.")
          cleanupSocketOnly()
          false
        }
      }
    }

  fun disconnect() {
    readerJob?.cancel()
    readerJob = null
    scope?.cancel()
    scope = null
    cleanupSocketOnly()
    _imeState.value = TvImeState()
    _sessionEvent.value = SessionEvent.Disconnected
  }

  private fun cleanupSocketOnly() {
    outputStream = null
    try {
      socket?.close()
    } catch (_: Exception) {
    }
    socket = null
  }

  companion object {
    const val DEFAULT_REMOTE_PORT = 6466
    private const val FEATURE_CODE_FULL = 622
    private const val CONNECT_TIMEOUT_MS = 8_000

    fun isCertificateOrHandshakeError(error: SSLException): Boolean {
      if (error is SSLHandshakeException) return true
      val message = error.message?.uppercase().orEmpty()
      return message.contains("CERTIFICATE_UNKNOWN") ||
        message.contains("BAD_CERTIFICATE") ||
        message.contains("UNKNOWN_CA") ||
        message.contains("CERTIFICATE_REQUIRED") ||
        message.contains("CERTIFICATE_REVOKED") ||
        message.contains("CERTIFICATE_EXPIRED") ||
        message.contains("SSLV3_ALERT") ||
        message.contains("HANDSHAKE") ||
        message.contains("ALERT")
    }

    fun charToRemoteKeyCode(ch: Char): RemoteKeyCode? {
      return when (ch.lowercaseChar()) {
        'a' -> RemoteKeyCode.KEYCODE_A
        'b' -> RemoteKeyCode.KEYCODE_B
        'c' -> RemoteKeyCode.KEYCODE_C
        'd' -> RemoteKeyCode.KEYCODE_D
        'e' -> RemoteKeyCode.KEYCODE_E
        'f' -> RemoteKeyCode.KEYCODE_F
        'g' -> RemoteKeyCode.KEYCODE_G
        'h' -> RemoteKeyCode.KEYCODE_H
        'i' -> RemoteKeyCode.KEYCODE_I
        'j' -> RemoteKeyCode.KEYCODE_J
        'k' -> RemoteKeyCode.KEYCODE_K
        'l' -> RemoteKeyCode.KEYCODE_L
        'm' -> RemoteKeyCode.KEYCODE_M
        'n' -> RemoteKeyCode.KEYCODE_N
        'o' -> RemoteKeyCode.KEYCODE_O
        'p' -> RemoteKeyCode.KEYCODE_P
        'q' -> RemoteKeyCode.KEYCODE_Q
        'r' -> RemoteKeyCode.KEYCODE_R
        's' -> RemoteKeyCode.KEYCODE_S
        't' -> RemoteKeyCode.KEYCODE_T
        'u' -> RemoteKeyCode.KEYCODE_U
        'v' -> RemoteKeyCode.KEYCODE_V
        'w' -> RemoteKeyCode.KEYCODE_W
        'x' -> RemoteKeyCode.KEYCODE_X
        'y' -> RemoteKeyCode.KEYCODE_Y
        'z' -> RemoteKeyCode.KEYCODE_Z
        '0' -> RemoteKeyCode.KEYCODE_0
        '1' -> RemoteKeyCode.KEYCODE_1
        '2' -> RemoteKeyCode.KEYCODE_2
        '3' -> RemoteKeyCode.KEYCODE_3
        '4' -> RemoteKeyCode.KEYCODE_4
        '5' -> RemoteKeyCode.KEYCODE_5
        '6' -> RemoteKeyCode.KEYCODE_6
        '7' -> RemoteKeyCode.KEYCODE_7
        '8' -> RemoteKeyCode.KEYCODE_8
        '9' -> RemoteKeyCode.KEYCODE_9
        ' ' -> RemoteKeyCode.KEYCODE_SPACE
        ',' -> RemoteKeyCode.KEYCODE_COMMA
        '.' -> RemoteKeyCode.KEYCODE_PERIOD
        '-' -> RemoteKeyCode.KEYCODE_MINUS
        '=' -> RemoteKeyCode.KEYCODE_EQUALS
        '\'' -> RemoteKeyCode.KEYCODE_APOSTROPHE
        '/' -> RemoteKeyCode.KEYCODE_SLASH
        '@' -> RemoteKeyCode.KEYCODE_AT
        '\n' -> RemoteKeyCode.KEYCODE_ENTER
        else -> null
      }
    }
  }
}
