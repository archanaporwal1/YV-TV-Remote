package com.example.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.UserProfile
import com.example.remote.discovery.DiscoveredTv
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.JetBrainsMonoFamily
import kotlin.math.roundToInt

@Composable
fun ConnectionHubView(
  uiState: RemoteUiState,
  hasWifiScanPermission: Boolean,
  onRequestScanPermission: () -> Unit,
  onStartScan: () -> Unit,
  onStopScan: () -> Unit,
  onOpenManualIp: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenRemote: () -> Unit,
  onSelectProfile: (String) -> Unit,
  onCreateProfileClick: () -> Unit,
  onConnectSavedTv: (SavedTvConnection) -> Unit,
  onEditSavedTv: (SavedTvConnection) -> Unit,
  onDeleteSavedTv: (SavedTvConnection) -> Unit,
  onConnectDiscoveredTv: (DiscoveredTv) -> Unit,
  onSaveDiscoveredTv: (DiscoveredTv) -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeProfileTvs =
    remember(uiState.savedConnections, uiState.activeProfileId) {
      uiState.savedConnections.filter { it.profileId == uiState.activeProfileId }
    }
  val otherProfileTvs =
    remember(uiState.savedConnections, uiState.activeProfileId) {
      uiState.savedConnections.filter { it.profileId != uiState.activeProfileId }
    }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(14.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
              modifier = Modifier.size(46.dp),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                Image(
                  painter = painterResource(id = R.drawable.ic_yv_brand_logo),
                  contentDescription = "YV-TV Remote Logo",
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
            Column {
              Text(
                text = "YV-TV Remote",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "Android TV & Google TV Controller",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
              color = MaterialTheme.colorScheme.surface,
              shape = CircleShape,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              IconButton(
                onClick = onOpenRemote,
                modifier = Modifier.size(48.dp).testTag("open_remote_surface_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.SettingsRemote,
                  contentDescription = "Open Remote Control",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
            Surface(
              color = MaterialTheme.colorScheme.surface,
              shape = CircleShape,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).testTag("hub_settings_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Settings,
                  contentDescription = "Open Settings",
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }

      // Live Connection Status Banner
      item {
        ConnectionStatusHeroCard(
          status = uiState.connectionStatus,
          onOpenRemote = onOpenRemote,
          onRetryConnection = { errorState ->
            errorState.targetIp?.let { ip ->
              onConnectDiscoveredTv(
                DiscoveredTv(
                  serviceName = errorState.targetName ?: ip,
                  displayName = errorState.targetName ?: ip,
                  ipAddress = ip,
                  port = errorState.targetPort,
                )
              )
            }
          },
        )
      }

      // User Profiles Bar
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "USER PROFILES",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
              onClick = onCreateProfileClick,
              modifier = Modifier.testTag("add_profile_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text("New Profile", maxLines = 1, softWrap = false)
            }
          }

          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.profiles, key = { it.id }) { profile ->
              val selected = profile.id == uiState.activeProfileId
              FilterChip(
                selected = selected,
                onClick = { onSelectProfile(profile.id) },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                },
                label = { Text(profile.name) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                  ),
                border =
                  FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                  ),
                modifier = Modifier.testTag("profile_chip_${profile.id}"),
              )
            }
          }
        }
      }

      // Saved Connections Section
      item {
        Text(
          text = "SAVED TV CONNECTIONS",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (activeProfileTvs.isEmpty() && otherProfileTvs.isEmpty()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = "No saved TVs yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Scan your Wi-Fi network below or enter an IP address to save and pair your TV.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(activeProfileTvs, key = { "saved_${it.id}" }) { saved ->
          SavedTvCard(
            savedTv = saved,
            profileName = uiState.profiles.firstOrNull { it.id == saved.profileId }?.name,
            isActiveConnection =
              (uiState.connectionStatus as? ConnectionStatus.Connected)?.ipAddress == saved.ipAddress,
            onConnect = { onConnectSavedTv(saved) },
            onEdit = { onEditSavedTv(saved) },
            onDelete = { onDeleteSavedTv(saved) },
          )
        }
        if (otherProfileTvs.isNotEmpty()) {
          item {
            Text(
              text = "OTHER PROFILES",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          items(otherProfileTvs, key = { "other_${it.id}" }) { saved ->
            SavedTvCard(
              savedTv = saved,
              profileName = uiState.profiles.firstOrNull { it.id == saved.profileId }?.name,
              isActiveConnection =
                (uiState.connectionStatus as? ConnectionStatus.Connected)?.ipAddress == saved.ipAddress,
              onConnect = { onConnectSavedTv(saved) },
              onEdit = { onEditSavedTv(saved) },
              onDelete = { onDeleteSavedTv(saved) },
            )
          }
        }
      }

      // Discovered Devices Section Header + Clean Full-Width Action Buttons
      item {
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "DISCOVERED ON WI-FI",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.isScanning) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                MiniRadarIndicator(modifier = Modifier.size(16.dp))
                Text(
                  text = "Scanning...",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = onOpenManualIp,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
              modifier = Modifier.weight(1f).height(48.dp).testTag("manual_ip_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Link,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = "Manual IP",
                maxLines = 1,
                softWrap = false,
              )
            }

            FilledTonalButton(
              onClick = {
                if (!hasWifiScanPermission) {
                  onRequestScanPermission()
                } else if (uiState.isScanning) {
                  onStopScan()
                } else {
                  onStartScan()
                }
              },
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
              modifier = Modifier.weight(1f).height(48.dp).testTag("scan_tvs_button"),
            ) {
              Icon(
                imageVector =
                  if (uiState.isScanning) Icons.Rounded.Stop else Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = if (uiState.isScanning) "Stop Scan" else "Scan Wi-Fi",
                maxLines = 1,
                softWrap = false,
              )
            }
          }
        }
      }

      if (!hasWifiScanPermission) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Wi-Fi Discovery Permission",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = "Grant Nearby Wi-Fi Devices permission to find TVs automatically, or use Manual IP.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(Modifier.width(12.dp))
              Button(
                onClick = onRequestScanPermission,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.testTag("grant_wifi_permission_button"),
              ) {
                Text("Allow", maxLines = 1, softWrap = false)
              }
            }
          }
        }
      }

      if (!uiState.scanError.isNullOrBlank()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = uiState.scanError,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(14.dp),
            )
          }
        }
      }

      if (uiState.discoveredTvs.isEmpty()) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border =
              BorderStroke(
                width = 1.dp,
                color =
                  if (uiState.isScanning) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                  else MaterialTheme.colorScheme.outlineVariant,
              ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              RadarScannerScope(
                isScanning = uiState.isScanning,
                modifier = Modifier.size(104.dp).testTag("discovery_radar_animation"),
              )
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text =
                    if (uiState.isScanning) "Scanning local Wi-Fi..."
                    else "Wi-Fi Discovery Idle",
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  textAlign = TextAlign.Center,
                )
                Text(
                  text =
                    if (uiState.isScanning) "Listening for _androidtvremote2._tcp devices"
                    else "Tap Scan Wi-Fi to discover Android TVs on your network",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
      } else {
        if (uiState.isScanning) {
          item {
            ActiveRadarStrip(modifier = Modifier.testTag("discovery_radar_strip"))
          }
        }
        items(uiState.discoveredTvs, key = { "disc_${it.ipAddress}_${it.serviceName}" }) { tv ->
          DiscoveredTvCard(
            tv = tv,
            onConnect = { onConnectDiscoveredTv(tv) },
            onSave = { onSaveDiscoveredTv(tv) },
          )
        }
      }

      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun RadarScannerScope(
  isScanning: Boolean,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val gridRingColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
  val crosshairColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
  val coreColor = MaterialTheme.colorScheme.primaryContainer

  val transition = rememberInfiniteTransition(label = "wifi_radar_scope")
  val sweepAngle by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2400, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "radar_sweep_angle",
    )
  val pulseOne by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "radar_pulse_one",
    )
  val pulseTwo by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Restart,
          initialStartOffset = StartOffset(1100),
        ),
      label = "radar_pulse_two",
    )

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val maxRadius = size.minDimension * 0.48f
      val centerOffset = Offset(size.width / 2f, size.height / 2f)

      // Subtle concentric radar range rings
      listOf(0.36f, 0.68f, 1f).forEach { fraction ->
        drawCircle(
          color = if (isScanning) primaryColor.copy(alpha = 0.16f) else gridRingColor,
          radius = maxRadius * fraction,
          center = centerOffset,
          style = Stroke(width = 1.dp.toPx()),
        )
      }

      // Faint cardinal crosshairs
      drawLine(
        color = crosshairColor,
        start = Offset(centerOffset.x - maxRadius, centerOffset.y),
        end = Offset(centerOffset.x + maxRadius, centerOffset.y),
        strokeWidth = 0.8.dp.toPx(),
      )
      drawLine(
        color = crosshairColor,
        start = Offset(centerOffset.x, centerOffset.y - maxRadius),
        end = Offset(centerOffset.x, centerOffset.y + maxRadius),
        strokeWidth = 0.8.dp.toPx(),
      )

      if (isScanning) {
        // Rotating conical radar sweep sector
        rotate(degrees = sweepAngle, pivot = centerOffset) {
          drawCircle(
            brush =
              Brush.sweepGradient(
                0.0f to Color.Transparent,
                0.72f to Color.Transparent,
                0.94f to primaryColor.copy(alpha = 0.14f),
                1.0f to primaryColor.copy(alpha = 0.30f),
                center = centerOffset,
              ),
            radius = maxRadius,
            center = centerOffset,
          )
          drawLine(
            color = primaryColor.copy(alpha = 0.55f),
            start = centerOffset,
            end = Offset(centerOffset.x + maxRadius, centerOffset.y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }

        // Staggered sonar ripple rings expanding outward
        listOf(pulseOne, pulseTwo).forEach { progress ->
          val rippleRadius = maxRadius * (0.24f + 0.76f * progress)
          val rippleAlpha = (1f - progress) * 0.42f
          drawCircle(
            color = primaryColor.copy(alpha = rippleAlpha),
            radius = rippleRadius,
            center = centerOffset,
            style = Stroke(width = 1.5.dp.toPx()),
          )
        }
      }

      // Center hub backing
      drawCircle(
        color = coreColor,
        radius = maxRadius * 0.36f,
        center = centerOffset,
      )
      drawCircle(
        color = primaryColor.copy(alpha = if (isScanning) 0.45f else 0.25f),
        radius = maxRadius * 0.36f,
        center = centerOffset,
        style = Stroke(width = 1.dp.toPx()),
      )
    }

    Icon(
      imageVector = Icons.Rounded.WifiFind,
      contentDescription = null,
      tint = primaryColor,
      modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun MiniRadarIndicator(modifier: Modifier = Modifier) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val transition = rememberInfiniteTransition(label = "mini_radar")
  val angle by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1600, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "mini_radar_angle",
    )

  Canvas(modifier = modifier) {
    val radius = size.minDimension * 0.46f
    val centerOffset = Offset(size.width / 2f, size.height / 2f)

    drawCircle(
      color = primaryColor.copy(alpha = 0.30f),
      radius = radius,
      center = centerOffset,
      style = Stroke(width = 1.2.dp.toPx()),
    )
    rotate(degrees = angle, pivot = centerOffset) {
      drawCircle(
        brush =
          Brush.sweepGradient(
            0.0f to Color.Transparent,
            0.60f to Color.Transparent,
            1.0f to primaryColor.copy(alpha = 0.45f),
            center = centerOffset,
          ),
        radius = radius,
        center = centerOffset,
      )
      drawLine(
        color = primaryColor,
        start = centerOffset,
        end = Offset(centerOffset.x + radius, centerOffset.y),
        strokeWidth = 1.3.dp.toPx(),
        cap = StrokeCap.Round,
      )
    }
    drawCircle(
      color = primaryColor,
      radius = 1.8.dp.toPx(),
      center = centerOffset,
    )
  }
}

