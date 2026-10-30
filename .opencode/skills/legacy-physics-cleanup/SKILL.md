---
name: Legacy Physics Cleanup
description: Archive and remove superseded tracked-vehicle physics code safely, preserving user worktree changes and proving zero references in active sources.
---

# 旧履带物理清理技能

开始前阅读：

1. `docs/physics/LEGACY_CLEANUP.md`
2. `docs/physics/CURRENT_WIRING.md`
3. `docs/physics/REPOSITORY_BOUNDARIES.md`
4. `docs/physics/STATUS.md`

并核对 KaguyaPyCore：

1. `KaguyaPyCore:docs/CORE_SCOPE.md`
2. `KaguyaPyCore:docs/PHYSICS_API.md`
3. `KaguyaPyCore:docs/TERRAIN_COLLISION.md`

## 硬边界

- 不执行 `git reset`、`git checkout`、批量格式化或递归删除。
- 不覆盖用户已有修改。
- 先归档，再删除。
- 归档目录不进入 Gradle source set。
- 活动源码和活动测试零引用旧符号后，才能标记 D1 完成。
- 删除通用地形代码前，先确认它不是普通车辆和地形系统依赖。

## 工作顺序

1. 记录两个仓库的 `git status --short`、`git diff --name-status`、`git diff --check`。
2. 建立文件所有权表：用户修改、旧实现、通用实现、新候选、测试夹具。
3. 归档旧文档和旧源码，记录原路径和 SHA-256。
4. 逐项删除活动路径：
   - 高度场和支撑对。
   - 楔面和 `SLOPE`。
   - 探针和 `PlanarTrackSupport`。
   - `VehicleBody` 旧履带分支。
   - 旧候选 `physics/tracked/`。
   - 旧轨迹动画和状态字段。
5. 保留通用 voxel/AABB 地形、物理线程、body/joint API 和普通车体生命周期。
6. 运行零引用检查：

```bash
rg -n "LocalHeightField|ProbeSuspensionUnit|PlanarTrackSupport|TerrainSupportField|SUPPORT_PAIR|HEIGHT_FIELD|TerrainSlopePlanner|TerrainSlopeBodies|SLOPE|RUN_BLOCKS|trackGroundFallback|applyTrackSuspension|onTrackHullContact|stepAssist|setNormalWorldOnB|TrackAnimationProfile|TrackSuspensionProfile" \
  src --glob '!**/history/**'
```

该检查只覆盖旧符号；`TrackedStepAssist` / `applyStepAssist()` 是 S3.2 目标类型，不表示
旧实现残留，也不能据此宣称辅助已实现。

7. 清理后重跑通用车体测试，确认没有与履带无关的回归。
8. 更新 `CURRENT_WIRING.md`、`STATUS.md` 和事件账本。

## 禁止

- 不因为测试失败恢复旧探针、高度场或楔面。
- 不把归档代码重新加入活动 source set。
- 不用 `.gitignore` 掩盖被追踪的测试环境文件。
- 不删除用户尚未提交的无关修改。

## 记录

归档路径、哈希、删除文件、零引用结果和回归测试写入 `docs/physics/LEGACY_CLEANUP.md`、
`docs/physics/STATUS.md` 和 `docs/physics/records/events.jsonl`。D1 完成不等于新物理实现
完成。
