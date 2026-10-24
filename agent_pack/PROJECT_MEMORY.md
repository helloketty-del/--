# PROJECT_MEMORY（唯一事实源，最高优先级）

> 优先级：`PROJECT_MEMORY.md` > `ADR/决策记录` > `代码现状` > `episodes`  
> 冲突处理：先更新本文件（含 MEMORY_PATCH）再改代码/文档。

最后更新：2026-10-24

## Project Summary
- 本仓库保留 AgentPack 协作模板，并新增独立 Android 项目 `ScreenCopy/`。
- 轻触取字面向 Android 11+：拖动悬浮小方块、松手吸边、点按截取当前可见屏幕、本地 OCR 并写入剪贴板。
- 截图只在内存中处理；不保存相册图片；识别失败不覆盖原剪贴板。
- 无障碍服务需要用户在系统设置中手动开启；不读取窗口控件、不自动操作其他应用。
- 本轮仅提交已有 1.0.0 源码和构建说明，不修改其应用行为，也不提交 APK、SDK、签名私钥或密钥。

## Constraints & Assumptions
> 任何信息不足/不确定点必须记录在此，并包含影响与验证计划。格式：`A-xxx`。

| ID | Statement | Impact | Validation Plan | Status | Date |
|---|---|---|---|---|---|
| A-001 | 本仓库在 AgentPack 初始化时（2026-03-01）目录为空且非 Git 仓库，无法从代码推断技术栈。 | Makefile/CI/dev/test/lint 仅能提供占位与 TODO 指引；需要后续补全真实命令与结构。 | 确认本目录为目标仓库根目录；如应存在代码则迁移/恢复后更新本文件与入口脚本。 | OPEN | 2026-03-01 |
| A-002 | 已导入 `ScreenCopy/`：Java 17、Android SDK 35、Gradle 8.9、AGP 8.7.3。 | 子项目构建、单元测试与 lint 命令已明确；根目录 CI 仍只验证 AgentPack。 | 依据构建清单及 `ScreenCopy/VALIDATION.md`，使用子项目命令验证。 | RESOLVED | 2026-10-24 |
| A-003 | ScreenCopy 为本地安卓工具，没有 HTTP 服务或新对外 API；原 OpenAPI 占位保留。 | 本轮无 API 契约变更。 | 核对清单与源码，不新增网络服务。 | RESOLVED | 2026-10-24 |
| A-004 | APK 原构建已通过 4 项 JUnit 与 Android Lint，但尚未真机验证。 | 悬浮、截屏、OCR、剪贴板和厂商后台管理仍需实际设备确认。 | 按 ScreenCopy/README.md 的真机清单验证；本轮恢复源码只做完整性和仓库检查，不重复声称执行安卓构建。 | OPEN | 2026-10-24 |

## Architecture
### Tech Stack
- ScreenCopy：Java 17、Android SDK 35（minSdk 30 / targetSdk 35）、AGP 8.7.3、Gradle 8.9、内置 ML Kit 中文 OCR 16.0.1；无业务数据库或服务端。

### Modules
- `ScreenCopy/app/`：MainActivity 授权引导、CaptureService 截屏与剪贴板、BubbleView 悬浮控件、DockMath 边界/吸附算法。
- `agent_pack/`：既有协作记录与自检，与安卓源码分开维护。

### Data Flow
- 用户点击悬浮块 → 隐藏浮块 → AccessibilityService 截屏 → 内存 Bitmap → 本地 OCR → 文字写入系统剪贴板 → 释放 Bitmap/硬件缓冲；不写截图文件。

## Interfaces & Contracts
### External Services
- 运行时 OCR 使用内置模型，无远程 API、无需 API Key；合并清单移除 INTERNET 与 ACCESS_NETWORK_STATE。
- 首次构建通过 Google Maven / Maven Central / Gradle 官方分发下载构建依赖；依赖更新需重新验证。

