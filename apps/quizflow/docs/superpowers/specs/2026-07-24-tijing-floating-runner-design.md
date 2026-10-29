# 题镜悬浮运行器设计规格

## 目标

将现有需要在 App 与微信之间切换的识题流程，改造成微信前台可操作的原生悬浮运行器。

- 自动模式：用户在微信内点击一次悬浮按钮，系统连续执行截图、识别、答案展示、选项点击和换题检测，完成第 100 题后停在提交前。
- 手动模式：用户每题点击一次悬浮按钮，系统截图并展示答案；用户自行答题并进入下一题，再次点击悬浮按钮。
- 截图前临时隐藏题镜悬浮层，确保模型收到的画面只有微信题目页面。

## 当前问题

当前“原生截图”从题镜 App 页面触发，截图内容包含题镜自己的模拟题预览。模型没有看到真实微信题目时，可能返回缺少 `answer_indices` 的响应，页面随后显示 `answer_indices 缺少或为空`。

现有 WebView 状态机依赖后台 JavaScript 定时器。切到微信后，系统可能限制 WebView 的计时器和执行频率，因此它不适合作为连续 100 题运行器的核心。

## 选定架构

采用全原生悬浮运行器。现有 `ScreenCaptureService` 继续作为唯一前台服务并持有 `MediaProjection`，避免新增第二个前台服务及额外的 Android 14+ 启动限制。

服务内部拆分为三个职责清晰的组件：

1. `OverlayController`
   - 使用 `WindowManager` 创建可拖动悬浮按钮和答案卡。
   - 负责显示运行状态、答案、错误、暂停和停止入口。
   - 截图前隐藏所有悬浮视图，截图完成后恢复。

2. `QuizRunCoordinator`
   - 维护自动/手动状态机、当前题号、执行代次和取消标记。
   - 编排截图、模型调用、结果校验、点击、换页检测和第 100 题结束行为。
   - 只通过接口依赖截图、模型和点击能力，不直接操作 Android View。

3. `QuizModelParser`
   - 在 Kotlin 原生层解析并校验模型 JSON。
   - 校验题型、选项编号、答案数量、答案坐标和多选题下一题坐标。
   - 将异常转换为可展示的中文错误，错误状态下不执行点击。

`MainActivity` 保留现有 App 内配置面板和权限引导。内嵌页面只作为 Android App 的本地设置界面，通过 `AndroidBridge.startFloatingSession(configJson)` 将本次运行配置交给原生层，配置包含模式、URL、模型名、提示词、API Key、超时和重试次数。浏览器预览不再作为产品或交付物继续开发。

## 配置与密钥生命周期

- URL、模型名、提示词、模式、超时和重试次数继续保存在 WebView 本地设置中。
- API Key 继续采用当前会话存储，并在启动悬浮会话时传入服务内存。
- 前台服务停止或进程退出后清除原生内存中的 API Key。
- 日志、通知、答案卡和错误卡只显示脱敏信息。

## 权限与启动流程

用户在题镜内点击“启用悬浮识题”后，按以下顺序准备运行环境：

1. 检查 `SYSTEM_ALERT_WINDOW`，缺少时打开题镜的悬浮窗权限页。
2. 自动模式检查题镜自动点击服务；需要时打开无障碍设置。
3. 请求 `MediaProjection` 屏幕捕获授权。
4. 启动 `ScreenCaptureService`，创建前台通知与悬浮按钮。
5. 打开微信，保留用户最近使用的小程序页面。
6. 悬浮按钮显示“开始识别”，等待用户在微信内主动点击。

`MainActivity.onResume()` 继续尚未完成的权限准备步骤。每一步都重新读取真实权限状态，避免依赖设置页面的返回码。

## 悬浮界面

### 悬浮按钮

