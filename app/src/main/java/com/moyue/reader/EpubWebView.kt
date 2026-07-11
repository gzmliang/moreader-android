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
    fontFamily: String = "sans-serif",
    fontWeight: String = "normal",
    onTextSelected: (String) -> Unit,
    onLinkClicked: (String) -> Unit = {},
    onParagraphClicked: ((Int) -> Unit)? = null,
    onScrollToParagraph: ((Int) -> Unit)? = null,
    ttsHighlightIndex: Int = -1,
    ttsSentenceIdx: Int = -1,
    scrollToParagraph: Int? = null,
    scrollToAnchor: String? = null,
    onAnchorScrolled: (() -> Unit)? = null,
    highlightsToRender: List<Triple<Int, Int, Int>> = emptyList(),  // (startParagraph, startOffset, endOffset)
    highlightToRemove: Pair<Int, Int>? = null,  // (startOffset, endOffset)
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val callbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val linkCallbackRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val paragraphCallbackRef = remember { mutableStateOf<(Int) -> Unit>({}) }
    val scrollCallbackRef = remember { mutableStateOf<(Int) -> Unit>({}) }
    LaunchedEffect(onTextSelected) { callbackRef.value = onTextSelected }
    LaunchedEffect(onLinkClicked) { linkCallbackRef.value = onLinkClicked }
    LaunchedEffect(onParagraphClicked) { paragraphCallbackRef.value = { idx -> onParagraphClicked?.invoke(idx) ?: Unit } }
    LaunchedEffect(onScrollToParagraph) { scrollCallbackRef.value = { idx -> onScrollToParagraph?.invoke(idx) ?: Unit } }

    // TTS paragraph + sentence highlight: merged into one effect
    // (was two separate effects — async evaluateJavascript order was undefined,
    //  causing initAndHighlight to run before ttsHL added .tts-hl class)
    var prevHighlightIdx by remember { mutableStateOf(ttsHighlightIndex) }
    LaunchedEffect(ttsHighlightIndex, ttsSentenceIdx) {
        val paraChanged = ttsHighlightIndex != prevHighlightIdx
        prevHighlightIdx = ttsHighlightIndex
        webView?.let { wv ->
            when {
                ttsHighlightIndex < 0 -> {
                    android.util.Log.d("EpubWV", "[TIME] ⏱ CLEAR ALL @${System.currentTimeMillis()}")
                    wv.evaluateJavascript("window.ttsClear();window.ttsSentenceClear()", null)
                }
                ttsSentenceIdx == 0 || paraChanged -> {
                    android.util.Log.d("EpubWV", "[TIME] ⏱ initHL($ttsHighlightIndex,0) paraChanged=$paraChanged @${System.currentTimeMillis()}")
                    wv.evaluateJavascript("window.initAndHighlight($ttsHighlightIndex,0)", null)
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

    // Scroll to paragraph for bookmark navigation
    LaunchedEffect(scrollToParagraph) {
        if (scrollToParagraph != null && scrollToParagraph >= 0) {
            kotlinx.coroutines.delay(500)
            webView?.evaluateJavascript("window.scrollToPara($scrollToParagraph)", null)
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
                    if (!el) el = document.querySelector('[name="$scrollToAnchor"]');
                    if (!el) el = document.querySelector('a[name="$scrollToAnchor"]');
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
    var lastLoadedContent by remember { mutableStateOf<String?>(null) }
    
    // Load HTML only when content or base URL actually changes
    LaunchedEffect(htmlContent, baseUrl) {
        if (htmlContent != null) {
            webView?.let { wv ->
                // Construct HTML with initial CSS. 
                // Note: This uses the *current* values of initCss variables at the moment of loading.
                val currentInitCss = """<style id="mt">
                    *{background-color:${bgColor}!important;color:${textColor}!important}
                    body{background-color:${bgColor}!important;color:${textColor}!important;font-family:${fontFamily}!important;line-height:1.8!important;margin:16px!important;font-size:${(fontScale*100).toInt()}%!important;font-weight:${fontWeight}!important}
                    p,div,span,li,a,h1,h2,h3,h4,h5,h6{font-size:${(fontScale*100).toInt()}%!important;line-height:1.8!important;font-family:${fontFamily}!important;font-weight:${fontWeight}!important}
                    p{margin-bottom:0.8em!important}
                    h1,h2,h3,h4{font-weight:bold!important}
                    img{max-width:100%!important;height:auto!important}
                    a{color:#06C!important}
                    .tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}
                    .tts-sentence-hl{background-color:rgba(34,197,94,0.25)!important}
                    .user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}
                    ::-webkit-scrollbar{width:0!important;height:0!important}
                    </style>"""
                
                val html = buildString {
                    append("<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0\">")
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
    LaunchedEffect(fontScale, bgColor, textColor, fontFamily, fontWeight) {
        webView?.let { wv ->
            if (lastLoadedContent == null) return@let // Not loaded yet
            
            val sz = (fontScale * 100).toInt()
            
            // If font size changed, save scroll ratio before applying new size
            val fontChanged = lastFontScale != fontScale
            lastFontScale = fontScale
            
            val js = """(function(){
                var e=document.getElementById('mt');
                if(!e)return;
                e.textContent='*{background-color:${bgColor}!important;color:${textColor}!important}'+
                'body{background-color:${bgColor}!important;color:${textColor}!important;font-family:${fontFamily}!important;line-height:1.8!important;margin:16px!important;font-size:${sz}%!important;font-weight:${fontWeight}!important}'+
                'p,div,span,li,a,h1,h2,h3,h4,h5,h6{font-size:${sz}%!important;line-height:1.8!important;font-family:${fontFamily}!important;font-weight:${fontWeight}!important}'+
                'p{margin-bottom:0.8em!important}'+
                'h1,h2,h3,h4{font-weight:bold!important}'+
                'img{max-width:100%!important;height:auto!important}'+
                'a{color:#06C!important}'+
                '.tts-hl{background-color:rgba(59,130,246,0.2)!important;border-left:3px solid #3b82f6!important}'+
                '.tts-sentence-hl{background-color:rgba(34,197,94,0.25)!important}'+
                '.user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}'+
                '::-webkit-scrollbar{width:0!important;height:0!important}';
            })()"""
            
            if (fontChanged) {
                // Step 1: Save scroll ratio
                wv.evaluateJavascript(
                    "(function(){var sy=window.scrollY;var sh=document.body.scrollHeight;var vh=window.innerHeight;window._savedScrollRatio=sy/Math.max(1,sh-vh);})()"
                ) { _ ->
                    // Step 2: Apply font change
                    wv.evaluateJavascript(js) { _ ->
                        // Step 3: Restore scroll ratio after reflow
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            wv.evaluateJavascript(
                                "(function(){if(window._savedScrollRatio!==undefined){var sh=document.body.scrollHeight;var vh=window.innerHeight;window.scrollTo(0,window._savedScrollRatio*Math.max(1,sh-vh));}})()",
                                null
                            )
                        }, 200)
                    }
                }
            } else {
                wv.evaluateJavascript(js, null)
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
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
                                window.ttsHL=function(idx){
                                    document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(idx>=0&&idx<all.length){all[idx].classList.add('tts-hl');all[idx].scrollIntoView({behavior:'smooth',block:'center'});}
                                };
                                window.ttsClear=function(){document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});};
                                // Sentence-level highlight
                                window.ttsSentences=[];
                                window._ttsSentencePara=null;
                                window.initAndHighlight=function(paraIdx,sentenceIdx){
                                    console.log('[initHL] para='+paraIdx+' sent='+sentenceIdx);
                                    MoreaderBridge.jsLog('[JS] initAndHighlight('+paraIdx+','+sentenceIdx+')');
                                    document.querySelectorAll('.tts-hl').forEach(function(e){e.classList.remove('tts-hl')});
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(paraIdx<0||paraIdx>=all.length){MoreaderBridge.jsLog('[JS] initHL bad paraIdx');return;}
                                    var el=all[paraIdx];
                                    el.classList.add('tts-hl');
                                    el.scrollIntoView({behavior:'smooth',block:'center'});
                                    window.ttsSentences=[];
                                    window._ttsSentencePara=el;
                                    var text=el.textContent;
                                    // 与 Kotlin SENTENCE_REGEX 保持一致：在 (.!?。！？；;) 之后拆分，包括末尾无标点的残留文本
                                    var matches=[];
                                    var splitPos=0;
                                    var re=/[.!?。！？；;]/g;
                                    var m;
                                    while((m=re.exec(text))!==null){
                                        var end=m.index+1;
                                        matches.push(text.substring(splitPos,end));
                                        splitPos=end;
                                        // 跳过标点后的空格
                                        while(splitPos<text.length&&text.charAt(splitPos)===' ')splitPos++;
                                        // 跳过闭合引号（避免 ." 或 !' 把引号当成独立句子）
                                        while(splitPos<text.length&&/["'\u00BB\u00AB\u201C\u201D\u2018\u2019\u300C\u300D\u300E\u300F]/.test(text.charAt(splitPos)))splitPos++;
                                    }
                                    // 兜住末尾没有标点的残留文本
                                    if(splitPos<text.length)matches.push(text.substring(splitPos));
                                    if(matches.length===0)matches=[text];
                                    MoreaderBridge.jsLog('[JS] sentSplit: newMatches='+matches.length+' textLen='+text.length);
                                    var pos=0;
                                    for(var i=0;i<matches.length;i++){
                                        var s=matches[i];
                                        window.ttsSentences.push({start:pos,end:pos+s.length});
                                        pos+=s.length;
                                        if(pos<text.length&&text.charAt(pos)===' ')pos++;
                                    }
                                    if(window.ttsSentences.length===0){
                                        window.ttsSentences.push({start:0,end:text.length});
                                    }
                                    if(sentenceIdx>=0&&sentenceIdx<window.ttsSentences.length){
                                        // 清除上一段残留的绿色高亮，再设新句子的
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
                                    var textNodes=[];
                                    var walker=document.createTreeWalker(hl,NodeFilter.SHOW_TEXT);
                                    var charCount=0;
                                    while(walker.nextNode()){
                                        textNodes.push({node:walker.currentNode,start:charCount,end:charCount+walker.currentNode.textContent.length});
                                        charCount+=walker.currentNode.textContent.length;
                                    }
                                    MoreaderBridge.jsLog('[JS] textNodes='+textNodes.length+' totalChars='+charCount);
                                    var sTN=null,sOff=0,eTN=null,eOff=0;
                                    for(var i=0;i<textNodes.length;i++){
                                        var tn=textNodes[i];
                                        if(!sTN&&tn.start<=sent.start&&tn.end>sent.start){sTN=tn.node;sOff=sent.start-tn.start;}
                                        if(tn.start<sent.end&&tn.end>=sent.end){eTN=tn.node;eOff=sent.end-tn.start;}
                                    }
                                    if(!sTN||!eTN){MoreaderBridge.jsLog('[JS] _ttsHL no nodes: sTN='+(sTN?'ok':'null')+' eTN='+(eTN?'ok':'null'));return;}
                                    MoreaderBridge.jsLog('[JS] _ttsHL sTN ok sOff='+sOff+' eTN ok eOff='+eOff);
                                    var range=document.createRange();
                                    range.setStart(sTN,sOff);
                                    range.setEnd(eTN,eOff);
                                    var span=document.createElement('span');
                                    span.className='tts-sentence-hl';
                                    span.setAttribute('data-tts-sentence','1');
                                    try{
                                        range.surroundContents(span);
                                        MoreaderBridge.jsLog('[JS] surroundContents OK');
                                    }catch(e){
                                        MoreaderBridge.jsLog('[JS] surroundContents EXCEPTION: '+e.message);
                                        range.insertNode(span);
                                    }
                                    // 滚动到屏幕中央
                                    span.scrollIntoView({behavior:'smooth',block:'center'});
                                };
                                window.ttsSentenceClear=function(){
                                    document.querySelectorAll('span[data-tts-sentence=\"1\"]').forEach(function(s){
                                        var p=s.parentNode;
                                        while(s.firstChild)p.insertBefore(s.firstChild,s);
                                        p.removeChild(s);
                                        p.normalize();
                                    });
                                };
                                window.scrollToPara=function(idx){
                                    if(idx===0){window.scrollTo(0,0);return;}
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    if(idx>=0&&idx<all.length){
                                        all[idx].scrollIntoView({behavior:'smooth',block:'center'});
                                        all[idx].style.backgroundColor='rgba(59,130,246,0.15)';
                                        setTimeout(function(){all[idx].style.backgroundColor='';},2000);
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
                                
                                // Intercept link clicks to handle internal navigation
                                document.addEventListener('click',function(e){
                                    var a=e.target.closest('a[href]');
                                    if(a){
                                        var href=a.getAttribute('href');
                                        if(href && !href.startsWith('http') && !href.startsWith('mailto:') && !href.startsWith('tel:')){
                                            e.preventDefault();
                                            // Get current visible paragraph index before navigating
                                            var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                            var scrollY=window.scrollY;
                                            var visibleIdx=0;
                                            for(var i=0;i<all.length;i++){
                                                if(all[i].offsetTop>scrollY+window.innerHeight*0.3)break;
                                                visibleIdx=i;
                                            }
                                            MoreaderBridge.onLinkClicked(href,visibleIdx);
                                        }
                                    }
                                });
                                
                                // For page-tap scrolling via scrollBy
                                window.pageUp=function(){window.scrollBy(0,-window.innerHeight*0.85);};
                                window.pageDown=function(){window.scrollBy(0,window.innerHeight*0.85);};
                                // Get current visible paragraph index
                                window.getVisiblePara=function(){
                                    var all=document.querySelectorAll('p,h1,h2,h3,h4,h5,h6');
                                    var scrollY=window.scrollY;
                                    for(var i=0;i<all.length;i++){
                                        if(all[i].offsetTop>scrollY+window.innerHeight*0.3)return i;
                                    }
                                    return all.length-1;
                                };
                                // Track visible paragraph on scroll — reports to Kotlin via bridge
                                var _lastReportedPara=-1;
                                window.addEventListener('scroll',function(){
                                    var idx=window.getVisiblePara();
                                    if(idx!==_lastReportedPara){
                                        _lastReportedPara=idx;
                                        MoreaderBridge.onScrollToParagraph(idx);
                                    }
                                },{passive:true});
                            })()""", null)
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onTextSelected(infoJson: String) { 
                            callbackRef.value(infoJson) 
                        }
                        @JavascriptInterface
                        fun onLinkClicked(url: String, visibleParaIdx: Int) { linkCallbackRef.value("$url|$visibleParaIdx") }
                        @JavascriptInterface
                        fun onParagraphClicked(index: Int) { paragraphCallbackRef.value(index) }
                        @JavascriptInterface
                        fun onScrollToParagraph(index: Int) { scrollCallbackRef.value(index) }
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
