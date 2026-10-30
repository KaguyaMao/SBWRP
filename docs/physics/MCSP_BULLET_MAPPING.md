# MCSP 证据与 Bullet 复现映射

更新时间：2026-10-26（补齐台阶辅助、悬挂与履带驱动证据边界；补充 S4 传动响应字段的已实现映射）  
状态：研究证据到实现目标的映射。本文不表示对应代码已经实现。

**阅读提示：** 本文与 `MCS_SOURCE_REVIEW/` 都不进入 git 提交。MCSP 只是行为与数据组织的参考，
不是 SBW 或 KaguyaPyCore 的实现来源，也不能代替 Bullet 测试证据。下表按**字段的实际消费位置**分层，
不再把整份 `PhysGearBelt` 当作单一的 native 物理规范。

## 1. 证据来源与可信度

| 来源 | 可信度 | 用途 |
|---|---|---|
| `MCS_SOURCE_REVIEW/quistaphys-java/src/quista/phys/gear/*.java` | 反编译 Java，可逐行核对 | 轮、悬挂、履带定义和状态流 |
| `MCS_SOURCE_REVIEW/mcs-java/quista/mcs/platform/PlatformGearDraw.java` | 反编译 Java，可逐行核对 | 可见履带路径、相位和链节采样 |
| `MCS_SOURCE_REVIEW/quistaphys-java/src/quista/phys/game/PhysTerrain.java` | 反编译 Java，可逐行核对 | 方块碰撞形状到 AABB |
| `MCS_SOURCE_REVIEW/disassembly/slope-belts-contacts.asm` | native 符号和反汇编 | 运行段创建和接触钩子的行为证据 |
| MCSP 原生 Rust 源码 | **不可用** | 不能声称已恢复原始算法 |

反编译和反汇编只能说明行为和调用关系，不能作为原始 Rust 源码。Rapier 的 API、单位和
求解参数不能直接当作 Bullet 参数。

## 2. 已确认事实

### 2.1 地形

`PhysTerrain.read`：

- 读取 `BlockState.getCollisionShape()`。
- 完整方块走 `PhysCellSource.FULL` 快路径。
- 非完整形状调用 `VoxelShape.toAabbs()`，保留半砖、楼梯等多盒碰撞。
- 按 section 烘焙，交给原生地形接口。

结论：MCSP 的基础地形不是全局平滑高度场，而是方块真实碰撞形状的 AABB 集合。

### 2.2 轮与悬挂

`PhysGearWheel.Suspension`：

- `Kind` 为 `ARM`、`SLIDER`、`RIGID`。
- 有 pivot、axis、min/max 行程、stiffness、damping 和 maxForce。
- `centreAt` 根据行程计算轮心：ARM 绕轴旋转，SLIDER 沿轴平移，RIGID 不动。

**边界：** 这些是 MCSP 内部的车型表达。SBW 不复制该 enum，也不把它作为 99A 的
资源字段或兼容层。SBW 的权威定义是 `TrackSuspensionDefinition` /
`TrackSuspensionOverride`，当前 99A 由 `Direction=+Y` 的独立平移行程表达。

**已落实的 MCSP 字段语义（2026-10-26）：**

- `Suspension.Spring` → SBW `TrackSuspensionDefinition.Spring`（N/m，可选）。
- `Suspension.Damper` → SBW `TrackSuspensionDefinition.Damper`（N·s/m，可选）。
- 声明直接刚度/阻尼后，`CompiledSuspension` 使用该值；有效频率由实际 `k/m`
  推导，不再把 authored `Frequency` 当成真实频率。
- 未声明时保留 SBW 原生的频率 + 压缩/回弹阻尼比模型，99A 当前数据不变。
- 逐轮覆盖同样支持 `Spring` / `Damper`，但 MCSP 的 `Kind`、`Travel` 单位与
  `centreAt` 公式不进入 SBW schema；SBW 仍只接受垂直平移悬挂。

