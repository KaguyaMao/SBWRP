# 履带物理当前状态

2026-11-01 交接入口：[`HANDOFF.md`](HANDOFF.md)（第 000 节为最新：`EnvelopeBelt`、底部动态拆合、隐式悬挂弹簧、2 子步、世界级 60 次迭代；Bullet 两方向摩擦已启用；KaguyaPyCore 163 项、SBW JVM 227 项通过，实机未确认）。本轮有效聚焦 GameTest：parking 与 forward drive 通过；左右转向失败，逐子步累计偏航仅约 0.002–0.003 rad（断言 0.1 rad，未改）。focused namespace 注册已修正为模板过滤真实执行。坡顶 wheel-lock 法向残差约 5.18 mm，仍未通过；GameTest 仍负载敏感。

2026-11-01 联动复核（见 HANDOFF 最新追加）：拆分/移动改为固定外销、按材料长度解析拟合内部销；合并不再按段复用总残差。T0、平地原生 T1、坡面原生 T1 通过；坡面原断言满足（拆 2/合 2、最多 3 段、最终 1 段、0 flap）。focused 平地直行 GameTest 的履带拓扑保持两侧各一段且无重建；俯仰约 -0.35°..0.37°。轮行程起步有明显重分配，巡航末段约 65..82 mm（观测，不是冻结阈值）；悬挂不宣称已完全稳定。左右转向仍失败，约 0.002..0.003 rad 偏航，断言未改。

本轮确认：`PhysicsWorld` 未启用 `Use2Directions` 是接触运动方向缺 solver row 的直接原因；新增原生接触对照验证方向 2 的目标传递。wheel-lock 的分段纵向限位与悬挂重复约束已移除，frame 重绑基于当前轮心；仍有约 5.18 mm 单轮法向残差。该值不是 pin gap，不视为通过。

更新时间：2026-10-30（同层交叉坡面改为较低包络；穿过机构内带的坡面被拒绝。调试命令不再要求乘车，并显示车体 OBB、轮圆柱和运行段。悬挂不再每子步强制唤醒。物理运行段仍是相邻轮对刚性盒，未按坡面拆分。99A 行驶与实机未确认。轮体地形碰撞仍保留。）

本记录类型：状态快照，不是通过证明

## 1. 架构基线

- 目标：以 SBW 自有车型与悬挂定义为权威，在 Bullet 上实现履带式载具物理。
- MCSP 分层：车型/悬挂 schema 以 SBW 为准，不复制 `Suspension.Kind`、`carrier`、`hub`、
  `knuckle`、`spin`；但 MCSP 对履带字段组织（轮序/wrap/厚度/节距/下垂/侧向摩擦/
  驱动分组）、可见路径求解与 Java/native 运行段桥接有可逐行核对的源码贡献。
  详见 [`MCSP_BULLET_MAPPING.md`](MCSP_BULLET_MAPPING.md) §3.1。
- 地形：Minecraft 方块 `VoxelShape` 转 AABB，不使用车辆高度场。
- KaguyaPyCore：只提供通用 Bullet API、TrackRun 和快照插值。
- SuperbWarfare：车型、悬挂、驱动、地形策略、生命周期和渲染。
- 当前样车：`superbwarfare:ztz_99a`。

详见 [`ARCHITECTURE.md`](ARCHITECTURE.md)、[`CONSTRAINTS.md`](CONSTRAINTS.md)。

## 2. 仓库和证据

| 项目 | 值 |
|---|---|
| SBW 分支 | `Physic-Update` |
| SBW 归并提交（D1+S1+B1） | `1b5c984`，父提交 `d345b3b` |
| KaguyaPyCore 归并提交 | `e90984a`，父提交 `ef5104a` |
| 归并前备份引用 | `backup/pre-squash-cf745c8`（SBW）、`backup/pre-squash-129ca4d`（Core） |
| SBW 远端 | `origin/Physic-Update` = `1b5c984`（已强制覆盖） |
| KaguyaPyCore 远端 | `origin/Physic-Update` = `e90984a`（已强制覆盖） |
| 提交内是否包含文档 | 否。`docs/**`、`.opencode/**`、`AGENT.md`、`PROMPT.md`、`plan.md` 均不在两个提交中 |
| 工作树 | 有未提交的 S1/B2 代码、测试与文档；`git status` 必须与 HEAD 分开读 |
| 99A 当前工作树机构 JSON SHA-256 | `7d318b7fb719d586a0a8dbff2d3a4ff5d9cfc2124fd76557eda2c3126752af1c` |
| 99A 模型 JSON SHA-256 | `fe7099f7a850bf3b8730471065c1b74ee53cd29dc182ff86dec6abe8006bf747` |
| 清理前文档/源码基线 | `docs/physics/history/2026-10-20-pre-mcsp/` |

工作树有未提交的 S1 轮对拓扑代码与数据改动；上表 `1b5c984` 只描述已提交的旧基线，
不包含本轮原型。`git status` 与 HEAD 必须分开读。

### 提交记录

| 仓库 | commit | 内容 |
|---|---|---|
| KaguyaPyCore | `f44e72ed10be63d3f54f89cd4526bd280269d300` | D1 旧实现清理 + K1 通用 TrackRun、生命周期门、快照插值 |
| KaguyaPyCore | `c2894c483d0253f3244374574decef428a943bfe` | 版本 1.20.1-1.2.0，供下游消费 K1 API |
| SuperbWarfare | `e41792c73b9dadc64068b8157b10e806cb51f367` | D1 旧履带实现删除、文档与 Skills |
| SuperbWarfare | `6e19d82669b393d5dddaca4a15c3b873df008ba3` | S1 99A 机构编译与数据/几何审计 |
| SuperbWarfare | `143563f16bab5350a65e99c7e1a4f71826c62e83` | S1 复审修正：鞋体实体间隙、编译错误隔离、轴向/惯量/渲染描述 |
| KaguyaPyCore | `ea877d6` | B1 通用逐站悬挂行 `PhysicsSuspensionRow` 与原生单轮 fixture；版本 1.20.1-1.3.0 |
| SuperbWarfare | `fab9ef7` | B1 99A 逐负重轮独立悬挂编译与审计 |
| KaguyaPyCore | `129ca4d` | B1 修正：悬挂行改为双向带符号力与对称绝对上限；版本 1.20.1-1.3.1 |
| SuperbWarfare | `2eb212e` | B1 修正：SBW 悬挂数据权威化、缓冲块透传、pivot 重合、双向恢复校验 |
| SuperbWarfare | `5ec4815` | 文档：SBW 悬挂定义权威化与双向恢复语义 |
| KaguyaPyCore | `e90984a` | 本轮代码归并提交：通用悬挂行与动态履带刚体接口、插值、生命周期与性能修复（不含文档） |
| SuperbWarfare | `1b5c984` | 本轮代码归并提交：99A 机构审计 + 负重轮独立悬挂（不含文档） |

