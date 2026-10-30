# 验收限值

更新时间：2026-10-26（S3.2 辅助指标口径修订；补充 S4 传动响应与牵引观测 ID；数值仍未冻结）  
状态：架构边界已冻结；B1 悬挂限值与 B2 动态运行段限值已由用户确认并冻结，其余大多未冻结

没有写入本文件并经过对应阶段用户确认的数字，不能当作通过阈值。旧测试里出现过的误差
只是观察值，不是新架构限值。禁止为了让失败测试变绿而在运行后放宽本文件。

## 1. 已冻结的边界（不是可调参数）

### 1.1 地形和接触

- 基础地形是 Minecraft 方块 `VoxelShape` 转成的 AABB。
- 不使用全局高度场、`LocalHeightField`、`TerrainSupportField`、`HEIGHT_FIELD` 或
  `SUPPORT_PAIR` 作为车辆支撑。
- 不使用探针承载、固定楔面、按车长坡长、`setPos`/直接位置修正、人工抬升，或旧
  `VehicleBody.stepAssist` 的无界/永久向上力。S3.2 的受限台阶辅助是独立目标能力，
  不等于这条禁令被解除。
- 可选台阶辅助只能由 SBW 实现，且必须先关闭辅助验证基础碰撞；辅助必须有高度、坡长和
  速度上限，并与原始 AABB 互斥承载。
- 同一接触片不得同时由原始地形和辅助表面提供向上支撑。
- 两格及以上立面不得被辅助策略削成可爬坡面。

### 1.2 机构

- 负重轮独立悬挂；导动轮、主动轮张紧自由度由数据声明。
- 悬挂平移、轮端相对转动和传动自由度必须正交。
- 车型和悬挂数据以 SBW 自有定义为准；MCSP 的 `Suspension.Kind` 等不是 SBW 字段。
- 独立悬挂是双向恢复机构：压缩支撑、下垂拉回。`MaxForce` 是力的绝对上限，
  不得把负向恢复力钳成零。
- 物理履带是相邻轮对之间的动态运行段加两端约束；可见链节不是物理刚体。
- 每个带内轮站一个动态轮端刚体，无独立轮面自转自由度。
- 运行段数量、可见链节数和模型骨骼数分别记录。
- 驱动必须有反作用、负载反馈、最大扭矩和可测滑移。

### 1.3 关节观测语义

- 关节坐标来自 native `New6Dof.getAngles`，单位弧度，仅在采样它的物理子步内有效。
- `jointAngleError` 只统计超出允许范围的角度与锁轴旋转；限位内必须为 0。
- 核心不发布关节载荷/冲量：Bullet 6-Dof 的 `appliedImpulse` 恒为 0，任何"载荷读数"
  都必须由接触冲量与施加力推导，不能来自该字段。
- 输入接口的同步返回值是「已接纳」大小，不是「已施加」证明；需要证明时使用 Future。

### 1.4 物理与渲染

- 渲染结果不写回物理 body、速度或位置。
- 客户端不独立模拟权威物理。
- 物理运行段和可见链节不一一绑定，但共享包络输入与权威机构状态。
- 可见链节数量初始化时固定，间距随当前路径长度变化。

## 2. 已冻结：B1 99A 逐站悬挂（2026-10-23 用户确认）

适用范围：KaguyaPyCore `1.20.1-1.3.1`（`129ca4d` 的悬挂行语义）、SBW 归并提交
`1b5c984` 的车辆数据与 fixture，以及 `IMPLEMENTATION_PLAN.md` §6 记录的单轮原生 fixture。
这组数字只覆盖**逐站数值与单轮夹具**，不覆盖整车动态承载路径。

