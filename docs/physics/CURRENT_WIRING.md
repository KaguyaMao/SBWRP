# 当前运行接线清单

更新时间：2026-10-30（调试线框覆盖已加载履带车，并叠加车体 OBB、轮圆柱和运行段。同层交叉坡面取较低包络；穿过机构内带的坡面不安装。物理运行段仍未按坡面拆分。轮体接地未关闭。）

记录类型：只读盘点，不是目标架构，也不是测试通过证明

本文列出 2026-10-20 D1 清理后工作树中的真实接线。目标架构见
[`ARCHITECTURE.md`](ARCHITECTURE.md)，撤销边界见 [`CONSTRAINTS.md`](CONSTRAINTS.md)。

## 1. 新目标尚未接线

| 目标符号 | 预期位置 | 当前状态 |
|---|---|---|
| `TrackRun` | KaguyaPyCore 通用物理层 | 已实现，T0/T1/T2 通过 |
| `TrackRunSnapshot` | KaguyaPyCore 通用快照 | 已实现，逐子步发布，含 runId/poseEpoch/linkBodyIds |
| `PhysicsStateInterpolator` | KaguyaPyCore 通用插值 | 已实现，保留关节采样并隔离不连续点 |
| `TrackedVehicleRuntime` | SBW `physics/tracked/` | **已接线（S2 草稿）**：双侧轮端、mount、run、外端 joint、悬挂+张紧、账本回滚、`closeAsync` 可等待；质量计划与显式重力已接入。故障注入、速度恢复、钉区块保持/松开后卸载、末地关世界前残余、静置轮位都有 GameTest。用户目视未确认 |
| `McspTrackPath` | SBW 客户端渲染 | 未实现；属于 R1 |
| B2 native fixture | SBW 测试代码 | 已执行；直接 Bullet `PhysicsSpace`，与生产接线分开 |
| `TrackedRunRuntimeGameTests` | SBW GameTest | 已执行；完整单侧经 `TrackRunRuntime` 装配（T2） |

S1 编译层符号。运行时已经消费其中的 spec、悬挂、joint factory 和质量计划；渲染路径仍未接入：

| 符号 | 位置 | 状态 |
|---|---|---|
| `BedrockModelGeometry` | SBW `physics/tracked/` | 已实现，T0 覆盖 |
| `ClosedTrackLoopSolver` | SBW `physics/tracked/` | **legacy 专用**；仅在 `LegacyShoeChainCompiler` 内使用 |
| `TrackedDefinitions` / `TrackedSpec` | SBW `physics/tracked/` | 已实现；`BeltOrder`/`Wrap` 强制显式 |
| `TrackedVehicleCompiler` | SBW `physics/tracked/` | 已实现：主路径只编译轮对机制，legacy 鞋链失败仅警告 |
| `TrackedRunSpecs` | SBW `physics/tracked/` | 已实现：`compileRuns()` 每 run 一条单 link spec |
| `LegacyShoeChainCompiler` / `LegacyShoeChainRunSpecs` | SBW `physics/tracked/legacy/` | 历史 79 鞋链，独立且不阻塞新路径 |
| `TrackedSuspensionCompiler` | SBW `physics/tracked/` | 已实现（B1）；S2 runtime 每子步对 Support 行施力，张紧器预载/阻尼已执行 |
| `TrackedBeltGeometry` | SBW `physics/tracked/` | 已实现：belt 平面、宽度、厚度与来源 |
| `TrackedRunGeometry` | SBW `physics/tracked/` | 已实现：有符号半径切线、四种 wrap、两端 joint plan |
| `TrackedRunGeometryAudit` | SBW `physics/tracked/` | 已实现：run/轮实体间隙与非相邻 run 相交 |
| `TrackedConstraintSpec` | SBW `physics/tracked/` | 已实现：`CompiledJointPlan` 六轴 + 两端 local frame |
| `TrackedMassAllocator` | SBW `physics/tracked/` | 已实现：方案 A 预算分配，策略 ID 版本化 |
| `TrackedCompiledSpecValidator` | SBW `physics/tracked/` | 已实现：最终门，含 Core Float/spec 校验 |
| `TrackedJointFactory` | SBW `physics/tracked/` | 已实现：`CompiledJointPlan` → `New6Dof` 的唯一入口，B1/B2/S2 共用 |
| `TrackedWheelMountTest` | SBW 测试代码 | 已实现（B1 复核，T1），5 项原生用例 |
| `CompiledWheelMount` / `CompiledRunAttachment` | SBW `physics/tracked/` | 已实现；runtime 消费 mount 的 springRow 与 tensioner 预载/阻尼 |
| `PhysicsSuspensionRow` | KaguyaPyCore `physics/` | 已实现（B1），车辆无关的逐站双向弹簧—阻尼原语 |
| `PhysicsJoints.free()` | KaguyaPyCore `physics/body/` | 已修正为倒置窗口（真自由）；`NativeJointFreeAxisTest` 5 项原生通过 |
| `B2RunObservation` / `TrackRunAssemblyProbe` | SBW `physics/tracked/` | 已按轮对运行段改写（B2.0，T0），旧鞋链探针降为 `LegacyB2AssemblyProbe` |
| B2.1–B2.4 运行段 fixture | SBW 测试代码 | 已执行（T1）；B2.3 单侧滑轨夹具、B2.4 自由双侧车体 |
| `TrackedRunRuntimeGameTests` | SBW GameTest | 已执行（T2）；完整单侧 8+8 经 `TrackRunRuntime` 与外端 `withLinkBodies` 装配 |

