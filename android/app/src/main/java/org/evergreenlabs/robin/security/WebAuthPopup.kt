package org.evergreenlabs.robin.security

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.http.SslError
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/** A narrowly scoped, visibly identified authentication window with no native JS bridge. */
object WebAuthPopup {
    private val activePopups = mutableMapOf<WebView, () -> Unit>()
    private val sessionGenerations = WeakHashMap<WebView, Long>()

    /** Cancel pending OAuth completion as well as the visible window before logout. */
    fun closeFor(host: WebView?) {
        if (host == null) return
        sessionGenerations[host] = (sessionGenerations[host] ?: 0L) + 1L
        activePopups.remove(host)?.invoke()
    }

    fun open(
        host: WebView?,
        isUserGesture: Boolean,
        resultMessage: Message?,
        onBlocked: (String) -> Unit,
        openExternal: (String) -> Unit,
    ): Boolean {
        if (host == null || !isUserGesture || !WebUrlPolicy.isTrustedMainFrameUrl(host.url)) {
            // A website must not be able to spam native dialogs without a tap.
            return false
        }
        if (activePopups.containsKey(host) || (host is HardenedWebView && host.isResettingSession)) return false
        val sessionGeneration = (sessionGenerations[host] ?: 0L) + 1L
        sessionGenerations[host] = sessionGeneration
        val message = resultMessage ?: return false
        val transport = message.obj as? WebView.WebViewTransport ?: return false
        val hitUrl = host.hitTestResult.extra
        if (!hitUrl.isNullOrBlank()) {
            val safe = WebUrlPolicy.safeHttpsUrl(hitUrl)
            if (safe == null) {
                onBlocked("Robin blocked an unsupported link. Use your browser to open external content.")
                return false
            }
            when {
                WebUrlPolicy.isFeedUrl(safe) -> {
                    host.loadUrl(safe)
                    return false
                }
                !WebUrlPolicy.isAuthUrl(safe) -> {
                    openExternal(safe)
                    return false
                }
            }
        }

        val context = host.context
        val popup = HardenedWebView(context).apply {
            WebViewHardening.configure(this, supportsPopups = false)
            // window.open may start with about:blank. Never show that document:
            // an untrusted iframe could write an imitation login form into it.
            visibility = View.INVISIBLE
        }
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            filterTouchesWhenObscured = true
        }
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            filterTouchesWhenObscured = true
        }
        val originLabel = TextView(context).apply {
            text = "Opening secure sign-in…"
            setTextColor(Color.BLACK)
            setTextSize(14f)
            setPadding(0, 0, dp(8), 0)
        }
        val close = Button(context).apply {
            text = "Close"
            filterTouchesWhenObscured = true
        }
        toolbar.addView(originLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(popup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var closed = false
        var trustedDocumentCommitted = false
        var sawAuthProvider = false
        var completionStarted = false
        var destructionQueued = false
        var destroyed = false
        var closeImmediately: (() -> Unit)? = null

        fun destroyNow() {
            if (destroyed) return
            destroyed = true
            if (activePopups[host] === closeImmediately) activePopups.remove(host)
            runCatching {
                popup.settings.javaScriptEnabled = false
                popup.stopLoading()
                root.removeView(popup)
                popup.destroy()
            }
        }

        fun destroyAfterMessages() {
            if (destructionQueued) return
            destructionQueued = true
            closed = true
            // Allow the provider's already-issued postMessage to the X opener
            // to finish. Keep the window registered until destruction so a
            // concurrent logout can cancel even this brief grace period.
            host.postDelayed({ destroyNow() }, 400)
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss() else destroyAfterMessages()
        }

        fun block(message: String) {
            if (closed) return
            popup.visibility = View.INVISIBLE
            popup.stopLoading()
            dismiss()
            onBlocked(message)
        }

        fun hasSession(): Boolean {
            // Do not log cookies or use the non-authenticating twid identifier
            // as evidence of a completed sign-in.
            val cookies = CookieManager.getInstance().getCookie("https://x.com").orEmpty()
            return cookies.split(';').any { cookie ->
                val entry = cookie.trim()
                entry.startsWith("auth_token=") && entry.substringAfter('=').isNotEmpty()
            }
        }

        fun completeAuth() {
            if (completionStarted) return
            completionStarted = true
            CookieManager.getInstance().flush()
            dismiss()
            if (!sawAuthProvider) return
            var attempts = 0
            val poll = object : Runnable {
                override fun run() {
                    if (!host.isAttachedToWindow || (sessionGenerations[host] ?: 0L) != sessionGeneration) return
                    CookieManager.getInstance().flush()
                    if (hasSession()) {
                        host.loadUrl("https://x.com/home")
                    } else if (++attempts < 40) {
                        host.postDelayed(this, 500)
                    }
                }
            }
            host.postDelayed(poll, 500)
        }

        popup.setDownloadListener { _, _, _, _, _ ->
            block("Downloads are not allowed during sign-in.")
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) = completeAuth()

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin.orEmpty(), false, false)
            }
        }
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return true
                val url = request.url.toString()
                if (!request.isForMainFrame) {
                    return !url.startsWith("https://", ignoreCase = true) && url != "about:blank"
                }
                if (WebUrlPolicy.isTrustedMainFrameUrl(url)) return false
                if (url == "about:blank" && !trustedDocumentCommitted) return false
                block("Robin blocked a sign-in page outside its supported secure sites. Close this window and sign in directly to X.")
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return WebViewHardening.blockedResponse()
                val isHttps = request.url.scheme.equals("https", ignoreCase = true)
                if (!isHttps || (request.isForMainFrame && !WebUrlPolicy.isTrustedMainFrameUrl(request.url.toString()))) {
                    if (request.isForMainFrame) host.post {
                        block("Robin blocked a sign-in page outside its supported secure sites.")
                    }
                    return WebViewHardening.blockedResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                popup.visibility = View.INVISIBLE
                if (url == "about:blank" && !trustedDocumentCommitted) return
                if (!WebUrlPolicy.isTrustedMainFrameUrl(url)) {
                    block("Robin blocked a sign-in page outside its supported secure sites.")
                    return
                }
                // Trusted native chrome contains only the validated origin,
                // never query strings, fragments or provider-supplied titles.
                originLabel.text = "Secure sign-in\n${WebUrlPolicy.origin(url)}"
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (closed) return
                if (WebUrlPolicy.isTrustedMainFrameUrl(url) &&
                    WebUrlPolicy.isTrustedMainFrameUrl(view?.url) &&
                    WebUrlPolicy.origin(url) == WebUrlPolicy.origin(view?.url)
                ) {
                    trustedDocumentCommitted = true
                    if (WebUrlPolicy.isAuthUrl(url)) sawAuthProvider = true
                    originLabel.text = "Secure sign-in\n${WebUrlPolicy.origin(url)}"
                    popup.visibility = View.VISIBLE
                } else {
                    popup.visibility = View.INVISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (closed || !WebUrlPolicy.isTrustedMainFrameUrl(url)) return
                if (WebUrlPolicy.isAuthUrl(url)) sawAuthProvider = true
                if (sawAuthProvider && WebUrlPolicy.isFeedUrl(url) && hasSession()) {
                    completeAuth()
                    return
                }
                val uri = url?.let(WebUrlPolicy::parseHttps) ?: return
                if (WebUrlPolicy.origin(url) == "https://accounts.google.com" &&
                    uri.path in setOf("/gsi/transform", "/gis/transform")
                ) {
                    // Some provider flows cannot close a window after several
                    // navigations. Keep a narrowly scoped completion fallback.
                    host.postDelayed({ if (!closed) completeAuth() }, 1_500)
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                block("Robin stopped sign-in because the secure connection could not be verified.")
            }

            override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
                callback?.backToSafety(false)
                block("Robin blocked a sign-in page flagged as unsafe.")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                closeFor(host)
                onBlocked("The sign-in window stopped. Close it and try again.")
                return true
            }
        }

        close.setOnClickListener { closeFor(host) }
        val cancelPopup: () -> Unit = {
            closed = true
            completionStarted = true
            destructionQueued = true
            dialog.setOnDismissListener(null)
            popup.visibility = View.INVISIBLE
            destroyNow()
            if (dialog.isShowing) dialog.dismiss()
        }
        closeImmediately = cancelPopup
        activePopups[host] = cancelPopup
        dialog.setContentView(root)
        dialog.setOnDismissListener { destroyAfterMessages() }
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setHideOverlayWindows(true)
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
        }
        // Attach before delivering WebViewTransport, preserving window.opener.
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        transport.webView = popup
        message.sendToTarget()
        host.postDelayed({
            if (!closed && !trustedDocumentCommitted) {
                block("Robin could not open a supported secure sign-in page. Close this window and sign in directly to X.")
            }
        }, 20_000)
        return true
    }
}