### API Contracts
- 契约锁文件：`agent_pack/schema/openapi.yaml`
- TODO：若存在多套契约（内外部/版本），在此建立索引与路径。

### Contract Lock（硬规则）
- 任何 API/契约变更（新增/修改/删除 endpoints、请求/响应、错误码、鉴权要求）必须：
  1. 更新 `agent_pack/schema/openapi.yaml`
  2. 更新 `Change Control -> Changelog`
  3. 必要时新增/更新 ADR（记录为何变更与权衡）

## Data Model
- TODO：核心实体、关系、关键约束、迁移策略。

## Engineering & Run
### Repo Structure（真实结构与映射）
> 变更目录结构必须触发 MEMORY_PATCH 并回写本节。

当前顶层结构（2026-10-24）：
- `ScreenCopy/`：轻触取字安卓独立项目，含源码、Gradle Wrapper、JUnit 测试、README 与 VALIDATION。
- `CODEX_START.md`：每轮入口与输出协议
- `agent_pack/`：可复用 AgentPack（硬规则、记忆库、runbook、prompts、schema、episodes）
- `agent_pack/AUTOPILOT_POLICY.md`：无人值守策略（变更范围/预算/停机/发布/回滚）
- `agent_pack/ROADMAP.md`：里程碑占位
- `agent_pack/BACKLOG.md`：任务池（schedule 默认挑选 Top-1 READY）
- `agent_pack/tools/validate_agent_pack.sh`：AgentPack 自检脚本（质量闸门）
- `.github/`：CI/Autopilot 工作流（不绑定具体技术栈）
- `.env.example`：环境变量占位（禁止真实密钥）
- `Makefile`：统一入口（dev/test/lint，占位 + TODO）
- `.github/workflows/ci.yml`：CI 自检闸门（仅跑 `make validate`，不绑定具体技术栈）
- `.github/workflows/autopilot.yml`：无人值守闭环骨架（Codex → PR → 合并 → Staging 占位部署）
- `scripts/`：部署/烟测钩子占位（不绑定云厂商）
- `scripts/deploy_staging.sh`：Staging 部署占位（默认不失败）
- `scripts/smoke_test.sh`：Staging 烟测占位（默认不失败）

若出现同名文件无法安全合并：创建 `*-v2` 并在此记录映射关系（当前：NONE）。

仓库现状备注（初始化时）：
- 已于 2026-10-24 导入 `ScreenCopy/`；原 AgentPack 规则和自动化工作流保留。
- 未发现仓库级 `docs/` 或 `prompts/` 目录；若后续引入，需在此建立与 `agent_pack/` 的关系映射，避免覆盖。
- `agent_pack/episodes/` 默认只检索最近 30 条（更老需归档且默认不检索）。

### Env Vars
- 仅允许在 `.env.example` 中描述变量名与用途；严禁提交真实密钥。
- ScreenCopy 构建需要系统 `JAVA_HOME`（JDK 17）和 `ANDROID_HOME`（SDK，含 platforms;android-35）。不提交本机 local.properties；运行 App 不需要 API Key。

### Commands（dev/test/lint/build）
> 不确定技术栈时只提供 TODO 指引，避免写会失败的命令。

- `make validate`：AgentPack 自检（质量闸门；CI 仅跑此项）
- `make dev`：占位（后续填入真实启动命令）
- `make test`：当前等价于 `make validate`（后续替换为真实测试命令）
- `make lint`：当前等价于 `make validate`（后续替换为真实静态检查命令）
- `make build`：占位（后续填入真实构建命令）
- Android 构建：`cd ScreenCopy && ./gradlew assembleDebug`。
- Android 单元测试：`cd ScreenCopy && ./gradlew testDebugUnitTest`。
- Android 静态检查：`cd ScreenCopy && ./gradlew lintDebug`。
- 本轮为源码归档提交，根目录 Makefile/CI 仍保留现有 AgentPack 自检；Android 自动化构建接入不在本轮范围，不能把根目录 CI 当成 Android 验证。

