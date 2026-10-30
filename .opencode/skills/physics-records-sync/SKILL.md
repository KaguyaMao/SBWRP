---
name: Physics Records Sync
description: Record tracked-vehicle decisions, stage changes, tests, manual gates, core API changes, and MCP synchronization status in the repository event ledger.
---

# 物理记录同步技能

开始前阅读：

1. `docs/physics/RECORD_FORMAT.md`
2. `docs/physics/STATUS.md`
3. `docs/physics/DECISIONS.md`
4. `docs/physics/IMPLEMENTATION_PLAN.md`
5. `docs/physics/MCP_OPERATIONS.md`

## 权威来源

- 规范：`docs/physics/CONSTRAINTS.md`。
- 阶段：`docs/physics/IMPLEMENTATION_PLAN.md`。
- 状态：`docs/physics/STATUS.md`。
- 事实历史：`docs/physics/records/events.jsonl`。
- Skills 只是工作流，不是事实权威。

## 必须记录的事件

- 决策变化和撤销。
- 阶段开始、退出、阻塞或失败。
- T0–T5 测试结果。
- 用户实机确认。
- 车型批准和常规获取变化。
- 核心库 `core_commit` 和 API 语义变化。
- Skills 目录或职责变化。
- MCP 建立、重建或同步状态。

## 追加规则

1. 确认仓库、分支、commit 和车型。
2. 追加 `records/events.jsonl`，不覆盖历史。
3. `source_commit` 使用 HEAD 或 `WORKTREE`；核心库提交放 `core_commit`。
4. 运行 `node docs/physics/records/validate-events.mjs`。
5. 更新 `STATUS.md` 和相关阶段/决策文档。
6. 硬边界变化同步 Skills。
7. MCP 可用时增量导入并比对 event ID/source hash。
8. 记录 `mcp_sync`。

## 状态规则

- `manual_passed` 必须 `user_confirmed=true` 且 `manual_gate` 为 A–D。
- `approved` 必须 `user_confirmed=true`。
- D0–S1 的用户确认使用 `in_progress`，不是实机通过。
- 未冻结限值不得写成 `auto_passed`。
- 旧 P0–P9 事件保留为历史，新事件使用 D0–F1 阶段 ID。

## 跨仓库

- 引用写成 `仓库名:仓库内路径`。
- 不写本机绝对路径、用户主目录、主机名或 `../其他仓库/...`。
- 核心库改动追加带 `core_commit` 的事件，并使受影响车型回到待复核。

## MCP 不可用

- 仍追加仓库事件。
- `mcp_sync` 写 `unavailable` 或 `pending`。
- 不声称已写入数据库。
- server 建立后从 JSONL 全量重建并校验。
