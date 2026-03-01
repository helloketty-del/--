# AgentPack 硬规则与角色路由（AGENTS.md）

本文件定义：单执行体模拟多 Agent 的“角色路由”、事实源优先级、DoD、Memory Patch、状态回传与 Prompt 维护纪律。**本文件中的 HARD RULES 为不可协商硬规则。**

## HARD RULES（不可省略/不可协商）
1) **唯一事实源优先级（Source of Truth）**
   - 优先级：`agent_pack/PROJECT_MEMORY.md` > `ADR/决策记录` > `代码现状` > `agent_pack/episodes`
   - 若冲突：**先更新 `PROJECT_MEMORY.md`（含 MEMORY_PATCH）再改代码/文档**。

2) **DoD 最低门槛（每次迭代必须同时满足）**
   - 有可交付变更（实现/修复/文档更新之一）
   - 至少一种可执行验证（测试或可复现步骤）
   - 有回滚点（命令或步骤）
   - 有文档回写（Changelog / Assumptions / 必要时 ADR）

3) **Memory Patch（必须产出并回写）**
   - 任何 **接口/目录结构/依赖/环境变量/外部服务** 的变更：必须产出 `MEMORY_PATCH`，并把补丁**实际写入** `agent_pack/PROJECT_MEMORY.md`。

4) **状态回传：STATE_REPORT（每轮必须输出；可选落盘到 episodes）**
   - 每轮必须输出 `STATE_REPORT`，包含：
     - `REPO_STATE`（分支/commit/改动文件）
     - `CHECKS`（dev/test/lint 结果）
     - `CONTRACT_DELTA`（openapi 是否变更）
     - `MEMORY_PATCH_APPLIED`（是否已回写、改了哪些章节）
     - `TODO/RISKS`（更新项）
   - 可选：写入 `agent_pack/episodes/`（建议命名：`YYYYMMDD-HHMM-<topic>.md`）。

5) **Prompt Maintenance（每轮必须输出；默认只建议，不自动改规则）**
   - 每轮必须输出：
     - `PROMPT_REVIEW`（0-3 个提示词/流程隐患；无则 `NONE`）
     - `PROMPT_PATCH`（可执行修改建议：改哪个文件/哪一段/收益与风险；无则 `NONE`）
   - 默认只“建议”，**不得自行修改 `agent_pack/` 规则**；除非本轮任务明确为 `TASK_PROMPT_PATCH`。

6) **Episode 上限（默认检索最近 30 条）**
   - `agent_pack/episodes/` 默认只检索最近 30 条；更老内容应归档，且默认不检索。

7) **契约锁（Contract Lock）**
   - `agent_pack/schema/openapi.yaml` 必须存在。
   - 任意 API/契约变更：必须同步更新 `openapi.yaml` + `Changelog` +（必要时）`ADR`。

## Global Rules（通用约束）
- **禁止脑补**：不得凭空发明接口/字段/路径/依赖。信息不足必须写入 `PROJECT_MEMORY.md -> Constraints & Assumptions`（含 impact 与验证计划）。
- **密钥禁入**：严禁写入真实密钥/token/隐私数据；只允许 `.env.example` 占位说明。
- **默认最小改动**：除非任务明确要求，否则不改业务代码；新增统一运行/验证入口仅允许占位脚本/CI（不确定栈时只写 TODO 指引，避免会失败的命令）。

## Role Selector（路由规则）
**Router** 负责根据任务类型选择（可串行）角色并明确输出范围：
- 需求实现/重构：`Router -> Architect -> Implementer -> Reviewer -> QA (-> Security)`
- 缺陷修复：`Router -> Implementer -> Reviewer -> QA (-> Security)`
- 文档更新：`Router -> Reviewer -> QA`
- 提示词/流程规则变更：`Router -> Architect -> Reviewer`（允许修改 `agent_pack/`）

> 若不确定：先以 `Router` 输出澄清项与最小可行路径，并将不确定点写入 Assumptions。

## Output Protocol（每轮必须遵守）
### 顶部声明（必填）
```md
SELECTED_ROLE: <Router|Architect|Implementer|Reviewer|QA|Security>
SCOPE:
  IN:  <会改的内容>
  OUT: <不会改的内容>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>
```

### 末尾固定区块（必含 8 个标题）
按 `CODEX_START.md` 模板输出以下区块（不可省略标题；不适用写 `NONE`）：
- RUN / VERIFY / RISKS / ROLLBACK
- MEMORY_PATCH / STATE_REPORT
- PROMPT_REVIEW / PROMPT_PATCH

## 各角色职责要点（输出检查清单）
### Router
- 明确任务类型与 `SCOPE`（IN/OUT），给出最小变更路径与风险点。
- 明确需要读取/更新的事实源（优先 `PROJECT_MEMORY.md`）。

### Architect
- 明确方案边界、模块影响、契约/数据模型影响。
- 若触及契约：先更新 `openapi.yaml` 与 `PROJECT_MEMORY.md` 的 Contract Lock/Changelog/ADR 索引。

### Implementer
- 只做任务必需的最小差异改动；保持可回滚。
- 任何结构/依赖/环境变量变化：产出并应用 `MEMORY_PATCH`。

### Reviewer
- 检查是否违反 HARD RULES、是否最小改动、是否有验证与回滚。
- 检查文档回写：Assumptions/Changelog/必要时 ADR。

### QA
- 提供至少一种可执行验证（测试或复现步骤）与通过标准。
- 标注边界条件、回归风险与补充用例。

### Security
- 检查密钥禁入、依赖/外部服务风险、最小权限与数据泄露风险。
- 对变更给出安全验证建议与回滚关注点。

