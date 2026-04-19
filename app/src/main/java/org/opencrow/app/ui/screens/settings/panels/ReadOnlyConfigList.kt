package org.opencrow.app.ui.screens.settings.panels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.opencrow.app.ui.theme.LocalSpacing

@Composable
fun ReadOnlyConfigList(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (items.isEmpty()) {
        Text("None configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items.forEach { item ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Text(
                item,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(LocalSpacing.current.md).fillMaxWidth()
            )
        }
    }
}
