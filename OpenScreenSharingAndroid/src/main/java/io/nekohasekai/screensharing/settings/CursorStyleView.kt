package io.nekohasekai.screensharing.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.CursorStyle
import io.nekohasekai.screensharing.session.CursorTheme
import io.nekohasekai.screensharing.session.CursorThemeStore
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private const val PREVIEW_SIZE = 28f

@Composable
internal fun CursorStyleView(navigationIcon: @Composable () -> Unit) {
    val resources = LocalResources.current
    var alert by remember { mutableStateOf<AlertState?>(null) }

    fun importThemes(uris: List<Uri>) {
        if (uris.isEmpty()) return
        App.scope.launch {
            var failure: Pair<String, Throwable>? = null
            for (uri in uris) {
                try {
                    CursorThemeStore.importFile(uri)
                } catch (error: Exception) {
                    failure = failure ?: (CursorThemeStore.displayName(uri) to error)
                }
            }
            failure?.let { (name, error) ->
                alert = AlertState.failure(resources.getString(R.string.settings_couldnt_import, name), error)
            }
        }
    }

    fun delete(theme: CursorTheme) {
        try {
            CursorThemeStore.delete(theme)
        } catch (error: Exception) {
            alert = AlertState.failure(resources.getString(R.string.settings_couldnt_delete, theme.name), error)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importThemes(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> importThemes(listOfNotNull(uri)) }

    PageScaffold(title = stringResource(R.string.settings_cursor_style), navigationIcon = navigationIcon) {
        CursorPreview()
        ListGroup(Modifier.selectableGroup()) {
            StyleRow(CursorStyle.System, stringResource(R.string.settings_system_style))
            StyleRow(CursorStyle.Remote, stringResource(R.string.settings_remote_style))
        }
        SectionHeader(stringResource(R.string.settings_custom))
        ListGroup {
            Box {
                var isChoosing by remember { mutableStateOf(false) }
                val isImporting = CursorThemeStore.isImporting
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_import)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                    trailingContent = if (isImporting) {
                        { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    colors = groupItemColors(),
                    modifier = Modifier.clickable(enabled = !isImporting) { isChoosing = true },
                )
                DropdownMenu(expanded = isChoosing, onDismissRequest = { isChoosing = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_import_files)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            isChoosing = false
                            pickFiles.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_import_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        onClick = {
                            isChoosing = false
                            pickFolder.launch(null)
                        },
                    )
                }
            }
            Column(Modifier.selectableGroup()) {
                for (theme in CursorThemeStore.themes) {
                    key(theme.id) {
                        ThemeRow(theme, onDelete = { delete(theme) })
                    }
                }
            }
        }
    }
    AlertStateDialog(alert) { alert = null }
}

@Composable
private fun CursorPreview() {
    val density = LocalDensity.current.density
    val images: List<Pair<ImageBitmap, Float>>? = when (val style = AppSettings.cursorStyle) {
        CursorStyle.System -> emptyList()
        CursorStyle.Remote -> null
        is CursorStyle.Theme -> {
            val theme = CursorThemeStore.theme(style.id)
            remember(theme, density) {
                theme?.previewImages(density)?.map { it.image.asImageBitmap() to it.scale }
            }
        }
    }
    if (images == null) return
    ListGroup {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (images.isEmpty()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SystemArrow() }
            }
            for ((image, scale) in images) {
                val width = image.width / scale
                val height = image.height / scale
                val factor = min(1f, PREVIEW_SIZE / max(width, height))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Image(
                        image,
                        contentDescription = null,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.size((width * factor).dp, (height * factor).dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SystemArrow() {
    val arrow = remember {
        Path().apply {
            moveTo(1f, 1f)
            lineTo(1f, 17f)
            lineTo(5f, 13.2f)
            lineTo(7.6f, 19.2f)
            lineTo(10.2f, 18.1f)
            lineTo(7.7f, 12.2f)
            lineTo(13f, 12.2f)
            close()
        }
    }
    Canvas(Modifier.size(14.dp, 20.dp)) {
        scale(size.width / 14f, size.height / 20f, pivot = Offset.Zero) {
            drawPath(arrow, Color.Black)
            drawPath(arrow, Color.White, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StyleRow(style: CursorStyle, label: String) {
    val isSelected = AppSettings.cursorStyle == style
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
        colors = groupItemColors(),
        modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton) { AppSettings.cursorStyle = style },
    )
}

@Composable
private fun ThemeRow(theme: CursorTheme, onDelete: () -> Unit) {
    val style = CursorStyle.Theme(theme.id)
    val isSelected = AppSettings.cursorStyle == style
    var isShowingMenu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(theme.name) },
            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
            colors = groupItemColors(),
            modifier = Modifier
                .semantics {
                    selected = isSelected
                    role = Role.RadioButton
                }
                .contextMenuGestures(
                    onClick = { AppSettings.cursorStyle = style },
                    onContextMenu = { isShowingMenu = true },
                ),
        )
        DropdownMenu(expanded = isShowingMenu, onDismissRequest = { isShowingMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                onClick = {
                    isShowingMenu = false
                    onDelete()
                },
            )
        }
    }
}
