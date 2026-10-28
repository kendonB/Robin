package org.evergreenlabs.robin.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceResponse
import android.webkit.PermissionRequest
import android.net.http.SslError
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebStorageCompat
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.evergreenlabs.robin.Analytics
import org.evergreenlabs.robin.AppGraph
import org.evergreenlabs.robin.BuildConfig
import org.evergreenlabs.robin.security.HardenedWebView
import org.evergreenlabs.robin.security.WebAuthPopup
import org.evergreenlabs.robin.security.WebBridgeRateLimiter
import org.evergreenlabs.robin.security.WebBridgeScript
import org.evergreenlabs.robin.security.WebNativeMessage
import org.evergreenlabs.robin.security.WebNativeMessagePolicy
import org.evergreenlabs.robin.security.WebUrlPolicy
import org.evergreenlabs.robin.security.WebViewHardening
import org.evergreenlabs.robin.R
import org.evergreenlabs.robin.ui.theme.RobinCream
import org.evergreenlabs.robin.ui.theme.RobinHeaderDark
import org.evergreenlabs.robin.ui.theme.RobinRed
import org.evergreenlabs.robin.ui.theme.RobinRedLight
import org.evergreenlabs.robin.xfilter.FilterSettings
import org.evergreenlabs.robin.xfilter.XFilterInjector
import org.evergreenlabs.robin.services.FeedRefreshIntervalStore

private const val TAG = "XWebFeed"
private const val X_HOME = "https://x.com/home"
private const val NEAR_TOP_PX = 80
private const val SCROLL_TOP_BUTTON_PX = 400
private const val WARM_CROSSFADE_MS = 450

// Recheck inside each script as navigation can race an evaluateJavascript call.
private val FEED_SCRIPT_GUARD =
    "if(window.top!==window||${WebUrlPolicy.feedOrigins.joinToString(",", "[", "]") { "'$it'" }}.indexOf(location.origin)===-1)return;"

private val OPEN_COMPOSE_JS = """
(function() {
  $FEED_SCRIPT_GUARD
  try {
    if (typeof window.__ROBIN_OPEN_COMPOSE__ === 'function') {
      return window.__ROBIN_OPEN_COMPOSE__();
    }
    var selectors = [
      'a[data-testid="FloatingActionButton_compose"]',
      'a[data-testid="SideNav_NewTweet_Button"]',
      'a[href="/compose/post"]',
      'a[href="/compose/tweet"]',
      'a[href="/compose/post/"]',
      'a[href*="/compose/"]'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        el.click();
        return true;
      }
    }
    var inline = document.querySelector('[data-testid="tweetTextarea_0"]') ||
                 document.querySelector('div[role="textbox"][contenteditable="true"]');
    if (inline) {
      inline.focus();
      return true;
    }
    var a = document.createElement("a");
    a.href = "/compose/post";
    a.style.display = "none";
    document.body.appendChild(a);
    a.click();
    a.remove();
    return true;
  } catch (e) {
    return false;
  }
})();
""".trimIndent()

/** Reports scrollY from nested X containers (window scroll often stays at 0). */
private val SCROLL_PROBE_JS = """
(function(){
  $FEED_SCRIPT_GUARD
  if (window.__ROBIN_SCROLL_PROBE__) return;
  window.__ROBIN_SCROLL_PROBE__ = true;
  var lastPosted = -1;
  var pending = 0;
  var idleTimer = 0;
  function postNow(y) {
    if (y === lastPosted) return;
    lastPosted = y;
    try {
      if (window.RobinScroll && window.RobinScroll.post) {
        window.RobinScroll.post(y);
      }
    } catch (e) {}
  }
  // Post only when the back-to-top / near-top flags change, then once more
  // when scrolling pauses. This keeps native message traffic bounded.
  function report(y) {
    pending = Math.max(0, Math.round(y || 0));
    var show = pending >= 400;
    var near = pending <= 80;
    var prevShow = lastPosted >= 400;
    var prevNear = lastPosted >= 0 && lastPosted <= 80;
    if (lastPosted < 0 || show !== prevShow || near !== prevNear) postNow(pending);
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () { postNow(pending); }, 160);
  }
  function fromEvent(e) {
    var t = e && e.target;
    if (!t || t === document || t === window) {
      var r = document.scrollingElement || document.documentElement;
      report(r ? r.scrollTop : 0);
      return;
    }
    if (typeof t.scrollTop === 'number') report(t.scrollTop);
  }
  window.addEventListener('scroll', fromEvent, true);
  document.addEventListener('scroll', fromEvent, true);

  var lastInteract = 0;
  function interact() {
    var now = Date.now();
    if (now - lastInteract < 1000) return;
    lastInteract = now;
    try {
      if (window.RobinInteract && window.RobinInteract.post) {
        window.RobinInteract.post();
      }
    } catch (e) {}
  }
  ['pointerdown', 'touchstart', 'keydown', 'wheel', 'mousemove'].forEach(function (n) {
    window.addEventListener(n, interact, { capture: true, passive: true });
  });
})();
""".trimIndent()

private class BootNonceGate {
    var rejectUntilFresh: Boolean = false
    var liveNonce: String = ""
    var pendingNonce: String? = null
    var fetchGen: Int = 0