**S2 已接线，但阶段退出条件未满足。** `VehicleBody` 在 `trackedVehicle && !clientSide`
时创建 `TrackedVehicleRuntime`，`preStep` 调用 `applySuspension` / `applyDrive` /
`applyStepAssist`。`closeAsync()` 先等待 runtime 清理再移除车体。质量由
`VehicleMassPlan` 按方案 A 拆分，轮/run 使用与车体相同的显式重力。
`applyStepAssist` 只分类、不施加辅助。这是**未完成状态**，不是目标：S3.2 要求可选辅助
在真实 AABB 障碍上完成有界爬越，且当前没有辅助开启的正向 GameTest。`applyDrive` 不施加净力；
接触目标由 `onContactConceived` / `onContactProcessed` 写入，`contactDriveEnabled` 默认为 `true`。
每侧目标速度只按油门缩放一次。COAST 清除传送带标志，不把旧目标留在接触点上。`MaxDriveTorque` /
`MaxBrakeTorque` 只收紧接触摩擦系数，不是已证明的扭矩闭环。

**2026-10-30 地形与碰撞线框：** `/sbwtest bt show`、`hide`、`status` 需要权限 2，不要求乘车。按下后显示当前维度已加载履带车的已安装网格，以及物理线程发布的车体 OBB（黄）、轮圆柱（白）和运行段盒（绿）。没有 Bullet 刚体的履带车在 `status` 里标明未接入，不拿伤害盒冒充。开启视图不加载区块，也不唤醒刚体。网格替换时发给订阅玩家。客户端按真实三角形画边：橙色是生成坡面并带法线，青色是平地，红色是墙，紫色是朝下的面。超过 24000 个三角形时只发送离车最近的部分，不另建几何。`status` 同时输出左右 run/wheel 法向与切向冲量、车体底和前进/偏航速度。线框不参与求解。

**2026-10-30 坡面冲突：** 车头、车尾和车底的纵向一格高差仍可建坡，左右侧面保持立面。同层重叠坡面在写入三角形前取较低包络，接缝不重复发射；垂直间距大于 `MaxClimb` 的两层都保留。坡面表面穿过已发布轮体或运行段探针内带时，该坡不安装，立面保留。轮底贴在坡面上不算侵入。机构探针按 0.25 m 量化后才进入网格哈希，避免悬挂微动每次都换网格。这只证明规划几何，不证明 99A 整车爬坡或行驶通过。物理运行段仍是相邻轮对的一具刚性盒，本轮没有按坡面增删刚体。

