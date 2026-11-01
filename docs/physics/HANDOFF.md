# ZTZ-99A 履带物理交接

更新时间：2026-10-31。当前交接入口，不是验收通过证明。**本节取代下文第 1~3 节中与之冲突的描述；下文保留为 2026-10-30 的历史。**

2026-11-01 追加（动态重建与平地行驶联动修复，自动结果部分通过，悬挂波动仍需评估）：
- `TrackedDynamicBelt.repartition` 不再把同一份外侧销点残差加到每条新段。拆分/移动由固定外销位置和材料段长解析求解内部销，且受现有最大销弯角约束；每个新刚性段按自身材料长度检查。单段合并保持材料长度，只允许本次两外销实测闭合残差用于表示端点误差。没有新增固定几何容差、冷却时间或参数改动。
- `SplitDegrees` 以下的局部射线折角不再移动/保持切点；活动切点仍必须达到原有拆分阈值，真正合并仍要求原 `MergeDegrees` 和 `HoldSeconds`。wheel-lock、悬挂参数和驱动映射未改。
- T0 `TrackedBeltGeometryTest` 通过。T1 `TrackedEnvelopeBeltNativeTest` 平地通过；原坡面门通过：爬升 `1.01 m`，拆 2、合 2、最多 3 段、结束 1 段、0 flap，最大 span pin gap 约 `3.3 mm`。原验收断言和数据阈值未改。
- T3 focused 平地直行场景通过：稳定巡航期间两侧保持原始底部整段拓扑，split/move/merge 均为 0；俯仰约 `-0.35°..0.37°`。该诊断窗内起步/载荷转移阶段轮行程变化较大；直接按物理线程轮心采样后，巡航末段负重轮约 `65..82 mm`。没有冻结的整车动态悬挂波动限值，因此该数值是观察结果，不标作通过阈值；仍需结合视频/姿态表现进一步检查。
- 同次 5 项 focused GameTest 中左右转向仍失败，累计偏航约 `-0.0024 rad` / `+0.0027 rad`，原 `0.1 rad` 断言未改。此失败与本轮履带重建改动分开记录，不声称已修复差速手感。
- 本条 supersede 下面“拆合首轮实现状态”中的坡面失败结果；旧事件保留为历史。未提交、未推送。

2026-11-01 追加（履带拆合几何修复首轮，未通过）：`TrackedDynamicBelt` 移除了折点仅因长期“不需要”就强制合并的路径；合并必须同时满足折角保持小于既有 `MergeDegrees`、持续时间达到既有 `HoldSeconds`，且材料长度与外端点弦长相容。拆分、移动和合并共用该几何表示检查；拆合的可表示性最多使用所选片段外侧现有销点的实测残差，不引入固定长度容差。只有相关地形折点不再位于待合并跨度内才允许合并。未修改 `MaxPieces`、`MergeDegrees`、`HoldSeconds`、验收断言或 wheel-lock 生产实现。

- T0 `TrackedBeltGeometryTest`：直且长度相容、达到 hold 的情况通过；弯曲 pin、长度不匹配、保持时间不足拒绝合并；只有实测外侧 pin 残差足够时才允许表示误差。T1 平地生产机构仍保持 1 个底部段。
- T1 `TrackedEnvelopeBeltNativeTest.theRampSplitsAndMergesTheBottom` 未通过原断言：最后一次串行运行仍观测到最多 8 段、结束 5 段、1 次 flap；原夹具 `maxPieces <= 4`、`flaps == 0` 和坡后回并要求均未放宽。仅按测得外侧销点残差计算的重建预算曾使段数较先前严格零残差检查下降，但现有结果仍不合格。下一步须定位合并后再次出现折点的原因，并改进保长重建，使坡后可在无活跃地形折点且销点几何相容时回到一段；随后复跑 T0/T1。
- 首轮 wheel-lock frame/限位实验已撤回；未改变生产 wheel-lock、张紧参数、悬挂、差速或接触规则。代码处于用户已有的脏工作树中，未提交、未推送。

