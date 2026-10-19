package org.pkgj.android

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pkgj.android.catalog.*
import org.pkgj.android.data.Repository

@Composable
internal fun SettingsScreen(repository: Repository, onImport: (Category) -> Unit, onFolder: (Platform) -> Unit,
    compatibilityDate: String?, rpcs3Date: String?, compatibilitySyncing: Boolean, navigationFocus: FocusRequester) {
    var section by rememberSaveable { mutableStateOf("Folders") }
    var category by rememberSaveable { mutableStateOf(Category.GAMES) }
    var url by remember(category) { mutableStateOf(repository.url(category)) }
    val folders by repository.downloadFolders.collectAsState()
    val loading by repository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val categoryFocus = remember { FocusRequester() }
    var editingUrl by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var folderNames by remember { mutableStateOf<Map<Platform, String>>(emptyMap()) }
    LaunchedEffect(folders) {
        folderNames = withContext(Dispatchers.IO) {
            folders.mapValues { (_, uri) -> runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull() ?: "Selected folder" }
        }
    }
    PageScrollTarget { pages ->
        keyboard?.hide()
        scope.launch {
            scroll.animateScrollTo((scroll.value + pages * scroll.viewportSize.coerceAtLeast(160)).coerceIn(0, scroll.maxValue))
            navigationFocus.requestFocus()
        }
    }
    BackHandler(enabled = editingUrl) { keyboard?.hide(); categoryFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Folders", "Catalogs", "About").forEach { name ->
                TextButton(onClick = { section = name; keyboard?.hide(); scope.launch { scroll.scrollTo(0) } },
                    modifier = Modifier.controllerFocus().focusProperties { up = navigationFocus }.semantics { selected = section == name },
                    colors = ButtonDefaults.textButtonColors(contentColor = if (section == name) Mint else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(name)
                }
            }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 700.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (section) {
                    "Folders" -> Column { Platform.entries.forEach { platform ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(platform.label, style = MaterialTheme.typography.bodyLarge)
                                Text(folderNames[platform] ?: "Not set", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { onFolder(platform) }, modifier = Modifier.controllerFocus()
                                .semantics { contentDescription = "Choose ${platform.label} folder" }) {
                                Text(if (folders[platform] == null) "Choose" else "Change")
                            }
                            if (folders[platform] != null) TextButton(onClick = { repository.clearExportFolder(platform) },
                                modifier = Modifier.controllerFocus().semantics { contentDescription = "Clear ${platform.label} folder" }) { Text("Clear") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    } }
                    "Catalogs" -> {
                        val catalog: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterMenu("Platform", category.platform.name, Platform.entries.map { it.name }, label = { Platform.valueOf(it).label },
                                    onSelect = { category = Platform.valueOf(it).categories().first() })
                                FilterMenu("Category", category.name, category.platform.categories().map { it.name }, label = { Category.valueOf(it).contentLabel },
                                    onSelect = { category = Category.valueOf(it) }, modifier = Modifier.focusRequester(categoryFocus))
                            }
                            OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().onFocusChanged { editingUrl = it.hasFocus }.onPreviewKeyEvent { event ->
                                    val key = event.nativeKeyEvent
                                    if (key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                                        if (key.action == KeyEvent.ACTION_DOWN) {
                                            keyboard?.hide()
                                            focusManager.moveFocus(if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down)
                                        }
                                        true
                                    } else false
                                })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { repository.saveUrl(category, url); scope.launch { runCatching { repository.refresh(category) } } },
                                    enabled = !loading, modifier = Modifier.controllerFocus()) { Text("Update") }
                                OutlinedButton(onClick = { onImport(category) }, enabled = !loading, modifier = Modifier.controllerFocus()) { Text("Import") }
                                TextButton(onClick = { repository.useUpstreamCatalogs() }, modifier = Modifier.controllerFocus()) { Text("Reset URLs") }
                            }
                        }
                        val compatibility: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Compatibility", style = MaterialTheme.typography.titleMedium)
                                    Text("Vita3K  ${compatibilityDate?.substringBefore(' ') ?: "No data"}", style = MaterialTheme.typography.bodySmall)
                                    Text("RPCS3  ${rpcs3Date?.substringBefore(' ') ?: "No data"}", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { repository.refreshCompatibility() }, enabled = !compatibilitySyncing,
                                    modifier = Modifier.controllerFocus()) { Text(if (compatibilitySyncing) "Updating" else "Update") }
                            }
                        }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) { catalog() }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { compatibility() }
                        } else { catalog(); HorizontalDivider(); compatibility() }
                    }
                    else -> {
                        Text("PKGj Android", style = MaterialTheme.typography.titleMedium)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AboutRow("Original project", "PKGj for PS Vita", Modifier.weight(1.2f)) { uriHandler.openUri("https://github.com/blastrock/pkgj") }
                            AboutRow("Compatibility reports", "Vita3K", Modifier.weight(1f)) { uriHandler.openUri("https://github.com/Vita3K/compatibility") }
                            AboutRow("Compatibility reports", "RPCS3", Modifier.weight(1f)) { uriHandler.openUri("https://rpcs3.net/compatibility") }
                            AboutRow("License", "BSD 2-Clause", Modifier.weight(1f)) { showLicense = true }
                        } else {
                            AboutRow("Original project", "PKGj for PS Vita") { uriHandler.openUri("https://github.com/blastrock/pkgj") }
                            AboutRow("Compatibility reports", "Vita3K") { uriHandler.openUri("https://github.com/Vita3K/compatibility") }
                            AboutRow("Compatibility reports", "RPCS3") { uriHandler.openUri("https://rpcs3.net/compatibility") }
                            AboutRow("License", "BSD 2-Clause") { showLicense = true }
                        }
                    }
                }
            }
        }
    }
    if (showLicense) LicenseDialog(repository.licenseText()) { showLicense = false }
}
