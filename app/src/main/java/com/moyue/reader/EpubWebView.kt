package com.moyue.app.reader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moyue.app.data.BookRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    fontFamily: String = "sans-serif",
    fontWeight: String = "normal",
    lineHeightScale: Float = 1.7f,
    isDualPage: Boolean = false,
    onTextSelected: (String) -> Unit,
    onLinkClicked: (String) -> Unit = {},
    onParagraphClicked: ((Int) -> Unit)? = null,
    onScrollToParagraph: ((Int) -> Unit)? = null,
    ttsHighlightIndex: Int = -1,
    ttsSentenceIdx: Int = -1,
    ttsSentenceEnds: String = "",
    // ★ 当前段落的「清洗后正文」（与 Kotlin 侧切句所用文本完全同一份）：
    //   交给 WebView 里的 JS 建「字符 → DOM 落点」地图，让高亮与朗读用同一把尺子。
    //   为空时 JS 自动走旧逻辑（只做加法，不可能变差）。
    ttsParaText: String = "",
    scrollToParagraph: Int? = null,
    onParagraphScrolled: (() -> Unit)? = null,
    scrollToAnchor: String? = null,
    onAnchorScrolled: (() -> Unit)? = null,
    scrollToPixel: Int? = null,
    onPixelScrolled: (() -> Unit)? = null,
    scrollToNavEntry: com.moyue.app.ui.NavHistoryEntry? = null,
    onNavEntryRestored: (() -> Unit)? = null,
    onShowFootnote: ((String, String, Int, Int) -> Unit)? = null,
    highlightsToRender: List<Triple<Int, Int, Int>> = emptyList(),  // (startParagraph, startOffset, endOffset)
    highlightToRemove: Pair<Int, Int>? = null,  // (startOffset, endOffset)
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    isEinkMode: Boolean = false,
    pageAnimationMode: String = "smooth", // "smooth" or "instant"
    clearSelectionTrigger: Long = 0L,
    pageScrollTrigger: Long = 0L, // Positive: pageDown, Negative: pageUp
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedContent by remember { mutableStateOf<String?>(null) }
    val callbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val linkCallbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val paragraphCallbackRef = remember { mutableStateOf<(Int) -> Unit>({}) }
    val scrollCallbackRef = remember { mutableStateOf<(Int) -> Unit>({}) }
    val footnoteCallbackRef = remember { mutableStateOf<(String, String, Int, Int) -> Unit>({ _, _, _, _ -> }) }
    val prevChapterCallbackRef = remember { mutableStateOf<() -> Unit>({}) }
    val nextChapterCallbackRef = remember { mutableStateOf<() -> Unit>({}) }

    var slideBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val slideOffset = remember { Animatable(0f) }
    var slideDirection by remember { mutableStateOf(1) } // 1: next, -1: prev
    var isPageAnimating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val pageSlideRef = remember { mutableStateOf<(Int, Int) -> Unit>({ _, _ -> }) }

    fun performPageSlide(direction: Int, scrollYDelta: Int) {
        val wv = webView ?: return
        if (isPageAnimating) return
        isPageAnimating = true

        val w = wv.width
        val h = wv.height
        val scrollJs = if (isDualPage) {
            val deltaSpread = if (direction > 0) 1 else -1
            "window._applySpread(window._spreadIndex + ($deltaSpread));"
        } else {
            val scrollAmount = if (direction > 0) scrollYDelta else -scrollYDelta
            "window.scrollBy({top: $scrollAmount, left: 0, behavior: 'instant'});"
        }

        if (w <= 0 || h <= 0) {
            wv.evaluateJavascript(
                "$scrollJs window._isPageAnimating=false;",
                null
            )
            isPageAnimating = false
            return
        }

        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            wv.draw(canvas)
            slideBitmap = bmp
            slideDirection = direction

            wv.evaluateJavascript(scrollJs, null)

            val triggerAnimation = {
                coroutineScope.launch {
                    slideOffset.snapTo(0f)
                    val density = wv.resources.displayMetrics.density
                    val shadowPx = 20f * density
                    val targetX = if (direction > 0) -(w.toFloat() + shadowPx) else (w.toFloat() + shadowPx)
                    slideOffset.animateTo(
                        targetValue = targetX,
                        animationSpec = tween(
                            durationMillis = 240,
                            easing = FastOutSlowInEasing
                        )
                    )
                    slideBitmap?.recycle()
                    slideBitmap = null
                    isPageAnimating = false
                    wv.evaluateJavascript("window._isPageAnimating=false;", null)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                wv.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        triggerAnimation()
                    }
                })
            } else {
                triggerAnimation()
            }
        } catch (e: Exception) {
            wv.evaluateJavascript(
                "$scrollJs window._isPageAnimating=false;",
                null
            )
            slideBitmap?.recycle()
            slideBitmap = null
            isPageAnimating = false
        }
    }

    LaunchedEffect(Unit) {
        pageSlideRef.value = { dir, step -> performPageSlide(dir, step) }
    }
    LaunchedEffect(onTextSelected) { callbackRef.value = onTextSelected }
    LaunchedEffect(onLinkClicked) { linkCallbackRef.value = onLinkClicked }
    LaunchedEffect(onParagraphClicked) { paragraphCallbackRef.value = { idx -> onParagraphClicked?.invoke(idx) ?: Unit } }
    LaunchedEffect(onScrollToParagraph) { scrollCallbackRef.value = { idx -> onScrollToParagraph?.invoke(idx) ?: Unit } }
    LaunchedEffect(onShowFootnote) { footnoteCallbackRef.value = { text, href, p, sy -> onShowFootnote?.invoke(text, href, p, sy) ?: Unit } }
    LaunchedEffect(onPrevChapter) { prevChapterCallbackRef.value = { onPrevChapter?.invoke() ?: Unit } }
    LaunchedEffect(onNextChapter) { nextChapterCallbackRef.value = { onNextChapter?.invoke() ?: Unit } }

    LaunchedEffect(isEinkMode) {
        webView?.evaluateJavascript("window.isEink = $isEinkMode;", null)
    }

    LaunchedEffect(isDualPage) {
        webView?.evaluateJavascript("window.isDualPage = $isDualPage; if(window._updateLayout) window._updateLayout();", null)
    }

    LaunchedEffect(pageAnimationMode) {
        webView?.evaluateJavascript("window.pageAnimationMode = '$pageAnimationMode';", null)
    }

    LaunchedEffect(clearSelectionTrigger) {
        if (clearSelectionTrigger > 0L) {
            webView?.evaluateJavascript("(function(){ var s = window.getSelection(); if (s) s.removeAllRanges(); })()", null)
        }
    }

    LaunchedEffect(pageScrollTrigger) {
        if (pageScrollTrigger > 0L) {
            webView?.evaluateJavascript("window.pageDown();", null)
        } else if (pageScrollTrigger < 0L) {
            webView?.evaluateJavascript("window.pageUp();", null)
        }
    }

    // TTS paragraph + sentence highlight: merged into one effect
    // (was two separate effects — async evaluateJavascript order was undefined,
    //  causing initAndHighlight to run before ttsHL added .tts-hl class)
    var prevHighlightIdx by remember { mutableStateOf(ttsHighlightIndex) }
    LaunchedEffect(ttsHighlightIndex, ttsSentenceIdx, ttsSentenceEnds, ttsParaText) {
        val paraChanged = ttsHighlightIndex != prevHighlightIdx
        prevHighlightIdx = ttsHighlightIndex
        webView?.let { wv ->
            when {
                ttsHighlightIndex < 0 -> {
                    android.util.Log.d("EpubWV", "[TIME] ⏱ CLEAR ALL @${System.currentTimeMillis()}")
                    wv.evaluateJavascript("window.ttsClear();window.ttsSentenceClear()", null)
                }
                ttsSentenceIdx == 0 || paraChanged -> {
                    // 把 Kotlin 算好的句子边界传给 JS，避免 JS 自己切分产生偏移不一致
                    val endsJson = if (ttsSentenceEnds.isNotBlank()) "[$ttsSentenceEnds]" else "[]"
                    // 段落正文用 Base64 传递：彻底避开引号/换行/反斜杠的转义坑
                    val paraB64 = if (ttsParaText.isNotEmpty())
                        android.util.Base64.encodeToString(ttsParaText.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    else ""
                    android.util.Log.d("EpubWV", "[TIME] ⏱ initHL($ttsHighlightIndex,0,$endsJson,paraLen=${ttsParaText.length}) paraChanged=$paraChanged @${System.currentTimeMillis()}")
                    wv.evaluateJavascript("window.initAndHighlight($ttsHighlightIndex,0,$endsJson,'$paraB64')", null)
                }
                ttsSentenceIdx > 0 -> {
                    android.util.Log.d("EpubWV", "[TIME] ⏱ ttsHLSentence($ttsSentenceIdx) @${System.currentTimeMillis()}")
                    wv.evaluateJavascript("window.ttsHLSentence($ttsSentenceIdx)", null)
                }
                else -> {
                    wv.evaluateJavascript("window.ttsHL($ttsHighlightIndex)", null)
                }
            }
        }
    }

    // Scroll to paragraph for bookmark navigation & jump back (with highlight)
    LaunchedEffect(scrollToParagraph, scrollToNavEntry) {
        if (scrollToParagraph != null && scrollToParagraph >= 0) {
            val linkHref = (scrollToNavEntry?.linkHref ?: "").replace("'", "\\'")
            kotlinx.coroutines.delay(400)
            webView?.evaluateJavascript("window.scrollToPara($scrollToParagraph, '$linkHref')", null)
            onParagraphScrolled?.invoke()
        }
    }

    // Scroll to HTML anchor (e.g. #filepos0000154187 for monolithic EPUBs)
    LaunchedEffect(scrollToAnchor) {
        if (scrollToAnchor != null && scrollToAnchor.isNotEmpty()) {
            kotlinx.coroutines.delay(500)
            webView?.evaluateJavascript("""
                (function(){
                    var id = '$scrollToAnchor';
                    var el = document.getElementById(id);
                    if (!el) {
                        try {
                            el = document.querySelector('[name="' + CSS.escape(id) + '"]') || document.querySelector('a[name="' + CSS.escape(id) + '"]');
                        } catch(ex){}
                    }
                    if (el) {
                        el.scrollIntoView({behavior:'smooth', block:'start'});
                        return 'found';
                    }
                    return 'not_found';
                })()
            """.trimIndent()) { _ ->
                onAnchorScrolled?.invoke()
            }
        }
    }

    // Scroll to exact pixel position for instant 0-error jump back
    LaunchedEffect(scrollToPixel) {
        if (scrollToPixel != null && scrollToPixel >= 0) {
            webView?.evaluateJavascript(
                "(function(){var y=$scrollToPixel;var b=window.isEink?'instant':'smooth';window.scrollTo({top:y,behavior:b});})()",
                null
            )
            onPixelScrolled?.invoke()
        }
    }

    // Render user highlights in WebView — clear all then re-render on every change
    LaunchedEffect(highlightsToRender) {
        webView?.evaluateJavascript(
            """(function(){
                document.querySelectorAll('span.user-highlight').forEach(function(span){
                    var parent=span.parentNode;
                    while(span.firstChild)parent.insertBefore(span.firstChild,span);
                    parent.removeChild(span);
                    parent.normalize();
                });
            })()""", null
        )
        kotlinx.coroutines.delay(100)
        
        if (highlightsToRender.isNotEmpty()) {
            val hlArray = highlightsToRender.joinToString(",", "[", "]") { "[${it.first},${it.second},${it.third}]" }
            webView?.evaluateJavascript(
                """(function(){
                    var s=document.getElementById('_uh');
                    if(!s){s=document.createElement('style');s.id='_uh';document.head.appendChild(s);}
                    s.textContent='span.user-highlight{background:#FFE082!important;color:inherit!important;border-radius:2px!important;padding:0 2px!important}';
                    var highlights=$hlArray;
                    for(var i=0;i<highlights.length;i++){
                        var h=highlights[i];
                        if(window.applyHighlight)window.applyHighlight(h[0],h[1],h[2],'#FFE082');
                    }
                })()""", null
            )
        }
    }

    // Remove a highlight in WebView
    LaunchedEffect(highlightToRemove) {
        val pair = highlightToRemove ?: return@LaunchedEffect
        webView?.evaluateJavascript(
            "window.removeHighlight(${pair.first},${pair.second})", null
        )
    }

    // We only want to reload HTML when content or base URL changes.
    // Theme changes (bgColor, textColor, fontScale) should be applied via JS (LaunchedEffect below)
    // to avoid jumping to the top of the page.
    
    // Load HTML only when content or base URL actually changes
    LaunchedEffect(htmlContent, baseUrl) {
        if (htmlContent != null) {
            webView?.let { wv ->
                // Construct HTML with initial CSS. 
                // Note: This uses the *current* values of initCss variables at the moment of loading.
                val dualCss = if (isDualPage) {
                    """:root{--edge-pad:28px;--spine-gap:calc(var(--edge-pad)*2);--col-width:calc(50vw - var(--spine-gap));}html{height:100vh!important;overflow:hidden!important}body{height:100vh!important;margin:0!important;padding:24px var(--edge-pad)!important;box-sizing:border-box!important;overflow:hidden!important;column-width:var(--col-width)!important;column-gap:var(--spine-gap)!important;column-fill:auto!important;column-rule:1px solid rgba(128,128,128,0.18)!important;max-width:none!important}"""
                } else {
                    "body{margin:16px!important}"
                }
                val currentInitCss = """<style id="mt">
                    html{overflow-x:hidden!important}
                    *{background-color:${bgColor}!important;color:${textColor}!important}
                    body{background-color:${bgColor}!important;color:${textColor}!important;font-family:${fontFamily}!important;line-height:${lineHeightScale}!important;font-size:${(fontScale*100).toInt()}%!important;font-weight:${fontWeight}!important}
                    p,div,span,li,a,h1,h2,h3,h4,h5,h6{font-size:${(fontScale*100).toInt()}%!important;line-height:${lineHeightScale}!important;font-family:${fontFamily}!important;font-weight:${fontWeight}!important}
                    p{margin-bottom:0.8em!important}
                    h1,h2,h3,h4{font-weight:bold!important}
                    img{max-width:100%!important;height:auto!important}
                    a{color:#06C!important}
                    $dualCss
                    .tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}
                    .tts-hl ruby, .tts-hl rt, .tts-hl rb, .tts-hl rp{background-color:transparent!important}
                    .tts-sentence-hl{background-color:rgba(34,197,94,0.30)!important;border-radius:2px!important;padding:1px 2px!important;-webkit-box-decoration-break:clone!important;box-decoration-break:clone!important}
                    .tts-sentence-hl ruby, .tts-sentence-hl rt, .tts-sentence-hl rb, .tts-sentence-hl rp{background-color:transparent!important}
                    .user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}
                    ::-webkit-scrollbar{width:0!important;height:0!important}
                    </style>"""
                
                val html = buildString {
                    append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0\">")
                    append(currentInitCss)
                    append("</head><body>")
                    append(htmlContent)
                    append("</body></html>")
                }
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                lastLoadedContent = htmlContent
            }
        }
    }

    // Dynamic theme/font update via JS (does not reload page, preserves scroll)
    // For font size changes: save scroll ratio before, restore after to keep visual position
    var lastFontScale by remember { mutableStateOf(fontScale) }
    var lastLineHeight by remember { mutableStateOf(lineHeightScale) }
    var lastDualPage by remember { mutableStateOf(isDualPage) }
    LaunchedEffect(fontScale, bgColor, textColor, fontFamily, fontWeight, lineHeightScale, isDualPage) {
        webView?.let { wv ->
            if (lastLoadedContent == null) return@let // Not loaded yet
            
            val sz = (fontScale * 100).toInt()
            
            // If font size, line height or dual page layout changed, save scroll ratio before applying new size
            val layoutChanged = (lastFontScale != fontScale) || (lastLineHeight != lineHeightScale) || (lastDualPage != isDualPage)
            lastFontScale = fontScale
            lastLineHeight = lineHeightScale
            lastDualPage = isDualPage

            val dualJs = if (isDualPage) {
                "':root{--edge-pad:28px;--spine-gap:calc(var(--edge-pad)*2);--col-width:calc(50vw - var(--spine-gap));}html{height:100vh!important;overflow:hidden!important}body{height:100vh!important;margin:0!important;padding:24px var(--edge-pad)!important;box-sizing:border-box!important;overflow:hidden!important;column-width:var(--col-width)!important;column-gap:var(--spine-gap)!important;column-fill:auto!important;column-rule:1px solid rgba(128,128,128,0.18)!important;max-width:none!important}'+ "
            } else {
                "'html{height:auto!important;overflow-x:hidden!important;overflow-y:visible!important}body{height:auto!important;column-count:auto!important;column-width:auto!important;column-gap:normal!important;column-rule:none!important;margin:16px!important;padding:0!important;overflow:visible!important;max-width:100%!important}'+ "
            }
            
            val js = """(function(){
                var e=document.getElementById('mt');
                if(!e)return;
                e.textContent='*{background-color:${bgColor}!important;color:${textColor}!important}'+
                'body{background-color:${bgColor}!important;color:${textColor}!important;font-family:${fontFamily}!important;line-height:${lineHeightScale}!important;font-size:${sz}%!important;font-weight:${fontWeight}!important}'+
                'p,div,span,li,a,h1,h2,h3,h4,h5,h6{font-size:${sz}%!important;line-height:${lineHeightScale}!important;font-family:${fontFamily}!important;font-weight:${fontWeight}!important}'+
                'p{margin-bottom:0.8em!important}'+
                'h1,h2,h3,h4{font-weight:bold!important}'+
                'img{max-width:100%!important;height:auto!important}'+
                'a{color:#06C!important}'+
                $dualJs
                '.tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}'+
                '.tts-hl ruby, .tts-hl rt, .tts-hl rb, .tts-hl rp{background-color:transparent!important}'+
                '.tts-sentence-hl{background-color:rgba(34,197,94,0.30)!important;border-radius:2px!important;padding:1px 2px!important;-webkit-box-decoration-break:clone!important;box-decoration-break:clone!important}'+
                '.tts-sentence-hl ruby, .tts-sentence-hl rt, .tts-sentence-hl rb, .tts-sentence-hl rp{background-color:transparent!important}'+
                '.user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}'+
                '::-webkit-scrollbar{width:0!important;height:0!important}';
            })()"""
            
            if (layoutChanged) {
                // Step 1: Save scroll ratio
                wv.evaluateJavascript(
                    """(function(){
                        if(window.isDualPage){
                            var maxIdx=window._getMaxSpreadIndex();
                            window._savedSpreadRatio=maxIdx>0?(window._spreadIndex/maxIdx):0;
                        }else{
                            var sy=window.scrollY;
                            var sh=document.body.scrollHeight;
                            var vh=window.innerHeight;
                            window._savedScrollRatio=sy/Math.max(1,sh-vh);
                        }
                    })()"""
                ) { _ ->
                    // Step 2: Apply font change
                    wv.evaluateJavascript(js) { _ ->
                        wv.evaluateJavascript("if(window._updateLayout) window._updateLayout();", null)
                        // Step 3: Restore scroll ratio after reflow
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            wv.evaluateJavascript(
                                """(function(){
                                    if(window._updateLayout) window._updateLayout();
                                    if(window.isDualPage){
                                        if(window._savedSpreadRatio!==undefined){
                                            var maxIdx=window._getMaxSpreadIndex();
                                            var targetIdx=Math.round(window._savedSpreadRatio*maxIdx);
                                            window._applySpread(targetIdx);
                                        }
                                    }else{
                                        if(window._savedScrollRatio!==undefined){
                                            var sh=document.body.scrollHeight;
                                            var vh=window.innerHeight;
                                            window.scrollTo(0,window._savedScrollRatio*Math.max(1,sh-vh));
                                        }
                                    }
                                })()""",
                                null
                            )
                        }, 200)
                    }
                }
            } else {
                wv.evaluateJavascript(js) {
                    wv.evaluateJavascript("if(window._updateLayout) window._updateLayout();", null)
                }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                object : WebView(ctx) {
                    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                        return if (callback != null) {
                            super.startActionMode(EmptyActionModeCallback(callback), type)
                        } else {
                            super.startActionMode(callback, type)
                        }
                    }
                    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
                        return if (callback != null) {
                            super.startActionMode(EmptyActionModeCallback(callback))
                        } else {
                            super.startActionMode(callback)
                        }
                    }
                }.apply {
                    webView = this
                    onWebViewCreated?.invoke(this)
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
                                        if(s&&!s.isCollapsed&&s.toString().trim()){
                                            var info=MoreaderBridge._getSelectionInfo();
                                            MoreaderBridge.onTextSelected(info);
                                        }
                                    },800);
                                });
                                document.addEventListener('touchstart',function(){
                                    var s=window.getSelection();
                                    if(s&&!s.isCollapsed)s.removeAllRanges();
                                });
                                window.isEink = $isEinkMode;
                                window.isDualPage = $isDualPage;
                                window._updateLayout = function() {
                                    if (window.isDualPage) {
                                        var H = window.innerHeight;
                                        var W = window.innerWidth;
                                        if (H <= 0 || W <= 0) return;
                                        document.documentElement.style.setProperty('height', H + 'px', 'important');
                                        document.body.style.setProperty('height', H + 'px', 'important');
                                        document.documentElement.style.setProperty('overflow', 'hidden', 'important');
                                        document.body.style.setProperty('overflow', 'hidden', 'important');
                                        var P = 28;
                                        var gap = P * 2;
                                        var colW = Math.floor((W / 2) - gap);
                                        document.body.style.setProperty('column-width', colW + 'px', 'important');
                                        document.body.style.setProperty('column-gap', gap + 'px', 'important');
                                        document.body.style.setProperty('column-fill', 'auto', 'important');
                                        document.body.style.setProperty('padding', '24px ' + P + 'px', 'important');
                                        document.body.style.setProperty('margin', '0px', 'important');
                                        document.body.style.setProperty('box-sizing', 'border-box', 'important');
                                        document.body.style.setProperty('column-rule', '1px solid rgba(128,128,128,0.18)', 'important');

                                        // 针对大图/封面/插图做自适应高度约束与禁止跨栏裁切：
                                        // 避免图片高度超出单栏可用高度从而被跨栏切成两半
                                        var availImgH = Math.max(100, H - 48);
                                        document.querySelectorAll('img').forEach(function(img){
                                            img.style.setProperty('max-width', '100%', 'important');
                                            img.style.setProperty('max-height', availImgH + 'px', 'important');
                                            img.style.setProperty('width', 'auto', 'important');
                                            img.style.setProperty('height', 'auto', 'important');
                                            img.style.setProperty('object-fit', 'contain', 'important');
                                            img.style.setProperty('display', 'block', 'important');
                                            img.style.setProperty('margin', '0 auto', 'important');
                                            img.style.setProperty('break-inside', 'avoid', 'important');
                                            img.style.setProperty('page-break-inside', 'avoid', 'important');
                                        });
                                        document.querySelectorAll('.cover, .titlepage').forEach(function(el){
                                            el.style.setProperty('max-height', availImgH + 'px', 'important');
                                            el.style.setProperty('text-align', 'center', 'important');
                                            el.style.setProperty('break-inside', 'avoid', 'important');
                                            el.style.setProperty('page-break-inside', 'avoid', 'important');
                                        });

                                        if (window._spreadIndex !== undefined && window._spreadIndex > 0) {
                                            window._applySpread(window._spreadIndex);
                                        }
                                    } else {
                                        document.documentElement.style.removeProperty('height');
                                        document.body.style.removeProperty('height');
                                        document.documentElement.style.removeProperty('overflow');
                                        document.body.style.removeProperty('overflow');
                                        document.body.style.removeProperty('column-width');
                                        document.body.style.removeProperty('column-gap');
                                        document.body.style.removeProperty('column-fill');
                                        document.body.style.removeProperty('padding');
                                        document.body.style.removeProperty('margin');
                                        document.body.style.removeProperty('column-rule');
                                        document.querySelectorAll('img').forEach(function(img){
                                            img.style.removeProperty('max-height');
                                            img.style.removeProperty('width');
                                            img.style.removeProperty('height');
                                            img.style.removeProperty('object-fit');
                                            img.style.removeProperty('display');
                                            img.style.removeProperty('margin');
                                            img.style.removeProperty('break-inside');
                                            img.style.removeProperty('page-break-inside');
                                        });
                                        document.querySelectorAll('.cover, .titlepage').forEach(function(el){
                                            el.style.removeProperty('max-height');
                                            el.style.removeProperty('text-align');
                                            el.style.removeProperty('break-inside');
                                            el.style.removeProperty('page-break-inside');
                                        });
                                    }
                                };
                                window.addEventListener('resize', function(){ window._updateLayout(); });
                                window._updateLayout();
                                setTimeout(function(){ window._updateLayout(); }, 50);
                                setTimeout(function(){ window._updateLayout(); }, 200);
                                window.getScrollBehavior = function(){ return window.isEink ? 'instant' : 'smooth'; };
                                window.ttsHL=function(idx){
                                    document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(idx>=0&&idx<all.length){all[idx].classList.add('tts-hl');all[idx].scrollIntoView({behavior: window.getScrollBehavior(), block:'center'});}
                                };
                                window.ttsClear=function(){document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});};
                                // ===== 【高亮对齐地图】与浏览器扩展端同一套方案 =====
                                // 病根：Kotlin 用「清洗后文本」算句子边界，JS 却去数「原始 DOM 字符」，
                                // 两把尺子长度不同 → 绿条整体漂移（这句不亮 / 亮到上一句）。
                                // 做法：把清洗文本的每个字符映射回真实 DOM 落点，高亮直接换算 Range。
                                window._paraMap=null;
                                window._paraCleanText='';
                                window._b64ToUtf8=function(b64){
                                    try{
                                        var bin=atob(b64);
                                        var bytes=new Uint8Array(bin.length);
                                        for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);
                                        return new TextDecoder('utf-8').decode(bytes);
                                    }catch(e){return '';}
                                };
                                window._buildParaMap=function(el,cleanText){
                                    if(!el||!cleanText)return null;
                                    try{
                                        var nodes=[],chars=[];
                                        var walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,null);
                                        while(walker.nextNode()){
                                            var tn=walker.currentNode;
                                            var pn=tn.parentNode;
                                            if(pn){
                                                var tag=pn.nodeName;
                                                if(tag==='SCRIPT'||tag==='STYLE'||tag==='RT'||tag==='RP'||tag==='RTC'||tag==='SUP'||tag==='SUB')continue;
                                            }
                                            var s=tn.data||'';
                                            for(var k=0;k<s.length;k++){chars.push(s.charAt(k));nodes.push({node:tn,off:k});}
                                        }
                                        if(!chars.length)return null;
                                        var map=new Array(cleanText.length);
                                        var i=0;
                                        for(var j=0;j<cleanText.length;j++){
                                            var c=cleanText.charAt(j);
                                            if(c===' '||c==='\t'||c==='\n'||c==='\r'){
                                                while(i<chars.length&&!/\s/.test(chars[i]))i++;
                                            }else{
                                                while(i<chars.length&&chars[i]!==c)i++;
                                            }
                                            if(i>=chars.length)return null;
                                            map[j]=nodes[i];
                                            i++;
                                        }
                                        return map;
                                    }catch(e){return null;}
                                };
                                // Sentence-level highlight
                                window.ttsSentences=[];
                                window._ttsSentencePara=null;
                                window.ttsSentEnds=[];  // 从 Kotlin 传入的句子边界
                                window.initAndHighlight=function(paraIdx,sentenceIdx,sentEnds,paraB64){
                                    console.log('[initHL] para='+paraIdx+' sent='+sentenceIdx+' ends='+JSON.stringify(sentEnds));
                                    MoreaderBridge.jsLog('[JS] initAndHighlight('+paraIdx+','+sentenceIdx+')');
                                    document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(paraIdx<0||paraIdx>=all.length){MoreaderBridge.jsLog('[JS] initHL bad paraIdx');return;}
                                    var el=all[paraIdx];
                                    el.classList.add('tts-hl');
                                    el.scrollIntoView({behavior: window.getScrollBehavior(), block:'center'});
                                    window._ttsSentencePara=el;
                                    // 使用 Kotlin 传来的句子边界
                                    window.ttsSentEnds=sentEnds||[];
                                    MoreaderBridge.jsLog('[JS] sentEnds from Kotlin: '+JSON.stringify(window.ttsSentEnds));
                                    // ★ 建地图：仅当地图能完整走通、且边界落在清洗文本范围内才启用（否则回退旧逻辑）
                                    window._paraMap=null;
                                    window._paraCleanText='';
                                    try{
                                        var lastEnd=(window.ttsSentEnds.length>0)?window.ttsSentEnds[window.ttsSentEnds.length-1]:0;
                                        if(paraB64&&lastEnd>0){
                                            var cleanText=window._b64ToUtf8(paraB64);
                                            if(cleanText&&lastEnd<=cleanText.length){
                                                var builtMap=window._buildParaMap(el,cleanText);
                                                if(builtMap){
                                                    window._paraMap=builtMap;
                                                    window._paraCleanText=cleanText;
                                                    MoreaderBridge.jsLog('[JS] 高亮地图启用 cleanLen='+cleanText.length+' lastEnd='+lastEnd);
                                                }else{
                                                    MoreaderBridge.jsLog('[JS] 高亮地图构建失败，回退旧逻辑');
                                                }
                                            }else{
                                                MoreaderBridge.jsLog('[JS] 地图自检不通过 cleanLen='+(cleanText?cleanText.length:'null')+' lastEnd='+lastEnd+'，回退旧逻辑');
                                            }
                                        }
                                    }catch(e){MoreaderBridge.jsLog('[JS] 地图异常: '+e.message);window._paraMap=null;window._paraCleanText='';}
                                    // 重建 ttsSentences 供兼容使用
                                    window.ttsSentences=[];
                                    var prev=0;
                                    for(var i=0;i<window.ttsSentEnds.length;i++){
                                        var e=window.ttsSentEnds[i];
                                        window.ttsSentences.push({start:prev,end:e});
                                        prev=e;
                                    }
                                    MoreaderBridge.jsLog('[JS] ttsSentences rebuilt: '+window.ttsSentences.length+' sentences');
                                    if(sentenceIdx>=0&&sentenceIdx<window.ttsSentences.length){
                                        window.ttsSentenceClear();
                                        window._ttsHLSentence(sentenceIdx);
                                    }
                                };
                                window.ttsHLSentence=function(idx){
                                    console.log('[ttsHL] idx='+idx+' sentences='+(window.ttsSentences?window.ttsSentences.length:'null')+' para='+(window._ttsSentencePara?window._ttsSentencePara.tagName:'null'));
                                    window.ttsSentenceClear();
                                    window._ttsHLSentence(idx);
                                };
                                window._ttsHLSentence=function(idx){
                                    console.log('[_ttsHL] idx='+idx+' sent='+JSON.stringify(window.ttsSentences&&window.ttsSentences[idx]));
                                    MoreaderBridge.jsLog('[JS] _ttsHLSentence('+idx+') start, sentences='+(window.ttsSentences?window.ttsSentences.length:'null')+' para='+(window._ttsSentencePara?window._ttsSentencePara.tagName:'null'));
                                    if(!window.ttsSentences||idx<0||idx>=window.ttsSentences.length){
                                        MoreaderBridge.jsLog('[JS] _ttsHL BOUNDS FAIL: idx='+idx+' len='+(window.ttsSentences?window.ttsSentences.length:'null'));
                                        return;
                                    }
                                    var sent=window.ttsSentences[idx];
                                    MoreaderBridge.jsLog('[JS] sent['+idx+']={start:'+sent.start+',end:'+sent.end+'}');
                                    var hl=window._ttsSentencePara;
                                    if(!hl){MoreaderBridge.jsLog('[JS] _ttsHL no para');return;}
                                    var range=document.createRange();
                                    var usedMap=false;
                                    // ① 首选（修复）：每句现场重建地图。ttsSentenceClear 的 normalize() 会销毁旧文本节点，
                                    //    段首建的那份地图到后面就是「脱树空节点」，必须重建，否则 setStart 直接抛异常。
                                    if(window._paraCleanText&&sent.start>=0&&sent.end<=window._paraCleanText.length){
                                        var freshMap=window._buildParaMap(hl,window._paraCleanText);
                                        if(freshMap){window._paraMap=freshMap;}
                                        var useMap=freshMap||window._paraMap;
                                        var mnStart=useMap?useMap[sent.start]:null,mnEnd=useMap?useMap[sent.end-1]:null;
                                        if(mnStart&&mnEnd){
                                            try{
                                                var sRuby=mnStart.node.parentElement&&mnStart.node.parentElement.closest?mnStart.node.parentElement.closest('ruby'):null;
                                                var eRuby=mnEnd.node.parentElement&&mnEnd.node.parentElement.closest?mnEnd.node.parentElement.closest('ruby'):null;
                                                if(sRuby&&hl.contains(sRuby)&&mnStart.off===0){
                                                    range.setStartBefore(sRuby);
                                                }else{
                                                    range.setStart(mnStart.node,mnStart.off);
                                                }
                                                if(eRuby&&hl.contains(eRuby)){
                                                    range.setEndAfter(eRuby);
                                                }else{
                                                    range.setEnd(mnEnd.node,mnEnd.off+1);
                                                }
                                                // 说明：清洗只删除字符（装饰符/脚注/拼音/多余空白），不改变字符顺序，
                                                // 因此地图换算出的范围天然正确；中间夹带的已删除字符（如 • 或脚注 *）
                                                // 属于正常现象，不应据此回退。
                                                usedMap=true;
                                            }catch(e){MoreaderBridge.jsLog('[JS] 地图取范围异常，回退: '+e.message);usedMap=false;}
                                        }
                                    }
                                    // ② 兜底：旧逻辑（数原始 DOM 字符）—— 行为与过去完全一致，绝不会更差
                                    if(!usedMap){
                                        var textNodes=[];
                                        var walker=document.createTreeWalker(hl,NodeFilter.SHOW_TEXT);
                                        var charCount=0;
                                        while(walker.nextNode()){
                                            textNodes.push({node:walker.currentNode,start:charCount,end:charCount+walker.currentNode.textContent.length});
                                            charCount+=walker.currentNode.textContent.length;
                                        }
                                        var sTN=null,sOff=0,eTN=null,eOff=0;
                                        for(var i=0;i<textNodes.length;i++){
                                            var tn=textNodes[i];
                                            if(!sTN&&tn.start<=sent.start&&tn.end>sent.start){sTN=tn.node;sOff=sent.start-tn.start;}
                                            if(tn.start<sent.end&&tn.end>=sent.end){eTN=tn.node;eOff=sent.end-tn.start;}
                                        }
                                        if(!sTN||!eTN){MoreaderBridge.jsLog('[JS] _ttsHL no nodes: sTN='+(sTN?'ok':'null')+' eTN='+(eTN?'ok':'null'));return;}
                                        MoreaderBridge.jsLog('[JS] 旧逻辑定位 sOff='+sOff+' eOff='+eOff);
                                        range.setStart(sTN,sOff);
                                        range.setEnd(eTN,eOff);
                                    }
                                    var span=document.createElement('span');
                                    span.className='tts-sentence-hl';
                                    span.setAttribute('data-tts-sentence','1');
                                    try{
                                        range.surroundContents(span);
                                        MoreaderBridge.jsLog('[JS] surroundContents OK');
                                    }catch(e){
                                        // 修复：跨元素（<a>/<em>/<br> 等）边界时 surroundContents 会拒绝，
                                        // 旧代码只 insertNode 一个「空 span」，等于没高亮。改为抽出内容再塞进 span。
                                        MoreaderBridge.jsLog('[JS] surroundContents EXCEPTION: '+e.message+' → extractContents 兜底');
                                        try{
                                            var frag=range.extractContents();
                                            while(span.firstChild)span.removeChild(span.firstChild);
                                            span.appendChild(frag);
                                            range.insertNode(span);
                                        }catch(e2){
                                            MoreaderBridge.jsLog('[JS] extractContents 兜底也失败: '+e2.message);
                                            try{range.insertNode(span);}catch(e3){}
                                        }
                                    }
                                    // 视口与滚动控制：
                                    // 在双页书本模式 (isDualPage) 下，只要当前高亮句子还在当前对开屏视口内，
                                    // 绝对严禁位移！文字纹丝不动，先读左边，再读右边；
                                    // 只有当句子完全超出当前屏幕右侧或左侧时，才触发整屏对开翻页！
                                    if(window.isDualPage){
                                        var rect=span.getBoundingClientRect();
                                        var vw=window.innerWidth;
                                        // 检查是否已经在当前可视对开屏内 (留出左右 15px 安全边距)
                                        var isVisible=(rect.left >= 15 && rect.right <= vw - 15);
                                        if(!isVisible){
                                            // 超出当前屏，根据落点翻页
                                            if (rect.right > vw - 15) {
                                                window.pageDown();
                                            } else if (rect.left < 15) {
                                                window.pageUp();
                                            }
                                        }
                                    }else{
                                        // 单页模式：居中平滑跟随
                                        span.scrollIntoView({behavior: window.getScrollBehavior(), block:'center'});
                                    }
                                };
                                window.ttsSentenceClear=function(){
                                    document.querySelectorAll('span[data-tts-sentence=\"1\"]').forEach(function(s){
                                        var p=s.parentNode;
                                        while(s.firstChild)p.insertBefore(s.firstChild,s);
                                        p.removeChild(s);
                                        p.normalize();
                                    });
                                };
                                window.scrollToPara=function(idx, linkHref){
                                    if(idx===0 && !linkHref){window.scrollTo(0,0);return;}
                                    function doHighlightAndScroll(){
                                        var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                        if(idx>=0&&idx<all.length){
                                            var el=all[idx];
                                            var targetA=null;
                                            if(linkHref){
                                                var shortHref=linkHref.indexOf('#')>=0?linkHref.substring(linkHref.indexOf('#')):linkHref;
                                                try{
                                                    targetA=el.querySelector('a[href*="'+CSS.escape(shortHref)+'"]') ||
                                                            document.querySelector('a[href*="'+CSS.escape(shortHref)+'"]');
                                                }catch(e){}
                                                if(!targetA){
                                                    try{targetA=el.querySelector('a');}catch(e){}
                                                }
                                            }
                                            if(window.isDualPage){
                                                var target = targetA || el;
                                                var rect = target.getBoundingClientRect();
                                                var curOffset = window._spreadIndex * window.innerWidth;
                                                var targetSpread = Math.floor((curOffset + rect.left) / window.innerWidth);
                                                window._applySpread(targetSpread);
                                            } else {
                                                if(targetA){
                                                    targetA.scrollIntoView({behavior: window.getScrollBehavior(), block:'center'});
                                                }else{
                                                    el.scrollIntoView({behavior: window.getScrollBehavior(), block:'center'});
                                                }
                                            }
                                            if(targetA){
                                                targetA.style.backgroundColor='#FFE082';
                                                targetA.style.borderRadius='3px';
                                                targetA.style.padding='0 3px';
                                                targetA.style.boxShadow='0 0 0 2px #FFB300';
                                                setTimeout(function(){
                                                    targetA.style.transition='all 1s ease';
                                                    targetA.style.backgroundColor='';
                                                    targetA.style.boxShadow='';
                                                    targetA.style.padding='';
                                                },3000);
                                            }
                                            el.style.backgroundColor='rgba(59,130,246,0.18)';
                                            el.style.borderRadius='4px';
                                            el.style.transition='background-color 0.8s ease';
                                            setTimeout(function(){el.style.backgroundColor='';},2500);
                                            return true;
                                        }
                                        return false;
                                    }
                                    if(!doHighlightAndScroll()){
                                        setTimeout(doHighlightAndScroll,150);
                                        setTimeout(doHighlightAndScroll,400);
                                    }
                                };
                                
                                // Get selection info with paragraph index and offsets
                                MoreaderBridge._getSelectionInfo=function(){
                                    var s=window.getSelection();
                                    if(!s||s.isCollapsed)return JSON.stringify({text:'',paraIdx:-1,startOffset:-1,endOffset:-1});
                                    var text=s.toString().trim();
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    var range=s.getRangeAt(0);
                                    
                                    // Find start paragraph and offset
                                    var startNode=range.startContainer;
                                    var endNode=range.endContainer;
                                    var startParaIdx=-1,startOffset=range.startOffset;
                                    var endParaIdx=-1,endOffset=range.endOffset;
                                    
                                    for(var i=0;i<all.length;i++){
                                        if(all[i].contains(startNode)){
                                            startParaIdx=i;
                                            // Calculate offset from paragraph start
                                            var walker=document.createTreeWalker(all[i],NodeFilter.SHOW_TEXT);
                                            var charCount=0;
                                            var found=false;
                                            while(walker.nextNode()&&!found){
                                                var node=walker.currentNode;
                                                if(node===startNode){
                                                    startOffset=charCount+range.startOffset;
                                                    found=true;
                                                }else{
                                                    charCount+=node.textContent.length;
                                                }
                                            }
                                        }
                                        if(all[i].contains(endNode)){
                                            endParaIdx=i;
                                            var walker=document.createTreeWalker(all[i],NodeFilter.SHOW_TEXT);
                                            var charCount=0;
                                            var found=false;
                                            while(walker.nextNode()&&!found){
                                                var node=walker.currentNode;
                                                if(node===endNode){
                                                    endOffset=charCount+range.endOffset;
                                                    found=true;
                                                }else{
                                                    charCount+=node.textContent.length;
                                                }
                                            }
                                        }
                                    }
                                    
                                    return JSON.stringify({text:text,paraIdx:startParaIdx,startOffset:startOffset,endParaIdx:endParaIdx,endOffset:endOffset});
                                };
                                
                                // Paragraph click detection for "read from here"
                                document.querySelectorAll('p,h1,h2,h3,h4,h5,h6').forEach(function(el, idx){
                                    el.addEventListener('click', function(e){
                                        if (e.target && e.target.closest('a')) {
                                            return; // 点击链接或角标时不触发段落朗读浮窗，避免与注释弹窗遮挡冲突
                                        }
                                        var s=window.getSelection();
                                        if(s&&s.isCollapsed){
                                            MoreaderBridge.onParagraphClicked(idx);
                                        }
                                    });
                                });
                                
                                // Highlight rendering
                                window.applyHighlight=function(paraIdx,startOffset,endOffset,color){
                                    try{
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(paraIdx<0||paraIdx>=all.length){console.log('HL bad para');return;}
                                    var el=all[paraIdx];
                                    var text=el.textContent;
                                    if(startOffset<0||endOffset>text.length||startOffset>=endOffset){console.log('HL bad offset');return;}
                                    var textNodes=[];
                                    var walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT);
                                    var charCount=0;
                                    while(walker.nextNode()){
                                        textNodes.push({node:walker.currentNode,start:charCount,end:charCount+walker.currentNode.textContent.length});
                                        charCount+=walker.currentNode.textContent.length;
                                    }
                                    var startTN=null,startOff=0,endTN=null,endOff=0;
                                    for(var i=0;i<textNodes.length;i++){
                                        var tn=textNodes[i];
                                        if(!startTN&&tn.start<=startOffset&&tn.end>startOffset){startTN=tn.node;startOff=startOffset-tn.start;}
                                        if(tn.start<endOffset&&tn.end>=endOffset){endTN=tn.node;endOff=endOffset-tn.start;}
                                    }
                                    if(!startTN||!endTN){console.log('HL no nodes');return;}
                                    var range=document.createRange();
                                    range.setStart(startTN,startOff);
                                    range.setEnd(endTN,endOff);
                                    var frag=range.extractContents();
                                    var span=document.createElement('span');
                                    span.className='user-highlight';
                                    span.style.setProperty('background-color',color,'important');
                                    span.setAttribute('data-hl-start',String(startOffset));
                                    span.setAttribute('data-hl-end',String(endOffset));
                                    span.appendChild(frag);
                                    range.insertNode(span);
                                    console.log('HL OK');
                                    }catch(e){console.log('HL err: '+e.message);}
                                };
                                
                                window.removeHighlight=function(startOffset,endOffset){
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    for(var p=0;p<all.length;p++){
                                        var el=all[p];
                                        var spans=el.querySelectorAll('span.user-highlight');
                                        for(var i=0;i<spans.length;i++){
                                            var sp=spans[i];
                                            if(parseInt(sp.dataset.hlStart)===startOffset&&parseInt(sp.dataset.hlEnd)===endOffset){
                                                var parent=sp.parentNode;
                                                while(sp.firstChild)parent.insertBefore(sp.firstChild,sp);
                                                parent.removeChild(sp);
                                                parent.normalize();
                                                return;
                                            }
                                        }
                                    }
                                };
                                
                                // Intercept link clicks to handle internal navigation & footnote preview
                                document.addEventListener('click',function(e){
                                    var a=e.target.closest('a[href]');
                                    if(a){
                                        var href=a.getAttribute('href');
                                        if(href && !href.startsWith('http') && !href.startsWith('mailto:') && !href.startsWith('tel:')){
                                            e.preventDefault();
                                            // Get current clicked paragraph index, exact scrollY and element viewport top before navigating
                                            var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                            var scrollY=Math.round(window.scrollY);
                                            var rect=a.getBoundingClientRect();
                                            var elementTop=Math.round(rect.top);
                                            var clickedPara=a.closest('p,h1,h2,h3,h4,h5,h6');
                                            var clickedIdx=-1;
                                            if(clickedPara){
                                                for(var i=0;i<all.length;i++){
                                                    if(all[i]===clickedPara){
                                                        clickedIdx=i;
                                                        break;
                                                    }
                                                }
                                            }
                                            if(clickedIdx<0){
                                                for(var i=0;i<all.length;i++){
                                                    if(all[i].contains(a)){
                                                        clickedIdx=i;
                                                        break;
                                                    }
                                                }
                                            }
                                            if(clickedIdx<0){
                                                for(var i=0;i<all.length;i++){
                                                    if(all[i].offsetTop>scrollY+window.innerHeight*0.3)break;
                                                    clickedIdx=i;
                                                }
                                            }
                                            if(clickedIdx<0) clickedIdx=0;

                                            // 方案 B：尝试在当前 DOM 嗅探注释内容（Footnote Preview）
                                            var anchorIdx = href.indexOf('#');
                                            if (anchorIdx >= 0) {
                                                var anchorId = href.substring(anchorIdx + 1).trim();
                                                if (anchorId) {
                                                    var target = document.getElementById(anchorId);
                                                    if (!target) {
                                                        try {
                                                            target = document.querySelector('[name="' + CSS.escape(anchorId) + '"]') ||
                                                                     document.querySelector('a[name="' + CSS.escape(anchorId) + '"]');
                                                        } catch(ex){}
                                                    }
                                                    if (target) {
                                                        var noteContainer = target.closest('li, aside, dd, p, [role="doc-footnote"]') || target;
                                                        var noteText = (noteContainer.textContent || '').trim();
                                                        noteText = noteText.replace(/[\u21A9\u2191\u21E7\^]/g, '').trim();
                                                        if (noteText.length > 0 && noteText.length < 1500) {
                                                            MoreaderBridge.onShowFootnote(noteText, href, clickedIdx, scrollY);
                                                            return;
                                                        }
                                                    }
                                                }
                                            }

                                            // 方案 A：直接触发精准跳转
                                            MoreaderBridge.onLinkClicked(href, clickedIdx, scrollY, elementTop);
                                        }
                                    }
                                });
                                
                                // Native-assisted smooth slide or instant paging
                                window._isPageAnimating = false;

                                window._spreadIndex = 0;
                                window._applySpread = function(idx) {
                                    window._spreadIndex = Math.max(0, idx);
                                    if (window.isDualPage) {
                                        document.body.scrollLeft = window._spreadIndex * window.innerWidth;
                                    } else {
                                        document.body.scrollLeft = 0;
                                    }
                                };
                                window._getMaxSpreadIndex = function() {
                                    if (!window.isDualPage) return 0;
                                    var w = window.innerWidth;
                                    var fullW = document.body.scrollWidth;
                                    return Math.max(0, Math.ceil(fullW / w) - 1);
                                };

                                window.pageUp = function() {
                                    if (window._isPageAnimating) return;
                                    if (window.isDualPage) {
                                        if (window._spreadIndex <= 0) {
                                            MoreaderBridge.onPrevChapter();
                                            return;
                                        }
                                        if (window.isEink || window.pageAnimationMode === 'instant') {
                                            window._applySpread(window._spreadIndex - 1);
                                            return;
                                        }
                                        window._isPageAnimating = true;
                                        MoreaderBridge.requestPageSlide(-1, 0);
                                    } else {
                                        if (window.scrollY <= 2) {
                                            MoreaderBridge.onPrevChapter();
                                            return;
                                        }
                                        var step = Math.max(200, window.innerHeight - 40);
                                        var actualScroll = Math.min(step, window.scrollY);
                                        if (window.isEink || window.pageAnimationMode === 'instant') {
                                            window.scrollBy({top: -actualScroll, left: 0, behavior: 'instant'});
                                            return;
                                        }
                                        window._isPageAnimating = true;
                                        MoreaderBridge.requestPageSlide(-1, actualScroll);
                                    }
                                };

                                window.pageDown = function() {
                                    if (window._isPageAnimating) return;
                                    if (window.isDualPage) {
                                        var maxIdx = window._getMaxSpreadIndex();
                                        if (window._spreadIndex >= maxIdx) {
                                            MoreaderBridge.onNextChapter();
                                            return;
                                        }
                                        if (window.isEink || window.pageAnimationMode === 'instant') {
                                            window._applySpread(window._spreadIndex + 1);
                                            return;
                                        }
                                        window._isPageAnimating = true;
                                        MoreaderBridge.requestPageSlide(1, 0);
                                    } else {
                                        var maxScroll = Math.max(0, document.body.scrollHeight - window.innerHeight);
                                        if (window.scrollY >= maxScroll - 4) {
                                            MoreaderBridge.onNextChapter();
                                            return;
                                        }
                                        var step = Math.max(200, window.innerHeight - 40);
                                        var actualScroll = Math.min(step, maxScroll - window.scrollY);
                                        if (window.isEink || window.pageAnimationMode === 'instant') {
                                            window.scrollBy({top: actualScroll, left: 0, behavior: 'instant'});
                                            return;
                                        }
                                        window._isPageAnimating = true;
                                        MoreaderBridge.requestPageSlide(1, actualScroll);
                                    }
                                };

                                // Edge-tap paging: left 25% for page-up, right 25% for page-down, middle 50% for menu/paragraph
                                document.addEventListener('click', function(e) {
                                    var s = window.getSelection();
                                    if (s && !s.isCollapsed) return;
                                    var t = e.target;
                                    if (t && (t.tagName === 'A' || t.closest('a'))) return;

                                    var x = e.clientX;
                                    var w = window.innerWidth;
                                    if (x < w * 0.25) {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        window.pageUp();
                                    } else if (x > w * 0.75) {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        window.pageDown();
                                    }
                                }, true);
                                // Get current visible paragraph index
                                window.getVisiblePara=function(){
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(window.isDualPage){
                                        for(var i=0;i<all.length;i++){
                                            var r = all[i].getBoundingClientRect();
                                            if(r.right > 20 && r.left < window.innerWidth) return i;
                                        }
                                        return all.length - 1;
                                    }
                                    var scrollY=window.scrollY;
                                    for(var i=0;i<all.length;i++){
                                        if(all[i].offsetTop>scrollY+window.innerHeight*0.3)return i;
                                    }
                                    return all.length-1;
                                };
                                // Track visible paragraph on scroll — reports to Kotlin via bridge
                                var _lastReportedPara=-1;
                                var reportScroll = function(){
                                    var idx=window.getVisiblePara();
                                    if(idx!==_lastReportedPara){
                                        _lastReportedPara=idx;
                                        MoreaderBridge.onScrollToParagraph(idx);
                                    }
                                };
                                window.addEventListener('scroll', reportScroll, {passive:true});
                                document.body.addEventListener('scroll', reportScroll, {passive:true});
                            })()""", null)
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onTextSelected(infoJson: String) { 
                            callbackRef.value(infoJson) 
                        }
                        @JavascriptInterface
                        fun onLinkClicked(url: String, visibleParaIdx: Int, scrollY: Int, elementTop: Int) { 
                            linkCallbackRef.value("$url|$visibleParaIdx|$scrollY|$elementTop") 
                        }
                        @JavascriptInterface
                        fun onShowFootnote(text: String, href: String, visibleParaIdx: Int, scrollY: Int) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                footnoteCallbackRef.value(text, href, visibleParaIdx, scrollY)
                            }
                        }
                        @JavascriptInterface
                        fun onParagraphClicked(index: Int) { paragraphCallbackRef.value(index) }
                        @JavascriptInterface
                        fun onScrollToParagraph(index: Int) { scrollCallbackRef.value(index) }
                        @JavascriptInterface
                        fun onPrevChapter() {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { prevChapterCallbackRef.value() }
                        }
                        @JavascriptInterface
                        fun onNextChapter() {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { nextChapterCallbackRef.value() }
                        }
                        @JavascriptInterface
                        fun requestPageSlide(direction: Int, scrollYDelta: Float) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                pageSlideRef.value(direction, scrollYDelta.toInt())
                            }
                        }
                        @JavascriptInterface
                        fun jsLog(msg: String) { android.util.Log.d("TTS-JS", msg) }
                    }, "MoreaderBridge")
                }
            },
            update = { wv ->
                webView = wv
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Native hardware-accelerated smooth slide overlay with edge drop shadow
        slideBitmap?.let { bmp ->
            val density = LocalDensity.current
            val shadowWidthDp = 20.dp
            val shadowWidthPx = with(density) { shadowWidthDp.toPx() }

            if (slideDirection > 0) {
                // Next page: old page slides out to the left with right-side drop shadow on revealed page
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(density) { (bmp.width.toFloat() + shadowWidthPx).toDp() })
                        .offset { IntOffset(slideOffset.value.roundToInt(), 0) }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(with(density) { bmp.width.toDp() })
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(shadowWidthDp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        Color.Black.copy(alpha = 0.08f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            } else {
                // Prev page: old page slides out to the right with left-side drop shadow on revealed page
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(density) { (bmp.width.toFloat() + shadowWidthPx).toDp() })
                        .offset { IntOffset((slideOffset.value - shadowWidthPx).roundToInt(), 0) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(shadowWidthDp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.08f),
                                        Color.Black.copy(alpha = 0.25f)
                                    )
                                )
                            )
                    )
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(with(density) { bmp.width.toDp() })
                    )
                }
            }
        }
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

private class EmptyActionModeCallback(
    private val wrapped: ActionMode.Callback
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean {
        wrapped.onCreateActionMode(mode, menu)
        menu?.clear() // 清空系统的 Copy, Share, Select all 等全部按钮，不弹出系统黑框
        return true   // 保持 ActionMode 处于活跃态，保留划选光标与 selectionchange 正常事件流
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: android.view.Menu?): Boolean {
        wrapped.onPrepareActionMode(mode, menu)
        menu?.clear()
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: android.view.MenuItem?): Boolean {
        return wrapped.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        wrapped.onDestroyActionMode(mode)
    }
}

