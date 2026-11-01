# ZTZ-99A 模型工作副本

这是给手工修改用的副本，不是游戏正在加载的文件。

| 用途 | 路径 |
|---|---|
| 模型副本 | `docs/vehicles/ztz_99a/ztz_99a.geo.json` |
| 贴图副本 | `docs/vehicles/ztz_99a/ztz_99a.png` |
| 游戏实际加载的模型 | `src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json` |
| 游戏实际加载的贴图 | `src/main/resources/assets/superbwarfare/textures/bedrock/vehicle/ztz_99a.png` |
| 动画 | `src/main/resources/assets/superbwarfare/animations/bedrock/vehicle/ztz_99a.animation.json` |
| 履带机构引用 | `src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json` |

改完要进游戏时，把对应副本覆盖回上面的游戏路径。只改文档里的文件，游戏和测试都不会变。贴图是 512×512 的 PNG，和模型 `texture_width` / `texture_height` 一致。

格式是 Bedrock `1.12.0`，`format_version` 不要改。坐标单位是模型像素，16 像素 = 1 格。`pivot` 是骨骼原点，物理轮心读的是这个点，不是方块的几何中心。

## 不要改名的骨骼

机构 JSON 按这些名字找骨骼。改名之后悬挂、轮序、锚点和可见履带会断开。模型副本和游戏资源现在是同一份（2026-10-31 已覆盖），改完后仍要两处保持一致。

| 骨骼 | 角色 | pivot（像素） |
|---|---|---|
| `followL` / `followR` | 前导动轮（IdlerGuide），固定安装，后续接被动张紧器 | `[±29.45, 17.8125, -61.5175]` |
| `wheelL1`–`wheelL6` / `wheelR1`–`wheelR6` | 负重轮，独立悬挂 | Y 都是 `9.375`，Z 从 `-45.73625` 到 `44.57625`，间距约 `18.125` |
| `leadL` / `leadR` | 后主动轮（DriveGuide），固定安装 | `[±29.45, 17.8125, 60.9825]` |
| `trackRotL0` / `trackRotR0` | 履带链节模板（可见链节重复绘制它） | `[±29.375, 24.85525, -62.28444]` |
| `PointL` / `PointR` | 履带包络锚点标记，6 个 2×2×2 小方块。只读方块中心的高度和前后位置，横向取履带平面。**不渲染**，由 `EnvelopeAnchorBone` 引用 | 方块中心见下表 |

锚点（左侧，像素，高度/前后）：`(0,-47.58)`、`(0,48.42)`、`(13,-67.58)`、`(13,67.42)`、`(24,67.42)`、`(24,-67.58)`。每个锚点归属距它的履带中心线圆最近的轮，随该轮移动：底部两点跟 1 号和 6 号负重轮，前部两点跟前导动轮，后部两点跟后主动轮。

## 方向约定（2026-10-31 更正）

- 模型 `-Z` 是车头：炮管在 `-Z`。所以模型 Z 为 `-61.5` 的是**前**端，`+60.98` 是**后**端。
- 前端是导动轮 `followL/R`，后端是主动轮 `leadL/R`，与实车 99A（主动轮后置）一致。名字里的 lead/follow 是"驱动轮/从动轮"的意思，不是前后。
- 驱动仍然只靠履带物理运行段与地面、坡面的接触，主动轮不施加扭矩。

## 负重轮横向位置

负重轮骨骼的 pivot 在 `x = ±32.89` 像素，而轮盘和履带中心在 `±29.4`。物理刚体按履带平面居中（`AlignWheelsToBelt`），骨骼偏移作为渲染偏移保留，所以显示位置不变，碰撞圆柱不再外偏 0.215 m。

## 已移除的骨骼

`wheelL0`/`wheelL7`/`wheelR0`/`wheelR7`（改名）、`trackMovL1`–`36`、`trackRotL1`–`36`（及右侧）、`ring_l`、`ring_l2`、`tread_l`、`tread_l2` 都已不在模型里。可见链节数不再等于骨骼数，由包络长度和链节模板长度决定。
