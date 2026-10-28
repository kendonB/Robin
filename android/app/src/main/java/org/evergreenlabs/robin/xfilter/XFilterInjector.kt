package org.evergreenlabs.robin.xfilter

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import org.evergreenlabs.robin.R
import org.evergreenlabs.robin.security.WebUrlPolicy

/**
 * Loads assets/x-filter and injects settings + CSS + JS into a WebView.
 */
object XFilterInjector {
    private const val CSS_ASSET = "x-filter/filter-core.css"
    private const val JS_ASSET = "x-filter/filter-core.js"

    fun buildBootstrapScript(
        context: Context,
        settings: FilterSettings = FilterSettings.Default,
        fontScale: Float = 1f,
    ): String {
        val css = readAsset(context, CSS_ASSET)
        val js = readAsset(context, JS_ASSET)
        val cssJson = jsonStringLiteral(css)
        val settingsJson = settings.toJsonObjectLiteral(fontScale)
        val trustedOrigins = trustedOriginArray()
        return """
            (function(){
              // Keep the trust boundary outside the recoverable setup block.
              // A failed origin/frame check must never fall through to core JS.
              if (window.top !== window || $trustedOrigins.indexOf(location.origin) === -1) return;
              try {
                // Login / SSO flows break under boot gate + chrome hides — leave them alone.
                var path = (location.pathname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                if (path.indexOf('/i/flow/') === 0 || href.indexOf('/i/flow/') !== -1 ||
                    path.indexOf('/i/jf/') === 0 || href.indexOf('/i/jf/') !== -1) {
                  try {
                    document.documentElement.setAttribute('data-mt-boot-ready', '1');
                  } catch (eAuth) {}
                  return;
                }

                window.__ROBIN_SETTINGS__ = $settingsJson;
                if (!document.getElementById('robin-x-filter-css')) {
                  var s = document.createElement('style');
                  s.id = 'robin-x-filter-css';
                  s.textContent = $cssJson;
                  var p = document.head || document.documentElement;
                  if (p) {
                    p.appendChild(s);
                  } else {
                    document.addEventListener('DOMContentLoaded', function(){
                      var p2 = document.head || document.documentElement;
                      if (p2 && !document.getElementById('robin-x-filter-css')) p2.appendChild(s);
                    });
                  }
                }
                var root = document.documentElement;
                if (root) {
                  // Android WebView paint path (see filter-core.css). iOS does not set this.
                  root.classList.add('mt-android-webview');
                  var p = location.pathname || '';
                  var home = p === '/' || p === '/home' || p.indexOf('/home') === 0;
                  if (home) {
                    root.setAttribute('data-mt-home', '1');
                    // Full home navigations re-run bootstrap; mask until filter boot settles.
                    if (!window.__ROBIN_FILTER_INSTALLED__) {
                      root.removeAttribute('data-mt-boot-ready');
                    }
                  } else {
                    root.removeAttribute('data-mt-home');
                    root.setAttribute('data-mt-boot-ready', '1');
                  }
                  var cfg = window.__ROBIN_SETTINGS__ || {};
                  root.classList.toggle('mt-hide-page-header', !!cfg.hidePageHeader);
                  root.classList.add('mt-hide-compose');
                  if (home && cfg.forceFollowing && cfg.preferLatest) {
                    root.setAttribute('data-mt-defer-feed-chrome', '1');
                  }
                  var scale = cfg.fontScale;
                  if (typeof scale === 'number' && scale > 0 && scale !== 1) {
                    root.style.zoom = String(scale);
                  }
                }
              } catch (e) {}
              $js
            })();
        """.trimIndent()
    }

