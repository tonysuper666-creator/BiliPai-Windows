// Original source app/src/main/java/com/android/purebilibili/feature/home/components/FloatingDockChrome.kt
// OriginalLF_SHA256 e1c3cd43037a554019356472de67e99208d78675ce8b20a40ad84125caf3999e
package com.android.purebilibili.feature.home.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import kotlin.math.*
import top.yukonga.miuix.kmp.blur.highlight.*
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

private const val LIGHT_REF_X = 0.5f

private const val LIGHT_REF_Y = 0.7f

private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f

internal const val GRAVITY_HIGHLIGHT_QUANTIZE_DEGREES = 3f

internal fun quantizeGravityHighlightDirection(
    gravityX: Float,
    gravityY: Float,
    stepDegrees: Float = GRAVITY_HIGHLIGHT_QUANTIZE_DEGREES,
): Pair<Float, Float> {
    val gMagSq = gravityX * gravityX + gravityY * gravityY
    if (gMagSq <= GRAVITY_DIR_THRESHOLD_SQ) return 0f to -1f
    val invMag = 1f / sqrt(gMagSq)
    val nx = gravityX * invMag
    val ny = gravityY * invMag
    val stepRad = (stepDegrees * PI / 180.0).toFloat()
    if (stepRad <= 0f) return nx to ny
    val angle = atan2(ny, nx)
    val quantized = (angle / stepRad).roundToInt() * stepRad
    return cos(quantized) to sin(quantized)
}

@Composable
internal fun rememberBiliPaiGravityHighlight(
    base: Highlight = iosIndicatorSpecular,
    extraDegrees: Float = 0f,
    width: Dp = base.width,
): State<Highlight> {
    val tiltState = rememberDeviceTilt()
    val quantizedDirection = remember(tiltState) {
        derivedStateOf {
            quantizeGravityHighlightDirection(
                gravityX = tiltState.value.gravityX,
                gravityY = tiltState.value.gravityY,
            )
        }
    }
    return remember(base, extraDegrees, quantizedDirection, width) {
        derivedStateOf {
            val baseStyle = base.style as BloomStroke
            val basePrimary = baseStyle.primaryLight
            val (lx0, ly0) = quantizedDirection.value
            val rad = extraDegrees * PI / 180.0
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val lx = c * lx0 - s * ly0
            val ly = s * lx0 + c * ly0
            base.copy(
                width = width,
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + lx,
                            y = LIGHT_REF_Y + ly,
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * Outer floating dock shell — BiliPai FloatingBottomBar base layer:
 * dropShadow + tuning-driven vibrancy / blur / lens + gravity highlight.
 */
