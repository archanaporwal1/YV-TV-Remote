package com.example.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.example.proto.remote.RemoteKeyCode
import com.example.remote.data.TvAppShortcut
import com.example.remote.data.TvAppShortcuts
import com.example.remote.feedback.FeedbackCue
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val HOLD_ACTIVATION_THRESHOLD_MS = 260L

@Composable
fun Modifier.holdableRemoteGesture(
  interactionSource: MutableInteractionSource,
  contentDescription: String,
  onClick: () -> Unit,
  onHoldStart: (() -> Unit)? = null,
  onHoldEnd: (() -> Unit)? = null,
): Modifier {
  if (onHoldStart == null || onHoldEnd == null) {
    return this.clickable(
      interactionSource = interactionSource,
      indication = androidx.compose.material3.ripple(color = MaterialTheme.colorScheme.primary),
      onClickLabel = contentDescription,
      onClick = onClick,
    )
  }

  val currentOnClick by rememberUpdatedState(onClick)
  val currentOnHoldStart by rememberUpdatedState(onHoldStart)
  val currentOnHoldEnd by rememberUpdatedState(onHoldEnd)
  var activeHold by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    onDispose {
      if (activeHold) {
        activeHold = false
        currentOnHoldEnd()
      }
    }
  }

  return this
    .indication(
      interactionSource = interactionSource,
      indication = androidx.compose.material3.ripple(color = MaterialTheme.colorScheme.primary),
    )
    .semantics {
      onClick(label = contentDescription) {
        currentOnClick()
        true
      }
    }
    .pointerInput(interactionSource) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = PressInteraction.Press(down.position)
        interactionSource.tryEmit(press)

        var upBeforeThreshold: PointerInputChange? = null
        var cancelledBeforeThreshold = false
        val timedOut =
          withTimeoutOrNull(HOLD_ACTIVATION_THRESHOLD_MS) {
            val result = waitForUpOrCancellation()
            if (result == null) {
              cancelledBeforeThreshold = true
            } else {
              upBeforeThreshold = result
            }
            false
          } ?: true

        if (!timedOut) {
          if (upBeforeThreshold != null) {
            upBeforeThreshold?.consume()
            interactionSource.tryEmit(PressInteraction.Release(press))
            currentOnClick()
          } else if (cancelledBeforeThreshold) {
            interactionSource.tryEmit(PressInteraction.Cancel(press))
          }
          return@awaitEachGesture
        }

        // Held past threshold: emit START_LONG and keep key pressed until release/cancel
        activeHold = true
        currentOnHoldStart()
        val releaseEvent = waitForUpOrCancellation()
        if (releaseEvent != null) {
          releaseEvent.consume()
          interactionSource.tryEmit(PressInteraction.Release(press))
        } else {
          interactionSource.tryEmit(PressInteraction.Cancel(press))
        }
        if (activeHold) {
          activeHold = false
          currentOnHoldEnd()
        }
      }
    }
}