历史提交（`fab9ef7`、`2eb212e`、`5ec4815` 等）仍可通过 `backup/pre-squash-*`
引用追溯；它们已被上表的归并提交取代，不再作为当前代码基线。

**重要边界：** SuperbWarfare 当前工作树请求消费 `cn.solarmoon:spark_core:1.20.1-1.4.3-physics-metrics`
（本地 `publishToMavenLocal` 制品）。1.3.3 把内嵌的 Libbulletjme 从 22.0.3 升到 23.2.0，
新增 `PhysicsContactConceivedEvent`（接触点进入 manifold **之前**的回调）与
`PhysicsBodyEvent.ConceivedContact`，并显式向 Bullet 请求 `contactConceived` step flag。
这修掉了“新接触点首个子步没有驱动目标”的缺陷。1.3.2 的关闭 participant、
`addCloseListener` 和 `TrackRunRuntime.closeNow` / `resetNow` 保持不变。B1 悬挂语义仍是 1.3.1。
SBW 已实际 import 核心 `TrackRunSpec` 等类型。
`TrackRunRuntime` 已在 S2 接线（`TrackedVehicleAssemblyLedger` 持有生产 runtime）；
悬挂行同样经 `toRowSpec()` 挂到车体。跨仓编译通过只证明 API 形状可用。

## 3. 当前完成度

2026-10-28 terrain correction: requested/installed windows 分离；per-runtime build/apply 单飞；
Core revision token 校验与锁内 install；按 consumer ID 请求并在 close 时释放 region；mesh
window 使用旋转无关 running-gear 包络；GameTest 失败输出含 physics/cache/mesh/contact 诊断。
Core 全量测试通过（159 tests）；SBW 最终全量单测通过（176 tests, 0 failures）；包络、窗口和
planner 定向回归通过。最新完整 GameTest 为 48 项中 42 项通过、6 项失败，故回归仍未关闭。

2026-10-28 physics-thread reference follow-up：逐行固定核对 MachineMax `ee1ffbdf` 与 YWZJ
`bfdd69a5`。MachineMax 把活动车辆/部件更新挂到 Spark physics pre/post 回调，并在部分部件上
用 active gate、旋转相关不变量缓存和临时对象复用降低无效工作；YWZJ 的 `PhysicsEngine.tick()`
从实体服务端 tick 同步调用，未发现独立物理 worker。Core 新增逐帧 `PhysicsStepMetrics`，不改变
求解调度，以区分主线程 terrain 请求、worker queue/task/terrain apply、Bullet update、前后回调、
快照采集成本和 body 遍历量。Core 全量 159 项通过，制品 `1.20.1-1.4.3-physics-metrics`
已发布本地 Maven；SBW 全量单测 176/176 通过，消费新制品的 48 项 GameTest 已完成，42 通过、6 失败。
当前只证明部分帧的 worker/回调开销显著，不能据此声称失败根因已被解释或性能已经改善。

| 能力 | 状态 | 说明 |
|---|---|---|
| 每 Level 物理线程 | 已有 | 60 Hz、每 tick 三子步、单飞帧和快照 |
| 通用 voxel/AABB 地形 | 已有并简化 | 只保留方块 AABB；高度场、楔面、支撑对已删除 |
| KaguyaPyCore 通用 TrackRun | 已实现，T0/T1/T2 历史通过 | `TrackRunSpec`/`Snapshot`/`Assembly`/`Ledger`/`Runtime`；当前 1.4.3-physics-metrics Core 全量 159 项通过；GameTest 16 项曾通过，本轮未重跑 |
| KaguyaPyCore 生命周期与任务接纳 | 已实现，T0/T2 通过 | `PhysicsLifecycleGate`；提交/启动/关闭原子化，公共 Java 入口同样可等待 |
| KaguyaPyCore 通用快照插值 | 已实现，T0 通过 | `PhysicsStateInterpolator`，含 body、整帧、关节采样保留与不连续点隔离 |
| 物理履带（SBW 定义 + Bullet） | S1 编译层实现并通过 T0；B2 诊断与 T2 已执行 | 轮对运行段 + 轮端刚体 + 六轴 joint plan 已编译；旧鞋链降为 legacy 审计；生产 `TrackRunRuntime` 由 T2 GameTest 验证；车辆接线属 S2 |
| 99A 负重轮独立悬挂（B1） | 待复核（T0/T1 旧成绩保留） | 6 行/侧、双向恢复、行程与力上限、缓冲块透传；轮端刚体与质量拆分改变前提，需 superseding 复核 |
| MCSP 风格履带渲染 | 未实现 | 旧预处理轨迹已删除，新渲染器尚未实现 |
| 普通车体生命周期 | 已有 | 轮式不恢复；履带 allowlist 只登记 `superbwarfare:ztz_99a` |
| SBW 台阶辅助 | 受限切向目标已实现；**一格正向未达成** | 只对真实 run-terrain 接触写受限切向目标。竖直立面没有合法平面内方向：早期“向上切向”回退等价于梯子，实测把车顶翻并摔到 y=-104，已移除。因此没有“辅助开启后爬过一格台阶”的正向证据，S3.2 未通过 |
| 99A 机构/模型数据审计 | 旧鞋链审计完成，轮对拓扑待重做 | 轮角色、轮心、轴向、质量、惯量、哈希有 T0 断言；轮序/wrap 需显式化 |
| 99A 新物理接线 | 已接线，退出条件未满足 | `VehicleBody` 创建 `TrackedVehicleRuntime`；缺规格或质量计划不闭合时拒绝准入 |
| 旧实现清理 | 已完成 | 活动源码零引用旧符号 |

## 4. 当前阻塞

2026-10-28 terrain scheduler correction 后第一次完整 SBW GameTest：48 项中 8 项失败（历史结果，
见 `tracked-terrain-final-unit-regression-2026-10-28`）；它已被下面的新 Core metrics 重跑 supersede。
最新完整 SBW GameTest：48 项中 **42 项通过、6 项失败**，不构成验收通过。失败为：
`thebeltdrivesunderabridgedeck`、`aleftsteeryawstheotherway`、
`aoneblockstepclimbsonthebulletramp`、`contactmotiondrivesthehullforward`、
`steeringyawsthehull`、`coastremovestheconveyortargetinsteadofdriving`。此前失败的
`contactmotionreversesthehull` 与 `aresting99akeepsitswheelsonthebindpose` 本轮通过；单轮结果波动
不证明修复或稳定改善。

