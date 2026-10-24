# 验证记录

日期：2026-10-24。

- Gradle assembleDebug、testDebugUnitTest、lintDebug：BUILD SUCCESSFUL。
- JUnit：4 项通过，0 失败，0 错误。
- Android Lint：0 项报告。
- APK Signature Scheme v2：验证通过，1 个签名者。
- 包名：com.screencopy.touch；版本：1.0.0；最低 Android 11（API 30）；目标 API 35。
- 最终 APK 已检查：无 INTERNET、ACCESS_NETWORK_STATE 和存储访问权限。
- 已确认安装包中包含中文与拉丁文字识别模型，以及 ARM64、ARMv7、x86、x86_64 本地库。
- APK ZIP 数据完整性检查通过。
- APK 大小：52,932,254 字节（52.93 MB）。
- SHA-256：`41e50234b44737dda2ce617ee20d903c8f976e98e7b3d1afe8a5546b056379f8`。

## 未完成的验证

本环境没有连接安卓手机，也没有运行安卓模拟器。尚未真机验证首次授权、悬浮块显示、拖动、屏幕截图、OCR 识别、剪贴板写入以及厂商系统后台保活。自动测试仅验证窗口位置算法，不代表已经完成端到端功能测试。

## 静态检查说明

小方块使用屏幕物理坐标，故有意使用 Gravity.LEFT；触摸监听器委托 touch()，其中实际调用 performClick()。这两项 lint 误报在对应方法上加了有说明的定点抑制，没有全局关闭检查。
