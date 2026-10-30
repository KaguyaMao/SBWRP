# MCSP 轮对运行段拓扑与 SBW 实施修正指南

- 日期：2026-10-23
- 状态：**修正指南，不是实现、不是验收通过**
- SBW 基线：`64ed9d6a1ac86ef4b0855d6a45bcd0ecf4213372`
- Core 基线：`e90984a4a8102f72a305e563fff9cacfb3ec48ff`
- 适用车型：`superbwarfare:ztz_99a`
- 相关文档：[`../physics/IMPLEMENTATION_PLAN.md`](../physics/IMPLEMENTATION_PLAN.md)、
  [`../physics/CONSTRAINTS.md`](../physics/CONSTRAINTS.md)、
  [`../physics/B2_RUN_TEST_PLAN.md`](../physics/B2_RUN_TEST_PLAN.md)

> 本文把“已恢复的 MCSP 行为”与“SBW 当前实现”分开陈述。任何“应改为”都只是建议，
> 需要按本文 §8 的门控逐项批准后执行。本文不修改任何既有验收成绩。

---

## 0. 结论摘要

### 0.1 一句话结论

**当前 SBW 的 79 鞋体 / 79 铰链闭链是一个独立拓扑，不是 MCSP 的等价复现；应改为
“相邻轮对动态运行段 + 混合约束 + 接触面运动驱动”，并相应重开 S1 拓扑编译与 B2 验证。
Core 的生命周期、快照、插值和双向悬挂原语可以保留。**

### 0.2 三档处理

| 档位 | 内容 |
|---|---|
| **保留（不改）** | Core 任务接纳/事务回滚/稳定身份/逐子步快照/插值；`PhysicsSuspensionRow` 双向带符号力与对称绝对力上限；SBW 四个权威轮组与悬挂定义、模型解析、编译错误隔离、盒体/圆柱惯量公式 |
| **修正（改）** | S1 拓扑编译产物、`TrackedRunSpecs`、质量预算、B2 观测契约、承载判据、渲染计数与相位、同步快照、阶段门控与相关文档语段 |
| **待证明（先原型）** | Bullet 求解前接触修改时序、双切向摩擦、持久接触刷新、整侧硬扭矩/功率限额、轮端稳定安装框架 |

### 0.3 优先级

| 级别 | 含义 | 条目 |
|---|---|---|
| **P0** | 不修就无法称为复现 MCSP | 拓扑、约束语义、驱动通路、承载判据 |
| **P1** | 会造成错误证据或错误接线 | B2 探针、渲染计数、同步契约、质量重复计 |
| **P2** | 文档与记录一致性 | 语段、术语、阶段表、账本 |

---

## 1. 已恢复的证据基线

### 1.1 native 目标

- quistaphys `1.0.0-beta.7`，ABI `39`
- SHA-256 `2ce7f123b6c9a2d0dcc29769363742e93043ce54b7e4d92c3cf5b287aa66a2ea`
- BuildID `446707ee9022949d2bcbe32bb57547dc92310296`
- 关键地址：`createBelt0=0x2820b0`、`insert_run=0x5c980`、`belt_sense=0x2e6090`、
  `belt_run=0x2e6190`、`Scene::step=0x64fd0`、`modify_solver_contacts=0x2a0460`、
  `readDriveTraction0=0x28ecd0`、`set_joint_motor=0x82740`
- 坡面/台阶：`update_slopes=0x7b550`、`slope_under=0x2a7520`、
  `setBridgesSteps0=0x298080`

### 1.2 拓扑与约束

| 事实 | 证据 |
|---|---|
| 每个相邻轮对生成一个动态 Cuboid 运行段，**包含最后一轮到第一轮** | `PhysGear.rebuildBelts` 按 `belt.wheels()` 全序传 carrier；`createBelt0` 循环调用 `insert_run`；两轮实验读回 2 个 run |
| 第一端是轮心处的多体转动副（mask `0x37`） | `slope-belts-contacts.asm` 约 7194 行 |
| 第二端是 impulse joint（mask `0x04`），只锁一个法向平移，允许切向滑动 | 同上约 7491、7502 行 |
| 单 run 局部盒形在创建/恢复时确定；轮位扰动后不变 | 抬升 carrier 0.75 m、120 子步后 collider 地址与半长不变，世界姿态改变 |
| 等轮距 4 m、半径 0.5 m、depth 0.1 m 时半长 `2.05 = 2.0 + 0.05` | `readBelt0` 实测 |
| 运行段中心线落在相邻轮外公切线位置（外包绕、无下垂） | Java `PhysLoop` 与 native run 对照，差异在浮点量级 |
| 混合 inside/outside 时 Java 路径与 native 段出现可测差异 | 同上对照，未完全一致 |

**边界：** `linkPitch`、`sag`、`driveSlot`、`lateralFriction` 不在 `createBelt0` 入参中；
但 `driveSlot` 与 `lateralFriction` 会经 carrier 元数据（`PartResponse.withDrive`）**间接**
影响 native 行为。因此不能写成“它们与物理无关”。

### 1.3 驱动与牵引

