package org.pkgj.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun KeyEvent.asActivate() = KeyEvent(downTime, eventTime, action, KeyEvent.KEYCODE_DPAD_CENTER,
    repeatCount, metaState, deviceId, scanCode, flags, InputDevice.SOURCE_DPAD)

/** One routing table for the app, with modal scroll targets taking precedence. */
internal class ControllerBindings {
    var section: (Int) -> Unit = {}
    var motion: (MotionEvent, View) -> Boolean = { _, _ -> false }
    var stopMotion: () -> Unit = {}
    var triggerHeld: (Int) -> Boolean = { false }
    val pages = linkedMapOf<Any, (Int) -> Unit>()
    private var digitalJob: Job? = null
    private var digitalHeld = 0
    fun releasePage(direction: Int? = null) {
        if (direction == null || direction == digitalHeld) { digitalJob?.cancel(); digitalJob = null; digitalHeld = 0 }
    }
    fun holdPage(direction: Int, view: View, scope: CoroutineScope) {
        if (digitalHeld == direction && digitalJob?.isActive == true) return
        releasePage(); digitalHeld = direction
        page(direction, 0)
        digitalJob = scope.launch {
            delay(300)
            var repeat = 1
            while (isActive && view.hasWindowFocus()) {
                if (!triggerHeld(direction)) page(direction, repeat++)
                delay(130)
            }
            if (digitalHeld == direction) { digitalHeld = 0; digitalJob = null }
        }
    }
    private var lastPageAt = 0L
    private var lastPageDirection = 0
    fun page(direction: Int, repeat: Int) {
        val now = SystemClock.uptimeMillis()
        if (direction == lastPageDirection && now - lastPageAt < 65) return
        lastPageAt = now; lastPageDirection = direction
        pages.values.lastOrNull()?.invoke(direction * if (repeat >= 5) 3 else 1)
    }
}

internal val LocalController = staticCompositionLocalOf<ControllerBindings?> { null }

@Composable
internal fun PageScrollTarget(onPage: (Int) -> Unit) {
    val bindings = LocalController.current
    val latest by rememberUpdatedState(onPage)
    DisposableEffect(bindings) {
        val owner = Any()
        bindings?.pages?.set(owner) { latest(it) }
        onDispose { bindings?.pages?.remove(owner) }
    }
}

/** Explicit focus and a visible ring also work when sticks are used after touch input. */
internal fun Modifier.controllerFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    focusProperties { canFocus = true }.onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(BorderStroke(2.dp, Mint), RoundedCornerShape(8.dp)) else Modifier)
}

// Popup and dialog windows receive input separately from the activity.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.gamepadKeys(onBack: () -> Unit): Modifier = composed {
    val view = LocalView.current
    val bindings = LocalController.current
    val inputMode = LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    DisposableEffect(view, bindings) {
        view.setOnGenericMotionListener { target, event -> bindings?.motion?.invoke(event, target) ?: false }
        bindings?.stopMotion?.invoke(); bindings?.releasePage()
        onDispose { view.setOnGenericMotionListener(null); bindings?.stopMotion?.invoke(); bindings?.releasePage() }
    }
    onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
            key.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_R2)
            inputMode.requestInputMode(InputMode.Keyboard)
        when (key.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> { view.dispatchKeyEvent(key.asActivate()); true }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onBack()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0)
                    bindings?.section?.invoke(if (key.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1)
                true
            }
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> {
                val direction = if (key.keyCode == KeyEvent.KEYCODE_BUTTON_L2) -1 else 1
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 && bindings?.triggerHeld?.invoke(direction) != true)
                    bindings?.holdPage(direction, view, scope)
                if (key.action == KeyEvent.ACTION_UP) bindings?.releasePage(direction)
                true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (key.action == KeyEvent.ACTION_DOWN)
                    bindings?.page(if (key.keyCode == KeyEvent.KEYCODE_PAGE_UP) -1 else 1, key.repeatCount)
                true
            }
            else -> false
        }
    }
}

/** Both digital and analog trigger mappings follow Android's controller axes. */
internal class AnalogNavigation(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var heldKey: Int? = null
    fun stop() { job?.cancel(); job = null; heldKey = null }
    fun triggerHeld(direction: Int) = heldKey == if (direction < 0) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
    fun handle(event: MotionEvent, target: View): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val rightY = event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { kotlin.math.abs(it) > .55f }
            ?: event.getAxisValue(MotionEvent.AXIS_RY)
        val x = event.getAxisValue(MotionEvent.AXIS_X).takeIf { kotlin.math.abs(it) > .55f } ?: event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y).takeIf { kotlin.math.abs(it) > .55f } ?: event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val key = when {
            right > .55f || rightY > .65f -> KeyEvent.KEYCODE_PAGE_DOWN
            left > .55f || rightY < -.65f -> KeyEvent.KEYCODE_PAGE_UP
            y > .55f -> KeyEvent.KEYCODE_DPAD_DOWN
            y < -.55f -> KeyEvent.KEYCODE_DPAD_UP
            x > .55f -> KeyEvent.KEYCODE_DPAD_RIGHT
            x < -.55f -> KeyEvent.KEYCODE_DPAD_LEFT
            else -> null
        }
        if (key != heldKey) {
            stop(); heldKey = key
            if (key != null) job = scope.launch {
                var repeat = 0
                fun emit() {
                    val now = SystemClock.uptimeMillis()
                    target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, repeat, 0, -1, 0, 0, InputDevice.SOURCE_DPAD))
                    target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, repeat++, 0, -1, 0, 0, InputDevice.SOURCE_DPAD))
                }
                emit(); delay(300)
                while (isActive) { emit(); delay(130) }
            }
        }
        return true
    }
}