2026-11-01 追加（替代上条过期的复现状态）：聚焦模板复制到 `superbwarfare_physics`，别名类必须使用可扫描的 `@GameTestHolder("superbwarfare")`，再以 `-PgameTestNamespaces=superbwarfare_physics` 过滤模板 namespace；这次实际运行了 forward、parked、right steer、left steer 各 1 项，不是 0 tests。fixture 在完整带体 footprint 下铺真实 AABB 地面并于结构中心创建；裸 runtime 通过 chassis pre-step hook 每个物理子步执行 `step()`，左右偏航通过 post-physics hook 逐子步累计。停车在当前复现中通过原 5 mm/30 s 断言，前进驱动也通过。左右转向仍失败：目标为 `+15.6/-9.03 m/s`（原地测试为 `+15.6/-15.6`），但 100 tick 累计偏航仅约 `0.002–0.003 rad`，现有断言 `0.1 rad` 保持不变；两侧真实履带接触与切向牵引均有记录。尝试禁用包覆轮地形碰撞并未提高偏航，且会改变载荷路径，已撤回。当前根因仍未关闭，不调摩擦、差速或断言来掩盖；未实机确认。

## 000. 2026-10-31 贴轮包络履带投入生产（本节为最新，取代第 00 节的锚点整带）

- **拓扑**：`Track.Topology = EnvelopeBelt`。`TrackedEnvelopeBeltCompiler` 按 `BeltOrder` 以 r + t/2 求外公切线包络；负重轮取静载下沉后的位置（`CompiledEnvelopeBelt.wheelCenters`，车轮也按此位置生成和重置），导/主动轮取绑定位置。锚点只用来算偏差：99A 两侧 6 个锚点离包络 57~158 mm（编译期警告）。包络静止长度 18.25 m，可见 79 节。
- **刚体**（`TrackedDynamicBelt`，SBW 自有，经 `BeltWorld` 记入回滚账本，不用核心 TrackRun）：
  - 前斜段 1.26 m、底部 6.01 m（初始一整块）、后斜段 1.29 m、顶部回程 7.66 m；每段 = 0.78 × 0.098 m 盒 + 两端半径 0.049 m 横向圆柱销头，碰撞余量 0.02 m。
  - 关节：前斜段—导动轮销；相邻段直接销接（±40°）；顶部—主动轮销、在导动轮处沿自身滑动；后斜段在主动轮处沿自身滑动（±0.3 m），由 `Envelope.Tension` 关节弹簧拉紧（20 kN、8 kN·s/m）；负重轮锁单向法向，允许离开履带；沿履带纵向完全自由，由悬挂 mount 约束位置；横向与角向冗余自由度释放。动态拆合后 wheel-lock 两端 frame 以当前轮心重新对齐。
  - 包角：导动轮、主动轮刚体的复合子形状（半节距弦），注册为履带接触驱动面。