- 驱动通过**求解前接触面运动**生效，不要求轮体真实自转。
- 修正后的对照 fixture（1000 kg 车体、2×10 kg 轮、2×5 kg run，`1/60 s`）：
  - 零目标速度驱动把 3 m/s 运动停住；
  - `free(0)` 保留 `2.979319 m/s`；`free(0.05)` 保留 `2.508371 m/s`；
  - `drive +3 / -3 m/s` 使车体得到 `+3.000044 / -2.999795 m/s`。
- 牵引读数 = 累计冲量 ÷ 时间步（`0x746c9 / 0x746d7`），单位为 N，世界空间切向。
- 持续滑移 `+100 m/s`，驱动摩擦 `0 / 0.2 / 0.8` → 牵引 `.z = 0 / 2020.164 / 8080.859 N`。
- 结论：**drive / brake / coast 必须是三个不同状态**；“目标速度 0 的驱动”不等于自由滑行。

### 1.4 质量

- MCSP 被 belt 覆盖的轮保留 `0.6 × m`；每段 run 取 `0.2 × (m_i + m_next)`。
- 该预算在 MCSP 内部自洽，但**不能叠加在 SBW 已有的每鞋 18 kg 之上**。
- 99A 现预算：总 55 000 kg，车体 39 756 kg，运行机构 15 244 kg（7 622/侧）；
  其中轮 6 200/侧（6×900 + 2×400），鞋 79×18 = 1 422/侧。
- 若按 MCSP 公式（8 轮/侧）：run 总质量 = `0.4 × 6200 = 2 480 kg/侧`。
  与现有 1 422 kg/侧 相差 **+1 058 kg/侧**，必须显式决定去向（见 §6）。

### 1.5 渲染

| 事实 | 证据 |
|---|---|
| 可见链节数在初始化时固定为 `max(1, ceil(L_rest / linkPitch - 0.001))` | `PhysLoop.linkCount` |
| 当前间距 = `L_current / N`，不是每帧重算 N | `PlatformGearDraw.build` 的 `restPitch` |
| `pose()` 用**同步后的关节角**重算轮心，再重建路径 | `PlatformGearDraw.pose` 288–317 |
| 每帧 `solve()` 以 `contactRadius - thickness` 重建 `PhysLoop` | `PlatformGearDraw.solve` 326–340 |
| `sag` 无法闭合时回退到无下垂 | 同上 159–168、319–324 |
| `advance()` 用 `driveSlot` 的带速 / `restPitch` 推进相位 | 同上 259–273 |
| 渲染器**不读取** native run 的逐段 pose | `PlatformGearRenderer`/`PlatformGearDraw` 无 `readBelt` 调用 |
| 模型尺寸参与物理规格：轮半径 + 厚度作为 contactRadius；belt 宽度/横向中心来自链节模板包围盒 | `PlatformGearSpec.toPhys` 289–347 |
| 路径长度变化超过 3% 时拒绝配置 | `PlatformGearSpec.checkStretch` 432–453 |

**结论：** 物理与渲染“共享定义、几何参数和权威机构状态，分别构建碰撞与可见表示”。
不是完全解耦，也不是同一批对象。

### 1.6 台阶辅助

已确认的 Java 入口：`PhysRamps` 提供 `minStep/maxStep/maxClimb/minRun/maxRun/minSpeed/fullSpeed`；
`PhysGearSpec.bridgesSteps()/ramps()` 是车型级开关；`PhysGear.hold()/bridgeSteps()` 把车体与
运行机构登记到辅助路径；`PhysScene.setBridgesSteps()`、`readSlopeState()`、`seedSlope()`
进入 native；`PhysGear.readState()` 回读坡面状态。

已确认的 native 调用关系：`Scene::step → update_slopes`，接触钩子
`modify_solver_contacts → slope_normal_y`。因此辅助是**物理步内的接触响应通路**，
不是可见路径生成器，也不是渲染侧抬高。

**不能确认：** 反汇编里能看到 Parry `HeightField` 的释放符号，但无法证明 MCSP 为每台车
生成了哪种局部坡面几何、覆盖范围、过滤规则、接触时序或是否与基础地形并行承载。所以
“局部 HeightField 碰撞几何”只是未经验证的推断，不能写成已恢复设计。

它**不授权** SBW 恢复本项目已撤销的全局高度场、探针承载或人工抬升；也不意味着可以
直接照搬 Rapier 钩子或 MCSP 车型参数。SBW 的 Bullet 等价方案仍需独立验证。

### 1.7 此前猜想逐项判定

| 猜想 | 判定 |
|---|---|
| 每个相邻轮对一个物理运行段，含 last→first | **正确** |
| `LinkPitch` 只决定可见重复 | **正确** |
| 物理段是 Cuboid 而非物理鞋体 | **正确**（但与可见直线段有共同几何基础） |
| 第一端 revolute、第二端只锁法向 | **正确** |
| 第二端允许切向滑动 | **正确** |
| 物理与渲染“完全分离” | **表述错误**；对象/职责分离，几何与状态耦合 |
| 模型几何可参与物理规格 | **正确**（初始化编译，不是运行时反写） |
| 地形直接重建履带形状 | **不支持**；只经轮/悬挂/车体姿态间接影响 |
| `sag`/公切线/包络同时约束两者 | **部分正确**；包络共享，`sag` 只属可见路径 |