@Composable
private fun ActiveRadarStrip(modifier: Modifier = Modifier) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      MiniRadarIndicator(modifier = Modifier.size(26.dp))
      Text(
        text = "Scanning Wi-Fi for additional Android TVs...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ConnectionStatusHeroCard(
  status: ConnectionStatus,
  onOpenRemote: () -> Unit,
  onRetryConnection: (ConnectionStatus.Error) -> Unit,
) {
  val gradient =
    Brush.linearGradient(
      colors =
        listOf(
          MaterialTheme.colorScheme.surfaceVariant,
          MaterialTheme.colorScheme.surface,
        )
    )
  Surface(
    shape = RoundedCornerShape(20.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          when (status) {
            is ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            is ConnectionStatus.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.outline
          },
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(modifier = Modifier.background(gradient).padding(18.dp)) {
      when (status) {
        is ConnectionStatus.Connected -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f),
            ) {
              Box(
                modifier =
                  Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Rounded.CastConnected,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
              Column {
                Text(
                  text = status.displayName,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "TLS Connected • ${status.ipAddress}:${status.port}",
                  style = MaterialTheme.typography.labelSmall,
                  color = EmeraldLive,
                )
              }
            }
            Button(
              onClick = onOpenRemote,
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
              modifier = Modifier.testTag("hero_open_remote_button"),
            ) {
              Text("Remote", maxLines = 1, softWrap = false)
            }
          }
        }

        is ConnectionStatus.Connecting -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(28.dp),
              strokeWidth = 2.5.dp,
              color = MaterialTheme.colorScheme.primary,
            )
            Column {
              Text(
                text = "Connecting to ${status.targetName}...",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = "${status.ipAddress}:${status.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        is ConnectionStatus.Error -> {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              text = "Connection Issue",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              text = status.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.targetIp != null) {
              OutlinedButton(
                onClick = { onRetryConnection(status) },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.testTag("retry_connection_button"),
              ) {
                Text(
                  text = "Retry ${status.targetName ?: status.targetIp}",
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        else -> {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            Column {
              Text(
                text = "Ready to Connect",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = "Select a saved TV for quick reconnect or scan your local Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SavedTvCard(
  savedTv: SavedTvConnection,
  profileName: String?,
  isActiveConnection: Boolean,
  onConnect: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(16.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (isActiveConnection) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onConnect)
        .testTag("saved_tv_card_${savedTv.id}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
              if (isActiveConnection) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Tv,
          contentDescription = null,
          tint =
            if (isActiveConnection) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = savedTv.customName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${savedTv.ipAddress}:${savedTv.port}" +
            (if (!profileName.isNullOrBlank()) " • $profileName" else ""),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onEdit,
        modifier = Modifier.size(48.dp).testTag("edit_saved_tv_${savedTv.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Edit,
          contentDescription = "Edit ${savedTv.customName}",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        onClick = onDelete,
        modifier = Modifier.size(48.dp).testTag("delete_saved_tv_${savedTv.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = "Delete ${savedTv.customName}",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun DiscoveredTvCard(
  tv: DiscoveredTv,
  onConnect: () -> Unit,
  onSave: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onConnect)
        .testTag("discovered_tv_card_${tv.ipAddress}"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Tv,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = tv.displayName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${tv.ipAddress}:${tv.port}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        onClick = onSave,
        modifier = Modifier.size(48.dp).testTag("save_discovered_tv_${tv.ipAddress}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.BookmarkAdd,
          contentDescription = "Name and save ${tv.displayName}",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(Modifier.width(4.dp))
      Button(
        onClick = onConnect,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
          ),
        modifier = Modifier.testTag("connect_discovered_tv_${tv.ipAddress}"),
      ) {
        Text(
          text = "Connect",
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModalSheet(
  uiState: RemoteUiState,
  onDismiss: () -> Unit,
  onToggleHaptics: (Boolean) -> Unit,
  onToggleSound: (Boolean) -> Unit,
  onChangeSoundVolume: (Float) -> Unit,
  onTestFeedback: () -> Unit,
  onToggleAutoReconnect: (Boolean) -> Unit,
  onSelectProfile: (String) -> Unit,
  onCreateProfileClick: () -> Unit,
  onRenameProfileClick: (UserProfile) -> Unit,
  onDeleteProfile: (UserProfile) -> Unit,
  onEditSavedTv: (SavedTvConnection) -> Unit,
  onDeleteSavedTv: (SavedTvConnection) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      item {
        Text(
          text = "Remote Settings",
          style = MaterialTheme.typography.headlineSmall,
        )
      }

      // Haptics & Sound Effects Section
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Text(
              text = "TACTILE & AUDIO FEEDBACK",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Vibration,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                  Text("Haptic Feedback", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "Subtle vibration on remote key & touchpad input",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = uiState.settings.hapticsEnabled,
                onCheckedChange = onToggleHaptics,
                colors =
                  SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                  ),
                modifier = Modifier.testTag("settings_haptics_switch"),
              )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
              ) {
                Icon(
                  imageVector = Icons.Rounded.GraphicEq,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                  Text("Sound Effects", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "Short distinct audio cues per button type",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Switch(
                checked = uiState.settings.soundEnabled,
                onCheckedChange = onToggleSound,
                colors =
                  SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                  ),
                modifier = Modifier.testTag("settings_sound_switch"),
              )
            }

            AnimatedVisibility(visible = uiState.settings.soundEnabled) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.VolumeUp,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(18.dp),
                    )
                    Text("Effect Volume", style = MaterialTheme.typography.bodyMedium)
                  }
                  Text(
                    text = "${(uiState.settings.soundVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                Slider(
                  value = uiState.settings.soundVolume,
                  onValueChange = onChangeSoundVolume,
                  colors =
                    SliderDefaults.colors(
                      thumbColor = MaterialTheme.colorScheme.primary,
                      activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                  modifier = Modifier.testTag("settings_sound_volume_slider"),
                )
              }
            }

            OutlinedButton(
              onClick = onTestFeedback,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
              modifier = Modifier.fillMaxWidth().testTag("settings_test_feedback_button"),
            ) {
              Text("Test Vibration & Sound")
            }
          }
        }
      }

      // Connection Preferences
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Auto-Reconnect on Launch", style = MaterialTheme.typography.titleSmall)
              Text(
                "Automatically reconnect to the last paired TV",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = uiState.settings.autoReconnectEnabled,
              onCheckedChange = onToggleAutoReconnect,
              colors =
                SwitchDefaults.colors(
                  checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                  checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
              modifier = Modifier.testTag("settings_autoreconnect_switch"),
            )
          }
        }
      }

      // User Profiles Management
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "USER PROFILES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          TextButton(
            onClick = onCreateProfileClick,
            modifier = Modifier.testTag("settings_add_profile_button"),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Profile")
          }
        }
      }

      items(uiState.profiles, key = { "settings_prof_${it.id}" }) { profile ->
        val isSelected = profile.id == uiState.activeProfileId
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(14.dp),
          border =
            BorderStroke(
              1.dp,
              if (isSelected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outlineVariant,
            ),
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .clickable { onSelectProfile(profile.id) },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Person,
              contentDescription = null,
              tint =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              )
              if (isSelected) {
                Text(
                  text = "Active Profile",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
            IconButton(
              onClick = { onRenameProfileClick(profile) },
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Rename ${profile.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (uiState.profiles.size > 1) {
              IconButton(
                onClick = { onDeleteProfile(profile) },
                modifier = Modifier.size(48.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteOutline,
                  contentDescription = "Delete ${profile.name}",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }

      // Saved Connections Quick Management
      if (uiState.savedConnections.isNotEmpty()) {
        item {
          Text(
            text = "SAVED TV CONNECTIONS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
        items(uiState.savedConnections, key = { "settings_tv_${it.id}" }) { tv ->
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(tv.customName, style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "${tv.ipAddress}:${tv.port}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(
                onClick = { onEditSavedTv(tv) },
                modifier = Modifier.size(48.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Edit,
                  contentDescription = "Edit ${tv.customName}",
                )
              }
              IconButton(
                onClick = { onDeleteSavedTv(tv) },
                modifier = Modifier.size(48.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteOutline,
                  contentDescription = "Delete ${tv.customName}",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }
    }
  }
}
