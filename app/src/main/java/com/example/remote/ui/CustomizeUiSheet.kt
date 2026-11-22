package com.example.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.remote.data.PreferredPadMode
import com.example.remote.data.RemoteUiPreferences
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.UiLayoutPreset
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.RemoteAccentTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomizeUiSheet(
  uiPrefs: RemoteUiPreferences,
  onDismiss: () -> Unit,
  onApplyPreset: (UiLayoutPreset) -> Unit,
  onSelectThemeMode: (AppThemeMode) -> Unit,
  onSelectAccentTheme: (RemoteAccentTheme) -> Unit,
  onSelectDefaultPad: (PreferredPadMode) -> Unit,
  onUpdatePreferences: ((RemoteUiPreferences) -> RemoteUiPreferences) -> Unit,
  onToggleShortcut: (String) -> Unit,
  onSelectAllShortcuts: () -> Unit,
  onOpenAddOttDialog: () -> Unit,
  onEditCustomShortcut: (TvAppShortcut) -> Unit,
  onDeleteCustomShortcut: (String) -> Unit,
  onResetDefaults: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val activePreset = uiPrefs.matchingPresetOrNull()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("customize_ui_list"),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      // Header
      item {
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
              imageVector = Icons.Rounded.Tune,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Column {
              Text(
                text = "Customize Remote UI",
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                text = "Tailor layout, controls, colors & streaming apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).testTag("customize_ui_close_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Close Customize UI",
            )
          }
        }
      }

      // Quick Presets
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = "QUICK LAYOUT PRESETS",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              UiLayoutPreset.entries.forEach { preset ->
                val selected = activePreset == preset
                FilterChip(
                  selected = selected,
                  onClick = { onApplyPreset(preset) },
                  label = {
                    Text(
                      text = preset.label,
                      maxLines = 1,
                      softWrap = false,
                    )
                  },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                      selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                  border =
                    FilterChipDefaults.filterChipBorder(
                      enabled = true,
                      selected = selected,
                      borderColor = MaterialTheme.colorScheme.outline,
                      selectedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                  modifier = Modifier.testTag("preset_${preset.name.lowercase()}"),
                )
              }
            }
          }
        }
      }

      // Theme Mode (System / Dark / Light)
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = "APPEARANCE (DARK & LIGHT MODE)",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              AppThemeMode.entries.forEach { mode ->
                val selected = uiPrefs.themeMode == mode
                val icon =
                  when (mode) {
                    AppThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                    AppThemeMode.DARK -> Icons.Rounded.DarkMode
                    AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                  }
                FilterChip(
                  selected = selected,
                  onClick = { onSelectThemeMode(mode) },
                  leadingIcon = {
                    Icon(
                      imageVector = icon,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  },
                  label = {
                    Text(
                      text = mode.displayName,
                      maxLines = 1,
                      softWrap = false,
                    )
                  },
                  colors =
                    FilterChipDefaults.filterChipColors(
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
                  modifier = Modifier.weight(1f).testTag("pref_theme_mode_${mode.storageKey}"),
                )
              }
            }
          }
        }
      }

      // Accent Theme Picker
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "ACCENT COLOR",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              RemoteAccentTheme.entries.forEach { theme ->
                val selected = uiPrefs.accentTheme == theme
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                  modifier =
                    Modifier
                      .clip(RoundedCornerShape(12.dp))
                      .clickable { onSelectAccentTheme(theme) }
                      .padding(horizontal = 4.dp, vertical = 4.dp)
                      .testTag("accent_theme_${theme.storageKey}"),
                ) {
                  Box(
                    modifier =
                      Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(theme.swatchColor)
                        .then(
                          if (selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                          } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                          }
                        ),
                    contentAlignment = Alignment.Center,
                  ) {
                    if (selected) {
                      Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "${theme.displayName} selected",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp),
                      )
                    }
                  }
                  Text(
                    text = theme.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color =
                      if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                  )
                }
              }
            }
          }
        }
      }

      // Default Input Pad & Compact Mode
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "ERGONOMICS & DEFAULT PAD",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              PreferredPadMode.entries.forEach { padMode ->
                val selected = uiPrefs.defaultPadMode == padMode
                FilterChip(
                  selected = selected,
                  onClick = { onSelectDefaultPad(padMode) },
                  leadingIcon = {
                    if (padMode == PreferredPadMode.DPAD) {
                      DPadModeGlyph(
                        tint =
                          if (selected) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                      )
                    } else {
                      Icon(
                        imageVector = Icons.Rounded.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  },
                  label = {
                    Text(
                      text = if (padMode == PreferredPadMode.DPAD) "Start on D-Pad" else "Start on Touchpad",
                      maxLines = 1,
                      softWrap = false,
                    )
                  },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                      selectedLabelColor = MaterialTheme.colorScheme.primary,
                      selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    ),
                  modifier = Modifier.weight(1f).testTag("pref_pad_${padMode.storageKey.lowercase()}"),
                )
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FeatureToggleRow(
              icon = Icons.Rounded.Compress,
              title = "Compact One-Handed Mode",
              subtitle = "Tighter padding and sleeker buttons to fit more on screen",
              checked = uiPrefs.compactMode,
              testTag = "pref_compact_mode_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(compactMode = enabled) }
              },
            )
          }
        }
      }

      // Visible Remote Sections
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "VISIBLE REMOTE CONTROLS",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )

            FeatureToggleRow(
              icon = Icons.Rounded.Search,
              title = "Search & Keyboard Bar",
              subtitle = "Quick TV voice/text search and mobile keyboard icons",
              checked = uiPrefs.showQuickSearchBar,
              testTag = "pref_show_search_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(showQuickSearchBar = enabled) }
              },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FeatureToggleRow(
              icon = Icons.Rounded.ViewWeek,
              title = "Navigation Row",
              subtitle = "Back, Home, Menu, and TV Input source buttons",
              checked = uiPrefs.showNavigationRow,
              testTag = "pref_show_nav_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(showNavigationRow = enabled) }
              },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FeatureToggleRow(
              icon = Icons.AutoMirrored.Rounded.VolumeUp,
              title = "Volume, Mute & Channel",
              subtitle = "Tactile VOL +/-, Mute, and CH +/- rockers",
              checked = uiPrefs.showVolumeAndChannel,
              testTag = "pref_show_volume_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(showVolumeAndChannel = enabled) }
              },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FeatureToggleRow(
              icon = Icons.Rounded.PlayCircleOutline,
              title = "Media Playback Controls",
              subtitle = "Previous, Rewind, Play/Pause, Fast Forward, Next",
              checked = uiPrefs.showMediaTransport,
              testTag = "pref_show_media_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(showMediaTransport = enabled) }
              },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FeatureToggleRow(
              icon = Icons.Rounded.Apps,
              title = "Pin OTT Apps on Main Remote",
              subtitle = "Keep off for a clean remote — always accessible via the Apps button",
              checked = uiPrefs.showStreamingShortcuts,
              testTag = "pref_show_ott_switch",
              onCheckedChange = { enabled ->
                onUpdatePreferences { it.copy(showStreamingShortcuts = enabled) }
              },
            )
          }
        }
      }

      // Streaming Apps & Grid Density (always available for the on-demand OTT Apps drawer)
      item {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "STREAMING APPS & GRID",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
                Row(
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  TextButton(
                    onClick = onOpenAddOttDialog,
                    modifier = Modifier.testTag("pref_add_ott_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.Add,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add App", maxLines = 1, softWrap = false)
                  }
                  TextButton(
                    onClick = onSelectAllShortcuts,
                    modifier = Modifier.testTag("pref_ott_select_all"),
                  ) {
                    Text("Select All", maxLines = 1, softWrap = false)
                  }
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = "Apps per row",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f),
                )
                listOf(2, 3, 4).forEach { cols ->
                  val selected = uiPrefs.shortcutColumns == cols
                  FilterChip(
                    selected = selected,
                    onClick = { onUpdatePreferences { it.copy(shortcutColumns = cols) } },
                    label = { Text("$cols") },
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                      ),
                    modifier = Modifier.testTag("pref_ott_cols_$cols"),
                  )
                }
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                  Text(
                    text = "Auto-Sync TV Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = "Automatically add apps detected while using your TV (no TV settings needed)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = uiPrefs.autoSyncTvApps,
                  onCheckedChange = { enabled ->
                    onUpdatePreferences { it.copy(autoSyncTvApps = enabled) }
                  },
                  modifier = Modifier.testTag("pref_auto_sync_tv_apps"),
                )
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Text(
                text = "Tap apps to show or hide them on your remote:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                uiPrefs.allAvailableShortcuts().forEach { shortcut ->
                  val selected = shortcut.id in uiPrefs.enabledShortcutIds
                  FilterChip(
                    selected = selected,
                    onClick = { onToggleShortcut(shortcut.id) },
                    leadingIcon = {
                      Box(
                        modifier =
                          Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(shortcut.accentColor),
                      )
                    },
                    label = {
                      Text(
                        text = shortcut.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                      ),
                    border =
                      FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = MaterialTheme.colorScheme.outline,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                      ),
                    modifier = Modifier.testTag("pref_ott_toggle_${shortcut.id}"),
                  )
                }
              }
            }
          }
      }

      if (uiPrefs.customShortcuts.isNotEmpty()) {
        item {
          Text(
            text = "YOUR ADDED OTT APPS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        items(uiPrefs.customShortcuts, key = { "custom_ott_row_${it.id}" }) { custom ->
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              OttAppIconTile(
                shortcut = custom,
                modifier = Modifier.size(36.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = custom.name,
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = custom.appLink,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(
                onClick = { onEditCustomShortcut(custom) },
                modifier = Modifier.size(48.dp).testTag("edit_custom_ott_${custom.id}"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Edit,
                  contentDescription = "Edit ${custom.name}",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
              IconButton(
                onClick = { onDeleteCustomShortcut(custom.id) },
                modifier = Modifier.size(48.dp).testTag("delete_custom_ott_${custom.id}"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteOutline,
                  contentDescription = "Delete ${custom.name}",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }

      // Reset Button
      item {
        OutlinedButton(
          onClick = onResetDefaults,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("pref_reset_ui_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.RestartAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(8.dp))
          Text("Reset UI to Defaults")
        }
      }
    }
  }
}

@Composable
private fun FeatureToggleRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  checked: Boolean,
  testTag: String,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.weight(1f).padding(end = 8.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
      Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
          checkedTrackColor = MaterialTheme.colorScheme.primary,
        ),
      modifier = Modifier.testTag(testTag),
    )
  }
}