**2026-10-29 坡面与可见带速：** 坡面足迹必须被同高度 AABB 完整覆盖，不能用“格子里有任意盒子”代替。坡三角形沿坡长约 1 米分段。可见履带相位和轮面自转使用运行段测得速度，不用油门目标。`lastSupportContacts()` 增加左右 run/wheel 切向冲量、车体前进速度和偏航角速度；轮体切向大于运行段时只记警告，不关闭轮地碰撞。爬坡 GameTest 已改为断言等待，并在物理线程施加悬挂和驱动；本轮未重跑。

**2026-10-26 传动响应层：** `TrackedDriveController.Controller` 按 `1/60 s` 子步复现
MCSP 的 `slew`、转向杠杆和 `PivotSpeed` 差速；仅当车型声明 `Accel`/`Brake`/`PivotSpeed`/
`SteerTime` 时启用。99A 当前未声明，仍走原无状态 `resolve`。`onResolvedContact` 累计
`tractionImpulseNs` 作为观测；`TractionLimit` 默认关闭。

**滚阻未实现（2026-10-27 实测）：** COAST 只清除传送带目标，不写速度目标。
`RollingResistance` 不再被写成接触摩擦系数。两种“把滚阻写成接触速度目标”的写法都实测
注入能量：按运行段自身表面速度衰减时 20 tick 内 0.98→0.15 m/s（重刹）；按车体速度衰减时
松油门后 120 tick 内反而加速 18%。`TrackedContactDrive.coastTarget()` 保留为未调用候选，
不得写成已实现的滚阻。

`GroundVehicleAllowlist` 只登记 `superbwarfare:ztz_99a`（`Model.TRACK_RUN`）。
`contains()` 目前仍是静态集合，不执行编译验证。

**仍未满足退出条件。** 最新完整 terrain regression 48 项中 6 项失败：

`thebeltdrivesunderabridgedeck`、`aleftsteeryawstheotherway`、
`aoneblockstepclimbsonthebulletramp`、`contactmotiondrivesthehullforward`、
`steeringyawsthehull`、`coastremovestheconveyortargetinsteadofdriving`。
本轮 `contactmotionreversesthehull` 与 `aresting99akeepsitswheelsonthebindpose` 通过；它们上一批失败，
单次结果变化不证明稳定修复。

下列条目记录的是已经由各自断言覆盖的 lifecycle/采样能力，不代表上面 6 项测试通过：

- 地形缓存卸载不会拆掉仍在世界里的车。
- 关闭 `KeepChunkLoaded` 后，载车区块卸载再加载，同一 UUID 回来。
- 默认钉区块会在强制票撤掉后仍保住区块。松开钉并清掉 `POST_TELEPORT` 票后，区块卸载、刚体清掉、同一 UUID 回来。
- 末地物理世界单独关闭。车辆清理注册为 Core `addShutdownParticipant`，在停止接纳后、native teardown 前于物理线程按 joint/run/body 顺序拆除；关闭监听器在该阶段断言该车 owner 的刚体数已为 0。关闭监听器只证明 world 还在，不代替这次拆除。
- 关闭本身可等待：`VehiclePhysics.closeFuture(entity)` 返回真实 teardown Future。`isManaged == false`
  只表示实体已移出管理表，拆除任务可能还没跑完，不能当作“刚体已消失”。
- 台阶采样读物理线程发布的轮位快照，游戏线程不再直接读 Bullet body。
- S3.2 已实现为“真实接触上的受限切向目标”，但**一格正向爬越未达成**。
  `publishStepPose()` 在 runtime 自己的 pre-step 钩子发布（裸 runtime 也有调用方），
  `sampleStepTerrain(Level)` 在游戏线程采样，`applyStepAssist()` 在物理线程刷新诊断。
  `applyBeltContact()` 只写 `contactMotion1` + 正交摩擦基，不改接触几何、不施加冲量。
  竖直立面的前进投影为零时**不写方向**：旧“向上切向”回退等价于梯子，实测把车顶翻并摔到
  y=-104，已移除。因此当前没有合法的竖直面辅助方向；一格台阶需要另一类机制。
