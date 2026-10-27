package com.mineygg.ergon.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.core.DeviceHeaders
import com.mineygg.ergon.core.HoyoApi
import kotlinx.coroutines.delay

private const val HOYOLAB_URL = "https://www.hoyolab.com/home"
private const val REDEEM_URL = "https://genshin.hoyoverse.com/en/gift"
private val REDEEM_SITES = listOf(
    "https://genshin.hoyoverse.com",
    "https://hsr.hoyoverse.com",
    "https://zenless.hoyoverse.com",
    "https://hoyoverse.com"
)

private class WebHolder {
    var view: WebView? = null
}

private fun cookies(cm: CookieManager, url: String): Map<String, String> =
    cm.getCookie(url).orEmpty()
        .split(";")
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
        .toMap()

/**
 * One configuration for the main WebView AND the popup window it opens, so both present the same
 * browser identity (Google's sign-in page loads in the popup, so it matters there most).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configure(wv: WebView, cm: CookieManager) {
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        userAgentString = DeviceHeaders.userAgent()
    }
    DeviceHeaders.applyClientHints(wv)
    // Stops WebView from sending "X-Requested-With: <package>", which Google uses to spot embedded browsers.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
    }
    wv.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(wv, true)
    if ((wv.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

@Composable
fun LoginScreen(vm: MainViewModel, startStep: Int, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cm = remember { CookieManager.getInstance() }
    val holder = remember { WebHolder() }
    val accepted by vm.disclaimerAccepted.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(startStep) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageUrl by remember { mutableStateOf("") }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var popupUrl by remember { mutableStateOf("") }

    // If the redeem session was flagged expired, don't accept the same stale cookie again.
    val staleCookieToken = remember { vm.creds.value.let { if (it.loginExpired) it.cookieToken else "" } }

    // Cookies persist between sign-ins on purpose: a still-valid web session reconnects without
    // typing anything. (Signing out in Settings clears them, which is how you switch accounts.)
    LaunchedEffect(step, accepted) {
        if (!accepted) return@LaunchedEffect
        var lastTried = ""
        while (true) {
            delay(1000)
            if (step == 1) {
                val c = cookies(cm, "https://www.hoyolab.com")
                val token = c["ltoken_v2"]
                val uid = c["ltuid_v2"]
                if (token.isNullOrEmpty() || uid.isNullOrEmpty()) continue
                val sig = "$token:$uid"
                if (sig == lastTried) continue // already checked this pair and it was rejected
                lastTried = sig
                if (HoyoApi.validateHoyolab(token, uid)) {
                    vm.saveHoyolab(token, uid)
                    step = 2
                    holder.view?.loadUrl(REDEEM_URL)
                    break
                }
            } else {
                val token = REDEEM_SITES.firstNotNullOfOrNull { site ->
                    cookies(cm, site)["cookie_token_v2"]?.takeIf { it.isNotEmpty() && it != staleCookieToken }
                }
                if (token != null) {
                    vm.saveCookieToken(token)
                    onClose()
                    break
                }
            }
        }
    }

    BackHandler(enabled = popup != null) {
        popup?.let { runCatching { it.destroy() } }
        popup = null
        popupUrl = ""
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { holder.view?.destroy() } }
    }

    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text("Connect account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (step == 1) "Step 1 of 2 · Sign in to HoYoLAB" else "Step 2 of 2 · Sign in on the redeem page",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(1, 2).forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (step >= i) cs.primary else cs.outlineVariant)
                    )
                }
            }

            // Shows the page the user is actually on (the Google popup's address while it's open).
            UrlBar(if (popup != null) popupUrl else pageUrl)
            Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 16.dp)) {
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (accepted) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                configure(this, cm)

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }

                                    // Google sign-in opens in a popup that reports back to this page.
                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                        val parent = view ?: return false
                                        val child = WebView(parent.context)
                                        configure(child, cm)
                                        child.webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                                popupUrl = url.orEmpty()
                                            }
                                        }
                                        child.webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                                                progress = newProgress
                                            }

                                            override fun onCloseWindow(window: WebView?) {
                                                popup = null
                                                popupUrl = ""
                                                runCatching { window?.destroy() }
                                            }
                                        }
                                        popup = child
                                        transport.webView = child
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        error = null
                                        pageUrl = url.orEmpty()
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        err: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            error = "${err?.description ?: "Couldn't load the page"}"
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        // Make sure cookies set during login are written out promptly.
                                        cm.flush()
                                        pageUrl = url.orEmpty()
                                    }
                                }
                                holder.view = this
                                loadUrl(if (step == 1) HOYOLAB_URL else REDEEM_URL)
                            }
                        }
                    )
                }

                popup?.let { w ->
                    Box(Modifier.fillMaxSize().background(cs.background)) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { w })
                        IconButton(
                            onClick = {
                                popup = null
                                popupUrl = ""
                                runCatching { w.destroy() }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(cs.surface.copy(alpha = 0.85f), CircleShape)
                        ) { Icon(Icons.Default.Close, contentDescription = "Close popup") }
                    }
                }

                error?.let { msg ->
                    Column(
                        Modifier.fillMaxSize().background(cs.background).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Page didn't load", style = MaterialTheme.typography.titleMedium)
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Button(onClick = {
                            error = null
                            holder.view?.reload()
                        }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Retry")
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (step == 1) {
                        "Log in as usual. This closes the step automatically."
                    } else {
                        "Log in here too (same account) so code redemption works."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (step == 2) TextButton(onClick = onClose) { Text("Skip") }
            }
        }
    }

    if (!accepted) {
        DisclaimerDialog(onProceed = { vm.acceptDisclaimer() }, onExit = onClose)
    }
}

/** Read-only address bar: lock + host (bold) + path. Query strings are hidden (they carry sign-in codes). */
@Composable
private fun UrlBar(url: String) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val uri = remember(url) { runCatching { Uri.parse(url) }.getOrNull() }
    val host = uri?.host.orEmpty()
    val secure = uri?.scheme == "https"
    val path = uri?.path.orEmpty().let { if (it == "/") "" else it }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (secure || url.isEmpty()) Icons.Default.Lock else Icons.Default.Warning,
            contentDescription = if (secure) "Secure connection" else "Not secure",
            tint = if (secure || url.isEmpty()) extra.success else extra.warning,
            modifier = Modifier.size(16.dp)
        )
        HSpace(8)
        Text(
            if (host.isEmpty()) {
                buildAnnotatedString { withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append("Loading…") } }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface)) { append(host) }
                    withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append(path) }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DisclaimerDialog(onProceed: () -> Unit, onExit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val points = listOf(
        "Unofficial app" to "Ergon is an independent, open-source project. It is not affiliated with, " +
            "endorsed by or sponsored by HoYoverse, COGNOSPHERE or miHoYo.",
        "Use at your own risk" to "Automating check-ins and code redemption may go against the game's Terms of " +
            "Service. HoYoverse can restrict or ban accounts at its discretion, and you are solely " +
            "responsible for what happens to yours.",
        "Your sign-in" to "You sign in on HoYoverse's own pages. The session tokens are kept encrypted on this " +
            "device and sent only to HoYoverse servers. Never share them with anyone.",
        "No warranty" to "Provided “as is” under the GNU GPL v3, without any warranty. It can stop working at " +
            "any time if the games or websites change."
    )
    AlertDialog(
        onDismissRequest = onExit,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Before you continue") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                points.forEach { (head, body) ->
                    Column {
                        Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onProceed) { Text("I understand, proceed") } },
        dismissButton = { TextButton(onClick = onExit) { Text("Exit") } }
    )
}
