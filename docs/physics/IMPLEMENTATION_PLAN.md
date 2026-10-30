# 履带物理实施总计划（SBW 定义 + Bullet）

- 更新时间：2026-10-26（按 MCSP 爬坡/悬挂/履带证据修正 S3.2 与撤销边界；同日晚补充 S4 传动响应层与悬挂 Spring/Damper 可选字段）
- 计划状态：D0/D1 已落实并提交；K1 已实现并提交（T0/T1/T2 通过）；
  **S1 轮对拓扑编译层已实现并通过 T0（119 项测试中的 S1 部分）**；旧鞋链降为 legacy 审计；
  B1 旧成绩保留，因轮端刚体与安装约束变化**进入待复核**；
  B2 直接 native 诊断（B2.1–B2.4）与单侧 `TrackRunRuntime` T2 门已执行，
  且 B2 阈值已冻结、T1/T2 在冻结值下通过；B2.3/B2.4 的承载结论仍有明确夹具边界，
  不能外推为整车通过。
- **S3.2 未通过：** 接触响应实现为“真实 AABB 接触上的受限切向目标”。竖直立面没有合法
  的平面内方向，早期“向上切向”回退等价于梯子并曾把车顶翻，已移除；因此一格正向爬越
  未达成，仍缺连续台阶、辅助期间悬挂 travel 记录和实机暂停点 C。
- 本轮提交基线：KaguyaPyCore `e90984a`、SuperbWarfare `1b5c984`。
  上述提交不含本轮 S1 轮对拓扑改动；当前工作树为未提交状态。
  `docs/` 与 `MCS_SOURCE_REVIEW/` 不进入代码提交，它们仍在本工作树中维护。
- 当前样车：`superbwarfare:ztz_99a`（ZTZ-99A），首个正向接入并验收的履带载具
- 架构基线：[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`CONSTRAINTS.md`](CONSTRAINTS.md)
- 跨仓 API 契约：[`CORE_API_CONTRACT.md`](CORE_API_CONTRACT.md)

## 0. 目标与仓库边界

### 0.1 目标

以 SBW 自有车型与悬挂定义为权威，在 Bullet 上实现履带式载具物理，并用 MCSP 的方式渲染履带：

- 动态负重轮和独立悬挂。
- 主动轮、导动轮、张紧器和动态履带运行段。
- 履带运行段按相邻轮对组织：每个轮对一个动态刚体，两端约束到轮端刚体；
  可见重复链节只是渲染模板。
- 地形基础碰撞仍是 Minecraft 方块 `VoxelShape` 转成的 AABB。
- 可选台阶辅助是 SBW 的独立策略，不是全局高度场。

### 0.2 仓库职责

KaguyaPyCore 只提供通用 Bullet 能力：

- 每 Level 物理线程、固定 60 Hz、任务队列和不可变帧快照。
- 通用刚体、形状、关节、owner、碰撞组和生命周期 API。
- 通用动态履带运行段 `TrackRun` 的创建、销毁和状态读取。
- 通用物理状态插值，不含 Minecraft、车型或渲染对象。

SuperbWarfare 负责全部车辆专用功能：

- 车型数据、模型适配、轮角色、质量预算和履带拓扑。
- 悬挂、张紧、驱动、差速、制动和牵引。
- 普通 AABB 地形策略和可选台阶辅助（复现 MCSP `BridgesSteps` 的行为目标）。
- 车辆生命周期、同步、预测和验收。
- MCSP 风格履带路径、相位、重复链节和骨骼渲染。

车型与悬挂 schema 以 SBW 为准（`TrackWheelRole`、`TrackWheelDefinition`、
`TrackSuspensionDefinition`、`TrackSuspensionOverride`），不把 `Suspension.Kind`、`carrier`、
`hub`、`knuckle` 或 `spin` 引入 SBW 字段。