- 接触驱动写入有两条路径：`onContactConceived`（Core 1.3.3，接触点进入 manifold 之前，
  覆盖新接触点首个子步）与 `onContactProcessed`（刷新已存在的持久接触点）。
  两者共享 `applyBeltContact`，不会分叉。`conceivedContactMotionCount()` 是首个子步钩子
  真的被调用的可观测证据。
- 滑移有实测分布 `SlipBand`（样本数、最小、最大、均值），不再只有 `target + 1 m/s` 健康检查；
  但它仍未冻结成验收带。`aLongPartialThrottleSoakStaysFiniteAndSupported` 跑 1800 tick。
- S4 有专门 GameTest 走真实实体输入链路：mock player 上车 →
  `serverInput` → `processInput` → `trackEngine` → `control` → `trackedCommand`，
  而不是直接调用 `TrackedVehicleRuntime.setDriveCommand`。
- 静置 220 tick：轮心相对绑定误差小于 0.45 m，导轮位移沿著作张紧轴投影，两侧都有地形载荷。这不是用户目视。
  该断言在中间一轮失败（左侧 `normalImpulseNs=0`），在最新完整重跑通过；由于结果波动，需重复验证，
  不能将一次通过视为稳定修复或用户目视验收。
- 滑移等于切向速度减目标。`maxSpeed + 1 m/s` 是健康检查，不是 `ACCEPTANCE_LIMITS.md` 里的冻结限值。一侧顶墙时该侧滑移更大，且仍有法向载荷。
- 自由车体在翻离单侧垫之前，垫子那一侧承担载荷。
- 600 tick 半油门保持有限、不穿地、仍有位移。
- 实机暂停点 A/B/C 没有用户确认。光滑连续斜坡不属于 AABB 范围；AABB 阶梯坡已覆盖。
  S3.2 受限切向接触方案已接线，但一格正向爬越未达成，未通过。

**2026-10-28 terrain scheduler correction and physics metrics:** `requestedTerrainWindow` and `installedTerrainWindow`
are separate; `terrainBuildQueued` spans worker build through physics apply; pending-plan requeue uses
a single drain helper; each plan carries a full `TerrainRevisionToken` and is installed only while
Core `withRevisionToken` proves the matching cached geometry; `requestRegion(consumerId, bounds)` is
released on runtime close. Core 1.4.3 `PhysicsStepMetrics` is now consumed by diagnostics. Full GameTest:
42/48 passed, 6 failed; reverse drive and static bilateral load passed in this run after failing in the
previous one. A ramp failure frame measured `stepNanos=805.0 ms`, Bullet update `666.7 ms` including
post callbacks `193.5 ms`, capture `87.6 ms`, 4,115 bodies / 1,104 terrain bodies. A forward-drive
failure frame measured `687.7 ms` total, Bullet update `559.4 ms` including post callbacks `136.0 ms`,
capture `118.0 ms`, 1,205 bodies / 341 terrain bodies, 7 terrain requests combined and 6 dropped requests.
The server also reported ~58 seconds / 1,160 ticks behind. Both scenes failed motion criteria with terrain
ready; these measurements show expensive frames but do not prove why the physics behavior failed. Post
callbacks include resolved-contact dispatch and all post-step listeners, so the current split cannot assign
time to manifold scanning or an individual listener. The `physicsTestGroup` Gradle property is currently
only forwarded as a system property and is not consumed by active test sources, so it is not a valid test
filter. The current Gradle GameTest launch collects batches before server startup; no supported per-test
selection entry point has yet been confirmed, and no isolated rerun has been claimed. See `STATUS.md` §4
and the superseding ledger entry.

悬挂数据流（S2 runtime 每子步消费）：

```text
SBW TrackSuspensionDefinition / TrackSuspensionOverride
  -> TrackedVehicleCompiler
  -> CompiledSuspension（含 BumpStopStart / BumpStopExponent）
  -> CompiledSuspensionStation（完整保留 CompiledSuspension）
  -> toRowSpec()
  -> KaguyaPyCore PhysicsSuspensionRow（双向带符号力）
```

`CompiledSuspensionStation` 不再复制部分字段，也不再硬编码缓冲块起始比例；
`anchorFor()` 返回 authored 轮心，使 bind pose 下两端 constraint frame 重合。

