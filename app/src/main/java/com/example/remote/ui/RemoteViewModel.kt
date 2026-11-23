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
import com.example.remote.data.DetectedTvApp
import com.example.remote.data.PreferredPadMode
import com.example.remote.data.RemotePreferencesStore
import com.example.remote.data.RemoteUiPreferences
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.SslCredentialsDataStore
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts
import com.example.remote.data.TvDetectedAppSync
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class RemoteInputMode {
  DPAD,
  TOUCHPAD,
}

sealed class ConnectionStatus {
  data object Disconnected : ConnectionStatus()
  data class Connecting(
    val targetName: String,
    val ipAddress: String,
    val port: Int,
    val isSilentAutoHeal: Boolean = false,
  ) : ConnectionStatus()

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
  val showOttAppsSheet: Boolean = false,
  val showKeyboardSheet: Boolean = false,
  val tvImeState: TvImeState = TvImeState(),
  val voiceState: VoiceInputUiState = VoiceInputUiState(),
  val statusBannerMessage: String? = null,
  val availableUpdate: AppReleaseInfo? = null,
  val showUpdateDialog: Boolean = false,
  val isCheckingUpdates: Boolean = false,
  val updateStatusMessage: String? = null,
  val detectedTvShortcuts: List<TvAppShortcut> = emptyList(),
  val verifiedOnTvShortcutIds: Set<String> = emptySet(),
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

  private val sslCredentialsStore = SslCredentialsDataStore(application)
  private val certificateManager = CertificateManager(application, sslCredentialsStore)
  private val discoveryManager = TvDiscoveryManager(application)
  private val pairingClient = PairingClient(certificateManager, application)
  private val remoteClient = RemoteControlClient(certificateManager, application)
  private val prefsStore = RemotePreferencesStore(application)
  private val feedbackManager = FeedbackManager(application)

  private val _uiState = MutableStateFlow(RemoteUiState())
  val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

