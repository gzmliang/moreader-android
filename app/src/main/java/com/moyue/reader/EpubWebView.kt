package com.moyue.app.reader

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moyue.app.data.BookRepository
import kotlin.math.abs

/**
 * WebView-based EPUB chapter renderer with page-tap support.
 *
 * Left 30% tap = scroll up (previous page)
 * Right 30% tap = scroll down (next page)
 *
 * @param ttsHighlightIndex when set to >=0, highlights that paragraph index.
 *   Host (ReaderScreen) changes this to drive TTS follow-along.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubWebView(
    htmlContent: String?,
    baseUrl: String?,
    bgColor: String,
    textColor: String,
    fontScale: Float,
    onTextSelected: (String) -> Unit,
    onLinkClicked: (String) -> Unit = {},
    onParagraphClicked: ((Int) -> Unit)? = null,  // New: paragraph click callback
    ttsHighlightIndex: Int = -1,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val callbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val linkCallbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val paragraphCallbackRef = remember { mutableStateOf<(Int) -> Unit>({}) }
    LaunchedEffect(onTextSelected) { callbackRef.value = onTextSelected }
    LaunchedEffect(onLinkClicked) { linkCallbackRef.value = onLinkClicked }
    LaunchedEffect(onParagraphClicked) { paragraphCallbackRef.value = { idx -> onParagraphClicked?.invoke(idx) ?: Unit } }

    // TTS highlight: call JS when index changes
    LaunchedEffect(ttsHighlightIndex) {
        webView?.let { wv ->
            if (ttsHighlightIndex >= 0) {
                wv.evaluateJavascript("window.ttsHL($ttsHighlightIndex)", null)
            } else {
                wv.evaluateJavascript("window.ttsClear()", null)
            }
        }
    }

    // Dynamic theme update when font/bg/text changes
    LaunchedEffect(fontScale, bgColor, textColor) {
        webView?.let { wv ->
            val sz = (fontScale * 100).toInt()
            val js = """(function(){
                var e=document.getElementById('mt');
                if(!e)return;
                e.textContent='*{background-color:${bgColor}!important;color:${textColor}!important}'+
                'body{background-color:${bgColor}!important;color:${textColor}!important;font-family:sans-serif!important;line-height:1.8!important;margin:16px!important;font-size:${sz}%!important}'+
                'p{margin-bottom:0.8em!important}'+
                'h1,h2,h3,h4{font-weight:bold!important}'+
                'img{max-width:100%!important;height:auto!important}'+
                'a{color:#06C!important}'+
                '.tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}'+
                '::-webkit-scrollbar{width:0!important;height:0!important}';
            })()"""
            wv.evaluateJavascript(js, null)
        }
    }

    val initCss = """<style id="mt">
        *{background-color:${bgColor}!important;color:${textColor}!important}
        body{background-color:${bgColor}!important;color:${textColor}!important;font-family:sans-serif!important;line-height:1.8!important;margin:16px!important;font-size:${(fontScale*100).toInt()}%!important}
        p{margin-bottom:0.8em!important}
        h1,h2,h3,h4{font-weight:bold!important}
        img{max-width:100%!important;height:auto!important}
        a{color:#06C!important}
        .tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}
        ::-webkit-scrollbar{width:0!important;height:0!important}
        </style>"""

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                        loadWithOverviewMode = true
                        useWideViewPort = false
                        builtInZoomControls = false
                        displayZoomControls = false
                        defaultTextEncodingName = "UTF-8"
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // Intercept internal EPUB links
                            if (url.startsWith("file:///OEBPS/") || 
                                url.startsWith("file:///")) {
                                linkCallbackRef.value(url)
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            if (!url.startsWith("epub-resource://")) return null
                            val repo = repositoryRef?.get()
                            val bytes = repo?.getResource(url)
                            if (bytes != null) {
                                return WebResourceResponse(guessMimeType(url), "UTF-8", java.io.ByteArrayInputStream(bytes))
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("""(function(){
                                document.addEventListener('selectionchange',function(){
                                    clearTimeout(window._selTimer);
                                    window._selTimer=setTimeout(function(){
                                        var s=window.getSelection();
                                        if(s&&!s.isCollapsed&&s.toString().trim())MoreaderBridge.onTextSelected(s.toString().trim());
                                    },1500);
                                });
                                document.addEventListener('touchstart',function(){
                                    var s=window.getSelection();
                                    if(s&&!s.isCollapsed)s.removeAllRanges();
                                });
                                window.ttsHL=function(idx){
                                    document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(idx>=0&&idx<all.length){all[idx].classList.add('tts-hl');all[idx].scrollIntoView({behavior:'smooth',block:'center'});}
                                };
                                window.ttsClear=function(){document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});};
                                
                                // Paragraph click detection for "read from here"
                                document.querySelectorAll('p,h1,h2,h3,h4,h5,h6').forEach(function(el, idx){
                                    el.addEventListener('click', function(e){
                                        // Only trigger if not selecting text
                                        var s=window.getSelection();
                                        if(s&&s.isCollapsed){
                                            MoreaderBridge.onParagraphClicked(idx);
                                        }
                                    });
                                });
                                
                                // Intercept link clicks to handle internal navigation
                                document.addEventListener('click',function(e){
                                    var a=e.target.closest('a[href]');
                                    if(a){
                                        var href=a.getAttribute('href');
                                        if(href && !href.startsWith('http') && !href.startsWith('mailto:') && !href.startsWith('tel:')){
                                            e.preventDefault();
                                            MoreaderBridge.onLinkClicked(href);
                                        }
                                    }
                                });
                                
                                // For page-tap scrolling via scrollBy
                                window.pageUp=function(){window.scrollBy(0,-window.innerHeight*0.85);};
                                window.pageDown=function(){window.scrollBy(0,window.innerHeight*0.85);};
                            })()""", null)
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onTextSelected(text: String) { callbackRef.value(text) }
                        @JavascriptInterface
                        fun onLinkClicked(url: String) { linkCallbackRef.value(url) }
                        @JavascriptInterface
                        fun onParagraphClicked(index: Int) { paragraphCallbackRef.value(index) }
                    }, "MoreaderBridge")
                }
            },
            update = { wv ->
                webView = wv
                if (htmlContent != null) {
                    val html = buildString {
                        append("""<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0">""")
                        append(initCss)
                        append("</head><body>")
                        append(htmlContent)
                        append("</body></html>")
                    }
                    wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

var repositoryRef: java.lang.ref.WeakReference<BookRepository>? = null

private fun guessMimeType(url: String): String = when {
    url.endsWith(".jpg") || url.endsWith(".jpeg") -> "image/jpeg"
    url.endsWith(".png") -> "image/png"
    url.endsWith(".gif") -> "image/gif"
    url.endsWith(".svg") -> "image/svg+xml"
    url.endsWith(".webp") -> "image/webp"
    url.endsWith(".css") -> "text/css"
    url.endsWith(".js") -> "application/javascript"
    url.endsWith(".ttf") -> "font/ttf"
    url.endsWith(".woff") -> "font/woff"
    url.endsWith(".woff2") -> "font/woff2"
    else -> "application/octet-stream"
}
