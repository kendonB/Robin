package org.evergreenlabs.robin

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import org.evergreenlabs.robin.ui.RootScreen
import org.evergreenlabs.robin.ui.theme.RobinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen can contain an authenticated X session or a password.
        // This also keeps its contents out of Android's recent-apps snapshot.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()

        setContent {
            RobinTheme {
                Surface {
                    RootScreen()
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Consume both fully and partially obscured touches. In particular, an
        // overlay must not be able to trick a tap on native AI confirmation.
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured != 0) return true
        return super.dispatchTouchEvent(event)
    }
}
