package io.nekohasekai.screensharing.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.ChoiceDialog
import io.nekohasekai.screensharing.app.ListGroup
import io.nekohasekai.screensharing.app.PageScaffold
import io.nekohasekai.screensharing.app.SectionFooter
import io.nekohasekai.screensharing.app.SectionHeader
import io.nekohasekai.screensharing.app.SwitchRow
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.app.slideInFromLeft
import io.nekohasekai.screensharing.app.slideInFromRight
import io.nekohasekai.screensharing.app.slideOutToLeft
import io.nekohasekai.screensharing.app.slideOutToRight
import io.nekohasekai.screensharing.model.AppDirectory
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.CursorStyle
import io.nekohasekai.screensharing.model.KeyMapping
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.KeyShortcuts
import io.nekohasekai.screensharing.model.MappingTarget
import io.nekohasekai.screensharing.model.QuickInput
import io.nekohasekai.screensharing.model.QuickInputs
import io.nekohasekai.screensharing.model.ShortcutGroup
import io.nekohasekai.screensharing.session.CursorThemeStore
import io.nekohasekai.screensharing.sessionui.SessionTutorial
import io.nekohasekai.screensharing.sessionui.SessionTutorialView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val SOURCE_CODE = "https://github.com/nekohasekai/open-screen-sharing"

fun NavGraphBuilder.settingsDestinations(navController: NavController) {
    val back: @Composable () -> Unit = { BackButton { navController.navigateUp() } }
    composable("settings") {
        SettingsScreen(navController)
    }
    composable(
        "settings/app",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        AppSettingsView(back)
    }
    composable(
        "settings/picture",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        PictureSettingsView(back)
    }
    composable(
        "settings/keyboard",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        KeyboardView(
            navigationIcon = back,
            onOpenGroup = { navController.navigate("settings/keyboard/shortcuts/${it.name}") },
            onEditMapping = { target, id ->
                navController.navigate(if (id == null) "settings/keyboard/mapping/new/${target.name}" else "settings/keyboard/mapping/edit/${target.name}/$id")
            },
        )
    }
    composable(
        "settings/keyboard/shortcuts/{group}",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) { entry ->
        val group = ShortcutGroup.entries.firstOrNull { it.name == entry.arguments?.getString("group") }
        if (group == null) {
            LaunchedEffect(Unit) { navController.navigateUp() }
        } else {
            ShortcutGroupView(group, back)
        }
    }
    composable(
        "settings/keyboard/mapping/new/{target}",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) { entry ->
        val target = MappingTarget.entries.firstOrNull { it.name == entry.arguments?.getString("target") }
        val mapping = remember { KeyMapping(UUID.randomUUID(), KeyShortcut.unset, emptyList()) }
        if (target == null) {
            LaunchedEffect(Unit) { navController.navigateUp() }
        } else {
            KeyMappingEditor(mapping, target, isNew = true) { navController.navigateUp() }
        }
    }
    composable(
        "settings/keyboard/mapping/edit/{target}/{id}",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) { entry ->
        val target = MappingTarget.entries.firstOrNull { it.name == entry.arguments?.getString("target") }
        val id = entry.arguments?.getString("id")
        val mapping = target?.let { KeyShortcuts.mappings(it).firstOrNull { mapping -> mapping.id.toString() == id } }
        if (target == null || mapping == null) {
            LaunchedEffect(Unit) { navController.navigateUp() }
        } else {
            KeyMappingEditor(mapping, target, isNew = false) { navController.navigateUp() }
        }
    }
    composable(
        "settings/quick_input",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        QuickInputPage(navigationIcon = back) { input ->
            navController.navigate(if (input == null) "settings/quick_input/new" else "settings/quick_input/edit/${input.id}")
        }
    }
    composable(
        "settings/quick_input/new",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        val input = remember { QuickInput(UUID.randomUUID(), "", emptyList()) }
        QuickInputEditor(input, isNew = true, navigationIcon = back) { navController.navigateUp() }
    }
    composable(
        "settings/quick_input/edit/{id}",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) { entry ->
        val id = entry.arguments?.getString("id")
        val input = QuickInputs.inputs.firstOrNull { it.id.toString() == id }
        if (input == null) {
            LaunchedEffect(Unit) { navController.navigateUp() }
        } else {
            QuickInputEditor(input, isNew = false, navigationIcon = back) { navController.navigateUp() }
        }
    }
    composable(
        "settings/control",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        ControlSettingsView(back) { navController.navigate("settings/control/cursor_style") }
    }
    composable(
        "settings/control/cursor_style",
        enterTransition = slideInFromRight,
        exitTransition = slideOutToLeft,
        popEnterTransition = slideInFromLeft,
        popExitTransition = slideOutToRight,
    ) {
        CursorStyleView(back)
    }
}

