package org.mhrri.wavestudio

import kotlin.math.*

/**
 * Trigger engine — direct port of iOS WaveStudio AudioEngineManager trigger pipeline.
 *
 * Pipeline:
 * 1. Adaptive threshold (max(userThr, RMS×0.10)) + Schmidt-trigger hysteresis crossing detection
 * 2. Period estimation via autocorrelation (refreshed every 8 frames) or crossing spacing
 * 3. Phase-locked crossing filtering — only crossings within ±22% of predicted period
 * 4. Multi-dimensional scoring: prediction(0.72) slope(0.18) symmetry(0.10) fingerprint(0.14)
 *    edge-consistency(0.08) ref-edge(0.20) anchor(0.10) phase(0.08) − half-cycle-alias(0.22)
 * 5. Phase stickiness — latch to predicted position when confidence marginal
 * 6. Window extraction — trigger point at 20% from left
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
        /** Max local anchor index for extraction safety. */
        val maxValidAnchor: Int = Int.MAX_VALUE,
    )

    data class Result(
        val anchorIndex: Int,
        val periodSamples: Int,
        val confidence: Float,
        val locked: Boolean,
        val mode: Mode,
        val freqHz: Float,
    )

    // ── iOS constants ───────────────────────────────────────────────
    private val predictionWeight = 0.72f
    private val slopeWeight = 0.18f
    private val symmetryWeight = 0.10f
    private val historyWeight = 0.14f
    private val edgeConsistencyWeight = 0.08f
    private val refEdgeConsistencyWeight = 0.20f
    private val anchorWeight = 0.10f
    private val phaseContinuityWeight = 0.08f
    private val halfCycleAliasPenaltyVal = 0.22f
    private val maxAcceptedPeriodJumpRatio = 0.30f
    private val maxPredictionErrorRatio = 0.22f
    private val predictionNeighborhoodRatio = 0.14f
    private val minConfidenceForAcceptance = 0.46f
    private val ambiguousScoreMargin = 0.055f
    private val phaseStickinessMargin = 0.075f
    private val minConfidenceMargin = 0.07f
    private val hysteresisRatio = 0.18f
    private val hysteresisFloorVal = 0.002f
    private val rmsHysteresisRatioVal = 0.06f
    private val phaseErrorEmaAlpha = 0.12f
    private val fingerprintSampleCount = 24
    private val edgeConsistencyRadiusVal = 3
    private val maxCrossingsToScore = 192
    private val maxFundamentalHz = 240f
    private val minFundamentalHz = 1f
    private val halfCycleAliasToleranceRatio = 0.12f
    private val weakSignalRmsFloorVal = 0.006f
    private val assistLowPassHzVal = 360f

    // ── state ──────────────────────────────────────────────────────
    private var lastTriggerGlobalIdx: Long = Long.MIN_VALUE
    private var estimatedPeriodSamples = 0f
    private var phaseErrorRatioEma = 0f
    private var lastTriggerFingerprint: FloatArray? = null
    private var pendingLocalAnchor = -1
    private var periodRefreshCounter = 0

    fun process(signal: FloatArray, config: Config): Result {
        val n = signal.size
        if (config.mode == Mode.OFF || n < 32) {
            reset()
            return Result(0, 0, 0f, false, config.mode, 0f)
        }

        // Resolve pending local anchor to global index
        if (pendingLocalAnchor >= 0 && config.globalBase >= 0) {
            lastTriggerGlobalIdx = config.globalBase + pendingLocalAnchor
            pendingLocalAnchor = -1
        }

        val preferredAnchor = max(1, n / 5)
        val isRising = config.mode == Mode.RISING

        // ── 1. Threshold + hysteresis ───────────────────────────────
        val threshold = adaptiveThreshold(signal, config.triggerThreshold)
        val rmsVal = rms(signal)
        val hysteresis = maxOf(hysteresisFloorVal, abs(threshold) * hysteresisRatio, rmsVal * rmsHysteresisRatioVal)

        val crossingsRaw =
            detectCrossings(signal, threshold, hysteresis, isRising, config.holdoffMs, config.sampleRateHz)
        if (crossingsRaw.isEmpty()) {
            val fb = if (lastTriggerGlobalIdx >= 0) (lastTriggerGlobalIdx - config.globalBase).toInt()
                .coerceIn(0, n - 1) else n / 2
            return Result(fb, estimatedPeriodSamples.roundToInt().coerceAtLeast(1), 0f, false, config.mode, 0f)
        }

        // ── 2. Period estimation ────────────────────────────────────
        val weakFloor = max(abs(config.triggerThreshold) * 0.8f, weakSignalRmsFloorVal)
        if (rmsVal >= weakFloor && shouldRefreshPeriod()) {
            val rawP = autocorrelationPeriod(signal, config.sampleRateHz)
            if (rawP > 1f) {
                estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, rawP)
            }
        }
        // fallback: crossing spacing
        if (estimatedPeriodSamples <= 0f && crossingsRaw.size >= 2) {
            val gaps = IntArray(crossingsRaw.size - 1) { crossingsRaw[it + 1] - crossingsRaw[it] }
            gaps.sort()
            estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, gaps[gaps.size / 2].toFloat())
        }

        // ── 3. Reduce crossings ─────────────────────────────────────
        val crossings = reduceCrossings(crossingsRaw, estimatedPeriodSamples, config.globalBase)
        val maxAnchor = min(config.maxValidAnchor, n - 1)
        val validCrossings = crossings.filter { it in 0..maxAnchor }
        if (validCrossings.isEmpty()) {
            val fb = if (lastTriggerGlobalIdx >= 0) (lastTriggerGlobalIdx - config.globalBase).toInt()
                .coerceIn(0, n - 1) else n / 2
            return Result(fb, estimatedPeriodSamples.roundToInt().coerceAtLeast(1), 0f, false, config.mode, 0f)
        }

        // ── 4. Choose best crossing ─────────────────────────────────
        val holdoffSamples = ((config.holdoffMs * config.sampleRateHz) / 1000f).roundToInt()

        // Shortcut: single crossing with no reliable period — just use it
        val chosen: Int
        if (validCrossings.size == 1 && estimatedPeriodSamples <= 0f) {
            chosen = validCrossings[0]
        } else {
            chosen = chooseBestCrossing(
                validCrossings, signal, estimatedPeriodSamples, preferredAnchor, isRising,
                holdoffSamples, config.globalBase, n
            )
        }

        // ── 5. Update state ─────────────────────────────────────────
        val globalChosen = config.globalBase + chosen

        // Primary period estimate: actual anchor-to-anchor distance (always accurate)
        if (lastTriggerGlobalIdx >= 0) {
            val anchorDelta = globalChosen - lastTriggerGlobalIdx
            if (anchorDelta > 1) {
                estimatedPeriodSamples = stabilizedPeriod(estimatedPeriodSamples, anchorDelta.toFloat())
            }
            val phaseErr = min(abs(anchorDelta - estimatedPeriodSamples) / max(estimatedPeriodSamples, 1f), 1f)
            phaseErrorRatioEma = phaseErrorRatioEma * (1f - phaseErrorEmaAlpha) + phaseErr * phaseErrorEmaAlpha
        }
        lastTriggerGlobalIdx = globalChosen

        // fingerprint
        val fpStart = max(0, chosen - preferredAnchor)
        val fpLen = min(fingerprintSampleCount, n - fpStart)
        if (fpLen > 0) lastTriggerFingerprint = computeFingerprint(signal, fpStart, fpLen)

        val periodInt = estimatedPeriodSamples.roundToInt().coerceAtLeast(1)
        val freqHz = if (estimatedPeriodSamples > 1) config.sampleRateHz / estimatedPeriodSamples else 0f

        return Result(chosen, periodInt, 0.85f, true, config.mode, freqHz)
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
    }

    // ═══════════════════════════════════════════════════════════════
    // THRESHOLD & CROSSING DETECTION (matches iOS)
    // ═══════════════════════════════════════════════════════════════

    private fun adaptiveThreshold(samples: FloatArray, userThr: Float) = max(abs(userThr), rms(samples) * 0.10f)

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var e = 0f; for (s in samples) e += s * s
        return sqrt(e / samples.size)
    }

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
            val slope = curr - prev
            val slopePass = if (isRising) slope > 0f else slope < 0f
            if (isRising) {
                if (curr <= lowThr) armed = true
                if (armed && slopePass && prev < lowThr && curr >= highThr) {
                    if (i - lastFire >= holdoffS) {
                        result.add(i); lastFire = i
                    }
                    armed = false
                }
            } else {
                if (curr >= highThr) armed = true
                if (armed && slopePass && prev > highThr && curr <= lowThr) {
                    if (i - lastFire >= holdoffS) {
                        result.add(i); lastFire = i
                    }
                    armed = false
                }
            }
        }
        // fallback
        if (result.isEmpty()) {
            for (i in 1 until n) {
                val sl = signal[i] - signal[i - 1]
                val sp = if (isRising) sl > 0f else sl < 0f
                if (!sp) continue
                if (isRising && signal[i - 1] < threshold && signal[i] >= threshold) result.add(i)
                else if (!isRising && signal[i - 1] > threshold && signal[i] <= threshold) result.add(i)
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // PERIOD ESTIMATION
    // ═══════════════════════════════════════════════════════════════

    private fun shouldRefreshPeriod(): Boolean {
        periodRefreshCounter++
        val stride = if (estimatedPeriodSamples > 0) 8 else 1
        return periodRefreshCounter % stride == 0
    }

    private fun autocorrelationPeriod(signal: FloatArray, sampleRateHz: Float): Float {
        val n = signal.size
        if (n < 64) return 0f
        val mean = signal.sum() / n
        val c = FloatArray(n) { signal[it] - mean }
        val sr = max(sampleRateHz, 1f)
        val minLag = max(4, (sr / maxFundamentalHz).roundToInt())
        val maxLag = min(n - 4, (sr / minFundamentalHz).roundToInt())
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

    private fun stabilizedPeriod(prev: Float, measured: Float): Float {
        if (prev <= 0f) return measured
        if (measured <= 0f) return prev
        val r = measured / max(prev, 1f)
        if (r !in 0.5f..2.0f) return measured
        return prev * 0.82f + measured * 0.18f
    }

    // ═══════════════════════════════════════════════════════════════
    // CHOOSE BEST CROSSING (matches iOS chooseBestTriggerCrossing)
    // ═══════════════════════════════════════════════════════════════

    private data class Scored(val index: Int, val score: Float, val predictionErrorRatio: Float)

    private fun chooseBestCrossing(
        crossings: List<Int>, samples: FloatArray, estimatedPeriod: Float,
        preferredAnchor: Int, isRising: Boolean, holdoffSamples: Int,
        globalBase: Long, n: Int
    ): Int {
        // ── prediction context ──────────────────────────────────────
        data class PredCtx(val predictedLocal: Float, val period: Float, val closestIdx: Int?)

        val predCtx: PredCtx? = if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
            val predictedLocal = (lastTriggerGlobalIdx + estimatedPeriod) - globalBase
            val neighborhood = max(estimatedPeriod * predictionNeighborhoodRatio, 2f)
            val filtered = crossings.filter { abs(it - predictedLocal) <= neighborhood }
            val scoped = filtered.ifEmpty { crossings }
            PredCtx(predictedLocal, estimatedPeriod, scoped.minByOrNull { abs(it - predictedLocal) })
        } else null

        // ── scope to prediction neighborhood ────────────────────────
        val scopedCrossings = if (predCtx != null) {
            val neighborhood = max(predCtx.period * predictionNeighborhoodRatio, 2f)
            crossings.filter { abs(it - predCtx.predictedLocal) <= neighborhood }.ifEmpty { crossings }
        } else crossings

        // ── phase-locked filtering ──────────────────────────────────
        val phaseLocked: List<Int> = if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
            val minD = estimatedPeriod * (1f - maxPredictionErrorRatio)
            val maxD = estimatedPeriod * (1f + maxPredictionErrorRatio)
            scopedCrossings.filter { c ->
                val delta = ((globalBase + c) - lastTriggerGlobalIdx).toFloat()
                delta in minD..maxD
            }.ifEmpty { scopedCrossings }
        } else scopedCrossings

        if (phaseLocked.isEmpty()) {
            // stable fallback: pick crossing closest to preferredAnchor or prediction
            val target = predCtx?.predictedLocal?.toInt()?.coerceIn(0, n - 1) ?: preferredAnchor
            return crossings.minByOrNull { abs(it - target) } ?: crossings.first()
        }

        // ── score each candidate ────────────────────────────────────
        val maxAbsSlope =
            max(phaseLocked.maxOfOrNull { if (it in 1 until n - 1) abs(samples[it + 1] - samples[it - 1]) else 0f }
                ?: 0f, 1e-7f)
        val anchorRange = max(n - preferredAnchor, 1).toFloat()

        val scored = phaseLocked.mapNotNull { c ->
            if (c !in 1 until n - 1) return@mapNotNull null

            // holdoff
            if (holdoffSamples > 0 && lastTriggerGlobalIdx >= 0) {
                val globalC = globalBase + c
                val delta = (globalC - lastTriggerGlobalIdx).toInt()
                if (delta in 1 until holdoffSamples) return@mapNotNull null
            }

            val globalC = globalBase + c
            val slopeVal = abs(samples[c + 1] - samples[c - 1])
            val slopeScore = slopeVal / maxAbsSlope
            val anchorScore = 1f - min(abs(c - preferredAnchor).toFloat() / anchorRange, 1f)
            val edgeScore = localEdgeConsistency(samples, c, isRising)
            val refEdgeScore = localEdgeConsistency(samples, c, true)

            var predictionScore = 0f
            var predictionErrorRatio = 1f
            var aliasPenalty = 0f
            var phaseScore = 0f

            if (estimatedPeriod > 1f && lastTriggerGlobalIdx >= 0) {
                val delta = globalC - lastTriggerGlobalIdx
                val predicted = lastTriggerGlobalIdx + estimatedPeriod
                val error = abs(globalC - predicted)
                val sigma = max(estimatedPeriod * 0.35f, 1f)
                predictionScore = exp(-(error * error) / (2f * sigma * sigma))
                predictionErrorRatio = min(error / max(estimatedPeriod, 1f), 2f)

                val halfPeriod = estimatedPeriod * 0.5f
                val halfErr = abs(delta - halfPeriod)
                val halfTol = max(estimatedPeriod * halfCycleAliasToleranceRatio, 1f)
                if (halfErr <= halfTol) {
                    aliasPenalty = halfCycleAliasPenaltyVal * (1f - min(halfErr / halfTol, 1f))
                }
                val emaSigma = max(estimatedPeriod * max(0.06f, phaseErrorRatioEma * 0.8f), 1f)
                phaseScore = exp(-(error * error) / (2f * emaSigma * emaSigma))
            }

            val symScore = halfWaveSymmetry(samples, c, estimatedPeriod)
            val historyScore = if (lastTriggerFingerprint != null && lastTriggerFingerprint!!.isNotEmpty()) {
                val fpLen = min(fingerprintSampleCount, n - max(0, c - preferredAnchor))
                if (fpLen > 0) fingerprintSimilarity(
                    computeFingerprint(samples, max(0, c - preferredAnchor), fpLen),
                    lastTriggerFingerprint!!
                ) else 0f
            } else 0f

            val combined = slopeWeight * slopeScore + predictionWeight * predictionScore +
                    symmetryWeight * symScore + historyWeight * historyScore +
                    edgeConsistencyWeight * edgeScore + refEdgeConsistencyWeight * refEdgeScore +
                    anchorWeight * anchorScore + phaseContinuityWeight * phaseScore - aliasPenalty

            Scored(c, combined, predictionErrorRatio)
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) {
            val target = predCtx?.predictedLocal?.toInt()?.coerceIn(0, n - 1) ?: preferredAnchor
            return phaseLocked.minByOrNull { abs(it - target) } ?: phaseLocked.first()
        }

        val best = scored[0]
        val second = scored.getOrElse(1) { Scored(0, -999f, 1f) }

        // ── phase stickiness (matches iOS) ──────────────────────────
        if (predCtx != null && predCtx.closestIdx != null) {
            val predictedCandidate = scored.find { it.index == predCtx.closestIdx }
            if (predictedCandidate != null) {
                val scoreGap = best.score - second.score
                val isAmbiguous = scoreGap < ambiguousScoreMargin || best.score < minConfidenceForAcceptance
                val predictionClearlyBetter =
                    (best.predictionErrorRatio - predictedCandidate.predictionErrorRatio) > 0.08f
                val stickPenalty = best.score - predictedCandidate.score
                if (isAmbiguous && predictionClearlyBetter && stickPenalty < phaseStickinessMargin)
                    return predCtx.closestIdx
            }
        }

        if (best.score >= minConfidenceForAcceptance) return best.index
        if (predCtx?.closestIdx != null && (best.score - second.score) < minConfidenceMargin)
            return predCtx.closestIdx
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
    // SCORING HELPERS (matches iOS)
    // ═══════════════════════════════════════════════════════════════

    private fun localEdgeConsistency(samples: FloatArray, crossing: Int, rising: Boolean): Float {
        if (samples.size < 3) return 0f
        val radius = max(1, edgeConsistencyRadiusVal)
        var before = 0f;
        var after = 0f;
        var bc = 0;
        var ac = 0
        for (o in 1..radius) {
            val l = crossing - o; if (l >= 0) {
                before += samples[l]; bc++
            }
            val r = crossing + o; if (r < samples.size) {
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
        var dot = 0f;
        var e0 = 0f;
        var e1 = 0f
        for (o in 0..radius * 2) {
            val a = samples[ls + o];
            val b = samples[rs + o]; dot += a * (-b); e0 += a * a; e1 += b * b
        }
        val denom = sqrt(max(e0 * e1, 1e-18f))
        return if (denom > 0f) max(0f, dot / denom) else 0f
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
}
