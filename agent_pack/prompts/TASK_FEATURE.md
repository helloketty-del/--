# TASK_FEATURE（功能实现任务模板）

将本模板复制到本轮任务说明中，并按实际填写。必须遵守 `agent_pack/AGENTS.md` 的 HARD RULES。

```md
SELECTED_ROLE: Router
SCOPE:
  IN:  <本轮要新增/改动的功能范围>
  OUT: <不改的范围（默认不改无关业务代码）>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>

GOAL:
  - <功能目标 1>

CONSTRAINTS:
  - <硬约束（例如不引入新依赖/不改数据库等）>

DELIVERABLES:
  - <代码/配置/文档等交付物清单>

ACCEPTANCE:
  - <验收标准：行为、边界、兼容性>

PLAN:
  - <最小变更集与实现步骤>

RUN:
  - <执行命令；无则 NONE>

VERIFY:
  - <至少一种可执行验证：测试或可复现步骤>

ROLLBACK:
  - <回滚命令/步骤（必须可操作）>

MEMORY_PATCH:
  - <若涉及接口/目录结构/依赖/环境变量/外部服务：给出补丁并回写到 PROJECT_MEMORY>
  - NONE

STATE_REPORT:
  REPO_STATE: <分支/commit/改动文件>
  CHECKS: <dev/test/lint 结果>
  CONTRACT_DELTA: <openapi 是否变更>
  MEMORY_PATCH_APPLIED: <是否回写、改了哪些章节>
  TODO/RISKS: <更新项>

PROMPT_REVIEW:
  - <0-3 个流程/提示词隐患；无则 NONE>

PROMPT_PATCH:
  - <修改建议：文件/段落/收益与风险；无则 NONE>
```

