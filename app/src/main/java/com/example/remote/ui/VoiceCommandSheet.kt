package com.example.remote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class QuickVoicePrompt(
  val tag: String,
  val phrase: String,
)

private val quickVoicePrompts =
  listOf(
    QuickVoicePrompt("open_netflix", "Open Netflix"),
    QuickVoicePrompt("volume_up", "Volume up"),
    QuickVoicePrompt("pause", "Pause"),
    QuickVoicePrompt("go_home", "Go Home"),
    QuickVoicePrompt("search_inception", "Search Inception"),
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCommandSheet(
  voiceState: VoiceInputUiState,
  hasMicPermission: Boolean,
  isSpeechRecognizerAvailable: Boolean,
  onDismiss: () -> Unit,
  onStartListening: () -> Unit,
  onStopListening: () -> Unit,
  onRequestMicPermission: () -> Unit,
  onTriggerTvAssistant: () -> Unit,
  onRunVoiceCommandText: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var fallbackCommandText by remember { mutableStateOf("") }

  val idleBreathing =
    if (voiceState.isListening) {
      val infiniteTransition = rememberInfiniteTransition(label = "voice_orb_pulse")
      infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.14f,
        animationSpec =
          infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
          ),
        label = "orb_breathing",
      ).value
    } else {
      1.0f
    }
  val rmsScale by
    animateFloatAsState(
      targetValue =
        if (voiceState.isListening) 1.06f + (voiceState.rmsLevel * 0.28f)
        else 1.0f,
      animationSpec = spring(),
      label = "rms_scale",
    )
  val orbRingScale =
    if (voiceState.isListening) maxOf(idleBreathing, rmsScale) else 1.0f

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().testTag("voice_command_sheet"),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Header Row
      item(key = "header") {
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
              imageVector = Icons.Rounded.GraphicEq,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Column {
              Text(
                text = "TV Voice Control",
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                text =
                  when {
                    voiceState.isTvStreamingVoice -> "Streaming mic audio directly to TV Assistant"
                    voiceState.isListening -> "Listening… say a TV action, app, or search"
                    else -> "Say \"Open Netflix\", \"Volume up\", \"Pause\", or a show name"
                  },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).testTag("voice_sheet_close_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Close Voice Control",
            )
          }
        }
      }

      // Animated Microphone Orb
      item(key = "orb") {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.padding(vertical = 2.dp),
        ) {
          val primary = MaterialTheme.colorScheme.primary
          Box(
            modifier =
              Modifier
                .size(88.dp)
                .scale(orbRingScale)
                .clip(CircleShape)
                .background(
                  Brush.radialGradient(
                    colors =
                      if (voiceState.isListening) {
                        listOf(primary.copy(alpha = 0.34f), primary.copy(alpha = 0.08f), Color.Transparent)
                      } else {
                        listOf(primary.copy(alpha = 0.16f), Color.Transparent)
                      }
                  )
                ),
          )
          Surface(
            color =
              if (voiceState.isListening) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            modifier =
              Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable {
                  when {
                    !hasMicPermission -> onRequestMicPermission()
                    voiceState.isListening -> onStopListening()
                    else -> onStartListening()
                  }
                }
                .testTag("voice_orb_button"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector =
                  when {
                    !hasMicPermission -> Icons.Rounded.MicOff
                    voiceState.isListening -> Icons.Rounded.Stop
                    else -> Icons.Rounded.Mic
                  },
                contentDescription =
                  when {
                    !hasMicPermission -> "Enable microphone permission"
                    voiceState.isListening -> "Stop listening"
                    else -> "Tap microphone to speak"
                  },
                tint =
                  if (voiceState.isListening) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
              )
            }
          }
        }
      }

      // Transcript & Action Result Card
      item(key = "transcript_card") {
        Surface(
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().testTag("voice_transcript_card"),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            val displayTranscript =
              when {
                voiceState.partialTranscript.isNotBlank() -> "\"${voiceState.partialTranscript}\""
                voiceState.lastTranscript.isNotBlank() -> "\"${voiceState.lastTranscript}\""
                voiceState.isListening -> "Listening now…"
                else -> "Tap the microphone and speak your TV command"
              }
            Text(
              text = displayTranscript,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
              modifier = Modifier.testTag("voice_transcript_text"),
            )

            if (!voiceState.executedActionLabel.isNullOrBlank()) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = voiceState.executedActionLabel.orEmpty(),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.testTag("voice_executed_label"),
                )
              }
            }

            if (!voiceState.errorMessage.isNullOrBlank()) {
              Text(
                text = voiceState.errorMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("voice_error_text"),
              )
            }
          }
        }
      }

      // Permission or Recognition Availability Notice
      if (!hasMicPermission) {
        item(key = "permission_notice") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.MicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
              )
              Text(
                text = "Microphone access is required for phone voice input. You can still trigger the TV's own Assistant key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              Button(
                onClick = onRequestMicPermission,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.height(48.dp).testTag("voice_grant_permission_button"),
              ) {
                Text("Allow Mic", maxLines = 1, softWrap = false)
              }
            }
          }
        }
      } else if (!isSpeechRecognizerAvailable) {
        item(key = "speech_unavailable_notice") {
          Text(
            text = "On-device speech service is unavailable on this device. Use TV Assistant or type a voice command below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }

      // Primary Voice Action Buttons
      item(key = "primary_actions") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = {
              when {
                !hasMicPermission -> onRequestMicPermission()
                voiceState.isListening -> onStopListening()
                else -> onStartListening()
              }
            },
            colors =
              ButtonDefaults.buttonColors(
                containerColor =
                  if (voiceState.isListening) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.primary,
                contentColor =
                  if (voiceState.isListening) MaterialTheme.colorScheme.onError
                  else MaterialTheme.colorScheme.onPrimary,
              ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("voice_mic_toggle_button"),
          ) {
            Icon(
              imageVector = if (voiceState.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text =
                when {
                  !hasMicPermission -> "Grant Mic"
                  voiceState.isListening -> "Stop"
                  else -> "Tap to Speak"
                },
              maxLines = 1,
              softWrap = false,
            )
          }

          OutlinedButton(
            onClick = onTriggerTvAssistant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).height(48.dp).testTag("voice_tv_assistant_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Tv,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("TV Assistant", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
          }
        }
      }

      // Quick Voice Command Chips
      item(key = "quick_prompts") {
        Column(
          modifier = Modifier.fillMaxWidth().testTag("voice_quick_prompts_row"),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "TRY SAYING OR TAP TO RUN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickVoicePrompts, key = { it.tag }) { item ->
              AssistChip(
                onClick = { onRunVoiceCommandText(item.phrase) },
                label = { Text(item.phrase, maxLines = 1, softWrap = false) },
                colors =
                  AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                  ),
                modifier = Modifier.testTag("voice_chip_${item.tag}"),
              )
            }
          }
        }
      }

      // Direct Command Text Fallback (always available without speech services)
      item(key = "manual_input") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = fallbackCommandText,
            onValueChange = { fallbackCommandText = it },
            placeholder = { Text("Or type a command (e.g. Open YouTube)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
              KeyboardActions(
                onSend = {
                  val cmd = fallbackCommandText.trim()
                  if (cmd.isNotEmpty()) {
                    onRunVoiceCommandText(cmd)
                    fallbackCommandText = ""
                  }
                }
              ),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
              ),
            modifier = Modifier.weight(1f).testTag("voice_manual_command_input"),
          )
          Button(
            onClick = {
              val cmd = fallbackCommandText.trim()
              if (cmd.isNotEmpty()) {
                onRunVoiceCommandText(cmd)
                fallbackCommandText = ""
              }
            },
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(52.dp).testTag("voice_manual_send_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Send,
              contentDescription = "Run Command",
            )
          }
        }
      }
    }
  }
}