---

## 2. 必须修正的硬约束（文档级）

> 逐条给出“文件 → 语段 → 问题 → 修正”。改文档前先追加 superseding 事件。

| 文件 | 位置 | 问题 | 修正 |
|---|---|---|---|
| `docs/physics/CONSTRAINTS.md` | §1.3 第 6–7 条 | 把“物理节距/闭合误差/销轴位置”写成所有运行段的必备属性 | 改为：运行段由“相邻轮对 + 混合约束”描述；销链是可选拓扑，不是定义性要求 |
| 同上 | §1.3 第 7 条 | “可见链节不与物理运行段一一绑定”仍正确，但缺“共享包络输入” | 补：物理与可见共享轮序、wrap、半径、厚度和权威机构状态 |
| 同上 | §1.4 标题与 1–5 条 | “物理与渲染分离”过于绝对 | 改为“共享定义与状态，分别构建表示”；保留“渲染不写回物理” |
| 同上 | §1.6 第 4 条 | “物理运行段与渲染链节分离是已确认架构事实” | 补“几何同源、表示不同、消费路径不同” |
| 同上 | §2 术语表 | “物理节距”“可见节距”隐含鞋链 | 增加“运行段（run）”“轮端绑定”“包络输入” |
| `docs/physics/ARCHITECTURE.md` | §3 承载路径 | 只写“地形→履带板→销链→轮→悬挂→车体” | 改为“地形→运行段/接触→轮端约束与轮体→悬挂→车体”，并注明约束传力合法 |
| 同上 | §3 驱动路径 | 以“轮带耦合/主动轮”为中心 | 改为“接触面运动 + 有界驱动策略”，轮自转不是必要条件 |
| 同上 | §4.1 | “link 之间由销链/铰链约束连接” | 改为“轮对运行段，两端约束类型不同” |
| 同上 | §4.2/§4.3 | 渲染只看轮组；未说明可见计数固定 | 补“计数初始化固定、间距随长度变化” |
| 同上 | §6 表后 | 表格外游离行 `| KaguyaPyCore 通用 voxel/AABB 地形 | 保留 |` | 并入表内 |
| 同上 | §7 末句 | “把履带视觉从物理链路中彻底分离” | 改为“可见表示不参与求解，但共享包络输入” |
| `docs/physics/IMPLEMENTATION_PLAN.md` | 头部状态 | 只写 B2 已授权，未写拓扑需重开 | 增加“S1 拓扑部分待复核” |
| 同上 | §7 B2.1–B2.4 | 以 79 鞋链为唯一验收路径 | 改为混合约束小机构→完整单侧轮对机构→真实承载→双侧扰动 |
| 同上 | §7 退出条件 | 要求“链节-轮体接触”覆盖 | 改为“整机构外力平衡 + 轮端约束残差 + 接触/穿透” |
| 同上 | §8 S1 完成情况 | 旧 79 节成绩仍标为当前完成 | 标注“旧鞋链拓扑审计，已被新拓扑要求取代（保留历史）” |
| 同上 | §17 阶段总表 | B2/S2 描述与新拓扑不符 | 同步改写；S1 状态改为“部分重开” |
| `docs/physics/B2_RUN_TEST_PLAN.md` | §2 输入表 | 要求 link 形状/质量/放置来自 `CompiledLinkPlacement` | 改为 run 盒形、两端绑定、质量与 frame |
| 同上 | §3 B2.0/B2.2 | `FULL == 79` | 改为“完整已审计拓扑”；密度只作为诊断标签 |
| 同上 | §3 B2.3 | 只认可“轮板接触”承载 | 增加约束传力与端轮分担；改为整机构外力平衡判据 |
| 同上 | §5 通过条件 | 依赖旧销距指标 | 改为轮端锚点残差、法向残差、锁轴偏差 |
| `docs/physics/MCSP_BULLET_MAPPING.md` | §2.3 native 边界 | “链节数量、形状、铰链类型无法恢复”已过时 | 更新为已恢复的轮对 Cuboid 与两类约束，并给出证据地址 |
| 同上 | §2.3 字段表 | 用“不在 createBelt 入参”推导“不影响物理” | 补 carrier 元数据间接通路 |
| 同上 | §2.4 | 缺“可见计数固定、间距浮动、sag 回退” | 补齐 |
| 同上 | §3.1 映射表 | 把 wrap 标为“真实缺口” | wrap 已是 `createBelt` 入参，改为“SBW 未声明” |
| `docs/physics/TRACK_RENDERING.md` | §2 来源表 | 可见节距“未确定”过于绝对 | 改为“模板纵向长度可作候选来源，规则待确认” |
| 同上 | §4 | `visibleLinks = round(pathLength / visiblePitch)` | 改为初始化固定 `ceil(L_rest/pitch - 0.001)`，当前间距 `L/N` |
| 同上 | §5 | 相位来源未区分带速与车体速度 | 明确带速/相位单位与 drive/brake/coast |
| 同上 | §9 退出条件 | “物理履带承载与渲染完全分离” | 改为“可见表示不参与求解，且共享包络输入” |
| `docs/physics/ACCEPTANCE_LIMITS.md` | §2.2 指标表 | `pinClosureError`/`trackRunDrift` 以鞋链为中心 | 拆为轮端锚点残差、法向残差、锁轴偏差、机构漂移 |
| 同上 | §2 `dampingAssertion`（能量） | 声称 `0.02×` 基线，T0 实际为 `0.25×` | 补足对应测试，或经确认修订声明（不得默认放宽） |
| 同上 | §4 接触所有权 | `TERRAIN_AABB` 与 `TRACK_RUN` 被写成互斥来源 | 改为：前者是环境来源，后者是参与对象；禁止的是同一支撑面被辅助几何重复承载 |
| `docs/physics/TEST_MATRIX.md` | §3 机构矩阵 | 以销链/闭链为主 | 增加轮对拓扑、第二端切向自由、轮位扰动、无地形对照 |
| 同上 | §3.1/§3.3 | 旧 S1/B2 用例仍标完成 | 分离“旧鞋链证据”与“新拓扑待验” |
| 同上 | §4 渲染矩阵 | 缺计数固定与相位单位 | 增加两条 T0 |
| `docs/physics/STATUS.md` | 头部/§2/§5 | SBW 基线写 `1b5c984` | 改为 `64ed9d6`，并注明 B2.0 提交 |
| 同上 | §3/§4 | 把 79 节稳定性列为主要阻塞 | 改为拓扑与接触能力待验证 |
| 同上 | §6 S1 数值表 | 79 节成绩 | 标注“旧拓扑审计” |
| `docs/physics/README.md` | §2 一句话定义 | “动态物理运行段”未定义粒度 | 补“轮对运行段” |
| 同上 | §5 证据基线 | 72 项写成 53 项 | 统一为 72 |
| `docs/physics/CURRENT_WIRING.md` | §1 表 | 缺“新拓扑未实现” | 增加行 |
| `docs/physics/DECISIONS.md` | 表 | 缺本轮决策 | 追加 superseding 行（拓扑、驱动、渲染耦合、质量） |
| `docs/physics/REPOSITORY_BOUNDARIES.md` | §1/§3 | “文档可入库”与“docs 不入代码提交”冲突 | 明确：文档可入库，但代码提交不含 docs |
| `SuperbWarfare/.opencode/skills/*` | `tracked-mechanism`、`tracked-rendering`、`vehicle-acceptance`、`mcsp-bullet-tracked-vehicle` | 鞋链必需性、固定参考板类比、`round` 计数、旧阶段顺序 | 同步修正 |
| `KaguyaPyCore/.opencode/skills/track-run-api` | 硬边界 | 把 TrackRun 写成 MCSP 等价拓扑 | 改为“可组合刚体/鞋链能力” |
| `KaguyaPyCore:docs/TRACK_RUN_API.md` | §2.1/§3/§4 | 同上；且“轮板碰撞才是承载” | 修正语义，增加外部约束所有权 |
| `KaguyaPyCore:docs/TRACKED_VEHICLE_SUPPORT.md` | §3 | 同上 | 同上 |
| `SuperbWarfare:docs/physics/CORE_API_CONTRACT.md` | §4.1/§4.2 | 同上 | 同上 |

