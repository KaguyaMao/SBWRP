# 旧实现归档与清理

更新时间：2026-10-23  
状态：D1 已执行；代码已归并至 `1b5c984`（不含文档）；活动源码零引用旧符号。  
清理前基线：[`history/2026-10-20-pre-mcsp/`](history/2026-10-20-pre-mcsp/)

## 1. 原则

- 先归档，再删除。
- 先确认工作树所有权，再动文件。
- 不执行 `git reset`、`git checkout`、批量格式化或递归删除。
- 归档内容不得进入 Gradle source set。
- 删除后必须在活动源码和活动测试中验证零引用。

## 2. 当前工作树风险

两个仓库在 2026-10-20 都有大量未提交修改和未跟踪文件。清理前必须逐文件分类：

| 类别 | 处理 |
|---|---|
| 用户已有修改 | 保留，不覆盖 |
| 旧履带实现 | 归档后从活动路径删除 |
| 通用 Bullet/地形实现 | 保留 |
| 新候选实现 | 归档，按新边界重建 |
| 测试夹具 | 保留通用部分，重写旧支撑部分 |
| 运行/日志/构建产物 | 不入库 |

分类结果写入本文和事件账本，不能只凭文件名猜测。

## 3. 归档布局

```text
SuperbWarfare/docs/physics/history/2026-10-20-pre-mcsp/
  superbwarfare/
    IMPLEMENTATION_PLAN.md
    CONSTRAINTS.md
    ARCHITECTURE.md
    STATUS.md
    CURRENT_WIRING.md
    DECISIONS.md
    TEST_MATRIX.md
    ACCEPTANCE_LIMITS.md
    HEIGHT_FIELD_DESIGN.md
    SKILLS.md
    MCP.md
    MCP_OPERATIONS.md
    VEHICLE_PHYSICS_PLAN.md
    VEHICLE_PHYSICS_HANDOFF.md
    GIT_STATUS.txt
    SHA256SUMS.txt

KaguyaPyCore/docs/history/2026-10-20-pre-mcsp/
  kaguayapycore/
    PHYSICS_API.md
    TRACKED_VEHICLE_SUPPORT.md
    TERRAIN_COLLISION.md
    ARCHITECTURE.md
    THREADING_AND_LIFECYCLE.md
    TESTING.md
    MCP_INTEGRATION.md
    plan.md
    GIT_STATUS.txt
    SHA256SUMS.txt
```

源码归档在删除前按同一规则补充，记录原路径、哈希、已知失败和替代方案。

## 4. KaguyaPyCore 清理清单

### 4.1 高度场

- `physics/terrain/LocalHeightField.kt`
- `physics/terrain/support/`
- `TerrainSupportField`
- `TerrainSupportFieldBuilder`
- `TerrainSectionBuild`
- `TerrainSectionRange`
- `TerrainSupportBodies`
- `GroundSupportPair`
- `SupportContactArbiter`
- `SupportDebugSample`

### 4.2 楔面和支撑对

- `TerrainSlopePlanner`
- `TerrainSlopeBodies`
- `SLOPE`
- `SUPPORT_PAIR`
- `RUN_BLOCKS`
- `TerrainShapePlan` 中与楔面/支撑对相关的逻辑

### 4.3 旧履带支撑

- `ProbeSuspensionUnit`
- `PlanarTrackSupport`
- 仅供旧履带支撑的 `PhysicsSuspensionSolver` 部分

### 4.4 保留

- `SectionSnapshot` 的普通方块快照。
- `TerrainShapePlan` 的 voxel/AABB 合并。
- `PhysicsChunkManager` 的普通地形生命周期。
- `PhysicsLevel`、任务队列、快照和通用 body/joint API。

### 4.5 注意

如果 `PhysicsSuspensionSolver` 仍被轮式车辆使用，必须先迁移到 SBW 或拆出通用数学部分，
不能按名称直接删除。

## 5. SuperbWarfare 清理清单

### 5.1 `VehicleBody.kt`

删除：

- `applyTrackSuspension`
- `trackGroundFallback`
- `onTrackHullContact`
- 旧 `stepAssist` 实现（不是 S3.2 的 `TrackedStepAssist` 目标类）
- `rawColumn`
- `CLIMB_RUN_BLOCKS`
- `RUN_BLOCKS`
- 高度场/探针 fallback
- `setNormalWorldOnB`
- 履带专用人工上抬和法线改写

保留：

- 通用车体生命周期。
- 普通车辆和飞行车辆仍需要的 Bullet body 管理。
- 通用输入、实体同步和快照边界。

### 5.2 `physics/tracked/`

归档后重建：

- `TrackedRig.kt`
- `ClosedTrackLoopSolver.kt`
- `TrackedVehicleCompiler.kt`
- `TrackedVehicleRegistry.kt`
- `TrackedStability.kt`
- `TrackedSpec.kt`
- `TrackedDefinitions.kt`
- `BedrockModelGeometry.kt`

保留其中经过审计的数据解析和几何测试结论，但不通过原类名继续隐式接线。

### 5.3 旧渲染

退出活动路径：

- `TrackAnimationProfile.kt`
- `TrackSuspensionProfile.kt`
- `VehicleModel.kt` 的旧预处理履带曲线分支
- `GeoVehicleRenderer.kt` 的旧轨迹动画分支
- `VehicleBody.updateTrackPhase()` 的旧视觉相位路径

