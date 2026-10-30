---
name: Tracked Terrain Assist
description: Design, implement, test, or diagnose tracked-vehicle terrain contact using Minecraft voxel AABBs and optional SBW step assistance, without core height fields or probe fallbacks.
---

# 履带地形与可选台阶辅助技能

开始前阅读：

1. `docs/physics/CONSTRAINTS.md`
2. `docs/physics/ARCHITECTURE.md`
3. `docs/physics/MCSP_BULLET_MAPPING.md`
4. `docs/physics/TEST_MATRIX.md`
5. `docs/physics/ACCEPTANCE_LIMITS.md`
6. `KaguyaPyCore:docs/TERRAIN_COLLISION.md`
7. `KaguyaPyCore:docs/CORE_SCOPE.md`

跨仓库引用写成 `仓库名:仓库内路径`。不要写本机绝对路径或相邻检出相对路径。

## 硬边界

- 基础地形是 Minecraft 方块 `VoxelShape` 转成的 AABB，包括半砖、楼梯和多 AABB 形状。
- 不使用 `LocalHeightField`、`TerrainSupportField`、`HEIGHT_FIELD`、`SUPPORT_PAIR`。
- 不使用探针 sweep 承载、探针 miss 后 fallback、固定楔面、按车长坡长、人工抬升或 `setPos`。
- 台阶辅助只在 SBW 实现，不进入 KaguyaPyCore。
- 辅助必须明确接触所有权，不能与原始地形在同一位置重复提供向上支撑。
- 两格墙和连续高墙必须拒绝。
- 未加载区块按地形缺失处理，不生成辅助坡。

## 工作次序

1. 先关闭台阶辅助，验证基础 AABB 碰撞。
2. 测试完整方块、半砖、楼梯、AABB 阶梯坡、两格墙、坑、桥面/桥下和 section 边界。
   光滑连续斜坡不是方块 AABB，不属于门禁。
3. 再单独实现 `TrackedTerrainContactPolicy`、`TrackedStepAssist` 和
   `TrackedObstacleClassifier`。
4. 只处理明确允许的一格台阶，并记录辅助高度门限、坡长/速度门限和实际效果。
   当前实现常量：单级 1.0 m、累计 2.0 m、run 0.25..2.5 m、速度 3.0..10.5 m/s；
   这些常量尚未经用户冻结，不得写成 `auto_passed`。
5. 若需要修改接触，先证明 Bullet 回调确实在求解前；否则改用明确的辅助碰撞几何或
   可行驶表面 body。两种方案都必须声明接触所有权，不能与原始 AABB 重复承载。
6. 不使用 `setNormalWorldOnB` 作为未经验证的主方案。
7. 测试辅助开启/关闭、真实正向爬越、连续台阶、两格墙拒绝、悬空、重复辅助、穿透和挖除失效。
8. 辅助期间记录真实履带接触、法向载荷、悬挂 travel 和车体速度；不得把辅助效果写成
   悬挂行程或关节反力。

## `BridgesSteps` 参考边界

- `BridgesSteps` 是 MCSP 的行为参考，不是可直接移植的算法。
- 已恢复的 Java 行为：`PhysRamps` 的台阶/坡长/速度门限，`PhysGearSpec.bridgesSteps`，
  `PhysGear.hold/bridgeSteps` 登记，`PhysScene.setBridgesSteps/readSlopeState/seedSlope`，
  以及 `Scene::step → update_slopes` 和 `modify_solver_contacts → slope_normal_y`。
- native 坡面几何、过滤规则、接触时序和与基础地形的并行承载不能唯一恢复；不得把
  “局部 HeightField”写成已复现设计。
- 不得把 MCSP 的 `slope_normal_y` 或 `modify_solver_contacts` 当作 Bullet API 契约，
  也不得直接搬用 Leopard 2A6 / Typhoon 的未冻结参数。
- SBW 必须独立验证 Bullet 等价方案；接触响应方式仍是 S3.2 的待决项。

## 必须记录

接触来源、接触点、法线、载荷、台阶高度、`rampGate` 的 rise/run/speed、地形 revision、
物理子步耗时。不得把 `liftImpulse` 写成当前生产路径的辅助方式。
状态写入 `docs/physics/STATUS.md` 和 `docs/physics/records/events.jsonl`。未冻结限值不得
写成通过。实机暂停点 C 必须等待用户确认。
