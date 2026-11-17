package com.example.remote.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.proto.remote.RemoteDirection
import com.example.proto.remote.RemoteKeyCode
import androidx.compose.ui.graphics.Color
import com.example.remote.data.AppSettings
import com.example.remote.data.CustomOttIconStyle
import com.example.remote.data.PreferredPadMode
import com.example.remote.data.RemotePreferencesStore
import com.example.remote.data.RemoteUiPreferences
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts
import com.example.remote.data.UiLayoutPreset
import com.example.remote.data.UserProfile
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.RemoteAccentTheme
import com.example.remote.discovery.DiscoveredTv
import com.example.remote.discovery.TvDiscoveryManager
import com.example.remote.feedback.FeedbackCue
import com.example.remote.feedback.FeedbackManager
import com.example.remote.protocol.CertificateManager
import com.example.remote.protocol.OttLaunchOutcome
import com.example.remote.protocol.PairingClient
import com.example.remote.protocol.RemoteControlClient
import com.example.remote.protocol.TvImeState
import com.example.remote.update.AppReleaseInfo
import com.example.remote.update.AppUpdateManager
import com.example.remote.update.UpdateCheckJobService
import com.example.remote.update.UpdateCheckResult
import com.example.remote.voice.ParsedVoiceCommand
import com.example.remote.voice.TvVoiceAudioStreamer
import com.example.remote.voice.VoiceCommandParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class RemoteInputMode {
  DPAD,
  TOUCHPAD,
}

sealed class ConnectionStatus {
  data object Disconnected : ConnectionStatus()
  data class Connecting(val targetName: String, val ipAddress: String, val port: Int) :
    ConnectionStatus()

  data class Pairing(
    val targetIp: String,
    val controlPort: Int,
    val defaultName: String,
    val customName: String,
    val serverName: String,
    val errorMessage: String? = null,
    val isSubmitting: Boolean = false,
  ) : ConnectionStatus()

  data class Connected(
    val displayName: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
  ) : ConnectionStatus()

  data class Error(
    val message: String,
    val targetIp: String? = null,
    val targetPort: Int = RemoteControlClient.DEFAULT_REMOTE_PORT,
    val targetName: String? = null,
    val isRapidDropLoop: Boolean = false,
    val diagnosticDetail: String? = null,
  ) : ConnectionStatus()
}

data class PendingConnectionTarget(
  val ipAddress: String,
  val controlPort: Int,
  val deviceName: String,
  val customName: String,
  val profileId: String,
)

data class VoiceInputUiState(
  val showVoiceSheet: Boolean = false,
  val isListening: Boolean = false,
  val rmsLevel: Float = 0f,
  val partialTranscript: String = "",
  val lastTranscript: String = "",
  val executedActionLabel: String? = null,
  val errorMessage: String? = null,
  val isTvStreamingVoice: Boolean = false,
)

