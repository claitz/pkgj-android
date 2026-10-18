package org.pkgj.android

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
internal fun DeleteFilesDialog(title: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val keep = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready) keep.requestFocus() }
    AlertDialog(modifier = Modifier.gamepadKeys(onDismiss), onDismissRequest = onDismiss,
        title = { Text("Delete files?") }, text = { PageScrollTarget { }; Text(title) },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.controllerFocus()) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(keep).controllerFocus()
            .onGloballyPositioned { ready = true }) { Text("Keep") } })
}
