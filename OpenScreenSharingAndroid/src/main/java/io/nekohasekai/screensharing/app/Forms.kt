package io.nekohasekai.screensharing.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass

@Composable
fun FormDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val fullScreen = !currentWindowAdaptiveInfo().windowSizeClass.isAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        DialogSystemBars(fullScreen)
        CompositionLocalProvider(LocalTopBarController provides null, LocalScaffoldPadding provides PaddingValues()) {
            if (fullScreen) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, content = content)
            } else {
                val colors = PageColors(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.surfaceContainerHigh, floating = true)
                CompositionLocalProvider(LocalPageColors provides colors) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            Modifier
                                .safeDrawingPadding()
                                .padding(24.dp)
                                .widthIn(max = 560.dp)
                                .heightIn(max = 760.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = colors.background,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleContent: @Composable () -> Unit = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    if (LocalTopBarController.current == null) {
        val background = pageColors.background
        Scaffold(
            topBar = {
                TopAppBar(
                    title = titleContent,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                )
            },
            containerColor = background,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .then(scrollModifier)
                    .padding(top = 8.dp, bottom = 16.dp),
                content = content,
            )
        }
    } else {
        OverrideTopBar {
            TopAppBar(title = titleContent, navigationIcon = navigationIcon, actions = actions)
        }
        val scaffoldPadding = LocalScaffoldPadding.current
        val layoutDirection = LocalLayoutDirection.current
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = scaffoldPadding.calculateStartPadding(layoutDirection), end = scaffoldPadding.calculateEndPadding(layoutDirection))
                .imePadding()
                .then(scrollModifier)
                .padding(top = scaffoldPadding.calculateTopPadding() + 8.dp, bottom = scaffoldPadding.calculateBottomPadding() + 8.dp),
            content = content,
        )
    }
}

@Composable
fun FormButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
