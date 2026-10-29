<div align="center">

# 题镜 · TiJing

**搜题 / 答题 / 刷题用的端侧识题助手 —— 截图 → AI 读题（屏幕 OCR + 视觉模型）→ 匹配「你自己的」题库 → 答案悬浮在屏幕之上。**

[English](README.md) · [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84.svg)
![Release](https://img.shields.io/github/v/release/futureYYY/TiJing?color=orange)

<img src="docs/screenshots/01-console.png" width="240" alt="运行台" /> <img src="docs/screenshots/02-run-history.png" width="240" alt="运行记录" /> <img src="docs/screenshots/03-model-settings.png" width="240" alt="模型设置" />

</div>

---

## ✨ 这是什么

**题镜（TiJing）** 是一个基于 Android 无障碍服务 + 屏幕采集的答题辅助工具，面向小程序 / 网页上的长列表刷题场景：截取当前题目 → 交给任意 OpenAI 兼容的视觉模型读题干 → 在**你自备的题库**里检索 → 把答案渲染成悬浮卡片；必要时还能自动点选正确答案并确认。

由三部分组成：

| 模块 | 技术栈 | 职责 |
|---|---|---|
| **Android App** | Kotlin、AccessibilityService、MediaProjection | 截屏、悬浮层、手势执行 |
| **网页控制台** | Vite + React | 配置、提示词编辑、题库管理、运行日志 |
| **本地桥** | Node（`server.mjs`） | 通过 WebSocket 连接浏览器控制台与设备 |

全部**本地运行**：没有云端后端、不用登录、无埋点。

## 🎯 功能特性

- **答案悬浮层** —— 答案卡浮在题目页上方，不抢占焦点。
- **三种模式** —— `自动刷题`（自动推进）/ `手动辅助`（只提示）/ `采集模式`（批量收题干建库）。
- **本地题库匹配** —— 归一化精确匹配 + 关键词模糊匹配，命中就**完全不调用模型**。
- **视觉兜底** —— 库里没有的题，用你配置的视觉模型读图，返回答案与可点击区域。
- **采集模式** —— 一键「下一题」节奏，边翻页边把你的题库养起来。
- **网页控制台 + 实时日志** —— 调提示词 / 端点 / 超时，逐条查看运行事件。

## ⬇️ 下载

**[⤓ TiJing-0.7.7-open.apk](https://github.com/futureYYY/TiJing/releases/latest/download/TiJing-0.7.7-open.apk)** · Android 8.0+（API 26）· 约 0.9 MB

```
SHA-256  3f4988b2fe4c5eaab22c4e604468c9c99114a0ce3b522611601b2b9a0fcff20f
```

> **发布的 APK 不含任何题库、不含任何 API Key。** 模型端点和题库都由你自己提供，见下方「配置」。

## 🚀 快速开始

### 我只想用

1. 安装 APK 并打开。
2. 按提示授予 **无障碍**、**悬浮窗**、**通知** 权限，然后启动**屏幕采集**。
3. 进入 **模型设置**，填写你的 接口地址 / 模型名 / API Key。
4. 准备题库：导入 JSON 题库，或切到 **采集模式** 边刷边收题干。
5. 切到 **手动辅助** 或 **自动刷题**，开始运行。

### 我要自己构建

前置：Node 22+、JDK 21、Android SDK 35。

```bash
# 1) 前端控制台 -> dist/ -> Android assets
npm install
npm run build
node scripts/sync-android-assets.mjs

# 2) Android release 出包
export JAVA_HOME=/path/to/jdk-21
export ANDROID_SDK_ROOT=/path/to/android-sdk
./gradlew -p android :app:assembleProdRelease   # 或用你本地的 gradle 8.7+

# 可选：release 签名，写入 android/local.properties（切勿提交）
# RELEASE_STORE_FILE=/abs/path/release.jks
# RELEASE_STORE_PASSWORD=...
# RELEASE_KEY_ALIAS=...
# RELEASE_KEY_PASSWORD=...
```

产物：`android/app/build/outputs/apk/prod/release/app-prod-release.apk`

跑测试：

```bash
npm test                       # 前端 / 共享逻辑
./gradlew -p android :app:testProdDebugUnitTest
```

## 🔑 配置

题镜遵循 **OpenAI 兼容**的 `POST {base}/chat/completions` 协议（带 `image_url` 附件）。任何实现了该协议的端点都能用：

| 服务商 | Base URL | 示例模型 |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | *你的接入点 ID* |
| Ollama（本地） | `http://127.0.0.1:11434/v1` | `llava` |
| 自建网关 | `https://your-gateway/v1` | *你的模型* |

API Key **只存在本机**（设备端 / 浏览器本地存储），除发送给你自己配置的端点外，不会外传。

网页控制台也可用 `.env.local` 预设（复制 `.env.example`）：

```bash
QUIZ_API_URL=https://api.openai.com/v1
QUIZ_API_KEY=
QUIZ_MODEL=gpt-4o-mini
```

## 🏗 工作原理

```
┌──────────────┐   截图   ┌──────────────────┐
│ 题目页       │─────────▶│ 无障碍 + 屏幕采集 │
│（小程序/网页）│◀─────────│   前台服务        │
└──────────────┘  点击手势 └────────┬─────────┘
                                    │ 题干
                         ┌──────────▼──────────┐
                         │ 本地题库匹配器      │ 命中 ─▶ 直接出答案（不调模型）
                         │ 精确 / 关键词       │
                         └──────────┬──────────┘
                                    │ 未命中
                         ┌──────────▼──────────┐
                         │ 视觉模型（BYOK）    │
                         │ OpenAI 兼容协议     │
                         └──────────┬──────────┘
                                    │ 答案 + 坐标
                         ┌──────────▼──────────┐
                         │ 悬浮层 / 自动点击   │
                         └─────────────────────┘
```

- `QuizAccessibilityService` 只在目标应用前台时**串行**执行手势。
- `QuizRunCoordinator` 是状态机：翻页检测、重试、暂停、无上限题号递增。
- 网页控制台经本地 Node 桥（`server.mjs`，WebSocket）与设备通信，**不出局域网**。

## ❓ 为什么没有内置题库？

这是有意设计的：题库属于**用户自己的内容**。每个人要用的题目都不一样，题库通常也不是项目可以代为分发的东西。因此本仓库只带一个**空**的 `question-bank.json` 占位（`{"questions": []}`），发布的 APK 同样如此。

这不影响使用 —— 本工具本来就是为「用你自己的题库」设计的：

- **已有题库**：在网页控制台「题库管理」导入 JSON，或把文件推到设备 `files/question-bank.json`。
- **没有题库**：用**采集模式**边翻页边收题干，导出后补答案。

题库最小格式：

```json
{
  "questions": [
    {
      "id": "q-1",
      "question": "题干文本",
      "answer": "B",
      "answer_indices": [1],
      "options": [{ "index": 0, "text": "选项A" }, { "index": 1, "text": "选项B" }]
    }
  ]
}
```

## 🔐 隐私

- 无账号、无统计、无远程后端。
- 截图**只**发给你自己配置的端点（题库命中时根本不发）。
- API Key 仅保存在你自己的设备（本地存储 / `SharedPreferences`）。

## ⚖️ 许可与商标

代码以 [MIT 许可证](LICENSE) 开源。

**「题镜 / TiJing」名称与 Logo 不在 MIT 授权范围内。** 你可以自由 fork、修改、再分发代码，但请勿以 TiJing 的名称或品牌发布衍生版本 —— 请为你的分支改名。名称与 Logo 归作者所有。

源码内置的提示词模板已做**通用化**处理；如需业务特定措辞，请放在你自己的、不提交进仓库的配置里。

## ⚠️ 免责声明

题镜只在**你自己的设备**上做屏幕识别与点击自动化。使用方式由你负责：请遵守你所用平台的条款，并仅用于个人学习、无障碍辅助或自动化研究。

## 🤝 参与贡献

欢迎 Issue 与 PR。适合上手的贡献：更多题库导入格式、更多匹配策略、国际化、悬浮层自适应布局。

<div align="center"><sub>如果题镜帮你省了时间，点个 ⭐ 能让更多人看到它。</sub></div>
