package com.futureyyy.tijing

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private data class PendingCapture(
        val callback: (Result<CaptureFrame>) -> Unit,
        var timeoutTask: Runnable? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val projectionStarting = AtomicBoolean(false)
    private val legacyFrameAfterStart = AtomicBoolean(false)
    private val frameRequested = AtomicBoolean(false)

    @Volatile
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var pendingCapture: PendingCapture? = null
    private var pendingSessionConfigJson: String? = null

    @Volatile
    private var latestFrame: String? = null
    @Volatile
    private var runtimeConfig: QuizRuntimeConfig? = null
    private var overlayController: OverlayController? = null
    @Volatile
    private var coordinator: QuizRunner? = null
    @Volatile
    private var sweep: CaptureSweep? = null
    private var activeCaptureGateway: CaptureGateway? = null
    private var activeModelGateway: ModelGateway? = null
    private var runnerGeneration = 0L
    private var gestureDispatchGeneration = 0L
    private var activeModelRequest: ModelRequestHandle? = null
    private var activeModelToken: Any? = null
    @Volatile
    private var questionBankStore: QuestionBankStore? = null
    private val bankWriteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TiJingBankWrite").apply { isDaemon = true }
    }
    @Volatile
    private var destroyed = false
    @Volatile
    private var terminalStopScheduled = false
    private val terminalStopRunnable = Runnable {
        if (!destroyed) stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        captureThread = HandlerThread("QuizCapture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (projection == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = projectionData(intent)
        val requestLegacyFrame = intent.getBooleanExtra(EXTRA_REQUEST_FRAME, false)
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            if (projection == null) {
                startCaptureFromAuthorization(resultCode, resultData, requestLegacyFrame)
            } else if (requestLegacyFrame) {
                requestNextFrame()
            }
        }

        intent.getStringExtra(EXTRA_SESSION_CONFIG)
            ?.takeIf { value -> value.isNotBlank() }
            ?.let(::queueRunnerSession)
        return START_NOT_STICKY
    }

    private fun projectionData(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
    }

    private fun startCaptureFromAuthorization(resultCode: Int, resultData: Intent, requestLegacyFrame: Boolean) {
        if (requestLegacyFrame) legacyFrameAfterStart.set(true)
        if (!projectionStarting.compareAndSet(false, true)) return
        val posted = captureHandler.post {
            try {
                if (projection == null) startCaptureInternal(resultCode, resultData)
                if (projection != null && legacyFrameAfterStart.getAndSet(false)) frameRequested.set(true)
            } finally {
                if (projection == null && legacyFrameAfterStart.getAndSet(false)) deliverLegacyResult("")
                projectionStarting.set(false)
            }
        }
        if (!posted) {
            projectionStarting.set(false)
            if (legacyFrameAfterStart.getAndSet(false)) deliverLegacyResult("")
        }
    }

    private fun startCaptureInternal(resultCode: Int, resultData: Intent) {
        if (projection != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val createdProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (error: Exception) {
            notifyCaptureSetupFailure(error)
            return
        } ?: run {
            notifyCaptureSetupFailure(IllegalStateException("截图授权创建失败"))
            return
        }
        projection = createdProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureInternal("截图授权已结束", stopProjection = false)
                terminateCaptureSession("截图授权已结束")
            }
        }
        projectionCallback = callback

        try {
            createdProjection.registerCallback(callback, captureHandler)
            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            require(width > 0 && height > 0) { "屏幕尺寸无效" }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_CAPACITY)
            reader.setOnImageAvailableListener(
                { available -> encodeRequestedFrame(available, width, height) },
                captureHandler,
            )
            imageReader = reader
            captureWidth = width
            captureHeight = height
            virtualDisplay = createdProjection.createVirtualDisplay(
                "TiJingCapture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            ) ?: throw IllegalStateException("虚拟屏幕创建失败")
            mainHandler.post { maybeStartPendingRunner() }
        } catch (error: Exception) {
            releaseCaptureInternal(error.message ?: "截图能力初始化失败", stopProjection = true)
            notifyCaptureSetupFailure(error)
        }
    }

    fun captureFreshFrame(callback: (Result<CaptureFrame>) -> Unit) {
        val completion = singleCaptureCompletion(callback)
        debugRunnerCapture("fresh-request")
        if (!::captureHandler.isInitialized || destroyed) {
            debugRunnerCapture("fresh-rejected-service-unavailable")
            completion(Result.failure(IllegalStateException("截图服务未运行")))
            return
        }
        val posted = captureHandler.post {
            val reader = imageReader
            when {
                projection == null || virtualDisplay == null || reader == null -> {
                    debugRunnerCapture("fresh-rejected-projection-unavailable")
                    completion(Result.failure(IllegalStateException("截图授权未准备")))
                }
                pendingCapture != null -> {
                    debugRunnerCapture("fresh-rejected-pending")
                    completion(Result.failure(IllegalStateException("已有截图请求正在等待")))
                }
                else -> {
                    val pending = PendingCapture(completion)
                    pendingCapture = pending
                    debugRunnerCapture("fresh-begin")
                    // Hiding the overlay often produces the only new frame on a static page.
                    // Consume that queued frame before waiting for another compositor update.
                    encodeRequestedFrame(reader, captureWidth, captureHeight)
                    if (pendingCapture !== pending) return@post
                    val timeout = Runnable {
                        if (pendingCapture === pending) {
                            debugRunnerCapture("fresh-timeout")
                            completePendingCapture(Result.failure(IllegalStateException("截图超时")))
                        }
                    }
                    pending.timeoutTask = timeout
                    if (!captureHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)) {
                        debugRunnerCapture("fresh-rejected-handler-stopped")
                        completePendingCapture(Result.failure(IllegalStateException("截图服务已停止")))
                    } else {
                        debugRunnerCapture("fresh-timeout-scheduled")
                    }
                }
            }
        }
        if (!posted) {
            debugRunnerCapture("fresh-rejected-post")
            completion(Result.failure(IllegalStateException("截图服务已停止")))
        }
    }

    fun requestNextFrame(): Boolean {
        if (!::captureHandler.isInitialized || projection == null || destroyed) return false
        return captureHandler.post {
            if (destroyed || projection == null || virtualDisplay == null || imageReader == null) {
                frameRequested.set(false)
                deliverLegacyResult("")
            } else {
                frameRequested.set(true)
            }
        }
    }

    fun isCaptureReady(): Boolean = projection != null && virtualDisplay != null

    fun isSessionRunning(): Boolean =
        !terminalStopScheduled && runtimeConfig != null && (coordinator != null || sweep != null || runtimeConfig?.gestureTestOnly == true || runtimeConfig?.mode == QuizMode.CAPTURE)

    private fun cancelFreshFrame(reason: String = "截图请求已取消") {
        if (!::captureHandler.isInitialized) return
        captureHandler.post {
            if (pendingCapture != null) {
                completePendingCapture(Result.failure(IllegalStateException(reason)))
            }
        }
    }

    private fun encodeRequestedFrame(reader: ImageReader, width: Int, height: Int) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: Exception) {
            if (pendingCapture != null) completePendingCapture(Result.failure(error))
            completeLegacyFrame("")
            return
        } ?: run {
            if (pendingCapture != null) debugRunnerCapture("frame-unavailable")
            return
        }
        var paddedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        val requestedByRunner = pendingCapture != null
        val requestedByLegacy = frameRequested.get()
        try {
            if (!requestedByRunner && !requestedByLegacy) return
            val plane = image.planes.firstOrNull() ?: throw IllegalStateException("截图像素平面为空")
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            require(pixelStride > 0 && rowStride >= pixelStride * width) { "截图像素格式无效" }
            val rowPadding = rowStride - pixelStride * width
            paddedBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            val pixels = IntArray(width * height)
            croppedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val signature = FrameSignature.fromArgbPixels(width, height, pixels)
            val output = ByteArrayOutputStream()
            check(croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "截图编码失败"
            }
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$encoded"
            latestFrame = dataUrl
            if (requestedByRunner) debugRunnerCapture("frame-encoded")
            if (requestedByRunner) completePendingCapture(Result.success(CaptureFrame(dataUrl, signature, width, height)))
            if (requestedByLegacy) completeLegacyFrame(dataUrl)
        } catch (error: Exception) {
            if (requestedByRunner) debugRunnerCapture("frame-failed=${error.javaClass.simpleName}")
            if (requestedByRunner) completePendingCapture(Result.failure(error))
            if (requestedByLegacy) completeLegacyFrame("")
        } finally {
            if (croppedBitmap !== paddedBitmap) runCatching { croppedBitmap?.recycle() }
            runCatching { paddedBitmap?.recycle() }
            runCatching { image.close() }
        }
    }

    private fun completeLegacyFrame(dataUrl: String) {
        if (frameRequested.compareAndSet(true, false)) deliverLegacyResult(dataUrl)
    }

    private fun deliverLegacyResult(dataUrl: String) {
        mainHandler.post { runCatching { listener?.invoke(dataUrl) } }
    }

    private fun singleCaptureCompletion(
        callback: (Result<CaptureFrame>) -> Unit,
    ): (Result<CaptureFrame>) -> Unit {
        val completed = AtomicBoolean(false)
        return { result ->
            if (completed.compareAndSet(false, true)) {
                mainHandler.post { runCatching { callback(result) } }
            }
        }
    }

    private fun completePendingCapture(result: Result<CaptureFrame>) {
        val pending = pendingCapture ?: return
        pendingCapture = null
        pending.timeoutTask?.let(captureHandler::removeCallbacks)
        pending.timeoutTask = null
        debugRunnerCapture(
            if (result.isSuccess) "fresh-complete=success"
            else "fresh-complete=failure:${result.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"}",
        )
        pending.callback(result)
    }

    private fun queueRunnerSession(configJson: String) {
        if (terminalStopScheduled) {
            mainHandler.removeCallbacks(terminalStopRunnable)
            terminalStopScheduled = false
            overlayController?.destroy()
            overlayController = null
        }
        pendingSessionConfigJson = configJson
        maybeStartPendingRunner()
    }

    private fun maybeStartPendingRunner() {
        val configJson = pendingSessionConfigJson ?: return
        if (!isCaptureReady()) return
        pendingSessionConfigJson = null
        startRunnerSession(configJson)
    }

    private fun startRunnerSession(configJson: String) {
        if (!isCaptureReady()) {
            pendingSessionConfigJson = configJson
            return
        }
        val config = try {
            QuizRuntimeConfig.fromJson(configJson)
        } catch (_: Exception) {
            notifySessionState(false)
            stopSelf()
            return
        }
        teardownRunner()
        runtimeConfig = config
        val questionBankRepository = if (config.answerSource == QuizAnswerSource.QUESTION_BANK) {
            QuestionBankRepository(this)
        } else {
            null
        }
        questionBankStore = if (config.answerSource == QuizAnswerSource.QUESTION_BANK) {
            QuestionBankStore.get(this).also { store -> bankWriteExecutor.execute { runCatching { store.index } } }
        } else {
            null
        }
        val generation = ++runnerGeneration
        val overlay = OverlayController(this, object : OverlayController.Callbacks {
            override fun onPrimaryAction() {
                if (runtimeConfig?.gestureTestOnly == true) {
                    testPageNavigation(generation)
                } else if (runtimeConfig?.mode == QuizMode.CAPTURE) {
                    startSweep(generation)
                } else {
                    withRunner(generation) { runner -> runner.onPrimaryAction() }
                }
            }

            override fun onRetry() {
                if (runtimeConfig?.mode == QuizMode.CAPTURE) startSweep(generation)
                else withRunner(generation) { it.retry() }
            }

            override fun onPause() {
                if (runtimeConfig?.mode == QuizMode.CAPTURE) stopSweep() else withRunner(generation) { runner -> runner.pause() }
            }

            override fun onResume() {
                if (runtimeConfig?.mode == QuizMode.CAPTURE) startSweep(generation)
                else withRunner(generation) { it.resume() }
            }

            override fun onStop() {
                if (runtimeConfig?.mode == QuizMode.CAPTURE) {
                    stopSweep()
                } else {
                    mainHandler.post {
                        if (!destroyed && generation == runnerGeneration) stopSelf()
                    }
                }
            }
        })
        overlayController = overlay
        if (!config.gestureTestOnly && config.mode == QuizMode.CAPTURE) overlay.setCaptureMode(true)
        if (!overlay.show()) {
            overlayController = null
            overlay.destroy()
            runtimeConfig = null
            terminateCaptureSession("悬浮窗创建失败，请检查练题流的悬浮窗权限")
            return
        }

        if (config.gestureTestOnly) {
            overlay.setGestureTestMode()
            notifySessionState(true)
            return
        }

        val captureGateway = object : CaptureGateway {
            private var activeCompletion: ((Result<CaptureFrame>) -> Unit)? = null

            override fun capture(callback: (Result<CaptureFrame>) -> Unit) {
                mainHandler.post {
                    debugRunnerCapture("gateway-capture-request")
                    val currentOverlay = overlayController
                    if (destroyed || generation != runnerGeneration || currentOverlay == null) {
                        debugRunnerCapture("gateway-capture-rejected-session")
                        callback(Result.failure(IllegalStateException("运行会话已停止")))
                        return@post
                    }
                    if (activeCompletion != null) {
                        debugRunnerCapture("gateway-capture-rejected-active")
                        callback(Result.failure(IllegalStateException("已有截图流程正在等待")))
                        return@post
                    }
                    val delivered = AtomicBoolean(false)
                    lateinit var completion: (Result<CaptureFrame>) -> Unit
                    completion = { result ->
                        if (delivered.compareAndSet(false, true)) {
                            if (activeCompletion === completion) activeCompletion = null
                            callback(result)
                        }
                    }
                    activeCompletion = completion
                    currentOverlay.hideForCapture {
                        debugRunnerCapture("gateway-overlay-hidden")
                        captureFreshFrame { result ->
                            debugRunnerCapture("gateway-capture-result=${if (result.isSuccess) "success" else "failure"}")
                            currentOverlay.restoreAfterCapture()
                            completion(result)
                        }
                    }
                }
            }

            override fun cancel() {
                val cancelAction = Runnable {
                    overlayController?.restoreAfterCapture()
                    activeCompletion?.invoke(Result.failure(IllegalStateException("截图请求已取消")))
                    cancelFreshFrame()
                }
                if (Looper.myLooper() == Looper.getMainLooper()) cancelAction.run() else mainHandler.post(cancelAction)
            }
        }
        val modelGateway = object : ModelGateway {
            private val concurrentHandles =
                java.util.Collections.synchronizedList(mutableListOf<ModelRequestHandle>())

            override fun analyze(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit) {
                request(frame, questionNo, cancelPrevious = true, callback = callback)
            }

            override fun analyzeConcurrent(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit) {
                request(frame, questionNo, cancelPrevious = false, callback = callback)
            }

            private fun requestConcurrent(payload: String, deliver: (Result<String>) -> Unit) {
                lateinit var handle: ModelRequestHandle
                handle = ModelClient.requestAsync(payload, connectionCheck = false) { result ->
                    mainHandler.post {
                        concurrentHandles.remove(handle)
                        deliver(result)
                    }
                }
                concurrentHandles.add(handle)
            }

            private fun request(
                frame: CaptureFrame,
                questionNo: Int,
                cancelPrevious: Boolean,
                callback: (Result<String>) -> Unit,
            ) {
                val currentConfig = runtimeConfig
                if (destroyed || generation != runnerGeneration || currentConfig == null) {
                    mainHandler.post { callback(Result.failure(IllegalStateException("运行会话已停止"))) }
                    return
                }
                val payload = JSONObject()
                    .put("endpoint", currentConfig.endpoint)
                    .put("model", currentConfig.model)
                    .put("apiKey", currentConfig.apiKey)
                    .put(
                        "prompt",
                        if (currentConfig.answerSource == QuizAnswerSource.QUESTION_BANK) {
                            currentConfig.questionBankPrompt
                        } else {
                            currentConfig.prompt
                        },
                    )
                    .put("timeoutMs", currentConfig.timeoutMs)
                    .put("retryCount", currentConfig.retryCount)
                    .put("questionNo", questionNo)
                    .put("image", frame.dataUrl)
                    .toString()
                val requestToken = Any()
                if (cancelPrevious) {
                    activeModelRequest?.cancel()
                    activeModelToken = requestToken
                }
                debugRunnerGesture("model-request q=$questionNo cancelPrev=$cancelPrevious")

                fun finish(result: Result<String>) {
                    if (cancelPrevious && activeModelToken === requestToken) {
                        activeModelToken = null
                        activeModelRequest = null
                    }
                    callback(result)
                }

                fun dispatch(result: Result<String>) {
                    debugRunnerGesture("model-result q=$questionNo success=${result.isSuccess}")
                    if (currentConfig.answerSource == QuizAnswerSource.QUESTION_BANK && result.isSuccess) {
                        val index = questionBankRepository?.index
                        val raw = result.getOrNull().orEmpty()
                        val bankStatus = index?.let { bankIndex ->
                            runCatching { QuestionBankResponseAdapter.matchStatus(raw, bankIndex) }.getOrNull()
                        }
                        if (index == null || bankStatus == null || QuestionBankResponseAdapter.shouldUseVisionFallback(bankStatus)) {
                            // A bank miss is resolved on the same screenshot with the
                            // regular visual-answer prompt. The next question starts a
                            // fresh bank request because the runtime config stays intact.
                            debugRunnerGesture("bank-vision-fallback q=$questionNo status=${bankStatus ?: "invalid"}")
                            overlayController?.showRecognitionStatus(
                                "题库未命中，正在切换到多模态视觉识别模式",
                                when (bankStatus) {
                                    QuestionBankMatchStatus.AMBIGUOUS -> "匹配不唯一"
                                    QuestionBankMatchStatus.CONFLICT -> "题库答案冲突"
                                    QuestionBankMatchStatus.NOT_FOUND, null -> "题库未命中"
                                    QuestionBankMatchStatus.EXACT -> "精确匹配"
                                    QuestionBankMatchStatus.KEY_PHRASE -> "关键短句匹配"
                                },
                            )
                            val fallbackPayload = JSONObject(payload)
                                .put("prompt", currentConfig.prompt)
                                .toString()
                            val onFallback: (Result<String>) -> Unit = { fallbackResult ->
                                debugRunnerGesture("bank-vision-result q=$questionNo success=${fallbackResult.isSuccess}")
                                fallbackResult.getOrNull()?.let(::writeBackFallbackAnswer)
                                finish(fallbackResult.map { recognized ->
                                    runCatching {
                                        JSONObject(recognized)
                                            .put("match_status", "vision_fallback")
                                            .put("match_label", "视觉模型兜底")
                                            .put("answer_source", "vision")
                                            .toString()
                                    }.getOrElse { recognized }
                                })
                            }
                            if (cancelPrevious) {
                                val fallbackRequest = ModelClient.requestAsync(
                                    fallbackPayload,
                                    connectionCheck = false,
                                ) { fallbackResult -> mainHandler.post { onFallback(fallbackResult) } }
                                if (activeModelToken === requestToken) activeModelRequest = fallbackRequest
                            } else {
                                requestConcurrent(fallbackPayload) { fallbackResult -> onFallback(fallbackResult) }
                            }
                            return
                        }
                        val bankJson = runCatching {
                            QuestionBankResponseAdapter.toModelJson(raw, index, questionNo)
                        }
                        if (bankJson.isSuccess) {
                            finish(Result.success(bankJson.getOrThrow()))
                            return
                        }
                    }
                    finish(result)
                }

                if (cancelPrevious) {
                    val request = ModelClient.requestAsync(payload, connectionCheck = false) { result ->
                        mainHandler.post { dispatch(result) }
                    }
                    activeModelRequest = request
                } else {
                    requestConcurrent(payload) { result -> dispatch(result) }
                }
            }

            override fun cancel() {
                val cancelAction = Runnable {
                    activeModelToken = null
                    val request = activeModelRequest
                    activeModelRequest = null
                    request?.cancel()
                    val pending = synchronized(concurrentHandles) { concurrentHandles.toList() }
                    concurrentHandles.clear()
                    pending.forEach { handle -> runCatching { handle.cancel() } }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) cancelAction.run() else mainHandler.post(cancelAction)
            }
        }
        val gestureGateway = object : GestureGateway {
            private var activeTargetPackage: String? = null

            private fun resolveTargetPackage(service: QuizAccessibilityService): String? {
                val lockedTarget = activeTargetPackage
                if (lockedTarget != null) {
                    return lockedTarget.takeIf(service::isTargetForeground)
                }
                val preferredTarget = config.targetApp.packageName
                    .takeIf { it.isNotBlank() && service.isTargetForeground(it) }
                return (preferredTarget ?: service.foregroundApplicationPackage())
                    ?.also { activeTargetPackage = it }
            }

            override fun isTargetForeground(): Boolean {
                val service = QuizAccessibilityService.instance ?: return false
                return resolveTargetPackage(service) != null
            }

            override fun targetUnavailableMessage(): String = "请保持题目页在前台"

            override fun resetTarget() {
                activeTargetPackage = null
            }

            override fun tap(box: NormalizedBox, callback: (Boolean) -> Unit) {
                dispatchGestureAfterAnswer(generation, callback) { service, completion ->
                    val targetPackage = resolveTargetPackage(service)
                    if (targetPackage == null) {
                        completion(false)
                    } else {
                        service.tapNormalizedAsync(
                            box.centerX / 100f,
                            box.centerY / 100f,
                            targetPackage,
                            completion,
                        )
                    }
                }
            }

            override fun tapConfirm(box: NormalizedBox?, callback: (Boolean) -> Unit) = tapConfirmAttempt(box, 1, callback)

            override fun tapConfirmAttempt(box: NormalizedBox?, attempt: Int, callback: (Boolean) -> Unit) {
                dispatchGestureAfterAnswer(generation, callback, CONFIRM_SETTLE_DELAY_MS) { service, completion ->
                    val targetPackage = resolveTargetPackage(service)
                    if (targetPackage == null) completion(false)
                    else service.tapConfirmationAsync(box, attempt, targetPackage, completion)
                }
            }

            override fun cancel() {
                val cancelAction = Runnable {
                    gestureDispatchGeneration += 1
                    overlayController?.cancelPendingGestureAction()
                    QuizAccessibilityService.instance?.cancelGestures()
                }
                if (Looper.myLooper() == Looper.getMainLooper()) cancelAction.run() else mainHandler.post(cancelAction)
            }
        }
        val listener = object : RunnerListener {
            override fun onSnapshot(snapshot: RunnerSnapshot) {
                mainHandler.post {
                    if (!destroyed && generation == runnerGeneration) {
                        debugRunnerGesture("snapshot phase=${snapshot.phase} q=${snapshot.questionNo}")
                        overlayController?.update(snapshot)
                        if (snapshot.phase == RunnerPhase.STOPPED && snapshot.message == "已核实提交成功，运行结束") {
                            Toast.makeText(this@ScreenCaptureService, snapshot.message, Toast.LENGTH_LONG).show()
                            mainHandler.postDelayed({ if (generation == runnerGeneration) stopSelf() }, 1500L)
                        }
                    }
                }
            }

            override fun onAnswer(result: QuizModelResult) {
                mainHandler.post {
                    if (!destroyed && generation == runnerGeneration) {
                        overlayController?.showAnswer(
                            result,
                            autoCollapse = config.answerSource == QuizAnswerSource.VISION && config.mode == QuizMode.AUTO,
                        )
                    }
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    if (!destroyed && generation == runnerGeneration) overlayController?.showError(message)
                }
            }
        }
        val scheduler = RunnerScheduler { delayMs, action ->
            val task = Runnable {
                if (!destroyed && generation == runnerGeneration) action()
            }
            mainHandler.postDelayed(task, delayMs)
            RunnerScheduledTask { mainHandler.removeCallbacks(task) }
        }
        activeCaptureGateway = captureGateway
        activeModelGateway = modelGateway
        if (config.mode == QuizMode.CAPTURE) {
            sweep = null
            overlay.updateSweepState(false, 0)
        } else {
            val newCoordinator: QuizRunner = if (config.mode == QuizMode.AUTO) {
                // Pin the initial foreground app for the whole run, rather than following app switches.
                val flowGestures = object : FlowGestures {
                    private var target: String? = null
                    override fun isForeground(): Boolean {
                        val service = QuizAccessibilityService.instance ?: return false
                        if (target == null) target = service.foregroundApplicationPackage()
                        return target?.let(service::isTargetForeground) == true
                    }
                    override fun tap(box: NormalizedBox, callback: (Boolean) -> Unit) {
                        dispatchGestureAfterAnswer(generation, callback) { service, done ->
                            val pkg = target
                            if (pkg == null) done(false) else service.tapNormalizedAsync(box.centerX / 100f, box.centerY / 100f, pkg, done)
                        }
                    }
                    override fun tapPoint(point: TapPoint, callback: (Boolean) -> Unit) {
                        dispatchGestureAfterAnswer(generation, callback) { service, done ->
                            val pkg = target
                            if (pkg == null) done(false) else service.tapPointAsync(point, pkg, done)
                        }
                    }
                    override fun confirm(box: NormalizedBox?, attempt: Int, callback: (Boolean) -> Unit) {
                        dispatchGestureAfterAnswer(generation, callback, CONFIRM_SETTLE_DELAY_MS) { service, done ->
                            val pkg = target
                            if (pkg == null) done(false) else service.tapConfirmationAsync(box, attempt, pkg, done)
                        }
                    }
                    override fun swipe(direction: FlowSwipe, navigation: Boolean, callback: (Boolean) -> Unit) {
                        dispatchGestureAfterAnswer(generation, callback) { service, done ->
                            val pkg = target
                            if (pkg == null) done(false) else if (navigation) {
                                service.swipePathAsync(config.swipeProfiles.getValue(direction), pkg, done)
                            } else service.swipeAsync(direction, pkg, done)
                        }
                    }
                    override fun cancel() = gestureGateway.cancel()
                }
                AdaptiveQuizRunner(config, captureGateway, AutomationModelClient(config, questionBankRepository), flowGestures, listener, scheduler)
            } else QuizRunCoordinator(
                mode = config.mode,
                startingQuestionNo = config.startingQuestionNo,
                captureGateway = captureGateway,
                modelGateway = modelGateway,
                gestureGateway = gestureGateway,
                listener = listener,
                scheduler = scheduler,
            )
            coordinator = newCoordinator
            overlay.update(newCoordinator.snapshot())
        }
        notifySessionState(true)
    }

    private fun startSweep(generation: Long) {
        val overlay = overlayController ?: return
        val capture = activeCaptureGateway ?: return
        val model = activeModelGateway ?: return
        val config = runtimeConfig ?: return
        sweep?.stop()
        val sweepScheduler = RunnerScheduler { delayMs, action ->
            val task = Runnable { if (!destroyed && generation == runnerGeneration) action() }
            mainHandler.postDelayed(task, delayMs)
            RunnerScheduledTask { mainHandler.removeCallbacks(task) }
        }
        val targetPackage = QuizAccessibilityService.instance?.foregroundApplicationPackage()
        val newSweep = CaptureSweep(
            captureGateway = capture,
            modelGateway = model,
            tapNext = { callback -> executePageNavigation(generation, config, targetPackage,
                { sweep?.isRunning == true }, callback) },
            scheduler = sweepScheduler,
            startingQuestionNo = config.startingQuestionNo,
            settleDelayMs = config.settleMs.toLong(),
            listener = object : CaptureSweepListener {
                override fun onNavigationError(message: String) {
                    mainHandler.post { if (generation == runnerGeneration) overlayController?.showError(message) }
                }
                override fun onProgress(questionNo: Int, recognized: Int, pending: Int) {
                    mainHandler.post {
                        if (!destroyed && generation == runnerGeneration) {
                            overlayController?.updateSweepState(true, recognized, pending)
                        }
                    }
                }

                override fun onFinished(answers: List<SweptAnswer>) {
                    mainHandler.post {
                        if (!destroyed && generation == runnerGeneration) {
                            overlayController?.updateSweepState(false, answers.size)
                            overlayController?.showOverview(answers)
                        }
                    }
                }
            },
        )
        sweep = newSweep
        overlay.updateSweepState(true, 0)
        newSweep.start()
    }

    private fun stopSweep() {
        sweep?.stop()
    }

    private var navigationTestRunning = false

    private fun testPageNavigation(generation: Long) {
        if (navigationTestRunning) return
        val config = runtimeConfig ?: return
        navigationTestRunning = true
        val target = QuizAccessibilityService.instance?.foregroundApplicationPackage()
        executePageNavigation(generation, config, target, { runtimeConfig?.gestureTestOnly == true }) { success ->
            navigationTestRunning = false
            if (!destroyed && generation == runnerGeneration) overlayController?.showGestureTestStatus(
                if (success) "动作已发送，请观察页面是否变化。可再次测试或停止。"
                else "动作未发出：请打开目标题目页，检查无障碍权限后重试。")
        }
    }

    private fun executePageNavigation(
        generation: Long, config: QuizRuntimeConfig, target: String?, allowed: () -> Boolean,
        callback: (Boolean) -> Unit,
    ) {
        val command = NavigationCommand.from(config)
        dispatchGestureAfterAnswer(generation, callback) { service, done ->
            if (!allowed() || target.isNullOrBlank() || !service.isTargetForeground(target)) done(false)
            else when (command) {
                is NavigationCommand.Click -> service.tapPointAsync(command.point, target, done)
                is NavigationCommand.Swipe -> service.swipePathAsync(command.path, target, done)
                NavigationCommand.Confirm -> service.tapConfirmationAsync(null, 1, target, done)
            }
        }
    }

    private fun withRunner(generation: Long, action: (QuizRunner) -> Unit) {
        mainHandler.post {
            if (!destroyed && generation == runnerGeneration) coordinator?.let(action)
        }
    }

    private fun dispatchGestureAfterAnswer(
        generation: Long,
        callback: (Boolean) -> Unit,
        settleDelayMs: Long = 0L,
        dispatch: (QuizAccessibilityService, (Boolean) -> Unit) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val finish: (Boolean) -> Unit = { succeeded ->
            if (completed.compareAndSet(false, true)) {
                debugRunnerGesture("callback success=$succeeded")
                overlayController?.restoreAfterCapture()
                callback(succeeded)
            }
        }
        mainHandler.post {
            if (destroyed || generation != runnerGeneration) {
                debugRunnerGesture("rejected-stale-run")
                finish(false)
                return@post
            }
            val dispatchGeneration = gestureDispatchGeneration
            val runGesture = runGesture@{
                if (
                    destroyed || generation != runnerGeneration ||
                    dispatchGeneration != gestureDispatchGeneration
                ) {
                    debugRunnerGesture("rejected-stale-dispatch")
                    finish(false)
                    return@runGesture
                }
                val service = QuizAccessibilityService.instance
                if (service == null) {
                    debugRunnerGesture("rejected-service-missing")
                    finish(false)
                    return@runGesture
                }
                try {
                    debugRunnerGesture("dispatching settleDelayMs=$settleDelayMs")
                    dispatch(service) { succeeded -> mainHandler.post { finish(succeeded) } }
                } catch (error: Exception) {
                    debugRunnerGesture("dispatch-exception=${error.javaClass.simpleName}")
                    finish(false)
                }
            }
            val dispatchAfterCollapse: () -> Unit = {
                val afterHidden = {
                    if (settleDelayMs > 0L) mainHandler.postDelayed(runGesture, settleDelayMs)
                    else runGesture()
                    Unit
                }
                // A floating bubble or answer control can otherwise intercept the target tap.
                overlayController?.hideForCapture(afterHidden) ?: afterHidden()
                Unit
            }
            debugRunnerGesture("waiting-for-overlay-collapse")
            overlayController?.runAfterAnswerCollapsed(dispatchAfterCollapse) ?: dispatchAfterCollapse()
        }
    }

    private fun writeBackFallbackAnswer(raw: String) {
        val store = questionBankStore ?: return
        val parsed = runCatching { QuizModelParser.parse(raw) }.getOrNull() ?: return
        if (parsed.question.isBlank()) return
        if (parsed.answerIndices.isEmpty() && parsed.answerText.isNullOrBlank()) return
        // Serialising the whole bank and rebuilding the match index must stay off the main
        // thread, otherwise each write-back stalls the capture loop for hundreds of ms.
        bankWriteExecutor.execute {
            runCatching {
                store.writeBackFallback(
                    question = parsed.question,
                    type = parsed.type,
                    options = parsed.options,
                    answerIndices = parsed.answerIndices,
                    answerText = parsed.answerText.orEmpty(),
                )
                store.index
            }
        }
    }

    private fun debugRunnerGesture(event: String) {
        if (BuildConfig.DEBUG) Log.i(RUNNER_LOG_TAG, "gesture-$event")
    }

    private fun debugRunnerCapture(event: String) {
        if (BuildConfig.DEBUG) Log.i(RUNNER_LOG_TAG, "capture-$event")
    }

    private fun teardownRunner() {
        val wasRunning = coordinator != null || sweep != null || runtimeConfig != null
        if (runtimeConfig?.gestureTestOnly == true) {
            gestureDispatchGeneration += 1
            QuizAccessibilityService.instance?.cancelGestures()
        }
        navigationTestRunning = false
        runnerGeneration += 1
        val currentCoordinator = coordinator
        coordinator = null
        currentCoordinator?.stop()
        val currentSweep = sweep
        sweep = null
        currentSweep?.stop()
        activeCaptureGateway = null
        activeModelGateway = null
        activeModelToken = null
        activeModelRequest?.cancel()
        activeModelRequest = null
        val currentOverlay = overlayController
        overlayController = null
        currentOverlay?.destroy()
        runtimeConfig = null
        questionBankStore = null
        if (wasRunning) notifySessionState(false)
    }

    private fun notifySessionState(running: Boolean) {
        mainHandler.post { runCatching { sessionStateListener?.invoke(running) } }
    }

    private fun notifyCaptureSetupFailure(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() }
        val message = if (detail == null || detail == "截图能力初始化失败") {
            "截图能力初始化失败"
        } else {
            "截图能力初始化失败：${detail.take(120)}"
        }
        terminateCaptureSession(message)
    }

    private fun terminateCaptureSession(message: String) {
        mainHandler.post {
            if (destroyed || terminalStopScheduled) return@post
            terminalStopScheduled = true
            pendingSessionConfigJson = null
            runnerGeneration += 1
            val terminalCoordinator = coordinator
            coordinator = null
            val terminalSweep = sweep
            sweep = null
            runtimeConfig = null
            questionBankStore = null
            terminalCoordinator?.stop()
            terminalSweep?.stop()
            activeCaptureGateway = null
            activeModelGateway = null
            activeModelToken = null
            activeModelRequest?.cancel()
            activeModelRequest = null
            notifySessionState(false)
            overlayController?.showError(message)
            Toast.makeText(this, "$message，请回到练题流重新启用", Toast.LENGTH_LONG).show()
            mainHandler.postDelayed(terminalStopRunnable, TERMINAL_MESSAGE_DELAY_MS)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.foreground_service_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun releaseCaptureInternal(reason: String, stopProjection: Boolean) {
        completeLegacyFrame("")
        if (legacyFrameAfterStart.getAndSet(false)) deliverLegacyResult("")
        if (pendingCapture != null) {
            completePendingCapture(Result.failure(IllegalStateException(reason)))
        }
        val currentDisplay = virtualDisplay
        virtualDisplay = null
        runCatching { currentDisplay?.release() }
        val currentReader = imageReader
        imageReader = null
        captureWidth = 0
        captureHeight = 0
        runCatching { currentReader?.setOnImageAvailableListener(null, null) }
        runCatching { currentReader?.close() }
        val currentProjection = projection
        projection = null
        projectionCallback?.let { callback -> runCatching { currentProjection?.unregisterCallback(callback) } }
        projectionCallback = null
        if (stopProjection) runCatching { currentProjection?.stop() }
        latestFrame = null
    }

    private fun stopCaptureSynchronously(reason: String) {
        if (!::captureHandler.isInitialized) return
        if (Looper.myLooper() == captureHandler.looper) {
            releaseCaptureInternal(reason, stopProjection = true)
            return
        }
        val latch = CountDownLatch(1)
        val posted = captureHandler.post {
            try {
                releaseCaptureInternal(reason, stopProjection = true)
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(CAPTURE_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    override fun onDestroy() {
        destroyed = true
        pendingSessionConfigJson = null
        mainHandler.removeCallbacks(terminalStopRunnable)
        teardownRunner()
        notifySessionState(false)
        stopCaptureSynchronously("截图服务已停止")
        if (::captureThread.isInitialized) captureThread.quitSafely()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val RUNNER_LOG_TAG = "TiJingRunner"
        const val ACTION_STOP = "com.futureyyy.tijing.action.STOP_CAPTURE"
        private const val CONFIRM_SETTLE_DELAY_MS = 240L
        private const val SWEEP_SETTLE_DELAY_MS = 1000L
        private const val CHANNEL_ID = "tijing-capture"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_REQUEST_FRAME = "requestFrame"
        private const val EXTRA_SESSION_CONFIG = "sessionConfig"
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val CAPTURE_RELEASE_WAIT_MS = 1_000L
        private const val TERMINAL_MESSAGE_DELAY_MS = 2_000L
        private const val IMAGE_READER_CAPACITY = 2
        private const val JPEG_QUALITY = 78

        @Volatile
        var instance: ScreenCaptureService? = null

        @Volatile
        var listener: ((String) -> Unit)? = null

        @Volatile
        var sessionStateListener: ((Boolean) -> Unit)? = null

        fun start(context: Context, resultCode: Int, resultData: Intent, requestFrame: Boolean = false) {
            val intent = baseStartIntent(context, resultCode, resultData)
                .putExtra(EXTRA_REQUEST_FRAME, requestFrame)
            startCompat(context, intent)
        }

        fun startSession(context: Context, resultCode: Int, resultData: Intent, configJson: String) {
            val intent = baseStartIntent(context, resultCode, resultData)
                .putExtra(EXTRA_SESSION_CONFIG, configJson)
            startCompat(context, intent)
        }

        /** Reuses a live MediaProjection grant, so the system consent dialog is not shown again. */
        fun resumeSession(context: Context, configJson: String) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_SESSION_CONFIG, configJson)
            startCompat(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        private fun baseStartIntent(context: Context, resultCode: Int, resultData: Intent) =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
