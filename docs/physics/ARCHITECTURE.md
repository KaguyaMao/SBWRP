# 履带物理目标架构

更新时间：2026-10-26（S3.2 辅助边界与 MCSP 证据修订）  
状态：SBW 车型定义 + Bullet 物理设计基线；D0/D1/K1 已完成，S1 拓扑部分待按轮对运行段重开，
B1 基线保留待复核，B2 进行中，S3.2 受限切向目标已实现但一格正向未达成

## 1. 分层

```text
Forge / Minecraft 主线程
  ├─ 读取车型资源、模型和地形区段
  ├─ 生成不可变 VehicleSpec / VehicleInput / SectionSnapshot
  ├─ 注册、批准和常规获取过滤
  └─ 消费 PhysicsFrameSnapshot 和渲染状态

KaguyaPyCore / Spark Core（通用 Bullet 能力）
  ├─ PhysicsLevel：每 Level 一个物理线程，固定 60 Hz
  ├─ Body / Shape / Joint 创建、owner、碰撞组和生命周期
  ├─ TrackRun：通用动态履带运行段的物理创建和状态读取
  ├─ PhysicsFrameSnapshot：跨线程不可变结果
  └─ PhysicsStateInterpolator：通用数值插值

SuperbWarfare（全部车辆专用功能）
  ├─ TrackedVehicleCompiler / Registry
  ├─ 轮角色、独立悬挂、张紧、质量预算（SBW 自有数据定义）
  ├─ TrackRunSpec 适配和驱动/差速
  ├─ 轮端刚体、运行段两端约束与接触驱动
  ├─ 普通 AABB 地形策略和可选台阶辅助（S3.2 当前只有分类原型）
  ├─ TrackedVehicleRuntime：创建、步进、快照、关闭
  ├─ 履带路径和重复链节渲染（MCSP 行为参考）
  └─ 网络、预测、验收和车型批准

车型数据 / 适配层
  ├─ 模型路径和骨骼绑定
  ├─ 轮心、半径、宽度、轴向和角色
  ├─ 物理运行段、可见节距候选来源和板形状
  ├─ 质量、惯量、悬挂和驱动参数
  └─ 准入状态、哈希和测试证据
```

## 2. 地形与接触

普通地形由 KaguyaPyCore 已有的 voxel/AABB 构建提供：

```text
LevelChunk
  -> SectionSnapshot
  -> TerrainShapePlan（保留非完整方块多 AABB）
  -> 物理线程静态地形刚体
  -> 履带板 / 车体 / 轮体碰撞
```

不得在核心库新增车辆高度场、全局平滑面或车辆专用坡面。SBW 的台阶辅助是独立策略：

```text
原始地形 AABB（含楼梯、半砖、AABB 阶梯坡）
  -> 游戏线程 TrackedTerrainSample（不可变 AABB + 障碍身份）
  -> 物理线程 TrackedObstacleClassifier
  -> 可选 TrackedStepAssist（逐障碍有限状态）
  -> TrackedTerrainContactPolicy（原始/辅助接触所有权）
```

辅助关闭时，车辆只依赖原始 AABB 碰撞。辅助开启时，同一位置不能同时由原始地形和辅助
表面提供向上支撑，同一障碍也不能被多个轮端或子步重复辅助。

**当前实现边界：** `TrackedStepAssist.sampleTerrain()` 读取真实 `VoxelShape.toAabbs()`，
`candidatesAhead()` 逐个盒筛选（含 `stackedOnSameFootprint` 拒绝连续立面），
`TrackedVehicleRuntime.applyBeltContact()` 只在真实 run-terrain 接触上写受限切向目标与
正交摩擦基。**竖直立面不辅助**：投影为零时不写方向，因为旧“向上切向”回退等价于梯子并
实测顶翻车辆。因此一格正向爬越未达成，上图仍是目标分层。

**MCSP 参考边界：** MCSP 在 `PhysScene.setBridgesSteps()` / `update_slopes` 中维护辅助
状态，并通过 `modify_solver_contacts` / `slope_normal_y` 影响接触；`PhysRamps` 提供台阶、
坡长和速度门限。native Rust 源码不可用，坡面几何生成、过滤细节和接触时序不能唯一恢复。
SBW 只能复现行为目标。接触响应已选定为真实接触上的受限切向目标，但它无法处理竖直立面；
一格台阶仍需另一类机制，不能照搬 Rapier 钩子，也不能据此恢复全局高度场或人工抬升。

## 3. 机构负载路径

```text
地形 AABB
  -> 动态运行段（相邻轮对之间的刚体）
  -> 运行段两端约束（第一端转动副 / 第二端单法向）
  -> 轮端刚体
  -> 独立悬挂（负重轮）
  -> 车体
```

