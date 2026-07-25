package org.mhrri.wavestudio

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.PI
import java.util.Locale
import androidx.core.content.edit


// Note: Shared utility functions (toEnglishOrdinal, rememberDisplayLowPass, computeEqResponse)
// are now in OscopeUIUtils.kt to avoid duplication

@SuppressLint("SuspiciousIndentation")
@Composable
fun ImmersiveScreen(
    modifier: Modifier,
    setLandscape: (Boolean) -> Unit,
    landscapeLocked: Boolean,
    onToggleLock: () -> Unit,
    audioViewModel: AudioEngineViewModel,
    filteredWaveform: StateFlow<FloatArray>,
    useTestSignal: Boolean,
    filteredDisplayScale: Float,
    showRefWaveforms: Boolean,
    onToggleShowRef: () -> Unit,
    showDebugInfo: Boolean,
    onToggleShowDebugInfo: () -> Unit,
    ampMin: Float,
    ampMax: Float,
    windowMinMs: Float,
    windowMaxMs: Float,
    gestureOverlayVisible: Boolean,
    onGestureOverlayVisible: (Boolean) -> Unit,
    gestureAmp: Float,
    onGestureAmp: (Float) -> Unit,
    gestureWindow: Float,
    onGestureWindow: (Float) -> Unit,
    gestureMode: Int,
    onGestureMode: (Int) -> Unit,
    waveformSpanMs: Float,
    ampScale: Float,
    onAmpScale: (Float) -> Unit,
    onWindowMs: (Float) -> Unit,
    onTriggerEnabled: (Boolean) -> Unit,
) {
    // Display-only snapping (keeps gesture feel continuous).
    fun snapForDisplay(v: Float): Float {
        val clamped = v.coerceIn(ampMin, ampMax)
        val step = when {
            clamped < 1f -> 0.05f
            clamped < 2f -> 0.1f
            clamped < 5f -> 0.2f
            clamped < 10f -> 0.5f
            else -> 1f
        }
        return (round(clamped / step) * step).coerceIn(ampMin, ampMax)
    }

    var settingsMenuExpanded by remember { mutableStateOf(false) }
    val currentFilteredDisplayScale by rememberUpdatedState(filteredDisplayScale)
    val currentGestureWindow by rememberUpdatedState(gestureWindow)
    val currentGestureMode by rememberUpdatedState(gestureMode)
    val currentAmpScale by rememberUpdatedState(ampScale)
    val filteredSamples by filteredWaveform.collectAsStateWithLifecycle()
    val vmTriggerResultValue by audioViewModel.triggerResult.collectAsStateWithLifecycle()
    val vmTriggeredWindowValue by audioViewModel.triggeredWindow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val triggerPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    // ===== 触发开关：开/关（开启时使用升沿 + 自相关辅助） =====
    fun parseTriggerMode(name: String): SimpleTriggerEngine.Mode = when (name) {
        SimpleTriggerEngine.Mode.OFF.name -> SimpleTriggerEngine.Mode.OFF
        SimpleTriggerEngine.Mode.FALLING.name -> SimpleTriggerEngine.Mode.FALLING
        else -> SimpleTriggerEngine.Mode.RISING // Migrates legacy persisted "ON" values.
    }

    val triggerModeNameInitial = remember(triggerPrefs) {
        triggerPrefs.getString(KEY_TRIGGER_MODE_NAME, SimpleTriggerEngine.Mode.OFF.name)
            ?: SimpleTriggerEngine.Mode.OFF.name
    }
    var triggerModeName by rememberSaveable { mutableStateOf(triggerModeNameInitial) }

    LaunchedEffect(
        triggerModeName,
    ) {
        triggerPrefs.edit {
            putString(KEY_TRIGGER_MODE_NAME, triggerModeName)
        }
    }
    val triggerMode = parseTriggerMode(triggerModeName)

    // Inform view model about trigger enabled state when UI mode changes.
    LaunchedEffect(triggerMode) {
        onTriggerEnabled(triggerMode != SimpleTriggerEngine.Mode.OFF)
    }

    fun nextTriggerModeName(name: String): String = when (name) {
        SimpleTriggerEngine.Mode.OFF.name -> SimpleTriggerEngine.Mode.RISING.name
        else -> SimpleTriggerEngine.Mode.OFF.name
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(landscapeLocked, ampMin, ampMax, windowMinMs, windowMaxMs) {
                if (landscapeLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onGestureOverlayVisible(true)
                        onGestureAmp(currentAmpScale)
                        onGestureWindow(currentGestureWindow)
                        onGestureMode(0)
                    },
                    onDragEnd = {
                        onGestureOverlayVisible(false)
                        onGestureMode(0)
                    },
                    onDragCancel = {
                        onGestureOverlayVisible(false)
                        onGestureMode(0)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        val dx = dragAmount.x
                        val dy = dragAmount.y

                        val invHF = (1.0 / ((size.height.toFloat()).let { if (it > 1f) it else 1f }).toDouble()).toFloat()
                        val invWF = (1.0 / ((size.width.toFloat()).let { if (it > 1f) it else 1f }).toDouble()).toFloat()

                        var mode = currentGestureMode
                        if (mode == 0) {
                            val ax = abs(dx)
                            val ay = abs(dy)
                            val thresholdPx = 6f
                            if (ax + ay > thresholdPx) {
                                mode = if (ay > ax * 1.2f) 1 else if (ax > ay * 1.2f) 2 else 0
                                onGestureMode(mode)
                            }
                        }

                        when (mode) {
                            1 -> {
                                val dy01 = (-(dy) * invHF).coerceIn(-1f, 1f)
                                val k = 2.5f
                                val factor = exp(dy01 * k)
                                val nextAmp = (currentFilteredDisplayScale * factor).coerceIn(ampMin, ampMax)
                                onGestureAmp(snapForDisplay(nextAmp))
                                onAmpScale(nextAmp)
                            }
                            2 -> {
                                val dx01 = (dx * invWF).coerceIn(-1f, 1f)
                                val baseStepMs = 60f
                                val accel = (currentGestureWindow / 40f).coerceIn(0.5f, 14f)
                                val deltaMs = (dx01) * baseStepMs * accel
                                val nextWindow = (currentGestureWindow + deltaMs).coerceIn(windowMinMs, windowMaxMs)
                                onGestureWindow(nextWindow)
                                onWindowMs(nextWindow)
                            }
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
        ) {
            val immersiveRef = filteredDisplayScale.coerceAtLeast(1e-4f)

            val displaySamples = if (triggerMode != SimpleTriggerEngine.Mode.OFF) {
                if (vmTriggeredWindowValue.isNotEmpty()) vmTriggeredWindowValue else filteredSamples
            } else {
                filteredSamples
            }

            WaveformView(
                samples = displaySamples,
                ampScale = filteredDisplayScale,
                lineColor = Color.White,
                modifier = Modifier.fillMaxSize(),
                showReferenceWhenBelow1x = showRefWaveforms,
                referenceAmpNormalized = immersiveRef,
                referenceColor = Color(0x44FFFFFF),
                referenceDashed = true,
                lineWidthDp = 2f,
            )

            val latestTriggerResult = vmTriggerResultValue
                // Always show trigger status when trigger is on
                if (triggerMode != SimpleTriggerEngine.Mode.OFF) {
                    val statusText = if (latestTriggerResult != null) {
                        "${"%.1f".format(latestTriggerResult.freqHz)}Hz c=${"%.2f".format(latestTriggerResult.confidence)} ${if (latestTriggerResult.locked) "LOCK" else "..."} a=${latestTriggerResult.anchorIndex} p=${latestTriggerResult.periodSamples} m=${latestTriggerResult.mode}"
                    } else "WAIT (null)"
                    Text(
                        text = "TRG $statusText",
                        color = Color(0xFF4FC3F7),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color(0x88000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            if (triggerMode != SimpleTriggerEngine.Mode.OFF && latestTriggerResult != null && showDebugInfo) {
                val conf = String.format(Locale.US, "%.2f", latestTriggerResult.confidence)
                val hz = String.format(Locale.US, "%.1f", latestTriggerResult.freqHz)
                Text(
                    text = "TRG=ON, f=${hz}Hz, per=${latestTriggerResult.periodSamples}, a=${latestTriggerResult.anchorIndex}, lock=${latestTriggerResult.locked}, c=$conf",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (!landscapeLocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { setLandscape(false) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.mode_normal), color = Color.White) }

                Spacer(Modifier.width(10.dp))

                Box {
                    OutlinedIconButton(
                        onClick = { settingsMenuExpanded = true },
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings_custom),
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = settingsMenuExpanded,
                        onDismissRequest = { settingsMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                               onClick = {},
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.trigger_label))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = triggerMode != SimpleTriggerEngine.Mode.OFF,
                                        onCheckedChange = { on ->
                                            triggerModeName = if (on) {
                                                SimpleTriggerEngine.Mode.RISING.name
                                            } else {
                                                SimpleTriggerEngine.Mode.OFF.name
                                            }
                                        }
                                    )
                                }
                            },
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
                                        onCheckedChange = null
                                    )
                                }
                            },
                            onClick = onToggleShowRef
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.show_debug_info))
                                    Spacer(Modifier.weight(1f))
                                    Switch(
                                        checked = showDebugInfo,
                                        onCheckedChange = null
                                    )
                                }
                            },
                            onClick = onToggleShowDebugInfo
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {}
        }


        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            OutlinedButton(
                onClick = onToggleLock,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(if (landscapeLocked) stringResource(R.string.unlock) else stringResource(R.string.lock), color = Color.White)
            }
        }

        if (gestureOverlayVisible && !landscapeLocked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (gestureMode) {
                    1 -> Text(
                        text = stringResource(R.string.amp_scale_overlay, String.format(Locale.US, "%.2f", gestureAmp)),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    2 -> Text(
                        text = stringResource(
                            R.string.time_window_overlay,
                            if (gestureWindow < 10f) String.format(Locale.US, "%.1f", gestureWindow) else String.format(Locale.US, "%.0f", gestureWindow)
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun LiveWaveformView(
    samplesFlow: StateFlow<FloatArray>,
    ampScale: Float,
    lineColor: Color,
    showReference: Boolean,
    referenceAmpNormalized: Float,
    referenceColor: Color,
    modifier: Modifier = Modifier,
) {
    val samples by samplesFlow.collectAsStateWithLifecycle()
    WaveformView(
        samples = samples,
        ampScale = ampScale,
        lineColor = lineColor,
        showReferenceWhenBelow1x = showReference,
        referenceAmpNormalized = referenceAmpNormalized,
        referenceColor = referenceColor,
        referenceDashed = true,
        modifier = modifier,
    )
}

@Composable
fun CaptureDiagnosticsLine(
    audioViewModel: AudioEngineViewModel,
    modifier: Modifier = Modifier,
) {
    val audioInputAlive by audioViewModel.audioInputAlive.collectAsStateWithLifecycle()
    val lastReadSamples by audioViewModel.lastReadSamples.collectAsStateWithLifecycle()
    val lastMaxAbsPcm by audioViewModel.lastMaxAbsPcm.collectAsStateWithLifecycle()
    val waveformFps by audioViewModel.publishedWaveformFps.collectAsStateWithLifecycle()

    Text(
        text = "wf_fps=${
            String.format(
                Locale.US,
                "%.1f",
                waveformFps
            )
        } read=$lastReadSamples max=$lastMaxAbsPcm/32767",
        style = MaterialTheme.typography.bodySmall,
        color = if (audioInputAlive) Color(0xFF2E7D32) else Color(0xFFC62828),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun ClickToEditNumberText(
    text: String,
    initialText: String,
    title: String,
    unit: String,
    parseAndClamp: (String) -> Float?,
    onValue: (Float) -> Unit,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    var editText by remember(showDialog) { mutableStateOf(initialText) }

    Row(
        modifier = modifier
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (tightAnnotated, tightStyle) = remember(text, style) {
            buildTightDigitText(text, style)
        }
        Text(
            text = tightAnnotated,
            style = tightStyle,
            modifier = Modifier.clickable { showDialog = true }
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            trailingIcon.invoke()
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = true,
                    label = { Text(unit) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = parseAndClamp(editText)
                        if (v != null) onValue(v)
                        showDialog = false
                    }
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Build an AnnotatedString that tightens spacing of digit runs by applying
 * a negative overall letterSpacing to a base [style] and then restoring
 * normal spacing for non-digit runs.
 */
private fun buildTightDigitText(text: String, style: TextStyle): Pair<AnnotatedString, TextStyle> {
    val tightOverall = style.copy(letterSpacing = (-0.03).em)
    val annotated = buildAnnotatedString {
        for (ch in text) {
            if (ch.isDigit()) {
                append(ch.toString())
            } else {
                withStyle(SpanStyle(letterSpacing = 0.sp)) {
                    append(ch.toString())
                }
            }
        }
    }
    return annotated to tightOverall
}

@Composable
fun InfoIconButton(title: String, message: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "i",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF6A5ACD))
                .clickable { expanded = true }
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, (-4).dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .background(Color(0xFFF7F3FF), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.Black)
                Text(message, style = MaterialTheme.typography.bodySmall, color = Color.Black)
            }
        }
    }
}

@Composable
fun LowHighPassRow(
    freq01: Float,
    onFreq01Change: (Float) -> Unit,
    onFreq01ChangeFinished: () -> Unit,
) {
    OscopeSlider(
        value = freq01.coerceIn(0f, 1f),
        onValueChange = onFreq01Change,
        onValueChangeFinished = onFreq01ChangeFinished,
        valueRange = 0f..1f,
        steps = 220,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun FilterOrderSelector(
    order: Int,
    orderOptions: List<Int>,
    onOrderChange: (Int) -> Unit,
) {
    var orderMenu by remember { mutableStateOf(false) }
    val currentLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0)?.language ?: ""
    val useEnglishOrdinal = currentLanguage.startsWith("en")
    val minO = orderOptions.minOrNull() ?: 1
    val maxO = orderOptions.maxOrNull() ?: 8

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            IconButton(
                onClick = { onOrderChange((order - 1).coerceAtLeast(minO)) },
                modifier = Modifier.size(24.dp)
            ) { Text("-", style = MaterialTheme.typography.bodySmall) }

            TextButton(
                onClick = { orderMenu = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                val orderLabel = if (useEnglishOrdinal) {
                    stringResource(R.string.filter_order_ordinal_format, toEnglishOrdinal(order))
                } else {
                    stringResource(R.string.filter_order_format, order)
                }
                Text(orderLabel, style = MaterialTheme.typography.bodySmall)
            }

            IconButton(
                onClick = { onOrderChange((order + 1).coerceAtMost(maxO)) },
                modifier = Modifier.size(24.dp)
            ) { Text("+", style = MaterialTheme.typography.bodySmall) }
        }

        DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
            for (o in orderOptions) {
                DropdownMenuItem(
                    text = {
                        val optionLabel = if (useEnglishOrdinal) {
                            stringResource(R.string.filter_order_ordinal_format, toEnglishOrdinal(o))
                        } else {
                            stringResource(R.string.filter_order_format, o)
                        }
                        Text(optionLabel, style = MaterialTheme.typography.bodyMedium)
                    },
                    onClick = {
                        orderMenu = false
                        onOrderChange(o)
                    }
                )
            }
        }
    }
}

@Composable
fun EqPanel(
    enabled: Boolean,
    draggable: Boolean = false,
    onEnabledChange: (Boolean) -> Unit,
    bands: List<AudioEngineViewModel.EqBand>,
    onReset: () -> Unit,
    onBandEnabled: (id: Int, enabled: Boolean) -> Unit,
    onBandType: (id: Int, type: AudioEngineViewModel.EqBandType) -> Unit,
    onBandFreq: (id: Int, hz: Float) -> Unit,
    onBandGainDb: (id: Int, db: Float) -> Unit,
    onBandQ: (id: Int, q: Float) -> Unit,
    logToSlider: (v: Float, min: Float, max: Float) -> Float,
    sliderToLog: (v01: Float, min: Float, max: Float) -> Float,
    onGraphDragging: (Boolean) -> Unit,
    filterGain: Float,
    lowPassEnabled: Boolean,
    lowPassCutoff: Float,
    lowPassStageCutoffs: List<Float>,
    lowPassOrder: Int,
    highPassEnabled: Boolean,
    highPassCutoff: Float,
    highPassStageCutoffs: List<Float>,
    highPassOrder: Int,
    sampleRate: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var expanded by rememberSaveable { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eq_panel_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = enabled,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        ),
                        contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
                    )
                }
            }

            ResetIconButton(
                onClick = onReset,
                enabled = enabled,
                contentDescriptionRes = R.string.eq_reset_all
            )
        }

        if (!enabled || !expanded) return

        val freqMin = 20f
        val freqMax = 20000f
        val gainMin = -40f
        val gainMax = 40f
        val qMin = 0.2f

        var selectedId by rememberSaveable { mutableStateOf(bands.firstOrNull()?.id ?: 0) }
        LaunchedEffect(bands) {
            if (bands.none { it.id == selectedId } && bands.isNotEmpty()) selectedId = bands.first().id
        }
        val sel = bands.firstOrNull { it.id == selectedId } ?: return
        val qMax = 6f
        val displaySelFreq = rememberDisplayLowPass(sel.freqHz, resetKey = "eq-freq-${sel.id}", alpha = 0.44f, snapThreshold = 0.2f)
        val displaySelGainDb = rememberDisplayLowPass(sel.gainDb, resetKey = "eq-gain-${sel.id}", alpha = 0.44f, snapThreshold = 0.03f)
        val displaySelQ = rememberDisplayLowPass(
            AudioEngineViewModel.clampEqQForBand(sel.type, sel.gainDb, sel.q),
            resetKey = "eq-q-${sel.id}",
            alpha = 0.44f,
            snapThreshold = 0.01f
        )

        EqResponseGraph(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            bands = bands,
            freqMin = freqMin,
            freqMax = freqMax,
            gainMin = gainMin,
            gainMax = gainMax,
            selectedId = selectedId,
            lowPassEnabled = lowPassEnabled,
            lowPassCutoff = lowPassCutoff,
            lowPassStageCutoffs = lowPassStageCutoffs,
            lowPassOrder = lowPassOrder,
            highPassEnabled = highPassEnabled,
            highPassCutoff = highPassCutoff,
            highPassStageCutoffs = highPassStageCutoffs,
            highPassOrder = highPassOrder,
            filterGain = filterGain,
            sampleRate = sampleRate,
            draggable = draggable,
            onBandFreq = onBandFreq,
            onBandGainDb = onBandGainDb,
                onBandSelect = { selectedId = it },
            onGraphDragging = onGraphDragging,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (b in bands) {
                val isSel = b.id == selectedId
                if (isSel) {
                    FilledTonalButton(
                        onClick = { selectedId = b.id },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(b.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = { selectedId = b.id },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(b.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.common_enabled), style = MaterialTheme.typography.bodySmall)
                Switch(checked = sel.enabled, onCheckedChange = { onBandEnabled(sel.id, it) })
            }
        }

        // ===== NEW: band mode selector (keeps original 4-band config, allows choosing PEAK/LOW/HIGH shelf) =====
        run {
            var modeMenu by remember(sel.id) { mutableStateOf(false) }
            val modeLabel = when (sel.type) {
                AudioEngineViewModel.EqBandType.PEAK -> "Peak"
                AudioEngineViewModel.EqBandType.LOW_SHELF -> "Low Shelf"
                AudioEngineViewModel.EqBandType.HIGH_SHELF -> "High Shelf"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClickToEditNumberText(
                    text = stringResource(R.string.eq_band_freq_value, sel.label, String.format(Locale.US, "%.0f", displaySelFreq)),
                    initialText = String.format(Locale.US, "%.0f", sel.freqHz),
                    title = stringResource(R.string.eq_band_freq_title, sel.label),
                    unit = "Hz",
                    parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(freqMin, freqMax) },
                    onValue = { v -> onBandFreq(sel.id, v) },
                    style = MaterialTheme.typography.bodyMedium,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f)
                )

                // Compact mode selector inline
                Box {
                    OutlinedButton(
                        onClick = { modeMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(modeLabel, style = MaterialTheme.typography.bodySmall)
                    }

                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Low Shelf") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.LOW_SHELF)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Peak") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.PEAK)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("High Shelf") },
                            onClick = {
                                modeMenu = false
                                onBandType(sel.id, AudioEngineViewModel.EqBandType.HIGH_SHELF)
                            }
                        )
                    }
                }

                ResetIconButton(
                    onClick = {
                        val defaultFreq = when (sel.id) {
                            0 -> 200f
                            1 -> 800f
                            2 -> 2000f
                            3 -> 5000f
                            else -> 1000f
                        }
                        onBandFreq(sel.id, defaultFreq)
                    },
                    modifier = Modifier.size(32.dp),
                    iconRes = R.drawable.ic_eq_reset_custom,
                )
            }
        }

        OscopeSlider(
            value = logToSlider(sel.freqHz, freqMin, freqMax),
            onValueChange = { onBandFreq(sel.id, sliderToLog(it, freqMin, freqMax)) },
            valueRange = 0f..1f,
            steps = 240,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClickToEditNumberText(
                        text = stringResource(R.string.eq_band_gain_value, String.format(Locale.US, "%+.1f", displaySelGainDb)),
                        initialText = String.format(Locale.US, "%.1f", sel.gainDb),
                        title = stringResource(R.string.eq_band_gain_title, sel.label),
                        unit = "dB",
                        parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(gainMin, gainMax) },
                        onValue = { v -> onBandGainDb(sel.id, v) },
                        style = MaterialTheme.typography.bodyMedium,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            InfoIconButton(
                                stringResource(R.string.processed_gain_info_title),
                                stringResource(R.string.processed_gain_info_message)
                            )
                        }
                    )
                    ResetIconButton(
                        onClick = { onBandGainDb(sel.id, 0f) },
                        modifier = Modifier.size(32.dp),
                        iconRes = R.drawable.ic_eq_reset_custom,
                    )
                }
                OscopeSlider(
                    value = run {
                        val gainPowCurve = 1.5f
                        val signed = if (sel.gainDb >= 0f) {
                            (sel.gainDb / gainMax).coerceIn(0f, 1f)
                        } else {
                            -(((-sel.gainDb) / (-gainMin)).coerceIn(0f, 1f))
                        }
                        val linearSigned = if (signed >= 0f) {
                            signed.pow(1f / gainPowCurve)
                        } else {
                            -((-signed).pow(1f / gainPowCurve))
                        }
                        ((linearSigned + 1f) * 0.5f).coerceIn(0f, 1f)
                    },
                    onValueChange = {
                        val gainPowCurve = 1.5f
                        val linearSigned = (it * 2f - 1f).coerceIn(-1f, 1f)
                        val curvedSigned = if (linearSigned >= 0f) {
                            linearSigned.pow(gainPowCurve)
                        } else {
                            -((-linearSigned).pow(gainPowCurve))
                        }
                        val db = if (curvedSigned >= 0f) {
                            (curvedSigned * gainMax).coerceIn(0f, gainMax)
                        } else {
                            -((-curvedSigned) * (-gainMin)).coerceIn(0f, -gainMin)
                        }
                        onBandGainDb(sel.id, db)
                    },
                    valueRange = 0f..1f,
                    steps = 120,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(modifier = Modifier.weight(2f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClickToEditNumberText(
                        text = "Q ${String.format(Locale.US, "%.2f", displaySelQ)}",
                        initialText = String.format(Locale.US, "%.2f", sel.q),
                        title = stringResource(R.string.eq_band_q_title, sel.label),
                        unit = "Q",
                        parseAndClamp = { s -> s.trim().replace(",", ".").toFloatOrNull()?.coerceIn(qMin, qMax) },
                        onValue = { v -> onBandQ(sel.id, v) },
                        style = MaterialTheme.typography.bodyMedium,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f)
                    )
                    ResetIconButton(
                        onClick = { onBandQ(sel.id, AudioEngineViewModel.DEFAULT_EQ_Q) },
                        modifier = Modifier.size(32.dp),
                        iconRes = R.drawable.ic_eq_reset_custom,
                    )
                }
                OscopeSlider(
                    value = logToSlider(sel.q, qMin, qMax),
                    onValueChange = { onBandQ(sel.id, sliderToLog(it, qMin, qMax)) },
                    valueRange = 0f..1f,
                    steps = 120,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        LaunchedEffect(Unit) { onGraphDragging(false) }
    }
}

