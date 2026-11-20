package com.example.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun catalogBrandBackground(brandKey: String): Brush? =
  when (brandKey) {
    // Indian OTT catalog
    "airtel_xstream" -> Brush.linearGradient(listOf(Color(0xFF1A070C), Color(0xFF33060E)))
    "tata_play_binge" -> Brush.linearGradient(listOf(Color(0xFF2C095C), Color(0xFF7B0D6E), Color(0xFFD91E7A)))
    "mx_player" -> Brush.linearGradient(listOf(Color(0xFF0077FF), Color(0xFF003EC7)))
    "aha" -> Brush.linearGradient(listOf(Color(0xFFFF7A00), Color(0xFFFF4D00)))
    "hoichoi" -> Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFFB20710)))
    "fancode" -> Brush.linearGradient(listOf(Color(0xFFFF5A00), Color(0xFFD93600)))
    "lionsgate_play" -> Brush.linearGradient(listOf(Color(0xFF0E1422), Color(0xFF1E2942)))
    "shemaroome" -> Brush.linearGradient(listOf(Color(0xFFFF4738), Color(0xFFD6006B)))
    "eros_now" -> Brush.linearGradient(listOf(Color(0xFF004D45), Color(0xFF00897B)))
    "manoramamax" -> Brush.linearGradient(listOf(Color(0xFF0A2E8A), Color(0xFF8C1443), Color(0xFFD9182B)))
    "etv_win" -> Brush.linearGradient(listOf(Color(0xFF0A2872), Color(0xFF1547B3)))
    "chaupal" -> Brush.linearGradient(listOf(Color(0xFFFFC107), Color(0xFFF59E0B)))
    "stage" -> Brush.linearGradient(listOf(Color(0xFF15151B), Color(0xFF0A0A0E)))
    "jiosaavn" -> Brush.linearGradient(listOf(Color(0xFF2BC5B4), Color(0xFF159B8C)))
    // Global OTT catalog
    "apple_tv" -> Brush.verticalGradient(listOf(Color(0xFF1B1C20), Color(0xFF070709)))
    "spotify" -> Brush.verticalGradient(listOf(Color(0xFF161616), Color(0xFF080808)))
    "twitch" -> Brush.linearGradient(listOf(Color(0xFF9146FF), Color(0xFF6B2AD6)))
    "plex" -> Brush.linearGradient(listOf(Color(0xFF24262C), Color(0xFF121317)))
    "crunchyroll" -> Brush.linearGradient(listOf(Color(0xFFF47521), Color(0xFFDF5600)))
    "discovery_plus" -> Brush.linearGradient(listOf(Color(0xFF001C4D), Color(0xFF0055D4)))
    "max" -> Brush.linearGradient(listOf(Color(0xFF002BE7), Color(0xFF001685)))
    "hulu" -> Brush.verticalGradient(listOf(Color(0xFF0E1115), Color(0xFF06080A)))
    else -> null
  }

internal fun isLightCatalogTile(brandKey: String): Boolean = brandKey == "chaupal"

internal fun tileScaledSp(density: androidx.compose.ui.unit.Density, tilePx: Float, fraction: Float): TextUnit =
  with(density) { (tilePx * fraction).coerceAtLeast(1f).toSp() }

internal fun crispBrandTextStyle(
  fontSize: TextUnit,
  color: Color,
  fontWeight: FontWeight = FontWeight.Black,
  letterSpacing: TextUnit = 0.sp,
): TextStyle =
  TextStyle(
    fontSize = fontSize,
    lineHeight = fontSize,
    color = color,
    fontWeight = fontWeight,
    letterSpacing = letterSpacing,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
  )

/**
 * Official Android TV launcher-style artwork for all Indian and Global catalog OTT apps.
 */
