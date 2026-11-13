package ai.moeru.airi_pocket.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import ai.moeru.airi_pocket.R

/**
 * Foreground service that owns the floating bubble, the screen capture and the full-screen AIRI panel.
 *
 * The panel is a transparent WebView that serves the app's own bundled web assets from the same
 * `https://localhost` origin as the main Capacitor WebView, so settings, providers and models in
 * localStorage and IndexedDB are shared. It loads `/overlay` and stays loaded while the service runs.
 */
class AiriOverlayService : Service() {
    companion object {
        const val ACTION_START = "ai.moeru.airi_pocket.overlay.START"
        const val ACTION_STOP = "ai.moeru.airi_pocket.overlay.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "airi_overlay"
        private const val NOTIFICATION_ID = 4101
        private const val CAPTURE_PATH = "__overlay/capture.jpg"

        @Volatile
        var running = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var engine: ScreenCaptureEngine? = null
    private var bubble: FloatingBubble? = null
    private var panelRoot: PanelRoot? = null
    private var webView: WebView? = null
    private var panelParams: WindowManager.LayoutParams? = null

    @Volatile private var capturedJpeg: ByteArray? = null
    private var captureToken = 0
    private var jsReady = false
    private var pendingOpenToken: Int? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (intent?.action != ACTION_START || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running)
            return START_NOT_STICKY

        // NOTICE: Android 14+ needs the projection consent granted before startForeground(mediaProjection).
        startAsForeground()

        windowManager = getSystemService(WindowManager::class.java)
        val captureEngine = ScreenCaptureEngine(this) { mainHandler.post { stopSelf() } }
        try {
            captureEngine.start(resultCode, resultData)
        }
        catch (error: Exception) {
            Toast.makeText(this, "AIRI overlay could not start screen capture", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        engine = captureEngine

        createPanel()
        bubble = FloatingBubble(this, windowManager, ::onBubbleTap).also { it.show() }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        bubble?.remove()
        panelRoot?.let { runCatching { windowManager.removeView(it) } }
        webView?.apply {
            removeJavascriptInterface("AiriOverlayHost")
            destroy()
        }
        engine?.dispose()
        bubble = null
        panelRoot = null
        webView = null
        engine = null
        super.onDestroy()
    }

    // region bubble and capture

    private fun onBubbleTap() {
        if (busy || panelRoot?.visibility == View.VISIBLE)
            return
        val engine = engine ?: return
        busy = true
        bubble?.hide()
        val hiddenAt = SystemClock.uptimeMillis()
        // The extra delay lets the compositor draw one frame without the bubble before the snapshot.
        mainHandler.postDelayed({
            engine.snapshot(hiddenAt) { jpeg ->
                mainHandler.post {
                    busy = false
                    if (jpeg == null) {
                        bubble?.show()
                        Toast.makeText(this, "Could not capture the screen", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        openPanel(jpeg)
                    }
                }
            }
        }, 90)
    }

    // endregion

    // region panel

    private class PanelRoot(context: Context, val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP)
                    onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun createPanel() {
        val loader = WebViewAssetLoader.Builder()
            .setDomain("localhost")
            .addPathHandler("/") { path -> handleAsset(path) }
            .build()

        val view = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "localhost"
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && micGranted
                    }
                    if (allowed.isEmpty())
                        request.deny()
                    else
                        request.grant(allowed.toTypedArray())
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    android.util.Log.d("AiriOverlay", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(Host(), "AiriOverlayHost")
        }

        val root = PanelRoot(this) { evaluate("window.__airiOverlay && window.__airiOverlay.requestClose()") }.apply {
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            visibility = View.INVISIBLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            closedFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        windowManager.addView(root, params)
        webView = view
        panelRoot = root
        panelParams = params
        view.loadUrl("https://localhost/overlay")
    }

    private fun closedFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    private fun openFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun openPanel(jpeg: ByteArray) {
        val root = panelRoot ?: return
        val params = panelParams ?: return
        capturedJpeg = jpeg
        captureToken += 1
        params.flags = openFlags()
        windowManager.updateViewLayout(root, params)
        root.visibility = View.VISIBLE
        root.requestFocus()
        if (jsReady)
            evaluate("window.__airiOverlay && window.__airiOverlay.open($captureToken)")
        else
            pendingOpenToken = captureToken
    }

    private fun closePanel() {
        val root = panelRoot ?: return
        val params = panelParams ?: return
        capturedJpeg = null
        root.visibility = View.INVISIBLE
        params.flags = closedFlags()
        windowManager.updateViewLayout(root, params)
        bubble?.show()
    }

    private fun evaluate(script: String) {
        webView?.post { webView?.evaluateJavascript(script, null) }
    }

    /** Called from the web page. Methods run on a WebView thread, so they hop to the main thread. */
    private inner class Host {
        @JavascriptInterface
        fun ready() {
            mainHandler.post {
                jsReady = true
                pendingOpenToken?.let { token ->
                    pendingOpenToken = null
                    evaluate("window.__airiOverlay && window.__airiOverlay.open($token)")
                }
            }
        }

        @JavascriptInterface
        fun close() {
            mainHandler.post { closePanel() }
        }
    }

    private fun handleAsset(path: String): WebResourceResponse? {
        if (path == CAPTURE_PATH) {
            val bytes = capturedJpeg ?: return null
            return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes)).apply {
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        }

        val clean = path.substringBefore('?').trimStart('/')
        val hasExtension = clean.substringAfterLast('/').contains('.')
        // The web app is a single-page app, so a path without a file extension is a route.
        val assetPath = if (clean.isEmpty() || !hasExtension) "public/index.html" else "public/$clean"
        return try {
            WebResourceResponse(mimeFor(assetPath), null, assets.open(assetPath))
        }
        catch (_: FileNotFoundException) {
            null
        }
    }

    private fun mimeFor(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "wasm" -> "application/wasm"
        "html" -> "text/html"
        "onnx", "moc3", "model3" -> "application/octet-stream"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // endregion

    // region notification

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AIRI overlay", NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AiriOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentTitle("AIRI is on your screen")
            .setContentText("Tap the bubble to ask or circle something")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(NOTIFICATION_ID, notification)
    }

    // endregion

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(name, Intent::class.java)
        else
            getParcelableExtra(name)
}