---

## 3. 代码与接口修正（SBW）

路径均相对 `SuperbWarfare/src/main/kotlin/com/atsuishio/superbwarfare/physics/`。

### 3.1 数据与编译

| 文件/符号 | 现状 | 修正 |
|---|---|---|
| `tracked/TrackedDefinitions.kt` `TrackMechanismDefinition` | `Pitch/PinInset/AngularLimitDegrees/LinkCount/Clearance/LinkMass` | **保留字段**但不得静默改义；新增运行段厚度、可见采样、质量预算字段 |
| 同上 `TrackSideDefinition` | 只有 `LinkTemplateBone`/`VisibleBonePrefix`/`Suspension`/`Wheels` | 新增**显式有序** belt member 引用与逐成员 wrap；不得用 Z 排序兼任遍历 |
| 同上 `TrackWheelDefinition` | 已有 `Role/LoadShare/Suspension/Tensioner/Mass/Radius/HalfWidth/AxleDirection` | **保留**；新增“是否 belt member”“安装体类型”声明 |
| `tracked/TrackedDefinitionValidator.kt` `validate()` | 校验 pitch/linkCount/linkMass 等旧字段 | 改为校验成员引用完整性、闭合遍历、质量为正、wrap 合法；旧字段校验降级为“仅旧拓扑路径” |
| `tracked/TrackedVehicleCompiler.kt` `compileSide()` 235–309 | 按 `bindCenter.z` 降序排序后建圆、解闭链 | 排序只用于稳定 ID；新增 `compileRuns()` 由显式循环生成相邻轮对 |
| 同上 289–309 | 依赖固定节距闭链与张紧器偏移闭合 | 新路径不要求闭链；张紧器成为可选自由度 |
| 同上 319–357 | `linkMass * linkCount` 与 `sprungPerSide` | 改为 run 质量 + 轮质量 + 车体质量的显式预算（§6） |
| 同上 536–635 `compileLinkTemplate()` | 要求单 cube、无 rotation/inflate，否则报错 | 新拓扑下模板只提供**有来源的尺寸**；不因渲染模板复杂度阻断物理 |
| 同上 637–665 `compileRenderTemplate()` | 因骨骼共 pivot 而恒为 `null` | 允许由模板纵向长度派生候选可见节距，记录来源与规则版本 |
| `tracked/TrackedSpec.kt` `CompiledTrackSide` | 必填 `loop/placements/linkMassKg` | 改为 `runs/attachments/beltMembers`；旧字段移入 `LegacyShoeChainAudit` |
| 同上 `TrackedVehicleSpec` | `pitch/pinInset/clearance/linkMassKg/angularLimitDegrees` 为顶层 | 移入拓扑子对象；保留 `mass/render/audit` |
| `tracked/ClosedTrackLoopSolver.kt` | 固定节距闭链求解 | **保留为旧拓扑能力**，退出新编译主路径；不得删除历史证据 |
| `tracked/TrackedAssemblyGeometry.kt` | 销弦放置与鞋/轮截面距离 | 同上；新增轮对几何（外公切线、法向、端部延长） |
| `tracked/TrackedGeometryAudit.kt` | 鞋体-轮间隙 | 新增 run-轮、run-地形、run-run 的装配审计 |