@Composable
internal fun CatalogOttBrandMark(
  brandKey: String,
  tilePx: Float,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    when (brandKey) {
      "airtel_xstream" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Signature Airtel curved crimson ribbon swoosh on top-left
          val wave =
            Path().apply {
              moveTo(w * 0.16f, h * 0.56f)
              cubicTo(w * 0.14f, h * 0.24f, w * 0.46f, h * 0.14f, w * 0.62f, h * 0.26f)
              cubicTo(w * 0.72f, h * 0.34f, w * 0.56f, h * 0.48f, w * 0.40f, h * 0.43f)
              cubicTo(w * 0.30f, h * 0.40f, w * 0.26f, h * 0.52f, w * 0.34f, h * 0.60f)
            }
          drawPath(
            path = wave,
            color = Color(0xFFE40000),
            style = Stroke(width = w * 0.12f, cap = StrokeCap.Round),
          )
          // Crisp bold white Xstream 'X' mark on right
          val xStroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
          drawLine(Color.White, Offset(w * 0.46f, h * 0.44f), Offset(w * 0.84f, h * 0.82f), strokeWidth = xStroke.width, cap = StrokeCap.Round)
          drawLine(Color(0xFFFF3B30), Offset(w * 0.84f, h * 0.44f), Offset(w * 0.46f, h * 0.82f), strokeWidth = xStroke.width, cap = StrokeCap.Round)
        }
      }

      "tata_play_binge" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Canvas(modifier = Modifier.fillMaxSize(0.46f)) {
            val w = size.width
            val h = size.height
            val play =
              Path().apply {
                moveTo(w * 0.22f, h * 0.12f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.22f, h * 0.88f)
                close()
              }
            drawPath(
              path = play,
              brush = Brush.linearGradient(listOf(Color(0xFFFFC107), Color(0xFFFF4081))),
            )
          }
          Text(
            text = "BINGE",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.21f), Color.White, FontWeight.ExtraBold, 0.4.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "mx_player" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Official MX Player crisp white circle with bold MX Blue play triangle
          drawCircle(
            color = Color.White,
            radius = w * 0.41f,
            center = center,
          )
          val play =
            Path().apply {
              moveTo(w * 0.40f, h * 0.30f)
              lineTo(w * 0.70f, h * 0.50f)
              lineTo(w * 0.40f, h * 0.70f)
              close()
            }
          drawPath(play, Color(0xFF005CE6))
        }
      }

      "aha" -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Playful aha smile arc beneath wordmark
            val smile =
              Path().apply {
                moveTo(w * 0.24f, h * 0.72f)
                quadraticTo(w * 0.50f, h * 0.90f, w * 0.76f, h * 0.72f)
              }
            drawPath(
              path = smile,
              color = Color.White.copy(alpha = 0.92f),
              style = Stroke(width = w * 0.075f, cap = StrokeCap.Round),
            )
          }
          Text(
            text = "aha",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.34f), Color.White, FontWeight.Black, (-0.4).sp),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.offset(y = (-1).dp),
          )
        }
      }

      "hoichoi" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "hoichoi",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.19f), Color.White, FontWeight.ExtraBold, (-0.3).sp),
            maxLines = 1,
            softWrap = false,
          )
          Canvas(modifier = Modifier.fillMaxSize(0.32f)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
              color = Color(0xFFFFD54F),
              topLeft = Offset(w * 0.20f, h * 0.35f),
              size = Size(w * 0.60f, h * 0.30f),
              cornerRadius = CornerRadius(h * 0.15f, h * 0.15f),
            )
          }
        }
      }

      "fancode" -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Dynamic angular stadium shield outline
            val shield =
              Path().apply {
                moveTo(w * 0.16f, h * 0.18f)
                lineTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.76f, h * 0.82f)
                lineTo(w * 0.10f, h * 0.82f)
                close()
              }
            drawPath(
              path = shield,
              color = Color.White.copy(alpha = 0.18f),
            )
          }
          Text(
            text = "FC",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.42f), Color.White, FontWeight.Black, (-0.5).sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "lionsgate_play" -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Architectural golden light-beams gate frame
            val goldBrush = Brush.verticalGradient(listOf(Color(0xFFFFE082), Color(0xFFF5B82E), Color(0xFFB87B14)))
            drawRoundRect(
              brush = goldBrush,
              topLeft = Offset(w * 0.12f, h * 0.12f),
              size = Size(w * 0.76f, h * 0.76f),
              cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
              style = Stroke(width = w * 0.07f),
            )
          }
          Text(
            text = "LP",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.36f), Color(0xFFFFD54F), FontWeight.Black, 0.5.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "shemaroome" -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
              color = Color(0xFFFFD54F),
              radius = w * 0.10f,
              center = Offset(w * 0.76f, h * 0.24f),
            )
          }
          Text(
            text = "S",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.56f), Color.White, FontWeight.Black),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "eros_now" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Canvas(modifier = Modifier.fillMaxSize(0.46f)) {
            val w = size.width
            val h = size.height
            val play =
              Path().apply {
                moveTo(w * 0.24f, h * 0.12f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.88f)
                close()
              }
            drawPath(play, Color(0xFF64FFDA))
          }
          Text(
            text = "EROS",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.21f), Color.White, FontWeight.ExtraBold, 0.6.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "manoramamax" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Canvas(modifier = Modifier.fillMaxSize(0.38f)) {
            val w = size.width
            val h = size.height
            val mWave =
              Path().apply {
                moveTo(w * 0.10f, h * 0.82f)
                lineTo(w * 0.28f, h * 0.18f)
                lineTo(w * 0.50f, h * 0.65f)
                lineTo(w * 0.72f, h * 0.18f)
                lineTo(w * 0.90f, h * 0.82f)
              }
            drawPath(
              path = mWave,
              color = Color(0xFFFFD54F),
              style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
          }
          Text(
            text = "MAX",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.26f), Color.White, FontWeight.Black, 0.3.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "etv_win" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "ETV",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.19f), Color(0xFFFFD000), FontWeight.Black, 0.5.sp),
            maxLines = 1,
            softWrap = false,
          )
          Text(
            text = "WIN",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.28f), Color.White, FontWeight.Black, 0.4.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "chaupal" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Bold black 'C' arc with centered play triangle on marigold yellow
          drawArc(
            color = Color(0xFF111111),
            startAngle = 42f,
            sweepAngle = 276f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = Size(w * 0.68f, h * 0.68f),
            style = Stroke(width = w * 0.15f, cap = StrokeCap.Round),
          )
          val play =
            Path().apply {
              moveTo(w * 0.44f, h * 0.34f)
              lineTo(w * 0.72f, h * 0.50f)
              lineTo(w * 0.44f, h * 0.66f)
              close()
            }
          drawPath(play, Color(0xFF111111))
        }
      }

      "stage" -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Canvas(modifier = Modifier.fillMaxSize(0.26f)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
              color = Color(0xFFE11D48),
              topLeft = Offset(w * 0.08f, h * 0.20f),
              size = Size(w * 0.84f, h * 0.55f),
              cornerRadius = CornerRadius(h * 0.25f, h * 0.25f),
            )
          }
          Text(
            text = "STAGE",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.20f), Color.White, FontWeight.Black, 0.5.sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "jiosaavn" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Iconic JioSaavn concentric musical sound wave ring + 'S' flow
          drawCircle(
            color = Color.White.copy(alpha = 0.25f),
            radius = w * 0.42f,
            center = center,
          )
          drawCircle(
            color = Color.White,
            radius = w * 0.36f,
            center = center,
            style = Stroke(width = w * 0.08f),
          )
          val waveS =
            Path().apply {
              moveTo(w * 0.62f, h * 0.30f)
              cubicTo(w * 0.36f, h * 0.26f, w * 0.32f, h * 0.46f, w * 0.50f, h * 0.50f)
              cubicTo(w * 0.68f, h * 0.54f, w * 0.64f, h * 0.74f, w * 0.38f, h * 0.70f)
            }
          drawPath(
            path = waveS,
            color = Color.White,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
          )
        }
      }

      // Global OTT Platforms
      "apple_tv" -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "tv",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.38f), Color.White, FontWeight.Bold, (-0.5).sp),
            maxLines = 1,
            softWrap = false,
          )
          Text(
            text = "+",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.30f), Color.White.copy(alpha = 0.92f), FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "spotify" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Official Spotify Green circle with 3 tilted dark curved sound waves
          drawCircle(
            color = Color(0xFF1DB954),
            radius = w * 0.43f,
            center = center,
          )
          val dark = Color(0xFF0A0A0A)
          val topWave =
            Path().apply {
              moveTo(w * 0.26f, h * 0.38f)
              quadraticTo(w * 0.51f, h * 0.30f, w * 0.76f, h * 0.41f)
            }
          drawPath(topWave, dark, style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))

          val midWave =
            Path().apply {
              moveTo(w * 0.30f, h * 0.52f)
              quadraticTo(w * 0.51f, h * 0.46f, w * 0.71f, h * 0.55f)
            }
          drawPath(midWave, dark, style = Stroke(width = w * 0.072f, cap = StrokeCap.Round))

          val botWave =
            Path().apply {
              moveTo(w * 0.35f, h * 0.65f)
              quadraticTo(w * 0.51f, h * 0.60f, w * 0.66f, h * 0.67f)
            }
          drawPath(botWave, dark, style = Stroke(width = w * 0.060f, cap = StrokeCap.Round))
        }
      }

      "twitch" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Iconic white Twitch Glitch speech bubble
          val bubble =
            Path().apply {
              moveTo(w * 0.20f, h * 0.14f)
              lineTo(w * 0.84f, h * 0.14f)
              lineTo(w * 0.84f, h * 0.58f)
              lineTo(w * 0.68f, h * 0.74f)
              lineTo(w * 0.52f, h * 0.74f)
              lineTo(w * 0.38f, h * 0.88f)
              lineTo(w * 0.38f, h * 0.74f)
              lineTo(w * 0.20f, h * 0.74f)
              close()
            }
          drawPath(bubble, Color.White)
          // Signature purple vertical eyes
          val eyeColor = Color(0xFF772CE8)
          drawRoundRect(
            color = eyeColor,
            topLeft = Offset(w * 0.44f, h * 0.30f),
            size = Size(w * 0.09f, h * 0.24f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
          )
          drawRoundRect(
            color = eyeColor,
            topLeft = Offset(w * 0.63f, h * 0.30f),
            size = Size(w * 0.09f, h * 0.24f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
          )
        }
      }

      "plex" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Official golden-amber Plex '>' chevron
          val chevron =
            Path().apply {
              moveTo(w * 0.28f, h * 0.16f)
              lineTo(w * 0.52f, h * 0.16f)
              lineTo(w * 0.78f, h * 0.50f)
              lineTo(w * 0.52f, h * 0.84f)
              lineTo(w * 0.28f, h * 0.84f)
              lineTo(w * 0.54f, h * 0.50f)
              close()
            }
          drawPath(
            path = chevron,
            brush = Brush.verticalGradient(listOf(Color(0xFFFFC107), Color(0xFFE5A00D), Color(0xFFCC7B19))),
          )
        }
      }

      "crunchyroll" -> {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Official Crunchyroll white concentric crescent-and-orb eye mark
          drawArc(
            color = Color.White,
            startAngle = 215f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.14f),
            size = Size(w * 0.72f, h * 0.72f),
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
          )
          drawCircle(
            color = Color.White,
            radius = w * 0.23f,
            center = Offset(w * 0.56f, h * 0.54f),
          )
          drawCircle(
            color = Color(0xFFF47521),
            radius = w * 0.09f,
            center = Offset(w * 0.64f, h * 0.45f),
          )
        }
      }

      "discovery_plus" -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Canvas(modifier = Modifier.fillMaxSize(0.28f)) {
            drawCircle(
              brush =
                Brush.linearGradient(
                  listOf(Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFFFF2A85)),
                ),
              radius = size.minDimension * 0.46f,
              center = center,
            )
          }
          Text(
            text = "d+",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.34f), Color.White, FontWeight.Black, (-0.4).sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "max" -> {
        Text(
          text = "max",
          style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.31f), Color.White, FontWeight.Black, (-0.5).sp),
          maxLines = 1,
          softWrap = false,
        )
      }

      "hulu" -> {
        Text(
          text = "hulu",
          style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.28f), Color(0xFF1CE783), FontWeight.Black, (-0.4).sp),
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}
