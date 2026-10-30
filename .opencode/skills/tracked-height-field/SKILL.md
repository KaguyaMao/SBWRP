---
name: Tracked Height Field
description: Legacy height-field skill. Use tracked-terrain-assist instead. This direction is revoked and retained only as a migration marker.
metadata:
  opencode/autoinvoke: false
---

# 旧高度场技能（已撤销）

高度场不再属于履带车辆架构。新的地形工作流使用 `tracked-terrain-assist`。

已撤销内容：

- KaguyaPyCore `LocalHeightField`、`TerrainSupportField`、`SUPPORT_PAIR`。
- 探针承载和探针 miss 后高度场 fallback。
- 固定楔面、按车长坡长和人工抬升。
- 用平滑高度场替代 Minecraft 方块 AABB。

当前规则：

- 基础地形是 Minecraft `VoxelShape` 转成的 AABB。
- 可选台阶辅助只在 SuperbWarfare 实现；S3.2 当前只有分类原型，未通过。
- 辅助不得与原始地形重复提供向上支撑。
- 光滑连续斜坡不属于 AABB 门禁；AABB 阶梯坡和楼梯才是范围内地形。

先阅读：

1. `docs/physics/CONSTRAINTS.md`
2. `docs/physics/ARCHITECTURE.md`
3. `docs/physics/LEGACY_CLEANUP.md`

本 Skill 不应被用于实现或验收。