### 3.2 规格适配

`tracked/TrackedRunSpecs.kt`：

- `compile(spec, side): TrackRunSpec` → 改为 `compileRuns(spec, side): List<TrackRunSpec>`，
  每个 run 一个单 link、无内部 joint 的规格。
- 新增 `CompiledRunAttachment` 描述两端：
  - 第一端：锚点（轮心）、局部 frame、锁三平移、放行轮轴转动；
  - 第二端：锚点、法向轴、**只锁该法向平移**，其余平移与转动按恢复语义放行。
- `wrapFits()` 改为按最大包绕角与运行段端部几何判断，不再依赖销链。
- 删除 `require(placements.size >= 4)` 这类鞋链前置条件。

**约束语义映射（不要照抄 Rapier mask）：**

| 端 | MCSP | Bullet 建议 |
|---|---|---|
| 第一端 | 多体转动副（锁 3 平移 + 2 角） | `New6Dof` 锁 LIN_X/Y/Z，锁 ANG_Y/Z，放行 ANG_X |
| 第二端 | 只锁一个法向平移 | 锁该法向对应线性轴；其余线性轴给足够窗口或自由；角自由度按“不传递弯矩”处理并测试 |

> 第二端若在 Bullet 里被做成“三平移全锁”，会退化成两个固定端，直接违背已恢复行为。

### 3.3 质量与惯量

- 每个 run 单独计算盒体惯量（`TrackedMassProperties.box`）。
- 轮惯量沿用 `cylinder`，但被 belt 覆盖的轮是否按 `0.6m` 折算必须显式声明。
- `TrackedVehicleCompiler.compile()` 的质量闭合检查改为：
  `车体 + Σ轮 + Σrun + 炮塔/炮管拆分 = MassKg`，并输出每项来源。
- `VehicleBody` 的 `chassisMass` 目前 `= mass - wheelMassTotal - articulationMassTotal`，
  且 tracked 分支 `wheelMassTotal = 0`。接线后必须**显式传入**已扣除运行机构的车体质量，
  否则 run/轮质量会被重复计入。

### 3.4 B2 观测契约

`tracked/B2RunObservation.kt`：

| 符号 | 问题 | 修正 |
|---|---|---|
| `B2AssemblyProbe.requireMatches()` 104–126 | 只比较数量、质量、盒体、位置 | 增加旋转、两端身份、连接关系、局部 frame、限位、碰撞过滤、重力设置 |
| `measure()` 137–174 | `hingeAxis()` 硬编码刚体 X 轴 | 用 `bodyRotation × frameRotation` 计算锁定轴 |
| `pinPoint()` 183–189 | 只适用于鞋链销点 | 改为通用“局部点 → 世界点”，按端类型取锚点 |
| `B2RunObservation` 213–245 | 无第二端切向滑移、无锚点/法向残差分离 | 增加 `endpointAnchorErrorM`、`normalResidualM`、`tangentialSlipM` |
| `problems()` 261–291 | 无拓扑 revision 校验 | 增加 `topologyRevision` 与成员覆盖 |
| `B2Sampling` | `FULL` 语义绑定 79 | 改为“完整已审计拓扑” |
| 反射测试 351–360 | 禁止任何含 `impulse` 字段 | 收窄为“禁止伪造关节载荷”，允许真实接触冲量 |

### 3.5 悬挂与轮端

`tracked/TrackedSuspension.kt`：保留 `CompiledSuspensionStation`、`toRowSpec()`、
双向恢复校验；仅当质量或施力点变化时重验。

Core `PhysicsJoints.guidedWheelAxle()`：

- 不要删除该通用自由滚动原语。
- **但不要把会自转的轮 body frame 直接当作运行段安装框架**，否则 run 会随轮公转。
- 两种可选方案（需原型验证后择一）：
  1. 引入**非自转安装体**（carrier），轮只作滚动子体；
  2. 安装框架与滚动部件分离，run 绑到安装框架。
