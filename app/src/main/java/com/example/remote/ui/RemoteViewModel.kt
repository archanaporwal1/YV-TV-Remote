package com.example.remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.proto.remote.RemoteDirection
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.AppSettings
import com.example.remote.data.RemotePreferencesStore
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.UserProfile
import com.example.remote.discovery.DiscoveredTv
import com.example.remote.discovery.TvDiscoveryManager
import com.example.remote.feedback.FeedbackCue
import com.example.remote.feedback.FeedbackManager
import com.example.remote.protocol.CertificateManager
import com.example.remote.protocol.PairingClient
import com.example.remote.protocol.RemoteControlClient
import com.example.remote.protocol.TvImeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
  ) : ConnectionStatus()
}

data class PendingConnectionTarget(
  val ipAddress: String,
  val controlPort: Int,
  val deviceName: String,
  val customName: String,
  val profileId: String,
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
  val inputMode: RemoteInputMode = RemoteInputMode.DPAD,
  val showConnectionHub: Boolean = true,
  val showSettingsSheet: Boolean = false,
  val showKeyboardSheet: Boolean = false,
  val tvImeState: TvImeState = TvImeState(),
  val statusBannerMessage: String? = null,
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
  private var autoConnectAttempted = false
  private var postPairingReconnectRetryAvailable = false

  init {
    loadStoredState()
    observeDiscovery()
    observeRemoteSession()
    observeTvImeState()
    attemptAutoReconnectIfEnabled()
  }

  private fun observeTvImeState() {
    viewModelScope.launch {
      remoteClient.imeState.collect { ime ->
        _uiState.update { it.copy(tvImeState = ime) }
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

    _uiState.update {
      it.copy(
        profiles = profiles,
        activeProfileId = activeProfileId,
        savedConnections = saved,
        settings = settings,
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
            _uiState.update { state ->
              if (state.connectionStatus is ConnectionStatus.Pairing) {
                state
              } else {
                state.copy(connectionStatus = ConnectionStatus.Disconnected)
              }
            }
          }

          is RemoteControlClient.SessionEvent.Connecting -> {
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
            val target = pendingTarget
            if (target != null) {
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
                  showConnectionHub = false,
                  statusBannerMessage = null,
                )
              }
            }
          }

          is RemoteControlClient.SessionEvent.PairingRequired -> {
            val target = pendingTarget
            if (target != null) {
              if (postPairingReconnectRetryAvailable) {
                postPairingReconnectRetryAvailable = false
                delay(500L)
                remoteClient.connect(target.ipAddress, target.controlPort)
              } else {
                initiatePairing(target)
              }
            }
          }

          is RemoteControlClient.SessionEvent.Error -> {
            postPairingReconnectRetryAvailable = false
            val target = pendingTarget
            _uiState.update {
              it.copy(
                connectionStatus =
                  ConnectionStatus.Error(
                    message = event.message,
                    targetIp = target?.ipAddress,
                    targetPort = target?.controlPort ?: RemoteControlClient.DEFAULT_REMOTE_PORT,
                    targetName = target?.customName ?: target?.deviceName,
                  )
              )
            }
          }
        }
      }
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
    val target =
      PendingConnectionTarget(
        ipAddress = ipAddress.trim(),
        controlPort = controlPort,
        deviceName = deviceName,
        customName = customName.trim().ifEmpty { deviceName },
        profileId = _uiState.value.activeProfileId,
      )
    pendingTarget = target
    initiatePairing(target)
  }

  private fun connectWithAutoPair(target: PendingConnectionTarget) {
    cancelPairingInternal()
    postPairingReconnectRetryAvailable = false
    pendingTarget = target
    discoveryManager.stopScan()
    viewModelScope.launch {
      remoteClient.connect(target.ipAddress, target.controlPort)
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
          val target = pendingTarget!!
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
          postPairingReconnectRetryAvailable = true
          delay(300L)
          remoteClient.connect(target.ipAddress, target.controlPort)
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
    cancelPairingInternal()
    remoteClient.disconnect()
    _uiState.update {
      it.copy(
        connectionStatus = ConnectionStatus.Disconnected,
        showConnectionHub = true,
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
      if (_uiState.value.connectionStatus !is ConnectionStatus.Connected) {
        _uiState.update {
          it.copy(statusBannerMessage = "Not connected to a TV. Connect or pair a device first.")
        }
        return@launch
      }
      val sent =
        if (useDirectKeys) {
          remoteClient.sendTextAsKeyEvents(text)
        } else {
          val prevLen = _uiState.value.tvImeState.currentText.length
          remoteClient.sendImeText(newText = text, previousLength = prevLen)
        }
      if (sent) {
        remoteClient.sendKey(RemoteKeyCode.KEYCODE_ENTER, RemoteDirection.SHORT)
      }
    }
  }

  fun sendKeyboardKey(keyCode: RemoteKeyCode, cue: FeedbackCue = FeedbackCue.NAV_TICK) {
    sendKey(keyCode, cue)
  }

  fun setInputMode(mode: RemoteInputMode) {
    feedbackManager.trigger(FeedbackCue.NAV_TICK, _uiState.value.settings)
    _uiState.update { it.copy(inputMode = mode) }
  }

  fun sendKey(keyCode: RemoteKeyCode, cue: FeedbackCue = FeedbackCue.NAV_TICK) {
    feedbackManager.trigger(cue, _uiState.value.settings)
    viewModelScope.launch {
      val sent = remoteClient.sendKey(keyCode, RemoteDirection.SHORT)
      if (!sent && _uiState.value.connectionStatus !is ConnectionStatus.Connected) {
        _uiState.update {
          it.copy(statusBannerMessage = "Not connected to a TV. Connect or pair a device first.")
        }
      }
    }
  }

  fun launchShortcut(shortcut: TvAppShortcut) {
    feedbackManager.trigger(FeedbackCue.POWER_OR_APP, _uiState.value.settings)
    viewModelScope.launch {
      val sent = remoteClient.launchApp(shortcut.appLink)
      _uiState.update {
        it.copy(
          statusBannerMessage =
            if (sent) "Launching ${shortcut.name} on TV..."
            else "Connect to your TV to launch ${shortcut.name}."
        )
      }
    }
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

  fun previewFeedback() {
    feedbackManager.trigger(FeedbackCue.CONFIRM_CHIME, _uiState.value.settings)
  }

  override fun onCleared() {
    super.onCleared()
    discoveryManager.stopScan()
    cancelPairingInternal()
    remoteClient.disconnect()
    feedbackManager.release()
  }
}
