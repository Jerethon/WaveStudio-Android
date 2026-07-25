package org.mhrri.wavestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import androidx.media3.exoplayer.ExoPlayer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaMuxer
import java.nio.ByteBuffer

class AudioEngineViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val DEFAULT_EQ_Q = 0.4f
        private const val SETTINGS_PREFS_NAME = "oscope_settings"
        private const val KEY_RECORDING_FORMAT = "recording_format"
        private const val KEY_RECORDING_SAMPLE_RATE = "recording_sample_rate"
        private const val KEY_WAVEFORM_PUBLISH_RATE_HZ = "waveform_publish_rate_hz"
        private const val KEY_CUSTOM_RECORDING_PATH = "custom_recording_path"
        private const val KEY_CUSTOM_RECORDING_TREE_URI = "custom_recording_tree_uri"
        private const val KEY_PERSISTED_RECORDINGS_JSON = "persisted_recordings_json"

        // Global HP setting keys
        private const val KEY_GLOBAL_HP_ENABLED = "global_hp_enabled"
        private const val KEY_GLOBAL_HP_CUTOFF_HZ = "global_hp_cutoff_hz"

        fun maxEqQForGainDb(gainDb: Float): Float {
            val a = abs(gainDb).coerceAtLeast(1e-3f)
            val a20 = 10f.pow(a / 20f)
            val a40 = 10f.pow(a / 40f)
            val denom = (a40 - 1f).let { it * it }
            if (denom <= 1e-6f) return 100f
            return (((a20 + 1f) / denom) - 0.07f).coerceAtLeast(0.2f)
        }

        fun clampEqQForBand(type: EqBandType, gainDb: Float, q: Float): Float {
            val qClamped = q.coerceIn(0.2f, 6f)
            return when (type) {
                EqBandType.PEAK -> qClamped
                EqBandType.LOW_SHELF,
                EqBandType.HIGH_SHELF -> qClamped.coerceAtMost(maxEqQForGainDb(gainDb))
            }
        }
    }

    enum class PublishRateOption(val hz: Int) {
        HZ_6(6),
        HZ_8(8),
        HZ_10(10),
        HZ_12(12),
        HZ_16(16),
        HZ_20(20),
        HZ_24(24),
        HZ_30(30),
        HZ_40(40),
        HZ_50(50),
        HZ_60(60);

        val publishIntervalMs: Long
            get() = (1000f / hz).roundToInt().toLong().coerceAtLeast(1L)
    }

    private val settingsPrefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Simple JSON persistence for RecordedClip list
    private fun persistRecordingsToPrefs() {
        try {
            val list = _allRecordings.value
            val arr = org.json.JSONArray()
            for (c in list) {
                val o = org.json.JSONObject()
                o.put("id", c.id)
                o.put("date", c.date)
                o.put("duration", c.duration)
                o.put("fileURL", c.fileURL)
                if (c.customName != null) o.put("customName", c.customName)
                if (c.deletedAt != null) o.put("deletedAt", c.deletedAt)
                arr.put(o)
            }
            settingsPrefs.edit().putString(KEY_PERSISTED_RECORDINGS_JSON, arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun restorePersistedRecordings() {
        try {
            val txt = settingsPrefs.getString(KEY_PERSISTED_RECORDINGS_JSON, null) ?: return
            val arr = org.json.JSONArray(txt)
            val list = ArrayList<RecordedClip>(arr.length())
            val now = System.currentTimeMillis()
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", java.util.UUID.randomUUID().toString())
                val date = o.optLong("date", System.currentTimeMillis())
                val duration = o.optDouble("duration", 0.0)
                val fileURL = o.optString("fileURL", "")
                val customName = if (o.has("customName")) o.optString("customName", null) else null
                val deletedAt =
                    if (o.has("deletedAt")) o.optLong("deletedAt", -1L).let { if (it < 0L) null else it } else null
                if (fileURL.isNotBlank()) {
                    // 自动清除已删除超过30天的录音
                    if (deletedAt != null && (now - deletedAt) > thirtyDaysMs) {
                        deleteRecordingFile(fileURL)
                        continue
                    }
                    list.add(
                        RecordedClip(
                            id = id,
                            date = date,
                            duration = duration,
                            fileURL = fileURL,
                            customName = customName,
                            deletedAt = deletedAt
                        )
                    )
                }
            }
            if (list.isNotEmpty()) _allRecordings.update { list }
        } catch (_: Throwable) {
        }
    }

    /** 删除录音文件（如果存在） */
    private fun deleteRecordingFile(fileURL: String) {
        try {
            if (fileURL.startsWith("content://")) {
                val app = getApplication<Application>()
                app.contentResolver.delete(android.net.Uri.parse(fileURL), null, null)
            } else {
                val f = File(fileURL)
                if (f.exists()) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private val _publishRateOption = MutableStateFlow(PublishRateOption.HZ_20)
    val publishRateOption: StateFlow<PublishRateOption> = _publishRateOption.asStateFlow()

    private fun currentPublishIntervalMs(): Long = _publishRateOption.value.publishIntervalMs

    // ===== Public recording controls (used by MainActivity) =====
    fun stopRecording() {
        if (!_isRecording.value) return
        Log.i("WaveStudio", "Stop recording")
        _isRecording.value = false
        // Avoid blocking UI
        recordingStopJob?.cancel()
        recordingStopJob = viewModelScope.launch(Dispatchers.Default) {
            stopRecordingInternalBlocking()
        }
    }

    // ===== Playback API (used by MainActivity) =====
    fun stopPlayback() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        _playingId.value = null
        _playbackPositionMs.value = 0L
        _playbackDurationMs.value = 0L
        _isSeeking.value = false
    }

    fun playRecording(context: Context, clip: RecordedClip) {
        // Stop existing playback if same id.
        if (_isRecording.value) {
            _engineError.value = "录音中无法播放"
            return
        }
        // If already playing this clip, stop instead (toggle)
        if (_playingId.value == clip.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        _playbackPositionMs.value = 0L
        try {
            val uri = if (clip.fileURL.startsWith("content://")) {
                android.net.Uri.parse(clip.fileURL)
            } else {
                android.net.Uri.fromFile(java.io.File(clip.fileURL))
            }
            val exoPlayer = ExoPlayer.Builder(context).build()
            val mediaItem = androidx.media3.common.MediaItem.fromUri(uri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        stopPlayback()
                    }
                }
            })
            player = exoPlayer
            _playingId.value = clip.id
            _playbackDurationMs.value = exoPlayer.duration.coerceAtLeast(0L)
            _engineError.value = null

            // Progress tracking
            playbackProgressJob?.cancel()
            playbackProgressJob = viewModelScope.launch(Dispatchers.Main) {
                while (isActive) {
                    val p = player
                    if (p != null) {
                        _playbackPositionMs.value = p.currentPosition
                    }
                    delay(50)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEngineVM", "playRecording failed", e)
            _engineError.value = "播放失败：${e.localizedMessage}"
        }
    }

    fun seekPlaybackTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Drains encoder output once (may drain multiple buffers per call) and returns true if more draining is needed.
     */
    private fun drainRecordingEncoderOnce(codec: MediaCodec, muxer: MediaMuxer): Boolean {
        val info = MediaCodec.BufferInfo()
        var didWork = false

        while (true) {
            val outIndex = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (_: Throwable) {
                return false
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return didWork
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!recordingMuxerStarted) {
                        val newFormat = codec.outputFormat
                        recordingTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        recordingMuxerStarted = true
                    }
                    didWork = true
                }

                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 && recordingMuxerStarted && recordingTrackIndex >= 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        try {
                            muxer.writeSampleData(recordingTrackIndex, outBuf, info)
                        } catch (_: Throwable) {
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    didWork = true
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return false
                    }
                }

                else -> return didWork
            }
        }
    }

    // ===== Engine state: public API (used by MainActivity) =====
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    @Volatile
    private var _triggerEnabled = false
    fun setTriggerEnabled(enabled: Boolean) {
        _triggerEnabled = enabled
    }

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    /** 全部录音（含已删除），内部状态 */
    private val _allRecordings = MutableStateFlow(emptyList<RecordedClip>())

    /** 活跃录音列表（排除已删除的） */
    val recordings: StateFlow<List<RecordedClip>> = _allRecordings
        .map { list -> list.filter { it.deletedAt == null } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** 最近删除的录音列表 */
    val recentlyDeletedRecordings: StateFlow<List<RecordedClip>> = _allRecordings
        .map { list ->
            list.filter { it.deletedAt != null }
                .sortedByDescending { it.deletedAt }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // 低通/高通：统一从 EQ bands(L/H) 派生（仍保留这四个 StateFlow 以兼容 MainActivity 现有 UI）
    private val _lowPassEnabled = MutableStateFlow(false)
    val lowPassEnabled: StateFlow<Boolean> = _lowPassEnabled.asStateFlow()

    private val _lowPassCutoff = MutableStateFlow(20000f)
    val lowPassCutoff: StateFlow<Float> = _lowPassCutoff.asStateFlow()
    private val _lowPassStageCutoffs = MutableStateFlow(List(8) { 20000f })
    val lowPassStageCutoffs: StateFlow<List<Float>> = _lowPassStageCutoffs.asStateFlow()

    private val _highPassEnabled = MutableStateFlow(false)
    val highPassEnabled: StateFlow<Boolean> = _highPassEnabled.asStateFlow()

    private val _highPassCutoff = MutableStateFlow(30f)
    val highPassCutoff: StateFlow<Float> = _highPassCutoff.asStateFlow()
    private val _highPassStageCutoffs = MutableStateFlow(List(8) { 30f })
    val highPassStageCutoffs: StateFlow<List<Float>> = _highPassStageCutoffs.asStateFlow()

    // Global 1Hz high-pass setting (exposed in settings)
    private val _globalHighPassEnabled = MutableStateFlow(true)
    val globalHighPassEnabled: StateFlow<Boolean> = _globalHighPassEnabled.asStateFlow()

    private val _globalHighPassCutoff = MutableStateFlow(1f)
    val globalHighPassCutoff: StateFlow<Float> = _globalHighPassCutoff.asStateFlow()

    private val _windowMs = MutableStateFlow(25f)
    val windowMs: StateFlow<Float> = _windowMs.asStateFlow()

    private val _ampScale = MutableStateFlow(1f)
    val ampScale: StateFlow<Float> = _ampScale.asStateFlow()

    private val _rawWaveform = MutableStateFlow(floatArrayOf())
    val rawWaveform: StateFlow<FloatArray> = _rawWaveform.asStateFlow()

    private val _filteredWaveform = MutableStateFlow(floatArrayOf())
    val filteredWaveform: StateFlow<FloatArray> = _filteredWaveform.asStateFlow()

    // 沉浸模式专用：保相位的均匀重采样波形，用于 Trigger 和全屏显示
    private val _immersiveFilteredWaveform = MutableStateFlow(floatArrayOf())
    val immersiveFilteredWaveform: StateFlow<FloatArray> = _immersiveFilteredWaveform.asStateFlow()

    // Simple trigger engine
    private val triggerEngine = SimpleTriggerEngine(windowSize = 512)
    private val _triggerResult = MutableStateFlow<SimpleTriggerEngine.Result?>(null)
    internal val triggerResult: StateFlow<SimpleTriggerEngine.Result?> = _triggerResult.asStateFlow()
    private val _triggeredWindow = MutableStateFlow(floatArrayOf())
    val triggeredWindow: StateFlow<FloatArray> = _triggeredWindow.asStateFlow()
    private val _pendingTimeWindowUpdate = MutableStateFlow(false)

    // Tracks the ring-buffer-absolute position of the last trigger anchor.
    // Used to re-project the engine's state across sliding input buffers.
    private var triggerRingAnchorBase: Int = -1

    private val _publishedWaveformSpanMs = MutableStateFlow(20f)
    val publishedWaveformSpanMs: StateFlow<Float> = _publishedWaveformSpanMs.asStateFlow()

    private val _publishedWaveformFps = MutableStateFlow(0f)
    val publishedWaveformFps: StateFlow<Float> = _publishedWaveformFps.asStateFlow()

    private var waveformPublishWindowStartMs = 0L
    private var waveformPublishFrameCount = 0

    private fun resetWaveformPublishStats() {
        waveformPublishWindowStartMs = 0L
        waveformPublishFrameCount = 0
        _publishedWaveformFps.value = 0f
    }

    private fun markWaveformPublished() {
        val nowMs = SystemClock.elapsedRealtime()
        if (waveformPublishWindowStartMs == 0L) waveformPublishWindowStartMs = nowMs
        waveformPublishFrameCount++
        val elapsedMs = nowMs - waveformPublishWindowStartMs
        if (elapsedMs >= 500L) {
            _publishedWaveformFps.value = (waveformPublishFrameCount * 1000f / elapsedMs).coerceAtLeast(0f)
            waveformPublishFrameCount = 0
            waveformPublishWindowStartMs = nowMs
        }
    }

    // ===== UI state: EQ graph draggable (to allow touch dragging on nodes) =====
    private val _eqGraphDraggable = MutableStateFlow(false)
    val eqGraphDraggable: StateFlow<Boolean> = _eqGraphDraggable.asStateFlow()

    fun setEqGraphDraggable(draggable: Boolean) {
        _eqGraphDraggable.value = draggable
    }

    // ===== UI state: EQ graph dragging (to disable surrounding scroll while dragging nodes) =====
    private val _eqGraphDragging = MutableStateFlow(false)
    val eqGraphDragging: StateFlow<Boolean> = _eqGraphDragging.asStateFlow()

    fun setEqGraphDragging(dragging: Boolean) {
        _eqGraphDragging.value = dragging
    }

    // ===== AudioRecord =====
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private var sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Realtime filter state for monitor + recording path (avoid per-block allocations)
    private var monitorRecordFilter = RtBiquadCascade(sampleRate)

    // ====== 采集诊断状态（用于判断“有没有真实音频输入”） ======
    private val _audioInputAlive = MutableStateFlow(false)

    /** 最近一段时间是否检测到非零音频输入（maxAbs > 0） */
    val audioInputAlive: StateFlow<Boolean> = _audioInputAlive.asStateFlow()

    private val _lastReadSamples = MutableStateFlow(0)

    /** 最近一次 read() 读到的采样点数（short 数量） */
    val lastReadSamples: StateFlow<Int> = _lastReadSamples.asStateFlow()

    private val _lastMaxAbsPcm = MutableStateFlow(0)

    /** 最近一次 buffer 的最大绝对值（0 表示全静音/无输入） */
    val lastMaxAbsPcm: StateFlow<Int> = _lastMaxAbsPcm.asStateFlow()

    // ====== 更详细的诊断状态 ======
    private val _audioInitOk = MutableStateFlow(false)

    /** AudioRecord 是否初始化成功 */
    val audioInitOk: StateFlow<Boolean> = _audioInitOk.asStateFlow()

    private val _audioRecordingState = MutableStateFlow(AudioRecord.RECORDSTATE_STOPPED)

    /** AudioRecord.recordingState（STOPPED/RECORDING） */
    val audioRecordingState: StateFlow<Int> = _audioRecordingState.asStateFlow()

    private val _audioState = MutableStateFlow(AudioRecord.STATE_UNINITIALIZED)

    /** AudioRecord.state（INITIALIZED/UNINITIALIZED） */
    val audioState: StateFlow<Int> = _audioState.asStateFlow()

    private val _audioSourceUsed = MutableStateFlow(Int.MIN_VALUE)

    /** 实际使用的 AudioSource（例如 MIC/VOICE_RECOGNITION） */
    val audioSourceUsed: StateFlow<Int> = _audioSourceUsed.asStateFlow()

    private val _lastReadError = MutableStateFlow(0)

    /** 最近一次 read() 的错误码（0 表示无错误；负数一般表示 ERROR_*） */
    val lastReadError: StateFlow<Int> = _lastReadError.asStateFlow()

    private val _engineError = MutableStateFlow<String?>(null)

    /** 引擎错误信息（用于直接在界面提示用户） */
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    private val _filterGain = MutableStateFlow(1f)

    /** 手动滤波增益（autoGain 关闭时使用；或者作为 autoGain 的额外倍率） */
    val filterGain: StateFlow<Float> = _filterGain.asStateFlow()

    // 每个滤波器独立的“阶数”选择（用户可选 1-8）
    private val _lowPassOrder = MutableStateFlow(1)  // 默认低通 1 阶
    val lowPassOrder: StateFlow<Int> = _lowPassOrder.asStateFlow()

    private val _highPassOrder = MutableStateFlow(1) // 默认高通 1 阶
    val highPassOrder: StateFlow<Int> = _highPassOrder.asStateFlow()

    fun setLowPassOrder(order: Int) {
        _lowPassOrder.value = order.coerceIn(1, 8)
    }

    fun setHighPassOrder(order: Int) {
        _highPassOrder.value = order.coerceIn(1, 8)
    }

    private val _useTestSignal = MutableStateFlow(false)
    val useTestSignal: StateFlow<Boolean> = _useTestSignal.asStateFlow()

    enum class TestSignalPreset(
        @StringRes
        val labelResId: Int,
        val carrierMultiple: Float = 0f,
        val modulationAmp: Float = 0f,
    ) {
        SPWM_1P(R.string.test_signal_spwm_1p, 3f, 2.5f),
        REC_5P(R.string.test_signal_5p),
        W7(R.string.test_signal_w7),
    }

    private val _testSignalPreset = MutableStateFlow(TestSignalPreset.SPWM_1P)
    val testSignalPreset: StateFlow<TestSignalPreset> = _testSignalPreset.asStateFlow()

    private val _useImportedSignal = MutableStateFlow(false)
    val useImportedSignal: StateFlow<Boolean> = _useImportedSignal.asStateFlow()

    private val _importedAudioLabel = MutableStateFlow<String?>(null)
    val importedAudioLabel: StateFlow<String?> = _importedAudioLabel.asStateFlow()

    private val _isImportingAudio = MutableStateFlow(false)
    val isImportingAudio: StateFlow<Boolean> = _isImportingAudio.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private val _importResultMessage = MutableStateFlow<String?>(null)
    val importResultMessage: StateFlow<String?> = _importResultMessage.asStateFlow()

    private val _importedSignalPaused = MutableStateFlow(false)
    val importedSignalPaused: StateFlow<Boolean> = _importedSignalPaused.asStateFlow()

    private val _importedPlaybackPositionMs = MutableStateFlow(0L)
    val importedPlaybackPositionMs: StateFlow<Long> = _importedPlaybackPositionMs.asStateFlow()

    private val _importedPlaybackDurationMs = MutableStateFlow(0L)
    val importedPlaybackDurationMs: StateFlow<Long> = _importedPlaybackDurationMs.asStateFlow()

    // ===== EQ (Parametric) =====
    enum class EqBandType { PEAK, LOW_SHELF, HIGH_SHELF }

    data class EqBand(
        val id: Int,
        val label: String,
        val type: EqBandType = EqBandType.PEAK,
        val enabled: Boolean = false,
        val freqHz: Float = 1000f,
        val gainDb: Float = 0f,
        val q: Float = DEFAULT_EQ_Q,
    )

    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqBands = MutableStateFlow(
        listOf(
            // Keep original 4-band EQ layout (do not change defaults)
            EqBand(
                id = 0,
                label = "1",
                type = EqBandType.LOW_SHELF,
                enabled = true,
                freqHz = 200f,
                gainDb = 0f,
                q = 0.3f
            ),
            EqBand(id = 1, label = "2", type = EqBandType.PEAK, enabled = true, freqHz = 800f, gainDb = 0f, q = 0.5f),
            EqBand(id = 2, label = "3", type = EqBandType.PEAK, enabled = true, freqHz = 2000f, gainDb = 0f, q = 0.5f),
            EqBand(
                id = 3,
                label = "4",
                type = EqBandType.HIGH_SHELF,
                enabled = true,
                freqHz = 5000f,
                gainDb = 0f,
                q = 0.3f
            ),
        )
    )
    val eqBands: StateFlow<List<EqBand>> = _eqBands.asStateFlow()

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
    }

    fun setEqBandEnabled(id: Int, enabled: Boolean) {
        _eqBands.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    fun setEqBandFreq(id: Int, freqHz: Float) {
        _eqBands.update { list -> list.map { if (it.id == id) it.copy(freqHz = freqHz) else it } }
    }

    /** 重置某一段 EQ 的频率为默认值（不会改启用状态/增益/Q）。 */
    fun resetEqBandFreq(id: Int) {
        val defaultFreq = when (id) {
            0 -> 200f
            1 -> 800f
            2 -> 2000f
            3 -> 5000f
            else -> 1000f
        }
        setEqBandFreq(id, defaultFreq)
    }

    fun setEqBandGainDb(id: Int, gainDb: Float) {
        _eqBands.update { list ->
            list.map { band ->
                if (band.id != id) band
                else band.copy(gainDb = gainDb.coerceIn(-40f, 40f))
            }
        }
    }

    fun setEqBandQ(id: Int, q: Float) {
        _eqBands.update { list ->
            list.map { band ->
                if (band.id != id) band
                else band.copy(q = q.coerceIn(0.2f, 6f))
            }
        }
    }

    fun setEqBandType(id: Int, type: EqBandType) {
        _eqBands.update { list ->
            list.map { band ->
                if (band.id == id) {
                    band.copy(type = type)
                } else band
            }
        }
    }

    fun resetEq() {
        _eqEnabled.value = true
        _eqBands.value = _eqBands.value.map { band ->
            when (band.id) {
                0 -> band.copy(type = EqBandType.LOW_SHELF, enabled = true, freqHz = 200f, gainDb = 0f, q = 0.3f)
                1 -> band.copy(type = EqBandType.PEAK, enabled = true, freqHz = 800f, gainDb = 0f, q = 0.6f)
                2 -> band.copy(type = EqBandType.PEAK, enabled = true, freqHz = 2000f, gainDb = 0f, q = 0.6f)
                3 -> band.copy(type = EqBandType.HIGH_SHELF, enabled = true, freqHz = 5000f, gainDb = 0f, q = 0.3f)
                else -> band.copy(enabled = true, gainDb = 0f, q = DEFAULT_EQ_Q)
            }
        }
    }

    // ===== Filtered recording (PCM -> filter -> AAC -> M4A) =====
    private var recordingStartMs: Long = 0L
    private var currentRecordingPath: String? = null
    @Volatile
    private var recordingMuxer: MediaMuxer? = null
    @Volatile
    private var recordingCodec: MediaCodec? = null
    private var recordingTrackIndex: Int = -1
    private var recordingMuxerStarted: Boolean = false
    private var recordingPtsUs: Long = 0L

    // WAV (raw PCM) recording
    @Volatile
    private var wavOut: java.io.RandomAccessFile? = null
    private var wavDataBytes: Long = 0L
    private var wavSampleRate: Int = 44100
    private var recordingActiveFormat: RecordingFormat? = null
    private var recordingDownsampleBuffer = ShortArray(0)
    private var recordingPcmBytesBuffer = ByteArray(0)
    private var pendingRelativeRecordingPath: String? = null
    private var lastRecordingUsedAbsoluteCustomPath: Boolean = false

    // stopRecording 时的后台收尾任务，避免 UI 卡死
    private var recordingStopJob: Job? = null

    private var monitorTrack: AudioTrack? = null

    @Volatile
    private var player: ExoPlayer? = null
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    // ===== Playback progress =====
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _isSeeking = MutableStateFlow(false)
    val isSeeking: StateFlow<Boolean> = _isSeeking.asStateFlow()

    private var playbackProgressJob: Job? = null

    // ===== Debug: track who last wrote key UI params (to diagnose 'value snaps back') =====
    private val _lastWindowWriteSource = MutableStateFlow("init")
    val lastWindowWriteSource: StateFlow<String> = _lastWindowWriteSource.asStateFlow()

    private val _lastAmpWriteSource = MutableStateFlow("init")
    val lastAmpWriteSource: StateFlow<String> = _lastAmpWriteSource.asStateFlow()

    // ===== UI actions / setters (MainActivity 依赖) =====
    fun toggleLowPass(enabled: Boolean) {
        _lowPassEnabled.value = enabled
    }

    fun updateLowPassSlider(value: Float) {
        _lowPassCutoff.value = value
        _lowPassStageCutoffs.update { cutoffs ->
            cutoffs.toMutableList().also { it[0] = value }
        }
    }

    fun updateLowPassStageCutoff(stageIndex: Int, value: Float) {
        if (stageIndex !in 0 until 8) return
        val cutoff = value.coerceIn(600f, 30001f)
        _lowPassStageCutoffs.update { cutoffs ->
            cutoffs.toMutableList().also { it[stageIndex] = cutoff }
        }
        if (stageIndex == 0) _lowPassCutoff.value = cutoff
    }

    fun toggleHighPass(enabled: Boolean) {
        _highPassEnabled.value = enabled
    }

    fun updateHighPassSlider(value: Float) {
        _highPassCutoff.value = value
        _highPassStageCutoffs.update { cutoffs ->
            cutoffs.toMutableList().also { it[0] = value }
        }
    }

    fun updateHighPassStageCutoff(stageIndex: Int, value: Float) {
        if (stageIndex !in 0 until 8) return
        val cutoff = value.coerceIn(20f, 10000f)
        _highPassStageCutoffs.update { cutoffs ->
            cutoffs.toMutableList().also { it[stageIndex] = cutoff }
        }
        if (stageIndex == 0) _highPassCutoff.value = cutoff
    }

    fun updateTimeSlider(value: Float) {
        _lastWindowWriteSource.value = "ui"
        _windowMs.value = value
        // 不清空 _triggeredWindow — 时间窗改变时由 trigger 处理循环自然刷新
        // 但立即触发一次重新提取（如果有有效的 trigger 结果缓存）
        _pendingTimeWindowUpdate.value = true
    }

    fun updateAmpSlider(value: Float) {
        _lastAmpWriteSource.value = "ui"
        _ampScale.value = value
    }

    fun updateFilterGain(value: Float) {
        _filterGain.value = value
    }

    fun setTestSignalPreset(preset: TestSignalPreset) {
        _testSignalPreset.value = preset
    }

    fun toggleVvvfTestSignal() {
        val next = !_useTestSignal.value
        _useTestSignal.value = next

        if (next) {
            // 切到测试波形：停止采集/监听/录音/播放，并启动测试协程
            stopPlayback()
            recordingStopJob?.cancel()
            recordingStopJob = viewModelScope.launch(Dispatchers.Default) { stopRecordingInternalBlocking() }
            monitorTrack?.let {
                try {
                    it.release()
                } catch (_: Throwable) {
                }
            }
            monitorTrack = null
            stopImportedSignalInput()
            stopEngine()
            startVvvfTestJob()
        } else {
            stopVvvfTestJob()
        }
    }

    fun importAudioAsInput(context: Context, uri: Uri) {
        if (_isImportingAudio.value) return
        Log.i("WaveStudio", "Prepare to import audio: $uri")
        _isImportingAudio.value = true
        _importProgress.value = 0f
        _importResultMessage.value = null
        // 使用传入的 context（已被 attachBaseContext 包装语言），不能用 applicationContext
        val ctx = context
        importJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val decoded = decodeAudioToMonoTempPcm(
                    context, uri,
                    onProgress = { progress -> _importProgress.value = progress },
                    shouldCancel = { !isActive }
                )
                _importProgress.value = 1f
                if (!decoded.pcmFile.exists() || decoded.pcmFile.length() < 2L) {
                    val failMsg =
                        ctx.getString(R.string.import_audio_failed_prefix) + ctx.getString(R.string.preset_import_failed_empty)
                    _engineError.value = failMsg
                    _importResultMessage.value = failMsg
                    clearImportedSignalData(decoded)
                    return@launch
                }

                _engineError.value = null
                stopPlayback()
                if (_isRecording.value) stopRecording()
                stopVvvfTestJob()
                _useTestSignal.value = false
                // 支持"导入中替换导入"：先显式停掉旧的导入协程，避免 startImportedSignalJob 被 guard 掉。
                stopImportedSignalInput()
                stopEngine()

                importedSignalData = decoded
                _importedAudioLabel.value = decoded.label
                _importedSignalPaused.value = false
                _importedPlaybackPositionMs.value = 0L
                _importedPlaybackDurationMs.value = (
                        (decoded.pcmFile.length().coerceAtLeast(0L) / 2L) * 1000L / decoded.sampleRate.coerceAtLeast(1)
                        ).coerceAtLeast(1L)
                _useImportedSignal.value = true
                _isRunning.value = true
                startImportedSignalJob()
                Log.i("WaveStudio", "Import audio success: ${decoded.label ?: "unknown"}")
                _importResultMessage.value = ctx.getString(R.string.import_audio_success_prefix) + (decoded.label ?: "")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    // 用户主动取消：显示取消提示，不报错
                    _engineError.value = null
                    _importResultMessage.value = ctx.getString(R.string.import_audio_cancelled)
                } else {
                    val failMsg =
                        ctx.getString(R.string.import_audio_failed_prefix) + (t.message ?: t.javaClass.simpleName)
                    _engineError.value = failMsg
                    _importResultMessage.value = failMsg
                }
            } finally {
                _isImportingAudio.value = false
                importJob = null
            }
        }
    }

    fun clearImportResultMessage() {
        _importResultMessage.value = null
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    fun stopImportedSignalInput() {
        _useImportedSignal.value = false
        _importedSignalPaused.value = false
        _importedPlaybackPositionMs.value = 0L
        _importedPlaybackDurationMs.value = 0L
        stopImportedSignalJob()
        if (_isRecording.value) stopRecording()
        monitorTrack?.let {
            try {
                it.pause()
            } catch (_: Throwable) {
            }
            try {
                it.flush()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        monitorTrack = null
        _isRunning.value = false
        _audioInputAlive.value = false
        _lastReadSamples.value = 0
        _lastMaxAbsPcm.value = 0
        clearImportedSignalData(importedSignalData)
        importedSignalData = null
    }

    fun toggleImportedSignalPause() {
        if (!_useImportedSignal.value) return
        _importedSignalPaused.value = !_importedSignalPaused.value
    }

    @SuppressLint("SuspiciousIndentation")
    fun startEngine(context: Context) {
        if (_isRunning.value) return

        _engineError.value = null
        resetWaveformPublishStats()

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _engineError.value = "未授予麦克风权限（RECORD_AUDIO）"
            return
        }

        try {
            val requestedRate = _recordingSampleRate.value
            val preferredRates = buildList {
                add(requestedRate)
                listOf(48000, 44100, 32000, 24000, 22050).forEach {
                    if (!contains(it)) add(it)
                }
            }

            // 某些机型/模拟器 MIC 会初始化失败，做一个 fallback
            val sources = listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            var record: AudioRecord? = null
            var chosenSource: Int? = null
            var chosenRate: Int? = null
            var chosenBufferBytes: Int? = null
            var lastMinBufError: Int? = null
            rateLoop@ for (rate in preferredRates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
                if (minBuf <= 0) {
                    lastMinBufError = minBuf
                    continue
                }

                // 用更小的采集 block（~20ms）来降低处理尖峰带来的 AudioTrack underrun（噼啪响常见原因）
                val targetBlockBytes = (rate / 50) * 2 // 20ms, mono 16bit
                val bufferSizeInBytes = max(minBuf, targetBlockBytes)

                for (src in sources) {
                    val r = AudioRecord(
                        src,
                        rate,
                        channelConfig,
                        audioFormat,
                        bufferSizeInBytes
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        record = r
                        chosenSource = src
                        chosenRate = rate
                        chosenBufferBytes = bufferSizeInBytes
                        break@rateLoop
                    }
                    r.release()
                }
            }
            if (record == null) {
                _audioInitOk.value = false
                _audioState.value = AudioRecord.STATE_UNINITIALIZED
                _audioRecordingState.value = AudioRecord.RECORDSTATE_STOPPED
                _audioSourceUsed.value = Int.MIN_VALUE
                _engineError.value = if (lastMinBufError != null) {
                    "AudioRecord 初始化失败（getMinBufferSize=$lastMinBufError）"
                } else {
                    "AudioRecord 初始化失败（state != INITIALIZED）"
                }
                Log.e("WaveStudio", "AudioRecord init failed for all sources")
                return
            }

            sampleRate = chosenRate ?: requestedRate
            monitorRecordFilter = RtBiquadCascade(sampleRate)
            val captureBufferBytes = chosenBufferBytes ?: return
            val monitorTargetBlockBytes = (sampleRate / 50) * 2 // 20ms, mono 16bit
            if (requestedRate != sampleRate) {
                _engineError.value = "设备不支持 ${requestedRate}Hz，已自动切换到 ${sampleRate}Hz"
            } else {
                _engineError.value = null
            }

            _audioInitOk.value = true
            _audioState.value = record.state
            _audioRecordingState.value = record.recordingState
            _audioSourceUsed.value = chosenSource ?: Int.MIN_VALUE

            audioRecord = record
            record.startRecording()

            _audioRecordingState.value = record.recordingState

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _engineError.value = "startRecording() 后仍未进入 RECORDING（recState=${record.recordingState}）"
                Log.e(
                    "WaveStudio",
                    "startRecording did not enter RECORDSTATE_RECORDING; state=${record.state}, recState=${record.recordingState}"
                )
            }

            Log.i(
                "WaveStudio",
                "AudioRecord started: src=$chosenSource, sr=$sampleRate, bufBytes=${chosenBufferBytes ?: -1}, state=${record.state}, recState=${record.recordingState}"
            )

            _isRunning.value = true

            // 重置诊断
            _audioInputAlive.value = false
            _lastReadSamples.value = 0
            _lastMaxAbsPcm.value = 0
            _lastReadError.value = 0

            // 用 ring buffer，避免每帧都做 List 拼接
            captureJob = viewModelScope.launch(Dispatchers.Default) {
                // 尽量把采集线程提到更高优先级（防止被系统调度导致周期性 underrun）
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                } catch (_: Throwable) {
                }

                val shortBuf = ShortArray(captureBufferBytes / 2)
                val readBlockSamples = (sampleRate / 50).coerceAtLeast(64).coerceAtMost(shortBuf.size)

                // 监听输出：尽量低延迟
                fun ensureMonitorStarted(): AudioTrack? {
                    if (!_isMonitoring.value) {
                        monitorTrack?.let {
                            try {
                                it.pause()
                            } catch (_: Throwable) {
                            }
                            try {
                                it.flush()
                            } catch (_: Throwable) {
                            }
                            try {
                                it.release()
                            } catch (_: Throwable) {
                            }
                        }
                        monitorTrack = null
                        return null
                    }
                    if (monitorTrack != null) return monitorTrack

                    val minOut = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minOut <= 0) return null

                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    // 给监听更大的缓冲避免噼啪（underrun）；minOut 通常偏小
                    val outBufferBytes = max(minOut * 4, monitorTargetBlockBytes * 4)
                    val track = AudioTrack(
                        attrs,
                        format,
                        outBufferBytes,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    try {
                        track.play()
                    } catch (_: Throwable) {
                    }
                    monitorTrack = track
                    return track
                }

                // 预分配滤波 block 的 float 缓冲，避免每次都 new ArrayList (会造成 GC，形成周期性噼啪)
                val inBlock = FloatArray(shortBuf.size)
                val filteredBlock = FloatArray(shortBuf.size)
                var filteredOutShort: ShortArray? = null
                val monitorSilence = ShortArray(shortBuf.size)

                // 用 ring buffer，避免每帧都做 List 拼接
                val maxWindowSamples = max(64, (sampleRate * 1.0f).toInt()) // 1s 上限
                val ring = FloatArray(maxWindowSamples)
                val ringFiltered = FloatArray(maxWindowSamples)
                var ringWrite = 0
                var ringSize = 0
                var totalSamplesWritten = 0L

                // UI 波形用：复用一个实时滤波器 + 复用数组，避免 applyFiltersBiquad() 产生大量 List<Float>
                val uiWaveformFilter = RtBiquadCascade(sampleRate)
                val uiSlice = FloatArray(ring.size)
                val uiFiltered = FloatArray(ring.size)

                var lastNonZeroAt = 0L
                var consecutiveZeroReads = 0
                var lastDiagPublishAt = 0L
                var lastPublishedRead = 0
                var lastPublishedMaxAbs = 0
                var lastPublishedAlive = false
                var lastLogAt = 0L
                var lastUiUpdateAt = 0L
                // Trigger processing: runs inline with UI publish, no independent cadence needed
                var lastGoodTriggerMs = 0L
                // Fixed ring buffer read position for trigger engine. By always reading from
                // the same position, the engine sees the same physical samples every frame,
                // so lastAnchor/lastPeriod/lockCounter remain valid without seekAnchorTo().
                var triggerRingBase: Int = -1

                fun publishCaptureDiagnostics(readSamples: Int, maxAbs: Int, alive: Boolean, force: Boolean = false) {
                    val now = SystemClock.elapsedRealtime()
                    val significantChange =
                        alive != lastPublishedAlive ||
                                readSamples != lastPublishedRead ||
                                kotlin.math.abs(maxAbs - lastPublishedMaxAbs) >= 512
                    val publishIntervalMs = currentPublishIntervalMs()
                    val shouldPublish = force ||
                            alive != lastPublishedAlive ||
                            (significantChange && now - lastDiagPublishAt >= publishIntervalMs)
                    if (!shouldPublish) return

                    lastDiagPublishAt = now
                    lastPublishedRead = readSamples
                    lastPublishedMaxAbs = maxAbs
                    lastPublishedAlive = alive

                    _lastReadSamples.value = readSamples
                    _lastMaxAbsPcm.value = maxAbs
                    _audioInputAlive.value = alive
                }

                while (isActive && _isRunning.value) {
                    val read = record.read(shortBuf, 0, readBlockSamples)

                    // 诊断节流输出：每 2s 打一次
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLogAt >= 2000L) {
                        lastLogAt = now
                        Log.i(
                            "WaveStudio",
                            "capture: read=$read err=${_lastReadError.value} state=${record.state} recState=${record.recordingState}"
                        )
                    }

                    // read() 可能返回 ERROR_INVALID_OPERATION / ERROR_BAD_VALUE
                    if (read <= 0) {
                        val alive = (SystemClock.elapsedRealtime() - lastNonZeroAt) <= 300L
                        publishCaptureDiagnostics(read, 0, alive, force = read < 0)

                        if (read == 0) {
                            consecutiveZeroReads++
                            // 某些环境会卡在 0：连续 1s 都是 0，就认为采集卡死
                            if (consecutiveZeroReads >= 10) {
                                _engineError.value =
                                    "AudioRecord.read() 连续返回 0：可能是模拟器无麦克风输入或麦克风被占用"
                                Log.e("WaveStudio", "AudioRecord.read() stuck at 0; stopping engine")
                                _isRunning.value = false
                                break
                            }
                        } else {
                            consecutiveZeroReads = 0
                        }

                        // 如果是明显错误，直接停止，避免“按钮没反应”
                        if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                            _lastReadError.value = read
                            _engineError.value = "AudioRecord.read() 错误：$read"
                            _isRunning.value = false
                            break
                        }
                        continue
                    }

                    consecutiveZeroReads = 0

                    // ===== 监听/录音共享：把当前 block 做滤波（用于监听输出 + 写入录音） =====
                    val needFilteredBlock =
                        _isMonitoring.value || (_isRecording.value && (recordingCodec != null || wavOut != null))
                    if (needFilteredBlock) {
                        try {
                            // short -> float（复用 buffer）
                            for (i in 0 until read) {
                                inBlock[i] = (shortBuf[i] / 32768f).coerceIn(-1f, 1f)
                            }

                            // 更新系数（如参数变化）并就地滤波
                            monitorRecordFilter.update(
                                sampleRate = sampleRate,
                                lowPassEnabled = lowPassEnabled.value,
                                lowPassCutoffHz = lowPassCutoff.value,
                                lowPassOrder = lowPassOrder.value,
                                lowPassStageCutoffsHz = lowPassStageCutoffs.value,
                                highPassEnabled = highPassEnabled.value,
                                highPassCutoffHz = highPassCutoff.value,
                                highPassOrder = highPassOrder.value,
                                highPassStageCutoffsHz = highPassStageCutoffs.value,
                                filterGain = filterGain.value,
                                eqEnabled = eqEnabled.value,
                                eqBands = eqBands.value,
                                globalHighPassEnabled = _globalHighPassEnabled.value,
                                globalHighPassCutoffHz = _globalHighPassCutoff.value,
                            )
                            monitorRecordFilter.process(inBlock, filteredBlock, read)

                            if (filteredOutShort == null || filteredOutShort.size < read) {
                                filteredOutShort = ShortArray(read)
                            }
                            val outShort = filteredOutShort

                            for (i in 0 until read) {
                                val v = filteredBlock[i].coerceIn(-1f, 1f)
                                outShort[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        } catch (_: Throwable) {
                            filteredOutShort = null
                        }
                    } else {
                        filteredOutShort = null
                    }

                    // 监听：尽早写 PCM 到 AudioTrack（减少延迟、避免后面处理阻塞）
                    val track = ensureMonitorStarted()
                    if (track != null) {
                        try {
                            // 监听：强制使用滤波后的数据（如果滤波失败，则静音这一帧，避免“回退原始”造成听感错乱）
                            val out = filteredOutShort
                            val pcm = out ?: monitorSilence
                            var offset = 0
                            while (offset < read) {
                                val written = track.write(pcm, offset, read - offset, AudioTrack.WRITE_NON_BLOCKING)
                                if (written > 0) {
                                    offset += written
                                } else if (written == 0) {
                                    break
                                } else {
                                    throw IllegalStateException("AudioTrack.write failed: $written")
                                }
                            }
                        } catch (_: Throwable) {
                            try {
                                track.release()
                            } catch (_: Throwable) {
                            }
                            monitorTrack = null
                        }
                    }

                    var maxAbs = 0
                    for (i in 0 until read) {
                        val v = abs(shortBuf[i].toInt())
                        if (v > maxAbs) maxAbs = v
                    }
                    if (maxAbs > 0) {
                        lastNonZeroAt = SystemClock.elapsedRealtime()
                    }
                    val alive = (SystemClock.elapsedRealtime() - lastNonZeroAt) <= 300L
                    publishCaptureDiagnostics(read, maxAbs, alive)

                    // NOTE: No synthetic injection in normal '开始' mode.
                    // Always display real microphone input.

                    // 写入 ring buffer（raw + filtered）。
                    // 监听路径下优先复用 float 域的 filteredBlock，避免 short 量化回写导致波形细节损失。
                    for (i in 0 until read) {
                        val raw = (shortBuf[i] / 32768f).coerceIn(-1f, 1f)
                        val filtered = if (needFilteredBlock) {
                            filteredBlock[i].coerceIn(-1f, 1f)
                        } else {
                            raw
                        }
                        ring[ringWrite] = raw
                        ringFiltered[ringWrite] = filtered
                        ringWrite = (ringWrite + 1) % ring.size
                        if (ringSize < ring.size) ringSize++
                    }
                    totalSamplesWritten += read

                    // ===== UI 波形更新限流：避免每个 audio block 都触发 Compose 重组造成卡顿 =====
                    val nowUi = SystemClock.elapsedRealtime()
                    val busyRealtimePath =
                        _isMonitoring.value || (_isRecording.value && (recordingCodec != null || wavOut != null))

                    val actualPublishIntervalMs = currentPublishIntervalMs()

                    if (nowUi - lastUiUpdateAt >= actualPublishIntervalMs) {
                        lastUiUpdateAt = nowUi

                        val windowSamples = max(64, (sampleRate * (_windowMs.value / 1000f)).toInt())
                            .coerceAtMost(ring.size)

                        // Trigger analysis needs enough history to contain repeated edges even
                        // when the visible time base is very short. Keep 100 ms (at least two
                        // 20 Hz periods) when available; the display still uses only its newest
                        // requested window below.
                        val minTriggerAnalysisSamples = (sampleRate / 10).coerceAtLeast(640)
                        val fillCount = min(
                            max(windowSamples * 2, minTriggerAnalysisSamples),
                            ringSize,
                        )
                        val fetchStart = (ringWrite - fillCount + ring.size) % ring.size

                        for (i in 0 until fillCount) {
                            uiSlice[i] = ring[(fetchStart + i) % ring.size]
                        }

                        if (needFilteredBlock && filteredOutShort != null) {
                            for (i in 0 until fillCount) {
                                uiFiltered[i] = ringFiltered[(fetchStart + i) % ring.size]
                            }
                        } else {
                            uiWaveformFilter.update(
                                sampleRate = sampleRate,
                                lowPassEnabled = lowPassEnabled.value,
                                lowPassCutoffHz = lowPassCutoff.value,
                                lowPassOrder = lowPassOrder.value,
                                lowPassStageCutoffsHz = lowPassStageCutoffs.value,
                                highPassEnabled = highPassEnabled.value,
                                highPassCutoffHz = highPassCutoff.value,
                                highPassOrder = highPassOrder.value,
                                highPassStageCutoffsHz = highPassStageCutoffs.value,
                                filterGain = filterGain.value,
                                eqEnabled = eqEnabled.value,
                                eqBands = eqBands.value,
                                globalHighPassEnabled = _globalHighPassEnabled.value,
                                globalHighPassCutoffHz = _globalHighPassCutoff.value,
                            )
                            uiWaveformFilter.process(uiSlice, uiFiltered, fillCount)
                        }

                        // UI display is the newest requested portion of the analysis buffer.
                        // This keeps the non-triggered fallback and the trigger source in sync.
                        val targetPoints = 512
                        val displayCount = min(windowSamples, fillCount)
                        val displayStart = fillCount - displayCount
                        val publishedSpanMs = _windowMs.value
                        val downRaw = downsamplePeakFloatArray(
                            uiSlice, displayStart, displayStart + displayCount, targetPoints = targetPoints
                        )
                        val downFiltered =
                            downsamplePeakFloatArray(
                                uiFiltered, displayStart, displayStart + displayCount, targetPoints = targetPoints
                            )
                        val immersiveFiltered = resampleLinearFloatArray(
                            uiFiltered, displayStart, displayStart + displayCount, targetPoints
                        )

                        _rawWaveform.value = downRaw
                        _filteredWaveform.value = downFiltered
                        _immersiveFilteredWaveform.value = immersiveFiltered
                        _publishedWaveformSpanMs.value = publishedSpanMs.coerceAtLeast(_windowMs.value)
                        markWaveformPublished()

                        // ── Real trigger engine ──
                        val triggerArmedNow = _triggerEnabled
                        if (triggerArmedNow) {
                            try {
                                val trigSignal = uiFiltered.copyOfRange(0, fillCount)
                                val triggerCfg = SimpleTriggerEngine.Config(
                                    mode = SimpleTriggerEngine.Mode.RISING,
                                    sampleRateHz = sampleRate.toFloat(),
                                    preTriggerRatio = 0.20f,
                                    displayWindowSamples = displayCount,
                                    globalBase = totalSamplesWritten - fillCount,
                                    triggerThreshold = 0.02f,
                                    holdoffMs = 1f,
                                )
                                val trigResult = triggerEngine.process(trigSignal, triggerCfg)
                                _triggerResult.value = trigResult

                                if (trigResult.locked) {
                                    val trigWindow = triggerEngine.extractWindow(
                                        trigSignal, trigResult, displayCount, 0.20f
                                    )
                                    _triggeredWindow.value =
                                        downsamplePeakFloatArray(trigWindow, 0, trigWindow.size, targetPoints)
                                } else {
                                    _triggeredWindow.value = downFiltered
                                }
                            } catch (t: Throwable) {
                                Log.e("WaveStudio", "Trigger engine error", t)
                                _triggeredWindow.value = downFiltered
                            }
                        } else {
                            _triggerResult.value = null
                            _triggeredWindow.value = floatArrayOf()
                        }
                        if (_pendingTimeWindowUpdate.value) {
                            _pendingTimeWindowUpdate.value = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            _engineError.value = "启动录音失败：${t.message}"
            Log.e("WaveStudio", "startEngine failed", t)
            stopEngine()
        }
    }

    private var testJob: Job? = null
    private var cachedTestAudioSamples: FloatArray? = null
    private var wavTestSignalSampleRate: Int = 0
    private var importedSignalJob: Job? = null
    private var importJob: Job? = null
    private var importedSignalData: ImportedAudioData? = null
    private var importedSeekRequestBytes: Long? = null

    private data class ImportedAudioData(
        val sampleRate: Int,
        val pcmFile: File,
        val label: String,
    )

    private fun clearImportedSignalData(data: ImportedAudioData?) {
        val file = data?.pcmFile ?: return
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
        }
    }

    /** Load cached WAV test signal from res/raw, parse 16-bit mono PCM into -1..1 floats */
    private fun loadWavTestSignal(): FloatArray? {
        cachedTestAudioSamples?.let { return it }
        try {
            val app = getApplication<Application>()
            val rawResId = when (_testSignalPreset.value) {
                TestSignalPreset.W7 -> R.raw.test_signal_w7
                else -> R.raw.test_signal_5p
            }
            val inputStream = app.resources.openRawResource(rawResId)
            val rawBytes = inputStream.readBytes()
            inputStream.close()
            if (rawBytes.size < 44) return null
            // Parse WAV header: sample rate at byte 24 (4 bytes LE), data size at byte 40 (4 bytes LE)
            val formatTag = (rawBytes[20].toInt() and 0xFF)
                .or((rawBytes[21].toInt() and 0xFF) shl 8)
            val sampleRate = (rawBytes[24].toInt() and 0xFF)
                .or((rawBytes[25].toInt() and 0xFF) shl 8)
                .or((rawBytes[26].toInt() and 0xFF) shl 16)
                .or((rawBytes[27].toInt() and 0xFF) shl 24)
            wavTestSignalSampleRate = sampleRate
            val bitsPerSample = (rawBytes[34].toInt() and 0xFF)
                .or((rawBytes[35].toInt() and 0xFF) shl 8)
            val dataSize = (rawBytes[40].toInt() and 0xFF)
                .or((rawBytes[41].toInt() and 0xFF) shl 8)
                .or((rawBytes[42].toInt() and 0xFF) shl 16)
                .or((rawBytes[43].toInt() and 0xFF) shl 24)
            val offset = 44
            val samples: FloatArray
            if (formatTag == 3) {
                // WAVE_FORMAT_IEEE_FLOAT: 32-bit float
                val sampleCount = dataSize / 4
                samples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val bits = (rawBytes[offset + i * 4].toInt() and 0xFF)
                        .or((rawBytes[offset + i * 4 + 1].toInt() and 0xFF) shl 8)
                        .or((rawBytes[offset + i * 4 + 2].toInt() and 0xFF) shl 16)
                        .or((rawBytes[offset + i * 4 + 3].toInt() and 0xFF) shl 24)
                    samples[i] = Float.fromBits(bits)
                }
            } else {
                // WAVE_FORMAT_PCM: assume 16-bit mono
                val sampleCount = dataSize / 2
                samples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val lo = rawBytes[offset + i * 2].toInt() and 0xFF
                    val hi = rawBytes[offset + i * 2 + 1].toInt() and 0xFF
                    val pcm16 = (hi shl 8 or lo).toShort().toInt()
                    samples[i] = pcm16 / 32767f
                }
            }
            cachedTestAudioSamples = samples
            return samples
        } catch (e: Exception) {
            Log.e("Oscope", "loadWavTestSignal failed", e)
            return null
        }
    }

    private fun startVvvfTestJob() {
        if (testJob != null) return
        resetWaveformPublishStats()
        testJob = viewModelScope.launch(Dispatchers.Default) {
            var cachedWavResampled: FloatArray? = null
            var lastWavPreset: TestSignalPreset? = null
            var wasWav = false
            var cycleOffset = 0L

            while (isActive && _useTestSignal.value) {
                val window = _windowMs.value
                val displayTargetPoints = 512
                val immersiveTargetPoints = 512 * 3
                val publishedSpanMs = window * 3f

                val samplesNeeded = ((sampleRate * publishedSpanMs) / 1000f).roundToInt().coerceAtLeast(1)
                val isWav =
                    _testSignalPreset.value == TestSignalPreset.REC_5P || _testSignalPreset.value == TestSignalPreset.W7

                // Invalidate WAV caches when switching between different WAV presets (e.g. REC_5P ↔ W7)
                if (isWav && _testSignalPreset.value != lastWavPreset) {
                    cachedTestAudioSamples = null
                    cachedWavResampled = null
                    lastWavPreset = _testSignalPreset.value
                }

                // (Re)load resampled WAV cache only when entering WAV mode
                if (isWav && cachedWavResampled == null) {
                    val wavSamples = loadWavTestSignal()
                    val wavSampleRate = if (wavSamples != null) wavTestSignalSampleRate else sampleRate
                    cachedWavResampled = if (wavSamples != null && wavSampleRate != sampleRate) {
                        resampleLinearFloatArray(
                            wavSamples,
                            0,
                            wavSamples.size,
                            (wavSamples.size.toLong() * sampleRate / wavSampleRate).toInt()
                        )
                    } else {
                        wavSamples
                    }
                }
                if (!isWav) {
                    cachedWavResampled = null
                    lastWavPreset = null
                }

                // Reset cycle position when switching modes (SPWM↔WAV)
                if (isWav != wasWav) {
                    cycleOffset = 0
                    wasWav = isWav
                }

                val samples: FloatArray
                if (isWav && cachedWavResampled != null && cachedWavResampled!!.isNotEmpty()) {
                    val cache = cachedWavResampled!!
                    // Static display: always show from the beginning, never scroll
                    samples = FloatArray(samplesNeeded) { i ->
                        if (i < cache.size) cache[i] else 0f
                    }
                    cycleOffset = 0
                } else {
                    val preset = _testSignalPreset.value
                    samples = VvvfTestSignal.generateLineUv(
                        sampleRate = sampleRate,
                        windowMs = publishedSpanMs,
                        baseHz = 50f,
                        modulationAmp = preset.modulationAmp,
                        carrierMultiple = preset.carrierMultiple,
                    )
                }

                val rawList = samples.toList()
                val visibleWindowSamples = ((sampleRate * window) / 1000f).roundToInt().coerceIn(1, samplesNeeded)
                val visibleStart = (samplesNeeded - visibleWindowSamples).coerceAtLeast(0)
                val downRaw =
                    downsamplePeakFloatArray(samples, visibleStart, samples.size, targetPoints = displayTargetPoints)

                val filteredAll = applyFiltersBiquad(
                    input = rawList,
                    sampleRate = sampleRate,
                    lowPassEnabled = lowPassEnabled.value,
                    lowPassCutoffHz = lowPassCutoff.value,
                    highPassEnabled = highPassEnabled.value,
                    highPassCutoffHz = highPassCutoff.value
                )
                val downFiltered = downsamplePeak(
                    filteredAll.subList(visibleStart, filteredAll.size),
                    targetPoints = displayTargetPoints
                ).toFloatArray()
                val immersiveFiltered =
                    resampleLinearFloatArray(filteredAll.toFloatArray(), 0, filteredAll.size, immersiveTargetPoints)

                _rawWaveform.value = downRaw
                _filteredWaveform.value = downFiltered
                _immersiveFilteredWaveform.value = immersiveFiltered
                _publishedWaveformSpanMs.value = publishedSpanMs
                markWaveformPublished()

                _audioInputAlive.value = true
                _lastReadSamples.value = samplesNeeded
                _lastMaxAbsPcm.value = if (isWav && cachedWavResampled != null) {
                    var maxAbs = 0
                    for (i in samples.indices) {
                        val pcm = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        val a = kotlin.math.abs(pcm)
                        if (a > maxAbs) maxAbs = a
                    }
                    maxAbs
                } else {
                    32767
                }

                kotlinx.coroutines.delay(currentPublishIntervalMs())
            }
        }
    }

    private fun stopVvvfTestJob() {
        testJob?.cancel()
        testJob = null
    }

    fun seekImportedSignalTo(positionMs: Long) {
        val totalMs = _importedPlaybackDurationMs.value.coerceAtLeast(1L)
        val posMs = positionMs.coerceIn(0L, totalMs)
        val sRate = importedSignalData?.sampleRate?.coerceAtLeast(8000) ?: return
        val posBytes = (posMs * sRate / 1000L) * 2L
        importedSeekRequestBytes = posBytes - (posBytes % 2L)
        _importedPlaybackPositionMs.value = posMs
    }

    private fun startImportedSignalJob() {
        if (importedSignalJob != null) return
        val data = importedSignalData ?: return
        resetWaveformPublishStats()
        importedSignalJob = viewModelScope.launch(Dispatchers.Default) {
            var raf: RandomAccessFile? = null
            try {
                val sourceFile = data.pcmFile
                val sourceBytes = sourceFile.length().coerceAtLeast(0L)
                if (!sourceFile.exists() || sourceBytes < 2L) {
                    _useImportedSignal.value = false
                    return@launch
                }

                raf = RandomAccessFile(sourceFile, "r")

                val importSampleRate = data.sampleRate.coerceAtLeast(8000)
                sampleRate = importSampleRate
                monitorRecordFilter = RtBiquadCascade(sampleRate)

                val totalAudioBytes = (sourceBytes - (sourceBytes % 2L)).coerceAtLeast(2L)
                val totalDurationMs = ((totalAudioBytes / 2L) * 1000L / sampleRate.coerceAtLeast(1)).coerceAtLeast(1L)
                _importedPlaybackDurationMs.value = totalDurationMs
                _importedPlaybackPositionMs.value = 0L

                val maxWindowSamples = max(64, (sampleRate * 1.0f).toInt())
                val ring = FloatArray(maxWindowSamples)
                val ringFiltered = FloatArray(maxWindowSamples)
                val uiSlice = FloatArray(ring.size)
                val uiFiltered = FloatArray(ring.size)
                val uiWaveformFilter = RtBiquadCascade(sampleRate)
                val inBlock = FloatArray(max(64, sampleRate / 50))
                val filteredBlock = FloatArray(inBlock.size)
                var filteredOutShort: ShortArray? = null
                val monitorSilence = ShortArray(inBlock.size)

                fun ensureMonitorStarted(): AudioTrack? {
                    if (!_isMonitoring.value) {
                        monitorTrack?.let {
                            try {
                                it.pause()
                            } catch (_: Throwable) {
                            }
                            try {
                                it.flush()
                            } catch (_: Throwable) {
                            }
                            try {
                                it.release()
                            } catch (_: Throwable) {
                            }
                        }
                        monitorTrack = null
                        return null
                    }
                    if (monitorTrack != null) return monitorTrack

                    val minOut = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minOut <= 0) return null

                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    val monitorTargetBlockBytes = (sampleRate / 50) * 2
                    val outBufferBytes = max(minOut * 4, monitorTargetBlockBytes * 4)
                    val track = AudioTrack(
                        attrs,
                        format,
                        outBufferBytes,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    try {
                        track.play()
                    } catch (_: Throwable) {
                    }
                    monitorTrack = track
                    return track
                }

                var ringWrite = 0
                var ringSize = 0
                var totalSamplesWritten = 0L
                var lastUiUpdateAt = 0L
                // Trigger processing: runs inline with UI publish, no independent cadence needed
                var lastGoodTriggerMs = 0L

                val chunkSize = inBlock.size
                val chunkDurationMs = (chunkSize * 1000L / sampleRate).coerceAtLeast(8L)
                val chunk = FloatArray(chunkSize)
                val chunkBytes = ByteArray(chunkSize * 2)
                var filePosBytes = 0L
                var nextChunkAtMs = SystemClock.elapsedRealtime()

                fun fillChunkBytes(): Int {
                    val localRaf = raf ?: return 0
                    var filled = 0
                    while (filled < chunkBytes.size && isActive && _useImportedSignal.value) {
                        val seekReq = importedSeekRequestBytes
                        if (seekReq != null) {
                            filePosBytes = seekReq
                            importedSeekRequestBytes = null
                        }
                        if (filePosBytes >= sourceBytes) filePosBytes = 0L
                        localRaf.seek(filePosBytes)
                        val maxRead = minOf((sourceBytes - filePosBytes).toInt(), chunkBytes.size - filled)
                        val read = localRaf.read(chunkBytes, filled, maxRead)
                        if (read <= 0) {
                            filePosBytes = 0L
                            continue
                        }
                        filled += read
                        filePosBytes += read.toLong()
                    }
                    return filled
                }

                fun publishImportedPosition() {
                    val durationBytes = totalAudioBytes.coerceAtLeast(2L)
                    val posBytes = (filePosBytes % durationBytes).coerceAtLeast(0L)
                    val posMs = ((posBytes / 2L) * 1000L / sampleRate.coerceAtLeast(1)).coerceIn(0L, totalDurationMs)
                    _importedPlaybackPositionMs.value = posMs
                }

                while (isActive && _useImportedSignal.value) {
                    if (_importedSignalPaused.value) {
                        _audioInputAlive.value = true
                        delay(50)
                        continue
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now < nextChunkAtMs) {
                        delay(nextChunkAtMs - now)
                    } else if (now - nextChunkAtMs > chunkDurationMs * 4L) {
                        // If processing was stalled, resync to avoid long-term drift bursts.
                        nextChunkAtMs = now
                    }

                    val bytesRead = fillChunkBytes()
                    if (bytesRead < chunkBytes.size) {
                        if (bytesRead <= 0) break
                    }

                    publishImportedPosition()

                    for (i in 0 until chunkSize) {
                        val lo = chunkBytes[i * 2].toInt() and 0xFF
                        val hi = chunkBytes[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort().toInt()
                        chunk[i] = (sample / 32768f).coerceIn(-1f, 1f)
                    }

                    var maxAbs = 0
                    for (i in 0 until chunkSize) {
                        val absPcm = abs((chunk[i] * 32767f).toInt())
                        if (absPcm > maxAbs) maxAbs = absPcm
                    }
                    _audioInputAlive.value = maxAbs > 0
                    _lastReadSamples.value = chunkSize
                    _lastMaxAbsPcm.value = maxAbs

                    val needFilteredBlock =
                        _isMonitoring.value || (_isRecording.value && (recordingCodec != null || wavOut != null))
                    if (needFilteredBlock) {
                        for (i in 0 until chunkSize) {
                            inBlock[i] = chunk[i].coerceIn(-1f, 1f)
                        }
                        monitorRecordFilter.update(
                            sampleRate = sampleRate,
                            lowPassEnabled = lowPassEnabled.value,
                            lowPassCutoffHz = lowPassCutoff.value,
                            lowPassOrder = lowPassOrder.value,
                            lowPassStageCutoffsHz = lowPassStageCutoffs.value,
                            highPassEnabled = highPassEnabled.value,
                            highPassCutoffHz = highPassCutoff.value,
                            highPassOrder = highPassOrder.value,
                            highPassStageCutoffsHz = highPassStageCutoffs.value,
                            filterGain = filterGain.value,
                            eqEnabled = eqEnabled.value,
                            eqBands = eqBands.value,
                            globalHighPassEnabled = globalHighPassEnabled.value,
                            globalHighPassCutoffHz = globalHighPassCutoff.value,
                        )
                        monitorRecordFilter.process(inBlock, filteredBlock, chunkSize)

                        if (filteredOutShort == null || filteredOutShort.size < chunkSize) {
                            filteredOutShort = ShortArray(chunkSize)
                        }
                        val outShort = filteredOutShort!!
                        for (i in 0 until chunkSize) {
                            val v = filteredBlock[i].coerceIn(-1f, 1f)
                            outShort[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    } else {
                        filteredOutShort = null
                    }

                    // 写入 ring（raw + filtered）。导入监听时直接复用块级滤波结果，避免 UI 二次整窗滤波。
                    for (i in 0 until chunkSize) {
                        val raw = chunk[i].coerceIn(-1f, 1f)
                        val filtered = if (needFilteredBlock) {
                            filteredBlock[i].coerceIn(-1f, 1f)
                        } else {
                            raw
                        }
                        ring[ringWrite] = raw
                        ringFiltered[ringWrite] = filtered
                        ringWrite = (ringWrite + 1) % ring.size
                        if (ringSize < ring.size) ringSize++
                    }
                    totalSamplesWritten += chunkSize

                    val track = ensureMonitorStarted()
                    if (track != null) {
                        try {
                            val out = filteredOutShort
                            val pcm = out ?: monitorSilence
                            var offset = 0
                            while (offset < chunkSize) {
                                val written =
                                    track.write(pcm, offset, chunkSize - offset, AudioTrack.WRITE_NON_BLOCKING)
                                if (written > 0) {
                                    offset += written
                                } else if (written == 0) {
                                    break
                                } else {
                                    throw IllegalStateException("AudioTrack.write failed: $written")
                                }
                            }
                        } catch (_: Throwable) {
                            try {
                                track.release()
                            } catch (_: Throwable) {
                            }
                            monitorTrack = null
                        }
                    }

                    val nowUi = SystemClock.elapsedRealtime()
                    val actualPublishIntervalMs = currentPublishIntervalMs()
                    if (nowUi - lastUiUpdateAt >= actualPublishIntervalMs) {
                        lastUiUpdateAt = nowUi

                        val windowSamples = max(64, (sampleRate * (_windowMs.value / 1000f)).toInt())
                            .coerceAtMost(ring.size)
                        val triggerArmed = _triggerEnabled
                        val monitorOn = _isMonitoring.value
                        val minTriggerAnalysisSamples = (sampleRate / 10).coerceAtLeast(640)
                        val analysisSamples = max(windowSamples * 2, minTriggerAnalysisSamples)
                        val fetchSamples = min(ringSize, analysisSamples)
                        if (fetchSamples > 0) {
                            val fillCount = fetchSamples
                            val fetchStart = (ringWrite - fillCount + ring.size) % ring.size
                            for (i in 0 until fillCount) {
                                uiSlice[i] = ring[(fetchStart + i) % ring.size]
                            }

                            if (needFilteredBlock) {
                                for (i in 0 until fillCount) {
                                    uiFiltered[i] = ringFiltered[(fetchStart + i) % ring.size]
                                }
                            } else {
                                uiWaveformFilter.update(
                                    sampleRate = sampleRate,
                                    lowPassEnabled = lowPassEnabled.value,
                                    lowPassCutoffHz = lowPassCutoff.value,
                                    lowPassOrder = lowPassOrder.value,
                                    lowPassStageCutoffsHz = lowPassStageCutoffs.value,
                                    highPassEnabled = highPassEnabled.value,
                                    highPassCutoffHz = highPassCutoff.value,
                                    highPassOrder = highPassOrder.value,
                                    highPassStageCutoffsHz = highPassStageCutoffs.value,
                                    filterGain = filterGain.value,
                                    eqEnabled = eqEnabled.value,
                                    eqBands = eqBands.value,
                                    globalHighPassEnabled = globalHighPassEnabled.value,
                                    globalHighPassCutoffHz = globalHighPassCutoff.value,
                                )
                                uiWaveformFilter.process(uiSlice, uiFiltered, fillCount)
                            }

                            val targetPoints = when {
                                monitorOn && triggerArmed -> 640
                                monitorOn -> 384
                                else -> 704
                            }
                            val displayCount = min(windowSamples, fillCount)
                            val displayStart = fillCount - displayCount
                            val downRaw =
                                downsamplePeakFloatArray(
                                    uiSlice, displayStart, displayStart + displayCount, targetPoints = targetPoints
                                )
                            val downFiltered =
                                downsamplePeakFloatArray(
                                    uiFiltered, displayStart, displayStart + displayCount, targetPoints = targetPoints
                                )
                            val immersiveFiltered = resampleLinearFloatArray(
                                uiFiltered, displayStart, displayStart + displayCount, targetPoints
                            )
                            val publishedSpanMs = _windowMs.value

                            _rawWaveform.value = downRaw
                            _filteredWaveform.value = downFiltered
                            _immersiveFilteredWaveform.value = immersiveFiltered
                            _publishedWaveformSpanMs.value = publishedSpanMs.coerceAtLeast(_windowMs.value)
                            markWaveformPublished()

                            // ── Real trigger engine (imported signal) ──
                            val triggerArmedNow2 = _triggerEnabled
                            if (triggerArmedNow2) {
                                try {
                                    val trigSignal = uiFiltered.copyOfRange(0, fillCount)
                                    val triggerCfg = SimpleTriggerEngine.Config(
                                        mode = SimpleTriggerEngine.Mode.RISING,
                                        sampleRateHz = sampleRate.toFloat(),
                                        preTriggerRatio = 0.20f,
                                        displayWindowSamples = displayCount,
                                        globalBase = totalSamplesWritten - fillCount,
                                        triggerThreshold = 0.02f,
                                        holdoffMs = 1f,
                                    )
                                    val trigResult = triggerEngine.process(trigSignal, triggerCfg)
                                    _triggerResult.value = trigResult

                                    if (trigResult.locked) {
                                        val trigWindow = triggerEngine.extractWindow(
                                            trigSignal, trigResult, displayCount, 0.20f
                                        )
                                        _triggeredWindow.value =
                                            downsamplePeakFloatArray(trigWindow, 0, trigWindow.size, targetPoints)
                                    } else {
                                        _triggeredWindow.value = downFiltered
                                    }
                                } catch (t: Throwable) {
                                    Log.e("WaveStudio", "Trigger engine error (imported)", t)
                                    _triggeredWindow.value = downFiltered
                                }
                            } else {
                                _triggerResult.value = null
                                _triggeredWindow.value = floatArrayOf()
                            }
                            if (_pendingTimeWindowUpdate.value) {
                                _pendingTimeWindowUpdate.value = false
                            }
                        }
                    }
                    if (_isRecording.value && (recordingCodec != null || wavOut != null)) {
                        filteredOutShort?.let { out ->
                            writeRecordingPcm(out, chunkSize)
                        }
                    }

                    // Imported loop keeps a stable real-time cadence regardless of monitor write mode.
                    nextChunkAtMs += chunkDurationMs
                }
            } finally {
                _isRunning.value = false
                importedSignalJob = null
            }
        }
    }

    private fun stopImportedSignalJob() {
        importedSignalJob?.cancel()
        importedSignalJob = null
    }

    private fun decodeAudioToMonoTempPcm(
        context: Context,
        uri: Uri,
        onProgress: ((Float) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
    ): ImportedAudioData {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开音频文件")
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            try {
                afd.close()
            } catch (_: Throwable) {
            }
            extractor.release()
            throw IllegalStateException("未找到可解码音轨")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("音频格式未知")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var outSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        var outEncoding = AudioFormat.ENCODING_PCM_16BIT

        val totalBytes = afd.length
        var bytesRead = 0L

        val outFile = File.createTempFile("oscope_import_", ".pcm", context.cacheDir)
        var success = false

        try {
            FileOutputStream(outFile).buffered().use { out ->
                fun writeSample(v: Float) {
                    val pcm = (v.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
                    out.write(pcm and 0xFF)
                    out.write((pcm ushr 8) and 0xFF)
                }

                while (!sawOutputEos) {
                    if (shouldCancel?.invoke() == true) {
                        throw kotlinx.coroutines.CancellationException("导入已取消")
                    }
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    bytesRead += sampleSize
                                    if (totalBytes > 0 && onProgress != null) {
                                        onProgress((bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.95f))
                                    }
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            outSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                            outEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && info.size > 0) {
                                val data = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(data)

                                if (outEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val bb = java.nio.ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                    val frameCount = info.size / (4 * outChannels)
                                    for (f in 0 until frameCount) {
                                        var s = 0f
                                        for (c in 0 until outChannels) s += bb.float
                                        writeSample(s / outChannels)
                                    }
                                } else {
                                    val bb = java.nio.ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                                    val frameCount = info.size / (2 * outChannels)
                                    for (f in 0 until frameCount) {
                                        var s = 0f
                                        for (c in 0 until outChannels) s += (bb.short.toInt() / 32768f)
                                        writeSample(s / outChannels)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                        }
                    }
                }
                success = true
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            extractor.release()
            try {
                afd.close()
            } catch (_: Throwable) {
            }
            if (!success) {
                try {
                    outFile.delete()
                } catch (_: Throwable) {
                }
            }
        }

        return ImportedAudioData(
            sampleRate = outSampleRate,
            pcmFile = outFile,
            label = resolveDisplayName(context, uri),
        )
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment ?: "导入音频"
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && c.moveToFirst()) c.getString(index) ?: fallback else fallback
            } ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    fun stopEngine() {
        _isRunning.value = false
        _isRecording.value = false

        // reset realtime filter states so next start doesn't pop
        try {
            monitorRecordFilter.resetState()
        } catch (_: Throwable) {
        }

        captureJob?.cancel()
        captureJob = null

        // stop monitor
        monitorTrack?.let {
            try {
                it.pause()
            } catch (_: Throwable) {
            }
            try {
                it.flush()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        monitorTrack = null

        // stop recording if any (don't block)
        recordingStopJob?.cancel()
        recordingStopJob = viewModelScope.launch(Dispatchers.Default) {
            stopRecordingInternalBlocking()
        }

        // stop playback
        stopPlayback()

        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
            it.release()
        }
        audioRecord = null

        _audioInitOk.value = false
        _audioState.value = AudioRecord.STATE_UNINITIALIZED
        _audioRecordingState.value = AudioRecord.RECORDSTATE_STOPPED
        _audioSourceUsed.value = Int.MIN_VALUE
        _lastReadError.value = 0

        _audioInputAlive.value = false
        _lastReadSamples.value = 0
        _lastMaxAbsPcm.value = 0
        _immersiveFilteredWaveform.value = floatArrayOf()
        _publishedWaveformSpanMs.value = _windowMs.value
    }

    override fun onCleared() {
        stopPlayback()
        // Cancel any in-flight stop — but don't rely on viewModelScope (it's cancelled after onCleared returns).
        recordingStopJob?.cancel()
        // Use NonCancellable so the cleanup runs even after the owning scope is cancelled.
        // A brief blocking call here is acceptable: the ViewModel is being destroyed, and
        // we must finalize the recording file to avoid corruption.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Default) {
                stopRecordingInternalBlocking()
            }
        }
        monitorTrack?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        monitorTrack = null
        try {
            monitorRecordFilter.resetState()
        } catch (_: Throwable) {
        }
        stopVvvfTestJob()
        stopImportedSignalInput()
        stopEngine()
        super.onCleared()
    }

    // ===== Recording settings =====
    enum class RecordingFormat(
        val label: String,
        val extension: String,
        /** For MediaMuxer based containers; ignored for raw formats like WAV. */
        val muxerFormat: Int,
        /** For MediaCodec encoder; ignored for raw PCM containers like WAV. */
        val mimeType: String,
        /** Whether this format is written using MediaMuxer + MediaCodec. */
        val usesMuxer: Boolean,
    ) {
        M4A_AAC(
            label = "m4a",
            extension = "m4a",
            muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            mimeType = android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
            usesMuxer = true
        ),
        WAV(
            label = "wav",
            extension = "wav",
            muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            mimeType = "audio/raw",
            usesMuxer = false
        ),
    }

    private val _recordingFormat = MutableStateFlow(RecordingFormat.M4A_AAC)
    val recordingFormat: StateFlow<RecordingFormat> = _recordingFormat.asStateFlow()

    // Recording encoder sample rate. This is independent of capture sampleRate.
    // We only support recording at or below the capture sample rate.
    // The engine currently captures at fixed 44.1k, so requesting 48k would otherwise
    // create a fake upsampled file with wrong spectral behavior.
    private val _recordingSampleRate = MutableStateFlow(44100)
    val recordingSampleRate: StateFlow<Int> = _recordingSampleRate.asStateFlow()

    init {
        restorePersistedAppSettings()
    }

    fun setRecordingFormat(fmt: RecordingFormat) {
        if (_isRecording.value) return
        _recordingFormat.value = fmt
        persistAppSettings()
    }

    fun setRecordingSampleRate(sr: Int) {
        if (_isRecording.value) return
        val safe = sanitizeRecordingSampleRate(sr)
        _recordingSampleRate.value = safe
        persistAppSettings()
    }

    fun setPublishRateOption(option: PublishRateOption) {
        if (_publishRateOption.value == option) return
        _publishRateOption.value = option
        persistAppSettings()
    }

    private fun restorePersistedAppSettings() {
        val restoredFormat = settingsPrefs.getString(KEY_RECORDING_FORMAT, null)
            ?.let { raw -> RecordingFormat.entries.firstOrNull { it.name == raw } }
            ?: RecordingFormat.M4A_AAC
        _recordingFormat.value = restoredFormat

        val restoredSampleRate = settingsPrefs.getInt(KEY_RECORDING_SAMPLE_RATE, _recordingSampleRate.value)
        _recordingSampleRate.value = sanitizeRecordingSampleRate(restoredSampleRate)

        val restoredPublishRateHz = settingsPrefs.getInt(KEY_WAVEFORM_PUBLISH_RATE_HZ, _publishRateOption.value.hz)
        _publishRateOption.value = sanitizePublishRateOption(restoredPublishRateHz)
        // global HP
        val globalHpEnabled = settingsPrefs.getBoolean(KEY_GLOBAL_HP_ENABLED, true)
        _globalHighPassEnabled.value = globalHpEnabled
        val globalHpCut = settingsPrefs.getFloat(KEY_GLOBAL_HP_CUTOFF_HZ, 1f)
        _globalHighPassCutoff.value = globalHpCut.coerceAtLeast(0.1f)
        // restore recordings list
        restorePersistedRecordings()
    }

    private fun persistAppSettings() {
        settingsPrefs.edit()
            .putString(KEY_RECORDING_FORMAT, _recordingFormat.value.name)
            .putInt(KEY_RECORDING_SAMPLE_RATE, _recordingSampleRate.value)
            .putInt(KEY_WAVEFORM_PUBLISH_RATE_HZ, _publishRateOption.value.hz)
            .putBoolean(KEY_GLOBAL_HP_ENABLED, _globalHighPassEnabled.value)
            .putFloat(KEY_GLOBAL_HP_CUTOFF_HZ, _globalHighPassCutoff.value)
            .apply()
    }

    fun setGlobalHighPassEnabled(enabled: Boolean) {
        _globalHighPassEnabled.value = enabled
        persistAppSettings()
    }

    fun setGlobalHighPassCutoff(hz: Float) {
        _globalHighPassCutoff.value = hz.coerceAtLeast(0.1f)
        persistAppSettings()
    }

    private fun sanitizeRecordingSampleRate(sr: Int): Int {
        return when (sr) {
            22050, 24000, 32000, 44100, 48000 -> sr
            else -> sr.coerceIn(22050, 48000)
        }
    }

    private fun sanitizePublishRateOption(hz: Int): PublishRateOption {
        return PublishRateOption.entries.firstOrNull { it.hz == hz } ?: PublishRateOption.HZ_20
    }

    private fun ensureRecordingDownsampleBuffer(minLen: Int): ShortArray {
        if (recordingDownsampleBuffer.size < minLen) {
            recordingDownsampleBuffer = ShortArray(minLen)
        }
        return recordingDownsampleBuffer
    }

    private fun ensureRecordingPcmBytesBuffer(minBytes: Int): ByteArray {
        if (recordingPcmBytesBuffer.size < minBytes) {
            recordingPcmBytesBuffer = ByteArray(minBytes)
        }
        return recordingPcmBytesBuffer
    }

    private fun downsampleNearest(pcm16: ShortArray, len: Int, srcRate: Int, dstRate: Int): Pair<ShortArray, Int> {
        if (dstRate == srcRate) return pcm16 to len
        if (dstRate <= 0 || srcRate <= 0) return pcm16 to len
        // Never upsample above capture rate here. Fake upsampling creates misleading spectra
        // and obvious artifacts in WAV files.
        if (dstRate >= srcRate) return pcm16 to len
        val outLen = ((len.toLong() * dstRate.toLong()) / srcRate.toLong()).toInt().coerceAtLeast(1)
        val out = ensureRecordingDownsampleBuffer(outLen)
        for (i in 0 until outLen) {
            val srcIndex = ((i.toLong() * srcRate.toLong()) / dstRate.toLong()).toInt().coerceIn(0, len - 1)
            out[i] = pcm16[srcIndex]
        }
        return out to outLen
    }

    private fun pcm16ToLeBytes(pcm: ShortArray, len: Int): Pair<ByteArray, Int> {
        val byteCount = len * 2
        val bytes = ensureRecordingPcmBytesBuffer(byteCount)
        var bi = 0
        for (i in 0 until len) {
            val v = pcm[i].toInt()
            bytes[bi++] = (v and 0xFF).toByte()
            bytes[bi++] = ((v ushr 8) and 0xFF).toByte()
        }
        return bytes to byteCount
    }

    private fun defaultRecordingDir(context: Context): File {
        val base = context.externalCacheDir ?: context.cacheDir
        return File(base, "recordings").apply { mkdirs() }
    }

    private fun isUsableDirectory(dir: File): Boolean {
        return try {
            (dir.exists() || dir.mkdirs()) && dir.isDirectory && dir.canWrite()
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveRecordingDir(context: Context): File {
        pendingRelativeRecordingPath = null
        lastRecordingUsedAbsoluteCustomPath = false

        val treeUriStr = settingsPrefs.getString(KEY_CUSTOM_RECORDING_TREE_URI, null)
        if (!treeUriStr.isNullOrBlank()) {
            return defaultRecordingDir(context)
        }

        val customPath = settingsPrefs.getString(KEY_CUSTOM_RECORDING_PATH, null)
        if (!customPath.isNullOrBlank()) {
            val normalized = customPath.trim()
            val candidate = try {
                File(normalized)
            } catch (_: Throwable) {
                null
            }
            if (candidate != null) {
                if (candidate.isAbsolute) {
                    val dir = if (
                        normalized.endsWith(".wav", ignoreCase = true) ||
                        normalized.endsWith(".m4a", ignoreCase = true) ||
                        normalized.endsWith(".mp4", ignoreCase = true) ||
                        normalized.endsWith(".aac", ignoreCase = true)
                    ) {
                        candidate.parentFile ?: candidate
                    } else {
                        candidate
                    }
                    if (isUsableDirectory(dir)) {
                        lastRecordingUsedAbsoluteCustomPath = true
                        return dir
                    }
                } else {
                    pendingRelativeRecordingPath = normalized
                    return defaultRecordingDir(context)
                }
            }
        }

        return defaultRecordingDir(context)
    }

    private fun normalizeRelativeRecordingPath(rawPath: String?): String {
        val clean = rawPath?.trim().orEmpty()
        if (clean.isBlank()) return ""
        return clean
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun publicDownloadsTargetPath(relativePath: String? = null): String {
        val rel = normalizeRelativeRecordingPath(relativePath)
        return if (rel.isBlank()) {
            "WaveStudio"
        } else {
            "WaveStudio/$rel"
        }
    }

    private fun recordingMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    private fun copyRecordingToSelectedTree(sourceFile: File): String? {
        try {
            if (!sourceFile.exists() || !sourceFile.isFile) return null
            val treeUriStr = settingsPrefs.getString(KEY_CUSTOM_RECORDING_TREE_URI, null)
            if (treeUriStr.isNullOrBlank()) return null

            val app = getApplication<Application>()
            val treeUri = android.net.Uri.parse(treeUriStr)
            try {
                app.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            val createdUri = try {
                DocumentFile.fromTreeUri(app, treeUri)
                    ?.createFile(recordingMimeType(sourceFile.name), sourceFile.name)?.uri
            } catch (_: Throwable) {
                null
            }
                ?: try {
                    android.provider.DocumentsContract.createDocument(
                        app.contentResolver,
                        treeUri,
                        recordingMimeType(sourceFile.name),
                        sourceFile.name,
                    )
                } catch (_: Throwable) {
                    null
                }
                ?: return null

            var copied = false
            app.contentResolver.openOutputStream(createdUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                    copied = true
                }
            }

            if (copied) {
                val sourceSize = try {
                    sourceFile.length()
                } catch (_: Throwable) {
                    -1L
                }
                val copiedOk = if (sourceSize > 0L) {
                    try {
                        app.contentResolver.openFileDescriptor(createdUri, "r")?.use { fd ->
                            fd.statSize < 0L || fd.statSize == sourceSize
                        } ?: false
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    true
                }

                if (!copiedOk) {
                    try {
                        app.contentResolver.delete(createdUri, null, null)
                    } catch (_: Throwable) {
                    }
                    return null
                }

                val newUriStr = createdUri.toString()
                _allRecordings.update { list -> list.map { if (it.fileURL == sourceFile.absolutePath) it.copy(fileURL = newUriStr) else it } }
                try {
                    persistRecordingsToPrefs()
                } catch (_: Throwable) {
                }
                return newUriStr
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun copyRecordingToPublicDownloads(sourceFile: File, relativePath: String? = null): String? {
        return try {
            if (!sourceFile.exists() || !sourceFile.isFile) return null
            val app = getApplication<Application>()
            val mime = recordingMimeType(sourceFile.name)
            val displayName = sourceFile.name
            val targetRelativePath = publicDownloadsTargetPath(relativePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$targetRelativePath")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = app.contentResolver.insert(collection, values) ?: return null
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return null
                try {
                    app.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                } catch (_: Throwable) {
                }
                val newUriStr = uri.toString()
                _allRecordings.update { list -> list.map { if (it.fileURL == sourceFile.absolutePath) it.copy(fileURL = newUriStr) else it } }
                try {
                    persistRecordingsToPrefs()
                } catch (_: Throwable) {
                }
                newUriStr
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    targetRelativePath
                ).apply { mkdirs() }
                val outFile = File(dir, displayName)
                sourceFile.copyTo(outFile, overwrite = true)
                val newPath = outFile.absolutePath
                _allRecordings.update { list -> list.map { if (it.fileURL == sourceFile.absolutePath) it.copy(fileURL = newPath) else it } }
                try {
                    persistRecordingsToPrefs()
                } catch (_: Throwable) {
                }
                newPath
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun publishRecordingForVisibility(sourceFile: File): String? {
        val treeUriStr = settingsPrefs.getString(KEY_CUSTOM_RECORDING_TREE_URI, null)
        if (!treeUriStr.isNullOrBlank()) return copyRecordingToSelectedTree(sourceFile)
            ?: copyRecordingToPublicDownloads(sourceFile, null)
        if (!pendingRelativeRecordingPath.isNullOrBlank()) return copyRecordingToPublicDownloads(
            sourceFile,
            pendingRelativeRecordingPath
        )
        if (!lastRecordingUsedAbsoluteCustomPath) return copyRecordingToPublicDownloads(sourceFile, null)
        return null
    }

    /** 计算下一个录音显示名称（格式："Record n"） */
    private fun nextRecordingDisplayName(): String {
        val maxNum = _allRecordings.value.maxOfOrNull { clip ->
            val name = clip.customName?.takeIf { it.isNotBlank() } ?: clip.fileName.removeSuffix(".m4a")
            Regex("""Record\D*(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } ?: 0
        return "录音 ${maxNum + 1}"
    }

    fun startRecording(context: Context) {
        if (!_isRunning.value && !_useImportedSignal.value) return
        if (_useTestSignal.value) {
            _engineError.value = "测试模式中无法录音"
            return
        }
        if (_isRecording.value) return

        Log.i("WaveStudio", "Start recording")
        try {
            val dir = resolveRecordingDir(context)
            val fmt = _recordingFormat.value
            val outFile = File(dir, "rec_${System.currentTimeMillis()}.${fmt.extension}")
            currentRecordingPath = outFile.absolutePath
            recordingStartMs = SystemClock.elapsedRealtime()
            recordingActiveFormat = fmt

            val effectiveSr = sampleRate
            startFilteredRecording(outFile, fmt, effectiveSr)
            _isRecording.value = true
        } catch (t: Throwable) {
            _engineError.value = "录音启动失败：${t.message ?: t.javaClass.simpleName}"
            // Avoid blocking UI, release in background
            recordingStopJob?.cancel()
            recordingStopJob = viewModelScope.launch(Dispatchers.Default) { stopRecordingInternalBlocking() }
            _isRecording.value = false
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private fun stopRecordingInternalBlocking() {
        // WAV path: finalize header and close.
        val wav = wavOut
        if (wav != null) {
            try {
                finalizeWavRecording(wav, wavSampleRate, wavDataBytes)
            } catch (_: Throwable) {
            }
            try {
                wav.close()
            } catch (_: Throwable) {
            }
            wavOut = null
            wavDataBytes = 0L
            recordingActiveFormat = null

            currentRecordingPath?.let { path ->
                val f = try {
                    File(path)
                } catch (_: Throwable) {
                    null
                }
                if (f != null && f.exists() && f.isFile) {
                    val displayName = nextRecordingDisplayName()
                    _allRecordings.update { list ->
                        val durSec = ((SystemClock.elapsedRealtime() - recordingStartMs).coerceAtLeast(0L)) / 1000.0
                        val newList = list + RecordedClip(
                            id = f.nameWithoutExtension + "_" + f.lastModified().toString(),
                            date = System.currentTimeMillis(),
                            duration = durSec,
                            fileURL = f.absolutePath,
                            customName = displayName
                        )
                        try {
                            persistRecordingsToPrefs()
                        } catch (_: Throwable) {
                        }
                        newList
                    }
                    publishRecordingForVisibility(f)
                }
            }
            return
        }

        val codec = recordingCodec
        val muxer = recordingMuxer

        recordingCodec = null
        recordingMuxer = null

        if (codec == null || muxer == null) {
            recordingMuxerStarted = false
            recordingTrackIndex = -1
            recordingPtsUs = 0L
            recordingActiveFormat = null
            return
        }

        try {
            // Signal EOS
            val inIndex = try {
                codec.dequeueInputBuffer(10_000)
            } catch (_: Throwable) {
                -1
            }
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, recordingPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Throwable) {
            // ignore
        }

        try {
            // Drain until EOS
            while (drainRecordingEncoderOnce(codec, muxer)) {
                // keep draining
            }
        } catch (_: Throwable) {
        }

        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }

        try {
            if (recordingMuxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                muxer.release()
            } catch (_: Throwable) {
            }
        }

        recordingMuxerStarted = false
        recordingTrackIndex = -1
        recordingPtsUs = 0L
        recordingActiveFormat = null

        currentRecordingPath?.let { path ->
            val f = try {
                File(path)
            } catch (_: Throwable) {
                null
            }
            if (f != null && f.exists() && f.isFile) {
                val displayName = nextRecordingDisplayName()
                _allRecordings.update { list ->
                    val durSec = ((SystemClock.elapsedRealtime() - recordingStartMs).coerceAtLeast(0L)) / 1000.0
                    val newList = list + RecordedClip(
                        id = f.nameWithoutExtension + "_" + f.lastModified().toString(),
                        date = System.currentTimeMillis(),
                        duration = durSec,
                        fileURL = f.absolutePath,
                        customName = displayName
                    )
                    try {
                        persistRecordingsToPrefs()
                    } catch (_: Throwable) {
                    }
                    newList
                }
                publishRecordingForVisibility(f)
            }
        }
    }

    // ===== Filtered recording encoder setup/feed =====
    private fun startFilteredRecording(outFile: File, fmt: RecordingFormat, targetSampleRate: Int) {
        // Reset counters/state for a new file
        recordingPtsUs = 0L
        recordingTrackIndex = -1
        recordingMuxerStarted = false

        if (fmt == RecordingFormat.WAV) {
            startWavRecording(outFile, targetSampleRate)
            return
        }

        // Only M4A(AAC) uses muxer/codec.
        if (fmt != RecordingFormat.M4A_AAC) {
            throw IllegalArgumentException("Unsupported recording format: ${fmt.name}")
        }

        // Encoder sample rate might differ from capture sampleRate.
        val encSr = targetSampleRate.coerceIn(8000, sampleRate)

        fun buildAacFormat(sr: Int): android.media.MediaFormat {
            return android.media.MediaFormat.createAudioFormat(fmt.mimeType, sr, 1).apply {
                setInteger(
                    android.media.MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
        }

        val codec = try {
            android.media.MediaCodec.createEncoderByType(fmt.mimeType)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to create AAC encoder (${fmt.mimeType}): ${t.message}", t)
        }

        try {
            try {
                codec.configure(buildAacFormat(encSr), null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (t: Throwable) {
                // Fallback to capture sample rate
                try {
                    codec.reset()
                } catch (_: Throwable) {
                }
                codec.configure(buildAacFormat(sampleRate), null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
        } catch (t: Throwable) {
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            throw IllegalStateException("Failed to initialize AAC encoder: ${t.message}", t)
        }

        val muxer = try {
            MediaMuxer(outFile.absolutePath, fmt.muxerFormat)
        } catch (t: Throwable) {
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            throw IllegalStateException("Failed to initialize muxer: ${t.message}", t)
        }

        recordingCodec = codec
        recordingMuxer = muxer
    }

    /** Feed filtered PCM16 mono into encoder or WAV file. Called from capture coroutine thread. */
    private fun writeRecordingPcm(pcm16: ShortArray, len: Int) {
        if (!_isRecording.value) return

        // WAV path
        val wav = wavOut
        if (wav != null) {
            val dstRate = min(wavSampleRate, sampleRate)
            val (pcm, newLen) = downsampleNearest(pcm16, len, sampleRate, dstRate)

            val (bytes, byteCount) = pcm16ToLeBytes(pcm, newLen)

            try {
                wav.write(bytes, 0, byteCount)
                wavDataBytes += byteCount.toLong()
            } catch (_: Throwable) {
            }
            return
        }

        val codec = recordingCodec ?: return

        // Resample if target differs
        val dstRate = recordingActiveFormat?.let {
            when (it) {
                RecordingFormat.WAV -> wavSampleRate
                else -> sampleRate
            }
        } ?: sampleRate
        val (pcm, newLen) = downsampleNearest(pcm16, len, sampleRate, dstRate)

        val (bytes, byteCount) = pcm16ToLeBytes(pcm, newLen)

        var offset = 0
        while (offset < byteCount) {
            val inIndex = try {
                codec.dequeueInputBuffer(0)
            } catch (_: Throwable) {
                -1
            }
            if (inIndex < 0) break

            val inBuf = codec.getInputBuffer(inIndex) ?: break
            inBuf.clear()

            val toWrite = min(inBuf.remaining(), byteCount - offset)
            inBuf.put(bytes, offset, toWrite)

            val frames = toWrite / 2 // mono 16-bit
            val ptsUs = recordingPtsUs
            val srForPts = dstRate.takeIf { it > 0 } ?: sampleRate
            recordingPtsUs += (frames * 1_000_000L) / srForPts

            codec.queueInputBuffer(inIndex, 0, toWrite, ptsUs, 0)
            offset += toWrite

            // Drain a bit on the same thread to keep encoder from backing up
            try {
                while (drainRecordingEncoderOnce(codec, recordingMuxer ?: break)) {
                    // keep draining
                }
            } catch (_: Throwable) {
                break
            }
        }
    }

    private fun startWavRecording(outFile: File, targetSampleRate: Int) {
        wavSampleRate = targetSampleRate.coerceIn(8000, sampleRate)
        wavDataBytes = 0L
        val raf = java.io.RandomAccessFile(outFile, "rw")
        raf.setLength(0L)
        writeWavHeader(raf, wavSampleRate, 0L)
        wavOut = raf
    }

    private fun finalizeWavRecording(raf: java.io.RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        raf.seek(0L)
        writeWavHeader(raf, sampleRate, dataBytes)
    }

    private fun writeWavHeader(raf: java.io.RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffChunkSize = 36L + dataBytes

        fun writeString(s: String) {
            raf.write(s.toByteArray(Charsets.US_ASCII))
        }

        fun writeLe16(v: Int) {
            raf.write(v and 0xFF)
            raf.write((v ushr 8) and 0xFF)
        }

        fun writeLe32(v: Long) {
            raf.write((v and 0xFF).toInt())
            raf.write(((v ushr 8) and 0xFF).toInt())
            raf.write(((v ushr 16) and 0xFF).toInt())
            raf.write(((v ushr 24) and 0xFF).toInt())
        }

        writeString("RIFF")
        writeLe32(riffChunkSize)
        writeString("WAVE")
        writeString("fmt ")
        writeLe32(16) // PCM fmt chunk size
        writeLe16(1) // PCM format
        writeLe16(channels)
        writeLe32(sampleRate.toLong())
        writeLe32(byteRate.toLong())
        writeLe16(blockAlign)
        writeLe16(bitsPerSample)
        writeString("data")
        writeLe32(dataBytes)
    }

    // ===== Waveform downsample =====
    private fun downsamplePeak(input: List<Float>, targetPoints: Int): List<Float> {
        if (input.isEmpty()) return emptyList()
        if (input.size <= targetPoints) return input

        val bucketSize = input.size.toFloat() / targetPoints
        val out = ArrayList<Float>(targetPoints)

        for (i in 0 until targetPoints) {
            val start = (i * bucketSize).toInt()
            val end = min(((i + 1) * bucketSize).toInt(), input.size)
            var peak = 0f
            for (j in start until end) {
                val v = input[j]
                if (abs(v) > abs(peak)) peak = v
            }
            out.add(peak)
        }
        return out
    }

    // 在类内（downsamplePeak 旁边）新增一个 FloatArray 版本的 downsample，避免创建 List
    private fun downsamplePeakFloatArray(
        input: FloatArray,
        start: Int,
        endExclusive: Int,
        targetPoints: Int,
    ): FloatArray {
        val n = (endExclusive - start).coerceAtLeast(0)
        if (n <= 0) return floatArrayOf()
        if (n <= targetPoints) {
            val out = FloatArray(n)
            var oi = 0
            for (i in start until endExclusive) out[oi++] = input[i]
            return out
        }

        val bucketSize = n.toFloat() / targetPoints
        val out = FloatArray(targetPoints)
        for (i in 0 until targetPoints) {
            val s = start + (i * bucketSize).toInt()
            val e = min(start + ((i + 1) * bucketSize).toInt(), endExclusive)
            var peak = 0f
            var j = s
            while (j < e) {
                val v = input[j]
                if (abs(v) > abs(peak)) peak = v
                j++
            }
            out[i] = peak
        }
        return out
    }

    private fun resampleLinearFloatArray(
        input: FloatArray,
        start: Int,
        endExclusive: Int,
        targetPoints: Int,
    ): FloatArray {
        val n = (endExclusive - start).coerceAtLeast(0)
        if (n <= 0 || targetPoints <= 0) return floatArrayOf()
        if (n == 1) return FloatArray(targetPoints) { input[start] }
        if (targetPoints == 1) return floatArrayOf(input[start])

        val out = FloatArray(targetPoints)
        val lastSourceIndex = (n - 1).toFloat()
        val lastTargetIndex = (targetPoints - 1).toFloat().coerceAtLeast(1f)
        for (i in 0 until targetPoints) {
            val pos = (i.toFloat() / lastTargetIndex) * lastSourceIndex
            val base = pos.toInt().coerceIn(0, n - 1)
            val next = (base + 1).coerceAtMost(n - 1)
            val frac = pos - base.toFloat()
            val a = input[start + base]
            val b = input[start + next]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    // ===== Filters (exact order using 1st-order + biquad cascades) =====
    private fun applyFiltersBiquad(
        input: List<Float>,
        sampleRate: Int,
        lowPassEnabled: Boolean,
        lowPassCutoffHz: Float,
        highPassEnabled: Boolean,
        highPassCutoffHz: Float
    ): List<Float> {
        if (input.isEmpty()) return emptyList()

        var out: List<Float> = input

        // Global HP: apply if enabled in settings
        if (_globalHighPassEnabled.value) {
            val cut = _globalHighPassCutoff.value.coerceAtLeast(0.1f)
            out = firstOrderHighPass(out, sampleRate, cut)
        }

        val nyquist = (sampleRate / 2f) - 1f

        // RC 等效：频率即截止频率；阶数即级联层数
        // 每一层都是一个一阶 RC 滤波器
        if (lowPassEnabled) {
            val order = _lowPassOrder.value.coerceIn(1, 8)
            repeat(order) { stage ->
                val fc = _lowPassStageCutoffs.value
                    .getOrElse(stage) { lowPassCutoffHz }
                    .coerceIn(5f, nyquist)
                out = firstOrderLowPass(out, sampleRate, fc)
            }
        }

        if (highPassEnabled) {
            val order = _highPassOrder.value.coerceIn(1, 8)
            repeat(order) { stage ->
                val fc = _highPassStageCutoffs.value
                    .getOrElse(stage) { highPassCutoffHz }
                    .coerceIn(5f, nyquist)
                out = firstOrderHighPass(out, sampleRate, fc)
            }
        }

        // EQ：peaking + shelves
        if (_eqEnabled.value) {
            val bands = _eqBands.value
            for (b in bands) {
                if (!b.enabled) continue
                val fc = b.freqHz.coerceIn(5f, nyquist)
                val g = b.gainDb.coerceIn(-40f, 40f)
                val qOrSlope = clampEqQForBand(b.type, g, b.q)
                out = when (b.type) {
                    EqBandType.PEAK -> biquadProcess(out, designPeakingEq(sampleRate, fc, qOrSlope, g))
                    EqBandType.LOW_SHELF -> biquadProcess(out, designLowShelf(sampleRate, fc, qOrSlope, g))
                    EqBandType.HIGH_SHELF -> biquadProcess(out, designHighShelf(sampleRate, fc, qOrSlope, g))
                }
            }
        }

        val gain = _filterGain.value.coerceIn(0.1f, 100f)
        if (gain == 1f) return out
        return out.map { it * gain }
    }

    private fun firstOrderLowPass(input: List<Float>, sampleRate: Int, cutoffHz: Float): List<Float> {
        if (input.isEmpty()) return emptyList()
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = (dt / (rc + dt)).coerceIn(0f, 1f)

        val out = ArrayList<Float>(input.size)
        var y = 0f
        for (x in input) {
            y += alpha * (x - y)
            out.add(y)
        }
        return out
    }

    private fun firstOrderHighPass(input: List<Float>, sampleRate: Int, cutoffHz: Float): List<Float> {
        if (input.isEmpty()) return emptyList()
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = (rc / (rc + dt)).coerceIn(0f, 1f)

        val out = ArrayList<Float>(input.size)
        var y = 0f
        var xPrev = 0f
        for (x in input) {
            y = alpha * (y + x - xPrev)
            out.add(y)
            xPrev = x
        }
        return out
    }

    private fun biquadProcess(input: List<Float>, biquad: Biquad): List<Float> {
        val out = ArrayList<Float>(input.size)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (x0 in input) {
            val y0 = biquad.b0 * x0 + biquad.b1 * x1 + biquad.b2 * x2 - biquad.a1 * y1 - biquad.a2 * y2
            out.add(y0)
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    private data class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private fun designLowPass(sampleRate: Int, cutoffHz: Float, q: Float): Biquad {
        val w0 = (2f * PI.toFloat() * cutoffHz) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)

        val b0 = (1f - cosW0) / 2f
        val b1 = 1f - cosW0
        val b2 = (1f - cosW0) / 2f
        val a0 = 1f + alpha
        val a1 = -2f * cosW0
        val a2 = 1f - alpha

        return Biquad(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }

    private fun designHighPass(sampleRate: Int, cutoffHz: Float, q: Float): Biquad {
        val w0 = (2f * PI.toFloat() * cutoffHz) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)

        val b0 = (1f + cosW0) / 2f
        val b1 = -(1f + cosW0)
        val b2 = (1f + cosW0) / 2f
        val a0 = 1f + alpha
        val a1 = -2f * cosW0
        val a2 = 1f - alpha

        return Biquad(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }

    /**
     * RBJ Audio EQ Cookbook: Peaking EQ
     * gainDb: positive boosts, negative cuts
     */
    private fun designPeakingEq(sampleRate: Int, centerHz: Float, q: Float, gainDb: Float): Biquad {
        val w0 = (2f * PI.toFloat() * centerHz) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10f.pow(gainDb / 40f) // sqrt(10^(dBgain/20))
        val alpha = sinW0 / (2f * q)

        val b0 = 1f + alpha * a
        val b1 = -2f * cosW0
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosW0
        val a2 = 1f - alpha / a

        return Biquad(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }

    /**
     * RBJ Audio EQ Cookbook: Low Shelf
     * slope: use q parameter as shelf slope (S). Typical 0.5..1.0.
     */
    private fun designLowShelf(sampleRate: Int, centerHz: Float, slope: Float, gainDb: Float): Biquad {
        val w0 = (2f * PI.toFloat() * centerHz) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10f.pow(gainDb / 40f)
        val s = safeShelfSlope(slope, gainDb)

        val alphaTerm = max((a + 1f / a) * (1f / s - 1f) + 2f, 0f)
        val alpha = sinW0 / 2f * sqrt(alphaTerm)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
        val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
        val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
        val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
        val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha

        return Biquad(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }

    /**
     * RBJ Audio EQ Cookbook: High Shelf
     * slope: use q parameter as shelf slope (S). Typical 0.5..1.0.
     */
    private fun designHighShelf(sampleRate: Int, centerHz: Float, slope: Float, gainDb: Float): Biquad {
        val w0 = (2f * PI.toFloat() * centerHz) / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10f.pow(gainDb / 40f)
        val s = safeShelfSlope(slope, gainDb)

        val alphaTerm = max((a + 1f / a) * (1f / s - 1f) + 2f, 0f)
        val alpha = sinW0 / 2f * sqrt(alphaTerm)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
        val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
        val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
        val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
        val a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha

        return Biquad(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }

    fun toggleMonitoring() {
        if (!_isRunning.value && !_useImportedSignal.value) return
        if (_useTestSignal.value) return
        _isMonitoring.value = !_isMonitoring.value
        Log.i("WaveStudio", if (_isMonitoring.value) "Enable monitoring" else "Disable monitoring")
    }

    fun renameRecording(clipId: String, newBaseName: String) {
        val trimmed = newBaseName.trim()
        if (trimmed.isEmpty()) return

        _allRecordings.update { list ->
            list.map { clip ->
                if (clip.id != clipId) clip else clip.copy(customName = trimmed)
            }
        }
        try {
            persistRecordingsToPrefs()
        } catch (_: Throwable) {
        }
    }


    fun deleteRecording(clipId: String) {
        Log.i("WaveStudio", "Delete recording: $clipId")
        val clip = _allRecordings.value.firstOrNull { it.id == clipId }
        if (clip != null) {
            if (_playingId.value == clipId) stopPlayback()
            // 不再删除文件，仅标记删除时间
        }
        _allRecordings.update { list ->
            list.map { if (it.id == clipId) it.copy(deletedAt = System.currentTimeMillis()) else it }
        }
        try {
            persistRecordingsToPrefs()
        } catch (_: Throwable) {
        }
    }

    /** 恢复最近删除的录音 */
    fun restoreRecording(clipId: String) {
        _allRecordings.update { list ->
            list.map { if (it.id == clipId) it.copy(deletedAt = null) else it }
        }
        try {
            persistRecordingsToPrefs()
        } catch (_: Throwable) {
        }
    }

    /** 永久删除录音文件并从列表中移除 */
    fun permanentlyDeleteRecording(clipId: String) {
        Log.i("WaveStudio", "Permanently delete recording: $clipId")
        val clip = _allRecordings.value.firstOrNull { it.id == clipId }
        if (clip != null) {
            if (_playingId.value == clipId) stopPlayback()
            try {
                if (clip.fileURL.startsWith("content://")) {
                    try {
                        val app = getApplication<Application>()
                        app.contentResolver.delete(android.net.Uri.parse(clip.fileURL), null, null)
                    } catch (_: Throwable) {
                    }
                } else {
                    val f = File(clip.fileURL)
                    if (f.exists()) f.delete()
                }
            } catch (_: Throwable) {
            }
        }
        _allRecordings.update { list -> list.filterNot { it.id == clipId } }
        try {
            persistRecordingsToPrefs()
        } catch (_: Throwable) {
        }
    }

    // NOTE: The public controls/playback stubs are defined at the top of this file.
    // (Removed duplicate definitions of stopRecording/stopPlayback/playRecording/seekPlaybackTo/drainRecordingEncoderOnce that
    // accidentally existed here in some merges.)

    /** 导出当前参数为预设（用于导出/分享） */
    private fun normalizeStageCutoffs(
        values: List<Float>,
        fallback: Float,
        minimum: Float,
        maximum: Float,
    ): List<Float> {
        val source = values.ifEmpty { listOf(fallback) }
        return List(8) { index ->
            source.getOrElse(index) { source.last() }.coerceIn(minimum, maximum)
        }
    }

    fun exportPreset(name: String? = null): FilterPreset {
        val bands = _eqBands.value.map {
            FilterPreset.EqBandPreset(
                id = it.id,
                enabled = it.enabled,
                freqHz = it.freqHz,
                gainDb = it.gainDb,
                q = it.q,
                type = it.type.name,
            )
        }

        return FilterPreset(
            schemaVersion = 2,
            name = name,
            lowPassEnabled = _lowPassEnabled.value,
            lowPassCutoffHz = _lowPassCutoff.value,
            lowPassOrder = _lowPassOrder.value,
            lowPassStageCutoffsHz = _lowPassStageCutoffs.value.take(_lowPassOrder.value),
            highPassEnabled = _highPassEnabled.value,
            highPassCutoffHz = _highPassCutoff.value,
            highPassOrder = _highPassOrder.value,
            highPassStageCutoffsHz = _highPassStageCutoffs.value.take(_highPassOrder.value),
            filterGain = _filterGain.value,
            windowMs = _windowMs.value,
            ampScale = _ampScale.value,
            eqEnabled = _eqEnabled.value,
            eqBands = bands,
        )
    }

    /** 应用导入的预设到当前参数（忽略未知字段；缺省会用默认值回填） */
    fun applyPreset(preset: FilterPreset) {
        // clamps to match UI ranges / safety
        _lowPassEnabled.value = preset.lowPassEnabled
        _lowPassCutoff.value = preset.lowPassCutoffHz.coerceIn(600f, 30001f)
        _lowPassOrder.value = preset.lowPassOrder.coerceIn(1, 8)
        _lowPassStageCutoffs.value = normalizeStageCutoffs(
            preset.lowPassStageCutoffsHz,
            _lowPassCutoff.value,
            600f,
            30001f,
        )
        _lowPassCutoff.value = _lowPassStageCutoffs.value.first()

        _highPassEnabled.value = preset.highPassEnabled
        _highPassCutoff.value = preset.highPassCutoffHz.coerceIn(20f, 10000f)
        _highPassOrder.value = preset.highPassOrder.coerceIn(1, 8)
        _highPassStageCutoffs.value = normalizeStageCutoffs(
            preset.highPassStageCutoffsHz,
            _highPassCutoff.value,
            20f,
            10000f,
        )
        _highPassCutoff.value = _highPassStageCutoffs.value.first()

        _filterGain.value = preset.filterGain.coerceIn(0.1f, 100f)

        _lastWindowWriteSource.value = "applyPreset"
        _windowMs.value = preset.windowMs.coerceIn(2f, 500f)

        _lastAmpWriteSource.value = "applyPreset"
        _ampScale.value = preset.ampScale.coerceIn(0.25f, 5f)

        _eqEnabled.value = preset.eqEnabled

        // merge bands by id (import type from preset, keep label from existing list)
        val importedById = preset.eqBands.associateBy { it.id }
        _eqBands.update { list ->
            list.map { b ->
                val p = importedById[b.id] ?: return@map b
                val importedType = try {
                    EqBandType.valueOf(p.type)
                } catch (e: IllegalArgumentException) {
                    b.type  // fallback to existing type if invalid
                }
                b.copy(
                    enabled = p.enabled,
                    freqHz = p.freqHz.coerceIn(20f, 20000f),
                    gainDb = p.gainDb.coerceIn(-40f, 40f),
                    q = p.q.coerceIn(0.2f, 6f),
                    type = importedType,
                )
            }
        }
    }

    /** 恢复一套“温和默认值”（不会影响录音文件） */
    fun resetFilterPresetToDefault() {
        _lowPassEnabled.value = false
        _lowPassCutoff.value = 20000f
        _lowPassStageCutoffs.value = List(8) { 20000f }
        _lowPassOrder.value = 1

        _highPassEnabled.value = false
        _highPassCutoff.value = 50f
        _highPassStageCutoffs.value = List(8) { 50f }
        _highPassOrder.value = 1

        _filterGain.value = 1f

        _lastWindowWriteSource.value = "resetDefault"
        _windowMs.value = 25f

        _lastAmpWriteSource.value = "resetDefault"
        _ampScale.value = 1f

        _eqEnabled.value = false
        resetEq()
    }

    init {
        // no-op
    }
}

// ===== Lightweight realtime biquad (no boxing/alloc) for monitor+record path =====
private data class RtBiquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

// RBJ shelf filter's S has a gain-dependent stability limit. Clamp to avoid negative sqrt term.
private fun safeShelfSlope(rawSlope: Float, gainDb: Float): Float {
    val s = rawSlope.coerceIn(0.1f, 18f)
    val a = 10f.pow(gainDb / 40f)
    val t = a + 1f / a // >= 2
    val denom = t - 2f
    if (denom <= 1e-6f) return s // near 0 dB, practical limit tends to infinity
    val sMax = (t / denom).coerceAtLeast(0.1f)
    return s.coerceAtMost(sMax)
}

private fun rtDesignLowPass(sampleRate: Int, cutoffHz: Float, q: Float = 1f / kotlin.math.sqrt(2f)): RtBiquad {
    val w0 = (2f * kotlin.math.PI.toFloat() * cutoffHz) / sampleRate
    val cosW0 = kotlin.math.cos(w0)
    val sinW0 = kotlin.math.sin(w0)
    val alpha = sinW0 / (2f * q)

    val b0 = (1f - cosW0) / 2f
    val b1 = 1f - cosW0
    val b2 = (1f - cosW0) / 2f
    val a0 = 1f + alpha
    val a1 = -2f * cosW0
    val a2 = 1f - alpha

    return RtBiquad(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0
    )
}

private fun rtDesignHighPass(sampleRate: Int, cutoffHz: Float, q: Float = 1f / kotlin.math.sqrt(2f)): RtBiquad {
    val w0 = (2f * kotlin.math.PI.toFloat() * cutoffHz) / sampleRate
    val cosW0 = kotlin.math.cos(w0)
    val sinW0 = kotlin.math.sin(w0)
    val alpha = sinW0 / (2f * q)

    val b0 = (1f + cosW0) / 2f
    val b1 = -(1f + cosW0)
    val b2 = (1f + cosW0) / 2f
    val a0 = 1f + alpha
    val a1 = -2f * cosW0
    val a2 = 1f - alpha

    return RtBiquad(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0
    )
}

private fun rtDesignPeakingEq(sampleRate: Int, centerHz: Float, q: Float, gainDb: Float): RtBiquad {
    val w0 = (2f * kotlin.math.PI.toFloat() * centerHz) / sampleRate
    val cosW0 = kotlin.math.cos(w0)
    val sinW0 = kotlin.math.sin(w0)
    val a = 10f.pow(gainDb / 40f)
    val alpha = sinW0 / (2f * q)

    val b0 = 1f + alpha * a
    val b1 = -2f * cosW0
    val b2 = 1f - alpha * a
    val a0 = 1f + alpha / a
    val a1 = -2f * cosW0
    val a2 = 1f - alpha / a

    return RtBiquad(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0
    )
}

private fun rtDesignLowShelf(sampleRate: Int, centerHz: Float, slope: Float, gainDb: Float): RtBiquad {
    val w0 = (2f * kotlin.math.PI.toFloat() * centerHz) / sampleRate
    val cosW0 = kotlin.math.cos(w0)
    val sinW0 = kotlin.math.sin(w0)
    val a = 10f.pow(gainDb / 40f)
    val s = safeShelfSlope(slope, gainDb)

    val alphaTerm = max((a + 1f / a) * (1f / s - 1f) + 2f, 0f)
    val alpha = sinW0 / 2f * kotlin.math.sqrt(alphaTerm)
    val twoSqrtAAlpha = 2f * kotlin.math.sqrt(a) * alpha

    val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
    val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
    val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
    val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
    val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
    val a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha

    return RtBiquad(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0
    )
}

private fun rtDesignHighShelf(sampleRate: Int, centerHz: Float, slope: Float, gainDb: Float): RtBiquad {
    val w0 = (2f * kotlin.math.PI.toFloat() * centerHz) / sampleRate
    val cosW0 = kotlin.math.cos(w0)
    val sinW0 = kotlin.math.sin(w0)
    val a = 10f.pow(gainDb / 40f)
    val s = safeShelfSlope(slope, gainDb)

    val alphaTerm = max((a + 1f / a) * (1f / s - 1f) + 2f, 0f)
    val alpha = sinW0 / 2f * kotlin.math.sqrt(alphaTerm)
    val twoSqrtAAlpha = 2f * kotlin.math.sqrt(a) * alpha

    val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
    val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
    val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
    val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
    val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
    val a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha

    return RtBiquad(
        b0 = b0 / a0,
        b1 = b1 / a0,
        b2 = b2 / a0,
        a1 = a1 / a0,
        a2 = a2 / a0
    )
}

private class RtBiquadCascade(private var sampleRate: Int) {
    private var lpCoefs: List<RtBiquad> = emptyList()
    private var hpCoefs: List<RtBiquad> = emptyList()

    // EQ
    private var eqCoefs: List<RtBiquad> = emptyList()
    private var filterGain: Float = 1f

    // Global unconditional 1 Hz high-pass (one 1st-order RC stage).
    // This ensures a consistent baseline HP filtering regardless of user-enabled filters.
    private var globalHpCoef: RtBiquad = rtDesignRCHighPass(sampleRate, 1f)
    private var globalHpZ1: Float = 0f
    private var globalHpZ2: Float = 0f

    // Direct Form II Transposed states
    // Need states per stage.
    // lpZ1[stage], lpZ2[stage]
    private var lpZ1 = FloatArray(0)
    private var lpZ2 = FloatArray(0)
    private var hpZ1 = FloatArray(0)
    private var hpZ2 = FloatArray(0)

    // EQ states: one pair of z1/z2 per band. Max supported is arbitrary but we use arrays.
    private var eqZ1 = FloatArray(0)
    private var eqZ2 = FloatArray(0)

    private var lastLpEnabled = false
    private var lastHpEnabled = false
    private var lastLpCutoffs: List<Float> = emptyList()
    private var lastHpCutoffs: List<Float> = emptyList()
    private var lastLpOrder = -1
    private var lastHpOrder = -1

    private var lastEqEnabled = false
    private var lastFilterGain = -1f

    // Store last bands list to avoid unnecessary recalc (assuming immutable list replacement)
    private var lastEqBands: List<AudioEngineViewModel.EqBand>? = null
    private var lastGlobalHpEnabled: Boolean = true
    private var lastGlobalHpCutoff: Float = 1f

    fun update(
        sampleRate: Int,
        lowPassEnabled: Boolean,
        lowPassCutoffHz: Float,
        lowPassOrder: Int,
        lowPassStageCutoffsHz: List<Float>,
        highPassEnabled: Boolean,
        highPassCutoffHz: Float,
        highPassOrder: Int,
        highPassStageCutoffsHz: List<Float>,
        filterGain: Float,
        eqEnabled: Boolean,
        eqBands: List<AudioEngineViewModel.EqBand>,
        globalHighPassEnabled: Boolean,
        globalHighPassCutoffHz: Float,
    ) {
        var eqChanged = false
        if (this.sampleRate != sampleRate) {
            this.sampleRate = sampleRate
            resetState()
            lastLpCutoffs = emptyList()
            lastHpCutoffs = emptyList()
            lastFilterGain = -1f
            lastEqBands = null
            eqChanged = true
            // Recompute global HP coef for new sample rate using last known cutoff
            globalHpCoef = rtDesignRCHighPass(this.sampleRate, lastGlobalHpCutoff)
        }

        // If global HP params changed, update coefficient
        if (globalHighPassEnabled != lastGlobalHpEnabled || globalHighPassCutoffHz != lastGlobalHpCutoff) {
            lastGlobalHpEnabled = globalHighPassEnabled
            lastGlobalHpCutoff = globalHighPassCutoffHz.coerceAtLeast(0.1f)
            globalHpCoef = rtDesignRCHighPass(this.sampleRate, lastGlobalHpCutoff)
        }

        val lpOrder = lowPassOrder.coerceIn(1, 8)
        val effectiveLpCutoffs = List(lpOrder) { stage ->
            lowPassStageCutoffsHz.getOrElse(stage) { lowPassCutoffHz }
                .coerceIn(5f, sampleRate / 2f - 1f)
        }
        if (lowPassEnabled != lastLpEnabled ||
            (lowPassEnabled && (effectiveLpCutoffs != lastLpCutoffs || lowPassOrder != lastLpOrder))
        ) {
            if (lowPassEnabled) {
                lpCoefs = effectiveLpCutoffs.map { cutoff ->
                    rtDesignRCLowPass(sampleRate, cutoff)
                }
                if (lpZ1.size < lpOrder) {
                    lpZ1 = FloatArray(lpOrder)
                    lpZ2 = FloatArray(lpOrder)
                }
            } else {
                lpCoefs = emptyList()
            }
            lastLpEnabled = lowPassEnabled
            lastLpCutoffs = effectiveLpCutoffs
            lastLpOrder = lowPassOrder
        }

        val hpOrder = highPassOrder.coerceIn(1, 8)
        val effectiveHpCutoffs = List(hpOrder) { stage ->
            highPassStageCutoffsHz.getOrElse(stage) { highPassCutoffHz }
                .coerceIn(5f, sampleRate / 2f - 1f)
        }
        if (highPassEnabled != lastHpEnabled ||
            (highPassEnabled && (effectiveHpCutoffs != lastHpCutoffs || highPassOrder != lastHpOrder))
        ) {
            if (highPassEnabled) {
                hpCoefs = effectiveHpCutoffs.map { cutoff ->
                    rtDesignRCHighPass(sampleRate, cutoff)
                }
                if (hpZ1.size < hpOrder) {
                    hpZ1 = FloatArray(hpOrder)
                    hpZ2 = FloatArray(hpOrder)
                }
            } else {
                hpCoefs = emptyList()
            }
            lastHpEnabled = highPassEnabled
            lastHpCutoffs = effectiveHpCutoffs
            lastHpOrder = highPassOrder
        }

        this.filterGain = filterGain

        // Recompute EQ coefficients if needed
        if (eqChanged || eqEnabled != lastEqEnabled || eqBands != lastEqBands) {
            lastEqEnabled = eqEnabled
            lastEqBands = eqBands

            if (eqEnabled && eqBands.isNotEmpty()) {
                val active = eqBands.filter { it.enabled }
                eqCoefs = active.map { b ->
                    val fc = b.freqHz.coerceIn(5f, sampleRate / 2f - 1f)
                    val g = b.gainDb
                    val qOrSlope = AudioEngineViewModel.clampEqQForBand(b.type, g, b.q)
                    when (b.type) {
                        AudioEngineViewModel.EqBandType.PEAK -> rtDesignPeakingEq(sampleRate, fc, qOrSlope, g)
                        AudioEngineViewModel.EqBandType.LOW_SHELF -> rtDesignLowShelf(sampleRate, fc, qOrSlope, g)
                        AudioEngineViewModel.EqBandType.HIGH_SHELF -> rtDesignHighShelf(sampleRate, fc, qOrSlope, g)
                    }
                }
                // Resize state arrays if needed (preserve old states where possible?)
                // Simpler to just resize. If size grows, new slots are 0.
                if (eqZ1.size < eqCoefs.size) {
                    eqZ1 = FloatArray(eqCoefs.size)
                    eqZ2 = FloatArray(eqCoefs.size)
                }
                // If size shrinks, we just use the first N slots. old states in e.g. slot 0 might not match slot 0's new band
                // but transient artifact is acceptable for realtime slider dragging.
            } else {
                eqCoefs = emptyList()
            }
        }
    }

    fun resetState() {
        lpZ1.fill(0f); lpZ2.fill(0f)
        hpZ1.fill(0f); hpZ2.fill(0f)
        eqZ1.fill(0f)
        eqZ2.fill(0f)
        globalHpZ1 = 0f
        globalHpZ2 = 0f
    }

    fun process(input: FloatArray, output: FloatArray, n: Int) {
        if (n <= 0) return

        if (input !== output) {
            System.arraycopy(input, 0, output, 0, n)
        }

        // Apply global HP first if enabled
        if (lastGlobalHpEnabled) {
            val c = globalHpCoef
            var z1 = globalHpZ1
            var z2 = globalHpZ2
            for (i in 0 until n) {
                val x = output[i]
                val yRaw = c.b0 * x + z1
                val y = if (yRaw.isFinite()) yRaw else 0f
                z1 = c.b1 * x - c.a1 * y + z2
                z2 = c.b2 * x - c.a2 * y
                if (!z1.isFinite() || !z2.isFinite()) {
                    z1 = 0f
                    z2 = 0f
                }
                output[i] = y
            }
            globalHpZ1 = z1
            globalHpZ2 = z2
        }

        // High Pass
        val hp = hpCoefs
        for (stage in hp.indices) {
            val c = hp[stage]
            var z1 = hpZ1[stage]
            var z2 = hpZ2[stage]
            for (i in 0 until n) {
                val x = output[i]
                val yRaw = c.b0 * x + z1
                val y = if (yRaw.isFinite()) yRaw else 0f
                z1 = c.b1 * x - c.a1 * y + z2
                z2 = c.b2 * x - c.a2 * y
                if (!z1.isFinite() || !z2.isFinite()) {
                    z1 = 0f
                    z2 = 0f
                }
                output[i] = y
            }
            hpZ1[stage] = z1
            hpZ2[stage] = z2
        }

        // Low Pass
        val lp = lpCoefs
        for (stage in lp.indices) {
            val c = lp[stage]
            var z1 = lpZ1[stage]
            var z2 = lpZ2[stage]
            for (i in 0 until n) {
                val x = output[i]
                val yRaw = c.b0 * x + z1
                val y = if (yRaw.isFinite()) yRaw else 0f
                z1 = c.b1 * x - c.a1 * y + z2
                z2 = c.b2 * x - c.a2 * y
                if (!z1.isFinite() || !z2.isFinite()) {
                    z1 = 0f
                    z2 = 0f
                }
                output[i] = y
            }
            lpZ1[stage] = z1
            lpZ2[stage] = z2
        }

        // EQ Bands
        val bands = eqCoefs
        val count = bands.size
        for (bIdx in 0 until count) {
            val c = bands[bIdx]
            var z1 = eqZ1[bIdx]
            var z2 = eqZ2[bIdx]
            for (i in 0 until n) {
                val x = output[i]
                val yRaw = c.b0 * x + z1
                val y = if (yRaw.isFinite()) yRaw else 0f
                z1 = c.b1 * x - c.a1 * y + z2
                z2 = c.b2 * x - c.a2 * y
                if (!z1.isFinite() || !z2.isFinite()) {
                    z1 = 0f
                    z2 = 0f
                }
                output[i] = y
            }
            eqZ1[bIdx] = z1
            eqZ2[bIdx] = z2
        }

        // Gain
        if (filterGain != 1f) {
            val g = filterGain
            for (i in 0 until n) {
                output[i] *= g
            }
        }
    }
}


private fun rtDesignRCLowPass(sampleRate: Int, cutoffHz: Float): RtBiquad {
    // 1st order RC Backward Euler
    // y[n] = alpha * x[n] + (1 - alpha) * y[n - 1]
    // alpha = dt / (RC + dt)
    val dt = 1f / sampleRate
    val rc = 1f / (2f * PI.toFloat() * cutoffHz)
    val alpha = (dt / (rc + dt)).coerceIn(0f, 1f)

    // Biquad map:
    // y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
    // Here: y[n] = alpha*x[n] + (1-alpha)*y[n-1]
    // => b0 = alpha, b1 = 0, b2 = 0
    // => -a1 = (1-alpha) => a1 = -(1-alpha) = alpha - 1
    // => a2 = 0
    return RtBiquad(
        b0 = alpha,
        b1 = 0f,
        b2 = 0f,
        a1 = alpha - 1f,
        a2 = 0f
    )
}

private fun rtDesignRCHighPass(sampleRate: Int, cutoffHz: Float): RtBiquad {
    // 1st order High Pass Backward Euler matches:
    // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
    // alpha = RC / (RC + dt)
    val dt = 1f / sampleRate
    val rc = 1f / (2f * PI.toFloat() * cutoffHz)
    val alpha = (rc / (rc + dt)).coerceIn(0f, 1f)

    // y[n] = alpha*x[n] - alpha*x[n-1] + alpha*y[n-1]
    // => b0 = alpha, b1 = -alpha, b2 = 0
    // => -a1 = alpha => a1 = -alpha
    // => a2 = 0
    return RtBiquad(
        b0 = alpha,
        b1 = -alpha,
        b2 = 0f,
        a1 = -alpha,
        a2 = 0f
    )
}