    /** @return false when [nonce] belongs to a document we already left. */
    fun onRetry(nonce: String): Boolean {
        if (rejectUntilFresh && liveNonce.isNotEmpty() && nonce != liveNonce) return false
        fetchGen++
        rejectUntilFresh = true
        liveNonce = ""
        pendingNonce = null
        return true
    }

    /** @return true when this boot should reveal the feed now. */
    fun onBoot(nonce: String): Boolean {
        if (!rejectUntilFresh) return true
        if (liveNonce.isNotEmpty() && nonce == liveNonce) {
            rejectUntilFresh = false
            pendingNonce = null
            return true
        }
        // Only stash while the new document's nonce is unknown.
        // A late message from the welcome page must not replace it.
        if (liveNonce.isEmpty() && nonce.isNotEmpty()) pendingNonce = nonce
        return false
    }

    /**
     * @return true when a boot message arrived before we learned [nonce] and it matches.
     */
    fun onDocumentNonce(gen: Int, nonce: String): Boolean {
        if (gen != fetchGen || nonce.isEmpty()) return false
        liveNonce = nonce
        if (rejectUntilFresh && pendingNonce == nonce) {
            pendingNonce = null
            rejectUntilFresh = false
            return true
        }
        return false
    }

    fun noteNavigationFinished(): Int = fetchGen

    fun clearReject() {
        rejectUntilFresh = false
    }
}

/** Mobile Chrome — keeps X on the TopNavBar layout (same CSS path as iOS). */
private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private fun isFeedHome(url: String?): Boolean {
    if (!WebUrlPolicy.isFeedUrl(url)) return false
    val path = url?.let(WebUrlPolicy::parseHttps)?.path.orEmpty()
    return path.isEmpty() || path == "/" || path == "/home" || path.startsWith("/home/")
}

/** Feed type / ordering / content filters that need a home reload to fully apply. */
private fun feedReloadFingerprint(s: FilterSettings): List<Boolean> = listOf(
    s.forceFollowing,
    s.preferLatest,
    s.hideLiveContent,
)

/** External browsing always requires a validated HTTPS destination. */
private fun openInDefaultBrowser(context: Context?, url: String): Boolean {
    if (context == null) return false
    val safeUrl = WebUrlPolicy.safeHttpsUrl(url) ?: return false
    val uri = android.net.Uri.parse(safeUrl)
    return try {
        val colorScheme = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(RobinRed.toArgb())
            .build()
        val customTabs = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colorScheme)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setShowTitle(true)
            .build()
        customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabs.launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (_: Exception) {
            false
        }
    } catch (_: Exception) {
        false
    }
}

/** External redirects cannot silently open another app or move outside trusted sites. */
private fun handleWebNavigation(
    view: WebView?,
    url: String?,
    isForMainFrame: Boolean,
    hasUserGesture: Boolean,
    onBlocked: (String) -> Unit,
): Boolean {
    if (url.isNullOrBlank()) return true
    if (view is HardenedWebView && view.isResettingSession) return url != "about:blank"
    if (!isForMainFrame) {
        // Subframes never receive usable native bridges. X/SSO still need HTTPS
        // embedded content, including providers with separate iframe origins.
        return !url.startsWith("https://", ignoreCase = true) && url != "about:blank"
    }
    if (WebUrlPolicy.isTrustedMainFrameUrl(url)) return false
    if (hasUserGesture && WebUrlPolicy.safeHttpsUrl(url) != null) {
        if (!openInDefaultBrowser(view?.context, url)) {
            onBlocked("Robin could not open this link in your browser.")
        }
        return true
    }
    onBlocked("Robin blocked a page outside its supported secure sites. Open external links directly in your browser.")
    return true
}

/** Preserve mouse hover behaviour without widening the browser's trust boundary. */
private fun dispatchWebViewHover(webView: WebView, action: Int, position: Offset) {
    val now = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(now, now, action, position.x, position.y, 0)
    event.source = InputDevice.SOURCE_MOUSE
    try {
        webView.dispatchGenericMotionEvent(event)
    } finally {
        event.recycle()
    }
}

/** Forward Compose hover enter/move/exit into [webView] without consuming touch. */
private fun Modifier.webViewMouseHover(webView: WebView?): Modifier =
    pointerInput(webView) {
        if (webView == null) return@pointerInput
        awaitPointerEventScope {
            var hovering = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Buttons down ⇒ click/drag; let AndroidView touch interop own that stream.
                val anyPressed = event.changes.any { it.pressed }
                if (anyPressed) {
                    if (hovering) {
                        val p = event.changes.firstOrNull()?.position ?: Offset.Zero
                        dispatchWebViewHover(webView, MotionEvent.ACTION_HOVER_EXIT, p)
                        hovering = false
                    }
                    continue
                }
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        val p = event.changes.firstOrNull()?.position ?: continue
                        val action =
                            if (hovering) MotionEvent.ACTION_HOVER_MOVE
                            else MotionEvent.ACTION_HOVER_ENTER
                        dispatchWebViewHover(webView, action, p)
                        hovering = true
                        // Consume so AndroidView's broken hover path does not double-fire.
                        event.changes.forEach { it.consume() }
                    }
                    PointerEventType.Exit -> {
                        if (!hovering) continue
                        val p = event.changes.firstOrNull()?.position ?: Offset.Zero
                        dispatchWebViewHover(webView, MotionEvent.ACTION_HOVER_EXIT, p)
                        hovering = false
                        event.changes.forEach { it.consume() }
                    }
                    else -> Unit
                }
            }
        }
    }

