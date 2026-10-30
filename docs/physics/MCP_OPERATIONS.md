# MCP 建立与日常同步操作手册

更新时间：2026-10-20  
状态：M0/M1 文档与事件账本已建立；MCP server、SQLite 和 OpenCode 配置未建立

## 1. 生命周期

- **M0 未建立：** 当前 MCP discovery 为空，没有 server/database；仓库文档和 JSONL 是事实。
- **M1 可重建账本：** schema、模板、追加账本已存在，可人工评审。
- **M2 本机索引：** 实现 JSONL reader、SQLite migration、幂等全量/增量导入和查询。
- **M3 MCP server：** 暴露只读 resources/查询；写入先追加 ledger。
- **M4 OpenCode 连接：** server 存在后再配置 local MCP，检查连接和重建一致性。

## 2. 每次工作后同步

```text
检查 git 状态/commit
 -> 判断是否产生决策、阶段、测试、实机、哈希事件
 -> 追加 records/events.jsonl
 -> schema/引用/哈希校验
 -> 更新 STATUS/DECISIONS/阶段文档
 -> 硬边界改变则更新 Skills
 -> MCP 可用时增量导入
 -> 对比 event IDs/source hashes
 -> 记录同步状态
```

只读搜索不必创建事件；一旦产生通过、失败、阻塞、批准、撤销或用户确认，就必须记录。

## 3. 检查命令

```bash
git diff --check
node docs/physics/records/validate-events.mjs
git ls-files | rg '(^|/)(build|run|logs|saves|world|crash-reports)(/|$)|\\.(jar|mca|mcr|log|sqlite|db)$'
git ls-files tools/client-test runTestClient.bat
opencode mcp list
```

最后一条在 M0 阶段可能显示无 server；不能用“命令可执行”代替连接证明。

## 4. 冲突处理

- SQLite 与仓库不同：仓库胜出，重建 SQLite。
- 决策相反：按时间和 source commit 排序，追加 supersedes，不删除旧事件。
- 用户与旧文档冲突：追加新 decision，归档旧文档并建立链接。
- 未提交代码：`source_commit=WORKTREE` 或当前 HEAD 加 `worktree_dirty=true`。
- MCP 不可用：`mcp_sync=pending/unavailable`，恢复后补导入。
- 未确认的实机：保持 `waiting_manual`，不能变成 `manual_passed` 或 `approved`。
- 只做 `JSON.parse` 不能写成 schema 校验通过。校验命令是 `validate-events.mjs`。

## 5. 跨仓库引用

事件和文档中的其他仓库文件使用 `仓库名:仓库内路径`。检查时拒绝：

- 以 `/` 或 `~` 开头的路径；
- 包含 `../` 的路径；
- 用户主目录、主机名、JDK 安装路径和设备路径。

## 6. 安全

MCP 配置和 OAuth/token 在用户配置目录或环境变量中；不写入仓库。SQLite、WAL、FTS、
游戏运行目录、日志、存档、设备配置和客户端/服务端实例均不入库。
