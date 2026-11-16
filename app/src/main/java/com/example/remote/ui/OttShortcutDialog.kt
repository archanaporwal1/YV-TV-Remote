package com.example.remote.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.remote.data.CustomOttIconStyle
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddOrEditOttShortcutDialog(
  editingShortcut: TvAppShortcut?,
  alreadyAddedIds: Set<String>,
  onQuickAddPopular: (TvAppShortcut) -> Unit,
  onSaveCustom: (name: String, appLink: String, badge: String, accentColor: Color, iconStyle: CustomOttIconStyle) -> Unit,
  onDismiss: () -> Unit,
) {
  val isEditing = editingShortcut != null
  var name by remember(editingShortcut) { mutableStateOf(editingShortcut?.name ?: "") }
  var appLink by remember(editingShortcut) { mutableStateOf(editingShortcut?.appLink ?: "") }
  var badge by remember(editingShortcut) { mutableStateOf(editingShortcut?.badge ?: "") }
  var badgeManuallyEdited by remember(editingShortcut) { mutableStateOf(editingShortcut != null) }
  var selectedColor by remember(editingShortcut) {
    mutableStateOf(editingShortcut?.accentColor ?: TvAppShortcuts.customColorPalette.first())
  }
  var selectedStyle by remember(editingShortcut) {
    mutableStateOf(editingShortcut?.iconStyle ?: CustomOttIconStyle.BADGE)
  }

  val availablePopular =
    remember(alreadyAddedIds, isEditing) {
      if (isEditing) emptyList()
      else TvAppShortcuts.popularAddableCatalog.filter { it.id !in alreadyAddedIds }
    }

  val effectiveBadge =
    badge.trim().ifEmpty { TvAppShortcuts.suggestBadge(name.ifBlank { "TV" }) }.take(3)
  val normalizedPreviewLink = remember(appLink) { TvAppShortcuts.normalizeAppLink(appLink) }
  val previewShortcut =
    remember(name, effectiveBadge, normalizedPreviewLink, selectedColor, selectedStyle) {
      TvAppShortcut(
        id = editingShortcut?.id ?: "preview_custom",
        name = name.ifBlank { "Custom App" },
        badge = effectiveBadge,
        appLink = normalizedPreviewLink.ifBlank { "https://example.com" },
        accentColor = selectedColor,
        isCustom = true,
        iconStyle = selectedStyle,
      )
    }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      tonalElevation = 6.dp,
      modifier =
        Modifier
          .fillMaxWidth(0.92f)
          .heightIn(max = 620.dp)
          .testTag("add_ott_dialog"),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
          ) {
            OttAppIconTile(
              shortcut = previewShortcut,
              modifier = Modifier.size(40.dp),
            )
            Column {
              Text(
                text = if (isEditing) "Edit OTT App" else "Add OTT App",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = if (isEditing) "Update shortcut icon or launch link" else "1-tap popular apps or add any custom OTT",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).testTag("ott_dialog_close_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Close",
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Scrollable body
        Column(
          modifier =
            Modifier
              .weight(1f, fill = false)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 20.dp, vertical = 14.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (availablePopular.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "1-TAP POPULAR STREAMING APPS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
              LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
              ) {
                items(availablePopular, key = { it.id }) { preset ->
                  Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, preset.accentColor.copy(alpha = 0.45f)),
                    modifier =
                      Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onQuickAddPopular(preset) }
                        .testTag("quick_add_ott_${preset.id}"),
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      OttAppIconTile(
                        shortcut = preset,
                        modifier = Modifier.size(28.dp),
                      )
                      Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                      )
                      Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add ${preset.name}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  }
                }
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }

          Text(
            text = if (isEditing) "APP DETAILS" else "OR ADD CUSTOM APP",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedTextField(
              value = name,
              onValueChange = { updated ->
                name = updated
                if (!badgeManuallyEdited) {
                  badge = if (updated.isBlank()) "" else TvAppShortcuts.suggestBadge(updated)
                }
              },
              label = { Text("App Name") },
              placeholder = { Text("e.g. Tubi TV") },
              singleLine = true,
              modifier = Modifier.weight(1.7f).testTag("ott_name_input"),
            )
            OutlinedTextField(
              value = badge,
              onValueChange = { updated ->
                badgeManuallyEdited = updated.isNotBlank()
                badge = updated.uppercase().take(3)
              },
              label = { Text("Badge") },
              placeholder = { Text(effectiveBadge) },
              singleLine = true,
              modifier = Modifier.weight(1f).testTag("ott_badge_input"),
            )
          }

          OutlinedTextField(
            value = appLink,
            onValueChange = { appLink = it },
            label = { Text("Launch URL or TV Package ID") },
            placeholder = { Text("e.g. tubitv.com or com.tubitv") },
            supportingText =
              if (normalizedPreviewLink.isNotEmpty()) {
                { Text("TV Launch URI: $normalizedPreviewLink", maxLines = 1, overflow = TextOverflow.Ellipsis) }
              } else {
                null
              },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("ott_link_input"),
          )

          // Accent Color Swatches
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "TILE COLOR",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              TvAppShortcuts.customColorPalette.forEachIndexed { index, color ->
                val isSelected = selectedColor == color
                Box(
                  modifier =
                    Modifier
                      .size(36.dp)
                      .clip(CircleShape)
                      .background(color)
                      .then(
                        if (isSelected) {
                          Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                          Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        }
                      )
                      .clickable { selectedColor = color }
                      .testTag("ott_color_$index"),
                  contentAlignment = Alignment.Center,
                ) {
                  if (isSelected) {
                    Icon(
                      imageVector = Icons.Rounded.Check,
                      contentDescription = "Selected color",
                      tint = if (color.luminance() > 0.65f) Color.Black else Color.White,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }

          // Icon Style Picker
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "ICON STYLE",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              CustomOttIconStyle.entries.forEach { style ->
                val isSelected = selectedStyle == style
                val leadingIcon =
                  when (style) {
                    CustomOttIconStyle.BADGE -> null
                    CustomOttIconStyle.PLAY -> Icons.Rounded.PlayArrow
                    CustomOttIconStyle.TV -> Icons.Rounded.Tv
                    CustomOttIconStyle.MOVIE -> Icons.Rounded.Movie
                    CustomOttIconStyle.SPORTS -> Icons.Rounded.SportsSoccer
                    CustomOttIconStyle.MUSIC -> Icons.Rounded.MusicNote
                    CustomOttIconStyle.LIVE -> Icons.Rounded.LiveTv
                  }
                FilterChip(
                  selected = isSelected,
                  onClick = { selectedStyle = style },
                  leadingIcon =
                    if (leadingIcon != null) {
                      {
                        Icon(
                          imageVector = leadingIcon,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                        )
                      }
                    } else {
                      null
                    },
                  label = {
                    Text(
                      text = style.label,
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
                  modifier = Modifier.testTag("ott_icon_${style.key}"),
                )
              }
            }
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Sticky footer actions
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onDismiss,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.weight(1f).height(48.dp).testTag("ott_cancel_button"),
          ) {
            Text("Cancel", maxLines = 1, softWrap = false)
          }
          Button(
            onClick = {
              onSaveCustom(
                name.trim(),
                appLink.trim(),
                effectiveBadge,
                selectedColor,
                selectedStyle,
              )
            },
            enabled = name.isNotBlank() && appLink.isNotBlank(),
            modifier = Modifier.weight(1f).height(48.dp).testTag("ott_save_button"),
          ) {
            Text(
              text = if (isEditing) "Save Changes" else "Add to Remote",
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
  }
}
