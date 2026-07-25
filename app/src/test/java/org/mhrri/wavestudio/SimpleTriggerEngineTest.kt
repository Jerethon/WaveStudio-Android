package org.mhrri.wavestudio

import kotlin.math.PI
import kotlin.math.abs
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

    private fun spwmLineFrame(
        globalBase: Long,
        frequency: Float,
        size: Int,
    ): FloatArray = FloatArray(size) { index ->
        val sample = globalBase + index
        val fundamentalPhase = 2.0 * PI * frequency * sample / sampleRate
        val carrierPhase = ((sample * frequency * 18.0 / sampleRate) % 1.0 + 1.0) % 1.0
        val carrier = 4.0 * abs(carrierPhase - 0.5) - 1.0
        val u = if (0.55 * sin(fundamentalPhase) > carrier) 1f else 0f
        val v = if (0.55 * sin(fundamentalPhase - 2.0 * PI / 3.0) > carrier) 1f else 0f
        u - v
    }

    private fun floorMod(value: Long, modulus: Int): Int {
        val result = (value % modulus).toInt()
        return if (result >= 0) result else result + modulus
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