| ID | 冻结定义 | 阈值 | 证据 |
|---|---|---|---|
| `staticLoadError` | 行静载与 `m_station·g` 的差 | `≤ 1 N` | `PhysicsSuspensionRowTest`、`TrackedSuspensionTest` |
| `staticLoadError`（质量守恒分量） | 每侧 6 行簧载质量之和与车体质量之差 | `≤ 1e-6 kg` | `TrackedSuspensionTest` |
| `staticSagError` | 原生静置后行程与 `g/ω²` 的差 | `≤ 0.012 m` | `NativeSuspensionRowTest` |
| `suspensionTravelError` | 关节限位处的行程误差 | `≤ 0.02 m` | `NativeSuspensionRowTest` |
| `bilateralRecoveryError`（方向） | 零速满压缩 `F > 0`；零速满下垂 `F < 0` | 布尔断言必须成立 | `PhysicsSuspensionRowTest`、`NativeSuspensionRowTest` |
| `bilateralRecoveryError`（幅值） | 下垂力与 `k·travelDown` 的差 | `≤ 1 N` | `PhysicsSuspensionRowTest` |
| `bilateralRecoveryError`（恢复残差） | 零重力释放后距 bind pose 的行程 | `≤ 0.02 m`，且离开下垂限位 `≥ 0.10 m` | `NativeSuspensionRowTest` |
| `forceCapAbsoluteError` | 正负饱和时力幅与上限差 | 两侧各自 `≤ 1e-3 N` | `PhysicsSuspensionRowTest` |
| `dampingAssertion`（能量） | 同一正弦输入一个周期的耗散积分 | `> 0`，且完整阻尼高于 `0.25×` 回弹阻尼基线 | `PhysicsSuspensionRowTest.dissipatedEnergyJ` |
| `dampingAssertion`（原生对照） | 相同释放条件下的峰值速度 | 完整阻尼模型小于 `0.02×` 阻尼模型 | `NativeSuspensionRowTest` |

**量测口径，避免把两种证据混为一谈：**

- 周期耗散必须在 T0 的 `dissipatedEnergyJ` 上判定；该测试实际把回弹阻尼降到 `0.25×`
  作为“更软”对照，不是 `0.02×`。
- 原生 `NativeSuspensionRowTest` 比较的是峰值速度，用的是 `0.02×` 缩放，
  只能作为原生对照，不能报告为原生能量实测，也不能与上面的 `0.25×` 混写。
- `staticSagError` 的 `0.012 m` 是单站质量在真实静态地面上的原生落点结果，
  不能把 T0 闭式解当成实测。
- 本组阈值只对上述 fixture 成立。整车履带板载荷路径属于 B2，不得引用这些数字作为
  B2 通过依据。

### 2.1 重新打开规则

以下任一变化必须追加 superseding 事件，并把 B1 置回待复核，旧成绩保留不删：

- 悬挂力律、符号约定、缓冲块或 `MaxForce` 语义变化；
- 99A 悬挂参数、`LoadShare`、`Mass`、`Pitch`、`LinkCount` 等机构数据变化；
- 轮端刚体、运行段拓扑或安装约束 frame 变化；
- 核心版本变化，或 `PhysicsSuspensionRow` / `SuspensionRowSpec` 的字段与检查变化；
- fixture 装配方式、重力、接触设置或采样窗口变化；
- 本文件任何阈值变化。

用户于 2026-10-23 明确说明“之后可能仍需要更改”，因此 B1 状态是**当前版本自动通过**，
不是永久批准，也不是实机通过。

**当前状态：** 轮对运行段引入了新的轮端刚体、安装约束 frame 与施力点，因此 B1 已进入待复核，
旧成绩作为历史保留。注意：质量方案 A 保留 authored 轮 `Mass`，所以**站质量数字本身可能
保持不变**；重新打开的理由是机构与安装语义变化，不能写成“A 必然改变了站质量”。

### 2.2 尚未冻结、通过前必须补记的限值

