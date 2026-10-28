package org.evergreenlabs.robin.security

import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Call inside a Compose dialog's content so the actual dialog window is protected. */
@Composable
fun ProtectDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
        val originalFilter = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window?.setHideOverlayWindows(true)

        // Activity.dispatchTouchEvent does not receive touches for a dialog.
        // View.filterTouchesWhenObscured alone covers only the fully obscured flag.
        val originalCallback = window?.callback
        val guardedCallback = originalCallback?.let { delegate ->
            object : Window.Callback by delegate {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                    if (event.flags and obscured != 0) return true
                    return delegate.dispatchTouchEvent(event)
                }
            }
        }
        if (window != null && guardedCallback != null) window.callback = guardedCallback

        onDispose {
            if (window != null && originalCallback != null && window.callback === guardedCallback) {
                window.callback = originalCallback
            }
            view.filterTouchesWhenObscured = originalFilter
            // Keep privacy flags until the window itself is destroyed.
        }
    }
}
