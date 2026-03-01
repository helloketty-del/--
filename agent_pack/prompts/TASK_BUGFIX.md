# TASK_BUGFIX（缺陷修复任务模板）

将本模板复制到本轮任务说明中，并按实际填写。必须遵守 `agent_pack/AGENTS.md` 的 HARD RULES。

```md
SELECTED_ROLE: Router
SCOPE:
  IN:  <要修复的缺陷范围>
  OUT: <不改的范围>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>

BUG:
  SYMPTOM: <现象/错误信息/截图文字>
  EXPECTED: <期望行为>
  ACTUAL: <实际行为>

REPRO:
  - <可复现步骤（必须可操作）>

ROOT_CAUSE_HYPOTHESIS:
  - <怀疑点；不确定写 Assumptions>

FIX_PLAN:
  - <最小修复路径>

RUN:
  - <执行过的命令；无则 NONE>

VERIFY:
  - <至少一种可执行验证：回归测试/复现验证/自动化测试>

ROLLBACK:
  - <回滚命令/步骤>

MEMORY_PATCH:
  - <若触及接口/目录结构/依赖/环境变量/外部服务：给出补丁并回写>
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

