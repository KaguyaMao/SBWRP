---
name: Tracked Rendering
description: Design, implement, test, or diagnose MCSP-style visible track rendering from interpolated wheel and TrackRun snapshots, including path rebuild, phase, link templates, and Bedrock/Geckolib binding.
---

# MCSP 风格履带渲染技能

开始前阅读：

1. `docs/physics/TRACK_RENDERING.md`
2. `docs/physics/ARCHITECTURE.md`
3. `docs/physics/CORE_API_CONTRACT.md`
4. `docs/physics/TEST_MATRIX.md`
5. `KaguyaPyCore:docs/PHYSICS_INTERPOLATION.md`
6. `KaguyaPyCore:docs/TRACK_RUN_API.md`

## 硬边界

- 物理运行段和可见重复链节是两套对象，但共享轮序、wrap、半径、厚度和机构状态。
- 可见链节不是物理刚体，不创建 Bullet body。
- 渲染结果不写回物理 body、速度或位置。
- 不在渲染线程读取 native Bullet body；只消费不可变快照。
- 客户端不独立模拟权威物理。
- 不使用 `TrackAnimationProfile` 预处理曲线作为新履带位置来源。
- 不用整侧统一高度代替逐轮路径。
- 履带根骨骼的悬挂偏移只应用一次，子骨骼不重复叠加。
- 轮 ID 从编译绑定取，不从骨骼名数字猜测。

## 路径重建

1. 读取同步后的轮心、半径、宽度、履带厚度和 wrap 方向。
2. 按显式轮序建立圆包络。
3. 求相邻圆的外公切线或内公切线。
4. 连接圆弧和切线，形成闭合路径。
5. 计算总长度和闭合误差。
6. 路径求解必须是 SBW 纯数学实现，不访问 Bullet 或 Minecraft 世界。
7. 不读取 native 运行段的逐段 pose 来反推路径。

## 链节采样

```text
count = max(1, ceil(restLength / visiblePitch - 0.001))   // 初始化时固定
restPitch = currentLength / count
sampleDistance = phase * restPitch + linkIndex * restPitch
point = path.pointAt(sampleDistance)
tangent = path.tangentAt(sampleDistance)
rotation = atan2(tangent)
```

- 可见链节数量在初始化时固定，不每帧用 `round(pathLength / visiblePitch)` 重算。
- 物理运行段数、可见链节数和模型骨骼数分别记录。
- 左右履带独立相位；倒车反向；相位环绕使用最短路径。
- 相位按 `restPitch` 由权威带速推进，不由纯车体速度单独推算。

## 骨骼和模型

- Bedrock 和 Geckolib 使用同一路径结果，只做绑定适配。
- 可见链节模板由车型模型定义重复。
- 物理运行段不直接绑定模型骨骼。
- 渲染插值只使用核心通用物理快照。

## 测试

T0：

- 两轮包络、多轮闭合、inside/outside wrap。
- 相位环绕、倒车、左右独立。
- 悬挂变化后路径连续。
- 可见数量初始化固定、间距随长度变化。
- 带速与车体速度分离。

T4：

- Bedrock 和 Geckolib 模型。
- 静止、前进、倒车、原地转向、单侧压坡。
- 网络延迟和物理帧丢失。
- 长时间行驶无漂移和分离。

## 记录

物理/可见节数、路径闭合误差、可见节距误差、相位偏差、模型类型和测试场景写入
`docs/physics/STATUS.md` 和 `docs/physics/records/events.jsonl`。实机暂停点 D 必须等待
用户确认。
