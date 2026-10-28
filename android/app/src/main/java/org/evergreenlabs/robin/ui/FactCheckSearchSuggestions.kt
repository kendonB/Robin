package org.evergreenlabs.robin.ui

import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.evergreenlabs.robin.security.FactCheckOutputPolicy
import org.evergreenlabs.robin.security.HardenedWebView
import org.evergreenlabs.robin.security.WebUrlPolicy
import java.io.ByteArrayInputStream

/**
 * Google's grounding display rules require the supplied HTML/CSS appearance:
 * https://firebase.google.com/docs/ai-logic/grounding-google-search
 * Render it as inert, offline content under an opaque data origin. This does not create
 * a separate cookie store: script/storage denial and complete resource/network blocking
 * prevent this document from accessing or sending the app's authenticated cookies.
 */
@Composable
internal fun FactCheckSearchSuggestions(
    html: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rendererUnavailable by remember { mutableStateOf(false) }
    val openUrl by rememberUpdatedState(onOpenUrl)
    if (html.length > FactCheckOutputPolicy.MAX_RESPONSE_CHARS) return
    if (rendererUnavailable) {
        Text("Search suggestions are unavailable. The fact-check result is still shown.", modifier = modifier)
        return
    }
    val policy = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; " +
        "img-src data:; frame-src 'none'; child-src 'none'; connect-src 'none'; " +
        "font-src 'none'; media-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    val document = "<meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" + html

    AndroidView(
        modifier = modifier,
        factory = { context ->
            HardenedWebView(context).apply {
                tag = SuggestionsViewState()
                settings.javaScriptEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportMultipleWindows(false)
                filterTouchesWhenObscured = true
                setBackgroundColor(Color.TRANSPARENT)
                setDownloadListener { _, _, _, _, _ -> /* Downloads are never authorised here. */ }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }

                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?,
                    ) { callback?.invoke(origin.orEmpty(), false, false) }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        return true
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse = emptyResource()

                    @Deprecated("Fallback for older WebView callbacks; no resources are allowed")
                    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse = emptyResource()

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if ((view as? HardenedWebView)?.isReleased == false &&
                            request?.isForMainFrame == true && request.hasGesture()
                        ) {
                            WebUrlPolicy.safeHttpsUrl(request.url.toString())?.let(openUrl)
                        }
                        return true
                    }

                    @Deprecated("No gesture information is available, so fail closed")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // Multiple WebViews may share a renderer. Clean up only
                        // this instance; do not retry potentially crashing HTML.
                        rendererUnavailable = true
                        releaseSuggestionsView(view, rendererGone = true)
                        return true
                    }
                }
            }
        },
        onReset = null,
        onRelease = { webView -> releaseSuggestionsView(webView) },
        update = { webView ->
            val state = webView.tag as SuggestionsViewState
            if (!webView.isReleased && !state.released && state.document != document) {
                state.document = document
                // loadData produces an opaque data: origin, never an authenticated Google origin.
                webView.loadData(Base64.encodeToString(document.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), "text/html", "base64")
            }
        },
    )
}

private class SuggestionsViewState {
    var document: String? = null
    var released = false
}

private fun releaseSuggestionsView(webView: WebView, rendererGone: Boolean = false) {
    val state = webView.tag as SuggestionsViewState
    if (state.released) return
    state.released = true
    state.document = null
    (webView as HardenedWebView).release(rendererGone)
}

private fun emptyResource(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
