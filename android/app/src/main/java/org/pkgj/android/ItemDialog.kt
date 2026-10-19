package org.pkgj.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.pkgj.android.catalog.CatalogItem
import org.pkgj.android.catalog.CatalogParser
import org.pkgj.android.catalog.Category
import org.pkgj.android.compatibility.CompatibilityReport

@Composable
internal fun ItemDialog(item: CatalogItem, report: CompatibilityReport?, repository: org.pkgj.android.data.Repository, onDismiss: () -> Unit, onQueue: (CatalogItem) -> Unit) {
    val primary = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val enabled = CatalogParser.usableUrl(item.url)
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // Capture the activity insets: dialog windows can report zero system-bar insets.
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) { if (enabled) primary.requestFocus() else close.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // Match the window to its measured content; avoid clipped actions on short screens.
            window?.let {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                if (android.os.Build.VERSION.SDK_INT >= 30) it.attributes = it.attributes.apply { setFitInsetsTypes(0) }
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }
        PageScrollTarget { pages -> scope.launch { scroll.animateScrollTo((scroll.value + pages * scroll.viewportSize.coerceAtLeast(120)).coerceIn(0, scroll.maxValue)) } }
        BoxWithConstraints(Modifier.fillMaxSize().padding(safePadding).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth().heightIn(max = maxHeight).gamepadKeys(onDismiss),
                color = SurfaceInk, shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp) {
                Column {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(if (compact) 16.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                        if (compact) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.titleId, color = Mint, style = MaterialTheme.typography.labelLarge)
                                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                if (item.category == Category.GAMES) CompatibilityPill(report?.status ?: "Unreported")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DetailValue("Region", item.region, Modifier.weight(1f))
                                DetailValue("Size", item.size?.let(::sizeLabel) ?: "Unknown", Modifier.weight(1f))
                                DetailValue("Category", item.category.label.removePrefix("Vita ").replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                                if (item.version.isNotEmpty()) DetailValue("Version", item.version, Modifier.weight(.65f))
                                DetailValue("License", if (item.zrif.isNotEmpty()) "RIF available" else "No RIF", Modifier.weight(1.2f))
                            }
                        } else {
                            Text(item.titleId, color = Mint, style = MaterialTheme.typography.labelLarge)
                            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            if (item.category == Category.GAMES) CompatibilityPill(report?.status ?: "Unreported")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                DetailValue("Region", item.region, Modifier.weight(1f))
                                DetailValue("Size", item.size?.let(::sizeLabel) ?: "Unknown", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                DetailValue("Category", item.category.label.removePrefix("Vita ").replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                                if (item.version.isNotEmpty()) DetailValue("Version", item.version, Modifier.weight(1f))
                                else DetailValue("License", if (item.zrif.isNotEmpty()) "RIF available" else "No RIF", Modifier.weight(1f))
                            }
                            if (item.version.isNotEmpty()) DetailValue("License", if (item.zrif.isNotEmpty()) "RIF available" else "No RIF")
                        }
                        if (item.category == Category.GAMES) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val url = report?.let { "https://github.com/Vita3K/compatibility/issues/${it.issueId}" }
                                    ?: "https://github.com/Vita3K/compatibility/issues?q=${java.net.URLEncoder.encode(item.titleId, "UTF-8")}"
                                TextButton(onClick = { uri.openUri(url) }, modifier = Modifier.controllerFocus()) {
                                    Text(if (report == null) "Search compatibility reports" else "Compatibility report")
                                }
                                report?.let { Text("Updated ${reportDate(it.updatedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (item.category == Category.GAMES) UpdateControls(item, repository, onQueue)
                        if (!enabled) Text("No download URL", color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).focusRequester(close).controllerFocus()
                            .onGloballyPositioned { if (!enabled) ready = true }) { Text("Close") }
                        Button(onClick = { onQueue(item) }, enabled = enabled, modifier = Modifier.weight(1f).focusRequester(primary).controllerFocus()
                            .onGloballyPositioned { if (enabled) ready = true }) { Text("Download") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