- 两者都不得把 MCSP 的 `carrier/hub/knuckle/spin` 变成 SBW 资源字段。

### 3.6 Core 组合方式

**首选：复用现有能力，不新增通用装配 API。**

- 每个 run 用**单 link、无内部 joint** 的 `TrackRunSpec`；
- 通过 `TrackRunRuntime.withLinkBodies()` 在物理线程建立外部约束；
- 外部 joint 由 SBW 记账，关闭前先移除；
- 只有原型证明事务/所有权无法可靠组合时，才新增通用 assembly API。

理由：`TrackRunRuntime.create()` 的事务回滚、`poseEpoch`、逐子步快照已满足需求；
贸然新增 API 会把车型拓扑写进核心。

### 3.7 接触驱动（P0，先原型）

Core `PhysicsWorld`/`ManifoldPoint`/`ContactSnapshot`：

- 现有 `onContactProcessed` 的**时序未证明**可用于求解前修改。
- Libbulletjme 22.0.3 暴露 `setContactMotion1/2`、`setCombinedFriction`、
  `setLateralFrictionDir1/2`、`getLifeTime`，但“有 setter”不等于链路可用。
- `PhysicsWorld` 当前未启用 `SolverMode.Use2Directions`，双切向摩擦行为未验证。
- 原型必须回答：
  1. 修改是否发生在求解前；
  2. 持久接触是否每步刷新；
  3. 纵向/侧向是否独立响应；
  4. 同一接触是否会被两侧回调重复累计。
- 通过后才设计通用接触面运动接口；**车辆 slot、drive/brake/coast 策略留在 SBW**。

### 3.8 驱动与牵引

新增车辆侧模块（建议 `tracked/TrackedDrive.kt`、`tracked/TrackedTraction.kt`）：

- 三态显式：`DRIVE`、`BRAKE`、`COAST`。
- 牵引输出：世界空间切向力 `J/Δt`，单位 N。
- 归属：按**实际源安装体/slot**，不平分给两轮，不自动记到 hull。
- 同一接触不得重复累计。
- 摩擦上限 ≠ 整侧硬扭矩/功率限额；后者需单独验证。

### 3.9 生命周期接线（S2）

`VehiclePhysics.kt` / `VehiclePhysicsSpec.kt` / `VehicleBody.kt`：

- `VehicleBody` 保留 chassis 与炮塔/炮管。
- 新增 `TrackedVehicleRuntime` 管理轮、run、绑定、悬挂、驱动，使用**同一 owner**。
- 向 `VehicleBody` 显式传入已扣除运行机构的车体质量。
- 整机构装配完成后才 `ready`；reset/close 覆盖全部对象，并提供可等待 Future。
- 不恢复旧 tracked 分支的整侧状态。

`GroundVehicleAllowlist.kt` / `supportsPhysics()` / `control()`：

- 增加明确的 tracked 选择与资源加载/编译注册入口。
- 未知模型不得 fallback 成轮式。
- 控制目前仍读旧 `EngineInfo`（`TrackDifferential`、`MaxForwardSpeedRate`），
  必须决定到编译 `drivetrain` 的映射，避免两套参数并行生效。

### 3.10 渲染与同步

| 文件 | 修正 |
|---|---|
| `physics/VehiclePhysicsRenderState.kt` | 新增 tracked 专用快照：稳定 wheel ID、逐轮机构状态、左右带速/相位、`configRevision`、`epoch`、`frame` |
| `network/message/receive/VehiclePhysicsStateMessage.kt` | 增加上述字段；拓扑/epoch 改变时禁止跨状态插值；保留 `values.size` 校验风格 |
| `client/renderer/entity/GeoVehicleRenderer.kt` 513–560 | 取消“整侧根偏移 + 逐轮位移”重复叠加；tracked 走新路径 |
| `client/model/entity/VehicleModel.kt` 250–286 | 同上；轮 ID 从编译绑定取，不再从骨骼名数字猜 |
| 新增 `client/render/.../McspTrackPath` 等 | 共享包络输入；可见计数初始化固定；模板重复绘制 |
| 旧 `trackPitch`/`trackPhase` 契约 | 明确单位与来源；不得用纯车体速度替代带速 |

**可见数量决策点：** 99A 模板纵向长度 `3.72912/16 = 0.23307 m`，路径长度约
`79 × 0.23307 = 18.41 m`，按 MCSP 规则会得到约 79 个可见实例，而当前模型只有
37 个 `trackRot` 骨骼。必须决定：
1. 隐藏旧 37 骨骼链，重复绘制 1 个模板约 79 次；或
2. 保留 37 骨骼作为可见节距来源，放弃 MCSP 计数规则。

不能两者混用后声称是 MCSP 行为。

---

## 4. 保留清单（不要改）