@Composable
private fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var alert by remember { mutableStateOf<AlertState?>(null) }
    PageScaffold(title = stringResource(R.string.settings), navigationIcon = {}) {
        ListGroup {
            SettingsLink(Icons.Outlined.Apps, stringResource(R.string.settings_app)) { navController.navigate("settings/app") }
            SettingsLink(Icons.Outlined.AspectRatio, stringResource(R.string.settings_picture)) { navController.navigate("settings/picture") }
            SettingsLink(Icons.Outlined.Keyboard, stringResource(R.string.settings_keyboard)) { navController.navigate("settings/keyboard") }
            SettingsLink(Icons.AutoMirrored.Outlined.Input, stringResource(R.string.settings_quick_input)) { navController.navigate("settings/quick_input") }
            SettingsLink(Icons.Outlined.Tune, stringResource(R.string.settings_control)) { navController.navigate("settings/control") }
        }
        SectionHeader(stringResource(R.string.settings_about))
        ListGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_source_code)) },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                colors = groupItemColors(),
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE.toUri()))
                    } catch (error: ActivityNotFoundException) {
                        alert = AlertState.failure(resources.getString(R.string.settings_couldnt_open_link), error)
                    }
                },
            )
        }
    }
    AlertStateDialog(alert) { alert = null }
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = groupItemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PictureSettingsView(navigationIcon: @Composable () -> Unit) {
    PageScaffold(title = stringResource(R.string.settings_picture), navigationIcon = navigationIcon) {
        SectionHeader(stringResource(R.string.settings_behavior))
        ListGroup {
            SwitchRow(
                stringResource(R.string.settings_full_screen),
                AppSettings.pictureFillsScreen,
                onCheckedChange = { AppSettings.pictureFillsScreen = it },
                icon = Icons.Outlined.Fullscreen,
            )
        }
        SectionFooter(stringResource(R.string.settings_full_screen_footer))
    }
}

@Composable
private fun ControlSettingsView(navigationIcon: @Composable () -> Unit, onOpenCursorStyle: () -> Unit) {
    PageScaffold(title = stringResource(R.string.settings_control), navigationIcon = navigationIcon) {
        SectionHeader(stringResource(R.string.settings_customization))
        val styleName = when (val style = AppSettings.cursorStyle) {
            CursorStyle.System -> stringResource(R.string.settings_system_style)
            CursorStyle.Remote -> stringResource(R.string.settings_remote_style)
            is CursorStyle.Theme -> CursorThemeStore.theme(style.id)?.name.orEmpty()
        }
        ListGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_cursor_style)) },
                supportingContent = { Text(styleName) },
                leadingContent = { Icon(Icons.Outlined.Mouse, contentDescription = null) },
                colors = groupItemColors(),
                modifier = Modifier.clickable(onClick = onOpenCursorStyle),
            )
        }
        SectionHeader(stringResource(R.string.settings_behavior))
        ListGroup {
            SwitchRow(
                stringResource(R.string.settings_pinch_to_zoom),
                AppSettings.pinchZooms,
                onCheckedChange = { AppSettings.pinchZooms = it },
                icon = Icons.Outlined.ZoomIn,
            )
        }
    }
}

@Composable
private fun AppSettingsView(navigationIcon: @Composable () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var alert by remember { mutableStateOf<AlertState?>(null) }
    var replayedTutorials by remember { mutableStateOf(emptyList<SessionTutorial>()) }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { AppDirectory.cacheSize() }
    }

    PageScaffold(title = stringResource(R.string.settings_app), navigationIcon = navigationIcon) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ListGroup {
                LanguageRow()
            }
        }
        SectionHeader(stringResource(R.string.settings_cache))
        val size = cacheSize
        ListGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_cache_size)) },
                supportingContent = if (size == null) null else ({ Text(Formatter.formatFileSize(context, size)) }),
                leadingContent = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                colors = groupItemColors(),
            )
            if (size != null && size > 0L) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                    leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    colors = groupItemColors(destructive = true),
                    modifier = Modifier.clickable {
                        scope.launch {
                            cacheSize = null
                            try {
                                withContext(Dispatchers.IO) { AppDirectory.clearCaches() }
                            } catch (error: Exception) {
                                alert = AlertState.failure(resources.getString(R.string.settings_couldnt_clear_cache), error)
                            }
                            cacheSize = withContext(Dispatchers.IO) { AppDirectory.cacheSize() }
                        }
                    },
                )
            }
        }
        SectionHeader(stringResource(R.string.screen_tips))
        val hasSeenTutorials = AppSettings.seenTutorials.isNotEmpty()
        ListGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_show_tips)) },
                leadingContent = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
                colors = groupItemColors(enabled = hasSeenTutorials),
                modifier = Modifier.clickable(enabled = hasSeenTutorials) {
                    replayedTutorials = SessionTutorial.entries.filter { it.rawValue in AppSettings.seenTutorials }
                },
            )
        }
    }
    AlertStateDialog(alert) { alert = null }
    if (replayedTutorials.isNotEmpty()) {
        SessionTutorialView(replayedTutorials) { replayedTutorials = emptyList() }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRow() {
    var language by remember { mutableStateOf(AppLanguage.selected) }
    var isPicking by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_language)) },
        supportingContent = { Text(language?.let { AppLanguage.name(it) } ?: stringResource(R.string.settings_system_default)) },
        leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
        colors = groupItemColors(),
        modifier = Modifier.clickable { isPicking = true },
    )
    if (isPicking) {
        val available = remember { AppLanguage.available }
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            choices = listOf<Pair<String?, String>>(null to stringResource(R.string.settings_system_default)) +
                available.map { it to AppLanguage.name(it) },
            selected = language,
            onSelect = { code ->
                isPicking = false
                if (code != language) {
                    language = code
                    AppLanguage.select(code)
                }
            },
            onDismiss = { isPicking = false },
        )
    }
}
