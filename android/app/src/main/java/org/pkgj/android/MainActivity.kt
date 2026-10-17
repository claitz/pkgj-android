package org.pkgj.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.paging.cachedIn
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.CompatibilityReport
import org.pkgj.android.data.Repository
import org.pkgj.android.download.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val repository by lazy { Repository.get(this) }
    private var importCategory = Category.GAMES
    private var exportJob: DownloadJob? = null
    private var licenseExportJob: DownloadJob? = null
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { runCatching { repository.import(uri, importCategory) } }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { repository.setExportFolder(uri) }
            .onFailure { repository.status.value = "Folder error: ${it.message}" }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = exportJob
        if (uri != null && job != null) lifecycleScope.launch {
            runCatching { repository.exportTo(job, uri) }
                .onSuccess { repository.status.value = "PKG exported" }
                .onFailure { repository.status.value = "PKG export failed: ${it.message}" }
        }
        exportJob = null
    }
    private val licenseExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = licenseExportJob
        if (uri != null && job != null) lifecycleScope.launch {
            runCatching { repository.exportLicenseTo(job, uri) }
                .onSuccess { repository.status.value = "RIF exported" }
                .onFailure { repository.status.value = "RIF export failed: ${it.message}" }
        }
        licenseExportJob = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CatalogApp(repository,
                onImport = { importCategory = it; importPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                onFolder = { folderPicker.launch(null) },
                onExport = { exportJob = it; exportPicker.launch(it.filename) },
                onLicenseExport = { licenseExportJob = it; licenseExportPicker.launch(repository.licenseFile(it).name) })
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { onBackPressedDispatcher.onBackPressed(); return true }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) return sendToFocus(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) return true
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) return sendToFocus(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
        return super.onKeyUp(keyCode, event)
    }
    private fun sendToFocus(key: Int, action: Int): Boolean =
        window.decorView.findFocus()?.dispatchKeyEvent(KeyEvent(action, key)) ?: false
    private var lastStick = 0L
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            val now = System.currentTimeMillis()
            if (now - lastStick > 180) {
                val x = event.getAxisValue(MotionEvent.AXIS_X); val y = event.getAxisValue(MotionEvent.AXIS_Y)
                val key = if (x > .65f) KeyEvent.KEYCODE_DPAD_RIGHT else if (x < -.65f) KeyEvent.KEYCODE_DPAD_LEFT
                    else if (y > .65f) KeyEvent.KEYCODE_DPAD_DOWN else if (y < -.65f) KeyEvent.KEYCODE_DPAD_UP else null
                if (key != null) {
                    lastStick = now
                    sendToFocus(key, KeyEvent.ACTION_DOWN)
                    sendToFocus(key, KeyEvent.ACTION_UP)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

internal val Ink = Color(0xFF101419)
internal val SurfaceInk = Color(0xFF1A2028)
internal val Mint = Color(0xFF74D5B5)


@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun CatalogApp(repository: Repository, onImport: (Category) -> Unit, onFolder: () -> Unit,
    onExport: (DownloadJob) -> Unit, onLicenseExport: (DownloadJob) -> Unit) {
    val colors = darkColorScheme(primary = Mint, onPrimary = Ink, secondary = Mint, secondaryContainer = Color(0xFF284439), onSecondaryContainer = Mint, background = Ink, onBackground = Color(0xFFE6E9EE), surface = SurfaceInk, onSurface = Color(0xFFE6E9EE), surfaceVariant = SurfaceInk, onSurfaceVariant = Color(0xFFA3ADB9), outline = Color(0xFF414B57), surfaceContainer = SurfaceInk, surfaceContainerHigh = Color(0xFF222A34), surfaceContainerHighest = Color(0xFF2B3540), surfaceContainerLow = Color(0xFF161C23), outlineVariant = Color(0xFF414B57))
    MaterialTheme(colorScheme = colors) {
        var section by rememberSaveable { mutableStateOf(0) }
        val screenState = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        var selected by remember { mutableStateOf<CatalogItem?>(null) }
        val libraryListState = rememberLazyListState()
        val libraryScope = rememberCoroutineScope()
        val filterSaver = remember { listSaver<CatalogFilter, String>(
            save = { listOf(it.search, it.region, it.category, it.sort, it.compatibilityStatus) },
            restore = { CatalogFilter(it[0], it[1], it[2], it[3], it[4]) }) }
        var libraryFilter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(CatalogFilter(category = Category.GAMES.name)) }
        // Keep loaded pages alive between sections and discard them when filters change.
        val libraryPages = remember(repository, libraryScope) {
            combine(snapshotFlow { libraryFilter }, repository.catalogVersion) { filter, _ -> filter }
                .flatMapLatest { repository.catalog(it) }.cachedIn(libraryScope)
        }
        val status by repository.status.collectAsState()
        val compatibility by repository.compatibility.collectAsState()
        val compatibilityDate by repository.compatibilityDatabaseDate.collectAsState()
        val compatibilitySyncing by repository.compatibilitySyncing.collectAsState()
        LaunchedEffect(status) {
            val message = status ?: return@LaunchedEffect
            launch { snackbar.showSnackbar(message, withDismissAction = true) }
            delay(3500)
            if (repository.status.value == message) repository.status.value = null
        }
        BackHandler(enabled = selected != null || section != 0) { if (selected != null) selected = null else section = 0 }
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 700.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail(containerColor = SurfaceInk, modifier = Modifier.width(96.dp)) {
                    Text("PKGj", Modifier.padding(top = 20.dp, bottom = 24.dp), color = Mint, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    listOf("Library", "Downloads", "Settings").forEachIndexed { i, label ->
                        NavigationRailItem(selected = section == i, onClick = { section = i }, icon = { Icon(NavigationIcons[i], contentDescription = null) }, label = { Text(label) }, alwaysShowLabel = true)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf("Library", "Downloads", "Settings")[section], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("PS Vita", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f)) {
                        screenState.SaveableStateProvider(section) { when (section) {
                            0 -> LibraryScreen(repository, libraryListState, compatibility, libraryFilter, libraryPages,
                                onFilter = { libraryFilter = it; libraryScope.launch { libraryListState.scrollToItem(0) } },
                                onSelect = { selected = it })
                            1 -> DownloadsScreen(repository, onExport, onLicenseExport)
                            else -> SettingsScreen(repository, onImport, onFolder, compatibilityDate, compatibilitySyncing)
                        } }
                    }
                    if (!wide) NavigationBar(containerColor = SurfaceInk) {
                        listOf("Library", "Downloads", "Settings").forEachIndexed { i, label ->
                            NavigationBarItem(selected = section == i, onClick = { section = i }, icon = { Icon(NavigationIcons[i], contentDescription = null) }, label = { Text(label) })
                        }
                    }
                }
            }
        }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar) }
        selected?.let { item -> ItemDialog(item, compatibility[item.titleId], onDismiss = { selected = null }, onQueue = { repository.queue(item); selected = null }) }
    }
}

