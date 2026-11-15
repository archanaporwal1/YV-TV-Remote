package com.example.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remote.data.SavedTvConnection
import com.example.remote.data.UserProfile
import com.example.remote.protocol.RemoteControlClient
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun PairingPinDialog(
  pairingState: ConnectionStatus.Pairing,
  onSubmitPin: (pin: String, customName: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var pin by remember(pairingState.targetIp) { mutableStateOf("") }
  var customName by remember(pairingState.targetIp) { mutableStateOf(pairingState.customName) }
  val isValidPin = pin.length == 6 && pin.all { it in '0'..'9' || it in 'A'..'F' }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Lock,
        contentDescription = "TLS Pairing",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
    },
    title = {
      Text(
        text = "Pair with ${pairingState.serverName}",
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "Enter the 6-character hexadecimal code displayed on your TV screen.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = pin,
          onValueChange = { raw ->
            val filtered =
              raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
            pin = filtered
          },
          label = { Text("6-Character Hex PIN") },
          placeholder = { Text("e.g. A4F92C") },
          singleLine = true,
          textStyle =
            MaterialTheme.typography.headlineSmall.copy(
              fontFamily = JetBrainsMonoFamily,
              letterSpacing = 6.sp,
            ),
          keyboardOptions =
            KeyboardOptions(
              capitalization = KeyboardCapitalization.Characters,
              keyboardType = KeyboardType.Ascii,
            ),
          colors = dialogFieldColors(),
          modifier = Modifier.fillMaxWidth().testTag("pairing_pin_input"),
        )

        OutlinedTextField(
          value = customName,
          onValueChange = { customName = it },
          label = { Text("Save TV As") },
          placeholder = { Text("Living Room TV") },
          singleLine = true,
          colors = dialogFieldColors(),
          modifier = Modifier.fillMaxWidth().testTag("pairing_custom_name_input"),
        )

        if (!pairingState.errorMessage.isNullOrBlank()) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = pairingState.errorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSubmitPin(pin, customName) },
        enabled = isValidPin && !pairingState.isSubmitting,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        modifier = Modifier.testTag("pairing_verify_button"),
      ) {
        if (pairingState.isSubmitting) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Text("Verify & Pair", maxLines = 1, softWrap = false)
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("pairing_cancel_button"),
      ) {
        Text("Cancel", maxLines = 1, softWrap = false)
      }
    },
  )
}

@Composable
fun ManualIpDialog(
  onConnect: (ip: String, port: Int, customName: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var ip by remember { mutableStateOf("") }
  var portText by remember { mutableStateOf(RemoteControlClient.DEFAULT_REMOTE_PORT.toString()) }
  var customName by remember { mutableStateOf("") }
  val isIpValid = ip.trim().isNotEmpty()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Link,
        contentDescription = "Manual TV Connection",
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(
        text = "Connect by IP Address",
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "Enter your Android TV's local Wi-Fi IPv4 address if mDNS discovery is restricted.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = ip,
          onValueChange = { ip = it },
          label = { Text("TV IPv4 Address") },
          placeholder = { Text("192.168.1.42") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          colors = dialogFieldColors(),
          modifier = Modifier.fillMaxWidth().testTag("manual_ip_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = dialogFieldColors(),
            modifier = Modifier.weight(0.38f).testTag("manual_port_input"),
          )
          OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text("TV Nickname") },
            placeholder = { Text("Bedroom TV") },
            singleLine = true,
            colors = dialogFieldColors(),
            modifier = Modifier.weight(0.62f).testTag("manual_name_input"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val port = portText.toIntOrNull() ?: RemoteControlClient.DEFAULT_REMOTE_PORT
          onConnect(ip.trim(), port, customName.trim())
        },
        enabled = isIpValid,
        modifier = Modifier.testTag("manual_connect_button"),
      ) {
        Text("Connect", maxLines = 1, softWrap = false)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", maxLines = 1, softWrap = false)
      }
    },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveOrEditTvDialog(
  title: String,
  initialName: String,
  initialIp: String,
  initialPort: Int,
  initialProfileId: String,
  profiles: List<UserProfile>,
  allowEditAddress: Boolean,
  onSave: (name: String, ip: String, port: Int, profileId: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var ip by remember { mutableStateOf(initialIp) }
  var portText by remember { mutableStateOf(initialPort.toString()) }
  var selectedProfileId by remember { mutableStateOf(initialProfileId) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Tv,
        contentDescription = title,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Custom TV Name") },
          placeholder = { Text("Living Room OLED") },
          singleLine = true,
          colors = dialogFieldColors(),
          modifier = Modifier.fillMaxWidth().testTag("save_tv_name_input"),
        )
        if (allowEditAddress) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = ip,
              onValueChange = { ip = it },
              label = { Text("IPv4 Address") },
              singleLine = true,
              colors = dialogFieldColors(),
              modifier = Modifier.weight(0.65f).testTag("edit_tv_ip_input"),
            )
            OutlinedTextField(
              value = portText,
              onValueChange = { portText = it.filter(Char::isDigit).take(5) },
              label = { Text("Port") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              colors = dialogFieldColors(),
              modifier = Modifier.weight(0.35f).testTag("edit_tv_port_input"),
            )
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "Assign to Profile",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            profiles.forEach { profile ->
              val selected = profile.id == selectedProfileId
              FilterChip(
                selected = selected,
                onClick = { selectedProfileId = profile.id },
                label = { Text(profile.name) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                  ),
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            name.trim().ifEmpty { initialName },
            ip.trim().ifEmpty { initialIp },
            portText.toIntOrNull() ?: initialPort,
            selectedProfileId,
          )
        },
        modifier = Modifier.testTag("save_tv_confirm_button"),
      ) {
        Text("Save TV", maxLines = 1, softWrap = false)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", maxLines = 1, softWrap = false)
      }
    },
  )
}

@Composable
fun ProfileEditorDialog(
  title: String,
  initialName: String = "",
  confirmLabel: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Profile Name") },
        placeholder = { Text("e.g. Bedroom, Guest, Office") },
        singleLine = true,
        colors = dialogFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("profile_name_input"),
      )
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(name.trim()) },
        enabled = name.trim().isNotEmpty(),
        modifier = Modifier.testTag("profile_confirm_button"),
      ) {
        Text(confirmLabel, maxLines = 1, softWrap = false)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", maxLines = 1, softWrap = false)
      }
    },
  )
}

@Composable
fun DeleteConfirmDialog(
  tv: SavedTvConnection,
  onConfirmDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    title = {
      Text(
        text = "Delete Saved TV?",
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Text(
        text = "Remove \"${tv.customName}\" (${tv.ipAddress}) from your saved connections?",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    confirmButton = {
      Button(
        onClick = onConfirmDelete,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        modifier = Modifier.testTag("delete_tv_confirm_button"),
      ) {
        Text("Delete", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", maxLines = 1, softWrap = false)
      }
    },
  )
}

@Composable
private fun dialogFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
  )
