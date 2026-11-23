package com.example.remote.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.UserProfile
import com.example.remote.discovery.DiscoveredTv
import com.example.remote.feedback.FeedbackCue
import com.example.remote.voice.AndroidSpeechRecognizerController
import com.example.ui.theme.AmberStatus

@Composable
fun YvTvRemoteApp(viewModel: RemoteViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }

  val requiredPermissions =
    remember {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
      } else {
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      }
    }

  var hasWifiPermission by remember {
    mutableStateOf(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        checkPermissionSafe(context, Manifest.permission.NEARBY_WIFI_DEVICES)
      } else {
        requiredPermissions.any { checkPermissionSafe(context, it) }
      }
    )
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
      hasWifiPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          results.values.all { it }
        } else {
          results.values.any { it }
        }
      if (hasWifiPermission) {
        viewModel.startDiscoveryScan()
      }
    }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

  var hasMicPermission by remember {
    mutableStateOf(checkPermissionSafe(context, Manifest.permission.RECORD_AUDIO))
  }
  var autoAssistantFallbackUsedForAttempt by remember { mutableStateOf(false) }
  var voiceLaunchRequestToken by remember { mutableIntStateOf(0) }

  val routeVoiceFailureToTvAssistant: (String) -> Unit = { reason ->
    if (!autoAssistantFallbackUsedForAttempt) {
      autoAssistantFallbackUsedForAttempt = true
      viewModel.fallbackToTvVoiceAssistant(reason)
    }
  }

  val appContext = remember(context) { context.applicationContext ?: context }
  val speechController =
    remember(appContext) {
      AndroidSpeechRecognizerController(
        context = appContext,
        onReady = { viewModel.onVoiceListeningStarted() },
        onRmsChanged = { level -> viewModel.onVoiceRmsChanged(level) },
        onPartialResult = { partial -> viewModel.onVoicePartialResult(partial) },
        onFinalResult = { transcript -> viewModel.onVoiceFinalResult(transcript) },
        onError = { errMsg -> routeVoiceFailureToTvAssistant(errMsg) },
      )
    }

  DisposableEffect(speechController) {
    onDispose { speechController.destroy() }
  }

  val fallbackSpeechLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        val spoken =
          runCatching {
            result.data
              ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
              ?.firstOrNull()
              ?.trim()
          }.getOrNull()
        if (!spoken.isNullOrEmpty()) {
          viewModel.onVoiceFinalResult(spoken)
        } else {
          routeVoiceFailureToTvAssistant("No spoken text captured — sent directly to TV Assistant.")
        }
      } else {
        routeVoiceFailureToTvAssistant("Voice capture cancelled — sent directly to TV Assistant.")
      }
    }

  val launchSpeechCapture: () -> Unit = {
    if (!checkPermissionSafe(context, Manifest.permission.RECORD_AUDIO)) {
      routeVoiceFailureToTvAssistant("Microphone permission unavailable — sent directly to TV Assistant.")
    } else if (speechController.isAvailable()) {
      speechController.startListening()
    } else {
      val fallbackIntent = AndroidSpeechRecognizerController.buildFallbackActivityIntent()
      val canResolve =
        runCatching {
          val pm = context.packageManager
          fallbackIntent.resolveActivity(pm) != null
        }.getOrDefault(false)
      if (canResolve) {
        val launched = runCatching { fallbackSpeechLauncher.launch(fallbackIntent) }
        if (launched.isFailure) {
          routeVoiceFailureToTvAssistant(
            "On-device speech service is unavailable — sent directly to TV Assistant."
          )
        }
      } else {
        routeVoiceFailureToTvAssistant(
          "Speech recognition service is unavailable on this phone — sent directly to TV Assistant."
        )
      }
    }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
      hasMicPermission = granted
      if (granted) {
        launchSpeechCapture()
      } else {
        routeVoiceFailureToTvAssistant(
          "Microphone permission denied — sent directly to TV Assistant."
        )
      }
    }

  LaunchedEffect(voiceLaunchRequestToken) {
    if (voiceLaunchRequestToken <= 0) return@LaunchedEffect
    autoAssistantFallbackUsedForAttempt = false
    val micGrantedNow = checkPermissionSafe(context, Manifest.permission.RECORD_AUDIO)
    hasMicPermission = micGrantedNow
    if (micGrantedNow) {
      launchSpeechCapture()
    } else {
      val permissionRequestResult =
        runCatching { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
      if (permissionRequestResult.isFailure) {
        routeVoiceFailureToTvAssistant(
          "Unable to prompt for microphone access — sent directly to TV Assistant."
        )
      }
    }
  }

  val triggerVoiceInputFlow: () -> Unit = {
    autoAssistantFallbackUsedForAttempt = false
    viewModel.openVoiceSheet(true)
    voiceLaunchRequestToken++
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> {
          hasMicPermission = checkPermissionSafe(context, Manifest.permission.RECORD_AUDIO)
          viewModel.onAppForegrounded()
        }
        Lifecycle.Event.ON_STOP -> {
          speechController.stopListening()
          viewModel.onVoiceListeningStopped()
          viewModel.onAppBackgrounded()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  LaunchedEffect(uiState.showConnectionHub, hasWifiPermission) {
    if (uiState.showConnectionHub && hasWifiPermission) {
      viewModel.startDiscoveryScan()
    } else if (!uiState.showConnectionHub) {
      viewModel.stopDiscoveryScan()
    }
  }

  LaunchedEffect(uiState.statusBannerMessage) {
    val msg = uiState.statusBannerMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearBannerMessage()
    }
  }

  // Modal Dialog States
  var showManualIpDialog by remember { mutableStateOf(false) }
  var discoveredToSave by remember { mutableStateOf<DiscoveredTv?>(null) }
  var savedTvToEdit by remember { mutableStateOf<SavedTvConnection?>(null) }
  var savedTvToDelete by remember { mutableStateOf<SavedTvConnection?>(null) }
  var showCreateProfileDialog by remember { mutableStateOf(false) }
  var profileToRename by remember { mutableStateOf<UserProfile?>(null) }
  var showAddOttDialog by remember { mutableStateOf(false) }
  var customOttToEdit by remember { mutableStateOf<TvAppShortcut?>(null) }

  if (!uiState.showConnectionHub) {
    BackHandler {
      viewModel.openConnectionHub()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      AnimatedContent(
        targetState = uiState.showConnectionHub,
        label = "screen_switch",
      ) { showHub ->
        if (showHub) {
          ConnectionHubView(
            uiState = uiState,
            hasWifiScanPermission = hasWifiPermission,
            onRequestScanPermission = {
              runCatching { permissionLauncher.launch(requiredPermissions) }
            },
            onStartScan = { viewModel.startDiscoveryScan() },
            onStopScan = { viewModel.stopDiscoveryScan() },
            onOpenManualIp = { showManualIpDialog = true },
            onOpenSettings = { viewModel.openSettingsSheet(true) },
            onOpenRemote = { viewModel.openRemoteScreen() },
            onSelectProfile = { viewModel.selectProfile(it) },
            onCreateProfileClick = { showCreateProfileDialog = true },
            onConnectSavedTv = { viewModel.connectToSavedTv(it) },
            onEditSavedTv = { savedTvToEdit = it },
            onDeleteSavedTv = { savedTvToDelete = it },
            onConnectDiscoveredTv = { viewModel.connectToDiscoveredTv(it) },
            onSaveDiscoveredTv = { discoveredToSave = it },
            onDownloadUpdate = { viewModel.downloadAvailableUpdate() },
            onOpenUpdateDetails = { viewModel.openUpdateDialog(true) },
            onRepairConnection = { viewModel.repairCurrentTarget() },
          )
        } else {
          RemoteControlScreen(
            uiState = uiState,
            onOpenConnectionHub = { viewModel.openConnectionHub() },
            onOpenCustomizeUi = { viewModel.openCustomizeUiSheet(true) },
            onOpenSettings = { viewModel.openSettingsSheet(true) },
            onOpenOttApps = { viewModel.openOttAppsSheet(true) },
            onOpenKeyboard = { viewModel.openKeyboardSheet(true) },
            onOpenSearch = { viewModel.openSearchWithKeyboard() },
            onOpenVoiceInput = triggerVoiceInputFlow,
            onTriggerTvVoiceAssistant = { viewModel.triggerTvVoiceAssistant() },
            onSwitchInputMode = { viewModel.setInputMode(it) },
            onSendKey = { keyCode, cue -> viewModel.sendKey(keyCode, cue) },
            onKeyHoldStart = { keyCode, cue -> viewModel.onKeyHoldStart(keyCode, cue) },
            onKeyHoldEnd = { keyCode -> viewModel.onKeyHoldEnd(keyCode) },
            onLaunchShortcut = { viewModel.launchShortcut(it) },
            onOpenAddOttDialog = { showAddOttDialog = true },
            onDownloadUpdate = { viewModel.downloadAvailableUpdate() },
            onOpenUpdateDetails = { viewModel.openUpdateDialog(true) },
            onRetryConnection = { viewModel.retryCurrentTarget() },
            onRepairConnection = { viewModel.repairCurrentTarget() },
          )
        }
      }
    }
  }

  // Pairing 6-Hex PIN Dialog
  val pairingState = uiState.connectionStatus as? ConnectionStatus.Pairing
  if (pairingState != null) {
    PairingPinDialog(
      pairingState = pairingState,
      onSubmitPin = { pin, customName -> viewModel.submitPairingPin(pin, customName) },
      onDismiss = { viewModel.cancelPairing() },
    )
  }

  if (showManualIpDialog) {
    ManualIpDialog(
      onConnect = { ip, port, customName ->
        showManualIpDialog = false
        viewModel.connectManualIp(ip, port, customName)
      },
      onDismiss = { showManualIpDialog = false },
    )
  }

  discoveredToSave?.let { tv ->
    SaveOrEditTvDialog(
      title = "Save Discovered TV",
      initialName = tv.displayName,
      initialIp = tv.ipAddress,
      initialPort = tv.port,
      initialProfileId = uiState.activeProfileId,
      profiles = uiState.profiles,
      allowEditAddress = false,
      onSave = { name, _, _, profileId ->
        viewModel.saveDiscoveredTv(tv, name, profileId)
        discoveredToSave = null
      },
      onDismiss = { discoveredToSave = null },
    )
  }

  savedTvToEdit?.let { savedTv ->
    SaveOrEditTvDialog(
      title = "Edit Saved TV",
      initialName = savedTv.customName,
      initialIp = savedTv.ipAddress,
      initialPort = savedTv.port,
      initialProfileId = savedTv.profileId,
      profiles = uiState.profiles,
      allowEditAddress = true,
      onSave = { name, ip, port, profileId ->
        viewModel.updateSavedTv(savedTv.id, name, ip, port, profileId)
        savedTvToEdit = null
      },
      onDismiss = { savedTvToEdit = null },
    )
  }

  savedTvToDelete?.let { savedTv ->
    DeleteConfirmDialog(
      tv = savedTv,
      onConfirmDelete = {
        viewModel.deleteSavedTv(savedTv.id)
        savedTvToDelete = null
      },
      onDismiss = { savedTvToDelete = null },
    )
  }

  if (showCreateProfileDialog) {
    ProfileEditorDialog(
      title = "New User Profile",
      confirmLabel = "Create",
      onConfirm = { name ->
        viewModel.createProfile(name)
        showCreateProfileDialog = false
      },
      onDismiss = { showCreateProfileDialog = false },
    )
  }

  profileToRename?.let { prof ->
    ProfileEditorDialog(
      title = "Rename Profile",
      initialName = prof.name,
      confirmLabel = "Save",
      onConfirm = { name ->
        viewModel.renameProfile(prof.id, name)
        profileToRename = null
      },
      onDismiss = { profileToRename = null },
    )
  }

  if (uiState.showSettingsSheet) {
    SettingsModalSheet(
      uiState = uiState,
      onDismiss = { viewModel.openSettingsSheet(false) },
      onOpenCustomizeUi = {
        viewModel.openSettingsSheet(false)
        viewModel.openCustomizeUiSheet(true)
      },
      onSelectThemeMode = { viewModel.setThemeMode(it) },
      onToggleHaptics = { viewModel.updateHapticsEnabled(it) },
      onToggleSound = { viewModel.updateSoundEnabled(it) },
      onChangeSoundVolume = { viewModel.updateSoundVolume(it) },
      onTestFeedback = { viewModel.previewFeedback() },
      onToggleAutoReconnect = { viewModel.updateAutoReconnect(it) },
      onToggleAutoCheckUpdates = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (!checkPermissionSafe(context, Manifest.permission.POST_NOTIFICATIONS)) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
          }
        }
        viewModel.updateAutoCheckUpdates(enabled)
      },
      onChangeReleaseRepo = { viewModel.updateReleaseRepoOverride(it) },
      onCheckUpdatesNow = { viewModel.checkForUpdates(manual = true) },
      onOpenAvailableUpdate = {
        viewModel.openSettingsSheet(false)
        viewModel.openUpdateDialog(true)
      },
      onSelectProfile = { viewModel.selectProfile(it) },
      onCreateProfileClick = { showCreateProfileDialog = true },
      onRenameProfileClick = { profileToRename = it },
      onDeleteProfile = { viewModel.deleteProfile(it.id) },
      onEditSavedTv = { savedTvToEdit = it },
      onDeleteSavedTv = { savedTvToDelete = it },
    )
  }

  if (uiState.showCustomizeUiSheet) {
    CustomizeUiSheet(
      uiPrefs = uiState.uiPreferences,
      onDismiss = { viewModel.openCustomizeUiSheet(false) },
      onApplyPreset = { viewModel.applyUiPreset(it) },
      onSelectThemeMode = { viewModel.setThemeMode(it) },
      onSelectAccentTheme = { viewModel.setAccentTheme(it) },
      onSelectDefaultPad = { viewModel.setPreferredPadMode(it) },
      onUpdatePreferences = { transform -> viewModel.updateUiPreferences(transform) },
      onToggleShortcut = { viewModel.toggleShortcutEnabled(it) },
      onSelectAllShortcuts = { viewModel.selectAllShortcuts() },
      onOpenAddOttDialog = { showAddOttDialog = true },
      onEditCustomShortcut = { customOttToEdit = it },
      onDeleteCustomShortcut = { viewModel.deleteCustomOttShortcut(it) },
      onResetDefaults = { viewModel.resetUiPreferences() },
    )
  }

  if (showAddOttDialog || customOttToEdit != null) {
    val editing = customOttToEdit
    AddOrEditOttShortcutDialog(
      editingShortcut = editing,
      alreadyAddedIds = uiState.uiPreferences.enabledShortcutIds,
      detectedOnTvShortcuts = uiState.detectedTvShortcuts,
      onQuickAddPopular = { preset ->
        viewModel.addPopularOttShortcut(preset)
        showAddOttDialog = false
        customOttToEdit = null
      },
      onSaveCustom = { name, appLink, packageName, badge, accentColor, iconStyle ->
        if (editing != null) {
          viewModel.updateCustomOttShortcut(
            id = editing.id,
            name = name,
            appLink = appLink,
            packageName = packageName,
            badge = badge,
            accentColor = accentColor,
            iconStyle = iconStyle,
          )
        } else {
          viewModel.addCustomOttShortcut(
            name = name,
            appLink = appLink,
            packageName = packageName,
            badge = badge,
            accentColor = accentColor,
            iconStyle = iconStyle,
          )
        }
        showAddOttDialog = false
        customOttToEdit = null
      },
      onDismiss = {
        showAddOttDialog = false
        customOttToEdit = null
      },
    )
  }

  val availableUpdate = uiState.availableUpdate
  if (uiState.showUpdateDialog && availableUpdate != null) {
    AppUpdateReleaseDialog(
      release = availableUpdate,
      onDownload = { viewModel.downloadAvailableUpdate() },
      onSkipVersion = { viewModel.skipAvailableUpdate() },
      onDismiss = { viewModel.openUpdateDialog(false) },
    )
  }

  if (uiState.showOttAppsSheet) {
    val ottShortcuts =
      remember(uiState.uiPreferences.enabledShortcutIds, uiState.uiPreferences.customShortcuts) {
        uiState.uiPreferences.enabledShortcuts()
      }
    OttAppsDrawerSheet(
      shortcuts = ottShortcuts,
      columns = uiState.uiPreferences.shortcutColumns,
      compact = uiState.uiPreferences.compactMode,
      verifiedOnTvIds = uiState.verifiedOnTvShortcutIds,
      onLaunchShortcut = { viewModel.launchShortcut(it) },
      onAddOttApp = { showAddOttDialog = true },
      onOpenCustomize = {
        viewModel.openOttAppsSheet(false)
        viewModel.openCustomizeUiSheet(true)
      },
      onDismiss = { viewModel.openOttAppsSheet(false) },
    )
  }

  if (uiState.showKeyboardSheet) {
    KeyboardSearchSheet(
      imeState = uiState.tvImeState,
      onDismiss = { viewModel.openKeyboardSheet(false) },
      onLiveImeChange = { newText, prevText ->
        viewModel.sendKeyboardImeText(newText, prevText)
      },
      onSubmitSearch = { text, useDirectKeys ->
        viewModel.submitKeyboardSearch(text, useDirectKeys)
      },
      onSendKey = { keyCode, cue -> viewModel.sendKeyboardKey(keyCode, cue) },
      onKeyHoldStart = { keyCode, cue -> viewModel.onKeyHoldStart(keyCode, cue) },
      onKeyHoldEnd = { keyCode -> viewModel.onKeyHoldEnd(keyCode) },
      onOpenVoiceInput = {
        viewModel.openKeyboardSheet(false)
        triggerVoiceInputFlow()
      },
    )
  }

  if (uiState.voiceState.showVoiceSheet) {
    VoiceCommandSheet(
      voiceState = uiState.voiceState,
      hasMicPermission = hasMicPermission,
      isSpeechRecognizerAvailable = speechController.isAvailable(),
      onDismiss = {
        speechController.stopListening()
        viewModel.openVoiceSheet(false)
      },
      onStartListening = {
        autoAssistantFallbackUsedForAttempt = false
        if (checkPermissionSafe(context, Manifest.permission.RECORD_AUDIO)) {
          hasMicPermission = true
          launchSpeechCapture()
        } else {
          val res = runCatching { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
          if (res.isFailure) {
            routeVoiceFailureToTvAssistant(
              "Unable to open microphone prompt — sent directly to TV Assistant."
            )
          }
        }
      },
      onStopListening = {
        speechController.stopListening()
        viewModel.onVoiceListeningStopped()
      },
      onRequestMicPermission = {
        autoAssistantFallbackUsedForAttempt = false
        val res = runCatching { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        if (res.isFailure) {
          routeVoiceFailureToTvAssistant(
            "Unable to open microphone prompt — sent directly to TV Assistant."
          )
        }
      },
      onTriggerTvAssistant = {
        speechController.stopListening()
        viewModel.triggerTvVoiceAssistant()
      },
      onRunVoiceCommandText = { phrase ->
        speechController.stopListening()
        viewModel.onVoiceFinalResult(phrase)
      },
    )
  }
}

private fun checkPermissionSafe(context: Context, permission: String): Boolean =
  runCatching {
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }.getOrDefault(false)

@Composable
fun RemoteControlScreen(
  uiState: RemoteUiState,
  onOpenConnectionHub: () -> Unit,
  onOpenCustomizeUi: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenOttApps: () -> Unit = {},
  onOpenKeyboard: () -> Unit,
  onOpenSearch: () -> Unit = onOpenKeyboard,
  onOpenVoiceInput: () -> Unit = {},
  onTriggerTvVoiceAssistant: () -> Unit = {},
  onSwitchInputMode: (RemoteInputMode) -> Unit,
  onSendKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  onLaunchShortcut: (TvAppShortcut) -> Unit,
  onOpenAddOttDialog: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onOpenUpdateDetails: () -> Unit,
  onRetryConnection: () -> Unit = {},
  onRepairConnection: () -> Unit = {},
) {
  val uiPrefs = uiState.uiPreferences
  val compact = uiPrefs.compactMode
  val sectionSpacing = if (compact) 10.dp else 14.dp
  val filteredShortcuts =
    remember(uiPrefs.enabledShortcutIds, uiPrefs.customShortcuts) {
      uiPrefs.enabledShortcuts()
    }
  val activeProfileName =
    uiState.profiles.firstOrNull { it.id == uiState.activeProfileId }?.name ?: "Profile"

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    BoxWithConstraints(
      modifier =
        Modifier
          .widthIn(max = 960.dp)
          .fillMaxSize()
          .padding(horizontal = 16.dp),
    ) {
      val availWidth = maxWidth
      val availHeight = maxHeight
      val isWideLayout = availWidth >= 660.dp || (availWidth > availHeight && availWidth >= 540.dp)
      val isNarrowWidth = availWidth < 370.dp

      // Adaptive viewport sizing so the entire remote fits smoothly without scrolling
      // on typical handhelds, compact phones, foldables, and tablets.
      val topChromeEstimate = 68.dp
      val bannerReserve =
        (if (uiState.connectionStatus is ConnectionStatus.Error) 86.dp else 0.dp) +
          (if (uiState.availableUpdate != null) 66.dp else 0.dp)
      val bodyBudget = (availHeight - topChromeEstimate - bannerReserve).coerceAtLeast(360.dp)

      val adaptiveGap: Dp
      val adaptivePodSize: Dp
      val adaptiveNavHeight: Dp
      val adaptiveConsoleHeight: Dp
      val adaptiveMediaHeight: Dp

      if (isWideLayout) {
        adaptiveGap =
          if (compact) 8.dp
          else ((bodyBudget - 340.dp) * 0.08f).coerceIn(10.dp, 18.dp)
        val columnBudget = (bodyBudget - adaptiveGap * 2).coerceAtLeast(240.dp)
        val maxPodByHeight = columnBudget - 48.dp - if (uiPrefs.showNavigationRow) 54.dp + adaptiveGap else 0.dp
        val maxPodByColWidth = (availWidth / 2f) - 48.dp
        adaptivePodSize =
          minOf(maxPodByHeight, maxPodByColWidth, if (compact) 232.dp else 268.dp).coerceAtLeast(168.dp)
        adaptiveNavHeight = (columnBudget * 0.14f).coerceIn(48.dp, 58.dp)
        adaptiveConsoleHeight = (columnBudget * 0.48f).coerceIn(154.dp, 182.dp)
        adaptiveMediaHeight = (columnBudget * 0.14f).coerceIn(48.dp, 56.dp)
      } else {
        val headerRowHeight = 48.dp
        val showNav = uiPrefs.showNavigationRow
        val showVol = uiPrefs.showVolumeAndChannel
        val showMedia = uiPrefs.showMediaTransport
        val activeSections =
          2 + (if (showNav) 1 else 0) + 1 + (if (showMedia) 1 else 0) // Header + Pad + Nav? + Vol/Voice + Media?
        val baseGapCount = (activeSections - 1).coerceAtLeast(1)

        adaptiveNavHeight =
          if (!showNav) 0.dp
          else (bodyBudget * 0.082f).coerceIn(48.dp, if (compact) 52.dp else 56.dp)
        adaptiveConsoleHeight =
          if (!showVol) 48.dp
          else (bodyBudget * 0.235f).coerceIn(152.dp, if (compact) 158.dp else 172.dp)
        adaptiveMediaHeight =
          if (!showMedia) 0.dp
          else (bodyBudget * 0.078f).coerceIn(48.dp, if (compact) 50.dp else 54.dp)

        val minGap = if (compact) 6.dp else 8.dp
        val maxGap = if (compact) 10.dp else 14.dp
        val fixedRowsHeight = headerRowHeight + adaptiveNavHeight + adaptiveConsoleHeight + adaptiveMediaHeight
        val remainingForPadAndGaps = (bodyBudget - fixedRowsHeight).coerceAtLeast(190.dp)
        val idealGap = ((remainingForPadAndGaps - 210.dp) / baseGapCount).coerceIn(minGap, maxGap)
        adaptiveGap = idealGap

        val maxPodByWidth = (availWidth - 40.dp).coerceAtLeast(172.dp)
        val maxPodByHeight = (remainingForPadAndGaps - idealGap * baseGapCount).coerceAtLeast(172.dp)
        val targetCap = if (compact) 224.dp else 258.dp
        adaptivePodSize = minOf(maxPodByWidth, maxPodByHeight, targetCap).coerceAtLeast(168.dp)
      }

      val horizontalPadding = if (isNarrowWidth) 0.dp else 2.dp

      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
      ) {
        Spacer(Modifier.height(if (availHeight < 660.dp) 6.dp else 10.dp))
        RemoteTopTelemetryBar(
          status = uiState.connectionStatus,
          activeProfileName = activeProfileName,
          onOpenConnectionHub = onOpenConnectionHub,
          onOpenCustomizeUi = onOpenCustomizeUi,
          onOpenSettings = onOpenSettings,
          onPowerClick = { onSendKey(RemoteKeyCode.KEYCODE_POWER, FeedbackCue.POWER_OR_APP) },
        )
        Spacer(Modifier.height(if (availHeight < 660.dp) 4.dp else 8.dp))

        Column(
          modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 2.dp),
          verticalArrangement = Arrangement.spacedBy(adaptiveGap),
        ) {
          val errorStatus = uiState.connectionStatus as? ConnectionStatus.Error
          AnimatedVisibility(visible = errorStatus != null) {
            errorStatus?.let { err ->
              RemoteDiagnosticBanner(
                error = err,
                onRepairClick = onRepairConnection,
                onRetryClick = onRetryConnection,
              )
            }
          }

          uiState.availableUpdate?.let { release ->
            UpdateAvailableBannerCard(
              release = release,
              onDownloadClick = onDownloadUpdate,
              onDetailsClick = onOpenUpdateDetails,
            )
          }

          if (isWideLayout) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(20.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(adaptiveGap),
              ) {
                ModeAndKeyboardHeaderRow(
                  mode = uiState.inputMode,
                  isImeRequested = uiState.tvImeState.isImeRequested,
                  showQuickSearchBar = uiPrefs.showQuickSearchBar,
                  narrowWidth = isNarrowWidth,
                  onSwitch = onSwitchInputMode,
                  onOpenKeyboard = onOpenKeyboard,
                  onOpenSearch = onOpenSearch,
                  onOpenOttApps = onOpenOttApps,
                )
                if (uiState.inputMode == RemoteInputMode.DPAD) {
                  ClassicDPadPod(
                    onKey = onSendKey,
                    onKeyHoldStart = onKeyHoldStart,
                    onKeyHoldEnd = onKeyHoldEnd,
                    compact = compact,
                    targetSize = adaptivePodSize,
                  )
                } else {
                  TouchpadSurfacePod(
                    onKey = onSendKey,
                    compact = compact,
                    targetHeight = adaptivePodSize,
                  )
                }
                AnimatedVisibility(visible = uiPrefs.showNavigationRow) {
                  PrimaryNavigationRow(
                    onKey = onSendKey,
                    onKeyHoldStart = onKeyHoldStart,
                    onKeyHoldEnd = onKeyHoldEnd,
                    compact = compact,
                    targetButtonHeight = adaptiveNavHeight,
                  )
                }
              }

              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(adaptiveGap),
              ) {
                if (uiPrefs.showVolumeAndChannel) {
                  VolumeAndChannelPod(
                    onKey = onSendKey,
                    onKeyHoldStart = onKeyHoldStart,
                    onKeyHoldEnd = onKeyHoldEnd,
                    isListening = uiState.voiceState.isListening,
                    onOpenVoiceInput = onOpenVoiceInput,
                    onTriggerTvAssistant = onTriggerTvVoiceAssistant,
                    compact = compact,
                    targetConsoleHeight = adaptiveConsoleHeight,
                  )
                } else {
                  CompactVoiceQuickStrip(
                    isListening = uiState.voiceState.isListening,
                    onOpenVoiceInput = onOpenVoiceInput,
                    onTriggerTvAssistant = onTriggerTvVoiceAssistant,
                  )
                }
                AnimatedVisibility(visible = uiPrefs.showMediaTransport) {
                  MediaTransportBar(
                    onKey = onSendKey,
                    onKeyHoldStart = onKeyHoldStart,
                    onKeyHoldEnd = onKeyHoldEnd,
                    compact = compact,
                    targetBarHeight = adaptiveMediaHeight,
                  )
                }
                AnimatedVisibility(visible = uiPrefs.showStreamingShortcuts) {
                  StreamingShortcutsGrid(
                    onLaunchShortcut = onLaunchShortcut,
                    shortcuts = filteredShortcuts,
                    columns = uiPrefs.shortcutColumns,
                    compact = compact,
                    verifiedOnTvIds = uiState.verifiedOnTvShortcutIds,
                    onOpenCustomize = onOpenCustomizeUi,
                    onAddOttApp = onOpenAddOttDialog,
                  )
                }
              }
            }
          } else {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(adaptiveGap),
            ) {
              ModeAndKeyboardHeaderRow(
                mode = uiState.inputMode,
                isImeRequested = uiState.tvImeState.isImeRequested,
                showQuickSearchBar = uiPrefs.showQuickSearchBar,
                narrowWidth = isNarrowWidth,
                onSwitch = onSwitchInputMode,
                onOpenKeyboard = onOpenKeyboard,
                onOpenSearch = onOpenSearch,
                onOpenOttApps = onOpenOttApps,
              )

              if (uiState.inputMode == RemoteInputMode.DPAD) {
                ClassicDPadPod(
                  onKey = onSendKey,
                  onKeyHoldStart = onKeyHoldStart,
                  onKeyHoldEnd = onKeyHoldEnd,
                  compact = compact,
                  targetSize = adaptivePodSize,
                )
              } else {
                TouchpadSurfacePod(
                  onKey = onSendKey,
                  compact = compact,
                  targetHeight = adaptivePodSize,
                )
              }

              AnimatedVisibility(visible = uiPrefs.showNavigationRow) {
                PrimaryNavigationRow(
                  onKey = onSendKey,
                  onKeyHoldStart = onKeyHoldStart,
                  onKeyHoldEnd = onKeyHoldEnd,
                  compact = compact,
                  targetButtonHeight = adaptiveNavHeight,
                )
              }

              if (uiPrefs.showVolumeAndChannel) {
                VolumeAndChannelPod(
                  onKey = onSendKey,
                  onKeyHoldStart = onKeyHoldStart,
                  onKeyHoldEnd = onKeyHoldEnd,
                  isListening = uiState.voiceState.isListening,
                  onOpenVoiceInput = onOpenVoiceInput,
                  onTriggerTvAssistant = onTriggerTvVoiceAssistant,
                  compact = compact,
                  targetConsoleHeight = adaptiveConsoleHeight,
                )
              } else {
                CompactVoiceQuickStrip(
                  isListening = uiState.voiceState.isListening,
                  onOpenVoiceInput = onOpenVoiceInput,
                  onTriggerTvAssistant = onTriggerTvVoiceAssistant,
                )
              }

              AnimatedVisibility(visible = uiPrefs.showMediaTransport) {
                MediaTransportBar(
                  onKey = onSendKey,
                  onKeyHoldStart = onKeyHoldStart,
                  onKeyHoldEnd = onKeyHoldEnd,
                  compact = compact,
                  targetBarHeight = adaptiveMediaHeight,
                )
              }

              AnimatedVisibility(visible = uiPrefs.showStreamingShortcuts) {
                StreamingShortcutsGrid(
                  onLaunchShortcut = onLaunchShortcut,
                  shortcuts = filteredShortcuts,
                  columns = uiPrefs.shortcutColumns,
                  compact = compact,
                  verifiedOnTvIds = uiState.verifiedOnTvShortcutIds,
                  onOpenCustomize = onOpenCustomizeUi,
                  onAddOttApp = onOpenAddOttDialog,
                )
              }
            }
          }

          Spacer(Modifier.height(6.dp))
        }
      }
    }
  }
}

