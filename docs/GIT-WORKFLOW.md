# Git 仓库规范

> 适用范围：`MCServerManager`（工作仓库）与 `MCServerManager.git-backup`（本地裸镜像）。
> 最后更新：2026-10-19

## 1. 仓库结构

| 路径 | 作用 |
|---|---|
| `MCServerManager/` | 工作仓库，日常开发、提交、打标签都在这里 |
| `MCServerManager.git-backup/` | 裸镜像（bare），只做备份，**不要在其中直接改文件或提交** |

镜像由 `.githooks/` 下的钩子自动维护，是本地第二份完整历史，不是工作副本。

## 2. 分支

- `master`：唯一长期分支，日常提交与发布都落在这里。
- `safety/*`：历史保护分支（3 个），只读保留；新的安全检查点一律用标签，不再新建分支。
- 远端目前只有 `master`；`safety/*` 不上推。

## 3. 提交信息

格式：`<type>(<scope>): <subject>`

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 不改变行为的重构 |
| `perf` | 性能优化 |
| `test` | 测试 |
| `docs` | 文档 |
| `build` / `ci` | 构建、依赖、流水线 |
| `chore` | 杂项 |
| `release` | 版本发布 |

- subject 中英文均可，不超过 50 字，不以句号结尾。
- body 写清动机、影响面、验证方式；破坏性变更用 `BREAKING CHANGE:` 起段。
- 示例：

```
feat(stun): STUN 直连会话自愈与并发探测
fix(port): 端口真源统一到服务端配置文件，修复界面显示连不上的端口
```

## 4. 标签

| 用途 | 格式 | 类型 | 示例 |
|---|---|---|---|
| 发布 | `vMAJOR.MINOR.PATCH` | annotated | `v1.3.0` |
| 安全检查点 | `backup/<slug>-YYYYMMDD` | annotated | `backup/pre-git-normalize-20261019` |
| 历史检查点 | `checkpoint-<slug>-YYYYMMDD` | annotated | `checkpoint-before-r8-performance-20260812` |

规则：

- 新发布标签必须 `v` 开头；CI 仅在 `v*` 标签上触发构建。
- 发布标签指向“可发布状态”：一般是版本号提交；若发布后补了同版本修复，允许前移标签，但必须 force-push，并立即运行 `tools/sync-git-backup.ps1`（`git tag -f` 不触发任何钩子）。
- 旧式无 `v` 标签（`1.0.9` / `1.1.0` / `1.1.1` / `1.1.3` / `1.1.5` / `1.1.6` / `1.1.7`）已推送且有远端引用，**冻结保留**，不删除、不改名，避免 CI 与外部下载链接失效。
- 标签消息写清原因，方便 `git tag -n` 直接阅读；不要用标签承载可变的构建产物。

## 5. 备份镜像

- 路径：`../MCServerManager.git-backup`（裸仓库）。
- 钩子（`.githooks/`，`core.hooksPath` 已指向该目录）：
  - `post-commit`：每次提交后同步；
  - `post-rewrite`：amend / rebase 后同步；
  - `pre-push`：推送前同步，覆盖 `git tag -f` 这类不触发提交钩子的操作。
- 同步内容：`refs/heads/*`、`refs/tags/*`，以及 `refs/stash → refs/backup/stash`。
- 日志：仓库根目录 `.git-backup.log`（已 gitignore）。
- 手动同步与校验：

```powershell
pwsh tools/sync-git-backup.ps1   # 拉取并逐引用比对，不一致时返回 1
pwsh tools/list-git-nodes.ps1    # 列出主库与镜像的全部节点
pwsh tools/list-git-nodes.ps1 -BackupOnly   # 只看备份/检查点/stash
```

恢复方式：

| 场景 | 命令 |
|---|---|
| 查看节点中的文件 | `git show <tag>:path/to/file` |
| 临时切到节点 | `git switch --detach <tag>` |
| 恢复单个文件 | `git restore --source=<tag> -- path/to/file` |
| 从镜像克隆 | `git clone MCServerManager.git-backup restored` |

## 6. 发布流程

1. 修改 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 提交：`git commit -m "release(x.y.z): ..."`。
3. 打标签：`git tag -a vX.Y.Z -m "Release x.y.z"`。
4. 推送：`git push origin master --follow-tags`（pre-push 会先同步镜像）。
5. 校验：`pwsh tools/sync-git-backup.ps1`。

## 7. 仓库配置

- `core.hooksPath = .githooks`
- `fetch.prune = true`
- 已移除失效的 `http.proxy = http://127.0.0.1:7897`（直连 GitHub 正常）。需要代理时再执行：
  `git config http.proxy http://127.0.0.1:7897`

## 8. 2026-10-19 整理记录

| 项 | 处理 |
|---|---|
| `v1.3.0` 主库与镜像不一致 | 主库与远端均为 `d66af82`，镜像已强制同步到 `d66af82` |
| stash 未进镜像 | 打标签 `backup/stash-rollback-import-20260817`，并镜像为 `refs/backup/stash` |
| 8 个孤儿 `.idx`（有索引无 pack） | 已删除；`count-objects` 的 garbage 归零，`fsck` 通过 |
| 失效代理 | 移除 `http.proxy`，恢复直连 |
| 整理前保护点 | 新增 `backup/pre-git-normalize-20261019`，指向整理前的 master |
| 行尾与编码 | 新增 `.gitattributes`、`.editorconfig`，索引统一 LF |

## 9. 不要做的事

- 不要执行 `git add --renormalize .`，避免制造全仓库行尾 diff。
- 不要在镜像仓库里直接提交或改文件。
- 不要删除、改名已推送的发布标签；确需修正时先沟通，再 force-push 并同步镜像。
- 不要执行 `git gc --prune=now`：仓库里仍保留悬空对象（截至整理时有 30 个），包括 3 个已丢弃的 stash（`review-fixes`、`zstd-fix`、`zstd-fix-partial`）和改写历史前的旧 release 提交。可用 `git fsck --unreachable` 查看；需要长期保留时，给对应提交补 `backup/` 标签。
