# 2026-10-20 清理前基线

本目录保存切换到 MCSP 风格 Bullet 架构前的文档和状态快照，仅用于追溯，不是当前实现或
验收输入。

- `superbwarfare/`：SuperbWarfare 的旧计划、约束、状态、高度场和接线文档。
- `kaguayapycore/`：KaguyaPyCore 的旧 API、地形、线程、测试和计划文档。
- `SHA256SUMS.txt`：归档时源文件哈希。
- `GIT_STATUS.txt`：归档时工作树状态。

新架构：

- `SuperbWarfare:docs/physics/ARCHITECTURE.md`
- `SuperbWarfare:docs/physics/CONSTRAINTS.md`
- `SuperbWarfare:docs/physics/IMPLEMENTATION_PLAN.md`
- `KaguyaPyCore:docs/CORE_SCOPE.md`
- `KaguyaPyCore:docs/TRACK_RUN_API.md`
- `KaguyaPyCore:docs/PHYSICS_INTERPOLATION.md`

归档中的高度场、探针、楔面和预处理履带方案已撤销，不得重新作为默认路径。

归档副本是原样快照，其中的相对链接和阶段编号属于当时状态，可能无法在当前目录解析。
需要追溯时以 `SHA256SUMS.txt` 和 `GIT_STATUS.txt` 为准，不修改归档正文。

已知限制：`.opencode/skills/` 在归档时是未跟踪目录，旧 Skill 正文已被就地改写，没有单独
快照。旧 Skill 的职责映射记录在 `docs/physics/SKILLS.md`，旧方向由
`docs/physics/DECISIONS.md` 的撤销条目覆盖。不要把当前 Skill 当作旧版本副本。
