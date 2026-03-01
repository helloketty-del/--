# Codex AgentPack 启动入口（CODEX_START）

本仓库已初始化 `agent_pack/`（单执行体模拟多 Agent 的协作包）。从现在起，每一轮在仓库内协作开发/修复/文档更新，都以本文件作为入口规范。

## 每轮固定使用顺序（强制）
1. 读取事实源：`agent_pack/PROJECT_MEMORY.md`
2. 读取流程手册：`agent_pack/RUNBOOK.md`
3. 读取硬规则与输出协议：`agent_pack/AGENTS.md`
4. 选择任务模板（按需复制到本轮对话/任务说明中）：
   - 功能：`agent_pack/prompts/TASK_FEATURE.md`
   - 修复：`agent_pack/prompts/TASK_BUGFIX.md`
   - 文档：`agent_pack/prompts/TASK_DOC_PATCH.md`
   - 提示词/流程规则：`agent_pack/prompts/TASK_PROMPT_PATCH.md`

> 任何事实冲突：先更新 `agent_pack/PROJECT_MEMORY.md`（并附带 MEMORY_PATCH），再改代码/文档。

## 每轮必须输出的区块（强制）
无论任务大小，每轮输出必须包含以下 8 个区块（若不适用写 `NONE`，但不得省略标题）：
- RUN
- VERIFY
- RISKS
- ROLLBACK
- MEMORY_PATCH
- STATE_REPORT
- PROMPT_REVIEW
- PROMPT_PATCH

## 本轮任务块模板（复制使用）
将下列模板粘贴到每轮任务说明中，并按实际填写：

```md
SELECTED_ROLE: <Router|Architect|Implementer|Reviewer|QA|Security>
SCOPE:
  IN:  <本轮要改什么>
  OUT: <本轮明确不改什么（默认不动业务代码）>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>

TASK:
  GOAL: <目标>
  CONSTRAINTS: <硬约束/不可做>
  DELIVERABLES: <交付物清单>
  ACCEPTANCE: <验收标准>

RUN:
  - <执行过的命令/步骤；无则写 NONE>

VERIFY:
  - <至少一种可执行验证：测试/可复现步骤/检查项；无则写清复现步骤>

RISKS:
  - <风险/不确定性/待确认点；无则写 NONE>

ROLLBACK:
  - <回滚命令或回滚步骤（必须可执行/可操作）>

MEMORY_PATCH:
  - <若涉及接口/目录/依赖/环境变量/外部服务变更：给出补丁，并确保已回写到 PROJECT_MEMORY>
  - NONE

STATE_REPORT:
  REPO_STATE: <分支/commit/改动文件>
  CHECKS: <dev/test/lint 结果或占位说明>
  CONTRACT_DELTA: <openapi 是否变更>
  MEMORY_PATCH_APPLIED: <是否已回写、改了哪些章节>
  TODO/RISKS: <更新项>

PROMPT_REVIEW:
  - <0-3 个提示词/流程隐患；无则写 NONE>

PROMPT_PATCH:
  - <可执行修改建议：改哪个文件/哪一段/收益与风险；无则写 NONE>
```

