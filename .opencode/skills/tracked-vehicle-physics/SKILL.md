---
name: Tracked Vehicle Physics
description: Legacy entry point kept for discovery. Use mcsp-bullet-tracked-vehicle for current tracked-vehicle physics architecture and stages.
metadata:
  opencode/autoinvoke: false
---

# 旧履带物理技能入口

本 Skill 已由 `mcsp-bullet-tracked-vehicle` 取代。它保留 ID 以避免旧引用静默失效，但不
出现在模型可自动发现的技能列表中。

当前架构：

- Bullet 复现 MCSP 物理，不嵌入 Rapier。
- 基础地形使用 Minecraft 方块 AABB。
- 物理履带运行段与可见重复链节分离。
- KaguyaPyCore 只提供通用 Bullet API、TrackRun 和插值。
- 车辆地形、可选台阶辅助、驱动、渲染和验收在 SuperbWarfare。
- S3.2 只有分类原型；没有辅助开启的正向爬越测试前，不得写成辅助已实现或已通过。

先阅读：

1. `docs/physics/README.md`
2. `docs/physics/CONSTRAINTS.md`
3. `docs/physics/ARCHITECTURE.md`
4. `docs/physics/IMPLEMENTATION_PLAN.md`

不要使用已撤销的高度场、探针、楔面或预处理履带方案。
