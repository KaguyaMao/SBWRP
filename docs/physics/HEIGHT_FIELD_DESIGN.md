# 历史归档：高度场支撑设计

更新时间：2026-10-20  
状态：已撤销，仅作历史记录

> **2026-10-20 起本文仅作历史记录，不再有效。**
>
> 新架构不再使用车辆高度场。基础地形是 Minecraft 方块 `VoxelShape` 转成的 AABB；
> 履带载荷由动态物理运行段、负重轮、独立悬挂和车体约束承担；低台阶由 SBW 可选
> 台阶辅助单独处理。
>
> 当前有效文档：[`ARCHITECTURE.md`](ARCHITECTURE.md)、[`CONSTRAINTS.md`](CONSTRAINTS.md)、
> [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)、
> [`LEGACY_CLEANUP.md`](LEGACY_CLEANUP.md)。
>
> 清理前原文保存在
> [`history/2026-10-20-pre-mcsp/superbwarfare/HEIGHT_FIELD_DESIGN.md`](history/2026-10-20-pre-mcsp/superbwarfare/HEIGHT_FIELD_DESIGN.md)。

## 为什么撤销

- 方块碰撞已经能表达半砖、楼梯、多层结构和桥面/桥下。
- 高度场会把车辆专用地形策略带入 KaguyaPyCore。
- 探针 fallback 和“原始高度配平滑法线”造成重复承载和语义冲突。
- MCSP 的基础地形使用真实方块 AABB，不是全局平滑高度场。

本文件不作为实现或验收输入。
