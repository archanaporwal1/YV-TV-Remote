package com.example.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.feedback.FeedbackCue
import com.example.remote.protocol.TvImeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSearchSheet(
  imeState: TvImeState,
  onDismiss: () -> Unit,
  onLiveImeChange: (newText: String, previousText: String) -> Unit,
  onSubmitSearch: (text: String, useDirectKeys: Boolean) -> Unit,
  onSendKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  onOpenVoiceInput: () -> Unit = {},
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current
  var useDirectKeys by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf(imeState.currentText) }

  LaunchedEffect(imeState.currentText) {
    if (!useDirectKeys && imeState.currentText != query) {
      query = imeState.currentText
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    LaunchedEffect(Unit) {
      kotlinx.coroutines.delay(120L)
      runCatching {
        focusRequester.requestFocus()
        keyboardController?.show()
      }
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp)
          .padding(bottom = 28.dp),
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
            imageVector = Icons.Rounded.Keyboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Column {
            Text(
              text = "TV Keyboard & Search",
              style = MaterialTheme.typography.headlineSmall,
            )
            val contextText =
              listOf(imeState.appLabel, imeState.fieldLabel)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
                .ifBlank { "Type directly into focused TV search boxes" }
            Text(
              text = contextText,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp).testTag("keyboard_close_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Close Keyboard",
          )
        }
      }

      // Input Mode Selector: Live IME Sync vs Direct Keystrokes
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = !useDirectKeys,
          onClick = { useDirectKeys = false },
          label = { Text("TV IME Sync") },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.primary,
            ),
          modifier = Modifier.testTag("keyboard_mode_ime"),
        )
        FilterChip(
          selected = useDirectKeys,
          onClick = { useDirectKeys = true },
          label = { Text("Direct Keystrokes") },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.primary,
            ),
          modifier = Modifier.testTag("keyboard_mode_direct"),
        )
      }

      OutlinedTextField(
        value = query,
        onValueChange = { updated ->
          runCatching {
            val prev = query
            query = updated
            if (!useDirectKeys) {
              onLiveImeChange(updated, prev)
            }
          }
        },
        label = { Text("Search or Type on TV") },
        placeholder = { Text("Movies, shows, YouTube videos...") },
        singleLine = true,
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(
              onClick = {
                runCatching {
                  val prev = query
                  query = ""
                  if (!useDirectKeys) {
                    onLiveImeChange("", prev)
                  }
                }
              },
              modifier = Modifier.testTag("keyboard_clear_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Clear,
                contentDescription = "Clear text",
              )
            }
          }
        },
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
          ),
        keyboardActions =
          KeyboardActions(
            onSearch = { runCatching { onSubmitSearch(query, useDirectKeys) } },
          ),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
          ),
        modifier =
          Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("keyboard_search_input"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = {
            onSendKey(RemoteKeyCode.KEYCODE_SEARCH, FeedbackCue.CONFIRM_CHIME)
            runCatching {
              focusRequester.requestFocus()
              keyboardController?.show()
            }
          },
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
          modifier = Modifier.weight(1f).height(48.dp).testTag("keyboard_open_tv_search"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text("TV Search", maxLines = 1, softWrap = false)
        }

        Button(
          onClick = { runCatching { onSubmitSearch(query, useDirectKeys) } },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          modifier = Modifier.weight(1f).height(48.dp).testTag("keyboard_submit_search"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardReturn,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = if (useDirectKeys) "Type & Go" else "Search",
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      // Quick Keystroke Controls
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        KeyboardQuickActionChip(
          icon = Icons.Rounded.Mic,
          label = "Voice",
          onClick = { runCatching { onOpenVoiceInput() } },
          modifier = Modifier.weight(1f).testTag("keyboard_voice_btn"),
        )
        KeyboardQuickActionChip(
          icon = Icons.Rounded.SpaceBar,
          label = "Space",
          onClick = { runCatching { onSendKey(RemoteKeyCode.KEYCODE_SPACE, FeedbackCue.NAV_TICK) } },
          modifier = Modifier.weight(1f).testTag("keyboard_space_btn"),
        )
        KeyboardQuickActionChip(
          icon = Icons.AutoMirrored.Rounded.Backspace,
          label = "Delete",
          onClick = {
            if (query.isNotEmpty()) {
              val prev = query
              query = query.dropLast(1)
              if (!useDirectKeys) {
                onLiveImeChange(query, prev)
                return@KeyboardQuickActionChip
              }
            }
            onSendKey(RemoteKeyCode.KEYCODE_DEL, FeedbackCue.BACK_HOME)
          },
          onHoldStart = {
            if (!useDirectKeys && query.isNotEmpty()) {
              val prev = query
              query = ""
              onLiveImeChange("", prev)
            } else {
              onKeyHoldStart(RemoteKeyCode.KEYCODE_DEL, FeedbackCue.BACK_HOME)
            }
          },
          onHoldEnd = {
            if (useDirectKeys || query.isEmpty()) {
              onKeyHoldEnd(RemoteKeyCode.KEYCODE_DEL)
            }
          },
          modifier = Modifier.weight(1f).testTag("keyboard_backspace_btn"),
        )
        KeyboardQuickActionChip(
          icon = Icons.AutoMirrored.Rounded.KeyboardReturn,
          label = "Enter",
          onClick = { onSendKey(RemoteKeyCode.KEYCODE_ENTER, FeedbackCue.CONFIRM_CHIME) },
          modifier = Modifier.weight(1f).testTag("keyboard_enter_btn"),
        )
      }
    }
  }
}

@Composable
private fun KeyboardQuickActionChip(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
  onHoldStart: (() -> Unit)? = null,
  onHoldEnd: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .height(50.dp)
        .clip(RoundedCornerShape(14.dp))
        .holdableRemoteGesture(
          interactionSource = interactionSource,
          contentDescription = label,
          onClick = onClick,
          onHoldStart = onHoldStart,
          onHoldEnd = onHoldEnd,
        ),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}
