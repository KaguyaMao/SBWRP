# 参考实现与证据边界

更新时间：2026-10-28（逐行核对 YWZJ / MachineMax 线程与活动工作门控）  
状态：参考范围已登记；YWZJ 与 MachineMax commit 已固定；MCSP 反编译产物信息见第 4 节待补项。
本文与 `MCS_SOURCE_REVIEW/` 都不进入 git 提交。  
本文件不是验收证明，也不能替代 T0–T5 任何一级测试。

## 1. 用途

记录 MCSP、YWZJ、MachineMax 在本项目中的**参考范围**：哪些行为可作为证据、哪些只能作为
对照、哪些明确拒绝移植。所有结论按证据级别标注，未复核的条目必须写为待复核，不能写成
已验证事实。

证据级别：

| 级别 | 含义 |
|---|---|
| `confirmed` | 本地可逐行核对的源码或符号，结论直接来自该文件 |
| `behavior-reference` | 行为模式可作为设计参考，但实现语言/引擎不同，不能照搬 |
| `inference` | 由文件名、符号或调用关系推断，尚未逐行确认 |
| `unavailable` | 当前环境无法取得，需后续补齐 |

## 2. MCSP（本地参考检出 `MCS_SOURCE_REVIEW/`，不入库）

本地为反编译 Java 与 native 反汇编，无原始工程。文件存在性已核对。

| 文件 | 参考内容 | 级别 | 边界 |
|---|---|---|---|
| `quistaphys-java/src/quista/phys/body/PhysScene.java` | scene、body/joint 生命周期、step/readback、motor、joint angle/rate/load、belt native bridge | `confirmed` | 只作通用能力对照；Rapier 内部算法不可恢复 |
| `quistaphys-java/src/quista/phys/body/PhysJointSpec.java` | spherical/revolute/prismatic/fixed、anchor、axis、limits、motor | `confirmed` | Bullet 的约束语义不同，不能照搬参数 |
| `quistaphys-java/src/quista/phys/body/PhysJoint.java` | 通用 joint 句柄、motor、angle/rate/load、destroy | `confirmed` | 同上 |
| `quistaphys-java/src/quista/phys/body/PhysJointBody.java` | 父子 body 重建、关节重指向、失效 child 清理 | `confirmed` | 仅参考重建顺序与清理思路 |
| `quistaphys-java/src/quista/phys/gear/PhysGear.java` | 车型层轮组收集、belt 构建与状态恢复 | `confirmed` | **不移植**：车型层职责属于 SBW |
| `quistaphys-java/src/quista/phys/gear/PhysGearWheel.java` | 独立悬挂、行程和轮体职责分离行为 | `confirmed` | 仅作行为参考；不复制 MCSP schema |
| `quistaphys-java/src/quista/phys/gear/PhysGearBelt.java` | belt 字段模型：轮序、逐轮 `wrap`、`thickness`、`linkPitch`、`driveSlot`、`sag`、`lateralFriction` 及构造器校验 | `confirmed` | 字段语义参考；不整体复制。`linkPitch`/`sag`/`driveSlot`/`lateralFriction` 不在 `createBelt` 入参中，不能算作 native 物理输入 |
| `quistaphys-java/src/quista/phys/gear/PhysGear.java` | `rebuildBelts()` 逐轮收集 carrier/半径/inside/宽度/质量分配，调 `createBelt`；`readState` 回读运行段状态 | `confirmed` | Java 侧负载路径与状态序列参考；车型层职责属 SBW，不移植 |
| `quistaphys-java/src/quista/phys/body/PhysScene.java` (`createBelt`/`readBeltState`/`destroyBelt`) | JNI 入参形状：`carriers, radii, inside, widths, runMassKg, axle, depth, runState` | `confirmed` | 只能说明字段渠道，不能说明 native 内部拓扑 |
| `quistaphys-java/src/quista/phys/gear/PhysLoop.java` | 圆包络、外公切线、圆弧、sag、路径采样 | `confirmed` | 留给 SBW 渲染/几何层 |
| `quistaphys-java/src/quista/phys/body/PhysRamps.java` | 台阶参数：`minStep` / `maxStep` / `maxClimb` / `minRun` / `maxRun` / `minSpeed` / `fullSpeed`，含范围校验 | `confirmed` | 只作行为门控参考；MCSP 数值不能直接冻结为 SBW 阈值 |
| `quistaphys-java/src/quista/phys/gear/PhysGear.java` (`hold` / `bridgeSteps` / `readState` / `rebuildBelts`) | 辅助登记、坡面状态回读/重建、按轮序建 belt | `confirmed`（调用存在性） | 不能说明 native 坡面几何、过滤规则或接触时序；不移植车型逻辑 |
| `quistaphys-java/src/quista/phys/body/PhysScene.java` (`setBridgesSteps` / `readSlopeState` / `seedSlope`) | 辅助开关与坡面状态 JNI 通道 | `confirmed`（接口形状） | native 内部实现不可恢复；不得据此写成局部 HeightField 已复现 |
| `mcs-java/quista/mcs/chassis/ChassisGround.java` | 每物理步读取牵引并写每侧带速 | `confirmed` | 驱动语义属于 SBW；不能照搬 Rapier 参数 |
| `mcs-java/quista/mcs/platform/PlatformGearDraw.java` | pose/solve/advance/link，物理与可见履带分离 | `confirmed` | 可见链节与渲染属于 SBW |
| `mcs-java/quista/mcs/chassis/ChassisGround.java` | 物理步读取接地/牵引并写轮速 | `confirmed` | 驱动与差速语义属于 SBW |
| `quistaphys-java/src/quista/phys/game/PhysTerrain.java` | `VoxelShape` → AABB 地形 | `confirmed` | 与核心现有 AABB 路线一致 |
| `disassembly/linux-x86_64-symbols.txt` | `insert_run`、`remove_runs`、`destroy_belt`、`read_belt_state`、`belt_run`、`belt_sense` | `inference` | 符号名不能证明算法或数值 |
| `disassembly/slope-belts-contacts.asm` | `insert_run` 内可见 `RigidBodyBuilder::build`、`RigidBodySet::insert`、`ColliderSet::insert_with_parent`、`MultibodyJointSet::do_insert`、`ImpulseJointSet::insert` 与失败清理 `RigidBodySet::remove` | `confirmed`（调用存在性） | 仅能说明“动态刚体+碰撞体+关节被创建且可清理”；链节数、形状布局、铰链类型与求解参数不可恢复 |