`PhysGear.Wheel`：

- 区分 carrier、hub、knuckle、spin 和 suspension。
- 独立悬挂使用 revolute 或 prismatic 关节。
- **带内轮（belt member）取得履带的 drive slot，因此 `free == false`，不创建独立
  hub/spin 层**；无转向时 carrier 直接经悬挂或固定副连到上一级。
- 驱动通过 drive slot 分组，落到 carrier 的 `PartResponse.withDrive`。
- 可见轮面自转由渲染侧按带速积分，不要求存在物理 spin body。

**边界：** MCSP 的 ARM 悬挂与转向会让 carrier 随机构改变姿态，不能概括为“所有
carrier 相对 hull 永不旋转”。SBW 只采用“带内轮无独立轮面自转”这一结论，
不导入 carrier/hub/knuckle/spin 字段。

### 2.3 履带

`PhysGearBelt` (字段定义层) 是一个 record：

```java
record PhysGearBelt(List<Wheel> wheels, double thickness, double linkPitch,
                    int driveSlot, double sag, double lateralFriction)
record Wheel(int index, PhysLoop.Wrap wrap)   // wrap ∈ {OUTSIDE, INSIDE}
```

构造器校验：至少两个轮；`thickness >= 0`、`linkPitch > 0`、`sag >= 0`；
`driveSlot ∈ [0, 255]`；`lateralFriction ∈ [0.01, 1.0]`（默认 `0.6`）；每个轮必须声明 `wrap`。

**字段实际消费位置（可逐行核对）：**

| 字段 | 可确认的消费者 | 是否进入 native `createBelt` 入参 |
|---|---|---|
| `wheels[].index`、轮序 | `PhysGear.rebuildBelts` 收集 carrier；`PlatformGearDraw` 建路径 | 间接（按轮序传 body id） |
| `wheels[].wrap` | `PhysGear.rebuildBelts` 转为 `inside[]`；`PlatformGearDraw.solve` 选包络侧 | 是（作为 `inside` 标记） |
| `thickness` | `PlatformGearDraw` 减去轮半径；`PhysGear` 传给 `createBelt` | 是 |
| `linkPitch` | `PlatformGearDraw.build` → `PhysLoop.linkCount(pitch)` 与可见节距 | **否** |
| `sag` | `PlatformGearDraw.Belt` → `topRun` 选下垂段，`PhysLoop.sag(span, depth)` | **否** |
| `driveSlot` | `PhysGearSpec.driveSlot(i)` 与 `driveResponse`；`PlatformGearDraw.advance` 取带速 | **否**（经 carrier 元数据间接生效） |
| `lateralFriction` | `PhysGearSpec.lateralFriction(i)` → `PartResponse.withDrive` | **否**（经 carrier 元数据间接生效） |

上表第四列很重要：`createBelt` 的 JNI 入参为
`(carriers, radii, inside, widths, runMassKg, axle, depth, runState)`，其中 `depth` 就是
`thickness`。**`linkPitch`、`sag`、`driveSlot`、`lateralFriction` 都不在该入参中。**
但这不等于“与物理无关”：`driveSlot` 与 `lateralFriction` 会经 carrier 的
`PartResponse.withDrive` 间接改变 native 驱动响应；`linkPitch`/`sag` 只服务可见路径。

`PhysGear.rebuildBelts` 的 Java 侧流程：

- 逐轮取 `carrier`、`contactRadius`、`wrap → inside`、`width`；
- 按 `0.2 × (本轮质量 + 下一轮质量)` 算每轮运行段质量分配；
- 调 `PhysScene.createBelt(...)`，再由 `PhysNative.createBelt0(...)` 进入 native；
- `readBeltState` 每轮回读 3 个 double（轮序×3），并进入 `PhysGear.readState` 的状态序列。

native 证据边界（2026-10-23 复审后）：

