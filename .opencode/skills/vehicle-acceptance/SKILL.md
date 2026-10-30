---
name: Vehicle Acceptance
description: Plan and record tracked-vehicle GameTests, manual gates, per-vehicle migration, and approval state for the MCSP-style Bullet architecture.
---

# 载具验收技能

开始前阅读：

1. `docs/physics/IMPLEMENTATION_PLAN.md`
2. `docs/physics/TEST_MATRIX.md`
3. `docs/physics/ACCEPTANCE_LIMITS.md`
4. `docs/physics/STATUS.md`
5. `docs/physics/VEHICLE_REGISTRY.md`
6. `docs/physics/RECORD_FORMAT.md`

## 证据等级

- T0：纯数据、几何、单位或渲染路径数学测试。
- T1：原生 Bullet 独立机构测试。
- T2：Spark/KaguyaPyCore 地形、线程和 TrackRun 测试。
- T3：SBW 单车服务端 GameTest。
- T4：客户端、网络、渲染烟测。
- T5：用户实机确认。

自动测试通过不等于实机通过。只有用户确认对应车型、场景和 A–D 门后，才可标记
`manual_passed`。D0–S1 的证据确认不是实机通过。

## 阶段门

```text
D0 文档 -> D1 清理 -> K1 核心 API -> S1 编译审计（拓扑重开）-> B1 悬挂（复核）
-> B2 运行段（重设计）-> S2 生命周期 -> S3.1 基础 AABB -> S4 驱动
-> S3.2 台阶辅助 -> R1 渲染 -> N1 同步/性能 -> V1 其他车型 -> F1 损毁
```

没有完成当前阶段退出条件，不开始下一阶段。阶段内修复和测试不算进入下一阶段。

## 测试要求

- 先 T0/T1，再 T3，再 T4，最后 T5。
- 未冻结限值只记录测量，不标通过。
- 失败必须记录车型、哈希、seed、位置、场景、接触来源、运行段/轮数、质量、悬挂力、
  驱动力、速度和耗时。
- 失败现场不删除；修复后追加新记录并引用旧记录。
- 旧高度场、探针、楔面和预处理轨迹结果不能作为新架构证据。

## 实机暂停点

- A 静态机构：轮位、轮段间隙、端锚点/法向残差、导轮高度、张紧方向和静置姿态。
- B 操控：差速方向、加速、倒车、制动、原地转向。
- C 地形：AABB 碰撞、AABB 阶梯坡、台阶辅助、墙拒绝、坑和履带贴地。
  光滑连续斜坡不属于 AABB 范围。
- D 渲染和长期：可见路径、链段连续、单车/四车性能、网络和长时间稳定。

## 车型批准

- Registered、Approved、NormallyAvailable 分离。
- 第一阶段只批准 `ztz_99a`。
- 其他车型逐车完成 V1 流程。
- 数据/模型哈希变化或核心 API 语义变化会使旧批准失效。
- 附属模组必须使用通用机构、TrackRun 和插值接口，不依赖已撤销高度场。

## 记录

状态写入 `docs/physics/STATUS.md` 和 `docs/physics/records/events.jsonl`。
`manual_passed` 必须 `user_confirmed=true` 且 `manual_gate` 为 A–D；`approved` 必须
`user_confirmed=true`。