| 项 | 位置 | 理由 |
|---|---|---|
| 任务接纳门、可等待关闭、统一 teardown | `Core PhysicsLevel`/`PhysicsLifecycleGate` | 已通过 T0/T2，与拓扑无关 |
| 事务装配与回滚账本 | `TrackRunAssemblyLedger` | 复用即可 |
| 稳定 `runId`/`poseEpoch`/`linkBodyIds` | `TrackRunSnapshot` | 与拓扑无关 |
| 逐子步快照与插值 | `TrackRunCapture`、`PhysicsStateInterpolator` | 与拓扑无关 |
| 双向悬挂力律、对称绝对力上限、缓冲块透传 | `PhysicsSuspensionRow` | B1 基线有效 |
| 四个权威定义 | `TrackedDefinitions.kt` | 用户明确要求保留 |
| 模型解析与坐标换算 | `BedrockModelGeometry` | S1 已审计 |
| 编译错误隔离 | `TrackedCompilation` | 防错误规格进入运行时 |
| 盒体/圆柱惯量公式 | `TrackedMassProperties` | 可追溯 |
| AABB 地形与 owner 过滤 | Core `CollisionRules`、`PhysicsChunkManager` | 与拓扑无关 |
| 已撤销方案清单 | `CONSTRAINTS.md` §3 | 不得复活 |

---

## 5. 质量预算决策（必须先定）

99A 当前：总 55 000，车体 39 756，运行机构 15 244（7 622/侧）。
轮 6 200/侧，鞋 1 422/侧。

| 方案 | run 质量 | 车体质量 | 影响 |
|---|---|---|---|
| A. 保持现有预算，把鞋质量转入 run | 1 422/侧（按相邻轮对分配） | 39 756 | 不改总质量；但 run 比 MCSP 公式轻 42%，惯量偏低 |
| B. 采用 MCSP 公式 | 2 480/侧 | 37 698 | 更接近 MCSP；车体减 2 058，需重跑 B1 静载与重心审计 |
| C. 调整轮质量后再套公式 | 取决于新轮质量 | 待定 | 改动最大，需重新审计轮惯量与悬挂载荷 |

**建议：** 先用 A 做拓扑原型（不改总质量，便于隔离变量），
B2 承载通过后再评估是否切到 B。无论哪种，**禁止**把 MCSP 的 `0.6m + 0.2(m_i+m_next)`
直接叠加在现有 1 422 kg 鞋质量之上。

---

## 6. 验证与验收

### 6.1 证据等级不变

T0 纯数学/数据 → T1 原生 Bullet → T2 核心地形/线程 → T3 GameTest →
T4 客户端/网络 → T5 用户实机。

### 6.2 新增/改写用例

| 用例 | 等级 | 断言 |
|---|---|---|
| 轮对 run 几何 | T0 | 半长 = 轮心半距 + depth/2；横向半宽来自 belt 宽度；中心线落在公切线 |
| 混合约束语义 | T1 | 第一端只放行轮轴转动；第二端只锁声明法向；切向可滑 |
| 切向自由度负向测试 | T1 | 把第二端改成三平移全锁后，测试必须失败（防回归） |
| 轮位扰动 | T1 | 抬升一端后 run 跟随，局部盒形不变 |
| 无地形/零摩擦对照 | T1 | 无地形时驱动不产生前进；零摩擦时不产生牵引 |
| drive/brake/coast 三态 | T1 | 三者响应可区分，零目标驱动 ≠ 自由滑行 |
| 牵引归属 | T1 | 按源 slot 归属，不平分、不重复累计 |
| 完整单侧机构 | T1 | 锚点残差、法向残差、锁轴偏差、穿透、能量 |
| 整机构外力平衡 | T1/T2 | 地面支撑 + 约束传力 + 悬挂力闭合到总重 |
| 双侧扰动 | T1/T2 | 左右不串扰；外部冲量后恢复 |
| 渲染计数 | T0 | 初始化固定、间距浮动、相位环绕、倒车反向 |
| 轮 ID 绑定 | T0 | 逐轮绑定，不从骨骼名猜 |

### 6.3 阈值

- B2 阈值**仍未冻结**。先收集真实观测，再由用户确认残差、漂移、覆盖、
  载荷平衡和穿透限值。
- 不得用 native Rapier 实验值或旧鞋链测试代替 Bullet/整车验收。
- `1e-6 m` / `1e-3 rad` 只是装配自洽上限，不是动态验收阈值。

### 6.4 B1 复核触发

run 质量、安装框架、施力点任一变化 → 追加 superseding 事件，重跑单站静载、
压缩/下垂恢复、行程与力上限。

---

## 7. 阶段重排