@Composable
fun TactileKeyButton(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  onHoldStart: (() -> Unit)? = null,
  onHoldEnd: (() -> Unit)? = null,
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

  val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val sheenBrush =
    remember(containerColor, isDarkSurface) {
      if (containerColor == Color.Transparent) {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
      } else if (isDarkSurface) {
        Brush.verticalGradient(
          listOf(Color.White.copy(alpha = 0.06f), Color.Transparent, Color.Black.copy(alpha = 0.14f))
        )
      } else {
        Brush.verticalGradient(
          listOf(Color.White.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.04f))
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
        .holdableRemoteGesture(
          interactionSource = interactionSource,
          contentDescription = contentDescription,
          onClick = onClick,
          onHoldStart = onHoldStart,
          onHoldEnd = onHoldEnd,
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
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  targetSize: Dp = Dp.Unspecified,
) {
  val podSize =
    if (targetSize.isSpecified) targetSize.coerceIn(168.dp, 288.dp)
    else if (compact) 212.dp else 248.dp
  val ringSize = podSize * 0.92f
  val padInset = podSize * 0.052f
  val armLength = podSize * 0.315f
  val armBreadth = (podSize * 0.228f).coerceAtLeast(48.dp)
  val centerSize = podSize * 0.336f
  val arrowSize = (podSize * 0.132f).coerceIn(24.dp, 36.dp)

  val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  val spokeColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
  val innerRingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

  Box(
    modifier =
      modifier
        .size(podSize)
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
      modifier = Modifier.size(ringSize),
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
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_DPAD_UP, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_DPAD_UP) },
      contentDescription = "D-Pad Up",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = padInset).size(armLength, armBreadth).testTag("dpad_up"),
    ) {
      Icon(
        imageVector = Icons.Rounded.KeyboardArrowUp,
        contentDescription = "Up",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(arrowSize),
      )
    }

    // Down
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_DOWN, FeedbackCue.NAV_TICK) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_DPAD_DOWN, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
      contentDescription = "D-Pad Down",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = padInset).size(armLength, armBreadth).testTag("dpad_down"),
    ) {
      Icon(
        imageVector = Icons.Rounded.KeyboardArrowDown,
        contentDescription = "Down",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(arrowSize),
      )
    }

    // Left
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_LEFT, FeedbackCue.NAV_TICK) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_DPAD_LEFT, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
      contentDescription = "D-Pad Left",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.CenterStart).padding(start = padInset).size(armBreadth, armLength).testTag("dpad_left"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
        contentDescription = "Left",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(arrowSize),
      )
    }

    // Right
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_RIGHT, FeedbackCue.NAV_TICK) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_DPAD_RIGHT, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
      contentDescription = "D-Pad Right",
      shape = RoundedCornerShape(24.dp),
      containerColor = Color.Transparent,
      borderColor = Color.Transparent,
      modifier = Modifier.align(Alignment.CenterEnd).padding(end = padInset).size(armBreadth, armLength).testTag("dpad_right"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = "Right",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(arrowSize),
      )
    }

    // Center OK Button
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_DPAD_CENTER, FeedbackCue.CONFIRM_CHIME) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_DPAD_CENTER, FeedbackCue.CONFIRM_CHIME) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
      contentDescription = "Select OK",
      shape = CircleShape,
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      borderColor = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(centerSize).testTag("dpad_center"),
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
  compact: Boolean = false,
  targetHeight: Dp = Dp.Unspecified,
) {
  val thresholdPx = with(LocalDensity.current) { 42.dp.toPx() }
  var lastGestureLabel by remember { mutableStateOf("Swipe to navigate • Tap for OK") }
  val primaryCyan = MaterialTheme.colorScheme.primary
  val padHeight =
    if (targetHeight.isSpecified) targetHeight.coerceIn(168.dp, 288.dp)
    else if (compact) 212.dp else 248.dp

  Surface(
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    modifier =
      modifier
        .fillMaxWidth()
        .height(padHeight)
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
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  targetButtonHeight: Dp = Dp.Unspecified,
) {
  val btnHeight =
    if (targetButtonHeight.isSpecified) targetButtonHeight.coerceIn(48.dp, 64.dp)
    else if (compact) 50.dp else 56.dp
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    NavActionButton(
      icon = Icons.AutoMirrored.Rounded.ArrowBack,
      label = "Back",
      testTag = "btn_back",
      onClick = { onKey(RemoteKeyCode.KEYCODE_BACK, FeedbackCue.BACK_HOME) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_BACK, FeedbackCue.BACK_HOME) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_BACK) },
      modifier = Modifier.weight(1f).height(btnHeight),
    )
    NavActionButton(
      icon = Icons.Rounded.Home,
      label = "Home",
      testTag = "btn_home",
      onClick = { onKey(RemoteKeyCode.KEYCODE_HOME, FeedbackCue.BACK_HOME) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_HOME, FeedbackCue.BACK_HOME) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_HOME) },
      modifier = Modifier.weight(1f).height(btnHeight),
    )
    NavActionButton(
      icon = Icons.Rounded.Menu,
      label = "Menu",
      testTag = "btn_menu",
      onClick = { onKey(RemoteKeyCode.KEYCODE_MENU, FeedbackCue.NAV_TICK) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_MENU, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_MENU) },
      modifier = Modifier.weight(1f).height(btnHeight),
    )
    TactileKeyButton(
      onClick = { onKey(RemoteKeyCode.KEYCODE_TV_INPUT, FeedbackCue.NAV_TICK) },
      onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_TV_INPUT, FeedbackCue.NAV_TICK) },
      onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_TV_INPUT) },
      contentDescription = "Input",
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.weight(1f).height(btnHeight).testTag("btn_input"),
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
  onHoldStart: (() -> Unit)? = null,
  onHoldEnd: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  TactileKeyButton(
    onClick = onClick,
    onHoldStart = onHoldStart,
    onHoldEnd = onHoldEnd,
    contentDescription = label,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.testTag(testTag),
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
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  isListening: Boolean = false,
  onOpenVoiceInput: () -> Unit = {},
  onTriggerTvAssistant: () -> Unit = {},
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  targetConsoleHeight: Dp = Dp.Unspecified,
) {
  val consoleHeight =
    if (targetConsoleHeight.isSpecified) targetConsoleHeight.coerceIn(152.dp, 184.dp)
    else if (compact) 154.dp else 166.dp
  val centerButtonHeight = ((consoleHeight - 10.dp) / 3f).coerceAtLeast(48.dp)
  val volDownInteraction = remember { MutableInteractionSource() }
  val volUpInteraction = remember { MutableInteractionSource() }
  val chDownInteraction = remember { MutableInteractionSource() }
  val chUpInteraction = remember { MutableInteractionSource() }
  val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val rockerSheen =
    remember(isDarkSurface) {
      if (isDarkSurface) {
        Brush.verticalGradient(
          listOf(Color.White.copy(alpha = 0.05f), Color.Transparent, Color.Black.copy(alpha = 0.16f))
        )
      } else {
        Brush.verticalGradient(
          listOf(Color.White.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.05f))
        )
      }
    }

  Row(
    modifier = modifier.fillMaxWidth().height(consoleHeight),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left Sculpted Vertical Volume Rocker
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(28.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f).fillMaxSize(),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().background(rockerSheen),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
              .holdableRemoteGesture(
                interactionSource = volUpInteraction,
                contentDescription = "Volume Up",
                onClick = { onKey(RemoteKeyCode.KEYCODE_VOLUME_UP, FeedbackCue.VOLUME_STEP) },
                onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_VOLUME_UP, FeedbackCue.VOLUME_STEP) },
                onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_VOLUME_UP) },
              )
              .testTag("btn_vol_up"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Volume Up",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(vertical = 2.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = "VOL",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
          )
        }

        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
              .holdableRemoteGesture(
                interactionSource = volDownInteraction,
                contentDescription = "Volume Down",
                onClick = { onKey(RemoteKeyCode.KEYCODE_VOLUME_DOWN, FeedbackCue.VOLUME_STEP) },
                onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_VOLUME_DOWN, FeedbackCue.VOLUME_STEP) },
                onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
              )
              .testTag("btn_vol_down"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Remove,
            contentDescription = "Volume Down",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
          )
        }
      }
    }

    // Center Quick-Action Stack: Voice Mic • Mute • TV Assistant
    Column(
      modifier = Modifier.weight(1.05f).fillMaxSize(),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      TactileKeyButton(
        onClick = onOpenVoiceInput,
        contentDescription = "Voice Command",
        shape = RoundedCornerShape(18.dp),
        containerColor =
          if (isListening) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().height(centerButtonHeight).testTag("btn_voice_mic"),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(horizontal = 8.dp),
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
            text = if (isListening) "Listening" else "Voice",
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
        onClick = { onKey(RemoteKeyCode.KEYCODE_VOLUME_MUTE, FeedbackCue.VOLUME_STEP) },
        contentDescription = "Mute",
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.fillMaxWidth().height(centerButtonHeight).testTag("btn_mute"),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(horizontal = 8.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
            contentDescription = "Mute",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(19.dp),
          )
          Text(
            text = "Mute",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      TactileKeyButton(
        onClick = onTriggerTvAssistant,
        contentDescription = "TV Assistant",
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.fillMaxWidth().height(centerButtonHeight).testTag("btn_tv_voice_assist"),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(horizontal = 8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = "TV Assistant",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "Assistant",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
          )
        }
      }
    }

    // Right Sculpted Vertical Channel Rocker
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(28.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.weight(1f).fillMaxSize(),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().background(rockerSheen),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
              .holdableRemoteGesture(
                interactionSource = chUpInteraction,
                contentDescription = "Channel Up",
                onClick = { onKey(RemoteKeyCode.KEYCODE_CHANNEL_UP, FeedbackCue.NAV_TICK) },
                onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_CHANNEL_UP, FeedbackCue.NAV_TICK) },
                onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_CHANNEL_UP) },
              )
              .testTag("btn_ch_up"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Channel Up",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
          )
        }

        Text(
          text = "CH",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.padding(vertical = 2.dp),
        )

        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
              .holdableRemoteGesture(
                interactionSource = chDownInteraction,
                contentDescription = "Channel Down",
                onClick = { onKey(RemoteKeyCode.KEYCODE_CHANNEL_DOWN, FeedbackCue.NAV_TICK) },
                onHoldStart = { onKeyHoldStart(RemoteKeyCode.KEYCODE_CHANNEL_DOWN, FeedbackCue.NAV_TICK) },
                onHoldEnd = { onKeyHoldEnd(RemoteKeyCode.KEYCODE_CHANNEL_DOWN) },
              )
              .testTag("btn_ch_down"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Channel Down",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
          )
        }
      }
    }
  }
}

