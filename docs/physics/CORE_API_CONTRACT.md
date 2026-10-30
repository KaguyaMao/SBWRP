# 跨仓核心 API 契约

更新时间：2026-10-28  
状态：代码归并基线 `e90984a`；工作树 API 追加 terrain consumer request ownership 与 revision install gate；Core 当前全量测试通过  
对应文档：`KaguyaPyCore:docs/CORE_SCOPE.md`、`KaguyaPyCore:docs/TRACK_RUN_API.md`、
`KaguyaPyCore:docs/PHYSICS_INTERPOLATION.md`、`KaguyaPyCore:docs/THREADING_AND_LIFECYCLE.md`、
[`REFERENCE_IMPLEMENTATIONS.md`](REFERENCE_IMPLEMENTATIONS.md)

## 1. 目标

冻结 KaguyaPyCore 向 SuperbWarfare 提供的通用 Bullet 能力边界，避免车辆逻辑再次进入
核心库，也避免 SBW 绕过物理线程和生命周期规则。

## 2. 允许进入核心库

- 物理线程、任务队列、固定步进和故障恢复。
- 单一任务接纳门、可等待关闭和统一 native teardown。
- 通用 Bullet body、shape、joint 创建和销毁。
- 通用 joint 两端局部 constraint frame 与关节坐标观测。
- owner、碰撞组、同 owner 过滤和元数据。
- 通用动态履带运行段 `TrackRun`。
- 不可变 `PhysicsFrameSnapshot`、`TrackRunSnapshot` 和稳定 body/run 身份。
- 通用物理状态插值。
- 通用接触快照中的稳定 body id 与帧号。
- 现有通用 Minecraft voxel/AABB 地形碰撞构建。
- Detached `TerrainRegionSnapshot`、完整 `TerrainRevisionToken`、consumer-id `requestRegion/releaseRegion`
  和短程 `withRevisionToken` 安装校验；这是通用 terrain-cache API，不承载车型策略。
- 不含车型语义的一维 SI 弹簧—阻尼原语：行程窗口、压缩/回弹阻尼、缓冲块、
  对称绝对力上限；返回带符号力（压缩为正，下垂为负）。

## 3. 禁止进入核心库

- `LocalHeightField`、`TerrainSupportField`、`HeightFieldContact`。
- `SUPPORT_PAIR`、`HEIGHT_FIELD`、`SLOPE`、`RUN_BLOCKS`。
- 车辆台阶规则、爬坡高度、车型坡面。
- ZTZ-99A 或其他车型 ID、资源路径、轮骨骼名。
- 模型、材质、Bedrock/Geckolib 类型。
- 差速、油门、制动和车辆输入语义。
- 可见链节模板和履带路径渲染。
- 车辆前进轴约定、电机符号映射、车型悬挂定义、轮角色、载荷分配和
  “固定参考履带板”的闭链方案。
- 核心可以提供一个**不含车型语义**的 SI 弹簧—阻尼数学原语（`PhysicsSuspensionRow`）
  以及通用 `guidedWheelAxle` / 成对力原语；如何把车型数据编译成该原语由 SBW 决定。
- 关节载荷/冲量字段（当前 wrapper 不维护该值，不得伪造）。
- 第二套全局物理世界或调度器。

## 4. TrackRun API

### 4.1 `TrackRunSpec`

通用物理描述：

```text
links: shape(Box|Cylinder), mass, initial pose
joints: pin anchors, single hinge axis, angular limit,
        frameRotationInPrevious, frameRotationInNext
collision group, collide-with mask, same-owner allow mask
damping, friction, restitution, CCD
explicitGravity (optional; null inherits world gravity)
maxLinkForceN, maxLinkTorqueImpulseNms
```

`frameRotationInPrevious/Next` 指定两端局部 constraint frame，`axis` 在该 frame 中解释。
两端初始姿态不同或左右镜像时必须显式给出；identity 默认保持旧行为。

不得包含：

- 左/右履带业务语义。
- 主动轮、导动轮、负重轮角色。
- 车型 ID。
- 模型骨骼。
- 可见链节模板。
- 地形策略。
- 履带包络层字段：逐轮 wrap、可见下垂 `sag`、可见节距、驱动分组、侧向摩擦。
  这些由 SBW 持有；MCSP 也未将其传入 `createBelt`。

### 4.1.1 `TrackRunAssembly`（public 装配 helper）

