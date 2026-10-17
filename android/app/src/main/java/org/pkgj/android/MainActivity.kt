package org.pkgj.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val Ink = Color(0xFF0B1020)
private val SurfaceInk = Color(0xFF172136)
private val Mint = Color(0xFF5CE0C3)
private val VitaCompatibilityStatuses = listOf("Nothing", "Bootable", "Intro", "Menu", "Ingame -", "Ingame +", "Playable", "Unreported")

@Composable
private fun CatalogApp(repository: Repository, onImport: (Category) -> Unit, onFolder: () -> Unit,
    onExport: (DownloadJob) -> Unit, onLicenseExport: (DownloadJob) -> Unit) {
    val colors = darkColorScheme(primary = Mint, background = Ink, onBackground = Color(0xFFE8EDF7), surface = SurfaceInk, onSurface = Color(0xFFE8EDF7))
    MaterialTheme(colorScheme = colors) {
        var section by remember { mutableStateOf(0) }
        var selected by remember { mutableStateOf<CatalogItem?>(null) }
        val libraryListState = rememberLazyListState()
        val status by repository.status.collectAsState()
        val compatibility by repository.compatibility.collectAsState()
        val compatibilityDate by repository.compatibilityDatabaseDate.collectAsState()
        val compatibilitySyncing by repository.compatibilitySyncing.collectAsState()
        BackHandler(enabled = selected != null || section != 0) { if (selected != null) selected = null else section = 0 }
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail(containerColor = SurfaceInk) {
                    Spacer(Modifier.height(28.dp))
                    listOf("Library", "Downloads", "Settings").forEachIndexed { i, label ->
                        NavigationRailItem(selected = section == i, onClick = { section = i }, icon = {}, label = { Text(label) }, alwaysShowLabel = true)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(listOf("Library", "Downloads", "Settings")[section], Modifier.padding(20.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (status != null) Text(status!!, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), color = Mint)
                    Box(Modifier.weight(1f)) {
                        when (section) {
                            0 -> LibraryScreen(repository, libraryListState, compatibility, onSelect = { selected = it })
                            1 -> DownloadsScreen(repository, onExport, onLicenseExport)
                            else -> SettingsScreen(repository, onImport, onFolder, compatibilityDate, compatibilitySyncing)
                        }
                    }
                    if (!wide) NavigationBar(containerColor = SurfaceInk) {
                        listOf("Library", "Downloads", "Settings").forEachIndexed { i, label ->
                            NavigationBarItem(selected = section == i, onClick = { section = i }, icon = {}, label = { Text(label) })
                        }
                    }
                }
            }
        }
        }
        selected?.let { item -> ItemDialog(item, compatibility[item.titleId], onDismiss = { selected = null }, onQueue = { repository.queue(item); selected = null }) }
    }
}