### Observability
- TODO：日志/指标/追踪/告警与本地调试方式。

## Automation
### Autopilot（zero-touch）概览
- 策略文件：`agent_pack/AUTOPILOT_POLICY.md`
- 工作流：`.github/workflows/autopilot.yml`
- 默认策略：**自动到 Staging**；Prod 自动化为可选开关（默认关闭）。

### Triggers
- `schedule`：周期触发（会尝试从 `agent_pack/BACKLOG.md` 选择 Top-1 `READY`；无任务或未配置密钥则 no-op）。
- `issues:labeled`：当 Issue 被添加 label `autopilot:run` 时触发（任务来源为 Issue 内容）。

### Flow（不依赖业务栈）
1. 运行 `make validate/test/lint`（当前为 AgentPack 自检闸门）
2. （若配置了 `OPENAI_API_KEY`）运行 `openai/codex-action@v1` 生成变更
3. 再次运行 `make validate/test/lint`
4. 通过后：创建分支 → 提 PR → 自动合并（不直接推主分支）
5. 合并后：调用 `scripts/deploy_staging.sh` 与 `scripts/smoke_test.sh`（当前为占位，默认不失败）

### Secrets（仅定义名称与用途；仓库内禁止真实值）
- 必需（启用 Codex）：
  - `OPENAI_API_KEY`：用于 `openai/codex-action@v1` 调用 Codex（仅在 Actions Secrets 配置，不写入仓库）
- 可选（未来接入真实部署/烟测时再补齐）：
  - `STAGING_DEPLOY_TOKEN`：Staging 部署凭证（占位）
  - `STAGING_BASE_URL`：Staging 烟测目标地址（占位）
  - `PROD_DEPLOY_TOKEN` / `PROD_BASE_URL`：Prod 相关占位（默认不开启）

### Prod 开关（默认关闭）
- Repo Variables：`AUTOPILOT_ENABLE_PROD=true` 才会执行 Prod 占位步骤。

### Rollback
- 部署或烟测失败时：优先用 `git revert` 方式回滚（不改写历史），并更新 Changelog（若已对外可见）。

## Quality Bar（DoD）
最低 DoD（不可降低）：
- 有可交付变更（实现/修复/文档更新之一）
- 至少一种可执行验证（测试或可复现步骤）
- 有回滚点
- 有文档回写（Changelog / Assumptions / 必要时 ADR）

## Change Control
### ADR Index
- TODO：如采用 ADR，在此记录索引（编号/标题/日期/链接）。

### Changelog
| Date | Area | Summary | Links |
|---|---|---|---|
| 2026-10-24 | ScreenCopy | 导入轻触取字 1.0.0 安卓源码、Gradle Wrapper、4 项算法测试与真实验证记录；无新 HTTP API | `ScreenCopy/README.md`, `ScreenCopy/VALIDATION.md` |
| 2026-03-01 | agent_pack | Bootstrap AgentPack（硬规则/记忆库/runbook/prompts/schema/入口） | N/A |
| 2026-03-01 | agent_pack | Add AgentPack validation gate（validate script + Makefile/CI + rollback docs） | `agent_pack/tools/validate_agent_pack.sh` |
| 2026-03-01 | autopilot | Add zero-touch Codex pipeline skeleton（policy + backlog + workflow + staging hooks） | `.github/workflows/autopilot.yml` |

### Todo
- TODO：根据需要将 Android 构建/测试接入 CI；当前根目录 CI 仅跑 AgentPack 自检。
- TODO：若存在 API，补齐 OpenAPI schema（见 A-003）。

### Risk Register
| Date | Risk | Mitigation | Status |
|---|---|---|---|
| 2026-10-24 | 尚未真机验证，且现有 CI 不构建 Android。 | 源码已包含原构建验证记录、可执行命令及真机复核清单；后续按需接入 Android CI。 | OPEN |