  private var activePairingSession: PairingClient.ActivePairingSession? = null
  private var pendingTarget: PendingConnectionTarget? = null
  private var lastActiveTarget: PendingConnectionTarget? = null
  private var isAppInForeground = true
  private var backgroundedAtMs = 0L
  private var silentAutoHealInProgress = false
  private var inFlightReconnect: CompletableDeferred<Boolean>? = null
  private var userManuallyDisconnected = false
  private var navigateToRemoteOnConnect = true
  private var autoConnectAttempted = false
  private var postPairingReconnectRetryAvailable = false
  private var pairedTvTlsRetryCount = 0
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
  private var detectedTvAppsCache: List<DetectedTvApp> = emptyList()
  private var dismissedAutoSyncIds: Set<String> = emptySet()
  private var pairedCredentialHosts: Set<String> = emptySet()
  private val reconnectMutex = Mutex()
  private val safeCoroutineHandler =
    CoroutineExceptionHandler { _, throwable ->
      if (throwable is VirtualMachineError) throw throwable
      _uiState.update { state ->
        state.copy(
          statusBannerMessage = throwable.message?.takeIf { it.isNotBlank() }
            ?: "Recovered from an unexpected remote operation error.",
        )
      }
    }

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
    runCatching { loadStoredState() }
    observeSslCredentials()
    observeDiscovery()
    observeRemoteSession()
    observeTvImeState()
    observeDetectedTvApps()
    observeVoiceSessionRequests()
    registerNetworkCallback()
    attemptAutoReconnectIfEnabled()
    syncAndCheckUpdatesOnStartup()
  }

  private fun observeSslCredentials() {
    viewModelScope.launch(safeCoroutineHandler) {
      sslCredentialsStore.pairedCredentialsFlow.collect { credentials ->
        pairedCredentialHosts =
          credentials
            .flatMap { listOf(SslCredentialsDataStore.normalizeHost(it.ipAddress), SslCredentialsDataStore.normalizeHost(it.tvKey)) }
            .filter { it.isNotEmpty() }
            .toSet()
      }
    }
  }

  private fun observeTvImeState() {
    var previousImeRequested = false
    viewModelScope.launch(safeCoroutineHandler) {
      remoteClient.imeState.collect { ime ->
        val becameRequested = !previousImeRequested && ime.isImeRequested
        previousImeRequested = ime.isImeRequested
        _uiState.update { state ->
          val shouldAutoOpenKeyboard =
            becameRequested &&
              !state.showConnectionHub &&
              !state.showSettingsSheet &&
              !state.showCustomizeUiSheet &&
              !state.voiceState.showVoiceSheet
          state.copy(
            tvImeState = ime,
            showOttAppsSheet = if (shouldAutoOpenKeyboard) false else state.showOttAppsSheet,
            showKeyboardSheet = if (shouldAutoOpenKeyboard) true else state.showKeyboardSheet,
          )
        }
      }
    }
  }

  private fun observeDetectedTvApps() {
    viewModelScope.launch(safeCoroutineHandler) {
      remoteClient.detectedTvApp.collect { signal ->
        handleDetectedTvApp(packageName = signal.packageName, label = signal.label)
      }
    }
  }

  private fun activeTvKey(): String {
    val status = _uiState.value.connectionStatus
    return when (status) {
      is ConnectionStatus.Connected -> status.ipAddress.trim().ifEmpty { "default_tv" }
      else -> lastActiveTarget?.ipAddress?.trim()?.ifEmpty { "default_tv" } ?: "default_tv"
    }
  }

  private fun publishDetectedAppsForActiveTv(tvKey: String = activeTvKey()) {
    val scoped =
      detectedTvAppsCache
        .filter { it.tvKey == tvKey }
        .sortedByDescending { it.lastSeenAt }
    val shortcuts =
      scoped
        .map { TvDetectedAppSync.toShortcut(it.packageName, it.label) }
        .distinctBy { it.id }
    val verifiedIds = shortcuts.map { it.id }.toSet()
    _uiState.update {
      it.copy(
        detectedTvShortcuts = shortcuts,
        verifiedOnTvShortcutIds = verifiedIds,
      )
    }
  }

  internal fun handleDetectedTvApp(
    packageName: String,
    label: String,
    tvKeyOverride: String? = null,
    allowAutoAdd: Boolean = true,
  ) {
    val cleanPkg = packageName.trim()
    if (!TvDetectedAppSync.isSyncableUserApp(cleanPkg)) return

    val tvKey = tvKeyOverride?.trim()?.ifEmpty { null } ?: activeTvKey()
    val cleanLabel = label.trim()
    val now = System.currentTimeMillis()
    val updatedCache =
      (detectedTvAppsCache.filterNot {
        it.tvKey == tvKey && it.packageName.equals(cleanPkg, ignoreCase = true)
      } + DetectedTvApp(tvKey = tvKey, packageName = cleanPkg, label = cleanLabel, lastSeenAt = now))
        .sortedByDescending { it.lastSeenAt }
        .take(64)
    detectedTvAppsCache = updatedCache
    prefsStore.saveDetectedTvApps(updatedCache)

    val resolvedShortcut = TvDetectedAppSync.toShortcut(cleanPkg, cleanLabel)
    val builtInIds = TvAppShortcuts.all.map { it.id }.toSet()
    val uiPrefs = _uiState.value.uiPreferences

    var autoSyncedNow = false
    if (
      allowAutoAdd &&
      uiPrefs.autoSyncTvApps &&
      resolvedShortcut.id !in dismissedAutoSyncIds &&
      resolvedShortcut.id !in uiPrefs.enabledShortcutIds
    ) {
      updateUiPreferences { current ->
        val isBuiltIn = resolvedShortcut.id in builtInIds
        val alreadyInCustom = current.customShortcuts.any { it.id == resolvedShortcut.id }
        val nextCustom =
          if (isBuiltIn || alreadyInCustom) current.customShortcuts
          else current.customShortcuts + resolvedShortcut.copy(isCustom = true)
        current.copy(
          customShortcuts = nextCustom,
          enabledShortcutIds = current.enabledShortcutIds + resolvedShortcut.id,
        )
      }
      autoSyncedNow = true
    }

    publishDetectedAppsForActiveTv(tvKey)
    if (autoSyncedNow) {
      _uiState.update {
        it.copy(statusBannerMessage = "Auto-synced ${resolvedShortcut.name} from your TV")
      }
    }
  }

  private fun observeVoiceSessionRequests() {
    viewModelScope.launch(safeCoroutineHandler) {
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
    detectedTvAppsCache = prefsStore.loadDetectedTvApps()
    dismissedAutoSyncIds = prefsStore.loadDismissedAutoSyncIds()
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
    viewModelScope.launch(safeCoroutineHandler) {
      discoveryManager.discoveredTvs.collect { list ->
        _uiState.update { it.copy(discoveredTvs = list) }
        if (list.isNotEmpty()) {
          refreshSavedTvIpsFromDiscovery(list)
        }
      }
    }
    viewModelScope.launch(safeCoroutineHandler) {
      discoveryManager.isScanning.collect { scanning ->
        _uiState.update { it.copy(isScanning = scanning) }
      }
    }
    viewModelScope.launch(safeCoroutineHandler) {
      discoveryManager.scanError.collect { err ->
        _uiState.update { it.copy(scanError = err) }
      }
    }
  }

  private fun refreshSavedTvIpsFromDiscovery(discovered: List<DiscoveredTv>) {
    val currentSaved = _uiState.value.savedConnections
    if (currentSaved.isEmpty()) return
    var anyChanged = false
    val ipMigrations = mutableListOf<Triple<String, String, Int>>()
    val updatedSaved =
      currentSaved.map { saved ->
        val match =
          discovered.firstOrNull { tv ->
            tv.displayName.equals(saved.deviceName, ignoreCase = true) &&
              tv.ipAddress.isNotBlank()
          }
        if (match != null && (match.ipAddress != saved.ipAddress || match.port != saved.port)) {
          anyChanged = true
          ipMigrations.add(Triple(saved.ipAddress, match.ipAddress, match.port))
          if (lastActiveTarget?.deviceName?.equals(saved.deviceName, ignoreCase = true) == true) {
            lastActiveTarget =
              lastActiveTarget?.copy(ipAddress = match.ipAddress, controlPort = match.port)
          }
          if (pendingTarget?.deviceName?.equals(saved.deviceName, ignoreCase = true) == true) {
            pendingTarget =
              pendingTarget?.copy(ipAddress = match.ipAddress, controlPort = match.port)
          }
          saved.copy(ipAddress = match.ipAddress, port = match.port)
        } else {
          saved
        }
      }
    if (!anyChanged) return
    _uiState.update { it.copy(savedConnections = updatedSaved) }
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { prefsStore.saveConnections(updatedSaved) }
      ipMigrations.forEach { (oldIp, newIp, newPort) ->
        runCatching { sslCredentialsStore.updatePairedHostAddress(oldIp, newIp, newPort) }
      }
    }
  }

  private fun observeRemoteSession() {
    viewModelScope.launch(safeCoroutineHandler) {
      remoteClient.sessionEvent.collect { event ->
        when (event) {
          is RemoteControlClient.SessionEvent.Disconnected -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            silentAutoHealInProgress = false
            inFlightReconnect?.complete(false)
            inFlightReconnect = null
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
            val target = pendingTarget ?: lastActiveTarget
            _uiState.update {
              it.copy(
                connectionStatus =
                  ConnectionStatus.Connecting(
                    targetName = target?.customName ?: target?.deviceName ?: "Android TV",
                    ipAddress = target?.ipAddress ?: "",
                    port = target?.controlPort ?: RemoteControlClient.DEFAULT_REMOTE_PORT,
                    isSilentAutoHeal = silentAutoHealInProgress,
                  )
              )
            }
          }

          is RemoteControlClient.SessionEvent.Connected -> {
            postPairingReconnectRetryAvailable = false
            pairedTvTlsRetryCount = 0
            autoReconnectPaused = false
            silentAutoHealInProgress = false
            inFlightReconnect?.complete(true)
            inFlightReconnect = null
            lastConnectedAtMs = System.currentTimeMillis()
            scheduleConnectionStabilityReset()
            val target = pendingTarget ?: lastActiveTarget
            if (target != null) {
              lastActiveTarget = target
              _uiState.update {
                it.copy(
                  connectionStatus =
                    ConnectionStatus.Connected(
                      displayName = target.customName,
                      deviceName = target.deviceName,
                      ipAddress = target.ipAddress,
                      port = target.controlPort,
                    ),
                  showConnectionHub = if (navigateToRemoteOnConnect) false else it.showConnectionHub,
                  statusBannerMessage = null,
                )
              }
              publishDetectedAppsForActiveTv(target.ipAddress)
              viewModelScope.launch(safeCoroutineHandler) {
                val serverFp = remoteClient.lastServerCertFingerprintSha256
                val (saved, allSaved) =
                  withContext(Dispatchers.IO) {
                    val upserted =
                      prefsStore.upsertConnection(
                        profileId = target.profileId,
                        customName = target.customName,
                        deviceName = target.deviceName,
                        ipAddress = target.ipAddress,
                        port = target.controlPort,
                      )
                    prefsStore.setLastConnectedTvId(upserted.id)
                    runCatching {
                      if (sslCredentialsStore.hasPairedCredential(target.ipAddress, serverFp)) {
                        sslCredentialsStore.updateLastConnected(
                          ipAddress = target.ipAddress,
                          controlPort = target.controlPort,
                          serverName = target.deviceName,
                          serverFingerprintSha256 = serverFp,
                        )
                      } else {
                        certificateManager.savePairedTvCredential(
                          ipAddress = target.ipAddress,
                          controlPort = target.controlPort,
                          serverName = target.deviceName,
                          serverCertFingerprintSha256 = serverFp.orEmpty(),
                          pairingTokenHash = "",
                        )
                      }
                    }
                    upserted to prefsStore.loadSavedConnections()
                  }
                _uiState.update { state ->
                  val currentStatus = state.connectionStatus
                  state.copy(
                    savedConnections = allSaved,
                    connectionStatus =
                      if (currentStatus is ConnectionStatus.Connected &&
                        currentStatus.ipAddress == saved.ipAddress &&
                        currentStatus.port == saved.port
                      ) {
                        currentStatus.copy(displayName = saved.customName, deviceName = saved.deviceName)
                      } else {
                        currentStatus
                      },
                  )
                }
              }
            }
          }

          is RemoteControlClient.SessionEvent.PairingRequired -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            silentAutoHealInProgress = false
            inFlightReconnect?.complete(false)
            inFlightReconnect = null
            val target = pendingTarget ?: lastActiveTarget
            if (target != null) {
              val normalizedTargetHost = SslCredentialsDataStore.normalizeHost(target.ipAddress)
              val isPreviouslyPaired =
                _uiState.value.savedConnections.any { it.ipAddress == target.ipAddress } ||
                  normalizedTargetHost in pairedCredentialHosts ||
                  certificateManager.hasStoredCredentialForHost(target.ipAddress)
              if (postPairingReconnectRetryAvailable) {
                postPairingReconnectRetryAvailable = false
                autoReconnectJob?.cancel()
                autoReconnectJob =
                  viewModelScope.launch(safeCoroutineHandler) {
                    delay(500L)
                    reconnectMutex.withLock {
                      remoteClient.connect(target.ipAddress, target.controlPort)
                    }
                  }
              } else if (isPreviouslyPaired && !event.isExplicitTlsRejection) {
                // Already-paired TV closed the control channel early (typical during
                // multi-device handoff when another phone briefly held the TV session).
                // Never ask for a pairing code again — recover as a transient disconnect.
                handleUnexpectedSessionInterruption(event.reason)
              } else if (isPreviouslyPaired && event.isExplicitTlsRejection && pairedTvTlsRetryCount < 1) {
                // Give already-paired TVs one silent reconnect retry before concluding the TV
                // genuinely evicted our certificate.
                pairedTvTlsRetryCount++
                autoReconnectJob?.cancel()
                autoReconnectJob =
                  viewModelScope.launch(safeCoroutineHandler) {
                    delay(450L)
                    reconnectMutex.withLock {
                      remoteClient.connect(target.ipAddress, target.controlPort)
                    }
                  }
              } else {
                unexpectedDisconnectRetryCount = 0
                rapidDropCount = 0
                lastConnectedAtMs = 0L
                autoReconnectPaused = false
                pairedTvTlsRetryCount = 0
                initiatePairing(target)
              }
            }
          }

          is RemoteControlClient.SessionEvent.ConnectionLost -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            postPairingReconnectRetryAvailable = false
            inFlightReconnect?.complete(false)
            inFlightReconnect = null
            handleUnexpectedSessionInterruption(event.message)
          }

          is RemoteControlClient.SessionEvent.Error -> {
            connectionStabilityJob?.cancel()
            connectionStabilityJob = null
            activeHeldKey = null
            postPairingReconnectRetryAvailable = false
            inFlightReconnect?.complete(false)
            inFlightReconnect = null
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
      silentAutoHealInProgress = false
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
      silentAutoHealInProgress = false
      _uiState.update {
        it.copy(connectionStatus = ConnectionStatus.Disconnected)
      }
      return
    }
    val hitRapidDropCap = rapidDropCount >= MAX_RAPID_DROP_CYCLES
    val hitAttemptCap = unexpectedDisconnectRetryCount >= MAX_AUTO_RECONNECT_ATTEMPTS
    if (autoReconnectPaused || hitRapidDropCap || hitAttemptCap) {
      autoReconnectPaused = true
      silentAutoHealInProgress = false
      autoReconnectJob?.cancel()
      autoReconnectJob = null
      val displayMsg =
        if (hitRapidDropCap) {
          "Background sync paused (TV busy or another remote active). Press any key to reconnect."
        } else {
          errorMessage
        }
      val detail =
        if (hitRapidDropCap) {
          "Last cause: $errorMessage. Press any remote key to reconnect automatically, or tap Re-pair TV if the TV reset pairing."
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
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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
              if (!remoteClient.isConnectionHealthy() || remoteClient.isStale(WAKE_STALE_SILENCE_MS)) {
                scheduleSilentReconnect(
                  target = target,
                  delayMs = 250L,
                  forceRestart = false,
                  forceReconnectEvenIfOpen = true,
                )
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
    backgroundedAtMs = System.currentTimeMillis()
    releaseHeldKeyIfNeeded()
    tvVoiceAudioStreamer.stopStreaming()
    autoReconnectJob?.cancel()
    autoReconnectJob = null
  }

  internal fun markBackgroundedAtForTest(timestampMs: Long) {
    backgroundedAtMs = timestampMs
  }

  fun onAppForegrounded() {
    isAppInForeground = true
    val elapsedBgMs =
      if (backgroundedAtMs > 0L) (System.currentTimeMillis() - backgroundedAtMs).coerceAtLeast(0L)
      else 0L
    backgroundedAtMs = 0L

    val state = _uiState.value
    if (userManuallyDisconnected || autoReconnectPaused) return
    if (!state.settings.autoReconnectEnabled) return
    if (state.connectionStatus is ConnectionStatus.Pairing) return
    if (state.connectionStatus is ConnectionStatus.Connecting) return

    val target = lastActiveTarget ?: pendingTarget ?: resolveLastSavedTarget(state) ?: return
    val needsWakeHeal =
      !remoteClient.isConnectionHealthy() ||
        state.connectionStatus !is ConnectionStatus.Connected ||
        elapsedBgMs >= BACKGROUND_PROACTIVE_RECONNECT_MS ||
        remoteClient.isStale(WAKE_STALE_SILENCE_MS)

    if (needsWakeHeal) {
      unexpectedDisconnectRetryCount = 0
      rapidDropCount = 0
      scheduleSilentReconnect(
        target = target,
        delayMs = 0L,
        forceRestart = true,
        forceReconnectEvenIfOpen = true,
      )
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

  private fun isReconnectingSilently(): Boolean =
    silentAutoHealInProgress || _uiState.value.connectionStatus is ConnectionStatus.Connecting

  private fun scheduleSilentReconnect(
    target: PendingConnectionTarget,
    delayMs: Long,
    forceRestart: Boolean = false,
    forceReconnectEvenIfOpen: Boolean = false,
  ) {
    if (autoReconnectPaused) return
    if (autoReconnectJob?.isActive == true) {
      if (!forceRestart) return
      autoReconnectJob?.cancel()
    }
    silentAutoHealInProgress = true
    _uiState.update { state ->
      if (state.connectionStatus is ConnectionStatus.Pairing) {
        state
      } else {
        state.copy(
          connectionStatus =
            ConnectionStatus.Connecting(
              targetName = target.customName,
              ipAddress = target.ipAddress,
              port = target.controlPort,
              isSilentAutoHeal = true,
            ),
        )
      }
    }
    autoReconnectJob =
      viewModelScope.launch(safeCoroutineHandler) {
        if (delayMs > 0L) {
          delay(delayMs)
        }
        if (!isAppInForeground || userManuallyDisconnected || autoReconnectPaused) {
          silentAutoHealInProgress = false
          return@launch
        }
        if (!_uiState.value.settings.autoReconnectEnabled) {
          silentAutoHealInProgress = false
          return@launch
        }
        if (_uiState.value.connectionStatus is ConnectionStatus.Pairing) {
          silentAutoHealInProgress = false
          return@launch
        }

        reconnectMutex.withLock {
          if (autoReconnectPaused) {
            silentAutoHealInProgress = false
            return@withLock
          }
          if (!forceReconnectEvenIfOpen && remoteClient.isConnectionHealthy()) {
            silentAutoHealInProgress = false
            return@withLock
          }
          val deferred = CompletableDeferred<Boolean>()
          inFlightReconnect?.complete(false)
          inFlightReconnect = deferred
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
    pairedTvTlsRetryCount = 0
    lastConnectedAtMs = 0L
    navigateToRemoteOnConnect = true
    pendingTarget = target
    lastActiveTarget = target
    certificateManager.clearCachedHostCredential(target.ipAddress)
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { sslCredentialsStore.removePairedCredential(target.ipAddress) }
    }
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
    silentAutoHealInProgress = false
    inFlightReconnect?.complete(false)
    inFlightReconnect = null
    navigateToRemoteOnConnect = if (keepCurrentScreen) !_uiState.value.showConnectionHub else true
    unexpectedDisconnectRetryCount = 0
    rapidDropCount = 0
    lastConnectedAtMs = 0L
    postPairingReconnectRetryAvailable = false
    pairedTvTlsRetryCount = 0
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
            isSilentAutoHeal = false,
          )
      )
    }
    viewModelScope.launch(safeCoroutineHandler) {
      reconnectMutex.withLock {
        remoteClient.connect(target.ipAddress, target.controlPort)
      }
    }
  }

  private fun initiatePairing(target: PendingConnectionTarget) {
    cancelPairingInternal()
    viewModelScope.launch(safeCoroutineHandler) {
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

    viewModelScope.launch(safeCoroutineHandler) {
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
          withContext(Dispatchers.IO) {
            runCatching {
              certificateManager.savePairedTvCredential(
                ipAddress = target.ipAddress,
                controlPort = target.controlPort,
                serverName = result.serverName.ifBlank { target.deviceName },
                serverCertFingerprintSha256 = result.serverCertFingerprintSha256,
                pairingTokenHash = result.pairingTokenHash,
              )
            }
          }
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
    silentAutoHealInProgress = false
    inFlightReconnect?.complete(false)
    inFlightReconnect = null
    rapidDropCount = 0
    lastConnectedAtMs = 0L
    unexpectedDisconnectRetryCount = 0
    pairedTvTlsRetryCount = 0
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

  fun openOttAppsSheet(show: Boolean) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    _uiState.update { it.copy(showOttAppsSheet = show) }
  }

  fun openKeyboardSheet(show: Boolean) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    if (!show) {
      remoteClient.clearImeRequested()
    }
    _uiState.update { it.copy(showKeyboardSheet = show) }
  }

  fun openSearchWithKeyboard() {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    _uiState.update { it.copy(showOttAppsSheet = false, showKeyboardSheet = true) }
    viewModelScope.launch(safeCoroutineHandler) {
      sendWithAutoRecovery(userInitiated = true) {
        remoteClient.sendKey(RemoteKeyCode.KEYCODE_SEARCH, RemoteDirection.SHORT)
      }
    }
  }

  fun sendKeyboardImeText(newText: String, previousText: String) {
    viewModelScope.launch(safeCoroutineHandler) {
      if (_uiState.value.tvImeState.isImeRequested) {
        sendWithAutoRecovery(userInitiated = true) {
          remoteClient.sendImeText(newText = newText, previousLength = previousText.length)
        }
      }
    }
  }

  fun submitKeyboardSearch(text: String, useDirectKeys: Boolean) {
    val clean = text.trim()
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    viewModelScope.launch(safeCoroutineHandler) {
      val sent =
        sendWithAutoRecovery(userInitiated = true) {
          val imeState = _uiState.value.tvImeState
          val textOk =
            if (clean.isEmpty()) {
              true
            } else if (useDirectKeys || !imeState.isImeRequested) {
              remoteClient.sendTextAsKeyEvents(clean)
            } else {
              remoteClient.sendImeText(newText = clean, previousLength = imeState.currentText.length)
            }
          if (textOk) {
            remoteClient.sendKey(RemoteKeyCode.KEYCODE_ENTER, RemoteDirection.SHORT)
          } else {
            false
          }
        }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected && !isReconnectingSilently()) {
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

  fun fallbackToTvVoiceAssistant(reason: String) {
    sendKey(RemoteKeyCode.KEYCODE_VOICE_ASSIST, FeedbackCue.POWER_OR_APP)
    val connected = _uiState.value.connectionStatus is ConnectionStatus.Connected
    val banner =
      if (connected) {
        "Opened TV Assistant on your TV."
      } else {
        "$reason Connect to a TV to open TV Assistant."
      }
    _uiState.update { state ->
      state.copy(
        voiceState =
          state.voiceState.copy(
            isListening = false,
            rmsLevel = 0f,
            partialTranscript = "",
            executedActionLabel = "Sent directly to TV Assistant",
            errorMessage = reason,
          ),
        statusBannerMessage = banner,
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

    val parsed =
      runCatching { VoiceCommandParser.parse(clean, allShortcuts) }.getOrNull() ?: return

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
    val cleanQuery = query.trim()
    if (cleanQuery.isEmpty()) return
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    viewModelScope.launch(safeCoroutineHandler) {
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
              remoteClient.sendImeText(newText = cleanQuery, previousLength = imeState.currentText.length)
            } else {
              remoteClient.sendTextAsKeyEvents(cleanQuery)
            }
          if (textOk) {
            remoteClient.sendKey(RemoteKeyCode.KEYCODE_ENTER, RemoteDirection.SHORT)
          } else {
            false
          }
        }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected && !isReconnectingSilently()) {
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
            isListening = false,
            rmsLevel = 0f,
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
    viewModelScope.launch(safeCoroutineHandler) {
      val sent = sendWithAutoRecovery { remoteClient.sendKey(keyCode, direction) }
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected && !isReconnectingSilently()) {
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
    viewModelScope.launch(safeCoroutineHandler) {
      val sent = sendWithAutoRecovery { remoteClient.sendKey(keyCode, RemoteDirection.START_LONG) }
      if (!sent) {
        if (activeHeldKey == keyCode) activeHeldKey = null
        if (_uiState.value.connectionStatus !is ConnectionStatus.Connected && !isReconnectingSilently()) {
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
    viewModelScope.launch(safeCoroutineHandler) {
      if (remoteClient.isConnectionHealthy()) {
        remoteClient.sendKey(keyCode, RemoteDirection.END_LONG)
      }
    }
  }

  private fun releaseHeldKeyIfNeeded() {
    val held = activeHeldKey ?: return
    activeHeldKey = null
    viewModelScope.launch(safeCoroutineHandler) {
      if (remoteClient.isConnectionHealthy()) {
        remoteClient.sendKey(held, RemoteDirection.END_LONG)
      }
    }
  }

  fun launchShortcut(shortcut: TvAppShortcut) {
    feedbackManager.trigger(FeedbackCue.POWER_OR_APP, _uiState.value.settings)
    _uiState.update { it.copy(showOttAppsSheet = false) }
    ottLaunchJob?.cancel()
    ottLaunchJob =
      viewModelScope.launch(safeCoroutineHandler) {
        if (!ensureConnectedForCommand(userInitiated = true)) {
          _uiState.update {
            it.copy(
              statusBannerMessage =
                if (autoReconnectPaused) "Unable to reach TV right now. Check Wi-Fi or tap Retry."
                else "Connect to your TV to launch ${shortcut.name}."
            )
          }
          return@launch
        }
        val plan = TvAppShortcuts.buildLaunchPlan(shortcut)
        var outcome = remoteClient.launchAppWithPlan(plan)
        if (
          outcome == OttLaunchOutcome.FAILED &&
          _uiState.value.connectionStatus !is ConnectionStatus.Pairing &&
          ensureConnectedForCommand(forceReconnect = true, userInitiated = true)
        ) {
          outcome = remoteClient.launchAppWithPlan(plan)
        }
        val banner =
          when (outcome) {
            OttLaunchOutcome.LAUNCHED_NATIVE_APP -> {
              val primaryPkg = shortcut.packageNames.firstOrNull().orEmpty()
              if (primaryPkg.isNotEmpty()) {
                handleDetectedTvApp(
                  packageName = primaryPkg,
                  label = shortcut.name,
                  allowAutoAdd = false,
                )
              }
              "Opening ${shortcut.name} app on TV..."
            }
            OttLaunchOutcome.OPENED_IN_BROWSER -> "${shortcut.name} app not on TV — opening in TV browser..."
            OttLaunchOutcome.FAILED ->
              "Connect to your TV to launch ${shortcut.name}."
          }
        _uiState.update { it.copy(statusBannerMessage = banner) }
      }
  }

  private suspend fun ensureConnectedForCommand(
    forceReconnect: Boolean = false,
    userInitiated: Boolean = true,
  ): Boolean {
    if (!forceReconnect && remoteClient.isConnectionHealthy()) return true
    if (userInitiated) {
      // Any explicit user key press, OTT tap, or search immediately clears background
      // reconnect pause (e.g. after multi-device handoff) and silently reconnects.
      if (autoReconnectPaused) {
        autoReconnectPaused = false
        rapidDropCount = 0
        unexpectedDisconnectRetryCount = 0
      }
      userManuallyDisconnected = false
    } else if (autoReconnectPaused) {
      return false
    }
    val target = lastActiveTarget ?: pendingTarget ?: resolveLastSavedTarget(_uiState.value) ?: return false
    if (!userInitiated && (userManuallyDisconnected || !_uiState.value.settings.autoReconnectEnabled)) return false
    if (_uiState.value.connectionStatus is ConnectionStatus.Pairing) return false

    // Single-flight coalescing: if a silent auto-heal or command reconnect is already running,
    // await it cleanly instead of tearing down or fighting the active handshake.
    val existing = inFlightReconnect
    if (existing != null && !existing.isCompleted) {
      val joinedResult =
        withTimeoutOrNull(RECONNECT_BEFORE_RETRY_TIMEOUT_MS) { existing.await() }
      if (joinedResult == true && remoteClient.isConnectionHealthy()) {
        return true
      }
    }

    autoReconnectJob?.cancel()
    autoReconnectJob = null
    return reconnectMutex.withLock {
      if (!userInitiated && autoReconnectPaused) {
        false
      } else if (!forceReconnect && remoteClient.isConnectionHealthy()) {
        true
      } else {
        if (userInitiated) {
          autoReconnectPaused = false
          rapidDropCount = 0
          unexpectedDisconnectRetryCount = 0
        }
        val deferred = CompletableDeferred<Boolean>()
        inFlightReconnect?.complete(false)
        inFlightReconnect = deferred
        silentAutoHealInProgress = true
        pendingTarget = target
        lastActiveTarget = target
        navigateToRemoteOnConnect = !_uiState.value.showConnectionHub
        remoteClient.connect(target.ipAddress, target.controlPort)
        val event =
          withTimeoutOrNull(RECONNECT_BEFORE_RETRY_TIMEOUT_MS) {
            remoteClient.sessionEvent.first {
              it is RemoteControlClient.SessionEvent.Connected ||
                it is RemoteControlClient.SessionEvent.PairingRequired ||
                it is RemoteControlClient.SessionEvent.ConnectionLost ||
                it is RemoteControlClient.SessionEvent.Error
            }
          }
        val connected = event is RemoteControlClient.SessionEvent.Connected
        deferred.complete(connected)
        if (inFlightReconnect === deferred) {
          inFlightReconnect = null
        }
        if (connected) {
          silentAutoHealInProgress = false
        }
        connected
      }
    }
  }

  private suspend fun sendWithAutoRecovery(
    userInitiated: Boolean = true,
    action: suspend () -> Boolean,
  ): Boolean {
    if (action()) return true
    if (!userInitiated && autoReconnectPaused) return false
    return if (ensureConnectedForCommand(forceReconnect = true, userInitiated = userInitiated)) action() else false
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
    val removed = _uiState.value.savedConnections.firstOrNull { it.id == id }
    val updated = _uiState.value.savedConnections.filterNot { it.id == id }
    prefsStore.saveConnections(updated)
    if (prefsStore.getLastConnectedTvId() == id) {
      prefsStore.setLastConnectedTvId(null)
    }
    if (removed != null && updated.none { it.ipAddress == removed.ipAddress }) {
      certificateManager.clearCachedHostCredential(removed.ipAddress)
      viewModelScope.launch(Dispatchers.IO) {
        runCatching { sslCredentialsStore.removePairedCredential(removed.ipAddress) }
      }
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
    viewModelScope.launch(safeCoroutineHandler) {
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
                if (manual) "Set your release source above to enable update checks."
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
            showStreamingShortcuts = false,
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
    val disabling = shortcutId in _uiState.value.uiPreferences.enabledShortcutIds
    dismissedAutoSyncIds =
      if (disabling) dismissedAutoSyncIds + shortcutId else dismissedAutoSyncIds - shortcutId
    prefsStore.saveDismissedAutoSyncIds(dismissedAutoSyncIds)
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
    dismissedAutoSyncIds = emptySet()
    prefsStore.saveDismissedAutoSyncIds(dismissedAutoSyncIds)
    updateUiPreferences { current ->
      current.copy(enabledShortcutIds = current.allAvailableShortcuts().map { it.id }.toSet())
    }
  }

  fun addPopularOttShortcut(preset: TvAppShortcut) {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
    dismissedAutoSyncIds = dismissedAutoSyncIds - preset.id
    prefsStore.saveDismissedAutoSyncIds(dismissedAutoSyncIds)
    val builtInIds = TvAppShortcuts.all.map { it.id }.toSet()
    updateUiPreferences { current ->
      val isBuiltIn = preset.id in builtInIds
      val existing = current.customShortcuts.any { it.id == preset.id }
      val nextCustom =
        if (isBuiltIn || existing) current.customShortcuts
        else current.customShortcuts + preset.copy(isCustom = true)
      current.copy(
        customShortcuts = nextCustom,
        enabledShortcutIds = current.enabledShortcutIds + preset.id,
      )
    }
    _uiState.update { it.copy(statusBannerMessage = "Added ${preset.name} to OTT Apps") }
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
      )
    }
    _uiState.update { it.copy(statusBannerMessage = "Added \"$cleanName\" to OTT Apps") }
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
    dismissedAutoSyncIds = dismissedAutoSyncIds + id
    prefsStore.saveDismissedAutoSyncIds(dismissedAutoSyncIds)
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
    private const val RECONNECT_BEFORE_RETRY_TIMEOUT_MS = 3_500L
    internal const val BACKGROUND_PROACTIVE_RECONNECT_MS = 2_000L
    internal const val WAKE_STALE_SILENCE_MS = 6_000L
  }
}