承载必须能经约束传力，不能只统计轮-板接触。导动轮和主动轮只承担数据声明的导向、
张紧和传动职责；悬挂平移、轮端相对转动与传动自由度必须正交。

悬挂数据以 SBW 的 `TrackSuspensionDefinition` / `TrackSuspensionOverride` 为准，
MCSP 的 `Suspension.Kind` 不是 SBW 字段。当前 99A 使用 `Direction=+Y` 的独立平移行程，
由 SBW 适配层用通用 `New6Dof` 构造；`PhysicsJoints.guidedWheelAxle` 的放行轮轴旋转
**不用于**带内轮安装。

可选 `Spring` / `Damper` 直接表达 MCSP 的 `Suspension.Spring` / `Damper`：声明后
`CompiledSuspension` 使用该 SI 值，并由实际 `k/m` 推导有效频率；未声明时保留频率 +
压缩/回弹阻尼比模型。99A 当前未声明，B1 仍待复核。

轮端刚体相对安装机构不自转：它承担安装、轮形碰撞与质量，可见轮面自转只为渲染。
这不代表世界角速度为零，车体转动时轮端随机构转动。

悬挂是**双向**的：压缩时车体与轮被推开（`F > 0`），下垂时弹簧把轮拉回 bind pose
（`F < 0`）。`MaxForce` 是绝对力上限，不是"仅允许支撑力"的上限。把负向力钳成零会
删除下垂恢复力，属于错误实现。

**承载路径的阶段分界：** B1 只证明单站悬挂行与车轮对之间的力学；
「地形 → 运行段 → 轮端约束/轮体 → 悬挂 → 车体」这条完整链路属于 B2 的承载 fixture。
B2 也不等于 S2 的生产生命周期接线。

驱动路径：

```text
SBW 左右履带输入
  -> 驱动状态（DRIVE / BRAKE / COAST）
  -> 真实接触面的切向运动目标
  -> 运行段与地面摩擦
  -> 反作用回到机构与车体
```

不直接设置车体速度，不直接设置运行段位置。轮面自转不是驱动生效的必要条件。

可选 MCSP 响应层（`Accel` / `Brake` / `PivotSpeed` / `SteerTime`）只限制**目标速度的
生成**，不改变传力路径；接触层仍是唯一把目标变成运动的地方。`TractionLimit` 默认关闭，
它使用上一子步的实测纵向冲量，只在已滑转时收紧目标。**滚阻尚未实现**：COAST 只清除
传送带目标；两种速度目标写法都实测注入能量（重刹或反向加速），`coastTarget()` 是未调用
候选。

## 4. 物理履带与渲染履带

### 4.1 物理履带

- 由 KaguyaPyCore 通用 `TrackRun` API 创建，每个相邻轮对一个运行段（含 last→first）。
- 每个运行段是一个动态 Bullet 刚体，带盒形碰撞体、质量、owner 和碰撞组。
- 运行段不与相邻运行段通过销链相连；它两端分别约束到两个轮端刚体。
- 第一端为轮心转动副，第二端只锁法向平移，切向可滑。
- 状态以不可变 `TrackRunSnapshot` 发布。

### 4.2 渲染履带

- SBW 读取插值后的权威机构状态：轮心、半径、厚度、wrap 和带速。
- 重建闭合履带包络路径。
- 可见链节数在初始化时固定，间距随当前路径长度分配。
- 按相位在路径上采样，输出位置和切线旋转，重复可见链节模板。
- 可见链节不创建 Bullet body，不写回物理状态。

### 4.3 数量关系

```text
物理运行段数  != 可见链节数  != 模型动画骨骼数
```

三者分别记录和测试。物理密度不足时降低物理段数，不能降低可见质量或用虚拟整侧板替代。

## 5. 线程和生命周期

### 创建

1. 游戏线程读取实体和车型数据，生成不可变 `TrackedVehicleSpec`。
2. 物理线程创建 chassis、轮体、TrackRun link、joint 和悬挂。
3. 设置 owner、碰撞组和同 owner 过滤后加入 physics world。
4. joint 两端 body 进入同一物理空间后才注册 joint。

### 每个物理子步

1. 处理输入、地形计划和机构状态。
2. 应用悬挂、张紧和驱动控制量；不直接改 pose。
3. Bullet 推进一个 `1/60 s` 子步。
4. 读取求解后的 body/link frame，发布不可变快照。

### 关闭

```text
停止接受输入
  -> 禁用驱动耦合
  -> 移除/禁用 joint
  -> 移除 link/wheel body
  -> 移除 chassis joint/body
  -> 清理 owner、缓存和快照引用
```

关闭必须幂等。区块卸载和 Level 卸载不能留下幽灵 body。

## 6. 当前代码与目标的关系

当前实际接线见 [`CURRENT_WIRING.md`](CURRENT_WIRING.md)。下表只写目标：

