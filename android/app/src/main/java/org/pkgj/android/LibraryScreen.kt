package org.pkgj.android

import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.ScrollState
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.CompatibilityReport
import org.pkgj.android.data.Repository

private val CompatibilityOptions = listOf("All", "Playable", "Ingame +", "Ingame -", "Menu", "Intro", "Bootable", "Nothing", "Unreported")
private val CategoryNames = mapOf("All" to "All content", "GAMES" to "Games", "DLCS" to "DLC", "DEMOS" to "Demos", "THEMES" to "Themes")

@Composable
internal fun LibraryScreen(repository: Repository, listState: LazyListState,
    compatibility: Map<String, CompatibilityReport>, filter: CatalogFilter, pages: Flow<PagingData<CatalogItem>>,
    navigationFocus: FocusRequester, onFilter: (CatalogFilter) -> Unit, onSelect: (CatalogItem) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val search = filter.search
    val regions = filter.regions
    val category = filter.category
    val sort = filter.sort
    val statuses = filter.compatibilityStatuses
    val syncing by repository.syncing.collectAsState()
    val paging = pages.collectAsLazyPagingItems()
    val searchFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val pageFocus = remember { FocusRequester() }
    var pendingPageIndex by remember { mutableStateOf<Int?>(null) }
    var pageJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    PageScrollTarget { pages ->
        if (paging.itemCount > 0) {
            pageJob?.cancel()
            val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(2) - 1
            val target = (listState.firstVisibleItemIndex + visible * pages).coerceIn(0, paging.itemCount - 1)
            pendingPageIndex = target
            pageJob = scope.launch {
                keyboard?.hide()
                listState.scrollToItem(target)
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == target } && paging.peek(target) != null }.first { it }
                withFrameNanos { }
                pageFocus.requestFocus()
            }
        }
    }
    var searchFocused by remember { mutableStateOf(false) }
    var listReady by remember { mutableStateOf(false) }
    var initialFocusApplied by remember { mutableStateOf(false) }
    LaunchedEffect(listReady) {
        if (listReady && !initialFocusApplied) {
            if (!searchFocused) listFocus.requestFocus()
            initialFocusApplied = true
        }
    }
    val filterNavigation = Modifier.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && listReady && paging.itemCount > 0) {
            if (key.action == KeyEvent.ACTION_DOWN) listFocus.requestFocus()
            true
        } else false
    }
    BackHandler(enabled = searchFocused) { keyboard?.hide(); categoryFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)
        .onPreviewKeyEvent { initialFocusApplied = true; false }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.changedToDownIgnoreConsumed() }) initialFocusApplied = true
                }
            }
        }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val field: @Composable (Modifier) -> Unit = { fieldModifier ->
                BasicTextField(search, { onFilter(filter.copy(search = it)) }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(Mint),
                    singleLine = true, modifier = fieldModifier.heightIn(min = 44.dp)
                        .focusRequester(searchFocus).onFocusChanged { searchFocused = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || key.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                if (key.action == KeyEvent.ACTION_DOWN) {
                                    keyboard?.hide()
                                    if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) categoryFocus.requestFocus()
                                    else navigationFocus.requestFocus()
                                }
                                true
                            } else false
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); if (listReady && paging.itemCount > 0) listFocus.requestFocus() else categoryFocus.requestFocus() }),
                    decorationBox = { inner ->
                        Row(Modifier.border(1.dp, if (searchFocused) Mint else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(SearchIcon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f)) {
                                if (search.isEmpty()) Text("Search games or title ID", style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                            if (search.isNotEmpty()) TextButton(onClick = { onFilter(filter.copy(search = "")) },
                                modifier = Modifier.controllerFocus().height(32.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Clear") }
                            else if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    })
            }
            val filters: @Composable (Modifier) -> Unit = { filterModifier ->
                Row(filterModifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterMenu("Category", category, listOf("GAMES", "DLCS", "DEMOS", "THEMES", "All"),
                        label = { CategoryNames.getValue(it) }, onSelect = { onFilter(filter.copy(category = it, compatibilityStatuses = emptySet())) },
                        modifier = Modifier.focusRequester(categoryFocus).focusProperties { up = searchFocus; if (listReady && paging.itemCount > 0) down = listFocus }.then(filterNavigation))
                    MultiFilterMenu("Region", regions, listOf("US", "EU", "JP", "ASIA"), "All regions",
                        modifier = Modifier.focusProperties { up = searchFocus; if (listReady && paging.itemCount > 0) down = listFocus }.then(filterNavigation),
                        onSelect = { onFilter(filter.copy(regions = it)) })
                    if (category == Category.GAMES.name) MultiFilterMenu("Compatibility", statuses, CompatibilityOptions.filter { it != "All" }, "Any status",
                        color = ::compatibilityColor,
                        modifier = Modifier.focusProperties { up = searchFocus; if (listReady && paging.itemCount > 0) down = listFocus }.then(filterNavigation),
                        onSelect = { onFilter(filter.copy(compatibilityStatuses = it)) })
                    FilterMenu("Sort", sort, listOf("Title", "Size"), label = { "Sort: $it" }, onSelect = { onFilter(filter.copy(sort = it)) },
                        modifier = Modifier.focusProperties { up = searchFocus; if (listReady && paging.itemCount > 0) down = listFocus }.then(filterNavigation))
                }
            }
            if (maxWidth >= 760.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.weight(1f)); filters(Modifier.weight(1.8f))
            } else Column { field(Modifier.fillMaxWidth()); filters(Modifier.fillMaxWidth()) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        when {
            paging.loadState.refresh is LoadState.Loading && paging.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            paging.loadState.refresh is LoadState.Error -> Column(Modifier.padding(24.dp)) {
                Text("Couldn’t load the library")
                TextButton(onClick = { paging.retry() }) { Text("Retry") }
            }
            paging.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (syncing) "Updating catalog" else "No matches", style = MaterialTheme.typography.titleMedium)
                    if (!syncing) TextButton(onClick = { onFilter(filter.copy(search = "", regions = emptySet(), compatibilityStatuses = emptySet())) }) { Text("Clear filters") }
                }
            }
            else -> LazyColumn(modifier = Modifier.focusGroup(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(count = paging.itemCount, key = paging.itemKey { "${it.category}:${it.contentId}" }) { index ->
                    val item = paging[index] ?: return@items
                    LibraryRow(item, showCategory = category == "All", compact = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE, report = if (item.category == Category.GAMES) compatibility[item.titleId] else null,
                        modifier = (if (index == listState.firstVisibleItemIndex) Modifier.focusRequester(listFocus).onGloballyPositioned { listReady = true } else Modifier)
                            .then(if (index == pendingPageIndex) Modifier.focusRequester(pageFocus) else Modifier)
                            .focusProperties { if (index == 0) up = categoryFocus }) { onSelect(item) }
                }
            }
        }
    }
}