- 只做逐项 native 创建：一个 link body 的形状、质量、位姿、owner、碰撞组、同 owner
  允许列表、摩擦、阻尼、显式重力与 CCD。
- 不管理线程、生命周期、快照、输入或回滚账本；这些仍属于 `TrackRunRuntime`。
- 下游在自己的 `PhysicsSpace` 里做 native 诊断时必须用它，避免复制一份 runtime 的 body
  配置而让夹具与生产出现差异。
- 生产车辆仍必须经 `TrackRunRuntime.create()` 与 `withLinkBodies(...)`；
  使用 `TrackRunAssembly` 不代表已经满足运行时门。

### 4.2 `TrackRunRuntime`

runtime 本身充当运行段句柄；不额外提供 `TrackRunHandle` 类型。

- 由物理线程**事务式**创建 link body 和 joint；每个对象创建即记账（配置属性前）。
- 任一步失败：逆序移除 joint、再逆序移除 body，清理异常作为 suppressed，原错误重新抛出。
- 回滚失败时残留对象仍由 runtime 记账，关闭必须重试或以异常结束，不能报告成功。
- 设置 owner、碰撞组和同 owner 允许列表。
- 默认继承世界重力，只有显式配置才使用自定义重力。
- 每个物理子步结束后发布 `TrackRunSnapshot`。
- `create()` 完成时首帧已经存在；关闭取消的 create 必须失败；关闭后不得复活。
- 提供有上限的力/力矩冲量输入，并区分「已接纳」与「已施加」：
  - 同步方法返回钳制后的接纳大小；
  - `*Async` 方法返回 `TrackRunInputReceipt.Future`，其 `completion` 在物理线程执行后完成。
- `withLinkBodies` 在物理线程提供受控的 native link 列表，用于建立外部约束或读取接触；
  回调不得保留列表/body，所创建的 joint 由调用方拥有并在本运行段关闭前移除。
- `closeAsync()` 幂等；Level 仍在运行时在 native 清理完成后完成，Level 已停止时改为在
  Level 统一 teardown 完成后完成。
- `closeNow()` / `resetNow()` 是物理线程同步入口，供 shutdown participant 和“已移动车体后
  立即重定位”使用；它们不再入队，因此不会在关闭期被拒绝。
- 处理 reset、remove 和 Level unload；reset 入队前复制位姿列表，并推进 `poseEpoch`。

### 4.3 `TrackRunSnapshot`

只包含普通值：

```text
name, runId, poseEpoch, physicsFrame, revision
links: position, rotation, linear/angular velocity, active, colliding
joints: previousIndex, nextIndex, angleX/Y/Z, positionError, angleError
jointPositionError, jointAngleError
linkBodyIds
```

不包含 native 指针、Minecraft 对象或渲染对象。列表和旋转在读取时不可被消费者改写。

- `runId` 是产生快照的 runtime 身份；跨运行段不得插值。
- `poseEpoch` 在 reset/teleport 时改变；跨 epoch 不得插值。
- `physicsFrame` 与 `PhysicsFrameSnapshot.tick` 同一计数器，可按帧号对齐。
- `linkBodyIds` 按 spec 顺序给出稳定 body id，供下游关联外部约束与接触。
- `joints[i].angleX/Y/Z` 来自 native `New6Dof.getAngles`，是约束坐标（弧度）。
- `jointAngleError` 由 native 约束坐标计算，表示超出允许范围的锁轴/自由轴偏差。
- **没有关节载荷字段**：Bullet 6-Dof 不维护 `appliedImpulse`（恒为 0）。接触冲量可用于
  接触载荷/牵引估计，但不能唯一恢复闭链内单个关节反力，不得把两者等同。

## 5. 插值 API

### 5.1 `PhysicsStateInterpolator`

输入：

- 前后两个不可变 `PhysicsFrameSnapshot`（按 `bodiesById` 稳定 id 对齐）。
- 时间比例。
- 可选 `TrackRunSnapshot`（要求 `runId` 与拓扑一致）。

输出：

- 插值后的 body pose、AABB、速度和 TrackRun link pose。
- 周期相位按最短路径插值。

规则：

- 不持有 Bullet native body。
- 不修改物理状态。
- 不访问 Minecraft。
- 不生成可见链节或骨骼变换。
- 离散字段（active/colliding/contactResponse/activationState）取较近帧，不混合。
- body 集合、world epoch、run 身份或 poseEpoch 不一致时直接返回新帧。
- native 关节采样不插值；`joints` 与 `linkBodyIds` 原样保留。
- 插值后的整帧同时重写 `bodies` 与 `bodiesById`。

