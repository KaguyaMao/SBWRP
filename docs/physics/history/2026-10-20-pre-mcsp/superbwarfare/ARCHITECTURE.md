# 履带物理目标架构

更新时间：2026-10-19  
状态：设计基线，尚未全部接线

## 1. 分层

```text
Forge/Minecraft 主线程
  ├─ 读取载具数据、模型资源和地形区段
  ├─ 生成不可变 VehicleInput / EntityState / SectionSnapshot
  ├─ 注册/批准车型、处理常规获取入口
  └─ 消费 PhysicsFrameSnapshot 和游戏事件

KaguyaPyCore / Spark Core
  ├─ PhysicsLevel：Level -> 一个物理线程 -> 固定 60 Hz
  ├─ TerrainSnapshot / TerrainSupportField：地形数值快照和高度场
  ├─ TerrainContactAuthority：支撑接触的唯一仲裁入口
  ├─ PhysicsJoints：关节、单位和线程安全 API
  └─ PhysicsFrameSnapshot：跨线程只读结果

SuperbWarfare 通用履带层
  ├─ TrackedVehicleRegistry / Compiler
  ├─ TrackedMechanism：轮、板、销链、张紧、悬挂
  ├─ TrackDriveCoupler：主动轮到履带/车体的有界传力
  ├─ TrackedVehicleRuntime：创建、步进、快照、关闭
  └─ TrackedRenderState：轮板姿态、相位和诊断

车型适配层 / 数据
  ├─ 模型路径和骨骼绑定
  ├─ 轮轴中心、半径、宽度和角色
  ├─ 履带节距、节数、销位置和单板形状
  ├─ 质量、惯量、悬挂及驱动参数
  └─ 准入状态、测试证据和模型/数据哈希
```

## 2. 高度场是唯一地面支撑来源

对已批准的履带车辆，地面接触必须经过统一的 `TerrainContactAuthority`：

```text
履带板/车体形状
       ↓
HeightField.query(shape footprint, pose, velocity)
       ↓
有效/无效 + 接触点 + 高度 + 法线 + 侵入/间隙 + 摩擦类别
       ↓
Bullet 接触约束
```

首轮地面支撑接触只来自履带板和车体对高度场支撑面的接触。负重轮、导动轮和主动轮
不把高度场支撑面放进地面支撑 mask；它们通过履带板接触和关节承受载荷。车体不得在
履带板已经拥有的同一接触片上再提供第二份向上支撑。

不得出现以下并行支撑链：

```text
旧探针 -> 自定义弹簧力 -> 车体
原始方块顶面 -> Bullet 支撑
楔面刚体 -> 额外承载
```

履带板和车体可以存在结构障碍碰撞，但障碍碰撞不能在同一地面位置再提供第二份
向上支撑。接触仲裁必须清楚标记：当前接触由高度场拥有，还是由结构障碍拥有。

## 3. 机构负载路径

```text
高度场地面
  -> 履带板接触
  -> 闭合销链和履带板之间的约束/接触
  -> 负重轮与履带板接触
  -> 负重轮独立悬挂
  -> 车体
```

导动轮和主动轮只承担其数据声明的导向、张紧和传动职责。独立悬挂的平动自由度、
轮体滚动轴和主动轮传力不能在同一个关节自由度上互相覆盖。

首轮的主动轮传力采用通用有界耦合：

- 驱动目标来自左右履带差速输入；
- 传力有最大扭矩/力和滑移上限；
- 负重轮接地阻力通过履带板、轮体和约束反作用回到主动轮/车体；
- 不直接设置车体速度，不直接设置履带板位置；
- 首轮不要求制作主动轮齿和履带导齿的真实啮合碰撞。

## 4. 线程和生命周期

### 创建

1. 游戏线程读取实体和车型数据，生成不可变 `TrackedVehicleSpec`。
2. 物理线程创建车体、轮、履带板、joint 和高度场支撑对象。
3. 所有 body 设置 owner、碰撞组和同 owner 过滤后，再加入 physics world。
4. joint 只能在两端 body 已进入同一 physics world 后加入。

### 每个物理子步

1. 处理待执行的输入、地形计划和机构状态。
2. 获取当前高度场版本，执行支撑接触查询/仲裁。
3. 应用显式重力、悬挂/张紧/驱动控制量；不直接改 pose。
4. Bullet 更新一个 `1/60 s` 子步。
5. 读取求解后的 body/link frame，生成只读诊断和渲染状态。

### 关闭

```text
停止接受输入
  -> 禁用履带驱动耦合
  -> 禁用/移除履带 joint
  -> 移除 link/wheel body
  -> 移除 chassis joint/body
  -> 清理 owner、缓存、字段版本和快照引用
```

## 5. 当前代码与目标架构的关系

当前调用点以 [`CURRENT_WIRING.md`](CURRENT_WIRING.md) 为准。下表只写目标，不表示这些路径已经退役。

| 当前代码 | 目标，不是现状 |
|---|---|
| `physics/tracked/TrackedRig` | 保留为通用机构候选；接入前必须补 owner、真实张紧自由度、传动和游戏生命周期 |
| `ClosedTrackLoopSolver` | 只做初始闭链几何审计；不能把静态 pin polygon 当作运行时 pose |
| `VehicleBody.applyTrackSuspension` | 新支撑链验收前不得继续作为履带支撑；验收后从运行路径退役 |
| KaguyaPyCore `TerrainSlopePlanner` / `TerrainSlopeBodies` | 履带车不得再依赖其楔面支撑；在履带路径拆除前，它们仍会生成 `SLOPE` 刚体 |
| `TrackAnimationProfile` | 不得作为 99A 新物理履带的姿态来源；它目前仍是客户端视觉路径 |
| `VehiclePrediction` | 改为消费权威履带/高度场快照；客户端不得另造一套游戏规则物理 |
