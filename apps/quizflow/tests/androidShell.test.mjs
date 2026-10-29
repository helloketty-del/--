import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const mainActivity = fs.readFileSync(
  new URL('../android/app/src/main/java/com/futureyyy/tijing/MainActivity.kt', import.meta.url),
  'utf8',
);
const viteConfig = fs.readFileSync(new URL('../vite.config.js', import.meta.url), 'utf8');
const settingsSource = fs.readFileSync(new URL('../src/lib/settings.js', import.meta.url), 'utf8');
const styles = fs.readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8');
const manifest = fs.readFileSync(new URL('../android/app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');
const modelClient = fs.readFileSync(
  new URL('../android/app/src/main/java/com/futureyyy/tijing/ModelClient.kt', import.meta.url),
  'utf8',
);
const accessibilityService = fs.readFileSync(
  new URL('../android/app/src/main/java/com/futureyyy/tijing/QuizAccessibilityService.kt', import.meta.url),
  'utf8',
);
const screenCaptureService = fs.readFileSync(
  new URL('../android/app/src/main/java/com/futureyyy/tijing/ScreenCaptureService.kt', import.meta.url),
  'utf8',
);
const runCoordinator = fs.readFileSync(
  new URL('../android/app/src/main/java/com/futureyyy/tijing/QuizRunCoordinator.kt', import.meta.url),
  'utf8',
);
const nativeBridge = fs.readFileSync(new URL('../src/lib/nativeBridge.js', import.meta.url), 'utf8');
const appSource = fs.readFileSync(new URL('../src/App.jsx', import.meta.url), 'utf8');
const overlayControllerPath = new URL(
  '../android/app/src/main/java/com/futureyyy/tijing/OverlayController.kt',
  import.meta.url,
);
const strings = fs.readFileSync(
  new URL('../android/app/src/main/res/values/strings.xml', import.meta.url),
  'utf8',
);
const androidAssetIndex = fs.readFileSync(
  new URL('../android/app/src/main/assets/web/index.html', import.meta.url),
  'utf8',
);
const simulatorManifest = fs.readFileSync(
  new URL('../android/simulator/src/main/AndroidManifest.xml', import.meta.url),
  'utf8',
);
const simulatorActivity = fs.readFileSync(
  new URL('../android/simulator/src/main/java/com/futureyyy/tijingsimulator/MainActivity.kt', import.meta.url),
  'utf8',
);
const simulatorQuestionBank = fs.readFileSync(
  new URL('../android/simulator/src/main/java/com/futureyyy/tijingsimulator/QuestionBank.kt', import.meta.url),
  'utf8',
);
const simulatorBuildGradle = fs.readFileSync(
  new URL('../android/simulator/build.gradle.kts', import.meta.url),
  'utf8',
);

test('Android serves bundled ES modules from a same-origin HTTPS URL', () => {
  assert.doesNotMatch(mainActivity, /loadUrl\("file:\/\//);
  assert.match(mainActivity, /https:\/\/quiz\.local\/index\.html/);
  assert.match(mainActivity, /shouldInterceptRequest/);
  assert.match(mainActivity, /assets\.open\("web\/\$relativePath"\)/);
  assert.match(mainActivity, /shouldOverrideUrlLoading/);
  assert.match(mainActivity, /blockedResource\(\)/);
  assert.match(mainActivity, /url\.host == LOCAL_APP_HOST/);
});

test('Android excludes private WebView state from backup and device transfer', () => {
  assert.match(manifest, /android:dataExtractionRules="@xml\/data_extraction_rules"/);
  assert.match(manifest, /android:fullBackupContent="false"/);
  const rulesPath = new URL('../android/app/src/main/res/xml/data_extraction_rules.xml', import.meta.url);
  assert.equal(fs.existsSync(rulesPath), true, 'data extraction rules should exist');
  const rules = fs.readFileSync(rulesPath, 'utf8');
  assert.match(rules, /<cloud-backup>[\s\S]*<exclude domain="root" path="\."/);
  assert.match(rules, /<device-transfer>[\s\S]*<exclude domain="root" path="\."/);
});

test('production bundle targets the oldest supported Android WebView syntax', () => {
  assert.match(viteConfig, /target:\s*['"]es2017['"]/);
});

test('prompt rendering avoids unsupported replaceAll calls', () => {
  assert.doesNotMatch(settingsSource, /\.replaceAll\(/);
});

test('Android first paint has no remote font dependency', () => {
  assert.doesNotMatch(styles, /@import\s+url\(['"]https:\/\/fonts\.googleapis\.com/);
});

test('answer choices render directly below model confidence', () => {
  const readoutStart = appSource.indexOf('<div className="answer-readout">');
  const confidence = appSource.indexOf('className="confidence"', readoutStart);
  const answers = appSource.indexOf('className="answer-chips"', confidence);
  const questionMeta = appSource.indexOf('<div className="answer-head">', answers);

  assert.notEqual(readoutStart, -1, 'answer readout wrapper should exist');
  assert.ok(confidence > readoutStart, 'confidence should be inside the answer readout');
  assert.ok(answers > confidence, 'answers should follow confidence');
  assert.ok(questionMeta > answers, 'question metadata should follow the confidence and answers');
  assert.match(styles, /\.answer-readout\s*\{[^}]*display:\s*grid/);
});

test('Android upload button opens the system image picker', () => {
  assert.match(mainActivity, /settings\.allowContentAccess = true/);
  assert.match(mainActivity, /onShowFileChooser/);
  assert.match(mainActivity, /REQUEST_FILE_CHOOSER/);
  assert.match(mainActivity, /FileChooserParams\.parseResult/);
});

test('Android package provides adaptive and legacy launcher icons', () => {
  assert.match(manifest, /android:icon="@mipmap\/ic_launcher"/);
  assert.match(manifest, /android:roundIcon="@mipmap\/ic_launcher_round"/);
  for (const path of [
    '../android/app/src/main/res/drawable/ic_launcher_foreground.xml',
    '../android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    '../android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml',
    '../android/app/src/main/res/mipmap-mdpi/ic_launcher.png',
    '../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png',
  ]) {
    assert.equal(fs.existsSync(new URL(path, import.meta.url)), true, `${path} should exist`);
  }
});

test('Android native runner exposes asynchronous model and gesture APIs', () => {
  assert.match(modelClient, /fun requestAsync\(/);
  assert.match(modelClient, /fun interface ModelRequestHandle/);
  assert.match(modelClient, /override fun cancel\(\)/);
  assert.match(modelClient, /future\?\.cancel\(true\)/);
  assert.match(modelClient, /getAndSet\(null\)\?\.disconnect\(\)/);
  assert.match(accessibilityService, /fun tapNormalizedAsync\(/);
  assert.match(accessibilityService, /fun tapTextAsync\(/);
  assert.match(accessibilityService, /fun isWeChatForeground\(/);
  assert.match(accessibilityService, /fun isTargetForeground\(targetPackage: String\)/);
  assert.match(accessibilityService, /fun foregroundApplicationPackage\(\): String\?/);
  assert.match(accessibilityService, /findTargetRoot\(targetPackage: String\)/);
  assert.match(accessibilityService, /findForegroundApplicationRoot\(\)/);
  assert.match(accessibilityService, /windows/);
  assert.match(accessibilityService, /performAction\(AccessibilityNodeInfo\.ACTION_CLICK\)/);
  assert.match(accessibilityService, /private fun recycleNodes\(/);
  assert.doesNotMatch(accessibilityService, /matches\.firstOrNull \{ it\.isVisibleToUser \}/);
  assert.match(accessibilityService, /pending\.completion\(succeeded\)/);
  assert.doesNotMatch(accessibilityService, /succeeded && isWeChatForeground\(\)/);
  assert.match(accessibilityService, /SIMULATOR_TARGET_PACKAGE/);
  assert.match(
    accessibilityService,
    /event\.eventType == AccessibilityEvent\.TYPE_WINDOW_STATE_CHANGED/,
  );
});

test('Android foreground service hosts the native overlay runner', () => {
  assert.equal(fs.existsSync(overlayControllerPath), true, 'OverlayController.kt should exist');
  const overlayController = fs.readFileSync(overlayControllerPath, 'utf8');
  assert.match(manifest, /android\.permission\.SYSTEM_ALERT_WINDOW/);
  assert.match(screenCaptureService, /OverlayController/);
  assert.match(screenCaptureService, /QuizRunCoordinator/);
  assert.match(screenCaptureService, /captureFreshFrame/);
  assert.match(screenCaptureService, /ACTION_STOP/);
  assert.match(overlayController, /TYPE_APPLICATION_OVERLAY/);
  assert.match(overlayController, /FLAG_NOT_FOCUSABLE/);
  assert.match(overlayController, /hideForCapture/);
  assert.match(overlayController, /180L/);
  assert.match(
    overlayController,
    /addView\(title,[\s\S]*addView\(status,[\s\S]*addView\(body,/,
    'floating answer card should place confidence before the answer body',
  );
  assert.match(overlayController, /fun show\(\): Boolean/);
  assert.match(overlayController, /return bubbleView != null && cardView != null/);
  assert.match(
    screenCaptureService,
    /if \(!overlay\.show\(\)\)[\s\S]*terminateCaptureSession\("悬浮窗创建失败/,
  );
  assert.match(screenCaptureService, /tapConfirmationAsync\(box, attempt/);
  assert.match(screenCaptureService, /CONFIRM_SETTLE_DELAY_MS/);
  assert.match(screenCaptureService, /private var activeTargetPackage: String\? = null/);
  assert.match(screenCaptureService, /resolveTargetPackage\(service: QuizAccessibilityService\)/);
  assert.match(screenCaptureService, /targetUnavailableMessage\(\): String = "请保持题目页在前台"/);
  assert.doesNotMatch(screenCaptureService, /tapTextAsync\("下一题"/);
  assert.match(runCoordinator, /AutoCheckpointStage\.CONFIRM_BASELINE/);
  assert.match(runCoordinator, /AutoCheckpointStage\.CONFIRM/);
  assert.match(runCoordinator, /gestureGateway\.tapConfirmAttempt\(plan\.confirmBox, confirmTapAttempts\)/);
  assert.match(runCoordinator, /MAX_CONFIRM_TAP_ATTEMPTS = 3/);
  assert.match(runCoordinator, /CONFIRM_RETRY_DELAY_MS = 350L/);
});

test('Android shell orchestrates floating runner permissions and session lifecycle', () => {
  assert.match(mainActivity, /fun startFloatingSession\(configJson: String\): Boolean/);
  assert.match(mainActivity, /QuizRuntimeConfig\.fromJson\(configJson\)/);
  assert.match(mainActivity, /fun stopFloatingSession\(\)/);
  assert.match(mainActivity, /fun floatingCapabilities\(\): String/);
  assert.match(mainActivity, /Settings\.canDrawOverlays/);
  assert.match(mainActivity, /openTargetApp\(targetApp: QuizTargetApp\)/);
  assert.match(mainActivity, /targetApp\.isAutomatic/);
  assert.match(mainActivity, /moveTaskToBack\(true\)/);
  assert.match(manifest, /<package android:name="com.futureyyy.tijingsimulator"\s*\/>/);
  assert.match(mainActivity, /Settings\.ACTION_MANAGE_OVERLAY_PERMISSION/);
  assert.match(mainActivity, /Settings\.ACTION_ACCESSIBILITY_SETTINGS/);
  assert.match(mainActivity, /AccessibilityManager/);
  assert.match(mainActivity, /MediaProjectionConfig\.createConfigForDefaultDisplay\(\)/);
  assert.match(mainActivity, /ScreenCaptureService\.startSession/);
  assert.match(mainActivity, /isCaptureReady\(\)/);
  assert.match(mainActivity, /isSessionRunning\(\)/);
});

test('quiz simulator is a dedicated accessibility-friendly target app', () => {
  assert.match(simulatorBuildGradle, /applicationId = "com.futureyyy.tijingsimulator"/);
  assert.match(simulatorBuildGradle, /versionCode = 2/);
  assert.match(simulatorBuildGradle, /versionName = "1\.1\.0"/);
  assert.match(simulatorManifest, /android:label="题库模拟器"/);
  assert.match(simulatorManifest, /android:icon="@drawable\/ic_simulator_launcher"/);
  assert.match(simulatorActivity, /contentDescription = "确认"/);
  assert.match(simulatorActivity, /root\.addView\(buildActionPanel\(\)\)/);
  assert.match(simulatorActivity, /setOnApplyWindowInsetsListener/);
  assert.match(simulatorActivity, /minHeight = dp\(56\)/);
  assert.match(simulatorActivity, /visibleQuestionNumber/);
  assert.match(simulatorQuestionBank, /QUESTION_COUNT = 100/);
  assert.match(simulatorActivity, /PAGE_SWITCH_DELAY_MS = 520L/);
  assert.match(simulatorActivity, /submitCurrentQuestion\(\)/);
  assert.match(simulatorQuestionBank, /SimulatorQuestionType\.SINGLE/);
  assert.match(simulatorQuestionBank, /SimulatorQuestionType\.MULTIPLE/);
  assert.match(simulatorQuestionBank, /SimulatorQuestionType\.TRUE_FALSE/);
  assert.match(simulatorQuestionBank, /SimulatorQuestionType\.INDEFINITE/);
  assert.match(simulatorQuestionBank, /randomSession/);
  assert.match(simulatorActivity, /loadFromAsset/);
  assert.match(simulatorActivity, /sessionSeed/);
  assert.match(simulatorQuestionBank, /question-bank\.json/);
});

test('floating session waits for capture readiness before reporting running', () => {
  assert.match(screenCaptureService, /pendingSessionConfigJson/);
  assert.match(screenCaptureService, /maybeStartPendingRunner\(\)/);
  assert.match(screenCaptureService, /if \(!isCaptureReady\(\)\) return/);
  assert.match(
    screenCaptureService,
    /virtualDisplay = createdProjection\.createVirtualDisplay[\s\S]*mainHandler\.post \{ maybeStartPendingRunner\(\) \}/,
  );
  assert.match(
    screenCaptureService,
    /!terminalStopScheduled && runtimeConfig != null && \(coordinator != null \|\| sweep != null \|\| runtimeConfig\?\.gestureTestOnly == true \|\| runtimeConfig\?\.mode == QuizMode\.CAPTURE\)/,
  );
  assert.match(screenCaptureService, /mainHandler\.removeCallbacks\(terminalStopRunnable\)/);
  assert.match(
    screenCaptureService,
    /terminalStopScheduled = true[\s\S]*coordinator = null[\s\S]*runtimeConfig = null/,
  );
});

test('notification return reuses MainActivity and preserves service listeners', () => {
  assert.match(manifest, /android:launchMode="singleTask"/);
  assert.match(mainActivity, /private val captureFrameListener/);
  assert.match(mainActivity, /private fun bindServiceListeners\(\)/);
  assert.match(mainActivity, /ScreenCaptureService\.listener === captureFrameListener/);
  assert.match(screenCaptureService, /Intent\.FLAG_ACTIVITY_CLEAR_TOP/);
  assert.match(screenCaptureService, /Intent\.FLAG_ACTIVITY_SINGLE_TOP/);
});

test('embedded settings page starts and stops the native floating session', () => {
  assert.match(nativeBridge, /export function startFloatingSession\(settings\)/);
  assert.match(nativeBridge, /AndroidBridge|startFloatingSession/);
  assert.match(nativeBridge, /export function stopFloatingSession\(\)/);
  assert.match(nativeBridge, /export function getFloatingCapabilities\(\)/);
  assert.match(nativeBridge, /export function tapConfirmButton\(box = null, targetApp = 'auto'\)/);
  assert.match(nativeBridge, /bridge\.tapText\('确认', targetApp\)/);
  assert.match(appSource, /startFloatingSession\(\{\.\.\.sanitizeSettings\(settingsRef\.current\), apiKey/);
  assert.match(appSource, /tapConfirmButton\(plan\.confirmBox, settingsRef\.current\.targetApp\)/);
  assert.match(appSource, /stopFloatingSession\(\)/);
  assert.match(appSource, /启用悬浮识题/);
  assert.match(appSource, /floatingSessionPreparing/);
  assert.match(appSource, /悬浮窗.*已就绪/);
  assert.match(appSource, /截图.*待授权/);
  assert.match(appSource, /自动点击.*待开启/);
  assert.match(appSource, /const currentSettings = settingsRef\.current/);
  assert.match(appSource, /const safe = sanitizeSettings\(currentSettings\)/);
  assert.match(appSource, /className="prompt-actions"/);
  assert.match(appSource, /保存提示词/);
  assert.match(appSource, /API Key <span>本机保存<\/span>/);
  assert.doesNotMatch(appSource, /API Key <span>仅当前会话<\/span>/);
  assert.match(appSource, /自动识别当前题目页/);
  assert.match(nativeBridge, /openTargetApp\(targetApp = 'auto'\)/);
});

test('continuous auto mode has no fixed question limit', () => {
  assert.doesNotMatch(runCoordinator, /WAITING_MANUAL_SUBMIT|totalQuestions/);
  assert.match(runCoordinator, /questionNo \+= 1/);
  assert.doesNotMatch(appSource, /waiting_manual_submit|100 题已完成|\/100/);
  assert.match(appSource, /持续自动执行，直到手动停止/);
  assert.match(appSource, /自动模式不设置固定题数上限/);
});

test('APK embedded assets contain the current floating-session entry', () => {
  const scriptMatch = androidAssetIndex.match(/src="\.\/assets\/([^"]+\.js)"/);
  assert.ok(scriptMatch, 'embedded index should reference a JavaScript bundle');
  const bundleUrl = new URL(`../android/app/src/main/assets/web/assets/${scriptMatch[1]}`, import.meta.url);
  assert.equal(fs.existsSync(bundleUrl), true, 'referenced embedded JavaScript bundle should exist');
  const bundle = fs.readFileSync(bundleUrl, 'utf8');
  assert.match(bundle, /startFloatingSession/);
  assert.match(bundle, /floatingCapabilities/);
  assert.match(bundle, /启用悬浮识题/);
  assert.match(bundle, /保存提示词/);
  assert.match(bundle, /confirm_box/);
  assert.match(bundle, /确认按钮点击失败/);
});

test('Android native shell uses the derivative brand', () => {
  assert.match(manifest, /android:label="@string\/app_name"/);
  assert.match(strings, /<string name="app_name">练题流<\/string>/);
  assert.match(strings, /<string name="accessibility_service_label">练题流自动点击服务<\/string>/);
  assert.match(strings, /<string name="foreground_service_title">练题流正在运行<\/string>/);
});
