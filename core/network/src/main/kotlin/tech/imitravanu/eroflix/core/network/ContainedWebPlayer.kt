package tech.imitravanu.eroflix.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import tech.imitravanu.eroflix.model.*
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference

/** One visible, explicit web source. Device V06 must verify renderer/audio behavior. */
@SuppressLint("SetJavaScriptEnabled")
class ContainedWebPlayer(context: Context,private val policy: WebSourcePolicy,
    pauseNative: ()->Unit,private val onExit: ()->Unit,private val onFailure: (AppError)->Unit,
    private val localAsset: ((String)->WebResourceResponse?)? = null,
    private val onBlockedHost: ((String)->Unit)? = null,
    private val onDownloadStream: ((DetectedStream)->Unit)? = null): LinearLayout(context),AutoCloseable {
    private var web: WebView?=null
    @Volatile private var closed=false
    private val streams=java.util.concurrent.ConcurrentHashMap<String,DetectedStream>()
    private var streamMenu: Button?=null
    private fun observe(url: String,headers: Map<String,String> = emptyMap(),mime: String?=null) {
        val stream=StreamDetection.candidate(url,headers,policy.entryUrl,mime) ?: return
        if(streams.size>=32 && !streams.containsKey(url)) return
        streams[url]=stream
        post { if(!closed) streamMenu?.text="Download detected video (${streams.size})" }
    }
    private val scan=object: Runnable {
        override fun run() {
            if(closed) return
            web?.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('video,video source')).map(v=>({url:v.currentSrc||v.src,type:v.type||''})).concat(performance.getEntriesByType('resource').map(r=>({url:r.name,type:''}))).slice(-128))") { raw ->
                runCatching {
                    val value=org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching
                    val array=org.json.JSONArray(value)
                    for(i in 0 until array.length()) array.getJSONObject(i).let { observe(it.optString("url"),mime=it.optString("type")) }
                }
            }
            postDelayed(this,1500)
        }
    }
    private val cookies=CookieManager.getInstance()
    private var previousCookies=true
    @Volatile var initialRefererObserved=false
        private set
    private val blocked=java.util.Collections.synchronizedSet(linkedSetOf<WebRequestBlock>())
    val blockedRequests: List<WebRequestBlock> get()=synchronized(blocked) { blocked.toList() }
    private fun recordBlocked(request: WebResourceRequest) {
        val host=request.url.host ?: return
        val path=request.url.path.orEmpty()
        val route=when {
            path.startsWith("/xhr/video/") -> "/xhr/video/{selected}"
            path=="/xhr/video" -> "/xhr/video"
            path.startsWith("/api/") -> path.split('/').take(3).joinToString("/").takeIf { it.matches(Regex("[A-Za-z0-9_./-]{1,60}")) } ?: "/api/{other}"
            path.substringAfterLast('.').lowercase() in setOf("mp4","m3u8","mpd","m4s","ts","webm") -> "{media}"
            path.endsWith(".js") -> "{script}"
            else -> "{other}"
        }
        synchronized(blocked) { if(blocked.size<64) blocked+=WebRequestBlock(host,request.url.scheme.orEmpty(),request.method,route) }
        onBlockedHost?.invoke(host)
    }
    init {
        orientation=VERTICAL
        active?.get()?.close();active=WeakReference(this)
        pauseNative()
        addView(TextView(context).apply { text="${policy.contextLabel} · Web player\nDetected streams can be downloaded when the provider exposes an ordinary media URL" },LayoutParams(-1,-2))
        if(onDownloadStream!=null) {
            streamMenu=Button(context).apply { text="Download detected video (0)";setOnClickListener {
                val found=streams.values.toList()
                if(found.isEmpty()) Toast.makeText(context,"Play the video first. Protected or blob-only media may not expose a downloadable stream.",Toast.LENGTH_LONG).show()
                else android.app.AlertDialog.Builder(context).setTitle("Choose detected video").setItems(found.map { it.label }.toTypedArray()) { _,index -> onDownloadStream.invoke(found[index]) }.show()
            } };addView(streamMenu,LayoutParams(-1,-2))
        }
        addView(Button(context).apply { text="Return to app";setOnClickListener { close();onExit() } },LayoutParams(-1,-2))
        previousCookies=cookies.acceptCookie();cookies.setAcceptCookie(false)
        // Service workers can outlive a view. Their network/file/content access stays blocked.
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            blockNetworkLoads=true;allowFileAccess=false;allowContentAccess=false
        }
        web=WebView(context).also { view ->
            view.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;safeBrowsingEnabled=true
                javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture=true;setGeolocationEnabled(false);cacheMode=WebSettings.LOAD_NO_CACHE
            }
            cookies.setAcceptThirdPartyCookies(view,false)
            view.setDownloadListener { url,userAgent,_,mime,_ -> observe(url,mapOf("User-Agent" to userAgent),mime) }
            view.webChromeClient=object: WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String,callback: GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
                override fun onCreateWindow(view: WebView,isDialog: Boolean,isUserGesture: Boolean,resultMsg: android.os.Message)=false
                override fun onShowFileChooser(view: WebView,filePathCallback: ValueCallback<Array<Uri>>,fileChooserParams: FileChooserParams): Boolean { filePathCallback.onReceiveValue(null);return true }
                override fun onJsAlert(view: WebView,url: String,message: String,result: JsResult): Boolean { result.cancel();return true }
                override fun onJsConfirm(view: WebView,url: String,message: String,result: JsResult): Boolean { result.cancel();return true }
                override fun onJsPrompt(view: WebView,url: String,message: String,defaultValue: String,result: JsPromptResult): Boolean { result.cancel();return true }
            }
            view.webViewClient=object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
                    val blocked=if(request.isForMainFrame) !policy.topLevel(request.url.toString()) else !policy.resource(request.url.toString())
                    if(blocked) recordBlocked(request)
                    return blocked
                }
                override fun onPageFinished(view: WebView,url: String) { removeCallbacks(scan);post(scan) }
                override fun shouldInterceptRequest(view: WebView,request: WebResourceRequest): WebResourceResponse? {
                    if(request.method=="GET" && policy.resource(request.url.toString())) observe(request.url.toString(),request.requestHeaders)
                    if(request.isForMainFrame && policy.topLevel(request.url.toString()) && request.requestHeaders.any { (key,value) -> key.equals("Referer",true) && value==policy.initialRequestHeaders["Referer"] }) initialRefererObserved=true
                    val allowed=if(request.isForMainFrame) policy.topLevel(request.url.toString()) else policy.resource(request.url.toString())
                    return if(allowed && request.method in setOf("GET","HEAD")) localAsset?.invoke(request.url.toString()) else {
                        recordBlocked(request)
                        WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))
                    }
                }
                override fun onReceivedSslError(view: WebView,handler: SslErrorHandler,error: SslError) { handler.cancel();onFailure(AppError(ErrorCode.NETWORK,"The provider's secure connection failed.")) }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) {
                    if(request.isForMainFrame) onFailure(AppError(ErrorCode.NETWORK,"The web source could not be loaded.",true))
                }
                override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                    dispose(true);onFailure(AppError(ErrorCode.PROVIDER_CHANGED,"The web player stopped. Return to the app and retry.",true));return true
                }
            }
            addView(view,LayoutParams(-1,0,1f))
            // Validate explicit app-initiated navigation; override callbacks do not cover it.
            check(policy.topLevel(policy.entryUrl));view.resumeTimers();view.loadUrl(policy.entryUrl,policy.initialRequestHeaders)
        }
    }
    fun pause() { close() }
    fun resumeVisible() { if(!closed) { web?.resumeTimers();web?.onResume() } }
    private fun dispose(rendererGone: Boolean) {
        if(closed) return
        closed=true;removeCallbacks(scan);streams.clear()
        val view=web;web=null
        view?.let {
            if(!rendererGone) { it.stopLoading();it.loadUrl("about:blank");it.onPause();it.pauseTimers();it.clearHistory();it.clearCache(true) }
            (it.parent as? ViewGroup)?.removeView(it);it.destroy()
        }
        policy.requestPolicy.allowedHosts.forEach { WebStorage.getInstance().deleteOrigin("https://$it") }
        cookies.setAcceptCookie(previousCookies)
        if(active?.get()===this) active=null
    }
    override fun close()=dispose(false)
    override fun onDetachedFromWindow() { close();super.onDetachedFromWindow() }
    companion object { private var active: WeakReference<ContainedWebPlayer>?=null }
}
data class WebRequestBlock(val host: String,val scheme: String,val method: String,val route: String)
