# 仓库边界与发布前清理

更新时间：2026-10-20

## 1. 可以进入仓库

- Kotlin/Java 源码、通用 API 和测试源码。
- 纯文本架构、计划、状态、决策和验收文档。
- 必要的车型 JSON、模型资源和可审计的测试夹具资源。
- Gradle 构建定义、版本定义和用于编译源码的配置。
- Skills 源码和 OpenCode 配置。
- 事件 schema、模板和 `events.jsonl`。

## 2. 禁止进入仓库或上游

- `build/`、`.gradle/`、`.kotlin/`、编译缓存和临时 native 解压目录。
- `run/`、游戏存档、世界、`logs/`、崩溃转储和测试运行目录。
- 测试客户端/服务端实例及其配置。
- 客户端/服务端启动脚本、下载脚本、手柄/设备桥接脚本和本机硬件映射。
- 依赖下载所得的本地 mod jar，除非另有明确发布许可和单独决策。
- MCP server、SQLite 搜索缓存、token、密钥和本机数据库。
- 本机绝对路径、用户主目录和依赖相邻检出布局的跨仓库相对路径。

可提交引用使用 `仓库名:仓库内路径`。

## 3. 归档内容

`docs/physics/history/` 和 `docs/history/` 中的归档文档、状态快照和源码快照可以入库，
但必须：

- 不进入 Gradle source set。
- 明确标记“仅历史，不可作为实现输入”。
- 不包含本机日志、存档、构建产物或凭据。
- 记录原路径和 SHA-256。

## 4. 当前已知遗留

历史工作树中已有 `tools/client-test/`、`runTestClient.bat` 等测试环境文件，以及本地提交
`d345b3b` 的依赖获取脚本。它们不能推送到上游；发布前必须由维护者决定是移出提交、
迁移到工作区外，还是按仓库政策重新整理。

D0/D1 文档轮次不自动删除这些用户已有文件。

## 5. 提交前检查

```bash
git status --short
git diff --check
git ls-files | rg '(^|/)(build|run|logs|saves|world|crash-reports)(/|$)|\\.(jar|mca|mcr|log|sqlite|db)$'
git ls-files tools/client-test runTestClient.bat
```

发现测试环境文件时，先停止发布，不用 `.gitignore` 误以为“已安全”：被追踪的文件仍会
进入提交和上游。

## 6. Skills 和配置

- Skills 放在 `.opencode/skills/<id>/SKILL.md`。
- 如使用仓库级 `opencode.jsonc`，保留 `$schema` 和无关配置，只添加需要的 `skills` 或
  `mcp` 字段。
- 不在仓库保存 OAuth、token、SQLite 或本机路径。