- **为什么不用导动轮张紧器**：刚性斜段 + 刚性底部 + 固定长度，悬挂下沉 20 mm 就要求导动轮外移约 30 mm，加上导动轮在安装锁上转动 2.4°，静止时就顶到 ±0.10 m 限位；坡上两端限位都会撞到，履带变成刚性连杆。履带不循环，下半圈长度必须能变，所以改为主动轮处滑动 + 关节弹簧张力（相当于顶部回程下垂吸收长度）。导动轮恢复固定。
- **动态分段**：每游戏帧一次（`FRAME_PRE`，不在 Bullet 更新内），沿底部按节距打射线（只测本车地形网格）。折点判定：凸起处要求履带贴着（≤ 0.15 m）；凹处要求两侧 1.5 m 内都贴地。拆分阈值 3°；已有切点随折点移动（履带不循环，地形折点沿底部移动）；伸直 < 1° 且保持 0.5 s 后合并，或 2 s 无折点需要时合并。所有位姿、销点和轮脚都由底部静止弧长推出，不累积漂移；新段继承原材料的速度场。
- **接触求解修正**：MCSP 反编译代码显示 drive slot/response 与 friction/lateral 参数附着在被驱动 carrier/part 上，Java `setDriveSpeed` 写入 native drive state；无法恢复 Rust 接触实现。SBW 的 Bullet 实测发现 Core 原生产 solver 未启用 `Use2Directions`，所以 `contactMotion2` 没有摩擦 solver row。现启用两条切向 friction rows；接触回调仍逐点设置正交摩擦基、`contactMotion1/2` 和 initialized flag，并在新接触与持久接触回调中更新。新增原生回归：两方向模式第二切向目标实际得到 0.5 m/s，单方向模式没有响应。接触目标由 Bullet 摩擦冲量限制，不直接施加车体净力。
- **其他改动**：不再使用逐关节 `overrideIterations`；KaguyaPyCore 新增世界级 `solverIterations.request/release`，99A 请求 60 次，接触与关节使用同一全局迭代数，避免关节在摩擦停止后继续单独修正。地形网格余量 0.02 m 并下移 0.02 m；`TrackedSuspensionSprings` 将普通悬挂弹簧和平均阻尼放入安装关节，只有 bump-stop/不对称残差显式施力；零残差不唤醒刚体，地形网格替换会唤醒整套机构；`/sbwtest bt status` 显示各侧底部段数和最大销点间隙；转向 GameTest 改为逐 tick 累计未折返的偏航。
- **实测**（`TrackedEnvelopeBeltNativeTest`，动态轮 + 生产悬挂 + 生产包角形状，单侧平面台架，牵引力上限 0.9 μ）：
  | 情况 | 结果 |
  |---|---|
  | 平地 10 s（2 子步） | 底部始终 1 段，静止下沉 21 mm，销点间隙 0.3 mm，行驶间隙 0.6 mm |
  | 1:3 坡上 1 m 台阶（2 子步、60 次全局迭代、生产配置） | 完整爬升 1.13 m；拆 2、合 2、移动 42 次；最多 3 段；坡后合回 1 段；反复拆合 0；段间销点间隙约 6.4 mm；下沉约 0 mm；wheel-lock 法向最大违规约 5.18 mm（joint `wheel1_lock`），总体销/限位诊断约 8.1 mm；该值仍是待修的物理残差，不是通过限值 |
  | 同上，1 子步 | 段间间隙 30 mm、下沉 65 mm，所以生产仍申请 2 子步 |
  | 同上，地形余量 0.001 m | 段间间隙 24 mm、下沉 17 mm |
  wheel-lock 收敛诊断（非验收阈值）：`60 iterations/2 substeps` 最大法向限位残差 5.18 mm；`120/2` 为 5.07 mm；`60/4` 为 2.57 mm；`120/4` 为 2.65 mm。增加迭代数几乎不改变残差，增加子步降低残差，说明剩余主要受单步时间/活动约束变化影响。该违规从旧报告约 49 mm 降低，但未归零，仍不得宣称通过。可见履带使用物理中心线重采样绘制，不隐藏结果。
- **验证**：KaguyaPyCore 163 项通过；SBW 全量 JVM 227 项通过。新增 `TrackedContactFrictionNativeTest` 证明 `Use2Directions` 下第二切向目标真实进入 Bullet friction row（0.5 m/s），单方向对照无响应。包络原生测试：平地 1 段、坡面爬升、动态拆合、停放休眠/唤醒通过；`TrackedBeltContactNativeTest` 接触回归通过。最新 GameTest 42/48；`contactmotiondrivesthehullforward` 本次通过，仍失败：停车漂移、左右转向、coast、坑洞、AABB 阶梯坡（6 项中包含两项左右转向）。服务端出现 1 次 `Can't keep up`；未实机确认。
- **下一步**：追查左右转向中有切向牵引但偏航响应极低的机构/接触力矩来源，同时保留已验证的聚焦 GameTest 入口；复跑停车稳定性；处理剩余 wheel-lock 法向残差、coast、坑洞和阶梯坡 GameTest；测量 60 次全局迭代对多车服务器成本；实机暂停点 A。

## 00. 2026-10-31 投入生产（锚点整带，已被第 000 节取代）