@Composable
fun MediaTransportBar(
  onKey: (RemoteKeyCode, FeedbackCue) -> Unit,
  onKeyHoldStart: (RemoteKeyCode, FeedbackCue) -> Unit = { _, _ -> },
  onKeyHoldEnd: (RemoteKeyCode) -> Unit = {},
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  targetBarHeight: Dp = Dp.Unspecified,
) {
  val barHeight =
    if (targetBarHeight.isSpecified) targetBarHeight.coerceIn(48.dp, 60.dp)
    else if (compact) 48.dp else 52.dp
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
      val cue = if (isPlayPause) FeedbackCue.CONFIRM_CHIME else FeedbackCue.NAV_TICK
      TactileKeyButton(
        onClick = { onKey(keyCode, cue) },
        onHoldStart = if (isPlayPause) null else ({ onKeyHoldStart(keyCode, cue) }),
        onHoldEnd = if (isPlayPause) null else ({ onKeyHoldEnd(keyCode) }),
        contentDescription = label,
        shape = RoundedCornerShape(16.dp),
        containerColor =
          if (isPlayPause) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        borderColor =
          if (isPlayPause) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
          else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.weight(if (isPlayPause) 1.28f else 1f).height(barHeight).testTag("media_${keyCode.name.lowercase()}"),
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
  shortcuts: List<TvAppShortcut> = TvAppShortcuts.all,
  columns: Int = 3,
  compact: Boolean = false,
  verifiedOnTvIds: Set<String> = emptySet(),
  onOpenCustomize: (() -> Unit)? = null,
  onAddOttApp: (() -> Unit)? = null,
) {
  val safeColumns = columns.coerceIn(2, 4)
  val tileHeight = if (compact) 76.dp else 88.dp
  val logoSize = if (compact) 36.dp else 42.dp

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "QUICK-LAUNCH STREAMING",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (verifiedOnTvIds.isNotEmpty()) {
          Text(
            text = "${verifiedOnTvIds.size} synced on your TV",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF10B981),
          )
        }
      }
      if (onAddOttApp != null) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(999.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
          modifier =
            Modifier
              .defaultMinSize(minHeight = 48.dp)
              .clip(RoundedCornerShape(999.dp))
              .clickable(onClick = onAddOttApp)
              .testTag("remote_add_ott_button"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "Add App",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }

    if (shortcuts.isEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onOpenCustomize != null) Modifier.clickable(onClick = onOpenCustomize) else Modifier)
            .testTag("shortcuts_empty_card"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = "No streaming apps selected",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Tap Customize UI or Add App to pick your favorite OTT apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      return
    }

    val rows = remember(shortcuts, safeColumns) { shortcuts.chunked(safeColumns) }
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
            modifier = Modifier.weight(1f).height(tileHeight).testTag("shortcut_${shortcut.id}"),
          ) {
            val isVerifiedOnTv = shortcut.id in verifiedOnTvIds
            Box(
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
                  ),
            ) {
              if (isVerifiedOnTv) {
                Box(
                  modifier =
                    Modifier
                      .align(Alignment.TopEnd)
                      .padding(top = 7.dp, end = 7.dp)
                      .size(7.dp)
                      .clip(CircleShape)
                      .background(Color(0xFF10B981))
                      .testTag("verified_tv_dot_${shortcut.id}"),
                )
              }
              Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                OttAppIconTile(
                  shortcut = shortcut,
                  modifier = Modifier.size(logoSize),
                )
                Spacer(Modifier.height(4.dp))
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
        }
        repeat(safeColumns - rowItems.size) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}
