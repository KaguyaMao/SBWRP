# 履带物理文档入口

最新交接：[`HANDOFF.md`](HANDOFF.md)（2026-10-30，当前代码、未完成项、模型工作副本与下一步）。下文旧阶段成绩须结合交接中的证据边界阅读。

更新时间：2026-10-28（最新 GameTest 42/48 通过、6 项失败；实机目视未确认）  
当前阶段：**D0/D1/K1 已完成；S1 编译层完成；B1 待复核；B2 阈值已冻结且 T1/T2 通过；S2/S3.1/S4 已接线但退出条件未满足；S4 已有可选 MCSP 响应层但 99A 未启用、滚阻未实现；S3.2 受限切向目标已实现但一格正向未达成**  
当前样车：`superbwarfare:ztz_99a`（ZTZ-99A）

## 1. 文档用途

这里是 SuperbWarfare 履带物理工作的唯一文档入口。后续实现、测试、实机调试和交接都必须
先遵守本目录中的硬约束，再参考历史记录。文档不替代代码测试；“已写入计划”不等于
“已实现”，“自动测试通过”也不等于“已经过用户实机确认”。

| 文档 | 用途 |
|---|---|
| [`CONSTRAINTS.md`](CONSTRAINTS.md) | 不可违反的约束、范围和术语 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 仓库分层、物理/渲染边界和线程模型 |
| [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) | MCSP 证据到 Bullet 实现的映射 |
| [`CORE_API_CONTRACT.md`](CORE_API_CONTRACT.md) | KaguyaPyCore 通用 TrackRun、生命周期和插值契约 |
| [`REFERENCE_IMPLEMENTATIONS.md`](REFERENCE_IMPLEMENTATIONS.md) | MCSP / YWZJ / MachineMax 参考范围与证据级别 |
| [`TRACK_RENDERING.md`](TRACK_RENDERING.md) | MCSP 风格履带路径和可见链节渲染 |
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | D0–F1 阶段、退出条件和实机暂停点 |
| [`B2_RUN_TEST_PLAN.md`](B2_RUN_TEST_PLAN.md) | B2 轮对运行段 fixture、读数与通过条件 |
| [`LEGACY_CLEANUP.md`](LEGACY_CLEANUP.md) | 旧实现归档和清理清单 |
| [`STATUS.md`](STATUS.md) | 当前代码、测试、阻塞点和证据哈希 |
| [`CURRENT_WIRING.md`](CURRENT_WIRING.md) | 当前运行接线；与目标文档冲突时以它描述现状 |
| [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) | 已冻结边界、B1 基线与尚未冻结的阈值 |
| [`TEST_MATRIX.md`](TEST_MATRIX.md) | 自动测试、机构和渲染验收矩阵 |
| [`VEHICLE_REGISTRY.md`](VEHICLE_REGISTRY.md) | 注册、批准、常规获取和附属模组规则 |
| [`DECISIONS.md`](DECISIONS.md) | 决策、撤销方案及原因 |
| [`REPOSITORY_BOUNDARIES.md`](REPOSITORY_BOUNDARIES.md) | 可入库文件、禁止入库文件和发布前检查 |
| [`RECORD_FORMAT.md`](RECORD_FORMAT.md) | 状态记录和 MCP 可重建格式 |
| [`SKILLS.md`](SKILLS.md) | 技能目录、触发条件、蒸馏和边界 |
| [`MCP.md`](MCP.md) | MCP 数据模型、资源和一致性协议 |
| [`MCP_OPERATIONS.md`](MCP_OPERATIONS.md) | MCP 建立、重建和日常同步 |
| [`history/`](history/) | 旧方案和清理前文档归档 |

本轮拓扑定稿与实施顺序见 [`../plan/wheel-end-attachment-plan-2026-10-23.md`](../plan/wheel-end-attachment-plan-2026-10-23.md)
与 [`../plan/mcsp-native-topology-and-sbw-plan-review-2026-10-23.md`](../plan/mcsp-native-topology-and-sbw-plan-review-2026-10-23.md)；
它们是计划文档，不是当前实现说明。

核心库侧配套文档在 KaguyaPyCore 仓库内：

- `KaguyaPyCore:docs/CORE_SCOPE.md`
- `KaguyaPyCore:docs/TRACK_RUN_API.md`
- `KaguyaPyCore:docs/PHYSICS_INTERPOLATION.md`
- `KaguyaPyCore:docs/PHYSICS_API.md`
- `KaguyaPyCore:docs/TRACKED_VEHICLE_SUPPORT.md`

跨仓库引用写成 `KaguyaPyCore:docs/...`，不使用本机绝对路径或相邻检出相对路径。

## 2. 当前架构一句话定义

> **基础地形使用 Minecraft 方块 AABB；履带载荷由相邻轮对动态运行段、轮端刚体、
> 独立悬挂和车体约束求解；可见履带按 MCSP 的方式由轮组路径、节距和相位重复渲染，
> 不参与物理承载。**

> **车型与悬挂数据以 SuperbWarfare 自有定义为准。** `TrackWheelDefinition`、
> `TrackSuspensionDefinition`、`TrackSuspensionOverride` 和 `TrackWheelRole` 是唯一
> 权威来源；不把 MCSP 的 `Suspension.Kind`、`carrier`、`hub`、`knuckle` 或 `spin`
> 变成 SBW 的数据字段。