采用的架构结论（`confirmed` 范围内的行为）：

1. 动态物理运行段与可见重复链节分离，可见链节不是物理刚体。
2. 通用 scene 提供 body/joint 生命周期、关节状态读取和重建能力。
3. 车型层（轮角色、belt、驱动、渲染路径）不进入通用层。
4. MCSP 对履带参数组织、可见路径求解与 Java/native 运行段桥接有可逐行核对的源码贡献，
   不能概括为“仅一般行为参考”；但这不等于 SBW 复制其车型 schema。
5. MCSP 的字段分散在不同层：厚度与轮序在物理侧被消费，
   `linkPitch`/`sag` 在可见路径侧被消费，`driveSlot`/`lateralFriction` 在驱动响应侧被消费。
   因此不能把整份 `PhysGearBelt` 当作单一 native 物理规范。
6. MCSP 与 SBW 的运行段拓扑是否等价，现有 Java 与反汇编证据不足以判断。

明确不进入 KaguyaPyCore：`PhysGear`/`PhysGearWheel`/`PhysGearBelt`/`PhysLoop` 的车型与
几何逻辑、`PlatformGearDraw` 的可见链节、`ChassisGround` 的驱动语义、Rapier belt 内部算法。

车型层边界：SBW 的 `TrackSuspensionDefinition` / `TrackSuspensionOverride` 是当前悬挂
模型的权威来源。MCSP 的 `Suspension.Kind`（`ARM`/`SLIDER`/`RIGID`）、`carrier`、
`hub`、`knuckle` 和 `spin` 只用于理解职责分离，不作为 SBW 字段、默认值或兼容层。

## 3. YWZJ 与 MachineMax

以下内容基于本轮固定提交的只读 checkout 逐行核对。它们是参考实现，不是性能基准，也不
证明其优化可直接迁移到 Bullet/Core。

### 3.1 YWZJ

仓库：`https://github.com/YWZJ-Studio/ywzj_vehicle`，分支 `1.20.1-dev`，核对提交
`bfdd69a5854279765471230ef234e09b342d02a8`（2026-10-28 抓取）。

| 目标 | 观察 | 级别 | 采用方式 |
|---|---|---|---|
| `entity/vehicle/AbstractVehicle.java:518-601` (`tick` / `tickPhysics`) | 服务端实体 tick 直接调用 `PhysicsEngine.tick`；未见独立车辆物理 worker | `confirmed` | 不是物理线程并行/调度优化的参考 |
| `vehicle/PhysicsEngine.java:83-123` | 实体 tick 同步执行 OBB、接触、悬挂与力矩计算；在该源码路径未见独立物理 worker | `confirmed` | 仅作同步模拟行为对照；不把游戏线程手工支撑替代 Bullet 接触 |
| `org/ywzj/vehicle/vehicle/part/SuspensionUnit.java` | 支撑采样、静载标定、压缩/回弹、客户端视觉插值 | `behavior-reference` | 留给 SBW 悬挂阶段；不进入核心 |
| 履带与渲染相关类 | 通过绘制或状态同步处理履带 | `inference` | 反例审查：渲染路径若无同步物理碰撞体会穿模；不能作为物理运行段证据 |