@Composable
internal fun FilterMenu(name: String, value: String, options: List<String>, label: (String) -> String,
    color: (String) -> Color? = { null }, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val menuScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box {
        TextButton(modifier = modifier.controllerFocus(), onClick = { keyboard?.hide(); focusManager.clearFocus(); expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = if (value == "All") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)) {
            color(value)?.let { StatusDot(it); Spacer(Modifier.width(7.dp)) }
            Text(label(value), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(ChevronIcon, contentDescription = name, modifier = Modifier.size(16.dp))
        }
        if (expanded) PageScrollTarget { pages -> scope.launch { menuScroll.animateScrollTo((menuScroll.value + pages * menuScroll.viewportSize.coerceAtLeast(120)).coerceIn(0, menuScroll.maxValue)) } }
        DropdownMenu(scrollState = menuScroll, expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.gamepadKeys { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        color(option)?.let { StatusDot(it); Spacer(Modifier.width(10.dp)) }
                        Text(label(option), fontWeight = if (option == value) FontWeight.Bold else FontWeight.Normal)
                    }
                }, trailingIcon = { if (option == value) Icon(CheckIcon, contentDescription = "Selected", tint = Mint) },
                    onClick = { onSelect(option); expanded = false }, modifier = Modifier.controllerFocus().widthIn(min = 180.dp))
            }
        }
    }
}

