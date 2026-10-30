# 履带物理 Skills 目录与蒸馏规则

更新时间：2026-10-19  
SuperbWarfare 技能根目录：`.opencode/skills/`  
KaguyaPyCore 技能：`KaguyaPyCore:.opencode/skills/physics-core-support/SKILL.md`

## 1. 技能不是事实数据库

Skill 只保存“遇到某类任务时必须遵守的工作流程、阅读顺序和禁止事项”，不保存易过期的
测试成绩、临时日志或用户尚未确认的结论。事实、阶段、哈希和失败结果放在
`docs/physics/STATUS.md` 与 `docs/physics/records/events.jsonl`。

加载技能后仍必须读取当前状态和阶段文档，不能只凭 Skill 正文继续工作。

## 2. 技能清单

| ID | 触发场景 | 主要职责 |
|---|---|---|
| `tracked-vehicle-physics` | 任何履带物理任务 | 总体硬约束、阶段顺序、统一入口 |
| `tracked-height-field` | 高度场、地形接触、台阶/坡/洞 | 唯一支撑表面、快照、revision、接触仲裁 |
| `tracked-mechanism` | 负重轮、导轮、张紧、履带板、闭链 | 自由度、质量、销链、原生单位和机构审计 |
| `vehicle-acceptance` | GameTest、逐车迁移、实机验收 | T0-T5 证据、A-D 暂停点、批准门控 |
| `physics-records-sync` | 状态、决策、测试证据、MCP/Skill 同步 | 追加事件、更新状态、处理冲突和同步结果 |
| `physics-core-support` | KaguyaPyCore 高度场/关节/线程 API | 核心库线程安全支撑协议和下游影响。文件不在本仓库 |

## 3. 选择规则

- 改高度场：加载 `tracked-vehicle-physics` + `tracked-height-field`。
- 改履带机构或核心关节：加载 `tracked-vehicle-physics` + `tracked-mechanism`。
- 运行/设计验收：加载 `tracked-vehicle-physics` + `vehicle-acceptance`。
- 任何会改变硬约束、阶段状态、测试结果、车型批准或文档事实的任务，再加载
  `physics-records-sync`。
- 修改 KaguyaPyCore 的支撑、关节单位或线程 API 时，再加载 `physics-core-support`。

## 4. 蒸馏格式

新增或修改 Skill 时必须包含：

1. frontmatter `name` 和 `description`；
2. 触发条件和必须先阅读的仓库文档；
3. 不能违反的边界；
4. 按底层 -> 通用层 -> 车型层的执行顺序；
5. 必须记录的测试/哈希/线程所有权信息；
6. 实机暂停点或交付状态如何更新；
7. 与其他 Skill 的职责边界。

禁止把一次对话摘要原样塞进 Skill；应提炼成跨车型、可重复执行的规则。

## 5. 版本与同步

- Skill 文件变更必须追加 `decision` 或 `stage` 事件，说明变更原因和影响阶段。
- 硬约束变化必须同时检查 `CONSTRAINTS.md`、`DECISIONS.md`、`IMPLEMENTATION_PLAN.md`、
  相关 Skill 和 `MCP.md`。
- 事实变化不应只改 Skill；Skill 只引用事实来源，不复制完整事实表。
- OpenCode skill discovery 能找到文件，不等于 Skill 已被当前模型加载；加载结果以实际
  `skill` 调用为准。
- Skill 中的跨仓库文档使用 `仓库名:仓库内路径`。不要把本机检出布局写进 Skill。
- Skill 不是事实权威。发生了什么以 `records/events.jsonl` 为准；规范以 `CONSTRAINTS.md` 为准。两者冲突时追加决策事件，不靠 Skill 裁决。