### 3.2 MachineMax

仓库：`https://github.com/Sweetzonzi/Machine-Max`，分支 `1.21.1`，只读核对提交
`ee1ffbdf7f869c3730c7a43c4c9d220d16433fe6`（2026-10-27 抓取）。

| 目标 | 观察 | 级别 | 采用方式 |
|---|---|---|---|
| `common/mech/vehicle/VehicleCore.java` | part 装配、track 身份、地形 ready/wait/timeout、物理线程任务提交 | `behavior-reference` | 借鉴生命周期门控与"等待就绪但不永久钉住"；核心不依赖该类 |
| `common/mech/ObjectManager.java:281-310` (`onPrePhysicsTick` / `onPostPhysicsTick`) | 由 Spark 物理线程事件驱动载具的物理更新 | `confirmed` | 线程边界对照；不是独立于 Spark 的第二套物理 worker |
| `common/mech/vehicle/VehicleCore.java:484-500` (`prePhysicsTick`) | `inLoadedChunk && !isRemoved` 时才遍历车辆部件和履带张紧更新 | `confirmed` | 可借鉴对无效/未加载工作做门控；须在 Core 侧先证明事件语义允许跳过 |
| `common/mech/vehicle/SubPart.java:300-320,393-415` (`prePhysicsTick`) | 非活动部件在少量活动状态维护后提前返回；形状最低点相对偏移按姿态缓存；临时向量字段复用见 301-308 | `confirmed` | 仅借鉴活动门控、可证明不变量缓存与 scratch reuse；不移植其 `LocalHeightField` 或地形扫描 |
| `common/mech/vehicle/track/TrackLink.java` | 主动轮 + 成员轮关系、GearJoint 重建、相邻成员张力 | `behavior-reference` | 只作对照；SBW 履带拓扑仍严格按 MCSP 运行段，不移植其 GearJoint 成员模型 |
| `common/mech/vehicle/connector/AbstractConnector.java` | `New6Dof` 逐轴限位/平衡点/刚度/阻尼、`enableSpring`、按等效质量与步长限制参数 | `confirmed`（公开源码阅读） | 借鉴约束轴配置与数值稳定性审计；**不**在 SBW 生产关节上启用弹簧，避免与 `PhysicsSuspensionRow` 重复计力 |
| `common/mech/vehicle/attr/connector/JointAttr.java` | `lower_limit` / `upper_limit` / `equilibrium` / `stiffness` / `damping` 字段与单位换算 | `confirmed` | 只作单位与字段语义对照；SBW 使用自有 `TrackSuspensionDefinition` |
| `common/mech/vehicle/SubPart.java` | 攀爬候选扫描、`climbableBlocks`、`stepHeight` 门控 | `confirmed` | 借鉴候选门控与拒绝逻辑；不移植其按部件高度缓存与 ground-only 过滤 |
| `common/mech/vehicle/collision/CollisionHandler.java` | 对候选方块改写法线、接触点、穿透距离并重算摩擦 | `confirmed` | **拒绝移植**：与“不修改接触几何、不伪造支撑”的约束直接冲突 |
| `util/terrain/LocalHeightField.java` | 局部采样、按材料分场、高度平滑、法线、轮球接触迭代 | `confirmed` | **拒绝移植**：与冻结的 `VoxelShape`→AABB 地形路线冲突 |

MachineMax 的台阶实现依赖“候选方块 → 高度场求解 → 改写接触几何”，因此只能作为
“门控条件可以借鉴、接触改写必须拒绝”的对照。SBW 的候选方案是：真实 AABB 接触上只写
受限切向目标，不修改法线、接触点、距离或车辆位置。

### 3.3 结论

- 可借鉴：生命周期门控、物理地形就绪等待、履带关系与可见链节分离、joint 重建顺序、
  多接触点诊断数据结构、约束轴与数值稳定性审计、攀爬候选门控。
- 不可移植：局部平滑高度场、轮球定点接触、接触法线/点/距离改写、手工 climb/抬升、
  ground-only 过滤、MachineMax 的 GearJoint 履带成员模型、车型驱动与渲染策略。
- 参考实现中的数值一律不是验收限值，见 [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md)。

## 4. 待补事项

以下内容当前仍需补齐或明确标为 `unavailable`：

- 上述外部文件的逐条行号引用（本轮已固定 commit 并核对实现；行号仍待整理）。
- MCSP 反编译产物的生成方式与版本信息。

MachineMax 固定为 `ee1ffbdf7f869c3730c7a43c4c9d220d16433fe6`，YWZJ 固定为
`bfdd69a5854279765471230ef234e09b342d02a8`。两者仅作代码行为对照；参考提交不进入项目依赖。

在这些补齐前，本文件只能作为范围声明，不能作为"已核对实现"的证据。
