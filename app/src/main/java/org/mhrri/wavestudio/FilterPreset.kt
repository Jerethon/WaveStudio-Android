package org.mhrri.wavestudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * 滤波/显示/均衡器“预设”
 * - 用 JSON 导入/导出，方便不同用户共享
 * - schemaVersion 用于后续兼容扩展
 */
data class FilterPreset(
    val schemaVersion: Int = 2,
    val name: String? = null,

    // HP/LP
    val lowPassEnabled: Boolean,
    val lowPassCutoffHz: Float,
    val lowPassOrder: Int,
    val lowPassStageCutoffsHz: List<Float> = listOf(lowPassCutoffHz),

    val highPassEnabled: Boolean,
    val highPassCutoffHz: Float,
    val highPassOrder: Int,
    val highPassStageCutoffsHz: List<Float> = listOf(highPassCutoffHz),

    // gains / display
    val filterGain: Float,
    val windowMs: Float,
    val ampScale: Float,

    // EQ
    val eqEnabled: Boolean,
    val eqBands: List<EqBandPreset>,
) {
    data class EqBandPreset(
        val id: Int,
        val enabled: Boolean,
        val freqHz: Float,
        val gainDb: Float,
        val q: Float,
        val type: String = "PEAK",  // PEAK, LOW_SHELF, HIGH_SHELF
    )

    fun toJsonString(pretty: Boolean = true): String {
        val root = JSONObject()
        root.put("schemaVersion", schemaVersion)
        if (!name.isNullOrBlank()) root.put("name", name)

        root.put(
            "lowPass",
            JSONObject()
                .put("enabled", lowPassEnabled)
                .put("cutoffHz", lowPassCutoffHz)
                .put("order", lowPassOrder)
                .put("stageCutoffsHz", JSONArray(lowPassStageCutoffsHz))
        )
        root.put(
            "highPass",
            JSONObject()
                .put("enabled", highPassEnabled)
                .put("cutoffHz", highPassCutoffHz)
                .put("order", highPassOrder)
                .put("stageCutoffsHz", JSONArray(highPassStageCutoffsHz))
        )

        root.put(
            "display",
            JSONObject()
                .put("windowMs", windowMs)
                .put("ampScale", ampScale)
        )
        root.put(
            "gain",
            JSONObject()
                .put("filterGain", filterGain)
        )

        root.put(
            "eq",
            JSONObject()
                .put("enabled", eqEnabled)
                .put(
                    "bands",
                    JSONArray().apply {
                        for (b in eqBands) {
                            put(
                                JSONObject()
                                    .put("id", b.id)
                                    .put("enabled", b.enabled)
                                    .put("freqHz", b.freqHz)
                                    .put("gainDb", b.gainDb)
                                    .put("q", b.q)
                                    .put("type", b.type)
                            )
                        }
                    }
                )
        )

        return if (pretty) root.toString(2) else root.toString()
    }

    companion object {
        fun fromJsonString(json: String): FilterPreset {
            val root = JSONObject(json)
            val schemaVersion = root.optInt("schemaVersion", 1)
            val name = root.optString("name", "").takeIf { it.isNotBlank() }

            val lp = root.optJSONObject("lowPass") ?: JSONObject()
            val hp = root.optJSONObject("highPass") ?: JSONObject()
            val display = root.optJSONObject("display") ?: JSONObject()
            val gain = root.optJSONObject("gain") ?: JSONObject()
            val eq = root.optJSONObject("eq") ?: JSONObject()

            val eqBands = mutableListOf<EqBandPreset>()
            val bandsArr = eq.optJSONArray("bands") ?: JSONArray()
            for (i in 0 until bandsArr.length()) {
                val o = bandsArr.optJSONObject(i) ?: continue
                eqBands += EqBandPreset(
                    id = o.optInt("id", i),
                    enabled = o.optBoolean("enabled", false),
                    freqHz = o.optDouble("freqHz", 1000.0).toFloat(),
                    gainDb = o.optDouble("gainDb", 0.0).toFloat(),
                    q = o.optDouble("q", AudioEngineViewModel.DEFAULT_EQ_Q.toDouble()).toFloat(),
                    type = o.optString("type", "PEAK"),
                )
            }

            fun stageCutoffs(
                filter: JSONObject,
                fallbackCutoff: Float,
                order: Int,
            ): List<Float> {
                val array = filter.optJSONArray("stageCutoffsHz")
                if (array == null || array.length() == 0) {
                    return List(order.coerceIn(1, 8)) { fallbackCutoff }
                }
                return List(array.length().coerceAtMost(8)) { index ->
                    array.optDouble(index, fallbackCutoff.toDouble()).toFloat()
                }
            }

            val lowPassCutoff = lp.optDouble("cutoffHz", 15000.0).toFloat()
            val lowPassOrder = lp.optInt("order", 2)
            val highPassCutoff = hp.optDouble("cutoffHz", 20.0).toFloat()
            val highPassOrder = hp.optInt("order", 2)

            return FilterPreset(
                schemaVersion = schemaVersion,
                name = name,
                lowPassEnabled = lp.optBoolean("enabled", false),
                lowPassCutoffHz = lowPassCutoff,
                lowPassOrder = lowPassOrder,
                lowPassStageCutoffsHz = stageCutoffs(lp, lowPassCutoff, lowPassOrder),
                highPassEnabled = hp.optBoolean("enabled", false),
                highPassCutoffHz = highPassCutoff,
                highPassOrder = highPassOrder,
                highPassStageCutoffsHz = stageCutoffs(hp, highPassCutoff, highPassOrder),
                filterGain = gain.optDouble("filterGain", 1.0).toFloat(),
                windowMs = display.optDouble("windowMs", 30.0).toFloat(),
                ampScale = display.optDouble("ampScale", 1.0).toFloat(),
                eqEnabled = eq.optBoolean("enabled", false),
                eqBands = eqBands,
            )
        }
    }
}
