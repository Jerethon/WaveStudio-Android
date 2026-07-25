package org.mhrri.wavestudio

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.abs

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OscopeApp(
    modifier: Modifier = Modifier,
    audioViewModel: AudioEngineViewModel = viewModel()
) {
    val context = LocalContext.current

    // 系统 Toast：强制居中 + 文本居中（你说的“气泡”）
    fun showCenterToast(msg: String) {
        try {
            val t = Toast.makeText(context, msg, Toast.LENGTH_SHORT)
            try {
                val tv = t.view?.findViewById<android.widget.TextView>(android.R.id.message)
                tv?.gravity = Gravity.CENTER
                tv?.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            } catch (_: Throwable) {
            }
            t.setGravity(Gravity.CENTER, 0, 0)
            t.show()
        } catch (_: Throwable) {
        }
    }

    val activity = MainActivityHolder.activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // 真·沉浸式：横屏时隐藏系统栏；竖屏恢复
    LaunchedEffect(isLandscape, activity) {
        val a = activity ?: return@LaunchedEffect
        try {
            val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())

                a.window.statusBarColor = android.graphics.Color.BLACK
                a.window.navigationBarColor = android.graphics.Color.BLACK

                // Ensure cutout area is usable in immersive landscape (API 28+)
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        a.window.attributes = a.window.attributes.apply {
                            layoutInDisplayCutoutMode =
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    } catch (_: Throwable) {
                    }
                }
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Throwable) {
        }
    }

    // ===== 仅给沉浸模式用的最小状态集合（避免大量无关 state 更新导致全屏重组/掉帧） =====
    val immersiveFilteredWaveform = audioViewModel.immersiveFilteredWaveform
    val immersiveWaveformSpanMs by audioViewModel.publishedWaveformSpanMs.collectAsStateWithLifecycle()
    val immersiveAmpScale by audioViewModel.ampScale.collectAsStateWithLifecycle()

    val resources = context.resources
    val startupNoteText = stringResource(R.string.startup_note_text)
    val shareTitleGeneric = stringResource(R.string.share_title_generic)
    val shareTitleRecording = stringResource(R.string.share_title_recording)
    // Pre-read some strings that may be used inside try/catch blocks to avoid
    // calling @Composable APIs from inside try/catch (not allowed).
    val settingsChooseDirSuccessMessage = stringResource(R.string.settings_choose_dir_success_message)
    val settingsInvalidPathMessage = stringResource(R.string.settings_invalid_path_message)
    val settingsSavePathMessage = stringResource(R.string.settings_save_path)
    val settingsChooseDirectoryLabel = stringResource(R.string.settings_choose_directory)
    val commonResetMessage = stringResource(R.string.common_reset)
    val commonCancelMessage = stringResource(R.string.common_cancel)
    val uiScope = rememberCoroutineScope()
    val startupPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val triggerPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    // 语言切换后显示成功 Toast（通过 SharedPreferences 标记传递，跨越 recreate）
    LaunchedEffect(Unit) {
        val pendingToast = startupPrefs.getString("pending_language_toast", null)
        if (pendingToast != null) {
            startupPrefs.edit { remove("pending_language_toast") }
            val langName = if (pendingToast == LANG_ZH)
                resources.getString(R.string.language_option_zh)
            else
                resources.getString(R.string.language_option_en)
            showCenterToast(
                resources.getString(R.string.language_switch_success, langName)
            )
        }
    }

    val hideStartupNoteInitially = remember(startupPrefs) {
        startupPrefs.getBoolean(KEY_HIDE_STARTUP_NOTE, false)
    }
    val savedLanguageInitial = remember(startupPrefs) {
        readSavedAppLanguage(context)
    }
    var selectedLanguage by rememberSaveable { mutableStateOf(savedLanguageInitial) }

    // 启动提示弹窗
    var showStartupNoteDialog by rememberSaveable {
        mutableStateOf(!hideStartupNoteInitially)
    }
    var doNotShowStartupNoteAgain by rememberSaveable {
        mutableStateOf(hideStartupNoteInitially)
    }
    // 关于弹窗
    var showAboutDialog by remember { mutableStateOf(false) }
    // 退出确认
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    // 语言切换确认弹窗
    var showLanguageSwitchDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf("") }

    fun switchAppLanguage(lang: String) {
        if (selectedLanguage == lang) return
        Log.i("WaveStudio", "Language switch: $lang")
        selectedLanguage = lang
        startupPrefs.edit {
            putString(KEY_APP_LANGUAGE, lang)
            // 标记待显示的 Toast，recreate 后由 LaunchedEffect 读取并显示
            putString("pending_language_toast", lang)
        }
        activity?.recreate()
    }

    fun requestLanguageSwitch(lang: String) {
        if (selectedLanguage == lang) return
        pendingLanguage = lang
        showLanguageSwitchDialog = true
    }

    fun openStartupNoteDialog() {
        doNotShowStartupNoteAgain = startupPrefs.getBoolean(KEY_HIDE_STARTUP_NOTE, false)
        showStartupNoteDialog = true
    }

    // 权限管理：引擎只需要麦克风权限；存储权限用于“录音文件导出/旧系统”
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    // ===== 播放进度（录音列表里显示） =====
    val playbackPositionMs by audioViewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val playbackDurationMs by audioViewModel.playbackDurationMs.collectAsStateWithLifecycle()

    // ===== 录音列表：重命名/删除对话框状态 =====
    var renameTarget by remember { mutableStateOf<RecordedClip?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RecordedClip?>(null) }

    fun setLandscape(landscape: Boolean) {
        try {
            activity?.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } catch (_: Throwable) {
        }
    }

    // ===== 预设：导入/导出/分享（SAF + FileProvider） =====
    // 预设提示：改为系统 Toast（不再用 SnackbarHost/presetTip）

    // 分享当前预设：先弹出重命名，再写入缓存文件并分享
    var presetShareDialog by remember { mutableStateOf(false) }
    var presetShareName by remember { mutableStateOf("oscope_preset") }

    // 预设“默认”：误触保护二次确认
    var presetResetConfirmDialog by remember { mutableStateOf(false) }
    // Compact custom storage dialog (opened from settings dropdown)
    var showCustomStorageDialog by remember { mutableStateOf(false) }

    fun exportCurrentPresetToCache(nameNoExt: String): File? {
        return try {
            val safeBase = nameNoExt.trim().ifEmpty { "oscope_preset" }
                .replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5-]+"), "_")
                .take(64)
                .ifEmpty { "oscope_preset" }

            val preset = audioViewModel.exportPreset()
            val json = preset.toJsonString(pretty = true)

            val dir = File(context.externalCacheDir, "presets").apply { mkdirs() }
            val file = File(dir, "$safeBase.json")
            file.writeText(json, Charsets.UTF_8)
            file
        } catch (_: Throwable) {
            null
        }
    }

    fun shareAnyFile(file: File, mime: String) {
        try {
            if (!file.exists()) return
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareTitleGeneric))
        } catch (_: Throwable) {
        }
    }

    // Import preset JSON
    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Log.i("WaveStudio", "Import preset file: $uri")
        try {
            context.contentResolver.openInputStream(uri).use { inS: InputStream? ->
                val text = inS?.bufferedReader(Charsets.UTF_8)?.readText()
                if (text.isNullOrBlank()) {
                    showCenterToast(resources.getString(R.string.preset_import_failed_empty))
                } else {
                    val preset = FilterPreset.fromJsonString(text)
                    audioViewModel.applyPreset(preset)
                    val presetName = preset.name?.takeIf { it.isNotBlank() }
                    Log.i("WaveStudio", "Import preset file success: ${presetName ?: "unnamed"}")
                    showCenterToast(
                        if (presetName != null) resources.getString(R.string.preset_import_success_named, presetName)
                        else resources.getString(R.string.preset_import_success)
                    )
                }
            }
        } catch (t: Throwable) {
            showCenterToast(resources.getString(R.string.preset_import_failed_with_message, t.message ?: ""))
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        audioViewModel.importAudioAsInput(context, uri)
    }

    // Export preset JSON via SAF
    val exportPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Log.i("WaveStudio", "Export preset file: $uri")
        try {
            val preset = audioViewModel.exportPreset()
            val json = preset.toJsonString(pretty = true)
            context.contentResolver.openOutputStream(uri).use { outS: OutputStream? ->
                outS?.write(json.toByteArray(Charsets.UTF_8))
                outS?.flush()
            }
            Log.i("WaveStudio", "Export preset file success")
            showCenterToast(resources.getString(R.string.preset_export_success))

            // 同时在 externalCacheDir 保存一份，方便一键分享
            try {
                val dir = File(context.externalCacheDir, "presets").apply { mkdirs() }
                val file = File(dir, "oscope_preset_${System.currentTimeMillis()}.json")
                file.writeText(json, Charsets.UTF_8)
            } catch (_: Throwable) {
                // ignore cache write
            }
        } catch (t: Throwable) {
            showCenterToast(resources.getString(R.string.preset_export_failed_with_message, t.message ?: ""))
        }
    }

    // SAF directory picker for compact settings dialog
    fun validateWritableTreeUri(treeUri: Uri): Boolean {
        val appContext = context.applicationContext
        return try {
            val testName = ".oscope_write_test_${System.currentTimeMillis()}.tmp"
            val testMime = "application/octet-stream"
            val testUri = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, treeUri)
                    ?.createFile(testMime, testName)
                    ?.uri
            } catch (_: Throwable) {
                null
            } ?: try {
                android.provider.DocumentsContract.createDocument(
                    appContext.contentResolver,
                    treeUri,
                    testMime,
                    testName,
                )
            } catch (_: Throwable) {
                null
            } ?: return false

            var wrote = false
            appContext.contentResolver.openOutputStream(testUri)?.use { out ->
                out.write(0)
                out.flush()
                wrote = true
            }
            if (!wrote) {
                try { appContext.contentResolver.delete(testUri, null, null) } catch (_: Throwable) {}
                return false
            }

            try { appContext.contentResolver.delete(testUri, null, null) } catch (_: Throwable) {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    val chooseDirLauncherForSettings = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val writable = withContext(Dispatchers.IO) { validateWritableTreeUri(uri) }
                if (!writable) {
                    showCenterToast(settingsInvalidPathMessage)
                    return@launch
                }

                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
                startupPrefs.edit { putString("custom_recording_tree_uri", uri.toString()) }
                // use pre-read string
                showCenterToast(settingsChooseDirSuccessMessage)
            } catch (_: Throwable) {
                showCenterToast(settingsInvalidPathMessage)
            }
        }
    }

    fun formatHz(hz: Float): String {
        val v = hz.coerceAtLeast(0f)
        return if (v < 10f) String.format(Locale.US, "%.1f", v) else v.toInt().toString()
    }

    fun cutoffStepLowPass(hz: Float): Float {
        val v = hz.coerceAtLeast(0f)
        return when {
            v < 5000f -> 100f
            v < 10000f -> 200f
            else -> 500f
        }
    }

    fun cutoffStepHighPass(hz: Float): Float {
        val v = hz.coerceAtLeast(0f)
        return when {
            v < 100f -> 5f
            v < 500f -> 10f
            v < 1000f -> 20f
            v < 2000f -> 50f
            else -> 100f
        }
    }

    fun snapLowPassHz(hz: Float): Float {
        val v = hz.coerceIn(800f, 30001f)
        val step = cutoffStepLowPass(v)
        return (round(v / step) * step).coerceIn(800f, 30001f)
    }

    fun snapHighPassHz(hz: Float): Float {
        val v = hz.coerceIn(20f, 10000f)
        val step = cutoffStepHighPass(v)
        return (round(v / step) * step).coerceIn(20f, 10000f)
    }

    fun formatLowPassHz(hz: Float): String {
        return snapLowPassHz(hz).toInt().toString()
    }

    fun formatHighPassHz(hz: Float): String {
        val v = snapHighPassHz(hz)
        return if (v < 100f) String.format(Locale.US, "%.0f", v) else v.toInt().toString()
    }

    // ===== 0dB 参考线显示开关（竖屏默认开启） =====
    var showRefWaveforms by rememberSaveable { mutableStateOf(true) }
    // ===== 调试信息显示开关 =====
    var showDebugInfo by rememberSaveable { mutableStateOf(true) }
    val normalTriggerEnabledInitial = remember(triggerPrefs) {
        triggerPrefs.getBoolean(KEY_TRIGGER_NORMAL_ENABLED, false)
    }
    var normalTriggerEnabled by rememberSaveable { mutableStateOf(normalTriggerEnabledInitial) }

    // ===== Recording settings UI state =====
    val recordingFormat by audioViewModel.recordingFormat.collectAsStateWithLifecycle()
    val recordingSampleRate by audioViewModel.recordingSampleRate.collectAsStateWithLifecycle()
    val publishRateOption by audioViewModel.publishRateOption.collectAsStateWithLifecycle()

    fun mimeForRecording(path: String): String {
        val lower = path.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    fun shareRecording(clip: RecordedClip) {
        try {
            if (clip.fileURL.startsWith("content://")) {
                val uri = Uri.parse(clip.fileURL)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeForRecording(clip.fileName)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, shareTitleRecording))
            } else {
                val file = File(clip.fileURL)
                if (!file.exists()) return

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeForRecording(file.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, shareTitleRecording))
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    val recordingSampleRateOptions = remember {
        listOf(16000, 22050, 32000, 44100, 48000)
    }
    fun nextRecordingFormat(current: AudioEngineViewModel.RecordingFormat): AudioEngineViewModel.RecordingFormat {
        return if (current == AudioEngineViewModel.RecordingFormat.WAV) {
            AudioEngineViewModel.RecordingFormat.M4A_AAC
        } else {
            AudioEngineViewModel.RecordingFormat.WAV
        }
    }
    fun nextRecordingSampleRate(current: Int): Int {
        val idx = recordingSampleRateOptions.indexOf(current)
        if (idx < 0) return recordingSampleRateOptions.first()
        return recordingSampleRateOptions[(idx + 1) % recordingSampleRateOptions.size]
    }
    fun prevPublishRateOption(current: AudioEngineViewModel.PublishRateOption): AudioEngineViewModel.PublishRateOption {
        val options = AudioEngineViewModel.PublishRateOption.entries
        val idx = options.indexOf(current)
        if (idx <= 0) return options.first()
        return options[idx - 1]
    }
    fun nextPublishRateOption(current: AudioEngineViewModel.PublishRateOption): AudioEngineViewModel.PublishRateOption {
        val options = AudioEngineViewModel.PublishRateOption.entries
        val idx = options.indexOf(current)
        if (idx < 0) return options.first()
        if (idx >= options.lastIndex) return options.last()
        return options[idx + 1]
    }

    // 收集ViewModel状态
    val isRunning by audioViewModel.isRunning.collectAsStateWithLifecycle()
    val useTestSignal by audioViewModel.useTestSignal.collectAsStateWithLifecycle()
    val testSignalPreset by audioViewModel.testSignalPreset.collectAsStateWithLifecycle()
    val useImportedSignal by audioViewModel.useImportedSignal.collectAsStateWithLifecycle()
    val importedAudioLabel by audioViewModel.importedAudioLabel.collectAsStateWithLifecycle()
    val isImportingAudio by audioViewModel.isImportingAudio.collectAsStateWithLifecycle()
    val importProgress by audioViewModel.importProgress.collectAsStateWithLifecycle()
    val importResultMessage by audioViewModel.importResultMessage.collectAsStateWithLifecycle()
    val isRecording by audioViewModel.isRecording.collectAsStateWithLifecycle()
    val isMonitoring by audioViewModel.isMonitoring.collectAsStateWithLifecycle()
    val recordings by audioViewModel.recordings.collectAsStateWithLifecycle()
    val recentlyDeletedRecordings by audioViewModel.recentlyDeletedRecordings.collectAsStateWithLifecycle()

    val lowPassEnabled by audioViewModel.lowPassEnabled.collectAsStateWithLifecycle()
    val lowPassCutoff by audioViewModel.lowPassCutoff.collectAsStateWithLifecycle()
    val lowPassStageCutoffs by audioViewModel.lowPassStageCutoffs.collectAsStateWithLifecycle()
    val highPassEnabled by audioViewModel.highPassEnabled.collectAsStateWithLifecycle()
    val highPassCutoff by audioViewModel.highPassCutoff.collectAsStateWithLifecycle()
    val highPassStageCutoffs by audioViewModel.highPassStageCutoffs.collectAsStateWithLifecycle()
    val globalHighPassEnabled by audioViewModel.globalHighPassEnabled.collectAsStateWithLifecycle()
    val globalHighPassCutoff by audioViewModel.globalHighPassCutoff.collectAsStateWithLifecycle()

    // 低通/高通阶数（1..8）
    val lowPassOrder by audioViewModel.lowPassOrder.collectAsStateWithLifecycle()
    val highPassOrder by audioViewModel.highPassOrder.collectAsStateWithLifecycle()

    val windowMs by audioViewModel.windowMs.collectAsStateWithLifecycle()
    val ampScale by audioViewModel.ampScale.collectAsStateWithLifecycle()
    val filteredWaveSamples by audioViewModel.filteredWaveform.collectAsStateWithLifecycle()

    LaunchedEffect(normalTriggerEnabled) {
        triggerPrefs.edit { putBoolean(KEY_TRIGGER_NORMAL_ENABLED, normalTriggerEnabled) }
    }
    LaunchedEffect(isLandscape, normalTriggerEnabled) {
        if (!isLandscape) audioViewModel.setTriggerEnabled(normalTriggerEnabled)
    }

    // ===== 显示幅度（倍数）范围：0.5..30（手势/显示用） =====
    val ampMin = 0.5f
    val ampMax = 30f

    // ===== 波形幅度缩放步进（x 倍数）=====
    // 需求：按住拖动时也保持步进显示/更新。
    // 分段步进（仅用于标题显示吸附，不影响实际手感/缩放）
    fun ampStepFor(v: Float): Float {
        val x = v.coerceAtLeast(0f)
        return when {
            x < 1f -> 0.05f
            x < 2f -> 0.1f
            x < 5f -> 0.2f
            x < 10f -> 0.5f
            else -> 1f
        }
    }
    fun snapAmp(v: Float): Float {
        val clamped = v.coerceIn(ampMin, ampMax)
        val step = ampStepFor(clamped)
        val snapped = (round(clamped / step) * step)
        return snapped.coerceIn(ampMin, ampMax)
    }

    // 竖屏：两条波形各自的“显示缩放”，互不影响
    var rawDisplayScale by remember { mutableStateOf(ampScale) }
    var filteredDisplayScale by remember { mutableStateOf(ampScale) }

    // ===== 竖屏：两条波形高度可调（标题行控件） =====
    val waveHeightMin = 50
    val waveHeightMax = 200
    val waveHeightStep = 10

    fun clampWaveHeight(v: Int): Int {
        val clamped = v.coerceIn(waveHeightMin, waveHeightMax)
        // Snap to step grid from min
        val snapped = (((clamped - waveHeightMin) + waveHeightStep / 2) / waveHeightStep) * waveHeightStep + waveHeightMin
        return snapped.coerceIn(waveHeightMin, waveHeightMax)
    }

    val rawWaveHeightInitial = remember(startupPrefs) {
        clampWaveHeight(startupPrefs.getInt(KEY_RAW_WAVE_HEIGHT_DP, 80))
    }
    val filteredWaveHeightInitial = remember(startupPrefs) {
        clampWaveHeight(startupPrefs.getInt(KEY_FILTERED_WAVE_HEIGHT_DP, 110))
    }
    var rawWaveHeightDp by rememberSaveable { mutableIntStateOf(rawWaveHeightInitial) }
    var filteredWaveHeightDp by rememberSaveable { mutableIntStateOf(filteredWaveHeightInitial) }

    fun incWaveHeight(current: Int): Int = clampWaveHeight(current + waveHeightStep)
    fun decWaveHeight(current: Int): Int = clampWaveHeight(current - waveHeightStep)

    LaunchedEffect(rawWaveHeightDp, filteredWaveHeightDp) {
        startupPrefs.edit {
            putInt(KEY_RAW_WAVE_HEIGHT_DP, rawWaveHeightDp)
            putInt(KEY_FILTERED_WAVE_HEIGHT_DP, filteredWaveHeightDp)
        }
    }

    // ViewModel 的 ampScale 变化时（例如重置/加载），如果用户还没改过各自缩放，则跟随
    LaunchedEffect(ampScale) {
        if (abs(rawDisplayScale - ampScale) < 1e-4f) rawDisplayScale = ampScale.coerceIn(ampMin, ampMax)
        if (abs(filteredDisplayScale - ampScale) < 1e-4f) filteredDisplayScale = ampScale.coerceIn(ampMin, ampMax)
    }

    // 竖屏标题用：仅显示内容做轻微低通，交互/波形绘制保持连续
    val rawScaleDisplay = rememberDisplayLowPass(rawDisplayScale, resetKey = "rawScale", alpha = 0.22f, snapThreshold = 0.01f)
    val filteredScaleDisplay = rememberDisplayLowPass(filteredDisplayScale, resetKey = "filteredScale", alpha = 0.22f, snapThreshold = 0.01f)
    val rawScaleText = String.format(Locale.US, "%.2f", snapAmp(rawScaleDisplay))
    val filteredScaleText = String.format(Locale.US, "%.2f", snapAmp(filteredScaleDisplay))

    // ===== 手势用：滤波后增益 dB <-> linear =====
    fun gainToDb(gain: Float): Float {
        val g = gain.coerceAtLeast(1e-6f)
        return (20f * (ln(g) / ln(10f)))
    }

    fun dbToGain(db: Float): Float {
        // gain = 10^(db/20)
        return exp((db / 20f) * ln(10f))
    }

    // ===== 频率滑块：拖动时用本地 0..1 状态避免“映射回写”造成手指/滑块不贴合 =====
    val lowPassMin = 800f
    val lowPassMax = 30001f
    val highPassMin = 20f
    val highPassMax = 10000f
    val lowPassLinearW = 0.5f

    // 明确类型，避免 Kotlin 推断失败
    var lpFreq01 by remember { mutableStateOf<Float>(hzToSliderBlend(lowPassCutoff, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)) }
    var hpFreq01 by remember { mutableStateOf<Float>(hzToSlider(highPassCutoff, highPassMin, highPassMax)) }
    var lpDragging by remember { mutableStateOf(false) }
    var hpDragging by remember { mutableStateOf(false) }

    // 拖动时标题显示值：做轻微低通平滑；实际参数仍实时生效
    val lowPassDisplayHzTarget = if (lpDragging)
        sliderToHzBlend(lpFreq01, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)
    else lowPassCutoff
    val lowPassDisplayHz = rememberDisplayLowPass(
        target = lowPassDisplayHzTarget,
        resetKey = "lowPassHz",
        alpha = 0.44f,
        snapThreshold = 1f,
    )

    val highPassDisplayHzTarget = if (hpDragging)
        sliderToHz(hpFreq01, highPassMin, highPassMax)
    else highPassCutoff
    val highPassDisplayHz = rememberDisplayLowPass(
        target = highPassDisplayHzTarget,
        resetKey = "highPassHz",
        alpha = 0.44f,
        snapThreshold = 0.25f,
    )

    // 拖动时也要实时影响滤波：节流（约 60Hz），避免每次 onValueChange 都回写引发抖动/性能问题
    LaunchedEffect(lpDragging, lpFreq01) {
        if (!lpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateLowPassSlider(
            snapLowPassHz(sliderToHzBlend(lpFreq01, lowPassMin, lowPassMax, linearWeight = lowPassLinearW))
        )
    }
    LaunchedEffect(hpDragging, hpFreq01) {
        if (!hpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateHighPassSlider(snapHighPassHz(sliderToHz(hpFreq01, highPassMin, highPassMax)))
    }

    // 当 ViewModel 的截止频率被外部改变（比如重置/加载/其它），且当前没有在拖动时，同步 UI 位置
    LaunchedEffect(lowPassCutoff) {
        if (!lpDragging) {
            lpFreq01 = hzToSliderBlend(lowPassCutoff, lowPassMin, lowPassMax, linearWeight = lowPassLinearW)
        }
    }
    LaunchedEffect(highPassCutoff) {
        if (!hpDragging) {
            hpFreq01 = hzToSlider(highPassCutoff, highPassMin, highPassMax)
        }
    }

    val engineError by audioViewModel.engineError.collectAsStateWithLifecycle()

    // ===== 滤波增益状态（纯手动） =====
    val filterGain by audioViewModel.filterGain.collectAsStateWithLifecycle()

    // ===== EQ state =====
    val eqEnabled by audioViewModel.eqEnabled.collectAsStateWithLifecycle()
    val eqBands by audioViewModel.eqBands.collectAsStateWithLifecycle()

    val playingId by audioViewModel.playingId.collectAsStateWithLifecycle()

    // 录音列表弹窗状态
    var showRecordList by rememberSaveable { mutableStateOf(false) }

    // 阶数选项：1..8（任意整数）
    val orderOptions = (1..8).toList()

    val settingsScroll = rememberScrollState()
    val eqDraggableInitial = remember(triggerPrefs) {
        triggerPrefs.getBoolean(KEY_EQ_DRAGGABLE, false)
    }
    var eqDraggable by rememberSaveable { mutableStateOf(eqDraggableInitial) }

    LaunchedEffect(eqDraggable) {
        audioViewModel.setEqGraphDraggable(eqDraggable)
        triggerPrefs.edit { putBoolean(KEY_EQ_DRAGGABLE, eqDraggable) }
    }

    // EQ 图拖动时，禁用外层滚动，避免“拖动节点同时界面滚动”
    val eqGraphDragging by audioViewModel.eqGraphDragging.collectAsStateWithLifecycle()

    // 对数映射：0..1 <-> [min,max]
    fun logToSlider(v: Float, min: Float, max: Float): Float = hzToSlider(v, min, max)
    fun sliderToLog(v01: Float, min: Float, max: Float): Float = sliderToHz(v01, min, max)

    // 时间窗（ms）范围：5..120
    val windowMinMs = 5f
    val windowMaxMs = 120f

    // 滤波后增益（线性）范围：-20dB..40dB (~0.1..100)
    val gainMin = 0.1f
    val gainMax = 100f


    // ===== 横屏手势：锁/解锁 + 实时显示（必须在 isLandscape 分支前定义） =====
    var landscapeLocked by remember { mutableStateOf(false) }
    var gestureOverlayVisible by remember { mutableStateOf(false) }
    var gestureAmp by remember { mutableStateOf(ampScale) }
    var gestureWindow by remember { mutableStateOf(windowMs) }
    // 0=none, 1=amp, 2=window
    var gestureMode by remember { mutableIntStateOf(0) }

    LaunchedEffect(ampScale) { if (!gestureOverlayVisible) gestureAmp = ampScale }
    LaunchedEffect(windowMs) { if (!gestureOverlayVisible) gestureWindow = windowMs }
    val currentWindowMs by rememberUpdatedState(windowMs)
    val currentRawDisplayScale by rememberUpdatedState(rawDisplayScale)
    val currentFilteredDisplayScale by rememberUpdatedState(filteredDisplayScale)

    // ===== 竖屏波形手势：两条波形分别控制纵向，横向共同控制时间窗 =====
    // （悬浮文本已移除，保留手势 lock 状态）

    // DELETE these if still present:
    // var portraitOverlayVisible by remember { mutableStateOf(false) }
    // var portraitOverlayText by remember { mutableStateOf("") }
    // var portraitGestureMode by remember { mutableIntStateOf(0) }

    // 拖动手势：锁定状态（避免同时影响两条波形）
    var rawDragMode by remember { mutableIntStateOf(0) }      // 0 none, 1 vertical, 2 horizontal
    var filteredDragMode by remember { mutableIntStateOf(0) } // 0 none, 1 vertical, 2 horizontal

    // ===== 仅横屏：全屏波形 + 手势控制 + 锁/解锁 + 切回竖屏 =====
    if (isLandscape) {
        key(isLandscape) {
            ImmersiveScreen(
                modifier = modifier,
                audioViewModel = audioViewModel,
                setLandscape = ::setLandscape,
                landscapeLocked = landscapeLocked,
                onToggleLock = { landscapeLocked = !landscapeLocked },
                filteredWaveform = immersiveFilteredWaveform,
                useTestSignal = useTestSignal,
                filteredDisplayScale = filteredDisplayScale,
                showRefWaveforms = showRefWaveforms,
                onToggleShowRef = { showRefWaveforms = !showRefWaveforms },
                showDebugInfo = showDebugInfo,
                onToggleShowDebugInfo = { showDebugInfo = !showDebugInfo },
                ampMin = ampMin,
                ampMax = ampMax,
                windowMinMs = windowMinMs,
                windowMaxMs = windowMaxMs,
                gestureOverlayVisible = gestureOverlayVisible,
                onGestureOverlayVisible = { gestureOverlayVisible = it },
                gestureAmp = gestureAmp,
                onGestureAmp = { gestureAmp = it },
                gestureWindow = gestureWindow,
                onGestureWindow = { gestureWindow = it },
                gestureMode = gestureMode,
                onGestureMode = { gestureMode = it },
                waveformSpanMs = immersiveWaveformSpanMs,
                ampScale = immersiveAmpScale,
                onAmpScale = { v ->
                    filteredDisplayScale = v
                    audioViewModel.updateAmpSlider(v)
                },
                onWindowMs = { v -> audioViewModel.updateTimeSlider(v) },
                onTriggerEnabled = { v -> audioViewModel.setTriggerEnabled(v) },
            )
        }
        return
    }

    // ===== 竖屏：原界面保持不变（仅加了横屏切换按钮） =====

    Column(modifier = modifier.fillMaxSize().navigationBarsPadding()) {
        // ===== 固定波形区：两行 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // 顶部：左侧“进入沉浸模式”，右侧“预设/关于/退出”
            var presetMenuExpanded by remember { mutableStateOf(false) }
            var settingsMenuExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val topActionModifier = Modifier.size(38.dp)
                val topActionShape = RoundedCornerShape(11.dp)
                val topActionBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                val topActionColors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedIconButton(
                    onClick = { setLandscape(true) },
                    modifier = topActionModifier,
                    shape = topActionShape,
                    border = topActionBorder,
                    colors = topActionColors
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_open_in_full),
                        contentDescription = stringResource(R.string.mode_immersive)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 预设：图标入口 + 下拉四个选项 + 提示语（文案保持不变）
                Box {
                    OutlinedIconButton(
                        onClick = { presetMenuExpanded = true },
                        modifier = topActionModifier,
                        shape = topActionShape,
                        border = topActionBorder,
                        colors = topActionColors
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_save_custom),
                            contentDescription = stringResource(R.string.preset_menu_title)
                        )
                    }

                    DropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_import)) },
                            onClick = {
                                presetMenuExpanded = false
                                importPresetLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export)) },
                            onClick = {
                                presetMenuExpanded = false
                                exportPresetLauncher.launch("oscope_preset.json")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            onClick = {
                                presetMenuExpanded = false
                                presetShareName = "oscope_preset"
                                presetShareDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_default)) },
                            onClick = {
                                presetMenuExpanded = false
                                presetResetConfirmDialog = true
                            }
                        )

                        HorizontalDivider()

                        // 提示语：保持原文案不变
                        Text(
                            text = stringResource(R.string.preset_help_text), //不要改
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .widthIn(max = 320.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedIconButton(
                    onClick = { showAboutDialog = true },
                    modifier = topActionModifier,
                    shape = topActionShape,
                    border = topActionBorder,
                    colors = topActionColors
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_custom),
                        contentDescription = stringResource(R.string.about_title)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box {
                    OutlinedIconButton(
                        onClick = { settingsMenuExpanded = true },
                        modifier = topActionModifier,
                        shape = topActionShape,
                        border = topActionBorder,
                        colors = topActionColors
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings_custom),
                            contentDescription = stringResource(R.string.settings_language_label)
                        )
                    }

                    DropdownMenu(
                        expanded = settingsMenuExpanded,
                        onDismissRequest = { settingsMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.trigger_label))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = normalTriggerEnabled,
                                        onCheckedChange = { normalTriggerEnabled = it }
                                    )
                                }
                            },
                            onClick = { normalTriggerEnabled = !normalTriggerEnabled }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.eq_graph_dragging_label))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = eqDraggable,
                                        onCheckedChange = { eqDraggable = it }
                                    )
                                }
                            },
                            onClick = { eqDraggable = !eqDraggable }
                        )
                        // Quick access: global 1Hz high-pass toggle (also present in full settings)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.global_high_pass_label))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = globalHighPassEnabled,
                                        onCheckedChange = { audioViewModel.setGlobalHighPassEnabled(it) }
                                    )
                                }
                            },
                            onClick = {
                                // Toggle via click as well
                                audioViewModel.setGlobalHighPassEnabled(!globalHighPassEnabled)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.waveform_ref_line_label))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = showRefWaveforms,
                                        onCheckedChange = { showRefWaveforms = it }
                                    )
                                }
                            },
                            onClick = { showRefWaveforms = !showRefWaveforms }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.recording_format_label) + ": " + recordingFormat.label
                                )
                            },
                            enabled = !isRecording,
                            onClick = {
                                audioViewModel.setRecordingFormat(nextRecordingFormat(recordingFormat))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.sample_rate_label) + ": ${recordingSampleRate}Hz"
                                )
                            },
                            enabled = !isRecording,
                            onClick = {
                                audioViewModel.setRecordingSampleRate(nextRecordingSampleRate(recordingSampleRate))
                            }
                        )
                        // Compact entry to open custom storage dialog (absolute path input + SAF picker)
                        DropdownMenuItem(
                            text = {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.settings_custom_storage_label))
                                    Spacer(Modifier.weight(1f))
                                    // show indicator if any path/tree saved
                                    val p = startupPrefs.getString("custom_recording_path", "") ?: ""
                                    val t = startupPrefs.getString("custom_recording_tree_uri", "") ?: ""
                                    if (p.isNotBlank() || t.isNotBlank()) Text("✓", style = MaterialTheme.typography.bodyMedium)
                                }
                            },
                            onClick = {
                                settingsMenuExpanded = false
                                showCustomStorageDialog = true
                            }
                        )
                        val publishRateOptions = AudioEngineViewModel.PublishRateOption.entries
                        val publishRateIdx = publishRateOptions.indexOf(publishRateOption).coerceAtLeast(0)
                        // Use DropdownMenuItem so the font/spacing matches other menu entries
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.waveform_refresh_rate_label))
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        enabled = publishRateIdx > 0,
                                        onClick = {
                                            audioViewModel.setPublishRateOption(prevPublishRateOption(publishRateOption))
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) { Text("-") }
                                    Text(
                                        text = "${publishRateOption.hz}Hz",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    TextButton(
                                        enabled = publishRateIdx < publishRateOptions.lastIndex,
                                        onClick = {
                                            audioViewModel.setPublishRateOption(nextPublishRateOption(publishRateOption))
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) { Text("+") }
                                }
                            },
                            onClick = { /* no-op: actions handled by inner buttons */ }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (selectedLanguage == LANG_ZH) "✓ ${stringResource(R.string.language_option_zh)}" else stringResource(R.string.language_option_zh)
                                )
                            },
                            onClick = {
                                settingsMenuExpanded = false
                                requestLanguageSwitch(LANG_ZH)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (selectedLanguage == LANG_EN) "✓ ${stringResource(R.string.language_option_en)}" else stringResource(R.string.language_option_en)
                                )
                            },
                            onClick = {
                                settingsMenuExpanded = false
                                requestLanguageSwitch(LANG_EN)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_scroll_top)) },
                            onClick = {
                                settingsMenuExpanded = false
                                uiScope.launch { settingsScroll.animateScrollTo(0) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        // 避免误触：退出前确认
                        showExitConfirmDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        // 不要太亮的红色
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.exit_title)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 原始波形标题：把实时值显示在括号里（替代悬浮文本）
                    text = stringResource(R.string.waveform_input_title_value, rawScaleText),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                // Height control
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { rawWaveHeightDp = decWaveHeight(rawWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("-", style = MaterialTheme.typography.bodyMedium) }

                    Text(
                        text = "${rawWaveHeightDp}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    IconButton(
                        onClick = { rawWaveHeightDp = incWaveHeight(rawWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
                }

            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rawWaveHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                rawDragMode = 0
                            },
                            onDragEnd = { rawDragMode = 0 },
                            onDragCancel = { rawDragMode = 0 },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val dx = dragAmount.x
                                val dy = dragAmount.y

                                val invH = (1.0 / (size.height.toDouble().coerceAtLeast(1.0))).toFloat()
                                val invW = (1.0 / (size.width.toDouble().coerceAtLeast(1.0))).toFloat()

                                // 竖屏：纵向调幅度，横向调时间窗
                                if (rawDragMode == 0) {
                                    val ax = abs(dx)
                                    val ay = abs(dy)
                                    val thresholdPx = 4f
                                    if (ax + ay > thresholdPx) {
                                        rawDragMode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                    }
                                }

                                when (rawDragMode) {
                                    1 -> {
                                        val dy01 = (-(dy) * invH).coerceIn(-1f, 1f)
                                        // 对数手感：在 log 域里加法 => 线性域里乘法
                                        // dy01>0 表示向上滑 => 放大；dy01<0 => 缩小
                                        val k = 0.6f // tweak: lower => less sensitive
                                        val factor = exp(dy01 * k)
                                        rawDisplayScale = (currentRawDisplayScale * factor).coerceIn(ampMin, ampMax)
                                    }
                                    2 -> {
                                        val dx01 = (dx * invW).coerceIn(-1f, 1f)
                                        val baseStepMs = 30f
                                        val accel = (currentWindowMs / 40f).coerceIn(0.5f, 14f)
                                        val deltaMs = (dx01) * baseStepMs * accel
                                        val nextWindow = (currentWindowMs + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                        audioViewModel.updateTimeSlider(nextWindow)
                                    }
                                }
                            }
                        )
                    }
            ) {
                LiveWaveformView(
                    samplesFlow = audioViewModel.rawWaveform,
                    ampScale = rawDisplayScale,
                    lineColor = Color.Blue,
                    showReference = showRefWaveforms,
                    referenceAmpNormalized = rawDisplayScale.coerceAtLeast(1e-4f),
                    referenceColor = Color(0x22000000),
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 处理后波形标题：仅显示缩放（与示例一致）
                    text = stringResource(R.string.waveform_processed_title_value, filteredScaleText),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                // Height control
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { filteredWaveHeightDp = decWaveHeight(filteredWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("-", style = MaterialTheme.typography.bodyMedium) }

                    Text(
                        text = "${filteredWaveHeightDp}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    IconButton(
                        onClick = { filteredWaveHeightDp = incWaveHeight(filteredWaveHeightDp) },
                        modifier = Modifier.size(28.dp)
                    ) { Text("+", style = MaterialTheme.typography.bodyMedium) }
                }

            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(filteredWaveHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                filteredDragMode = 0
                            },
                            onDragEnd = { filteredDragMode = 0 },
                            onDragCancel = { filteredDragMode = 0 },
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val dx = dragAmount.x
                                val dy = dragAmount.y

                                val invH = (1.0 / (size.height.toDouble().coerceAtLeast(1.0))).toFloat()
                                val invW = (1.0 / (size.width.toDouble().coerceAtLeast(1.0))).toFloat()

                                // 竖屏：纵向调幅度，横向调时间窗
                                if (filteredDragMode == 0) {
                                    val ax = abs(dx)
                                    val ay = abs(dy)
                                    val thresholdPx = 4f
                                    if (ax + ay > thresholdPx) {
                                        filteredDragMode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                    }
                                }

                                when (filteredDragMode) {
                                    1 -> {
                                        val dy01 = (-(dy) * invH).coerceIn(-1f, 1f)
                                        val k = 0.6f // tweak: lower => less sensitive
                                        val factor = exp(dy01 * k)
                                        filteredDisplayScale = (currentFilteredDisplayScale * factor).coerceIn(ampMin, ampMax)
                                    }
                                    2 -> {
                                        val dx01 = (dx * invW).coerceIn(-1f, 1f)
                                        val baseStepMs = 30f
                                        val accel = (currentWindowMs / 40f).coerceIn(0.5f, 14f)
                                        val deltaMs = (dx01) * baseStepMs * accel
                                        val nextWindow = (currentWindowMs + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                        audioViewModel.updateTimeSlider(nextWindow)
                                    }
                                }
                            }
                        )
                    }
            ) {
                val vmTriggeredWindow by audioViewModel.triggeredWindow.collectAsStateWithLifecycle()
                val displayFilteredSamples = if (normalTriggerEnabled && vmTriggeredWindow.isNotEmpty())
                    vmTriggeredWindow else filteredWaveSamples

                WaveformView(
                    samples = displayFilteredSamples,
                    ampScale = filteredDisplayScale,
                    lineColor = Color.Red,
                    showReferenceWhenBelow1x = showRefWaveforms,
                    referenceAmpNormalized = filteredDisplayScale.coerceAtLeast(1e-4f),
                    referenceColor = Color(0x22FF0000),
                    referenceDashed = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            CaptureDiagnosticsLine(audioViewModel = audioViewModel)

            // 竖屏波形手势浮层：已移除（改为显示在标题括号里）
            // if (portraitOverlayVisible && portraitOverlayText.isNotBlank()) {
            //     Box(
            //         modifier = Modifier
            //             .fillMaxWidth()
            //             .padding(top = 4.dp),
            //         contentAlignment = Alignment.Center
            //     ) {
            //         Box(
            //             modifier = Modifier
            //                 .clip(RoundedCornerShape(10.dp))
            //                 .background(Color(0xAA000000))
            //                 .padding(horizontal = 12.dp, vertical = 8.dp)
            //         ) {
            //             Text(
            //                 text = portraitOverlayText,
            //                 color = Color.White,
            //                 style = MaterialTheme.typography.bodyMedium,
            //                 textAlign = TextAlign.Center
            //             )
            //         }
            //     }
            // }
        }

        HorizontalDivider()

        PortraitSettingsSection(
            modifier = Modifier.fillMaxSize(),
            settingsScroll = settingsScroll,
            eqGraphDragging = eqGraphDragging,
            eqDraggable = eqDraggable,
            audioViewModel = audioViewModel,
            state = PortraitSettingsState(
                isRunning = isRunning,
                useTestSignal = useTestSignal,
                testSignalPreset = testSignalPreset,
                useImportedSignal = useImportedSignal,
                importedAudioLabel = importedAudioLabel,
                isImportingAudio = isImportingAudio,
                importProgress = importProgress,
                importResultMessage = importResultMessage,
                isRecording = isRecording,
                isMonitoring = isMonitoring,
                engineError = engineError,
                recordings = recordings,
                recentlyDeletedRecordings = recentlyDeletedRecordings,
                lowPassEnabled = lowPassEnabled,
                lowPassCutoff = lowPassCutoff,
                lowPassStageCutoffs = lowPassStageCutoffs,
                highPassEnabled = highPassEnabled,
                highPassCutoff = highPassCutoff,
                highPassStageCutoffs = highPassStageCutoffs,
                lowPassOrder = lowPassOrder,
                highPassOrder = highPassOrder,
                windowMs = windowMs,
                filterGain = filterGain,
                eqEnabled = eqEnabled,
                eqBands = eqBands,
                globalHighPassEnabled = globalHighPassEnabled,
                globalHighPassCutoff = globalHighPassCutoff,
                playingId = playingId,
                playbackPositionMs = playbackPositionMs,
                playbackDurationMs = playbackDurationMs,
            ),
            actions = PortraitSettingsActions(
                onStartStopAction = {
                    if (useTestSignal) {
                        Log.i("WaveStudio", "Stop (source: test signal)")
                        audioViewModel.toggleVvvfTestSignal()
                    } else if (useImportedSignal) {
                        Log.i("WaveStudio", "Stop (source: imported signal)")
                        audioViewModel.stopImportedSignalInput()
                    } else if (isRunning) {
                        Log.i("WaveStudio", "Stop (source: user)")
                        audioViewModel.stopEngine()
                    } else {
                        Log.i("WaveStudio", "Start (source: user)")
                        val recordAudioGranted = permissionsState.permissions
                            .firstOrNull { it.permission == Manifest.permission.RECORD_AUDIO }
                            ?.status is PermissionStatus.Granted
                        if (recordAudioGranted) audioViewModel.startEngine(context)
                        else permissionsState.launchMultiplePermissionRequest()
                    }
                },
                onRecordAction = {
                    if (isRecording) {
                        audioViewModel.stopRecording()
                    } else {
                        val recordAudioGranted = permissionsState.permissions
                            .firstOrNull { it.permission == Manifest.permission.RECORD_AUDIO }
                            ?.status is PermissionStatus.Granted
                        if (!useImportedSignal && !recordAudioGranted) {
                            permissionsState.launchMultiplePermissionRequest()
                        } else {
                            audioViewModel.startRecording(context)
                        }
                    }
                },
                onMonitoringToggle = { audioViewModel.toggleMonitoring() },
                onToggleLowPass = { audioViewModel.toggleLowPass(it) },
                onToggleHighPass = { audioViewModel.toggleHighPass(it) },
                onSetLowPassOrder = { audioViewModel.setLowPassOrder(it) },
                onSetHighPassOrder = { audioViewModel.setHighPassOrder(it) },
                onSetLowPassStageCutoff = { stage, cutoff ->
                    audioViewModel.updateLowPassStageCutoff(stage, cutoff)
                },
                onSetHighPassStageCutoff = { stage, cutoff ->
                    audioViewModel.updateHighPassStageCutoff(stage, cutoff)
                },
                onUpdateFilterGain = { audioViewModel.updateFilterGain(it) },
                onUpdateTimeSlider = { audioViewModel.updateTimeSlider(it) },
                onSetEqEnabled = { audioViewModel.setEqEnabled(it) },
                onResetEq = { audioViewModel.resetEq() },
                onSetEqBandEnabled = { id, en -> audioViewModel.setEqBandEnabled(id, en) },
                onSetEqBandType = { id, type -> audioViewModel.setEqBandType(id, type) },
                onSetEqBandFreq = { id, hz -> audioViewModel.setEqBandFreq(id, hz) },
                onSetEqBandGainDb = { id, db -> audioViewModel.setEqBandGainDb(id, db) },
                onSetEqBandQ = { id, q -> audioViewModel.setEqBandQ(id, q) },
                onSetEqGraphDragging = { audioViewModel.setEqGraphDragging(it) },
                onToggleVvvfTestSignal = { audioViewModel.toggleVvvfTestSignal() },
                onSetTestSignalPreset = { audioViewModel.setTestSignalPreset(it) },
                onToggleGlobalHighPass = { audioViewModel.setGlobalHighPassEnabled(it) },
                onSetGlobalHighPassCutoff = { audioViewModel.setGlobalHighPassCutoff(it) },
            ),
        )

    }

    if (showStartupNoteDialog) {
        AlertDialog(
            onDismissRequest = { showStartupNoteDialog = false },
            title = { Text(stringResource(R.string.startup_note_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(startupNoteText)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .toggleable(
                                value = doNotShowStartupNoteAgain,
                                role = Role.Checkbox,
                                onValueChange = { doNotShowStartupNoteAgain = it }
                            )
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = doNotShowStartupNoteAgain,
                            onCheckedChange = null
                        )
                        Text(
                            text = stringResource(R.string.startup_note_do_not_show),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startupPrefs.edit { putBoolean(KEY_HIDE_STARTUP_NOTE, doNotShowStartupNoteAgain) }
                        showStartupNoteDialog = false
                    }
                ) { Text(stringResource(R.string.startup_note_ack)) }
            }
        )
    }

    AboutDialog(
        visible = showAboutDialog,
        selectedLanguage = selectedLanguage,
        onDismiss = { showAboutDialog = false },
        onShowStartupNote = { openStartupNoteDialog() }
    )

    if (showCustomStorageDialog) {
        // Dialog-local state
        var dialogPath by rememberSaveable { mutableStateOf(startupPrefs.getString("custom_recording_path", "") ?: "") }
        val savedTree = startupPrefs.getString("custom_recording_tree_uri", "") ?: ""

        AlertDialog(
            onDismissRequest = { showCustomStorageDialog = false },
            title = { Text(stringResource(R.string.settings_custom_storage_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialogPath,
                        onValueChange = { dialogPath = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_custom_storage_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { chooseDirLauncherForSettings.launch(null) }) { Text(stringResource(R.string.settings_choose_directory)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        startupPrefs.edit { putString("custom_recording_path", dialogPath.trim()) }
                        showCenterToast(settingsSavePathMessage)
                    } catch (_: Throwable) {
                        showCenterToast(settingsInvalidPathMessage)
                    }
                    showCustomStorageDialog = false
                }) { Text(settingsSavePathMessage) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        startupPrefs.edit { remove("custom_recording_path"); remove("custom_recording_tree_uri") }
                        showCenterToast(commonResetMessage)
                        showCustomStorageDialog = false
                    }) { Text(commonResetMessage) }
                    TextButton(onClick = { showCustomStorageDialog = false }) { Text(commonCancelMessage) }
                }
            }
        )
    }

    if (presetShareDialog) {
        PresetShareDialog(
            visible = true,
            presetShareName = presetShareName,
            onPresetShareNameChange = { presetShareName = it },
            onDismiss = { presetShareDialog = false },
            onShare = {
                Log.i("WaveStudio", "Share preset file: $presetShareName")
                val file = exportCurrentPresetToCache(presetShareName)
                if (file != null) {
                    showCenterToast(resources.getString(R.string.preset_share_success_named, file.name))
                    shareAnyFile(file, "application/json")
                } else {
                    showCenterToast(resources.getString(R.string.preset_share_failed))
                }
                presetShareDialog = false
            },
        )
    }

    if (presetResetConfirmDialog) {
        PresetResetConfirmDialog(
            visible = true,
            onDismiss = { presetResetConfirmDialog = false },
            onConfirm = {
                Log.i("WaveStudio", "Reset preset to defaults")
                audioViewModel.resetFilterPresetToDefault()
                showCenterToast(resources.getString(R.string.preset_restore_default_done))
                presetResetConfirmDialog = false
            },
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        Log.i("WaveStudio", "Exit application")
                        showExitConfirmDialog = false
                        // 尽量优雅退出：先停引擎/录音/监听
                        try { audioViewModel.stopEngine() } catch (_: Throwable) {}
                        try { activity?.finish() } catch (_: Throwable) {}
                    }
                ) { Text(stringResource(R.string.exit_title)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 语言切换确认弹窗
    if (showLanguageSwitchDialog) {
        val langName = if (pendingLanguage == LANG_ZH)
            stringResource(R.string.language_option_zh)
        else
            stringResource(R.string.language_option_en)

        AlertDialog(
            onDismissRequest = { showLanguageSwitchDialog = false },
            title = { Text(stringResource(R.string.language_switch_confirm_title)) },
            text = { Text(stringResource(R.string.language_switch_confirm_message, langName)) },
            confirmButton = {
                TextButton(onClick = {
                    showLanguageSwitchDialog = false
                    switchAppLanguage(pendingLanguage)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageSwitchDialog = false }) {
                    Text(stringResource(R.string.language_switch_cancel))
                }
            }
        )
    }
}

/**
 * 子组件和工具函数已拆分到：
 * - OscopeUIComponents.kt (ImmersiveScreen, LiveWaveformView, CaptureDiagnosticsLine, EqPanel 等组件)
 * - OscopeUIUtils.kt (sliderToHz, rememberDisplayLowPass, computeEqResponse 等工具函数)
 */
