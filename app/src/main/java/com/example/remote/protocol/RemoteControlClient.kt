package com.example.remote.protocol

import android.content.Context
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
import com.example.proto.remote.RemotePingRequest
import com.example.proto.remote.RemotePingResponse
import com.example.proto.remote.RemoteSetActive
import com.example.proto.remote.RemoteVoiceBegin
import com.example.proto.remote.RemoteVoiceEnd
import com.example.proto.remote.RemoteVoicePayload
import com.example.remote.data.OttLaunchPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

data class TvImeState(
  val imeCounter: Int = 0,
  val fieldCounter: Int = 0,
  val currentText: String = "",
  val fieldLabel: String = "",
  val appLabel: String = "",
  val appPackage: String = "",
  val isImeRequested: Boolean = false,
)

data class DetectedTvAppSignal(
  val packageName: String,
  val label: String,
)

enum class OttLaunchOutcome {
  LAUNCHED_NATIVE_APP,
  OPENED_IN_BROWSER,
  FAILED,
}

class RemoteControlClient(
  private val certificateManager: CertificateManager,
  private val appContext: Context? = null,
  private val keepaliveIntervalMs: Long = DEFAULT_KEEPALIVE_INTERVAL_MS,
  private val socketReadTimeoutMs: Int = DEFAULT_SOCKET_READ_TIMEOUT_MS,
  private val staleConnectionTimeoutMs: Long = DEFAULT_STALE_CONNECTION_TIMEOUT_MS,
) {

  sealed class SessionEvent {
    data object Disconnected : SessionEvent()
    data object Connecting : SessionEvent()
    data object Connected : SessionEvent()
    data class PairingRequired(
      val reason: String,
      val eventId: Long = nextEventId(),
    ) : SessionEvent()

    data class ConnectionLost(
      val message: String,
      val eventId: Long = nextEventId(),
    ) : SessionEvent()

    data class Error(
      val message: String,
      val eventId: Long = nextEventId(),
    ) : SessionEvent()
  }

  private val _sessionEvent = MutableStateFlow<SessionEvent>(SessionEvent.Disconnected)
  val sessionEvent: StateFlow<SessionEvent> = _sessionEvent.asStateFlow()

  private val _imeState = MutableStateFlow(TvImeState())
  val imeState: StateFlow<TvImeState> = _imeState.asStateFlow()

  private val _appLinkErrors = MutableSharedFlow<String>(extraBufferCapacity = 32)
  val appLinkErrors: SharedFlow<String> = _appLinkErrors.asSharedFlow()

  private val _voiceSessionRequest = MutableStateFlow<Int?>(null)
  val voiceSessionRequest: StateFlow<Int?> = _voiceSessionRequest.asStateFlow()

  private val _detectedTvApp = MutableSharedFlow<DetectedTvAppSignal>(extraBufferCapacity = 16)
  val detectedTvApp: SharedFlow<DetectedTvAppSignal> = _detectedTvApp.asSharedFlow()

  private val writeMutex = Mutex()
  private val appLinkLaunchMutex = Mutex()
  private var scope: CoroutineScope? = null
  private var readerJob: Job? = null
  private var keepaliveJob: Job? = null
  @Volatile private var socket: SSLSocket? = null
  @Volatile private var outputStream: OutputStream? = null
  @Volatile private var lastInboundAtMs: Long = 0L
  @Volatile private var serverSendsPings: Boolean = false
  @Volatile private var serverAnswersClientPings: Boolean = false
  @Volatile private var activeAppLinkProbe: String? = null
  private val unansweredClientPings = AtomicInteger(0)
  private val pingSequence = AtomicInteger(1)

  fun isConnectionHealthy(): Boolean {
    val sock = socket ?: return false
    return !sock.isClosed &&
      sock.isConnected &&
      outputStream != null &&
      _sessionEvent.value is SessionEvent.Connected
  }

  fun millisSinceLastInbound(): Long {
    val last = lastInboundAtMs
    if (last <= 0L) return Long.MAX_VALUE
    return (System.currentTimeMillis() - last).coerceAtLeast(0L)
  }

  fun isStale(maxSilenceMs: Long): Boolean {
    if (!isConnectionHealthy()) return true
    return millisSinceLastInbound() > maxSilenceMs
  }

  suspend fun connect(host: String, port: Int = DEFAULT_REMOTE_PORT) {
    teardownSessionOnly()
    _sessionEvent.value = SessionEvent.Connecting
    _imeState.value = TvImeState()
    _voiceSessionRequest.value = null

    val connectSuccess =
      withContext(Dispatchers.IO) {
        var plainSocket: java.net.Socket? = null
        var sslSocket: SSLSocket? = null
        try {
          val tls = certificateManager.createTlsSessionContext()
          plainSocket =
            WifiNetworkBinder.createPlainSocket(appContext, host).apply {
              keepAlive = true
              tcpNoDelay = true
              soTimeout = CONNECT_TIMEOUT_MS
              connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
          sslSocket =
            (tls.sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket).apply {
              useClientMode = true
              keepAlive = true
              tcpNoDelay = true
              soTimeout = HANDSHAKE_TIMEOUT_MS
              startHandshake()
              soTimeout = socketReadTimeoutMs
            }
          socket = sslSocket
          outputStream = sslSocket.outputStream
          lastInboundAtMs = System.currentTimeMillis()
          serverSendsPings = false
          serverAnswersClientPings = false
          unansweredClientPings.set(0)
          activeAppLinkProbe = null

          val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          scope = sessionScope
          readerJob =
            sessionScope.launch {
              runReaderLoop(sslSocket, sessionScope)
            }
          keepaliveJob =
            sessionScope.launch {
              runKeepaliveLoop(sslSocket, sessionScope)
            }
          true
        } catch (e: SSLException) {
          runCatching { sslSocket?.close() }
          runCatching { plainSocket?.close() }
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "TV requires pairing before remote control access (${e.message ?: "TLS rejected"})."
            )
          false
        } catch (e: kotlinx.coroutines.CancellationException) {
          runCatching { sslSocket?.close() }
          runCatching { plainSocket?.close() }
          throw e
        } catch (t: Throwable) {
          runCatching { sslSocket?.close() }
          runCatching { plainSocket?.close() }
          if (t is VirtualMachineError) throw t
          _sessionEvent.value =
            SessionEvent.Error(t.message ?: "Unable to connect to $host:$port.")
          false
        }
      }

    if (!connectSuccess) {
      cleanupSocketOnly()
    }
  }

  private suspend fun runKeepaliveLoop(sslSocket: SSLSocket, sessionScope: CoroutineScope) {
    while (sessionScope.isActive && !sslSocket.isClosed && socket === sslSocket) {
      delay(keepaliveIntervalMs)
      if (!sessionScope.isActive || sslSocket.isClosed || socket !== sslSocket) break
      if (_sessionEvent.value !is SessionEvent.Connected) continue

      val silentMs = System.currentTimeMillis() - lastInboundAtMs

      // Real Android TV Remote v2 servers send RemotePingRequest every ~5s.
      // When the server drives heartbeats, never spam unsolicited client pings;
      // only fail if the server goes completely silent past the stale timeout.
      if (serverSendsPings) {
        if (silentMs >= staleConnectionTimeoutMs) {
          if (sessionScope.isActive && socket === sslSocket) {
            _sessionEvent.value =
              SessionEvent.ConnectionLost("TV heartbeat timed out after ${silentMs}ms of silence.")
            cleanupSocketOnly(expectedSocket = sslSocket)
          }
          break
        }
        continue
      }

      // Fallback for servers that do not send server-initiated pings:
      // only probe when the socket has been silent for at least one keepalive interval.
      if (silentMs < keepaliveIntervalMs) {
        continue
      }
      if (
        serverAnswersClientPings &&
        unansweredClientPings.get() >= MIN_UNANSWERED_PINGS_FOR_STALE &&
        silentMs >= staleConnectionTimeoutMs
      ) {
        if (sessionScope.isActive && socket === sslSocket) {
          _sessionEvent.value =
            SessionEvent.ConnectionLost("TV did not respond to keepalive heartbeat.")
          cleanupSocketOnly(expectedSocket = sslSocket)
        }
        break
      }

      if (!sendKeepalivePing(sslSocket)) {
        break
      }
    }
  }

  private suspend fun sendKeepalivePing(targetSocket: SSLSocket? = null): Boolean {
    unansweredClientPings.incrementAndGet()
    val ping =
      RemoteMessage.newBuilder()
        .setRemotePingRequest(
          RemotePingRequest.newBuilder()
            .setVal1(pingSequence.getAndIncrement())
            .build()
        )
        .build()
    return sendRemoteMessage(ping, expectedSocket = targetSocket)
  }

  private suspend fun runReaderLoop(sslSocket: SSLSocket, sessionScope: CoroutineScope) {
    val input = sslSocket.inputStream
    var hasReceivedMessage = false
    var sentConfigureReply = false
    var repliedToServerSetActive = false
    var negotiatedFeatures = DEFAULT_FEATURE_CODE
    try {
      while (sessionScope.isActive && !sslSocket.isClosed && socket === sslSocket) {
        val frameBytes =
          try {
            DelimitedStreamHelper.readDelimitedBytes(input) ?: break
          } catch (_: SocketTimeoutException) {
            if (!sessionScope.isActive || sslSocket.isClosed || socket !== sslSocket) break
            if (_sessionEvent.value is SessionEvent.Connected) {
              val silentMs = System.currentTimeMillis() - lastInboundAtMs
              if (serverSendsPings) {
                if (silentMs >= staleConnectionTimeoutMs) {
                  throw IOException("TV heartbeat timed out after ${silentMs}ms of silence.")
                }
              } else {
                if (
                  serverAnswersClientPings &&
                  unansweredClientPings.get() >= MIN_UNANSWERED_PINGS_FOR_STALE &&
                  silentMs >= staleConnectionTimeoutMs
                ) {
                  throw IOException("TV connection timed out after ${silentMs}ms without heartbeat.")
                }
                if (silentMs >= keepaliveIntervalMs && !sendKeepalivePing(sslSocket)) {
                  break
                }
              }
            }
            continue
          }

        lastInboundAtMs = System.currentTimeMillis()
        unansweredClientPings.set(0)
        hasReceivedMessage = true
        val message = RemoteMessage.parseFrom(frameBytes)

        when {
          message.hasRemoteConfigure() -> {
            negotiatedFeatures = negotiateFeatureCode(message.remoteConfigure.code1)
            if (!sentConfigureReply) {
              sentConfigureReply = true
              sendConfigureReply(negotiatedFeatures, sslSocket)
            }
            if (_sessionEvent.value != SessionEvent.Connected) {
              _sessionEvent.value = SessionEvent.Connected
            }
          }
          message.hasRemoteSetActive() -> {
            val serverActive = message.remoteSetActive.active
            val negotiatedActive =
              if (serverActive > 0) negotiateFeatureCode(serverActive) else negotiatedFeatures
            if (!repliedToServerSetActive) {
              repliedToServerSetActive = true
              sendSetActiveReply(negotiatedActive, sslSocket)
            }
            if (_sessionEvent.value != SessionEvent.Connected) {
              _sessionEvent.value = SessionEvent.Connected
            }
          }
          message.hasRemotePingRequest() -> {
            serverSendsPings = true
            val pingVal = message.remotePingRequest.val1
            val pong =
              RemoteMessage.newBuilder()
                .setRemotePingResponse(
                  RemotePingResponse.newBuilder()
                    .setVal1(pingVal)
                    .build()
                )
                .build()
            sendRemoteMessage(pong, expectedSocket = sslSocket)
          }
          message.hasRemotePingResponse() -> {
            serverAnswersClientPings = true
            unansweredClientPings.set(0)
          }
          message.hasRemoteImeKeyInject() -> {
            val imeInject = message.remoteImeKeyInject
            val status = imeInject.textFieldStatus
            val appPkg = imeInject.appInfo.appPackage.trim()
            val appLbl = imeInject.appInfo.label.trim()
            _imeState.update { current ->
              current.copy(
                imeCounter = imeInject.appInfo.counter,
                fieldCounter = status.counterField,
                currentText = status.value,
                fieldLabel = status.label.ifBlank { current.fieldLabel },
                appLabel = appLbl.ifBlank { current.appLabel },
                appPackage = appPkg.ifBlank { current.appPackage },
              )
            }
            if (appPkg.isNotEmpty()) {
              _detectedTvApp.tryEmit(DetectedTvAppSignal(packageName = appPkg, label = appLbl))
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
          message.hasRemoteVoiceBegin() -> {
            _voiceSessionRequest.value = message.remoteVoiceBegin.sessionId
          }
          message.hasRemoteVoiceEnd() -> {
            _voiceSessionRequest.value = null
          }
          message.hasRemoteError() -> {
            val err = message.remoteError
            val echoedLink =
              if (err.hasMessage() && err.message.hasRemoteAppLinkLaunchRequest()) {
                err.message.remoteAppLinkLaunchRequest.appLink.trim()
              } else {
                ""
              }
            val currentProbe = activeAppLinkProbe
            when {
              echoedLink.isNotEmpty() -> {
                _appLinkErrors.tryEmit(echoedLink)
              }
              currentProbe != null -> {
                // TV rejected the active OTT launch probe without echoing app_link; treat as non-fatal
                _appLinkErrors.tryEmit(currentProbe)
              }
              _sessionEvent.value is SessionEvent.Connected -> {
                // Non-fatal command error on an already established session: keep TLS socket open
              }
              else -> {
                _sessionEvent.value = SessionEvent.Error("Remote session error reported by TV.")
                break
              }
            }
          }
        }
      }
      if (sessionScope.isActive && socket === sslSocket) {
        if (!hasReceivedMessage && _sessionEvent.value == SessionEvent.Connecting) {
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "TV closed the remote channel before handshake completed. Starting pairing..."
            )
        } else if (
          _sessionEvent.value == SessionEvent.Connected ||
          _sessionEvent.value == SessionEvent.Connecting
        ) {
          _sessionEvent.value =
            SessionEvent.ConnectionLost("TV connection closed while idle or switching apps.")
        }
      }
    } catch (ce: kotlinx.coroutines.CancellationException) {
      throw ce
    } catch (e: SSLException) {
      if (sessionScope.isActive && socket === sslSocket) {
        if (isCertificateOrHandshakeError(e) || !hasReceivedMessage) {
          _sessionEvent.value =
            SessionEvent.PairingRequired(
              "Certificate not trusted by TV (${e.message ?: "SSLException"}). Starting pairing..."
            )
        } else {
          _sessionEvent.value =
            SessionEvent.ConnectionLost(e.message ?: "TLS connection to TV was interrupted.")
        }
      }
    } catch (t: Throwable) {
      if (t is VirtualMachineError) throw t
      if (sessionScope.isActive && socket === sslSocket) {
        _sessionEvent.value =
          SessionEvent.ConnectionLost(t.message ?: "Connection to TV was interrupted.")
      }
    } finally {
      cleanupSocketOnly(expectedSocket = sslSocket)
    }
  }

  private suspend fun sendConfigureReply(featureCode: Int, targetSocket: SSLSocket) {
    val modelName = Build.MODEL.orEmpty().ifBlank { "Android" }
    val vendorName = Build.MANUFACTURER.orEmpty().ifBlank { "Android" }
    val deviceInfo =
      RemoteDeviceInfo.newBuilder()
        .setModel(modelName)
        .setVendor(vendorName)
        .setUnknown1(1)
        .setUnknown2("1")
        .setPackageName("in.yourview.yvtvremote")
        .setAppVersion("1.0")
        .build()

    val configureReply =
      RemoteMessage.newBuilder()
        .setRemoteConfigure(
          RemoteConfigure.newBuilder()
            .setCode1(featureCode)
            .setDeviceInfo(deviceInfo)
            .build()
        )
        .build()
    sendRemoteMessage(configureReply, expectedSocket = targetSocket)
  }

  private suspend fun sendSetActiveReply(activeFeatures: Int, targetSocket: SSLSocket) {
    val setActiveReply =
      RemoteMessage.newBuilder()
        .setRemoteSetActive(
          RemoteSetActive.newBuilder()
            .setActive(activeFeatures)
            .build()
        )
        .build()
    sendRemoteMessage(setActiveReply, expectedSocket = targetSocket)
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

  suspend fun sendVoiceBegin(sessionId: Int): Boolean {
    val message =
      RemoteMessage.newBuilder()
        .setRemoteVoiceBegin(
          RemoteVoiceBegin.newBuilder()
            .setSessionId(sessionId)
            .build()
        )
        .build()
    return sendRemoteMessage(message)
  }

  suspend fun sendVoicePayload(pcmData: ByteArray): Boolean {
    if (pcmData.isEmpty()) return true
    val message =
      RemoteMessage.newBuilder()
        .setRemoteVoicePayload(
          RemoteVoicePayload.newBuilder()
            .setData(com.google.protobuf.ByteString.copyFrom(pcmData))
            .build()
        )
        .build()
    return sendRemoteMessage(message)
  }

  suspend fun sendVoiceEnd(sessionId: Int): Boolean {
    val message =
      RemoteMessage.newBuilder()
        .setRemoteVoiceEnd(
          RemoteVoiceEnd.newBuilder()
            .setSessionId(sessionId)
            .build()
        )
        .build()
    return sendRemoteMessage(message)
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

  suspend fun launchAppWithPlan(
    plan: OttLaunchPlan,
    errorWaitMs: Long = NATIVE_LAUNCH_ERROR_WAIT_MS,
  ): OttLaunchOutcome =
    appLinkLaunchMutex.withLock {
      coroutineScope {
        for (candidate in plan.nativeCandidates) {
          val trimmedCandidate = candidate.trim()
          activeAppLinkProbe = trimmedCandidate
          try {
            val errorMonitor =
              async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(errorWaitMs) {
                  appLinkErrors.first { it.trim() == trimmedCandidate }
                } != null
              }
            val sent = launchApp(candidate)
            if (!sent) {
              errorMonitor.cancel()
              return@coroutineScope OttLaunchOutcome.FAILED
            }
            val rejectedByTv = errorMonitor.await()
            if (!rejectedByTv) {
              return@coroutineScope OttLaunchOutcome.LAUNCHED_NATIVE_APP
            }
          } finally {
            if (activeAppLinkProbe == trimmedCandidate) {
              activeAppLinkProbe = null
            }
          }
        }

        val fallback = plan.browserFallback
        if (!fallback.isNullOrBlank()) {
          return@coroutineScope if (launchApp(fallback)) {
            OttLaunchOutcome.OPENED_IN_BROWSER
          } else {
            OttLaunchOutcome.FAILED
          }
        }

        OttLaunchOutcome.FAILED
      }
    }

  private suspend fun sendRemoteMessage(
    message: RemoteMessage,
    expectedSocket: SSLSocket? = null,
  ): Boolean =
    withContext(Dispatchers.IO) {
      writeMutex.withLock {
        val sock = socket
        val out = outputStream
        if (expectedSocket != null && sock !== expectedSocket) {
          return@withLock false
        }
        if (out == null || sock == null || sock.isClosed || !sock.isConnected) {
          if (_sessionEvent.value is SessionEvent.Connected) {
            _sessionEvent.value = SessionEvent.ConnectionLost("Remote socket disconnected.")
          }
          if (sock != null) {
            cleanupSocketOnly(expectedSocket = sock)
          }
          return@withLock false
        }
        try {
          DelimitedStreamHelper.writeMessage(out, message)
          true
        } catch (ce: kotlinx.coroutines.CancellationException) {
          throw ce
        } catch (e: SSLException) {
          _sessionEvent.value =
            if (isCertificateOrHandshakeError(e)) {
              SessionEvent.PairingRequired(
                "Certificate rejected by TV (${e.message ?: "SSLException"}). Starting pairing..."
              )
            } else {
              SessionEvent.ConnectionLost(e.message ?: "TLS session interrupted.")
            }
          cleanupSocketOnly(expectedSocket = sock)
          false
        } catch (e: Exception) {
          _sessionEvent.value =
            SessionEvent.ConnectionLost(e.message ?: "Failed to send command to TV.")
          cleanupSocketOnly(expectedSocket = sock)
          false
        }
      }
    }

  private fun teardownSessionOnly() {
    keepaliveJob?.cancel()
    keepaliveJob = null
    readerJob?.cancel()
    readerJob = null
    scope?.cancel()
    scope = null
    cleanupSocketOnly()
  }

  fun disconnect() {
    teardownSessionOnly()
    _imeState.value = TvImeState()
    _voiceSessionRequest.value = null
    _sessionEvent.value = SessionEvent.Disconnected
  }

  private fun cleanupSocketOnly(expectedSocket: SSLSocket? = null) {
    val current = socket
    if (expectedSocket != null && current !== expectedSocket) {
      return
    }
    activeAppLinkProbe = null
    _voiceSessionRequest.value = null
    outputStream = null
    try {
      current?.close()
    } catch (_: Exception) {
    }
    socket = null
  }

  companion object {
    const val DEFAULT_REMOTE_PORT = 6466
    const val NATIVE_LAUNCH_ERROR_WAIT_MS = 420L
    const val DEFAULT_KEEPALIVE_INTERVAL_MS = 25_000L
    const val DEFAULT_SOCKET_READ_TIMEOUT_MS = 30_000
    const val DEFAULT_STALE_CONNECTION_TIMEOUT_MS = 45_000L
    private const val MIN_UNANSWERED_PINGS_FOR_STALE = 2
    const val DEFAULT_FEATURE_CODE = 622
    const val FEATURE_CODE_FULL = 639
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private val eventSequence = AtomicLong(1L)

    private fun nextEventId(): Long = eventSequence.getAndIncrement()

    fun negotiateFeatureCode(serverFeatureCode: Int): Int {
      if (serverFeatureCode <= 0) return DEFAULT_FEATURE_CODE
      val intersection = serverFeatureCode and FEATURE_CODE_FULL
      return if (intersection > 0) intersection else DEFAULT_FEATURE_CODE
    }

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
