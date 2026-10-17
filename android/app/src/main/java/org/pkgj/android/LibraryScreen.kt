package org.pkgj.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
    onFilter: (CatalogFilter) -> Unit, onSelect: (CatalogItem) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val search = filter.search
    val region = filter.region
    val category = filter.category
    val sort = filter.sort
    val status = filter.compatibilityStatus
    val syncing by repository.syncing.collectAsState()
    val paging = pages.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(search, { onFilter(filter.copy(search = it)) }, placeholder = { Text("Search games or title ID") },
            singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }),
            leadingIcon = { Icon(SearchIcon, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) TextButton(onClick = { onFilter(filter.copy(search = "")) }) { Text("Clear") }
                else if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterMenu("Category", category, listOf("GAMES", "DLCS", "DEMOS", "THEMES", "All"),
                label = { CategoryNames.getValue(it) }, onSelect = { onFilter(filter.copy(category = it, compatibilityStatus = "All")) })
            FilterMenu("Region", region, listOf("All", "US", "EU", "JP", "ASIA"),
                label = { if (it == "All") "All regions" else it }, onSelect = { onFilter(filter.copy(region = it)) })
            if (category == Category.GAMES.name) FilterMenu("Compatibility", status, CompatibilityOptions,
                label = { if (it == "All") "Any status" else it }, color = { if (it == "All") null else compatibilityColor(it) },
                onSelect = { onFilter(filter.copy(compatibilityStatus = it)) })
            FilterMenu("Sort", sort, listOf("Title", "Size"), label = { "Sort: $it" }, onSelect = { onFilter(filter.copy(sort = it)) })
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
                    if (!syncing) TextButton(onClick = { onFilter(filter.copy(search = "", region = "All", compatibilityStatus = "All")) }) { Text("Clear filters") }
                }
            }
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(count = paging.itemCount, key = paging.itemKey { "${it.category}:${it.contentId}" }) { index ->
                    val item = paging[index] ?: return@items
                    LibraryRow(item, if (item.category == Category.GAMES) compatibility[item.titleId] else null) { onSelect(item) }
                }
            }
        }
    }
}

@Composable
internal fun FilterMenu(name: String, value: String, options: List<String>, label: (String) -> String,
    color: (String) -> Color? = { null }, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box {
        TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus(); expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = if (value == "All") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)) {
            color(value)?.let { StatusDot(it); Spacer(Modifier.width(7.dp)) }
            Text(label(value), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(ChevronIcon, contentDescription = name, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        color(option)?.let { StatusDot(it); Spacer(Modifier.width(10.dp)) }
                        Text(label(option), fontWeight = if (option == value) FontWeight.Bold else FontWeight.Normal)
                    }
                }, trailingIcon = { if (option == value) Icon(CheckIcon, contentDescription = "Selected", tint = Mint) },
                    onClick = { onSelect(option); expanded = false }, modifier = Modifier.widthIn(min = 180.dp))
            }
        }
    }
}

@Composable
private fun LibraryRow(item: CatalogItem, report: CompatibilityReport?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        color = if (focused) SurfaceInk else Color.Transparent, shape = RoundedCornerShape(8.dp),
        border = if (focused) BorderStroke(2.dp, Mint) else null) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.titleId, item.region, CategoryNames.getValue(item.category.name)).joinToString("    "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
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

internal val NavigationIcons = listOf(
    glyph { moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 7f); lineTo(3f, 7f); close(); moveTo(3f, 10f); lineTo(21f, 10f); lineTo(21f, 14f); lineTo(3f, 14f); close(); moveTo(3f, 17f); lineTo(21f, 17f); lineTo(21f, 21f); lineTo(3f, 21f); close() },
    glyph { moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 12f); lineTo(17f, 8f); lineTo(18.5f, 9.5f); lineTo(12f, 16f); lineTo(5.5f, 9.5f); lineTo(7f, 8f); lineTo(11f, 12f); close(); moveTo(4f, 18f); lineTo(20f, 18f); lineTo(20f, 21f); lineTo(4f, 21f); close() },
    glyph { moveTo(3f, 6f); lineTo(7f, 6f); lineTo(7f, 3f); lineTo(9f, 3f); lineTo(9f, 11f); lineTo(7f, 11f); lineTo(7f, 8f); lineTo(3f, 8f); close(); moveTo(12f, 6f); lineTo(21f, 6f); lineTo(21f, 8f); lineTo(12f, 8f); close(); moveTo(3f, 16f); lineTo(12f, 16f); lineTo(12f, 18f); lineTo(3f, 18f); close(); moveTo(15f, 12f); lineTo(17f, 12f); lineTo(17f, 16f); lineTo(21f, 16f); lineTo(21f, 18f); lineTo(17f, 18f); lineTo(17f, 21f); lineTo(15f, 21f); close() }
)
private val ChevronIcon = glyph { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f); lineTo(16.5f, 7.5f); lineTo(12f, 12f); lineTo(7.5f, 7.5f); close() }
private val CheckIcon = glyph { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f); lineTo(18.5f, 4.5f); lineTo(9f, 14f); lineTo(5.5f, 10.5f); close() }
private val SearchIcon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    path(stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
        moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f); arcTo(7f, 7f, 0f, true, true, 17f, 10f)
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
}.build()
