# RUNBOOK（每轮开发闭环）

本手册把每一轮协作开发固化为可重复的闭环流程，并绑定 DoD、契约锁与安全规则。

## 每轮闭环（强制顺序）
1. **读事实源**
   - 必读：`agent_pack/PROJECT_MEMORY.md`
   - 必读：`agent_pack/RUNBOOK.md`
   - 必读：`agent_pack/AGENTS.md`
   - 可选：相关 ADR、相关代码、最近 30 条 episodes
2. **路由角色（Router）**
   - 明确 `SELECTED_ROLE` 与 `SCOPE (IN/OUT)`，列出不确定点并写入 Assumptions（如有）。
3. **计划**
   - 给出最小变更集与验证方案；若会触及接口/目录/依赖/环境变量/外部服务，预先声明需要 MEMORY_PATCH。
4. **实现（最小差异）**
   - 默认不改业务代码；若必须改，保持差异最小、可回滚、可验证。
5. **验证（至少一种可执行）**
   - 优先：自动化测试；否则：可复现步骤（清晰到可操作）。
6. **回写（Memory Patch / Changelog / ADR）**
   - 触发条件见 `AGENTS.md -> HARD RULES`。
   - **先回写事实源，再继续扩展改动**。
7. **提交（单一 commit）**
   - 所有改动合并为一个 commit（避免碎片化），保证可回滚。
8. **状态回传（必须输出）**
   - 按 `CODEX_START.md` 输出 `STATE_REPORT` 与 `PROMPT_REVIEW/PROMPT_PATCH`。

## DoD（最低门槛，不可降低）
- 有可交付变更（实现/修复/文档更新之一）
- 至少一种可执行验证（测试或可复现步骤）
- 有回滚点
- 有文档回写（Changelog / Assumptions / 必要时 ADR）

## Rollback（回滚操作指南）
> 原则：优先“可审计、可协作”的回滚方式；历史改写（reset/force push）需谨慎。

### 未推送（本地尚未 push）
- 回退最近一次提交（会丢弃该提交的工作区改动，请先确认无需保留）：
  - `git reset --hard HEAD~1`

### 已推送（远端已可见/多人协作）
- 推荐生成反向提交（不改写历史）：
  - `git revert <commit> --no-edit`
- 说明：
  - 初始提交/根提交在不同 Git 版本与历史状态下可能出现差异；若 `git revert` 失败，再评估是否使用 `git reset --hard <commit>` 并 `git push --force-with-lease`（**高风险：会改写历史，需团队协商**）。

### 文档纪律
- 若回滚影响已对外可见的行为/契约/发布内容：**回滚动作本身也必须更新 Changelog（必要时 ADR）**。

## 契约变更纪律（Contract Lock）
- `agent_pack/schema/openapi.yaml` 为契约锁文件。
- 任意 API/契约变更必须同步更新：
  - `openapi.yaml`
  - `PROJECT_MEMORY.md -> Change Control -> Changelog`
  - 必要时新增/更新 ADR（记录原因与权衡）

## Secrets & Safety
- 严禁提交真实密钥/token/隐私数据。
- 环境变量只写入 `.env.example`（变量名/用途/来源/是否必需），不得包含真实值。
- 依赖变更必须说明来源、风险与回滚方案，并回写到 `PROJECT_MEMORY.md`。

## Troubleshooting（占位）
- TODO：当技术栈明确后补齐：本地启动失败、测试失败、常见依赖问题、CI 失败定位路径等。

