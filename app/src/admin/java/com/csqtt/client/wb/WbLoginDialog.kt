package com.csqtt.client.wb

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray

private const val WB_LOGIN_URL = "https://stream.wb.ru/login"
private val JWT = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

/**
 * Full-screen WB Stream sign-in. The person logs in with their phone number as usual;
 * the app picks up the login token the site itself uses (Authorization header, cookie or storage).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WbLoginDialog(onToken: (String) -> Unit, onDismiss: () -> Unit) {
    var hint by remember { mutableStateOf("Войди в WB Stream по номеру телефона. Как только вход пройдёт, окно закроется само.") }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var done by remember { mutableStateOf(false) }
    // Before sign-in the site may already use a guest token; only accept one after leaving the login page.
    val signedIn = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun deliver(token: String) {
        if (done || !signedIn.get()) return
        val clean = token.removePrefix("Bearer ").trim()
        if (!JWT.matches(clean) && clean.length < 40) return
        done = true
        handler.post { onToken(clean) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Вход в WB Stream", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                var webViewRef by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(Unit) {
                    val poll = object : Runnable {
                        override fun run() {
                            if (done) return
                            webViewRef?.let { view ->
                                CookieManager.getInstance().getCookie("https://stream.wb.ru")
                                    ?.split(';')
                                    ?.map(String::trim)
                                    ?.firstOrNull { it.startsWith("wb_access_token=") }
                                    ?.let { deliver(it.substringAfter('=')) }
                                // The site may keep the token in web storage instead of a cookie.
                                view.evaluateJavascript(STORAGE_SCAN_JS) { raw -> pickFromStorage(raw)?.let(::deliver) }
                            }
                            handler.postDelayed(this, 1500)
                        }
                    }
                    handler.postDelayed(poll, 1500)
                    onDispose {
                        handler.removeCallbacks(poll)
                        webViewRef?.apply {
                            stopLoading()
                            destroy()
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    val host = request.url.host.orEmpty()
                                    if (host.endsWith("wb.ru")) {
                                        request.requestHeaders.entries
                                            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                            ?.value
                                            ?.takeIf { it.startsWith("Bearer ") }
                                            ?.let(::deliver)
                                    }
                                    return null
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    val page = url.orEmpty()
                                    if (page.contains("stream.wb.ru") && !page.contains("/login") && !page.contains("/auth")) {
                                        if (!signedIn.getAndSet(true)) {
                                            handler.post { hint = "Вход выполнен, получаю токен… Если окно не закрылось, открой любую страницу WB Stream." }
                                        }
                                    }
                                }
                            }
                            loadUrl(WB_LOGIN_URL)
                            webViewRef = this
                        }
                    },
                )
            }
        }
    }
}

private const val STORAGE_SCAN_JS = """
(function(){
  var out=[];
  function scan(s){ try{ for(var i=0;i<s.length;i++){ var k=s.key(i); out.push([k, String(s.getItem(k))]); } }catch(e){} }
  scan(window.localStorage); scan(window.sessionStorage);
  return JSON.stringify(out);
})()
"""

/** Looks for a JWT in web storage, preferring keys that mention an access token. */
private fun pickFromStorage(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val json = runCatching {
        // evaluateJavascript returns the result JSON-encoded as a string literal.
        val unquoted = org.json.JSONTokener(raw).nextValue() as? String ?: return null
        JSONArray(unquoted)
    }.getOrNull() ?: return null
    val candidates = mutableListOf<Pair<String, String>>()
    for (i in 0 until json.length()) {
        val pair = json.optJSONArray(i) ?: continue
        val key = pair.optString(0)
        val value = pair.optString(1)
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").findAll(value).forEach {
            candidates += key to it.value
        }
    }
    return candidates.firstOrNull { it.first.contains("access", ignoreCase = true) }?.second
        ?: candidates.firstOrNull { it.first.contains("token", ignoreCase = true) && !it.first.contains("refresh", true) }?.second
}