Core 1.4.3 metrics 的本轮失败采样显示：ramp 帧 `stepNanos=805.0 ms`、3 次 Bullet update
合计 666.7 ms（其中 post callback 193.5 ms）、末帧 capture 87.6 ms；样本有 4,115 bodies、
其中 terrain 1,104。正向接触驱动帧 `stepNanos=687.7 ms`、Bullet update 559.4 ms
（post callback 136.0 ms）、末帧 capture 118.0 ms；样本有 1,205 bodies、terrain 341，
7 次 terrain request、6 次请求因单飞帧被丢弃。forward drive 仍有目标接触回调，但车体只从
z=119.0 移至 118.9696；ramp 仍未到平台且接触窗口为零。Bridge deck 位移约 0.0043 m，
两项 yaw 断言分别报告 -0.00913 rad 与 +0.000375 rad；coast 由约 0.961 m/s 降至约
0.010 m/s。所有指标为个别同批次失败现场；post callback 计时含 resolved-contact 分发，
不能单凭这些值区分 Bullet 求解、manifold/contact event 派发及其他回调成本，也不能证明它们是失败根因。

最新 SBW 全量 `./gradlew test --offline --no-daemon --console=plain`：176 项通过，0 failures，
0 errors。之前的包络审计失败已由实际旋转盒投影和轮轴方向包络修正消除；`WindowRadius=6`
未修改。

1. **S2/S3.1/S4 已接线，S3.2 正向门未通过；退出条件仍未满足。** 最新本轮 48 项中 6 项失败，
   但台阶相关证据是否定性的（竖面不可辅助），没有“辅助开启后爬过一格台阶”的正向结果。
    钉区块的车在票还有效时区块不卸载；松开钉并清票后，区块卸载、刚体清掉、同一 UUID 回来。
   末地关世界时，shutdown participant 在原生销毁前按顺序拆掉车辆；关闭监听器仍能读到尚未销毁的
    native world，并断言该车 owner 的刚体数已经是 0。静置 220 tick 记录曾有两侧履带载荷证据；
    之后一轮完整 GameTest 左侧法向冲量为 0，而最新完整重跑通过该双侧承载断言。结果存在批次间波动，
    不能称稳定修复，也不是用户目视的暂停点 A。
   滑移落在著作最高速度带内；一侧顶墙时该侧滑移更大。自由车体在翻离垫子前由垫子那一侧承载。
   600 tick 行驶保持有限。AABB 阶梯坡由真实方块逐级构成，属于 S3.1；光滑连续斜坡不是
   方块 AABB，不属于门禁，也不允许用高度场伪造。S3.2 的受限切向目标已实现，但竖直立面
   没有合法方向，不能辅助一格竖面台阶；缺少辅助开启的正向 GameTest。
   **本轮的关闭证据是“可等待”的**：`VehiclePhysics.closeFuture(entity)` 返回真实 teardown Future，
   测试不再把 `isManaged == false` 当成刚体已消失。此前两次失败正是这个错误的等待方式造成的。
   **接触驱动时序已按源码核实并纠正**（此前文档结论过悲观），残余边界见 §6.4.1。
2. **B2 阈值已冻结（2026-10-24 用户确认）。** `ACCEPTANCE_LIMITS.md` §2.3 记录了 B2 的
   逐项冻结值；B2.1–B2.4 的 T1 与单侧 T2 门在冻结值下全部通过。`trackRunInputAccept`
   仍需 S4 产生输入路径后才能确认，属 S4 而非 B2 阻塞。
3. **B2.3/B2.4 的承载结论有明确夹具边界。** B2.3 是单侧夹具，车体由竖直滑轨约束姿态，
   只证明重量经运行段到地面；旁路由接触计数（窗口累计）观测，轮/车体掩码允许接地，
   不再靠掩码“保证”没有旁路。B2.4 有自由双侧车体，2400 子步（40 s）后仍以约 0.5 m/s
   沿 Z 蠕移 18.5 m，因为该夹具没有驱动、制动或滚阻。**B2.4 只证明窗口内保持装配与承载、
   冲量被接纳并衰减，不证明静止**；静止在 S4 提供滚阻/制动之前不可达，不能靠加长窗口
   或调阻尼伪造。两项都不能写成“整车静置通过”。
4. Bullet 是否提供可靠的求解前接触修改尚未证明；端 2 单法向约束的长期稳定性也未验证。
5. 关节载荷观测不可用：Bullet 6-Dof 的 `appliedImpulse` 恒为 0；接触冲量可用于接触负荷与牵引估计，
   但不能唯一恢复约束内力。这一点对 B2 的负载断言直接有限制。
6. 核心仍保留少量历史车辆辅助接口（`setRollMotor`、`applyOleoForce`、`trackLinkCarrier` 等）；
   已标记 deprecated，尚未从 SBW 调用点移除。
7. 车体惯量未审计：它需要车辆碰撞 OBB，机构定义中不存在；S1 只输出 run 与车轮惯量。
   B2.3 若需精确车体质量分布，必须先补审计。
8. 质量方案已定为 A：run 预算按侧显式声明（99A 各 1422 kg），按相邻轮质量分配；
   `39756 kg` 是剩余簧载总成预算，不是已审计的最终 chassis body mass。

## 5. 阶段状态

