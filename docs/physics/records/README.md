# 版本控制的履带物理事件账本

`events.jsonl` 是追加式文本记录，每行一个 JSON object，依照
[`schema/physics-event.schema.json`](schema/physics-event.schema.json)。它是 MCP SQLite
索引的可重建来源。不得把日志全文、存档、世界、设备配置或密钥写入此目录。

## 更新要求

1. 新事件使用唯一、稳定的 kebab-case `event_id`。
2. 记录的 commit、车型数据哈希、模型哈希和测试证据必须来自实际只读/测试结果；没有
   执行就写 `not_run`，不能推测为 pass。
3. 实机结果必须 `user_confirmed=true` 并引用对应 manual gate；没有用户明确确认不得批准。
4. 历史事件只追加，不改写。修正用新 event 并通过 `supersedes` 关联。
5. MCP 不可用时仍更新账本，置 `mcp_sync=pending` 或 `unavailable`。
6. 路径使用仓库内相对路径或 `仓库名:仓库内路径`。禁止本机绝对路径和 `../其他仓库/...`。
7. 已提交事件只追加、不改写。尚未提交、且 schema 不通过或证据结论写错的事件，可以在首次提交前修正，但必须追加事件说明修正原因，不能把修正伪装成原始记录。

## 账本

- [`events.jsonl`](events.jsonl)：当前已确认记录。
- [`schema/physics-event.schema.json`](schema/physics-event.schema.json)：机器校验格式。
- [样例模板](event-template.json)：复制后填写。模板不是账本事件，校验脚本不读取它。
- [`validate-events.mjs`](validate-events.mjs)：校验账本的字段、状态门和路径。它不证明测试真正跑过。