- `rapier_bridge::belts::<impl Scene>::insert_run` 反汇编可见对
  `RigidBodyBuilder::build`、`RigidBodySet::insert`、`ColliderSet::insert_with_parent`、
  `MultibodyJointSet::do_insert`、`ImpulseJointSet::insert` 的调用，以及失败时的
  `RigidBodySet::remove`。同类符号还有 `remove_runs`、`destroy_belt`、
  `read_belt_state::{{closure}}`、`belt_run`、`belt_sense`。
- 已恢复的拓扑：每个相邻轮对（含 last→first）一个动态 Cuboid 运行段。
- 已恢复的约束：第一端为轮心处多体转动副（mask `0x37`：锁三平移 + 两角）；
  第二端为 impulse joint（mask `0x04`：只锁一个法向平移）。
- 轮位扰动实验显示运行段世界姿态跟随机构改变，而局部盒形在创建/恢复时确定。
- 仍无法从反汇编恢复的部分：完整求解参数、摩擦/自由滚动律、接触修改的精确时序、
  `BridgesSteps` 的完整过滤与失效规则。
- 因此不能声称 SBW 的 Bullet 实现与 Rapier 在动态上等价，只能声称目标语义已确认。

### 2.3.1 上游尺寸与碰撞半径的转换

native `createBelt0` 的入参名字（`radii`、`widths`）容易被读成“原始轮半径/轮宽”，
但 Java 侧在调用前已经改写过它们：

| 步骤 | 位置 | 行为 |
|---|---|---|
| 1 | `PlatformGearSpec.toPhys()` | 有 `linkBone` 时，从 link 模板 bounds 取得横向中心与宽度，**覆盖**带内轮的横向 center/width |
| 2 | 同上 | `contactRadius = (authored wheel radius + belt thickness) × 单位换算` |
| 3 | `PhysGear.rebuildBelts()` | 按 belt wheel 顺序传 `carriers`、`radii=contactRadius`、`inside[]`、`widths`、`runKg`、`axle`、`depth` |
| 4 | `PhysGearWheel.runningSurface()` | 默认轮碰撞圆柱同样使用 `contactRadius` 与 `0.5×width` |

因此：

- **`contactRadius` 不是 authored 轮几何半径**，它已经包含履带厚度。
- run 中心面相对**轮碰撞体**的位置，必须结合 `contactRadius` 与 `depth` 一起解释，
  不能把某个 native 实验里的偏移量直接搬到另一套半径定义上。
- SBW 的 `TrackWheelDefinition.Radius` 保持 authored 轮体半径语义；编译期另有
  `CompiledBeltGeometry.runCenterOffset()` 表达“履带在轮外”的包络。

SBW 采用的外包绕模型：轮碰撞体半径 `R`，履带厚度 `t`，run 中心线在 `R + t/2`，
run 径向半厚 `t/2`。这样 run 与轮是**两个不相交的实体**，静态审计可证明无穿透。
若改用 `R − t/2`，run 会与轮重叠整整一个厚度——这是 SBW 早期实现的实际缺陷。

> `R + t/2` 是 SBW 的包络定义，不是“已从反汇编恢复的 native 公式”。
> native 的精确 cuboid 中心与 self-collision 策略仍未完全恢复，见
> [`B2_RUN_TEST_PLAN.md`](B2_RUN_TEST_PLAN.md)。

### 2.4 渲染

`PlatformGearDraw` 是可见路径与链节采样的参考实现：

- `pose(…)` 按悬挂行程与转向重算每轮中心；`RIGID` 轮不参与行程。
- `solve(belt, sag)` 以 `contactRadius - thickness` 为半径、逐轮 `wrap` 为包绕侧，
  调 `PhysLoop.circles/…/thickness/sag/solve`；**sag 无法闭合时会自动回退到无下垂**。