- **生产拓扑已换成锚点整带**（`Track.Topology = AnchorChain`）。每侧 6 个核心 TrackRun、18 个刚体：前上、前下、底部 13 单元（12 个铰链）、后下、后上、顶部 1 段。锚点处的销轴固定到所属轮（每个锚点两侧各一个关节，共 12 个），负重轮 2~5 对其下方单元只锁法向（4 个）。
- **参数**（用户确认先用，不冻结）：单元 2 节距、相对静止角 ±40°、顶部 1 段、底部碰撞厚 0.3 m（向带内加厚，接地面不变）、单元惯量 ×16、`SolverSubsteps` 2、`RunMassBudgetKg` 5688/侧（总重 55 t 不变，车体 31,224 kg）。
- **核心库（未提交）**：`PhysicsLevel.solverSubsteps.request/release` 与 `stepSeconds`；子步内 N 次 Bullet 更新，`tickCount` 仍 60 Hz。SBW 中所有按 `1/TPS` 写死步长的回调已改用 `stepSeconds`。核心 161 项测试通过，已发布到本地 Maven。
- **单一装配路径**：`CompiledTrackSide.physicalRuns`。旧相邻轮对运行段自动转换成同一结构，`WheelPairRuns` 仍可用。
- **渲染**：运行时在物理线程把链节销点（相邻两端取平均）写进渲染快照 `beltY/beltZ`，客户端沿这条折线排链节；可见链节数按整带静止长度 18.74 m 取 81 节。
- **验证**：
  - `TrackedChainProductionNativeTest`：用生产的 `physicalRuns` + 核心 `TrackRunAssembly` + `TrackedJointFactory` + 生产悬挂，静置下沉 34 mm、负重轮静压 33~56 mm、1:3 坡完整爬升 1.00 m、无刚体中心穿地。
  - JVM 215 项通过；GameTest 44/48（失败集合每次不同：滑行、桥下驱动、左转向、接触驱动目标计数），都属于 S4 驱动/转向，未调。
- **注意**：原生测试空间没有 Spark 的同车过滤，必须把链节掩码收窄到地形，否则加厚的底部单元会撞本车轮把车体顶飞；生产里由 `PhysicsWorld.needsCollision` 过滤。
- **下一步**：S4 驱动与原地转向（6 m 摩擦履带的转向阻力）、张紧器接入 `followL/R`、坡角处链节转角的可见圆滑、实机暂停点 A。

## 0. 2026-10-31 本轮结果

### 环境
- 核心库 `KaguyaPyCore` 已在 `f58ba77`（`1.4.3-physics-metrics`，Libbulletjme 23.2.0），已发布到本地 Maven，SBW 可编译。先前"版本不配套"的问题已解除。
- 基线：JVM 测试 **202 项全部通过**。GameTest 48 项中 **3 项失败**（`coast…`、`onesidecarriestheterrainload`、`aoneblockstepclimbsonthebulletramp`），失败集合随负载变化（9 → 2 → 4 → 3），不是稳定结果。没有实机确认。

### 已落实
| 项 | 内容 |
|---|---|
| 模型 | 新模型和贴图已覆盖游戏资源。前端 `followL/R` 导动轮，后端 `leadL/R` 主动轮。`PointL/R` 锚点标记不渲染。详见 `docs/vehicles/ztz_99a/README.md` |
| 轮位 | 轮体按履带平面居中，骨骼偏移保留为渲染偏移 |
| 锚点 | `EnvelopeAnchorBone` 读取 6 个锚点，各归属最近的轮；整带长 18.74 m，底部跨度 6.0 m |
| 车体 | 5 个物理盒，编译期用 SAT 确认不压住轮和运行段 |
| 接触 | 运行段同车内只接触地形网格；轮的同车接触掩码清空 |
| 地形窗口 | 半径含 3 m 坡长和 1 m 前瞻，2 m 才重建，重建不冻结车。规划器加平面格索引，平地 285→18 ms |
| 建坡 | 与轮/运行段位置脱钩；60° 内建坡带滞回；较高包络加外角锥面。左右侧面仍是墙 |
| 调试框 | 车体局部坐标 + 同一份插值变换；补画炮塔。yaw/pitch/roll 插值修复 |

