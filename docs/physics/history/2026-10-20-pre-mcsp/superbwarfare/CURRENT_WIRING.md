# 当前运行接线清单

更新时间：2026-10-19  
记录类型：只读盘点，不是目标架构，也不是测试通过证明

本文列出 2026-10-19 在 `Physic-Update` 上实际能找到的履带支撑、爬坡和动画接线。
目标禁令见 [`CONSTRAINTS.md`](CONSTRAINTS.md)。符号改名或移动后，本清单失效，必须重查调用点，
不能只改行号。

## 1. 新机构没有进入游戏生命周期

| 符号 | 位置 | 实际调用 |
|---|---|---|
| `TrackedRig` | SuperbWarfare `physics/tracked/TrackedRig.kt` | 只在 `TrackedRigStaticTest` 中构造 |
| `TrackedVehicleCompiler` | SuperbWarfare `physics/tracked/TrackedVehicleCompiler.kt` | 被注册表和编译测试调用，不被 `VehiclePhysics` / `VehicleBody` 调用 |
| `TrackedVehicleRegistry` | SuperbWarfare `physics/tracked/TrackedVehicleRegistry.kt` | 无游戏运行时调用点 |

因此它们是主源码中的候选实现，不是“只存在于测试源码”，也不是已接线。

## 2. 仍在提供履带地面支撑或爬坡辅助的路径

这些路径与新架构冲突。文档不得把它们写成已经退役。

| 作用 | 仓库和符号 | 已核对的事实 |
|---|---|---|
| 固定坡长楔面规划 | KaguyaPyCore `TerrainSlopePlanner` | `RUN_BLOCKS = 6`。`TerrainShapePlan.build` 调用 `TerrainSlopePlanner.build` |
| 楔面刚体 | KaguyaPyCore `TerrainSlopeBodies` | `PhysicsChunkManager.replaceBody` 创建，碰撞组为 `CollisionGroups.SLOPE` |
| 可行驶掩码包含楔面 | KaguyaPyCore `CollisionGroups` | `DRIVABLE = TERRAIN or SLOPE` |
| 履带探针承载 | SuperbWarfare `VehicleBody.applyTrackSuspension` | 使用 `ProbeSuspensionUnit`。`VehiclePrediction.applyTrackSuspension` 复用同一方法 |
| 探针 miss 后的高度场 fallback | SuperbWarfare `VehicleBody.trackGroundFallback` | 支撑高度取 raw column，法线取平滑采样。这正是新设计禁止的“平滑法线配原始高度” |
| hull 接触法线改写 | SuperbWarfare `VehicleBody` 的爬坡接触处理 | `CLIMB_RUN_BLOCKS = 3f`，并调用 `setNormalWorldOnB`。这与核心库的 `RUN_BLOCKS = 6` 不是同一个常量 |
| 旧履带相位 | SuperbWarfare `VehicleBody.updateTrackPhase` | 由车体速度计算左右相位，不是动态板链姿态 |
| 旧轨迹动画 | `VehicleModel`、`GeoVehicleRenderer` 的 `TrackAnimationProfile` | 仍是客户端履带视觉路径 |

`LocalHeightField` 仍是采样辅助。它不是 `TerrainSupportField`，也不能因为被 fallback 调用就写成唯一支撑来源。

## 3. 常规获取现状

`GroundVehicleAllowlist` 当前把以下 8 个 ID 注册为 `TRACK_PROBE`，并供创造栏/容器过滤：

`bmp_2`、`bradley`、`m_1a_2`、`plz_05`、`prism_tank`、`t_90a`、`yx_100`、`ztz_99a`。

这是当前事实。第一阶段“只开放 99A”是目标，不是当前 allowlist。目标表见
[`VEHICLE_REGISTRY.md`](VEHICLE_REGISTRY.md)。

## 4. 已编译并进入运行路径

KaguyaPyCore `TerrainSupportFieldBuilder.build` 只消费 `SectionSnapshot`。审查后的 builder 会拒绝非整格伪支撑、未证明的 section 顶部和未分类硬地面。`PhysicsChunkManager.replaceBody` 会在物理线程安装 `TerrainSupportInstallPolicy.LIVE` 生成的支撑对。缓存淘汰、区块卸载和 Level 销毁都会移除该对。请求 `SUPPORT_PAIR` 的刚体会激活对应区段；该掩码仍不在 `DRIVABLE` 里。实时下削上限是已写入的 1 格共享边，不是车长楔面，也还不是用户确认过的爬坡通过线。未分类方块没有有效支撑格，也不再被复制成结构顶面。被拥有顶面埋住的整格会合并，不是逐块刚体。section 边界上的两格立面由较高一侧生成唯一边界面；边界上的一格台阶按 margin 证据下削，未知 margin 不生成坡。`DRIVABLE` 仍只有 `TERRAIN` 与 `SLOPE`，现有车辆掩码碰不到这套物体。因此它还不是车辆的唯一支撑，也不能用来声称 P1 已经完成。

## 5. 楔面仍被非履带路径使用

拆除 `SLOPE` 之前必须单独处理这些调用，不能把“履带不再使用楔面”写成“楔面已经删除”：

- KaguyaPyCore 实时 `TerrainShapePlan.build` 不再发出楔面，`replaceBody` 因此不再创建 `SLOPE` 刚体。`TerrainSlopePlanner` 源码仍在，只被历史测试调用。
- `CollisionGroups.DRIVABLE` 仍包含 `SLOPE` 位。请求它的物体现在碰不到楔面，只碰到原始方块箱。这是非履带碰撞的影响：一格台阶不再有 6 格坡。
- `PhysicsLevel.raycastClosestTerrain` 的默认组是 `DRIVABLE`。
- SuperbWarfare `VehicleBody` 的车体、履带和轮掩码仍包含 `DRIVABLE`。
- KaguyaPyCore 旧 `PhysicsGameTests` 仍使用 `TERRAIN`。新增的泥土支撑 GameTest 使用 `SUPPORT_PAIR`。
- `/sbwdebug showterra true|false` 只显示已安装高度场，不改变表面分类，也不是验收。
- `/sbwdebug supportbody` 生成一个只碰撞 `SUPPORT_PAIR` 的刚体箱，并在客户端显示它的真实物理姿态。它不碰原始方块顶面，所以支撑缺失时会下落。它是调试夹具，不是验收证明，也不是车辆部件；实体不保存，5 分钟后自动移除。

本次只增加了高度场支撑对，没有删除上述路径。

## 6. 发布边界

SBW `d345b3be3328ea14af25047fde542b491729fd6f` 增加了 `tools/client-test/fetch-deps.sh`
和对应 README。该提交尚未推送。它属于禁止入库的测试环境获取脚本，不能因为文档已记录
就视为允许推送。