@Composable
private fun RemoteDiagnosticBanner(
  error: ConnectionStatus.Error,
  onRepairClick: () -> Unit,
  onRetryClick: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
    shape = RoundedCornerShape(18.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    modifier = Modifier.fillMaxWidth().testTag("remote_diagnostic_banner"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.WarningAmber,
          contentDescription = "Connection diagnostic alert",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              if (error.isRapidDropLoop) "Auto-Reconnect Paused"
              else "TV Connection Interrupted",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          Text(
            text = error.diagnosticDetail ?: error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
          )
        }
      }

      if (error.targetIp != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = onRepairClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("remote_diagnostic_repair_button"),
          ) {
            Text("Re-pair TV", maxLines = 1, softWrap = false)
          }
          OutlinedButton(
            onClick = onRetryClick,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("remote_diagnostic_retry_button"),
          ) {
            Text(
              text = "Retry",
              color = MaterialTheme.colorScheme.onErrorContainer,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TelemetryStatusDot(
  isConnected: Boolean,
  dotColor: androidx.compose.ui.graphics.Color,
) {
  if (isConnected) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by
      infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha",
      )
    Box(
      modifier =
        Modifier
          .size(10.dp)
          .alpha(pulseAlpha)
          .clip(CircleShape)
          .background(dotColor),
    )
  } else {
    Box(
      modifier =
        Modifier
          .size(10.dp)
          .clip(CircleShape)
          .background(dotColor),
    )
  }
}

@Composable
private fun RemoteTopTelemetryBar(
  status: ConnectionStatus,
  activeProfileName: String,
  onOpenConnectionHub: () -> Unit,
  onOpenCustomizeUi: () -> Unit,
  onOpenSettings: () -> Unit,
  onPowerClick: () -> Unit,
) {
  val (title, subtitle, dotColor) =
    when (status) {
      is ConnectionStatus.Connected ->
        Triple(status.displayName, "${status.ipAddress}:${status.port} • $activeProfileName", MaterialTheme.colorScheme.tertiary)
      is ConnectionStatus.Connecting ->
        Triple(
          if (status.isSilentAutoHeal) "Reconnecting..." else "Connecting...",
          "${status.targetName} (${status.ipAddress})",
          AmberStatus,
        )
      is ConnectionStatus.Pairing ->
        Triple("Pairing...", status.serverName, AmberStatus)
      is ConnectionStatus.Error ->
        Triple("Connection Lost", status.message, MaterialTheme.colorScheme.error)
      ConnectionStatus.Disconnected ->
        Triple("No TV Connected", "Tap to select or pair a TV • $activeProfileName", MaterialTheme.colorScheme.onSurfaceVariant)
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier =
        Modifier
          .weight(1f)
          .clip(RoundedCornerShape(16.dp))
          .clickable(onClick = onOpenConnectionHub)
          .testTag("remote_tv_selector_chip"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        TelemetryStatusDot(
          isConnected = status is ConnectionStatus.Connected,
          dotColor = dotColor,
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Icon(
          imageVector =
            if (status is ConnectionStatus.Connected) Icons.Rounded.SwapHoriz
            else Icons.Rounded.CastConnected,
          contentDescription = "Switch TV",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    IconButton(
      onClick = onOpenCustomizeUi,
      modifier =
        Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface)
          .testTag("remote_customize_ui_button"),
    ) {
      Icon(
        imageVector = Icons.Rounded.Tune,
        contentDescription = "Customize UI",
        tint = MaterialTheme.colorScheme.primary,
      )
    }

    IconButton(
      onClick = onOpenSettings,
      modifier =
        Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface)
          .testTag("remote_settings_button"),
    ) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "Settings",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }

    TactileKeyButton(
      onClick = onPowerClick,
      contentDescription = "Power",
      shape = CircleShape,
      containerColor = MaterialTheme.colorScheme.errorContainer,
      borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
      modifier = Modifier.size(48.dp).testTag("btn_power"),
    ) {
      Icon(
        imageVector = Icons.Rounded.PowerSettingsNew,
        contentDescription = "TV Power",
        tint = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun ModeAndKeyboardHeaderRow(
  mode: RemoteInputMode,
  isImeRequested: Boolean,
  showQuickSearchBar: Boolean,
  narrowWidth: Boolean = false,
  onSwitch: (RemoteInputMode) -> Unit,
  onOpenKeyboard: () -> Unit,
  onOpenSearch: () -> Unit,
  onOpenOttApps: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    InputModeSwitcher(
      mode = mode,
      compactPills = narrowWidth,
      onSwitch = onSwitch,
    )

    Row(
      horizontalArrangement = Arrangement.spacedBy(if (narrowWidth) 6.dp else 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (showQuickSearchBar) {
        TactileKeyButton(
          onClick = onOpenSearch,
          contentDescription = "TV Search",
          shape = CircleShape,
          containerColor = MaterialTheme.colorScheme.surface,
          borderColor = MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.size(48.dp).testTag("btn_tv_search"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "Search on TV",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
          )
        }

        TactileKeyButton(
          onClick = onOpenKeyboard,
          contentDescription = "Mobile Keyboard",
          shape = CircleShape,
          containerColor =
            if (isImeRequested) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
          borderColor =
            if (isImeRequested) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.size(48.dp).testTag("btn_open_keyboard"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Keyboard,
            contentDescription = "Open Mobile Keyboard",
            tint =
              if (isImeRequested) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      // On-demand OTT Apps Drawer Trigger (OTT apps shown only when requested)
      TactileKeyButton(
        onClick = onOpenOttApps,
        contentDescription = "OTT Apps",
        shape = RoundedCornerShape(999.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
        modifier = Modifier.height(48.dp).testTag("btn_open_ott_apps"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = if (narrowWidth) 10.dp else 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = "Open OTT Apps",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          if (!narrowWidth) {
            Text(
              text = "Apps",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CompactVoiceQuickStrip(
  isListening: Boolean,
  onOpenVoiceInput: () -> Unit,
  onTriggerTvAssistant: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TactileKeyButton(
      onClick = onOpenVoiceInput,
      contentDescription = "Voice Command",
      shape = RoundedCornerShape(16.dp),
      containerColor =
        if (isListening) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
      borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
      modifier = Modifier.weight(1f).height(48.dp).testTag("btn_voice_mic"),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = "Voice Command Microphone",
          tint =
            if (isListening) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(19.dp),
        )
        Text(
          text = if (isListening) "Listening…" else "Voice Command",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color =
            if (isListening) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
        )
      }
    }

    TactileKeyButton(
      onClick = onTriggerTvAssistant,
      contentDescription = "TV Assistant",
      shape = RoundedCornerShape(16.dp),
      containerColor = MaterialTheme.colorScheme.surface,
      borderColor = MaterialTheme.colorScheme.outlineVariant,
      modifier = Modifier.height(48.dp).testTag("btn_tv_voice_assist"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Tv,
          contentDescription = "TV Assistant",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = "Assistant",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OttAppsDrawerSheet(
  shortcuts: List<TvAppShortcut>,
  columns: Int,
  compact: Boolean,
  verifiedOnTvIds: Set<String>,
  onLaunchShortcut: (TvAppShortcut) -> Unit,
  onAddOttApp: () -> Unit,
  onOpenCustomize: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp)
          .padding(bottom = 28.dp)
          .testTag("ott_apps_sheet"),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Column {
            Text(
              text = "OTT Apps",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "Tap any app to launch directly on your TV",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        IconButton(
          onClick = onOpenCustomize,
          modifier = Modifier.size(48.dp).testTag("ott_apps_customize_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = "Customize OTT Apps",
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("ott_apps_close_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Close OTT Apps",
          )
        }
      }

      StreamingShortcutsGrid(
        onLaunchShortcut = onLaunchShortcut,
        shortcuts = shortcuts,
        columns = columns,
        compact = compact,
        verifiedOnTvIds = verifiedOnTvIds,
        onOpenCustomize = onOpenCustomize,
        onAddOttApp = onAddOttApp,
      )
    }
  }
}

@Composable
private fun InputModeSwitcher(
  mode: RemoteInputMode,
  compactPills: Boolean = false,
  onSwitch: (RemoteInputMode) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(999.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      ModePillOption(
        selected = mode == RemoteInputMode.DPAD,
        icon = null,
        label = "D-Pad",
        compact = compactPills,
        testTag = "mode_dpad_button",
        onClick = { onSwitch(RemoteInputMode.DPAD) },
      )
      ModePillOption(
        selected = mode == RemoteInputMode.TOUCHPAD,
        icon = Icons.Rounded.TouchApp,
        label = "Touchpad",
        compact = compactPills,
        testTag = "mode_touchpad_button",
        onClick = { onSwitch(RemoteInputMode.TOUCHPAD) },
      )
    }
  }
}

@Composable
private fun ModePillOption(
  selected: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector?,
  label: String,
  compact: Boolean = false,
  testTag: String,
  onClick: () -> Unit,
) {
  Surface(
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer
      else androidx.compose.ui.graphics.Color.Transparent,
    shape = RoundedCornerShape(999.dp),
    modifier =
      Modifier
        .clip(RoundedCornerShape(999.dp))
        .clickable(onClick = onClick)
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
    ) {
      val iconTint =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(16.dp),
        )
      } else {
        DPadModeGlyph(
          tint = iconTint,
          modifier = Modifier.size(16.dp),
        )
      }
      Text(
        text = label,
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color =
          if (selected) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