### 原型发现（`TrackedBeltChainNativeTest`，T1，运动学轮，不是整车）
- 平地：单顶段，销轴间隙 ≤0.6 mm，抖动 0.001 mm/步（2、4 子步为 0.000）。
- 顶部切 3 段会被重力拉伸（1 子步 24 mm，4 子步 3.9 mm），默认改 1 段。
- 底部端销固定在轮上优于滑动端（44~55 mm 对 130~250 mm）。
- 被动张紧器可用（预紧 20 kN、阻尼 8 kN·s/m、限位 ±0.10 m，行程 −58~+50 mm），但不是坡顶间隙的限制因素。
- 增加求解迭代次数使坡顶更差。
- **未解决**：1:3 坡顶仍有 44~55 mm 间隙，底部单元弯折瞬时到 60°~69°（限位 40°），说明两端固定销加路由轮法向锁在坡顶过约束。
- 生产尚未接入锚点整带；生产仍是相邻轮对 8 段运行段。

### 坡顶对比（2026-10-31 续，`TrackedBeltChainNativeTest`）
- 运动学轮脚本下的坡顶间隙是**夹具假象**：脚本让负重轮和导/驱动轮按不同规则移动，不守恒链长。销轴加 CFM 柔度完全无效果，端销加纵向行程更差。
- 改用**动态轮 + 生产悬挂行（`PhysicsSuspensionRow`）+ 自由俯仰车体**后（链单元无摩擦，因独立空间没有传送带接触目标），结果由子步频率决定：

| 每 1/60 s 子步 | 轮质量 | 单元惯量 | 爬升（1 m） | 最大销轴间隙 | 最大下沉 |
|---|---|---|---|---|---|
| 1（当前生产） | 450/320 | ×64 | 0.97 m | 326 mm | 328 mm |
| 2 | 450/320 | ×4 | 1.00 m | 65 mm | 52 mm |
| 4 | 900/400 | ×1 | 1.00 m | 51 mm | 27 mm |
| 4 | 900/400 | ×16 | 1.00 m | 28 mm | 11 mm |
| 4 | 450/320 | ×16 | 1.00 m | 24 mm | 11 mm |
| 8 | 900/400 | ×1 | 1.00 m | 20 mm | 5 mm |

- 结论：锚点整带在 60 Hz 单步下承载 55 t 不成立；需要核心库提供通用的"每物理步内部子步数"设置（至少 4，即 240 Hz），并对链单元做惯量放大（不改静力）。增加约束迭代次数无帮助。
- 4 子步意味着整个维度的 Bullet 计算量约 ×4，这是需要你决定的成本；在你同意前不改核心库。

### 候选参数（2026-10-31 用户确认先按现值使用，未冻结）
| 参数 | 候选 | 依据 |
|---|---|---|
| 底部单元长度 | 2 个节距（0.466 m，13 段） | `BeltChainPolicy.unitPitches` |
| 单销弯角上限 | 相对静止角 ±40° | `maxBendDegrees`；坡顶实测瞬时超限，需先解决过约束 |
| 顶部分段 | 1 段 | 3 段会被拉伸 |
| 张紧器 | 预紧 20 kN / 阻尼 8 kN·s/m / 行程 ±0.10 m | 原型行程在限位内 |
| 车体物理盒 | `ztz_99a.json` 的 `HullCollision` 5 个盒 | 由模型包围盒量得，未与实机比对 |
| 负重轮质量 | 900 kg，导/驱动轮 400 kg | 2026-10-31 用户确认 900/400 与 450/320 均可，保留 900/400 |

### 下一步
1. 解决坡顶过约束：候选是给销轴加柔度（约束软化）、让端销带小行程，或让底部端单元只与轮做法向锁，需在原型里逐个比较。
2. 原型过关后再接入生产：替换相邻轮对运行段，接触驱动沿用，渲染改读同一条折线。
3. 张紧器接入 `followL/R`（目前仍是固定安装），再回头复核 GameTest。
4. 实机暂停点 A 之前不要把 99A 标成通过。

### 两处不属于物理的本地修复（未提交；2026-10-31 用户同意保留）
- `ModLootModifier.kt`：`getRandomItems` 改回 `getRandomItemsRaw`，否则战利品修改器对同一张表递归，GameTest 服务端栈溢出。
- `TurretWreckEntity.kt`：残骸战利品用 `ResourceLocation.tryParse`，非法载具名（`GunMu`）不再让服务端崩溃。

---

## 1. 提交和工作区

