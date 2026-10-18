package org.pkgj.android

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun LicenseDialog(license: String, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val close = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) close.requestFocus() }
    AlertDialog(modifier = Modifier.gamepadKeys(onDismiss), onDismissRequest = onDismiss,
        title = { Text("BSD 2-Clause license") }, text = {
            PageScrollTarget { pages -> scope.launch { scroll.animateScrollTo((scroll.value + pages * scroll.viewportSize.coerceAtLeast(120)).coerceIn(0, scroll.maxValue)) } }
            Text(license, Modifier.heightIn(max = 420.dp).verticalScroll(scroll))
        }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(close).controllerFocus().onGloballyPositioned { ready = true }) { Text("Close") } })
}
