package com.example.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.remote.data.TvAppShortcut
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Natural 4-way D-Pad ring glyph for switching into D-Pad navigation mode.
 */
@Composable
fun DPadModeGlyph(
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    drawCircle(
      color = tint,
      radius = w * 0.44f,
      center = Offset(cx, cy),
      style = Stroke(width = w * 0.11f),
    )
    drawCircle(
      color = tint,
      radius = w * 0.14f,
      center = Offset(cx, cy),
    )
    val tickLen = w * 0.12f
    val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx, h * 0.16f), Offset(cx, h * 0.16f + tickLen), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx, h * 0.84f), Offset(cx, h * 0.84f - tickLen), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.16f, cy), Offset(w * 0.16f + tickLen, cy), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.84f, cy), Offset(w * 0.84f - tickLen, cy), strokeWidth = stroke.width, cap = StrokeCap.Round)
  }
}

/**
 * Dual Play + Pause glyph matching physical TV remote media keys.
 */
@Composable
fun PlayPauseGlyph(
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val play =
      Path().apply {
        moveTo(w * 0.14f, h * 0.20f)
        lineTo(w * 0.52f, h * 0.50f)
        lineTo(w * 0.14f, h * 0.80f)
        close()
      }
    drawPath(play, color = tint)

    val barW = w * 0.12f
    val barH = h * 0.56f
    val top = h * 0.22f
    drawRoundRect(
      color = tint,
      topLeft = Offset(w * 0.58f, top),
      size = Size(barW, barH),
      cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
    )
    drawRoundRect(
      color = tint,
      topLeft = Offset(w * 0.76f, top),
      size = Size(barW, barH),
      cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
    )
  }
}

/**
 * Landscape TV screen with an inward source arrow matching physical TV Input / Source buttons.
 */
@Composable
fun TvInputSourceGlyph(
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val strokeW = w * 0.10f
    // TV monitor outline (open slightly on left for the arrow)
    val frame =
      Path().apply {
        moveTo(w * 0.30f, h * 0.28f)
        lineTo(w * 0.30f, h * 0.20f)
        lineTo(w * 0.88f, h * 0.20f)
        lineTo(w * 0.88f, h * 0.78f)
        lineTo(w * 0.30f, h * 0.78f)
        lineTo(w * 0.30f, h * 0.70f)
      }
    drawPath(
      path = frame,
      color = tint,
      style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Inward arrow
    drawLine(
      color = tint,
      start = Offset(w * 0.10f, h * 0.49f),
      end = Offset(w * 0.58f, h * 0.49f),
      strokeWidth = strokeW,
      cap = StrokeCap.Round,
    )
    val head =
      Path().apply {
        moveTo(w * 0.43f, h * 0.35f)
        lineTo(w * 0.60f, h * 0.49f)
        lineTo(w * 0.43f, h * 0.63f)
      }
    drawPath(
      path = head,
      color = tint,
      style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }
}

/**
 * Natural OTT app icon tile matching each streaming platform's real launcher identity.
 */
@Composable
fun OttAppIconTile(
  shortcut: TvAppShortcut,
  modifier: Modifier = Modifier,
) {
  val bgBrush =
    when (shortcut.id) {
      "youtube" -> Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF3F4F6)))
      "netflix" -> Brush.verticalGradient(listOf(Color(0xFF171717), Color(0xFF0A0A0A)))
      "prime_video" -> Brush.linearGradient(listOf(Color(0xFF001F3F), Color(0xFF00539B)))
      "jiocinema" -> Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFF8C0052)))
      "hotstar" -> Brush.linearGradient(listOf(Color(0xFF071330), Color(0xFF0D4484)))
      "sonyliv" -> Brush.linearGradient(listOf(Color(0xFF1B153D), Color(0xFF0E0B22)))
      "zee5" -> Brush.linearGradient(listOf(Color(0xFF140726), Color(0xFF090312)))
      "sunnxt" -> Brush.linearGradient(listOf(Color(0xFFFF3B00), Color(0xFFFF9800)))
      else -> Brush.linearGradient(listOf(Color(0xFFE60023), Color(0xFFA30019)))
    }

  val borderColor =
    if (shortcut.id == "youtube") Color.White.copy(alpha = 0.65f)
    else Color.White.copy(alpha = 0.18f)

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = Color.Transparent,
    border = BorderStroke(1.dp, borderColor),
    shadowElevation = 4.dp,
    modifier = modifier,
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(bgBrush)
          .padding(5.dp),
      contentAlignment = Alignment.Center,
    ) {
      OttBrandMarkCanvas(id = shortcut.id, modifier = Modifier.fillMaxSize())
    }
  }
}