| ID | 用途 | 阶段 | 状态 | 单位要求 |
|---|---|---|---|---|
| `endpointAnchorError` | 运行段第一端两侧锚点距离误差 | B2 | **已冻结（§2.3）** | 米；初始装配与运行中分开 |
| `endpointFrameAngleError` | 第一端锁轴角误差 | B2 | **已冻结（§2.3）** | 弧度 |
| `normalResidual` | 第二端沿声明法向的残余位移 | B2 | **已冻结（§2.3）** | 米 |
| `tangentialSlip` | 第二端切向滑移（诊断量，不是越小越好） | B2 | **已冻结（§2.3）** | 米；须注明采样窗口 |
| `lockedAxisDrift` | 轮端相对安装机构的旋转漂移 | B2 | **已冻结（§2.3，并入锁轴角）** | 弧度；应接近 0 |
| `runDrift` | 运行段相对轮端的长期漂移 | B2 | **已冻结（§2.3）** | 米/物理子步或总漂移，须注明采样窗口 |
| `runWheelPenetration` | 运行段与轮体/地形的最大穿入深度 | B2 | **已冻结（§2.3）** | 米 |
| `runContactCoverage` | 受载期实际出现运行段-地形接触的比例 | B2 | **已冻结（§2.3）** | 计数或比例 |
| `trackRunStaticLoadError` | 静置窗口地面对运行段的总法向支撑与总重力负载之差 | B2 | **已冻结（§2.3）** | 相对误差；由接触冲量估算，不得解释为关节反力 |
| `trackRunInputAccept` | 输入接纳/钳制比例 | B2/S4 | 未冻结（S4 才产生输入路径） | 比例或 N / N·m·s |
| `visiblePitchError` | 可见链节节距误差 | R1 | 未冻结 | 米或模型单位 |
| `visiblePhaseSlip` | 可见链路与权威带速的相位偏差 | R1 | 未冻结 | 弧度或周期比例 |
| `visibleCountStability` | 可见链节数在同一配置内是否恒定 | R1 | 未冻结 | 布尔 + 观测计数 |
| `stepAssistMaxRise` | SBW 辅助允许的最大单级台阶；当前实现常量为 1.0 m，但尚未经用户冻结 | S3.2 | 未冻结（实现常量 1.0） | 米/格 |
| `stepAssistMaxClimb` | 一个候选坡段的累计上升上限；当前常量 2.0 m | S3.2 | 未冻结（实现常量 2.0） | 米 |
| `stepAssistRunWindow` | 可用坡段长度窗口；当前常量 0.25..2.5 m | S3.2 | 未冻结（实现常量） | 米 |
| `stepAssistSpeedWindow` | 辅助启动/满权重速度；当前常量 3.0 / 10.5 m/s | S3.2 | 未冻结（实现常量） | m/s |
| `stepAssistInjectedImpulse` | 辅助注入的独立向上冲量；选定真实接触方案后必须恒为 0 | S3.2 | 未冻结；当前实现不提供该路径 | N·s |
| `stepAssistContactMotionCount` | 真实 run-terrain 接触上收到辅助目标的数量 | S3.2 | 未冻结（诊断计数） | 计数 |
| `stepAssistNormalRewrite` | 辅助是否修改接触法线/接触点/距离 | S3.2 | **硬性禁止** | 布尔，必须为 0 |
| `stepAssistDuplicatePerFrame` | 同一候选在同一物理子步是否被重复消费 | S3.2 | 未冻结（当前实现每候选每子步最多一次） | 计数 |
| `stepAssistMaxImpulse` | 旧冲量指标；选定接触方案后必须替换，不得保留空 ID | S3.2 | **不再使用** | — |
| `driveAccelSlope` | 声明 `Accel` 后目标速度上升斜率 | S4 | 未冻结 | m/s² |
| `driveBrakeSlope` | 声明 `Brake` 后的减速/反向制动斜率 | S4 | 未冻结 | m/s² |
| `driveSteerLeverTime` | 转向杠杆从 0 到满舵的时间 | S4 | 未冻结 | 秒 |
| `drivePivotSpeed` | 满舵时两侧速度差 | S4 | 未冻结 | m/s |
| `tractionGripFraction` | 滑转侧 `|traction|/(μN)` 的观测比例 | S4 | 未冻结 | 比例 0..1 |
| `p7Budget1` / `p7Budget4` | 1 台和 4 台活动车性能预算 | N1 | 未冻结 | ms、接触数、内存、网络延迟 |

在对应 ID 仍是“未冻结”时，可以记录测试命令是否跑通，但不得写成验收通过、
`auto_passed` 或实机通过。

### 2.3 已冻结：B2 99A 动态运行段（2026-10-24 用户确认）

**适用范围：** 仅覆盖 `superbwarfare:ztz_99a` 的 B2 native fixture（`TrackedTwoWheelRunNativeTest`、
`TrackedSingleSideRunNativeTest`、`TrackedChassisLoadPathNativeTest`、`TrackedBothSidesRunNativeTest`）
与单侧 T2 装配门（`TrackedRunRuntimeGameTests`）。它**不**覆盖整车动态、真实 Minecraft
`VoxelShape` 地形（S3）、驱动/制动（S4）或 `TrackRunRuntime` 的整车生命周期（S2）。
用户于 2026-10-24 明确确认“阈值即为此”，本表数值自此为该版本的冻结基线。