- `topRun` 从所有上向段中选下垂段，且跳过任一端为 `INSIDE` 的段。
- `linkCount(linkPitch)` 在初始化时给出可见链节数（`max(1, ceil(L/pitch-0.001))`）；
  `restPitch = length / links` 随当前路径长度变化；`advance(…)` 按 `driveSlot`
  对应带速推进相位，并用 `belt.dir` 处理左右/转向符号。
- `link(belt, index, …)` 在路径上按 `phase` 取点与切线，输出位置与 `rotationX`。
- 渲染器**不读取** native 运行段的逐段 pose；它由同步后的悬挂状态重建路径。

结论：物理运行段与可见链节分离，可见链节不是物理刚体。
同时要看清楚：`sag`、`linkPitch`、`driveSlot` 在这里是**渲染侧**消费者，
不是 native 物理运行段的输入。

### 2.5 台阶辅助

Java 侧入口（可逐行核对）：

| 位置 | 行为 |
|---|---|
| `quistaphys-java/src/quista/phys/body/PhysRamps.java` | `minStep` / `maxStep` / `maxClimb` / `minRun` / `maxRun` / `minSpeed` / `fullSpeed`，带范围校验 |
| `quistaphys-java/src/quista/phys/gear/PhysGearSpec.java` | `bridgesSteps` 与 `ramps` 是车型级可选配置 |
| `PhysGear.hold()` / `bridgeSteps()` | 车体与运行机构刚体登记到辅助路径 |
| `PhysScene.setBridgesSteps(...)` | `setBridgesSteps0` 进入 native |
| `PhysScene.readSlopeState()` / `seedSlope()`、`PhysGear.readState()` | 坡面状态参与回读与重建 |

native 符号：

```text
rapier_bridge::slope::<impl Scene>::update_slopes
rapier_bridge::slope::slope_under
rapier_bridge::slope::SlopeView::terrain_carries
rapier_bridge::hooks::slope_normal_y
rapier_bridge::hooks::ResponseHooks::modify_solver_contacts
```

`Scene::step` 调用 `update_slopes`；接触钩子在 `modify_solver_contacts` 内调用
`slope_normal_y`。因此可以确认：辅助在物理步内更新，并经接触钩子参与求解，而不是渲染
侧临时抬高。**不能确认：**

- 坡面几何的精确生成条件。
- 接触修改的完整时序与过滤规则，包括是否只在求解前生效。
- 是否由辅助接触完全接管原始接触，还是与原始地形并行承载。
- 任何可以直接搬到 Bullet 的数值或 API 契约。

**关于“局部 `HeightField`”的表述边界：** 反汇编中可见 Parry 的 `HeightField` 释放符号，
但无法证明 MCSP 为每台车生成了哪种局部坡面几何、覆盖多大范围、何时失效。因此 SBW 文档
只能写“MCSP 存在一条独立的辅助接触路径”，不能把“局部 HeightField”写成已恢复的设计，
更不能据此恢复本项目已撤销的车辆全局/探针高度场。

因此 `BridgesSteps` 只作为行为参考，SBW 必须独立验证 Bullet 等价方案；采用哪种接触响应
（求解前接触修改、受限辅助几何或不实现）仍是 S3.2 的待决项。

## 3. Bullet 复现映射

### 3.1 履带字段的细分映射

不再以整份 `PhysGearBelt` 为一行映射，而是按字段落到它实际影响的层：

