# 履带物理 Skills 目录与蒸馏规则

更新时间：2026-10-26（S3.2 分类原型与 AABB 阶梯坡术语）  
Skills 目录：`SuperbWarfare/.opencode/skills/`  
核心库 Skills：`KaguyaPyCore:.opencode/skills/`

OpenCode V2 实际发现规则：

- Project source：`.opencode/skills`，从当前目录向上查找到项目根目录。
- 也可以在仓库根 `opencode.jsonc` 的 `skills` 数组显式添加目录。
- Skill 文件必须是 `.opencode/skills/<skill-id>/SKILL.md` 或源根目录下的扁平
  `<skill-id>.md`。
- Skill ID 由路径决定，区分大小写；frontmatter `name` 只是显示名。

因此两个仓库都使用 `.opencode/skills/`，不另建 `skills/` 根目录，避免两套规则冲突。

## 1. Skills 不是事实数据库

Skill 只保存“遇到某类任务时必须遵守的工作流程、阅读顺序和禁止事项”，不保存易过期的
测试成绩、临时日志或用户尚未确认的结论。事实、阶段、哈希和失败结果放在
`docs/physics/STATUS.md` 与 `docs/physics/records/events.jsonl`。

加载 Skill 后仍必须读取当前状态和阶段文档。

## 2. SBW Skills

| ID | 触发场景 | 主要职责 |
|---|---|---|
| `mcsp-bullet-tracked-vehicle` | 任何履带物理任务 | 总架构、阶段门控、KaguyaPyCore API 边界 |
| `tracked-mechanism` | 负重轮、导轮、张紧、履带板、闭链 | 角色、自由度、质量、销链、Bullet 测试 |
| `tracked-terrain-assist` | AABB 地形、台阶、AABB 阶梯坡、洞 | 普通地形和 SBW 可选台阶辅助（S3.2 当前只有分类原型） |
| `tracked-rendering` | MCSP 履带路径、可见链节、渲染 | 路径重建、节距、相位、骨骼绑定 |
| `vehicle-acceptance` | GameTest、逐车迁移、实机验收 | T0–T5 证据、A–D 暂停点、批准门控 |
| `legacy-physics-cleanup` | 删除旧实现、归档、零引用检查 | 工作树所有权、归档、清理顺序 |
| `physics-records-sync` | 状态、决策、测试、MCP/Skill 同步 | 追加事件、更新状态、处理冲突 |

## 3. KaguyaPyCore Skills

| ID | 触发场景 | 主要职责 |
|---|---|---|
| `physics-core-support` | 核心 API、线程、生命周期变化 | 通用物理边界和同步规则 |
| `track-run-api` | TrackRun、关节单位、link 生命周期 | 通用运行段 API 和测试 |
| `physics-core-testing` | 核心 T0/T1、原生 Bullet 测试 | 核心测试命令和证据规则 |

## 4. 选择规则

- 履带物理总体任务：`mcsp-bullet-tracked-vehicle`。
- 轮组、销链、悬挂或驱动：`mcsp-bullet-tracked-vehicle` + `tracked-mechanism`。
- 地形、台阶、AABB 阶梯坡或洞：`mcsp-bullet-tracked-vehicle` + `tracked-terrain-assist`。
- 渲染、路径、相位或骨骼：`mcsp-bullet-tracked-vehicle` + `tracked-rendering`。
- 测试、验收、逐车迁移：`mcsp-bullet-tracked-vehicle` + `vehicle-acceptance`。
- 删除旧实现或归档：`legacy-physics-cleanup`。
- 修改核心库 API、线程或 TrackRun：`physics-core-support` + `track-run-api`。
- 运行核心测试：`physics-core-support` + `physics-core-testing`。
- 任何会改变硬约束、阶段状态、测试结果、车型批准或文档事实的任务，再加载
  `physics-records-sync`。

## 5. 蒸馏格式

新增或修改 Skill 必须包含：

1. frontmatter `name` 和 `description`；
2. 触发条件和必须先阅读的仓库文档；
3. 不能违反的边界；
4. 按核心层 -> 通用机构层 -> 车型层 -> 渲染层的执行顺序；
5. 必须记录的测试、哈希、线程所有权和 `core_commit`；
6. 阶段状态和实机暂停点如何更新；
7. 与其他 Skill 的职责边界。

禁止把一次对话摘要原样塞进 Skill。Skill 必须提炼成跨车型、可重复执行的规则。

## 6. 同步规则

- Skill 变更必须追加 `decision` 或 `stage` 事件。
- 硬约束变化必须同步 `CONSTRAINTS.md`、`DECISIONS.md`、`IMPLEMENTATION_PLAN.md`、
  相关 Skill 和 `MCP.md`。
- Skill 只引用事实来源，不复制事实表。
- OpenCode 能找到 Skill 文件，不等于当前模型已加载；加载以实际 `skill` 调用为准。
- Skill 中的跨仓库文档使用 `仓库名:仓库内路径`。
- Skill 不是事实权威。发生什么以 `records/events.jsonl` 为准；规范以 `CONSTRAINTS.md`
  为准。冲突时追加决策事件。

## 7. 已撤销 Skill

旧的 `tracked-height-field` 和 `physics-height-field` 方向不再适用。其内容已随
`HEIGHT_FIELD_DESIGN.md` 归档。新的地形工作流使用 `tracked-terrain-assist`。
