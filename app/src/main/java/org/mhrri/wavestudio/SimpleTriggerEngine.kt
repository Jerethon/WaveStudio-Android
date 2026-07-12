package org.mhrri.wavestudio

import kotlin.math.*

/**
 * Trigger engine — stabilises repetitive waveforms on screen.
 *
 * 7-step pipeline (driven at ~30 FPS):
 * 1. Signal conditioning: conditioned mode → 156 Hz high-shelf (-40 dB) + 800 Hz 4th-order lowpass
 * 2. Zero-crossing: dual-threshold hysteresis (Schmitt-trigger), holdoff 1 ms, fallback to simple scan
 * 3. Period estimation: dual estimators (raw + 360 Hz lowpass autocorrelation), ±18 % jump clamp, EMA
 * 4. Scoring: prediction(0.72) slope(0.18) symmetry(0.10) fingerprint(0.14)
 *            edge-consistency(0.08) ref-edge(0.20) phase(0.08) − half-cycle-alias(0.22)
 * 5. Phase stickiness: confidence < 0.46 or gap < 0.055 → latch to predicted position
 * 6. Edge refinement (conditioned mode): offset EMA, ±18 sample search, deadband
 * 7. Window extraction: trigger point at 20 % from left
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

    // ── Constants ─────────────────────────────────────────────────
    private val predictionWeight = 0.72f
    private val slopeWeight = 0.18f
    private val symmetryWeight = 0.10f
    private val fingerprintWeight = 0.14f
    private val edgeConsistencyWeight = 0.08f
    private val refEdgeConsistencyWeight = 0.20f
    private val phaseContinuityWeight = 0.08f
    private val halfCycleAliasPenaltyVal = 0.22f

    private val maxAcceptedPeriodJumpRatio = 0.18f       // spec: ±18 %
    private val maxPredictionErrorRatio = 0.22f           // phase-locked filter ±22 %
    private val predictionNeighborhoodRatio = 0.14f
    private val minConfidenceForAcceptance = 0.46f        // spec: < 0.46
    private val ambiguousScoreMargin = 0.055f             // spec: < 0.055
    private val phaseStickinessMargin = 0.075f
    private val minConfidenceMargin = 0.07f
    private val maxCrossingsToScore = 40
    private val fingerprintSampleCount = 24

    private val hysteresisRatio = 0.18f
    private val hysteresisFloorVal = 0.002f
    private val rmsHysteresisRatioVal = 0.06f
    private val weakSignalRmsFloorVal = 0.006f

    private val edgeConsistencyRadius = 3

    // ── State ─────────────────────────────────────────────────────
    private var lastTriggerGlobalIdx = Long.MIN_VALUE
    private var estimatedPeriodSamples = 0f
    private var phaseErrorRatioEma = 0f
    private var lastTriggerFingerprint: FloatArray? = null
    private var pendingLocalAnchor = -1
    private var periodRefreshCounter = 0
    private var conditionedOffsetEma = 0f

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
    private var lp360B0 = 0f;
    private var lp360B1 = 0f;
    private var lp360B2 = 0f;
    private var lp360A1 = 0f;
    private var lp360A2 = 0f

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

        // ── 3. Dual period estimation (raw + 360 Hz lowpass) ───────
        val weakFloor = max(abs(config.triggerThreshold) * 0.8f, weakSignalRmsFloorVal)
        if (rmsVal >= weakFloor && shouldRefreshPeriod()) {
            val rawP = autocorrelationPeriod(workSignal, config.sampleRateHz)
            val lpP = lowpassAutocorrelationPeriod(workSignal, config.sampleRateHz, 360f)
            val measured = chooseFundamentalCandidate(rawP, lpP, estimatedPeriodSamples)
            if (measured > 0f) {
                estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, measured)
            }
        }
        // fallback: crossing spacing
        if (estimatedPeriodSamples <= 0f && crossingsRaw.size >= 2) {
            val gaps = IntArray(crossingsRaw.size - 1) { crossingsRaw[it + 1] - crossingsRaw[it] }
            gaps.sort()
            estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, gaps[gaps.size / 2].toFloat())
        }
        // last resort: window fraction
        if (estimatedPeriodSamples <= 0f) {
            estimatedPeriodSamples = (n * 0.5f)
        }

        // ── 4. Reduce + phase-locked filtering ─────────────────────
        val crossings = reduceCrossings(crossingsRaw, estimatedPeriodSamples, config.globalBase)
        val validCrossings = crossings.filter { it in 0 until n }
        if (validCrossings.isEmpty()) {
            val fb = if (lastTriggerGlobalIdx >= 0) (lastTriggerGlobalIdx - config.globalBase).toInt()
                .coerceIn(0, n - 1) else n / 2
            return Result(fb, estimatedPeriodSamples.roundToInt().coerceAtLeast(1), 0f, false, config.mode, 0f)
        }

        // Single crossing shortcut
        if (validCrossings.size == 1) {
            val chosen = validCrossings[0]
            val globalChosen = config.globalBase + chosen
            updateState(globalChosen, config)
            return Result(
                chosen, estimatedPeriodSamples.roundToInt().coerceAtLeast(1), 0.9f, true, config.mode,
                if (estimatedPeriodSamples > 1) config.sampleRateHz / estimatedPeriodSamples else 0f
            )
        }

        // ── 5. Choose best crossing (scoring + stickiness) ─────────
        val holdoffSamples = ((config.holdoffMs * config.sampleRateHz) / 1000f).roundToInt()
        val chosen = chooseBestCrossing(
            validCrossings, workSignal, estimatedPeriodSamples, preferredAnchor, isRising,
            holdoffSamples, config.globalBase, n
        )

        // ── 6. Edge refinement (conditioned mode only) ─────────────
        val finalAnchor = if (config.sourceMode == SourceMode.CONDITIONED) {
            refineEdge(workSignal, signal, chosen, estimatedPeriodSamples, isRising)
        } else {
            chosen
        }

        // ── 7. Update state ────────────────────────────────────────
        val globalChosen = config.globalBase + finalAnchor
        updateState(globalChosen, config)

        val locked = estimatedPeriodSamples > 0
        val confidence = if (locked) 0.85f else 0.3f
        val freqHz = if (estimatedPeriodSamples > 1) config.sampleRateHz / estimatedPeriodSamples else 0f

        return Result(
            finalAnchor,
            estimatedPeriodSamples.roundToInt().coerceAtLeast(1),
            confidence,
            locked,
            config.mode,
            freqHz
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
        phaseErrorRatioEma = 0f
        lastTriggerFingerprint = null
        pendingLocalAnchor = -1
        conditionedOffsetEma = 0f
    }

    private fun updateState(globalChosen: Long, config: Config) {
        if (lastTriggerGlobalIdx >= 0) {
            val anchorDelta = globalChosen - lastTriggerGlobalIdx
            if (anchorDelta > 1) {
                estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, anchorDelta.toFloat())
            }
            if (estimatedPeriodSamples > 0) {
                val phaseErr = min(abs(anchorDelta - estimatedPeriodSamples) / max(estimatedPeriodSamples, 1f), 1f)
                phaseErrorRatioEma = phaseErrorRatioEma * 0.8f + phaseErr * 0.2f
            }
        }
        lastTriggerGlobalIdx = globalChosen
        periodRefreshCounter = (periodRefreshCounter + 1) % 8
    }

    private fun shouldRefreshPeriod(): Boolean = periodRefreshCounter == 0

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

        // 360 Hz lowpass for dual period estimator
        val l3 = designLowPass(sampleRateHz, 360f, 0.7071f)
        lp360B0 = l3[0]; lp360B1 = l3[1]; lp360B2 = l3[2]; lp360A1 = l3[3]; lp360A2 = l3[4]
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

    /** Apply 360 Hz lowpass biquad for the auxiliary period estimator. */
    private fun applyLowpass360(signal: FloatArray, sampleRateHz: Float): FloatArray {
        ensureBiquadCoeffs(sampleRateHz)
        val n = signal.size
        val out = FloatArray(n)
        var x1 = 0f;
        var x2 = 0f;
        var y1 = 0f;
        var y2 = 0f
        for (i in 0 until n) {
            val x = signal[i]
            val y = lp360B0 * x + lp360B1 * x1 + lp360B2 * x2 - lp360A1 * y1 - lp360A2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            out[i] = y
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
    // STEP 3: DUAL PERIOD ESTIMATION
    // ═══════════════════════════════════════════════════════════════

    /** Autocorrelation period on raw (de-meaned) signal with lag bias toward longer periods. */
    private fun autocorrelationPeriod(signal: FloatArray, sampleRateHz: Float): Float {
        val n = signal.size
        if (n < 64) return 0f
        val mean = signal.sum() / n
        val c = FloatArray(n) { signal[it] - mean }
        val sr = max(sampleRateHz, 1f)
        val minLag = max(4, (sr / 2000f).roundToInt())    // 2 kHz max
        val maxLag = min(n - 4, (sr / 20f).roundToInt())  // 20 Hz min
        if (maxLag <= minLag) return 0f
        var bestLag = -1;
        var bestScore = -1e9f
        val lagSpan = max(maxLag - minLag, 1).toFloat()
        for (lag in minLag..maxLag) {
            var dot = 0f;
            var e0 = 0f;
            var e1 = 0f
            val lim = n - lag; if (lim <= 0) continue
            for (i in 0 until lim) {
                dot += c[i] * c[i + lag]; e0 += c[i] * c[i]; e1 += c[i + lag] * c[i + lag]
            }
            val denom = sqrt(max(e0 * e1, 1e-18f))
            val corr = if (denom > 0f) dot / denom else 0f
            val bias = 0.75f + 0.25f * (lag - minLag) / lagSpan  // prefer longer periods
            val score = corr * bias
            if (score > bestScore) {
                bestScore = score; bestLag = lag
            }
        }
        return if (bestLag > 0 && bestScore > 0.15f) bestLag.toFloat() else 0f
    }

    /** Autocorrelation period on 360 Hz lowpassed signal (auxiliary estimator). */
    private fun lowpassAutocorrelationPeriod(signal: FloatArray, sampleRateHz: Float, cutoffHz: Float): Float {
        val n = signal.size
        if (n < 64) return 0f
        // Use cached 360 Hz lowpass biquad
        ensureBiquadCoeffs(sampleRateHz)
        val lp = FloatArray(n)
        var x1 = 0f;
        var x2 = 0f;
        var y1 = 0f;
        var y2 = 0f
        for (i in 0 until n) {
            val x = signal[i]
            val y = lp360B0 * x + lp360B1 * x1 + lp360B2 * x2 - lp360A1 * y1 - lp360A2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            lp[i] = y
        }
        // de-mean
        val mean = lp.sum() / n
        val c = FloatArray(n) { lp[it] - mean }
        val sr = max(sampleRateHz, 1f)
        val minLag = max(4, (sr / 2000f).roundToInt())
        val maxLag = min(n - 4, (sr / 20f).roundToInt())
        if (maxLag <= minLag) return 0f
        var bestLag = -1;
        var bestScore = -1e9f
        val lagSpan = max(maxLag - minLag, 1).toFloat()
        for (lag in minLag..maxLag) {
            var dot = 0f;
            var e0 = 0f;
            var e1 = 0f
            val lim = n - lag; if (lim <= 0) continue
            for (i in 0 until lim) {
                dot += c[i] * c[i + lag]; e0 += c[i] * c[i]; e1 += c[i + lag] * c[i + lag]
            }
            val denom = sqrt(max(e0 * e1, 1e-18f))
            val corr = if (denom > 0f) dot / denom else 0f
            val bias = 0.75f + 0.25f * (lag - minLag) / lagSpan
            val score = corr * bias
            if (score > bestScore) {
                bestScore = score; bestLag = lag
            }
        }
        return if (bestLag > 0 && bestScore > 0.15f) bestLag.toFloat() else 0f
    }

    /** Choose best candidate from dual estimators, preferring consistent ones. */
    private fun chooseFundamentalCandidate(raw: Float, lp: Float, prev: Float): Float {
        val candidates = listOf(raw, lp).filter { it > 0f }
        if (candidates.isEmpty()) return if (prev > 0f) prev else 0f
        if (candidates.size == 1) return candidates[0]
        // prefer the one closer to previous estimate
        if (prev > 0f) {
            val dRaw = abs(raw - prev) / prev
            val dLp = abs(lp - prev) / prev
            return if (dRaw <= dLp) raw else lp
        }
        // average
        return candidates.average().toFloat()
    }

    /** EMA smoothing with ±18 % jump clamp. */
    private fun stabilizedPeriod(prev: Float, measured: Float): Float {
        if (prev <= 0f) return measured
        if (measured <= 0f) return prev
        val r = measured / max(prev, 1f)
        // clamp jump to ±18 %
        val clamped = when {
            r > 1f + maxAcceptedPeriodJumpRatio -> prev * (1f + maxAcceptedPeriodJumpRatio)
            r < 1f - maxAcceptedPeriodJumpRatio -> prev * (1f - maxAcceptedPeriodJumpRatio)
            else -> measured
        }
        return prev * 0.82f + clamped * 0.18f
    }

    // ═══════════════════════════════════════════════════════════════
    // STEP 4: SCORING + PHASE-LOCKED FILTERING
    // ═══════════════════════════════════════════════════════════════

    private data class Scored(val index: Int, val score: Float, val predictionErrorRatio: Float)

    private fun chooseBestCrossing(
        crossings: List<Int>, samples: FloatArray, estimatedPeriod: Float,
        preferredAnchor: Int, isRising: Boolean, holdoffSamples: Int,
        globalBase: Long, n: Int
    ): Int {
        if (crossings.isEmpty()) return preferredAnchor.coerceIn(0, n - 1)

        // prediction context
        val predCtx: Triple<Float, Float, Int?>? = if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
            val predictedGlobal = lastTriggerGlobalIdx + estimatedPeriod.toLong()
            val predictedLocal = (predictedGlobal - globalBase).toFloat()
            val neighborhood = max(estimatedPeriod * predictionNeighborhoodRatio, 2f)
            val reduced = reduceCrossings(crossings, estimatedPeriod, globalBase)
            val filtered = reduced.filter { abs(it - predictedLocal) <= neighborhood }
            val scoped = if (filtered.isNotEmpty()) filtered else reduced
            val closest = scoped.minByOrNull { abs(it - predictedLocal) }
            Triple(predictedLocal, estimatedPeriod, closest)
        } else null

        // phase-locked filtering
        val phaseLocked = if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
            val minD = estimatedPeriod * (1f - maxPredictionErrorRatio)
            val maxD = estimatedPeriod * (1f + maxPredictionErrorRatio)
            crossings.filter { c ->
                val delta = ((globalBase + c) - lastTriggerGlobalIdx).toFloat()
                delta in minD..maxD
            }.ifEmpty { crossings }
        } else crossings

        if (phaseLocked.isEmpty()) {
            return predCtx?.third ?: crossings.minByOrNull { abs(it - preferredAnchor) } ?: crossings.first()
        }

        // scoring
        val scored = phaseLocked.mapNotNull { c ->
            if (c !in 2 until n - 2) return@mapNotNull null
            val slope = abs(samples[c + 1] - samples[c - 1])

            // prediction score
            val predScore = if (predCtx != null) {
                val dist = abs(c - predCtx.first)
                val tol = max(estimatedPeriod * 0.35f, 4f)
                exp(-(dist * dist) / (2f * tol * tol))
            } else 0.5f

            // slope score (normalised)
            val maxSlope = phaseLocked.map { cc ->
                if (cc in 1 until n - 1) abs(samples[cc + 1] - samples[cc - 1]) else 0f
            }.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1e-6f
            val slopeScore = (slope / maxSlope).coerceIn(0f, 1f)

            // symmetry score
            val symScore = halfWaveSymmetry(samples, c, estimatedPeriod)

            // fingerprint score
            val fpScore = fingerprintScore(samples, c, n)

            // edge consistency
            val ecScore = localEdgeConsistency(samples, c, isRising)

            // ref edge consistency
            val refFp = lastTriggerFingerprint
            val refScore = if (refFp != null && refFp.isNotEmpty()) {
                val fp = computeFingerprint(
                    samples,
                    max(0, c - preferredAnchor),
                    min(n, c + preferredAnchor) - max(0, c - preferredAnchor)
                )
                fingerprintSimilarity(fp, refFp)
            } else 0.5f

            // phase continuity
            val phaseScore = if (predCtx != null && estimatedPeriod > 0) {
                val err = abs(c - predCtx.first) / estimatedPeriod
                max(0f, 1f - err)
            } else 0.5f

            // half-cycle alias penalty
            val aliasPenalty = if (estimatedPeriod > 4f) {
                val halfP = estimatedPeriod * 0.5f
                val errFromHalf = abs((c - (predCtx?.first ?: c.toFloat())) % halfP)
                val errFromFull = abs(errFromHalf - halfP)
                val minErr = min(errFromHalf, errFromFull)
                val halfTol = estimatedPeriod * 0.08f
                if (minErr < halfTol) -halfCycleAliasPenaltyVal * (1f - minErr / halfTol) else 0f
            } else 0f

            val total = predScore * predictionWeight +
                    slopeScore * slopeWeight +
                    symScore * symmetryWeight +
                    fpScore * fingerprintWeight +
                    ecScore * edgeConsistencyWeight +
                    refScore * refEdgeConsistencyWeight +
                    phaseScore * phaseContinuityWeight +
                    aliasPenalty

            Scored(c, total, predScore)
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) {
            return predCtx?.third ?: crossings.minByOrNull { abs(it - preferredAnchor) } ?: crossings.first()
        }

        val best = scored[0]
        val second = scored.getOrElse(1) { Scored(0, -999f, 1f) }

        // ── 5. Phase stickiness ────────────────────────────────────
        if (predCtx != null && predCtx.third != null) {
            val predictedCandidate = scored.find { it.index == predCtx.third }
            if (predictedCandidate != null) {
                val isAmbiguous = best.score < minConfidenceForAcceptance ||
                        (best.score - second.score) < ambiguousScoreMargin
                val predictionClearlyBetter = predictedCandidate.score > best.score * 1.05f
                val stickPenalty = abs(best.index - predCtx.first) / max(estimatedPeriod, 1f)
                if (isAmbiguous && predictionClearlyBetter && stickPenalty < phaseStickinessMargin) {
                    return predCtx.third!!
                }
            }
        }

        if (best.score >= minConfidenceForAcceptance) return best.index
        if (predCtx?.third != null && (best.score - second.score) < minConfidenceMargin) {
            return predCtx.third!!
        }
        return best.index
    }

    private fun reduceCrossings(crossings: List<Int>, estimatedPeriod: Float, globalBase: Long): List<Int> {
        if (crossings.size <= maxCrossingsToScore) return crossings
        if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
            val minD = estimatedPeriod * (1f - maxPredictionErrorRatio)
            val maxD = estimatedPeriod * (1f + maxPredictionErrorRatio)
            val phaseLocked = crossings.filter { c ->
                val delta = ((globalBase + c) - lastTriggerGlobalIdx).toFloat()
                delta in minD..maxD
            }
            if (phaseLocked.size >= 4) return compactByStride(phaseLocked, maxCrossingsToScore)
        }
        return compactByStride(crossings, maxCrossingsToScore)
    }

    private fun compactByStride(list: List<Int>, maxCount: Int): List<Int> {
        if (list.size <= maxCount) return list
        val stride = (list.size + maxCount - 1) / maxCount
        val out = mutableListOf<Int>()
        var i = 0
        while (i < list.size) {
            out.add(list[i]); i += stride
        }
        if (out.lastOrNull() != list.lastOrNull()) out.add(list.last())
        return out.take(maxCount)
    }

    // ═══════════════════════════════════════════════════════════════
    // STEP 6: EDGE REFINEMENT (conditioned mode)
    // ═══════════════════════════════════════════════════════════════

    /**
     * In conditioned mode the crossing sits on the filtered signal.
     * Search ±18 samples in the raw signal for the best zero-crossing.
     * Track offset EMA with deadband to prevent jitter.
     */
    private fun refineEdge(
        condSignal: FloatArray, rawSignal: FloatArray,
        condCrossing: Int, estimatedPeriod: Float, isRising: Boolean
    ): Int {
        val n = rawSignal.size
        val searchRadius = 18
        val baseOffset = conditionedOffsetEma.roundToInt()
        val centre = (condCrossing + baseOffset).coerceIn(searchRadius, n - searchRadius - 1)

        var bestIdx = condCrossing  // fallback to conditioned crossing
        var bestSlope = -1f

        for (offset in -searchRadius..searchRadius) {
            val idx = centre + offset
            if (idx !in 1 until n - 1) continue
            val isMatch = if (isRising) rawSignal[idx - 1] < 0f && rawSignal[idx] >= 0f
            else rawSignal[idx - 1] > 0f && rawSignal[idx] <= 0f
            if (!isMatch) continue
            val slope = abs(rawSignal[idx + 1] - rawSignal[idx - 1])
            if (slope > bestSlope) {
                bestSlope = slope; bestIdx = idx
            }
        }

        // update offset EMA with deadband
        val rawOffset = (bestIdx - condCrossing).toFloat()
        if (abs(rawOffset - conditionedOffsetEma) > 2f) {  // deadband = 2 samples
            conditionedOffsetEma = conditionedOffsetEma * 0.8f + rawOffset * 0.2f
        }
        return bestIdx
    }

    // ═══════════════════════════════════════════════════════════════
    // SCORING HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun localEdgeConsistency(samples: FloatArray, crossing: Int, rising: Boolean): Float {
        val n = samples.size
        val radius = edgeConsistencyRadius
        var before = 0f;
        var after = 0f;
        var bc = 0;
        var ac = 0
        for (o in 1..radius) {
            val l = crossing - o; if (l >= 0) {
                before += samples[l]; bc++
            }
            val r = crossing + o; if (r < n) {
                after += samples[r]; ac++
            }
        }
        if (bc == 0 || ac == 0) return 0f
        val d = (after / ac) - (before / bc)
        val oriented = if (rising) d else -d
        return max(0f, oriented / (abs(before / bc) + abs(after / ac) + 1e-6f))
    }

    private fun halfWaveSymmetry(samples: FloatArray, crossing: Int, estimatedPeriod: Float): Float {
        if (estimatedPeriod <= 1f) return 0f
        val half = max((estimatedPeriod * 0.5f).roundToInt(), 1)
        val radius = max(2, min(12, (estimatedPeriod * 0.08f).roundToInt()))
        val ls = crossing - half - radius;
        val rs = crossing + half - radius
        val n = samples.size
        if (ls < 0 || rs < 0 || ls + radius * 2 >= n || rs + radius * 2 >= n) return 0f
        var sumXY = 0f;
        var sumXX = 0f;
        var sumYY = 0f
        for (k in 0 until radius * 2) {
            val lv = samples[ls + k];
            val rv = samples[rs + k]
            sumXY += lv * rv; sumXX += lv * lv; sumYY += rv * rv
        }
        val denom = sqrt(sumXX * sumYY)
        return if (denom > 1e-12f) max(0f, sumXY / denom) else 0f
    }

    private fun fingerprintScore(samples: FloatArray, crossing: Int, n: Int): Float {
        val refFp = lastTriggerFingerprint ?: return 0.5f
        val fpLen = min(fingerprintSampleCount, n)
        val fp = computeFingerprint(
            samples,
            max(0, crossing - fpLen / 2),
            min(n, crossing + fpLen / 2) - max(0, crossing - fpLen / 2)
        )
        return fingerprintSimilarity(fp, refFp)
    }

    private fun computeFingerprint(samples: FloatArray, start: Int, length: Int): FloatArray {
        val count = min(fingerprintSampleCount, length)
        if (count <= 0) return FloatArray(0)
        val step = max(length - 1, 1).toFloat() / max(count - 1, 1).toFloat()
        val vals = FloatArray(count);
        var mean = 0f
        for (i in 0 until count) {
            vals[i] = samples[start + min(length - 1, (i * step).roundToInt())]; mean += vals[i]
        }
        mean /= count
        var energy = 0f;
        val norm = FloatArray(count)
        for (i in 0 until count) {
            val c = vals[i] - mean; norm[i] = c; energy += c * c
        }
        val s = sqrt(max(energy, 1e-18f))
        if (s > 0f) for (i in norm.indices) norm[i] /= s
        return norm
    }

    private fun fingerprintSimilarity(a: FloatArray, b: FloatArray): Float {
        val count = min(a.size, b.size);
        var dot = 0f
        for (i in 0 until count) dot += a[i] * b[i]
        return dot.coerceIn(0f, 1f)
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
