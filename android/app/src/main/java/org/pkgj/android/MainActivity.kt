package org.pkgj.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.paging.cachedIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.*
import org.pkgj.android.data.Repository
import org.pkgj.android.download.*
import java.util.Locale

class FolderPickerState : ViewModel() {
    var platform = Platform.VITA
    var pendingDownload: List<CatalogItem>? = null
}

class MainActivity : ComponentActivity() {
    private val repository by lazy { Repository.get(this) }
    private var importCategory = Category.GAMES
    private val folderState by lazy { ViewModelProvider(this)[FolderPickerState::class.java] }
    private var exportJob: DownloadJob? = null
    private var licenseExportJob: DownloadJob? = null
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { runCatching { repository.import(uri, importCategory) } }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = folderState.pendingDownload
        folderState.pendingDownload = null
        if (uri != null) runCatching { repository.setExportFolder(uri, folderState.platform) }
            .onSuccess { pending?.let(::queueOrChooseFolder) }
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
                .onSuccess { repository.status.value = "${job.licenseLabel} exported" }
                .onFailure { repository.status.value = "${job.licenseLabel} export failed: ${it.message}" }
        }
        licenseExportJob = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("folderPlatform")?.let { name ->
            Platform.entries.firstOrNull { it.name == name }?.let { folderState.platform = it }
        }
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CatalogApp(repository,
                onMotion = { event, target -> analog.handle(event, target) }, onStopMotion = { analog.stop() },
                triggerHeld = { analog.triggerHeld(it) },
                onImport = { importCategory = it; importPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                onFolder = { platform ->
                    folderState.platform = platform; folderState.pendingDownload = null
                    folderPicker.launch(repository.downloadFolder(platform)?.let(Uri::parse))
                },
                onQueue = ::queueOrChooseFolder,
                onExport = { exportJob = it; exportPicker.launch(it.filename) },
                onLicenseExport = { licenseExportJob = it; licenseExportPicker.launch(repository.licenseFile(it).name) })
        }
    }
    private fun queueOrChooseFolder(items: List<CatalogItem>) {
        val missing = items.firstOrNull { repository.downloadFolder(it.category.platform) == null }
        if (missing == null) repository.queue(items)
        else {
            folderState.platform = missing.category.platform
            folderState.pendingDownload = items
            folderPicker.launch(null)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folderPlatform", folderState.platform.name)
        super.onSaveInstanceState(outState)
    }
    private val analog by lazy { AnalogNavigation(lifecycleScope) }
    override fun onPause() { analog.stop(); super.onPause() }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        analog.handle(event, window.decorView) || super.onGenericMotionEvent(event)

}

internal val Ink = Color(0xFF101419)
internal val SurfaceInk = Color(0xFF1A2028)
internal val Mint = Color(0xFF74D5B5)


