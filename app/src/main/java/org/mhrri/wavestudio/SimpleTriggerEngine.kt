package org.mhrri.wavestudio

import kotlin.math.*

/**
 * Trigger engine — stabilises repetitive waveforms on screen.
 *
 * 4-step pipeline (driven at ~30 FPS):
 * 1. Zero-crossing: dual-threshold hysteresis (Schmitt-trigger), holdoff 1 ms, fallback to simple scan
 * 2. Cross-correlation: slope-finder template correlation, peak normalized as confidence
 * 3. Hysteresis lock/bypass: dual-threshold (lock > 0.20, bypass < 0.08) with state memory
 * 4. Edge refinement: ±16 sample nearest-zero-crossing search
 */
internal class SimpleTriggerEngine(
    private val windowSize: Int = 512,
) {
    enum class Mode { OFF, RISING, FALLING }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val preTriggerRatio: Float = 0.20f,
        val globalBase: Long = 0L,
        val triggerThreshold: Float = 0.02f,
        val holdoffMs: Float = 1f,
    )

    data class Result(
        val anchorIndex: Int,
        val periodSamples: Int,
        val confidence: Float,
        val locked: Boolean,
        val mode: Mode,
        val freqHz: Float,
    )

    // ── Correlation constants ──────────────────────────────────
    private val kernelSize = 256
    private val slopeWidth = 32f               // Gaussian std for slope_finder
    private val lockThreshold = 0.20f           // peak > this → lock
    private val bypassThreshold = 0.08f         // peak < this → bypass (free-run)

    // ── Zero-crossing constants (shared with step 2) ────────────
    private val hysteresisRatio = 0.18f
    private val hysteresisFloorVal = 0.002f
    private val rmsHysteresisRatioVal = 0.06f

    // ── State ─────────────────────────────────────────────────────
    private var lastTriggerGlobalIdx = Long.MIN_VALUE
    private var lastLockedAnchor = -1           // fallback for hysteresis
    private var correlationLocked = false       // hysteresis between lock/bypass
    private var pendingLocalAnchor = -1
    private var estimatedPeriodSamples = 0f

    // Precomputed slope-finder template (built on first use)
    private var slopeFinder: FloatArray? = null

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    fun process(signal: FloatArray, config: Config): Result {
        val n = signal.size
        if (config.mode == Mode.OFF || n < 32) {
            reset()
            return Result(0, 0, 0f, false, config.mode, 0f)
        }

        // Resolve pending local anchor
        if (pendingLocalAnchor >= 0 && config.globalBase >= 0) {
            lastTriggerGlobalIdx = config.globalBase + pendingLocalAnchor
            pendingLocalAnchor = -1
        }

        val preferredAnchor = max(1, n / 5)   // spec: 20 % from left
        val isRising = config.mode == Mode.RISING

        val workSignal = signal

        // ── 1. Threshold + hysteresis crossing detection ───────────
        val threshold = adaptiveThreshold(workSignal, config.triggerThreshold)
        val rmsVal = rms(workSignal)
        val hysteresis = maxOf(
            hysteresisFloorVal,
            abs(threshold) * hysteresisRatio,
            rmsVal * rmsHysteresisRatioVal
        )
        val crossingsRaw = detectCrossings(
            workSignal, threshold, hysteresis, isRising, config.holdoffMs, config.sampleRateHz
        )
        if (crossingsRaw.isEmpty()) {
            val fb = if (lastTriggerGlobalIdx >= 0) (lastTriggerGlobalIdx - config.globalBase).toInt()
                .coerceIn(0, n - 1) else n / 2
            return Result(fb, estimatedPeriodSamples.roundToInt().coerceAtLeast(1), 0f, false, config.mode, 0f)
        }

        // ── 2. Cross-correlation with slope-finder template ──────
        // Build precomputed slope-finder once
        val sf = slopeFinder ?: buildSlopeFinder(kernelSize, slopeWidth).also { slopeFinder = it }

        // Normalize signal by RMS for amplitude-independent correlation
        val rmsWork = rms(workSignal)
        val normSignal = if (rmsWork > 1e-6f) {
            val scale = 1f / rmsWork
            FloatArray(n) { workSignal[it] * scale }
        } else {
            workSignal
        }

        // Buffer for cross-correlation output: dataSize - kernelSize + 1
        val corrOut = FloatArray(n - kernelSize + 1)
        correlate(normSignal, sf, corrOut)

        // Find peak and its magnitude
        var peakVal = Float.NEGATIVE_INFINITY
        var peakIdx = 0
        for (i in corrOut.indices) {
            if (corrOut[i] > peakVal) { peakVal = corrOut[i]; peakIdx = i }
        }

        // Normalize peak: divide by kernelSize for amplitude-independent measure
        val normPeak = (peakVal / kernelSize).coerceIn(0f, 1f)

        // ── 3. Hysteresis lock/bypass ─────────────────────────────
        val prevLocked = correlationLocked
        correlationLocked = when {
            normPeak > lockThreshold       -> true
            normPeak < bypassThreshold     -> false
            else                           -> prevLocked  // hold previous state
        }

        if (!correlationLocked) {
            // Free-run: no trigger, return center anchor
            val center = n / 2
            lastTriggerGlobalIdx = config.globalBase + center
            return Result(center, 0, normPeak, false, config.mode, 0f)
        }

        // Locked: find zero-crossing nearest to correlation peak
        val corrCenter = peakIdx + kernelSize / 2
        val bestCrossing = crossingsRaw.minByOrNull { abs(it - corrCenter) }
            ?: corrCenter.coerceIn(0, n - 1)

        // ── 4. Edge refinement ────────────────────────────────────
        val finalAnchor = findNearestZeroCross(workSignal, bestCrossing, isRising)

        // ── 6. Update state ───────────────────────────────────────
        val globalChosen = config.globalBase + finalAnchor
        lastTriggerGlobalIdx = globalChosen
        lastLockedAnchor = finalAnchor

        return Result(
            finalAnchor,
            0,      // periodSamples (not estimated in correlation mode)
            normPeak,
            true,   // locked
            config.mode,
            0f      // freqHz (not estimated in correlation mode)
        )
    }

    fun seekAnchorTo(localAnchor: Int) {
        if (localAnchor < 0) return
        pendingLocalAnchor = localAnchor
    }

    fun extractWindow(source: FloatArray, result: Result, targetSize: Int, preTriggerRatio: Float): FloatArray {
        if (source.isEmpty() || result.mode == Mode.OFF) return source.copyOf()
        val tgt = targetSize.coerceAtLeast(64)
        val pre = (tgt * preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt().coerceAtLeast(1)
        val start = (result.anchorIndex - pre).coerceIn(0, max(0, source.size - tgt))
        val end = (start + tgt).coerceAtMost(source.size)
        val win = source.copyOfRange(start, end)
        return if (win.size == tgt) win else win + FloatArray(tgt - win.size) { 0f }
    }

    private fun reset() {
        lastTriggerGlobalIdx = Long.MIN_VALUE
        estimatedPeriodSamples = 0f
        pendingLocalAnchor = -1
        correlationLocked = false
        lastLockedAnchor = -1
    }

    // ═══════════════════════════════════════════════════════════════
    // CORRELATION ENGINE
    // ═══════════════════════════════════════════════════════════════

    /** Build a slope-finder template: left half negative, right half positive,
     *  windowed by a Gaussian to give highest weight to the center (the edge). */
    private fun buildSlopeFinder(size: Int, sigma: Float): FloatArray {
        val half = size / 2
        val out = FloatArray(size)
        // Gaussian: exp(-0.5 * (i - center)^2 / sigma^2), center = (size-1)/2
        val center = (size - 1) / 2f
        for (i in 0 until size) {
            val x = (i - center) / sigma
            val w = kotlin.math.exp(-0.5f * x * x)
            out[i] = if (i < half) -w else w
        }
        return out
    }

    /** Direct O(N*K) cross-correlation of signal with kernel.
     *  output[i] = sum over j of signal[i + j] * kernel[j]
     *  output length = signal.size - kernel.size + 1 */
    private fun correlate(signal: FloatArray, kernel: FloatArray, output: FloatArray) {
        val n = signal.size
        val k = kernel.size
        var i = 0
        while (i < output.size) {
            var sum = 0f
            var j = 0
            while (j < k) {
                sum += signal[i + j] * kernel[j]
                j++
            }
            output[i] = sum
            i++
        }
    }

    /** Scan ±16 samples around [center] for the nearest zero-crossing in
     *  the desired direction. If none found, return center unchanged. */
    private fun findNearestZeroCross(samples: FloatArray, center: Int, rising: Boolean): Int {
        val n = samples.size
        val radius = 16
        for (dist in 0..radius) {
            for (dir in intArrayOf(1, -1)) {
                val idx = center + dist * dir
                if (idx !in 1 until n - 1) continue
                if (rising && samples[idx - 1] < 0f && samples[idx] >= 0f) return idx
                if (!rising && samples[idx - 1] > 0f && samples[idx] <= 0f) return idx
            }
        }
        return center.coerceIn(1, n - 2)
    }

    // ═══════════════════════════════════════════════════════════════
    // STEP 2: ZERO-CROSSING DETECTION (hysteresis + holdoff)
    // ═══════════════════════════════════════════════════════════════

    private fun adaptiveThreshold(samples: FloatArray, userThr: Float): Float {
        return max(abs(userThr), rms(samples) * 0.10f)
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var e = 0f; for (s in samples) e += s * s
        return sqrt(e / samples.size)
    }

    /**
     * Dual-threshold hysteresis (Schmitt-trigger):
     *   rising: arm below lowThr, fire when crossing above highThr
     *   falling: arm above highThr, fire when crossing below lowThr
     * Holdoff suppresses crossings for 1 ms after a fire.
     * Fallback: simple zero-crossing scan if hysteresis finds nothing.
     */
    private fun detectCrossings(
        signal: FloatArray, threshold: Float, hysteresis: Float, isRising: Boolean,
        holdoffMs: Float, sampleRateHz: Float
    ): List<Int> {
        val n = signal.size
        if (n < 3) return emptyList()
        val lowThr = threshold - hysteresis
        val highThr = threshold + hysteresis
        val holdoffS = ((holdoffMs * sampleRateHz) / 1000f).roundToInt().coerceAtLeast(1)

        val result = mutableListOf<Int>()
        var armed = if (isRising) signal[0] <= lowThr else signal[0] >= highThr
        var lastFire = -holdoffS - 1

        for (i in 1 until n - 1) {
            val prev = signal[i - 1];
            val curr = signal[i]
            if (isRising) {
                if (prev <= lowThr) armed = true
                if (armed && prev <= highThr && curr > highThr && (i - lastFire) >= holdoffS) {
                    result.add(i); lastFire = i; armed = false
                }
            } else {
                if (prev >= highThr) armed = true
                if (armed && prev >= lowThr && curr < lowThr && (i - lastFire) >= holdoffS) {
                    result.add(i); lastFire = i; armed = false
                }
            }
        }

        // fallback: simple zero-crossing
        if (result.isEmpty()) {
            for (i in 1 until n) {
                if (isRising && signal[i - 1] < 0f && signal[i] >= 0f) result.add(i)
                else if (!isRising && signal[i - 1] > 0f && signal[i] <= 0f) result.add(i)
            }
        }
        return result
    }

}