- 默认位于屏幕右侧中部，可上下拖动并吸附屏幕边缘。
- 图标使用题镜现有“扫描框 + 答题勾选”图标。
- 状态文案：`开始`、`识别中`、`自动 23/100`、`已暂停`、`完成`。
- 手动模式点击一次只执行当前题。
- 自动模式第一次点击启动连续循环；运行中点击展开答案卡。

### 答案卡

- 位于状态栏下方，宽度为屏幕宽度减去 24dp，圆角不超过 8dp。
- 最多显示：题号、题型、答案字母、答案文本、置信度和一行状态。
- 手动模式保持显示，用户可点击收起。
- 自动模式展示约 1 秒后收起，悬浮按钮继续显示进度。
- 错误状态显示错误摘要以及“重试”“暂停/继续”“停止”按钮。
- 悬浮视图只占自身区域，微信其余区域保持可点击。

## 截图时序

每次识题严格执行：

1. 记录当前执行代次。
2. 隐藏悬浮按钮和答案卡。
3. 等待 180ms，使 WindowManager 完成一帧刷新。
4. 请求 `ScreenCaptureService` 的下一张新鲜帧。
5. 在后台线程生成 JPEG data URL，并计算排除状态栏与导航栏的中央内容感知指纹。
6. 恢复悬浮按钮；模型完成后再显示答案卡。

截图请求只允许一个处于等待状态。超时、停止或新执行代次开始时，旧回调失效。

## 模型数据契约

模型继续返回：

```json
{
  "question_no": 1,
  "question_type": "single",
  "question": "题干",
  "options": [
    { "index": 0, "text": "选项 A", "box": [8, 43, 84, 8] }
  ],
  "answer_indices": [0],
  "next_box": null,
  "confidence": 0.95,
  "next_action": "tap_then_wait"
}
```

校验规则：

- `question_type` 仅接受 `single`、`multiple`、`true_false`。
- `single` 与 `true_false` 只接受一个答案；`multiple` 接受一个或多个答案。
- `answer_indices` 必须引用存在的唯一选项编号。
- 自动模式要求每个答案选项都有位于 0 到 100 范围内的 `box`。
- 多选题优先使用 `next_box`，缺少时尝试按“下一题”文本查找。
- 第 100 题忽略任何下一题坐标和提交类按钮。

## 自动模式状态机

状态：

```text
READY
  -> CAPTURING
  -> RECOGNIZING
  -> SHOWING_ANSWER
  -> EXECUTING
  -> WAITING_PAGE_CHANGE
  -> READY（下一题）
  -> WAITING_MANUAL_SUBMIT（第 100 题）
```

规则：

- 用户只在 `READY` 状态第一次点击悬浮按钮。
- 每次操作前确认微信是当前前台包。
- 答案坐标校验通过后，按顺序提交无障碍点击。
- 单选/判断点击答案后等待换页；多选点击全部答案后再点击下一题。
- 换页检测使用中央内容感知指纹，排除动态状态栏；新页面需要连续两帧稳定后才推进题号。
- 任何截图、模型、JSON、点击或换页错误都会进入 `PAUSED_ERROR`，保留当前题号并停止后续动作。
- 第 100 题只点击答案，随后进入 `WAITING_MANUAL_SUBMIT`。

## 手动模式状态机

状态：

```text
READY
  -> CAPTURING
  -> RECOGNIZING
  -> SHOWING_ANSWER
  -> READY（等待用户进入下一题并再次点击）
```

规则：

- 每次悬浮按钮点击只执行一次截图和模型识别。
- 答案卡持续显示，题镜不执行任何选项点击。
- 下一次识别前比较上一题内容指纹；页面相同时提示用户确认已进入下一题，并允许用户主动重试。
- 手动模式的最终提交由用户在微信内完成。

## 点击边界

