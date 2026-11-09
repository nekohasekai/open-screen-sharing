package io.nekohasekai.screensharing.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AlertState
import io.nekohasekai.screensharing.app.LocalScaffoldPadding
import io.nekohasekai.screensharing.app.OverrideTopBar
import io.nekohasekai.screensharing.app.pageColors
import io.nekohasekai.screensharing.discovery.AvailabilityMonitor
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.ComputerLayout
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.session.SessionRoute
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val CardInset = 4.dp
private val CardWidth = 340.dp + CardInset * 2
private val CardSpacing = 20.dp - CardInset * 2
private val RowSpacing = 24.dp - CardInset * 2
private val GridPadding = 12.dp - CardInset
private val FabSpace = 88.dp
private val ListMaxWidth = 840.dp

private data class ItemMenu(val id: UUID, val position: Offset)

private class ComputerActions(
    val connect: (Computer) -> Unit,
    val openInNewWindow: ((Computer) -> Unit)?,
    val edit: (Computer) -> Unit,
    val delete: (Computer) -> Unit,
    val move: (Computer, Int) -> Unit,
    val canMove: (Computer, Int) -> Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputersView(
    connect: (SessionRoute) -> Unit,
    openInNewWindow: ((SessionRoute) -> Unit)?,
    edit: (Computer) -> Unit,
    newConnection: () -> Unit,
    reselections: Int,
) {
    val computers = ComputerStore.computers
    var alert by remember { mutableStateOf<AlertState?>(null) }
    var menu by remember { mutableStateOf<ItemMenu?>(null) }
    var dragOrder by remember { mutableStateOf<List<UUID>?>(null) }

    val servers = remember(computers) {
        buildMap<ComputerAddress, RemoteProtocol> { computers.forEach { putIfAbsent(it.address, it.remoteProtocol) } }
    }
    LaunchedEffect(servers) { AvailabilityMonitor.shared.update(servers) }

    val ordered = orderedComputers(computers, AppSettings.computerOrder)
    val shown = dragOrder?.let { order ->
        ordered.sortedBy { computer -> order.indexOf(computer.id).let { if (it < 0) Int.MAX_VALUE else it } }
    } ?: ordered

    val actions = ComputerActions(
        connect = { connect(SessionRoute(it)) },
        openInNewWindow = openInNewWindow?.let { open -> { open(SessionRoute(it)) } },
        edit = edit,
        delete = { computer ->
            alert = AlertState(
                App.instance.getString(R.string.home_delete_named, computer.displayName),
                actions = listOf(
                    AlertState.Action(App.instance.getString(R.string.delete), AlertState.Role.Destructive) {
                        try {
                            ComputerStore.delete(computer.id)
                        } catch (error: Exception) {
                            alert = AlertState.failure(App.instance.getString(R.string.home_couldnt_delete, computer.displayName), error)
                        }
                    },
                    AlertState.Action(App.instance.getString(R.string.cancel), AlertState.Role.Cancel) {},
                ),
            )
        },
        move = { computer, offset ->
            val ids = shown.map { it.id }.toMutableList()
            val index = ids.indexOf(computer.id)
            if (index >= 0 && index + offset in ids.indices) {
                ids.add(index + offset, ids.removeAt(index))
                AppSettings.computerOrder = ids
            }
        },
        canMove = { computer, offset -> shown.indexOfFirst { it.id == computer.id }.let { it >= 0 && it + offset in shown.indices } },
    )
    val onMove: (Any, Any) -> Unit = { from, to ->
        val ids = (dragOrder ?: shown.map { it.id }).toMutableList()
        val fromIndex = ids.indexOfFirst { it == from }
        val toIndex = ids.indexOfFirst { it == to }
        if (fromIndex >= 0 && toIndex >= 0) {
            ids.add(toIndex, ids.removeAt(fromIndex))
            dragOrder = ids
            menu = null
        }
    }
    val onDragStopped = {
        dragOrder?.let { AppSettings.computerOrder = it }
        dragOrder = null
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.home_connections)) },
            actions = {
                if (AppSettings.computerLayout == ComputerLayout.Grid) {
                    IconButton(onClick = { AppSettings.computerLayout = ComputerLayout.List }) {
                        Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = stringResource(R.string.home_list))
                    }
                } else {
                    IconButton(onClick = { AppSettings.computerLayout = ComputerLayout.Grid }) {
                        Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.home_icons))
                    }
                }
            },
        )
    }

    val padding = LocalScaffoldPadding.current
    val layoutDirection = LocalLayoutDirection.current
    val topPadding = padding.calculateTopPadding()
    val bottomPadding = padding.calculateBottomPadding()
    val horizontalPadding = Modifier.padding(start = padding.calculateStartPadding(layoutDirection), end = padding.calculateEndPadding(layoutDirection))
    Box(Modifier.fillMaxSize()) {
        val container = Modifier
            .fillMaxSize()
            .then(horizontalPadding)
            .dismissMenuOnMove(isShown = { menu != null }, dismiss = { menu = null })
        when {
            shown.isEmpty() -> EmptyView(Modifier.fillMaxSize().padding(padding))
            AppSettings.computerLayout == ComputerLayout.List -> {
                val listState = rememberLazyListState()
                OnReselect(reselections) { listState.animateScrollToItem(0) }
                val reorderState = rememberReorderableLazyListState(listState) { from, to -> onMove(from.key, to.key) }
                Box(container, contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        Modifier
                            .widthIn(max = ListMaxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        state = listState,
                        contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = bottomPadding + FabSpace),
                    ) {
                        itemsIndexed(shown, key = { _, computer -> computer.id }) { index, computer ->
                            ReorderableItem(reorderState, key = computer.id) { isDragging ->
                                ComputerListRow(
                                    computer,
                                    shape = MaterialTheme.shapes.medium.run {
                                        if (isDragging) {
                                            this
                                        } else {
                                            val top = if (index == 0) topStart else ZeroCornerSize
                                            val bottom = if (index == shown.lastIndex) bottomStart else ZeroCornerSize
                                            copy(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
                                        }
                                    },
                                    isDragging = isDragging,
                                    menu = menu?.takeIf { it.id == computer.id },
                                    setMenu = { position -> menu = position?.let { ItemMenu(computer.id, it) } },
                                    actions = actions,
                                    onDragStopped = onDragStopped,
                                )
                            }
                        }
                    }
                }
            }
            else -> BoxWithConstraints(container) {
                val density = LocalDensity.current
                var labelHeight by remember { mutableStateOf(0.dp) }
                val compact = maxWidth < 600.dp
                val margin = (if (compact) 16.dp else 24.dp) - CardInset
                val layout = if (compact) {
                    GridLayout(1, null)
                } else {
                    gridLayout(shown.size, maxWidth - margin * 2, maxHeight - topPadding - bottomPadding - FabSpace - GridPadding * 2, labelHeight)
                }
                val gridState = rememberLazyGridState()
                OnReselect(reselections) { gridState.animateScrollToItem(0) }
                val reorderState = rememberReorderableLazyGridState(gridState) { from, to -> onMove(from.key, to.key) }
                LazyVerticalGrid(
                    columns = ComputerColumns(layout.columns, layout.cardWidth),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = PaddingValues(start = margin, end = margin, top = topPadding + GridPadding, bottom = GridPadding + bottomPadding + FabSpace),
                    verticalArrangement = Arrangement.spacedBy(RowSpacing),
                    horizontalArrangement = Arrangement.spacedBy(CardSpacing, Alignment.CenterHorizontally),
                ) {
                    items(shown, key = { it.id }) { computer ->
                        ReorderableItem(reorderState, key = computer.id) { isDragging ->
                            val scale by animateFloatAsState(if (isDragging) 1.04f else 1f, label = "drag")
                            ComputerItem(
                                computer,
                                menu = menu?.takeIf { it.id == computer.id },
                                setMenu = { position -> menu = position?.let { ItemMenu(computer.id, it) } },
                                actions = actions,
                                onDragStopped = onDragStopped,
                            ) { itemModifier ->
                                ComputerCard(
                                    name = computer.displayName,
                                    detail = computer.detailText,
                                    device = computer.device,
                                    modifier = Modifier
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .onSizeChanged { labelHeight = labelHeightOf(it, density) }
                                        .clip(RoundedCornerShape(CardInset + 16.dp))
                                        .then(itemModifier)
                                        .padding(CardInset),
                                    thumbnail = computer.thumbnail,
                                    availability = AvailabilityMonitor.shared.availability(computer.address),
                                    isPC = computer.remoteProtocol == RemoteProtocol.Rdp,
                                )
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = newConnection,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(horizontalPadding)
                .padding(bottom = bottomPadding)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_new_connection))
        }
    }
    AlertHost(alert) { alert = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderableCollectionItemScope.ComputerListRow(
    computer: Computer,
    shape: Shape,
    isDragging: Boolean,
    menu: ItemMenu?,
    setMenu: (Offset?) -> Unit,
    actions: ComputerActions,
    onDragStopped: () -> Unit,
) {
    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "drag")
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        dismissState,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .shadow(elevation, shape, clip = true),
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = {
            actions.delete(computer)
            scope.launch { dismissState.reset() }
        },
    ) {
        Surface(shape = shape, color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else pageColors.group) {
            ComputerItem(computer, menu, setMenu, actions, onDragStopped) { itemModifier ->
                ComputerRow(
                    name = computer.displayName,
                    detail = computer.detailText,
                    device = computer.device,
                    modifier = itemModifier,
                    availability = AvailabilityMonitor.shared.availability(computer.address),
                    isPC = computer.remoteProtocol == RemoteProtocol.Rdp,
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.ComputerItem(
    computer: Computer,
    menu: ItemMenu?,
    setMenu: (Offset?) -> Unit,
    actions: ComputerActions,
    onDragStopped: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val openInNewWindowLabel = stringResource(R.string.home_open_in_new_window)
    val editLabel = stringResource(R.string.edit)
    val deleteLabel = stringResource(R.string.delete)
    val moveUpLabel = stringResource(R.string.home_move_up)
    val moveDownLabel = stringResource(R.string.home_move_down)
    Box {
        content(
            Modifier
                .onSizeChanged { anchor = it }
                .secondaryClick { setMenu(it) }
                .longPressDraggableHandle(
                    onDragStarted = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        setMenu(it)
                    },
                    onDragStopped = onDragStopped,
                )
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed) -> {
                            setMenu(Offset.Unspecified)
                            true
                        }
                        event.key == Key.Delete -> {
                            actions.delete(computer)
                            true
                        }
                        else -> false
                    }
                }
                .clickable { if (menu == null) actions.connect(computer) }
                .semantics {
                    onLongClick {
                        setMenu(Offset.Unspecified)
                        true
                    }
                    customActions = buildList {
                        actions.openInNewWindow?.let { open -> add(CustomAccessibilityAction(openInNewWindowLabel) { open(computer).let { true } }) }
                        add(CustomAccessibilityAction(editLabel) { actions.edit(computer).let { true } })
                        add(CustomAccessibilityAction(deleteLabel) { actions.delete(computer).let { true } })
                        if (actions.canMove(computer, -1)) add(CustomAccessibilityAction(moveUpLabel) { actions.move(computer, -1).let { true } })
                        if (actions.canMove(computer, 1)) add(CustomAccessibilityAction(moveDownLabel) { actions.move(computer, 1).let { true } })
                    }
                },
        )
        DropdownMenu(
            expanded = menu != null,
            onDismissRequest = { setMenu(null) },
            offset = menuOffset(menu?.position, anchor),
        ) {
            actions.openInNewWindow?.let { open ->
                DropdownMenuItem(
                    text = { Text(openInNewWindowLabel) },
                    onClick = {
                        setMenu(null)
                        open(computer)
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(editLabel) },
                onClick = {
                    setMenu(null)
                    actions.edit(computer)
                },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(deleteLabel) },
                onClick = {
                    setMenu(null)
                    actions.delete(computer)
                },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
            )
        }
    }
}

@Composable
private fun EmptyView(modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Computer, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_no_connections), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val marker = "￼"
        val description = stringResource(R.string.home_tap_to_add, marker)
        val index = description.indexOf(marker)
        val text = buildAnnotatedString {
            if (index < 0) {
                append(description)
            } else {
                append(description.substring(0, index))
                appendInlineContent("add", "+")
                append(description.substring(index + marker.length))
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            inlineContent = mapOf(
                "add" to InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.fillMaxSize())
                },
            ),
        )
    }
}

