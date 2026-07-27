package org.mhrri.wavestudio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealRecordingTriggerDiagnosticTest {
    @Test
    fun importedRecordingKeepsContinuousTriggerPhaseFromFifteenToThirtyFiveSeconds() {
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
        var lockDrops = 0
        var previousWindow: FloatArray? = null
        var previousGlobalAnchor: Long? = null
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
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (cursor >= measurementStartSample) {
                    if (!result.locked) {
                        lockDrops += 1
                        previousWindow = null
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
                        previousWindow?.let { previous ->
                            val shift = bestAlignmentShift(previous, window, 100)
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
                            if (abs(shift) > 12 && largeShiftEvents.size < 50) {
                                largeShiftEvents +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz shift=$shift " +
                                    "period=${result.periodSamples} " +
                                    "weight=${"%.3f".format(result.corrScopeWeight)} " +
                                    "score=${"%.3f".format(result.triggerScore)}"
                            }
                        }
                        previousWindow = window
                        val globalAnchor = signalStart.toLong() + result.anchorIndex
                        previousGlobalAnchor?.let { previous ->
                            val delta = globalAnchor - previous
                            val nearestPeriods =
                                (delta.toDouble() / result.periodSamples).roundToInt()
                            val residual =
                                abs(delta - nearestPeriods.toLong() * result.periodSamples)
                                    .toFloat()
                            residualsBySecond.getOrPut(cursor / sampleRate) { ArrayList() }
                                .add(residual)
                        }
                        previousGlobalAnchor = globalAnchor
                    }
                }
                cursor += publishStepSamples
            }
        }

        val sortedFrequencies = frequencies.sorted()
        val medianFrequency =
            sortedFrequencies.getOrElse(sortedFrequencies.size / 2) { Float.NaN }
        val largeDisplayShifts = displayShifts.count { abs(it) > 12 }
        val maximumDisplayShift = displayShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        val directionReversals =
            displayShifts.zipWithNext().count { (previous, current) ->
                abs(previous) > 8 && abs(current) > 8 && previous * current < 0
            }
        val highFrequencyDirectionReversals =
            highFrequencyShifts.zipWithNext().count { (previous, current) ->
                abs(previous) > 8 && abs(current) > 8 && previous * current < 0
            }
        val terminalLargeShifts =
            terminalMidFrequencyShifts.count { abs(it) > 12 }
        val terminalMaximumShift =
            terminalMidFrequencyShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        val perSecondSummary = shiftsBySecond.map { (second, shifts) ->
            val residuals = residualsBySecond[second].orEmpty()
            "$second:" +
                "n=${shifts.size}," +
                "large=${shifts.count { abs(it) > 12 }}," +
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
                "terminalMax=$terminalMaximumShift perSecond=$perSecondSummary",
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
                "perSecond=$perSecondSummary events=$largeShiftEvents",
            frequencies.size >= 300 &&
                lockDrops == 0 &&
                displayShifts.size >= 300 &&
                highFrequencyShifts.size >= 10 &&
                highFrequencyDirectionReversals <= 1 &&
                highFrequencyShifts.maxOfOrNull { abs(it) }?.let { it <= 80 } == true &&
                terminalMidFrequencyShifts.size >= 30 &&
                terminalLargeShifts <= 2 &&
                terminalMaximumShift <= 20,
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
        val events = ArrayList<String>()
        var previousOffset: Int? = null
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
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                if (!result.locked) {
                    previousOffset = null
                } else if (result.periodSamples > 0) {
                    val offset = result.assistPhaseOffsetSamples
                    if (result.freqHz in 50f..65f && offset != null) {
                        val window = engine.extractWindow(
                            source = signal,
                            result = result,
                            targetSize = displaySamples,
                            preTriggerRatio = 0.20f,
                        )
                        previousWindow?.let { previous ->
                            displayShifts += bestAlignmentShift(previous, window, 100)
                        }
                        previousWindow = window
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
                    }
                }
                cursor += publishStepSamples
            }
        }

        val largeJumps = offsetJumps.count { it > 12 }
        val maximumJump = offsetJumps.maxOrNull() ?: Int.MAX_VALUE
        val largeDisplayShifts = displayShifts.count { abs(it) > 12 }
        val maximumDisplayShift = displayShifts.maxOfOrNull { abs(it) } ?: Int.MAX_VALUE
        println(
            "[REAL-TRIGGER-50-65] samples=${offsetJumps.size} " +
                "largeJumps=$largeJumps maxJump=$maximumJump " +
                "displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift",
        )
        assertTrue(
            "samples=${offsetJumps.size} largeJumps=$largeJumps " +
                "maxJump=$maximumJump displaySamples=${displayShifts.size} " +
                "largeDisplayShifts=$largeDisplayShifts " +
                "maxDisplayShift=$maximumDisplayShift events=$events",
            offsetJumps.size >= 20 &&
                largeJumps <= 50 &&
                maximumJump <= 180 &&
                displayShifts.size >= 20 &&
                largeDisplayShifts <= 45 &&
                maximumDisplayShift <= 100,
        )
    }

    @Test
    fun importedRecordingKeepsContinuousTriggerPhase() {
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
        val largeJumpEvents = ArrayList<String>()
        var previousGlobalAnchor: Long? = null
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
                        globalBase = signalStart.toLong(),
                        triggerThreshold = 0.02f,
                        holdoffMs = 1f,
                    ),
                )
                assertTrue("cursor=$cursor did not lock", result.locked)
                if (result.periodSamples > 0) {
                    val globalAnchor = signalStart.toLong() + result.anchorIndex
                    if (cursor >= measurementStartSample) {
                        previousGlobalAnchor?.let { previous ->
                            val delta = globalAnchor - previous
                            val nearestPeriods =
                                (delta.toDouble() / result.periodSamples).roundToInt()
                            val residual =
                                abs(delta - nearestPeriods.toLong() * result.periodSamples)
                                    .toFloat()
                            phaseResiduals += residual
                            if (residual > 24f) {
                                largeJumpEvents +=
                                    "t=${"%.2f".format(cursor.toDouble() / sampleRate)}s " +
                                    "f=${"%.2f".format(result.freqHz)}Hz " +
                                    "period=${result.periodSamples} residual=$residual"
                            }
                        }
                    }
                    previousGlobalAnchor = globalAnchor
                }
                cursor += publishStepSamples
            }
        }

        val largeJumps = phaseResiduals.count { it > 24f }
        val maximumResidual = phaseResiduals.maxOrNull() ?: Float.POSITIVE_INFINITY
        assertTrue(
            "largeJumps=$largeJumps maxResidual=$maximumResidual " +
                "events=$largeJumpEvents",
            largeJumps <= 20 && maximumResidual <= 60f,
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

}
