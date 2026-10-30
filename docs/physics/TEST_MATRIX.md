# 履带车辆测试与验收矩阵

更新时间：2026-10-26（AABB 阶梯坡与 S3.2 未完成状态修订；补充 S4 MCSP 传动响应用例）  
适用架构：SBW 车型定义 + Bullet 物理 + MCSP 风格履带渲染

## 1. 证据等级

| 等级 | 含义 |
|---|---|
| T0 | 纯数据、几何、单位或渲染路径数学测试，不创建 Minecraft 世界 |
| T1 | 原生 Bullet 独立机构测试，不读取 Level |
| T2 | Spark/KaguyaPyCore 地形、线程和通用 TrackRun 测试 |
| T3 | SBW 单车服务端 GameTest |
| T4 | 客户端、网络、渲染烟测 |
| T5 | 用户实机调试确认 |

只有用户在游戏中确认对应车型和场景后，该场景才可标记为“实机通过”。D0–S1 的证据确认
不是实机通过。未写入 [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) 的数字不能当作
通过阈值。

**首个正向车辆案例固定为 `superbwarfare:ztz_99a`。** T2 通用核心测试不绑定车型；
从 T3 起的车型测试第一台必须是 99A。禁用车型“不应进入物理路径”的负向测试不算首车接入。

## 2. 普通地形矩阵

| 用例 | 期望 | 最低等级 |
|---|---|---|
| 完整方块平面 | 车体、轮、履带板与 AABB 正常接触 | T1/T3 |
| 半砖 | 保留原始碰撞高度和形状 | T0/T3 |
| 楼梯 | 多 AABB 接触，不被压成整格 | T0/T3 |
| AABB 阶梯坡（由真实方块逐级构成） | 接触法线和摩擦正常；不是光滑高度场 | T1/T3 |
| 光滑连续斜坡 | **不属于 AABB 地形门禁**；不得用高度场或固定楔面伪造 | 不适用 |
| 一格台阶 | 基础碰撞先验证；辅助开启后由 SBW 策略处理 | T1/T3 |
| 连续一格楼梯 | 逐级接触；辅助不得产生悬浮或重复承载 | T3 |
| 两格墙 | 必须拒绝 | T2/T3 |
| 连续高墙 | 必须拒绝 | T3 |
| 洞/空气 | 失去支撑，不生成伪地面 | T1/T2 |
| 未加载区块 | 等待或无支撑，不猜测地形 | T2 |
| 桥面/桥下 | 选择正确碰撞层，不穿层承载 | T2/T3 |
| 部分方块和栅栏 | 保留非完整 AABB | T0/T2 |
| 方块挖除 | 地形 revision 更新，旧支撑失效 | T2/T3 |
| section 边界 | 碰撞连续，无重复刚体 | T1/T2 |

## 3. 机构矩阵

| 用例 | 期望 |
|---|---|
| 单负重轮 | 静载约束、轮端无独立自转、悬挂平移独立 |
| 悬挂行程 | 上下限位有效，无穿透 |
| 压缩/回弹 | 阻尼方向正确，有力和能量断言 |
| 两轮两运行段 | 端 1 可相对转动；端 2 只锁法向、切向可滑 |
| 端 2 负向测试 | 改成三平移全锁后，切向滑移测试必须失败 |
| 轮位扰动 | 抬升一端后运行段跟随，局部盒形不变 |
| 单侧完整机构 | 8 轮端 + 8 运行段，端约束残差可测 |
| 双侧机构 | 左右互不串扰，质量预算一致 |
| 导动轮 | 不被误当负重轮；张紧自由度可解释 |
| 主动轮 | 有界耦合、反作用、滑移读数；无轮自转电机 |
| 运行段碰撞 | 与地形和轮体接触正确，自碰撞过滤 |
| 外部冲量 | 机构保留惯性和约束，不直接校姿态 |
| 关闭/卸载 | joint/body 按顺序清理，无幽灵对象；关闭可等待完成 |

