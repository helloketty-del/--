# 题镜 Android

原生层由以下组件组成：

- `MainActivity`：App 内配置界面和权限编排。
- `ScreenCaptureService`：持有 `MediaProjection`、悬浮层和运行协调器的唯一前台服务。
- `OverlayController`：微信上方的悬浮按钮、答案卡和错误操作。
- `QuizRunCoordinator`：手动/自动状态机、持续换页检测、暂停、重试和无限题号递增。
- `QuizAccessibilityService`：仅在微信 `com.tencent.mm` 前台执行串行手势。

从项目根目录构建：

```bash
npm run build
node scripts/sync-android-assets.mjs
export JAVA_HOME="$PWD/.tooling/jdk/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_SDK_ROOT="$PWD/.tooling/android-sdk"
export GRADLE_USER_HOME="$PWD/.tooling/gradle-home"
.tooling/gradle/gradle-8.7/bin/gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 输出：`android/app/build/outputs/apk/debug/app-debug.apk`。