| 当前代码 | 目标 |
|---|---|
| KaguyaPyCore `LocalHeightField`、`terrain/support/` | 已退出活动路径并归档 |
| KaguyaPyCore `TerrainSlopePlanner`/`TerrainSlopeBodies` | 已删除，不再提供楔面 |
| KaguyaPyCore `ProbeSuspensionUnit`/`PlanarTrackSupport` | 已退出活动路径；旧履带支撑不恢复 |
| SBW `VehicleBody` 旧履带分支 | 已删除；由 `TrackedVehicleRuntime`（S2）取代 |
| SBW `physics/tracked/TrackedRig` 等候选 | 已归档删除；按 SBW 数据定义由 S1/S2 重建 |
| SBW `TrackAnimationProfile` | 不再作为新履带位置来源；由 MCSP 路径渲染取代 |
| KaguyaPyCore 通用 voxel/AABB 地形 | 保留 |

MCSP 证据的分层边界（详见 [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) §3.1）：

- 履带字段模型、可见包络求解、以及 Java/native 运行段桥接是可逐行核对的贡献。
- `linkPitch`、`sag`、`driveSlot`、`lateralFriction` 不在 `createBelt` 入参中；
  其中 `linkPitch`/`sag` 属于可见路径层，`driveSlot`/`lateralFriction` 经 carrier
  元数据间接影响驱动响应，都不能当作 native 物理入参。
- native `insert_run` 已确认创建“相邻轮对运行段 + 第一端转动副 + 第二端单法向约束”；
  仍不能据此推断 SBW 的 Bullet 实现与 Rapier 动态等价。

## 7. 与旧高度场架构的区别

旧架构试图让高度场成为车辆唯一地面支撑。新架构：

- 保留真实方块碰撞形状。
- 用动态运行段和轮端刚体承担载荷。
- 把低台阶处理降为 SBW 可选辅助。
- 可见表示不参与求解，但与物理共享包络输入。

因此核心库不再需要高度场，SBW 也不再需要探针 fallback。

## 8. 履带地形：detached region snapshot

履带车的局部地形 mesh 不再由 SBW 在服务端线程直接读取 `Level`。整条路径是：

```text
PhysicsChunkManager（Core；主线程快照 / 物理线程已应用状态）
  SectionSnapshot 缓存（不可变、带 revision）
    -> snapshotRegion(bounds) -> TerrainRegionSnapshot + TerrainRevisionToken
    -> requestRegion(consumerId, bounds) / releaseRegion(consumerId)
       按消费者声明、续期、释放缓存区域

TrackedVehicleRuntime（主线程）
  publishStepPose() 发布的 chassis 中心
    + TerrainEnvelope（编译期轮系/运行段包络）
    -> TrackedTerrainSampler.window()（中心按 1 格对齐，半径包含半格位移余量）

TrackedTerrainAutoSampler（每 Level 独立队列）
  -> 首次 mesh 优先、ready 车辆轮转、每车 build/apply 同时至多一个
  -> 构建线程只读 TerrainRegionSnapshot（无 Level / BlockState / Bullet）

物理线程
  -> revisionTokenAt / withRevisionToken 校验完整 section identity
  -> TrackedTerrainMeshProxyRuntime.install(plan, requestId, generation)
     revision、window、request、generation 不符 -> 丢弃
     add replacement 成功后才 remove previous
```

要点：

- `WindowRadius` 变成**上界**，真正的窗口由 `TerrainEnvelope` 决定。固定 6 格半径曾把窗口放大到
  20×16×20，覆盖 Core 根本不会构建的 section，导致车辆永远等不到地形。
- runtime 将 requested 与 installed window 分离；对已安装 mesh 的移动或 revision 变化必定
  触发 rebuild，不会把新的 cache request 误当成已安装 coverage。
- 计划安装期间持有 Core `withRevisionToken`；Core cache edit/build/replacement 使用同一 revision
  边界，stale section build 和 stale vehicle mesh 均不允许覆盖较新的 solver geometry。
- 任一被窗口覆盖的 section 缺失 / inactive / dirty / revision 未应用时，`snapshotRegion` 返回
  `Missing`，车辆等待；不会退化成部分 mesh，也不会去读 `Level`。
- 履带刚体被排除在 `CollisionGroups.TERRAIN` 之外，因此不会出现在 Core 的激活查询里；它必须
  用 `requestRegion` 显式声明自己需要的区域，否则缓存不会构建，请求会永远是 `Missing`。
- 等待期间动态刚体**不转 kinematic**，只把 linear/angular factor 归零并保存速度，见
  `TrackedTerrainWaitController`。Bullet 不支持两个 kinematic 刚体之间的关节，而履带机构本来就
  由 mount 与运行段端约束连接。