- `QuizAccessibilityService` 保持目标包校验，只在 `com.tencent.mm` 前台时接受和派发手势。
- 悬浮层、题镜 App、系统设置或其他应用处于前台时清空待执行手势。
- 停止、暂停、服务销毁或新执行代次开始时取消未派发手势。
- 悬浮答案卡处于展开状态时，自动点击前先收起卡片，避免遮挡题目坐标。

## 错误处理

- 截图授权结束：悬浮层显示“截图授权已结束”，停止会话并引导回题镜重新启用。
- 模型超时或网络错误：自动模式暂停，显示“重试”；手动模式保留悬浮按钮。
- JSON 缺字段：显示具体字段错误，例如 `answer_indices 缺少或为空`，不执行点击。
- 微信未在前台：显示“请打开微信题目页”，等待用户返回后重试。
- 页面长时间未变化：自动模式暂停，避免重复点击当前题。
- 无障碍服务失效：自动模式暂停并提供打开设置的入口。

## App 与品牌调整

- 应用名称：`题镜`
- 副标题：`悬浮识题助手`
- 无障碍服务名称：`题镜自动点击服务`
- 前台通知标题：`题镜正在运行`
- 保留现有深绿色、荧光绿、米白和橙色视觉体系及新 launcher icon。
- 运行台主操作从“开始运行”改为“启用悬浮识题”，并显示三项准备状态：悬浮窗、屏幕捕获、自动点击。

## 文件边界

计划新增：

- `android/app/src/main/java/com/futureyyy/tijing/OverlayController.kt`
- `android/app/src/main/java/com/futureyyy/tijing/QuizRunCoordinator.kt`
- `android/app/src/main/java/com/futureyyy/tijing/QuizModelParser.kt`
- `android/app/src/main/java/com/futureyyy/tijing/QuizRuntimeConfig.kt`
- `android/app/src/test/java/com/futureyyy/tijing/QuizRunCoordinatorTest.kt`
- `android/app/src/test/java/com/futureyyy/tijing/QuizModelParserTest.kt`

计划修改：

- `MainActivity.kt`：权限编排、悬浮会话桥接、微信跳转。
- `ScreenCaptureService.kt`：悬浮层生命周期、新鲜帧接口、感知指纹和协调器宿主。
- `ModelClient.kt`：增加原生回调接口并保留 WebView 连接测试接口。
- `QuizAccessibilityService.kt`：提供执行完成回调与状态查询。
- `AndroidManifest.xml`、`strings.xml`：服务、品牌和文案。
- `src/App.jsx`、`src/lib/nativeBridge.js`：只完成 Android 内嵌设置页所需的新启动入口、权限状态和停止接线，不扩展浏览器模式。
- `android/app/build.gradle.kts`：原生单元测试依赖和版本号。

## 测试与验收

自动验证：

- Kotlin 单元测试覆盖三类题型、坐标校验、第 100 题、暂停/重试、旧回调失效和手动单次识别。
- Node 测试覆盖 Android 内嵌页面 bridge、Manifest、品牌名和启动配置。
- `npm test` 与 `npm run build` 只用于生成 APK 内嵌静态资源，最终以 Android `assembleDebug` 和 APK 校验为交付标准。
- APK 通过 `apksigner verify`，`aapt2 dump badging` 显示应用名与 launcher icon。

真机验收：

1. 题镜完成权限准备后打开微信并显示悬浮按钮。
2. 截图中不包含悬浮按钮、答案卡或题镜 App 页面。
3. 手动模式连续识别两题，答案卡正确展示且微信页面仍可操作。
4. 自动模式分别验证单选、判断和多选，点击坐标与换页正确。
5. 暂停、继续、停止和模型错误均不会产生额外点击。
6. 第 100 题停在提交前。

## 本阶段范围

本阶段只支持微信 `com.tencent.mm`，总题数固定为 100，最终提交固定为人工操作。悬浮运行器不扩展到其他应用，也不加入题库、云端历史记录或账号系统。最终只交付 Android APK，不交付浏览器版本、Web 预览地址或独立 Web 应用。
