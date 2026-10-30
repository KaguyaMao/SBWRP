# B2 动态履带运行段测试计划

更新时间：2026-10-24（改为轮对运行段拓扑；阈值已由用户确认并冻结）  
状态：B2 已获授权启动。**B2.0 观测契约（T0）、B2.1–B2.4 直接 native 诊断、
单侧 8+8 的 Core 运行时 T2 门均已在冻结阈值下通过**；阈值见
[`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2.3。B2.3/B2.4 的承载结论仍是
**夹具边界内的结构证据**，不是整车实机结论。
本文件是 fixture 与量测设计，**不是通过证明**  
上游阶段：[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) §7、S1 审计（拓扑待重开）、B1 悬挂行  
下游阶段：S2 生产生命周期接线

## 1. 本阶段的边界

B2 只证明一件事：**已审计的轮对运行段机构能被 Bullet 装配成稳定机构，并且
地形 → 运行段 → 轮端约束/轮体 → 悬挂 → 车体的载荷路径真实存在且可测量。**

B2 不做：

- 车辆实体生成、存档、区块/Level 卸载（S2）。
- 驱动、差速、滑移控制（S4）。
- 可见履带路径与链节渲染（R1）。
- 台阶辅助（S3.2，当前只有分类原型，未通过）。
- 99A 实机验收（T5）。

B2 fixture 与 S2 的生产 `TrackedVehicleRuntime` 不是同一件东西。fixture 通过不等于车辆
已经接入；`VehicleBody` 仍然不调用 `TrackRunRuntime`。

## 2. 唯一输入来源

所有几何与质量必须来自 S1 编译结果，fixture 不另建一套数字：

| 需要的东西 | 来源 | 禁止行为 |
|---|---|---|
| 运行段盒形与质量 | `CompiledTrackSide.runs` + `TrackedMassAllocator` | 手写 box 或凭感觉改质量 |
| 运行段初始位姿 | `CompiledRun.center` / `rotation` / `halfExtents` | 另算一套闭链几何 |
| 端 1 / 端 2 锚点与 frame | `CompiledRunAttachment.joint`（`CompiledJointPlan`） | 用刚体 X 轴猜测关节轴 |
| 轮心/半径/轴向/角色 | `CompiledWheel` | 从骨骼顺序推断角色 |
| 轮端安装类型与限位 | `CompiledWheelMount` + `CompiledJointPlan` | 在 fixture 里重写自由度 |
| 悬挂行参数 | `CompiledSuspensionStation.toRowSpec()` | 在 fixture 里重写刚度 |
| 摩擦与预算 | `CompiledDrivetrain`、`TrackRunSpec` 默认值 | 为让测试好看而调预算 |

`TrackedRunSpecs.compileRuns(...)` 是唯一允许的 `TrackRunSpec` 生产者。若发现它缺少字段，
应先补它的测试，而不是在 fixture 里手工拼一个 spec。

> 上述符号已在 S1 轮对拓扑编译层实现。B2 fixture 现在通过
> `TrackedRunSpecs.compileRuns(...)` 与 `TrackRunAssembly` 创建运行段，
> 并通过 `TrackRunAssemblyProbe.requireMatches(...)` 强制 spec 与编译侧一致；
> B2.1 起的外端约束统一走 `TrackedJointFactory`。
>
> **生产 `TrackRunRuntime` 的创建仍在 T2 GameTest 中验证，不在纯 JVM 的 B2 native
> fixture 里**：native fixture 没有 `PhysicsLevel`。B2 fixture 通过不等于
> `VehicleBody` 已接线；allowlist 仍为空，生产接线属 S2。

## 3. 子阶段与 fixture

### B2.0 规格与读数准备

**状态：已按轮对运行段改写完成（T0）。** 新探针与记录契约已实现，旧鞋链探针降为
`LegacyB2AssemblyProbe`（deprecated），`FULL` 密度改按 run 数量定义。
B2.1–B2.4 的直接 native 诊断已执行（T1）；单侧 8+8 的 Core 运行时装配由 T2 门覆盖。

现有产物是
[`B2RunObservation.kt`](../../src/main/kotlin/com/atsuishio/superbwarfare/physics/tracked/B2RunObservation.kt)
与 [`B2RunObservationTest.kt`](../../src/test/kotlin/com/atsuishio/superbwarfare/physics/tracked/B2RunObservationTest.kt)：

- `B2Sampling`：固定 `1/60 s` 子步、短窗口 300 子步、完整窗口 600 子步
  （B2.4 的测量窗口额外扩到 2400 子步，见 §B2.4）。
  其中 `INITIAL_PIN_TOLERANCE_M = 1e-6` 与 `INITIAL_FRAME_TOLERANCE_RAD = 1e-3`
  是**装配自洽上限**，不是 B2 验收阈值。
- `TrackRunAssemblyProbe.requireMatches(...)`：强制 spec 与 `CompiledTrackSide` 一致：
  质量、盒形、位姿、旋转、单 link/无内部 joint，以及 spec 与 run 一一对应。
- `TrackRunAssemblyProbe.measure(...)`：把每个端的两侧分别按**各自 body** 放置后比较，
  而不是都从 run 推算（后者会恒为零、无差别通过）。
- `B2RunObservation`：字段全部必填，`problems(expectedRuns)` 拒绝非有限值、
  自碰撞、站数不匹配、符号错误与超出轮数的接触计数；它**不**包含任何阈值判定。
- 刻意缺省：记录中没有关节载荷/冲量字段；负载证据只来自悬挂行与接触冲量。
- `B2ChainDensity`：`FULL` 改按 run 数量定义，不再写死审计的 79 节。

已验证：`B2RunObservationTest` 16 项全部通过；旧鞋链探针降为
`LegacyB2AssemblyProbe`（deprecated），只保留可复现性，不作 FULL 依据。

**轮对运行段改写状态（2026-10-24）：已完成。**

1. `TrackRunAssemblyProbe.requireMatches()` 校验 spec 与编译侧一致：质量、盒形、位姿、
   旋转、单 link/无内部 joint，以及 spec 与 run 一一对应。
2. `TrackRunAssemblyProbe.measure()` 把每个端的两侧分别按**各自 body** 放置后比较，
   而不是都从 run 推算（后者会恒为零、无差别通过）。
3. 端 1 度量 `local X`（轮轴，因为该端锁 Y/Z 而放行 X）；端 2 没有锁定角轴，
   改由 `normalResidualM` 度量。
4. `B2RunObservation` 增加 `topologyRevision`、`endpointAnchorErrorM`、
   `endpointFrameAngleErrorRad`、`normalResidualM`、`tangentialSlipM`、
   `lockedAxisDriftRad` 与 run 口径的接触/穿透/漂移字段。
5. 旧鞋链探针改为 `LegacyB2AssemblyProbe`，保留可复现但不作 FULL 依据。
6. 反射测试禁止伪造关节载荷字段，允许真实接触冲量。

必须先定义的观测量（字段名进测试，不许只在日志里打印）：

```text
endpointAnchorErrorM[i]         端 1 两侧锚点距离误差
endpointFrameAngleErrorRad[i]   端 1 锁轴角误差
normalResidualM[i]              端 2 沿声明法向的残余位移
tangentialSlipM[i]              端 2 切向滑移（应可自由增长）
lockedAxisDriftRad              轮端相对安装机构的旋转漂移（应恒为 0）
runWheelPenetrationM            运行段与轮体最大穿入深度
runTerrainContactCount          出现运行段↔地形接触的运行段数量
runRunContactCount              同 owner 运行段互相接触次数（必须恒为 0）
runDriftM                       指定窗口内运行段相对轮端的漂移
suspensionTravelM[i]            每站行程
suspensionForceN[i]             每站行力（来自核心行，不是关节冲量）
chassisHeaveM / pitchRad        车体位移与俯仰
```

**关节载荷仍不可用。** Bullet 6-Dof 的 `appliedImpulse` 恒为 0，B2 的载荷结论只能来自
接触冲量与施加力。任何“关节反力”读数都不允许出现在本阶段。

### B2.1 两轮两运行段（最小混合约束）

**前置门：S1 轮对拓扑 T0 通过（已完成）、Core `free()` 真自由轴原生验证通过（已完成）、
B2.0 新拓扑探针改写完成（已完成）。前置门已满足。**

**状态（2026-10-24）：直接 native 诊断已执行（T1）；正式 runtime 门由 T2 覆盖。**
B2.1 的 T1 用例是 5 项；B2.2/2.4 各 5/6 项；T2 用例会以完整 8+8 单侧创建
8 个 `TrackRunRuntime`，并断言 16 个外端 joint。
`TrackedTwoWheelRunNativeTest` 5 项原生用例：端 1 锚点在绕轴转动下保持、两条 run 各自的
切向可滑与法向受限、无载不漂移、每个 joint 与它来源的 `CompiledJointPlan` 六轴逐项比对。
端 2 改为三平移全锁时 5 项全部失败，负向对照有区分力。

两轮机制是**诊断拓扑**，不是验收规模：完整 8+8 由 B2.2 与 T2 门覆盖。

| 项目 | 要求 |
|---|---|
| 规模 | 2 个轮端刚体 + 2 个运行段（A→B、B→A），**诊断拓扑** |
| 目的 | 验证端 1 转动副、端 2 单法向约束、无地形静置 |
| 端 2 切向 | 两条 run 各自沿自身 tangent 施加扰动，而不是世界 Y |
| 观测 | 锚点误差、锁轴漂移、法向残差、切向滑移、无自发运动 |
| 通过前提 | 初始误差在装配容差内；本阶段 T1 断言 300 子步内锚点与锁轴保持在结构上界内 |
| 端 1 转动 | 绕自由轴施加力矩后，run 必须真的转过一个可测角度 |
| 负向测试 | 把端 2 改成三平移全锁后，B2.1 五项全部失败（已实测） |
| 失败处理 | 先查 frame 与锚点定义，不得先调阻尼 |
| owner | 两个 wheel body 与两条 run 共享同一个 vehicle owner；side/run 只用逻辑 id 区分 |

本子阶段不承载车体，也不接触地形；它只验证约束语义。

### B2.2 单侧完整轮对机构

| 项目 | 要求 |
|---|---|
| 规模 | 8 个轮端刚体 + 8 个运行段（99A 显式轮序，含 last→first） |
| 目的 | 验证完整侧机构在重力下稳定 |
| 观测 | 端约束残差、运行段漂移、自碰撞、轮段穿透 |
| 静置窗口 | 600 子步 |
| 诊断例外 | 允许减少轮数试验，但必须标记 `diagnostic = true`，不得作为验收证据 |

若完整机构无法稳定，正确做法是先记录失败数据并收窄原因（轮序、wrap、几何、质量、
求解参数），不能永久降低物理规模后宣布通过。

### B2.3 真实承载路径 fixture

**当前实现的夹具边界（必须随结果一起读）：** 单侧只有一条履带，无法平衡车体的滚转力矩，
因此该夹具把车体约束在一根**竖直滑轨**上（仅保留竖直平移），车体仍为动态体并携带单侧
簧载质量。滑轨不承担竖向载荷，只移除单侧夹具无法平衡的滚转/俯仰自由度；夹具因此只能
证明“重量经悬挂、轮端、运行段到达地面”，**不能**证明“车体被抬起”，后者由 B2.4 的自由
双侧车体覆盖。同时车体质量取 `sprungAssemblyMassKg / 2`，与该侧编译行参数一致。

装配顺序（缺一不可）：

```text
静态地形（AABB 或等价平面）
  ↕ 接触
8 个运行段（单侧）
  ↕ 端 1 转动副 / 端 2 单法向约束
8 个轮端刚体
  ↕ 悬挂行力（B1 的 PhysicsSuspensionRow）
车体刚体（质量 = 单侧簧载质量 = sprungAssemblyMassKg / 2）
  ↕ 竖直滑轨（夹具边界，仅锁 X/Z 平移与全部转动；不承担竖向载荷）
静态参考体
```

必须能回答的问题：

1. 车体重量是否主要经由运行段 → 轮端约束/轮体 → 悬挂传递？
2. 是否存在轮体直接接地、车体直接接触、或某个运行段被固定导致的伪承载？
3. 静置后各站行程是否落在 B1 声明的行程窗口内？
4. 各站行力之和与车体重量，加上约束传力后是否闭合？
5. 是否存在运行段完全无接触却仍被当作支撑？

排除条件（任一出现即本次测试无效）：

- 任一运行段被用作支撑的手段性保护重力开关。
- 车体被保护重力或外力托住。
- 存在把某块参考板固定到车体的约束。
- 用 `setPhysicsLocation` 周期性纠正姿态。
- 用不可检测的固定支撑替代承载；夹具的竖直滑轨属于显式声明的边界，必须写进结果。

### B2.4 双侧与扰动

| 项目 | 要求 |
|---|---|
| 双侧机构 | 左右各 8 轮端 + 8 运行段；**同一 vehicle owner**，side/run 用逻辑 id 区分 |
| 互不串扰 | 左侧运行段与右侧运行段不接触 |
| 测量窗口 | 先 600 子步装配，再 2400 子步（40 s）测量窗口；**只证明窗口内装配与承载，不证明静止**，见下 |
| 外部冲量 | 40000 N·s 沿 +X 作用于车体中心；判定 `Δv = J/m`，并检查横向速度衰减 |
| 横向衰减 | 冲量沿 X、蠕移沿 Z，故用 X 速度判定；不带制动，只要求持续衰减而非停止 |
| 旁路观测 | 窗口累计接触，按侧分组；轮端偶发擦地对末轮站位是几何事实，是否旁路按**法向冲量占比**判定 |
| 逐侧承载 | 每侧都要有运行段地形接触；每侧 `(车轮+车体)/运行段` 冲量占比 `< 0.05`；两侧运行段冲量 `min > 0.5 × max` |
| 清理 | 逐项拆卸；装配失败时验证逆序回滚且无幽灵 body/joint |

## 4. 证据等级与记录

| 子阶段 | 最低等级 | 说明 |
|---|---|---|
| B2.0 观测定义 | T0 | 纯数据/纯数学，不创建 Bullet |
| B2.1 两轮两运行段 | T1 | `TrackedTwoWheelRunNativeTest` 5 项，含端 2 全锁负向对照 |
| B2.2 单侧完整机构 | T1 | `TrackedSingleSideRunNativeTest` 5 项，8 轮端 + 8 运行段，重力下 |
| B2.3 单侧承载路径 | T1 | `TrackedChassisLoadPathNativeTest` 4 项，单侧夹具（见下） |
| B2.4 双侧与扰动 | T1 | `TrackedBothSidesRunNativeTest` 6 项，自由车体 |
| B2.1 正式装配 | T2 | `TrackedRunRuntimeGameTests`：完整单侧 8+8 经 `TrackRunRuntime`，chassis-local spec 平移到同一世界原点后再创建，含真实轮端 mount 与全量拆卸断言 |

每次运行必须记录：命令、轮数、运行段数、body/joint 数、采样窗口、诊断标记、
实际失败信息。未冻结的指标只记录观测值，不判定通过。

## 5. 通过条件

B2 可标记通过，必须同时满足：

1. B2.1–B2.4 都有可重现的 T1/T2 证据，且 B2.1 的正式装配由 T2 门覆盖。
2. [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2.3 中属于 B2 的 ID 已逐项确认阈值。
3. 承载路径 fixture 证明载荷经过运行段与轮端约束，而非绕过。
4. 双侧承载与外部冲量结果都在确认后的范围内。
5. 关闭与失败回滚路径都留有证据。

**当前状态：第 1、2、3、5 条满足；第 4 条按冻结口径满足。**

用户于 2026-10-24 确认阈值即 §2.3 所列数值。在该口径下：

- **B2.4 的通过口径是“40 s 窗口内保持装配与承载、冲量被接纳并衰减”，不是静置平衡。**
  该夹具没有驱动、制动或滚阻（属 S4），实测 2400 子步（40 s）内沿 Z 自由蠕移 18.5 m，
  车体速度仍有约 0.5 m/s；继续延长窗口不会收敛。因此“静止”在 S4 提供滚阻/制动策略之前
  **不可达**，只能记录为阶段依赖，不能靠加长窗口或调阻尼伪造。
- 冲量判定为 `Δv = J/m`：39756 kg 车体接受 40000 N·s，实测 1.0061 m/s，理论 1.0061 m/s；
  随后检查 X 速度持续衰减。原先用位移差判定，即使冲量被忽略也可能通过。
- **B2.3 是单侧夹具**，车体由竖直滑轨约束姿态，只证明“重量经 run 到地面”，不证明
  “车体被抬起”。旁路由窗口累计接触计数观测（轮/车体掩码允许接地）。
- **B2.4 逐侧检查**：每侧都必须有运行段地形接触，且每侧 `(车轮+车体)/运行段` 冲量占比
  `< 0.05`；两侧运行段冲量 `min > 0.5 × max`。合并总量不能替代逐侧证据。

B2 的“通过”只表示上述 fixture 在冻结阈值下通过，**不等于整车静置通过，也不等于 S2 接线完成**。

## 6. 与 B1 的关系

B1 已按用户确认的基线在当前版本通过，但轮对运行段会改变下列前提，因此**必须复核**：

- 悬挂行的采样方式或力施加点（轮端刚体取代旧轮体）。
- 站点质量分配（轮端刚体质量、运行段质量与车体质量的拆分）。
- 行程窗口的使用方式。

因此 B2 的第一步不是写 fixture，而是在测试里显式声明它使用的 B1 行参数来源，
避免 B2 悄悄改动 B1 的前提。
