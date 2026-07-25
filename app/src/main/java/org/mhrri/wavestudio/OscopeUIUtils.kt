package org.mhrri.wavestudio

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.pow

// ===== Slider mapping helpers (top-level) =====
fun sliderToHz(v01: Float, minHz: Float, maxHz: Float): Float {
    val v = v01.coerceIn(0f, 1f)
    val logMin = ln(minHz)
    val logMax = ln(maxHz)
    val logHz = logMin + (logMax - logMin) * v
    return exp(logHz)
}

fun hzToSlider(hz: Float, minHz: Float, maxHz: Float): Float {
    val h = hz.coerceIn(minHz, maxHz)
    val logMin = ln(minHz)
    val logMax = ln(maxHz)
    return ((ln(h) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
}

/**
 * 混合映射：linearWeight 越大越线性。
 * - 0 -> 纯对数
 * - 1 -> 纯线性
 */
fun sliderToHzBlend(v01: Float, minHz: Float, maxHz: Float, linearWeight: Float): Float {
    val w = linearWeight.coerceIn(0f, 1f)
    val v = v01.coerceIn(0f, 1f)
    val hzLog = sliderToHz(v, minHz, maxHz)
    val hzLin = minHz + (maxHz - minHz) * v
    return (hzLog * (1f - w) + hzLin * w).coerceIn(minHz, maxHz)
}

fun hzToSliderBlend(hz: Float, minHz: Float, maxHz: Float, linearWeight: Float): Float {
    val w = linearWeight.coerceIn(0f, 1f)
    val h = hz.coerceIn(minHz, maxHz)
    val vLog = hzToSlider(h, minHz, maxHz)
    val vLin = ((h - minHz) / (maxHz - minHz)).coerceIn(0f, 1f)
    return (vLog * (1f - w) + vLin * w).coerceIn(0f, 1f)
}

@Composable
fun rememberDisplayLowPass(
    target: Float,
    resetKey: Any? = Unit,
    alpha: Float = 0.18f,
    snapThreshold: Float = 0.01f,
): Float {
    val targetState = rememberUpdatedState(target)
    val smoothed by produceState(initialValue = target, resetKey, alpha, snapThreshold) {
        value = targetState.value
        snapshotFlow { targetState.value }.collectLatest { newTarget ->
            if (!newTarget.isFinite()) {
                value = newTarget
                return@collectLatest
            }

            var current = value.takeIf { it.isFinite() } ?: newTarget
            val safeAlpha = alpha.coerceIn(0.01f, 0.9f)
            val safeThreshold = snapThreshold.coerceAtLeast(1e-6f)

            while (true) {
                val delta = newTarget - current
                if (abs(delta) <= safeThreshold) {
                    current = newTarget
                    value = current
                    break
                }
                current += delta * safeAlpha
                value = current
                yield()
            }
        }
    }
    return smoothed
}

fun toEnglishOrdinal(value: Int): String {
    val absValue = abs(value)
    val suffix = if (absValue % 100 in 11..13) {
        "th"
    } else {
        when (absValue % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$value$suffix"
}

// Compute the EQ combined response (in dB) for a list of bands at given freqs.
// Uses RBJ Peaking EQ design (same math as in the audio engine) so the UI graph matches actual filter effect.
fun computeEqResponse(
    bands: List<AudioEngineViewModel.EqBand>,
    freqs: FloatArray,
    lowPassEnabled: Boolean,
    lowPassCutoff: Float,
    lowPassStageCutoffs: List<Float>,
    lowPassOrder: Int,
    highPassEnabled: Boolean,
    highPassCutoff: Float,
    highPassStageCutoffs: List<Float>,
    highPassOrder: Int,
    filterGain: Float,
    sampleRate: Int,
): FloatArray {
    val out = FloatArray(freqs.size) { 0f }

    // Precompute enabled biquad coefficients for each band (PEAK/LOW_SHELF/HIGH_SHELF)
    data class Coef(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)
    val coefs = bands.filter { it.enabled }
        .mapTo(mutableListOf()) { b ->
            val centerHz = b.freqHz.coerceAtLeast(1f)
            val gainDb = b.gainDb.coerceIn(-60f, 60f) // clamp wide just for computation
            val qOrSlope = AudioEngineViewModel.clampEqQForBand(b.type, gainDb, b.q)

            val w0 = (2f * PI.toFloat() * centerHz) / sampleRate.toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val a = 10f.pow(gainDb / 40f)

            // RBJ cookbook formulas
            val coeffs: FloatArray = when (b.type) {
                AudioEngineViewModel.EqBandType.PEAK -> {
                    val alpha = sinW0 / (2f * qOrSlope)
                    val b0 = 1f + alpha * a
                    val b1 = -2f * cosW0
                    val b2 = 1f - alpha * a
                    val a0 = 1f + alpha / a
                    val a1 = -2f * cosW0
                    val a2 = 1f - alpha / a
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }

                AudioEngineViewModel.EqBandType.LOW_SHELF -> {
                    val s = qOrSlope.coerceAtLeast(0.1f)
                    val alpha = (sinW0 / 2f) * sqrt((a + 1f / a) * (1f / s - 1f) + 2f)
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha

                    val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
                    val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                    val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
                    val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
                    val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                    val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }

                AudioEngineViewModel.EqBandType.HIGH_SHELF -> {
                    val s = qOrSlope.coerceAtLeast(0.1f)
                    val alpha = (sinW0 / 2f) * sqrt((a + 1f / a) * (1f / s - 1f) + 2f)
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha

                    val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
                    val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                    val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
                    val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
                    val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                    val a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha
                    floatArrayOf(b0, b1, b2, a0, a1, a2)
                }
            }

            val b0 = coeffs[0]
            val b1 = coeffs[1]
            val b2 = coeffs[2]
            val a0 = coeffs[3]
            val a1 = coeffs[4]
            val a2 = coeffs[5]

            Coef(b0 = b0 / a0, b1 = b1 / a0, b2 = b2 / a0, a1 = a1 / a0, a2 = a2 / a0)
        }

    val nyquist = sampleRate / 2f - 1f
    if (lowPassEnabled) {
        repeat(lowPassOrder.coerceIn(1, 8)) { stage ->
            val cutoff = lowPassStageCutoffs.getOrElse(stage) { lowPassCutoff }
                .coerceIn(5f, nyquist)
            val dt = 1f / sampleRate
            val rc = 1f / (2f * PI.toFloat() * cutoff)
            val alpha = (dt / (rc + dt)).coerceIn(0f, 1f)
            coefs += Coef(alpha, 0f, 0f, alpha - 1f, 0f)
        }
    }
    if (highPassEnabled) {
        repeat(highPassOrder.coerceIn(1, 8)) { stage ->
            val cutoff = highPassStageCutoffs.getOrElse(stage) { highPassCutoff }
                .coerceIn(5f, nyquist)
            val dt = 1f / sampleRate
            val rc = 1f / (2f * PI.toFloat() * cutoff)
            val alpha = (rc / (rc + dt)).coerceIn(0f, 1f)
            coefs += Coef(alpha, -alpha, 0f, -alpha, 0f)
        }
    }

    for (i in freqs.indices) {
        val f = freqs[i]
        val w = 2f * PI.toFloat() * f / sampleRate.toFloat()
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2f * w)
        val s2 = sin(2f * w)

        var magSquared = 1f
        for (coef in coefs) {
            // Numerator: b0 + b1 e^{-jω} + b2 e^{-j2ω}
            val realNum = coef.b0 + coef.b1 * c1 + coef.b2 * c2
            val imagNum = -coef.b1 * s1 - coef.b2 * s2
            // Denominator: 1 + a1 e^{-jω} + a2 e^{-j2ω}
            val realDen = 1f + coef.a1 * c1 + coef.a2 * c2
            val imagDen = -coef.a1 * s1 - coef.a2 * s2

            val numSq = realNum * realNum + imagNum * imagNum
            val denSq = realDen * realDen + imagDen * imagDen
            val hSq = if (denSq <= 0f) numSq else numSq / denSq
            magSquared *= hSq
        }

        val mag = sqrt(magSquared.toDouble()).toFloat().coerceAtLeast(1e-12f)
        val db = 20f * log10(mag)
        val gainDb = 20f * log10(filterGain.coerceAtLeast(1e-6f))
        out[i] = (db + gainDb).coerceIn(-120f, 120f)
    }

    return out
}
