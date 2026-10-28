package org.evergreenlabs.robin.security

import android.content.Context
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** Web content must not receive touches obscured by another application. */
class HardenedWebView(context: Context) : WebView(context) {
    @Volatile // shouldInterceptRequest reads this on WebView's worker thread.
    var isResettingSession: Boolean = false
        internal set
    internal var onSessionBlankCommitted: (() -> Unit)? = null

    init {
        filterTouchesWhenObscured = true
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        val obscuredFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        return event.flags and obscuredFlags == 0 && super.onFilterTouchEventForSecurity(event)
    }

    /** Only an app-initiated reset can use an about:blank main document. */
    fun completeSessionResetIfBlank(url: String?): Boolean {
        if (!isResettingSession || url != "about:blank") return false
        val complete = onSessionBlankCommitted
        onSessionBlankCommitted = null
        complete?.invoke()
        return true
    }
}

object WebViewHardening {
    private const val SESSION_PREFERENCES = "web_session_security"
    private const val RESET_PENDING = "reset_pending"

    /** A process exit during sign-out must not silently resume the old session. */
    fun isSessionResetPending(context: Context): Boolean =
        context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(RESET_PENDING, false)

    fun recordSessionReset(context: Context, pending: Boolean): Boolean =
        context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(RESET_PENDING, pending).commit()

    fun configureServiceWorkers(blockNetwork: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)
        ) return false
        return runCatching {
            val settings = ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = blockNetwork
        }.isSuccess
    }

    fun setServiceWorkerNetworkBlocked(blocked: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)
        ) return false
        return runCatching {
            ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.blockNetworkLoads = blocked
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    fun configure(webView: WebView, supportsPopups: Boolean) {
        WebView.setWebContentsDebuggingEnabled(false)
        val resetPending = isSessionResetPending(webView.context)
        with(webView.settings) {
            javaScriptEnabled = !resetPending // Required by x.com; native messages are separately scoped.
            blockNetworkLoads = resetPending
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(supportsPopups)
        }
        CookieManager.getInstance().setAcceptCookie(!resetPending)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'"),
        ByteArrayInputStream("Robin blocked this page because it is outside the supported secure sites.".toByteArray()),
    )
}
