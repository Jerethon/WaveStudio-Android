package org.mhrri.wavestudio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stateful waveform trigger inspired by CorrScope's correlation trigger.
 *
 * Unlike a frame-local edge detector, this engine keeps an aligned waveform from the
 * previous frame and correlates new candidate edges against it. A slope score acquires
 * the first edge, autocorrelation limits subsequent searches to a plausible period, and
 * the aligned buffer preserves the selected waveform phase between UI refreshes.
 *
 * CorrScope is Copyright (c) 2018-2020+, nyanpasu64 and distributed under
 * the BSD 2-Clause License. See THIRD_PARTY_NOTICES.md.
 */
internal class SimpleTriggerEngine(
    private val windowSize: Int = 512,
) {
    enum class Mode { OFF, RISING, FALLING }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val preTriggerRatio: Float = 0.20f,
        val displayWindowSamples: Int = 512,
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

    private val kernelSize = windowSize.coerceIn(128, 1024).let { it - it % 2 }
    private val kernelHalf = kernelSize / 2
    private val correlationBuffer = FloatArray(kernelSize)
    private val candidateBuffer = FloatArray(kernelSize)
    private var bufferInitialized = false
    private var lastTriggerGlobalIdx = Long.MIN_VALUE
    private var lastGlobalBase = Long.MIN_VALUE
    private var estimatedPeriodSamples = 0
    private var processFrame = 0
    private var autocorrInput = FloatArray(0)
    private var autocorrOutput = FloatArray(0)
    private var periodAssistBuffer = FloatArray(0)
    private var usingLowFrequencyAssist = false

    fun process(signal: FloatArray, config: Config): Result {
        val n = signal.size
        if (config.mode == Mode.OFF || n < kernelSize + 4) {
            reset()
            return Result(0, 0, 0f, false, config.mode, 0f)
        }
        if (lastGlobalBase != Long.MIN_VALUE && config.globalBase < lastGlobalBase) {
            reset()
        }
        lastGlobalBase = config.globalBase
        processFrame++

        prepareLowFrequencyAssist(signal, config.sampleRateHz)
        updatePeriodEstimate(signal, config.sampleRateHz)
        val lowFrequencyMode = usingLowFrequencyAssist
        val triggerSignal = if (lowFrequencyMode) periodAssistBuffer else signal
        val isRising = config.mode == Mode.RISING
        val signalRms = rms(triggerSignal)
        val threshold = max(abs(config.triggerThreshold), signalRms * 0.10f)
        val hysteresis = max(0.002f, max(threshold * 0.18f, signalRms * 0.06f))
        val crossings = detectCrossings(
            signal = triggerSignal,
            threshold = threshold,
            hysteresis = hysteresis,
            rising = isRising,
            holdoffMs = config.holdoffMs,
            sampleRateHz = config.sampleRateHz,
        )
        if (crossings.isEmpty() || signalRms < 0.001f) {
            return unlockedFallback(n, config)
        }

        val displaySamples = config.displayWindowSamples.coerceIn(64, n)
        val preSamples = (displaySamples * config.preTriggerRatio.coerceIn(0.05f, 0.45f))
            .roundToInt()
            .coerceAtLeast(1)
        val preferredAnchor = (n - displaySamples + preSamples)
            .coerceIn(kernelHalf, n - kernelHalf - 1)
        val searchRadius = if (estimatedPeriodSamples > 0) {
            (estimatedPeriodSamples * 1.5f).roundToInt()
        } else {
            max(kernelSize, displaySamples / 2)
        }.coerceIn(kernelHalf, max(kernelHalf, n / 2))
        // CorrScope's correlation scoring is already strong at normal frequencies. Global
        // phase prediction is only needed for the low-frequency assisted waveform, where a
        // display frame may contain less than one fundamental cycle.
        val predictedAnchor = if (lowFrequencyMode) {
            predictedLocalAnchor(
                globalBase = config.globalBase,
                preferredAnchor = preferredAnchor,
                periodSamples = estimatedPeriodSamples,
            )
        } else {
            -1
        }
        val predictionRadius = if (predictedAnchor >= 0 && estimatedPeriodSamples > 0) {
            max(8, (estimatedPeriodSamples * 0.14f).roundToInt())
        } else {
            0
        }

        val candidateAnchors = crossings.asSequence()
            .map { crossing ->
                refineZeroCrossing(
                    signal = triggerSignal,
                    center = crossing,
                    rising = isRising,
                    periodSamples = estimatedPeriodSamples,
                )
            }
            .filter { anchor ->
                anchor >= kernelHalf &&
                    anchor + kernelHalf <= n &&
                    anchor <= preferredAnchor
            }
            .distinct()
            .toList()
        val phaseScopedAnchors = if (predictedAnchor >= 0) {
            candidateAnchors.filter { abs(it - predictedAnchor) <= predictionRadius }
        } else {
            emptyList()
        }
        val anchorsToScore =
            if (phaseScopedAnchors.isNotEmpty()) phaseScopedAnchors else candidateAnchors

        var bestAnchor = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCorrelation = 0f
        var foundInRadius = false

        fun scoreCandidates(limitToRadius: Boolean) {
            for (anchor in anchorsToScore) {
                val distance = abs(anchor - preferredAnchor)
                if (limitToRadius && distance > searchRadius) continue

                foundInRadius = foundInRadius || limitToRadius
                val edgeScore = edgeScore(
                    triggerSignal,
                    anchor,
                    isRising,
                    estimatedPeriodSamples,
                    signalRms,
                )
                val corrScore = if (bufferInitialized) {
                    normalizedWindowCorrelation(triggerSignal, anchor)
                } else {
                    0f
                }
                val distanceScale = max(estimatedPeriodSamples, kernelSize).toFloat()
                val proximityPenalty = 0.08f * (distance / distanceScale).coerceAtMost(2f)
                val predictionScore = if (predictedAnchor >= 0 && predictionRadius > 0) {
                    val error = abs(anchor - predictedAnchor).toFloat()
                    val sigma = max(1f, estimatedPeriodSamples * 0.14f)
                    exp(-0.5f * (error / sigma) * (error / sigma))
                } else {
                    0f
                }
                val score = if (lowFrequencyMode) {
                    if (bufferInitialized) {
                        corrScore * 0.55f +
                            edgeScore * 0.22f +
                            predictionScore * 0.72f -
                            proximityPenalty
                    } else {
                        edgeScore * 0.30f +
                            predictionScore * 0.72f -
                            proximityPenalty
                    }
                } else {
                    // Preserve CorrScope-style behavior outside the low-frequency assist:
                    // waveform similarity leads, while edge strength only helps acquisition.
                    if (bufferInitialized) {
                        corrScore + edgeScore * 0.30f - proximityPenalty
                    } else {
                        edgeScore - proximityPenalty
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestAnchor = anchor
                    bestCorrelation = corrScore
                }
            }
        }

        scoreCandidates(limitToRadius = true)
        if (!foundInRadius || bestAnchor < 0) {
            scoreCandidates(limitToRadius = false)
        }
        if (bestAnchor < 0) {
            return unlockedFallback(n, config)
        }
        val confidence = if (bufferInitialized) {
            ((bestCorrelation + 1f) * 0.5f).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        updateCorrelationBuffer(triggerSignal, bestAnchor)
        lastTriggerGlobalIdx = config.globalBase + bestAnchor

        val period = estimatedPeriodSamples.coerceAtLeast(0)
        val frequency = if (period > 0) config.sampleRateHz / period else 0f
        return Result(
            anchorIndex = bestAnchor,
            periodSamples = period,
            confidence = confidence,
            locked = true,
            mode = config.mode,
            freqHz = frequency,
        )
    }

    private fun predictedLocalAnchor(
        globalBase: Long,
        preferredAnchor: Int,
        periodSamples: Int,
    ): Int {
        if (lastTriggerGlobalIdx == Long.MIN_VALUE || periodSamples <= 0) return -1
        val period = periodSamples.toLong()
        val preferredGlobal = globalBase + preferredAnchor
        val delta = preferredGlobal - lastTriggerGlobalIdx
        val cycles = Math.floorDiv(delta, period)
        val predictedGlobal = lastTriggerGlobalIdx + cycles * period
        return (predictedGlobal - globalBase)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun seekAnchorTo(localAnchor: Int) {
        if (localAnchor >= 0 && lastGlobalBase != Long.MIN_VALUE) {
            lastTriggerGlobalIdx = lastGlobalBase + localAnchor
        }
    }

    fun extractWindow(
        source: FloatArray,
        result: Result,
        targetSize: Int,
        preTriggerRatio: Float,
    ): FloatArray {
        if (source.isEmpty() || result.mode == Mode.OFF) return source.copyOf()
        val target = targetSize.coerceAtLeast(64)
        val pre = (target * preTriggerRatio.coerceIn(0.05f, 0.45f))
            .roundToInt()
            .coerceAtLeast(1)
        val start = (result.anchorIndex - pre).coerceIn(0, max(0, source.size - target))
        val end = (start + target).coerceAtMost(source.size)
        val window = source.copyOfRange(start, end)
        return if (window.size == target) {
            window
        } else {
            window + FloatArray(target - window.size)
        }
    }

    private fun updatePeriodEstimate(signal: FloatArray, sampleRateHz: Float) {
        val scheduledUpdate = processFrame == 1 || processFrame % 8 == 0
        val shouldReleaseLowAssist = usingLowFrequencyAssist &&
            assistedCrossingFrequencyHz(sampleRateHz) >
            LOW_FREQUENCY_ASSIST_EXIT_HZ * LOW_FREQUENCY_FAST_EXIT_RATIO
        if (!scheduledUpdate && !shouldReleaseLowAssist) return

        val downsample = 2
        val sourceCount = min(signal.size, 8192)
        val sourceStart = signal.size - sourceCount
        val count = sourceCount / downsample
        if (count < 64) return
        if (autocorrInput.size < count) autocorrInput = FloatArray(count)

        var rawEnergy = 0f
        var assistEnergy = 0f
        val energyStart = sourceCount / 4
        val energyEnd = sourceCount - energyStart
        for (i in 0 until sourceCount) {
            val sample = signal[sourceStart + i]
            if (i in energyStart until energyEnd) {
                rawEnergy += sample * sample
                val assisted = periodAssistBuffer[sourceStart + i]
                assistEnergy += assisted * assisted
            }
        }

        val effectiveRate = sampleRateHz / downsample
        val lowFrequencyEnergyRatio = sqrt(
            assistEnergy / rawEnergy.coerceAtLeast(1e-6f),
        )
        var lag = 0
        var usedLowFrequencyAssist = false
        val lowFrequencyAssistThreshold =
            if (usingLowFrequencyAssist) 0.02f else 0.04f
        if (lowFrequencyEnergyRatio >= lowFrequencyAssistThreshold) {
            for (i in 0 until count) {
                autocorrInput[i] = periodAssistBuffer[sourceStart + i * downsample]
            }
            val assistedLag = estimatePreparedPeriod(
                count = count,
                effectiveRate = effectiveRate,
                fMinHz = 10f,
                // Probe above the assist range so a 120–150 Hz signal is identified at its
                // true period instead of being folded to a 60–75 Hz subharmonic.
                fMaxHz = LOW_FREQUENCY_PROBE_MAX_HZ,
                seedLag = 0,
            )
            if (assistedLag > 0) {
                val assistedFrequencyHz = effectiveRate / assistedLag
                val assistFrequencyLimitHz =
                    if (usingLowFrequencyAssist) {
                        LOW_FREQUENCY_ASSIST_EXIT_HZ
                    } else {
                        LOW_FREQUENCY_ASSIST_ENTER_HZ
                    }
                if (assistedFrequencyHz <= assistFrequencyLimitHz) {
                    lag = assistedLag
                    usedLowFrequencyAssist = true
                }
            }
        }

        if (lag <= 0) {
            for (i in 0 until count) {
                autocorrInput[i] = signal[sourceStart + i * downsample]
            }
            lag = estimatePreparedPeriod(
                count = count,
                effectiveRate = effectiveRate,
                fMinHz = 10f,
                fMaxHz = 4000f,
                seedLag = if (!usingLowFrequencyAssist && estimatedPeriodSamples > 0) {
                    estimatedPeriodSamples / downsample
                } else {
                    0
                },
            )
        }

        if (lag > 0) {
            val candidate = lag * downsample
            val estimatorChanged = usedLowFrequencyAssist != usingLowFrequencyAssist
            estimatedPeriodSamples =
                if (estimatedPeriodSamples > 0 && !estimatorChanged) {
                    if (usedLowFrequencyAssist) {
                        val ratio = candidate.toFloat() / estimatedPeriodSamples
                        if (ratio in 0.70f..1.30f) {
                            (estimatedPeriodSamples * 0.82f + candidate * 0.18f).roundToInt()
                        } else {
                            estimatedPeriodSamples
                        }
                    } else {
                        // Normal frequencies keep CorrScope's quicker period response.
                        (estimatedPeriodSamples * 0.75f + candidate * 0.25f).roundToInt()
                    }
                } else {
                    candidate
                }
            usingLowFrequencyAssist = usedLowFrequencyAssist
            if (estimatorChanged) {
                correlationBuffer.fill(0f)
                bufferInitialized = false
            }
        }
    }

    private fun prepareLowFrequencyAssist(signal: FloatArray, sampleRateHz: Float) {
        if (periodAssistBuffer.size != signal.size) {
            periodAssistBuffer = FloatArray(signal.size)
        }
        if (signal.isEmpty()) return

        // Forward/backward filtering removes the phase delay of a causal low-pass. This lets
        // the assisted trigger choose the fundamental edge without shifting the displayed
        // waveform horizontally.
        val assistCutoffHz = min(120f, sampleRateHz * 0.20f)
        val alpha = (1.0 - exp(-2.0 * Math.PI * assistCutoffHz / sampleRateHz))
            .toFloat()
            .coerceIn(0.001f, 1f)
        var stage1 = signal[0]
        var stage2 = stage1
        var stage3 = stage2
        for (i in signal.indices) {
            val sample = signal[i]
            stage1 += alpha * (sample - stage1)
            stage2 += alpha * (stage1 - stage2)
            stage3 += alpha * (stage2 - stage3)
            periodAssistBuffer[i] = stage3
        }

        stage1 = periodAssistBuffer.last()
        stage2 = stage1
        stage3 = stage2
        for (i in periodAssistBuffer.lastIndex downTo 0) {
            val sample = periodAssistBuffer[i]
            stage1 += alpha * (sample - stage1)
            stage2 += alpha * (stage1 - stage2)
            stage3 += alpha * (stage2 - stage3)
            periodAssistBuffer[i] = stage3
        }
    }

    private fun estimatePreparedPeriod(
        count: Int,
        effectiveRate: Float,
        fMinHz: Float,
        fMaxHz: Float,
        seedLag: Int,
    ): Int {
        // Pair-energy normalization becomes unreliable when only a handful of samples
        // overlap at very long lags. Keep at least a quarter of the input in every pair.
        val minimumOverlap = max(64, count / 4)
        val maxLag = min(count - minimumOverlap, (effectiveRate / fMinHz).roundToInt())
        if (maxLag < 16) return 0
        if (autocorrOutput.size < maxLag + 1) {
            autocorrOutput = FloatArray(maxLag + 1)
        }
        Autocorrelation.computeNormalized(
            x = autocorrInput,
            start = 0,
            len = count,
            maxLag = maxLag + 1,
            out = autocorrOutput,
        )
        val lag = Autocorrelation.estimatePeriodFromAutocorrSeeded(
            ac = autocorrOutput,
            acLen = maxLag + 1,
            dt = 1f / effectiveRate,
            fMinHz = fMinHz,
            fMaxHz = fMaxHz,
            seedLag = seedLag,
        )
        return lag
    }

    private fun assistedCrossingFrequencyHz(sampleRateHz: Float): Float {
        if (periodAssistBuffer.size < 8 || sampleRateHz <= 0f) return 0f
        val start = periodAssistBuffer.size / 4
        val endExclusive = periodAssistBuffer.size - start
        if (endExclusive - start < 4) return 0f

        var mean = 0f
        for (i in start until endExclusive) mean += periodAssistBuffer[i]
        mean /= (endExclusive - start)

        var firstCrossing = -1
        var lastCrossing = -1
        var crossingCount = 0
        for (i in start + 1 until endExclusive) {
            if (periodAssistBuffer[i - 1] < mean && periodAssistBuffer[i] >= mean) {
                if (firstCrossing < 0) firstCrossing = i
                lastCrossing = i
                crossingCount++
            }
        }
        if (crossingCount < 3 || lastCrossing <= firstCrossing) return 0f
        return (crossingCount - 1) * sampleRateHz / (lastCrossing - firstCrossing)
    }

    private fun normalizedWindowCorrelation(signal: FloatArray, anchor: Int): Float {
        val start = anchor - kernelHalf
        var mean = 0f
        for (i in 0 until kernelSize) mean += signal[start + i]
        mean /= kernelSize

        var dot = 0f
        var candidateEnergy = 0f
        var bufferEnergy = 0f
        for (i in 0 until kernelSize) {
            val candidate = signal[start + i] - mean
            candidateBuffer[i] = candidate
            dot += candidate * correlationBuffer[i]
            candidateEnergy += candidate * candidate
            bufferEnergy += correlationBuffer[i] * correlationBuffer[i]
        }
        val denom = sqrt(candidateEnergy * bufferEnergy).coerceAtLeast(1e-6f)
        return (dot / denom).coerceIn(-1f, 1f)
    }

    private fun updateCorrelationBuffer(signal: FloatArray, anchor: Int) {
        val start = anchor - kernelHalf
        var mean = 0f
        for (i in 0 until kernelSize) mean += signal[start + i]
        mean /= kernelSize

        var peak = 0f
        for (i in 0 until kernelSize) {
            candidateBuffer[i] = signal[start + i] - mean
            peak = max(peak, abs(candidateBuffer[i]))
        }
        val scale = 1f / max(peak, 0.01f)
        val std = if (estimatedPeriodSamples > 0) {
            (estimatedPeriodSamples * 0.5f).coerceIn(8f, kernelHalf.toFloat())
        } else {
            kernelSize / 4f
        }
        val responsiveness = if (bufferInitialized) 0.20f else 1f
        val center = (kernelSize - 1) / 2f
        for (i in 0 until kernelSize) {
            val x = (i - center) / std
            val window = exp(-0.5f * x * x)
            val aligned = candidateBuffer[i] * scale * window
            correlationBuffer[i] += responsiveness * (aligned - correlationBuffer[i])
        }
        bufferInitialized = true
    }

    private fun edgeScore(
        signal: FloatArray,
        anchor: Int,
        rising: Boolean,
        periodSamples: Int,
        signalRms: Float,
    ): Float {
        val radius = if (periodSamples > 0) {
            (periodSamples * 0.25f).roundToInt()
        } else {
            kernelSize / 8
        }.coerceIn(2, kernelSize / 3)
        var left = 0f
        var right = 0f
        for (i in 1..radius) {
            left += signal[anchor - i]
            right += signal[anchor + i - 1]
        }
        val slope = (right - left) / radius
        val directed = if (rising) slope else -slope
        return (directed / max(signalRms, 0.01f)).coerceIn(-2f, 2f) * 0.5f
    }

    private fun detectCrossings(
        signal: FloatArray,
        threshold: Float,
        hysteresis: Float,
        rising: Boolean,
        holdoffMs: Float,
        sampleRateHz: Float,
    ): List<Int> {
        val low = threshold - hysteresis
        val high = threshold + hysteresis
        val holdoff = ((holdoffMs * sampleRateHz) / 1000f)
            .roundToInt()
            .coerceAtLeast(1)
        val result = ArrayList<Int>()
        var armed = if (rising) signal[0] <= low else signal[0] >= high
        var lastFire = -holdoff - 1
        for (i in 1 until signal.lastIndex) {
            val previous = signal[i - 1]
            val current = signal[i]
            if (rising) {
                if (previous <= low) armed = true
                if (armed && previous <= high && current > high && i - lastFire >= holdoff) {
                    result += i
                    lastFire = i
                    armed = false
                }
            } else {
                if (previous >= high) armed = true
                if (armed && previous >= low && current < low && i - lastFire >= holdoff) {
                    result += i
                    lastFire = i
                    armed = false
                }
            }
        }
        if (result.isEmpty()) {
            for (i in 1 until signal.size) {
                if (rising && signal[i - 1] < 0f && signal[i] >= 0f) result += i
                if (!rising && signal[i - 1] > 0f && signal[i] <= 0f) result += i
            }
        }
        return result
    }

    private fun refineZeroCrossing(
        signal: FloatArray,
        center: Int,
        rising: Boolean,
        periodSamples: Int,
    ): Int {
        // At low frequencies the waveform slope is shallow, so a threshold crossing can be
        // tens of samples away from the actual zero crossing. Scale the refinement range with
        // the estimated period while keeping it well below half a cycle.
        val radius = if (periodSamples > 0) {
            (periodSamples * 0.10f).roundToInt().coerceIn(32, 512)
        } else {
            64
        }
        var best = center.coerceIn(1, signal.lastIndex)
        var bestDistance = Int.MAX_VALUE
        val begin = max(1, center - radius)
        val end = min(signal.lastIndex, center + radius)
        for (i in begin..end) {
            val crossing = if (rising) {
                signal[i - 1] < 0f && signal[i] >= 0f
            } else {
                signal[i - 1] > 0f && signal[i] <= 0f
            }
            if (crossing && abs(i - center) < bestDistance) {
                best = i
                bestDistance = abs(i - center)
            }
        }
        return best
    }

    private fun unlockedFallback(n: Int, config: Config): Result {
        val local = if (lastTriggerGlobalIdx != Long.MIN_VALUE) {
            (lastTriggerGlobalIdx - config.globalBase).toInt().coerceIn(0, n - 1)
        } else {
            n / 2
        }
        val period = estimatedPeriodSamples.coerceAtLeast(0)
        return Result(
            anchorIndex = local,
            periodSamples = period,
            confidence = 0f,
            locked = false,
            mode = config.mode,
            freqHz = if (period > 0) config.sampleRateHz / period else 0f,
        )
    }

    private fun rms(signal: FloatArray): Float {
        if (signal.isEmpty()) return 0f
        var energy = 0f
        for (sample in signal) energy += sample * sample
        return sqrt(energy / signal.size)
    }

    private fun reset() {
        correlationBuffer.fill(0f)
        candidateBuffer.fill(0f)
        bufferInitialized = false
        lastTriggerGlobalIdx = Long.MIN_VALUE
        lastGlobalBase = Long.MIN_VALUE
        estimatedPeriodSamples = 0
        processFrame = 0
        usingLowFrequencyAssist = false
    }

    private companion object {
        const val LOW_FREQUENCY_ASSIST_ENTER_HZ = 70f
        const val LOW_FREQUENCY_ASSIST_EXIT_HZ = 85f
        const val LOW_FREQUENCY_FAST_EXIT_RATIO = 1.10f
        const val LOW_FREQUENCY_PROBE_MAX_HZ = 240f
    }
}
