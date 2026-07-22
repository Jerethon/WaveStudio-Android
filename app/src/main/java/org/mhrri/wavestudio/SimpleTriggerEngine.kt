package org.mhrri.wavestudio

import kotlin.math.*

/**
 * Trigger engine — stabilises repetitive waveforms on screen.
 *
 * 5-step pipeline (driven at ~30 FPS):
 * 1. Signal conditioning: conditioned mode → 156 Hz high-shelf (-40 dB) + 800 Hz 4th-order lowpass
 * 2. Zero-crossing: dual-threshold hysteresis (Schmitt-trigger), holdoff 1 ms, fallback to simple scan
 * 3. Cross-correlation: slope-finder template correlation, peak normalized as confidence
 * 4. Hysteresis lock/bypass: dual-threshold (lock > 0.20, bypass < 0.08) with state memory
 * 5. Edge refinement: ±16 sample nearest-zero-crossing search
 */
internal class SimpleTriggerEngine(
    private val windowSize: Int = 512,
) {
    enum class Mode { OFF, RISING, FALLING }

    /** Whether to apply internal conditioning filters before zero-crossing detection. */
    enum class SourceMode {
        CONDITIONED,
        OUTPUT,
    }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val preTriggerRatio: Float = 0.20f,
        val sourceMode: SourceMode = SourceMode.OUTPUT,
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

    // biquad coefficient cache
    private var cachedSampleRate = -1f
    private var hsB0 = 0f;
    private var hsB1 = 0f;
    private var hsB2 = 0f;
    private var hsA1 = 0f;
    private var hsA2 = 0f
    private var lp1B0 = 0f;
    private var lp1B1 = 0f;
    private var lp1B2 = 0f;
    private var lp1A1 = 0f;
    private var lp1A2 = 0f
    private var lp2B0 = 0f;
    private var lp2B1 = 0f;
    private var lp2B2 = 0f;
    private var lp2A1 = 0f;
    private var lp2A2 = 0f
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

        // ── 1. Signal conditioning ─────────────────────────────────
        val workSignal = if (config.sourceMode == SourceMode.CONDITIONED) {
            applyConditioningFilters(signal, config.sampleRateHz)
        } else {
            signal
        }

        // ── 2. Threshold + hysteresis crossing detection ───────────
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

        // ── 3. Cross-correlation with slope-finder template ──────
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

        // ── 4. Hysteresis lock/bypass ─────────────────────────────
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

        // ── 5. Edge refinement ────────────────────────────────────
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
    // STEP 1: SIGNAL CONDITIONING
    // ═══════════════════════════════════════════════════════════════

    private fun ensureBiquadCoeffs(sampleRateHz: Float) {
        if (cachedSampleRate == sampleRateHz) return
        cachedSampleRate = sampleRateHz

        // 156 Hz high-shelf, -40 dB, slope 0.7
        val hs = designHighShelf(sampleRateHz, 156f, 0.7f, -40f)
        hsB0 = hs[0]; hsB1 = hs[1]; hsB2 = hs[2]; hsA1 = hs[3]; hsA2 = hs[4]

        // 800 Hz 4th-order Butterworth = two cascaded biquads
        // Butterworth Qs: 0.5412, 1.3066
        val l1 = designLowPass(sampleRateHz, 800f, 0.5412f)
        lp1B0 = l1[0]; lp1B1 = l1[1]; lp1B2 = l1[2]; lp1A1 = l1[3]; lp1A2 = l1[4]
        val l2 = designLowPass(sampleRateHz, 800f, 1.3066f)
        lp2B0 = l2[0]; lp2B1 = l2[1]; lp2B2 = l2[2]; lp2A1 = l2[3]; lp2A2 = l2[4]
    }

    /** Two-stage conditioning: 156 Hz high-shelf (-40 dB) → 800 Hz 4th-order lowpass. */
    private fun applyConditioningFilters(signal: FloatArray, sampleRateHz: Float): FloatArray {
        ensureBiquadCoeffs(sampleRateHz)
        val n = signal.size
        val out = FloatArray(n)
        // biquad state
        var hsX1 = 0f;
        var hsX2 = 0f;
        var hsY1 = 0f;
        var hsY2 = 0f
        var lp1X1 = 0f;
        var lp1X2 = 0f;
        var lp1Y1 = 0f;
        var lp1Y2 = 0f
        var lp2X1 = 0f;
        var lp2X2 = 0f;
        var lp2Y1 = 0f;
        var lp2Y2 = 0f

        for (i in 0 until n) {
            // high-shelf
            val x = signal[i]
            val yHs = hsB0 * x + hsB1 * hsX1 + hsB2 * hsX2 - hsA1 * hsY1 - hsA2 * hsY2
            hsX2 = hsX1; hsX1 = x; hsY2 = hsY1; hsY1 = yHs
            // lowpass stage 1
            val yL1 = lp1B0 * yHs + lp1B1 * lp1X1 + lp1B2 * lp1X2 - lp1A1 * lp1Y1 - lp1A2 * lp1Y2
            lp1X2 = lp1X1; lp1X1 = yHs; lp1Y2 = lp1Y1; lp1Y1 = yL1
            // lowpass stage 2
            val yL2 = lp2B0 * yL1 + lp2B1 * lp2X1 + lp2B2 * lp2X2 - lp2A1 * lp2Y1 - lp2A2 * lp2Y2
            lp2X2 = lp2X1; lp2X1 = yL1; lp2Y2 = lp2Y1; lp2Y1 = yL2
            out[i] = yL2
        }
        return out
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

    // ═══════════════════════════════════════════════════════════════
    // BIQUAD FILTER DESIGN (RBJ Audio EQ Cookbook)
    // ═══════════════════════════════════════════════════════════════

    /** Returns [b0, b1, b2, a1, a2] normalised by a0. */
    private fun designLowPass(sampleRateHz: Float, cutoffHz: Float, q: Float): FloatArray {
        val w0 = 2f * PI.toFloat() * cutoffHz / sampleRateHz
        val cosW0 = cos(w0);
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)
        val b0 = (1f - cosW0) / 2f
        val b1 = 1f - cosW0
        val b2 = (1f - cosW0) / 2f
        val a0 = 1f + alpha
        val a1 = -2f * cosW0
        val a2 = 1f - alpha
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** High-shelf with gainDb (negative = cut), slope parameter S. */
    private fun designHighShelf(sampleRateHz: Float, centerHz: Float, slope: Float, gainDb: Float): FloatArray {
        val w0 = 2f * PI.toFloat() * centerHz / sampleRateHz
        val cosW0 = cos(w0);
        val sinW0 = sin(w0)
        val a = 10f.pow(gainDb / 40f)
        val s = slope.coerceIn(0.1f, 2f)
        val alphaTerm = max((a + 1f / a) * (1f / s - 1f) + 2f, 0f)
        val alpha = sinW0 / 2f * sqrt(alphaTerm)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha
        val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
        val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
        val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
        val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
        val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
