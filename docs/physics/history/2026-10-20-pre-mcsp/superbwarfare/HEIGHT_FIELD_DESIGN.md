# 高度场支撑与地面接触设计

更新时间：2026-10-19  
状态：当前最高优先级底层设计

## 1. 目标

高度场不是“探针没扫到时的 fallback”。对于已批准履带车辆，它必须直接决定：

1. 某个履带板/车体碰撞形状是否有有效地面支撑；
2. 支撑接触的位置和高度；
3. 接触法线；
4. 有符号间隙或侵入深度；
5. 该接触的表面类别、摩擦和是否允许由 Bullet 产生支撑反力。

Bullet 只负责根据这些接触数据和机构约束求解反力与运动。高度场不能用直接位移、
人工向上力或“爬坡速度”替代接触求解。

## 2. 地形数据边界

### 2.1 主线程快照

主线程从区块复制纯数据：

- section 坐标和 revision；
- 方块 collision voxel box；
- 表面类别和摩擦相关数值；
- 空气、未知区块、净空和多层表面信息；
- 一格边界 margin，供跨 section 构场；
- 正上方 section 的覆盖状态，以及其底部一层的纯数值碰撞，用来证明本 section
  顶部是暴露、延续还是未知。未知净空不是无限开放。

快照不得包含 `Level`、`Chunk`、`BlockState`、实体或 Bullet 对象引用。

### 2.2 builder 输出

builder 只生成数值结构，例如：

```text
TerrainSupportFieldTile
  tileOrigin / cellSize / revision
  cells[]:
    valid
    rawHeight
    supportHeight
    surfaceClass
    friction
    hard
    normal/gradient（或由相邻有效格计算）
    clearance / layerId
  invalidReason / sourceRevision
```

未知和空气不是“很低的有效地面”。它们必须是 `invalid`，查询必须返回无支撑。
高度场不能把桥下、地下空洞或未加载区块填成 `baseY - 6` 这种伪地面。

## 3. 受限向下平滑规则

当前采用 Machine-Max 的语义方向，但不直接复制代码：

1. 只允许高列向低列方向修正，不允许把低列抬高。
2. 只在相同表面类别、相同层、连续有效单元之间传播。
3. 遇到空气、洞、未知区块、结构边界或不连续净空立即截断。
4. `rawHeight - supportHeight` 不得超过显式的 `maxDownwardCorrection`（包含上限）。
   该上限相对原始表面累计计算，不是每一轮迭代各自再扣一次。
5. 平滑迭代次数和邻域必须是通用策略参数，不随车型长度动态改变。
6. 不使用 `RUN_BLOCKS`、坡长、车长或“前方多少格”生成楔面。
7. 平滑结果低于原始可见表面时，允许的有限误差必须被记录，并由高度场接触层统一
   负责；不能让原始方块顶面再生成第二个向上支撑。

这意味着高度场可以使一个受批准的一格高度差具有连续接触过渡，但不会把连续高墙、
跨洞地形或多级楼梯全部削成一条可用斜坡。可爬性来自局部连续表面和车辆真实几何，
不是一个独立的坡道实体。

## 4. 查询契约

查询输入至少包含：

- world position / body pose；
- 碰撞形状或接触 footprint；
- 当前速度和向下查询范围；
- 需要的表面层和 revision。

查询输出：

```text
HeightFieldContact
  valid
  supportPoint
  heightY
  normal
  signedDistance / penetration
  surfaceClass
  friction
  terrainRevision
  authority = HEIGHT_FIELD | STRUCTURAL_OBSTACLE | NONE
```

查询超出 tile 范围不能夹到边界；必须返回 `NONE` 并请求扩展/激活邻域。

法线必须由与高度同一版本的有效邻域计算。不能使用高度场法线配原始方块高度，
也不能使用原始接触点配平滑法线而不修正间隙。

当前纯数据 `query` 只是向下表面采样，不是形状接触求解。探针已经进入表面时，
不得把“未选中表面”解释成侵入深度。采样高度、法线和 signed distance 必须来自
同一个角点补丁；策略禁止过渡的台阶不得只靠倾斜法线表现成坡。

