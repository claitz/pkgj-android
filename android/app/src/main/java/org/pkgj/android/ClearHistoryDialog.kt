package org.pkgj.android

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
internal fun ClearHistoryDialog(onClear: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) cancel.requestFocus() }
    AlertDialog(modifier = Modifier.gamepadKeys(onDismiss), onDismissRequest = onDismiss,
        title = { Text("Clear history?") },
        text = { PageScrollTarget { }; Text("Remove saved and canceled downloads from the list. Files are kept.") },
        confirmButton = { TextButton(onClick = onClear, modifier = Modifier.controllerFocus()) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel).controllerFocus()
            .onGloballyPositioned { ready = true }) { Text("Cancel") } })
}