### 5.4 状态和网络

检查并重构：

- `VehiclePhysicsRenderState.kt`
- `VehiclePrediction.kt`
- `VehiclePhysicsStateMessage.kt`
- `VehiclePhysics.kt`
- `VehiclePhysicsSpec.kt`

删除旧整体侧悬挂高度、旧 phase-only 状态和旧探针/高度场调试字段。

## 6. 零引用检查

在活动源码和活动测试中检查：

```bash
rg -n "LocalHeightField|ProbeSuspensionUnit|PlanarTrackSupport|TerrainSupportField|SUPPORT_PAIR|HEIGHT_FIELD|TerrainSlopePlanner|TerrainSlopeBodies|SLOPE|RUN_BLOCKS|trackGroundFallback|applyTrackSuspension|onTrackHullContact|stepAssist|setNormalWorldOnB|TrackAnimationProfile|TrackSuspensionProfile" \
  src --glob '!**/history/**'
```

允许命中：

- 归档目录。
- 明确标注的迁移说明。
- 通用测试中对旧符号存在性的历史断言（应尽量迁移）。

不允许命中：

- 活动运行路径。
- 新履带 runtime。
- 新渲染路径。

## 7. 退出条件

- 归档完整，哈希记录。
- 活动源码零引用。
- 通用车体测试重新通过。
- `CURRENT_WIRING.md` 更新为清理后的真实状态。
- 没有因清理引入与履带无关的回归。

## 8. D1 执行结果（2026-10-20）

### 8.1 归档

- `docs/physics/history/2026-10-20-pre-mcsp/superbwarfare/`：14 个旧文档。
- `docs/physics/history/2026-10-20-pre-mcsp/source/`：26 个旧源码文件，
  含 `SHA256SUMS.txt`。
- `KaguyaPyCore:docs/history/2026-10-20-pre-mcsp/source/`：33 个旧源码文件，
  含 `SHA256SUMS.txt`。

### 8.2 KaguyaPyCore 删除

- `LocalHeightField.kt`、`terrain/support/` 整个目录。
- `TerrainSectionBuild.kt`、`TerrainSupportBodies.kt`、`GroundSupportPair.kt`。
- `TerrainSlopePlanner.kt`、`TerrainSlopeBodies.kt`。
- `PlanarTrackSupport.kt`、`suspension/ProbeSuspensionUnit.kt`。
- 对应 11 个测试文件和 2 个高度场 GameTest。

### 8.3 KaguyaPyCore 简化

- `SectionSnapshot`：只剩 section、revision、方块碰撞快照。
- `TerrainShapePlan`：只剩方块 AABB 合并。
- `PhysicsChunkSection`：只剩 box bodies、active、appliedRevision。
- `PhysicsChunkManager`：只做快照 -> 计划 -> AABB 刚体。
- `CollisionGroups`：删除 `SLOPE`、`HEIGHT_FIELD`、`STRUCTURAL`、`SUPPORT_PAIR`；
  `DRIVABLE = TERRAIN`。
- `PhysicsLevel`：删除支撑对激活逻辑。

### 8.4 SuperbWarfare 删除

- `VehicleBody` 的旧履带支撑、fallback、法线改写、step assist、履带驱动、
  偏航限速和履带相位/投影。
- `TrackSuspensionProfile.kt`、`TrackAnimationProfile.kt`。
- `physics/tracked/` 整个目录（8 个源文件、5 个测试）。
- `SupportTestBodyEntity`、`SupportTestBodyRenderer`、`HeightFieldDebugState`、
  `HeightFieldDebugMessage`、`SbwDebugCommand`、`SupportFieldGameTests`、
  `SupportFieldRenderTypes`、`data/superbwarfare_support`。
- `TrackContactGameTests`、`VehicleDriveGameTests`、`VehicleDriveFixtures`、
  `WheeledDriveGameTests`。
- 实体、渲染器、网络通道、命令注册和语言条目中的对应项。

### 8.5 SuperbWarfare 保留与调整

- `VehicleBody` 保留通用车体、重力、地形等待、轮式悬挂、飞行力和关节。
- `VehiclePrediction` 不再调用履带支撑。
- `GroundVehicleAllowlist` 暂时为空：履带车没有批准运行时；轮式车不恢复。
- `VehiclePhysicsGameTests` 保留履带车被排除、炮塔残骸落地、越界车辆三项。
- `VehicleModel` 和 `GeoVehicleRenderer` 保留模型骨骼与悬挂根偏移，删除旧轨迹动画。

### 8.6 验证

| 命令 | 结果 |
|---|---|
| `KaguyaPyCore: ./gradlew compileKotlin` | 通过 |
| `KaguyaPyCore: ./gradlew compileTestKotlin compileTestJava` | 通过 |
| `KaguyaPyCore: ./gradlew test` | 通过（21 s） |
| `SuperbWarfare: ./gradlew compileKotlin compileJava` | 通过 |
| `SuperbWarfare: ./gradlew compileTestKotlin compileTestJava` | 见 STATUS |

### 8.7 未完成

- D1 已提交；轮式 allowlist 本轮不恢复；B0 只跑回归，不作为 99A 前置门。
- 履带车在恢复前不进入常规获取。