@Composable
private fun MultiFilterMenu(name: String, selected: Set<String>, options: List<String>, allLabel: String,
    modifier: Modifier = Modifier, color: (String) -> Color? = { null }, onSelect: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val menuScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val label = when (selected.size) {
        0 -> allLabel
        1 -> selected.single()
        else -> if (name == "Region") options.filter { it in selected }.joinToString(", ") else "${selected.size} statuses"
    }
    Box {
        TextButton(modifier = modifier.controllerFocus(), onClick = { keyboard?.hide(); focusManager.clearFocus(); expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            if (selected.size == 1) color(selected.single())?.let { StatusDot(it); Spacer(Modifier.width(7.dp)) }
            Text(label, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(ChevronIcon, contentDescription = name, modifier = Modifier.size(16.dp))
        }
        if (expanded) PageScrollTarget { pages -> scope.launch { menuScroll.animateScrollTo((menuScroll.value + pages * menuScroll.viewportSize.coerceAtLeast(120)).coerceIn(0, menuScroll.maxValue)) } }
        DropdownMenu(scrollState = menuScroll, expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.gamepadKeys { expanded = false }) {
            DropdownMenuItem(text = { Text(allLabel) },
                leadingIcon = { Checkbox(selected.isEmpty(), onCheckedChange = null) }, onClick = { onSelect(emptySet()) }, modifier = Modifier.controllerFocus())
            options.forEach { option ->
                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    color(option)?.let { StatusDot(it); Spacer(Modifier.width(8.dp)) }
                    Text(option)
                } }, leadingIcon = { Checkbox(option in selected, onCheckedChange = null) },
                    onClick = { onSelect(if (option in selected) selected - option else selected + option) }, modifier = Modifier.controllerFocus())
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Done", color = Mint) }, onClick = { expanded = false }, modifier = Modifier.controllerFocus())
        }
    }
}

@Composable
private fun LibraryRow(item: CatalogItem, showCategory: Boolean, compact: Boolean, report: CompatibilityReport?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.controllerFocus().fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        color = if (focused) SurfaceInk else Color.Transparent, shape = RoundedCornerShape(8.dp),
        border = if (focused) BorderStroke(2.dp, Mint) else null) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.titleId, item.region, if (showCategory) CategoryNames.getValue(item.category.name) else null).joinToString("    "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (report != null) CompatibilityPill(report.status)
                Text(item.size?.let(::sizeLabel) ?: "", modifier = Modifier.width(68.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (report != null) CompatibilityPill(report.status)
                item.size?.let { Text(sizeLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .22f))
}

internal fun compatibilityColor(status: String): Color = when (status) {
    "Playable" -> Color(0xFF60C783)
    "Ingame +" -> Color(0xFFA2C65C)
    "Ingame -" -> Color(0xFFE4BF56)
    "Menu" -> Color(0xFFEB9B58)
    "Intro" -> Color(0xFFEC7F70)
    "Bootable" -> Color(0xFFD76575)
    else -> Color(0xFF9AA3B0)
}

@Composable
private fun StatusDot(color: Color) { Box(Modifier.size(8.dp).background(color, CircleShape)) }

@Composable
internal fun CompatibilityPill(status: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        status.split(" / ").forEach { label ->
            val color = compatibilityColor(label)
            Surface(color = color.copy(alpha = .14f), contentColor = color, shape = CircleShape) {
                Text(label, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun glyph(draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    path(fill = SolidColor(Color.White), pathBuilder = draw)
}.build()

private val ChevronIcon = glyph { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f); lineTo(16.5f, 7.5f); lineTo(12f, 12f); lineTo(7.5f, 7.5f); close() }
private val CheckIcon = glyph { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f); lineTo(18.5f, 4.5f); lineTo(9f, 14f); lineTo(5.5f, 10.5f); close() }
private val SearchIcon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    path(stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
        moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f); arcTo(7f, 7f, 0f, true, true, 17f, 10f)
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
}.build()
