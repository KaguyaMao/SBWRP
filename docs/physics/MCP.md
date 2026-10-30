# 履带物理 MCP 建立、重建与实时跟进

更新时间：2026-10-20  
状态：设计已更新到 SBW 定义 + Bullet 架构；MCP server/database 运行时尚未建立

## 1. 现状与目标

当前会话调用 MCP resource discovery 得到空的 resources 和 templates；工作区没有发现已配置
的履带物理 MCP 数据库或 MCP server source。此文档记录建立方案，**不能将计划冒充为
已连接的 MCP 服务**。

目标是让后续工作能查询当前硬约束、决定、阶段、车型批准状态、测试证据、失败和下一动作。

## 2. 单一事实来源

事实权威来源：`docs/physics/*.md`、`records/events.jsonl` 和事件 schema。
`.opencode/skills/**/SKILL.md` 只保存工作流，不是事实权威，不得覆盖状态或测试结果。
SQLite 只作为本机全文搜索和查询索引，不是权威来源；放在 `.local/physics-mcp/` 或用户
数据目录，不提交 WAL、SHM、FTS、缓存或 OAuth/config。

每个导入事件必须保留 `event_id`、source file、source hash 和 schema version，能够全量重建。
跨仓库引用使用 `仓库名:仓库内路径`；不得保存本机绝对路径、用户主目录或
`../其他仓库/...`。

## 3. 建议实体与资源

| 实体 | 关键字段 |
|---|---|
| Decision | id、date、decision、status、supersedes、reason、source |
| Stage | stage_id、parent_stage、vehicle、status、criteria、gate |
| TestRun | run_id、level、command、result、commits、hashes、evidence |
| Vehicle | id、registry、approval、availability、adapter、hash |
| ManualGate | gate、vehicle、confirmation、scenarios、result |
| CoreApi | core_commit、api_version、affected_vehicles、revalidation_status |

只读资源建议：`tracked://status/current`、`tracked://constraints`、`tracked://plan`、
`tracked://vehicle/{id}`、`tracked://stage/{id}`、`tracked://decisions/{id}`、
`tracked://core-api/{version}`。

查询工具建议：`physics_status`、`physics_search`、`physics_get_test_run`、
`physics_diff_records`、`physics_verify_index`。

## 4. 写入规则

第一版优先通过 code task 追加仓库 event ledger，再触发数据库导入。未来若提供
`physics_append_event`，必须做 schema/commit/车型/阶段状态校验、原子追加、event ID
幂等、source hash 返回；“实机通过”和“批准车型”必须带用户确认，agent 不能自行提升。

## 5. 实时同步协议

实时是每个重要事件后同步，不是后台轮询，也不是每次对话强制创建记录：

1. 确认仓库、分支、commit 和车型；
2. 追加 `records/events.jsonl`，不覆盖历史；
3. 校验 schema、引用和哈希；
4. 更新 `STATUS.md`、阶段/决策文档；
5. 硬边界改变时同步相关 Skills；
6. MCP 可用时增量导入并比对 event ID/source hash；
7. 记录 `synced/pending/mismatch/unavailable`。

MCP 不可用时，事件仍写入仓库，不能声称已写库。SQLite 与仓库不一致时仓库胜出并重建。

## 6. 建立步骤

1. 选定可维护运行时，实现 JSONL reader、schema 校验、SQLite migration、幂等导入和 FTS。
2. 实现 resources 和只读工具；写工具只能追加事件。
3. server 源码、schema、导入器和测试入库；数据库和运行产物放忽略目录。
4. 只有 server entrypoint 已存在后，才添加 OpenCode V2 local MCP 配置。
5. 用 `opencode mcp list`、resource discovery、删库全量重建和增量导入测试验证。
6. 将建立/重建/故障恢复结果追加 `mcp_sync` 事件。

## 7. 新架构查询字段

事件和索引需要能表达：

- 物理履带 link 数、可见链节数和模型骨骼数。
- 普通 AABB 地形、TrackRun、STEP_ASSIST 和 STRUCTURAL_OBSTACLE 接触来源。
- KaguyaPyCore `TrackRun` API 版本和 `core_commit`。
- 车型批准是否因核心 API 变化而失效。

## 8. 禁止保存

不保存完整世界、NBT 存档、日志原文、客户端/服务端文件、硬件配置、凭据、token 或未经
同意的个人资料。大日志只保存脱敏摘要；不可提交的本机路径不得进入仓库事件或 MCP 索引。