data class RemoteUiState(
  val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
  val discoveredTvs: List<DiscoveredTv> = emptyList(),
  val isScanning: Boolean = false,
  val scanError: String? = null,
  val profiles: List<UserProfile> = emptyList(),
  val activeProfileId: String = RemotePreferencesStore.DEFAULT_PROFILE_ID,
  val savedConnections: List<SavedTvConnection> = emptyList(),
  val settings: AppSettings = AppSettings(),
  val uiPreferences: RemoteUiPreferences = RemoteUiPreferences(),
  val inputMode: RemoteInputMode = RemoteInputMode.DPAD,
  val showConnectionHub: Boolean = true,
  val showSettingsSheet: Boolean = false,
  val showCustomizeUiSheet: Boolean = false,
  val showKeyboardSheet: Boolean = false,
  val tvImeState: TvImeState = TvImeState(),
  val voiceState: VoiceInputUiState = VoiceInputUiState(),
  val statusBannerMessage: String? = null,
  val availableUpdate: AppReleaseInfo? = null,
  val showUpdateDialog: Boolean = false,
  val isCheckingUpdates: Boolean = false,
  val updateStatusMessage: String? = null,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

  private val certificateManager = CertificateManager(application)
  private val discoveryManager = TvDiscoveryManager(application)
  private val pairingClient = PairingClient(certificateManager)
  private val remoteClient = RemoteControlClient(certificateManager)
  private val prefsStore = RemotePreferencesStore(application)
  private val feedbackManager = FeedbackManager(application)

  private val _uiState = MutableStateFlow(RemoteUiState())
  val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

  private var activePairingSession: PairingClient.ActivePairingSession? = null
  private var pendingTarget: PendingConnectionTarget? = null
  private var lastActiveTarget: PendingConnectionTarget? = null
  private var isAppInForeground = true
  private var userManuallyDisconnected = false
  private var navigateToRemoteOnConnect = true
  private var autoConnectAttempted = false
  private var postPairingReconnectRetryAvailable = false
  private var unexpectedDisconnectRetryCount = 0
  private var rapidDropCount = 0
  private var lastConnectedAtMs = 0L
  private var autoReconnectPaused = false
  private var activeHeldKey: RemoteKeyCode? = null
  private var autoReconnectJob: Job? = null
  private var connectionStabilityJob: Job? = null
  private var ottLaunchJob: Job? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var hasSeenInitialNetworkCallback = false
  private val reconnectMutex = Mutex()

  private val tvVoiceAudioStreamer =
    TvVoiceAudioStreamer(
      context = application,
      scope = viewModelScope,
      sendBegin = { sessionId -> remoteClient.sendVoiceBegin(sessionId) },
      sendPayload = { bytes -> remoteClient.sendVoicePayload(bytes) },
      sendEnd = { sessionId -> remoteClient.sendVoiceEnd(sessionId) },
      onStopped = {
        _uiState.update { state ->
          state.copy(voiceState = state.voiceState.copy(isTvStreamingVoice = false))
        }
      },
    )

  init {
    loadStoredState()
    observeDiscovery()
    observeRemoteSession()
    observeTvImeState()
    observeVoiceSessionRequests()
    registerNetworkCallback()
    attemptAutoReconnectIfEnabled()
    syncAndCheckUpdatesOnStartup()
  }

  private fun observeTvImeState() {
    viewModelScope.launch {
      remoteClient.imeState.collect { ime ->
        _uiState.update { it.copy(tvImeState = ime) }
      }
    }
  }

  private fun observeVoiceSessionRequests() {
    viewModelScope.launch {
      remoteClient.voiceSessionRequest.collect { sessionId ->
        if (sessionId != null) {
          _uiState.update { state ->
            state.copy(voiceState = state.voiceState.copy(isTvStreamingVoice = true))
          }
          tvVoiceAudioStreamer.startStreaming(sessionId)
        } else {
          tvVoiceAudioStreamer.stopStreaming()
          _uiState.update { state ->
            state.copy(voiceState = state.voiceState.copy(isTvStreamingVoice = false))
          }
        }
      }
    }
  }

  private fun loadStoredState() {
    val profiles = prefsStore.loadProfiles()
    val activeProfileId =
      prefsStore.getActiveProfileId().let { storedId ->
        if (profiles.any { it.id == storedId }) storedId else profiles.first().id
      }
    val saved = prefsStore.loadSavedConnections()
    val settings = prefsStore.loadSettings()
    val uiPrefs = prefsStore.loadUiPreferences()
    val initialMode =
      if (uiPrefs.defaultPadMode == PreferredPadMode.TOUCHPAD) RemoteInputMode.TOUCHPAD
      else RemoteInputMode.DPAD

    _uiState.update {
      it.copy(
        profiles = profiles,
        activeProfileId = activeProfileId,
        savedConnections = saved,
        settings = settings,
        uiPreferences = uiPrefs,
        inputMode = initialMode,
      )
    }
  }

  private fun observeDiscovery() {
    viewModelScope.launch {
      discoveryManager.discoveredTvs.collect { list ->
        _uiState.update { it.copy(discoveredTvs = list) }
      }
    }
    viewModelScope.launch {
      discoveryManager.isScanning.collect { scanning ->
        _uiState.update { it.copy(isScanning = scanning) }
      }
    }
    viewModelScope.launch {
      discoveryManager.scanError.collect { err ->
        _uiState.update { it.copy(scanError = err) }
      }
    }
  }

  private fun observeRemoteSession() {
    viewModelScope.launch {
      remoteClient.sessionEvent.collect { event ->
        when (event) {
          is RemoteControlClient.SessionEvent.Disconnected -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            unexpectedDisconnectRetryCount = 0
            rapidDropCount = 0
            lastConnectedAtMs = 0L
            _uiState.update { state ->
              if (state.connectionStatus is ConnectionStatus.Pairing) {
                state
              } else {
                state.copy(connectionStatus = ConnectionStatus.Disconnected)
              }
            }
          }

          is RemoteControlClient.SessionEvent.Connecting -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            val target = pendingTarget
            _uiState.update {
              it.copy(
                connectionStatus =
                  ConnectionStatus.Connecting(
                    targetName = target?.customName ?: target?.deviceName ?: "Android TV",
                    ipAddress = target?.ipAddress ?: "",
                    port = target?.controlPort ?: RemoteControlClient.DEFAULT_REMOTE_PORT,
                  )
              )
            }
          }

          is RemoteControlClient.SessionEvent.Connected -> {
            postPairingReconnectRetryAvailable = false
            autoReconnectPaused = false
            lastConnectedAtMs = System.currentTimeMillis()
            scheduleConnectionStabilityReset()
            val target = pendingTarget ?: lastActiveTarget
            if (target != null) {
              lastActiveTarget = target
              val saved =
                prefsStore.upsertConnection(
                  profileId = target.profileId,
                  customName = target.customName,
                  deviceName = target.deviceName,
                  ipAddress = target.ipAddress,
                  port = target.controlPort,
                )
              prefsStore.setLastConnectedTvId(saved.id)
              _uiState.update {
                it.copy(
                  savedConnections = prefsStore.loadSavedConnections(),
                  connectionStatus =
                    ConnectionStatus.Connected(
                      displayName = saved.customName,
                      deviceName = saved.deviceName,
                      ipAddress = saved.ipAddress,
                      port = saved.port,
                    ),
                  showConnectionHub = if (navigateToRemoteOnConnect) false else it.showConnectionHub,
                  statusBannerMessage = null,
                )
              }
            }
          }

          is RemoteControlClient.SessionEvent.PairingRequired -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            unexpectedDisconnectRetryCount = 0
            rapidDropCount = 0
            lastConnectedAtMs = 0L
            autoReconnectPaused = false
            val target = pendingTarget
            if (target != null) {
              if (postPairingReconnectRetryAvailable) {
                postPairingReconnectRetryAvailable = false
                delay(500L)
                reconnectMutex.withLock {
                  remoteClient.connect(target.ipAddress, target.controlPort)
                }
              } else {
                initiatePairing(target)
              }
            }
          }

          is RemoteControlClient.SessionEvent.ConnectionLost -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            postPairingReconnectRetryAvailable = false
            handleUnexpectedSessionInterruption(event.message)
          }

          is RemoteControlClient.SessionEvent.Error -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            postPairingReconnectRetryAvailable = false
            handleUnexpectedSessionInterruption(event.message)
          }
        }
      }
    }
  }

  private fun scheduleConnectionStabilityReset() {
    connectionStabilityJob?.cancel()
    connectionStabilityJob =
      viewModelScope.launch {
        delay(STABLE_CONNECTION_WINDOW_MS)
        if (remoteClient.isConnectionHealthy()) {
          unexpectedDisconnectRetryCount = 0
          rapidDropCount = 0
          autoReconnectPaused = false
        }
      }
  }

  private fun handleUnexpectedSessionInterruption(errorMessage: String) {
    val state = _uiState.value
    if (state.connectionStatus is ConnectionStatus.Pairing) {
      return
    }
    val connectedDurationMs =
      if (lastConnectedAtMs > 0L) (System.currentTimeMillis() - lastConnectedAtMs).coerceAtLeast(0L)
      else -1L
    if (connectedDurationMs in 0L until STABLE_CONNECTION_WINDOW_MS) {
      rapidDropCount++
    }
    lastConnectedAtMs = 0L

    val target = pendingTarget ?: lastActiveTarget
    if (userManuallyDisconnected || target == null || !state.settings.autoReconnectEnabled) {
      _uiState.update {
        it.copy(
          connectionStatus =
            if (userManuallyDisconnected || target == null) {
              ConnectionStatus.Disconnected
            } else {
              ConnectionStatus.Error(
                message = errorMessage,
                targetIp = target.ipAddress,
                targetPort = target.controlPort,
                targetName = target.customName,
              )
            }
        )
      }
      return
    }
    if (!isAppInForeground) {
      // Hold quietly while backgrounded; onAppForegrounded() will immediately resume.
      _uiState.update {
        it.copy(connectionStatus = ConnectionStatus.Disconnected)
      }
      return
    }
    val hitRapidDropCap = rapidDropCount >= MAX_RAPID_DROP_CYCLES
    val hitAttemptCap = unexpectedDisconnectRetryCount >= MAX_AUTO_RECONNECT_ATTEMPTS
    if (autoReconnectPaused || hitRapidDropCap || hitAttemptCap) {
      autoReconnectPaused = true
      autoReconnectJob?.cancel()
      autoReconnectJob = null
      val displayMsg =
        if (hitRapidDropCap) {
          "Connection kept dropping right after connecting (${rapidDropCount}x). Auto-retry paused."
        } else {
          errorMessage
        }
      val detail =
        if (hitRapidDropCap) {
          "Last cause: $errorMessage. Tap Re-pair TV to refresh the pairing certificate or Retry to reconnect."
        } else {
          errorMessage
        }
      _uiState.update {
        it.copy(
          connectionStatus =
            ConnectionStatus.Error(
              message = displayMsg,
              targetIp = target.ipAddress,
              targetPort = target.controlPort,
              targetName = target.customName,
              isRapidDropLoop = hitRapidDropCap,
              diagnosticDetail = detail,
            )
        )
      }
      return
    }
    val backoffIndex = unexpectedDisconnectRetryCount.coerceAtMost(AUTO_RECONNECT_BACKOFF_MS.lastIndex)
    val backoffMs = AUTO_RECONNECT_BACKOFF_MS[backoffIndex]
    unexpectedDisconnectRetryCount++
    scheduleSilentReconnect(target, delayMs = backoffMs)
  }

  private fun registerNetworkCallback() {
    try {
      val cm =
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE)
          as? ConnectivityManager ?: return
      val request =
        NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .build()
      val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            if (!hasSeenInitialNetworkCallback) {
              hasSeenInitialNetworkCallback = true
              return
            }
            viewModelScope.launch {
              if (!isAppInForeground || userManuallyDisconnected || autoReconnectPaused) return@launch
              val state = _uiState.value
              if (!state.settings.autoReconnectEnabled) return@launch
              if (state.connectionStatus is ConnectionStatus.Pairing) return@launch
              if (state.connectionStatus is ConnectionStatus.Connecting) return@launch
              if (autoReconnectJob?.isActive == true) return@launch
              val target = lastActiveTarget ?: pendingTarget ?: resolveLastSavedTarget(state) ?: return@launch
              if (!remoteClient.isConnectionHealthy()) {
                scheduleSilentReconnect(target, delayMs = 250L, forceRestart = false)
              }
            }
          }
        }
      cm.registerNetworkCallback(request, callback)
      networkCallback = callback
    } catch (_: Throwable) {
      // Safe fallback in restricted/test environments
    }
  }

  private fun attemptAutoReconnectIfEnabled() {
    if (autoConnectAttempted) return
    autoConnectAttempted = true
    val state = _uiState.value
    if (!state.settings.autoReconnectEnabled) return
    val lastId = prefsStore.getLastConnectedTvId() ?: return
    val lastTv = state.savedConnections.firstOrNull { it.id == lastId } ?: return
    connectToSavedTv(lastTv)
  }

  fun onAppBackgrounded() {
    isAppInForeground = false
    releaseHeldKeyIfNeeded()
    tvVoiceAudioStreamer.stopStreaming()
    autoReconnectJob?.cancel()
    autoReconnectJob = null
  }

  fun onAppForegrounded() {
    isAppInForeground = true
    val state = _uiState.value
    if (userManuallyDisconnected || autoReconnectPaused) return
    if (!state.settings.autoReconnectEnabled) return
    if (state.connectionStatus is ConnectionStatus.Pairing) return
    if (state.connectionStatus is ConnectionStatus.Connecting) return

    val target = lastActiveTarget ?: resolveLastSavedTarget(state) ?: return
    if (!remoteClient.isConnectionHealthy() || state.connectionStatus !is ConnectionStatus.Connected) {
      unexpectedDisconnectRetryCount = 0
      rapidDropCount = 0
      scheduleSilentReconnect(target, delayMs = 0L, forceRestart = true)
    }
  }

  private fun resolveLastSavedTarget(state: RemoteUiState): PendingConnectionTarget? {
    val lastId = prefsStore.getLastConnectedTvId() ?: return null
    val savedTv = state.savedConnections.firstOrNull { it.id == lastId } ?: return null
    return PendingConnectionTarget(
      ipAddress = savedTv.ipAddress,
      controlPort = savedTv.port,
      deviceName = savedTv.deviceName,
      customName = savedTv.customName,
      profileId = savedTv.profileId,
    )
  }

  private fun scheduleSilentReconnect(
    target: PendingConnectionTarget,
    delayMs: Long,
    forceRestart: Boolean = false,
  ) {
    if (autoReconnectPaused) return
    if (autoReconnectJob?.isActive == true) {
      if (!forceRestart) return
      autoReconnectJob?.cancel()
    }
    autoReconnectJob =
      viewModelScope.launch {
        if (delayMs > 0L) {
          delay(delayMs)
        }
        if (!isAppInForeground || userManuallyDisconnected || autoReconnectPaused) return@launch
        if (!_uiState.value.settings.autoReconnectEnabled) return@launch
        if (_uiState.value.connectionStatus is ConnectionStatus.Pairing) return@launch

        reconnectMutex.withLock {
          if (autoReconnectPaused || remoteClient.isConnectionHealthy()) return@withLock
          pendingTarget = target
          lastActiveTarget = target
          navigateToRemoteOnConnect = !_uiState.value.showConnectionHub
          remoteClient.connect(target.ipAddress, target.controlPort)
        }
      }
  }

  fun startDiscoveryScan() {
    discoveryManager.startScan()
  }

  fun stopDiscoveryScan() {
    discoveryManager.stopScan()
  }

  fun connectToDiscoveredTv(tv: DiscoveredTv, customName: String? = null) {
    val profileId = _uiState.value.activeProfileId
    val existing =
      _uiState.value.savedConnections.firstOrNull {
        it.ipAddress == tv.ipAddress && it.profileId == profileId
      }
    val chosenName = customName?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.customName ?: tv.displayName
    val target =
      PendingConnectionTarget(
        ipAddress = tv.ipAddress,
        controlPort = tv.port,
        deviceName = tv.displayName,
        customName = chosenName,
        profileId = profileId,
      )
    connectWithAutoPair(target)
  }

  fun connectManualIp(ipAddress: String, port: Int, customName: String) {
    val cleanIp = ipAddress.trim()
    if (cleanIp.isEmpty()) return
    val profileId = _uiState.value.activeProfileId
    val chosenName = customName.trim().ifEmpty { cleanIp }
    val target =
      PendingConnectionTarget(
        ipAddress = cleanIp,
        controlPort = port.takeIf { it in 1..65535 } ?: RemoteControlClient.DEFAULT_REMOTE_PORT,
        deviceName = chosenName,
        customName = chosenName,
        profileId = profileId,
      )
    connectWithAutoPair(target)
  }

  fun connectToSavedTv(savedTv: SavedTvConnection) {
    if (savedTv.profileId != _uiState.value.activeProfileId &&
      _uiState.value.profiles.any { it.id == savedTv.profileId }
    ) {
      selectProfile(savedTv.profileId)
    }
    val target =
      PendingConnectionTarget(
        ipAddress = savedTv.ipAddress,
        controlPort = savedTv.port,
        deviceName = savedTv.deviceName,
        customName = savedTv.customName,
        profileId = savedTv.profileId,
      )
    connectWithAutoPair(target)
  }

  fun forcePairWithTarget(ipAddress: String, controlPort: Int, deviceName: String, customName: String) {
    autoReconnectJob?.cancel()
    autoReconnectJob = null
    connectionStabilityJob?.cancel()
    connectionStabilityJob = null
    val target =
      PendingConnectionTarget(
        ipAddress = ipAddress.trim(),
        controlPort = controlPort,
        deviceName = deviceName,
        customName = customName.trim().ifEmpty { deviceName },
        profileId = _uiState.value.activeProfileId,
      )
    userManuallyDisconnected = false
    autoReconnectPaused = false
    rapidDropCount = 0
    unexpectedDisconnectRetryCount = 0
    lastConnectedAtMs = 0L
    navigateToRemoteOnConnect = true
    pendingTarget = target
    lastActiveTarget = target
    remoteClient.disconnect()
    initiatePairing(target)
  }

  fun retryCurrentTarget() {
    val err = _uiState.value.connectionStatus as? ConnectionStatus.Error
    val target =
      if (err?.targetIp != null) {
        PendingConnectionTarget(
          ipAddress = err.targetIp,
          controlPort = err.targetPort,
          deviceName = err.targetName ?: err.targetIp,
          customName = err.targetName ?: err.targetIp,
          profileId = _uiState.value.activeProfileId,
        )
      } else {
        lastActiveTarget ?: pendingTarget ?: resolveLastSavedTarget(_uiState.value)
      } ?: return
    connectWithAutoPair(target, keepCurrentScreen = !_uiState.value.showConnectionHub)
  }

  fun repairCurrentTarget() {
    val err = _uiState.value.connectionStatus as? ConnectionStatus.Error
    val base = lastActiveTarget ?: pendingTarget ?: resolveLastSavedTarget(_uiState.value)
    val ip = err?.targetIp ?: base?.ipAddress ?: return
    val port = err?.targetPort ?: base?.controlPort ?: RemoteControlClient.DEFAULT_REMOTE_PORT
    val deviceName = err?.targetName ?: base?.deviceName ?: ip
    val customName = base?.customName ?: deviceName
    forcePairWithTarget(
      ipAddress = ip,
      controlPort = port,
      deviceName = deviceName,
      customName = customName,
    )
  }

  private fun connectWithAutoPair(
    target: PendingConnectionTarget,
    keepCurrentScreen: Boolean = false,
  ) {
    autoReconnectJob?.cancel()
    autoReconnectJob = null
    connectionStabilityJob?.cancel()
    connectionStabilityJob = null
    cancelPairingInternal()
    userManuallyDisconnected = false
    autoReconnectPaused = false
    navigateToRemoteOnConnect = if (keepCurrentScreen) !_uiState.value.showConnectionHub else true
    unexpectedDisconnectRetryCount = 0
    rapidDropCount = 0
    lastConnectedAtMs = 0L
    postPairingReconnectRetryAvailable = false
    pendingTarget = target
    lastActiveTarget = target
    discoveryManager.stopScan()
    _uiState.update {
      it.copy(
        connectionStatus =
          ConnectionStatus.Connecting(
            targetName = target.customName,
            ipAddress = target.ipAddress,
            port = target.controlPort,
          )
      )
    }
    viewModelScope.launch {
      reconnectMutex.withLock {
        remoteClient.connect(target.ipAddress, target.controlPort)
      }
    }
  }

  private fun initiatePairing(target: PendingConnectionTarget) {
    cancelPairingInternal()
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          connectionStatus =
            ConnectionStatus.Connecting(
              targetName = target.customName,
              ipAddress = target.ipAddress,
              port = PairingClient.DEFAULT_PAIRING_PORT,
            )
        )
      }
      when (val result = pairingClient.startPairing(target.ipAddress, PairingClient.DEFAULT_PAIRING_PORT)) {
        is PairingClient.PairingStartResult.AwaitingPin -> {
          activePairingSession = result.session
          _uiState.update {
            it.copy(
              connectionStatus =
                ConnectionStatus.Pairing(
                  targetIp = target.ipAddress,
                  controlPort = target.controlPort,
                  defaultName = target.deviceName,
                  customName = target.customName,
                  serverName = result.serverName,
                )
            )
          }
        }

        is PairingClient.PairingStartResult.Failed -> {
          _uiState.update {
            it.copy(
              connectionStatus =
                ConnectionStatus.Error(
                  message = result.reason,
                  targetIp = target.ipAddress,
                  targetPort = target.controlPort,
                  targetName = target.customName,
                )
            )
          }
        }
      }
    }
  }

  fun submitPairingPin(pin: String, customTvName: String) {
    val session = activePairingSession ?: return
    val currentPairing = _uiState.value.connectionStatus as? ConnectionStatus.Pairing ?: return
    val finalName = customTvName.trim().ifEmpty { currentPairing.customName }

    pendingTarget =
      pendingTarget?.copy(customName = finalName)
        ?: PendingConnectionTarget(
          ipAddress = currentPairing.targetIp,
          controlPort = currentPairing.controlPort,
          deviceName = currentPairing.defaultName,
          customName = finalName,
          profileId = _uiState.value.activeProfileId,
        )

    _uiState.update {
      it.copy(
        connectionStatus =
          currentPairing.copy(
            customName = finalName,
            errorMessage = null,
            isSubmitting = true,
          )
      )
    }

    viewModelScope.launch {
      when (val result = session.submitPin(pin)) {
        is PairingClient.PinSubmitResult.Success -> {
          activePairingSession = null
          val target =
            pendingTarget
              ?: PendingConnectionTarget(
                ipAddress = currentPairing.targetIp,
                controlPort = currentPairing.controlPort,
                deviceName = currentPairing.defaultName,
                customName = finalName,
                profileId = _uiState.value.activeProfileId,
              )
          val saved =
            prefsStore.upsertConnection(
              profileId = target.profileId,
              customName = finalName,
              deviceName = target.deviceName,
              ipAddress = target.ipAddress,
              port = target.controlPort,
            )
          prefsStore.setLastConnectedTvId(saved.id)
          _uiState.update { it.copy(savedConnections = prefsStore.loadSavedConnections()) }
          autoReconnectPaused = false
          rapidDropCount = 0
          unexpectedDisconnectRetryCount = 0
          postPairingReconnectRetryAvailable = true
          delay(450L)
          reconnectMutex.withLock {
            remoteClient.connect(target.ipAddress, target.controlPort)
          }
        }

        is PairingClient.PinSubmitResult.InvalidPinFormatOrCheck -> {
          _uiState.update {
            it.copy(
              connectionStatus =
                currentPairing.copy(
                  customName = finalName,
                  errorMessage = result.reason,
                  isSubmitting = false,
                )
            )
          }
        }

        is PairingClient.PinSubmitResult.RejectedByTv,
        is PairingClient.PinSubmitResult.ConnectionLost -> {
          activePairingSession = null
          val msg =
            when (result) {
              is PairingClient.PinSubmitResult.RejectedByTv -> result.reason
              is PairingClient.PinSubmitResult.ConnectionLost -> result.reason
              else -> "Pairing failed."
            }
          _uiState.update {
            it.copy(
              connectionStatus =
                ConnectionStatus.Error(
                  message = msg,
                  targetIp = currentPairing.targetIp,
                  targetPort = currentPairing.controlPort,
                  targetName = finalName,
                )
            )
          }
        }
      }
    }
  }

  fun cancelPairing() {
    cancelPairingInternal()
    _uiState.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
  }

  private fun cancelPairingInternal() {
    activePairingSession?.close()
    activePairingSession = null
  }

  fun disconnect() {
    userManuallyDisconnected = true
    autoReconnectPaused = false
    rapidDropCount = 0
    lastConnectedAtMs = 0L
    unexpectedDisconnectRetryCount = 0
    releaseHeldKeyIfNeeded()
    tvVoiceAudioStreamer.stopStreaming()
    connectionStabilityJob?.cancel()
    connectionStabilityJob = null
    autoReconnectJob?.cancel()
    autoReconnectJob = null
    pendingTarget = null
    lastActiveTarget = null
    cancelPairingInternal()
    remoteClient.disconnect()
    _uiState.update {
      it.copy(
        connectionStatus = ConnectionStatus.Disconnected,
        showConnectionHub = true,
        voiceState = VoiceInputUiState(),
      )
    }
  }

  fun openConnectionHub() {
    _uiState.update { it.copy(showConnectionHub = true) }
  }

  fun openRemoteScreen() {
    _uiState.update { it.copy(showConnectionHub = false) }
  }

  fun openSettingsSheet(show: Boolean) {
    _uiState.update { it.copy(showSettingsSheet = show) }
  }

  fun openKeyboardSheet(show: Boolean) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    if (!show) {
      remoteClient.clearImeRequested()
    }
    _uiState.update { it.copy(showKeyboardSheet = show) }
  }

  fun sendKeyboardImeText(newText: String, previousText: String) {
    viewModelScope.launch {
      remoteClient.sendImeText(newText = newText, previousLength = previousText.length)
    }
  }

  fun submitKeyboardSearch(text: String, useDirectKeys: Boolean) {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    viewModelScope.launch {
      val sent =
        sendWithAutoRecovery {
          val textOk =
            if (useDirectKeys) {
              remoteClient.sendTextAsKeyEvents(text)
            } else {
              val prevLen = _uiState.value.tvImeState.currentText.length
              remoteClient.sendImeText(newText = text, previousLength = prevLen)
            }
          if (textOk) {
            remoteClient.sendKey(RemoteKeyCode.KEYCODE_ENTER, RemoteDirection.SHORT)
          } else {
            false
          }
        }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected) {
        _uiState.update {
          it.copy(statusBannerMessage = "Not connected to a TV. Connect or pair a device first.")
        }
      }
    }
  }

  fun sendKeyboardKey(keyCode: RemoteKeyCode, cue: FeedbackCue = FeedbackCue.NAV_TICK) {
    sendKey(keyCode, cue)
  }

  // --- Voice Input & Commands ---

  fun openVoiceSheet(show: Boolean) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    if (!show) {
      tvVoiceAudioStreamer.stopStreaming()
    }
    _uiState.update { state ->
      state.copy(
        voiceState =
          if (show) {
            state.voiceState.copy(
              showVoiceSheet = true,
              errorMessage = null,
            )
          } else {
            state.voiceState.copy(
              showVoiceSheet = false,
              isListening = false,
              rmsLevel = 0f,
              partialTranscript = "",
            )
          }
      )
    }
  }

  fun onVoiceListeningStarted() {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            showVoiceSheet = true,
            isListening = true,
            rmsLevel = 0f,
            partialTranscript = "",
            errorMessage = null,
          )
      )
    }
  }

  fun onVoiceListeningStopped() {
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            isListening = false,
            rmsLevel = 0f,
          )
      )
    }
  }

  fun onVoiceRmsChanged(normalizedLevel: Float) {
    _uiState.update { state ->
      if (!state.voiceState.isListening) state
      else state.copy(voiceState = state.voiceState.copy(rmsLevel = normalizedLevel.coerceIn(0f, 1f)))
    }
  }

  fun onVoicePartialResult(partialText: String) {
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            partialTranscript = partialText,
            errorMessage = null,
          )
      )
    }
  }

  fun onVoiceError(message: String) {
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            isListening = false,
            rmsLevel = 0f,
            errorMessage = message,
          )
      )
    }
  }

  fun onVoiceFinalResult(transcript: String) {
    val clean = transcript.trim()
    if (clean.isEmpty()) return

    val uiPrefs = _uiState.value.uiPreferences
    val allShortcuts =
      (uiPrefs.customShortcuts + TvAppShortcuts.all + TvAppShortcuts.popularAddableCatalog)
        .distinctBy { it.id }

    val parsed = VoiceCommandParser.parse(clean, allShortcuts) ?: return

    val actionLabel =
      when (parsed) {
        is ParsedVoiceCommand.SendKey -> {
          sendKey(parsed.keyCode, parsed.cue)
          parsed.label
        }
        is ParsedVoiceCommand.OpenOttApp -> {
          launchShortcut(parsed.shortcut)
          parsed.label
        }
        is ParsedVoiceCommand.SearchOrType -> {
          executeVoiceTvSearch(parsed.query)
          parsed.label
        }
      }

    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            isListening = false,
            rmsLevel = 0f,
            partialTranscript = "",
            lastTranscript = clean,
            executedActionLabel = actionLabel,
            errorMessage = null,
          ),
        statusBannerMessage = actionLabel,
      )
    }
  }

  private fun executeVoiceTvSearch(query: String) {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    viewModelScope.launch {
      val sent =
        sendWithAutoRecovery {
          val wasImeFocused = _uiState.value.tvImeState.isImeRequested
          if (!wasImeFocused) {
            val openedSearch = remoteClient.sendKey(RemoteKeyCode.KEYCODE_SEARCH, RemoteDirection.SHORT)
            if (!openedSearch) return@sendWithAutoRecovery false
            delay(450L)
          }
          val imeState = _uiState.value.tvImeState
          val textOk =
            if (imeState.isImeRequested) {
              remoteClient.sendImeText(newText = query, previousLength = imeState.currentText.length)
            } else {
              remoteClient.sendTextAsKeyEvents(query)
            }
          if (textOk) {
            remoteClient.sendKey(RemoteKeyCode.KEYCODE_ENTER, RemoteDirection.SHORT)
          } else {
            false
          }
        }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected) {
        _uiState.update {
          it.copy(
            statusBannerMessage =
              if (autoReconnectPaused) "Auto-retry paused after repeated drops. Tap Re-pair TV or Retry."
              else "Not connected to a TV. Connect or pair a device first."
          )
        }
      }
    }
  }

  fun triggerTvVoiceAssistant() {
    sendKey(RemoteKeyCode.KEYCODE_VOICE_ASSIST, FeedbackCue.POWER_OR_APP)
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            executedActionLabel = "Triggered TV Voice Assistant",
            errorMessage = null,
          )
      )
    }
  }

  fun setInputMode(mode: RemoteInputMode) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    _uiState.update { it.copy(inputMode = mode) }
  }

  fun sendKey(
    keyCode: RemoteKeyCode,
    cue: FeedbackCue = FeedbackCue.NAV_TICK,
    direction: RemoteDirection = RemoteDirection.SHORT,
  ) {
    feedbackManager.trigger(cue, _uiState.value.settings)
    viewModelScope.launch {
      val sent = sendWithAutoRecovery { remoteClient.sendKey(keyCode, direction) }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected) {
        _uiState.update {
          it.copy(
            statusBannerMessage =
              if (autoReconnectPaused) "Auto-retry paused after repeated drops. Tap Re-pair TV or Retry."
              else "Not connected to a TV. Connect or pair a device first."
          )
        }
      }
    }
  }

  fun onKeyHoldStart(keyCode: RemoteKeyCode, cue: FeedbackCue = FeedbackCue.NAV_TICK) {
    feedbackManager.trigger(cue, _uiState.value.settings)
    activeHeldKey = keyCode
    viewModelScope.launch {
      val sent = sendWithAutoRecovery { remoteClient.sendKey(keyCode, RemoteDirection.START_LONG) }
      if (!sent) {
        if (activeHeldKey == keyCode) activeHeldKey = null
        if (_uiState.value.connectionStatus !is ConnectionStatus.Connected) {
          _uiState.update {
            it.copy(
              statusBannerMessage =
                if (autoReconnectPaused) "Auto-retry paused after repeated drops. Tap Re-pair TV or Retry."
                else "Not connected to a TV. Connect or pair a device first."
            )
          }
        }
      }
    }
  }

  fun onKeyHoldEnd(keyCode: RemoteKeyCode) {
    if (activeHeldKey == keyCode) {
      activeHeldKey = null
    }
    viewModelScope.launch {
      if (remoteClient.isConnectionHealthy()) {
        remoteClient.sendKey(keyCode, RemoteDirection.END_LONG)
      }
    }
  }

  private fun releaseHeldKeyIfNeeded() {
    val held = activeHeldKey ?: return
    activeHeldKey = null
    viewModelScope.launch {
      if (remoteClient.isConnectionHealthy()) {
        remoteClient.sendKey(held, RemoteDirection.END_LONG)
      }
    }
  }

  fun launchShortcut(shortcut: TvAppShortcut) {
    feedbackManager.trigger(FeedbackCue.POWER_OR_APP, _uiState.value.settings)
    if (autoReconnectPaused) {
      _uiState.update {
        it.copy(
          statusBannerMessage = "Connection paused after repeated drops. Tap Re-pair TV or Retry."
        )
      }
      return
    }
    ottLaunchJob?.cancel()
    ottLaunchJob =
      viewModelScope.launch {
        if (!ensureConnectedForCommand()) {
          _uiState.update {
            it.copy(
              statusBannerMessage =
                if (autoReconnectPaused) "Connection paused after repeated drops. Tap Re-pair TV or Retry."
                else "Connect to your TV to launch ${shortcut.name}."
            )
          }
          return@launch
        }
        val plan = TvAppShortcuts.buildLaunchPlan(shortcut)
        var outcome = remoteClient.launchAppWithPlan(plan)
        if (
          outcome == OttLaunchOutcome.FAILED &&
          !autoReconnectPaused &&
          _uiState.value.connectionStatus !is ConnectionStatus.Error &&
          ensureConnectedForCommand(forceReconnect = true)
        ) {
          outcome = remoteClient.launchAppWithPlan(plan)
        }
        val banner =
          when (outcome) {
            OttLaunchOutcome.LAUNCHED_NATIVE_APP -> "Opening ${shortcut.name} app on TV..."
            OttLaunchOutcome.OPENED_IN_BROWSER -> "${shortcut.name} app not on TV — opening in TV browser..."
            OttLaunchOutcome.FAILED ->
              if (autoReconnectPaused) "Connection paused after repeated drops. Tap Re-pair TV or Retry."
              else "Connect to your TV to launch ${shortcut.name}."
          }
        _uiState.update { it.copy(statusBannerMessage = banner) }
      }
  }

  private suspend fun ensureConnectedForCommand(forceReconnect: Boolean = false): Boolean {
    if (!forceReconnect && remoteClient.isConnectionHealthy()) return true
    if (autoReconnectPaused) return false
    val target = lastActiveTarget ?: pendingTarget ?: return false
    if (userManuallyDisconnected || !_uiState.value.settings.autoReconnectEnabled) return false
    if (_uiState.value.connectionStatus is ConnectionStatus.Pairing) return false

    autoReconnectJob?.cancel()
    autoReconnectJob = null
    return reconnectMutex.withLock {
      if (autoReconnectPaused) {
        false
      } else if (!forceReconnect && remoteClient.isConnectionHealthy()) {
        true
      } else {
        pendingTarget = target
        lastActiveTarget = target
        navigateToRemoteOnConnect = !_uiState.value.showConnectionHub
        remoteClient.connect(target.ipAddress, target.controlPort)
        val event =
          withTimeoutOrNull(RECONNECT_BEFORE_RETRY_TIMEOUT_MS) {
            remoteClient.sessionEvent.first {
              it is RemoteControlClient.SessionEvent.Connected ||
                it is RemoteControlClient.SessionEvent.PairingRequired ||
                it is RemoteControlClient.SessionEvent.Error
            }
          }
        event is RemoteControlClient.SessionEvent.Connected
      }
    }
  }

  private suspend fun sendWithAutoRecovery(action: suspend () -> Boolean): Boolean {
    if (action()) return true
    if (autoReconnectPaused) return false
    return if (ensureConnectedForCommand(forceReconnect = true)) action() else false
  }

  fun clearBannerMessage() {
    _uiState.update { it.copy(statusBannerMessage = null) }
  }

  // --- User Profiles Management ---

  fun selectProfile(profileId: String) {
    prefsStore.setActiveProfileId(profileId)
    _uiState.update { it.copy(activeProfileId = profileId) }
  }

  fun createProfile(name: String, badgeIcon: String = "person") {
    val clean = name.trim()
    if (clean.isEmpty()) return
    val newProfile =
      UserProfile(
        id = UUID.randomUUID().toString(),
        name = clean,
        badgeIcon = badgeIcon,
      )
    val updated = _uiState.value.profiles + newProfile
    prefsStore.saveProfiles(updated)
    prefsStore.setActiveProfileId(newProfile.id)
    _uiState.update {
      it.copy(
        profiles = updated,
        activeProfileId = newProfile.id,
        statusBannerMessage = "Switched to profile \"$clean\"",
      )
    }
  }

  fun renameProfile(profileId: String, newName: String) {
    val clean = newName.trim()
    if (clean.isEmpty()) return
    val updated =
      _uiState.value.profiles.map {
        if (it.id == profileId) it.copy(name = clean) else it
      }
    prefsStore.saveProfiles(updated)
    _uiState.update { it.copy(profiles = updated) }
  }

  fun deleteProfile(profileId: String) {
    val currentProfiles = _uiState.value.profiles
    if (currentProfiles.size <= 1) return

    val updatedProfiles = currentProfiles.filterNot { it.id == profileId }
    val fallbackProfileId = updatedProfiles.first().id
    val updatedConnections =
      _uiState.value.savedConnections.map { tv ->
        if (tv.profileId == profileId) tv.copy(profileId = fallbackProfileId) else tv
      }

    prefsStore.saveProfiles(updatedProfiles)
    prefsStore.saveConnections(updatedConnections)
    val nextActive =
      if (_uiState.value.activeProfileId == profileId) fallbackProfileId
      else _uiState.value.activeProfileId
    prefsStore.setActiveProfileId(nextActive)

    _uiState.update {
      it.copy(
        profiles = updatedProfiles,
        activeProfileId = nextActive,
        savedConnections = updatedConnections,
      )
    }
  }

  // --- Saved TV Connections CRUD ---

  fun saveDiscoveredTv(tv: DiscoveredTv, customName: String, profileId: String) {
    val saved =
      prefsStore.upsertConnection(
        profileId = profileId,
        customName = customName,
        deviceName = tv.displayName,
        ipAddress = tv.ipAddress,
        port = tv.port,
      )
    _uiState.update {
      it.copy(
        savedConnections = prefsStore.loadSavedConnections(),
        statusBannerMessage = "Saved \"${saved.customName}\"",
      )
    }
  }

  fun updateSavedTv(
    id: String,
    customName: String,
    ipAddress: String,
    port: Int,
    profileId: String,
  ) {
    val updated =
      _uiState.value.savedConnections.map { tv ->
        if (tv.id == id) {
          tv.copy(
            customName = customName.trim().ifEmpty { tv.deviceName },
            ipAddress = ipAddress.trim().ifEmpty { tv.ipAddress },
            port = port.takeIf { it in 1..65535 } ?: tv.port,
            profileId = profileId,
          )
        } else {
          tv
        }
      }
    prefsStore.saveConnections(updated)
    _uiState.update {
      it.copy(
        savedConnections = updated,
        statusBannerMessage = "Updated saved TV connection",
      )
    }
  }

  fun deleteSavedTv(id: String) {
    val updated = _uiState.value.savedConnections.filterNot { it.id == id }
    prefsStore.saveConnections(updated)
    if (prefsStore.getLastConnectedTvId() == id) {
      prefsStore.setLastConnectedTvId(null)
    }
    _uiState.update {
      it.copy(
        savedConnections = updated,
        statusBannerMessage = "Removed saved TV",
      )
    }
  }

  // --- Feedback & App Settings ---

  fun updateHapticsEnabled(enabled: Boolean) {
    val updated = _uiState.value.settings.copy(hapticsEnabled = enabled)
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
    if (enabled) {
      feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, updated.copy(soundEnabled = false))
    }
  }

  fun updateSoundEnabled(enabled: Boolean) {
    val updated = _uiState.value.settings.copy(soundEnabled = enabled)
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
    if (enabled) {
      feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, updated.copy(hapticsEnabled = false))
    }
  }

  fun updateSoundVolume(volume: Float) {
    val updated = _uiState.value.settings.copy(soundVolume = volume.coerceIn(0f, 1f))
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
  }

  fun updateAutoReconnect(enabled: Boolean) {
    val updated = _uiState.value.settings.copy(autoReconnectEnabled = enabled)
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
  }

  fun updateAutoCheckUpdates(enabled: Boolean) {
    val updated = _uiState.value.settings.copy(autoCheckUpdates = enabled)
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated) }
    UpdateCheckJobService.syncSchedule(getApplication(), enabled)
    if (enabled) {
      checkForUpdates(manual = false)
    }
  }

  fun updateReleaseRepoOverride(repo: String) {
    val updated = _uiState.value.settings.copy(releaseRepoOverride = repo.trim())
    prefsStore.saveSettings(updated)
    _uiState.update { it.copy(settings = updated, updateStatusMessage = null) }
  }

  private fun syncAndCheckUpdatesOnStartup() {
    val settings = _uiState.value.settings
    UpdateCheckJobService.syncSchedule(getApplication(), settings.autoCheckUpdates)
    if (settings.autoCheckUpdates && AppUpdateManager.resolveSource(settings.releaseRepoOverride).isNotBlank()) {
      checkForUpdates(manual = false)
    }
  }

  fun checkForUpdates(manual: Boolean = true) {
    if (_uiState.value.isCheckingUpdates) return
    val settings = _uiState.value.settings
    _uiState.update {
      it.copy(
        isCheckingUpdates = true,
        updateStatusMessage = if (manual) "Checking for updates..." else it.updateStatusMessage,
      )
    }
    viewModelScope.launch {
      when (val result = AppUpdateManager.checkForUpdate(settings.releaseRepoOverride)) {
        is UpdateCheckResult.UpdateAvailable -> {
          val release = result.release
          val skipped = prefsStore.getSkippedUpdateTag()
          val shouldSurface = manual || release.tag != skipped
          if (shouldSurface && release.tag != prefsStore.getLastNotifiedUpdateTag()) {
            AppUpdateManager.showUpdateNotification(getApplication(), release)
            prefsStore.setLastNotifiedUpdateTag(release.tag)
          }
          _uiState.update {
            it.copy(
              isCheckingUpdates = false,
              availableUpdate = if (shouldSurface) release else it.availableUpdate,
              showUpdateDialog = if (manual && shouldSurface) true else it.showUpdateDialog,
              updateStatusMessage = "Update v${release.versionName} available!",
            )
          }
        }

        is UpdateCheckResult.UpToDate -> {
          _uiState.update {
            it.copy(
              isCheckingUpdates = false,
              availableUpdate = null,
              updateStatusMessage = "Up to date (v${result.currentVersion})",
            )
          }
        }

        UpdateCheckResult.NotConfigured -> {
          _uiState.update {
            it.copy(
              isCheckingUpdates = false,
              updateStatusMessage =
                if (manual) "Set GitHub owner/repo above to enable release checks."
                else null,
            )
          }
        }

        is UpdateCheckResult.Error -> {
          _uiState.update {
            it.copy(
              isCheckingUpdates = false,
              updateStatusMessage = if (manual) "Update check failed: ${result.message}" else it.updateStatusMessage,
            )
          }
        }
      }
    }
  }

  fun openUpdateDialog(show: Boolean) {
    _uiState.update { it.copy(showUpdateDialog = show) }
  }

  fun downloadAvailableUpdate() {
    val release = _uiState.value.availableUpdate ?: return
    AppUpdateManager.openUpdateDownload(getApplication(), release)
    _uiState.update { it.copy(showUpdateDialog = false) }
  }

  fun skipAvailableUpdate() {
    val release = _uiState.value.availableUpdate ?: return
    prefsStore.setSkippedUpdateTag(release.tag)
    _uiState.update {
      it.copy(
        availableUpdate = null,
        showUpdateDialog = false,
        statusBannerMessage = "Skipped update v${release.versionName}",
      )
    }
  }

  fun previewFeedback() {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
  }

  // --- UI & Layout Customization ---

  fun openCustomizeUiSheet(show: Boolean) {
    _uiState.update { it.copy(showCustomizeUiSheet = show) }
  }

  fun updateUiPreferences(transform: (RemoteUiPreferences) -> RemoteUiPreferences) {
    val updated = transform(_uiState.value.uiPreferences)
    prefsStore.saveUiPreferences(updated)
    _uiState.update { it.copy(uiPreferences = updated) }
  }

  fun setPreferredPadMode(mode: PreferredPadMode) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    val updated = _uiState.value.uiPreferences.copy(defaultPadMode = mode)
    prefsStore.saveUiPreferences(updated)
    _uiState.update {
      it.copy(
        uiPreferences = updated,
        inputMode =
          if (mode == PreferredPadMode.TOUCHPAD) RemoteInputMode.TOUCHPAD
          else RemoteInputMode.DPAD,
      )
    }
  }

  fun setThemeMode(mode: AppThemeMode) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    updateUiPreferences { it.copy(themeMode = mode) }
  }

  fun setAccentTheme(theme: RemoteAccentTheme) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    updateUiPreferences { it.copy(accentTheme = theme) }
  }

  fun applyUiPreset(preset: UiLayoutPreset) {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    updateUiPreferences { current ->
      when (preset) {
        UiLayoutPreset.FULL ->
          current.copy(
            compactMode = false,
            showQuickSearchBar = true,
            showNavigationRow = true,
            showVolumeAndChannel = true,
            showMediaTransport = true,
            showStreamingShortcuts = true,
            shortcutColumns = 3,
          )
        UiLayoutPreset.MINIMAL ->
          current.copy(
            compactMode = true,
            showQuickSearchBar = false,
            showNavigationRow = true,
            showVolumeAndChannel = true,
            showMediaTransport = false,
            showStreamingShortcuts = false,
          )
        UiLayoutPreset.STREAMING ->
          current.copy(
            compactMode = true,
            showQuickSearchBar = true,
            showNavigationRow = true,
            showVolumeAndChannel = true,
            showMediaTransport = true,
            showStreamingShortcuts = true,
            shortcutColumns = 3,
          )
      }
    }
  }

  fun toggleShortcutEnabled(shortcutId: String) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    updateUiPreferences { current ->
      val nextIds =
        if (shortcutId in current.enabledShortcutIds) {
          current.enabledShortcutIds - shortcutId
        } else {
          current.enabledShortcutIds + shortcutId
        }
      current.copy(enabledShortcutIds = nextIds)
    }
  }

  fun selectAllShortcuts() {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    updateUiPreferences { current ->
      current.copy(enabledShortcutIds = current.allAvailableShortcuts().map { it.id }.toSet())
    }
  }

  fun addPopularOttShortcut(preset: TvAppShortcut) {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    updateUiPreferences { current ->
      val existing = current.customShortcuts.any { it.id == preset.id }
      val nextCustom =
        if (existing) current.customShortcuts
        else current.customShortcuts + preset.copy(isCustom = true)
      current.copy(
        customShortcuts = nextCustom,
        enabledShortcutIds = current.enabledShortcutIds + preset.id,
        showStreamingShortcuts = true,
      )
    }
    _uiState.update { it.copy(statusBannerMessage = "Added ${preset.name} to your remote") }
  }

  fun addCustomOttShortcut(
    name: String,
    appLink: String,
    packageName: String = "",
    badge: String,
    accentColor: Color,
    iconStyle: CustomOttIconStyle,
  ) {
    val cleanName = name.trim()
    val cleanPkg = packageName.trim()
    val rawLink = appLink.trim().ifEmpty { cleanPkg }
    val normalizedLink = TvAppShortcuts.normalizeAppLink(rawLink)
    if (cleanName.isEmpty() || (normalizedLink.isEmpty() && cleanPkg.isEmpty())) return

    val resolvedPackages =
      buildList {
        if (cleanPkg.isNotEmpty()) add(cleanPkg)
        if (TvAppShortcuts.isPackageIdentifier(rawLink)) add(rawLink)
      }.distinct()

    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    val newShortcut =
      TvAppShortcut(
        id = "custom_${UUID.randomUUID().toString().take(8)}",
        name = cleanName,
        badge = badge.trim().ifEmpty { TvAppShortcuts.suggestBadge(cleanName) }.take(3),
        appLink = normalizedLink,
        accentColor = accentColor,
        isCustom = true,
        iconStyle = iconStyle,
        packageNames = resolvedPackages,
      )
    updateUiPreferences { current ->
      current.copy(
        customShortcuts = current.customShortcuts + newShortcut,
        enabledShortcutIds = current.enabledShortcutIds + newShortcut.id,
        showStreamingShortcuts = true,
      )
    }
    _uiState.update { it.copy(statusBannerMessage = "Added \"$cleanName\" to your remote") }
  }

  fun updateCustomOttShortcut(
    id: String,
    name: String,
    appLink: String,
    packageName: String = "",
    badge: String,
    accentColor: Color,
    iconStyle: CustomOttIconStyle,
  ) {
    val cleanName = name.trim()
    val cleanPkg = packageName.trim()
    val rawLink = appLink.trim().ifEmpty { cleanPkg }
    val normalizedLink = TvAppShortcuts.normalizeAppLink(rawLink)
    if (cleanName.isEmpty() || (normalizedLink.isEmpty() && cleanPkg.isEmpty())) return

    val resolvedPackages =
      buildList {
        if (cleanPkg.isNotEmpty()) add(cleanPkg)
        if (TvAppShortcuts.isPackageIdentifier(rawLink)) add(rawLink)
      }.distinct()

    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    updateUiPreferences { current ->
      val updatedCustom =
        current.customShortcuts.map { shortcut ->
          if (shortcut.id == id) {
            shortcut.copy(
              name = cleanName,
              badge = badge.trim().ifEmpty { TvAppShortcuts.suggestBadge(cleanName) }.take(3),
              appLink = normalizedLink,
              accentColor = accentColor,
              iconStyle = iconStyle,
              packageNames = resolvedPackages,
            )
          } else {
            shortcut
          }
        }
      current.copy(customShortcuts = updatedCustom)
    }
    _uiState.update { it.copy(statusBannerMessage = "Updated \"$cleanName\"") }
  }

  fun deleteCustomOttShortcut(id: String) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    val removedName = _uiState.value.uiPreferences.customShortcuts.firstOrNull { it.id == id }?.name
    updateUiPreferences { current ->
      current.copy(
        customShortcuts = current.customShortcuts.filterNot { it.id == id },
        enabledShortcutIds = current.enabledShortcutIds - id,
      )
    }
    if (!removedName.isNullOrBlank()) {
      _uiState.update { it.copy(statusBannerMessage = "Removed \"$removedName\"") }
    }
  }

  fun resetUiPreferences() {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    val preservedCustom = _uiState.value.uiPreferences.customShortcuts
    val defaults =
      RemoteUiPreferences(
        customShortcuts = preservedCustom,
        enabledShortcutIds = TvAppShortcuts.defaultEnabledIds + preservedCustom.map { it.id },
      )
    prefsStore.saveUiPreferences(defaults)
    _uiState.update {
      it.copy(
        uiPreferences = defaults,
        inputMode = RemoteInputMode.DPAD,
        statusBannerMessage = "UI customization reset to defaults",
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    releaseHeldKeyIfNeeded()
    tvVoiceAudioStreamer.stopStreaming()
    ottLaunchJob?.cancel()
    connectionStabilityJob?.cancel()
    autoReconnectJob?.cancel()
    try {
      val cb = networkCallback
      if (cb != null) {
        val cm =
          getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        cm?.unregisterNetworkCallback(cb)
      }
    } catch (_: Throwable) {
    }
    networkCallback = null
    discoveryManager.stopScan()
    cancelPairingInternal()
    remoteClient.disconnect()
    feedbackManager.release()
  }

  companion object {
    private const val STABLE_CONNECTION_WINDOW_MS = 8_000L
    private const val MAX_RAPID_DROP_CYCLES = 3
    private const val MAX_AUTO_RECONNECT_ATTEMPTS = 6
    private val AUTO_RECONNECT_BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L)
    private const val RECONNECT_BEFORE_RETRY_TIMEOUT_MS = 3_000L
  }
}
