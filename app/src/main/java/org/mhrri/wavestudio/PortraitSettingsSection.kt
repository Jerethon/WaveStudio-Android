package org.mhrri.wavestudio

// Intent/Uri are referenced via fully-qualified names to avoid import ambiguity in some environments.
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

internal data class PortraitSettingsState(
    val isRunning: Boolean,
    val useTestSignal: Boolean,
    val testSignalPreset: AudioEngineViewModel.TestSignalPreset,
    val useImportedSignal: Boolean,
    val importedAudioLabel: String?,
    val isImportingAudio: Boolean,
    val importProgress: Float,
    val importResultMessage: String?,
    val isRecording: Boolean,
    val isMonitoring: Boolean,
    val engineError: String?,
    val recordings: List<RecordedClip>,
    val recentlyDeletedRecordings: List<RecordedClip>,
    val lowPassEnabled: Boolean,
    val lowPassCutoff: Float,
    val lowPassStageCutoffs: List<Float>,
    val highPassEnabled: Boolean,
    val highPassCutoff: Float,
    val highPassStageCutoffs: List<Float>,
    val lowPassOrder: Int,
    val highPassOrder: Int,
    val windowMs: Float,
    val filterGain: Float,
    val eqEnabled: Boolean,
    val eqBands: List<AudioEngineViewModel.EqBand>,
    val globalHighPassEnabled: Boolean,
    val globalHighPassCutoff: Float,
    val playingId: String?,
    val playbackPositionMs: Long,
    val playbackDurationMs: Long,
)

internal data class PortraitSettingsActions(
    val onStartStopAction: () -> Unit,
    val onRecordAction: () -> Unit,
    val onMonitoringToggle: () -> Unit,
    val onToggleLowPass: (Boolean) -> Unit,
    val onToggleHighPass: (Boolean) -> Unit,
    val onSetLowPassOrder: (Int) -> Unit,
    val onSetHighPassOrder: (Int) -> Unit,
    val onSetLowPassStageCutoff: (Int, Float) -> Unit,
    val onSetHighPassStageCutoff: (Int, Float) -> Unit,
    val onUpdateFilterGain: (Float) -> Unit,
    val onUpdateTimeSlider: (Float) -> Unit,
    val onSetEqEnabled: (Boolean) -> Unit,
    val onResetEq: () -> Unit,
    val onSetEqBandEnabled: (Int, Boolean) -> Unit,
    val onSetEqBandType: (Int, AudioEngineViewModel.EqBandType) -> Unit,
    val onSetEqBandFreq: (Int, Float) -> Unit,
    val onSetEqBandGainDb: (Int, Float) -> Unit,
    val onSetEqBandQ: (Int, Float) -> Unit,
    val onSetEqGraphDragging: (Boolean) -> Unit,
    val onToggleVvvfTestSignal: () -> Unit,
    val onSetTestSignalPreset: (AudioEngineViewModel.TestSignalPreset) -> Unit,
    val onToggleGlobalHighPass: (Boolean) -> Unit,
    val onSetGlobalHighPassCutoff: (Float) -> Unit,
)

private fun showCenterToast(context: android.content.Context, msg: String) {
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

private fun exportCurrentPresetToCache(context: android.content.Context, audioViewModel: AudioEngineViewModel, nameNoExt: String): File? {
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

private fun shareAnyFile(context: android.content.Context, file: File, mime: String) {
    try {
        if (!file.exists()) return
        val uri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_title_generic)))
    } catch (_: Throwable) {
    }
}

private fun mimeForRecording(path: String): String {
    val lower = path.lowercase(Locale.getDefault())
    return when {
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".mp4") -> "audio/mp4"
        lower.endsWith(".aac") -> "audio/aac"
        else -> "application/octet-stream"
    }
}

private fun nextRecordingFormat(current: AudioEngineViewModel.RecordingFormat): AudioEngineViewModel.RecordingFormat {
    return if (current == AudioEngineViewModel.RecordingFormat.WAV) {
        AudioEngineViewModel.RecordingFormat.M4A_AAC
    } else {
        AudioEngineViewModel.RecordingFormat.WAV
    }
}