| 阶段 | 状态 | 备注 |
|---|---|---|
| D0 文档基线 | 已落实 | 新文档和 Skills 已写入；S3.2 的撤销边界与 MCSP 证据于 2026-10-26 拆分 |
| D1 旧实现清理 | 已完成并提交 | 活动源码零引用；核心测试通过；保留车体 GameTest 3 项通过 |
| K1 核心 TrackRun API | 已完成并提交，T0/T1/T2 通过 | 不含高度场；核心全量 124 项 0 失败；GameTest 16 项通过 |
| B0 通用车体基线 | 未开始 | 只跑回归；不恢复轮式 allowlist，不作 99A 前置门 |
| S1 99A 编译审计 | 轮对拓扑已实现并通过 T0（97 项） | `BeltOrder`/`Wrap` 显式化、`CompiledBeltMember`/`CompiledBeltGeometry`、`CompiledJointPlan` 六轴、每 run 单 link spec、方案 A 显式预算、legacy 鞋链隔离 |
| B1 99A 独立悬挂 | 待复核（旧成绩保留） | 轮端刚体与安装约束改变前提；`TrackedWheelMountTest` 5 项原生用例已通过，待用户确认阈值范围。2026-10-26 新增可选 `Spring`/`Damper` 直接参数，99A 未声明，原模型不变 |
| B2 99A 动态运行段 | **T1/T2 在冻结阈值下通过**（2026-10-24 用户确认阈值） | B2.0 T0、B2.1–B2.4 T1、单侧 T2 全部通过；阈值见 `ACCEPTANCE_LIMITS.md` §2.3。B2.3 为单侧滑轨夹具（承载经 run 到地面），B2.4 只证明 40 s 窗口内装配/承载/冲量衰减，不证明静止；均不得写成整车通过 |
| S2 99A 生命周期 | **自动证据已补，实机暂停点 A 未确认** | 缺规格或质量计划不闭合时拒绝准入。创建失败不置 ready。关闭失败可重试，`closeFuture` 可等待真实 native 拆除。钉区块会保住区块；松开钉后卸载/重载同一 UUID。关世界走 Core shutdown participant，在 teardown 前按顺序拆运行机构；关闭监听器断言该车 owner 刚体已为 0。静置轮位、张紧轴投影和双侧载荷有测量。用户目视确认仍未做 |
| S3.1 基础 AABB | **AABB 项已测，光滑连续斜坡不属于门禁** | 石头、半砖、楼梯、AABB 阶梯坡、坑、桥面、桥下行驶和两格墙有 GameTest。光滑连续斜坡不是方块 AABB，不能也不需要用高度场或楔面补。`empty.nbt` 为 32×8×32 |
| S3.2 台阶辅助 | **实现已落地，正向门未通过** | 采样走 `VoxelShape.toAabbs()` 并保留多盒；`rise` 从 `supportHeightUnder()` 的轮下支撑面量起（轮底会因穿入把一格读成更高而误拒）；`stackedOnSameFootprint` 拒绝连续立面，防止爬上一层两格墙后把上层当成一格逐层棘轮。辅助只对真实 run-terrain 接触写 `contactMotion1` + 正交摩擦基，**不改法线/接触点/距离，不施加独立向上力**。竖直立面投影为零时**不写方向**：旧回退等价于梯子，实测顶翻车辆。`stepAssistEnabled` 默认关闭。51 项中相关 T3 为：竖面不可辅助（否定）、两格墙拒绝、坑无支撑、辅助关闭不可跨越 |
| S4 驱动差速 | **接触驱动已接，MCSP 响应层已实现但未在 99A 启用；滚阻未实现；实机暂停点 B 未确认** | 接触写入走两条路径：`onContactConceived`（Core 1.3.3 新增，接触点进入 manifold 之前）与 `onContactProcessed`（刷新已存在的点）。滑移有 `SlipBand` 实测分布。**1800 tick soak 实测均值 -3.0～-3.1 m/s（指令 5.46 m/s），证明 `target + 1 m/s` 不能当验收带**（见 §6.4.2）。2026-10-26 新增可选 `Accel`/`Brake`/`PivotSpeed`/`SteerTime` 与 `TractionLimit`，纯控制律 T0 通过；99A 未声明这些字段，默认行为不变。**`RollingResistance` 未实现**：两种速度目标写法都实测注入能量（按运行段表面速度→20 tick 内 0.98→0.15 m/s 重刹；按车体速度→松油门后反而加速 18%）。COAST 现在只清除传送带目标，`coastTarget()` 保留为未调用候选。真实实体输入链路有 GameTest。用户目视确认仍未做 |
| R1 MCSP 渲染 | 未开始 | 等待 S2 快照 |
| N1 同步/性能 | 未开始 | 等待 R1 |
| V1 其他车型 | 未开始 | 首轮之后 |
| F1 损毁玩法 | 未开始 | 仅预留 |

## 6. D1 验证记录

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew compileKotlin` | 通过 |
| `KaguyaPyCore: ./gradlew compileTestKotlin compileTestJava` | 通过 |
| `KaguyaPyCore: ./gradlew test` | 通过（21 s） |
| `SuperbWarfare: ./gradlew compileKotlin compileJava` | 通过 |
| `SuperbWarfare: ./gradlew compileTestKotlin compileTestJava` | 通过 |
| `SuperbWarfare: ./gradlew runGameTestServer --offline -Dnet.minecraftforge.gradle.check.certs=false` | 通过，3 项 `All 3 required tests passed`（不恢复轮式 allowlist） |

保留的车体回归为：

- `trackedVehiclesStayOutsidePhysicsUntilTheTrackRunRuntimeExists`
- `turretWreckFallsOntoTerrain`
- `outOfScopeVehicleStaysOutsidePhysics`

它们验证清理后普通车体路径未回归，且履带车/范围外车辆仍不进入 Spark 物理路径；
不构成 99A 接入证据。

零引用检查：

```bash
rg -n "LocalHeightField|ProbeSuspensionUnit|PlanarTrackSupport|TerrainSupportField|SUPPORT_PAIR|HEIGHT_FIELD|TerrainSlopePlanner|TerrainSlopeBodies|SLOPE|RUN_BLOCKS|trackGroundFallback|applyTrackSuspension|onTrackHullContact|stepAssist|setNormalWorldOnB|TrackAnimationProfile|TrackSuspensionProfile" \
  src --glob '!**/history/**'
```

两个仓库的活动源码均无命中。

## 6.1 K1 验证记录

本轮（复审修正实施）：

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew test --offline --console=plain` | 通过，**124 项 0 失败** |
| `KaguyaPyCore: ./gradlew compileKotlin compileJava --offline` | 通过 |
| `KaguyaPyCore: ./gradlew runGameTestServer --offline -Dnet.minecraftforge.gradle.check.certs=false` | 通过，16 项 `All 16 required tests passed` |
| `SuperbWarfare: ./gradlew compileKotlin compileJava --offline` | 通过（依赖已发布制品，未消费新 API） |

新增/更新的测试：

- `PhysicsLifecycleGateTest`（20 项）：并发提交与关闭、关闭后拒绝、STARTING 排队、
  listener 恰好一次、重复关闭同一 Future、剩余任务失败、错误抑制、启动/关闭竞态、
  去重接纳、关闭后不执行已排队任务。
- `TrackRunAssemblyFailureTest`（6 项）：回滚顺序、单个清理失败不中断、suppressed 附加、
  失败残留保留所有权、配置前已记账。
- `TrackRunHingeFrameTest`（7 项）：identity/镜像 frame、frame 内限位、非法 frame 拒绝。
- `TrackRunJointStateTest`（4 项）：native 关节坐标与符号、未采样时不伪造误差。
- `TrackRunHingeAngleErrorTest`（6 项）：native 约束坐标的限位判定与几何 swing-twist 符号。
- `SnapshotIdentityTest`（13 项）：runId/topology/poseEpoch、BodyFrame 不可变、body 与整帧插值、
  关节采样保留、集合防御性复制。
- `TrackRunSpecTest`（12 项）：Float 可表示性、大数四元数安全归一化。

本轮修正内容：

