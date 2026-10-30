# 履带物理当前状态

更新时间：2026-10-19  
本记录类型：状态快照，不是通过证明

## 1. 仓库和证据

| 项目 | 值 |
|---|---|
| SBW 分支 | `Physic-Update` |
| SBW 本地 HEAD | `d345b3be3328ea14af25047fde542b491729fd6f` |
| SBW 远端关系 | 本地领先 `origin/Physic-Update` 一提交，未推送 |
| KaguyaPyCore HEAD | `ef5104aaf1c1cbb8f0d176506dfbbd8523f60b38`，工作树有未提交的 P1.1 代码 |
| 99A 机构 JSON SHA-256 | `89f8831a9ab1af9120217d40e43fe54af6e6d6aeb9a6f7a895f34d4e1509fa8f` |
| 99A 模型 JSON SHA-256 | `fe7099f7a850bf3b8730471065c1b74ee53cd29dc182ff86dec6abe8006bf747` |
| 本轮代码状态 | KaguyaPyCore 把 `LIVE` 支撑对接入区段物理线程，实时计划不再发出楔面；SBW 增加了调试测试实体、命令和 T3 测试。未提交、未推送 |

## 2. 当前完成度

| 能力 | 状态 | 说明 |
|---|---|---|
| Spark 每 Level 物理线程 | 已有 | 60 Hz、每 MC tick 三子步、单飞帧和快照已在核心库中 |
| 区段地形快照/构建 | 已有 | 现有 `SectionSnapshot`/`TerrainShapePlan`，需要扩展高度场支撑表面 |
| 通用高度场查询 | 部分已有 | `LocalHeightField` 仍是采样辅助。`TerrainSupportFieldBuilder` 是未接线的纯数据契约，不是运行时唯一支撑 |
| 99A 数据编译器 | 候选已有 | 能解析角色、轮位、节距和闭链候选，未接入游戏运行时 |
| 99A 动态轮/板测试机构 | 候选已有 | `TrackedRig` 只在测试层使用，存在自由度、质量和闭链几何问题 |
| 实际游戏动态闭链 | 未接入 | `VehicleBody` 仍使用旧探针/旧牵引路径 |
| 高度场唯一支撑 | 部分接线 | 区段物理线程已安装 `LIVE` 支撑对；`SUPPORT_PAIR` 测试实体已通过真实 Level 支撑/失效/边界测试。现有车辆仍使用 `DRIVABLE`，碰不到该对 |
| 主动轮通用耦合 | 未实现 | 新 `TrackDrivetrainDefinition` 尚未驱动物理板链 |
| 99A 常规入口批准 | 待实现 | 当前旧 allowlist 仍包含多台履带车 |
| 断带/脱轨/修复 | 未实现 | 仅在计划中预留 |

## 3. 历史 JUnit 基线

来源：2026-10-18 本地报告。当前文档轮次未重跑，因此以下不是本轮结果。

- 7 项测试：4 通过、3 失败。
- 失败：
  - `TrackedRigStaticTest.a_wheelsAndSpringsCarryTheHullAtTheAuthoredPose`
    - `spring force not near static load: 0.210146...`
    - 这是相对误差，不应解释为“只承载 21%”。
  - `TrackedRigStaticTest.b_closedBeltStaysClosedUnderLoad`
    - `dy=-0.10023558`
    - `worstPitchError=0.016635...`，闭链测量本身还需要重审。
  - `TrackedVehicleCompilationTest.ztz99aCompilesToAClosedMechanism`
    - 旧断言把车体局部轮心与模型坐标直接比较，差值包含 `ChassisOrigin.y`，不能直接作为
      骨骼提取错误的证据。
- 通过的弹簧探测测试主要打印结果，缺少足够的行为断言；不能据此冻结弹簧 API 语义。

## 4. 当前阻塞