### 3.1 旧鞋链拓扑的纯数据用例（T0，历史证据）

> 本节记录 2026-10-23 之前完成的 79 鞋链审计。轮对运行段定稿后，这些用例只证明
> 旧拓扑的数据/几何自洽，**不再作为当前拓扑的完成证据**。对应测试保留，直到
> 新拓扑测试覆盖同等断言。

| 用例 | 断言 | 测试 |
|---|---|---|
| 数据/模型哈希 | 与审计记录的 SHA-256 一致 | `TrackedVehicleAuditTest` |
| 轮位 | 编译轮心 == 车辆 JSON 位置 − `Collision` OBB 原点，全部 16 轮 | 同上 |
| 轮角色 | 来自 `Role` 字段，不来自骨骼顺序；打乱列表不改变结果 | 同上 |
| 质量预算 | 车体 + 运行机构 == `MassKg`；支撑站质量 == 车体质量 | 同上 |
| 惯量 | 每个动态刚体有可追溯的盒体/圆柱惯量 | 同上 |
| 计数分离 | 物理链节 / 可见骨骼 / 模型骨骼三者分别记录 | 同上 |
| 横向位置 | 履带平面来自模板 pivot，不等于轮心均值 | 同上 |
| 闭链几何 | 闭合误差、销距误差、销弦间隙 ≤ 求解器限值 | 同上 |
| 实体间隙 | 鞋体盒与每个轮无穿透；销弦为正而鞋体相交的反例必须被发现 | 同上 / `TrackedGeometryAuditTest` |
| 核心规格 | `TrackedRunSpecs` 产出可校验的 `TrackRunSpec`，且复用审计放置 | `TrackedRunSpecTest` |
| 销点重合 | 相邻 link 的铰链销在世界坐标重合 | 同上 |
| 铰链限位 | 半角本身覆盖最大包绕角（不是双倍） | 同上 |
| 错误隔离 | 有 error 的定义不产出可用规格 | `TrackedVehicleAuditTest` |
| 字段校验 | 非有限值、负阻尼、重复骨骼、斜置轴均被拒绝 | `TrackedDefinitionValidationTest` |
| 模型解析 | 父骨骼缺失、骨骼环、非有限几何被拒绝 | `BedrockModelGeometryTest` |

以上只证明旧鞋链拓扑的静态几何与数据自洽。B1 已验证单站悬挂数值；
整车动态承载、运行段约束传力与长期漂移属于 B2 fixture，生产接线仍属 S2。

### 3.2 B1 已执行的悬挂用例（T0/T1，阈值已按基线冻结）