    /**
     * Prefer document-start injection when the system WebView supports it;
     * otherwise fall back to evaluateJavascript on page events.
     */
    fun install(
        webView: WebView,
        context: Context,
        settings: FilterSettings = FilterSettings.Default,
        fontScale: Float = 1f,
    ) {
        val script = buildBootstrapScript(context, settings, fontScale)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    script,
                    WebUrlPolicy.feedOrigins,
                )
                webView.setTag(R.id.robin_x_filter_script, null)
                return
            } catch (_: Exception) {
                // The fallback has the same exact origin and top-frame guard.
            }
        }
        webView.setTag(R.id.robin_x_filter_script, script)
    }

    fun applySettingsOnly(
        webView: WebView,
        settings: FilterSettings,
        fontScale: Float = 1f,
    ) {
        if (!WebUrlPolicy.isFeedUrl(webView.url)) return
        val json = settings.toJsonObjectLiteral(fontScale)
        val trustedOrigins = trustedOriginArray()
        webView.evaluateJavascript(
            """
            (function(){
              if (window.top !== window || $trustedOrigins.indexOf(location.origin) === -1) return;
              window.__ROBIN_SETTINGS__ = $json;
              var href = (location.href || '').toLowerCase();
              var path = (location.pathname || '').toLowerCase();
              if (path.indexOf('/i/flow/') === 0 || href.indexOf('/i/flow/') !== -1 ||
                  path.indexOf('/i/jf/') === 0 || href.indexOf('/i/jf/') !== -1) {
                try {
                  document.documentElement.setAttribute('data-mt-boot-ready', '1');
                } catch (e) {}
                return;
              }
              window.__ROBIN_USER_PICKED_TAB__ = 0;
              var cfg = window.__ROBIN_SETTINGS__ || {};
              var s = document.getElementById('robin-x-filter-css');
              if (s) s.disabled = !cfg.hidePromoted;
              var root = document.documentElement;
              if (!root) return;
              root.classList.add('mt-android-webview');
              var p = location.pathname || '';
              var home = p === '/' || p === '/home' || p.indexOf('/home') === 0;
              if (home) root.setAttribute('data-mt-home', '1');
              else root.removeAttribute('data-mt-home');
              root.classList.toggle('mt-hide-page-header', !!cfg.hidePageHeader);
              root.classList.add('mt-hide-compose');
              root.classList.toggle('mt-hide-live', !!cfg.hideLiveContent);
              if (home && cfg.forceFollowing && cfg.preferLatest && !window.__ROBIN_LATEST_OK__) {
                root.setAttribute('data-mt-defer-feed-chrome', '1');
              } else {
                root.removeAttribute('data-mt-defer-feed-chrome');
              }
              // Show Feed Tab: restore category tabs only. TopNavBar stays hidden.
              if (!cfg.hidePageHeader) {
                var hh = document.querySelectorAll('[data-testid="ScrollSnap-List"][data-mt-robin-hide], [role="tablist"][data-mt-robin-hide]');
                for (var i = 0; i < hh.length; i++) {
                  hh[i].style.removeProperty('display');
                  hh[i].removeAttribute('data-mt-robin-hide');
                }
              }
              var scale = cfg.fontScale;
              if (typeof scale !== 'number' || !(scale > 0)) scale = 1;
              if (scale === 1) root.style.removeProperty('zoom');
              else root.style.zoom = String(scale);
              if (typeof window.__ROBIN_ON_SETTINGS__ === 'function') window.__ROBIN_ON_SETTINGS__();
            })();
            """.trimIndent(),
            null,
        )
    }

    fun evaluateIfNeeded(webView: WebView) {
        if (!WebUrlPolicy.isFeedUrl(webView.url)) return
        val script = webView.getTag(R.id.robin_x_filter_script) as? String ?: return
        webView.evaluateJavascript(script, null)
    }

    private fun trustedOriginArray(): String =
        WebUrlPolicy.feedOrigins.sorted().joinToString(prefix = "[", postfix = "]") {
            jsonStringLiteral(it)
        }

    private fun readAsset(context: Context, path: String): String {
        context.assets.open(path).use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        }
    }

    private fun jsonStringLiteral(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
