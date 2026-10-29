package com.futureyyy.tijing

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.AssetManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class PendingSettingsPage {
        OVERLAY,
        ACCESSIBILITY,
    }

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bankExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TiJingBank").apply { isDaemon = true }
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFloatingConfigJson: String? = null
    private var pendingFloatingConfig: QuizRuntimeConfig? = null
    private var pendingSettingsPage: PendingSettingsPage? = null
    private var projectionRequestInFlight = false
    private var floatingServiceExpected = false
    private val captureFrameListener: (String) -> Unit = { dataUrl -> onScreenshot(dataUrl) }
    private val captureSessionStateListener: (Boolean) -> Unit = { running ->
        runOnUiThread {
            floatingServiceExpected = running
            if (running) mainHandler.removeCallbacks(sessionStartVerification)
            notifyFloatingSessionState(running)
        }
    }
    private val sessionStartVerification = Runnable {
        if (floatingServiceExpected && ScreenCaptureService.instance?.isSessionRunning() != true) {
            floatingServiceExpected = false
            notifyFloatingSessionError("悬浮识题服务启动超时，请重试")
            notifyFloatingSessionState(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            webViewClient = LocalAssetWebViewClient(this@MainActivity.assets)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("QuizWebView", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = filePathCallback
                    return try {
                        val pickerIntent = fileChooserParams.createIntent().apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            val accept = fileChooserParams.acceptTypes
                                ?.filter { value -> value.isNotBlank() }
                                ?.joinToString(",")
                            type = accept?.takeIf { value -> value.isNotBlank() } ?: "image/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                        }
                        startActivityForResult(pickerIntent, REQUEST_FILE_CHOOSER)
                        true
                    } catch (_: ActivityNotFoundException) {
                        this@MainActivity.fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }
            addJavascriptInterface(NativeBridge(this@MainActivity), "AndroidBridge")
        }
        setContentView(webView)
        bindServiceListeners()
        webView.loadUrl(LOCAL_APP_URL)
    }

    override fun onResume() {
        super.onResume()
        bindServiceListeners()
        val returnedFrom = pendingSettingsPage
        if (returnedFrom != null) {
            pendingSettingsPage = null
            when (returnedFrom) {
                PendingSettingsPage.OVERLAY -> if (!hasOverlayPermission()) {
                    cancelPendingFloatingSession("未授予悬浮窗权限，悬浮识题未启用")
                    return
                }
                PendingSettingsPage.ACCESSIBILITY -> if (!hasGesturePermission()) {
                    awaitGesturePermission(GESTURE_PERMISSION_RETRIES)
                    return
                }
            }
        }
        continuePendingFloatingSession()
        if (pendingFloatingConfig == null && !projectionRequestInFlight) {
            val captureService = ScreenCaptureService.instance
            if (captureService != null) {
                val running = captureService.isSessionRunning()
                floatingServiceExpected = running
                notifyFloatingSessionState(running)
            } else if (!floatingServiceExpected) {
                notifyFloatingSessionState(false)
            }
        }
    }

    private fun bindServiceListeners() {
        ScreenCaptureService.listener = captureFrameListener
        ScreenCaptureService.sessionStateListener = captureSessionStateListener
    }

    fun requestProjection() {
        if (ScreenCaptureService.instance?.requestNextFrame() == true) return
        if (projectionRequestInFlight) return
        projectionRequestInFlight = true
        try {
            startActivityForResult(createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (_: ActivityNotFoundException) {
            projectionRequestInFlight = false
            onScreenshot("")
        }
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openTargetApp(targetApp: QuizTargetApp): Boolean {
        if (targetApp.isAutomatic) return true
        val launchIntent = packageManager.getLaunchIntentForPackage(targetApp.packageName) ?: return false
        runOnUiThread {
            try {
                startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // The JavaScript caller receives false only when the package is not visible.
            }
        }
        return true
    }

    private fun beginFloatingSession(configJson: String, config: QuizRuntimeConfig) {
        pendingFloatingConfigJson = configJson
        pendingFloatingConfig = config
        pendingSettingsPage = null
        continuePendingFloatingSession()
    }

    private fun continuePendingFloatingSession() {
        val config = pendingFloatingConfig ?: return
        if (pendingSettingsPage != null || projectionRequestInFlight) return
        if (!hasOverlayPermission()) {
            openPendingSettingsPage(
                PendingSettingsPage.OVERLAY,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        if (config.mode != QuizMode.MANUAL && !hasGesturePermission()) {
            openPendingSettingsPage(
                PendingSettingsPage.ACCESSIBILITY,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
            return
        }
        val configJson = pendingFloatingConfigJson
        if (configJson != null && ScreenCaptureService.instance?.isCaptureReady() == true) {
            // The capture service is still alive, so its MediaProjection grant is reusable:
            // skip the system consent dialog instead of asking the user every time.
            try {
                ScreenCaptureService.resumeSession(this, configJson)
                floatingServiceExpected = true
                mainHandler.removeCallbacks(sessionStartVerification)
                mainHandler.postDelayed(sessionStartVerification, SESSION_START_TIMEOUT_MS)
                clearPendingFloatingSession()
                moveTaskToBack(true)
            } catch (_: Exception) {
                cancelPendingFloatingSession("悬浮识题服务启动失败")
            }
            return
        }
        projectionRequestInFlight = true
        try {
            startActivityForResult(createScreenCaptureIntent(), REQUEST_FLOATING_CAPTURE)
        } catch (_: ActivityNotFoundException) {
            projectionRequestInFlight = false
            cancelPendingFloatingSession("系统未提供屏幕捕获授权页面")
        }
    }

    private fun openPendingSettingsPage(page: PendingSettingsPage, intent: Intent) {
        pendingSettingsPage = page
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            pendingSettingsPage = null
            cancelPendingFloatingSession("系统未提供所需权限设置页面")
        }
    }

    private fun createScreenCaptureIntent(): Intent {
        val manager = getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    /**
     * Some ROMs (vivo/OriginOS and friends) bind an accessibility service a moment after the
     * user toggles it, so a single check on resume can false-negative.
     */
    private fun awaitGesturePermission(remaining: Int) {
        if (destroyedActivity()) return
        if (hasGesturePermission()) {
            continuePendingFloatingSession()
            return
        }
        if (remaining <= 0) {
            cancelPendingFloatingSession(gesturePermissionMessage())
            return
        }
        mainHandler.postDelayed({ awaitGesturePermission(remaining - 1) }, GESTURE_PERMISSION_RETRY_MS)
    }

    private fun destroyedActivity(): Boolean = isFinishing || isDestroyed

    private fun gesturePermissionMessage(): String {
        val manager = try {
            getSystemService(AccessibilityManager::class.java)
        } catch (_: Exception) {
            null
        }
        val masterOn = try {
            manager?.isEnabled != false
        } catch (_: Exception) {
            true
        }
        return if (!masterOn) {
            "系统无障碍服务总开关未开启，请先到系统设置里打开无障碍"
        } else {
            "练题流“自动点击”未在无障碍服务列表中启用，请在系统设置中手动打开"
        }
    }

    private fun hasGesturePermission(): Boolean {
        if (QuizAccessibilityService.instance?.canPerformGestures() == true) return true
        val expected = ComponentName(this, QuizAccessibilityService::class.java)
        val manager = getSystemService(AccessibilityManager::class.java)
        return try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun floatingCapabilitiesJson(): String {
        val captureService = ScreenCaptureService.instance
        return JSONObject()
            .put("overlay", hasOverlayPermission())
            .put("capture", captureService?.isCaptureReady() == true)
            .put("gesture", hasGesturePermission())
            .put("sessionRunning", captureService?.isSessionRunning() == true)
            .toString()
    }

    private fun stopFloatingSessionInternal() {
        clearPendingFloatingSession()
        floatingServiceExpected = false
        mainHandler.removeCallbacks(sessionStartVerification)
        ScreenCaptureService.stop(this)
        notifyFloatingSessionState(false)
    }

    private fun clearPendingFloatingSession() {
        pendingFloatingConfigJson = null
        pendingFloatingConfig = null
        pendingSettingsPage = null
    }

    private fun cancelPendingFloatingSession(message: String) {
        clearPendingFloatingSession()
        notifyFloatingSessionError(message)
    }

    private fun notifyFloatingSessionError(message: String) {
        val displayMessage = message.ifBlank { "悬浮识题启动失败" }
        val quoted = JSONObject.quote(displayMessage)
        runOnUiThread {
            Toast.makeText(this, displayMessage, Toast.LENGTH_LONG).show()
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.onNativeFloatingSessionError?.($quoted)", null)
            }
        }
    }

    private fun notifyFloatingSessionState(running: Boolean) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.onNativeFloatingSessionState?.($running)", null)
            }
        }
    }

    private fun onScreenshot(dataUrl: String) {
        val quoted = JSONObject.quote(dataUrl)
        runOnUiThread { webView.evaluateJavascript("window.onNativeScreenshot?.($quoted)", null) }
    }

    fun deliverModelResponse(requestId: String, content: String, error: String?) {
        val id = JSONObject.quote(requestId)
        val body = JSONObject.quote(content)
        val failure = if (error.isNullOrBlank()) "null" else JSONObject.quote(error)
        runOnUiThread {
            webView.evaluateJavascript("window.onNativeModelResponse?.($id, $body, $failure)", null)
        }
    }

    fun handleQuestionBankList(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val requestId = json.optString("requestId")
        val query = json.optString("query")
        val offset = json.optInt("offset", 0)
        val limit = json.optInt("limit", 50).coerceIn(1, 500)
        bankExecutor.execute {
            val body = runCatching {
                QuestionBankStore.get(this).pageJson(query, offset, limit)
            }.getOrElse { """{"total":0,"offset":0,"records":[]}""" }
            deliverBankResult(requestId, body)
        }
    }

    fun handleQuestionBankSave(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val requestId = json.optString("requestId")
        val record = json.optJSONObject("record")
        bankExecutor.execute {
            val result = if (record == null) {
                QuestionBankStore.SaveResult(false, null, "缺少题目数据")
            } else {
                runCatching { QuestionBankStore.get(this).saveRecordJson(record.toString()) }
                    .getOrElse { QuestionBankStore.SaveResult(false, null, it.message ?: "保存失败") }
            }
            deliverBankResult(
                requestId,
                JSONObject()
                    .put("ok", result.ok)
                    .put("id", result.id ?: JSONObject.NULL)
                    .put("error", result.error ?: JSONObject.NULL)
                    .toString(),
            )
        }
    }

    fun handleQuestionBankDelete(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val requestId = json.optString("requestId")
        val id = json.optString("id")
        bankExecutor.execute {
            val ok = runCatching { QuestionBankStore.get(this).delete(id) }.getOrDefault(false)
            deliverBankResult(requestId, JSONObject().put("ok", ok).toString())
        }
    }

    fun handleQuestionBankExport() {
        bankExecutor.execute {
            val path = runCatching {
                val file = File(getExternalFilesDir(null) ?: filesDir, EXPORT_FILE_NAME)
                file.writeText(QuestionBankStore.get(this).exportJson(pretty = true), Charsets.UTF_8)
                file.absolutePath
            }.getOrNull()
            runOnUiThread {
                val message = path?.let { "题库已导出到 $it" } ?: "题库导出失败"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (::webView.isInitialized) {
                    val payload = JSONObject().put("ok", path != null).put("path", path ?: "").toString()
                    webView.evaluateJavascript(
                        "window.onNativeQuestionBankExport?.(${JSONObject.quote(payload)})",
                        null,
                    )
                }
            }
        }
    }

    fun handleQuestionBankImport(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val requestId = json.optString("requestId")
        val content = json.optString("content")
        bankExecutor.execute {
            val result = runCatching { QuestionBankStore.get(this).importJson(content) }
                .getOrElse { QuestionBankStore.ImportResult(false, 0, it.message ?: "导入失败") }
            deliverBankResult(
                requestId,
                JSONObject()
                    .put("ok", result.ok)
                    .put("count", result.count)
                    .put("error", result.error ?: JSONObject.NULL)
                    .toString(),
            )
        }
    }

    private fun deliverBankResult(requestId: String, body: String) {
        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread
            webView.evaluateJavascript(
                "window.onNativeQuestionBankResult?.(${JSONObject.quote(requestId)}, ${JSONObject.quote(body)})",
                null,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val callback = fileChooserCallback
            fileChooserCallback = null
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        if (requestCode == REQUEST_CAPTURE) {
            projectionRequestInFlight = false
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data, requestFrame = true)
            } else {
                runOnUiThread { webView.evaluateJavascript("window.onNativeScreenshot?.('')", null) }
            }
            continuePendingFloatingSession()
            return
        }
        if (requestCode == REQUEST_FLOATING_CAPTURE) {
            projectionRequestInFlight = false
            val configJson = pendingFloatingConfigJson ?: return
            if (resultCode != RESULT_OK || data == null) {
                cancelPendingFloatingSession("已取消屏幕捕获授权，悬浮识题未启用")
                return
            }
            try {
                ScreenCaptureService.startSession(this, resultCode, data, configJson)
                floatingServiceExpected = true
                mainHandler.removeCallbacks(sessionStartVerification)
                mainHandler.postDelayed(sessionStartVerification, SESSION_START_TIMEOUT_MS)
                clearPendingFloatingSession()
                moveTaskToBack(true)
            } catch (_: Exception) {
                cancelPendingFloatingSession("悬浮识题服务启动失败")
            }
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        bankExecutor.shutdownNow()
        if (ScreenCaptureService.listener === captureFrameListener) {
            ScreenCaptureService.listener = null
        }
        if (ScreenCaptureService.sessionStateListener === captureSessionStateListener) {
            ScreenCaptureService.sessionStateListener = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (!floatingServiceExpected && ScreenCaptureService.instance?.isSessionRunning() != true) {
            ScreenCaptureService.stop(this)
        }
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }

    class NativeBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun requestScreenshot() {
            activity.runOnUiThread { activity.requestProjection() }
        }

        @JavascriptInterface
        fun tapNormalized(x: Float, y: Float, targetAppId: String): Boolean {
            val targetApp = try {
                QuizTargetApp.fromId(targetAppId.trim().lowercase())
            } catch (_: Exception) {
                return false
            }
            val service = QuizAccessibilityService.instance ?: return false
            val targetPackage = targetApp.packageName.ifBlank { service.foregroundApplicationPackage().orEmpty() }
            return targetPackage.takeIf { it.isNotBlank() }?.let { service.tapNormalized(x, y, it) } ?: false
        }

        @JavascriptInterface
        fun tapText(text: String, targetAppId: String): Boolean {
            val targetApp = try {
                QuizTargetApp.fromId(targetAppId.trim().lowercase())
            } catch (_: Exception) {
                return false
            }
            val service = QuizAccessibilityService.instance ?: return false
            val targetPackage = targetApp.packageName.ifBlank { service.foregroundApplicationPackage().orEmpty() }
            return targetPackage.takeIf { it.isNotBlank() }?.let { service.tapText(text, it) } ?: false
        }

        @JavascriptInterface
        fun cancelGestures() {
            QuizAccessibilityService.instance?.cancelGestures()
        }

        @JavascriptInterface
        fun stopCapture() {
            ScreenCaptureService.stop(activity)
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            activity.runOnUiThread { activity.openAccessibilitySettings() }
        }

        @JavascriptInterface
        fun openTargetApp(targetAppId: String): Boolean {
            val targetApp = try {
                QuizTargetApp.fromId(targetAppId.trim().lowercase())
            } catch (_: Exception) {
                return false
            }
            return activity.openTargetApp(targetApp)
        }

        @JavascriptInterface
        fun startFloatingSession(configJson: String): Boolean {
            val config = try {
                QuizRuntimeConfig.fromJson(configJson)
            } catch (error: Exception) {
                activity.notifyFloatingSessionError(error.message ?: "运行配置校验失败")
                return false
            }
            activity.runOnUiThread { activity.beginFloatingSession(configJson, config) }
            return true
        }

        @JavascriptInterface
        fun stopFloatingSession() {
            activity.runOnUiThread { activity.stopFloatingSessionInternal() }
        }

        @JavascriptInterface
        fun floatingCapabilities(): String = activity.floatingCapabilitiesJson()

        @JavascriptInterface
        fun capabilities(): String {
            val capture = ScreenCaptureService.instance?.isCaptureReady() == true
            val gesture = activity.hasGesturePermission()
            return "{\"capture\":$capture,\"gesture\":$gesture}"
        }

        @JavascriptInterface
        fun analyze(payload: String, requestId: String) {
            ModelClient.request(activity, payload, requestId, false)
        }

        @JavascriptInterface
        fun testConnection(payload: String, requestId: String) {
            ModelClient.request(activity, payload, requestId, true)
        }

        @JavascriptInterface
        fun questionBankList(payload: String) {
            activity.handleQuestionBankList(payload)
        }

        @JavascriptInterface
        fun questionBankSave(payload: String) {
            activity.handleQuestionBankSave(payload)
        }

        @JavascriptInterface
        fun questionBankDelete(payload: String) {
            activity.handleQuestionBankDelete(payload)
        }

        @JavascriptInterface
        fun questionBankExport() {
            activity.handleQuestionBankExport()
        }

        @JavascriptInterface
        fun questionBankImport(payload: String) {
            activity.handleQuestionBankImport(payload)
        }
    }

    private class LocalAssetWebViewClient(private val assets: AssetManager) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            request?.url?.let { url -> !isLocalAppUrl(url) } ?: true

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            url?.let(Uri::parse)?.let(::isLocalAppUrl)?.not() ?: true

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url ?: return null
            if (!isLocalAppUrl(url)) {
                return if (url.scheme in LOCAL_RESOURCE_SCHEMES) {
                    super.shouldInterceptRequest(view, request)
                } else {
                    blockedResource()
                }
            }

            val relativePath = url.path
                ?.removePrefix("/")
                ?.ifBlank { "index.html" }
                ?: "index.html"
            if (relativePath.split('/').any { it == ".." } || relativePath.contains('\\')) {
                return notFound()
            }

            return try {
                val mimeType = mimeTypeFor(Uri.parse(relativePath).path ?: relativePath)
                val encoding = if (mimeType.startsWith("text/") || mimeType == "text/javascript") "UTF-8" else null
                WebResourceResponse(mimeType, encoding, assets.open("web/$relativePath"))
            } catch (_: FileNotFoundException) {
                notFound()
            }
        }

        private fun notFound(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream("Not Found".toByteArray()),
            )
        }

        private fun blockedResource(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

        private fun isLocalAppUrl(url: Uri): Boolean =
            url.scheme == "https" && url.host == LOCAL_APP_HOST

        private fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_FLOATING_CAPTURE = 4102
        private const val REQUEST_FILE_CHOOSER = 4103
        private const val SESSION_START_TIMEOUT_MS = 5_000L
        private const val GESTURE_PERMISSION_RETRY_MS = 300L
        private const val GESTURE_PERMISSION_RETRIES = 8
        private const val EXPORT_FILE_NAME = "question-bank-export.json"
        private const val LOCAL_APP_HOST = "quiz.local"
        private const val LOCAL_APP_URL = "https://quiz.local/index.html"
        private val LOCAL_RESOURCE_SCHEMES = setOf("blob", "content", "data")
    }
}