但 MCSP 不只是“一般行为参考”：它对履带字段组织、可见路径求解与
Java/native 运行段桥接有可逐行核对的源码贡献（见
[`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) §3.1）。这些字段分散在物理、渲染与驱动层，
不是一体的 native 物理规范。

### 0.3 明确撤销与仍有效的目标能力

以下方案不得重新进入活动实现或验收：

- KaguyaPyCore 中的 `LocalHeightField`、`TerrainSupportField`、`HEIGHT_FIELD`、
  `SUPPORT_PAIR` 和车辆高度场支撑。
- 探针承载、探针 miss 后高度场 fallback、左右整体接触组。
- 固定 `RUN_BLOCKS` 楔面、按车长自适应坡长、`setPos`/直接位置修正和人工抬升。
- 旧 `VehicleBody.stepAssist` 实现：hull contact 法线改写、`setNormalWorldOnB`、
  无界或永久向上力、每个轮每子步重复叠加的延迟 lift。
- 预处理履带轨迹或整侧悬挂高度作为物理来源。
- 把每个可见链节当作独立物理刚体，或把物理运行段直接绑定到模型链节。
- 把逐鞋销链当作唯一物理拓扑；该方案已降为历史能力。

**仍有效的目标能力：** S3.2 的 SBW 可选台阶辅助。它必须从真实方块 AABB 读取障碍，
只处理车型明确允许的低台阶，有高度、坡长和速度上限，与原始地形互斥承载，并保留真实
履带接触与悬挂行程。撤销的是旧的伪地形、无界抬升和位置修正，不是“辅助”这个概念。
接触响应方式已选定为“真实 AABB 接触上的受限切向目标”（见 §S3.2），仍不得标为通过。

历史依据见 [`LEGACY_CLEANUP.md`](LEGACY_CLEANUP.md) 和
[`history/2026-10-20-pre-mcsp/`](history/2026-10-20-pre-mcsp/)。

## 1. 阶段规则

- 每个阶段只解决一个层次的问题；不以调参掩盖契约错误。
- 当前阶段退出条件未全部满足，不进入下一阶段，也不把下一阶段标为进行中。
- 自动测试通过不等于实机通过；用户实机确认单独记录。
- 物理代码改动前先完成 D0 文档和 D1 清理。
- 任何 Bullet 参数都要在 Bullet 中重新验证，不直接沿用 Rapier 数值。
- 所有阶段记录 commit、车型/模型/数据哈希、测试命令、结果、失败现场和人工结论。
- 运行实例、存档、日志、构建产物、测试客户端/服务端和启动脚本禁止入库。

## 2. D0：文档、边界和记录

### D0.1 文档落地

- 重写本计划、[`CONSTRAINTS.md`](CONSTRAINTS.md)、[`ARCHITECTURE.md`](ARCHITECTURE.md)。
- 新增 [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md)、
  [`CORE_API_CONTRACT.md`](CORE_API_CONTRACT.md)、
  [`TRACK_RENDERING.md`](TRACK_RENDERING.md)、
  [`LEGACY_CLEANUP.md`](LEGACY_CLEANUP.md)。
- 同步 KaguyaPyCore 的 `docs/CORE_SCOPE.md`、`docs/TRACK_RUN_API.md`、
  `docs/PHYSICS_INTERPOLATION.md`、`docs/PHYSICS_API.md` 和
  `docs/TRACKED_VEHICLE_SUPPORT.md`。
- 将旧计划、旧状态和清理前源码基线归档到
  [`history/2026-10-20-pre-mcsp/`](history/2026-10-20-pre-mcsp/)。

### D0.2 Skills 与记录

- 按 [`SKILLS.md`](SKILLS.md) 蒸馏两个仓库的 Skills。
- Skills 目录以 OpenCode 实际发现规则为准：`.opencode/skills/<id>/SKILL.md`，
  或在仓库 `opencode.jsonc` 的 `skills` 数组显式注册。
- 追加 `docs/physics/records/events.jsonl` 事件。
- MCP 未建立时记录 `mcp_sync=unavailable`，不能声称已写库。

### D0 退出条件

- 新文档不再把高度场写成目标架构。
- 文档能区分“已实现”“候选”“已撤销”“待清理”。
- Skills 与新文档一致，且不复制易过期事实。
- **证据确认门 D0：** 用户确认文档基线后，才进入 D1 删除代码。

## 3. D1：旧实现归档与清理

### D1.1 基线

- 记录两个仓库的 `git status --short`、`git diff --name-status` 和相关文件哈希。
- 逐文件标记所有权：用户已有修改、旧实现、通用实现、新候选。
- 不执行 `git reset`、`git checkout`、批量格式化或递归删除。

### D1.2 归档

- 旧计划和旧源码快照进入 `docs/physics/history/`，不得进入 Gradle source set。
- 归档记录原路径、SHA-256、已知失败和替代方案。
- 活动源码删除后，历史归档仍可追溯。

### D1.3 清理范围

KaguyaPyCore：

- 高度场：`LocalHeightField`、`terrain/support/`、`TerrainSupportField*`、
  `TerrainSectionBuild/Range`、`TerrainSupportBodies`、`GroundSupportPair`、
  `SupportContactArbiter`、`SupportDebugSample`。
- 楔面和支撑对：`TerrainSlopePlanner`、`TerrainSlopeBodies`、`SLOPE`、
  `SUPPORT_PAIR`、`RUN_BLOCKS`。
- 旧履带支撑：`ProbeSuspensionUnit`、`PlanarTrackSupport`。
- 保留普通 `SectionSnapshot`、`TerrainShapePlan`、`PhysicsChunkManager` 的
  voxel/AABB 构建和通用物理线程。

SuperbWarfare：

- `VehicleBody` 的旧 `applyTrackSuspension`、`trackGroundFallback`、
  `onTrackHullContact`、旧 `stepAssist` 实现、`rawColumn`、`CLIMB_RUN_BLOCKS`。
  **注意：** 新 `TrackedStepAssist` / `applyStepAssist()` 属于 S3.2 目标能力，
  不是本项要删除的旧实现；D1 删除的是旧的 VehicleBody 抬升/fallback 链路。
- 旧 `TrackAnimationProfile`、`TrackSuspensionProfile` 作为物理来源的路径。
- 旧 `physics/tracked/` 候选实现整体归档后重建，不在原类名上隐式续接。
- 旧渲染状态字段和旧相位-only 同步路径。

### D1 退出条件

活动源码和活动测试中零引用以下符号：

```text
LocalHeightField
ProbeSuspensionUnit
PlanarTrackSupport
TerrainSupportField
SUPPORT_PAIR
HEIGHT_FIELD
TerrainSlopePlanner
TerrainSlopeBodies
SLOPE
RUN_BLOCKS
trackGroundFallback
applyTrackSuspension
onTrackHullContact
stepAssist
setNormalWorldOnB
TrackAnimationProfile
TrackSuspensionProfile
```

归档目录和说明文档允许出现这些字符串。清理后必须重新跑通用车体测试，确认没有
引入与履带无关的回归。该回归已在 2026-10-22 执行：`runGameTestServer` 通过 3 项，
未恢复轮式 allowlist，结果记录在 [`STATUS.md`](STATUS.md)。

零引用检查只针对**旧实现符号**。S3.2 的新类型（`TrackedStepAssist`、`applyStepAssist`）
不在此列，不能用这条检查证明辅助已实现或已删除。

## 4. K1：KaguyaPyCore 通用 Bullet API

K1 拆成六个子门。只有全部子门满足退出条件，K1 才能标记通过；任何子门未完成时，后续
S1/B1 不得开始。

| 子门 | 内容 | 当前状态 |
|---|---|---|
| K1-L 生命周期 | 任务接纳门、可等待关闭、统一 teardown、FAILED 清理 | 已实现；T0/T2 通过 |
| K1-A 装配事务 | 逐项创建、失败回滚、输入接纳语义 | 已实现；T0/T1/T2 通过 |
| K1-J 约束 | 两端局部 constraint frame、native 关节坐标观测 | 已实现；T0/T1 通过 |
| K1-S 快照 | 稳定 runId/poseEpoch/bodyId、不可变 BodyFrame、body 与整帧插值 | 已实现；T0 通过 |
| K1-C 接触 | 通用接触快照方向/单位/稳定 id 与线程边界 | 已有原语并补充 id/frame；未新增车辆语义 |
| K1-T 验证 | 核心全量测试与 T2 GameTest | T0/T1 通过（124 项）；T2 通过（16 项） |

### K1.1 TrackRun API

- 新增通用 `TrackRunSpec`、link 描述、pin/hinge 描述、runtime 和 snapshot。
  runtime 本身充当运行段句柄，不额外承诺独立 `TrackRunHandle`。
- 只描述物理运行段，不含车型、轮业务角色、模型骨骼、材质或可见链节。
- 在物理线程创建 link body 和 joint；owner、碰撞组和同 owner 过滤由调用方声明。
- joint 支持两端局部 constraint frame；`axis` 在该 frame 中解释。
- 创建必须事务化：每个对象创建即记账，失败逆序回滚，joint 先于 body。
- 回滚失败时保留残留所有权，关闭阶段必须重试，不得报告成功。
- 创建、reset、remove、Level unload 和失败恢复必须幂等。
- `create()` 完成时首帧必须已经存在；关闭取消的创建必须失败。
- 每个物理子步结束后发布不可变快照，并携带稳定 `runId`、`poseEpoch` 和 `linkBodyIds`。
- 默认继承世界重力；自定义重力必须是显式配置。
- 提供有上限的通用力/力矩冲量接口，区分「已接纳」与「已施加」；差速和目标速度由 SBW 决定。
- 提供 `withLinkBodies` 作为受控的外部约束装配点，不暴露长期 native 句柄。
- 同 owner 碰撞使用允许列表：link 之间过滤，link 与同车车轮/车体碰撞。
- 关节坐标来自 native `getAngles`；**不提供**关节载荷字段，因为当前 wrapper 不维护
  `appliedImpulse`（恒为 0），伪造该值会误导驱动与验收。接触冲量不能唯一恢复闭链内单个
  关节反力，文档必须区分这两件事。

### K1.2 通用插值

- 提供 body/link 位置、旋转、AABB、速度、TrackRun pose 和相位插值。
- 插值只消费不可变快照，不持有 native 指针，不修改物理 body。
- body 按稳定 `bodyId` 对齐；run 按 `runId` + `poseEpoch` + 拓扑对齐，不一致直接采用新帧。
- native 关节采样不插值；`joints` 与 `linkBodyIds` 原样保留。
- 离散字段取较近帧，不做数值混合。
- 不包含模型骨骼、可见链节、Geckolib/Bedrock 或材质逻辑。

### K1.3 核心测试

- 两 link、短闭链、多 link 闭链。
- pin 闭合误差、长期漂移、休眠/唤醒。
- 物理线程创建/销毁、owner 生命周期和碰撞过滤。
- 插值不写回物理状态。
- 任务接纳、启动/关闭竞态、部分装配失败回滚、非 identity joint frame。
- 创建完成时首帧存在；关闭取消的创建失败；关闭后不复活。
- 公共 Java/Kotlin 可等待入口不会在关闭竞态中挂起。
- 测试必须走 `TrackRunAssembly`/`TrackRunRuntime` 的生产路径，不能只测旁路 helper。

### K1 退出条件

- API 不出现 SBW、ZTZ-99A、模型或高度场概念。
- 原生测试通过，native 访问全部在物理线程。
- T2 GameTest 在真实服务端执行并记录结果（本轮已通过 16 项）。
- 核心文档同步更新，并向 SBW 事件账本记录 `core_commit`（未提交时写 `WORKTREE`）。

## 5. B0：通用车体基线（不改变 allowlist）

耦合原 `VEHICLE_PHYSICS_PLAN.md` 中仍然有效的车体测试，但移除旧履带支撑假设。

**范围限制：** B0 只跑清理后的回归，不恢复轮式 `GroundVehicleAllowlist`，也不作为
99A 接入的前置决策门。轮式车辆恢复属于独立后续范围。

### B0.1 坐标与落地

- 复核 `VehiclePhysics.buildSpec()`、`resolveCollisionParts()`、
  `resolveSuspensionSides()` 的坐标转换。
- 保持放置后 dynamic body；terrain 未 ready 时不改 kinematic、不清零速度。
- 普通 voxel/AABB 地形接触正常。
- 重跑 `everyGroundVehicleGetsReadyBody`、`wheelChairFallsOntoTerrain`、
  `turretWreckFallsOntoTerrain`、`fixedTurretStaysAnchored`。

### B0.2 通用回归

- 飞行器重力、升力和输入门控。
- 外部冲量、重载、卸载和 VS 船体代理。
- 轮式车辆保持 allowlist 之外；只验证它们不进入 Spark 物理路径。

### B0 退出条件

- 清理旧履带路径没有破坏通用车体生命周期。
- 车体坐标、OBB、模型 pivot 和地形高度使用同一坐标系。
- 失败必须修复夹具或物理环节，不能放宽阈值。

## 6. B1：独立负重轮悬挂

### 借鉴

- MCSP 悬挂部分只作为行为参考：独立轮悬挂、轮体自转与悬挂平移分离、行程限位。
- `PhysGearWheel.Suspension` 的 `kind/pivot/axis/min/max/stiffness/damping/maxForce`
  与 `centreAt(travel)` 说明轮位随行程变化；`PhysGear.Wheel.applyMotors()` 把弹簧、
  阻尼、限位和最大力接到关节。这些是可逐行核对的**行为与字段消费者**，不是 SBW schema。
- 已落实 `Spring` / `Damper` 的直接 SI 语义（2026-10-26）：`TrackSuspensionDefinition`
  与逐轮 override 均可选声明；声明后由实际 `k/m` 推导有效频率。未声明时保留原频率 +
  压缩/回弹阻尼比模型，99A 当前数据不变。MCSP 的 `Kind` / `centreAt` 公式仍不进入 SBW。
- 不采用 MCSP 的车型模型定义。`Suspension.Kind`、`carrier`、`hub`、`knuckle`、
  `spin` 不进入 SBW schema。
- YWZJ 的独立轮悬挂方向：静平衡、行程限位、压缩/回弹区分、多支撑点。
- 不直接复制 YWZJ 代码、参数或其 `climb()` 位置抬升。

### 实现

- 车型与悬挂数据以 SBW 自有定义为权威：`TrackWheelRole`、`TrackWheelDefinition`、
  `TrackSuspensionDefinition`、`TrackSuspensionOverride`、`CompiledSuspension`。
- KaguyaPyCore 提供通用 `guidedWheelAxle`、constraint frame、成对力和生命周期；
  另外提供不含车型语义的 SI 双向弹簧—阻尼数学原语 `PhysicsSuspensionRow`。
- SBW 决定站点质量分配、轮角色和悬挂策略。
- 悬挂平移与轮端相对转动正交；带内轮端刚体不提供独立轮面自转。
- 悬挂是双向的：压缩支撑、下垂恢复；`MaxForce` 是绝对力上限。

### 测试与退出

- 单轮静载、压缩、下垂恢复、上下限位、轮端相对安装机构不转动。
- 负重轮不直接以地形作为独立地面支撑；载荷经过运行段和轮端约束。
- 静载误差和行程误差写入 `ACCEPTANCE_LIMITS.md` 后才可标通过。

### B1 实现与验收现状（2026-10-23，core `e90984a` + SBW `1b5c984`）

- KaguyaPyCore 的 `PhysicsSuspensionRow` 是车辆无关的逐站悬挂行，单位为
  N/m、N·s/m、N、m；力为 `k·travel + c·velocity + bump`，**带符号**，
  钳制在 `[-MaxForce, +MaxForce]`。压缩为正（推开），下垂为负（拉回）。
- `MaxForce` 是绝对力上限。把负向力钳成零会删除下垂恢复力，属于错误实现。
- 原生 fixture `NativeSuspensionRowTest` 由 `fromSag` 生成行参数，不再手写刚度；
  验证静载落点、压缩恢复、下垂恢复、上下限位、绕轴自由滚动、行程不产生滚动、
  阻尼耗散大于近似无阻尼行。
- SBW 的 `TrackedSuspensionCompiler` 每个 `Support` 轮一行，保留完整
  `CompiledSuspension`（含 `BumpStopStart` / `BumpStopExponent`），并提供
  `toRowSpec()` 适配到核心原语；不再使用硬编码的 `0.85` 缓冲块常数。
- bind pose 下两端 constraint frame 重合：`chassisPivot == wheelCenter`，
  `wheelPivot == 原点`。不再用"锚点抬高一个行程"制造行程。
- 编译器拒绝：静载下沉超出压缩行程、力上限无法承载、轴向/行程轴与平面模型不符、
  pivot 不重合、以及没有下垂恢复力的机构。
- 99A：6 行/侧，19878 kg/侧，最大静载下沉 0.0513 m，最小力余量 12.31x。
- 验收：用户已于 2026-10-23 确认
  [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2 的五组阈值与阻尼声明，
  B1 在**旧轮体拓扑与当前 fixture** 上曾记为自动通过。
- **2026-10-23 拓扑定稿后：B1 转入待复核。** 轮端刚体取代旧轮体、
  质量分配与施力点改变，满足重新打开条件；旧成绩保留为历史，不删除。
- 边界：这不是永久批准，也不是实机通过。力律、参数、夹具、
  核心版本或阈值任一变化都必须追加 superseding 事件并重新复核。
- **当前状态：待复核。** 轮对运行段把旧轮体替换为不自转轮端刚体，并改变站点质量分配，
  属于 §2.1 的重新打开条件；旧 T0/T1 成绩作为历史保留。
- 未接线：悬挂行仍未挂到车体，属于 S2；整车动态承载路径属于 B2。

## 7. B2：动态履带运行段

状态：**进行中**（2026-10-24）。B2.0 观测契约已按轮对运行段改写；B2.1–B2.4 直接
native 诊断与单侧 T2 运行时门已通过；阈值已冻结（`ACCEPTANCE_LIMITS.md` §2.3），B2.3/B2.4 的夹具边界使结论不能外推
到整车静置。
详细 fixture 与量测设计见 [`B2_RUN_TEST_PLAN.md`](B2_RUN_TEST_PLAN.md)。

### 借鉴

- native `insert_run` 的行为证据：每个相邻轮对（含 last→first）一个动态 Cuboid 运行段；
  第一端为轮心处多体转动副，第二端为只锁一个法向平移的 impulse joint。
  这确认了拓扑语义，但仍不能推断 Bullet 实现与 Rapier 动态等价。
- `PhysGearBelt`：字段语义参考（轮序、逐轮 wrap、厚度、节距、下垂、侧向摩擦、驱动分组）。
  其中 `linkPitch`/`sag` 属可见路径层；`driveSlot`/`lateralFriction` 经 carrier 元数据
  间接影响驱动响应，见 [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) §3.1。
- 已删除的 `TrackedRig` 与现有 `ClosedTrackLoopSolver` 只作为几何、质量和失败案例参考。

### 子阶段

**B2.0 规格与读数准备**

- 唯一输入来源：S1 重开后的 `CompiledTrackSide`（含 runs/attachments/mounts）、
  `CompiledWheel` 与 `TrackedRunSpecs.compileRuns` 产出的 `TrackRunSpec`。
  fixture 不得另建一套几何。
- 额外定义下列可观测量（字段名与采样窗口必须写进测试）：
  端锚点距离误差、锁轴角误差、端 2 法向残差与切向滑移、轮端相对安装机构旋转漂移、
  运行段-轮体接触与穿透、运行段-地形接触、长期漂移、以及悬挂 travel 与行力。
- 若需要车体 OBB 或车体惯量，必须补审计，不得声称 S1 已覆盖。
- 本子阶段不创建 Minecraft 世界，也不接入车辆实体生命周期。

**B2.1 两轮两运行段**

- 用两个轮端刚体和两个运行段验证：端 1 转动副、端 2 单法向约束、
  切向可滑、无自发运动。
- 必须根据 `TrackRunAssembly` 真实路径测试，不得用模拟机构代替。
  → 已执行：native 诊断用 `TrackedRunSpecs.compileRuns` + `TrackRunAssembly.createBody`
  与 `TrackedJointFactory`；完整单侧经 `TrackRunRuntime` 的 T2 GameTest 另行覆盖。
- 必须包含负向测试：端 2 改成三平移全锁时，切向滑移断言失败。

**B2.2 单侧完整轮对机构**

- 扩到 99A 显式轮序的 **8 轮端 + 8 运行段/侧**，记录端约束残差、
  运行段漂移、自碰撞过滤与轮段穿透。
- 若为排查稳定性临时减少轮数，只能标为诊断试验，
  **不能作为 99A 完整机构的验收证据**。

**B2.3 真实承载路径 fixture**

```text
地形 AABB
  -> 动态运行段
  -> 端 1 转动副 / 端 2 单法向约束
  -> 轮端刚体
  -> B1 悬挂行力
  -> 车体
```

- 必须能证明载荷经过运行段与轮端约束；若轮直接接地或车体接触承担了主要负荷，
  该次测试不成立，也不得改成“仅静态通过”。
- 这是 **B2 fixture**，不等于 S2 的生产车辆 runtime。
- **夹具边界：** 单侧无法平衡车体滚转，当前实现把车体约束在竖直滑轨上、并取单侧簧载
  质量；它只证明重量经悬挂/轮端/运行段到地面，不证明“车体被抬起”。后者由 B2.4 的自由
  双侧车体承担。

**B2.4 双侧与扰动**

- 左右两条机构独立；至少 600 个物理子步静置；外部冲量；
  拆卸与部分装配失败清理。
- 不得用固定参考板、人工抬升、姿态重置或全局阻尼
  掩盖失效。
- 当前 B2.4 已有自由双侧车体、同 owner、2400 子步（40 s）与 40000 N·s 冲量；但该夹具
  没有驱动、制动或滚阻（S4 才提供），实测仍沿 Z 蠕移 18.5 m，**静止在 S4 之前不可达**。
  冲量为 `Δv = J/m` 判定并检查横向速度衰减，不再用位移差。不得写成实机静置通过，
  也不得靠延长窗口或调阻尼制造收敛。

### 测试与退出

- 退出条件：上述四个子阶段均有可重现的 T1/T2 证据，且
  [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2.2 中属于 B2 的 ID 已逐项确认阈值。
- 未冻结前只能记录观测值与命令是否跑通，不得写成验收通过。
- 不提前混入 S2 生命周期、S4 驱动或 R1 渲染工作。

## 8. S1：SBW 车型编译与数据审计

### 实现

- SBW 新增 `TrackedVehicleSpec`、几何编译器、轮规格和 `TrackRunSpec` 适配。
- 复用但重审 `BedrockModelGeometry`、`TrackedDefinitions` 和 `TrackedSpec` 的数据解析。
- 输出物理描述和渲染描述，两者不共用可见链节对象。

### 拓扑重开（2026-10-23 定稿，2026-10-23 编译层完成）

S1 的旧鞋链部分已降为**显式 legacy 审计**，不再参与轮对运行段的编译或门禁：

1. `TrackSideDefinition` 增加**显式有序** belt member 引用与逐成员 wrap。
   → 已实现：`BeltOrder` 缺失为 ERROR，`Wrap` 必须逐轮声明。
2. 新增 `CompiledWheelMount`：角色 → 安装约束类型、框架、轴、限位、预载/阻尼来源。
   → 已实现：每个 mount 携带完整 `CompiledJointPlan`（两端 local frame + 六轴状态）。
3. 新增 `CompiledRunAttachment`：运行段两端的锚点、局部旋转与 body 身份。
   → 已实现：两端各自持有 `CompiledJointPlan`，第一端 wheel→run，第二端 run→wheel。
4. `CompiledTrackSide` 增加 `runs`；旧 `loop/placements` 移入 `LegacyShoeChainAudit`。
5. `TrackedRunSpecs` 改为每 run 一条单 link、无内部 joint 的 `TrackRunSpec`。
   → 已实现：旧 `compile()` 移至 `legacy/LegacyShoeChainRunSpecs`。
6. 质量闭合改为逐项可追溯：车体 + Σ轮端 + Σrun + 炮塔/炮管拆分 = `MassKg`。
   → 已实现到预算层：每侧显式 `RunMassBudgetKg`，`TrackedMassAllocator` 分配，
   `TrackedMassAudit.detailCloses()` 校验 wheel+run 明细；完整 chassis/articulation
   拆分仍属 S2 质量计划。

实现落点：

| 文件 | 职责 |
|---|---|
| `TrackedBeltGeometry.kt` | `CompiledBeltMember`、`CompiledBeltGeometry`（平面、宽、厚、来源） |
| `TrackedRunGeometry.kt` | 有符号半径切线、四种 wrap、run 盒形、两端 joint plan |
| `TrackedRunGeometryAudit.kt` | run/轮实体间隙与非相邻 run 相交审计 |
| `TrackedConstraintSpec.kt` | `CompiledJointPlan`、`CompiledAxisLimit`、`CompiledBodyRef` |
| `TrackedMassAllocator.kt` | 方案 A 预算分配（`sbw-adjacent-pair-mass-share-v1`） |
| `TrackedCompiledSpecValidator.kt` | 最终门：引用、六轴、Float、Core spec 校验 |
| `TrackedVehicleCompiler.kt` | 主路径只编译轮对机制；legacy 鞋链失败仅警告 |
| `legacy/LegacyShoeChainCompiler.kt` | 历史 79 鞋链，独立且不阻塞新路径 |
| `legacy/LegacyShoeChainRunSpecs.kt` | 历史鞋链的 `TrackRunSpec` 适配器 |

**已验证（T0，纯 JVM）**：`physics.tracked` 97 项 0 失败。

轮端安装框架已定稿：每个带内轮站一个动态轮端刚体，无独立轮面自转；
run 第一端为轮心转动副，第二端只锁法向。详见
[`../plan/wheel-end-attachment-plan-2026-10-23.md`](../plan/wheel-end-attachment-plan-2026-10-23.md)。

### 99A 审计

- 车体原点、模型 pivot、Bullet body origin。
- 每轮中心、半径、宽度、轴向、角色、悬挂方向和所属侧。
- 履带平面、显式轮序与 wrap、pinInset、idler offset 和运行段几何。
- 车体、轮端、运行段质量总和与惯量。
- 数据/模型 SHA-256。

### S1 退出条件

- 纯 JVM 数据和几何测试通过。
- 不按骨骼顺序猜测轮角色或履带遍历。
- 运行段数、可见链节数和旧动画骨骼数分别记录。

### S1 旧鞋链完成情况（2026-10-22，已提交 `6e19d82`，保留为历史）

- 已恢复并重审 `BedrockModelGeometry`、`ClosedTrackLoopSolver`、`TrackedDefinitions`、
  `TrackedSpec`、`TrackedVehicleCompiler`；新增 `TrackedRunSpecs` 产出核心 `TrackRunSpec`。
- 99A：8 轮/侧（6 支撑 + 1 主动 + 1 导动），79 物理链节/侧，37 可见骨骼/侧，
  287 模型骨骼，节距 0.23307 m，闭合误差 ~7e-15 m，导轮偏移 +23.9 mm，
  最大包绕 32.30°（限值 40°），质量 39756 + 15244 = 55000 kg。
- 角色来自数据 `Role` 字段，不来自骨骼顺序。
- 未接线：`TrackRunRuntime` 的实际创建属于 B2/S2。
- **上述 79 节成绩只对旧鞋链拓扑成立，不能作为轮对运行段的完成证据。**

### S1 复审修正（2026-10-22）

复审发现两类真实缺陷，均已修正：

1. **间隙指标错误。**原审计报告的是销弦到轮圆的距离（31.49 mm），并把它当作链节
   间隙。链节是有厚度的盒体，包绕弦的矢高使 99A 鞋体实际穿入导轮 17.34 mm。
   现在销轨迹按 `hypot(wheelRadius + PinInset + Clearance, pitch/2)` 求解，并对
   每个链节 × 每个轮做实体截面检查；`minPinChordClearanceM` 与
   `minShoeWheelGapM` 分开报告，只有后者可以决定装配是否成立。
   修正后 78 链节无法闭合，故 `LinkCount` 78 → 79、`Clearance` 0 → 2 mm。
2. **错误规格可被适配。**`compile()` 原来同时承担审计报告与成功规格，存在 error
   时仍可能产出可用的 `TrackRunSpec`。现在返回 `TrackedCompilation`，
   只有无错误且两侧完整时才带 `spec`；适配器另有实体间隙门禁。

同时补齐的计划项：

- 轮轴向 `AxleDirection`、悬挂方向 `Direction`、张紧轴 `Axis`，均校验为非零、有限、
  轴对齐；S1 平面模型不接受斜置并被静默投影。
- 链节盒体惯量与车轮圆柱惯量（`TrackedMassProperties`），来源可追溯到实际形状。
- 独立的可见描述 `TrackedRenderTemplate`；99A 的可见节距标记为未确定，因为模型
  37 个履带骨骼共用一个 pivot，没有间距信息。
- 输入字段的完整有限性/范围校验（`TrackedDefinitionValidator`）。
- 编译结果深不可变：旋转改为标量，drivetrain 复制为 `CompiledDrivetrain`，集合复制。
- Bedrock 解析时校验父骨骼、环和数值有限性。
- 测试改为直接读取车辆 JSON 核对全部 16 个轮，并新增几何审计、字段校验与模型解析
  三组测试，共 41 项。

## 9. S2：ZTZ-99A 生命周期接线

- 建立新的 `TrackedVehicleRuntime`，负责 chassis、轮组、TrackRun、悬挂 joint、
  owner、碰撞组、输入、快照和幂等关闭。
- 在 `VehiclePhysics`/`VehicleBody` 中接入明确的新运行时，不恢复旧探针分支。
- 处理生成、reset、NBT 重载、区块卸载、Level 卸载、实体移除和失败恢复。

退出条件：

- 无幽灵 body/joint。
- 真实履带板到车体的静载路径可测。
- **实机暂停点 A：** 用户检查轮位、轮板间隙、闭合、导轮高度、张紧方向和静置姿态。

## 10. S3：普通 AABB 地形与可选台阶辅助

### S3.1 基础地形

- 使用 Minecraft `VoxelShape` 转 AABB 的普通地形碰撞。
- 验证完整方块、半砖、楼梯多 AABB、一格台阶、由真实方块组成的 AABB 阶梯坡、
  两格墙、坑、桥面/桥下、section 边界和方块挖除。
- **光滑连续斜坡不是方块 AABB，不属于本阶段门禁。** 不得用高度场、固定楔面、探针
  或人工抬升把它补成“已测斜坡”；AABB 阶梯坡和楼梯就是本架构能提供的坡。
- 不生成高度场、全局平滑面、固定楔面或车辆专用地形 body。

### S3.2 SBW 台阶辅助

#### 已恢复的 MCSP 行为（只作参考，不是可移植算法）

| 环节 | MCSP 证据 | 可确认内容 |
|---|---|---|
| 参数 | `MCS_SOURCE_REVIEW/quistaphys-java/src/quista/phys/body/PhysRamps.java` | `minStep` / `maxStep` / `maxClimb` / `minRun` / `maxRun` / `minSpeed` / `fullSpeed`，并有范围校验 |
| 车型开关 | `PhysGearSpec.bridgesSteps()` / `ramps()` | 辅助是车型级可选策略，关闭时不影响基础 AABB |
| 注册 | `PhysGear.hold()`、`PhysGear.bridgeSteps()`、`PhysScene.setBridgesSteps(...)` | 车体与运行机构刚体被登记到辅助路径 |
| 每步更新 | `MCS_SOURCE_REVIEW/KEY_FILES.md`：`Scene::step → update_slopes` | 坡面状态在物理步内更新，不是渲染侧临时抬高 |
| 接触响应 | `modify_solver_contacts → slope_normal_y`（`slope-belts-contacts.asm`） | 反汇编可见接触钩子调用坡面法线函数；**不能**据此确定辅助只在求解前生效或完全取代原始接触 |
| 状态恢复 | `PhysGear.readState()`、`PhysScene.readSlopeState()` / `seedSlope()` | 坡面状态参与存档/重建 |
| 悬挂 | `PhysGearWheel.Suspension`、`centreAt()`、`PhysGear.Wheel.applyMotors()` | 真实轮位随行程变化，弹簧/阻尼/限位进入关节 |
| 履带 | `PhysGear.rebuildBelts()`、`setSurfaceSpeed()` / `setFree()` | 辅助与真实 carrier、运行段和驱动状态协同 |
| 驱动 | `mcs-java/.../ChassisGround.java` | 读取牵引并按侧写带速；不是直接推车体 |

**不可恢复的部分：** native Rust 源码不可用；坡面几何的精确生成条件、完整接触过滤规则、
与基础地形是否并行承载，以及接触修改的精确时序都不能从符号和反汇编唯一确定。因此
SBW 只能复现**行为目标**，不能声称与 MCSP 动态等价，也不能照搬 Rapier 的钩子或参数。

#### 适配决策（已选定候选 A）

1. **候选 A：求解前接触响应。** 已采用：只在 `onContactConceived` / `onContactProcessed`
   中写切向 `contactMotion1` 与摩擦方向，不改写法线/接触点/距离，也不施加独立冲量。
   未使用 `setNormalWorldOnB`。
2. **候选 B：受限辅助几何。** 未采用；没有新增辅助碰撞体或地形 body。
3. **候选 C：不实现辅助。** 保留为回退项：若候选 A 在连续台阶或实机上不成立，
   S3.2 回到未通过，不得改成直接抬车。

无论选哪种，辅助都必须：从真实方块 AABB 采样；只处理车型明确允许的一格台阶；有台阶
高度、坡长和速度门限；同一障碍只辅助一次；与真实履带接触、悬挂行程和驱动状态可同时
观测；不产生悬空、重复支撑或穿透。

#### 目标接口（已实现部分与未实现部分）

已实现：

- `TrackedStepAssist.TerrainCandidate` / `TerrainCandidateKey`：游戏线程产出的不可变
  真实 AABB 候选，带 rise/run/distance。
- `TrackedStepAssist.candidatesAhead()` / `contactMatches()` / `contactAssist()`：
  候选筛选、接触归属匹配与切向目标。
- `TrackedStepAssist.approachRefused()`：任一超过一格的盒否决整条接近路径。
- `TrackedVehicleRuntime.StepTerrainSnapshot`：游戏线程发布、物理线程消费的候选快照。
- `TrackedVehicleRuntime.applyStepAssist()`：物理线程刷新诊断并清理已消失的障碍状态。
- `TrackedVehicleRuntime.assistTarget()`：逐候选、逐子步一次的消费状态。

未实现：

- `TrackedTerrainAssistDefinition`：尚未做成车型数据字段；当前是 `TrackedStepAssist`
  常量。冻结前不得写进 99A JSON 当作已校准值。
- 连续台阶的整段累积 climb 与逐级状态机；当前 `totalClimb` 只用单个候选的 rise。
- 辅助期间逐帧记录悬挂 travel 的 T3。

#### 已实现的接触响应方式（候选 A）与它的硬边界

采用**求解前接触响应**，但只写切向目标，不改写接触几何：

- `TrackedStepAssist.candidatesAhead()` 只从真实 `VoxelShape.toAabbs()` 采样结果中筛选：
  逐个 AABB、横向与履带重叠、`rise` 从**轮下真实支撑面**（不是轮底、不是轮心）量起、
  `run` 在窗口内、超过 `MaxRun` 丢弃。两格墙仍是两个盒，不合并成坡。
- 采样基准必须用 `supportHeightUnder()`：轮底会因履带穿入而低于地面，直接把一格台阶
  读成高于一格并拒绝。用轮下最高支撑盒的顶面才是真实台阶高度。
- `stackedOnSameFootprint` 拒绝“叠在同样大小盒子上”的方块。爬上一层两格墙后，上层方块
  从新支撑量起只高一格，单靠 rise 会被当成合法台阶并逐层棘轮爬墙。真实台阶踩在更宽的
  地面上，footprint 比例能区分两者。
- `TrackedStepAssist.contactMatches()` 要求流形点落在该真实盒附近。
- `TrackedStepAssist.contactAssist()` 把车体前进方向投影到**真实接触平面**。
- `TrackedVehicleRuntime.assistTarget()` 只允许一个**台阶面**（`StepFaceKey`：接近方向的
  近面位置 + 顶面高度）在每个物理子步驱动一个接触点。按单盒计费时，一条九格宽的台阶
  可以每列各写一次目标，等于把辅助乘以列数。
- `applyBeltContact()` 只写 `contactMotion1` 与两条摩擦方向。**不写** `setNormalWorldOnB`、
  `setPositionWorldOnA/B`、`setDistance1`，不施加车体或轮端冲量。
- 摩擦方向由 `TrackedContactDrive.contactFrame()` 生成正交单位基。**车体前进方向在接触面内
  投影为零时不写方向**：旧实现回退到水平车体轴，在竖直立面上该轴与法线平行，交叉积退化
  为零，等于把摩擦行写到了法线方向。

**硬边界（本轮实测得出）：竖直立面不能被本策略辅助。** 竖直面的法线是水平的，车体前进
方向在其上的投影为零。早期实现此时“沿平面内向上切向”处理，等价于给履带一条爬墙驱动：
实机 GameTest 中它把车顶起、翻覆并摔到 y=-104。移除该回退后，`contactAssist()` 对竖直面
返回 null，一格正向用例因此**不再爬越**。这不是回归，而是纠正：

- 之前“一格正向通过”其实是这条梯子路径的结果，不是真实履带爬越；
- 当前策略没有合法方向可作用于竖直面，因此 S3.2 的正向退出条件**未满足**；
- 一格台阶需要另一类机制（自有接触/支撑模型），在此之前 S3.2 保持未通过。

`stepAssistEnabled` 默认关闭；关闭时行为与普通驱动路径一致。

#### 现有实现边界

- 51/51 GameTest 通过，其中台阶相关项是：竖面不可辅助（否定结论）、两格墙拒绝、坑无
  支撑、辅助关闭时不可跨越。**没有“辅助开启后爬过一格台阶”的正向证据。**
- 连续一格台阶、辅助期间悬挂 travel 逐帧记录尚未覆盖。
- 台阶常量仍是行为参考值（1.0 / 0.8 m/s 爬升上限 / 2.0 / 0.25..2.5 / 3.0..10.5），
  未经用户冻结；`STEP_TOLERANCE = 0.08` 是穿入容差，同样未冻结。
- 因此 S3.2 仍是 **in_progress 且正向门未通过**，实机暂停点 C 未确认。

#### 退出条件

- 辅助关闭时基础 AABB 碰撞正确，AABB 阶梯坡、楼梯和坑的测试通过。
- 辅助开启时，真实驱动能爬过车型允许的一格台阶；两格墙和连续高墙被拒绝。
- 连续台阶、坑、方块挖除、未加载区块和无接触工况下不产生伪支撑或悬空。
- 同一障碍只辅助一次；没有重复向上冲量、重复承载或穿入加深。
- 记录辅助前后的真实履带接触、法向载荷、悬挂 travel 和车体速度；辅助效果不得写成
  悬挂行程或关节反力。
- `stepAssistMaxRise`、`stepAssistMaxClimb`、`stepAssistRunWindow`、`stepAssistSpeedWindow`
  在 `ACCEPTANCE_LIMITS.md` 冻结后，才可标 `auto_passed`。当前实现常量只是候选值，
  不是验收值；`stepAssistMaxImpulse` 已不再使用。
- **实机暂停点 C：** 用户检查 AABB 阶梯坡、楼梯、一格台阶、墙拒绝、挖空和履带贴地表现。

## 11. S4：驱动、差速和反作用

- 参考 MCSP 左右履带独立 drive slot、skid steering 和负载反馈。
- SBW 实现有界扭矩/切向耦合、制动、滚阻、滑移和反作用。
- 不直接设置 chassis 或履带板速度，不用求解后限制偏航速度代替转向。
- S4 只负责驱动与滑移；不得把台阶辅助提前混入驱动控制，也不得以无接触净力伪造爬坡。

### MCSP 传动响应（2026-10-26 已实现，默认关闭）

`TrackDrivetrainDefinition` / `CompiledDrivetrain` 新增可选字段：

| 字段 | 语义 | 来源 |
|---|---|---|
| `Accel` | 目标速度上升斜率，m/s² | MCSP `Row.Accel` |
| `Brake` | 减速/反向前的制动斜率，m/s² | MCSP `Row.Brake` |
| `PivotSpeed` | 满舵时两侧速度差，m/s | MCSP `Row.PivotSpeed` |
| `SteerTime` | 转向杠杆 0→满舵所需秒数 | MCSP `Row.SteerTime` |
| `TractionLimit` | 是否用实测牵引限制已滑转侧 | MCSP `slotGrip` 意图，SBW 适配 |

实现位置：

- `TrackedDriveController.Controller`：有状态推进，保留 `slew`、转向杠杆和
  `target = throttle * maxSpeed ± lever * pivotSpeed`。
- `TrackedVehicleRuntime.applyDrive()`：按 `1/60 s` 物理子步调用；未声明任何响应字段时
  仍走原无状态 `resolve`，不改变现有 99A 行为。
- `TrackedVehicleRuntime.onResolvedContact`：按侧累计纵向牵引冲量，供 `TractionLimit`
  的下一子步使用；这是观测，不是施加力。
- `TrackedDriveController.slipping`：复现 `(surface - craft) * sign(target) > 0.5 m/s`。
- `TrackedDriveController.tractionGripFraction`：`|traction| / (μN)` 的比例，仅在滑转时
  收紧目标。
- `TrackedContactDrive.coastTarget`：COAST 时按 `exp(-RollingResistance / TPS)` 衰减履带
  自身的切向目标，写回同一条真实接触；不写 `combinedFriction`，不对车体或运行段施力。
  `TrackedVehicleRuntime.lastCoastTarget()` / `coastContactCount()` 为诊断读数。

**边界：** `RollingResistance` 现作为自由滚动**速度衰减**使用，不是 Coulomb 摩擦系数；
MCSP native `setDriveFree0` 的完整求解公式不可恢复，因此这是行为近似，不声称精确复刻。
`TractionLimit` 默认关闭，尚无整车 GameTest。
MCSP 的发动机/变速箱功率预算没有对应移植，`MaxDriveTorque` 仍只是接触摩擦上限。

退出条件：

- 直行、倒车、释放油门、制动、原地左右转、行进转弯方向正确。
- 一侧悬空或阻力增大时可观察到滑移和载荷反馈。
- 无 relay chatter、无突然角速度尖峰。
- 若车型声明 `Accel`/`Brake`/`PivotSpeed`/`SteerTime`：T0 必须证明斜率、反向先制动、
  转向杠杆时间和满舵差速；T3 必须证明真实接触下仍由履带传力。
- 若启用 `TractionLimit`：必须证明只在滑转时收紧、不会把正常加速误判成打滑。
- **实机暂停点 B：** 用户检查操控方向、加速、制动、原地转向和声音/视觉相位。

## 12. R1：MCSP 风格履带渲染

- KaguyaPyCore 只提供物理快照插值和 TrackRun 状态。
- SBW 读取插值后的轮心、半径、厚度和包络方向，重建闭合履带路径。
- 按可见节距和相位采样重复链节模板，输出位置和切线旋转。
- 可见链节不是物理实体；物理运行段和可见链节数量分别管理。
- 履带根骨骼的悬挂偏移只应用一次。
- 旧 `TrackAnimationProfile` 不再作为新履带位置来源。

退出条件：

- T0 路径、wrap、相位、倒车、左右独立和悬挂变化测试通过。
- T4 Bedrock/Geckolib、静止、前进、倒车、原地转向、单侧压坡和网络延迟测试通过。
- **实机暂停点 D：** 用户检查低速/高速、悬挂回弹、落地、转向、渲染和长时间稳定。

## 13. N1：同步、预测和性能

- 服务端权威；客户端只消费不可变快照并做插值。
- 同步车体、轮组、TrackRun 摘要、相位、frame/timestamp 和机构 revision。
- 不发送每个可见链节实体、native handle 或完整世界快照。
- 先测 1 台活动 99A，再测 4 台，包含地形更新、休眠/唤醒、区块边界和网络延迟。
- 记录子步耗时、body/link/joint 数、manifold 数、快照大小、内存和 GC。
- 超预算先做通用优化，不删减物理正确性。

## 14. V1：其他履带车型逐车迁移

每台车独立完成：

```text
数据/模型审计
-> 轮角色审计
-> 物理运行段编译
-> 单轮静载
-> 单侧/双侧闭链
-> 生命周期
-> 驱动差速
-> AABB 地形
-> 台阶辅助（S3.2，必须是受限、可拒绝、与真实履带/悬挂协同的辅助）
-> MCSP 渲染
-> 网络/性能
-> 用户实机确认
-> 常规获取批准
```

推荐顺序：`BMP-2 -> Bradley -> T-90A -> PLZ-05 -> M1A2 -> YX-100 -> Prism Tank`。
99A 通过不能替代其他车型验收。

## 15. F1：断带、脱轨和损毁（后续）

- 只预留 TrackLink、断带、脱轨和修复拓扑接口。
- 断带先表现为失去驱动耦合但保留运行段动力学。
- 脱轨需要独立设计闭链重建、同步、存档和人工测试。
- 不阻塞首轮履带物理和渲染。

## 16. 旧阶段映射

| 旧阶段 | 新处理 |
|---|---|
| P0 文档/边界 | 扩展为 D0 |
| P1 核心高度场 | 撤销并归档，改由 K1 通用 TrackRun API 取代 |
| P2 悬挂/闭链 | 拆为 K1、B1、B2 |
| P3 99A 数据审计 | 保留为 S1 |
| P4 99A 生命周期 | 保留为 S2 |
| P5 驱动差速 | 保留为 S4 |
| P6 高度场越障 | 改为 S3：普通 AABB + SBW 可选台阶辅助 |
| P7 同步/渲染/性能 | 拆为 R1、N1 |
| P8 其他车型 | 保留为 V1 |
| P9 损毁玩法 | 保留为 F1 |

## 17. 阶段总表

首车原则：**ZTZ-99A 是第一辆正向接入并验收的履带载具**；轮式 allowlist 本轮不恢复。
阶段依赖：`D0 → D1 → K1 → S1（拓扑重开）→ B1（复核）→ B2（重设计）→ S2 → S3.1 → S4 → S3.2 → R1 → N1 → 99A 批准 → V1 → F1`。

阶段启动授权记录（2026-10-23）：B1 基线确认后已授权启动 B2；同日晚间轮对运行段拓扑定稿，
因此 S1 拓扑部分重开、B1 转入待复核、B2 fixture 按新拓扑重设计。
授权不等于通过，也不豁免阈值确认。

| 阶段 | 目标与产物 | 退出条件 | 当前状态 |
|---|---|---|---|
| **D0** 文档与边界 | 冻结仓库边界、AABB 地形、物理/渲染分离、阶段门和记录格式 | 文档互相一致；区分目标、现状、撤销和证据 | 已落实；2026-10-26 修正 S3.2 撤销边界、AABB 阶梯坡与 MCSP 证据层级 |
| **D1** 归档与清理 | 移除高度场、探针、楔面、旧履带路径；allowlist 为空 | 零引用、编译和回归通过；GameTest 完成 | 已提交；保留车体 GameTest 3 项通过 |
| **K1** 核心 API | 通用 TrackRun、关节、逐子步快照、限幅输入、插值 | T0/T1/T2 通过；API 契约一致 | 已提交；T0/T1 通过（151 项），T2 通过（16 项）；混合约束与接触面运动仍待原型 |
| **S1** 99A 数据审计 | 显式轮序/wrap、轮端安装、运行段附件、质量拆分、哈希 | 纯数据/几何测试通过；角色与遍历不靠骨骼顺序；三类数量分别记录 | **编译层已完成并通过 T0（97 项）**；旧鞋链为 legacy。S2 接线已开始，退出条件未满足 |
| **B1** 99A 悬挂 | 负重轮独立悬挂、行程、静载、压缩/回弹、双向恢复 | 单轮/单侧/静载可测；力与行程限值冻结 | 待复核（旧成绩保留）；轮端刚体与安装约束改变前提 |
| **B2** 99A 动态履带 | 两轮两运行段 → 单侧完整机构 → 承载 → 双侧扰动 | 四个子阶段 T1/T2 通过；B2 阈值已确认 | **T1/T2 在冻结阈值下通过**；B2.3/B2.4 夹具边界已记录，不得写成整车通过 |
| **S2** 99A 生命周期 | `TrackedVehicleRuntime` 接入 chassis、轮端、运行段、约束、悬挂、owner、输入、快照 | 生成/reset/重载/移除/区块与 Level 卸载/故障恢复均无幽灵对象 | 最新完整 GameTest 48 项中 6 项失败，S2/S3/S4 退出门仍未满足；关世界仍走 Core `addShutdownParticipant`，`closeFuture` 可等待真实拆除。实机暂停点 A 未由用户确认 |
| **S3.1** 基础 AABB | 只用原始方块碰撞验证行驶 | 完整方块/半砖/楼梯/AABB 阶梯坡/坑/桥/两格墙 | AABB 项已有 GameTest。光滑连续斜坡不是方块碰撞，不属于门禁，不另造高度场 |
| **S3.2** 台阶辅助 | 可选 SBW 辅助策略，复现 MCSP 行为目标 | 辅助关闭时基础碰撞正确；辅助开启后真实爬越一格台阶、墙拒绝、无重复承载 | **未通过（in_progress）**：切向目标方案已实现；竖面辅助被证实等价于梯子（曾把车顶翻，已移除），因此**一格正向爬越未达成**。已通过的是：竖面不可辅助、两格墙拒绝（含 `stackedOnSameFootprint` 防棘轮）、坑无支撑、辅助关闭不可跨越。缺连续台阶与悬挂 travel 记录；实机暂停点 C 未确认 |
| **S4** 99A 驱动 | 接触面运动、drive/brake/coast、差速、可测滑移；可选 MCSP 响应层 | 直行/倒车/释放油门/制动/转向/单侧负载；声明响应字段时斜率与转向时间可测 | 控制律 T0 通过（含 `slew`、转向杠杆、`PivotSpeed`、滑移判定、牵引比例）。接触写入有 `onContactConceived`（Core 1.3.3 / Libbulletjme 23.2.0，接触点进入 manifold 之前）与 `onContactProcessed` 两条路径，共享 `applyBeltContact`。真实实体输入链路有 GameTest。`SlipBand` 实测；1800 tick soak 均值约 -3.0 m/s（指令 5.46 m/s），**证明 `target + 1 m/s` 不能当验收带**。MCSP 响应字段默认未在 99A 声明，`TractionLimit` 默认关闭。**滚阻未实现**：两种速度目标写法均实测注入能量（重刹 / 松油门后加速 18%），COAST 现只清除传送带目标。实机暂停点 B 未由用户确认 |
| **R1** 99A 渲染 | MCSP 路径重建与可见链节采样 | T0 路径数学、T4 Bedrock/Geckolib、悬挂变化/倒车/转向/延迟 | 未开始 |
| **N1** 同步与性能 | 服务端权威同步，客户端只插值 | 1 台再 4 台；子步耗时、body/joint 数、内存、网络 | 未开始 |
| **99A 首车批准** | 汇总自动证据、哈希、限值、实机暂停点 A–D | 用户实机确认后标记 Approved/NormallyAvailable | 未开始 |
| **V1** 其他履带车型 | 逐车审计、编译、测试、批准 | 每车独立完成全部阶段 | 未开始，仅 99A 通过后 |
| **F1** 损毁玩法 | 断带、脱轨、修复、同步、存档 | 独立验证拓扑重建 | 仅预留 |
| **轮式车辆** | 不在本轮范围 | 另立范围与验收计划 | **不恢复 allowlist** |
