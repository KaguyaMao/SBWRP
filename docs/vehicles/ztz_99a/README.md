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

机构 JSON 按这些名字找骨骼。改名之后悬挂、轮序和可见履带会断开。

| 骨骼 | 角色 | pivot（像素） |
|---|---|---|
| `wheelL0` | 左导动轮，固定 | `[29.45313, 17.8125, -61.5175]` |
| `wheelL1`–`wheelL6` | 左负重轮，独立悬挂 | Y 都是 `9.375`，Z 从 `-45.73625` 到 `44.57625`，间距约 `18.125` |
| `wheelL7` | 左主动轮，固定 | `[29.45313, 17.8125, 60.9825]` |
| `wheelR0` | 右导动轮，固定 | `[-29.45312, 17.8125, -61.5175]` |
| `wheelR1`–`wheelR6` | 右负重轮，独立悬挂 | Y 都是 `9.375`，Z 与左侧对应 |
| `wheelR7` | 右主动轮，固定 | `[-29.45312, 17.8125, 60.9825]` |
| `trackRotL0` | 左履带链节模板 | `[29.375, 24.85525, -62.28444]` |
| `trackRotR0` | 右履带链节模板 | `[-29.375, 24.85525, -62.28444]` |

`trackRotL1`–`trackRotL36` 和 `trackRotR1`–`trackRotR36` 是重复链节，可见履带按 `trackRotL` / `trackRotR` 前缀找。模板方块在 `trackRotL0` / `trackRotR0` 上。

轮序是 `wheel*0` 到 `wheel*7`。`0` 在车尾一侧（Z 为负），`7` 在车头一侧（Z 为正）。