1. 高度场必须改为实际支撑判定，不是探针 fallback。
2. `TrackedRig` 尚无游戏生命周期接线和 owner 管理。
3. 导轮张紧自由度与当前锁轴冲突；闭链求出的 idler offset 没有应用到轮体。
4. 履带板当前使用简化 Box，未完整消费编译出的板形状/销位置。
5. 当前 `wheelAxleSpring` 使用的旧式 spring 与 `New6Dof`/Machine-Max 单位语义尚未通过
   有断言的原生测试。
6. 旧 `TerrainSlopePlanner`、探针和预处理轨迹仍在实际路径，必须先拆除接线再验收新架构。
7. SBW 当前本地提交包含测试依赖脚本，按用户约束发布前必须清理/迁移，不得推送。

## 5. 阶段状态

| 阶段 | 状态 | 备注 |
|---|---|---|
| P0 文档与边界 | 进行中 | 接线清单和限值缺口已补记；等待证据确认门，未标完成 |
| P1 核心高度场 | 进行中 | 实时支撑是已写入的 1 格共享边高度场，已由区段物理线程安装。区段不再安装固定楔面，请求 `DRIVABLE` 的物体只剩原始方块箱。支撑对仍不在 `DRIVABLE`。石头仍是 `UNKNOWN`。求解前回调时序未证明；改用支撑 shape，不改 manifold。限值待用户确认。不能标完成 |
| P2 通用悬挂/闭链 | 未开始 | 先完成原生单位和自由度测试 |
| P3 99A 几何数据 | 未批准 | 候选 JSON 存在，坐标/板形状/质量仍需审计 |
| P4 99A 生命周期 | 未开始 | 暂不接入 `VehicleBody` |
| P5 99A 驱动差速 | 未开始 | 只允许通用轮带耦合 |
| P6 99A 高度场越障 | 未开始 | 不能使用旧楼梯/楔面结果 |
| P7 同步性能 | 未开始 | 目标一台后四台 |
| P8 其他车型 | 未开始 | 每车独立暂停和批准 |

## 6. 状态标签规则

中文标签只用于本文。机器记录使用 schema 的 `status`，对照见 [`RECORD_FORMAT.md`](RECORD_FORMAT.md)。

- `候选` / `candidate`：代码或数据存在，但未接线。
- `已接线` / `wired`：运行路径使用，但尚未满足测试退出条件。
- `自动通过` / `auto_passed`：规定的自动测试通过，且使用的限值已经冻结。
- `待实机` / `waiting_manual`：自动测试通过，等待用户做游戏内检查。
- `实机通过` / `manual_passed`：用户明确确认对应车型、场景和 A–D 门。
- `批准` / `approved`：自动、实机、文档证据和哈希均已记录，可进入常规获取入口。
- `证据已确认`不是 `manual_passed`。P0–P3 的用户确认只允许阶段离开“进行中”，不得写成实机通过或批准。

## 7. Skills 与 MCP 状态

- 已有 Skills：`tracked-vehicle-physics`、`tracked-height-field`、`tracked-mechanism`、
  `vehicle-acceptance`、`physics-records-sync`；KaguyaPyCore 侧有 `physics-core-support`。
- Skills 文档已写入 `.opencode/skills/`，但是否被某个 OpenCode 会话加载必须以实际
  `skill` 调用为准。
- 事件 schema、模板和 `events.jsonl` 已写入 `docs/physics/records/`。
- 当前 MCP resource discovery 为空；没有 MCP server、SQLite 索引或 OpenCode MCP 配置。
- 本轮文档同步事件的 `mcp_sync` 为 `unavailable`，后续 server 建立后需从 JSONL 重建。
- 跨仓库引用规则为 `仓库名:仓库内路径`。未提交事件 `skills-mcp-docs-2026-10-19`
  在首次提交前修正了截断的 `source_commit` 和相邻目录路径；原因记录在
  `cross-repo-path-convention-2026-10-19`。
- 该事件里“schema 校验通过”只做了 `JSON.parse`，已撤回为 `not_run`。撤回说明见
  `doc-rigor-corrections-2026-10-19`。
- 当前接线见 [`CURRENT_WIRING.md`](CURRENT_WIRING.md)。未冻结阈值见
  [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md)。