- 分支：`Physic-Update`。代码基线：`fe6f71f02139d6cbb28d7357e2fd6358a3f08c26`。
- 用户已要求全部文档入库，撤销此前不提交 MCSP 文档的要求。本次包括历史归档、事件账本、Skills 和模型/贴图工作副本。
- `TurretWreckEntity.kt`、`ModLootModifier.kt`、`tools/client-test/` 脚本仍有独立未提交修改，不要清理。脚本说明文件随文档提交，不代表脚本实现已提交。
- 本次没有修改 KaguyaPyCore，也没有推送远端。

## 2. 用户要求

- 99A 正常前进、倒车、转向、制动，真实坡面承载并稳定停住。
- 主动轮和 wheel0 导动轮固定，每侧仅六个负重轮独立竖直悬挂。
- 前、后、车底允许纵向建坡，左右侧面保持墙，不随油门切换。
- 相邻坡面统一消解交叉，不跨空气填坑，不合并桥面/桥下。
- 不强制固定八段；研究可弯子段，但不做逐可见鞋刚体，不由渲染写回物理。
- 不人工抬升、不写车体速度伪造驱动；生产运行段承载未证明前保留轮地碰撞。
- 无需乘车即可显示已加载载具真实碰撞，不加载区块、不改变物理。

## 3. 当前接线和未完成项

| 部分 | 已接线 | 未证明/未实现 |
|---|---|---|
| 机构 | 每侧八个轮端刚体、八个相邻轮对运行段、六行悬挂 | 每跨度仍是一具刚性盒，自适应可弯子段未实现 |
| 地形 | VoxelShape AABB 转每车 MeshCollisionShape，异步构建、revision 校验、物理线程安装 | 不是原生 Heightfield；几何生成不等于整车承载通过 |
| 坡面 | 前后/车底门控、真实足迹覆盖、坡长不超过 Step；同层重叠尝试较低包络 | 实现是 0.25 m 小格中心选择整片，不是精确交线裁剪；错位、斜交、裂隙仍需测试 |
| 车底放块 | 已发布轮体/运行段 GearProbe 内带拒绝辅助坡面，保留立面 | 不是精确 OBB 测试；未解决真实方块插入刚体的初始重叠，也无安装时最终姿态复核 |
| 驱动 | run-terrain 接触切向目标，DRIVE/BRAKE/COAST 分离 | 99A 无法正常移动仍未关闭；被动滚阻未实现 |
| 稳定性 | 已活动刚体不再每子步强制 activate；轮休眠阈值 0→0.02 | 没有生产静置通过证据；持续弹簧力仍会唤醒睡眠刚体 |
| 渲染 | 轮圆包络和测得带速驱动相位，物理与渲染分离 | 未消费两轮之间真实接触折点 |
| 调试 | 已安装地形、黄车体 OBB、白轮圆柱、绿运行段盒 | 当前仅扫描当前维度已加载履带车，不是所有载具类型；没有逐接触点/穿深图层 |

调试：权限 2，`/sbwtest bt show|hide|status`，按键/手柄调用同一订阅。无需乘车，机构形状首次开启后需等下一次物理发布。橙色为坡面，青色平面、红色立面、紫色朝下面。未接入 Bullet 的履带车只在 status 标注，不能用伤害盒冒充。

### 需要继续审查的风险

- 目前按高度差辨层，不是来源/连通性辨层，近距离不同层可能错误耦合。
- 小格中心胜出不保证全片无交叉、接缝无洞；不能宣称精确裁剪完成。
- 探针位姿哈希按 0.25 m 量化：同桶可能产生不同拒绝结果，不同桶也可能是相同几何，需要改进几何身份策略。
- 原始顶面/立面与辅助坡面的接触所有权仍需核实。
- 调试存在全局订阅门控、按子步发布的开销；跨维度无关车也可能计算。
- 运行段调试在缺少快照时有绑定姿态回退，那一帧不能作为实际位姿证据。

## 4. 测试事实

最近一次执行并通过（23 项，同时编译主代码和测试源码）：

```bash
./gradlew test --offline --no-daemon --console=plain \
  --tests com.atsuishio.superbwarfare.physics.tracked.TrackedTerrainMeshPlannerTest \
  --tests com.atsuishio.superbwarfare.physics.tracked.TrackedCollisionDebugTest
```

