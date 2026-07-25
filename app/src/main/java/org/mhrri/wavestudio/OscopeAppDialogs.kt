package org.mhrri.wavestudio
 
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestoreFromTrash

@Composable
internal fun PresetShareDialog(
    visible: Boolean,
    presetShareName: String,
    onPresetShareNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetShareName,
                    onValueChange = onPresetShareNameChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_file_name_label)) },
                    supportingText = { Text(stringResource(R.string.preset_file_name_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.action_share_direct))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun PresetResetConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_restore_default_title)) },
        text = { Text(stringResource(R.string.preset_restore_default_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun ImportProgressDialog(
    visible: Boolean,
    progress: Float,
    importedAudioLabel: String?,
    onCancel: () -> Unit,
) {
    if (!visible) return

    val percent = (progress * 100f).toInt().coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_audio_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = importedAudioLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.import_audio_decoding),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun RecordingsListDialog(
    visible: Boolean,
    recordings: List<RecordedClip>,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    playingId: String?,
    onSeek: (Long) -> Unit = {},
    onDismiss: () -> Unit,
    onPlayClick: (RecordedClip) -> Unit,
    onShareClick: (RecordedClip) -> Unit,
    onRenameClick: (RecordedClip) -> Unit,
    onDeleteClick: (RecordedClip) -> Unit,
    onBatchShare: (List<RecordedClip>) -> Unit = { list -> list.forEach(onShareClick) },
    onBatchDelete: (List<RecordedClip>) -> Unit = { list -> list.forEach(onDeleteClick) },
    recentlyDeletedClips: List<RecordedClip> = emptyList(),
    onRestore: (RecordedClip) -> Unit = {},
    onPermanentDelete: (RecordedClip) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
) {
    if (!visible) return

    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentTab by remember { mutableStateOf(0) } // 0 = Recordings, 1 = Recently Deleted

    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }
    var pendingPermanentDeletes by remember { mutableStateOf<List<RecordedClip>>(emptyList()) }
    var isClearingAllTrash by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.recordings_list_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (isEditMode) {
                                    selectedIds = emptySet()
                                }
                                isEditMode = !isEditMode
                            }) {
                                Text(
                                    text = stringResource(
                                        if (isEditMode) R.string.common_done else R.string.action_edit
                                    ),
                                )
                            }

                            if (currentTab == 0 && recentlyDeletedClips.isNotEmpty()) {
                                TextButton(onClick = {
                                    isEditMode = false
                                    selectedIds = emptySet()
                                    currentTab = 1
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.RestoreFromTrash,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.recordings_recently_deleted_button, recentlyDeletedClips.size),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }

                            if (currentTab == 1) {
                                TextButton(onClick = {
                                    isEditMode = false
                                    selectedIds = emptySet()
                                    currentTab = 0
                                }) {
                                    Text(text = stringResource(R.string.recordings_list_title))
                                }
                            }

                            IconButton(onClick = onDismiss) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_expand_less),
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (currentTab == 0) {
                        if (isEditMode) {
                            val sortedRecordings = recordings.sortedByDescending { it.date }
                            val allSelected = sortedRecordings.isNotEmpty() &&
                                    sortedRecordings.all { it.id in selectedIds }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    selectedIds = if (allSelected) emptySet()
                                    else sortedRecordings.map { it.id }.toSet()
                                }) {
                                    Text(
                                        text = stringResource(
                                            if (allSelected) R.string.action_deselect_all
                                            else R.string.action_select_all
                                        ),
                                    )
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedRecordings.filter { it.id in selectedIds }
                                        onBatchShare(selected)
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_share),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedRecordings.filter { it.id in selectedIds }
                                        onBatchDelete(selected)
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_delete),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        }

                        RecordingsListView(
                            recordings = recordings.sortedByDescending { it.date },
                            onItemClick = { },
                            onPlayClick = onPlayClick,
                            onShareClick = onShareClick,
                            onRenameClick = onRenameClick,
                            onDeleteClick = onDeleteClick,
                            playingPositionMs = playbackPositionMs,
                            playingDurationMs = playbackDurationMs,
                            onSeek = onSeek,
                            playingId = playingId,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            isEditMode = isEditMode,
                            selectedIds = selectedIds,
                            onToggleSelect = { id ->
                                selectedIds = if (id in selectedIds)
                                    selectedIds - id
                                else
                                    selectedIds + id
                            },
                        )
                    } else {
                        if (isEditMode) {
                            val sortedDeleted = recentlyDeletedClips.sortedByDescending { it.date }
                            val allSelected = sortedDeleted.isNotEmpty() &&
                                    sortedDeleted.all { it.id in selectedIds }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = {
                                    selectedIds = if (allSelected) emptySet()
                                    else sortedDeleted.map { it.id }.toSet()
                                }) {
                                    Text(
                                        text = stringResource(
                                            if (allSelected) R.string.action_deselect_all
                                            else R.string.action_select_all
                                        ),
                                    )
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedDeleted.filter { it.id in selectedIds }
                                        selected.forEach(onRestore)
                                        selectedIds = emptySet()
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_restore),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }

                                TextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = {
                                        val selected = sortedDeleted.filter { it.id in selectedIds }
                                        pendingPermanentDeletes = selected
                                        isClearingAllTrash = false
                                        showPermanentDeleteConfirm = true
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_permanently_delete),
                                        color = if (selectedIds.isNotEmpty())
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        }

                        if (recentlyDeletedClips.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.recently_deleted_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 16.dp),
                            ) {
                                items(recentlyDeletedClips, key = { it.id }) { clip ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .clickable(enabled = isEditMode) {
                                                selectedIds = if (clip.id in selectedIds)
                                                    selectedIds - clip.id
                                                else
                                                    selectedIds + clip.id
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isEditMode) {
                                            Checkbox(
                                                checked = clip.id in selectedIds,
                                                onCheckedChange = {
                                                    selectedIds = if (clip.id in selectedIds)
                                                        selectedIds - clip.id
                                                    else
                                                        selectedIds + clip.id
                                                },
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = clip.fileName,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = clip.dateText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!isEditMode) {
                                            IconButton(onClick = { onRestore(clip) }) {
                                                Icon(
                                                    imageVector = Icons.Default.RestoreFromTrash,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            IconButton(onClick = {
                                                pendingPermanentDeletes = listOf(clip)
                                                isClearingAllTrash = false
                                                showPermanentDeleteConfirm = true
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isEditMode) {
                                TextButton(
                                    onClick = {
                                        isClearingAllTrash = true
                                        showPermanentDeleteConfirm = true
                                    },
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(end = 8.dp, bottom = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.permanently_delete_all),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showPermanentDeleteConfirm) {
        AlertDialog(
            onDismissRequest = {
                showPermanentDeleteConfirm = false
                pendingPermanentDeletes = emptyList()
                isClearingAllTrash = false
            },
            title = {
                Text(
                    text = if (isClearingAllTrash)
                        stringResource(R.string.permanently_delete_all)
                    else
                        stringResource(R.string.action_permanently_delete)
                )
            },
            text = {
                Text(
                    text = if (isClearingAllTrash)
                        stringResource(R.string.permanently_delete_all_confirm)
                    else if (pendingPermanentDeletes.size == 1)
                        stringResource(R.string.permanently_delete_confirm, pendingPermanentDeletes.first().fileName)
                    else
                        stringResource(R.string.permanently_delete_batch_confirm, pendingPermanentDeletes.size)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isClearingAllTrash) {
                            onEmptyTrash()
                        } else {
                            pendingPermanentDeletes.forEach(onPermanentDelete)
                        }
                        showPermanentDeleteConfirm = false
                        pendingPermanentDeletes = emptyList()
                        isClearingAllTrash = false
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermanentDeleteConfirm = false
                        pendingPermanentDeletes = emptyList()
                        isClearingAllTrash = false
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    }
}

@Composable
internal fun RenameRecordingDialog(
    clip: RecordedClip?,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (RecordedClip, String) -> Unit,
) {
    val target = clip ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                singleLine = true,
                label = { Text(stringResource(R.string.file_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target, renameText) },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun DeleteRecordingDialog(
    clip: RecordedClip?,
    onDismiss: () -> Unit,
    onConfirm: (RecordedClip) -> Unit,
) {
    val target = clip ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_recording_title)) },
        text = { Text(stringResource(R.string.delete_recording_confirm, target.fileName)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(target) }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun StartupNoteDialog(
    visible: Boolean,
    startupNoteText: String,
    doNotShowStartupNoteAgain: Boolean,
    onDoNotShowChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
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
                            onValueChange = onDoNotShowChanged,
                        )
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = doNotShowStartupNoteAgain,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.startup_note_do_not_show),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.startup_note_ack)) }
        },
    )
}

// Data classes for About dialog sections
internal data class AboutDialogAboutSection(val title: String, val bullets: List<String>)
internal data class ContributorSection(val title: String, val members: List<String>, val past: Boolean = false)

@Composable
internal fun AboutDialog(
    visible: Boolean,
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onShowStartupNote: () -> Unit,
) {
    if (!visible) return

    val context = androidx.compose.ui.platform.LocalContext.current
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showContributorsDialog by remember { mutableStateOf(false) }
    var showCommunityDialog by remember { mutableStateOf(false) }
    var showWebsiteConfirm by remember { mutableStateOf(false) }

    val appTitle = stringResource(R.string.about_app_title)
    val appVersion = try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${pkgInfo.versionName}"
    } catch (_: Exception) {
        ""
    }
    val aboutHint = stringResource(R.string.about_hint)
    val aboutWebsiteUrl = stringResource(R.string.about_website_url_new)
    // Pre-compute strings needed in non-Composable onClick lambdas
    val presetUnavailableMsg = stringResource(R.string.about_preset_download_unavailable)
    val websiteRedirectMsg = stringResource(R.string.about_website_redirect_confirm, aboutWebsiteUrl)

    val aboutSections = listOf(
        "0.18.1" to R.array.about_changelog_v0181,
        "0.18.0" to R.array.about_changelog_v0180,
        "0.17.0" to R.array.about_changelog_v0170,
        "0.16.1" to R.array.about_changelog_v0161,
        "0.16.0" to R.array.about_changelog_v0160,
        "0.15.3.1" to R.array.about_changelog_v01531,
        "0.15.3" to R.array.about_changelog_v0153,
        "0.15.1" to R.array.about_changelog_v0151,
        "0.15.0" to R.array.about_changelog_v0150,
        "0.14.1" to R.array.about_changelog_v0141,
        "0.14.0" to R.array.about_changelog_v0140,
        "0.13.0" to R.array.about_changelog_v0130,
        "0.11.5" to R.array.about_changelog_v0115,
        "0.11.4" to R.array.about_changelog_v0114,
        "0.11.3" to R.array.about_changelog_v0113,
    ).map { (version, arrayResId) ->
        AboutDialogAboutSection(
            title = stringResource(R.string.about_changelog_version_title, version),
            bullets = context.resources.getStringArray(arrayResId).toList()
        )
    }

    // Structured contributor sections (extensible: add new sections / members here)
    val contributorSections = listOf(
        ContributorSection(
            title = stringResource(R.string.about_dev_section_main),
            members = context.resources.getStringArray(R.array.about_dev_main_members).toList(),
        ),
        ContributorSection(
            title = stringResource(R.string.about_dev_section_testers),
            members = context.resources.getStringArray(R.array.about_dev_tester_members).toList(),
        ),
        ContributorSection(
            title = stringResource(R.string.about_dev_section_icon_design),
            members = context.resources.getStringArray(R.array.about_dev_icon_design_members).toList(),
        ),
        ContributorSection(
            title = stringResource(R.string.about_dev_section_english_proofreading),
            members = context.resources.getStringArray(R.array.about_dev_english_proofreading_members).toList(),
        ),
        ContributorSection(
            title = stringResource(R.string.about_dev_section_past_contributors),
            members = context.resources.getStringArray(R.array.about_dev_past_contributors_members).toList(),
            past = true,
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.about_title))
                TextButton(
                    onClick = {
                        onDismiss()
                        onShowStartupNote()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.startup_note_show_button),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // 1. App icon + name + version
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // App icon with border, adapts to dark/light mode
                    val isDark = isSystemInDarkTheme()
                    Surface(
                        modifier = Modifier
                            .size(64.dp)
                            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                    ) {
                        Image(
                            painter = painterResource(
                                if (isDark) R.drawable.ic_launcher_dark else R.drawable.ic_launcher_light
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = appTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${stringResource(R.string.about_version_label)}: $appVersion",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 3. Separator (with vertical margin)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                // 4. Usage tips (prominent red warning)
                Text(
                    text = stringResource(R.string.about_usage_tips_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.Red,
                )
                Text(
                    text = aboutHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Red,
                )

                // 5. Bottom buttons – Row 1: 更新日志 | 开发人员名单
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showChangelogDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.about_changelog_title),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { showContributorsDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.about_contributors_button),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showCommunityDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.about_community_button),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        enabled = false,
                    ) {
                        Text(
                            text = stringResource(R.string.about_preset_download_button),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )

    // Full-screen changelog dialog
    if (showChangelogDialog) {
        ChangelogFullScreenDialog(
            aboutSections = aboutSections,
            onDismiss = { showChangelogDialog = false },
        )
    }

    // Full-screen contributors dialog
    if (showContributorsDialog) {
        ContributorsFullScreenDialog(
            sections = contributorSections,
            onDismiss = { showContributorsDialog = false },
        )
    }

    // Community dialog (QQ + Discord clipboard)
    if (showCommunityDialog) {
        CommunityDialog(
            qqGroupNumber = stringResource(R.string.about_qq_group_number),
            onDismiss = { showCommunityDialog = false },
        )
    }

    // Website redirect confirmation dialog
    if (showWebsiteConfirm) {
        AlertDialog(
            onDismissRequest = { showWebsiteConfirm = false },
            title = { Text(stringResource(R.string.about_website_redirect_title)) },
            text = { Text(websiteRedirectMsg) },
            confirmButton = {
                TextButton(onClick = {
                    showWebsiteConfirm = false
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, aboutWebsiteUrl.toUri())
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebsiteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChangelogFullScreenDialog(
    aboutSections: List<AboutDialogAboutSection>,
    onDismiss: () -> Unit,
) {
    var animatedVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { animatedVisible = true }

    val dismissWithAnimation: () -> Unit = { animatedVisible = false }

    LaunchedEffect(animatedVisible) {
        if (!animatedVisible) {
            kotlinx.coroutines.delay(350)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = dismissWithAnimation,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = animatedVisible,
            enter = fadeIn(animationSpec = tween(300)) +
                    slideInVertically(animationSpec = tween(300)) { it },
            exit = fadeOut(animationSpec = tween(300)) +
                    slideOutVertically(animationSpec = tween(300)) { it },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismissWithAnimation,
                    ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.about_changelog_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(onClick = dismissWithAnimation) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            aboutSections.forEachIndexed { idx, section ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    section.bullets.forEach { bullet ->
                                        AboutBullet(bullet, bulletColor = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (idx < aboutSections.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        thickness = 0.5.dp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorsFullScreenDialog(
    sections: List<ContributorSection>,
    onDismiss: () -> Unit,
) {
    var animatedVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { animatedVisible = true }

    val dismissWithAnimation: () -> Unit = { animatedVisible = false }

    LaunchedEffect(animatedVisible) {
        if (!animatedVisible) {
            kotlinx.coroutines.delay(350)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = dismissWithAnimation,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = animatedVisible,
            enter = fadeIn(animationSpec = tween(300)) +
                    slideInVertically(animationSpec = tween(300)) { it },
            exit = fadeOut(animationSpec = tween(300)) +
                    slideOutVertically(animationSpec = tween(300)) { it },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismissWithAnimation,
                    ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.about_contributors_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(onClick = dismissWithAnimation) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            sections.filter { it.members.isNotEmpty() }.forEach { section ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    section.members.forEach { member ->
                                        AboutBullet(member, color = if (section.past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityDialog(
    qqGroupNumber: String,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copiedToast = stringResource(R.string.about_copied_toast)
    val qqLabel = stringResource(R.string.about_qq_group_label)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_community_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // QQ section
                Text(
                    text = qqLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(qqLabel, qqGroupNumber)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = qqGroupNumber,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.about_qq_copy_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun AboutBullet(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, bulletColor: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "–",
            style = MaterialTheme.typography.bodyMedium,
            color = bulletColor,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
internal fun ExitConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exit_title)) },
        text = { Text(stringResource(R.string.exit_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirmExit) { Text(stringResource(R.string.exit_title)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
internal fun RecentlyDeletedDialog(
    visible: Boolean,
    recentlyDeletedClips: List<RecordedClip>,
    onDismiss: () -> Unit,
    onRestore: (RecordedClip) -> Unit,
    onPermanentDelete: (RecordedClip) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.recordings_recently_deleted_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_expand_more),
                                contentDescription = stringResource(R.string.common_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (recentlyDeletedClips.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.recently_deleted_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                        ) {
                            items(recentlyDeletedClips, key = { it.id }) { clip ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = clip.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = clip.dateText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { onRestore(clip) }) {
                                        Icon(
                                            imageVector = Icons.Default.RestoreFromTrash,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = { onPermanentDelete(clip) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = onEmptyTrash,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 8.dp, bottom = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.permanently_delete_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