| 阶段 | 修订后内容 | 退出条件 |
|---|---|---|
| **D0** | 更新约束、架构、映射、渲染、验收、状态、Skills | 文档互相一致；区分目标/现状/撤销 |
| **D1** | 不重做清理 | 保持零引用 |
| **K1** | 保留已完成；补“混合约束组合”和“接触面运动”原型 | 原型有结论；未证明能力不得标支持 |
| **S1（重开）** | 显式轮序/wrap、轮对几何、质量/惯量、车体 OBB/COM 审计 | 纯 JVM 测试通过；旧鞋链成绩保留为历史 |
| **B1（复核）** | 按新质量与施力点复核 | 单站基线仍成立或 superseding |
| **B2（重设计）** | 小机构→完整单侧→真实承载→双侧扰动 | T1/T2 证据 + 阈值确认 |
| **S2** | `TrackedVehicleRuntime` 生产接线 | 无幽灵对象；实机暂停点 A |
| **S3.1** | 原始 AABB 基础验证 | 完整方块/半砖/楼梯/AABB 阶梯坡/坑/桥/两格墙；光滑连续斜坡不在范围 |
| **S4** | 驱动、差速、制动、滑移 | 操控正确；实机暂停点 B |
| **S3.2** | 可选台阶辅助（独立开关） | 辅助关闭时基础碰撞正确；辅助开启后真实爬越一格；实机暂停点 C |
| **R1** | 共享包络的可见路径 | T0 路径数学 + T4 模型；实机暂停点 D |
| **N1** | 同步、revision/epoch、性能 | 1 台再 4 台 |
| **V1/F1** | 逐车迁移；断带/脱轨按 run 失效重新设计 | 独立验收 |

依赖：`D0 → S1(重开) → B1(复核) → B2(重设计) → S2 → S3.1 → S4 → S3.2 → R1 → N1`。

---

## 8. 未决问题（需用户决定）

1. **拓扑方向**：是否正式采用轮对运行段？旧鞋链是否保留为可选/历史能力？
2. **质量方案**：§5 的 A/B/C 选哪一个？
3. **轮端安装框架**：非自转 carrier，还是安装框架与滚动部件分离？
4. **可见数量**：79 模板重复，还是保留 37 骨骼？
5. **驱动实现**：接触面运动（需 Bullet 原型）还是先做有界轮带耦合过渡？
6. **S3 顺序**：是否接受“S3.1 → S4 → S3.2”的新顺序？
7. **旧成绩处理**：79 节审计与 B2.0 是否降级为“旧拓扑证据”？

---

## 9. 风险与禁止事项

- 不得用调参掩盖契约错误。
- 不得把第二端做成三平移全锁。
- 不得把会自转的轮 body 直接当作 run 安装框架而不验证公转。
- 不得在未证明时序前声称已实现求解前接触修改。
- 不得用摩擦上限冒充整侧硬扭矩/功率限额。
- 不得把 MCSP 质量公式叠加到现有鞋质量上。
- 不得让渲染结果反写物理。
- 不得恢复全局高度场、探针承载、固定楔面、`setPos`/直接位置修正或人工抬升。
  这不禁止 S3.2 的受限 `TrackedStepAssist` 目标；它仍必须独立验证。
- 不得把 Rapier `modify_solver_contacts` / `slope_normal_y` 写成 Bullet API 契约，或把
  MCSP 的坡面参数直接当作 SBW 冻结阈值。
- 不得把 research/docs 提交进代码提交。
- 不得在阈值未冻结时写 `auto_passed`。

---

## 附录 A：99A 关键数值（现状，供对照）

| 项 | 值 |
|---|---|
| 总质量 | 55 000 kg |
| 车体 / 运行机构 | 39 756 / 15 244 kg |
| 轮 | 8/侧（6 Support + 1 DriveGuide + 1 IdlerGuide），6 200 kg/侧 |
| 旧鞋链 | 79 节/侧，18 kg/节，1 422 kg/侧 |
| 可见骨骼 | 37/侧；模型骨骼 287 |
| 节距 / PinInset / Clearance | 0.23307 / 0.048828125 / 0.002 m |
| 模板 cube | 12.5 × 1.5625 × 3.72912 px → 0.78125 × 0.09766 × 0.23307 m |
| 张紧偏移 / 行程 | +23.9 mm / ±100 mm |
| 最大包绕角 / 限值 | 32.30° / 40° |
| 旧鞋体-轮最小间隙 | 1.99 mm（导轮处） |
| 悬挂 | 6 行/侧，19878 kg/侧，最大静载下沉 0.0513 m，最小力余量 12.31× |

## 附录 B：证据索引

- `MCS_SOURCE_REVIEW/disassembly/linux-x86_64-full.asm`
- `MCS_SOURCE_REVIEW/disassembly/slope-belts-contacts.asm`
- `MCS_SOURCE_REVIEW/disassembly/linux-x86_64-symtab.txt`
- `MCS_SOURCE_REVIEW/quistaphys-native/linux-x86_64/librapier_bridge.so`
- `MCS_SOURCE_REVIEW/quistaphys-java/src/quista/phys/gear/{PhysGear,PhysGearSpec,PhysGearBelt,PhysGearWheel,PhysLoop}.java`
- `MCS_SOURCE_REVIEW/quistaphys-java/src/quista/phys/body/{PhysScene,PhysJointSpec,PhysNative}.java`
- `MCS_SOURCE_REVIEW/mcs-java/quista/mcs/platform/{PlatformGearSpec,PlatformGearDraw,PlatformRig}.java`
- `MCS_SOURCE_REVIEW/mcs-java/quista/mcs/platform/client/PlatformGearRenderer.java`
- `MCS_SOURCE_REVIEW/mcs-java/quista/mcs/chassis/{ChassisGround,ChassisShadow}.java`

> 以上仅为行为证据，不是原始 Rust 源码；Rapier 参数不得直接当作 Bullet 参数。