@Composable
private fun ItemDialog(item: CatalogItem, report: CompatibilityReport?, onDismiss: () -> Unit, onQueue: () -> Unit) {
    val focus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val uriHandler = LocalUriHandler.current
    val queueEnabled = CatalogParser.usableUrl(item.url)
    val compatibilityUrl = report?.let { "https://github.com/Vita3K/compatibility/issues/${it.issueId}" }
        ?: "https://github.com/Vita3K/compatibility/issues?q=${java.net.URLEncoder.encode(item.titleId, "UTF-8")}" 
    LaunchedEffect(item.contentId) { if (queueEnabled) focus.requestFocus() else closeFocus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(itemDetails(item))
            Text(if (item.category.vita3kRelevant) {
                if (item.zrif.isNotBlank()) "RIF included" else "No RIF"
            } else "Not supported by Vita3K")
            if (item.category == Category.GAMES) {
                if (report != null) CompatibilityPill(report.status) else Text("No compatibility report")
                report?.let { Text("Reported ${reportDate(it.updatedAt)}") }
                TextButton(onClick = { uriHandler.openUri(compatibilityUrl) }) {
                    Text(if (report == null) "Search reports" else "View report")
                }
            }
            if (!queueEnabled) Text("No download URL")
        }
    }, confirmButton = { Button(onClick = onQueue, modifier = Modifier.focusRequester(focus), enabled = queueEnabled) { Text("Queue") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) { Text("Close") } })
}