@Composable
fun ResetIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(34.dp),
    enabled: Boolean = true,
    contentDescriptionRes: Int = R.string.common_reset,
    iconRes: Int = R.drawable.ic_reset_settings,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = stringResource(contentDescriptionRes)
        )
    }
}

@SuppressLint("SuspiciousIndentation")
@Composable
fun EqResponseGraph(
    modifier: Modifier,
    bands: List<AudioEngineViewModel.EqBand>,
    freqMin: Float,
    freqMax: Float,
    gainMin: Float,
    gainMax: Float,
    selectedId: Int,
    lowPassEnabled: Boolean,
    lowPassCutoff: Float,
    lowPassStageCutoffs: List<Float>,
    lowPassOrder: Int,
    highPassEnabled: Boolean,
    highPassCutoff: Float,
    highPassStageCutoffs: List<Float>,
    highPassOrder: Int,
    filterGain: Float,
    sampleRate: Int,
    draggable: Boolean = false,
    onBandFreq: (id: Int, hz: Float) -> Unit = { _, _ -> },
    onBandGainDb: (id: Int, db: Float) -> Unit = { _, _ -> },
    onBandSelect: (Int) -> Unit = {},
    onGraphDragging: (Boolean) -> Unit = {},
) {
    // Theme-aware colors
    val curveColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val selectedColor = MaterialTheme.colorScheme.primary

        Canvas(modifier = modifier
            .pointerInput(draggable, selectedId) {
                if (!draggable) return@pointerInput
                // ~24px ≈ 8–12dp gesture threshold to disambiguate scroll from EQ drag.
                // If the drag never exceeds this distance, treat as a tap (no-op here).
                val slopPx = 16f
                // |dy| > 2.0 × |dx| → treat as vertical scroll intent, let parent handle.
                val vertRatio = 2.5f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var committed = false
                    val startX = down.position.x
                    val startY = down.position.y
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            if (committed) onGraphDragging(false)
                            break
                        }
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        val dist = sqrt(dx * dx + dy * dy)
                        if (!committed) {
                            if (dist < slopPx) continue
                            if (abs(dy) > abs(dx) * vertRatio) {
                                // Vertical-dominant → treat as scroll, pass through
                                break
                            }
                            committed = true
                            onGraphDragging(true)
                        }
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val x = change.position.x.coerceIn(0f, w)
                        val y = change.position.y.coerceIn(0f, h)
                        val lmin = ln(freqMin)
                        val lmax = ln(freqMax)
                        val newLf = lmin + (x / w) * (lmax - lmin)
                        val newFreq = exp(newLf).coerceIn(freqMin, freqMax)
                        onBandFreq(selectedId, newFreq)
                        val newT = 1f - (y / h)
                        val newGainDb = (gainMin + newT * (gainMax - gainMin)).coerceIn(gainMin, gainMax)
                        onBandGainDb(selectedId, newGainDb)
                    } while (true)
                }
            }
            .pointerInput(draggable) {
                if (!draggable) return@pointerInput
                    detectTapGestures { offset ->
                        if (bands.isEmpty()) return@detectTapGestures
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                
                    val sorted = bands.sortedBy { it.freqHz }
                    var nearestId = sorted.first().id
                    var nearestDist = Float.MAX_VALUE
                
                    for (b in sorted) {
                        val lf = ln(b.freqHz.coerceIn(freqMin, freqMax))
                        val lmin_ = ln(freqMin)
                        val lmax_ = ln(freqMax)
                        val bx = ((lf - lmin_) / (lmax_ - lmin_)).toFloat() * w
                        val t = ((b.gainDb - gainMin) / (gainMax - gainMin)).coerceIn(0f, 1f)
                        val by = h * (1f - t)
                        val dx = offset.x - bx
                        val dy = offset.y - by
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist < nearestDist) {
                            nearestDist = dist
                            nearestId = b.id
                        }
                    }
                
                    if (nearestDist < minOf(w, h) * 0.3f) {
                        onBandSelect(nearestId)
                    }
                }
            }
        ) {
        val w = size.width
        val h = size.height

        fun xForFreq(f: Float): Float {
            val lf = ln(f.coerceIn(freqMin, freqMax))
            val lmin = ln(freqMin)
            val lmax = ln(freqMax)
            return ((lf - lmin) / (lmax - lmin)).toFloat() * w
        }

        fun yForDb(db: Float): Float {
            val t = ((db - gainMin) / (gainMax - gainMin)).coerceIn(0f, 1f)
            return (h * (1f - t))
        }

        val sorted = bands.sortedBy { it.freqHz }

        // 0dB reference line (still useful)
        val y0 = yForDb(0f)
        drawLine(
            color = gridColor,
            start = Offset(0f, y0),
            end = Offset(w, y0),
            strokeWidth = 2f
        )

        // Build frequency axis (log-spaced)
        val nPoints = 240
        val freqs = FloatArray(nPoints + 1) { i ->
            val frac = i / nPoints.toFloat()
            exp(ln(freqMin) + (ln(freqMax) - ln(freqMin)) * frac).toFloat()
        }

        // Compute combined EQ response (dB) across freqs using RBJ peaking formula for each enabled band
        val respDb = computeEqResponse(
            bands = sorted,
            freqs = freqs,
            lowPassEnabled = lowPassEnabled,
            lowPassCutoff = lowPassCutoff,
            lowPassStageCutoffs = lowPassStageCutoffs,
            lowPassOrder = lowPassOrder,
            highPassEnabled = highPassEnabled,
            highPassCutoff = highPassCutoff,
            highPassStageCutoffs = highPassStageCutoffs,
            highPassOrder = highPassOrder,
            filterGain = filterGain,
            sampleRate = sampleRate,
        )

        // Draw smooth curve using the computed dB values
        val curvePath = Path()
        for (i in respDb.indices) {
            val x = xForFreq(freqs[i])
            val y = yForDb(respDb[i])
            if (i == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
        }
        drawPath(path = curvePath, color = curveColor.copy(alpha = 0.90f), style = Stroke(width = 3f))

        // Draw dots at band centers for visual anchors
        for (b in sorted) {
            val pt = Offset(xForFreq(b.freqHz), yForDb(b.gainDb))
            val isSel = b.id == selectedId
            drawCircle(
                color = if (isSel) selectedColor else dotColor,
                radius = if (isSel) 8f else 6f,
                center = pt
            )
        }
    }
}