| 用例 | 断言 | 等级 | 测试 |
|---|---|---|---|
| 逐站静载 | `k·s = m_station·g`，静载落点可复现 | T0/T1 | `PhysicsSuspensionRowTest` / `NativeSuspensionRowTest` |
| 行质量守恒 | 每侧 6 行之和 == 该侧簧载质量；两侧之和 == 车体质量 | T0 | `TrackedSuspensionTest` |
| 行程限位 | 关节在上下限位处停止，行不能推过窗口 | T1 | `NativeSuspensionRowTest` |
| 自由滚动 | 绕轮轴自由旋转，另两轴锁定 | T1 | 同上 |
| 正交性 | 行程不引起滚动；轮轴与行程方向点积为 0 | T0/T1 | 同上 |
| 压缩阻尼 | 压缩阶段使用压缩阻尼率 | T0 | `PhysicsSuspensionRowTest` |
| 回弹阻尼 | 回弹阶段使用回弹阻尼率，不被零力钳掉 | T0/T1 | 同上 |
| 双向恢复 | 下垂 `F < 0` 把轮拉回 bind pose；压缩 `F > 0` 推开 | T0/T1 | 同上 |
| 对称力上限 | `MaxForce` 同时限制正负两个方向 | T0 | 同上 |
| 阻尼耗散 | 一个周期内耗散能量 > 0，且随回弹阻尼增大 | T0 | 同上 |
| 渐进缓冲块 | 仅在接近满压缩时介入并单调增长 | T0 | 同上 |
| 直接 `Spring` | 声明后行刚度等于 authored N/m；有效频率由 `k/m` 推导 | T0 | `TrackedSuspensionTest.anAuthoredSpringRateWinsOverTheNominalFrequency` |
| 直接 `Damper` | 单个 authored N·s/m 同时进入压缩与回弹方向 | T0 | `TrackedSuspensionTest.anAuthoredDamperReachesBothDirections` |
| 未声明直接参数 | 保留原频率/阻尼比模型，99A 数据不变 | T0 | `TrackedSuspensionTest.anUnauthoredDirectRateLeavesTheFrequencyModelIntact` |
| 缓冲块数据 | 改 `BumpStopStart` / `BumpStopExponent` 会到达核心行 | T0 | `TrackedSuspensionTest` |
| bind pose | 两端 constraint frame 重合，零行程即 authored 轮位 | T0 | 同上 |
| 不可达静载 | 静载下沉超出压缩行程时编译失败 | T0 | 同上 |
| 行独立性 | 改一个站的 LoadShare 只改变该站刚度，静载下沉不变 | T0 | 同上 |
| 逐轮覆盖 | 单轮 `Suspension` 覆盖只影响该站 | T0 | 同上 |

