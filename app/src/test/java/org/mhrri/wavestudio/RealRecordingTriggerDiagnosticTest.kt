package org.mhrri.wavestudio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealRecordingTriggerDiagnosticTest {
    @Test
    fun cp013DoesNotReverseVisiblePhaseNearSixteenPointSevenSeconds() {
        val pcmPath = System.getenv("OSCOPE_REAL_TRIGGER_PCM").orEmpty()
        assumeTrue(
            "Set OSCOPE_REAL_TRIGGER_PCM to a decoded mono 48 kHz PCM16 file",
            pcmPath.isNotBlank() && File(pcmPath).isFile,
        )
        val sampleRate = 48_000
        val analysisSamples = 4_800
        val displaySamples = 1_200
        val publishStepSamples = 2_880
        val playbackStartSample = 10 * sampleRate
        val measurementStartSample = (16.58 * sampleRate).toInt()
        val endSample = (16.77 * sampleRate).toInt()
        val engine = SimpleTriggerEngine(windowSize = 512)
        data class VisiblePhaseEvent(
            val pair: Int,
            val shift: Int,
            val details: String,
        )
        val qualifiedEvents = ArrayList<VisiblePhaseEvent>()
        val frameDiagnostics = ArrayList<String>()
        var previousWindow: FloatArray? = null
        var previousGlobalAnchor: Long? = null
        var previousPeriodSamples: Int? = null
        var measuredPairs = 0
        var lockDrops = 0
        var cursor = playbackStartSample + analysisSamples

        RandomAccessFile(pcmPath, "r").use { pcm ->
            while (cursor < endSample) {
                val signalStart = cursor - analysisSamples
                val signal = readPcm16(pcm, signalStart, analysisSamples)
                val result = engine.process(
                    signal,
                    SimpleTriggerEngine.Config(
                        mode = SimpleTriggerEngine.Mode.RISING,
                        sampleRateHz = sampleRate.toFloat(),
                        preTriggerRatio = 0.20f,
                        displayWindowSamples = displaySamples,
                        displayAlignmentPoints = 512,
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (!result.locked) {
                    if (cursor >= measurementStartSample) lockDrops += 1
                    previousWindow = null
                    previousGlobalAnchor = null
                    previousPeriodSamples = null
                    cursor += publishStepSamples
                    continue
                }

                val displayedWindow = downsamplePeakFloatArray(
                    engine.extractWindow(
                        source = signal,
                        result = result,
                        targetSize = displaySamples,
                        preTriggerRatio = 0.20f,
                    ),
                    0,
                    displaySamples,
                    512,
                )
                val globalAnchor = signalStart.toLong() + result.anchorIndex
                if (cursor >= measurementStartSample) {
                    val priorWindow = previousWindow
                    val priorAnchor = previousGlobalAnchor
                    val priorPeriod = previousPeriodSamples
                    if (priorWindow != null && priorAnchor != null && priorPeriod != null) {
                        measuredPairs += 1
                        val evidence = fixedRoiAlignmentEvidence(
                            priorWindow,
                            displayedWindow,
                            maximumShift = 48,
                            zeroBandRadius = 6,
                        )
                        val meanPeriod = (priorPeriod + result.periodSamples) * 0.5
                        val globalDelta = globalAnchor - priorAnchor
                        val cycleCount = kotlin.math.round(globalDelta / meanPeriod)
                        val residualSamples = globalDelta - cycleCount * meanPeriod
                        val phaseResidualDisplayPoints =
                            abs(residualSamples) * 512.0 / displaySamples
                        val details =
                            "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                "state=${result.phaseIdentityState} " +
                                "accepted=${result.coreObservationAccepted} " +
                                "period=${result.periodSamples} " +
                                "shift=${evidence.bestShift} " +
                                "best=${"%.3f".format(evidence.largeBandScore)} " +
                                "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                "phaseResidual=${"%.2f".format(phaseResidualDisplayPoints)} " +
                                "predicted=${result.predictedAnchorIndex} " +
                                "selected=${result.selectedCandidateAnchorIndex} " +
                                "core=${result.coreAnchorIndex} out=${result.anchorIndex} " +
                                "display=${result.displayAlignmentApplied}/" +
                                "${"%.3f".format(result.displayCenterScore)}/" +
                                "${"%.3f".format(result.displayBestScore)}/" +
                                "${"%.3f".format(result.displayPeakScoreGap)} " +
                                "score=${"%.3f".format(result.triggerScore)} " +
                                "gap=${"%.3f".format(result.candidateScoreGap)} " +
                                "candidates=${result.rawCandidateCount}/" +
                                "${result.assistCandidateCount}/${result.scoredCandidateCount}"
                        frameDiagnostics += details
                        if (abs(evidence.bestShift) > 6 &&
                            evidence.largeBandScore >= 0.75f &&
                            evidence.largeBandScore - evidence.zeroBandScore >= 0.05f &&
                            phaseResidualDisplayPoints > 6.0
                        ) {
                            qualifiedEvents += VisiblePhaseEvent(
                                pair = measuredPairs,
                                shift = evidence.bestShift,
                                details = details,
                            )
                        }
                    }
                }
                previousWindow = displayedWindow
                previousGlobalAnchor = globalAnchor
                previousPeriodSamples = result.periodSamples
                cursor += publishStepSamples
            }
        }

        val reversals = qualifiedEvents.zipWithNext().filter { (previous, current) ->
            current.pair - previous.pair == 1 && previous.shift * current.shift < 0
        }
        println(
            "[REAL-TRIGGER-16.7] measuredPairs=$measuredPairs lockDrops=$lockDrops " +
                "qualified=${qualifiedEvents.size} reversals=${reversals.size} " +
                "frames=$frameDiagnostics",
        )
        assertTrue(
            "measuredPairs=$measuredPairs lockDrops=$lockDrops frames=$frameDiagnostics " +
                "qualified=${qualifiedEvents.map { it.details }} " +
                "reversals=${reversals.map { (a, b) -> "${a.details} -> ${b.details}" }}",
            measuredPairs >= 3 && lockDrops == 0 && reversals.isEmpty(),
        )
    }

    @Test
    fun cp013DoesNotTogglePhaseIdentityNearTwentyThreePointNineSeconds() {
        val pcmPath = System.getenv("OSCOPE_REAL_TRIGGER_PCM").orEmpty()
        assumeTrue(
            "Set OSCOPE_REAL_TRIGGER_PCM to a decoded mono 48 kHz PCM16 file",
            pcmPath.isNotBlank() && File(pcmPath).isFile,
        )
        val sampleRate = 48_000
        val analysisSamples = 4_800
        val displaySamples = 1_200
        val publishStepSamples = 2_880
        val playbackStartSample = 10 * sampleRate
        val measurementStartSample = (23.78 * sampleRate).toInt()
        val endSample = (23.97 * sampleRate).toInt()
        val engine = SimpleTriggerEngine(windowSize = 512)
        data class PhaseIdentityEvent(
            val frame: Int,
            val shift: Int,
            val phaseResidualPoints: Double,
            val details: String,
        )
        val qualifiedEvents = ArrayList<PhaseIdentityEvent>()
        val frameDiagnostics = ArrayList<String>()
        val holdoverVisualViolations = ArrayList<String>()
        var previousWindow: FloatArray? = null
        var previousGlobalAnchor: Long? = null
        var previousPeriodSamples: Int? = null
        var lastAcceptedDisplayOffsetSamples = 0
        var lockDrops = 0
        var measuredPairs = 0
        var cursor = playbackStartSample + analysisSamples

        RandomAccessFile(pcmPath, "r").use { pcm ->
            while (cursor < endSample) {
                val signalStart = cursor - analysisSamples
                val signal = readPcm16(pcm, signalStart, analysisSamples)
                val result = engine.process(
                    signal,
                    SimpleTriggerEngine.Config(
                        mode = SimpleTriggerEngine.Mode.RISING,
                        sampleRateHz = sampleRate.toFloat(),
                        preTriggerRatio = 0.20f,
                        displayWindowSamples = displaySamples,
                        displayAlignmentPoints = 512,
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (!result.locked) {
                    if (cursor >= measurementStartSample) lockDrops += 1
                    previousWindow = null
                    previousGlobalAnchor = null
                    previousPeriodSamples = null
                    cursor += publishStepSamples
                    continue
                }

                val globalAnchor = signalStart.toLong() + result.anchorIndex
                val displayedWindow = downsamplePeakFloatArray(
                    engine.extractWindow(
                        source = signal,
                        result = result,
                        targetSize = displaySamples,
                        preTriggerRatio = 0.20f,
                    ),
                    0,
                    displaySamples,
                    512,
                )
                if (cursor >= measurementStartSample) {
                    val priorWindow = previousWindow
                    val priorAnchor = previousGlobalAnchor
                    val priorPeriod = previousPeriodSamples
                    if (priorWindow != null && priorAnchor != null && priorPeriod != null) {
                        measuredPairs += 1
                        val evidence = fixedRoiAlignmentEvidence(
                            priorWindow,
                            displayedWindow,
                            maximumShift = 48,
                            zeroBandRadius = 6,
                        )
                        val meanPeriod = (priorPeriod + result.periodSamples) * 0.5
                        val globalDelta = globalAnchor - priorAnchor
                        val cycleCount = kotlin.math.round(globalDelta / meanPeriod)
                        val residualSamples = globalDelta - cycleCount * meanPeriod
                        val signedPhaseResidualDisplayPoints =
                            residualSamples * 512.0 / displaySamples
                        val predicted = result.predictedAnchorIndex
                        val predictedEvidence =
                            if (result.phaseIdentityState ==
                                SimpleTriggerEngine.PhaseIdentityState.HOLDOVER &&
                                predicted != null
                            ) {
                                val predictedWindow = downsamplePeakFloatArray(
                                    engine.extractWindow(
                                        source = signal,
                                        result = result.copy(anchorIndex = predicted),
                                        targetSize = displaySamples,
                                        preTriggerRatio = 0.20f,
                                    ),
                                    0,
                                    displaySamples,
                                    512,
                                )
                                fixedRoiAlignmentEvidence(
                                    priorWindow,
                                    predictedWindow,
                                    maximumShift = 48,
                                    zeroBandRadius = 6,
                                )
                            } else {
                                null
                            }
                        val predictedWithOffsetEvidence =
                            if (result.phaseIdentityState ==
                                SimpleTriggerEngine.PhaseIdentityState.HOLDOVER &&
                                predicted != null
                            ) {
                                val predictedWithOffsetWindow = downsamplePeakFloatArray(
                                    engine.extractWindow(
                                        source = signal,
                                        result = result.copy(
                                            anchorIndex =
                                                predicted + lastAcceptedDisplayOffsetSamples,
                                        ),
                                        targetSize = displaySamples,
                                        preTriggerRatio = 0.20f,
                                    ),
                                    0,
                                    displaySamples,
                                    512,
                                )
                                fixedRoiAlignmentEvidence(
                                    priorWindow,
                                    predictedWithOffsetWindow,
                                    maximumShift = 48,
                                    zeroBandRadius = 6,
                                )
                            } else {
                                null
                            }
                        frameDiagnostics +=
                            "t=${"%.2f".format(cursor.toDouble() / sampleRate)} " +
                            "state=${result.phaseIdentityState} " +
                            "accepted=${result.coreObservationAccepted} " +
                            "period=${result.periodSamples} " +
                            "predicted=${result.predictedAnchorIndex} " +
                            "selected=${result.selectedCandidateAnchorIndex} " +
                            "core=${result.coreAnchorIndex} " +
                            "out=${result.anchorIndex} " +
                            "residual=${"%.2f".format(signedPhaseResidualDisplayPoints)} " +
                            "fixed=${evidence.bestShift}/" +
                            "${"%.3f".format(evidence.largeBandScore)}/" +
                            "${"%.3f".format(evidence.zeroBandScore)} " +
                            "predFixed=${predictedEvidence?.let {
                                "${it.bestShift}/" +
                                    "${"%.3f".format(it.largeBandScore)}/" +
                                    "${"%.3f".format(it.zeroBandScore)}"
                            }} " +
                            "predOffsetFixed=${predictedWithOffsetEvidence?.let {
                                "${it.bestShift}/" +
                                    "${"%.3f".format(it.largeBandScore)}/" +
                                    "${"%.3f".format(it.zeroBandScore)}"
                            }} " +
                            "score=${"%.3f".format(result.confidence)} " +
                            "assist=${"%.3f".format(result.assistScore)} " +
                            "gap=${"%.3f".format(result.candidateScoreGap)} " +
                            "display=${"%.3f".format(result.displayCenterScore)}/" +
                            "${"%.3f".format(result.displayBestScore)}/" +
                            "${"%.3f".format(result.displayPeakScoreGap)} " +
                            "candidates=${result.rawCandidateCount}/" +
                            "${result.assistCandidateCount}/${result.scoredCandidateCount}"
                        if (result.phaseIdentityState ==
                            SimpleTriggerEngine.PhaseIdentityState.HOLDOVER &&
                            abs(evidence.bestShift) > 6
                        ) {
                            holdoverVisualViolations += frameDiagnostics.last()
                        }
                        if (abs(evidence.bestShift) > 6 &&
                            evidence.largeBandScore >= 0.70f &&
                            evidence.largeBandScore - evidence.zeroBandScore >= 0.15f &&
                            abs(signedPhaseResidualDisplayPoints) >= 64.0 &&
                            evidence.bestShift * signedPhaseResidualDisplayPoints > 0.0
                        ) {
                            qualifiedEvents += PhaseIdentityEvent(
                                frame = measuredPairs,
                                shift = evidence.bestShift,
                                phaseResidualPoints = signedPhaseResidualDisplayPoints,
                                details =
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                        "f=${"%.2f".format(result.freqHz)}Hz " +
                                        "period=${result.periodSamples} " +
                                        "shift=${evidence.bestShift} " +
                                        "best=${"%.3f".format(evidence.largeBandScore)} " +
                                        "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                        "phaseResidual=" +
                                        "${"%.2f".format(signedPhaseResidualDisplayPoints)} " +
                                        "predicted=${result.predictedAnchorIndex} " +
                                        "selected=${result.selectedCandidateAnchorIndex} " +
                                        "core=${result.coreAnchorIndex} out=${result.anchorIndex} " +
                                        "candidates=${result.rawCandidateCount}/" +
                                        "${result.assistCandidateCount}/${result.scoredCandidateCount}",
                            )
                        }
                    }
                }
                previousWindow = displayedWindow
                previousGlobalAnchor = globalAnchor
                previousPeriodSamples = result.periodSamples
                if (result.coreObservationAccepted) {
                    lastAcceptedDisplayOffsetSamples = result.displayOffsetSamples
                }
                cursor += publishStepSamples
            }
        }

        val identityToggles =
            qualifiedEvents.zipWithNext().filter { (previous, current) ->
                current.frame - previous.frame == 1 &&
                    previous.shift * current.shift < 0 &&
                    previous.phaseResidualPoints * current.phaseResidualPoints < 0.0 &&
                    abs(previous.phaseResidualPoints + current.phaseResidualPoints) <= 64.0
            }
        val toggleDetails = identityToggles.map { (previous, current) ->
            "${previous.details} -> ${current.details}"
        }
        println(
            "[REAL-TRIGGER-23.9] measuredPairs=$measuredPairs lockDrops=$lockDrops " +
                "qualified=${qualifiedEvents.size} toggles=${identityToggles.size} " +
                "frames=$frameDiagnostics",
        )
        assertTrue(
            "measuredPairs=$measuredPairs lockDrops=$lockDrops " +
                "frames=$frameDiagnostics " +
                "holdoverVisualViolations=$holdoverVisualViolations " +
                "qualifiedEvents=${qualifiedEvents.map { it.details }} " +
                "identityToggles=$toggleDetails",
            measuredPairs >= 3 &&
                lockDrops == 0 &&
                holdoverVisualViolations.isEmpty() &&
                qualifiedEvents.isEmpty() &&
                identityToggles.isEmpty(),
        )
    }

    @Test
    fun importedRecordingKeepsLockAndProtectsTerminalSubrangeFromFifteenToThirtyFiveSeconds() {
        val pcmPath = System.getenv("OSCOPE_REAL_TRIGGER_PCM").orEmpty()
        assumeTrue(
            "Set OSCOPE_REAL_TRIGGER_PCM to a decoded mono 48 kHz PCM16 file",
            pcmPath.isNotBlank() && File(pcmPath).isFile,
        )
        val sampleRate = 48_000
        val analysisSamples = 4_800
        val displaySamples = 1_200
        val publishStepSamples = 2_880
        val playbackStartSample = 10 * sampleRate
        val measurementStartSample = 15 * sampleRate
        val endSample = 35 * sampleRate
        val engine = SimpleTriggerEngine(windowSize = 512)
        val displayShifts = ArrayList<Int>()
        val highFrequencyShifts = ArrayList<Int>()
        val terminalMidFrequencyShifts = ArrayList<Int>()
        val frequencies = ArrayList<Float>()
        val weights = ArrayList<Float>()
        val largeShiftEvents = ArrayList<String>()
        val frequencyBins = sortedMapOf<Int, Int>()
        val shiftsBySecond = sortedMapOf<Int, MutableList<Int>>()
        val residualsBySecond = sortedMapOf<Int, MutableList<Float>>()
        val highConfidencePhaseJumps = ArrayList<String>()
        val terminalHighConfidencePhaseJumps = ArrayList<String>()
        var lockDrops = 0
        var previousWindow: FloatArray? = null
        var previousGlobalAnchor: Long? = null
        var previousPeriodSamples: Int? = null
        var cursor = playbackStartSample + analysisSamples

        RandomAccessFile(pcmPath, "r").use { pcm ->
            while (cursor < endSample) {
                val signalStart = cursor - analysisSamples
                val signal = readPcm16(pcm, signalStart, analysisSamples)
                val result = engine.process(
                    signal,
                    SimpleTriggerEngine.Config(
                        mode = SimpleTriggerEngine.Mode.RISING,
                        sampleRateHz = sampleRate.toFloat(),
                        preTriggerRatio = 0.20f,
                        displayWindowSamples = displaySamples,
                        displayAlignmentPoints = 512,
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (cursor >= measurementStartSample) {
                    if (!result.locked) {
                        lockDrops += 1
                        previousWindow = null
                        previousGlobalAnchor = null
                        previousPeriodSamples = null
                    } else {
                        frequencies += result.freqHz
                        weights += result.corrScopeWeight
                        val bin = (result.freqHz / 5f).toInt() * 5
                        frequencyBins[bin] = (frequencyBins[bin] ?: 0) + 1
                        val window = engine.extractWindow(
                            source = signal,
                            result = result,
                            targetSize = displaySamples,
                            preTriggerRatio = 0.20f,
                        )
                        val displayedWindow =
                            downsamplePeakFloatArray(window, 0, window.size, 512)
                        previousWindow?.let { previous ->
                            val shift = bestAlignmentShift(previous, displayedWindow, 48)
                            val evidence =
                                fixedRoiAlignmentEvidence(previous, displayedWindow, 48, 6)
                            displayShifts += shift
                            val timeSeconds = cursor.toDouble() / sampleRate
                            if (timeSeconds in 15.20..16.10 &&
                                result.freqHz in 200f..350f
                            ) {
                                highFrequencyShifts += shift
                            }
                            if (timeSeconds >= 33.0 &&
                                result.freqHz in 40f..65f
                            ) {
                                terminalMidFrequencyShifts += shift
                            }
                            shiftsBySecond.getOrPut(cursor / sampleRate) { ArrayList() }
                                .add(shift)
                            val globalAnchor = signalStart.toLong() + result.anchorIndex
                            val priorAnchor = previousGlobalAnchor
                            val priorPeriod = previousPeriodSamples
                            val phaseResidualDisplayPoints =
                                if (priorAnchor != null && priorPeriod != null) {
                                    val meanPeriod =
                                        (priorPeriod + result.periodSamples) * 0.5
                                    val globalDelta = globalAnchor - priorAnchor
                                    val cycleCount =
                                        kotlin.math.round(globalDelta / meanPeriod)
                                    val residualSamples =
                                        globalDelta - cycleCount * meanPeriod
                                    abs(residualSamples) * 512.0 / displaySamples
                                } else {
                                    0.0
                                }
                            val highConfidenceJump =
                                abs(evidence.bestShift) > 6 &&
                                    evidence.largeBandScore >= 0.75f &&
                                    evidence.largeBandScore - evidence.zeroBandScore >= 0.05f &&
                                    phaseResidualDisplayPoints > 6.0
                            if (highConfidenceJump) {
                                val event =
                                    "t=${"%.2f".format(timeSeconds)}s shift=$shift " +
                                    "best=${"%.3f".format(evidence.largeBandScore)} " +
                                    "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                    "phaseResidual=${"%.2f".format(phaseResidualDisplayPoints)}"
                                highConfidencePhaseJumps += event
                                if (timeSeconds >= 33.0 && result.freqHz in 40f..65f) {
                                    terminalHighConfidencePhaseJumps += event
                                }
                            }
                            if (abs(shift) > 6 && largeShiftEvents.size < 50) {
                                largeShiftEvents +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz shift=$shift " +
                                    "period=${result.periodSamples} " +
                                    "weight=${"%.3f".format(result.corrScopeWeight)} " +
                                    "score=${"%.3f".format(result.triggerScore)}"
                            }
                        }
                        previousWindow = displayedWindow
                        val globalAnchor = signalStart.toLong() + result.anchorIndex
                        previousGlobalAnchor?.let { previous ->
                            val delta = globalAnchor - previous
                            val meanPeriod =
                                ((previousPeriodSamples ?: result.periodSamples) +
                                    result.periodSamples) * 0.5
                            val nearestPeriods = kotlin.math.round(delta / meanPeriod)
                            val residual =
                                abs(delta - nearestPeriods * meanPeriod)
                                    .toFloat()
                            residualsBySecond.getOrPut(cursor / sampleRate) { ArrayList() }
                                .add(residual)
                        }
                        previousGlobalAnchor = globalAnchor
                        previousPeriodSamples = result.periodSamples
                    }
                }
                cursor += publishStepSamples
            }
        }

        val sortedFrequencies = frequencies.sorted()
        val medianFrequency =
            sortedFrequencies.getOrElse(sortedFrequencies.size / 2) { Float.NaN }
        val largeDisplayShifts = displayShifts.count { abs(it) > 6 }
        val maximumDisplayShift = displayShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        val directionReversals =
            displayShifts.zipWithNext().count { (previous, current) ->
                abs(previous) > 6 && abs(current) > 6 && previous * current < 0
            }
        val highFrequencyDirectionReversals =
            highFrequencyShifts.zipWithNext().count { (previous, current) ->
                abs(previous) > 6 && abs(current) > 6 && previous * current < 0
            }
        val terminalLargeShifts =
            terminalMidFrequencyShifts.count { abs(it) > 6 }
        val terminalMaximumShift =
            terminalMidFrequencyShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        val perSecondSummary = shiftsBySecond.map { (second, shifts) ->
            val residuals = residualsBySecond[second].orEmpty()
            "$second:" +
                "n=${shifts.size}," +
                "large=${shifts.count { abs(it) > 6 }}," +
                "max=${shifts.maxOfOrNull { abs(it) }}," +
                "resLarge=${residuals.count { it > 24f }}," +
                "resMax=${residuals.maxOrNull()}"
        }
        println(
            "[REAL-TRIGGER-15-35] locked=${frequencies.size} lockDrops=$lockDrops " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "directionReversals=$directionReversals " +
                "highSamples=${highFrequencyShifts.size} " +
                "highReversals=$highFrequencyDirectionReversals " +
                "terminalSamples=${terminalMidFrequencyShifts.size} " +
                "terminalLarge=$terminalLargeShifts " +
                "terminalMax=$terminalMaximumShift " +
                "highConfidencePhaseJumps=${highConfidencePhaseJumps.size} " +
                "perSecond=$perSecondSummary",
        )
        assertTrue(
            "locked=${frequencies.size} lockDrops=$lockDrops " +
                "freqMin=${frequencies.minOrNull()} freqMedian=$medianFrequency " +
                "freqMax=${frequencies.maxOrNull()} bins=$frequencyBins " +
                "weightMin=${weights.minOrNull()} weightMax=${weights.maxOrNull()} " +
                "displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "directionReversals=$directionReversals " +
                "highConfidencePhaseJumps=$highConfidencePhaseJumps " +
                "terminalHighConfidencePhaseJumps=$terminalHighConfidencePhaseJumps " +
                "perSecond=$perSecondSummary events=$largeShiftEvents",
            frequencies.size >= 300 &&
                lockDrops == 0 &&
                displayShifts.size >= 300 &&
                highFrequencyShifts.size >= 10 &&
                highFrequencyDirectionReversals <= 1 &&
                terminalMidFrequencyShifts.size >= 30 &&
                terminalLargeShifts <= 4 &&
                terminalMaximumShift <= 40 &&
                terminalHighConfidencePhaseJumps.isEmpty(),
        )
    }

    @Test
    fun importedRecordingKeepsContinuousTriggerPhaseFromFiftyToSixtyFiveHertz() {
        val pcmPath = System.getenv("OSCOPE_REAL_TRIGGER_PCM").orEmpty()
        assumeTrue(
            "Set OSCOPE_REAL_TRIGGER_PCM to a decoded mono 48 kHz PCM16 file",
            pcmPath.isNotBlank() && File(pcmPath).isFile,
        )
        val sampleRate = 48_000
        val analysisSamples = 4_800
        val displaySamples = 1_200
        val publishStepSamples = 2_880
        val playbackStartSample = 150 * sampleRate
        val endSample = 175 * sampleRate
        val engine = SimpleTriggerEngine(windowSize = 512)
        val offsetJumps = ArrayList<Int>()
        val displayShifts = ArrayList<Int>()
        val significantDisplayShifts = ArrayList<Pair<Int, Int>>()
        val events = ArrayList<String>()
        val displayEvents = ArrayList<String>()
        val highConfidencePhaseJumps = ArrayList<String>()
        var previousOffset: Int? = null
        var previousWindow: FloatArray? = null
        var previousGlobalAnchor: Long? = null
        var previousPeriodSamples: Int? = null
        var processedFrames = 0
        var lockDrops = 0
        var cursor = playbackStartSample + analysisSamples

        RandomAccessFile(pcmPath, "r").use { pcm ->
            while (cursor < endSample) {
                processedFrames += 1
                val signalStart = cursor - analysisSamples
                val signal = readPcm16(pcm, signalStart, analysisSamples)
                val result = engine.process(
                    signal,
                    SimpleTriggerEngine.Config(
                        mode = SimpleTriggerEngine.Mode.RISING,
                        sampleRateHz = sampleRate.toFloat(),
                        preTriggerRatio = 0.20f,
                        displayWindowSamples = displaySamples,
                        displayAlignmentPoints = 512,
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (!result.locked) {
                    lockDrops += 1
                    previousOffset = null
                    previousWindow = null
                    previousGlobalAnchor = null
                    previousPeriodSamples = null
                } else if (result.periodSamples > 0) {
                    val offset = result.assistPhaseOffsetSamples
                    if (result.freqHz in 50f..65f && offset != null) {
                        val window = engine.extractWindow(
                            source = signal,
                            result = result,
                            targetSize = displaySamples,
                            preTriggerRatio = 0.20f,
                        )
                        val displayedWindow =
                            downsamplePeakFloatArray(window, 0, window.size, 512)
                        previousWindow?.let { previous ->
                            val evidence =
                                fixedRoiAlignmentEvidence(previous, displayedWindow, 48, 6)
                            // Trend guard keeps the historical end-to-end visual metric so the
                            // pre-fix baseline remains comparable. Fixed-ROI evidence below is
                            // used only to classify high-confidence phase jumps.
                            val shift = bestAlignmentShift(previous, displayedWindow, 48)
                            val pairIndex = displayShifts.size
                            displayShifts += shift
                            if (abs(shift) > 6) {
                                significantDisplayShifts += pairIndex to shift
                            }
                            val globalAnchor = signalStart.toLong() + result.anchorIndex
                            val priorAnchor = previousGlobalAnchor
                            val priorPeriod = previousPeriodSamples
                            val phaseResidualDisplayPoints =
                                if (priorAnchor != null && priorPeriod != null) {
                                    val meanPeriod =
                                        (priorPeriod + result.periodSamples) * 0.5
                                    val globalDelta = globalAnchor - priorAnchor
                                    val cycleCount =
                                        kotlin.math.round(globalDelta / meanPeriod)
                                    val residualSamples =
                                        globalDelta - cycleCount * meanPeriod
                                    abs(residualSamples) * 512.0 / displaySamples
                                } else {
                                    0.0
                                }
                            val isHighConfidencePhaseJump =
                                abs(evidence.bestShift) > 6 &&
                                    evidence.largeBandScore >= 0.75f &&
                                    evidence.largeBandScore - evidence.zeroBandScore >= 0.05f &&
                                    phaseResidualDisplayPoints > 6.0
                            if (isHighConfidencePhaseJump) {
                                highConfidencePhaseJumps +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "shift=$shift best=${"%.3f".format(evidence.largeBandScore)} " +
                                    "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                    "phaseResidual=${"%.2f".format(phaseResidualDisplayPoints)}"
                            }
                            if (abs(shift) > 6 && displayEvents.size < 60) {
                                displayEvents +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz shift=$shift " +
                                    "best=${"%.3f".format(evidence.largeBandScore)} " +
                                    "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                    "phaseResidual=${"%.2f".format(phaseResidualDisplayPoints)} " +
                                    "core=${result.coreAnchorIndex} out=${result.anchorIndex} " +
                                    "displayOffset=${result.displayOffsetSamples} " +
                                    "peakGap=${"%.4f".format(result.displayPeakScoreGap)} " +
                                    "center=${"%.3f".format(result.displayCenterScore)}"
                            }
                        }
                        previousWindow = displayedWindow
                        previousGlobalAnchor = signalStart.toLong() + result.anchorIndex
                        previousPeriodSamples = result.periodSamples
                        previousOffset?.let { previous ->
                            val directJump = abs(offset - previous)
                            val jump =
                                minOf(
                                    directJump,
                                    abs(directJump - result.periodSamples),
                                )
                            offsetJumps += jump
                            if (jump > 12 && events.size < 40) {
                                events +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz " +
                                    "period=${result.periodSamples} offset=$offset jump=$jump " +
                                    "weight=${"%.3f".format(result.corrScopeWeight)} " +
                                    "score=${"%.3f".format(result.triggerScore)}"
                            }
                        }
                        previousOffset = offset
                    } else {
                        previousOffset = null
                        previousWindow = null
                        previousGlobalAnchor = null
                        previousPeriodSamples = null
                    }
                }
                cursor += publishStepSamples
            }
        }

        val largeJumps = offsetJumps.count { it > 12 }
        val maximumJump = offsetJumps.maxOrNull() ?: Int.MAX_VALUE
        val largeDisplayShifts = displayShifts.count { abs(it) > 6 }
        val maximumDisplayShift = displayShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        val displayDirectionReversals =
            significantDisplayShifts.zipWithNext().count { (previous, current) ->
                current.first - previous.first <= 3 &&
                    previous.second * current.second < 0
            }
        println(
            "[REAL-TRIGGER-50-65] samples=${offsetJumps.size} " +
                "processedFrames=$processedFrames lockDrops=$lockDrops " +
                "largeJumps=$largeJumps maxJump=$maximumJump " +
                "displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "displayDirectionReversals=$displayDirectionReversals " +
                "highConfidencePhaseJumps=${highConfidencePhaseJumps.size} " +
                "displayShifts=$displayShifts displayEvents=$displayEvents",
        )
        assertTrue(
            "samples=${offsetJumps.size} processedFrames=$processedFrames " +
                "lockDrops=$lockDrops largeJumps=$largeJumps " +
                "maxJump=$maximumJump displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "displayDirectionReversals=$displayDirectionReversals " +
                "highConfidencePhaseJumps=$highConfidencePhaseJumps " +
                "assistEvents=$events displayEvents=$displayEvents",
            processedFrames >= 400 &&
                lockDrops == 0 &&
                displayShifts.size >= 150 &&
                largeDisplayShifts * 10 <= displayShifts.size &&
                maximumDisplayShift <= 40 &&
                displayDirectionReversals == 0 &&
                highConfidencePhaseJumps.isEmpty(),
        )
    }

    @Test
    fun importedRecordingKeepsContinuousTriggerPhaseFromEightyToOneHundredFifteenSeconds() {
        val pcmPath = System.getenv("OSCOPE_REAL_TRIGGER_PCM").orEmpty()
        assumeTrue(
            "Set OSCOPE_REAL_TRIGGER_PCM to a decoded mono 48 kHz PCM16 file",
            pcmPath.isNotBlank() && File(pcmPath).isFile,
        )
        val sampleRate = 48_000
        val analysisSamples = 4_800
        val displaySamples = 1_200
        val publishStepSamples = 2_880
        val playbackStartSample = 80 * sampleRate
        val measurementStartSample = 90 * sampleRate
        val endSample = 115 * sampleRate
        val engine = SimpleTriggerEngine(windowSize = 512)
        val phaseResiduals = ArrayList<Float>()
        val displayShifts = ArrayList<Int>()
        val largeJumpEvents = ArrayList<String>()
        val highConfidencePhaseJumps = ArrayList<String>()
        var previousGlobalAnchor: Long? = null
        var previousPeriodSamples: Int? = null
        var previousWindow: FloatArray? = null
        var cursor = playbackStartSample + analysisSamples

        RandomAccessFile(pcmPath, "r").use { pcm ->
            while (cursor < endSample) {
                val signalStart = cursor - analysisSamples
                val signal = readPcm16(pcm, signalStart, analysisSamples)
                val result = engine.process(
                    signal,
                    SimpleTriggerEngine.Config(
                        mode = SimpleTriggerEngine.Mode.RISING,
                        sampleRateHz = sampleRate.toFloat(),
                        preTriggerRatio = 0.20f,
                        displayWindowSamples = displaySamples,
                        displayAlignmentPoints = 512,
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                assertTrue("cursor=$cursor did not lock", result.locked)
                if (result.periodSamples > 0) {
                    val globalAnchor = signalStart.toLong() + result.anchorIndex
                    val window = engine.extractWindow(
                        source = signal,
                        result = result,
                        targetSize = displaySamples,
                        preTriggerRatio = 0.20f,
                    )
                    val displayedWindow =
                        downsamplePeakFloatArray(window, 0, window.size, 512)
                    if (cursor >= measurementStartSample) {
                        previousGlobalAnchor?.let { previous ->
                            val delta = globalAnchor - previous
                            val meanPeriod =
                                ((previousPeriodSamples ?: result.periodSamples) +
                                    result.periodSamples) * 0.5
                            val nearestPeriods = kotlin.math.round(delta / meanPeriod)
                            val residual =
                                abs(delta - nearestPeriods * meanPeriod)
                                    .toFloat()
                            phaseResiduals += residual
                            if (residual > 24f) {
                                largeJumpEvents +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz " +
                                    "period=${result.periodSamples} residual=$residual"
                            }
                        }
                        previousWindow?.let { previous ->
                            val shift = bestAlignmentShift(previous, displayedWindow, 48)
                            val evidence =
                                fixedRoiAlignmentEvidence(previous, displayedWindow, 48, 6)
                            displayShifts += shift
                            val priorAnchor = previousGlobalAnchor
                            val priorPeriod = previousPeriodSamples
                            val phaseResidualDisplayPoints =
                                if (priorAnchor != null && priorPeriod != null) {
                                    val meanPeriod =
                                        (priorPeriod + result.periodSamples) * 0.5
                                    val globalDelta = globalAnchor - priorAnchor
                                    val cycleCount =
                                        kotlin.math.round(globalDelta / meanPeriod)
                                    val residualSamples =
                                        globalDelta - cycleCount * meanPeriod
                                    abs(residualSamples) * 512.0 / displaySamples
                                } else {
                                    0.0
                                }
                            if (abs(evidence.bestShift) > 6 &&
                                evidence.largeBandScore >= 0.75f &&
                                evidence.largeBandScore - evidence.zeroBandScore >= 0.05f &&
                                phaseResidualDisplayPoints > 6.0
                            ) {
                                highConfidencePhaseJumps +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "shift=$shift best=${"%.3f".format(evidence.largeBandScore)} " +
                                    "zero=${"%.3f".format(evidence.zeroBandScore)} " +
                                    "phaseResidual=${"%.2f".format(phaseResidualDisplayPoints)}"
                            }
                        }
                    }
                    previousGlobalAnchor = globalAnchor
                    previousPeriodSamples = result.periodSamples
                    previousWindow = displayedWindow
                }
                cursor += publishStepSamples
            }
        }

        val largeJumps = phaseResiduals.count { it > 24f }
        val maximumResidual = phaseResiduals.maxOrNull() ?: Float.POSITIVE_INFINITY
        val largeDisplayShifts = displayShifts.count { abs(it) > 6 }
        val maximumDisplayShift =
            displayShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        println(
            "[REAL-TRIGGER-80-115] samples=${phaseResiduals.size} " +
                "largeResiduals=$largeJumps maxResidual=$maximumResidual " +
                "displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "highConfidencePhaseJumps=${highConfidencePhaseJumps.size}",
        )
        assertTrue(
            "largeJumps=$largeJumps maxResidual=$maximumResidual " +
                "displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift " +
                "highConfidencePhaseJumps=$highConfidencePhaseJumps " +
                "events=$largeJumpEvents",
            phaseResiduals.size >= 300 &&
                displayShifts.size >= 300 &&
                largeJumps <= 2 &&
                maximumResidual <= 32f &&
                largeDisplayShifts == 0 &&
                maximumDisplayShift <= 6 &&
                highConfidencePhaseJumps.isEmpty(),
        )
    }

    private fun readPcm16(
        file: RandomAccessFile,
        startSample: Int,
        sampleCount: Int,
    ): FloatArray {
        val bytes = ByteArray(sampleCount * 2)
        file.seek(startSample.toLong() * 2L)
        file.readFully(bytes)
        return FloatArray(sampleCount) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            (((high shl 8) or low).toShort().toInt() / 32768f)
                .coerceIn(-1f, 1f)
        }
    }

    private fun bestAlignmentShift(
        previous: FloatArray,
        current: FloatArray,
        maximumShift: Int,
    ): Int {
        var bestShift = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (shift in -maximumShift..maximumShift) {
            val previousStart = maxOf(0, shift)
            val currentStart = maxOf(0, -shift)
            val count = minOf(
                previous.size - previousStart,
                current.size - currentStart,
            )
            var previousMean = 0f
            var currentMean = 0f
            for (i in 0 until count) {
                previousMean += previous[previousStart + i]
                currentMean += current[currentStart + i]
            }
            previousMean /= count
            currentMean /= count

            var dot = 0f
            var previousEnergy = 0f
            var currentEnergy = 0f
            for (i in 0 until count) {
                val a = previous[previousStart + i] - previousMean
                val b = current[currentStart + i] - currentMean
                dot += a * b
                previousEnergy += a * a
                currentEnergy += b * b
            }
            val score = dot / kotlin.math.sqrt(
                previousEnergy * currentEnergy,
            ).coerceAtLeast(1e-6f)
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return bestShift
    }

    private data class AlignmentEvidence(
        val bestShift: Int,
        val largeBandScore: Float,
        val zeroBandScore: Float,
    )

    private fun fixedRoiAlignmentEvidence(
        previous: FloatArray,
        current: FloatArray,
        maximumShift: Int,
        zeroBandRadius: Int,
    ): AlignmentEvidence {
        require(previous.size == current.size)
        val roiStart = maximumShift
        val roiEnd = previous.size - maximumShift
        var bestShift = 0
        var bestScore = Float.NEGATIVE_INFINITY
        var largeBandScore = Float.NEGATIVE_INFINITY
        var zeroBandScore = Float.NEGATIVE_INFINITY
        for (shift in -maximumShift..maximumShift) {
            var previousMean = 0f
            var currentMean = 0f
            val count = roiEnd - roiStart
            for (index in roiStart until roiEnd) {
                previousMean += previous[index]
                currentMean += current[index + shift]
            }
            previousMean /= count
            currentMean /= count

            var dot = 0f
            var previousEnergy = 0f
            var currentEnergy = 0f
            for (index in roiStart until roiEnd) {
                val a = previous[index] - previousMean
                val b = current[index + shift] - currentMean
                dot += a * b
                previousEnergy += a * a
                currentEnergy += b * b
            }
            val score =
                dot / kotlin.math.sqrt(previousEnergy * currentEnergy).coerceAtLeast(1e-6f)
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
            if (abs(shift) <= zeroBandRadius) {
                zeroBandScore = maxOf(zeroBandScore, score)
            } else {
                largeBandScore = maxOf(largeBandScore, score)
            }
        }
        return AlignmentEvidence(
            bestShift = bestShift,
            largeBandScore = largeBandScore,
            zeroBandScore = zeroBandScore,
        )
    }

}
