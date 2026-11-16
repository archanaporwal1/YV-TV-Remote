package com.example.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts
import com.example.remote.feedback.FeedbackCue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TactileKeyButton(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(16.dp),
  containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
  borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by
    animateFloatAsState(
      targetValue = if (isPressed) 0.93f else 1f,
      animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      label = "key_scale",
    )
  val animatedBorder by
    animateColorAsState(
      targetValue = if (isPressed) MaterialTheme.colorScheme.primary else borderColor,
      label = "key_border",
    )

  val sheenBrush =
    remember(containerColor) {
      if (containerColor == Color.Transparent) {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
      } else {
        Brush.verticalGradient(
          listOf(Color.White.copy(alpha = 0.06f), Color.Transparent, Color.Black.copy(alpha = 0.14f))
        )
      }
    }

  Surface(
    color = containerColor,
    shape = shape,
    border = BorderStroke(1.dp, animatedBorder),
    modifier =
      modifier
        .scale(scale)
        .clip(shape)
        .clickable(
          interactionSource = interactionSource,
          indication = androidx.compose.material3.ripple(color = MaterialTheme.colorScheme.primary),
          onClickLabel = contentDescription,
          onClick = onClick,
        ),
  ) {
    Box(
      modifier = Modifier.background(sheenBrush),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

@Composable
fun ClassicDPadPod(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  modifier: Modifier = Modifier,
) {
  val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  val spokeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
  val innerRingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

  Box(
    modifier =
      modifier
        .size(256.dp)
        .drawBehind {
          drawCircle(
            brush =
              Brush.radialGradient(
                colors = listOf(glowColor, Color.Transparent),
                center = center,
                radius = size.minDimension * 0.56f,
              )
          )
        },
    contentAlignment = Alignment.Center,
  ) {
    // Outer precision chassis ring with engraved 45° quadrant spokes
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
      tonalElevation = 6.dp,
      modifier = Modifier.size(236.dp),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val outerR = size.minDimension * 0.47f
        val innerR = size.minDimension * 0.21f
        val angles = listOf(45.0, 135.0, 225.0, 315.0)
        angles.forEach { deg ->
          val rad = Math.toRadians(deg)
          val start =
            Offset(
              center.x + (innerR * cos(rad)).toFloat(),
              center.y + (innerR * sin(rad)).toFloat(),
            )
          val end =
            Offset(
              center.x + (outerR * cos(rad)).toFloat(),
              center.y + (outerR * sin(rad)).toFloat(),
            )
          drawLine(
            color = spokeColor,
            start = start,
            end = end,
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
          )
        }
        drawCircle(
          color = innerRingColor,
          radius = innerR,
          style = Stroke(width = 1.2.dp.toPx()),
        )
      }
    }

    // Up
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_UP, FeedbackCue.NAV_TICK) },
      contentDescription = "D-Pad Up",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(80.dp, 58.dp).testTag("dpad_up"),
    ) {
      Icon(
        imageVector = Icons.Rounded.KeyboardArrowUp,
        contentDescription = "Up",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(34.dp),
      )
    }

    // Down
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_DOWN, FeedbackCue.NAV_TICK) },
      contentDescription = "D-Pad Down",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(80.dp, 58.dp).testTag("dpad_down"),
    ) {
      Icon(
        imageVector = Icons.Rounded.KeyboardArrowDown,
        contentDescription = "Down",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(34.dp),
      )
    }

    // Left
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_LEFT, FeedbackCue.NAV_TICK) },
      contentDescription = "D-Pad Left",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(58.dp, 80.dp).testTag("dpad_left"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
        contentDescription = "Left",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(34.dp),
      )
    }

    // Right
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT, FeedbackCue.NAV_TICK) },
      contentDescription = "D-Pad Right",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(58.dp, 80.dp).testTag("dpad_right"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = "Right",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(34.dp),
      )
    }

    // Center OK Button
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_CENTER, FeedbackCue.CONFIRM_CHIME) },
      contentDescription = "Select OK",
      shape = CircleShape,
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      borderColor = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(86.dp).testTag("dpad_center"),
    ) {
      Text(
        text = "OK",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
fun TouchpadSurfacePod(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  modifier: Modifier = Modifier,
) {
  val thresholdPx = with(LocalDensity.current) { 42.dp.toPx() }
  var lastGestureLabel by remember { mutableStateOf("Swipe to navigate • Tap for OK") }
  val primaryCyan = MaterialTheme.colorScheme.primary

  Surface(
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    modifier =
      modifier
        .fillMaxWidth()
        .height(256.dp)
        .testTag("touchpad_surface")
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = {
              lastGestureLabel = "OK Selected"
              onKey(RemoteKeyCode.KEYCODE_DPAD_CENTER, FeedbackCue.CONFIRM_CHIME)
            }
          )
        }
        .pointerInput(thresholdPx) {
          var accum = Offset.Zero
          detectDragGestures(
            onDragStart = { accum = Offset.Zero },
            onDragEnd = { accum = Offset.Zero },
            onDragCancel = { accum = Offset.Zero },
            onDrag = { change, dragAmount ->
              change.consume()
              accum += dragAmount
              if (abs(accum.x) > abs(accum.y) && abs(accum.x) >= thresholdPx) {
                if (accum.x > 0) {
                  lastGestureLabel = "Swipe →"
                  onKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT, FeedbackCue.NAV_TICK)
                } else {
                  lastGestureLabel = "Swipe ←"
                  onKey(RemoteKeyCode.KEYCODE_DPAD_LEFT, FeedbackCue.NAV_TICK)
                }
                accum = Offset.Zero
              } else if (abs(accum.y) >= thresholdPx) {
                if (accum.y > 0) {
                  lastGestureLabel = "Swipe ↓"
                  onKey(RemoteKeyCode.KEYCODE_DPAD_DOWN, FeedbackCue.NAV_TICK)
                } else {
                  lastGestureLabel = "Swipe ↑"
                  onKey(RemoteKeyCode.KEYCODE_DPAD_UP, FeedbackCue.NAV_TICK)
                }
                accum = Offset.Zero
              }
            },
          )
        },
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .drawBehind {
            drawCircle(
              brush =
                Brush.radialGradient(
                  colors = listOf(primaryCyan.copy(alpha = 0.14f), Color.Transparent),
                  center = center,
                  radius = size.maxDimension * 0.55f,
                )
            )
          },
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.TouchApp,
          contentDescription = "Touchpad",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(34.dp),
        )
        Text(
          text = lastGestureLabel,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun PrimaryNavigationRow(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    NavActionButton(
      icon = Icons.AutoMirrored.Rounded.ArrowBack,
      label = "Back",
      testTag = "btn_back",
      onClick = { onKey(RemoteKeyCode.KEYCODE_BACK, FeedbackCue.BACK_HOME) },
      modifier = Modifier.weight(1f),
    )
    NavActionButton(
      icon = Icons.Rounded.Home,
      label = "Home",
      testTag = "btn_home",
      onClick = { onKey(RemoteKeyCode.KEYCODE_HOME, FeedbackCue.BACK_HOME) },
      modifier = Modifier.weight(1f),
    )
    NavActionButton(
      icon = Icons.Rounded.Menu,
      label = "Menu",
      testTag = "btn_menu",
      onClick = { onKey(RemoteKeyCode.KEYCODE_MENU, FeedbackCue.NAV_TICK) },
      modifier = Modifier.weight(1f),
    )
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_TV_INPUT, FeedbackCue.NAV_TICK) },
      contentDescription = "Input",
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.weight(1f).height(58.dp).testTag("btn_input"),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        TvInputSourceGlyph(
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = "Input",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

@Composable
private fun NavActionButton(
  icon: ImageVector,
  label: String,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TactileKeyButton(
    onClick = onClick,
    contentDescription = label,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.height(58.dp).testTag(testTag),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.height(2.dp))
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

@Composable
fun VolumeAndChannelPod(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Volume Rocker
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f).height(58.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clickable { onKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN, FeedbackCue.VOLUME_STEP) }
              .testTag("btn_vol_down"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Remove,
            contentDescription = "Volume Down",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "VOL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
        }
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clickable { onKey(RemoteKeyCode.KEYCODE_VOLUME_UP, FeedbackCue.VOLUME_STEP) }
              .testTag("btn_vol_up"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Volume Up",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }

    // Mute Button
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE, FeedbackCue.VOLUME_STEP) },
      contentDescription = "Mute",
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.size(56.dp, 58.dp).testTag("btn_mute"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
        contentDescription = "Mute",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }

    // Channel Rocker
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(20.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f).height(58.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clickable { onKey(RemoteKeyCode.KEYCODE_CHANNEL_DOWN, FeedbackCue.NAV_TICK) }
              .testTag("btn_ch_down"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Channel Down",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          text = "CH",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          softWrap = false,
        )
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clickable { onKey(RemoteKeyCode.KEYCODE_CHANNEL_UP, FeedbackCue.NAV_TICK) }
              .testTag("btn_ch_up"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Channel Up",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
fun MediaTransportBar(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    val buttons =
      listOf(
        Triple(Icons.Rounded.SkipPrevious, "Previous", RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS),
        Triple(Icons.Rounded.FastRewind, "Rewind", RemoteKeyCode.KEYCODE_MEDIA_REWIND),
        Triple(null, "Play Pause", RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE),
        Triple(Icons.Rounded.FastForward, "Fast Forward", RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD),
        Triple(Icons.Rounded.SkipNext, "Next", RemoteKeyCode.KEYCODE_MEDIA_NEXT),
      )

    buttons.forEachIndexed { index, (icon, label, keyCode) ->
      val isPlayPause = index == 2
      TactileKeyButton(
        onClick = {
          onKey(
            keyCode,
            if (isPlayPause) FeedbackCue.CONFIRM_CHIME else FeedbackCue.NAV_TICK,
          )
        },
        contentDescription = label,
        shape = RoundedCornerShape(16.dp),
        containerColor =
          if (isPlayPause) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        borderColor =
          if (isPlayPause) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
          else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.weight(if (isPlayPause) 1.28f else 1f).height(52.dp).testTag("media_${keyCode.name.lowercase()}"),
      ) {
        if (icon == null) {
          PlayPauseGlyph(
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
          )
        } else {
          Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
fun StreamingShortcutsGrid(
  onLaunchShortcut: (TvAppShortcut) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "QUICK-LAUNCH STREAMING",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val rows = remember { TvAppShortcuts.all.chunked(3) }
    rows.forEach { rowItems ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        rowItems.forEach { shortcut ->
          TactileKeyButton(
            onClick = { onLaunchShortcut(shortcut) },
            contentDescription = "Launch ${shortcut.name}",
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            borderColor = shortcut.accentColor.copy(alpha = 0.35f),
            modifier = Modifier.weight(1f).height(88.dp).testTag("shortcut_${shortcut.id}"),
          ) {
            Column(
              modifier =
                Modifier
                  .fillMaxSize()
                  .background(
                    Brush.verticalGradient(
                      colors =
                        listOf(
                          shortcut.accentColor.copy(alpha = 0.14f),
                          Color.Transparent,
                        )
                    )
                  )
                  .padding(horizontal = 6.dp, vertical = 8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              OttAppIconTile(
                shortcut = shortcut,
                modifier = Modifier.size(42.dp),
              )
              Spacer(Modifier.height(6.dp))
              Text(
                text = shortcut.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
        repeat(3 - rowItems.size) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}