@Composable
private fun DownloadsScreen(repository: Repository, onExport: (DownloadJob) -> Unit,
    onLicenseExport: (DownloadJob) -> Unit) {
    val jobs by repository.jobs.collectAsState()
    val folder by repository.exportFolder.collectAsState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<DownloadJob?>(null) }
    if (jobs.isEmpty()) { Text("No downloads", Modifier.padding(24.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = SurfaceInk)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(job.state.name.lowercase().replaceFirstChar { it.uppercase() }, color = Mint)
                    if (job.exported || job.licenseExported) Text(
                        listOfNotNull(if (job.exported) "PKG exported" else null,
                            if (job.licenseExported) "RIF exported" else null).joinToString("    "),
                        color = Color(0xFFB7C5D9), style = MaterialTheme.typography.bodySmall)
                    if (job.state == JobState.RUNNING || job.state == JobState.PAUSED) {
                        LinearProgressIndicator(progress = { if (job.total != null && job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                        Text("${sizeLabel(job.bytes)} / ${sizeLabel(job.total)}    ${sizeLabel(job.speed)}/s")
                    }
                    if (!job.error.isNullOrBlank()) Text(job.error, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (job.state == JobState.RUNNING) OutlinedButton(onClick = { repository.action(job, JobState.PAUSED) }) { Text("Pause") }
                        if (job.state == JobState.PAUSED || job.state == JobState.FAILED || job.state == JobState.CANCELED) Button(onClick = { repository.action(job, JobState.QUEUED) }) { Text(if (job.state == JobState.PAUSED) "Resume" else "Retry") }
                        if (job.state == JobState.DOWNLOADED) {
                            OutlinedButton(onClick = { onExport(job) }) { Text("Export PKG") }
                            if (repository.hasCatalogLicense(job)) OutlinedButton(onClick = { onLicenseExport(job) }) { Text("Export RIF") }
                            if (folder != null) OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { repository.exportToFolder(job) }
                                        .onSuccess { repository.status.value = "Copied to folder" }
                                        .onFailure { repository.status.value = "Copy failed: ${it.message}" }
                                }
                            }) { Text("Copy to folder") }
                            TextButton(onClick = { deleteTarget = job }) { Text("Delete") }
                        }
                        if (job.state != JobState.DOWNLOADED && job.state != JobState.CANCELED) TextButton(onClick = { repository.delete(job) }) { Text("Cancel") }
                    }
                }
            }
        }
    }
    deleteTarget?.let { job -> AlertDialog(onDismissRequest = { deleteTarget = null },
        title = { Text("Delete local files?") },
        text = { Text(job.title) },
        confirmButton = { TextButton(onClick = { repository.deleteCompleted(job); deleteTarget = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } }) }
}

@Composable
private fun SettingsScreen(repository: Repository, onImport: (Category) -> Unit, onFolder: () -> Unit,
    compatibilityDate: String?, compatibilitySyncing: Boolean) {
    var category by remember { mutableStateOf(Category.GAMES) }
    var url by remember(category) { mutableStateOf(repository.url(category)) }
    val folder by repository.exportFolder.collectAsState()
    val loading by repository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var showLicense by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var folderName by remember(folder) { mutableStateOf<String?>(null) }
    LaunchedEffect(folder) {
        folderName = withContext(Dispatchers.IO) {
            runCatching { folder?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } }.getOrNull()
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Catalog", style = MaterialTheme.typography.titleLarge)
        FilterMenu("Category", category.name, VitaCategories.map { it.name },
            label = { Category.valueOf(it).label.removePrefix("Vita ").replaceFirstChar { c -> c.uppercase() } },
            onSelect = { category = Category.valueOf(it) })
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { repository.saveUrl(category, url); scope.launch { runCatching { repository.refresh(category) } } }, enabled = !loading) { Text("Update") }
            OutlinedButton(onClick = { onImport(category) }, enabled = !loading) { Text("Import TSV") }
            OutlinedButton(onClick = { repository.useUpstreamCatalogs() }) { Text("Reset URLs") }
        }
        HorizontalDivider()
        Text("Export folder", style = MaterialTheme.typography.titleLarge)
        Text(if (folder == null) "None" else folderName ?: "Selected folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFolder) { Text("Choose folder") }
            if (folder != null) TextButton(onClick = { repository.clearExportFolder() }) { Text("Clear folder") }
        }
        HorizontalDivider()
        Text("Compatibility", style = MaterialTheme.typography.titleLarge)
        Text(compatibilityDate?.let { "Updated ${it.substringBefore(' ')}" } ?: "No data",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { repository.refreshCompatibility() }, enabled = !compatibilitySyncing) {
            Text(if (compatibilitySyncing) "Updating" else "Update")
        }
        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PKGj Android", style = MaterialTheme.typography.titleMedium)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AboutRow("Original project", "PKGj for PS Vita") { uriHandler.openUri("https://github.com/blastrock/pkgj") }
        AboutRow("Compatibility reports", "Vita3K") { uriHandler.openUri("https://github.com/Vita3K/compatibility") }
        AboutRow("License", "BSD 2-Clause") { showLicense = true }
    }
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("BSD 2-Clause license") },
        text = { Text(repository.licenseText(), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } })
}

@Composable
private fun AboutRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Mint)
    }
}

internal fun sizeLabel(bytes: Long?): String {
    if (bytes == null) return "Unknown size"
    if (bytes <= 0) return "0 B"
    val unit = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var index = 0
    while (value >= 1024 && index < unit.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.ROOT, "%.1f %s", value, unit[index])
}

internal fun itemDetails(item: CatalogItem): String = listOfNotNull(
    item.titleId, item.region, item.category.label, item.size?.let(::sizeLabel)
).joinToString("    ")

private fun reportDate(timestamp: Long): String = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    .format(java.util.Date(timestamp * 1000))
