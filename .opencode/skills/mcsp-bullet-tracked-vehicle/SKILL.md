---
name: MCSP Bullet Tracked Vehicle
description: Plan, implement, review, or diagnose MCSP-style tracked vehicle physics on Bullet, including repository boundaries, stage gates, physical belt runs, and MCSP-style rendering separation.
---

# MCSP 风格履带载具总技能

开始前阅读：

1. `docs/physics/README.md`
2. `docs/physics/CONSTRAINTS.md`
3. `docs/physics/ARCHITECTURE.md`
4. `docs/physics/IMPLEMENTATION_PLAN.md`
5. `docs/physics/STATUS.md`
6. `docs/physics/CURRENT_WIRING.md`
7. `KaguyaPyCore:docs/CORE_SCOPE.md`
8. `KaguyaPyCore:docs/TRACK_RUN_API.md`

跨仓库引用使用 `仓库名:仓库内路径`，不写本机绝对路径或 `../KaguyaPyCore/...`。

## 目标

用 Bullet 复现 MCSP 的履带物理结构，并用 MCSP 的方式渲染履带：

- 动态负重轮和独立悬挂。
- 主动轮、导动轮、张紧器和动态履带运行段。
- 运行段按相邻轮对组织，两端约束到不自转的轮端刚体。
- 物理运行段和可见重复链节是两套对象，但共享包络输入。
- 基础地形是 Minecraft 方块 AABB。
- 台阶辅助是 SBW 可选策略，不是核心高度场；旧 `VehicleBody.stepAssist` 的无界/永久抬升
  已撤销，S3.2 的受限 `TrackedStepAssist` 仍未实现。
- 光滑连续斜坡不是方块 AABB，不属于 S3.1 门禁；AABB 阶梯坡、楼梯和阶梯楔是范围内地形。
- MCSP 传动响应已按字段语义实现为可选层：`Accel`/`Brake`/`PivotSpeed`/`SteerTime`，
  以及默认关闭的 `TractionLimit`。99A 未声明这些字段，不得据此声称 S4 已通过。
- 悬挂可选 `Spring`/`Damper` 直接 SI 参数；未声明时保留频率 + 阻尼比模型。

## 硬边界

- KaguyaPyCore 只提供通用 Bullet API、TrackRun 和快照插值。
- 车辆、地形策略、驱动、渲染和验收全部在 SBW。
- 不使用 `LocalHeightField`、`TerrainSupportField`、`SUPPORT_PAIR`、`HEIGHT_FIELD`、
  `SLOPE`、`RUN_BLOCKS`、探针承载、固定楔面或人工抬升。
- 可见链节不是物理刚体，不与物理运行段一一绑定。
- 运行段数、可见链节数和模型骨骼数分别记录。
- 运行段第二端只锁一个法向平移，不得做成三平移全锁。
- 带内轮端刚体无独立轮面自转；可见轮面自转只服务渲染。
- 不直接移植 Rapier API 或参数。
- 反编译 Java 和 native 反汇编只是证据，不是原始 Rust 源码。

## 工作顺序

1. 确认当前阶段和 `CURRENT_WIRING.md` 的真实状态。
2. 先完成文档和旧实现清理，不越过阶段门。
3. 核心 API 变化先更新 KaguyaPyCore 文档和测试。
4. 车辆实现按 D0 → D1 → K1 → S1（拓扑）→ B1 → B2 → S2 → S3.1 → S4 → S3.2 → R1 → N1。
5. 每个阶段先 T0/T1，再 T3，再 T4，最后实机暂停点。
6. 任何物理修复只改一个环节，不用调参掩盖契约错误。

## 必须记录

- 仓库、分支、commit 和车型。
- 数据/模型哈希。
- 测试命令、结果和失败摘要。
- 运行段数、可见链节数和模型骨骼数。
- 接触来源：`TERRAIN_AABB`、`TRACK_RUN`、`WHEEL_BODY`、`STEP_ASSIST`、`STRUCTURAL_OBSTACLE`。
- `core_commit` 和受影响阶段。

状态写入 `docs/physics/STATUS.md` 和 `docs/physics/records/events.jsonl`。
未冻结限值不得写成通过。实机暂停点必须等待用户确认。
