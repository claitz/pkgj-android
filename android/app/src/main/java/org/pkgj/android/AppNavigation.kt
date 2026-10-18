package org.pkgj.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun AppNavigation(section: Int, focus: List<FocusRequester>, wide: Boolean,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (wide) Text("PKGj Android", color = Mint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f)) {
            TabRow(selectedTabIndex = section, containerColor = Ink, contentColor = Mint,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(), divider = {}) {
                listOf("Catalog", "Downloads", "Settings").forEachIndexed { index, name ->
                    Tab(selected = section == index, onClick = { onSelect(index) },
                        modifier = Modifier.height(40.dp).focusRequester(focus[index]).controllerFocus(),
                        selectedContentColor = Mint, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(name, maxLines = 1) })
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
}

@Composable
internal fun ControllerHelp() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyHint("A", "Select"); KeyHint("B", "Back")
        Spacer(Modifier.weight(1f))
        KeyHint("L1 / R1", "Tabs"); KeyHint("L2 / R2", "Page")
    }
}

@Composable
private fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = SurfaceInk, shape = RoundedCornerShape(4.dp)) {
            Text(key, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        Text(action, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