> **但 MCSP 不只是“一般行为参考”。** 它对履带字段组织（轮序、逐轮 wrap、厚度、
> 节距、下垂、侧向摩擦、驱动分组）、可见路径求解与 Java/native 运行段桥接
> 有可逐行核对的源码贡献。这些字段分散在物理、渲染与驱动三层，
> 并非一体的 native 物理规范；详见 [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) §3.1。

这意味着：

- 不再使用车辆高度场、探针承载、固定楔面、`setPos` 或人工抬升。
- KaguyaPyCore 只提供通用 Bullet 能力，不实现车辆地形策略。
- SBW 负责车型、驱动、可选台阶辅助、生命周期、同步和渲染。
- 物理履带运行段和可见链节数量独立。
- 每个相邻轮对一个动态运行段；第一端为轮心转动副，第二端只锁法向。
- 每个带内轮站一个动态轮端刚体，无独立轮面自转；可见轮面自转只服务渲染。

## 3. 当前范围

- 第一阶段只开放 ZTZ-99A 的常规获取和验收；它是第一辆正向接入并验收的履带载具。
- 其他履带车型保留数据和开发通道，但不自动批准。
- 断带、脱轨、单轮损毁和修复只预留接口。
- 首轮性能目标为一台活动履带车，99A 稳定后扩展到四台。
- 普通轮式、飞行器和 VS 船体代理不在本次重构目标内。轮式 allowlist 保持为空，不恢复。
- 清理旧履带路径后必须跑通用车体回归，但回归不改变 allowlist，也不阻塞 99A。

## 4. 历史文档

旧的 `VEHICLE_PHYSICS_PLAN.md`、`VEHICLE_PHYSICS_HANDOFF.md` 和旧高度场文档已归档到
[`history/2026-10-20-pre-mcsp/`](history/2026-10-20-pre-mcsp/)。它们描述的整体探针、
高度场支撑和预处理履带方案已经撤销，不能重新成为默认实现路径。

## 5. 当前证据基线

- SBW 分支 `Physic-Update`，归并提交 `1b5c984`（父 `d345b3b`）；远端已强制覆盖。
- KaguyaPyCore 归并提交 `e90984a`（父 `ef5104a`）；远端已强制覆盖。
- 两个提交都不包含 `docs/**`、`.opencode/**`、`AGENT.md`、`PROMPT.md`、`plan.md`；
  文档与 `MCS_SOURCE_REVIEW/` 只在本工作树维护。
- 归并后重验：Core 全量 146 项 0 失败；SBW `physics.tracked` 以 `--rerun-tasks` 真实执行
  119 项 0 失败（含 B2.1–B2.4 直接 native 诊断）。
- 其中 `TrackedBeltMemberTest` 10 项与 `TrackedRunGeometryTest` 15 项是轮对运行段的 S1 用例；
  其余覆盖旧鞋链历史、悬挂编译与 B2.0 新观测契约。
- B2.1–B2.4 已执行，但都是**夹具证据**：B2.3 车体由竖直滑轨约束姿态，旁路由窗口累计
  接触计数观测；B2.4 虽已跑到 2400 子步，仍沿 Z 蠕移 18.5 m，因为滚阻/制动属 S4。
  B2 阈值已冻结（`ACCEPTANCE_LIMITS.md` §2.3）；B2.3/B2.4 仍是带夹具边界的证据，不能写成整车通过。
- 早期 `runGameTestServer` 4 项证明完整单侧 8+8 经 `TrackRunRuntime` 装配（T2），
  含真实轮端 mount 与全量拆卸断言。该装配断言仍在当前套件里。
- 2026-10-27：`runGameTestServer` 51 项通过；`physics.tracked` 180 项通过。S3.2 的一格正向
  用例改为**否定结论**：竖直立面没有合法平面内方向，早期“向上切向”回退等价于梯子并实测
  顶翻车辆，已移除。因此“辅助开启后爬过一格台阶”仍无证据，S3.2 未通过。
- 2026-10-26：`runGameTestServer` 48 项通过。覆盖钉区块保持/松开、末地关世界前残余、
  静置轮位、速度带内滑移、单侧顶墙、自由车体单侧垫、600 tick 行驶，以及经
  `serverInput → processInput → trackEngine → control → trackedCommand` 的真实实体输入链路。
  用户没有做实机暂停点。AABB 阶梯坡已覆盖；光滑连续斜坡不属于 AABB 门禁。
  S3.2 当时仍只有分类原型，没有辅助开启的正向 GameTest。
- 历史 JUnit 和 GameTest 结果属于旧架构，不能作为新架构通过证明。
- B1 因轮端刚体与安装约束变化进入待复核；B2 阈值已冻结，T1/T2 通过。S2/S3.1/S4 已接线但
  退出条件未满足；S3.2 只有分类原型，未通过。2026-10-26 新增 MCSP 传动响应层与悬挂
  `Spring`/`Damper` 可选字段，纯控制律 T0 通过；99A 未启用响应字段，滚阻仍未实现。
- Core `PhysicsJoints.free()` 的 ±1e9 有限窗口已改为倒置窗口（真自由）；
  `NativeJointFreeAxisTest` 5 项原生通过；受影响 Core 分组 83 项（TrackRun 47 + native/suspension 36）
  全部通过。
- `TrackedVehicleRuntime` 已接入 `VehicleBody`。B2 fixture 仍然不等于生产退出。
- MCP server、SQLite 索引和 OpenCode MCP 配置尚未建立。
