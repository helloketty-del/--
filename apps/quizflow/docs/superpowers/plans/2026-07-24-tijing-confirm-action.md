# 题镜确认按钮流程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 自动模式在所有题型选择答案后点击“确认”，第 100 题确认后停在最终提交页。

**Architecture:** 在模型结果中增加 `confirm_box`，将原生协调器检查点从下一题动作改为确认动作。无坐标时由无障碍服务按“确认”文字定位，现有帧签名负责确认后的换页检测。

**Tech Stack:** Kotlin、Android AccessibilityService、React、Node Test、JUnit 4

---

### Task 1: JSON 协议与动作计划

**Files:**
- Modify: `android/app/src/main/java/com/futureyyy/tijing/QuizModelParser.kt`
- Test: `android/app/src/test/java/com/futureyyy/tijing/QuizModelParserTest.kt`

- [x] 先新增失败测试，断言 `confirm_box` 被解析并进入动作计划。
- [x] 运行 `:app:testDebugUnitTest`，确认测试因字段缺失失败。
- [x] 在模型结果和动作计划中增加 `confirmBox`，保留 `nextBox` 解析兼容。
- [x] 重新运行解析器测试并确认通过。

### Task 2: 原生确认状态机

**Files:**
- Modify: `android/app/src/main/java/com/futureyyy/tijing/QuizRunCoordinator.kt`
- Modify: `android/app/src/main/java/com/futureyyy/tijing/ScreenCaptureService.kt`
- Test: `android/app/src/test/java/com/futureyyy/tijing/QuizRunCoordinatorTest.kt`

- [x] 新增单选、多选、第 100 题的确认动作失败测试。
- [x] 将检查点改为 `ANSWERS -> CONFIRM_BASELINE -> CONFIRM -> PAGE_POLL`。
- [x] 选完答案后截取确认前基线，避免选中样式触发换题误判。
- [x] 将手势接口改为 `tapConfirm`，缺少坐标时按文字“确认”定位。
- [x] 第 100 题在确认并检测到稳定页面后进入手动提交状态。
- [x] 运行协调器测试并确认暂停、重试和取消行为保持通过。

### Task 3: 内嵌配置与兼容执行层

**Files:**
- Modify: `src/lib/settings.js`
- Modify: `src/lib/quizEngine.js`
- Modify: `src/lib/nativeBridge.js`
- Modify: `src/App.jsx`
- Test: `tests/settings.test.mjs`
- Test: `tests/quizEngine.test.mjs`
- Test: `tests/androidShell.test.mjs`

- [x] 新增 `confirm_box` 解析、动作计划和提示词契约测试。
- [x] 更新默认提示词，要求所有题型输出确认按钮坐标。
- [x] 更新兼容执行层为答案后点击确认。
- [x] 运行 `npm test` 并确认全部通过。

### Task 4: 构建与交付

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `android/app/build.gradle.kts`
- Generate: `android/app/src/main/assets/web/**`
- Generate: `outputs/TiJing-v0.3.0-confirm.apk`

- [x] 构建前端并同步 Android assets。
- [x] 运行 Kotlin 单元测试和 `assembleDebug`。
- [x] 校验 APK 版本、签名、内嵌确认按钮逻辑和密钥扫描。