@Composable
private fun OttBrandMarkCanvas(
  id: String,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    when (id) {
      "youtube" -> {
        drawRoundRect(
          color = Color(0xFFFF0000),
          topLeft = Offset(w * 0.04f, h * 0.16f),
          size = Size(w * 0.92f, h * 0.68f),
          cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        )
        val play =
          Path().apply {
            moveTo(w * 0.41f, h * 0.33f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.41f, h * 0.67f)
            close()
          }
        drawPath(play, Color.White)
      }

      "netflix" -> {
        // Authentic 3-part Netflix folded scarlet N ribbon
        drawRect(
          color = Color(0xFFB20710),
          topLeft = Offset(w * 0.22f, h * 0.08f),
          size = Size(w * 0.18f, h * 0.84f),
        )
        drawRect(
          color = Color(0xFFB20710),
          topLeft = Offset(w * 0.60f, h * 0.08f),
          size = Size(w * 0.18f, h * 0.84f),
        )
        val diag =
          Path().apply {
            moveTo(w * 0.22f, h * 0.08f)
            lineTo(w * 0.40f, h * 0.08f)
            lineTo(w * 0.78f, h * 0.92f)
            lineTo(w * 0.60f, h * 0.92f)
            close()
          }
        drawPath(diag, Color(0xFFE50914))
      }

      "prime_video" -> {
        // Bold Prime play mark + signature Amazon cyan smile arrow
        val play =
          Path().apply {
            moveTo(w * 0.36f, h * 0.16f)
            lineTo(w * 0.68f, h * 0.36f)
            lineTo(w * 0.36f, h * 0.56f)
            close()
          }
        drawPath(play, Color.White)
        val smile =
          Path().apply {
            moveTo(w * 0.14f, h * 0.66f)
            quadraticTo(w * 0.50f, h * 0.94f, w * 0.84f, h * 0.64f)
          }
        drawPath(
          path = smile,
          color = Color(0xFF00A8E1),
          style = Stroke(width = w * 0.10f, cap = StrokeCap.Round),
        )
        val arrowTip =
          Path().apply {
            moveTo(w * 0.72f, h * 0.59f)
            lineTo(w * 0.87f, h * 0.62f)
            lineTo(w * 0.82f, h * 0.77f)
          }
        drawPath(
          path = arrowTip,
          color = Color(0xFF00A8E1),
          style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }

      "jiocinema" -> {
        // Cinema glow halo + centered white play + golden sparkle
        drawCircle(
          color = Color.White.copy(alpha = 0.22f),
          radius = w * 0.44f,
          center = center,
        )
        drawCircle(
          color = Color.White,
          radius = w * 0.41f,
          center = center,
          style = Stroke(width = w * 0.09f),
        )
        val play =
          Path().apply {
            moveTo(w * 0.41f, h * 0.31f)
            lineTo(w * 0.68f, h * 0.50f)
            lineTo(w * 0.41f, h * 0.69f)
            close()
          }
        drawPath(play, Color.White)
        drawCircle(
          color = Color(0xFFFFD54F),
          radius = w * 0.07f,
          center = Offset(w * 0.78f, h * 0.22f),
        )
      }

      "hotstar" -> {
        // Curved Disney+ orbital arc + luminous 4-point sparkle star
        val arc =
          Path().apply {
            moveTo(w * 0.14f, h * 0.42f)
            quadraticTo(w * 0.50f, h * 0.06f, w * 0.86f, h * 0.42f)
          }
        drawPath(
          path = arc,
          brush =
            Brush.horizontalGradient(
              listOf(Color(0xFF00E5FF), Color(0xFFFFFFFF), Color(0xFFFFD54F)),
            ),
          style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
        )
        val star = Path()
        val cx = w * 0.50f
        val cy = h * 0.60f
        val outer = w * 0.36f
        val inner = w * 0.13f
        for (i in 0 until 8) {
          val r = if (i % 2 == 0) outer else inner
          val angle = -PI / 2.0 + i * (PI / 4.0)
          val x = cx + (r * cos(angle)).toFloat()
          val y = cy + (r * sin(angle)).toFloat()
          if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(
          path = star,
          brush =
            Brush.radialGradient(
              colors = listOf(Color.White, Color(0xFF60D9FF)),
              center = Offset(cx, cy),
              radius = outer,
            ),
        )
      }

      "sonyliv" -> {
        // Multi-layered colorful LIV play prism
        val back =
          Path().apply {
            moveTo(w * 0.16f, h * 0.14f)
            lineTo(w * 0.84f, h * 0.48f)
            lineTo(w * 0.16f, h * 0.86f)
            close()
          }
        drawPath(
          path = back,
          brush =
            Brush.linearGradient(
              listOf(Color(0xFFFF9800), Color(0xFFFF2A85), Color(0xFF7C3AED)),
            ),
        )
        val mid =
          Path().apply {
            moveTo(w * 0.24f, h * 0.24f)
            lineTo(w * 0.78f, h * 0.50f)
            lineTo(w * 0.24f, h * 0.78f)
            close()
          }
        drawPath(
          path = mid,
          brush =
            Brush.linearGradient(
              listOf(Color(0xFF00D4FF), Color(0xFF8B5CF6)),
            ),
        )
        val core =
          Path().apply {
            moveTo(w * 0.34f, h * 0.36f)
            lineTo(w * 0.60f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.64f)
            close()
          }
        drawPath(core, Color.White)
      }

      "zee5" -> {
        // Signature ZEE5 rainbow neon ring + bold Z5 monogram
        drawCircle(
          brush =
            Brush.sweepGradient(
              colors =
                listOf(
                  Color(0xFFFF007F),
                  Color(0xFF8A2BE2),
                  Color(0xFF00D4FF),
                  Color(0xFFFF007F),
                ),
            ),
          radius = w * 0.44f,
          center = center,
          style = Stroke(width = w * 0.10f),
        )
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Bold 'Z'
        val z =
          Path().apply {
            moveTo(w * 0.23f, h * 0.35f)
            lineTo(w * 0.47f, h * 0.35f)
            lineTo(w * 0.23f, h * 0.65f)
            lineTo(w * 0.47f, h * 0.65f)
          }
        drawPath(z, Color.White, style = stroke)
        // Crisp '5'
        val five =
          Path().apply {
            moveTo(w * 0.77f, h * 0.35f)
            lineTo(w * 0.56f, h * 0.35f)
            lineTo(w * 0.56f, h * 0.49f)
            lineTo(w * 0.72f, h * 0.49f)
            quadraticTo(w * 0.80f, h * 0.50f, w * 0.78f, h * 0.58f)
            quadraticTo(w * 0.77f, h * 0.66f, w * 0.56f, h * 0.65f)
          }
        drawPath(five, Color.White, style = stroke)
      }

      "sunnxt" -> {
        // Golden glowing sun disc + tapered sunburst corona rays
        val sunCenter = center
        drawCircle(
          brush = Brush.radialGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFD54F))),
          radius = w * 0.24f,
          center = sunCenter,
        )
        for (i in 0 until 8) {
          val angle = i * (PI / 4.0)
          val start =
            Offset(
              sunCenter.x + (w * 0.31f * cos(angle)).toFloat(),
              sunCenter.y + (h * 0.31f * sin(angle)).toFloat(),
            )
          val end =
            Offset(
              sunCenter.x + (w * 0.46f * cos(angle)).toFloat(),
              sunCenter.y + (h * 0.46f * sin(angle)).toFloat(),
            )
          drawLine(
            color = Color(0xFFFFF176),
            start = start,
            end = end,
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
          )
        }
      }

      else -> {
        // JioTV: Crisp white live broadcast waves + screen bezel
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.14f, h * 0.24f),
          size = Size(w * 0.72f, h * 0.54f),
          cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
          style = Stroke(width = w * 0.08f),
        )
        drawCircle(
          color = Color.White,
          radius = w * 0.08f,
          center = Offset(w * 0.50f, h * 0.58f),
        )
        drawArc(
          color = Color.White,
          startAngle = 215f,
          sweepAngle = 110f,
          useCenter = false,
          topLeft = Offset(w * 0.32f, h * 0.38f),
          size = Size(w * 0.36f, h * 0.36f),
          style = Stroke(width = w * 0.07f, cap = StrokeCap.Round),
        )
        drawLine(
          color = Color.White,
          start = Offset(w * 0.36f, h * 0.86f),
          end = Offset(w * 0.64f, h * 0.86f),
          strokeWidth = w * 0.08f,
          cap = StrokeCap.Round,
        )
      }
    }
  }
}
