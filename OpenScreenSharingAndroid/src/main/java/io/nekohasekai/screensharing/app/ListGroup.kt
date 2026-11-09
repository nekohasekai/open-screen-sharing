package io.nekohasekai.screensharing.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R

class PageColors(val background: Color, val group: Color, val floating: Boolean = false)

val LocalPageColors = compositionLocalOf<PageColors?> { null }

val pageColors: PageColors
    @Composable get() = LocalPageColors.current
        ?: PageColors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer)

@Composable
fun ListGroup(modifier: Modifier = Modifier, clipContent: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .then(if (clipContent) Modifier.clip(shape) else Modifier)
            .background(pageColors.group, shape),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
fun groupItemColors(enabled: Boolean = true, destructive: Boolean = false): ListItemColors {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    return ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = (if (destructive) colors.error else colors.onSurface).copy(alpha = alpha),
        leadingIconColor = (if (destructive) colors.error else colors.primary).copy(alpha = alpha),
        supportingColor = colors.onSurfaceVariant.copy(alpha = alpha),
        trailingIconColor = colors.onSurfaceVariant.copy(alpha = alpha),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        colors = groupItemColors(),
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
fun <T> ChoiceDialog(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                items(choices) { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