## 2. 已删除的旧路径

### KaguyaPyCore

- `LocalHeightField`、`terrain/support/` 整个目录。
- `TerrainSectionBuild`、`TerrainSupportBodies`、`GroundSupportPair`。
- `TerrainSlopePlanner`、`TerrainSlopeBodies`。
- `PlanarTrackSupport`、`ProbeSuspensionUnit`。
- `CollisionGroups` 中的 `SLOPE`、`HEIGHT_FIELD`、`STRUCTURAL`、`SUPPORT_PAIR`。
- `PhysicsChunkManager` 中的支撑对安装、楔面创建、调试采样和 debug activation。
- `SectionSnapshot` 的 coverage、above evidence、margin tops/classes 和 surface class。
- `TerrainShapePlan` 的 `ShapeSlope` 和 `slopes`。
- 对应核心测试和高度场 GameTest。

`DRIVABLE` 现在等于 `TERRAIN`。地形只有 Minecraft 方块 AABB。

### SuperbWarfare

- `VehicleBody` 的 `applyTrackSuspension`、`trackGroundFallback`、
  `onTrackHullContact`、旧 `stepAssist` 实现、`refreshTrackHeightField`、`rawColumn`、
  `applyTrackDrive`、`limitTrackYawRate`、`updateTrackPhase`、`updateTrackProjection`。
  新 `TrackedStepAssist` / `applyStepAssist()` 不属于这条旧路径；它是 S3.2 的未完成目标。
- `TrackSuspensionProfile`、`TrackAnimationProfile`。
- `physics/tracked/` 整个候选目录及其测试。
- `SupportTestBodyEntity`、`SupportTestBodyRenderer`、`HeightFieldDebugState`、
  `HeightFieldDebugMessage`、`SbwDebugCommand`、`SupportFieldGameTests`。
- `TrackContactGameTests`、`VehicleDriveGameTests`、`VehicleDriveFixtures`、
  `WheeledDriveGameTests`。

## 3. 当前车辆物理状态

- `GroundVehicleAllowlist` 只登记 `superbwarfare:ztz_99a`。定义编译失败或质量计划
  不闭合时，`supportsPhysics` 拒绝该车，不创建只有车体的履带物理。
- 其他履带车仍是注册实体，可被命令或旧存档加载，但不进入 Spark 物理路径。
- 轮式车同样暂时停用；**本轮不恢复轮式 allowlist**，B0 只跑回归。
- `VehicleBody` 保留通用车体、重力、地形等待、轮式悬挂、飞行力和关节。
- `VehicleBody` 不再包含任何履带承载、旧爬坡辅助或履带相位代码。
  新 `TrackedStepAssist` 属于 S3.2，不在 `VehicleBody` 里实现。
- `VehiclePrediction` 不再调用履带支撑。

## 4. 当前测试

- `VehiclePhysicsGameTests` 保留：
  - `trackedVehiclesStayOutsidePhysicsUntilTheTrackRunRuntimeExists`
  - `turretWreckFallsOntoTerrain`
  - `outOfScopeVehicleStaysOutsidePhysics`
- KaguyaPyCore 保留普通 AABB 地形、合并、孔洞、section 接缝和碰撞相等性测试。
- KaguyaPyCore 的 `PhysicsGameTests` 与 `TrackRunGameTests` 本轮共 16 项通过；
  `TrackRunGameTests` 覆盖首帧、关闭取消、关闭后不复活、reset、`withLinkBodies`
  和公共 Java 任务入口。
- SBW `physics.tracked` 纯 JVM/native 测试 **119 项**通过（S1 轮对拓扑、B1 mount 复核、B2.0–B2.4）：
  `TrackedVehicleAuditTest`、`TrackedRunSpecTest`、`TrackedRunGeometryTest`、`TrackedBeltMemberTest`、
  `TrackedWheelMountTest`、`TrackedGeometryAuditTest`、`TrackedDefinitionValidationTest`、
  `BedrockModelGeometryTest`、`TrackedSuspensionTest`、`B2RunObservationTest`，以及
  `TrackedTwoWheelRunNativeTest`、`TrackedSingleSideRunNativeTest`、
  `TrackedChassisLoadPathNativeTest`、`TrackedBothSidesRunNativeTest`。
  本轮以 `--rerun-tasks` 真实执行，119 项 0 失败。