- `PhysicsLevel` 任务接纳与关闭改为单一 `PhysicsLifecycleGate`：提交与状态转换原子化，
  被拒任务立即失败；`NEW`/`STARTING` 仍排队。
- 启动完成也经过同一门：关闭抢先后不能把 Level 写回 `RUNNING`，也不能发布成功的 Init 事件。
- 关闭统一走 `runShutdownCleanup`：停接纳 → 通知 listener → 排空队列 → 失败剩余任务 →
  先 joint 后 body → terrain/world/shape teardown → 完成 Future；`FAILED` 同样完整清理。
- native teardown 错误不再被吞掉：每项独立尝试，汇总后让 shutdown Future 异常完成。
- `close()` 超时不再 `shutdownNow()`，避免丢弃唯一已排队的 cleanup。
- 公共 `PhysicsTasks.call/run` 改为委托 `submitTask`，不再出现“状态检查后被静默丢弃”的挂起。
- 去重任务经同一接纳门；单个任务异常不再带走同阶段后续任务。
- TrackRun 创建改为显式状态机：事务装配、首帧在 ACTIVE 前采集、关闭取消的 create 明确失败、
  关闭后不复活；回滚失败保留所有权并由 close 重试。
- 输入接口区分「已接纳」与「已施加」，新增 `TrackRunInputReceipt` 与 `*Async`。
- joint 支持两端局部 constraint frame；`jointAngleError` 改由 native 约束坐标计算。
- 快照新增 `runId`、`poseEpoch`、`linkBodyIds`；`BodyFrame` 不可变并携带稳定 `bodyId`；
  `PhysicsFrameSnapshot` 构造即复制，新增 world `epoch`。
- 插值不再丢失 joint 采样，且同时重写 `bodies`/`bodiesById`，不跨 reset/recovery 混合。
- 数值校验覆盖 Float 可表示性、派生溢出和大数四元数。
- 核心车辆方向/悬挂策略接口标记 deprecated，并新增通用 `setRotationMotor`、
  `applyPairedForce`、`withLinkBodies`。
- 无 owner 的 TrackRun 使用私有 owner token，而不是把 `null == null` 当作同 owner；
  这样同段 link 互相过滤，但不会让世界中所有无 owner 刚体彼此失去碰撞。
- 几何铰链诊断对 q 与 -q 做规范化，避免等价四元数被报成整圈违例。

历史记录（上一轮，不适用于当前代码）：68 项核心测试与 12 项 GameTest 曾通过；该批次
未包含本轮的生命周期、事务回滚、joint frame 与快照身份改动。本轮以 124 项单元/原生测试
和 16 项 GameTest 重新验证。

构建注意：本机 ForgeGradle 的环境检查会在插件应用阶段做 SSL 连接，`--offline` 不能跳过。
本地构建需加 `-Dnet.minecraftforge.gradle.check.certs=false`。

## 6.3 B1 验证记录

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew test --offline` | 通过，146 项 0 失败（含 `PhysicsSuspensionRowTest` 14 项、`NativeSuspensionRowTest` 8 项） |
| `KaguyaPyCore: ./gradlew publishToMavenLocal --offline` | 通过，发布 `cn.solarmoon:spark_core:1.20.1-1.3.1` |
| `SuperbWarfare: ./gradlew test --offline --tests 'com.atsuishio.superbwarfare.physics.tracked.*'` | 通过，97 项 0 失败（轮对拓扑重开后） |
| `SuperbWarfare: ./gradlew test compileJava --offline` | 通过（消费 1.3.1） |

归并提交后重验（2026-10-23，仅代码，不含文档）：

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew test --offline` | 通过，统计 **146 项 0 失败 0 错误**（读取 `build/test-results` 汇总） |
| `SuperbWarfare: ./gradlew test compileJava --offline -Dnet.minecraftforge.gradle.check.certs=false` | 通过（首次命令 `test` 为 `FROM-CACHE`，因此另行重跑） |
| SBW `physics.tracked`（本轮，含 B2 native） | 通过，119 项 0 失败 | 含 B2.1 5、B2.2 5、B2.3 4、B2.4 6；旧鞋链用例经 legacy 入口运行 |
| SBW `runGameTestServer` | 通过，4 项 | 含 `TrackedRunRuntimeGameTests` 单侧 8+8 经 `TrackRunRuntime` 装配 |

注意：`FROM-CACHE` 不能作为本次归并后的新鲜执行证据，上表已区分。

新增代码：

| 仓库 | 文件 | 职责 |
|---|---|---|
| Core | `physics/PhysicsSuspensionRow.kt` | 车辆无关的逐站悬挂行：双向带符号力、对称绝对上限、渐进缓冲块、静载与耗散 |
| Core | `physics/NativeSuspensionRowTest.kt` | 原生单轮 fixture：静载落点、压缩/下垂双向恢复、上下限位、自由滚动、阻尼耗散 |
| SBW | `physics/tracked/TrackedSuspension.kt` | 99A 每负重轮一行，保留完整 `CompiledSuspension`，适配核心双向原语 |
| SBW | `physics/tracked/TrackedSuspensionTest.kt` | 行数与质量守恒、静载可落、双向恢复、缓冲块数据、轴向正交、行独立性 |

99A 悬挂审计数值：

| 项目 | 值 |
|---|---|
| 每侧悬挂行 | 6（仅 `Support` 轮） |
| 每侧簧载质量 | 19878 kg（两侧之和 == 车体 39756 kg） |
| 最大静载下沉 | 0.0513 m（压缩行程 0.18 m） |
| 最小力上限余量 | 12.31x |
| 自然频率 / 阻尼比 | 2.2 Hz / 0.65 压缩、0.45 回弹 |
| 轴向正交 | 轮轴 `+X`、行程 `+Y`，点积为 0 |
| 恢复方向 | 压缩 `F > 0`，下垂 `F < 0`；`MaxForce` 对称 |
| bind pose | `chassisPivot == wheelCenter`，`wheelPivot == 0` |

