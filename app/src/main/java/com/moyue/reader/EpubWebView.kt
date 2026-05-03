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
    onParagraphClicked: ((Int) -> Unit)? = null,
    ttsHighlightIndex: Int = -1,
    scrollToParagraph: Int? = null,
    highlightsToRender: List<Triple<Int, Int, Int>> = emptyList(),  // (startParagraph, startOffset, endOffset)
    highlightToRemove: Pair<Int, Int>? = null,  // (startOffset, endOffset)
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null,
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

    // Scroll to paragraph for bookmark navigation
    LaunchedEffect(scrollToParagraph) {
        if (scrollToParagraph != null && scrollToParagraph >= 0) {
            kotlinx.coroutines.delay(500)
            webView?.evaluateJavascript("window.scrollToPara($scrollToParagraph)", null)
        }
    }

    // Render user highlights in WebView
    LaunchedEffect(highlightsToRender) {
        if (highlightsToRender.isNotEmpty()) {
            kotlinx.coroutines.delay(600)
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
                '.user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}'+
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
        .user-highlight{background-color:rgba(255,255,0,0.35)!important;border-radius:2px!important}
        ::-webkit-scrollbar{width:0!important;height:0!important}
        </style>"""

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
                                window.scrollToPara=function(idx){
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
                        fun onTextSelected(infoJson: String) { 
                            callbackRef.value(infoJson) 
                        }
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