上表用例已在 2026-10-23 由用户确认的基线下冻结，见
[`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2。注意量测口径：

- 周期耗散以 `PhysicsSuspensionRowTest.dissipatedEnergyJ` 的积分为准；
  `NativeSuspensionRowTest` 比较的是峰值速度，只能作为原生对照。
- 这组阈值只适用于单站与单轮 fixture，不能作为整车或 B2 的通过依据。

### 3.3 B2 动态运行段用例（B2.0 T0；B2.1–B2.4 T1；B2.1 正式装配 T2，已在冻结阈值下通过）

详细 fixture、读数字段与通过条件见
[`B2_RUN_TEST_PLAN.md`](B2_RUN_TEST_PLAN.md)。阈值已冻结（`ACCEPTANCE_LIMITS.md` §2.3），
B2.3/B2.4 仍是带夹具边界的证据，**“通过”不等于整车静置通过**，详见下文备注。

| 用例 | 断言 | 等级 | 状态 |
|---|---|---|---|
| 规格来源 | fixture 只用 `TrackedRunSpecs.compileRuns` 产出的 spec，不手拼几何 | T0 | **已执行**：`TrackedRunSpecs` 增加已编译侧重载，各 fixture 统一走它 |
| 初始装配 | 端锚点距离与锁轴角误差在装配容差内 | T0 | **已执行**：`TrackedRunAssemblyProbe.requireMatches`，旧鞋链探针降为 legacy |
| 记录完整性 | 字段全必填；拒绝 NaN/自碰撞/站数不匹配/符号错误 | T0 | **已执行**：`B2RunObservationTest` 16 项按轮对运行段改写 |
| 拓扑声明 | 完整机构 = 显式轮序的全部运行段；`DIAGNOSTIC` 不得当证据 | T0 | **已执行**：两轮机制标为诊断拓扑 |
| 两轮两运行段 | 端 1 可转、端 2 单法向；无自发运动 | T1 | **已执行**：`TrackedTwoWheelRunNativeTest` 5 项 |
| 端 2 负向测试 | 三平移全锁时切向滑移测试必须失败 | T1 | **已执行**：端 2 全锁时 B2.1 + B2.2 共 10 项全部失败 |
| 单侧完整机构 | 8 轮端 + 8 运行段；端约束残差/漂移可量测 | T1 | **已执行**：`TrackedSingleSideRunNativeTest` 5 项（重力下，轮端 kinematic 隔离） |
| 内部碰撞过滤 | 同 owner 运行段互相不接触；与本车轮体/车体可接触 | T1 | **已执行**：窗口累计 `runRun`/`runWheel`/`runChassis` 计数 |
| 承载路径 | 载荷经过 地形→运行段→轮端约束/轮体→悬挂→车体；无旁路承载 | T1/T2 | **通过**：`TrackedChassisLoadPathNativeTest` 4 项（单侧滑轨夹具） |
| 窗口稳定 | 2400 子步窗口内无发散、锚点保持、冲量衰减 | T1 | **通过**：B2.4 跑到 2400 子步；**不证明静止**，静止需 S4 滚阻/制动（见备注） |
| 双侧互不串扰 | 左右运行段不互相接触；质量预算各自独立 | T1 | **通过**：`TrackedBothSidesRunNativeTest` 6 项，含逐侧承载与两侧平衡 |
| 外部冲量 | 机构保留惯性；不用姿态重置恢复 | T1/T2 | **已执行**：`Δv = J/m` 判定 + 横向速度衰减检查 |
| 拆卸与回滚 | joint 先于 body 逆序移除；无幽灵对象 | T1 | **已执行**：native 幂等拆卸 + T2 断言 native 对象离开 world |

**夹具边界备注（结论必须连同这些一起读）：**

- **B2.3** 是单侧夹具：一条履带无法平衡滚转，车体由竖直滑轨约束姿态。它只证明“重量
  经悬挂 → 轮端 → 运行段 → 地面”，不证明“车体被抬起”。旁路由**窗口累计接触计数**
  观测，轮/车体掩码允许接地，不靠掩码排除。
- **B2.4** 车体自由，但没有驱动/制动/滚阻（S4），2400 子步后仍沿 Z 蠕移约 18.5 m，
  因此**不能证明静止**，只能证明窗口内保持装配与承载。
- **T2** `TrackedRunRuntimeGameTests` 用完整单侧 8+8，chassis-local spec 平移到同一世界
  原点后再经 `TrackRunRuntime.create()` 创建，含真实轮端 mount 与全量拆卸断言。

B2 自身阈值已冻结（[`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2.3）；上表用例在
该组数值下通过，`trackRunInputAccept` 仍留待 S4。

### 3.4 S3.2 台阶辅助用例（候选实现：真实接触上的切向目标）

> 生产路径现为：真实 AABB 候选 → 真实 run-terrain 接触匹配 → 仅在
> `contactMotion1` 上写入受限切向目标。**不修改法线、接触点、穿透距离，不施加独立
> 向上力。** `stepAssistEnabled` 默认关闭，正向用例尚需实跑确认，**不得标为通过**。

| 用例 | 断言 | 等级 | 状态 |
|---|---|---|---|
| 关闭辅助的一格台阶 | 真实驱动不能把车抬上台阶 | T3 | **已执行**：`aOneBlockStepBlocksWhileAssistIsOff` |
| 分类：一格可辅助 | `assess()` 对真实 rise 返回 `stepAhead=true, refused=false` | T0 | **已执行**：`TrackedStepAssistTest` |
| 分类：两格墙拒绝 | 超过 `MAX_STEP_HEIGHT` 必须 `refused=true`，且 `contactAssist` 返回 null | T0 | **已执行** |
| 分类：平地/身后不误判 | 无台阶时返回 `Assessment.NONE` | T0 | **已执行** |
| 候选：两盒不合并且不越 `MaxRun` | 两格墙仍是两个盒；超过 `MaxRun` 的盒被丢弃 | T0 | **已执行** |
| 候选：真实接触匹配 | 接触点落在候选盒外时不匹配 | T0 | **已执行** |
| 切向目标在接触平面内 | `tangent·normal == 0`，且垂直分量有界 | T0 | **已执行** |
| 无驱动目标不辅助 | `baseTargetSpeedMps <= 0` 返回 null | T0 | **已执行** |
| 低于 `MinSpeed` 不辅助 | 2 m/s 返回 null | T0 | **已执行** |
| 采样：楼梯保留多 AABB | `VoxelShape.toAabbs()` 不被合并成整格 | T0 | **未单测**：`sampleTerrain()` 依赖 `Level` |
| 辅助开启：竖直一格台阶 | 当前策略**不得**爬越；竖面没有合法平面内方向 | T3 | **已执行（否定结论）**：`aVerticalStepIsNotAssistedByTheContactTangentPolicy`。旧“向上切向”回退等价于梯子，实测顶翻车辆（y=-104），已移除 |
| 辅助关闭：一格台阶不可跨越 | 真实驱动下停在竖面前 | T3 | **已执行**：`aOneBlockStepBlocksWhileAssistIsOff`（按是否越过竖面判定，不再只看高度） |
| 辅助开启：两格墙 | 被拒绝，车体停住，且不得逐层棘轮 | T3 | **已执行**：`aTwoBlockWallIsRefusedEvenWithAssistOn`（chassis 不与地形碰撞，逐 tick 采样，断言候选存在、拒绝发生、辅助写入为 0） |
| 辅助开启：坑/无接触 | 采样到真实地形但不生成辅助目标 | T3 | **已执行**：`aPitProducesNoStepAssist`（先采样再断言计数，坑底有实体地面） |
| 连续台阶 | 逐级辅助，不悬空、不重复承载 | T3 | **未实现** |
| 同一台阶面每子步只消费一次 | 宽台阶按面而非按列计费 | T0 | **已执行**：`StepFaceKey` + `faceKey()`；`aLedgeMadeOfSeveralColumnsSharesOneFaceKey` |
| 连续立面防棘轮 | 爬上一层两格墙后，上层不得被当成一格 | T0 | **已执行**：`stackedOnSameFootprint`；`aContinuousFaceIsRefusedEvenAfterClimbingItsFirstBlock` |
| 竖直面不伪造方向 | 前进投影为零时不写摩擦方向 | T0 | **已执行**：`TrackedContactDriveTest` 的 `contactFrame` 用例 |
| 摩擦基正交 | 写入的 dir1/dir2 均为单位切向且互相垂直 | T0/T3 | **已执行**：`aSlopeProducesTwoUnitRows`、竖面用例 |
| 悬挂与履带观测 | 辅助期间记录真实 travel、法向载荷和车顶速度 | T3 | **部分**：正向用例断言真实法向冲量与切向平面 |

### 3.5 S4 MCSP 传动响应用例（2026-10-26）

| 用例 | 断言 | 等级 | 状态 |
|---|---|---|---|
| `slew` 加速 | 3 m/s²、0.1 s 从 0 到 0.3 m/s；目标更近时落在目标上 | T0 | **已执行**：`TrackedDriveControllerTest` |
| `slew` 反向先制动 | 1 m/s 正向、目标 -10，0.1 s 后仍为 +0.4 m/s，不穿零 | T0 | **已执行** |
| `slew` 用制动率减速 | 10→5 的目标更接近零，0.1 s 后为 9.4 m/s | T0 | **已执行** |
| 转向杠杆时间 | `SteerTime=1.5`、15×0.1 s 达到满舵 | T0 | **已执行** |
| `PivotSpeed` 目标 | 0.2×15.6±1.8 = 4.92 / 1.32 m/s | T0 | **已执行** |
| `PivotSpeed` 超速整体缩放 | 峰值超过 `MaxSpeed` 时按同一比例缩放两侧 | T0 | **已执行** |
| 无响应字段保持旧行为 | 未声明 `Accel`/`Brake`/`PivotSpeed` 时仍给即时目标 | T0 | **已执行** |
| 滑移判定 | 表面速度高于 craft 0.5 m/s 才滑转；反向用目标符号 | T0 | **已执行** |
| 牵引比例 | `|traction|/(μN)` 截断到 0..1；无载荷不降级 | T0 | **已执行** |
| 真实接触下响应层 | 声明字段后仍由履带接触传力，车体不被直接推 | T3 | **未执行** |
| `TractionLimit` 整车 | 只在滑转时收紧，正常加速不误判 | T3 | **未执行**（默认关闭） |
| 自由滚动损耗 | `RollingLoss` 使松油门后速度衰减 | T0 | **已执行**：`TrackedContactDriveTest`（`coastTarget`） |
| 自由滚动损耗整车 | — | T3 | **未实现**：两种速度目标写法都实测注入能量（重刹 / 松油门后加速 18%）。COAST 现只清除传送带目标；`coastRemovesTheConveyorTargetInsteadOfDriving` 断言不得保留驱动目标、不得像驱动一样持续加速 |

**注意：** 上表 T0 只证明纯控制律。它们不证明履带牵引、不证明滑移验收带，也不等于 S4 通过。

## 4. 渲染矩阵

| 用例 | 期望 | 等级 |
|---|---|---|
| 两轮包络 | 圆弧和切线闭合 | T0 |
| 多轮包络 | inside/outside wrap 正确 | T0 |
| 悬挂变化 | 路径连续，无跳变 | T0 |
| 相位前进/倒车 | 方向正确，环绕无跳变 | T0 |
| 左右独立相位 | 差速转向时方向正确 | T0/T4 |
| 物理/可见节数不同 | 两者独立配置 | T0 |
| Bedrock 模型 | 链节模板和骨骼绑定正确 | T4 |
| Geckolib 模型 | 同一路径结果 | T4 |
| 网络延迟 | 插值平滑，不写回物理 | T4 |
| 长时间行驶 | 无漂移、分离或穿模 | T4/T5 |

## 5. 通用核心（T1/T2，不含车型）

| 用例 | 期望 |
|---|---|
| 默认重力 | link 不调用 `setProtectGravity`，受世界重力影响 |
| 显式重力 | 只有 `explicitGravity` 非空时才保护并设置 |
| 逐子步快照 | 每个物理子步后 revision 递增，数据来自求解之后 |
| 快照不可变 | 列表和旋转读取不可改写已发布快照 |
| 同 owner 碰撞 | link↔link 过滤，link↔同车轮/车体碰撞 |
| 输入上限 | 单次力/力矩冲量被钳制到 spec 预算 |
| 输入语义 | 同步返回值为「已接纳」大小；`*Async` 的 completion 在物理线程执行后完成 |
| 拓扑校验 | 正反向重复对、自连、断连、度>2 均拒绝 |
| 圆柱轴向 | X/Y/Z 三轴包围盒正确 |
| 生命周期 | 重复关闭、创建中关闭、Level 卸载均无幽灵对象 |
| 创建完成契约 | `create()` 完成时首帧已存在；关闭取消的 create 失败；关闭后不复活 |
| 公共任务入口 | `PhysicsTasks.call/run` 与 `submitTask` 在关闭竞态中不挂起、不丢弃 |
| 任务接纳竞态 | 提交与关闭并发时 Future 必有终态；关闭后提交立即失败 |
| 启动/关闭竞态 | 关闭抢先后 Level 不得回到 `RUNNING`，不得再接纳任务 |
| STARTING 接纳 | 物理线程启动完成前提交的任务排队执行，不被丢弃 |
| FAILED teardown | 失败后 joint/body/world 注册表与 native world 均被清理 |
| native 清理失败 | shutdown Future 异常完成，不伪装成 STOPPED 成功 |
| 装配回滚 | 第 N 个 body/joint 失败时逆序回滚，joint 先于 body |
| 回滚残留 | 回滚失败的 native 对象保留所有权，close 重试或以异常结束 |
| 清理错误抑制 | 清理异常作为 suppressed 附加，主错误不被覆盖 |
| joint frame | 非 identity 与镜像 frame 下闭链闭合，误差按 frame 测量 |
| 关节坐标 | 快照 angleX/Y/Z 来自 native `getAngles`；未采样时不报 0 |
| 关节角误差 | 由 native 约束坐标判定，不使用轴角投影 |
| 关节载荷 | 核心不提供载荷字段；`appliedImpulse` 不得作为载荷来源 |
| run 身份 | 同名不同 runId 的快照不得插值混合 |
| 不连续点 | reset/teleport 的 poseEpoch 与世界重建的 epoch 不插值 |
| body 身份 | `bodiesById` 按稳定 bodyId 对齐；集合变化时直接采用新帧 |
| 插值视图一致 | 整帧插值后 `bodies` 与 `bodiesById` 不矛盾 |
| 关节采样插值 | `joints` 与 `linkBodyIds` 原样保留，不伪造中间采样 |
| BodyFrame 不可变 | 旋转读取返回副本；离散字段不混合 |
| 快照集合不可变 | 构造后修改来源 Map/List 不影响已发布帧 |
| 数值校验 | 超出 Float 范围的 Double 被拒绝；大数四元数安全归一化 |
| 外部 link 访问 | `withLinkBodies` 在物理线程提供 body，id 与快照一致 |
| 静态体速度 | 地形与零质量 link 读取速度不得触发 Libbulletjme 断言 |

## 6. 99A 场景

1. 平地放置并静置至少 600 个物理子步。
2. 前进、倒车、释放油门、制动。
3. 原地左转、原地右转、行进左转、行进右转。
4. AABB 阶梯坡上下行（由真实方块逐级构成；光滑连续斜坡不在范围内）。
5. 一格高差、连续三阶楼梯、两格墙拒绝。
6. 一侧挖空、两侧挖空、跨越局部洞。
7. 区块加载/卸载、地形 revision 更新、外部冲量。
8. 车辆移除、重载、Level 卸载和重新放置。

记录：

- 车体、轮体、履带板数量。
- 物理 link 数、可见链节数和模型骨骼数。
- 每个接触的来源和法向载荷。
- 悬挂行程、驱动力矩、目标/实际带速和滑移。
- 物理子步耗时和快照延迟。

## 7. 实机暂停点

### A：静态机构

- 轮位、半径和高度是否符合数据。
- 履带板是否落在轮包络外。
- 闭合是否完整，有无开口、穿轮或侧向漂移。
- 静置时车体、负重轮和履带板是否承载合理。

### B：操控

- 左右差速方向。
- 加速和倒车是否突跳。
- 刹车和释放油门是否抽搐。
- 原地转向是否围绕合理瞬时中心。

### C：地形

- 普通方块碰撞是否与可见地形一致。
- 单阶、楼梯、AABB 阶梯坡、两格墙和洞的边界。光滑连续斜坡不属于 AABB 范围，不在此门。
- 台阶辅助是否只处理允许的低台阶，且没有重复承载；辅助开启后的真实爬越必须单独验收。
- 没有旧探针、高度场、固定楔面或人工抬升支撑。

### D：渲染、长期和性能

- 可见履带路径与轮组一致。
- 前进、倒车、原地转向和单侧压坡时链节连续。
- 单车长时间驾驶和 4 台活动车。
- 区块边界、休眠唤醒、地形更新、网络延迟和资源占用。

## 8. 失败记录格式

失败不得只写“卡住/抖动”。至少记录：

- 车型。
- 数据/模型哈希。
- seed。
- 位置和场景。
- 地形 revision。
- 接触来源、接触点、法线、载荷。
- 板/轮数量、物理节数、可见节数。
- 质量、悬挂力、驱动力和滑移。
- 车体速度/角速度。
- 耗时。
- 最小复现步骤。
