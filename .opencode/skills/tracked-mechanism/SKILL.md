---
name: Tracked Mechanism
description: Audit, design, test, or integrate independent road-wheel suspension, dynamic track runs, idlers, tensioners, and generic differential drive on Bullet.
---

# 独立轮悬挂与物理履带运行段技能

开始前阅读本仓库：

1. `docs/physics/CONSTRAINTS.md`
2. `docs/physics/ARCHITECTURE.md`
3. `docs/physics/MCSP_BULLET_MAPPING.md`
4. `docs/physics/TEST_MATRIX.md`
5. `docs/physics/ACCEPTANCE_LIMITS.md`

并核对 KaguyaPyCore：

1. `KaguyaPyCore:docs/TRACK_RUN_API.md`
2. `KaguyaPyCore:docs/PHYSICS_API.md`
3. `KaguyaPyCore:docs/THREADING_AND_LIFECYCLE.md`
4. `KaguyaPyCore:docs/LIBBULLETJME_INTEGRATION.md`

## 角色与自由度

- `SUPPORT` 负重轮：独立动态轮端 body；有明确悬挂行程、弹簧/阻尼和 bump stop；
  该 body 相对安装机构不自转，可见轮面自转由渲染负责。
- `DRIVE` 主动轮：带内轮端 body 同样不自转；驱动经接触面切向运动生效，
  不能误分给悬挂负重轮逻辑，也不施加轮自转电机扭矩。
- `IDLER` 导动/张紧轮：张紧轴必须真实自由且有行程限位；不要对锁死的自由度施力。
- `RETURN_ROLLER` 托带轮：是否承载/驱动必须由通用角色契约明确。
- 悬挂平移、张紧轴、轮端相对转动和轮带耦合不得争用同一自由度。

## 物理运行段

- 2026-10-31 起拓扑由数据 `Track.Topology` 选择：
  - `EnvelopeBelt`（99A 生产使用，取代 `AnchorChain`）：`TrackedEnvelopeBeltCompiler` 按 `BeltOrder`
    以"轮半径 + 半个履带厚"求外公切线包络，负重轮取静载下沉后的位置（`wheelCenters`），锚点只算偏差。
    `TrackedDynamicBelt`（SBW 自有刚体，经 `BeltWorld` 记入回滚账本）：前斜段、底部、后斜段、顶部回程
    为刚性段，真实厚度盒 + 两端半径 t/2 横向圆柱销头；相邻段直接销接；前斜段销在导动轮，顶部销在
    主动轮、在导动轮处沿自身滑动；后斜段在主动轮处沿自身滑动，由 `Envelope.Tension` 内力拉紧
    （履带不循环，下半圈长度由此适应悬挂，导动轮固定）；负重轮只锁法向。包角是导/主动轮刚体的
    复合子形状。底部初始一整块，每游戏帧（FRAME_PRE，不在 Bullet 更新内）按本车地形射线剖面
    在折点拆分、随折点移动、伸直并保持后合并。必须测量销点间隙、轮锁误差、下沉、拆合次数与反复拆合。
    普通悬挂弹簧/平均阻尼放在轮—车体安装关节内，只有 bump-stop 与不对称阻尼残差显式施力；不允许
    对履带关节使用 `overrideIterations`，长链需要通过核心的世界级 `solverIterations` 请求，让接触、
    摩擦和关节使用同一迭代次数。若接触回调写两条摩擦切线/`contactMotion1/2`，Core world 必须启用
    `SolverMode.Use2Directions`，否则第二摩擦目标无 solver row。道路轮锁应只约束单向法向，纵向自由，
    并在分段重绑时将两 frame 锚到当前轮心。依据见 `TrackedEnvelopeBeltNativeTest`、
    `TrackedContactFrictionNativeTest`、`TrackedSuspensionSprings`。
  - `AnchorChain`（已被取代，仅保留可编译对比）：由 `EnvelopeAnchorBone` 的锚点给出整条闭合履带，底部按
    `Chain.UnitPitches` 等分为铰接单元（共享销轴，无缝），在锚点处断开为若干核心 TrackRun；
    锚点销轴固定到所属轮，非端点负重轮只锁法向。底部单元可向带内加厚，接地面不变。
    该拓扑需要 `Chain.SolverSubsteps ≥ 2`（核心 `solverSubsteps` 申请）和单元惯量放大，
    依据见 `TrackedBeltChainNativeTest` / `TrackedChainProductionNativeTest`。
  - `WheelPairRuns`：以下各条描述的相邻轮对运行段，保留为旧拓扑与组件测试。