边界：B1 证明的是逐站数值、双向恢复方向与原生单轮行为。用户已于 2026-10-23 确认
[`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) §2 的基线，B1 在**当前版本与当前 fixture** 上记为自动通过。
这不是永久批准：力律、参数、夹具、核心版本或阈值变化时必须追加 superseding 事件并重新复核。
悬挂行尚未挂到车体，整车动态承载属于 B2。

悬挂语义以 SBW 自有 `TrackSuspensionDefinition` / `TrackSuspensionOverride` 为准；
MCSP 的 `Suspension.Kind` 等车型模型字段不进入 SBW schema。

但 MCSP 对履带字段组织、可见路径求解与 Java/native 运行段桥接有可逐行核对的源码贡献，
不应概括为“仅一般行为参考”；但 `linkPitch`、`sag`、`driveSlot`、`lateralFriction`
不在 `createBelt` 入参中，属于渲染或驱动响应层，不能当作 native 物理输入。

## 6.4 S2/S3.1/S4 关闭链复验（2026-10-26）

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew test --offline --console=plain` | 通过，**151 项 0 失败 0 错误**（读取 `build/test-results` 汇总） |
| `KaguyaPyCore: ./gradlew publishToMavenLocal --offline` | 通过，发布 `cn.solarmoon:spark_core:1.20.1-1.3.3` |
| `SuperbWarfare: ./gradlew test --offline --console=plain` | 通过，**146 项 0 失败 0 错误** |
| `SuperbWarfare: ./gradlew compileKotlin compileJava --offline` | 通过 |
| `SuperbWarfare: ./gradlew runGameTestServer --offline` | 通过，**48 项 `All 48 required tests passed`** |
| 两个仓库 `git diff --check` | 通过（无空白错误） |

本轮关闭链修正：

- Core 新增公开 `PhysicsLevel.addShutdownParticipant`，在停止接纳后、close listener 和
  `teardownNative()` 之前于物理线程执行；participant 抛错仍继续 native teardown，并让
  close Future 异常完成。关闭开始后再注册返回空 `Subscription`。
- Core `TrackRunRuntime.closeNow` / `resetNow` 提供同步版本，避免在关闭期再次 `submitTask`。
- SBW `VehicleBody.closeNow` / `TrackedVehicleRuntime.closeNow` /
  `VehiclePhysics.closeVehiclesNow` 构成车辆级同步拆除顺序：joint → run → 轮端 → 车体。
- `VehiclePhysics.closeFuture(entity)` 公开真实 teardown Future。此前测试把
  `isManaged == false` 当作“刚体已消失”，而拆除任务在 runtime 关闭回调中才入队，
  因此统计任务可能先跑并看到残留。这是测试证据缺陷，不是生产逻辑错误。
- 关闭参与者用游戏线程捕获的 `bodyLevels` 判断归属，不再在物理线程读 `entity.level()`。
- `begin()` 对非车辆实体提前返回，避免每次 `baseTick` 触碰物理 Level。
- `savedMotionIsRestoredWhenTheEntityReenters` 用存档时刻的位姿重载，并采样恢复后的速度峰值。
  用漂移后的坐标重载会被 `begin` 正确判定为“重新部署”并清零速度。
- 实体 Mixin 显式实现 `PhysicsHost.getPhysicsBody`，并让 `PhysicsLevelEvents` 直接访问
  `allPhysicsBodies["body"]`。此前 reobf 后 Kotlin 默认接口方法缺失，启动期抛
  `AbstractMethodError`（`ItemEntity`），整套 GameTest 无法开始。

仍未完成：真实 `VehicleEntity` 输入链路的专门 S4 GameTest 已在本轮补齐（见下）；
求解前接触修改的结论已按源码核实并纠正，但仍有明确残余边界；滑移带未冻结；
自由车体只有单侧载荷窗口，不是连续自由倾覆证明；实机暂停点 A/B/C 未做；S3.2 只有
分类原型，没有辅助开启的正向爬越。光滑连续斜坡不是方块 AABB，不属于门禁；AABB 阶梯坡
已覆盖。**车辆不标记 `Approved` / `NormallyAvailable`。**

### 6.4.1 接触修改时序：源码核实与修复（2026-10-26）

此前记录写的是“`setContactMotion1` 仍位于 `onContactProcessed`，尚未证明为求解前钩子”。
本轮核对了 Bullet 与 Libbulletjme 的源码，结论需要**修正**，并已据此完成升级：

| 事实 | 依据 |
|---|---|
| `onContactProcessed` 由 `btPersistentManifold::refreshContactPoints` 触发 | `btPersistentManifold.cpp` |
| `refreshContactPoints` 在 `processCollision` 内被调用 | `btBoxBoxCollisionAlgorithm.cpp`、`btConvexConvexAlgorithm.cpp` 等 |
| `processCollision` 属于 `performDiscreteCollisionDetection`，在 `solveConstraints` **之前** | `btDiscreteDynamicsWorld::internalSingleStepSimulation` |
| `m_contactMotion1/2` 只在求解器 `convertContact` 构建摩擦行时被读取 | `btSequentialImpulseConstraintSolver.cpp` |

因此写入本来就在同一子步的求解之前，不是“下一子步才生效”。

但 `refreshContactPoints` 只处理**已存在**的持久接触点：刚由 `addContactPoint` 创建的点
要等下一次刷新，于是新接触点的首个碰撞子步没有驱动目标。真正能覆盖这一点的是
`btManifoldResult::addContactPoint` 里的 `gContactAddedCallback` / Libbulletjme 的
`contactConceived`，它在点进入 manifold **之前**触发。

**已完成的修复（Core 1.3.3）：**

- 内嵌 Libbulletjme 从 22.0.3 升级到 23.2.0。22.0.3 没有 `StepFlag` 类，
  `ContactListener` 只有 `onContactEnded/onContactProcessed/onContactStarted`，
  无法拿到首个子步钩子。
- 新增公开 `PhysicsContactConceivedEvent` 与 `PhysicsBodyEvent.ConceivedContact`。
- `PhysicsLevel.performStep` 显式请求 `StepFlag.contactProcessed or StepFlag.contactConceived`。
  该 flag 不请求就不会触发，属于升级后必须同步的一步。
- `TrackedVehicleRuntime` 把接触配置抽成共享的 `applyBeltContact`，
  `onContactConceived` 与 `onContactProcessed` 都走它，避免两套逻辑分叉；
  `conceivedContactMotionCount()` 让测试能证明首个子步钩子真的被调用。
- 升级连带同步：`NativePhysicsLoader.VERSION`、Linux/Windows 原生库 SHA-256
  （实测值，不是抄写）、`LIBBULLETJME_INTEGRATION.md`。

**残余边界：** 滑移带仍是 `target + 1 m/s` 级别的健康检查，没有冻结成验收带；
自由车体只有单侧载荷窗口，不是连续时间自由倾覆证明。这两条不因本次升级而关闭。

### 6.4.2 长期 soak 实测：`+1 m/s` 不能当验收带（2026-10-26）

`aLongPartialThrottleSoakStaysFiniteAndSupported`（1800 tick、35% 油门、真实 AABB 地形）
给出了第一批长窗口滑移实测值。指令目标为 5.46 m/s，车体沿 Z 前进 31.16 m：

| 侧 | 样本 | min | max | mean |
|---|---|---|---|---|
| 左 | 26101 | -9.72 m/s | +0.45 m/s | **-3.14 m/s** |
| 右 | 27301 | -8.93 m/s | +0.87 m/s | **-2.98 m/s** |

这说明：

- 持续负载下，履带表面速度长期比指令低约 **3.0–3.1 m/s**（约为指令值的 55%）。
  这不是抖动或数值噪声，是真实的大滑移，与“爬方块边缘 + 车体加速”一致。
- 因此 **`maxSpeed + 1 m/s` 不能作为验收带**。它只在一小段稳态直行里成立，
  不能外推到长窗口或负载工况。这条此前只是“健康检查”的说法现在有了实测反证。
- 该测试因此改为断言“有限、有进展、双侧持续接触、滑移有界（`target + 20 m/s`）”，
  并把实测分布写进日志。它证明的是**长窗口不崩坏**，不是滑移验收通过。

滑移带要冻结成验收值，必须先确定工况（坡度、负载、速度、时长）再取分位数；
在那之前不得写成 S4 滑移通过。

## 6.2 S1 验证记录（旧鞋链拓扑，历史）

> 本节数值与测试结论只对旧 79 鞋链拓扑成立。轮对运行段定稿后，S1 拓扑部分待重开；
> 下列记录作为历史证据保留，不删除。

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew publishToMavenLocal --offline -Dnet.minecraftforge.gradle.check.certs=false` | 通过，发布 `cn.solarmoon:spark_core:1.20.1-1.2.0` |
| `SuperbWarfare: ./gradlew test --offline -Dnet.minecraftforge.gradle.check.certs=false --tests 'com.atsuishio.superbwarfare.physics.tracked.*'` | 通过，41 项 0 失败 |
| `SuperbWarfare: ./gradlew test compileJava --offline -Dnet.minecraftforge.gradle.check.certs=false` | 通过（首次真正消费 1.2.0 的 `TrackRunSpec` 等类型） |

S1 代码（纯数据，不创建 Bullet 对象）：

| 文件 | 职责 |
|---|---|
| `physics/tracked/BedrockModelGeometry.kt` | Bedrock pivot/cube 解析、物理局部坐标换算、骨骼祖先校验 |
| `physics/tracked/ClosedTrackLoopSolver.kt` | 固定节距闭链装配几何、包络求解、销轨迹半径 |
| `physics/tracked/TrackedDefinitions.kt` | 机构数据 schema（轮角色、轴向、悬挂方向、张紧、传动） |
| `physics/tracked/TrackedDefinitionValidator.kt` | 不依赖模型的字段校验 |
| `physics/tracked/TrackedSpec.kt` | 不可变编译结果、`TrackedCompilation` 成功/失败隔离 |
| `physics/tracked/TrackedAssemblyGeometry.kt` | 唯一链节放置来源，供审计与适配器共用 |
| `physics/tracked/TrackedGeometryAudit.kt` | 鞋体-轮实体间隙审计 |
| `physics/tracked/TrackedMassProperties.kt` | 链节盒体与车轮圆柱惯量 |
| `physics/tracked/TrackedVehicleCompiler.kt` | 车型数据 + 模型 → 审计结果 |
| `physics/tracked/TrackedRunSpecs.kt` | `CompiledTrackSide` → 核心 `TrackRunSpec` |

99A 审计数值（`TrackedVehicleAuditTest` 断言，**旧鞋链拓扑**）：

| 项目 | 值 |
|---|---|
| 每侧轮数 | 8（6 `Support` + 1 `DriveGuide` + 1 `IdlerGuide`） |
| 物理链节（旧） | 79/侧，共 158 |
| 可见链节骨骼 | 37/侧，共 74 |
| 模型骨骼总数 | 287 |
| 节距 | 0.23307 m |
| 闭合误差 / 销距误差 | ~7e-15 m / ~6e-15 m |
| 导轮张紧偏移 | +23.9 mm（行程 ±100 mm） |
| 最大包绕角 | 32.30°（限值 40°） |
| 销弦间隙 | 50.82 mm |
| 鞋体-轮间隙 | 1.99 mm（最小，导轮处） |
| 质量 | 车体 39756 kg + 运行机构 15244 kg = 55000 kg |

**轮对运行段拓扑下的目标值（尚未实现）：**

| 项目 | 目标 |
|---|---|
| 运行段 | 8/侧（相邻轮对，含 last→first），共 16 |
| 轮端刚体 | 8/侧，共 16，均无独立轮面自转 |
| 可见链节 | 仍由模板与节距规则决定，与运行段数无关 |
| 质量 | 待选方案 A/B/C，见计划 §5 |

S1 期间修正的两个真实缺陷：

1. **横向中心。**`CompiledLinkTemplate` 原本没有横向中心，若用轮心均值会得到 2.002 m，
   而模板骨骼 pivot 是 1.8359 m，履带会被整体横移约 166 mm。现在 `centerX` 取自模板
   骨骼 pivot，并有测试覆盖。
2. **间隙指标错误（复审修正）。**原审计把销弦到轮圆的距离当作间隙报告（31.49 mm），
   但链节是有厚度的盒体。以 `pinRadius = wheelRadius + PinInset` 求出的销多边形，每条
   包绕弦向轮内下垂一个矢高，99A 导轮处为 **17.34 mm**，即鞋体实际穿入轮体 17.34 mm。
   现在销轨迹按 `hypot(wheelRadius + PinInset + Clearance, pitch/2)` 求解，并对每个
   链节 × 每个轮做实体截面检查。修正后 78 链节无法在 ±100 mm 张紧行程内闭合，故
   `LinkCount` 改为 79、`Clearance` 设为 2 mm。

S1 的边界：这些是静态几何与质量审计，不证明 Bullet 动态承载、轮板接触或驱动；
那属于 B1/B2/S2。

## 7. 状态标签规则

中文标签只用于本文。机器记录使用 schema 的 `status`，对照见
[`RECORD_FORMAT.md`](RECORD_FORMAT.md)。

- `候选` / `candidate`：代码或数据存在，但未接线。
- `已接线` / `wired`：运行路径使用，但尚未满足测试退出条件。
- `自动通过` / `auto_passed`：规定自动测试通过，且限值已冻结。
- `待实机` / `waiting_manual`：自动测试通过，等待用户实机检查。
- `实机通过` / `manual_passed`：用户明确确认对应车型、场景和 A–D 门。
- `批准` / `approved`：自动、实机、文档证据和哈希均已记录。
- `证据已确认`不是 `manual_passed`。D0–S1 的用户确认只允许阶段离开“进行中”。

## 8. Skills 与 MCP

- Skills 已按新架构蒸馏，目录和触发规则见 [`SKILLS.md`](SKILLS.md)。
- 事件 schema、模板和 `events.jsonl` 已存在。
- MCP server、SQLite 索引和 OpenCode MCP 配置尚未建立。
- 本轮 `mcp_sync` 为 `unavailable`，后续从 JSONL 重建。

## 9. 下一步

1. ~~B2.0：把观测字段与采样窗口写进测试。~~ 已按轮对拓扑改写（`B2RunObservationTest` 16 项）。
2. ~~S1 拓扑重开：显式轮序/wrap、轮端安装、运行段附件、质量拆分。~~
   已完成：`BeltOrder`/`Wrap` 强制显式、`CompiledBeltMember`/`CompiledBeltGeometry`、
   `CompiledJointPlan` 六轴、run 实体间隙审计、方案 A 显式预算、legacy 鞋链隔离；
   `physics.tracked` 97 项 0 失败。
3. Core K1-J：`PhysicsJoints.free()` 已从 ±1e9 有限窗口改为倒置窗口，
   `NativeJointFreeAxisTest` 5 项原生验证通过；受影响 Core 分组 83 项
   （TrackRun 47 + native/suspension 36）全部通过。
4. B1 复核：按新 wheel mount / joint frame 重跑单站基线，或追加 superseding。
5. B2.0 改写：probe 按 logical body、两端 local frame、六轴 DOF 与新 topology 取数。
6. ~~B2.1 → B2.2：两轮两运行段 → 单侧完整机构。~~ 已执行；两轮机制标为诊断拓扑。
7. ~~B2.3 → B2.4：承载路径、双侧与外部冲量。~~ 已执行，夹具边界写在
   [`B2_RUN_TEST_PLAN.md`](B2_RUN_TEST_PLAN.md) §B2.3。
8. B2.4 窗口已延长到 2400 子步（40 s），但**静止在 S4 之前不可达**：该夹具没有驱动、
   制动或滚阻，实测仍沿 Z 蠕移 18.5 m。冲量判定已从“位移差”改为 `Δv = J/m` 并加上
   横向速度衰减检查。B2.3 若需要“车体被抬起”，必须用双侧自由车体，不能靠延长单侧夹具。
9. B2 阈值已由用户在 2026-10-24 确认并冻结（`ACCEPTANCE_LIMITS.md` §2.3）；B2.1–B2.4
   的 T1 与单侧 T2 门在冻结值下全部通过。S2/S3.1/S4 生产接线已开始；2026-10-26 GameTest 48 项、
   2026-10-27 GameTest 51 项通过。
    2026-10-26 另完成 MCSP 传动响应层与悬挂 `Spring`/`Damper` 可选字段，`physics.tracked`
    单测/原生测试 161 项通过；这些是纯控制律与数据层证据，不等于 S4 或 B1 通过。
    还不能标通过：用户没有做实机暂停点 A/B/C，S3.2 的正向门仍未达成（竖直立面不可辅助），光滑连续斜坡不属于
    AABB 地形范围；滚阻尚未实现；99A 未启用响应字段。
10. 最新 T3 已在 2026-10-28 完成（42/48；6 项失败）。Core metrics 显示部分失败帧 Bullet update、
    post callbacks 与 body capture 都很重；post callbacks 包含 resolved-contact 分发，尚不能据此定位到
    单一子系统。下一步分开计量 resolved-contact/manifold 扫描与有监听器的交付量，并重跑隔离用例；
    不改驱动/悬挂参数或拓扑，不把同批测试中减少两项失败宣称为修复。当前 `physicsTestGroup` 只被
    Gradle 写入系统属性，活动测试源码未读取；不能据此隔离用例。GameTest 通过前不推进验收门或实机暂停点。

## 履带地形迁移状态（2026-10-28 更新）

### 已完成并自测通过

- Core `TerrainRegionSnapshot` / `TerrainRevisionToken` / `snapshotRegion()` / `requestRegion()`。
  当前 Core 全量 159 项通过，`PhysicsChunkSection` 记录 applied snapshot。
- `TrackedTerrainSampler` 改为纯数据变换，不再读 `Level`；窗口由 `TerrainEnvelope` 决定。
- `TrackedTerrainAutoSampler` 改为每 Level 独立队列 + 每 Level 单线程构建器；
  每车同时只有一个在飞构建；每 tick 续期区域请求。
- `TrackedTerrainMeshProxyRuntime.install` 增加 request/generation 校验，先加后删。
- `TrackedTerrainWaitController`：等待期 dynamic 保持 dynamic，只做 factor lock。
- `VehicleBody` tracked 分支不再自行 capture/kinematic，等待权归 runtime。
- planner 输出六面外壳，按区间包含消除内部面，保留桥面底面。
- SBW JVM/原生全量单测 176 项通过（0 failures / 0 errors）。

### 未完成

- 前一轮完整 GameTest 为 40/48，已由最新完整重跑 supersede。当前 48 项 **42 通过、6 失败**：
  `thebeltdrivesunderabridgedeck`、`aleftsteeryawstheotherway`、
  `aoneblockstepclimbsonthebulletramp`、`contactmotiondrivesthehullforward`、
  `steeringyawsthehull`、`coastremovestheconveyortargetinsteadofdriving`。
  reverse drive 和静置双侧承载在这次批次通过，但因前后批次波动不能据此标注为稳定修复。GameTest
  仍失败，不得外推为车辆验收通过。
- 最新带完整 runtime 诊断的失败中，ramp、forward drive 均为 terrain `ready=true`、`missing=null`；
  forward 目标接触回调有效且有法向冲量，但 hull 位移未达断言；ramp 仍零接触。两个 yaw、桥下和
  coast 的具体结果见 §4；本轮静置双侧承载通过。结果按各自断言解释，不能合并成一个故障原因。
- Core 1.4.3 分项计时：ramp 失败帧 worker 805.0 ms，其中 Bullet update 666.7 ms、post callback
  193.5 ms、末帧 capture 87.6 ms；4,115 body / 1,104 terrain。forward drive 失败帧 worker
  687.7 ms，其中 Bullet update 559.4 ms、post callback 136.0 ms、capture 118.0 ms；1,205 body /
  341 terrain。其 terrain 请求 7 次合并在一个成功请求帧的数据里、6 次帧请求被丢弃；此测试全在
  `defaultBatch:1 (48 tests)` 同批运行。post callback 包含 manifold resolved-contact dispatch 和所有 post-step
  工作，不能等同纯 `pcoList` loop，也不证明高负载造成验收失败。下一步拆分 manifold scan / subscribed
  delivery 与剩余 callback，再用有效隔离测试重跑；在证据前不改车辆参数或机构拓扑。
- 99A `Step=3.0` 仍未获用户确认；JSON hash 变化不等于参数批准。
- 未做实机暂停点 A/B/C，S3.1/S3.2/S4 与 99A Approved 均未达成。
