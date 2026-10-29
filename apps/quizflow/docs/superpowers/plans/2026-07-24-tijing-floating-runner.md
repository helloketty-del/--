# 题镜悬浮运行器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将题镜改造成微信前台可用的原生悬浮识题工具，自动模式点击一次连续运行到第 100 题，手动模式每题点击一次并显示答案。

**Architecture:** 保留 `ScreenCaptureService` 作为唯一前台服务和 `MediaProjection` 持有者，在服务内组合 `OverlayController`、`QuizRunCoordinator`、`QuizModelParser`。App 内现有页面只负责配置与启动原生会话；浏览器预览不属于交付范围。

**Tech Stack:** Kotlin、Android `WindowManager`、`MediaProjection`、`AccessibilityService`、`org.json`、JUnit 4、React/Vite 内嵌设置页。

**Repository note:** 当前目录没有 Git 元数据，各任务使用测试、构建产物哈希和文件清单作为检查点。

---

### Task 1: 原生运行配置与模型 JSON 解析

**Files:**
- Create: `android/app/src/main/java/com/futureyyy/tijing/QuizRuntimeConfig.kt`
- Create: `android/app/src/main/java/com/futureyyy/tijing/QuizModelParser.kt`
- Create: `android/app/src/test/java/com/futureyyy/tijing/QuizModelParserTest.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: 添加本地单元测试依赖**

在 `android/app/build.gradle.kts` 增加：

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 2: 写失败测试**

测试覆盖：

```kotlin
@Test fun parsesSingleQuestionWithNormalizedBox()
@Test fun parsesMultipleQuestionAndNextBox()
@Test fun rejectsMissingAnswerIndices()
@Test fun rejectsAnswerIndexOutsideOptions()
@Test fun rejectsSingleQuestionWithMultipleAnswers()
@Test fun ignoresNextButtonAtQuestionOneHundredInActionPlan()
```

运行：

```bash
./gradlew :app:testDebugUnitTest --tests com.futureyyy.tijing.QuizModelParserTest
```

预期：FAIL，`QuizModelParser` 尚未定义。

- [ ] **Step 3: 实现不可变配置和模型类型**

核心 API：

```kotlin
enum class QuizMode { AUTO, MANUAL }
enum class QuestionType { SINGLE, MULTIPLE, TRUE_FALSE }

data class NormalizedBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

data class QuizOption(val index: Int, val text: String, val box: NormalizedBox?)
data class QuizModelResult(
    val questionNo: Int?,
    val type: QuestionType,
    val question: String,
    val options: List<QuizOption>,
    val answerIndices: List<Int>,
    val nextBox: NormalizedBox?,
    val confidence: Double,
)

