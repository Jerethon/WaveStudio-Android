package org.mhrri.wavestudio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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
        val triggerScore: Float = 0f,
        val corrScopeWeight: Float = 0f,
        val corrScopeScore: Float = 0f,
        val assistScore: Float = 0f,
        val assistPhaseOffsetSamples: Int? = null,
    )

    private val kernelSize = windowSize.coerceIn(128, 1024).let { it - it % 2 }
    private val kernelHalf = kernelSize / 2
    private val correlationBuffer = FloatArray(kernelSize)
    private val assistCorrelationBuffer = FloatArray(kernelSize)
    private val candidateBuffer = FloatArray(kernelSize)
    private var bufferInitialized = false
    private var assistBufferInitialized = false
    private var lastTriggerGlobalIdx = Long.MIN_VALUE
    private var lastTriggerGlobalPhase = Double.NaN
    private var pendingPredictedGlobalPhase = Double.NaN
    private var lastGlobalBase = Long.MIN_VALUE
    private var estimatedPeriodSamples = 0
    private var estimatedPeriodExactSamples = 0f
    private var lastScoredPeriodSamples = 0
    private var lastAssistPhaseOffsetSamples = Int.MIN_VALUE
    private var currentCorrScopeWeight = 0f
    private var processFrame = 0
    private var autocorrInput = FloatArray(0)
    private var autocorrOutput = FloatArray(0)
    private var periodAssistBuffer = FloatArray(0)
    private var fundamentalProbeBuffer = FloatArray(0)
    private var displayAlignmentBuffer = FloatArray(0)
    private var displayAlignmentScores = FloatArray(0)
    private var displayAlignmentInitialized = false
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
        prepareFundamentalProbe(signal, config.sampleRateHz)
        updatePeriodEstimate(signal, config.sampleRateHz)
        val estimatedFrequencyHz =
            if (estimatedPeriodSamples > 0) {
                config.sampleRateHz / estimatedPeriodSamples
            } else {
                0f
            }
        val periodIsEffective =
            estimatedFrequencyHz > MIN_EFFECTIVE_TRIGGER_HZ &&
                estimatedFrequencyHz <= MAX_VVVF_FUNDAMENTAL_HZ
        currentCorrScopeWeight = smoothStep(
            estimatedFrequencyHz,
            CORRSCOPE_BLEND_START_HZ,
            CORRSCOPE_BLEND_END_HZ,
        )
        val phaseAssistStrength =
            (1f - currentCorrScopeWeight).coerceIn(0f, 1f).let { it * it }
        val isRising = config.mode == Mode.RISING
        val rawRms = rms(signal)
        val assistRms = rms(periodAssistBuffer)

        fun crossingsFor(source: FloatArray, sourceRms: Float): List<Int> {
            if (sourceRms < 0.001f) return emptyList()
            val threshold = max(abs(config.triggerThreshold), sourceRms * 0.10f)
            val hysteresis = max(0.002f, max(threshold * 0.18f, sourceRms * 0.06f))
            return detectCrossings(
                signal = source,
                threshold = threshold,
                hysteresis = hysteresis,
                rising = isRising,
                holdoffMs = config.holdoffMs,
                sampleRateHz = config.sampleRateHz,
            )
        }

        val rawCrossings = crossingsFor(signal, rawRms)
        val assistCrossings = crossingsFor(periodAssistBuffer, assistRms)
        if ((rawCrossings.isEmpty() && assistCrossings.isEmpty()) || rawRms < 0.001f) {
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
        val predictedAnchor = if (periodIsEffective) {
            predictedLocalAnchor(
                globalBase = config.globalBase,
                preferredAnchor = preferredAnchor,
                periodSamples =
                    estimatedPeriodExactSamples.takeIf { it > 0f }
                        ?: estimatedPeriodSamples.toFloat(),
            )
        } else {
            pendingPredictedGlobalPhase = Double.NaN
            -1
        }
        val predictionRadius = if (predictedAnchor >= 0 && estimatedPeriodSamples > 0) {
            max(8, (estimatedPeriodSamples * 0.14f).roundToInt())
        } else {
            0
        }
        val predictedRawCorrelationQuality =
            if (bufferInitialized &&
                predictedAnchor >= kernelHalf &&
                predictedAnchor + kernelHalf <= n
            ) {
                (
                    (
                        normalizedWindowCorrelation(
                            signal,
                            predictedAnchor,
                            correlationBuffer,
                        ) + 1f
                        ) * 0.5f
                    ).coerceIn(0f, 1f)
            } else {
                0.5f
            }
        val predictedAssistCorrelationQuality =
            if (assistBufferInitialized &&
                predictedAnchor >= kernelHalf &&
                predictedAnchor + kernelHalf <= n
            ) {
                (
                    (
                        normalizedWindowCorrelation(
                            periodAssistBuffer,
                            predictedAnchor,
                            assistCorrelationBuffer,
                        ) + 1f
                        ) * 0.5f
                    ).coerceIn(0f, 1f)
            } else {
                0.5f
            }
        val rawCandidatesEnabled =
            currentCorrScopeWeight >= RAW_CANDIDATE_WEIGHT ||
                estimatedFrequencyHz >= CORRSCOPE_BLEND_END_HZ

        fun anchorsFor(source: FloatArray, crossings: List<Int>): List<Int> {
            return crossings.asSequence()
                .map { crossing ->
                    refineZeroCrossing(
                        signal = source,
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
                .toList()
        }

        val rawAnchors = anchorsFor(signal, rawCrossings)
        val assistAnchors = anchorsFor(periodAssistBuffer, assistCrossings)
        val phaseScopedRawAnchors =
            if (periodIsEffective &&
                estimatedFrequencyHz in PHASE_STICKINESS_MIN_HZ..
                PHASE_CANDIDATE_SCOPE_MAX_HZ &&
                predictedAnchor >= 0
            ) {
                val rawPhaseBlend = smoothStep(
                    currentCorrScopeWeight,
                    RAW_CANDIDATE_PHASE_RADIUS_START_WEIGHT,
                    RAW_CANDIDATE_FULL_PHASE_RADIUS_WEIGHT,
                )
                val rawPhaseRadiusRatio =
                    RAW_CANDIDATE_MIN_PHASE_RADIUS_RATIO +
                        (
                            RAW_CANDIDATE_MAX_PHASE_RADIUS_RATIO -
                                RAW_CANDIDATE_MIN_PHASE_RADIUS_RATIO
                            ) * rawPhaseBlend
                val rawPhaseRadius =
                    max(
                        4,
                        (estimatedPeriodSamples * rawPhaseRadiusRatio).roundToInt(),
                    )
                rawAnchors.filter { abs(it - predictedAnchor) <= rawPhaseRadius }
            } else {
                rawAnchors
            }
        val phaseScopedAssistAnchors =
            if (periodIsEffective &&
                !rawCandidatesEnabled &&
                predictedAnchor >= 0 &&
                predictionRadius > 0
            ) {
                assistAnchors.filter { abs(it - predictedAnchor) <= predictionRadius }
            } else {
                emptyList()
            }
        val crossingAnchorsToScore = if (!periodIsEffective) {
            if (rawAnchors.isNotEmpty()) rawAnchors.distinct() else assistAnchors.distinct()
        } else {
            if (!rawCandidatesEnabled && assistAnchors.isNotEmpty()) {
                (
                    if (phaseScopedAssistAnchors.isNotEmpty()) {
                        phaseScopedAssistAnchors
                    } else {
                        assistAnchors
                    }
                    ).distinct()
            } else if (
                currentCorrScopeWeight >= CORRSCOPE_PHASE_STABILIZE_WEIGHT &&
                rawAnchors.isNotEmpty()
            ) {
                if (phaseScopedRawAnchors.isNotEmpty()) {
                    phaseScopedRawAnchors.distinct()
                } else if (predictedAnchor >= 0) {
                    listOf(rawAnchors.minBy { abs(it - predictedAnchor) })
                } else {
                    rawAnchors.distinct()
                }
            } else {
                (phaseScopedRawAnchors + assistAnchors).distinct()
            }
        }
        val anchorsToScore = crossingAnchorsToScore

        fun nearestAssistOffset(anchor: Int): Int? {
            if (assistAnchors.isEmpty()) return null
            var nearest = assistAnchors[0]
            var nearestDistance = abs(anchor - nearest)
            for (i in 1 until assistAnchors.size) {
                val candidate = assistAnchors[i]
                val distance = abs(anchor - candidate)
                if (distance < nearestDistance) {
                    nearest = candidate
                    nearestDistance = distance
                }
            }
            return anchor - nearest
        }

        var bestAnchor = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCorrScopeEffect = 0f
        var bestAssistEffect = 0f
        var bestAssistPhaseOffset: Int? = null
        var foundInRadius = false
        data class ScoredTriggerCandidate(
            val anchor: Int,
            val score: Float,
            val predictionErrorRatio: Float,
            val corrScopeEffect: Float,
            val assistEffect: Float,
            val assistPhaseOffset: Int?,
        )
        val scoredCandidates = LinkedHashMap<Int, ScoredTriggerCandidate>()
        val periodContinuity =
            if (periodIsEffective && lastScoredPeriodSamples > 0) {
                val shorter = min(estimatedPeriodSamples, lastScoredPeriodSamples).toFloat()
                val longer = max(estimatedPeriodSamples, lastScoredPeriodSamples).toFloat()
                val ratio = shorter / longer.coerceAtLeast(1f)
                ratio * ratio
            } else if (periodIsEffective) {
                0.65f
            } else {
                0f
            }
        fun scoreCandidates(limitToRadius: Boolean) {
            for (anchor in anchorsToScore) {
                val distance = abs(anchor - preferredAnchor)
                if (limitToRadius && distance > searchRadius) continue

                foundInRadius = foundInRadius || limitToRadius
                val rawEdgeScore = edgeScore(
                    signal,
                    anchor,
                    isRising,
                    estimatedPeriodSamples,
                    rawRms,
                )
                val assistEdgeScore = edgeScore(
                    periodAssistBuffer,
                    anchor,
                    isRising,
                    estimatedPeriodSamples,
                    assistRms,
                )
                val rawCorrelation = if (bufferInitialized) {
                    normalizedWindowCorrelation(signal, anchor, correlationBuffer)
                } else {
                    0f
                }
                val assistCorrelation = if (assistBufferInitialized) {
                    normalizedWindowCorrelation(
                        periodAssistBuffer,
                        anchor,
                        assistCorrelationBuffer,
                    )
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
                val rawCorrelationQuality =
                    if (bufferInitialized) ((rawCorrelation + 1f) * 0.5f) else 0.5f
                val assistCorrelationQuality =
                    if (assistBufferInitialized) ((assistCorrelation + 1f) * 0.5f) else 0.5f
                val rawEdgeQuality = ((rawEdgeScore + 1f) * 0.5f).coerceIn(0f, 1f)
                val assistEdgeQuality = ((assistEdgeScore + 1f) * 0.5f).coerceIn(0f, 1f)
                val assistPhaseOffset = nearestAssistOffset(anchor)
                val assistPhaseOffsetScore =
                    if (assistPhaseOffset != null &&
                        lastAssistPhaseOffsetSamples != Int.MIN_VALUE &&
                        estimatedPeriodSamples > 0
                    ) {
                        val directError =
                            abs(assistPhaseOffset - lastAssistPhaseOffsetSamples).toFloat()
                        val wrappedError =
                            abs(directError - estimatedPeriodSamples.toFloat())
                        val error = min(directError, wrappedError)
                        val sigma = max(4f, estimatedPeriodSamples * 0.04f)
                        exp(-0.5f * (error / sigma) * (error / sigma))
                    } else if (assistPhaseOffset != null) {
                        0.65f
                    } else {
                        0.5f
                    }
                val assistedCorrScopeEffect =
                    rawCorrelationQuality * 0.30f +
                        predictedRawCorrelationQuality * 0.25f +
                        rawEdgeQuality * 0.10f +
                        predictionScore * 0.10f +
                        periodContinuity * 0.05f +
                        assistPhaseOffsetScore * 0.20f
                val pureCorrScopeEffect =
                    rawCorrelationQuality * 0.45f +
                        predictedRawCorrelationQuality * 0.20f +
                        rawEdgeQuality * 0.10f +
                        predictionScore * 0.20f +
                        periodContinuity * 0.05f
                val corrScopePurity = smoothStep(
                    currentCorrScopeWeight,
                    CORRSCOPE_PURITY_START_WEIGHT,
                    CORRSCOPE_PURITY_FULL_WEIGHT,
                )
                val corrScopeEffect =
                    assistedCorrScopeEffect +
                        (pureCorrScopeEffect - assistedCorrScopeEffect) *
                        corrScopePurity
                val assistEffect =
                    assistCorrelationQuality * 0.20f +
                        predictedAssistCorrelationQuality * 0.15f +
                        assistEdgeQuality * 0.10f +
                        predictionScore * 0.30f +
                        periodContinuity * 0.05f +
                        assistPhaseOffsetScore * 0.20f
                val score =
                    assistEffect +
                        (corrScopeEffect - assistEffect) * currentCorrScopeWeight -
                        proximityPenalty
                val predictionErrorRatio =
                    if (predictedAnchor >= 0 && estimatedPeriodSamples > 0) {
                        abs(anchor - predictedAnchor).toFloat() / estimatedPeriodSamples
                    } else {
                        1f
                    }
                val scoredCandidate = ScoredTriggerCandidate(
                    anchor = anchor,
                    score = score,
                    predictionErrorRatio = predictionErrorRatio,
                    corrScopeEffect = corrScopeEffect,
                    assistEffect = assistEffect,
                    assistPhaseOffset = assistPhaseOffset,
                )
                val previousScore = scoredCandidates[anchor]?.score ?: Float.NEGATIVE_INFINITY
                if (score > previousScore) scoredCandidates[anchor] = scoredCandidate
                if (score > bestScore) {
                    bestScore = score
                    bestAnchor = anchor
                    bestCorrScopeEffect = corrScopeEffect
                    bestAssistEffect = assistEffect
                    bestAssistPhaseOffset = assistPhaseOffset
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
        val rankedCandidates = scoredCandidates.values.sortedWith(
            compareByDescending<ScoredTriggerCandidate> { it.score }
                .thenBy { it.predictionErrorRatio },
        )
        if (rankedCandidates.isNotEmpty()) {
            var selectedCandidate = rankedCandidates.first()
            if (estimatedFrequencyHz >= PHASE_STICKINESS_MIN_HZ &&
                phaseAssistStrength > 0f &&
                predictedAnchor >= 0
            ) {
                val predictedCandidate = rankedCandidates.minByOrNull { candidate ->
                    abs(candidate.anchor - predictedAnchor)
                }
                if (predictedCandidate != null) {
                    val second = rankedCandidates.getOrNull(1)
                    val scoreGap =
                        selectedCandidate.score -
                            (second?.score ?: Float.NEGATIVE_INFINITY)
                    val isAmbiguous =
                        scoreGap < AMBIGUOUS_SCORE_MARGIN * phaseAssistStrength ||
                            selectedCandidate.score <
                            MINIMUM_CONFIDENCE_FOR_SWITCH * phaseAssistStrength
                    val predictionClearlyBetter =
                        selectedCandidate.predictionErrorRatio -
                            predictedCandidate.predictionErrorRatio >
                            PREDICTION_ERROR_ADVANTAGE
                    val scorePenaltyForSticking =
                        selectedCandidate.score - predictedCandidate.score
                    if (isAmbiguous &&
                        predictionClearlyBetter &&
                        scorePenaltyForSticking <
                        PHASE_STICKINESS_MARGIN * phaseAssistStrength
                    ) {
                        selectedCandidate = predictedCandidate
                    }
                }
            }
            if (estimatedFrequencyHz >= PHASE_STICKINESS_MIN_HZ &&
                phaseAssistStrength > 0f &&
                lastAssistPhaseOffsetSamples != Int.MIN_VALUE &&
                estimatedPeriodSamples > 0
            ) {
                fun assistOffsetError(candidate: ScoredTriggerCandidate): Float {
                    val offset = candidate.assistPhaseOffset ?: return Float.POSITIVE_INFINITY
                    val direct =
                        abs(offset - lastAssistPhaseOffsetSamples).toFloat()
                    return min(
                        direct,
                        abs(direct - estimatedPeriodSamples.toFloat()),
                    )
                }

                val phaseStableCandidate = rankedCandidates.minByOrNull(::assistOffsetError)
                if (phaseStableCandidate != null) {
                    val selectedError = assistOffsetError(selectedCandidate)
                    val stableError = assistOffsetError(phaseStableCandidate)
                    val switchThreshold =
                        estimatedPeriodSamples * ASSIST_PHASE_SWITCH_THRESHOLD_RATIO
                    val stableCandidateIsCloser =
                        stableError + ASSIST_PHASE_SWITCH_HYSTERESIS_SAMPLES < selectedError
                    val scorePenalty =
                        selectedCandidate.score - phaseStableCandidate.score
                    if (selectedError > switchThreshold &&
                        stableCandidateIsCloser &&
                        scorePenalty <
                        ASSIST_PHASE_SWITCH_SCORE_ADVANTAGE * phaseAssistStrength
                    ) {
                        selectedCandidate = phaseStableCandidate
                    }
                }
            }
            bestAnchor = selectedCandidate.anchor
            bestScore = selectedCandidate.score
            bestCorrScopeEffect = selectedCandidate.corrScopeEffect
            bestAssistEffect = selectedCandidate.assistEffect
            bestAssistPhaseOffset = selectedCandidate.assistPhaseOffset
        }

        val refinementRadius = (estimatedPeriodSamples * 0.04f)
            .roundToInt()
            .coerceIn(3, MAX_CORRELATION_REFINEMENT_RADIUS_SAMPLES)
        val correlationAnchor =
            if (rawCandidatesEnabled &&
                bufferInitialized &&
                currentCorrScopeWeight >= CORRSCOPE_PHASE_STABILIZE_WEIGHT
            ) {
                refineCorrelationPeak(
                    signal = signal,
                    center = bestAnchor,
                    radius = refinementRadius,
                    buffer = correlationBuffer,
                )
            } else {
                bestAnchor
            }
        val displayAlignmentStrength =
            smoothStep(
                estimatedFrequencyHz,
                DISPLAY_ALIGNMENT_BLEND_IN_START_HZ,
                DISPLAY_ALIGNMENT_BLEND_IN_END_HZ,
            ) * (
                1f - smoothStep(
                    estimatedFrequencyHz,
                    DISPLAY_ALIGNMENT_BLEND_OUT_START_HZ,
                    DISPLAY_ALIGNMENT_BLEND_OUT_END_HZ,
                )
                )
        val displayAlignmentRadius = (
            estimatedPeriodSamples *
                DISPLAY_ALIGNMENT_RADIUS_RATIO *
                displayAlignmentStrength
            )
            .roundToInt()
            .coerceIn(4, MAX_DISPLAY_ALIGNMENT_RADIUS_SAMPLES)
        val stabilizedAnchor =
            if (displayAlignmentInitialized &&
                displayAlignmentBuffer.size == displaySamples &&
                currentCorrScopeWeight >= DISPLAY_ALIGNMENT_MIN_WEIGHT &&
                displayAlignmentStrength > DISPLAY_ALIGNMENT_MIN_STRENGTH
            ) {
                refineDisplayAlignment(
                    signal = signal,
                    center = correlationAnchor,
                    radius = displayAlignmentRadius,
                    displaySamples = displaySamples,
                    preSamples = preSamples,
                )
            } else {
                correlationAnchor
            }
        val stabilizedAssistPhaseOffset = nearestAssistOffset(stabilizedAnchor)

        val triggerScore = (
            bestAssistEffect +
                (bestCorrScopeEffect - bestAssistEffect) * currentCorrScopeWeight
            ).coerceIn(0f, 1f)
        bufferInitialized = updateCorrelationBuffer(
            signal = signal,
            anchor = stabilizedAnchor,
            buffer = correlationBuffer,
            wasInitialized = bufferInitialized,
            responsiveness = RAW_REFERENCE_RESPONSIVENESS,
        )
        assistBufferInitialized = updateCorrelationBuffer(
            signal = periodAssistBuffer,
            anchor = stabilizedAnchor,
            buffer = assistCorrelationBuffer,
            wasInitialized = assistBufferInitialized,
            responsiveness = ASSIST_REFERENCE_RESPONSIVENESS,
        )
        displayAlignmentInitialized = updateDisplayAlignmentBuffer(
            signal = signal,
            anchor = stabilizedAnchor,
            displaySamples = displaySamples,
            preSamples = preSamples,
            wasInitialized = displayAlignmentInitialized,
        )
        lastTriggerGlobalIdx = config.globalBase + stabilizedAnchor
        lastTriggerGlobalPhase =
            if (pendingPredictedGlobalPhase.isFinite() &&
                estimatedPeriodExactSamples > 0f &&
                abs(lastTriggerGlobalIdx - pendingPredictedGlobalPhase) <=
                estimatedPeriodExactSamples * 0.25
            ) {
                val phaseFeedback =
                    if (estimatedFrequencyHz < PHASE_STICKINESS_MIN_HZ) {
                        1f
                    } else {
                        smoothStep(
                            currentCorrScopeWeight,
                            PHASE_FEEDBACK_START_WEIGHT,
                            PHASE_FEEDBACK_FULL_WEIGHT,
                        )
                    }
                pendingPredictedGlobalPhase +
                    (lastTriggerGlobalIdx - pendingPredictedGlobalPhase) * phaseFeedback
            } else {
                lastTriggerGlobalIdx.toDouble()
        }
        if (periodIsEffective) lastScoredPeriodSamples = estimatedPeriodSamples
        bestAssistPhaseOffset?.let { selectedOffset ->
            if (lastAssistPhaseOffsetSamples == Int.MIN_VALUE) {
                lastAssistPhaseOffsetSamples = selectedOffset
            } else {
                val difference = selectedOffset - lastAssistPhaseOffsetSamples
                val maximumUpdate = max(2, (estimatedPeriodSamples * 0.02f).roundToInt())
                lastAssistPhaseOffsetSamples += difference.coerceIn(-maximumUpdate, maximumUpdate)
            }
        }

        val period = estimatedPeriodSamples.coerceAtLeast(0)
        val frequency = if (period > 0) config.sampleRateHz / period else 0f
        return Result(
            anchorIndex = stabilizedAnchor,
            periodSamples = period,
            confidence = triggerScore,
            locked = true,
            mode = config.mode,
            freqHz = frequency,
            triggerScore = triggerScore,
            corrScopeWeight = currentCorrScopeWeight,
            corrScopeScore = bestCorrScopeEffect,
            assistScore = bestAssistEffect,
            assistPhaseOffsetSamples = stabilizedAssistPhaseOffset,
        )
    }

    private fun predictedLocalAnchor(
        globalBase: Long,
        preferredAnchor: Int,
        periodSamples: Float,
    ): Int {
        if (!lastTriggerGlobalPhase.isFinite() || periodSamples <= 0f) {
            pendingPredictedGlobalPhase = Double.NaN
            return -1
        }
        val period = periodSamples.toDouble()
        val preferredGlobal = (globalBase + preferredAnchor).toDouble()
        val delta = preferredGlobal - lastTriggerGlobalPhase
        val cycles = floor(delta / period)
        pendingPredictedGlobalPhase = lastTriggerGlobalPhase + cycles * period
        return (pendingPredictedGlobalPhase.roundToLong() - globalBase)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun seekAnchorTo(localAnchor: Int) {
        if (localAnchor >= 0 && lastGlobalBase != Long.MIN_VALUE) {
            lastTriggerGlobalIdx = lastGlobalBase + localAnchor
            lastTriggerGlobalPhase = lastTriggerGlobalIdx.toDouble()
            pendingPredictedGlobalPhase = Double.NaN
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
        val previousFrequencyHz =
            if (estimatedPeriodExactSamples > 0f) {
                sampleRateHz / estimatedPeriodExactSamples
            } else {
                0f
            }
        val previousCorrScopeWeight = smoothStep(
            previousFrequencyHz,
            CORRSCOPE_BLEND_START_HZ,
            CORRSCOPE_BLEND_END_HZ,
        )
        val scheduledUpdate =
            processFrame == 1 ||
                previousCorrScopeWeight >= PERIOD_ESTIMATE_EVERY_FRAME_WEIGHT ||
                processFrame % 8 == 0
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
        var usedWideFundamentalProbe = false
        var wideProbeCrossingPeriod = 0f
        for (i in 0 until count) {
            autocorrInput[i] = fundamentalProbeBuffer[sourceStart + i * downsample]
        }
        val wideProbeLag = estimatePreparedPeriod(
            count = count,
            effectiveRate = effectiveRate,
            fMinHz = MIN_EFFECTIVE_TRIGGER_HZ,
            fMaxHz = MAX_VVVF_FUNDAMENTAL_HZ,
            seedLag = estimatedPeriodSamples.takeIf { it > 0 }?.div(downsample) ?: 0,
        )
        if (wideProbeLag > 0) {
            val wideProbeFrequencyHz = effectiveRate / wideProbeLag
            if (wideProbeFrequencyHz > ASSISTED_CROSSING_PERIOD_MAX_HZ &&
                wideProbeFrequencyHz <= MAX_VVVF_FUNDAMENTAL_HZ
            ) {
                lag = wideProbeLag
                usedWideFundamentalProbe = true
                wideProbeCrossingPeriod = crossingPeriodSamples(fundamentalProbeBuffer)
            }
        }
        if (usedWideFundamentalProbe) {
            for (i in 0 until count) {
                autocorrInput[i] = signal[sourceStart + i * downsample]
            }
            val rawComparisonLag = estimatePreparedPeriod(
                count = count,
                effectiveRate = effectiveRate,
                fMinHz = MIN_EFFECTIVE_TRIGGER_HZ,
                fMaxHz = 4000f,
                seedLag = 0,
            )
            if (rawComparisonLag > 0) {
                val rawFrequencyHz = effectiveRate / rawComparisonLag
                val wideFrequencyHz = effectiveRate / lag
                val rawToWideRatio = rawFrequencyHz / wideFrequencyHz.coerceAtLeast(1f)
                if (rawFrequencyHz > MAX_VVVF_FUNDAMENTAL_HZ &&
                    rawToWideRatio in 1.90f..2.10f
                ) {
                    lag = rawComparisonLag
                    usedWideFundamentalProbe = false
                    wideProbeCrossingPeriod = 0f
                }
            }
        }
        val lowFrequencyAssistThreshold =
            if (usingLowFrequencyAssist) 0.02f else 0.04f
        if (lag <= 0 && lowFrequencyEnergyRatio >= lowFrequencyAssistThreshold) {
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
                } else if (assistedFrequencyHz <= LOW_FREQUENCY_PROBE_MAX_HZ) {
                    // The conditioned waveform remains useful for estimating the
                    // fundamental above the low-frequency trigger-assist range. Keep
                    // CorrScope's raw candidates, but avoid letting PWM carrier structure
                    // pull the raw autocorrelation toward a subharmonic.
                    lag = assistedLag
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
            val autocorrelationCandidate = (lag * downsample).toFloat()
            val crossingCandidate = assistedCrossingPeriodSamples(sampleRateHz)
            val crossingFrequencyHz =
                if (crossingCandidate > 0) sampleRateHz / crossingCandidate else 0f
            val crossingToAutocorrelationRatio =
                crossingCandidate / autocorrelationCandidate.coerceAtLeast(1f)
            val wideProbeCrossingFrequencyHz =
                if (wideProbeCrossingPeriod > 0f) {
                    sampleRateHz / wideProbeCrossingPeriod
                } else {
                    0f
                }
            val wideProbeCrossingRatio =
                wideProbeCrossingPeriod / autocorrelationCandidate.coerceAtLeast(1f)
            val candidate =
                if (usedWideFundamentalProbe &&
                    wideProbeCrossingFrequencyHz in ASSISTED_CROSSING_PERIOD_MAX_HZ..
                    MAX_VVVF_FUNDAMENTAL_HZ &&
                    wideProbeCrossingRatio in 0.90f..1.10f
                ) {
                    wideProbeCrossingPeriod
                } else if (crossingFrequencyHz in MIN_EFFECTIVE_TRIGGER_HZ..
                    ASSISTED_CROSSING_PERIOD_MAX_HZ &&
                    crossingToAutocorrelationRatio in 0.90f..1.10f
                ) {
                    crossingCandidate
                } else {
                    autocorrelationCandidate
                }
            val estimatorChanged = usedLowFrequencyAssist != usingLowFrequencyAssist
            val previousExact =
                estimatedPeriodExactSamples.takeIf { it > 0f }
                    ?: estimatedPeriodSamples.toFloat()
            estimatedPeriodExactSamples =
                if (previousExact > 0f && !estimatorChanged) {
                    if (usedLowFrequencyAssist) {
                        val ratio = candidate / previousExact
                        if (ratio in 0.70f..1.30f) {
                            previousExact * 0.82f + candidate * 0.18f
                        } else {
                            previousExact
                        }
                    } else {
                        // Normal frequencies keep CorrScope's quicker period response.
                        previousExact * 0.75f + candidate * 0.25f
                    }
                } else {
                    candidate
                }
            estimatedPeriodSamples = estimatedPeriodExactSamples.roundToInt()
            usingLowFrequencyAssist = usedLowFrequencyAssist
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
        val previousFrequencyHz =
            if (estimatedPeriodExactSamples > 0f) {
                sampleRateHz / estimatedPeriodExactSamples
            } else {
                0f
            }
        val assistCutoffHz =
            if (previousFrequencyHz in PHASE_STICKINESS_MIN_HZ..
                LOW_FREQUENCY_ASSIST_ENTER_HZ
            ) {
                (previousFrequencyHz * 1.5f).coerceIn(75f, 120f)
            } else {
                min(120f, sampleRateHz * 0.20f)
            }
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

    private fun prepareFundamentalProbe(signal: FloatArray, sampleRateHz: Float) {
        if (fundamentalProbeBuffer.size != signal.size) {
            fundamentalProbeBuffer = FloatArray(signal.size)
        }
        if (signal.isEmpty()) return

        val alpha =
            (1.0 - exp(-2.0 * Math.PI * FUNDAMENTAL_PROBE_CUTOFF_HZ / sampleRateHz))
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
            fundamentalProbeBuffer[i] = stage3
        }

        stage1 = fundamentalProbeBuffer.last()
        stage2 = stage1
        stage3 = stage2
        for (i in fundamentalProbeBuffer.lastIndex downTo 0) {
            val sample = fundamentalProbeBuffer[i]
            stage1 += alpha * (sample - stage1)
            stage2 += alpha * (stage1 - stage2)
            stage3 += alpha * (stage2 - stage3)
            fundamentalProbeBuffer[i] = stage3
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
        val periodSamples = assistedCrossingPeriodSamples(sampleRateHz)
        return if (periodSamples > 0) sampleRateHz / periodSamples else 0f
    }

    private fun assistedCrossingPeriodSamples(sampleRateHz: Float): Float {
        if (sampleRateHz <= 0f) return 0f
        return crossingPeriodSamples(periodAssistBuffer)
    }

    private fun crossingPeriodSamples(source: FloatArray): Float {
        if (source.size < 8) return 0f
        val start = source.size / 16
        val endExclusive = source.size - start
        if (endExclusive - start < 4) return 0f

        var mean = 0f
        for (i in start until endExclusive) mean += source[i]
        mean /= (endExclusive - start)

        var previousCrossing = Float.NaN
        val intervals = ArrayList<Float>()
        for (i in start + 1 until endExclusive) {
            if (source[i - 1] < mean && source[i] >= mean) {
                val previous = source[i - 1]
                val current = source[i]
                val fraction = ((mean - previous) / (current - previous).coerceAtLeast(1e-9f))
                    .coerceIn(0f, 1f)
                val crossing = (i - 1) + fraction
                if (previousCrossing.isFinite()) intervals += crossing - previousCrossing
                previousCrossing = crossing
            }
        }
        if (intervals.isEmpty()) return 0f
        intervals.sort()
        val middle = intervals.size / 2
        val median =
            if (intervals.size % 2 == 0) {
                (intervals[middle - 1] + intervals[middle]) * 0.5f
            } else {
                intervals[middle]
            }
        return median.coerceAtLeast(1f)
    }

    private fun normalizedWindowCorrelation(
        signal: FloatArray,
        anchor: Int,
        buffer: FloatArray,
    ): Float {
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
            dot += candidate * buffer[i]
            candidateEnergy += candidate * candidate
            bufferEnergy += buffer[i] * buffer[i]
        }
        val denom = sqrt(candidateEnergy * bufferEnergy).coerceAtLeast(1e-6f)
        return (dot / denom).coerceIn(-1f, 1f)
    }

    private fun refineCorrelationPeak(
        signal: FloatArray,
        center: Int,
        radius: Int,
        buffer: FloatArray,
    ): Int {
        val begin = max(kernelHalf, center - radius)
        val end = min(signal.size - kernelHalf, center + radius)
        var bestAnchor = center.coerceIn(begin, end)
        var bestScore = normalizedWindowCorrelation(signal, bestAnchor, buffer)
        for (anchor in begin..end) {
            val score = normalizedWindowCorrelation(signal, anchor, buffer)
            if (score > bestScore) {
                bestScore = score
                bestAnchor = anchor
            }
        }
        return bestAnchor
    }

    private fun updateCorrelationBuffer(
        signal: FloatArray,
        anchor: Int,
        buffer: FloatArray,
        wasInitialized: Boolean,
        responsiveness: Float,
    ): Boolean {
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
        val updateAmount = if (wasInitialized) responsiveness.coerceIn(0f, 1f) else 1f
        val center = (kernelSize - 1) / 2f
        for (i in 0 until kernelSize) {
            val x = (i - center) / std
            val window = exp(-0.5f * x * x)
            val aligned = candidateBuffer[i] * scale * window
            buffer[i] += updateAmount * (aligned - buffer[i])
        }
        return true
    }

    private fun refineDisplayAlignment(
        signal: FloatArray,
        center: Int,
        radius: Int,
        displaySamples: Int,
        preSamples: Int,
    ): Int {
        val minimumAnchor = preSamples
        val maximumAnchor = signal.size - displaySamples + preSamples
        val begin = max(minimumAnchor, center - radius)
        val end = min(maximumAnchor, center + radius)
        if (begin >= end) return center.coerceIn(minimumAnchor, maximumAnchor)

        val scoreCount = end - begin + 1
        if (displayAlignmentScores.size < scoreCount) {
            displayAlignmentScores = FloatArray(scoreCount)
        }
        for (offset in 0 until scoreCount) {
            displayAlignmentScores[offset] = displayAlignmentCorrelation(
                signal = signal,
                anchor = begin + offset,
                displaySamples = displaySamples,
                preSamples = preSamples,
            )
        }

        var bestAnchor = center.coerceIn(begin, end)
        var bestScore = Float.NEGATIVE_INFINITY
        for (offset in 1 until scoreCount - 1) {
            val score = displayAlignmentScores[offset]
            if (score >= displayAlignmentScores[offset - 1] &&
                score >= displayAlignmentScores[offset + 1] &&
                score > bestScore
            ) {
                bestScore = score
                bestAnchor = begin + offset
            }
        }
        return bestAnchor
    }

    private fun displayAlignmentCorrelation(
        signal: FloatArray,
        anchor: Int,
        displaySamples: Int,
        preSamples: Int,
    ): Float {
        val start = (anchor - preSamples)
            .coerceIn(0, max(0, signal.size - displaySamples))
        var mean = 0f
        for (i in 0 until displaySamples) mean += signal[start + i]
        mean /= displaySamples

        var dot = 0f
        var candidateEnergy = 0f
        var bufferEnergy = 0f
        for (i in 0 until displaySamples) {
            val candidate = signal[start + i] - mean
            val reference = displayAlignmentBuffer[i]
            dot += candidate * reference
            candidateEnergy += candidate * candidate
            bufferEnergy += reference * reference
        }
        val denominator = sqrt(candidateEnergy * bufferEnergy).coerceAtLeast(1e-6f)
        return (dot / denominator).coerceIn(-1f, 1f)
    }

    private fun updateDisplayAlignmentBuffer(
        signal: FloatArray,
        anchor: Int,
        displaySamples: Int,
        preSamples: Int,
        wasInitialized: Boolean,
    ): Boolean {
        if (displayAlignmentBuffer.size != displaySamples) {
            displayAlignmentBuffer = FloatArray(displaySamples)
            displayAlignmentInitialized = false
        }
        val start = (anchor - preSamples)
            .coerceIn(0, max(0, signal.size - displaySamples))
        var mean = 0f
        for (i in 0 until displaySamples) mean += signal[start + i]
        mean /= displaySamples

        var energy = 0f
        for (i in 0 until displaySamples) {
            val centered = signal[start + i] - mean
            energy += centered * centered
        }
        val scale = 1f / sqrt(energy).coerceAtLeast(1e-6f)
        val responsiveness =
            if (wasInitialized && displayAlignmentInitialized) {
                DISPLAY_ALIGNMENT_RESPONSIVENESS
            } else {
                1f
            }
        for (i in 0 until displaySamples) {
            val aligned = (signal[start + i] - mean) * scale
            displayAlignmentBuffer[i] +=
                responsiveness * (aligned - displayAlignmentBuffer[i])
        }
        return true
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
            triggerScore = 0f,
            corrScopeWeight = currentCorrScopeWeight,
        )
    }

    private fun rms(signal: FloatArray): Float {
        if (signal.isEmpty()) return 0f
        var energy = 0f
        for (sample in signal) energy += sample * sample
        return sqrt(energy / signal.size)
    }

    private fun smoothStep(value: Float, edge0: Float, edge1: Float): Float {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun reset() {
        correlationBuffer.fill(0f)
        assistCorrelationBuffer.fill(0f)
        candidateBuffer.fill(0f)
        bufferInitialized = false
        assistBufferInitialized = false
        displayAlignmentInitialized = false
        displayAlignmentBuffer.fill(0f)
        lastTriggerGlobalIdx = Long.MIN_VALUE
        lastTriggerGlobalPhase = Double.NaN
        pendingPredictedGlobalPhase = Double.NaN
        lastGlobalBase = Long.MIN_VALUE
        estimatedPeriodSamples = 0
        estimatedPeriodExactSamples = 0f
        lastScoredPeriodSamples = 0
        lastAssistPhaseOffsetSamples = Int.MIN_VALUE
        currentCorrScopeWeight = 0f
        processFrame = 0
        usingLowFrequencyAssist = false
    }

    internal companion object {
        private const val LOW_FREQUENCY_ASSIST_ENTER_HZ = 70f
        private const val LOW_FREQUENCY_ASSIST_EXIT_HZ = 85f
        private const val LOW_FREQUENCY_FAST_EXIT_RATIO = 1.10f
        private const val LOW_FREQUENCY_PROBE_MAX_HZ = 240f
        private const val FUNDAMENTAL_PROBE_CUTOFF_HZ = 360f
        private const val ASSISTED_CROSSING_PERIOD_MAX_HZ = 70f
        private const val MIN_EFFECTIVE_TRIGGER_HZ = 10f
        private const val MAX_VVVF_FUNDAMENTAL_HZ = 300f
        private const val CORRSCOPE_BLEND_START_HZ = 40f
        private const val CORRSCOPE_BLEND_END_HZ = 65f
        private const val PERIOD_ESTIMATE_EVERY_FRAME_WEIGHT = 0.50f
        private const val CORRSCOPE_PURITY_START_WEIGHT = 0.35f
        private const val CORRSCOPE_PURITY_FULL_WEIGHT = 0.75f
        private const val RAW_CANDIDATE_WEIGHT = 0.05f
        private const val RAW_CANDIDATE_MIN_PHASE_RADIUS_RATIO = 0.006f
        private const val RAW_CANDIDATE_PHASE_RADIUS_START_WEIGHT = 0.08f
        private const val RAW_CANDIDATE_FULL_PHASE_RADIUS_WEIGHT = 0.25f
        private const val RAW_CANDIDATE_MAX_PHASE_RADIUS_RATIO = 0.10f
        private const val PHASE_FEEDBACK_START_WEIGHT = 0.08f
        private const val PHASE_FEEDBACK_FULL_WEIGHT = 0.25f
        private const val CORRSCOPE_PHASE_STABILIZE_WEIGHT = 0.65f
        private const val MAX_CORRELATION_REFINEMENT_RADIUS_SAMPLES = 12
        private const val DISPLAY_ALIGNMENT_MIN_WEIGHT = 0.65f
        private const val DISPLAY_ALIGNMENT_MIN_STRENGTH = 0.05f
        private const val DISPLAY_ALIGNMENT_BLEND_IN_START_HZ = 45f
        private const val DISPLAY_ALIGNMENT_BLEND_IN_END_HZ = 52f
        private const val DISPLAY_ALIGNMENT_BLEND_OUT_START_HZ = 75f
        private const val DISPLAY_ALIGNMENT_BLEND_OUT_END_HZ = 95f
        private const val DISPLAY_ALIGNMENT_RADIUS_RATIO = 0.12f
        private const val MAX_DISPLAY_ALIGNMENT_RADIUS_SAMPLES = 100
        private const val DISPLAY_ALIGNMENT_RESPONSIVENESS = 1.00f
        private const val RAW_REFERENCE_RESPONSIVENESS = 0.05f
        private const val ASSIST_REFERENCE_RESPONSIVENESS = 0.15f
        private const val PHASE_STICKINESS_MIN_HZ = 35f
        private const val PHASE_CANDIDATE_SCOPE_MAX_HZ = MAX_VVVF_FUNDAMENTAL_HZ
        private const val ASSIST_PHASE_SWITCH_THRESHOLD_RATIO = 0.025f
        private const val ASSIST_PHASE_SWITCH_HYSTERESIS_SAMPLES = 4f
        private const val ASSIST_PHASE_SWITCH_SCORE_ADVANTAGE = 0.25f
        private const val AMBIGUOUS_SCORE_MARGIN = 0.08f
        private const val PHASE_STICKINESS_MARGIN = 0.14f
        private const val PREDICTION_ERROR_ADVANTAGE = 0.08f
        private const val MINIMUM_CONFIDENCE_FOR_SWITCH = 0.46f
    }
}
