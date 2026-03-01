# AUTOPILOT_POLICY（无人值守策略 / Zero-touch Policy）

本文件定义 **Autopilot（无人值守）** 模式下的硬约束与停机/发布/回滚策略，用于让“开发 → 集成 →（可选）部署”在不依赖业务技术栈的前提下先跑通闭环。

> 适用范围：仅当通过 `.github/workflows/autopilot.yml` 触发的 Codex 非交互任务运行时生效。  
> 事实源优先级仍然遵守：`agent_pack/PROJECT_MEMORY.md` > ADR > 代码现状 > episodes。

## 1) Allowed Change Scope（允许的变更范围）
默认允许修改（白名单；**仅允许这些路径**）：
- `src/`（如存在）
- `tests/`（如存在）
- `docs/`（如存在）
- `agent_pack/`（允许：记忆回写、episodes 记录、roadmap/backlog 维护；禁止擅自削弱硬规则）
- `scripts/`（仅允许与部署/自检相关的脚本占位或实现）

默认禁止修改（黑名单）：
- `.github/`（**禁止**；除非本轮任务明确为 `TASK_PROMPT_PATCH` 且目标是工作流/自动化规则维护）
- `CODEOWNERS` / 分支保护相关文件（如存在）
- 任何可能引入真实密钥/隐私数据的文件（包括但不限于：`.env`、私钥文件、云厂商凭证）

## 2) Change Budget（变更预算）
为了降低风险与回滚成本，单轮无人值守变更预算：
- **最多改动文件数：12**（含新增/修改/删除）
- **最多新增行数：500**（以 `git diff --numstat` 的 “added lines” 统计为准）

超出预算：必须拆分为多轮（每轮独立 PR），并在 `agent_pack/BACKLOG.md` 拆分任务。

## 3) Stop Conditions（停机条件）
满足任一条件立即停止无人值守推进（不自动重试，等待人工介入）：
- 连续 2 次 Autopilot 运行失败（生成 PR 失败或 validate/test/lint 未通过）
- 单次运行耗时超过 30 分钟（含等待 PR 合并/部署）
- 发现触碰黑名单路径或出现疑似密钥/隐私泄露风险（哪怕 validate 通过也应停止）

## 4) Release Strategy（发布策略）
默认发布策略：
- **Auto → Staging：开启（默认）**
- **Auto → Prod：关闭（默认）**，仅在显式开关开启时允许

Prod 开关建议形式（任选其一，默认均为关闭）：
- Repo Variables：`AUTOPILOT_ENABLE_PROD=true`
- 或：Workflow manual dispatch 输入参数（未来可加）

## 5) Rollback Strategy（回滚策略）
当 Staging 部署或烟测失败：
- 默认策略：**revert 上一个“部署相关提交”或本次合并提交**，并以 PR 方式回滚（不改写主分支历史）。
- 回滚动作若已对外可见：必须回写 `agent_pack/PROJECT_MEMORY.md -> Change Control -> Changelog`（必要时 ADR）。