- 物理履带是**相邻轮对**之间的动态运行段，不是整侧虚拟板，也不是逐鞋销链（`WheelPairRuns`）。
- 每个相邻轮对（含 last→first）一个运行段；轮序必须显式声明，不用 Z 排序推断。
  `BeltOrder` 缺失是编译错误，不是回退到纵向排序的提示。
- 运行段第一端为轮心转动副（锁三平移、只放行轮轴转动）；
  第二端只锁一个法向平移，切向必须可滑。第二端不得做成三平移全锁。
- 两端都必须是完整的六轴规格（`CompiledJointPlan`：两具 body 逻辑引用、两侧 local frame、
  六轴状态），不能只存一个枚举名或世界方向。
- 运行段盒形、质量、惯量和碰撞过滤来自 SBW 编译结果；盒形落在已审计的履带平面上，
  不得用轮心平均反推平面。
- KaguyaPyCore 只创建通用运行段 body 和 joint；车型拓扑、轮序和两端约束由 SBW 决定。
- 可见链节不是物理刚体；运行段数、可见链节数和模型骨骼数三者独立。
- 端锚点误差、法向残差、切向滑移、锁轴漂移、轮段间隙和长期漂移必须测量。
- 若完整机构不稳定，先定位轮序/wrap/质量/求解参数，不减少轮数后宣布通过。

## 审计清单

1. 确认模型骨骼 pivot、车型 OBB、`ChassisOrigin` 和 Bullet body frame 的变换；测试比较
   同一坐标系。
2. 每轮审计半径、宽度、轴向、质量、角色、簧载份额和所属侧。
3. 每侧审计显式轮序、逐轮 wrap、track center plane、运行段厚度和轮对几何。
4. 装配时应用 idler offset；测量端锚点距离与 frame 角误差，不以轮心距代替。
5. 每个动态 body 设置 owner、碰撞组、同 owner mask、重力策略、CCD、休眠唤醒、质量和惯量；
   统计全机构质量，避免 double-count。
6. 先核对核心 API 的真实单位和版本，再写公式。`New6Dof` 与旧弹簧接口语义不可仅凭字段名
   视作等价。
7. 用有断言的原生测试验证静载、储能/耗散、限位、轮端不自转、混合约束和长时间稳定；
   只打印 probe 数值不算通过。
8. 生命周期验证创建、reset、remove、Level unload、重建和错误恢复；joint 两端 body 入同一
   物理空间后才注册；删除先断约束再移除 body。

## 驱动边界

首轮允许通用有界轮带耦合，不要求主动轮齿真实啮合。但必须有反作用、负载反馈、滑移、
制动和扭矩限幅；禁止直接给 chassis 施加替代履带接触的驱动力、写速度或按车型分支。
drive/brake/coast 必须是三个可区分状态，零目标速度驱动不等于自由滑行。
先单侧低速测试，再左右差速，最后整车转向。

## 记录

子阶段和阶段状态同步到 `docs/physics/STATUS.md` 与
`docs/physics/records/events.jsonl`。闭合误差和静载限值未写入
`docs/physics/ACCEPTANCE_LIMITS.md` 时，测试只记录测量，不标通过。到实机 A/B gate 时
停止推进，等待用户确认。