| MCSP 字段 | 当前 SBW / Core 对应 | 分层 | 必须注明的边界 |
|---|---|---|---|
| 轮序 | SBW 车型数据的轴向顺序 + `CompiledWheel.index` | SBW 几何层 | 当前按纵向坐标排序，不是显式 `Belt.Wheels` 列表；S1 重开时必须改为显式声明 |
| 逐轮 `wrap` (INSIDE/OUTSIDE) | 无字段；`ClosedTrackLoopSolver` 假设外侧包络 | SBW 几何层 | **真实缺口**。`wrap` 已是 `createBelt` 入参，但 SBW 尚未声明；99A 当前均为外侧，仍需显式化 |
| `thickness` | `CompiledLinkTemplate.thickness` / 物理鞋体 halfExtents | SBW 几何层 | 当前来自模型模板实体尺寸，不是独立字段；两者目的不同，不能直接互换数值 |
| `linkPitch` | `Track.Pitch` | SBW 几何层 | 用于物理 pin 距与闭链求解；MCSP 的 `linkPitch` 只进入可见链节计数与采样 |
| `sag` | 无字段 | 展示层（R1） | 仅在 MCSP 可见路径中生效。**不要把渲染下垂当成 native 物理下垂，也不要给 99A 填造数值** |
| `driveSlot` | 无字段；驱动轮角色隐含一个分组 | SBW 驱动策略（S4） | MCSP 用它做同一输入分组；SBW 当前每侧只有一个主动轮，暂无多分组需求 |
| `lateralFriction` | `Drivetrain.Friction` + 待定的轮接触策略 | SBW 接触策略（S4） | 不等于核心 `TrackRunSpec.friction`（那是链节与地形的库仑摩擦） |

结论：MCSP 的履带字段中，当前 SBW 已明确承接的是节距与厚度的等价概念；
`wrap`、`sag`、`driveSlot`、`lateralFriction` 仍是相应层的未实现能力，属于 B2/R1/S4 的工作项。

### 3.2 机制与运行段映射

| MCSP | Bullet/SBW 目标 | 注意 |
|---|---|---|
| `PhysTerrain` AABB | 保留 KaguyaPyCore voxel/AABB 地形 | 不新增高度场 |
| `PhysGearWheel.Suspension` | SBW 自有悬挂定义 + KaguyaPyCore 通用关节 | 只参考行为；不复制 schema |
| `SLIDER` | 行为参考：平移行程；带内轮无独立滚动轴 | 对应 SBW `Direction` 平移行程，轮端刚体不自转 |
| `ARM` | 行为参考：摆臂 + 轮体自转 | SBW 当前未使用；需要时新增 SBW 自有字段 |
| `RIGID` | 行为参考：固定轮或仅轴转 | 对应 SBW 非悬挂轮角色 |
| `createBelt` 入参 | 每 run 一条单 link、无内部 joint 的 `TrackRunSpec` | 参数集不同：MCSP 以轮对为主，SBW 由 SBW 侧构造外部两端约束 |
| `insert_run` | KaguyaPyCore 通用 `TrackRun` + SBW 两端约束 | 拓扑语义已确认；Bullet 动态等价仍待测试 |
| `readBeltState` | `TrackRunSnapshot` | 不可变，跨线程 |
| `PlatformGearDraw.solve` | SBW `McspTrackPath` | 纯数学，不创建 Bullet |
| `PlatformGearDraw.link` | SBW 可见链节采样 | 只输出渲染变换 |
| `BridgesSteps` | SBW 可选 `TrackedStepAssist` | 独立验证，不移植 Rapier 钩子；实现为真实 AABB 接触上的受限切向目标（`contactMotion1` + 正交摩擦基），不修改法线/接触点/距离，不施加独立向上力。**竖直立面没有合法平面内方向**：早期“向上切向”回退等价于梯子，实测顶翻车辆，已移除。因此一格正向爬越未达成，S3.2 未通过 |
| `ChassisDrivetrain.Row.Accel` / `Brake` | `TrackedDriveController.Controller` 的 `slew` | 已实现为可选 `Accel` / `Brake`（m/s²）；未声明时保持原即时目标 |
| `ChassisDrivetrain.Row.PivotSpeed` | `TrackedDriveController.Controller` 的差速目标 | 已实现为可选 `PivotSpeed`（m/s）；替代 `Differential` 的归一化拆分 |
| `ChassisDrivetrain.Row.SteerTime` | 转向杠杆斜率 | 已实现为可选 `SteerTime`（秒）；`lever += clamp(steer-lever, ±dt/time)` |
| `ChassisDrivetrain.slew` | `TrackedDriveController.slew` | 反向时先按 `Brake` 减速到零，再按 `Accel` 反向；目标更接近零时用制动率 |
| `SLIP_MPS = 0.5` | `TrackedDriveController.slipping` | 已实现为诊断判定；不等于验收滑移带 |
| `slotGrip = |traction|` | `tractionGripFraction` + `TractionLimit` | 已实现为可选反馈；默认关闭，当前未做整车 GameTest |
| `ChassisGround` 读牵引/接触 | `onResolvedContact` 累计 `tractionImpulseNs` | 已接线为上一子步的纵向冲量观测；不是关节载荷 |
| `Drivetrain.RollingLoss` | `TrackedContactDrive.coastTarget`（**未调用**） | **未实现。** 两种把损耗写成接触速度目标的写法都实测注入能量：按运行段表面速度→20 tick 内 0.98→0.15 m/s（重刹）；按车体速度→松油门后 120 tick 加速 18%。COAST 现在只清除传送带目标。`coastTarget()` 的指数数学保留为候选，不代表已实现的滚阻；native `setDriveFree0` 公式不可恢复 |