- 其中 `TrackedBeltMemberTest`（10）与 `TrackedRunGeometryTest`（15）是新轮对拓扑用例；
  `B2RunObservationTest`（16）覆盖新拓扑观测契约；`TrackedRunSpecTest`（5）仍走
  legacy 鞋链入口，只证明历史契约自洽。
- B2.1–B2.4 都是直接 Bullet 诊断。B2.3 的车体由竖直滑轨约束姿态，只证明重量经
  运行段到地面；旁路由**窗口累计接触计数**观测（轮/车体掩码允许接地，不再靠掩码
  "保证"没有旁路）。B2.4 的车体自由，跑到 2400 子步（40 s）后仍沿 Z 蠕移 18.5 m，
  因为该夹具没有驱动、制动或滚阻（S4）。两者都不能写成整车静置通过。
- 早期 `runGameTestServer` 曾以 4 项证明 `TrackedRunRuntimeGameTests` 用完整单侧 8+8
  经 `TrackRunRuntime.create()`、`withLinkBodies(...)` 与 `closeAsync()` 装配和拆卸。
  该装配断言仍在当前套件里。2026-10-26 的同一命令通过 48 项。48 项通过不等于用户实机确认。
- KaguyaPyCore 历史全量 151 项通过，其中 B1 新增 `PhysicsSuspensionRowTest`（14）与
  `NativeSuspensionRowTest`（8，原生 Bullet 单轮 fixture）。
- SBW 历史全量 146 项单元/原生测试通过。
- SBW 历史构建消费 `cn.solarmoon:spark_core:1.20.1-1.3.3`。1.3.3 增加接触 conceived 回调与
  `TrackRunRuntime.closeNow` / `resetNow`，不改变 B1 悬挂行语义。

旧探针、楔面、高度场和旧履带 GameTest 已随实现删除，不能作为新架构证据。

2026-10-28 当前工作树证据：Core `1.20.1-1.4.3-physics-metrics` 全量 159 项通过并已发布到
Maven local；SBW 最终全量单测 176 项通过。requested/installed terrain window、consumer-ID
region request、revision token install gate、projected envelope、planner partial-face culling
均已覆盖；最新完整 GameTest 为 48 项中 42 项通过、6 项失败。本轮 reverse drive 和静置双侧承载通过，
但不能据单批变化标注稳定修复。Core 帧 metrics 对主线程 terrain/pose 请求、worker 排队、任务、
terrain body apply、Bullet update（含子项前后回调）、帧末 body capture 与遍历对象数分项观测；
指标不改变调度或快照成员语义，且尚未证明失败根因。

## 5. 常规获取

`GroundVehicleAllowlist.enabledIds()` 目前只有 `ztz_99a`。这是登记，不是批准或
常规获取验收；S2、S3.1、S4 退出条件未满足，S3.2 未实现，任何一项未满足前不得写成已开放。
轮式车辆不在本轮恢复范围内。

## 6. 发布边界

- SBW 本地提交包含测试环境获取脚本，发布前必须清理或移出提交。
- `tools/client-test/`、`runTestClient.bat` 等不能推送上游。
- 工作树已提交，D1 未执行任何批量重置。

## 7. 零引用检查

```bash
rg -n "LocalHeightField|ProbeSuspensionUnit|PlanarTrackSupport|TerrainSupportField|SUPPORT_PAIR|HEIGHT_FIELD|TerrainSlopePlanner|TerrainSlopeBodies|SLOPE|RUN_BLOCKS|trackGroundFallback|applyTrackSuspension|onTrackHullContact|stepAssist|setNormalWorldOnB|TrackAnimationProfile|TrackSuspensionProfile" \
  src --glob '!**/history/**'
```

两个仓库的活动源码均已无命中。历史归档目录保留原文。
