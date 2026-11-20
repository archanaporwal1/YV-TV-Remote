package com.example.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remote.data.CustomOttIconStyle
import com.example.remote.data.OttBrandResolver
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
 * Official Android TV app launcher-style OTT icon tile.
 * Automatically resolves built-in, catalog, and custom shortcuts to their authentic brand artwork.
 */
@Composable
fun OttAppIconTile(
  shortcut: TvAppShortcut,
  modifier: Modifier = Modifier,
) {
  val resolvedBrand =
    remember(shortcut.id, shortcut.appLink, shortcut.packageNames, shortcut.name) {
      OttBrandResolver.resolveBrandKey(shortcut)
    }

  val bgBrush =
    when (resolvedBrand) {
      "youtube" -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF3F4F6)))
      "netflix" -> Brush.verticalGradient(listOf(Color(0xFF141414), Color(0xFF050505)))
      "prime_video" -> Brush.linearGradient(listOf(Color(0xFF005AE0), Color(0xFF00A8E1)))
      "jiocinema" -> Brush.linearGradient(listOf(Color(0xFFE6008F), Color(0xFFB30070)))
      "hotstar" ->
        Brush.linearGradient(
          colors = listOf(Color(0xFF09207A), Color(0xFF4C1BB8), Color(0xFFE1007A)),
        )
      "sonyliv" -> Brush.verticalGradient(listOf(Color(0xFF121218), Color(0xFF050508)))
      "zee5" -> Brush.verticalGradient(listOf(Color(0xFF100C18), Color(0xFF050408)))
      "sunnxt" ->
        Brush.verticalGradient(
          colors = listOf(Color(0xFFC40018), Color(0xFFFF4D00), Color(0xFFFF8F00)),
        )
      "jiotv" -> Brush.linearGradient(listOf(Color(0xFFE60023), Color(0xFFC2001D)))
      else -> {
        resolvedBrand?.let { catalogBrandBackground(it) }
          ?: run {
            val top = lerp(shortcut.accentColor, Color.White, 0.16f)
            val bottom = lerp(shortcut.accentColor, Color.Black, 0.36f)
            Brush.linearGradient(listOf(top, bottom))
          }
      }
    }

  val isLightTile =
    resolvedBrand == "youtube" || (resolvedBrand != null && isLightCatalogTile(resolvedBrand))
  val borderColor =
    if (isLightTile) Color.Black.copy(alpha = 0.14f)
    else Color.White.copy(alpha = 0.22f)

  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val minSide = minOf(maxWidth, maxHeight)
    val tilePx = with(density) { minSide.toPx() }
    val cornerRadius = (minSide * 0.24f).coerceIn(6.dp, 14.dp)
    val innerPad = (minSide * 0.08f).coerceIn(2.dp, 5.dp)

    Surface(
      shape = RoundedCornerShape(cornerRadius),
      color = Color.Transparent,
      border = BorderStroke(1.dp, borderColor),
      shadowElevation = 4.dp,
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(bgBrush),
        contentAlignment = Alignment.Center,
      ) {
        // Subtle glossy Android TV launcher top sheen
        if (!isLightTile) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = 0.14f),
                    0.42f to Color.Transparent,
                  )
                )
          )
        }

        Box(
          modifier = Modifier.fillMaxSize().padding(innerPad),
          contentAlignment = Alignment.Center,
        ) {
          when {
            resolvedBrand != null && resolvedBrand in OttBrandResolver.builtInDefaultBrandKeys -> {
              DefaultOttBrandMark(
                brandKey = resolvedBrand,
                tilePx = tilePx,
                modifier = Modifier.fillMaxSize(),
              )
            }
            resolvedBrand != null -> {
              CatalogOttBrandMark(
                brandKey = resolvedBrand,
                tilePx = tilePx,
                modifier = Modifier.fillMaxSize(),
              )
            }
            else -> {
              CustomOttArtwork(
                shortcut = shortcut,
                tilePx = tilePx,
                modifier = Modifier.fillMaxSize(),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CustomOttArtwork(
  shortcut: TvAppShortcut,
  tilePx: Float,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val fgColor =
    if (shortcut.accentColor.luminance() > 0.65f) Color(0xFF111318) else Color.White

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    when (shortcut.iconStyle) {
      CustomOttIconStyle.BADGE -> {
        val text = shortcut.badge.take(3).ifEmpty { "TV" }
        val frac = if (text.length >= 3) 0.27f else 0.36f
        Text(
          text = text,
          style = crispBrandTextStyle(tileScaledSp(density, tilePx, frac), fgColor, FontWeight.ExtraBold),
          maxLines = 1,
          softWrap = false,
        )
      }
      CustomOttIconStyle.PLAY ->
        Icon(
          imageVector = Icons.Rounded.PlayArrow,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.84f),
        )
      CustomOttIconStyle.TV ->
        Icon(
          imageVector = Icons.Rounded.Tv,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.74f),
        )
      CustomOttIconStyle.MOVIE ->
        Icon(
          imageVector = Icons.Rounded.Movie,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.74f),
        )
      CustomOttIconStyle.SPORTS ->
        Icon(
          imageVector = Icons.Rounded.SportsSoccer,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.74f),
        )
      CustomOttIconStyle.MUSIC ->
        Icon(
          imageVector = Icons.Rounded.MusicNote,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.76f),
        )
      CustomOttIconStyle.LIVE ->
        Icon(
          imageVector = Icons.Rounded.LiveTv,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.fillMaxSize(0.74f),
        )
    }
  }
}

