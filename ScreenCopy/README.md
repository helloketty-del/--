# 轻触取字 / ScreenCopy

Android 11+ 原生安卓小工具：可拖动、松手自动吸附最近的左右边缘的辅助触控小方块。点一下识别当前整个屏幕的可见文字，直接写入系统剪贴板。

## 安装与使用

1. 在安卓手机上安装同包提供的 `ScreenCopy-1.0.apk`。
2. 打开「轻触取字」→「开启小方块」→阅读用途说明→在系统设置中手动开启同名无障碍服务。
3. 回到需要取字的页面，点一下小方块。绿色反馈及「已复制」提示表示完成，可直接在输入框长按粘贴。
4. 按住小方块可拖动；松开自动靠边。位置会保留，横竖屏切换按相对高度恢复。
5. 长按不移动可打开应用设置，点「停止小方块」会关闭无障碍服务。

如果 Android 13+ 提示「受限制的设置」，在系统「应用信息 → 轻触取字 → 右上角菜单」中查找「允许受限制的设置」，再手动开启服务。品牌系统可能使用不同名称。若后台被清理，需要重新开启服务；可在系统电池管理中允许后台运行。

## 截图与文字如何处理

- 无障碍截图 API 只在你点按时调用，不监听界面内容或自动操作其他应用。
- 小方块先隐藏，再截取当前主屏；图像只进入内存 Bitmap 和 OCR，不创建截图文件，不写相册，无需先保存再删除。
- 内置 ML Kit 中文模型，在本机识别。应用合并清单明确移除网络权限；不依赖远程 API 或 Google Play 服务下载模型。
- 硬件图像缓冲区在转换后关闭，Bitmap 在 OCR 任务结束时释放；没有图片缓存，也不记录识别结果历史。
- 结果直接覆盖系统剪贴板，保留换行。系统键盘或剪贴板管理器可能保留你复制的文字，应用不控制其历史。
- 未识别到文字、截图或 OCR 失败时不会清空或覆盖原剪贴板。
- 仅保存小方块的位置；应用禁止 Android 自动备份。

## 范围与限制

- 仅识别本次屏幕可见内容；不会自动滚动，也不会读取整本书或整个网页。
- 页面若设置系统安全保护，截图可能失败或部分内容为空。不绕过系统限制。
- 文字过小、模糊、艺术字体、手写、复杂分栏及复杂表格可能识别或排序不准确，不能保证全对。
- 折叠屏、外接屏、悬浮键盘、厂商后台管理等情况仍需在实际设备验证；截取默认主屏。
- 本版本是开发签名 APK，用于个人安装测试；未进行 Google Play 发布。

## 构建

需要 JDK 17、Android SDK 35、Gradle 8.9；Android Gradle Plugin 8.7.3。

在 Android Studio 打开本目录并同步，或配置 `ANDROID_HOME` 后运行：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows 使用 `gradlew.bat`。首次构建需要网络下载 Gradle、构建依赖及内置 OCR 模型；安装后识别不需要网络。源码中不包含开发机 SDK 路径或签名私钥。自行重编译会生成不同签名，覆盖原 APK 可能需先卸载。

## 验证

已提供边界约束、自动靠边和横竖屏位置恢复的 JUnit 测试。构建与静态检查的实际执行结果见同包的 `VALIDATION.md`。自动检查不代表真机功能已验证。

建议首次在手机上验证：中文/英文页面取字、长按粘贴、拖动靠边、横竖屏、重复点按、无文字页面、受保护页面、停止服务后悬浮块消失。

## 技术依据

- Android AccessibilityService.takeScreenshot（API 30）：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)
- ML Kit Android Text Recognition v2：https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Android 受限制的设置：https://support.google.com/android/answer/12623953

版权所有：该应用为本次需求生成。第三方 ML Kit、AndroidX、Google SDK 及模型仍适用各自许可和条款。