@Composable
private fun FeedBootCover(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = fadeOut(animationSpec = tween(durationMillis = 220)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.preparing_feed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Discard running pages before clearing the shared WebView session. */
private fun signOutOfX(webView: WebView?, onFailure: (String) -> Unit) {
    WebAuthPopup.closeFor(webView)
    val hardened = webView as? HardenedWebView
    if (hardened == null) {
        onFailure("Robin's secure browser is unavailable, so sign-out could not be completed. " +
            "Clear Robin's storage in Android Settings to remove saved sign-in data before using Robin again.")
        return
    }
    if (hardened.isResettingSession) return
    hardened.isResettingSession = true
    val handler = Handler(Looper.getMainLooper())
    var finished = false
    fun failClosed() {
        if (finished) return
        finished = true
        hardened.isResettingSession = true
        hardened.onSessionBlankCommitted = null
        runCatching { WebViewHardening.recordSessionReset(hardened.context, pending = true) }
        runCatching { WebViewHardening.setServiceWorkerNetworkBlocked(true) }
        runCatching { CookieManager.getInstance().setAcceptCookie(false) }
        runCatching {
            hardened.settings.javaScriptEnabled = false
            hardened.settings.blockNetworkLoads = true
            hardened.stopLoading()
        }
        onFailure("Robin could not finish clearing your sign-in data and has blocked browsing. " +
            "Reopen Robin to retry. If this continues, clear Robin's storage in Android Settings before signing in again.")
    }
    val timeout = Runnable { failClosed() }
    try {
        hardened.settings.javaScriptEnabled = false
        hardened.settings.blockNetworkLoads = true
        hardened.stopLoading()
        CookieManager.getInstance().setAcceptCookie(false)
        check(WebViewHardening.recordSessionReset(hardened.context, pending = true))
        check(WebViewHardening.setServiceWorkerNetworkBlocked(true))
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA))
        hardened.onSessionBlankCommitted = {
            if (!finished) {
                try {
                    // Unlike legacy WebStorage.deleteAllData, this clears all
                    // website data, including service workers and network cache.
                    // It is not atomic: keep pages and workers offline until done.
                    WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) {
                        if (!finished) {
                            try {
                                CookieManager.getInstance().flush()
                                if (hardened.isAttachedToWindow) {
                                    hardened.clearFormData()
                                    hardened.clearHistory()
                                    hardened.clearSslPreferences()
                                }
                                check(WebViewHardening.recordSessionReset(hardened.context, pending = false))
                                check(WebViewHardening.setServiceWorkerNetworkBlocked(false))
                                CookieManager.getInstance().setAcceptCookie(true)
                                hardened.isResettingSession = false
                                if (hardened.isAttachedToWindow) {
                                    hardened.settings.blockNetworkLoads = false
                                    hardened.settings.javaScriptEnabled = true
                                    hardened.loadUrl("https://x.com/i/flow/login")
                                }
                                finished = true
                                handler.removeCallbacks(timeout)
                            } catch (_: Exception) {
                                // Leave the persisted pending flag set on any
                                // failure so a process restart retries the wipe.
                                failClosed()
                            }
                        }
                    }
                } catch (_: Exception) {
                    failClosed()
                }
            }
        }
        handler.postDelayed(timeout, 30_000)
        // A committed blank document discards old timers before deleting data.
        hardened.loadUrl("about:blank")
    } catch (_: Exception) {
        failClosed()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XWebFeedScreen(
    modifier: Modifier = Modifier,
) {
    val chrome = AppGraph.chrome
    val filterStore = AppGraph.filterSettings
    val filterSettings by filterStore.settings.collectAsStateWithLifecycle(
        initialValue = FilterSettings.Default,
    )
    val fontScale by AppGraph.fontScale.scale.collectAsStateWithLifecycle()
    val refreshIntervalSeconds by AppGraph.feedRefreshInterval.seconds.collectAsStateWithLifecycle()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf<String?>(null) }
    var showScrollTop by remember { mutableStateOf(false) }
    // Plain holders: writing Compose snapshot state on every scroll pixel
    // recomposes the screen and stalls WebView tile raster, which shows up
    // as multi-second blank regions further down the feed.
    val scrollYPx = remember { intArrayOf(0) }
    /** Covers the WebView until filter-core calls RobinBoot.ready() (or timeout). */
    val showBootCoverState = remember { mutableStateOf(true) }
    var showBootCover by showBootCoverState
    val suppressNextHomeBootCoverState = remember { mutableStateOf(false) }
    var lastFeedRefreshAtMs by remember { mutableLongStateOf(0L) }
    var wasPaused by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var warmReloadJob by remember { mutableStateOf<Job?>(null) }
    /** Frozen old feed shown over the WebView during a warm reload; crossfaded out on boot. */
    var warmSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val warmSnapshotAlpha = remember { Animatable(1f) }
    val lastInteractionAtMs = remember { longArrayOf(System.currentTimeMillis()) }
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val infoPresented by chrome.infoPresented.collectAsStateWithLifecycle()
    // WebViewClient is created once in factory — keep latest values for page-load re-apply.
    val liveFilterSettings = remember { mutableStateOf(filterSettings) }
    val liveFontScale = remember { mutableFloatStateOf(fontScale) }
    liveFilterSettings.value = filterSettings
    liveFontScale.floatValue = fontScale
    /** Snapshot of feed-affecting settings when Settings opened. */
    var filterSettingsWhenInfoOpened by remember { mutableStateOf<FilterSettings?>(null) }
    /** True after Settings changed Following / ordering / feed filters until home reloads. */
    var pendingFeedSettingsReload by remember { mutableStateOf(false) }
    /** One automatic /home reload per refresh when X paints the welcome empty state. */
    var welcomeAutoRetryUsed by remember { mutableStateOf(false) }
    var activeFactCheck by remember { mutableStateOf<FactCheckRequest?>(null) }
    var securityNotice by remember { mutableStateOf<String?>(null) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var mainDocumentTrusted by remember { mutableStateOf(false) }
    val bridgeRateLimiter = remember { WebBridgeRateLimiter() }
    /** Bumped to restart the boot-cover timeout without toggling visibility. */
    var coverDeadline by remember { mutableIntStateOf(0) }
    val welcomeRetryHolder = remember { mutableStateOf<(() -> Unit)?>(null) }
    val revealBootHolder = remember { mutableStateOf<(() -> Unit)?>(null) }
    val bootNonceGate = remember { BootNonceGate() }

    fun scrollWebToTop() {
        val wv = webViewRef ?: return
        if (!WebUrlPolicy.isFeedUrl(wv.url)) return
        wv.scrollTo(0, 0)
        wv.evaluateJavascript(
            """
            (function(){
              $FEED_SCRIPT_GUARD
              var roots = [];
              function add(el) { if (el) roots.push(el); }
              add(document.scrollingElement);
              add(document.documentElement);
              add(document.body);
              add(document.querySelector('main[role="main"]'));
              var col = document.querySelector('[data-testid="primaryColumn"]');
              var ancestor = col;
              for (var a = 0; a < 8 && ancestor; a++) {
                add(ancestor);
                ancestor = ancestor.parentElement;
              }
              var stack = col ? [col] : [];
              var seen = 0;
              while (stack.length && seen < 60) {
                var node = stack.pop();
                seen++;
                if (!node || node.nodeType !== 1) continue;
                if (node.getAttribute && node.getAttribute('data-testid') === 'tweet') continue;
                add(node);
                var kids = node.children;
                for (var i = 0; i < kids.length && i < 8; i++) stack.push(kids[i]);
              }
              for (var r = 0; r < roots.length; r++) {
                var el = roots[r];
                if (el && el.scrollTop > 0) {
                  if (el.scrollTo) el.scrollTo({ top: 0, behavior: 'smooth' });
                  else el.scrollTop = 0;
                }
              }
            })();
            """.trimIndent(),
            null,
        )
        scrollYPx[0] = 0
        showScrollTop = false
    }

    fun clearWarmSnapshot() {
        warmReloadJob?.cancel()
        warmReloadJob = null
        warmSnapshot = null
        suppressNextHomeBootCoverState.value = false
    }

    suspend fun captureWebViewSnapshot(wv: WebView): Bitmap? = suspendCancellableCoroutine { cont ->
        val activity = wv.context.findActivity()
        val window = activity?.window
        if (window == null || wv.width <= 0 || wv.height <= 0) {
            val fallback = try {
                val b = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(b))
                b
            } catch (_: Throwable) {
                null
            }
            if (cont.isActive) cont.resume(fallback)
            return@suspendCancellableCoroutine
        }

        val location = IntArray(2)
        wv.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + wv.width, location[1] + wv.height)
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                window,
                rect,
                bmp,
                { result ->
                    if (!cont.isActive) return@request
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(bmp)
                    } else {
                        Log.w(TAG, "PixelCopy returned status $result; falling back to software draw")
                        try {
                            wv.draw(Canvas(bmp))
                            cont.resume(bmp)
                        } catch (_: Throwable) {
                            cont.resume(null)
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "PixelCopy request failed", e)
            if (cont.isActive) {
                try {
                    wv.draw(Canvas(bmp))
                    cont.resume(bmp)
                } catch (_: Throwable) {
                    cont.resume(null)
                }
            }
        }
    }

    fun hardReloadFeed(warm: Boolean, markRefresh: Boolean = true) {
        val wv = webViewRef ?: return
        if (markRefresh) lastFeedRefreshAtMs = System.currentTimeMillis()
        welcomeAutoRetryUsed = false
        warmReloadJob?.cancel()
        warmReloadJob = null

        fun doLoad() {
            // reload() can reuse a cached document; force a network home load.
            val previous = wv.settings.cacheMode
            wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            wv.loadUrl(X_HOME)
            wv.post { wv.settings.cacheMode = previous }
        }

        if (warm && wv.width > 0 && wv.height > 0) {
            // Freeze the old feed over the WebView, load underneath, and let
            // RobinBoot.ready() crossfade the snapshot away — the blank document
            // and boot-gated body are never visible.
            suppressNextHomeBootCoverState.value = true
            warmReloadJob = scope.launch {
                val bmp = captureWebViewSnapshot(wv)
                if (bmp != null) {
                    warmSnapshotAlpha.snapTo(1f)
                    warmSnapshot = bmp.asImageBitmap()
                }
                doLoad()
                delay(5_500)
                if (warmSnapshot != null) {
                    suppressNextHomeBootCoverState.value = false
                    warmSnapshotAlpha.animateTo(0f, tween(WARM_CROSSFADE_MS, easing = LinearOutSlowInEasing))
                    warmSnapshot = null
                }
            }
        } else {
            warmSnapshot = null
            doLoad()
        }
    }

    /**
     * Cache-bypassing /home sometimes commits X's "Welcome to X!" empty state.
     * Load once more under the cover or warm snapshot already up — a new
     * snapshot would freeze the welcome screen.
     */
    fun retryWelcomeLoad() {
        if (welcomeAutoRetryUsed) return
        val wv = webViewRef ?: return
        welcomeAutoRetryUsed = true
        Log.i(TAG, "welcome interstitial — reloading home once")
        warmReloadJob?.cancel()
        warmReloadJob = null
        if (warmSnapshot != null) {
            suppressNextHomeBootCoverState.value = true
            warmReloadJob = scope.launch {
                delay(5_500)
                if (warmSnapshot != null) {
                    suppressNextHomeBootCoverState.value = false
                    warmSnapshotAlpha.animateTo(
                        0f,
                        tween(WARM_CROSSFADE_MS, easing = LinearOutSlowInEasing),
                    )
                    warmSnapshot = null
                }
            }
        } else {
            clearWarmSnapshot()
            suppressNextHomeBootCoverState.value = false
            if (!showBootCover) showBootCover = true
            wv.alpha = 0f
            coverDeadline++
        }
        val previous = wv.settings.cacheMode
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.loadUrl(X_HOME)
        wv.post { wv.settings.cacheMode = previous }
    }

    fun flushPendingFeedSettingsReloadIfNeeded() {
        if (!pendingFeedSettingsReload) return
        if (infoPresented) return
        if (!isFeedHome(pageUrl)) return
        clearWarmSnapshot()
        pendingFeedSettingsReload = false
        hardReloadFeed(warm = false, markRefresh = false)
    }

    fun canRefreshFeedNow(): Boolean {
        if (showBootCover) return false
        if (warmSnapshot != null) return false
        if (!isFeedHome(pageUrl)) return false
        if (scrollYPx[0] > NEAR_TOP_PX) return false
        if (progress in 0f..<1f && progress > 0f) return false
        if (refreshIntervalSeconds > 0) {
            val now = System.currentTimeMillis()
            if (lastFeedRefreshAtMs > 0L &&
                now - lastFeedRefreshAtMs < refreshIntervalSeconds * 1000L
            ) {
                return false
            }
        }
        return webViewRef != null
    }

    fun noteUserInteraction() {
        lastInteractionAtMs[0] = System.currentTimeMillis()
    }

    /** Automatic refreshes wait until the user has left the feed alone. */
    fun userIsIdle(): Boolean {
        if (infoPresented) return false
        return System.currentTimeMillis() - lastInteractionAtMs[0] >= FeedRefreshIntervalStore.IDLE_MS
    }

    fun requestNearTopAutoRefresh(userInitiated: Boolean = false) {
        if (!userInitiated && refreshIntervalSeconds <= 0) return
        if (!canRefreshFeedNow()) return
        if (!userInitiated && !userIsIdle()) return
        // Pill auto-click already handles “Show N posts” when X paints it.
        // Host-driven refresh must force a network fetch.
        hardReloadFeed(warm = true)
    }

    /** Back-to-top only — native header stays visible. */
    fun onWebScroll(scrollY: Int) {
        scrollYPx[0] = scrollY
        val show = scrollY >= SCROLL_TOP_BUTTON_PX
        if (show != showScrollTop) showScrollTop = show
        noteUserInteraction()
    }

    fun onTitleTap() {
        if (scrollYPx[0] <= NEAR_TOP_PX) {
            requestNearTopAutoRefresh(userInitiated = true)
        } else {
            scrollWebToTop()
        }
    }

    LaunchedEffect(filterSettings, fontScale) {
        webViewRef?.let { XFilterInjector.applySettingsOnly(it, filterSettings, fontScale) }
    }

    LaunchedEffect(infoPresented) {
        if (infoPresented) return@LaunchedEffect
        val opened = filterSettingsWhenInfoOpened
        if (opened != null &&
            feedReloadFingerprint(filterSettings) != feedReloadFingerprint(opened)
        ) {
            filterSettingsWhenInfoOpened = null
            pendingFeedSettingsReload = true
        } else if (opened != null) {
            filterSettingsWhenInfoOpened = null
        }
        flushPendingFeedSettingsReloadIfNeeded()
    }

    LaunchedEffect(filterSettings) {
        if (infoPresented) return@LaunchedEffect
        val opened = filterSettingsWhenInfoOpened ?: return@LaunchedEffect
        if (feedReloadFingerprint(filterSettings) != feedReloadFingerprint(opened)) {
            filterSettingsWhenInfoOpened = null
            pendingFeedSettingsReload = true
            flushPendingFeedSettingsReloadIfNeeded()
        }
    }

    LaunchedEffect(pageUrl, showBootCover, warmSnapshot, pendingFeedSettingsReload) {
        flushPendingFeedSettingsReloadIfNeeded()
    }

    // Slightly longer than filter-core's 5s hard reveal.
    // coverDeadline restarts the wait when a welcome-state retry keeps the cover up.
    LaunchedEffect(showBootCover, coverDeadline) {
        if (!showBootCover) {
            webViewRef?.alpha = 1f
            return@LaunchedEffect
        }
        delay(5_500)
        if (showBootCover) {
            showBootCover = false
            webViewRef?.alpha = 1f
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPaused = true
                    isResumed = false
                    clearWarmSnapshot()
                }
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    if (wasPaused) {
                        wasPaused = false
                        requestNearTopAutoRefresh()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            warmReloadJob?.cancel()
            warmReloadJob = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val activeBenchmarkCallback = remember {
        mutableStateOf<((org.evergreenlabs.robin.services.BenchmarkResult?) -> Unit)?>(null)
    }

    DisposableEffect(webViewRef) {
        val wv = webViewRef
        if (wv != null && BuildConfig.DEBUG) {
            AppGraph.benchmark.registerRunner { onComplete ->
                activeBenchmarkCallback.value = onComplete
                wv.evaluateJavascript(
                    "(function() { $FEED_SCRIPT_GUARD if (typeof window.__ROBIN_RUN_BENCHMARK__ === 'function') window.__ROBIN_RUN_BENCHMARK__(); })();",
                    null,
                )
            }
        } else {
            AppGraph.benchmark.registerRunner(null)
        }
        onDispose {
            AppGraph.benchmark.registerRunner(null)
        }
    }

    // While foregrounded near the top, periodically self-refresh (same path as resume/title).
    LaunchedEffect(isResumed, refreshIntervalSeconds) {
        if (!isResumed || refreshIntervalSeconds <= 0) return@LaunchedEffect
        while (true) {
            delay(FeedRefreshIntervalStore.POLL_TICK_MS)
            requestNearTopAutoRefresh()
        }
    }

    fun revealBoot() {
        showBootCover = false
        suppressNextHomeBootCoverState.value = false
        webViewRef?.alpha = 1f
        if (warmSnapshot != null) {
            warmReloadJob?.cancel()
            warmReloadJob = scope.launch {
                warmSnapshotAlpha.animateTo(
                    0f,
                    tween(WARM_CROSSFADE_MS, easing = LinearOutSlowInEasing),
                )
                warmSnapshot = null
            }
        }
    }
    welcomeRetryHolder.value = { retryWelcomeLoad() }
    revealBootHolder.value = { revealBoot() }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    val headerBg = if (isSystemInDarkTheme()) RobinHeaderDark else RobinCream
    val headerAccent = if (isSystemInDarkTheme()) RobinRedLight else RobinRed
    Column(modifier = modifier.fillMaxSize()) {
        // Compact custom header (TopAppBar paints through status bars and reads too tall).
        Column(
            modifier = Modifier
                .zIndex(1f)
                .background(headerBg),
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onTitleTap() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_robin_mark),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.x_web_feed_title),
                                color = headerAccent,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            WebUrlPolicy.origin(pageUrl)?.let { origin ->
                                Text(
                                    text = origin,
                                    color = headerAccent,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showScrollTop) {
                        IconButton(
                            onClick = { scrollWebToTop() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.back_to_top),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            Analytics.track("compose_opened")
                            noteUserInteraction()
                            webViewRef?.takeIf { WebUrlPolicy.isFeedUrl(it.url) }
                                ?.evaluateJavascript(OPEN_COMPOSE_JS, null)
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.new_post),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { hardReloadFeed(warm = !showBootCover) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            Analytics.track("settings_opened")
                            filterSettingsWhenInfoOpened = filterSettings
                            chrome.setInfoPresented(true)
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
        ) {
            if (!webViewUnavailable) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .webViewMouseHover(webViewRef),
                factory = { ctx ->
                HardenedWebView(ctx).apply {
                    // Prevent black surface flash during cold start before first visual paint.
                    alpha = if (showBootCover) 0f else 1f
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    WebViewHardening.configure(this, supportsPopups = true)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = MOBILE_CHROME_USER_AGENT
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100
                    setDownloadListener { _, _, _, _, _ ->
                        securityNotice = "Downloads are disabled in Robin. Open the link in your browser if you need the file."
                    }

                    val secureMessagingSupported =
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA) &&
                            WebViewHardening.configureServiceWorkers(WebViewHardening.isSessionResetPending(ctx))
                    if (!secureMessagingSupported) {
                        webViewUnavailable = true
                        securityNotice = "Update Android System WebView or Chrome, then reopen Robin. This version cannot provide Robin's secure connection to the feed."
                        return@apply
                    }
                    val bridgeInstalled = runCatching {
                        WebViewCompat.addWebMessageListener(
                            this,
                            WebBridgeScript.TRANSPORT_NAME,
                            WebUrlPolicy.feedOrigins,
                        ) { _, message, sourceOrigin, isMainFrame, _ ->
                            if (isResettingSession || !WebUrlPolicy.isFeedUrl(this.url) ||
                                !WebUrlPolicy.isBridgeOrigin(sourceOrigin.toString(), isMainFrame)
                            ) {
                                return@addWebMessageListener
                            }
                            val messageTime = SystemClock.elapsedRealtime()
                            if (!bridgeRateLimiter.acceptEnvelope(messageTime)) return@addWebMessageListener
                            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
                            val parsed = WebNativeMessagePolicy.parse(raw, BuildConfig.ENABLE_AI, BuildConfig.DEBUG)
                                ?: return@addWebMessageListener
                            if (!bridgeRateLimiter.accept(parsed.kind, messageTime)) {
                                return@addWebMessageListener
                            }
                            when (parsed) {
                                is WebNativeMessage.Scroll -> onWebScroll(parsed.y)
                                WebNativeMessage.Interact -> noteUserInteraction()
                                is WebNativeMessage.BootReady -> {
                                    if (bootNonceGate.onBoot(parsed.nonce)) revealBootHolder.value?.invoke()
                                }
                                is WebNativeMessage.BootRetry -> {
                                    if (bootNonceGate.onRetry(parsed.nonce)) welcomeRetryHolder.value?.invoke()
                                }
                                is WebNativeMessage.FactCheck -> {
                                    // The sheet has its own native confirmation before sending
                                    // any data. Repeated page requests cannot replace an open sheet.
                                    if (BuildConfig.ENABLE_AI && activeFactCheck == null) {
                                        val input = parsed.input
                                        activeFactCheck = FactCheckRequest(
                                            text = input.text,
                                            author = input.author,
                                            tweetURL = input.tweetURL,
                                            externalLinks = input.externalLinks,
                                        )
                                    }
                                }
                                is WebNativeMessage.Benchmark -> {
                                    if (BuildConfig.DEBUG) {
                                        val result = org.evergreenlabs.robin.services.BenchmarkResult(
                                            initialLoadMs = parsed.initialLoadMs,
                                            scrollRenderCompletionMs = parsed.scrollRenderCompletionMs,
                                            timestamp = parsed.timestamp,
                                            bootReady = parsed.bootReady,
                                        )
                                        AppGraph.benchmark.recordRun(result)
                                        activeBenchmarkCallback.value?.invoke(result)
                                        activeBenchmarkCallback.value = null
                                    }
                                }
                            }
                        }
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            WebBridgeScript.source(BuildConfig.ENABLE_AI, BuildConfig.DEBUG),
                            WebUrlPolicy.feedOrigins,
                        )
                    }.isSuccess
                    if (!bridgeInstalled) {
                        webViewUnavailable = true
                        securityNotice = "Robin could not establish a secure connection to the feed. Update Android System WebView or Chrome and reopen Robin."
                        return@apply
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) noteUserInteraction()
                        false
                    }
                    setOnHoverListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
                        ) noteUserInteraction()
                        false
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.deny()
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: android.webkit.GeolocationPermissions.Callback?,
                        ) {
                            callback?.invoke(origin.orEmpty(), false, false)
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = WebAuthPopup.open(
                            host = view,
                            isUserGesture = isUserGesture,
                            resultMessage = resultMsg,
                            onBlocked = { securityNotice = it },
                            openExternal = { target ->
                                if (!openInDefaultBrowser(view?.context, target)) {
                                    securityNotice = "Robin could not open this link in your browser."
                                }
                            },
                        )
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = handleWebNavigation(
                            view,
                            request?.url?.toString(),
                            request?.isForMainFrame ?: true,
                            request?.hasGesture() == true,
                            onBlocked = { securityNotice = it },
                        )

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (request == null) return WebViewHardening.blockedResponse()
                            if (view is HardenedWebView && view.isResettingSession) {
                                return if (request.url.toString() == "about:blank") null else WebViewHardening.blockedResponse()
                            }
                            val isHttps = request.url.scheme.equals("https", ignoreCase = true)
                            if (!isHttps || (request.isForMainFrame && !WebUrlPolicy.isTrustedMainFrameUrl(request.url.toString()))) {
                                if (request.isForMainFrame) view?.post {
                                    securityNotice = "Robin blocked a page outside its supported secure sites."
                                }
                                return WebViewHardening.blockedResponse()
                            }
                            return null
                        }

                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            handler?.cancel()
                            securityNotice = "Robin stopped loading because this site's secure connection could not be verified."
                        }

                        override fun onSafeBrowsingHit(
                            view: WebView?,
                            request: WebResourceRequest?,
                            threatType: Int,
                            callback: SafeBrowsingResponse?,
                        ) {
                            callback?.backToSafety(false)
                            securityNotice = "Robin blocked a page flagged as unsafe."
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            webViewRef = null
                            webViewUnavailable = true
                            securityNotice = "Robin's browser stopped. Reopen Robin to continue."
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageUrl = url
                            mainDocumentTrusted = false
                            view?.visibility = View.INVISIBLE
                            if (view is HardenedWebView && view.isResettingSession && url == "about:blank") return
                            if (!WebUrlPolicy.isTrustedMainFrameUrl(url)) {
                                view?.stopLoading()
                                securityNotice = "Robin blocked a page outside its supported secure sites."
                                return
                            }
                            if (isFeedHome(url)) {
                                if (suppressNextHomeBootCoverState.value) {
                                    // Warm reload: keep suppress until RobinBoot.ready().
                                    // X often fires multiple onPageStarted (redirects);
                                    // clearing on the first one re-shows the spinner cover.
                                } else {
                                    showBootCoverState.value = true
                                    warmSnapshot = null
                                }
                            }
                            if (WebUrlPolicy.isFeedUrl(url)) {
                                view?.let { XFilterInjector.evaluateIfNeeded(it) }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) bootNonceGate.clearReject()
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            if ((view as? HardenedWebView)?.completeSessionResetIfBlank(url) == true) return
                            mainDocumentTrusted = WebUrlPolicy.isTrustedMainFrameUrl(url) &&
                                WebUrlPolicy.isTrustedMainFrameUrl(view?.url) &&
                                WebUrlPolicy.origin(url) == WebUrlPolicy.origin(view?.url)
                            if (mainDocumentTrusted) pageUrl = view?.url
                            view?.visibility = if (mainDocumentTrusted) View.VISIBLE else View.INVISIBLE
                            if (mainDocumentTrusted && !showBootCover) view?.alpha = 1f
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if ((view as? HardenedWebView)?.completeSessionResetIfBlank(url) == true) return
                            pageUrl = view?.url
                            if (!WebUrlPolicy.isTrustedMainFrameUrl(url) ||
                                !WebUrlPolicy.isTrustedMainFrameUrl(view?.url) ||
                                WebUrlPolicy.origin(url) != WebUrlPolicy.origin(view?.url)
                            ) {
                                view?.visibility = View.INVISIBLE
                                return
                            }
                            view?.let {
                                if (WebUrlPolicy.isFeedUrl(url)) {
                                    val nonceGen = bootNonceGate.noteNavigationFinished()
                                    it.evaluateJavascript("(function() { $FEED_SCRIPT_GUARD var n=window.__ROBIN_BOOT_NONCE__; return typeof n==='string' && n.length<=128 ? n : ''; })();") { raw ->
                                        val nonce = raw?.trim()?.removeSurrounding("\"") ?: ""
                                        if (bootNonceGate.onDocumentNonce(nonceGen, nonce)) {
                                            revealBootHolder.value?.invoke()
                                        }
                                    }
                                    it.evaluateJavascript(SCROLL_PROBE_JS, null)
                                    XFilterInjector.evaluateIfNeeded(it)
                                    XFilterInjector.applySettingsOnly(
                                        it,
                                        liveFilterSettings.value,
                                        liveFontScale.floatValue,
                                    )
                                }
                                // After login, drop history so system back cannot return to login.
                                if (isFeedHome(url)) {
                                    it.clearHistory()
                                } else {
                                    it.alpha = 1f
                                    showBootCover = false
                                }
                                canGoBack = it.canGoBack()
                            }
                            progress = 1f
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            pageUrl = view?.url
                            canGoBack = view?.canGoBack() == true
                        }
                    }

                    XFilterInjector.install(
                        this,
                        ctx.applicationContext,
                        FilterSettings.Default,
                        AppGraph.fontScale.scale.value,
                    )
                    webViewRef = this
                    if (WebViewHardening.isSessionResetPending(ctx)) {
                        signOutOfX(this) { message ->
                            securityNotice = message
                            showBootCover = false
                            webViewUnavailable = true
                        }
                    } else {
                        loadUrl(X_HOME)
                    }
                }
            },
                update = { view ->
                    webViewRef = view
                    canGoBack = view.canGoBack()
                    pageUrl = view.url
                    view.alpha = if (showBootCover) 0f else 1f
                    view.visibility = if (mainDocumentTrusted) View.VISIBLE else View.INVISIBLE
                    // Settings / font scale are applied via LaunchedEffect — avoid
                    // re-toggling zoom/classes on every recomposition (paint churn).
                },
                onRelease = { view ->
                    WebAuthPopup.closeFor(view)
                    runCatching {
                        view.stopLoading()
                        view.destroy()
                    }
                    if (webViewRef === view) webViewRef = null
                },
            )

            } else {
                Text(
                    text = "Update Android System WebView or Chrome if needed, then reopen Robin to continue.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            warmSnapshot?.let { snapshot ->
                Image(
                    bitmap = snapshot,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(warmSnapshotAlpha.value),
                )
            }

            if (progress in 0f..<1f && !showBootCover && warmSnapshot == null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            FeedBootCover(visible = showBootCover)
        }
    }

    if (infoPresented) {
        InfoSheet(
            onDismiss = { chrome.setInfoPresented(false) },
            onSignOut = {
                activeFactCheck = null
                mainDocumentTrusted = false
                clearWarmSnapshot()
                showBootCoverState.value = true
                signOutOfX(webViewRef) { message ->
                    securityNotice = message
                    showBootCover = false
                    webViewUnavailable = true
                }
            },
        )
    }

    securityNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { securityNotice = null },
            title = { Text("Robin security") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { securityNotice = null }) { Text("OK") }
            },
        )
    }

    activeFactCheck?.let { req ->
        FactCheckSheet(
            request = req,
            onDismiss = { activeFactCheck = null },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
