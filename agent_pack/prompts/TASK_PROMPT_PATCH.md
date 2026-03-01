# TASK_PROMPT_PATCH（提示词/流程规则变更任务模板）

当且仅当本轮任务明确为“提示词/规则维护”时使用本模板。本任务允许修改 `agent_pack/` 规则文件，但仍需遵守 HARD RULES（尤其是事实源优先级与 DoD）。

```md
SELECTED_ROLE: Router
SCOPE:
  IN:  <要改的 prompts / runbook / agents / memory 结构>
  OUT: <不改的范围>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>

GOAL:
  - <要解决的提示词/流程问题>

CURRENT_ISSUES:
  - <0-3 个具体问题（可引用失败案例/症状）>

PROPOSED_PATCH:
  - FILE: <文件路径>
    SECTION: <章节/段落>
    CHANGE: <改动描述>
    BENEFIT: <收益>
    RISK: <风险/回退方案>

VERIFY:
  - <至少一种可执行验证：用一段示例任务验证输出协议/硬规则是否仍满足>

ROLLBACK:
  - <回滚命令/步骤>

MEMORY_PATCH:
  - <若本次修改改变了事实源/流程/契约纪律等“项目事实”，必须回写 PROJECT_MEMORY>
  - NONE

STATE_REPORT:
  REPO_STATE: <分支/commit/改动文件>
  CHECKS: <dev/test/lint 结果或 NONE>
  CONTRACT_DELTA: <openapi 是否变更>
  MEMORY_PATCH_APPLIED: <是否回写、改了哪些章节>
  TODO/RISKS: <更新项>

PROMPT_REVIEW:
  - <本次变更后的残余隐患；无则 NONE>

PROMPT_PATCH:
  - <下一轮建议（若无则 NONE）>
```

