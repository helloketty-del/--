# BACKLOG（任务池 / 需求队列）

本文件是 Autopilot 的默认任务来源之一（schedule 触发时会尝试选择 Top-1）。

## 任务条目格式（必须包含：目标/验收/风险/优先级）
建议用单行条目，便于工作流解析：

```md
- [P1][READY] <TITLE> | Goal: <...> | Acceptance: <...> | Risks: <...>
```

字段说明：
- Priority：`P0`（最高）→ `P3`（最低）
- Status：`READY` 才会被 schedule 自动选中；其他状态（如 `DRAFT`/`HOLD`）不会被自动执行

## Items
> 初始默认不放 READY 条目，避免 schedule 在“尚未配置 Secrets/发布策略”时持续产出 PR 噪音。

- [P2][DRAFT] Define real tech stack commands | Goal: 填充 make dev/test/lint/build | Acceptance: Commands 可执行且 CI 通过 | Risks: 误判技术栈导致命令失败