| ID | 冻结定义 | 阈值 | 证据 |
|---|---|---|---|
| `endpointAnchorError`（装配） | 第一端两侧锚点世界距离，装配瞬间 | `≤ 1e-4 m` | `TrackedTwoWheelRunNativeTest`、`TrackedSingleSideRunNativeTest` |
| `endpointAnchorError`（B2.1 转动窗口） | 绕自由轴施加力矩后，300 子步锚点距离 | `≤ 5e-3 m` | `TrackedTwoWheelRunNativeTest` |
| `endpointAnchorError`（B2.2 重力窗口） | 完整单侧重力下 600 子步锚点距离 | `≤ 1e-2 m` | `TrackedSingleSideRunNativeTest` |
| `endpointAnchorError`（B2.4 40 s 窗口） | 自由双侧车体 2400 子步后，每侧第一端锚点间隙 | `< 0.10 m` | `TrackedBothSidesRunNativeTest` |
| `endpointAnchorError`（B2.3 单侧滑轨） | 单侧夹具 B2.3 内第一端锚点距离 | 由 `TrackedChassisLoadPathNativeTest` 的结构断言覆盖 | `TrackedChassisLoadPathNativeTest` |
| `endpointAnchorError`（T2 装配） | 生产 runtime 装配时第一端两锚点间隙 | `< 0.02 m` | `TrackedRunRuntimeGameTests` |
| `endpointFrameAngleError`（B2.1） | 第一端锁轴角误差，力矩窗口内 | `≤ 5e-3 rad` | `TrackedTwoWheelRunNativeTest` |
| `endpointFrameAngleError`（B2.2） | 第一端锁轴角误差，重力窗口内 | `≤ 1e-2 rad` | `TrackedSingleSideRunNativeTest` |
| `normalResidual` | 第二端沿声明法向的残余位移（沿法向 400 N、120 子步） | `< 0.02 m` | `TrackedTwoWheelRunNativeTest` |
| `tangentialSlip` | 第二端切向滑移（沿切向 400 N、120 子步，**诊断量，必须可滑而非越小越好**） | `> 1e-4 m`；全锁负向对照 `< 0.1×` 自由值 | `TrackedTwoWheelRunNativeTest` |
| `lockedAxisDrift` | 并入 `endpointFrameAngleError`：本组 fixture 用第一端锁轴角代表轮端相对安装漂移 | 同 `endpointFrameAngleError` | 同上 |
| `runDrift`（无载） | 无载运行段位置漂移，300 子步 | `< 0.01 m` | `TrackedTwoWheelRunNativeTest` |
| `runDrift`（相对首轮） | 完整单侧重力下，运行段中心到首轮中心距离变化，600 子步 | `< 0.02 m` | `TrackedSingleSideRunNativeTest` |
| `runDrift`（闭链） | 闭链运行段到末轮距离变化，180 子步受 1200 N 拉力 | `< 0.02 m` | `TrackedSingleSideRunNativeTest` |
| `runWheelPenetration` | 运行段与轮体/地形最大穿入深度 | `≥ -0.001 m`（`TrackedRunGeometryAudit.CONTACT_TOLERANCE_M`） | `TrackedSingleSideRunNativeTest` |
| `runContactCoverage`（B2.3） | 单侧窗口内运行段-地形接触 | `runTerrain > 0` 且 `wheelTerrain = 0` 且 `chassisTerrain = 0` | `TrackedChassisLoadPathNativeTest` |
| `runContactCoverage`（B2.4） | 双侧窗口内每侧运行段-地形接触 | 每侧 `runTerrainByGroup > 0` | `TrackedBothSidesRunNativeTest` |
| `trackRunStaticLoadError`（B2.3 闭合） | 地面对运行段总法向支撑与 `(簧载+运行机构)` 重力之差的相对值，180 子步窗口 | `< 0.2` | `TrackedChassisLoadPathNativeTest` |
| `trackRunStaticLoadError`（B2.3 行份额） | 簧载行承担的车体重占比 | `0.5..1.15`（余量经惰轮/主动轮固定 mount） | `TrackedChassisLoadPathNativeTest` |
| B2.4 旁路占比 | `(车轮+车体) 地形冲量 / 运行段地形冲量`，全车与逐侧 | 全车 `< 0.05`；每侧 `< 0.05` | `TrackedBothSidesRunNativeTest` |
| B2.4 两侧平衡 | 两侧运行段地形冲量之比 | `min > 0.5 × max` | `TrackedBothSidesRunNativeTest` |
| B2.4 冲量接纳 | 40000 N·s 施加后 `Δv` 与 `J/m` 的相对误差 | `< 0.15` | `TrackedBothSidesRunNativeTest` |
| B2.4 横向衰减 | 1 s 后 `|vx| < 0.75×peak`；再 2400 子步后 `|vx| < 0.5×peak` 且不回升 | 布尔断言成立 | `TrackedBothSidesRunNativeTest` |
| B2.4 无 mount 负向对照 | 拆除全部 mount 后车体下降量 | 比有 mount 多降 `> 0.05 m` | `TrackedBothSidesRunNativeTest` |

