package com.example.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.UserProfile
import com.example.remote.discovery.DiscoveredTv
import com.example.remote.feedback.FeedbackCue
import com.example.ui.theme.AmberStatus
import com.example.ui.theme.EmeraldLive

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
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        requiredPermissions.any {
          ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
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
            onRequestScanPermission = { permissionLauncher.launch(requiredPermissions) },
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
          )
        } else {
          RemoteControlScreen(
            uiState = uiState,
            onOpenConnectionHub = { viewModel.openConnectionHub() },
            onOpenSettings = { viewModel.openSettingsSheet(true) },
            onOpenKeyboard = { viewModel.openKeyboardSheet(true) },
            onSwitchInputMode = { viewModel.setInputMode(it) },
            onSendKey = { keyCode, cue -> viewModel.sendKey(keyCode, cue) },
            onLaunchShortcut = { viewModel.launchShortcut(it) },
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
      onToggleHaptics = { viewModel.updateHapticsEnabled(it) },
      onToggleSound = { viewModel.updateSoundEnabled(it) },
      onChangeSoundVolume = { viewModel.updateSoundVolume(it) },
      onTestFeedback = { viewModel.previewFeedback() },
      onToggleAutoReconnect = { viewModel.updateAutoReconnect(it) },
      onSelectProfile = { viewModel.selectProfile(it) },
      onCreateProfileClick = { showCreateProfileDialog = true },
      onRenameProfileClick = { profileToRename = it },
      onDeleteProfile = { viewModel.deleteProfile(it.id) },
      onEditSavedTv = { savedTvToEdit = it },
      onDeleteSavedTv = { savedTvToDelete = it },
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
    )
  }
}

@Composable
fun RemoteControlScreen(
  uiState: RemoteUiState,
  onOpenConnectionHub: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenKeyboard: () -> Unit,
  onSwitchInputMode: (RemoteInputMode) -> Unit,
  onSendKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  onLaunchShortcut: (com.example.remote.data.TvAppShortcut) -> Unit,
) {
  val activeProfileName =
    uiState.profiles.firstOrNull { it.id == uiState.activeProfileId }?.name ?: "Profile"

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    BoxWithConstraints(
      modifier = Modifier.widthIn(max = 920.dp).fillMaxSize().padding(horizontal = 18.dp),
    ) {
      val isWideLayout = maxWidth >= 680.dp

      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        RemoteTopTelemetryBar(
          status = uiState.connectionStatus,
          activeProfileName = activeProfileName,
          onOpenConnectionHub = onOpenConnectionHub,
          onOpenSettings = onOpenSettings,
          onPowerClick = { onSendKey(RemoteKeyCode.KEYCODE_POWER, FeedbackCue.POWER_OR_APP) },
        )

        if (isWideLayout) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Column(
              modifier = Modifier.weight(1f),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              ModeAndKeyboardHeaderRow(
                mode = uiState.inputMode,
                isImeRequested = uiState.tvImeState.isImeRequested,
                onSwitch = onSwitchInputMode,
                onOpenKeyboard = onOpenKeyboard,
                onOpenSearch = { onSendKey(RemoteKeyCode.KEYCODE_SEARCH, FeedbackCue.CONFIRM_CHIME) },
              )
              if (uiState.inputMode == RemoteInputMode.DPAD) {
                ClassicDPadPod(onKey = onSendKey)
              } else {
                TouchpadSurfacePod(onKey = onSendKey)
              }
              PrimaryNavigationRow(onKey = onSendKey)
            }

            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              VolumeAndChannelPod(onKey = onSendKey)
              MediaTransportBar(onKey = onSendKey)
              StreamingShortcutsGrid(onLaunchShortcut = onLaunchShortcut)
            }
          }
        } else {
          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            ModeAndKeyboardHeaderRow(
              mode = uiState.inputMode,
              isImeRequested = uiState.tvImeState.isImeRequested,
              onSwitch = onSwitchInputMode,
              onOpenKeyboard = onOpenKeyboard,
              onOpenSearch = { onSendKey(RemoteKeyCode.KEYCODE_SEARCH, FeedbackCue.CONFIRM_CHIME) },
            )

            if (uiState.inputMode == RemoteInputMode.DPAD) {
              ClassicDPadPod(onKey = onSendKey)
            } else {
              TouchpadSurfacePod(onKey = onSendKey)
            }

            PrimaryNavigationRow(onKey = onSendKey)
            VolumeAndChannelPod(onKey = onSendKey)
            MediaTransportBar(onKey = onSendKey)
            StreamingShortcutsGrid(onLaunchShortcut = onLaunchShortcut)
          }
        }

        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun RemoteTopTelemetryBar(
  status: ConnectionStatus,
  activeProfileName: String,
  onOpenConnectionHub: () -> Unit,
  onOpenSettings: () -> Unit,
  onPowerClick: () -> Unit,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by
    infiniteTransition.animateFloat(
      initialValue = 0.45f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
      label = "pulse_alpha",
    )

  val (title, subtitle, dotColor) =
    when (status) {
      is ConnectionStatus.Connected ->
        Triple(status.displayName, "${status.ipAddress}:${status.port} • $activeProfileName", EmeraldLive)
      is ConnectionStatus.Connecting ->
        Triple("Connecting...", "${status.targetName} (${status.ipAddress})", AmberStatus)
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
        Box(
          modifier =
            Modifier
              .size(10.dp)
              .alpha(if (status is ConnectionStatus.Connected) pulseAlpha else 1f)
              .clip(CircleShape)
              .background(dotColor),
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
  onSwitch: (RemoteInputMode) -> Unit,
  onOpenKeyboard: () -> Unit,
  onOpenSearch: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    InputModeSwitcher(
      mode = mode,
      onSwitch = onSwitch,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
  }
}

@Composable
private fun InputModeSwitcher(
  mode: RemoteInputMode,
  onSwitch: (RemoteInputMode) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(999.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      ModePillOption(
        selected = mode == RemoteInputMode.DPAD,
        icon = null,
        label = "D-Pad",
        testTag = "mode_dpad_button",
        onClick = { onSwitch(RemoteInputMode.DPAD) },
      )
      ModePillOption(
        selected = mode == RemoteInputMode.TOUCHPAD,
        icon = Icons.Rounded.TouchApp,
        label = "Touchpad",
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
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
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
        style = MaterialTheme.typography.labelLarge,
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