首次编译暴露 CollisionPart 可见性问题，已修正。之前启动超时不是通过。
本轮没有重跑原生坡面回归、生产驱动/爬坡 GameTest、全量单测或客户端烟测。
历史单轮坡面 T1 仅证明夹具；历史完整 GameTest 42/48 仍有六项失败，不是当前提交成绩。
没有用户实机 A/B/C/D 通过确认，没有新车型批准。

## 5. 用户正在修改模型

- 工作模型：`docs/vehicles/ztz_99a/ztz_99a.geo.json`
- 工作贴图：`docs/vehicles/ztz_99a/ztz_99a.png`
- 操作说明：`docs/vehicles/ztz_99a/README.md`

**模型副本已与游戏资源不同，不要重新导出覆盖用户修改。** 游戏仍加载：

- `src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json`
- `src/main/resources/assets/superbwarfare/textures/bedrock/vehicle/ztz_99a.png`
- 机构：`src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json`

本次交接 SHA-256：

| 文件 | SHA-256 |
|---|---|
| 游戏模型 | `5809b45cc3bd3121ce439aaa6cab5e7113623f18ef30d849692205c90f1df369` |
| 机构定义 | `f375e73ba3376eb405523db05cdf4219fac5c3235575ed3226db042387c81e72` |
| 文档模型副本 | `faab4fddc8be948d5caf40d79bce46c1ebeb0fc1d7c29f0467e925e46eca8f51` |

用户继续编辑后副本哈希会变；旧事件中的其他哈希是历史记录，不是当前资源哈希。
只在用户要求后回写游戏资源；先比较骨骼名、pivot、层级、模板及 UV，再跑机构审计、更新哈希，旧成绩不能自动继承。

## 6. 下一步

1. 保留模型修改，用新线框复现车底放块、坡顶不折弯、无法移动。
2. 补逐刚体接触点、法线、穿深、法向/切向冲量、悬挂行程及 mesh 替换次数，区分轮体拖曳、车腹托底、无 run 接触和输入失效。
3. 把坡面中心采样近似升级为带来源层次的交线裁剪/拼接，验证全域无交叉、无洞、无重复承载，再处理实时安装侵入。
4. 在独立 B2 原型验证可弯子段、质量/动量迁移、滞回、约束拆装及拓扑快照；不直接在生产每子步重建。
5. 分别修驱动、制动耗散和稳定性，不先调摩擦/阻尼掩盖问题。
6. T0/T1→生产 GameTest→客户端图层核对→用户实机确认，不能以橙色坡面出现收尾。

## 7. 文件入口

以下路径均相对 `src/main/kotlin/com/atsuishio/superbwarfare/`：

- `physics/tracked/TrackedVehicleRuntime.kt`：运行机构、悬挂、接触驱动、地形等待、快照。
- `physics/tracked/TrackedTerrainMeshPlanner.kt`：候选坡面和 mesh；`TrackedTerrainMeshProxyRuntime.kt`：实际安装/替换。
- `physics/tracked/TrackedTerrainSampler.kt`、`TrackedTerrainAutoSampler.kt`：缓存快照、异步构建、哈希。
- `physics/tracked/TrackedRunGeometry.kt`、`TrackedRunSpecs.kt`：单跨度盒与端约束。
- `physics/VehiclePhysics.kt`、`VehicleBody.kt`：实体输入、生命周期、子步施力。
- `physics/tracked/TrackedTerrainDebugService.kt`、`TrackedCollisionDebug.kt`、`client/debug/`：调试。
- `client/renderer/entity/TrackedBeltRenderer.kt`、`physics/tracked/render/McspTrackPath.kt`：可见履带。
- `gametest/TrackedTerrainAndDriveGameTests.kt`：生产测试。

规范以 `CONSTRAINTS.md`、`IMPLEMENTATION_PLAN.md`、`ACCEPTANCE_LIMITS.md` 为准；接线见 `CURRENT_WIRING.md`，参考证据见 `REFERENCE_IMPLEMENTATIONS.md`、`MCSP_BULLET_MAPPING.md`。
`docs/VEHICLE_PHYSICS_HANDOFF.md` 是历史归档，不得照搬其探针/整侧悬挂方案。
