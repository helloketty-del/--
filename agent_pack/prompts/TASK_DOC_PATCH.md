# TASK_DOC_PATCH（文档更新任务模板）

将本模板复制到本轮任务说明中，并按实际填写。必须遵守 `agent_pack/AGENTS.md` 的 HARD RULES。

```md
SELECTED_ROLE: Router
SCOPE:
  IN:  <要更新的文档范围>
  OUT: <不改的范围>
FACT_SOURCES_USED: <PROJECT_MEMORY / ADR / code / episodes>

GOAL:
  - <文档要解决的问题：缺失/歧义/过期>

CHANGES:
  - <将修改哪些文件/章节>

VERIFY:
  - <至少一种可执行验证：链接检查/指令可运行性/与代码一致性抽查/人工复核步骤>

ROLLBACK:
  - <回滚命令/步骤（git revert 等）>

MEMORY_PATCH:
  - <若文档变化改变了接口/目录/依赖/环境变量/外部服务的“事实描述”，也必须回写 PROJECT_MEMORY>
  - NONE

STATE_REPORT:
  REPO_STATE: <分支/commit/改动文件>
  CHECKS: <dev/test/lint 结果或 NONE>
  CONTRACT_DELTA: <openapi 是否变更>
  MEMORY_PATCH_APPLIED: <是否回写、改了哪些章节>
  TODO/RISKS: <更新项>

PROMPT_REVIEW:
  - <0-3 个流程/提示词隐患；无则 NONE>

PROMPT_PATCH:
  - <修改建议：文件/段落/收益与风险；无则 NONE>
```

