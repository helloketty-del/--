# PROJECT_MEMORY（唯一事实源，最高优先级）

> 优先级：`PROJECT_MEMORY.md` > `ADR/决策记录` > `代码现状` > `episodes`  
> 冲突处理：先更新本文件（含 MEMORY_PATCH）再改代码/文档。

最后更新：2026-03-01

## Project Summary
- TODO：用 5-10 行描述本项目“做什么、给谁用、核心边界、关键质量目标”。

## Constraints & Assumptions
> 任何信息不足/不确定点必须记录在此，并包含影响与验证计划。格式：`A-xxx`。

| ID | Statement | Impact | Validation Plan | Status | Date |
|---|---|---|---|---|---|
| A-001 | 本仓库在 AgentPack 初始化时（2026-03-01）目录为空且非 Git 仓库，无法从代码推断技术栈。 | Makefile/CI/dev/test/lint 仅能提供占位与 TODO 指引；需要后续补全真实命令与结构。 | 确认本目录为目标仓库根目录；如应存在代码则迁移/恢复后更新本文件与入口脚本。 | OPEN | 2026-03-01 |
| A-002 | 当前未知技术栈（Node/Python/Go/Java 等），未知可运行命令。 | 无法提供可运行的 lint/test；需后续补齐 `Engineering & Run -> Commands`。 | 引入代码后扫描清单文件（如 package.json/pyproject/go.mod 等）并更新命令。 | OPEN | 2026-03-01 |
| A-003 | 当前未知对外 API/契约；`openapi.yaml` 为契约锁占位。 | 未来新增/变更 API 时必须同步更新 schema + Changelog + 必要时 ADR。 | 首次引入 API 时补齐 OpenAPI 路径/模型，并在 Changelog 记录。 | OPEN | 2026-03-01 |

## Architecture
### Tech Stack
- TODO：语言/框架/运行时/数据库/队列/缓存/部署形态。

### Modules
- TODO：列出模块边界与责任（前端/后端/数据/基础设施等）。

### Data Flow
- TODO：从入口请求到数据落库/外部依赖的主链路（可用文本或简图链接）。

## Interfaces & Contracts
### External Services
- TODO：外部服务清单（域名/用途/鉴权方式/超时/重试/降级）。

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

当前顶层结构（2026-03-01 初始化后）：
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
- 未发现业务代码/模块目录（可能尚未导入代码）。
- 未发现仓库级 `docs/` 或 `prompts/` 目录；若后续引入，需在此建立与 `agent_pack/` 的关系映射，避免覆盖。
- `agent_pack/episodes/` 默认只检索最近 30 条（更老需归档且默认不检索）。

### Env Vars
- 仅允许在 `.env.example` 中描述变量名与用途；严禁提交真实密钥。
- TODO：补齐本项目需要的环境变量列表（名称/用途/是否必需/默认值/来源）。

### Commands（dev/test/lint/build）
> 不确定技术栈时只提供 TODO 指引，避免写会失败的命令。

- `make validate`：AgentPack 自检（质量闸门；CI 仅跑此项）
- `make dev`：占位（后续填入真实启动命令）
- `make test`：当前等价于 `make validate`（后续替换为真实测试命令）
- `make lint`：当前等价于 `make validate`（后续替换为真实静态检查命令）
- `make build`：占位（后续填入真实构建命令）
- TODO：当技术栈明确后，将真实命令写入此处，并同步更新 Makefile/CI。

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
| 2026-03-01 | agent_pack | Bootstrap AgentPack（硬规则/记忆库/runbook/prompts/schema/入口） | N/A |
| 2026-03-01 | agent_pack | Add AgentPack validation gate（validate script + Makefile/CI + rollback docs） | `agent_pack/tools/validate_agent_pack.sh` |
| 2026-03-01 | autopilot | Add zero-touch Codex pipeline skeleton（policy + backlog + workflow + staging hooks） | `.github/workflows/autopilot.yml` |

### Todo
- TODO：补齐技术栈与真实命令（见 A-002）。
- TODO：若存在 API，补齐 OpenAPI schema（见 A-003）。

### Risk Register
| Date | Risk | Mitigation | Status |
|---|---|---|---|
| 2026-03-01 | 技术栈未知导致验证链路缺失。 | 代码引入后第一时间补齐 Commands/CI，并补充可执行验证。 | OPEN |