### 5.2 边界

KaguyaPyCore 的“插值”只指物理快照数值插值。SBW 的 MCSP 路径重建、链节采样和骨骼
绑定不属于核心库。

## 6. 线程与生命周期

- 创建、修改、加入、移除和销毁 Bullet 对象只在物理线程。
- 跨线程调用使用核心任务 API，不得直接访问 native getter。
- `NEW`/`STARTING` 状态仍接纳任务（排队）；`STOPPING`/`STOPPED`/`FAILED` 立即拒绝并
  让 Future 失败，不挂起调用方。
- 启动完成也经过接纳门：关闭抢先后不能把 Level 写回 `RUNNING`。
- 已接纳但尚未执行的任务在关闭开始后失败，不会在 teardown 中创建新 native 对象。
- joint 两端 body 进入同一物理空间后才注册 joint。
- 关闭顺序：停止接纳 -> shutdown participant（物理线程，可有序 removeJoint/removeBody）
  -> 通知 listener -> 排空已接纳任务 -> 移除 joint -> 移除 body
  -> 销毁 terrain/world/shape 注册表 -> 完成 Future。
- shutdown participant 不得再 `submitTask`：此时任务已被拒绝，被拒绝的清理不能算完成。
- `FAILED` 保留可观测状态，但仍执行完整 teardown。
- native 清理失败会让 shutdown Future 异常完成，不能伪装成 STOPPED 成功。
- Level 卸载和区块卸载必须清理运行段，不留幽灵对象。
- 关闭是可等待的；`isClosed` 单独不足以证明 native 清理完成。
- 静态刚体（地形、零质量 link）不读取速度：Libbulletjme 对静态体速度 getter 有断言。

## 7. 版本与兼容

- 当前核心代码基线为 `e90984a`（归并提交，不含文档）；工作树在此基础上增加了
  1.3.2 的关闭 participant、1.3.3 的 Libbulletjme 23.2.0 升级与接触 conceived 回调，
  均尚未提交。
- 核心 API 变更必须记录 `core_commit`；未提交时写 `WORKTREE`，不得写占位 SHA。
- SBW 事件账本记录 API 版本、受影响车型和复核状态。
- 核心库不为单一车型增加兼容分支。
- 下游不得依赖核心库内部类或 native 实现细节。
- SBW 工作树依赖 `cn.solarmoon:spark_core:1.20.1-1.4.3-physics-metrics`（本地 `publishToMavenLocal` 制品），
  并实际创建 `TrackRunRuntime`：`TrackedVehicleAssemblyLedger` 持有生产 runtime，
  车辆创建/回滚/关闭都经它。1.3.2 新增公开 `addShutdownParticipant`、`addCloseListener`，
  以及 `TrackRunRuntime.closeNow` / `resetNow`。
- 1.3.2 的关闭顺序：停止接纳 → shutdown participant（物理线程，可直接 removeJoint/removeBody）
  → close listener → 排空任务 → native teardown → 完成 Future。participant 抛错仍继续 teardown，
  并让 Future 异常完成。
- 1.3.3 新增 `PhysicsContactConceivedEvent`（接触点进入 manifold 之前）与
  `PhysicsBodyEvent.ConceivedContact`，并把内嵌 Libbulletjme 升到 23.2.0。
  `PhysicsLevel` 显式请求 `StepFlag.contactConceived`：不请求就不会触发，
  因此升级后必须同时保留该 flag 与 `contactProcessed`。
- 1.4.3-physics-metrics 在 1.4.1 terrain API 基础上增加不可变 `PhysicsStepMetrics` 快照值和
  分阶段计时；Core 全量 159 项通过，SBW 已消费并重跑完整 48 项 GameTest（42 通过、6 失败）。
  指标不改求解行为，也未证明失败的唯一原因。

## 8. 验收

- 核心库测试只验证通用物理、线程和生命周期。
- 车辆数据、轮角色、驱动和渲染测试留在 SBW。
- 核心测试通过不能把任何车型标为实机通过或批准。
- T2 已执行通过（16 项 GameTest）；K1 已提交，`core_commit` 记录为
  `f44e72ed10be63d3f54f89cd4526bd280269d300`。