@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun CatalogApp(repository: Repository, onMotion: (MotionEvent, View) -> Boolean, onStopMotion: () -> Unit,
    triggerHeld: (Int) -> Boolean, onImport: (Category) -> Unit, onFolder: (Platform) -> Unit, onQueue: (List<CatalogItem>) -> Unit,
    onExport: (DownloadJob) -> Unit, onLicenseExport: (DownloadJob) -> Unit) {
    val colors = darkColorScheme(primary = Mint, onPrimary = Ink, secondary = Mint, secondaryContainer = Color(0xFF284439), onSecondaryContainer = Mint, background = Ink, onBackground = Color(0xFFE6E9EE), surface = SurfaceInk, onSurface = Color(0xFFE6E9EE), surfaceVariant = SurfaceInk, onSurfaceVariant = Color(0xFFA3ADB9), outline = Color(0xFF414B57), surfaceContainer = SurfaceInk, surfaceContainerHigh = Color(0xFF222A34), surfaceContainerHighest = Color(0xFF2B3540), surfaceContainerLow = Color(0xFF161C23), outlineVariant = Color(0xFF414B57))
    MaterialTheme(colorScheme = colors) {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        var section by rememberSaveable { mutableStateOf(0) }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val bindings = remember { ControllerBindings() }
        val tabs = remember { List(3) { FocusRequester() } }
        var navigationReady by remember { mutableStateOf(false) }
        LaunchedEffect(section, navigationReady) { if (navigationReady) tabs[section].requestFocus() }
        val screenState = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        var selected by remember { mutableStateOf<CatalogItem?>(null) }
        val libraryListState = rememberLazyListState()
        val libraryScope = rememberCoroutineScope()
        val filterSaver = remember { listSaver<CatalogFilter, String>(
            save = { listOf(it.search, it.regions.sorted().joinToString("|"), it.category, it.sort, it.compatibilityStatuses.sorted().joinToString("|"), it.platform.name) },
            restore = { CatalogFilter(it[0], it[1].split('|').filter { value -> value.isNotEmpty() && value != "All" }.toSet(), it[2], it[3], it[4].split('|').filter { value -> value.isNotEmpty() && value != "All" }.toSet(), it.getOrNull(5)?.let(Platform::valueOf) ?: Platform.VITA) }) }
        var libraryFilter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(CatalogFilter(category = Category.GAMES.name)) }
        // Keep loaded pages alive between sections and discard them when filters change.
        val libraryPages = remember(repository, libraryScope) {
            combine(snapshotFlow { libraryFilter }, repository.catalogVersion) { filter, _ -> filter }
                .flatMapLatest { repository.catalog(it) }.cachedIn(libraryScope)
        }
        val status by repository.status.collectAsState()
        val compatibility by repository.compatibility.collectAsState()
        val compatibilityDate by repository.compatibilityDatabaseDate.collectAsState()
        val rpcs3Date by repository.rpcs3DatabaseDate.collectAsState()
        val compatibilitySyncing by repository.compatibilitySyncing.collectAsState()
        LaunchedEffect(status) {
            val message = status ?: return@LaunchedEffect
            launch { snackbar.showSnackbar(message, withDismissAction = true) }
            delay(3500)
            if (repository.status.value == message) repository.status.value = null
        }
        fun selectSection(index: Int) {
            bindings.releasePage(); onStopMotion()
            selected = null
            keyboard?.hide(); focusManager.clearFocus(force = true)
            section = index
        }
        SideEffect {
            bindings.section = { delta -> selectSection((section + delta + 3) % 3) }
            bindings.motion = onMotion; bindings.stopMotion = onStopMotion; bindings.triggerHeld = triggerHeld
        }
        BackHandler(enabled = selected != null || section != 0) { if (selected != null) selected = null else section = 0 }
        CompositionLocalProvider(LocalController provides bindings) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().gamepadKeys { backDispatcher?.onBackPressed() }) {
                val wide = maxWidth >= 700.dp
                Column(Modifier.fillMaxSize()) {
                    AppNavigation(section, tabs, wide, onSelect = ::selectSection,
                        modifier = Modifier.onGloballyPositioned { navigationReady = true })
                    Box(Modifier.weight(1f)) {
                        screenState.SaveableStateProvider(section) { when (section) {
                            0 -> LibraryScreen(repository, libraryListState, compatibility, libraryFilter, libraryPages,
                                navigationFocus = tabs[0],
                                onFilter = { libraryFilter = it; libraryScope.launch { libraryListState.scrollToItem(0) } },
                                onSelect = { selected = it })
                            1 -> DownloadsScreen(repository, onExport, onLicenseExport, tabs[1])
                            else -> SettingsScreen(repository, onImport, onFolder, compatibilityDate, rpcs3Date, compatibilitySyncing, tabs[2])
                        } }
                    }
                    if (wide) ControllerHelp()
                }
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar) }
        selected?.let { item -> ItemDialog(item, compatibility[CompatibilityKey(item.category.platform, item.titleId)], repository, onDismiss = { selected = null }, onQueue = { download -> onQueue(download); selected = null }) }
        }
    }
}