private fun nextRecordingSampleRate(current: Int, options: List<Int>): Int {
    val idx = options.indexOf(current)
    if (idx < 0) return options.first()
    return options[(idx + 1) % options.size]
}

private fun prevPublishRateOption(current: AudioEngineViewModel.PublishRateOption): AudioEngineViewModel.PublishRateOption {
    val options = AudioEngineViewModel.PublishRateOption.entries
    val idx = options.indexOf(current)
    if (idx <= 0) return options.first()
    return options[idx - 1]
}

private fun nextPublishRateOption(current: AudioEngineViewModel.PublishRateOption): AudioEngineViewModel.PublishRateOption {
    val options = AudioEngineViewModel.PublishRateOption.entries
    val idx = options.indexOf(current)
    if (idx < 0) return options.first()
    if (idx >= options.lastIndex) return options.last()
    return options[idx + 1]
}

private fun cutoffStepLowPass(hz: Float): Float {
    val v = hz.coerceAtLeast(0f)
    return when {
        v < 5000f -> 100f
        v < 10000f -> 200f
        else -> 500f
    }
}

private fun cutoffStepHighPass(hz: Float): Float {
    val v = hz.coerceAtLeast(0f)
    return when {
        v < 100f -> 5f
        v < 500f -> 10f
        v < 1000f -> 20f
        v < 2000f -> 50f
        else -> 100f
    }
}

private fun snapLowPassHz(hz: Float): Float {
    val v = hz.coerceIn(600f, 30001f)
    val step = cutoffStepLowPass(v)
    return (round(v / step) * step).coerceIn(600f, 30001f)
}

private fun snapHighPassHz(hz: Float): Float {
    val v = hz.coerceIn(20f, 10000f)
    val step = cutoffStepHighPass(v)
    return (round(v / step) * step).coerceIn(20f, 10000f)
}

private fun formatLowPassHz(hz: Float): String = snapLowPassHz(hz).toInt().toString()

private fun formatHighPassHz(hz: Float): String {
    val v = snapHighPassHz(hz)
    return if (v < 100f) String.format(Locale.US, "%.0f", v) else v.toInt().toString()
}

private fun gainToDb(gain: Float): Float {
    val g = gain.coerceAtLeast(1e-6f)
    return 20f * (ln(g) / ln(10f))
}

private fun dbToGain(db: Float): Float {
    return exp((db / 20f) * ln(10f))
}

@Composable
private fun FilterStageCutoffControl(
    stageIndex: Int,
    cutoffHz: Float,
    toSlider: (Float) -> Float,
    fromSlider: (Float) -> Float,
    snap: (Float) -> Float,
    format: (Float) -> String,
    onCutoffChange: (Float) -> Unit,
) {
    var sliderValue by remember(stageIndex) { mutableStateOf(toSlider(cutoffHz)) }
    var dragging by remember(stageIndex) { mutableStateOf(false) }

    LaunchedEffect(cutoffHz) {
        if (!dragging) sliderValue = toSlider(cutoffHz)
    }

    val displayedHz = if (dragging) snap(fromSlider(sliderValue)) else cutoffHz
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ClickToEditNumberText(
            text = stringResource(
                R.string.filter_stage_cutoff_value,
                stageIndex + 1,
                format(displayedHz),
            ),
            initialText = format(cutoffHz),
            title = stringResource(R.string.filter_stage_cutoff_set_title, stageIndex + 1),
            unit = "Hz",
            parseAndClamp = { text ->
                text.trim().replace(",", ".").toFloatOrNull()?.let(snap)
            },
            onValue = { value ->
                dragging = false
                val snapped = snap(value)
                sliderValue = toSlider(snapped)
                onCutoffChange(snapped)
            },
            style = MaterialTheme.typography.bodyMedium,
            contentPadding = PaddingValues(0.dp),
        )
        LowHighPassRow(
            freq01 = sliderValue,
            onFreq01Change = { value ->
                dragging = true
                sliderValue = value
                onCutoffChange(snap(fromSlider(value)))
            },
            onFreq01ChangeFinished = {
                dragging = false
                val snapped = snap(fromSlider(sliderValue))
                sliderValue = toSlider(snapped)
                onCutoffChange(snapped)
            },
        )
    }
}