private fun Modifier.dismissMenuOnMove(isShown: () -> Boolean, dismiss: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (isShown() && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dismiss()
        }
    }
}

private fun orderedComputers(computers: List<Computer>, order: List<UUID>): List<Computer> {
    val positions = HashMap<UUID, Int>()
    order.forEachIndexed { index, id -> positions.putIfAbsent(id, index) }
    return computers.withIndex()
        .sortedWith(compareBy({ positions[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

private fun labelHeightOf(size: IntSize, density: Density): Dp = with(density) { (size.height - size.width / ComputerCardAspectRatio).toDp() }

private data class GridLayout(val columns: Int, val cardWidth: Dp?)

private fun gridLayout(count: Int, width: Dp, height: Dp, labelHeight: Dp): GridLayout {
    val maxColumns = max(1, ((width + CardSpacing) / (CardWidth + CardSpacing)).toInt())
    for (columns in 1..max(1, min(count, maxColumns))) {
        val rows = (count + columns - 1) / columns
        val cardWidth = (width - CardSpacing * (columns - 1)) / columns
        val cardHeight = cardWidth / ComputerCardAspectRatio + labelHeight
        if (cardHeight * rows + RowSpacing * (rows - 1) <= height) return GridLayout(columns, null)
    }
    val cardWidth = floor(((height - labelHeight) * ComputerCardAspectRatio).value).dp
    if (count <= maxColumns && cardWidth >= CardWidth) return GridLayout(count, cardWidth)
    return GridLayout(maxColumns, null)
}

private data class ComputerColumns(private val columns: Int, private val cardWidth: Dp?) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val fixed = cardWidth?.roundToPx()
        if (fixed != null && fixed * columns + spacing * (columns - 1) <= availableSize) return List(columns) { fixed }
        val total = (availableSize - spacing * (columns - 1)).coerceAtLeast(0)
        val base = total / columns
        val remainder = total % columns
        return List(columns) { base + if (it < remainder) 1 else 0 }
    }
}
