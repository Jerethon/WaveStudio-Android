package org.mhrri.wavestudio

import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStageFilterTest {
    @Test
    fun eachLowPassStageCutoffChangesCombinedResponse() {
        val frequency = floatArrayOf(5_000f)
        val sameCutoffs = computeEqResponse(
            bands = emptyList(),
            freqs = frequency,
            lowPassEnabled = true,
            lowPassCutoff = 1_000f,
            lowPassStageCutoffs = listOf(1_000f, 1_000f),
            lowPassOrder = 2,
            highPassEnabled = false,
            highPassCutoff = 30f,
            highPassStageCutoffs = listOf(30f),
            highPassOrder = 1,
            filterGain = 1f,
            sampleRate = 48_000,
        )
        val independentCutoffs = computeEqResponse(
            bands = emptyList(),
            freqs = frequency,
            lowPassEnabled = true,
            lowPassCutoff = 1_000f,
            lowPassStageCutoffs = listOf(1_000f, 10_000f),
            lowPassOrder = 2,
            highPassEnabled = false,
            highPassCutoff = 30f,
            highPassStageCutoffs = listOf(30f),
            highPassOrder = 1,
            filterGain = 1f,
            sampleRate = 48_000,
        )

        assertTrue(independentCutoffs[0] > sameCutoffs[0] + 6f)
    }

    @Test
    fun eachHighPassStageCutoffChangesCombinedResponse() {
        val frequency = floatArrayOf(200f)
        val sameCutoffs = computeEqResponse(
            bands = emptyList(),
            freqs = frequency,
            lowPassEnabled = false,
            lowPassCutoff = 20_000f,
            lowPassStageCutoffs = listOf(20_000f),
            lowPassOrder = 1,
            highPassEnabled = true,
            highPassCutoff = 1_000f,
            highPassStageCutoffs = listOf(1_000f, 1_000f),
            highPassOrder = 2,
            filterGain = 1f,
            sampleRate = 48_000,
        )
        val independentCutoffs = computeEqResponse(
            bands = emptyList(),
            freqs = frequency,
            lowPassEnabled = false,
            lowPassCutoff = 20_000f,
            lowPassStageCutoffs = listOf(20_000f),
            lowPassOrder = 1,
            highPassEnabled = true,
            highPassCutoff = 1_000f,
            highPassStageCutoffs = listOf(1_000f, 100f),
            highPassOrder = 2,
            filterGain = 1f,
            sampleRate = 48_000,
        )

        assertTrue(independentCutoffs[0] > sameCutoffs[0] + 6f)
    }
}
