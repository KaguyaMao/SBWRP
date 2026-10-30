# ZTZ-99A 履带物理交接

更新时间：2026-10-30。当前交接入口，不是验收通过证明。

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