P1.3 另有独立支撑网格 `TerrainSupportBodies`，以及按形状分离的
`GroundSupportPairs`。选择 `SUPPORT_PAIR` 的物体不会再碰到同一片原始方块顶面或楔面。
两格及以上立面保留为结构侧面；section 边界上的立面由较高一侧生成唯一边界面，
边界上的一格台阶按 margin 证据下削。该对已由区段物理线程安装和清理。
这仍不修改已求解 manifold，也不把支撑对并入 `DRIVABLE`。

非整格碰撞不能扩成整格支撑。无法用一格高度场诚实表达的形状保持无支撑，留给
结构障碍阶段处理。一个 tile 不把上一 section 的地面复制进自己的空洞。

## 5. Bullet 接触实现方向

首选实现是“高度场支撑表面 + 结构障碍仲裁”，而不是继续保留旧楔面：

1. 物理线程将有效高度场 tile 生成可碰撞的支撑表面（高度网格或等价三角面/静态
   支撑 shape）。
2. 履带板的地面支撑 mask 只接受高度场支撑表面。车体的地面支撑 mask 也只接受高度场
   支撑表面，但仲裁必须去掉履带板已经拥有的同一接触片上的车体向上支撑。负重轮、
   导动轮和主动轮首轮不把高度场支撑面列入地面支撑 mask。
3. 原始 voxel terrain 只负责结构障碍。分类规则见下一节。同一接触片不能由高度场和
   原始方块顶面同时提供向上反力。
4. 接触仲裁在 Bullet 求解前完成；若 native callback 只能修改已有 manifold，必须
   额外保证高度场支持面能生成缺失的接触，不能只改已有法线。
5. 接触修改必须验证 callback 的真实时序、A/B 法线符号、manifold 缓存和 CCD；不能
   以方法名 `onContactProcessed` 推断它一定是求解前或求解后。

## 5.1 地面与结构障碍的分类

先分类，再平滑：

1. 未加载、空气、洞或越界：权威为 `NONE`。不得发明支撑高度。
2. 与相邻有效列的连续立面高差达到或超过 2 格：该立面是 `STRUCTURAL_OBSTACLE`。
   高度场不得跨过它生成支撑坡。较低一侧仍可以是普通地面。
3. 1 格高差是否允许进入支撑过渡，取决于已冻结的 `maxRiseForSupportTransition`。
   该值未写入 [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) 之前，不得把 1 格台阶测试写成通过。
4. 侧面、天花板和 `NON_GROUND` 形状是结构障碍，不是地面支撑。
5. 被标成地面支撑的格子，其向上接触只属于 `HEIGHT_FIELD`。该格原始方块顶面不得再产生向上支撑。
6. 平滑只允许按 [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md) 中已冻结的
   `maxDownwardCorrection` 降低高列。该值未冻结时，只能记录测量，不能宣布平滑测试通过。

## 6. 更新与失效

- 方块碰撞几何变化立即使涉及的 tile/section revision 失效。
- builder 结果必须检查 revision，旧结果不能覆盖新结果。
- physics worker 在换用新表面前完成旧 surface body 的安全替换。
- 车辆接触结果必须带 terrain revision，便于诊断“旧表面继续承载”。
- 区块卸载、Level 卸载和车辆离开范围都必须清理或降级为无支撑，不得保留旧高度。

## 7. 验收顺序

1. 纯 JVM：坐标、半格中心、插值、法线、无效单元和硬软分离。
2. 原生 Bullet：单个板/箱体落在高度场支撑面上，验证载荷、摩擦、分离和挖空失效。
3. 车辆机构：高度场 -> 板 -> 轮 -> 悬挂 -> 车体的载荷路径，禁止探针和楔面承载。
4. 地形矩阵：平地、真实斜坡、一格高差、连续阶梯、两格墙、洞、桥、侧向台阶、
   section 边界和未知区块。
5. 99A 实机：显示 raw/support surface、有效格、接触点、法线和 revision。用户确认的是
   该场景的实机结果，不是事后补写通过阈值的授权。阈值必须事先写在
   [`ACCEPTANCE_LIMITS.md`](ACCEPTANCE_LIMITS.md)。