@Composable
private fun FocusCard(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(16.dp), border = if (focused) BorderStroke(2.dp, Mint) else null,
        colors = CardDefaults.cardColors(containerColor = SurfaceInk)) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun LibraryScreen(repository: Repository, listState: LazyListState,
    compatibility: Map<String, CompatibilityReport>, onSelect: (CatalogItem) -> Unit) {
    var search by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("All") }
    var category by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Title") }
    var platform by remember { mutableStateOf(CatalogPlatform.PSVITA) }
    var compatibilityStatus by remember { mutableStateOf("All") }
    val version by repository.catalogVersion.collectAsState()
    val syncing by repository.syncing.collectAsState()
    val categoryOptions = if (platform == CatalogPlatform.PSVITA) Category.entries.filter { it.vita3kRelevant } else Category.entries
    val filter = remember(search, region, category, sort, platform, compatibilityStatus) {
        CatalogFilter(search, region, category, sort, platform, compatibilityStatus)
    }
    val paging = remember(filter, version) { repository.catalog(filter) }.collectAsLazyPagingItems()
    LaunchedEffect(filter) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogPlatform.entries.forEach { option ->
                FilterChip(selected = platform == option, onClick = {
                    platform = option
                    category = "All"
                    if (option == CatalogPlatform.ALL) compatibilityStatus = "All"
                }, label = { Text(option.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "US", "EU", "JP", "ASIA").forEach { FilterChip(selected = region == it, onClick = { region = it }, label = { Text(it) }) }
            Spacer(Modifier.width(8.dp))
            (listOf("All") + categoryOptions.map { it.name }).forEach {
                FilterChip(selected = category == it, onClick = { category = it }, label = { Text(if (it == "All") it else Category.valueOf(it).label) })
            }
            Text("Sort", Modifier.align(Alignment.CenterVertically))
            listOf("Title", "Size").forEach { FilterChip(selected = sort == it, onClick = { sort = it }, label = { Text(it) }) }
        }
        if (platform == CatalogPlatform.PSVITA) {
            Text("Compatibility", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB7C5D9), modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = compatibilityStatus == "All", onClick = { compatibilityStatus = "All" }, label = { Text("All statuses") })
                VitaCompatibilityStatuses.forEach { value ->
                    FilterChip(selected = compatibilityStatus == value, onClick = { compatibilityStatus = value }, label = { Text(value) })
                }
            }
        }
        if (paging.itemCount == 0 && paging.loadState.refresh !is androidx.paging.LoadState.Loading)
            Text(if (syncing) "Updating catalog" else "No entries. Check Settings.", Modifier.padding(24.dp))
        if (paging.loadState.refresh is androidx.paging.LoadState.Loading) CircularProgressIndicator()
        if (paging.loadState.refresh is androidx.paging.LoadState.Error) Text("Catalog error. Check Settings.", color = MaterialTheme.colorScheme.error)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(paging.itemCount) { index ->
                val item = paging[index] ?: return@items
                FocusCard(onClick = { onSelect(item) }) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(itemDetails(item), color = Color(0xFFB7C5D9), style = MaterialTheme.typography.bodySmall)
                    if (item.category == Category.GAMES) compatibility[item.titleId]?.let { report ->
                        CompatibilityPill(report.status)
                    }
                }
            }
        }
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
private fun CompatibilityPill(status: String) {
    val colors = when (status) {
        "Playable" -> Color(0xFF2E9B59) to Color.White
        "Ingame +" -> Color(0xFF78A83B) to Color(0xFF10160B)
        "Ingame -" -> Color(0xFFD1A52E) to Color(0xFF171207)
        "Menu" -> Color(0xFFE88732) to Color(0xFF1A1007)
        "Intro" -> Color(0xFFD45C4B) to Color.White
        "Bootable" -> Color(0xFFB5434A) to Color.White
        "Nothing" -> Color(0xFF555B66) to Color.White
        else -> Color(0xFF555B66) to Color.White
    }
    Surface(color = colors.first, contentColor = colors.second, shape = CircleShape) {
        Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
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
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Catalog", style = MaterialTheme.typography.titleLarge)
        Text("Updates automatically. Set a URL or import a TSV to change a category.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it.label) }) }
        }
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { repository.saveUrl(category, url); scope.launch { runCatching { repository.refresh(category) } } }, enabled = !loading) { Text("Update") }
            OutlinedButton(onClick = { onImport(category) }, enabled = !loading) { Text("Import TSV") }
            OutlinedButton(onClick = { repository.useUpstreamCatalogs() }) { Text("Reset URLs") }
        }
        HorizontalDivider()
        Text("Export folder", style = MaterialTheme.typography.titleLarge)
        Text(if (folder == null) "No folder selected" else "New downloads copy here")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFolder) { Text("Choose folder") }
            if (folder != null) TextButton(onClick = { repository.clearExportFolder() }) { Text("Clear folder") }
        }
        HorizontalDivider()
        Text("Vita3K", style = MaterialTheme.typography.titleLarge)
        Text("Install .pkg in Vita3K. Select the matching RIF when asked.")
        Text("PSP, PS1 and PS Mobile are not supported.")
        HorizontalDivider()
        Text("Compatibility", style = MaterialTheme.typography.titleLarge)
        Text(compatibilityDate?.let { "Updated $it" } ?: "No compatibility data")
        Text("Community reports. Not device-specific.")
        OutlinedButton(onClick = { repository.refreshCompatibility() }, enabled = !compatibilitySyncing) {
            Text(if (compatibilitySyncing) "Updating" else "Update")
        }
        TextButton(onClick = { showLicense = true }) { Text("App license") }
    }
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("BSD 2-Clause license") },
        text = { Text(repository.licenseText(), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } })
}

private fun sizeLabel(bytes: Long?): String {
    if (bytes == null) return "Unknown size"
    if (bytes <= 0) return "0 B"
    val unit = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var index = 0
    while (value >= 1024 && index < unit.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.ROOT, "%.1f %s", value, unit[index])
}

private fun itemDetails(item: CatalogItem): String = listOfNotNull(
    item.titleId, item.region, item.category.label, item.size?.let(::sizeLabel)
).joinToString("    ")

private fun reportDate(timestamp: Long): String = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    .format(java.util.Date(timestamp * 1000))