data class QuizRuntimeConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val prompt: String,
    val mode: QuizMode,
    val timeoutMs: Int,
    val retryCount: Int,
    val totalQuestions: Int = 100,
)
```

`QuizRuntimeConfig.fromJson(raw)` 必须校验 Key、URL、模型名，并限制超时 `5000..120000`、重试 `0..5`。

- [ ] **Step 4: 实现解析与动作计划**

```kotlin
object QuizModelParser {
    fun parse(raw: String): QuizModelResult
    fun actionPlan(result: QuizModelResult, questionNo: Int, total: Int): QuizActionPlan
}
```

解析器移除 Markdown JSON 围栏，验证所有坐标位于 0..100，按 `option.index` 查找答案。第 100 题的 `QuizActionPlan.tapNext` 固定为 `false`。

- [ ] **Step 5: 运行测试**

运行同一测试命令，预期全部 PASS；随后运行 `npm test`，确认现有 21 项测试继续通过。

---

### Task 2: 模型回调与可等待的无障碍点击

**Files:**
- Modify: `android/app/src/main/java/com/futureyyy/tijing/ModelClient.kt`
- Modify: `android/app/src/main/java/com/futureyyy/tijing/QuizAccessibilityService.kt`
- Test: `tests/androidShell.test.mjs`

- [ ] **Step 1: 写 bridge 静态失败测试**

在 `tests/androidShell.test.mjs` 断言以下原生 API 存在：

```js
assert.match(modelClient, /fun requestAsync\(/);
assert.match(accessibilityService, /fun tapNormalizedAsync\(/);
assert.match(accessibilityService, /fun isWeChatForeground\(/);
```

运行 `node --test tests/androidShell.test.mjs`，预期 FAIL。

- [ ] **Step 2: 重构模型请求为双入口**

新增：

```kotlin
fun requestAsync(
    payloadText: String,
    connectionCheck: Boolean = false,
    callback: (Result<String>) -> Unit,
)
```

现有 `request(context, payloadText, requestId, connectionCheck)` 调用 `requestAsync`，再把结果送回 `MainActivity.deliverModelResponse`。原生协调器直接使用 `requestAsync`，回调中不包含 API Key。

- [ ] **Step 3: 为手势队列增加完成回调**

将队列元素改成：

```kotlin
private data class PendingGesture(
    val description: GestureDescription,
    val completion: (Boolean) -> Unit,
)
```

提供：

```kotlin
fun tapNormalizedAsync(nx: Float, ny: Float, completion: (Boolean) -> Unit): Boolean
fun tapTextAsync(text: String, completion: (Boolean) -> Unit): Boolean
fun isWeChatForeground(): Boolean
```

`onCompleted` 回传 `true`，`onCancelled`/拒绝派发/离开微信回传 `false`。原有 JavaScript 同步入口继续保留。

- [ ] **Step 4: 验证**

运行 Node 测试和 Android `compileDebugKotlin`，预期通过。

---

### Task 3: 原生协调器与自动/手动状态机

**Files:**
- Create: `android/app/src/main/java/com/futureyyy/tijing/QuizRunCoordinator.kt`
- Create: `android/app/src/main/java/com/futureyyy/tijing/CaptureFrame.kt`
- Create: `android/app/src/test/java/com/futureyyy/tijing/QuizRunCoordinatorTest.kt`

- [ ] **Step 1: 定义可替换依赖和状态**

```kotlin
enum class RunnerPhase {
    READY, CAPTURING, RECOGNIZING, SHOWING_ANSWER,
    EXECUTING, WAITING_PAGE_CHANGE, PAUSED_ERROR,
    WAITING_MANUAL_SUBMIT, STOPPED,
}

data class CaptureFrame(val dataUrl: String, val signature: FrameSignature)

interface CaptureGateway { fun capture(callback: (Result<CaptureFrame>) -> Unit) }
interface ModelGateway { fun analyze(frame: CaptureFrame, questionNo: Int, callback: (Result<String>) -> Unit) }
interface GestureGateway {
    fun isWeChatForeground(): Boolean
    fun tap(box: NormalizedBox, callback: (Boolean) -> Unit)
    fun tapNext(box: NormalizedBox?, callback: (Boolean) -> Unit)
    fun cancel()
}
interface RunnerListener {
    fun onSnapshot(snapshot: RunnerSnapshot)
    fun onAnswer(result: QuizModelResult)
    fun onError(message: String)
}
```

- [ ] **Step 2: 写状态机失败测试**

覆盖：

```kotlin
@Test fun manualTapPerformsOneRecognitionWithoutGestures()
@Test fun autoTapStartsNextQuestionAfterStablePageChange()
@Test fun multipleChoiceTapsEveryAnswerThenNext()
@Test fun questionOneHundredNeverTapsNext()
@Test fun staleCallbackCannotAdvanceNewGeneration()
@Test fun stopCancelsQueuedGestures()
@Test fun malformedJsonPausesWithoutGesture()
```

使用同步 fake gateway，不依赖 Android UI。

- [ ] **Step 3: 实现状态机**

公共入口：

```kotlin
fun onPrimaryAction()
fun pause()
fun resume()
fun retry()
fun stop()
fun snapshot(): RunnerSnapshot
```

自动模式在第一次 `onPrimaryAction()` 后循环；手动模式每次只执行 `capture -> recognize -> show answer -> ready`。每个异步回调捕获 generation，generation 不匹配时直接丢弃。

- [ ] **Step 4: 实现换页稳定判定**

`FrameSignature` 使用排除顶部 12% 与底部 8% 的中央区域 16x16 感知哈希。页面变化需要：

```text
与基线 Hamming distance >= 20
并且连续两张新帧之间 Hamming distance <= 8
```

首次轮询延迟 650ms，后续间隔 450ms，最多 12 次。超时进入 `PAUSED_ERROR`。

- [ ] **Step 5: 验证**

运行两组 Kotlin 单元测试，预期全部通过。

---

### Task 4: 悬浮按钮、答案卡与截图服务集成

**Files:**
- Create: `android/app/src/main/java/com/futureyyy/tijing/OverlayController.kt`
- Modify: `android/app/src/main/java/com/futureyyy/tijing/ScreenCaptureService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `tests/androidShell.test.mjs`

- [ ] **Step 1: 写原生资源/接口失败测试**

断言：

```js
assert.match(manifest, /android.permission.SYSTEM_ALERT_WINDOW/);
assert.match(screenCaptureService, /OverlayController/);
assert.match(screenCaptureService, /QuizRunCoordinator/);
assert.match(overlayController, /TYPE_APPLICATION_OVERLAY/);
assert.match(overlayController, /hideForCapture/);
```

- [ ] **Step 2: 实现 `OverlayController`**

使用程序化 Android View 创建：

- 56dp 可拖动悬浮按钮，右侧吸附。
- 状态标签显示 `开始`、`识别中`、`自动 N/100`、`已暂停`、`完成`。
- 顶部答案卡显示题型、答案字母/文本、置信度。
- 错误卡包含“重试”“继续/暂停”“停止”。

关键接口：

```kotlin
fun show()
fun update(snapshot: RunnerSnapshot)
fun showAnswer(result: QuizModelResult, autoCollapse: Boolean)
fun showError(message: String)
fun hideForCapture(afterHidden: () -> Unit)
fun restoreAfterCapture()
fun destroy()
```

窗口使用 `TYPE_APPLICATION_OVERLAY` 与 `FLAG_NOT_FOCUSABLE`，只拦截自身区域触摸。

- [ ] **Step 3: 扩展截图服务**

服务新增单一 pending frame callback：

```kotlin
fun captureFreshFrame(callback: (Result<CaptureFrame>) -> Unit)
```

截图流程由 `OverlayController.hideForCapture` 延迟 180ms 后触发。编码线程生成 JPEG data URL 与 `FrameSignature`。完成后恢复悬浮按钮，再把帧交给协调器。

- [ ] **Step 4: 在服务内装配协调器**

服务收到 session config 后创建 overlay、model gateway、gesture gateway 和 coordinator。停止服务时按顺序执行：

```text
coordinator.stop -> overlay.destroy -> capture resources release -> config clear
```

- [ ] **Step 5: 品牌文案**

`strings.xml` 更新为：

```xml
<string name="app_name">题镜</string>
<string name="accessibility_service_label">题镜自动点击服务</string>
<string name="foreground_service_title">题镜正在运行</string>
```

- [ ] **Step 6: 验证**

运行 Node 测试、Kotlin 测试和 `assembleDebug`。

---

### Task 5: App 权限编排与原生会话桥接

**Files:**
- Modify: `android/app/src/main/java/com/futureyyy/tijing/MainActivity.kt`
- Modify: `src/lib/nativeBridge.js`
- Modify: `src/App.jsx`
- Modify: `tests/androidShell.test.mjs`

- [ ] **Step 1: 写启动 bridge 失败测试**

断言存在：

```text
AndroidBridge.startFloatingSession(configJson)
AndroidBridge.stopFloatingSession()
AndroidBridge.floatingCapabilities()
Settings.ACTION_MANAGE_OVERLAY_PERMISSION
```

- [ ] **Step 2: 实现原生权限状态**

`floatingCapabilities()` 返回：

```json
{
  "overlay": true,
  "capture": true,
  "gesture": true,
  "sessionRunning": false
}
```

`startFloatingSession` 保存 pending config，依次检查悬浮窗、自动模式无障碍和屏幕捕获。`onResume()` 继续 pending 流程；全部满足后启动服务、打开微信并清除 pending config。

- [ ] **Step 3: 接入 App 内设置页**

`nativeBridge.js` 新增：

```js
export function startFloatingSession(settings) {
  return window.AndroidBridge?.startFloatingSession(JSON.stringify(settings)) !== false;
}

export function stopFloatingSession() {
  window.AndroidBridge?.stopFloatingSession();
}
```

运行台 Android 原生环境中的主按钮文案改为“启用悬浮识题”。点击时发送设置快照，不再启动 WebView 后台 100 题循环。浏览器环境保留静态展示，不新增行为。

- [ ] **Step 4: 停止入口**

App 的停止按钮和前台通知停止 action 都调用 `ScreenCaptureService.stop(context)`；服务停止后悬浮层立即移除。

- [ ] **Step 5: 验证**

运行 `npm test`、`npm run build`、`node scripts/sync-android-assets.mjs` 和 Android `assembleDebug`。

---

### Task 6: 完整回归、APK 生成与真机检查清单

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `README.md`
- Output: `outputs/TiJing-floating-debug.apk`

- [ ] **Step 1: 更新 Android 版本**

```kotlin
versionCode = 2
versionName = "0.2.0"
```

- [ ] **Step 2: 全量自动验证**

```bash
npm test
npm run build
node scripts/sync-android-assets.mjs
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

预期：所有 Node/Kotlin 测试通过，APK 构建成功。

- [ ] **Step 3: APK 校验**

```bash
apksigner verify --verbose app-debug.apk
aapt2 dump badging app-debug.apk
unzip -l app-debug.apk
```

确认：包名 `com.futureyyy.tijing`、版本 `0.2.0`、应用名 `题镜`、launcher icon、Overlay/MediaProjection/Accessibility service 均存在，APK 中不包含 API Key。

- [ ] **Step 4: 复制交付物并记录哈希**

```bash
cp android/app/build/outputs/apk/debug/app-debug.apk outputs/TiJing-floating-debug.apk
sha256sum outputs/TiJing-floating-debug.apk
```

- [ ] **Step 5: 真机检查**

设备接入后执行：

```bash
adb -P 5038 install -r outputs/TiJing-floating-debug.apk
adb -P 5038 logcat -c
adb -P 5038 shell monkey -p com.futureyyy.tijing 1
```

依次验证悬浮权限、截图授权、微信悬浮按钮、手动两题、自动三种题型、暂停/停止和第 100 题提交边界。记录 `QuizWebView`、`QuizRunner`、`QuizOverlay` 日志。
