# 状态记录与 MCP 可重建格式

更新时间：2026-10-20

当前 OpenCode 会话没有发现可写 MCP resource/database，因此本仓库文本是事实权威；未来
MCP 只能索引这些记录，不能维护一套独立且不可追溯的事实。

## 1. 规范性字段

机器记录以 [`records/schema/physics-event.schema.json`](records/schema/physics-event.schema.json)
为准。下面只解释语义；字段名不同的旧草稿不再有效。

| 语义 | schema 字段 | 不允许的替代写法 |
|---|---|---|
| 代码提交 | `source_commit` | `code_commit` |
| 核心库提交 | `core_commit` | 把核心库提交写进 `source_commit` |
| 测试证据 | `tests[].evidence_path` | 本机日志路径 |
| 失败说明 | `tests[].failure_summary` | 只写“失败” |
| 同步结果 | `mcp_sync` | 把 `pending` / `unavailable` 写进 `status` |
| 实机门 | `manual_gate` | 用阶段号代替 A–D |

`status` 只表示阶段或车型状态：

| 中文 | `status` | 含义 |
|---|---|---|
| 未开始 | `planned` | 尚未开始 |
| 进行中 | `in_progress` | 已开始，未退出 |
| 候选 | `candidate` | 代码或数据存在，未接线 |
| 已接线 | `wired` | 运行路径已使用，测试未退出 |
| 自动通过 | `auto_passed` | 规定自动测试通过，且限值已冻结 |
| 待实机 | `waiting_manual` | 等待游戏内用户确认 |
| 实机通过 | `manual_passed` | 用户确认了对应 A–D 门 |
| 批准 | `approved` | 可以进入常规获取 |
| 阻塞 | `blocked` | 不能继续，原因在 `summary` |
| 失败 | `failed` | 规定测试或门控失败 |

`manual_passed` 必须 `user_confirmed=true` 且 `manual_gate` 为 A–D。`approved` 必须
`user_confirmed=true`。D0–S1 的证据确认使用 `in_progress` 或后续阶段的 `planned`，
不得使用 `manual_passed`。

同一天的事件没有时钟，排序以文件中的先后为准，不以 `recorded_at` 区分先后。

## 2. 阶段 ID

当前使用：

```text
D0, D1, K1, B0, B1, B2, S1, S2, S3, S4, R1, N1, V1, F1
```

子阶段可写成 `D1.2`、`B2.3` 等。旧 `P0`–`P9` 事件保留为历史，不重新解释为新阶段。
新事件如需引用旧阶段，在 `supersedes` 或 `summary` 中说明映射。

## 3. 状态同步规则

- 代码提交变化时，`STATUS.md` 必须注明新 commit。
- 车型 JSON 或模型变化时，必须重新计算哈希并使旧批准失效。
- 核心库 API 变化时记录 `core_commit`，受影响车型回到待复核。
- 自动测试和实机检查分别记录，不能用一个状态覆盖另一个状态。
- 失败现场不删除；修复后追加新记录并引用旧记录。
- MCP 索引只保存路径、哈希、阶段和状态，不保存本机 token、存档或完整日志。

## 4. 新架构事件字段使用

- `changed_paths` 必须列出文档、Skills 或代码变更。
- `tests[].level` 使用 T0–T5；渲染路径数学测试用 T0，渲染烟测用 T4。
- `summary` 必须区分物理、地形、驱动和渲染。
- 旧高度场测试不得作为新架构证据，除非明确标记为历史。

## 5. 跨仓库引用

- 本仓库文件使用仓库内相对路径，例如 `docs/physics/STATUS.md`。
- 另一个仓库的文件写成 `仓库名:仓库内路径`，例如
  `KaguyaPyCore:docs/TRACK_RUN_API.md`。
- 禁止写入本机绝对路径、用户主目录、主机名，以及 `../其他仓库/...` 这类依赖当前
  检出布局的路径。
- `changed_paths`、`references` 和 `evidence_path` 使用同一规则。证据若只存在于本机且
  不可提交，不要把本机路径写入账本；在 `failure_summary` 中写脱敏摘要。

## 6. Skills 与记录的关系

- Skill 变更是 `kind=decision` 或 `kind=stage` 事件；事件引用对应 Skill 文件。
- Skill 不复制测试成绩和车型批准状态，避免状态过期；它只定义工作流程和硬边界。
- 当前技能目录见 [`SKILLS.md`](SKILLS.md)；MCP 同步规则见 [`MCP.md`](MCP.md) 和
  [`MCP_OPERATIONS.md`](MCP_OPERATIONS.md)。
- 当前没有 MCP server/database 时，事件的 `mcp_sync` 写为 `unavailable` 或 `pending`。

## 7. 当前记录位置

- 当前接线：[`CURRENT_WIRING.md`](CURRENT_WIRING.md)
- 验收限值：[`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md)
- 总状态：[`STATUS.md`](STATUS.md)
- 阶段计划：[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)
- 测试矩阵：[`TEST_MATRIX.md`](TEST_MATRIX.md)
- 决策：[`DECISIONS.md`](DECISIONS.md)
- 旧实现清理：[`LEGACY_CLEANUP.md`](LEGACY_CLEANUP.md)