@Composable
private fun DefaultOttBrandMark(
  brandKey: String,
  tilePx: Float,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    when (brandKey) {
      "youtube" -> {
        // Authentic organic YouTube red curved play badge + centered white triangle
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          val ytShape =
            Path().apply {
              moveTo(w * 0.50f, h * 0.16f)
              cubicTo(w * 0.78f, h * 0.16f, w * 0.90f, h * 0.18f, w * 0.94f, h * 0.28f)
              cubicTo(w * 0.97f, h * 0.36f, w * 0.97f, h * 0.64f, w * 0.94f, h * 0.72f)
              cubicTo(w * 0.90f, h * 0.82f, w * 0.78f, h * 0.84f, w * 0.50f, h * 0.84f)
              cubicTo(w * 0.22f, h * 0.84f, w * 0.10f, h * 0.82f, w * 0.06f, h * 0.72f)
              cubicTo(w * 0.03f, h * 0.64f, w * 0.03f, h * 0.36f, w * 0.06f, h * 0.28f)
              cubicTo(w * 0.10f, h * 0.18f, w * 0.22f, h * 0.16f, w * 0.50f, h * 0.16f)
              close()
            }
          drawPath(ytShape, Color(0xFFFF0000))
          val play =
            Path().apply {
              moveTo(w * 0.42f, h * 0.34f)
              lineTo(w * 0.67f, h * 0.50f)
              lineTo(w * 0.42f, h * 0.66f)
              close()
            }
          drawPath(play, Color.White)
        }
      }

      "netflix" -> {
        // Official 3D folded Netflix scarlet 'N' ribbon with shadow depth and subtle bottom arch
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          // Left crimson vertical bar
          val leftBar =
            Path().apply {
              moveTo(w * 0.22f, h * 0.08f)
              lineTo(w * 0.40f, h * 0.08f)
              lineTo(w * 0.40f, h * 0.90f)
              quadraticTo(w * 0.31f, h * 0.91f, w * 0.22f, h * 0.92f)
              close()
            }
          drawPath(leftBar, Color(0xFFB20710))

          // Right crimson vertical bar
          val rightBar =
            Path().apply {
              moveTo(w * 0.60f, h * 0.08f)
              lineTo(w * 0.78f, h * 0.08f)
              lineTo(w * 0.78f, h * 0.92f)
              quadraticTo(w * 0.69f, h * 0.91f, w * 0.60f, h * 0.90f)
              close()
            }
          drawPath(rightBar, Color(0xFFB20710))

          // Fold cast shadow beneath diagonal ribbon
          val shadow =
            Path().apply {
              moveTo(w * 0.22f, h * 0.08f)
              lineTo(w * 0.43f, h * 0.08f)
              lineTo(w * 0.78f, h * 0.92f)
              lineTo(w * 0.57f, h * 0.92f)
              close()
            }
          drawPath(shadow, Color.Black.copy(alpha = 0.42f))

          // Bright Netflix red front diagonal ribbon
          val diag =
            Path().apply {
              moveTo(w * 0.22f, h * 0.08f)
              lineTo(w * 0.40f, h * 0.08f)
              lineTo(w * 0.78f, h * 0.92f)
              quadraticTo(w * 0.69f, h * 0.90f, w * 0.60f, h * 0.89f)
              close()
            }
          drawPath(diag, Color(0xFFE50914))
        }
      }

      "prime_video" -> {
        // Official Prime Blue tile with crisp bold 'prime' wordmark + iconic curved Amazon smile arrow
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = "prime",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.27f), Color.White, FontWeight.ExtraBold, (-0.3).sp),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.offset(y = (-tilePx * 0.06f / density.density).dp),
          )
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val smile =
              Path().apply {
                moveTo(w * 0.16f, h * 0.67f)
                quadraticTo(w * 0.50f, h * 0.90f, w * 0.82f, h * 0.66f)
              }
            drawPath(
              path = smile,
              color = Color.White,
              style = Stroke(width = w * 0.085f, cap = StrokeCap.Round),
            )
            val arrowTip =
              Path().apply {
                moveTo(w * 0.71f, h * 0.62f)
                lineTo(w * 0.85f, h * 0.64f)
                lineTo(w * 0.81f, h * 0.77f)
              }
            drawPath(
              path = arrowTip,
              color = Color.White,
              style = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
          }
        }
      }

      "jiocinema" -> {
        // Official JioCinema vivid magenta tile with solid crisp white circle & centered magenta play mark
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          drawCircle(
            color = Color.White,
            radius = w * 0.40f,
            center = center,
          )
          val play =
            Path().apply {
              moveTo(w * 0.42f, h * 0.31f)
              lineTo(w * 0.70f, h * 0.50f)
              lineTo(w * 0.42f, h * 0.69f)
              close()
            }
          drawPath(play, Color(0xFFD9008D))
        }
      }

      "hotstar" -> {
        // Official 2025/2026 JioHotstar radiant multi-pointed burst star on deep blue -> magenta gradient
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height
          val cx = w * 0.50f
          val cy = h * 0.50f

          // Soft radiant aurora glow behind star
          drawCircle(
            brush =
              Brush.radialGradient(
                colors =
                  listOf(
                    Color(0xFF7DD3FC).copy(alpha = 0.42f),
                    Color(0xFFFF40B5).copy(alpha = 0.24f),
                    Color.Transparent,
                  ),
                center = Offset(cx, cy),
                radius = w * 0.50f,
              ),
            radius = w * 0.50f,
            center = Offset(cx, cy),
          )

          // Secondary 4 diagonal flare points (rotated 45 deg)
          val diagStar = Path()
          val diagOuter = w * 0.30f
          val diagInner = w * 0.10f
          for (i in 0 until 8) {
            val r = if (i % 2 == 0) diagOuter else diagInner
            val angle = -PI / 4.0 + i * (PI / 4.0)
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) diagStar.moveTo(x, y) else diagStar.lineTo(x, y)
          }
          diagStar.close()
          drawPath(
            path = diagStar,
            brush =
              Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.92f), Color(0xFFFFA6DF).copy(alpha = 0.70f)),
                center = Offset(cx, cy),
                radius = diagOuter,
              ),
          )

          // Primary bold luminous 4-point JioHotstar burst star
          val mainStar =
            Path().apply {
              moveTo(cx, cy - h * 0.44f)
              quadraticTo(cx + w * 0.05f, cy - h * 0.05f, cx + w * 0.44f, cy)
              quadraticTo(cx + w * 0.05f, cy + h * 0.05f, cx, cy + h * 0.44f)
              quadraticTo(cx - w * 0.05f, cy + h * 0.05f, cx - w * 0.44f, cy)
              quadraticTo(cx - w * 0.05f, cy - h * 0.05f, cx, cy - h * 0.44f)
              close()
            }
          drawPath(
            path = mainStar,
            brush =
              Brush.radialGradient(
                colors = listOf(Color.White, Color(0xFFE0F2FE), Color(0xFFFFC1E8)),
                center = Offset(cx, cy),
                radius = w * 0.45f,
              ),
          )
        }
      }

      "sonyliv" -> {
        // Official black SonyLIV tile: crisp white 'SONY' header + bold multicolor geometric 'LIV'
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            modifier = Modifier.weight(0.34f).fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
          ) {
            Text(
              text = "SONY",
              style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.16f), Color.White, FontWeight.Bold, 1.0.sp),
              maxLines = 1,
              softWrap = false,
            )
          }
          Canvas(modifier = Modifier.weight(0.66f).fillMaxSize()) {
            val w = size.width
            val h = size.height
            val sw = w * 0.135f
            val topY = h * 0.14f
            val botY = h * 0.84f

            // 'L' in vibrant amber-to-orange
            val lPath =
              Path().apply {
                moveTo(w * 0.14f, topY)
                lineTo(w * 0.14f, botY)
                lineTo(w * 0.37f, botY)
              }
            drawPath(
              path = lPath,
              brush = Brush.verticalGradient(listOf(Color(0xFFFFC107), Color(0xFFFF5722))),
              style = Stroke(width = sw, cap = StrokeCap.Square, join = StrokeJoin.Miter),
            )

            // 'I' in electric cyan-to-violet
            drawLine(
              brush = Brush.verticalGradient(listOf(Color(0xFF00E5FF), Color(0xFF7C3AED))),
              start = Offset(w * 0.49f, topY),
              end = Offset(w * 0.49f, botY),
              strokeWidth = sw,
              cap = StrokeCap.Square,
            )

            // 'V' in emerald-to-magenta
            val vPath =
              Path().apply {
                moveTo(w * 0.62f, topY)
                lineTo(w * 0.76f, botY)
                lineTo(w * 0.90f, topY)
              }
            drawPath(
              path = vPath,
              brush = Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF06B6D4), Color(0xFFEC4899))),
              style = Stroke(width = sw, cap = StrokeCap.Square, join = StrokeJoin.Miter),
            )
          }
        }
      }

      "zee5" -> {
        // Official ZEE5 black tile: concentric multicolor segmented ring arcs + crisp bold 'ZEE5'
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val outerSweep =
              Brush.sweepGradient(
                colors =
                  listOf(
                    Color(0xFFFF007F),
                    Color(0xFF8B5CF6),
                    Color(0xFF00E5FF),
                    Color(0xFFC6FF00),
                    Color(0xFFFF007F),
                  ),
              )
            // Outer broken multicolor ring segments
            drawArc(
              brush = outerSweep,
              startAngle = -145f,
              sweepAngle = 125f,
              useCenter = false,
              topLeft = Offset(w * 0.05f, h * 0.05f),
              size = Size(w * 0.90f, h * 0.90f),
              style = Stroke(width = w * 0.075f, cap = StrokeCap.Round),
            )
            drawArc(
              brush = outerSweep,
              startAngle = 35f,
              sweepAngle = 125f,
              useCenter = false,
              topLeft = Offset(w * 0.05f, h * 0.05f),
              size = Size(w * 0.90f, h * 0.90f),
              style = Stroke(width = w * 0.075f, cap = StrokeCap.Round),
            )
            // Inner concentric companion accent arcs
            drawArc(
              brush = outerSweep,
              startAngle = -120f,
              sweepAngle = 78f,
              useCenter = false,
              topLeft = Offset(w * 0.16f, h * 0.16f),
              size = Size(w * 0.68f, h * 0.68f),
              style = Stroke(width = w * 0.055f, cap = StrokeCap.Round),
            )
            drawArc(
              brush = outerSweep,
              startAngle = 60f,
              sweepAngle = 78f,
              useCenter = false,
              topLeft = Offset(w * 0.16f, h * 0.16f),
              size = Size(w * 0.68f, h * 0.68f),
              style = Stroke(width = w * 0.055f, cap = StrokeCap.Round),
            )
          }
          Text(
            text = "ZEE5",
            style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.24f), Color.White, FontWeight.Black, (-0.3).sp),
            maxLines = 1,
            softWrap = false,
          )
        }
      }

      "sunnxt" -> {
        // Official Sun NXT fiery crimson-to-orange tile with iconic Sun TV sunburst + crisp 'SUN NXT'
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Canvas(modifier = Modifier.weight(0.50f).fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.50f
            val cy = h * 0.86f
            val coreRadius = h * 0.38f
            val rayOuter = h * 0.82f

            // Tapered Sun TV golden rays fanning across upper semicircle
            val rayCount = 9
            for (i in 0 until rayCount) {
              val angleDeg = 188.0 + i * (164.0 / (rayCount - 1))
              val angleRad = angleDeg * PI / 180.0
              val halfSpread = 0.09
              val tipX = cx + (rayOuter * cos(angleRad)).toFloat()
              val tipY = cy + (rayOuter * sin(angleRad)).toFloat()
              val base1X = cx + (coreRadius * 0.85f * cos(angleRad - halfSpread)).toFloat()
              val base1Y = cy + (coreRadius * 0.85f * sin(angleRad - halfSpread)).toFloat()
              val base2X = cx + (coreRadius * 0.85f * cos(angleRad + halfSpread)).toFloat()
              val base2Y = cy + (coreRadius * 0.85f * sin(angleRad + halfSpread)).toFloat()
              val ray =
                Path().apply {
                  moveTo(base1X, base1Y)
                  lineTo(tipX, tipY)
                  lineTo(base2X, base2Y)
                  close()
                }
              drawPath(ray, Color(0xFFFFE55C))
            }
            drawCircle(
              brush = Brush.radialGradient(listOf(Color(0xFFFFFDE7), Color(0xFFFFD54F)), center = Offset(cx, cy), radius = coreRadius),
              radius = coreRadius,
              center = Offset(cx, cy),
            )
          }
          Column(
            modifier = Modifier.weight(0.50f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = "SUN",
              style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.22f), Color.White, FontWeight.Black, 0.5.sp),
              maxLines = 1,
              softWrap = false,
            )
            Text(
              text = "NXT",
              style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.18f), Color(0xFFFFEA70), FontWeight.ExtraBold, 0.8.sp),
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }

      else -> {
        // Official JioTV red tile: bold white circular 'Jio' medallion + crisp 'TV' badge pill
        Box(modifier = Modifier.fillMaxSize()) {
          // Main white Jio circle slightly shifted toward top-left
          Box(
            modifier =
              Modifier
                .align(Alignment.Center)
                .offset(x = (-tilePx * 0.04f / density.density).dp, y = (-tilePx * 0.05f / density.density).dp),
            contentAlignment = Alignment.Center,
          ) {
            Canvas(modifier = Modifier.fillMaxSize(0.78f)) {
              drawCircle(
                color = Color.White,
                radius = size.minDimension * 0.50f,
                center = center,
              )
            }
            Text(
              text = "Jio",
              style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.29f), Color(0xFFE60023), FontWeight.Black, (-0.4).sp),
              maxLines = 1,
              softWrap = false,
            )
          }

          // Crisp dark-crimson/white 'TV' pill at bottom-right
          Surface(
            shape = RoundedCornerShape(percent = 36),
            color = Color(0xFF1A060B),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.85f)),
            modifier = Modifier.align(Alignment.BottomEnd),
          ) {
            Text(
              text = "TV",
              style = crispBrandTextStyle(tileScaledSp(density, tilePx, 0.16f), Color.White, FontWeight.Black, 0.2.sp),
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
            )
          }
        }
      }
    }
  }
}
