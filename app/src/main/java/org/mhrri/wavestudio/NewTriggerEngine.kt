package org.mhrri.wavestudio

import kotlin.collections.iterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Deprecated

internal class NewTriggerEngine(
    private val nominalWindowSize: Int = 512,
) {
    companion object {
        private const val FIXED_TRIGGER_THRESHOLD = 0.02f
    }

    enum class Mode {
        OFF,
        RISING,
        FALLING,
    }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val strongLowPassHz: Float = 160f,
        val fMinHz: Float = 5f,
        val fMaxHz: Float = 2000f,
        val useAutocorrelation: Boolean = false,
        val autocorrRefreshFrames: Int = 8,
        val autocorrMaxSamples: Int = 512,
        val preTriggerRatio: Float = 0.22f,
        val hysteresisRatio: Float = 0.16f,
        val holdoffRatio: Float = 0.60f,
        val maxStepPerFrame: Int = 10,
        val lockEnterConfidence: Float = 0.24f,
        val lockExitConfidence: Float = 0.10f,
        val unlockAfterBadFrames: Int = 6,
        val periodSmoothAlpha: Float = 0.18f,
        // Extra fields for API compatibility (unused in 0.12.0 core logic)
        val triggerThreshold: Float = FIXED_TRIGGER_THRESHOLD,
        val triggerCrossingHysteresisFloor: Float = 0.002f,
        val triggerCrossingHysteresisRatio: Float = 0.18f,
        val triggerWeakSignalRmsFloor: Float = 0.006f,
        val triggerMaxAcceptedPeriodJumpRatio: Float = 0.18f,
        val triggerHoldoffMs: Float = 1.0f,
        val triggerEdgeConsistencyRadius: Int = 10,
        val triggerPhaseErrorEmaAlpha: Float = 0.12f,
        val triggerPreferRisingPrimaryReference: Boolean = true,
        val triggerHalfCycleAliasPenalty: Float = 0.22f,
        val triggerPredictionWeight: Float = 0.82f,
        val triggerMinimumConfidenceForAcceptance: Float = 0.52f,
        val triggerPhaseStickinessMargin: Float = 0.12f,
        val triggerSearchMaxSamples: Int = 6144,
        val triggerPeriodEstimateMaxSamples: Int = 2048,
        val triggerRenderRefineMaxOffsetSamples: Int = 18,
        val triggerAssistLowPassCutoffHz: Float = 360.0f,
        val triggerAssistLowPassOrder: Int = 3,
    )

    data class Result(
        val startIndex: Int,
        val anchorIndex: Int,
        val periodSamples: Int,
        val confidence: Float,
        val locked: Boolean,
        val mode: Mode,
        val freqHz: Float,
    )

    private var lp = FloatArray(0)
    private var ac = FloatArray(0)

    private var estimatedPeriodSamples: Int = 0
    private var lastTriggerAnchor: Int = -1
    private var processFrameIndex: Int = 0
    private var lastConfig: Config = Config(Mode.OFF, 44100f)

    fun process(x: FloatArray, config: Config): Result {
        lastConfig = config
        val n = x.size
        val maxStart = max(0, n - nominalWindowSize)
        processFrameIndex++
        if (config.mode == Mode.OFF || n < 64) {
            val start = defaultStart(n)
            val anchor = (start + (nominalWindowSize * config.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
            return Result(start, anchor, 0, 0f, false, config.mode, 0f)
        }

        ensureCapacity(n)
        val triggerSignal = triggerLowPass(x, n, config.sampleRateHz, config.strongLowPassHz)
        val allCrossings = collectThresholdCrossings(triggerSignal, n, config.mode, FIXED_TRIGGER_THRESHOLD, config.hysteresisRatio)

        val crossingPeriodSamples = estimatePeriodFromCrossings(allCrossings)
        val shouldRefreshAutocorr = config.useAutocorrelation && (
                crossingPeriodSamples <= 0 ||
                        estimatedPeriodSamples <= 0 ||
                        (processFrameIndex % config.autocorrRefreshFrames.coerceAtLeast(1) == 0)
                )
        val autocorrPeriodSamples = if (shouldRefreshAutocorr) {
            estimatePeriodFromAutocorrelation(triggerSignal, n, config, crossingPeriodSamples)
        } else 0

        val observedPeriodSamples = when {
            autocorrPeriodSamples > 0 -> autocorrPeriodSamples
            crossingPeriodSamples > 0 -> crossingPeriodSamples
            else -> 0
        }
        if (observedPeriodSamples > 0) {
            estimatedPeriodSamples = smoothPeriodSamples(estimatedPeriodSamples, observedPeriodSamples, config.periodSmoothAlpha)
        }

        val holdoffSamples = triggerHoldoffSamples(estimatedPeriodSamples, config)
        val validCrossings = ArrayList<Int>(allCrossings.size)
        var lastKept = Int.MIN_VALUE / 4
        for (crossing in allCrossings) {
            if (crossing - lastKept < holdoffSamples) continue
            if (isLegalAnchor(crossing, n, config.preTriggerRatio)) {
                validCrossings += crossing
                lastKept = crossing
            }
        }
        if (validCrossings.isEmpty()) {
            return fallbackResult(n, config.mode, config.preTriggerRatio)
        }

        // 3. 弱信号防抖
        var rmsSq = 0f
        for (i in 0 until n) {
            rmsSq += triggerSignal[i] * triggerSignal[i]
        }
        val rms = kotlin.math.sqrt(rmsSq / n)

        val thresholdLimit = max(0.8f * FIXED_TRIGGER_THRESHOLD, 0.010f)

        val predictedAnchor = predictedAnchor(estimatedPeriodSamples, lastTriggerAnchor, n)
        val anchorIndex = selectBestAnchor(
            signal = triggerSignal,
            crossings = validCrossings,
            mode = config.mode,
            predictedAnchor = predictedAnchor,
            estimatedPeriodSamples = estimatedPeriodSamples,
            searchRadius = phaseSearchRadius(estimatedPeriodSamples, config),
        )
        if (anchorIndex < 0) {
            return fallbackResult(n, config.mode, config.preTriggerRatio)
        }
        lastTriggerAnchor = anchorIndex

        val startIndex = startFromAnchor(anchorIndex, n, config.preTriggerRatio).coerceIn(0, maxStart)

        val periodSamples = estimatedPeriodSamples

        val freqHz = if (periodSamples > 1) config.sampleRateHz / periodSamples.toFloat() else 0f
        val confidence = candidateConfidence(
            signal = triggerSignal,
            anchorIndex = anchorIndex,
            mode = config.mode,
            predictedAnchor = predictedAnchor,
            searchRadius = phaseSearchRadius(estimatedPeriodSamples, config),
        )
        val confidenceAdjusted = if (rms < thresholdLimit && periodSamples > 0) {
            (confidence * 0.82f).coerceAtLeast(0.2f)
        } else {
            confidence
        }

        return Result(
            startIndex = startIndex,
            anchorIndex = anchorIndex,
            periodSamples = periodSamples,
            confidence = confidenceAdjusted,
            locked = true,
            mode = config.mode,
            freqHz = freqHz,
        )
    }

    private fun candidateConfidence(
        signal: FloatArray,
        anchorIndex: Int,
        mode: Mode,
        predictedAnchor: Int,
        searchRadius: Int,
    ): Float {
        if (anchorIndex !in 1 until signal.size) return 0f
        val edgeScore = candidateEdgeStrength(signal, anchorIndex, mode)
        val phaseScore = if (predictedAnchor >= 0 && searchRadius > 0) {
            val dist = abs(anchorIndex - predictedAnchor).toFloat()
            (1f - (dist / searchRadius.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else 0.5f
        val base = 0.7f * edgeScore + 0.3f * phaseScore
        return base.coerceIn(0.2f, 1f)
    }

    private fun selectBestAnchor(
        signal: FloatArray,
        crossings: List<Int>,
        mode: Mode,
        predictedAnchor: Int,
        estimatedPeriodSamples: Int,
        searchRadius: Int,
    ): Int {
        if (crossings.isEmpty()) return -1

        val primaryCandidates = if (predictedAnchor >= 0 && searchRadius > 0) {
            val nearby = ArrayList<Int>(crossings.size)
            for (c in crossings) {
                if (abs(c - predictedAnchor) <= searchRadius) nearby += c
            }
            nearby
        } else {
            crossings.toMutableList()
        }

        val scored = if (primaryCandidates.isNotEmpty()) primaryCandidates else crossings
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (c in scored) {
            val score = candidateScore(
                signal = signal,
                anchorIndex = c,
                mode = mode,
                predictedAnchor = predictedAnchor,
                estimatedPeriodSamples = estimatedPeriodSamples,
                searchRadius = searchRadius,
            )
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun candidateScore(
        signal: FloatArray,
        anchorIndex: Int,
        mode: Mode,
        predictedAnchor: Int,
        estimatedPeriodSamples: Int,
        searchRadius: Int,
    ): Float {
        if (anchorIndex !in 1 until signal.size) return Float.NEGATIVE_INFINITY

        val edgeStrength = candidateEdgeStrength(signal, anchorIndex, mode)
        val phaseScore = if (predictedAnchor >= 0 && searchRadius > 0) {
            val dist = abs(anchorIndex - predictedAnchor).toFloat()
            (1f - (dist / searchRadius.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else 0.45f
        val historyScore = if (estimatedPeriodSamples > 0 && lastTriggerAnchor >= 0) {
            val expected = lastTriggerAnchor + estimatedPeriodSamples
            val dist = abs(anchorIndex - expected).toFloat()
            val historyRadius = max(searchRadius, (estimatedPeriodSamples * 0.25f).roundToInt().coerceAtLeast(1))
            (1f - (dist / historyRadius.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else 0.35f

        val positionBias = if (predictedAnchor >= 0 && estimatedPeriodSamples > 0) {
            1f - (abs(anchorIndex - predictedAnchor).toFloat() / max(estimatedPeriodSamples, 1).toFloat()).coerceIn(0f, 1f)
        } else 0.5f

        return (0.28f * edgeStrength + 0.38f * phaseScore + 0.28f * historyScore + 0.06f * positionBias)
            .coerceIn(0f, 1f)
    }

    private fun candidateEdgeStrength(signal: FloatArray, anchorIndex: Int, mode: Mode): Float {
        val i0 = (anchorIndex - 1).coerceAtLeast(0)
        val i1 = anchorIndex.coerceAtMost(signal.lastIndex)
        val i2 = (anchorIndex + 1).coerceAtMost(signal.lastIndex)
        val slope = when (mode) {
            Mode.FALLING -> signal[i0] - signal[i1]
            else -> signal[i1] - signal[i0]
        }
        val localRms = localRms(signal, i1, 2).coerceAtLeast(1e-4f)
        return ((abs(slope) / localRms) * 0.65f).coerceIn(0f, 1f)
    }

    private fun localRms(signal: FloatArray, center: Int, radius: Int): Float {
        val start = max(0, center - radius)
        val end = min(signal.size - 1, center + radius)
        var sumSq = 0f
        var count = 0
        for (i in start..end) {
            val v = signal[i]
            sumSq += v * v
            count++
        }
        return if (count > 0) kotlin.math.sqrt(sumSq / count) else 0f
    }

    private fun predictedAnchor(periodSamples: Int, lastAnchor: Int, n: Int): Int {
        if (periodSamples <= 0 || lastAnchor < 0) return -1
        return (lastAnchor + periodSamples).coerceIn(0, n - 1)
    }

    private fun phaseSearchRadius(periodSamples: Int, config: Config): Int {
        val base = if (periodSamples > 0) (periodSamples * 0.35f).roundToInt() else (nominalWindowSize * 0.20f).roundToInt()
        return max(8, base)
    }

    private fun triggerHoldoffSamples(periodSamples: Int, config: Config): Int {
        val holdoff = if (periodSamples > 0) {
            (periodSamples * config.holdoffRatio.coerceIn(0.10f, 0.90f)).roundToInt()
        } else {
            (nominalWindowSize * 0.14f).roundToInt()
        }
        return holdoff.coerceAtLeast(4)
    }

    private fun estimatePeriodFromCrossings(crossings: IntArray): Int {
        if (crossings.size < 2) return 0
        val lastC = crossings[crossings.size - 1]
        val beforeLast = crossings[crossings.size - 2]
        return (lastC - beforeLast).coerceAtLeast(0)
    }

    private fun estimatePeriodFromAutocorrelation(
        signal: FloatArray,
        n: Int,
        config: Config,
        seedLag: Int,
    ): Int {
        if (n <= 16) return 0
        val windowLen = min(n, config.autocorrMaxSamples.coerceIn(64, n))
        ensureAutocorrCapacity(windowLen)
        val acLen = min(windowLen, ac.size)
        if (acLen <= 16) return 0
        val start = n - acLen

        Autocorrelation.computeNormalized(
            x = signal,
            start = start,
            len = acLen,
            maxLag = acLen,
            out = ac,
        )

        val dt = 1f / config.sampleRateHz.coerceAtLeast(1f)
        return Autocorrelation.estimatePeriodFromAutocorrSeeded(
            ac = ac,
            acLen = acLen,
            dt = dt,
            fMinHz = config.fMinHz,
            fMaxHz = config.fMaxHz,
            seedLag = seedLag.coerceAtLeast(0),
        )
    }

    private fun smoothPeriodSamples(previous: Int, candidate: Int, alpha: Float): Int {
        if (candidate <= 0) return previous
        if (previous <= 0) return candidate
        val t = alpha.coerceIn(0f, 1f)
        return ((1f - t) * previous + t * candidate).roundToInt().coerceAtLeast(1)
    }
    private fun fallbackResult(n: Int, mode: Mode, preTriggerRatio: Float): Result {
        val fallbackStart = defaultStart(n)
        val fallbackAnchor = (fallbackStart + (nominalWindowSize * preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
        return Result(
            startIndex = fallbackStart,
            anchorIndex = fallbackAnchor,
            periodSamples = 0,
            confidence = 0f,
            locked = false,
            mode = mode,
            freqHz = 0f,
        )
    }

    private fun startFromAnchor(anchor: Int, n: Int, preTriggerRatio: Float): Int {
        val pre = (nominalWindowSize * preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt()
        return (anchor - pre).coerceIn(0, max(0, n - nominalWindowSize))
    }

    private fun isLegalAnchor(anchor: Int, n: Int, preTriggerRatio: Float): Boolean {
        if (n < nominalWindowSize) return false
        val pre = (nominalWindowSize * preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt()
        val rawStart = anchor - pre
        val rawEnd = rawStart + nominalWindowSize
        return rawStart >= 0 && rawEnd <= n
    }

    @JvmOverloads
    fun extractTriggeredWindow(source: FloatArray, result: Result?, targetSize: Int = nominalWindowSize): FloatArray {
        if (source.isEmpty()) return source
        val tgt = targetSize.coerceAtLeast(64)
        if (result == null || result.mode == Mode.OFF) {
            val tail = source.copyOfRange(max(0, source.size - tgt), source.size)
            return if (tail.size < tgt) tail + FloatArray(tgt - tail.size) { 0f } else tail
        }
        val preSamples = (tgt * lastConfig.preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt().coerceAtLeast(1)
        val start = (result.anchorIndex - preSamples).coerceIn(0, max(0, source.size - tgt))
        val end = (start + tgt).coerceAtMost(source.size)
        return source.copyOfRange(start, end).let { if (it.size < tgt) it + FloatArray(tgt - it.size) { 0f } else it }
    }


    private fun collectThresholdCrossings(x: FloatArray, n: Int, mode: Mode, threshold: Float, hysteresisRatio: Float): IntArray {
        val list = ArrayList<Int>(64)
        val hysteresis = max(0.001f, abs(threshold) * hysteresisRatio.coerceIn(0f, 0.9f))
        var armed = true
        for (i in 1 until n) {
            when (mode) {
                Mode.FALLING -> {
                    if (!armed && x[i] >= threshold + hysteresis) armed = true
                    if (armed && x[i - 1] > threshold && x[i] <= threshold) {
                        list += i
                        armed = false
                    }
                }
                else -> {
                    if (!armed && x[i] <= threshold - hysteresis) armed = true
                    if (armed && x[i - 1] < threshold && x[i] >= threshold) {
                        list += i
                        armed = false
                    }
                }
            }
        }
        return IntArray(list.size) { idx -> list[idx] }
    }

    private fun triggerLowPass(x: FloatArray, n: Int, sampleRateHz: Float, cutoffHz: Float): FloatArray {
        val dt = 1f / sampleRateHz.coerceAtLeast(1f)
        val rc = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(5f))
        val alpha = dt / (rc + dt)
        var y = 0f
        for (i in 0 until n) {
            y += alpha * (x[i] - y)
            lp[i] = y
        }
        return lp
    }

    private fun defaultStart(n: Int): Int = max(0, n - nominalWindowSize)

    private fun ensureCapacity(n: Int) {
        if (lp.size < n) lp = FloatArray(n)
    }

    private fun ensureAutocorrCapacity(n: Int) {
        if (ac.size < n) ac = FloatArray(n)
    }
}