## 4. MachineMax 参考边界（悬挂与台阶）

MachineMax 基于 Bullet/Libbulletjme，其悬挂与台阶实现与 MCSP 的 Rapier 方案无关，因此
只能按“同一引擎下的对照”使用，不能替代 MCSP 的履带语义。

| MachineMax 内容 | 证据 | SBW 处理 |
|---|---|---|
| `New6Dof` 逐轴限位、平衡点、刚度、阻尼 | 公开源码 | 只借鉴约束轴配置与单位；SBW 的 `TrackedJointFactory` 保持弹簧关闭 |
| 按等效质量与步长限制刚度/阻尼 | 公开源码 | 借鉴为稳定性审计思路；不自动裁剪用户参数 |
| 生产关节弹簧（`enableSpring`） | 公开源码 | **不启用**：SBW 的力由 `PhysicsSuspensionRow` 成对施加，同时启用会重复计力 |
| GearJoint 履带成员模型 | 公开源码 | **不移植**：履带拓扑严格按 MCSP 运行段 |
| 攀爬候选扫描与 `stepHeight` 门控 | 公开源码 | 借鉴候选与拒绝逻辑 |
| `LocalHeightField.solveContact` 与接触几何改写 | 公开源码 | **拒绝移植**：与 AABB 约束和“不伪造支撑”冲突 |

结论：MachineMax 用于悬挂配置与稳定性对照；履带严格按 MCSP；台阶只借鉴门控，接触
响应由 SBW 自己的真实 AABB 切向目标方案承担。

## 5. 不直接移植的内容

- Rapier 的 joint 类型、solver 参数和接触钩子 API。
- native `insert_run` 的内部结构布局，以及从反汇编推断出的任何“等价拓扑”。
- MCSP 的车型数值。
- MCSP 模型路径和资源命名。
- Rapier 的高度场或坡面内部实现。
- 把 `PhysGearBelt` 整体当作 SBW 字段方案：它只提供字段语义与消费位置的参照。
- 把 MCSP 渲染侧的 `sag` / 可见 `linkPitch` 写成 native 物理下垂或物理节距。

## 6. 验证顺序

1. 普通 AABB 地形和车体。
2. 单轮悬挂静载与轮端框架。
3. 两轮两运行段的混合约束（端 1 可转、端 2 单法向）。
4. 完整单侧轮对机构与承载。
5. 双侧与扰动。
6. 驱动和差速。
7. 台阶辅助独立验证。
8. 可见履带路径和链节渲染。

每一步都必须有 Bullet 测试证据，不能以 MCSP 行为描述代替。