@Composable
private fun DownloadsScreen(repository: Repository, onExport: (DownloadJob) -> Unit,
    onLicenseExport: (DownloadJob) -> Unit, navigationFocus: FocusRequester) {
    val jobs by repository.jobs.collectAsState()
    val folders by repository.downloadFolders.collectAsState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<DownloadJob?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    val historyFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val pageFocus = remember { FocusRequester() }
    var pageIndex by remember { mutableStateOf<Int?>(null) }
    var pageJob by remember { mutableStateOf<Job?>(null) }
    PageScrollTarget { pages ->
        if (jobs.isNotEmpty()) {
            pageJob?.cancel()
            val target = (listState.firstVisibleItemIndex + pages * (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)).coerceIn(0, jobs.lastIndex)
            pageIndex = target
            pageJob = scope.launch {
                listState.scrollToItem(target)
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == target } }.first { it }
                withFrameNanos { }
                pageFocus.requestFocus()
            }
        }
    }
    if (jobs.isEmpty()) {
        val controls = LocalController.current
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("No downloads", style = MaterialTheme.typography.titleMedium)
            TextButton(modifier = Modifier.controllerFocus(), onClick = { controls?.section?.invoke(-1) }) { Text("Browse catalog") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${jobs.size} ${if (jobs.size == 1) "download" else "downloads"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clearHistory = true }, enabled = jobs.any { it.isHistory },
                modifier = Modifier.focusRequester(historyFocus).controllerFocus()) { Text("Clear history") }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(jobs, key = { _, job -> job.id }) { index, job ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .then(if (index == pageIndex) Modifier.focusRequester(pageFocus) else Modifier).focusGroup(), colors = CardDefaults.cardColors(containerColor = SurfaceInk)) {
                    val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val actions: @Composable () -> Unit = {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (job.state == JobState.RUNNING) OutlinedButton(modifier = Modifier.controllerFocus(), onClick = { repository.action(job, JobState.PAUSED) }) { Text("Pause") }
                            if (job.state == JobState.PAUSED || job.state == JobState.FAILED || job.state == JobState.CANCELED) Button(modifier = Modifier.controllerFocus(), onClick = { repository.action(job, JobState.QUEUED) }) { Text(if (job.state == JobState.PAUSED) "Resume" else "Retry") }
                            if (job.state == JobState.DOWNLOADED) {
                                if (job.folderUri == null) {
                                OutlinedButton(modifier = Modifier.controllerFocus(), onClick = { onExport(job) }) { Text("Export PKG") }
                                if (repository.hasCatalogLicense(job)) OutlinedButton(modifier = Modifier.controllerFocus(), onClick = { onLicenseExport(job) }) { Text("Export ${job.licenseLabel}") }
                                if (folders[job.category.platform] != null) OutlinedButton(modifier = Modifier.controllerFocus(), onClick = {
                                    scope.launch {
                                        runCatching { repository.exportToFolder(job) }
                                            .onSuccess { repository.status.value = "Saved" }
                                            .onFailure { repository.status.value = "Copy failed: ${it.message}" }
                                    }
                                }) { Text("Save to folder") }
                                }
                                TextButton(modifier = Modifier.controllerFocus(), onClick = { deleteTarget = job }) { Text("Delete") }
                            }
                            if (job.updateCheck == "FAILED" && job.state != JobState.CANCELED)
                                TextButton(modifier = Modifier.controllerFocus(), onClick = { repository.retryGameUpdate(job) }) { Text("Retry update") }
                            if (job.state != JobState.DOWNLOADED && job.state != JobState.CANCELED) TextButton(modifier = Modifier.controllerFocus(), onClick = { repository.delete(job) }) { Text("Cancel") }
                        }
                    }
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(job.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${job.category.platform.label}    ${downloadStateLabel(job)}", color = Mint, style = MaterialTheme.typography.bodySmall)
                                    if (job.state == JobState.RUNNING || job.state == JobState.PAUSED)
                                        Text("${sizeLabel(job.bytes)} / ${sizeLabel(job.total)}    ${sizeLabel(job.speed)}/s",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box(Modifier.widthIn(max = 380.dp)) { actions() }
                        } else {
                            Text(job.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${job.category.platform.label}    ${downloadStateLabel(job)}", color = Mint, style = MaterialTheme.typography.bodySmall)
                        }
                        if (job.folderUri == null && (job.exported || job.licenseExported)) Text(
                            listOfNotNull(if (job.exported) "PKG exported" else null,
                                if (job.licenseExported) "${job.licenseLabel} exported" else null).joinToString("    "),
                            color = Color(0xFFB7C5D9), style = MaterialTheme.typography.bodySmall)
                        if (job.state == JobState.RUNNING || job.state == JobState.PAUSED) {
                            LinearProgressIndicator(progress = { if (job.total != null && job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                            if (!wide) Text("${sizeLabel(job.bytes)} / ${sizeLabel(job.total)}    ${sizeLabel(job.speed)}/s", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!job.error.isNullOrBlank()) Text(job.error, color = MaterialTheme.colorScheme.error)
                        if (job.updateCheck == "FAILED" && job.state != JobState.CANCELED)
                            Text("Update: ${job.updateError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (!wide) actions()
                    }
                }
            }
        }
    }
    if (clearHistory) ClearHistoryDialog(onClear = {
        clearHistory = false
        scope.launch { repository.clearDownloadHistory(); navigationFocus.requestFocus() }
    }, onDismiss = { clearHistory = false; historyFocus.requestFocus() })
    deleteTarget?.let { job -> DeleteFilesDialog(job.displayTitle,
        onDelete = { repository.deleteCompleted(job); deleteTarget = null }, onDismiss = { deleteTarget = null }) }
}

private fun downloadStateLabel(job: DownloadJob): String = when {
    job.state == JobState.RUNNING && job.updateCheck == "PENDING" -> "Checking update"
    job.state == JobState.DOWNLOADED && job.folderUri != null -> "Saved"
    else -> job.state.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
internal fun AboutRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocus().clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
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

internal fun reportDate(timestamp: Long): String = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    .format(java.util.Date(timestamp * 1000))