**必须连同结论一起读的边界：**

- **B2.3 是单侧夹具**：车体由竖直滑轨约束姿态，只证明“重量经悬挂 → 轮端 → 运行段 → 地面”，
  不证明“车体被抬起”。旁路由窗口累计接触计数观测，轮/车体掩码允许接地，不靠掩码排除。
- **B2.4 不证明静止**：该夹具没有驱动/制动/滚阻（S4），2400 子步后仍沿 Z 蠕移约 18.5 m。
  因此 B2.4 的通过口径是“40 s 窗口内保持装配与承载、冲量被接纳并衰减”，**不是静置平衡**。
  静置读数在 S4 提供滚阻/制动策略之前不可达，不得靠加长窗口或调阻尼伪造。
- **T2 只证明装配生命周期**：完整单侧 8+8 经 `TrackRunRuntime` 创建、真实轮端 mount、
  外端 joint 与运行段 body 全量拆除；它不加载荷。
- `runContactCoverage` 允许末端站位出现少量 `wheelTerrain` 擦地（编译弦相对异径轮的几何事实）；
  是否旁路按**法向冲量占比**判定，不以原始接触计数为零为条件。

B2 的阈值不得直接沿用 §2 的 B1 数字，也不得用 B1 的单轮成绩推断 B2 通过。

## 3. 明确不是新限值的旧数字

- 2026-10-18 弹簧相对误差 `0.210146...`。
- 闭链观察 `dy=-0.10023558`、`worstPitchError=0.016635...`。
- 旧探针 GameTest 中的 `drift <= 1.2` 和偏航角速度限制。
- 旧高度场 `maxDownwardCorrection = 1`、`maxRiseForSupportTransition = 1` 和
  `RUN_BLOCKS = 6`。这些属于已撤销方案，不是新限值。
- 旧 `CLIMB_RUN_BLOCKS = 3` 和任何法线改写阈值。
- K1 原生测试里的 `worst < 0.08 m`、`distance == 0.3 ± 0.01 m` 等数字只是当前夹具断言，
  不是 99A 验收限值。

补充说明：B1 单轮夹具中的 `0.012 m`（静载落点）与 `0.02 m`（属于行程与恢复残差）
已在 §2 被用户确认为 **B1 暂时冻结基线**，但适用范围仅限于该 fixture。
不得把它们当作整车、B2 运行段或其他车型的阈值，也不得以此推断 B2 通过。

## 4. 接触所有权

新架构的接触来源闭集：

| 来源 | 含义 |
|---|---|
| `TERRAIN_AABB` | Minecraft 方块碰撞形状的普通地形接触 |
| `TRACK_RUN` | 动态运行段与地形的物理接触 |
| `WHEEL_BODY` | 轮端刚体与地形的物理接触（应仅限未受带包覆的轮） |
| `STEP_ASSIST` | SBW 可选辅助表面或辅助接触 |
| `STRUCTURAL_OBSTACLE` | 侧壁、天花板、实体等障碍接触 |
| `NONE` | 无接触 |

`TERRAIN_AABB` 是环境来源，`TRACK_RUN`/`WHEEL_BODY` 是参与接触的对象，两者不是互斥枚举：
运行段与地形接触时，环境来源仍是 `TERRAIN_AABB`。禁止的是**同一支撑面被辅助几何
重复提供向上支撑**，不是禁止运行段本身接触地形。

## 5. 表面和材质

- 摩擦和软硬属性来自方块数据或 SBW 显式策略。
- 不在核心库维护车辆高度场表面类别。
- 辅助台阶策略不得依赖未加载区块的猜测表面。
- 未加载区块按普通地形缺失处理，不生成辅助坡。

## 6. 变更规则

- 新增或修改限值必须先改本文件，再改实现和测试。
- 测试失败后放宽限值必须记录原因、影响车型和用户确认。
- 限值变化会使受影响车型回到待复核状态。
- 已冻结基线的修改不得直接覆写：需追加 `records/events.jsonl` 的 superseding 事件，
  并保留旧基线与旧成绩作为历史。旧事件不得被改写或删除。
- 阈值与实现必须同步：若代码已通过新阈值而本文件尚未记录，不得宣称验收通过。
