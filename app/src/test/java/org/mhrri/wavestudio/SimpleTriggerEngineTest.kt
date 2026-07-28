package org.mhrri.wavestudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleTriggerEngineTest {
    private val sampleRate = 48_000f
    private val frequencyHz = 440f
    private val frameSize = 2_400
    private val displaySize = 512

    @Test
    fun acquisitionPlacesRisingEdgeNearDisplayReference() {
        val engine = SimpleTriggerEngine()
        val result = engine.process(
            sineFrame(globalBase = 0L),
            config(globalBase = 0L),
        )

        assertTrue(result.locked)
        assertTrue("frequency=${result.freqHz}, period=${result.periodSamples}", abs(result.freqHz - 440f) <= 12f)
        assertEdgeAtDisplayReference(sineFrame(globalBase = 0L), result)
    }

    @Test
    fun phaseLockRemainsAtReferenceAfterMultiplePeriodsBetweenFrames() {
        val engine = SimpleTriggerEngine()
        engine.process(sineFrame(globalBase = 0L), config(globalBase = 0L))

        // A 30 FPS display update advances 1,440 samples at 48 kHz: over 13 periods at 440 Hz.
        val second = engine.process(sineFrame(globalBase = 1_440L), config(globalBase = 1_440L))
        val third = engine.process(sineFrame(globalBase = 2_880L), config(globalBase = 2_880L))

        assertTrue(second.locked)
        assertTrue(third.locked)
        assertEdgeAtDisplayReference(sineFrame(globalBase = 1_440L), second)
        assertEdgeAtDisplayReference(sineFrame(globalBase = 2_880L), third)
        assertTrue(abs(second.periodSamples - third.periodSamples) <= 3)
    }

    @Test
    fun resetMakesTheNextFrameAFreshAcquisition() {
        val warmed = SimpleTriggerEngine()
        repeat(8) { frame ->
            val globalBase = frame * 1_440L
            warmed.process(sineFrame(globalBase), config(globalBase))
        }

        warmed.reset()
        val afterReset = warmed.process(sineFrame(globalBase = 0L), config(globalBase = 0L))
        val fresh = SimpleTriggerEngine().process(
            sineFrame(globalBase = 0L),
            config(globalBase = 0L),
        )

        assertTrue(afterReset.locked)
        assertTrue(afterReset.anchorIndex == fresh.anchorIndex)
        assertTrue(afterReset.periodSamples == fresh.periodSamples)
    }

    @Test
    fun displayTopologyChangeReacquiresInsteadOfUsingTheOldTemplate() {
        val warmed = SimpleTriggerEngine()
        repeat(8) { frame ->
            val globalBase = frame * 1_440L
            warmed.process(
                sineFrame(globalBase, size = 4_800),
                config(globalBase).copy(
                    displayWindowSamples = 2_400,
                    displayAlignmentPoints = 512,
                ),
            )
        }

        val changedBase = 8 * 1_440L
        val changedConfig = config(changedBase).copy(
            displayWindowSamples = 1_200,
            displayAlignmentPoints = 384,
        )
        val changedSource = sineFrame(changedBase, size = 4_800)
        val afterChange = warmed.process(changedSource, changedConfig)
        val fresh = SimpleTriggerEngine().process(changedSource, changedConfig)

        assertTrue(afterChange.locked)
        assertTrue(afterChange.anchorIndex == fresh.anchorIndex)
        assertTrue(afterChange.periodSamples == fresh.periodSamples)
        assertTrue(!afterChange.displayAlignmentApplied)
    }

    @Test
    fun sustainedUnlockDiscardsOldPhaseFeedbackBeforeRecovery() {
        val warmed = SimpleTriggerEngine()
        repeat(8) { frame ->
            val globalBase = frame * 1_440L
            warmed.process(sineFrame(globalBase), config(globalBase))
        }

        val firstMissBase = 8 * 1_440L
        val secondMissBase = 9 * 1_440L
        val recoveryBase = 10 * 1_440L
        val firstMiss = warmed.process(FloatArray(frameSize), config(firstMissBase))
        val secondMiss = warmed.process(FloatArray(frameSize), config(secondMissBase))
        val recoverySource = sineFrame(recoveryBase)
        val recovered = warmed.process(recoverySource, config(recoveryBase))
        val fresh = SimpleTriggerEngine().process(recoverySource, config(recoveryBase))

        assertTrue(!firstMiss.locked)
        assertTrue(!secondMiss.locked)
        assertTrue(recovered.locked)
        assertTrue(recovered.anchorIndex == fresh.anchorIndex)
        assertTrue(recovered.periodSamples == fresh.periodSamples)
        assertTrue(!recovered.displayAlignmentApplied)
    }

    @Test
    fun phaseLockIsIndependentOfDisplayRefreshRate() {
        for (refreshHz in listOf(10, 20, 30, 60)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / refreshHz).roundToInt()
            repeat(12) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val result = engine.process(
                    sineFrame(globalBase),
                    config(globalBase),
                )

                assertTrue("refresh=$refreshHz frame=$frame did not lock", result.locked)
                assertEdgeAtDisplayReference(sineFrame(globalBase), result)
            }
        }
    }

    @Test
    fun highFrequencyHarmonicWaveformKeepsTheSameCorrelationPhase() {
        val frequency = 220f
        val engine = SimpleTriggerEngine()
        val samplesPerFrame = (sampleRate / 60f).roundToInt()
        val phases = ArrayList<Double>()
        val estimatedFrequencies = ArrayList<Float>()

        repeat(48) { frame ->
            val globalBase = frame.toLong() * samplesPerFrame
            val source = highFrequencyHarmonicFrame(
                globalBase = globalBase,
                frequency = frequency,
                size = 4_800,
            )
            val result = engine.process(
                source,
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue("frame=$frame did not lock", result.locked)
            if (frame >= 8) {
                phases += phaseFraction(globalBase + result.anchorIndex, frequency)
                estimatedFrequencies += result.freqHz
            }
        }

        val reference = phases.first()
        val maxPhaseJump = phases.maxOf { phase ->
            val direct = abs(phase - reference)
            minOf(direct, 1.0 - direct)
        }
        assertTrue(
            "max phase jump=$maxPhaseJump turns, phases=$phases, frequencies=$estimatedFrequencies",
            maxPhaseJump <= 0.04,
        )
    }

    @Test
    fun aboveFortyHertzHarmonicWaveformsKeepCorrelationPhase() {
        for (frequency in listOf(50f, 60f, 80f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val phases = ArrayList<Double>()

            repeat(48) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val source = highFrequencyHarmonicFrame(
                    globalBase = globalBase,
                    frequency = frequency,
                    size = 4_800,
                )
                val result = engine.process(
                    source,
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 24) {
                    phases += phaseFraction(globalBase + result.anchorIndex, frequency)
                }
            }

            val reference = phases.first()
            val maxPhaseJump = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, 1.0 - direct)
            }
            assertTrue(
                "frequency=$frequency max phase jump=$maxPhaseJump turns, phases=$phases",
                maxPhaseJump <= 0.04,
            )
        }
    }

    @Test
    fun corrScopeDominantRangeSuppressesSmallRandomHorizontalJitter() {
        for (frequency in listOf(50f, 60f, 120f, 240f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val fundamentalPeriod = (sampleRate / frequency).roundToInt()
            val phases = ArrayList<Int>()
            val weights = ArrayList<Float>()
            val periods = ArrayList<Int>()

            repeat(96) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val result = engine.process(
                    noisyHarmonicFrame(globalBase, frequency, size = 4_800),
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 16) {
                    phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                    weights += result.corrScopeWeight
                    periods += result.periodSamples
                }
            }

            val reference = phases.sorted()[phases.size / 2]
            val maximumJitter = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, fundamentalPeriod - direct)
            }
            assertTrue(
                "frequency=$frequency jitter=$maximumJitter phases=$phases " +
                    "weights=$weights periods=$periods",
                maximumJitter <= 3,
            )
            if (frequency >= 60f) {
                assertTrue(
                    "frequency=$frequency average weight=${weights.average()} weights=$weights",
                    weights.average() >= 0.65,
                )
            }
        }
    }

    @Test
    fun midFrequencyAsyncSpwmKeepsFundamentalPhaseSteady() {
        for (frequency in listOf(50f, 45f, 40f, 35f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val fundamentalPeriod = (sampleRate / frequency).roundToInt()
            val phases = ArrayList<Int>()
            val weights = ArrayList<Float>()
            val periods = ArrayList<Int>()
            val displayAlignment = ArrayList<String>()

            repeat(72) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val result = engine.process(
                    spwmLineFrame(
                        globalBase = globalBase,
                        frequency = frequency,
                        size = 4_800,
                        carrierMultiple = 17.35,
                    ),
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 16) {
                    phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                    weights += result.corrScopeWeight
                    periods += result.periodSamples
                    displayAlignment +=
                        "${result.displayAlignmentApplied}:" +
                        "${"%.3f".format(result.displayBestScore - result.displayCenterScore)}"
                }
            }

            val reference = phases.sorted()[phases.size / 2]
            val maximumJitter = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, fundamentalPeriod - direct)
            }
            assertTrue(
                "frequency=$frequency jitter=$maximumJitter phases=$phases " +
                    "weights=$weights periods=$periods displayAlignment=$displayAlignment",
                maximumJitter <= 10,
            )
        }
    }

    @Test
    fun corrScopeDominantAsyncSpwmKeepsRawWindowContinuity() {
        val measuredCorrelations = LinkedHashMap<Float, Double>()
        for (frequency in listOf(55f, 60f, 65f, 70f, 80f, 120f, 180f, 240f, 300f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val correlations = ArrayList<Float>()
            val estimatedFrequencies = ArrayList<Float>()
            var previousWindow: FloatArray? = null

            repeat(72) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val source = spwmLineFrame(
                    globalBase = globalBase,
                    frequency = frequency,
                    size = 4_800,
                    carrierMultiple = 17.35,
                )
                val result = engine.process(
                    source,
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 16) {
                    estimatedFrequencies += result.freqHz
                    val window = engine.extractWindow(source, result, 512, 0.20f)
                    previousWindow?.let { previous ->
                        correlations += normalizedCorrelation(previous, window)
                    }
                    previousWindow = window
                }
            }

            val averageCorrelation = correlations.average()
            measuredCorrelations[frequency] = averageCorrelation
            assertTrue(
                "frequency=$frequency estimated=$estimatedFrequencies",
                estimatedFrequencies.all {
                    abs(it - frequency) <= maxOf(2f, frequency * 0.05f)
                },
            )
        }
        assertTrue(
            "average correlations=$measuredCorrelations",
            measuredCorrelations.all { (frequency, correlation) ->
                val minimumCorrelation = if (frequency < 70f) 0.55 else 0.70
                correlation >= minimumCorrelation
            },
        )
    }

    @Test
    fun midFrequencyThresholdChatterKeepsTheSamePhaseCandidate() {
        for (frequency in listOf(40f, 45f, 50f, 55f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val phases = ArrayList<Double>()
            val phaseDiagnostics = ArrayList<String>()

            repeat(72) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val phaseOffset = frame * 0.10
                val result = engine.process(
                    midFrequencyChatterFrame(
                        globalBase = globalBase,
                        frequency = frequency,
                        size = 4_800,
                        phaseOffset = phaseOffset,
                        frame = frame,
                    ),
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 16) {
                    val turns =
                        frequency * (globalBase + result.anchorIndex) / sampleRate +
                            phaseOffset / (2.0 * PI)
                    phases += turns - floor(turns)
                    if (frame < 28 ||
                        !result.coreObservationAccepted ||
                        abs(result.corePhaseResidualSamples) >
                        result.periodSamples * 0.10f
                    ) {
                        phaseDiagnostics +=
                            "frame=$frame state=${result.phaseIdentityState} " +
                            "period=${result.periodSamples} " +
                            "pred=${result.predictedAnchorIndex} " +
                            "selected=${result.selectedCandidateAnchorIndex} " +
                            "out=${result.anchorIndex} residual=${result.corePhaseResidualSamples}"
                    }
                }
            }

            val reference = phases.sorted()[phases.size / 2]
            val maximumPhaseJump = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, 1.0 - direct)
            }
            assertTrue(
                "frequency=$frequency maxPhaseJump=$maximumPhaseJump phases=$phases " +
                    "diagnostics=$phaseDiagnostics",
                maximumPhaseJump <= 0.02,
            )
        }
    }

    @Test
    fun corrScopeWeightFollowsPreferredFortyToSixtyFiveHertzTransition() {
        val expectedWeights = listOf(
            40f to 0f,
            50f to 0.352f,
            55f to 0.648f,
            60f to 0.896f,
            65f to 1f,
            70f to 1f,
        )

        for ((frequency, expectedWeight) in expectedWeights) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val weights = ArrayList<Float>()

            repeat(48) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val result = engine.process(
                    sineFrame(globalBase, frequency, size = 4_800),
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 24) weights += result.corrScopeWeight
            }

            val averageWeight = weights.average()
            assertTrue(
                "frequency=$frequency expected=$expectedWeight actual=$averageWeight weights=$weights",
                abs(averageWeight - expectedWeight) <= 0.04,
            )
        }
    }

    @Test
    fun frequenciesAboveLowAssistRangeStayOnCorrscopePeriodEstimate() {
        for (frequency in listOf(80f, 100f, 120f, 150f, 220f, 440f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val estimatedFrequencies = ArrayList<Float>()

            repeat(24) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val result = engine.process(
                    sineFrame(globalBase, frequency, size = 4_800),
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 8) estimatedFrequencies += result.freqHz
            }

            assertTrue(
                "expected=$frequency frequencies=$estimatedFrequencies",
                estimatedFrequencies.all { abs(it - frequency) <= frequency * 0.05f },
            )
        }
    }

    @Test
    fun switchingFromLowToHighFrequencyReleasesAssistImmediately() {
        val engine = SimpleTriggerEngine()
        val samplesPerFrame = (sampleRate / 60f).roundToInt()
        var globalBase = 0L

        repeat(16) {
            val result = engine.process(
                sineFrame(globalBase, 30f, size = 4_800),
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue(result.locked)
            globalBase += samplesPerFrame
        }

        val highFrequencyResults = ArrayList<Float>()
        repeat(4) {
            val result = engine.process(
                sineFrame(globalBase, 150f, size = 4_800),
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue(result.locked)
            highFrequencyResults += result.freqHz
            globalBase += samplesPerFrame
        }

        assertTrue(
            "high-frequency estimates=$highFrequencyResults",
            highFrequencyResults.drop(1).all { abs(it - 150f) <= 8f },
        )
    }

    @Test
    fun lowFrequencyPhaseLockRemainsStableAcrossRefreshRates() {
        for (frequency in listOf(20f, 30f, 40f, 50f)) {
            for (refreshHz in listOf(20, 30, 60)) {
                val engine = SimpleTriggerEngine()
                val samplesPerFrame = (sampleRate / refreshHz).roundToInt()
                repeat(16) { frame ->
                    val globalBase = frame.toLong() * samplesPerFrame
                    val source = sineFrame(globalBase, frequency, size = 4_800)
                    val result = engine.process(
                        source,
                        config(globalBase).copy(displayWindowSamples = 2_400),
                    )

                    assertTrue(
                        "frequency=$frequency refresh=$refreshHz frame=$frame did not lock",
                        result.locked,
                    )
                    assertTrue(
                        "expected=$frequency actual=${result.freqHz} period=${result.periodSamples}",
                        abs(result.freqHz - frequency) <= maxOf(2f, frequency * 0.08f),
                    )
                    assertEdgeAtDisplayReference(
                        source = source,
                        result = result,
                        displayWindowSize = 2_400,
                    )
                }
            }
        }
    }

    @Test
    fun lowFrequencySpwmDoesNotJumpBetweenCarrierEdges() {
        for (frequency in listOf(20f, 30f, 40f, 50f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val fundamentalPeriod = (sampleRate / frequency).roundToInt()
            val phases = ArrayList<Int>()
            val estimatedPeriods = ArrayList<Int>()

            repeat(24) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val source = spwmLineFrame(globalBase, frequency, size = 4_800)
                val result = engine.process(
                    source,
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 4) {
                    phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                    estimatedPeriods += result.periodSamples
                }
            }

            val reference = phases.first()
            val maxPhaseJump = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, fundamentalPeriod - direct)
            }
            assertTrue(
                "frequency=$frequency max phase jump=$maxPhaseJump samples, " +
                    "phases=$phases, estimatedPeriods=$estimatedPeriods",
                maxPhaseJump <= (sampleRate * 0.001f).roundToInt(),
            )
        }
    }

    @Test
    fun lowFrequencyAsyncSpwmTracksFundamentalPhase() {
        for (frequency in listOf(20f, 30f, 40f, 50f)) {
            val engine = SimpleTriggerEngine()
            val samplesPerFrame = (sampleRate / 60f).roundToInt()
            val fundamentalPeriod = (sampleRate / frequency).roundToInt()
            val phases = ArrayList<Int>()

            repeat(36) { frame ->
                val globalBase = frame.toLong() * samplesPerFrame
                val source = spwmLineFrame(
                    globalBase = globalBase,
                    frequency = frequency,
                    size = 4_800,
                    carrierMultiple = 17.35,
                )
                val result = engine.process(
                    source,
                    config(globalBase).copy(displayWindowSamples = 2_400),
                )
                assertTrue("frequency=$frequency frame=$frame did not lock", result.locked)
                if (frame >= 8) {
                    phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                }
            }

            val reference = phases.sorted()[phases.size / 2]
            val maxPhaseJump = phases.maxOf { phase ->
                val direct = abs(phase - reference)
                minOf(direct, fundamentalPeriod - direct)
            }
            assertTrue(
                "frequency=$frequency max phase jump=$maxPhaseJump samples, phases=$phases",
                maxPhaseJump <= (sampleRate * 0.0015f).roundToInt(),
            )
        }
    }

    @Test
    fun weakLowFundamentalDoesNotJumpToDominantHarmonicPhase() {
        val frequency = 18f
        val engine = SimpleTriggerEngine()
        val samplesPerFrame = (sampleRate / 60f).roundToInt()
        val fundamentalPeriod = (sampleRate / frequency).roundToInt()
        val phases = ArrayList<Int>()
        val estimatedFrequencies = ArrayList<Float>()
        val adaptiveWeights = ArrayList<Float>()

        repeat(40) { frame ->
            val globalBase = frame.toLong() * samplesPerFrame
            val source = driftingHarmonicFrame(
                globalBase = globalBase,
                frequency = frequency,
                size = 4_800,
                frame = frame,
            )
            val result = engine.process(
                source,
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue("frame=$frame did not lock", result.locked)
            if (frame >= 8) {
                phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                estimatedFrequencies += result.freqHz
                adaptiveWeights += result.corrScopeWeight
            }
        }

        val reference = phases.sorted()[phases.size / 2]
        val maxPhaseJump = phases.maxOf { phase ->
            val direct = abs(phase - reference)
            minOf(direct, fundamentalPeriod - direct)
        }
        assertTrue(
            "max phase jump=$maxPhaseJump samples, phases=$phases, " +
                "frequencies=$estimatedFrequencies, weights=$adaptiveWeights",
            maxPhaseJump <= (fundamentalPeriod * 0.10f).roundToInt(),
        )
        assertTrue(
            "expected=$frequency frequencies=$estimatedFrequencies",
            estimatedFrequencies.all { abs(it - frequency) <= 2f },
        )
    }

    @Test
    fun lowFrequencySweepReleasesStalePhasePrediction() {
        val engine = SimpleTriggerEngine()
        val samplesPerFrame = (sampleRate / 60f).roundToInt()
        val visiblePhaseErrors = ArrayList<Double>()
        val corePhaseErrors = ArrayList<Double>()
        val estimatedFrequencies = ArrayList<Float>()
        val adaptiveWeights = ArrayList<Float>()
        val phaseDiagnostics = ArrayList<String>()
        val visiblePhaseErrorDiagnostics = ArrayList<String>()
        val displayCorrectionViolations = ArrayList<String>()

        repeat(64) { frame ->
            val globalBase = frame.toLong() * samplesPerFrame
            val source = sweptHarmonicFrame(globalBase = globalBase, size = 4_800)
            val result = engine.process(
                source,
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue("frame=$frame did not lock", result.locked)
            if (frame < 28 ||
                !result.coreObservationAccepted ||
                abs(result.corePhaseResidualSamples) >
                result.periodSamples * 0.10f
            ) {
                phaseDiagnostics +=
                    "frame=$frame state=${result.phaseIdentityState} " +
                    "period=${result.periodSamples} " +
                    "pred=${result.predictedAnchorIndex} " +
                    "selected=${result.selectedCandidateAnchorIndex} " +
                    "out=${result.anchorIndex} residual=${result.corePhaseResidualSamples} " +
                    "display=${result.displayAlignmentApplied}/" +
                    "${"%.3f".format(result.displayCenterScore)}/" +
                    "${"%.3f".format(result.displayBestScore)}/" +
                    "${"%.3f".format(result.displayPeakScoreGap)} " +
                    "score=${"%.3f".format(result.confidence)} " +
                    "assist=${"%.3f".format(result.assistScore)} " +
                    "gap=${"%.3f".format(result.candidateScoreGap)} " +
                    "candidates=${result.assistCandidateCount}/${result.scoredCandidateCount}"
            }
            if (frame >= 8) {
                val globalAnchor = globalBase + result.anchorIndex
                val visiblePhaseError =
                    risingPhaseErrorRatio(sweptFundamentalPhase(globalAnchor))
                val coreGlobalAnchor = globalBase + result.coreAnchorIndex
                val corePhaseError =
                    risingPhaseErrorRatio(sweptFundamentalPhase(coreGlobalAnchor))
                visiblePhaseErrors += visiblePhaseError
                corePhaseErrors += corePhaseError

                // The low-frequency display bridge is presentation-only and may move within
                // 2.5% of the estimated period. Its output is therefore not the core phase
                // estimator; verify both contracts independently.
                val displayCorrection = abs(result.anchorIndex - result.coreAnchorIndex)
                val allowedDisplayCorrection =
                    maxOf(4, (result.periodSamples * 0.025f).roundToInt()) + 1
                if (displayCorrection > allowedDisplayCorrection) {
                    displayCorrectionViolations +=
                        "frame=$frame correction=$displayCorrection " +
                        "allowed=$allowedDisplayCorrection period=${result.periodSamples}"
                }
                if (visiblePhaseError > 0.03 || corePhaseError > 0.03) {
                    visiblePhaseErrorDiagnostics +=
                        "frame=$frame visible=${"%.4f".format(visiblePhaseError)} " +
                        "core=${"%.4f".format(corePhaseError)} " +
                        "coreAnchor=${result.coreAnchorIndex} out=${result.anchorIndex} " +
                        "period=${result.periodSamples} pred=${result.predictedAnchorIndex} " +
                        "display=${result.displayAlignmentApplied}/" +
                        "${"%.3f".format(result.displayCenterScore)}/" +
                        "${"%.3f".format(result.displayBestScore)}"
                }
                estimatedFrequencies += result.freqHz
                adaptiveWeights += result.corrScopeWeight
            }
        }

        val maxCorePhaseError = corePhaseErrors.maxOrNull() ?: 1.0
        assertTrue(
            "max core phase error=$maxCorePhaseError, errors=$corePhaseErrors, " +
                "frequencies=$estimatedFrequencies diagnostics=$phaseDiagnostics " +
                "visibleErrors=$visiblePhaseErrorDiagnostics",
            maxCorePhaseError <= 0.03,
        )
        assertTrue(
            "display correction violations=$displayCorrectionViolations",
            displayCorrectionViolations.isEmpty(),
        )
        val maxVisiblePhaseError = visiblePhaseErrors.maxOrNull() ?: 1.0
        assertTrue(
            "max visible phase error=$maxVisiblePhaseError, errors=$visiblePhaseErrors, " +
                "diagnostics=$visiblePhaseErrorDiagnostics",
            maxVisiblePhaseError <= 0.07,
        )
        val maximumWeightStep = adaptiveWeights.zipWithNext { previous, current ->
            abs(current - previous)
        }.maxOrNull() ?: 0f
        assertTrue(
            "weight step=$maximumWeightStep weights=$adaptiveWeights",
            maximumWeightStep <= 0.20f,
        )
    }

    @Test
    fun varyingCarrierLevelDoesNotToggleAwayFromLowFundamental() {
        val frequency = 30f
        val engine = SimpleTriggerEngine()
        val samplesPerFrame = (sampleRate / 60f).roundToInt()
        val fundamentalPeriod = (sampleRate / frequency).roundToInt()
        val phases = ArrayList<Int>()
        val estimatedFrequencies = ArrayList<Float>()
        val adaptiveWeights = ArrayList<Float>()

        repeat(96) { frame ->
            val globalBase = frame.toLong() * samplesPerFrame
            val source = varyingCarrierFrame(
                globalBase = globalBase,
                frequency = frequency,
                size = 4_800,
            )
            val result = engine.process(
                source,
                config(globalBase).copy(displayWindowSamples = 2_400),
            )
            assertTrue("frame=$frame did not lock", result.locked)
            if (frame >= 8) {
                phases += floorMod(globalBase + result.anchorIndex, fundamentalPeriod)
                estimatedFrequencies += result.freqHz
                adaptiveWeights += result.corrScopeWeight
            }
        }

        val reference = phases.sorted()[phases.size / 2]
        val maxPhaseJump = phases.maxOf { phase ->
            val direct = abs(phase - reference)
            minOf(direct, fundamentalPeriod - direct)
        }
        assertTrue(
            "max phase jump=$maxPhaseJump, phases=$phases, frequencies=$estimatedFrequencies, " +
                "weights=$adaptiveWeights",
            maxPhaseJump <= (fundamentalPeriod * 0.08f).roundToInt(),
        )
        assertTrue(
            "expected=$frequency frequencies=$estimatedFrequencies",
            estimatedFrequencies.all { abs(it - frequency) <= 3f },
        )
    }

    private fun config(globalBase: Long) = SimpleTriggerEngine.Config(
        mode = SimpleTriggerEngine.Mode.RISING,
        sampleRateHz = sampleRate,
        globalBase = globalBase,
        triggerThreshold = 0.02f,
    )

    private fun sineFrame(
        globalBase: Long,
        frequency: Float = frequencyHz,
        size: Int = frameSize,
    ): FloatArray = FloatArray(size) { index ->
        sin(2.0 * PI * frequency * (globalBase + index) / sampleRate).toFloat()
    }

    private fun highFrequencyHarmonicFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val phase = 2.0 * PI * frequency * sample / sampleRate
        (
            0.12 * sin(phase) +
                0.52 * sin(5.0 * phase + 0.35) +
                0.18 * sin(7.0 * phase - 0.70)
            ).toFloat()
    }

    private fun noisyHarmonicFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val phase = 2.0 * PI * frequency * sample / sampleRate
        val hash = (sample * 1_664_525L + 1_013_904_223L) xor (sample shl 11)
        val noise = ((hash and 0xffffL) / 32767.5) - 1.0
        (
            0.16 * sin(phase) +
                0.52 * sin(5.0 * phase + 0.35) +
                0.18 * sin(7.0 * phase - 0.70) +
                0.035 * noise
            ).toFloat()
    }

    private fun phaseFraction(sample: Long, frequency: Float): Double {
        val turns = frequency.toDouble() * sample / sampleRate
        return turns - floor(turns)
    }

    private fun normalizedCorrelation(lhs: FloatArray, rhs: FloatArray): Float {
        val count = minOf(lhs.size, rhs.size)
        val lhsMean = lhs.take(count).average().toFloat()
        val rhsMean = rhs.take(count).average().toFloat()
        var dot = 0f
        var lhsEnergy = 0f
        var rhsEnergy = 0f
        for (i in 0 until count) {
            val left = lhs[i] - lhsMean
            val right = rhs[i] - rhsMean
            dot += left * right
            lhsEnergy += left * left
            rhsEnergy += right * right
        }
        return dot / kotlin.math.sqrt(lhsEnergy * rhsEnergy).coerceAtLeast(1e-6f)
    }

    private fun spwmLineFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
        carrierMultiple: Double = 18.0,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val fundamentalPhase = 2.0 * PI * frequency * sample / sampleRate
        val carrierPhase =
            ((sample * frequency * carrierMultiple / sampleRate) % 1.0 + 1.0) % 1.0
        val carrier = 4.0 * abs(carrierPhase - 0.5) - 1.0
        val u = if (0.55 * sin(fundamentalPhase) > carrier) 1f else 0f
        val v = if (0.55 * sin(fundamentalPhase - 2.0 * PI / 3.0) > carrier) 1f else 0f
        u - v
    }

    private fun midFrequencyChatterFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
        phaseOffset: Double,
        frame: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val t = sample / sampleRate.toDouble()
        val phase = 2.0 * PI * frequency * t + phaseOffset
        val chatter = 0.016 * sin(2.0 * PI * 920.0 * t + frame * 0.35)
        (
            0.11 * sin(phase) +
                0.26 * sin(5.0 * phase) +
                0.12 * sin(7.0 * phase) +
                0.05 * sin(9.0 * phase) +
                chatter
            ).toFloat()
    }

    private fun floorMod(value: Long, modulus: Int): Int {
        val result = (value % modulus).toInt()
        return if (result >= 0) result else result + modulus
    }

    private fun driftingHarmonicFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
        frame: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val phase = 2.0 * PI * frequency * sample / sampleRate - PI / 2.0
        val shapeDrift = frame * 0.11
        (
            0.08 * sin(phase) +
                0.34 * sin(5.0 * phase + shapeDrift) +
                0.15 * sin(7.0 * phase - shapeDrift * 0.6) +
                0.07 * sin(9.0 * phase + shapeDrift * 0.35)
            ).toFloat()
    }

    private fun sweptHarmonicFrame(
        globalBase: Long,
        size: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val phase = sweptFundamentalPhase(sample)
        val t = sample / sampleRate.toDouble()
        val shapeDrift = 0.45 * sin(2.0 * PI * 0.7 * t)
        (
            0.12 * sin(phase) +
                0.28 * sin(5.0 * phase + shapeDrift) +
                0.13 * sin(7.0 * phase - shapeDrift * 0.5) +
                0.05 * sin(9.0 * phase + shapeDrift * 0.25)
            ).toFloat()
    }

    private fun sweptFundamentalPhase(sample: Long): Double {
        val t = sample / sampleRate.toDouble()
        val startHz = 15.0
        val sweepHzPerSecond = 32.0
        return 2.0 * PI * (startHz * t + 0.5 * sweepHzPerSecond * t * t) - PI / 2.0
    }

    private fun risingPhaseErrorRatio(phase: Double): Double {
        val turns = phase / (2.0 * PI)
        val fraction = turns - floor(turns)
        return minOf(fraction, 1.0 - fraction)
    }

    private fun varyingCarrierFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val t = sample / sampleRate.toDouble()
        val fundamentalPhase = 2.0 * PI * frequency * t
        val carrierAmplitude = 0.05 + 0.90 * (0.5 + 0.5 * sin(2.0 * PI * 1.1 * t))
        (
            0.08 * sin(fundamentalPhase) +
                carrierAmplitude * sin(2.0 * PI * 617.0 * t + 0.35)
            ).toFloat()
    }

    private fun assertEdgeAtDisplayReference(
        source: FloatArray,
        result: SimpleTriggerEngine.Result,
        displayWindowSize: Int = displaySize,
    ) {
        val expectedEdge = (displayWindowSize * 0.20f).toInt()
        val window = SimpleTriggerEngine().extractWindow(source, result, displayWindowSize, 0.20f)
        val nearestRisingEdge = (1 until window.size)
            .filter { window[it - 1] < 0f && window[it] >= 0f }
            .minByOrNull { abs(it - expectedEdge) }
        assertTrue(
            "display edge=$nearestRisingEdge, trigger=${result.anchorIndex}",
            nearestRisingEdge != null && abs(nearestRisingEdge - expectedEdge) <= 4,
        )
    }
}
