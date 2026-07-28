package org.mhrri.wavestudio

import kotlin.math.abs
import kotlin.math.min

/**
 * Peak-preserving sampler used by both the production waveform publisher and Trigger tests.
 *
 * Keeping this operation shared matters because Trigger stability is judged on the rendered
 * waveform, not on the higher-resolution analysis buffer that precedes it.
 */
internal fun downsamplePeakFloatArray(
    input: FloatArray,
    start: Int,
    endExclusive: Int,
    targetPoints: Int,
): FloatArray {
    val n = (endExclusive - start).coerceAtLeast(0)
    if (n <= 0 || targetPoints <= 0) return floatArrayOf()
    if (n <= targetPoints) return input.copyOfRange(start, endExclusive)

    val bucketSize = n.toFloat() / targetPoints
    return FloatArray(targetPoints) { point ->
        val bucketStart = start + (point * bucketSize).toInt()
        val bucketEnd =
            min(start + ((point + 1) * bucketSize).toInt(), endExclusive)
        var peak = 0f
        for (sampleIndex in bucketStart until bucketEnd) {
            val sample = input[sampleIndex]
            if (abs(sample) > abs(peak)) peak = sample
        }
        peak
    }
}