@Composable
internal fun PortraitSettingsSection(
    modifier: Modifier = Modifier,
    settingsScroll: androidx.compose.foundation.ScrollState,
    eqGraphDragging: Boolean,
    eqDraggable: Boolean,
    audioViewModel: AudioEngineViewModel,
    state: PortraitSettingsState,
    actions: PortraitSettingsActions,
) {
    val context = LocalContext.current
    val resources = context.resources
    val coroutineScope = rememberCoroutineScope()

    var presetShareDialog by rememberSaveable { mutableStateOf(false) }
    var presetShareName by rememberSaveable { mutableStateOf("oscope_preset") }
    var presetResetConfirmDialog by rememberSaveable { mutableStateOf(false) }

    var showRecordList by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecordedClip?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RecordedClip?>(null) }
    var testSignalMenu by remember { mutableStateOf(false) }

    var lpFreq01 by remember { mutableStateOf(hzToSliderBlend(state.lowPassCutoff, 600f, 30001f, linearWeight = 0.5f)) }
    var hpFreq01 by remember { mutableStateOf(hzToSlider(state.highPassCutoff, 20f, 10000f)) }
    var lpDragging by remember { mutableStateOf(false) }
    var hpDragging by remember { mutableStateOf(false) }

    // 拖动时标题显示值：做轻微低通平滑；实际参数仍实时生效
    val lowPassDisplayHzTarget = if (lpDragging)
        sliderToHzBlend(lpFreq01, 600f, 30001f, linearWeight = 0.5f)
    else state.lowPassCutoff
    val lowPassDisplayHz = rememberDisplayLowPass(
        target = lowPassDisplayHzTarget,
        resetKey = "lowPassHz",
        alpha = 0.44f,
        snapThreshold = 1f,
    )

    val highPassDisplayHzTarget = if (hpDragging)
        sliderToHz(hpFreq01, 20f, 10000f)
    else state.highPassCutoff
    val highPassDisplayHz = rememberDisplayLowPass(
        target = highPassDisplayHzTarget,
        resetKey = "highPassHz",
        alpha = 0.44f,
        snapThreshold = 0.25f,
    )

    LaunchedEffect(state.lowPassCutoff) {
        if (!lpDragging) lpFreq01 = hzToSliderBlend(state.lowPassCutoff, 600f, 30001f, linearWeight = 0.5f)
    }
    LaunchedEffect(state.highPassCutoff) {
        if (!hpDragging) hpFreq01 = hzToSlider(state.highPassCutoff, 20f, 10000f)
    }

    // 拖动时实时影响滤波，效果会立即体现在波形上，而不是等松手。
    LaunchedEffect(lpDragging, lpFreq01) {
        if (!lpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateLowPassSlider(
            snapLowPassHz(sliderToHzBlend(lpFreq01, 600f, 30001f, linearWeight = 0.5f))
        )
    }
    LaunchedEffect(hpDragging, hpFreq01) {
        if (!hpDragging) return@LaunchedEffect
        kotlinx.coroutines.delay(8)
        audioViewModel.updateHighPassSlider(snapHighPassHz(sliderToHz(hpFreq01, 20f, 10000f)))
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Log.i("WaveStudio", "Import preset file: $uri")
        try {
            context.contentResolver.openInputStream(uri).use { inS: InputStream? ->
                val text = inS?.bufferedReader(Charsets.UTF_8)?.readText()
                if (text.isNullOrBlank()) {
                    showCenterToast(context, resources.getString(R.string.preset_import_failed_empty))
                } else {
                    val preset = FilterPreset.fromJsonString(text)
                    audioViewModel.applyPreset(preset)
                    val presetName = preset.name?.takeIf { it.isNotBlank() }
                    Log.i("WaveStudio", "Import preset file success: ${presetName ?: "unnamed"}")
                    showCenterToast(
                        context,
                        if (presetName != null) resources.getString(R.string.preset_import_success_named, presetName)
                        else resources.getString(R.string.preset_import_success),
                    )
                }
            }
        } catch (t: Throwable) {
            showCenterToast(context, resources.getString(R.string.preset_import_failed_with_message, t.message ?: ""))
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        audioViewModel.importAudioAsInput(context, uri)
    }

    fun shareRecording(clip: RecordedClip) {
        try {
                if (clip.fileURL.startsWith("content://")) {
                    val uri = android.net.Uri.parse(clip.fileURL)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mimeForRecording(clip.fileName)
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, resources.getString(R.string.share_title_recording)))
            } else {
                val file = File(clip.fileURL)
                if (!file.exists()) return
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mimeForRecording(file.name)
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, resources.getString(R.string.share_title_recording)))
            }
        } catch (_: Throwable) {
        }
    }

    val recordingSampleRateOptions = remember { listOf(16000, 22050, 32000, 44100, 48000) }

    val importedPlaybackPositionMs by audioViewModel.importedPlaybackPositionMs.collectAsStateWithLifecycle()
    val importedPlaybackDurationMs by audioViewModel.importedPlaybackDurationMs.collectAsStateWithLifecycle()
    val importedSignalPaused by audioViewModel.importedSignalPaused.collectAsStateWithLifecycle()

    var showImportedAudioControllerDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.useImportedSignal) {
        if (!state.useImportedSignal) showImportedAudioControllerDialog = false
    }

    fun openPresetShareDialog() {
        presetShareName = "oscope_preset"
        presetShareDialog = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(settingsScroll, enabled = !eqGraphDragging)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Main start/stop action: always keep start/stop behavior here. The imported-audio
            // controller is opened from the import button (see below) per user request.
            val mainActionText = when {
                state.useTestSignal || state.isRunning -> stringResource(R.string.action_stop)
                else -> stringResource(R.string.action_start)
            }
            val mainActionColor = when {
                state.useTestSignal || state.isRunning -> Color(0xFFC62828)
                else -> Color(0xFF2E7D32)
            }
            Button(
                onClick = { actions.onStartStopAction() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = mainActionColor,
                ),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(vertical = 9.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(mainActionText, fontSize = 15.sp)
            }

        if (state.engineError != null) {
            Text(
                text = stringResource(R.string.error_prefix_with_message, state.engineError ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFC62828),
                modifier = Modifier.fillMaxWidth(),
            )
        }

            Button(
                onClick = actions.onRecordAction,
                enabled = state.isRunning || state.useImportedSignal,
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(vertical = 9.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isRecording) stringResource(R.string.action_stop) else stringResource(R.string.action_record), fontSize = 15.sp)
            }

            Button(
                onClick = actions.onMonitoringToggle,
                enabled = state.isRunning || state.useImportedSignal,
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(vertical = 9.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isMonitoring) stringResource(R.string.monitor_off) else stringResource(R.string.monitor_on), fontSize = 15.sp)
            }

            Button(
                onClick = { importAudioLauncher.launch(arrayOf("audio/*")) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(vertical = 9.dp),
                modifier = Modifier.weight(1f),
                enabled = !state.isImportingAudio,
            ) {
                Text(
                    if (state.isImportingAudio) stringResource(R.string.importing) else stringResource(R.string.load_audio),
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = { showRecordList = true },
                enabled = state.recordings.isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Icon(painter = painterResource(id = R.drawable.ic_list), contentDescription = stringResource(R.string.list_title)) }
        }

        if (state.useImportedSignal) {
            ImportedAudioProgressBar(
                positionMs = importedPlaybackPositionMs,
                durationMs = importedPlaybackDurationMs,
                isPaused = importedSignalPaused,
                audioLabel = state.importedAudioLabel,
                onSeek = { audioViewModel.seekImportedSignalTo(it) },
                onTogglePause = { audioViewModel.toggleImportedSignalPause() },
                onStop = { audioViewModel.stopImportedSignalInput() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClickToEditNumberText(
                    text = stringResource(R.string.low_pass_value_hz, formatLowPassHz(lowPassDisplayHz)),
                    initialText = formatLowPassHz(state.lowPassCutoff),
                    title = stringResource(R.string.low_pass_set_title),
                    unit = "Hz",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.let { snapLowPassHz(it) } },
                    onValue = { hz ->
                        lpDragging = false
                        val snapped = snapLowPassHz(hz)
                        audioViewModel.updateLowPassSlider(snapped)
                        lpFreq01 = hzToSliderBlend(snapped, 600f, 30001f, linearWeight = 0.5f)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        InfoIconButton(
                            stringResource(R.string.low_pass_info_title),
                            stringResource(R.string.low_pass_info_message),
                        )
                    },
                )
                Switch(
                    checked = state.lowPassEnabled,
                    onCheckedChange = actions.onToggleLowPass,
                )
            }
            FilterOrderSelector(
                order = state.lowPassOrder,
                orderOptions = (1..8).toList(),
                onOrderChange = actions.onSetLowPassOrder,
            )
        }

        LowHighPassRow(
            freq01 = lpFreq01,
            onFreq01Change = {
                lpDragging = true
                lpFreq01 = it
            },
            onFreq01ChangeFinished = {
                lpDragging = false
                audioViewModel.updateLowPassSlider(snapLowPassHz(sliderToHzBlend(lpFreq01, 600f, 30001f, linearWeight = 0.5f)))
            },
        )
        for (stageIndex in 1 until state.lowPassOrder) {
            FilterStageCutoffControl(
                stageIndex = stageIndex,
                cutoffHz = state.lowPassStageCutoffs.getOrElse(stageIndex) {
                    state.lowPassStageCutoffs.lastOrNull() ?: state.lowPassCutoff
                },
                toSlider = {
                    hzToSliderBlend(it, 600f, 30001f, linearWeight = 0.5f)
                },
                fromSlider = {
                    sliderToHzBlend(it, 600f, 30001f, linearWeight = 0.5f)
                },
                snap = ::snapLowPassHz,
                format = ::formatLowPassHz,
                onCutoffChange = {
                    actions.onSetLowPassStageCutoff(stageIndex, it)
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClickToEditNumberText(
                    text = stringResource(R.string.high_pass_value_hz, formatHighPassHz(highPassDisplayHz)),
                    initialText = formatHighPassHz(state.highPassCutoff),
                    title = stringResource(R.string.high_pass_set_title),
                    unit = "Hz",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.let { snapHighPassHz(it) } },
                    onValue = { hz ->
                        hpDragging = false
                        val snapped = snapHighPassHz(hz)
                        audioViewModel.updateHighPassSlider(snapped)
                        hpFreq01 = hzToSlider(snapped, 20f, 10000f)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        InfoIconButton(
                            stringResource(R.string.high_pass_info_title),
                            stringResource(R.string.high_pass_info_message),
                        )
                    },
                )
                Switch(
                    checked = state.highPassEnabled,
                    onCheckedChange = actions.onToggleHighPass,
                )
            }
            FilterOrderSelector(
                order = state.highPassOrder,
                orderOptions = (1..8).toList(),
                onOrderChange = actions.onSetHighPassOrder,
            )
        }

        LowHighPassRow(
            freq01 = hpFreq01,
            onFreq01Change = {
                hpDragging = true
                hpFreq01 = it
            },
            onFreq01ChangeFinished = {
                hpDragging = false
                audioViewModel.updateHighPassSlider(snapHighPassHz(sliderToHz(hpFreq01, 20f, 10000f)))
            },
        )
        for (stageIndex in 1 until state.highPassOrder) {
            FilterStageCutoffControl(
                stageIndex = stageIndex,
                cutoffHz = state.highPassStageCutoffs.getOrElse(stageIndex) {
                    state.highPassStageCutoffs.lastOrNull() ?: state.highPassCutoff
                },
                toSlider = { hzToSlider(it, 20f, 10000f) },
                fromSlider = { sliderToHz(it, 20f, 10000f) },
                snap = ::snapHighPassHz,
                format = ::formatHighPassHz,
                onCutoffChange = {
                    actions.onSetHighPassStageCutoff(stageIndex, it)
                },
            )
        }

        EqPanel(
            enabled = state.eqEnabled,
            draggable = eqDraggable,
            onEnabledChange = actions.onSetEqEnabled,
            bands = state.eqBands,
            onReset = actions.onResetEq,
            onBandEnabled = actions.onSetEqBandEnabled,
            onBandType = actions.onSetEqBandType,
            onBandFreq = actions.onSetEqBandFreq,
            onBandGainDb = actions.onSetEqBandGainDb,
            onBandQ = actions.onSetEqBandQ,
            logToSlider = ::hzToSlider,
            sliderToLog = ::sliderToHz,
            onGraphDragging = actions.onSetEqGraphDragging,
            filterGain = state.filterGain,
            lowPassEnabled = state.lowPassEnabled,
            lowPassCutoff = state.lowPassCutoff,
            lowPassStageCutoffs = state.lowPassStageCutoffs,
            lowPassOrder = state.lowPassOrder,
            highPassEnabled = state.highPassEnabled,
            highPassCutoff = state.highPassCutoff,
            highPassStageCutoffs = state.highPassStageCutoffs,
            highPassOrder = state.highPassOrder,
            sampleRate = 44100,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        run {
            val gainDb = gainToDb(state.filterGain)
            val displayGainDb = rememberDisplayLowPass(gainDb, resetKey = "filterGainDb", alpha = 0.44f, snapThreshold = 0.03f)
            val displayGainX = dbToGain(displayGainDb)
            val gainDbText = String.format(Locale.US, "%+.1f", displayGainDb)
            val gainXText = if (displayGainX < 1f) String.format(Locale.US, "%.2f", displayGainX) else String.format(Locale.US, "%.1f", displayGainX)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ClickToEditNumberText(
                    text = stringResource(R.string.processed_gain_value, gainDbText, gainXText),
                    initialText = String.format(Locale.US, "%.2f", gainDb),
                    title = stringResource(R.string.processed_gain_set_title),
                    unit = "dB",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(-20f, 40f) },
                    onValue = { db -> actions.onUpdateFilterGain(dbToGain(db).coerceIn(0.1f, 100f)) },
                    style = MaterialTheme.typography.bodyLarge,
                contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        InfoIconButton(
                            stringResource(R.string.processed_gain_info_title),
                            stringResource(R.string.processed_gain_info_message),
                        )
                    },
                )
                ResetIconButton(
                    onClick = { actions.onUpdateFilterGain(dbToGain(0f).coerceIn(0.1f, 100f)) },
                )
            }

            val gainDbMin = -16f
            val gainDbMax = 32.05f
            val powerCurve = 2.5f
            val linearWeight = 0.65f

            fun dbToSlider(db: Float): Float {
                val d = db.coerceIn(gainDbMin, gainDbMax)
                val normTarget = (d - gainDbMin) / (gainDbMax - gainDbMin)
                var lo = 0f
                var hi = 1f
                repeat(20) {
                    val mid = (lo + hi) * 0.5f
                    val midNorm = mid.pow(powerCurve) * (1f - linearWeight) + mid * linearWeight
                    if (midNorm < normTarget) lo = mid else hi = mid
                }
                return (lo + hi) * 0.5f
            }

            fun sliderToDb(p01: Float): Float {
                val p = p01.coerceIn(0f, 1f)
                val norm = p.pow(powerCurve) * (1f - linearWeight) + p * linearWeight
                return gainDbMin + norm * (gainDbMax - gainDbMin)
            }

            val v01 = dbToSlider(gainDb)
            OscopeSlider(
                value = v01,
                onValueChange = { p -> actions.onUpdateFilterGain(dbToGain(sliderToDb(p)).coerceIn(0.1f, 100f)) },
                valueRange = 0f..1f,
                steps = 0,
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        run {
            val displayWindowMs = rememberDisplayLowPass(state.windowMs, resetKey = "windowMs", alpha = 0.44f, snapThreshold = 0.05f)
            val winText = if (displayWindowMs < 10f) String.format(Locale.US, "%.1f", displayWindowMs) else String.format(Locale.US, "%.0f", displayWindowMs)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ClickToEditNumberText(
                    text = stringResource(R.string.time_window_value_ms, winText),
                    initialText = winText,
                    title = stringResource(R.string.time_window_set_title),
                    unit = "ms",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(5f, 120f) },
                    onValue = actions.onUpdateTimeSlider,
                    style = MaterialTheme.typography.bodyLarge,
                contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        InfoIconButton(
                            stringResource(R.string.time_window_info_title),
                            stringResource(R.string.time_window_info_message),
                        )
                    },
                )
                ResetIconButton(
                    onClick = { actions.onUpdateTimeSlider(20f) },
                )
            }

            val p01 = hzToSlider(state.windowMs, 5f, 120f)
            OscopeSlider(
                value = p01,
                onValueChange = { p -> actions.onUpdateTimeSlider(sliderToHz(p, 5f, 120f)) },
                valueRange = 0f..1f,
                steps = 180,
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth(),
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
                    val file = exportCurrentPresetToCache(context, audioViewModel, presetShareName)
                    if (file != null) {
                        showCenterToast(context, resources.getString(R.string.preset_share_success_named, file.name))
                        shareAnyFile(context, file, "application/json")
                    } else {
                        showCenterToast(context, resources.getString(R.string.preset_share_failed))
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
                    showCenterToast(context, resources.getString(R.string.preset_restore_default_done))
                    presetResetConfirmDialog = false
                },
            )
        }

        RecordingsListDialog(
            visible = showRecordList,
            recordings = state.recordings,
            playbackPositionMs = state.playbackPositionMs,
            playbackDurationMs = state.playbackDurationMs,
            playingId = state.playingId,
            onSeek = { pos -> audioViewModel.seekPlaybackTo(pos) },
            onDismiss = { showRecordList = false },
            onPlayClick = { clip ->
                if (clip.id == state.playingId) {
                    audioViewModel.stopPlayback()
                } else {
                    audioViewModel.playRecording(context, clip)
                }
            },
            onShareClick = { shareRecording(it) },
            onRenameClick = {
                renameTarget = it
                renameText = it.fileName.substringBeforeLast('.')
            },
            onDeleteClick = { deleteTarget = it },
            onBatchDelete = { list -> list.forEach { audioViewModel.deleteRecording(it.id) } },
            recentlyDeletedClips = state.recentlyDeletedRecordings,
            onRestore = { clip ->
                audioViewModel.restoreRecording(clip.id)
            },
            onPermanentDelete = { clip ->
                audioViewModel.permanentlyDeleteRecording(clip.id)
            },
            onEmptyTrash = {
                state.recentlyDeletedRecordings.forEach { clip ->
                    audioViewModel.permanentlyDeleteRecording(clip.id)
                }
            },
        )

        RenameRecordingDialog(
            clip = renameTarget,
            renameText = renameText,
            onRenameTextChange = { renameText = it },
            onDismiss = { renameTarget = null },
            onConfirm = { clip, name ->
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    audioViewModel.renameRecording(clip.id, trimmed)
                }
                renameTarget = null
            },
        )

        DeleteRecordingDialog(
            clip = deleteTarget,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                audioViewModel.deleteRecording(it.id)
                deleteTarget = null
            },
        )

        // ---- 导入进度弹窗 ----
        ImportProgressDialog(
            visible = state.isImportingAudio,
            progress = state.importProgress,
            importedAudioLabel = state.importedAudioLabel,
            onCancel = { audioViewModel.cancelImport() },
        )

        // ---- 导入结果提示 ----
        LaunchedEffect(state.importResultMessage) {
            val msg = state.importResultMessage ?: return@LaunchedEffect
            showCenterToast(context, msg)
            audioViewModel.clearImportResultMessage()
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        run {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.test_mode_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                Box {
                    OutlinedButton(
                        onClick = actions.onToggleVvvfTestSignal,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (state.useTestSignal) stringResource(R.string.test_mode_running) else stringResource(R.string.test_mode_start))
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    InfoIconButton(
                        stringResource(R.string.test_mode_info_title),
                        stringResource(R.string.test_mode_info_message),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.test_waveform_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                Box {
                    OutlinedButton(
                        onClick = { testSignalMenu = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(stringResource(state.testSignalPreset.labelResId))
                    }
                    DropdownMenu(
                        expanded = testSignalMenu,
                        onDismissRequest = { testSignalMenu = false },
                    ) {
                        AudioEngineViewModel.TestSignalPreset.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelResId)) },
                                onClick = {
                                    testSignalMenu = false
                                    actions.onSetTestSignalPreset(option)
                                },
                            )
                        }
                    }
                }
            }
        }
        // custom recording storage moved to the compact settings menu (see OscopeApp)
    }
}







