package org.mhrri.wavestudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleTriggerEngineTest {
    private val sampleRate = 48_000f
    private val frequencyHz = 440f
    private val frameSize = 2_400
    private val displaySize = 512
    private val expectedDisplayEdge = (displaySize * 0.20f).toInt()

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

    private fun config(globalBase: Long) = SimpleTriggerEngine.Config(
        mode = SimpleTriggerEngine.Mode.RISING,
        sampleRateHz = sampleRate,
        globalBase = globalBase,
        triggerThreshold = 0.02f,
    )

    private fun sineFrame(globalBase: Long): FloatArray = FloatArray(frameSize) { index ->
        sin(2.0 * PI * frequencyHz * (globalBase + index) / sampleRate).toFloat()
    }

    private fun assertEdgeAtDisplayReference(
        source: FloatArray,
        result: SimpleTriggerEngine.Result,
    ) {
        val window = SimpleTriggerEngine().extractWindow(source, result, displaySize, 0.20f)
        val nearestRisingEdge = (1 until window.size)
            .filter { window[it - 1] < 0f && window[it] >= 0f }
            .minByOrNull { abs(it - expectedDisplayEdge) }
        assertTrue(
            "display edge=$nearestRisingEdge, trigger=${result.anchorIndex}",
            nearestRisingEdge != null && abs(nearestRisingEdge - expectedDisplayEdge) <= 4,
        )
    }
}


