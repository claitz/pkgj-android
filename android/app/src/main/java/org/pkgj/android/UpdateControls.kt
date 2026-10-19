package org.pkgj.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.pkgj.android.catalog.CatalogItem
import org.pkgj.android.data.Repository
import org.pkgj.android.updates.PatchCheck
import org.pkgj.android.updates.updateCheckError

@Composable
internal fun UpdateControls(item: CatalogItem, repository: Repository, onQueue: (CatalogItem) -> Unit) {
    var check by remember(item.titleId) { mutableStateOf<PatchCheck?>(null) }
    var checking by remember(item.titleId) { mutableStateOf(true) }
    var error by remember(item.titleId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(refresh: Boolean) {
        checking = true; error = null
        try { check = repository.checkPatch(item.titleId, refresh = refresh) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = updateCheckError(e) }
        finally { checking = false }
    }
    LaunchedEffect(item.titleId) {
        try { check = repository.cachedPatch(item.titleId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A fresh check can still succeed. */ }
        load(refresh = false)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val patch = check?.patch
            Text(patch?.let { "Update v${it.version}" } ?: "Update", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val details = if (patch != null) listOfNotNull(patch.size?.let(::sizeLabel),
                patch.requiredFirmware?.let { "Firmware $it" }).joinToString("    ")
                else if (checking) "Checking…" else if (error == null) "None available" else "Unavailable"
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        check?.patch?.let { patch ->
            TextButton(modifier = Modifier.controllerFocus(), onClick = { onQueue(patch.downloadItem(item)) }) { Text("Download update") }
        }
        IconButton(modifier = Modifier.controllerFocus(), onClick = { if (!checking) scope.launch { load(refresh = true) } }) {
            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, contentDescription = "Refresh update")
        }
    }
}
