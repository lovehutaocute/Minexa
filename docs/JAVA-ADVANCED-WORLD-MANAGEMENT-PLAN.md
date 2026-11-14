# Java 版核心 · 高级世界管理 实现计划书

> 目标：把基岩版（BDS）侧已经落地的「世界 / 附加包 / 资源包」这一套能力，**按 Java 版的真实机制**重做一份。
> 所有技术断言都来自本机实跑，未实测的一律标注"未验证"，不当作事实。
> 配套实测脚本：`_java_verify/`（可重跑）。

---

## 0. 结论摘要

| 结论 | 说明 |
|---|---|
| **Java 侧比基岩侧简单** | 世界创建参数全在 `server.properties`（`level-type` / `generator-settings` / `level-seed`），**不需要**像 BDS 那样预写 `level.dat`，也没有"两段式" |
| **但不能只靠 properties** | 读种子、读生成类型、读数据包启用顺序、改游戏规则，都要解析 `level.dat`（**gzip + 大端 NBT**，与基岩版的小端完全相反） |
| **1.26+（26.x）世界布局已重构** | 根级 `region/`、`DIM-1`、`DIM1`、`playerdata/` 全部消失，改到 `dimensions/`、`players/`、`data/minecraft/`；种子也从 `level.dat` 搬到 `data/minecraft/world_gen_settings.dat` |
| **有一个必须先修的阻塞项** | `ServerProperties` 对 Java properties 的 `\:` 转义"原样透传"，而 Java 服务端**每次启动都会把 `:` 转义写回**；结果 `level-type` 读成 `minecraft\:flat`、`generator-settings` 读成非法 JSON。不修这个，读生成配置必错 |
| **资源包只能一个** | Java 的 `resource-pack` 是单个 URL（官方无多包），`require-resource-pack=true` 时拒绝下载会被踢。与基岩版"多包 + 强制"不是同一模型 |
| **附加包要改名叫数据包** | Java 的等价物是 datapack：`world/datapacks/*.zip`，必须含 `pack.mcmeta`，启用顺序存在 `level.dat:DataPacks` |

---

## 1. 实测环境与方法

| 项 | 值 |
|---|---|
| 核心 | 官方 Vanilla `1.20.4`（Java 17 可跑）、`26.3`（当前最新正式版，需 Java 25） |
| 运行 | Windows 本机直跑 `java -jar server.jar nogui`，独立目录 `_java_verify/j1204`、`j263` |
| 判定手段 | ① 控制台日志（`say` 类命令的回显）② **RCON**（`enable-rcon=true`）③ 自写 **gzip + 大端 NBT** 解析器读 `level.dat` / `world_gen_settings.dat` / `game_rules.dat` |
| 地形验证 | `/execute if block 0 -64 0 minecraft:bedrock run say OK_BEDROCK` 等，看控制台是否出现 `OK_*` |
| 脚本 | `_java_verify/{java_srv.py, probe_java.py, probe_java26.py, probe_java2.py}` |

> 与基岩版的关键差异：Java 服务端**没有** `testforblock`，只能靠 `execute if block` + `say` 回显，或解析 Anvil 区域文件。前者够用。

---

## 2. 逐条实测结论（带证据）

### 2.1 超平坦 + 自定义层：`generator-settings` 生效（1.20.4）

写入：

```properties
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3},{"block":"minecraft:glass","height":2}],"biome":"minecraft:plains"}
level-seed=1234
```

结果（控制台）：`OK_BEDROCK`（y=-64 基岩）、`OK_STONE`（y=-61 石头）、`OK_GLASS`（y=-59 玻璃）全部打印。

写入 `level.dat` 的实际内容（`Data.WorldGenSettings.dimensions["minecraft:overworld"].generator`）：

```
type      = minecraft:flat
settings  = { features:0, biome:"minecraft:plains", lakes:0,
              layers:[{block:"minecraft:bedrock",height:1},
                      {block:"minecraft:stone",height:3},
                      {block:"minecraft:glass",height:2}] }
```

→ **服务端把 properties 的 JSON 原样吃进了世界设置**。层序自下而上，`height` 是层数。

### 2.2 同一个配置在 26.3 仍然有效

26.3 用同一份 properties 起服，`data/minecraft/world_gen_settings.dat` 里：

```
data.seed = 1234
data.dimensions["minecraft:overworld"].generator.type = minecraft:flat
data.dimensions["minecraft:overworld"].generator.settings.layers = [
  {block:"minecraft:bedrock",height:1},{block:"minecraft:stone",height:3},{block:"minecraft:glass",height:2}]
```

并且 `level.dat:Data.spawn.pos = [0, -58, 0]`（平坦层顶面在 -59，出生点就在其上方一格），与地形一致。

> 26.3 的 `execute if block` 命令没有在控制台留下回显，**方块级验证只在 1.20.4 完成**；26.3 依据是"世界生成设置被原样记录 + 出生点高度吻合"。

### 2.3 世界已存在时，改 `level-type` / `level-seed` **无效**（1.20.4）

把 `level-type` 改成 `minecraft:amplified`、`level-seed` 改成 `999` 后重启：

- 地形仍是石头/玻璃（`OK_STONE`、`OK_GLASS` 照旧）；
- `/seed` 返回 `Seed: [1234]`；
- `level.dat` 的 `WorldGenSettings` 完全没变。

→ 与基岩版同一规则：**生成参数只在世界不存在时生效**。换种子只能删世界重建。

### 2.4 世界结构：两代布局（重要）

| | ≤ 1.21.x | 26.x（26.1 起重构） |
|---|---|---|
| 主世界区域 | `world/region/*.mca` | `world/dimensions/minecraft/overworld/region/*.mca` |
| 下界 / 末地 | `world/DIM-1`、`world/DIM1` | `world/dimensions/minecraft/the_nether`、`the_end` |
| 玩家数据 | `world/playerdata/` | `world/players/data/` |
| 游戏规则 | `level.dat:Data.GameRules`（驼峰键 + `"true"/"false"` 字符串） | `world/data/minecraft/game_rules.dat`（`minecraft:snake_case` 键 + 0/1） |
| 世界生成 | `level.dat:Data.WorldGenSettings` | `world/data/minecraft/world_gen_settings.dat`（根 `{data:{...},DataVersion}`） |
| 难度 | `level.dat:Data.Difficulty`（int） | `level.dat:Data.difficulty_settings`（`{difficulty:"easy",hardcore:0,locked:0}`） |
| `level.dat` 字段数 | 41 | 15 |

两代都保留的：`world/level.dat`（gzip+NBT）、`world/datapacks/`、`world/session.lock`。

> 另外还有第三种布局：**Bukkit/Spigot/Paper 把维度拆成同级目录**（`world/`、`world_nether/`、`world_the_end/`）。Minexa 现有的 `Worlds._nether` / `_the_end` 后缀逻辑就是给这种布局写的。26.x 的 Paper 是否仍拆分 **未验证**。

### 2.5 数据包（datapack）

在 `world/datapacks/minexa-test.zip`（内含 `pack.mcmeta` + `data/minexatest/functions/hello.mcfunction`）后重启：

- RCON `/datapack list` → `There are 2 data pack(s) enabled: [vanilla (built-in)], [file/minexa-test.zip (world)]`
- RCON `/function minexatest:hello` → `Running function minexatest:hello`，控制台出现 `DATA_PACK_OK`
- `level.dat:Data.DataPacks` = `{Enabled:["vanilla","file/minexa-test.zip"], Disabled:["bundle","trade_rebalance","update_1_21"]}`

→ 数据包**只要放进目录就自动启用**，启用状态与顺序记录在 `level.dat`。`server.properties` 另有 `initial-enabled-packs` / `initial-disabled-packs`（1.20.4 默认 `initial-enabled-packs=vanilla`）。

### 2.6 资源包（Java 侧）

`server.properties` 里与资源包有关的键（1.20.4 实跑生成的默认文件里全部存在）：

| 键 | 说明（官方语义） |
|---|---|
| `resource-pack` | **单个** URL，包体上限 250 MiB |
| `resource-pack-sha1` | 小写十六进制 SHA-1，不一致时控制台给黄字警告 |
| `resource-pack-id` | 单个 UUID，用于向客户端标识 |
| `resource-pack-prompt` | 拒绝提示语（聊天组件语法） |
| `require-resource-pack` | `true` 时玩家拒绝下载会被**断开连接** |

→ **官方不支持多个资源包**，与基岩版 `world_resource_packs.json` 的数组模型不同。

### 2.7 文本种子 = `String.hashCode`（1.20.4 实测）

`level-seed=hello` 起服后：

- RCON `/seed` → `Seed: [99162322]`
- `level.dat:Data.WorldGenSettings.seed` → `99162322`
- 手算 Java `String.hashCode("hello")` → `99162322`

→ **与基岩版完全一致**（基岩侧已在 1.19.83 ~ 1.26.63 五版本验证过同一值）。
所以 Minexa 现有的 `resolveSeed()` 实现可以直接复用到 Java 侧，不需要第二套算法。

### 2.8 properties 的转义（阻塞项）

服务端启动时会**重写** `server.properties`，并把 `:` 转义：

```properties
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1}],"biome"\:"minecraft\:plains"}
```

而 Minexa 的 `ServerProperties.parse` 注释写明"转义序列（`\:` `\=` `\ ` `\t` `\\`）**不解码也不编码**，原样透传"。

后果：

1. `level-type` 读出来是 `minecraft\:flat`，与 UI 里的选项比较必然不相等 → 生成类型判定错；
2. `generator-settings` 读出来**不是合法 JSON** → `Json.parseToJsonElement` 直接抛，超平坦层编辑器读不出当前值。

---

## 3. Java 与 Bedrock 能力对照

| 能力 | 基岩版（已落地） | Java 版等价物 | 差异/风险 |
|---|---|---|---|
| 世界根目录 | `worlds/<名>/` | `<serverDir>/<level-name>/` | Java 世界与服务端同根，必须靠"目录里有 `level.dat`"识别（Minexa 已有） |
| 新建世界 + 种子 | 预写 5 字段 `level.dat` | `level-name` + `level-seed`（文本种子同样走 `String.hashCode`） | Java 不需要预写 level.dat |
| 生成类型 | `level.dat:Generator` 1/2/5 | `level-type` + `generator-settings` | Java 支持 5 种预设 + 自定义层 + 单生物群系；基岩版只有 3 种 |
| 读种子 | `level.dat:RandomSeed` | `level.dat:Data.WorldGenSettings.seed`（≤1.21）/ `data/minecraft/world_gen_settings.dat`（26.x）/ 运行时 `/seed` | 两代路径不同 |
| 读生成类型 | `level.dat:Generator` | 同上，`generator.type` | 两代路径不同 |
| 附加包 | `behavior_packs` + `world_*_packs.json` | `world/datapacks` + `level.dat:DataPacks` | Java datapack 必须含 `pack.mcmeta` |
| 强制资源包 | `texturepack-required` + 多包数组 | `require-resource-pack` + **单** `resource-pack` URL | 数量模型不同，不能照搬 UI |
| 游戏规则 | 无（BDS 走 properties/命令） | `level.dat:GameRules` / 26.x `game_rules.dat` | 两代 schema 不同（驼峰+"true" vs 命名空间+0/1） |
| 维度 | 无独立维度概念 | ≤1.21 `DIM-1/DIM1`；26.x `dimensions/`；Bukkit 拆分同级目录 | 三种布局都要认 |

---

## 4. Minexa 现状与差距

| 编号 | 现状（文件） | 差距 |
|---|---|---|
| J1 | `ServerProperties`：转义不解码不编码 | **阻塞**：`level-type` / `generator-settings` 读不对（§2.7）。必须先修 |
| J2 | `LevelDat.kt` 只支持基岩版（小端、无 gzip） | Java 的 `level.dat` 是 **gzip + 大端**，且字段都在 `Data` 里；不能复用 |
| J3 | 无读取 Java 世界种子/生成类型的代码 | `WorldsScreen` 目前对 Java 世界不显示任何生成信息 |
| J4 | `WorldManager.createBedrockWorld` 只服务基岩 | Java 侧"新建世界"缺生成类型、超平坦层、单生物群系 |
| J5 | 无 datapack 任何代码 | 缺扫描 / 校验 `pack.mcmeta` / 导入 / 启用停用 / 顺序 |
| J6 | `PropertyCatalog` 有 `resource-pack*` 纯文本项 | 缺 SHA-1 计算、URL 合法性校验、与 `require-resource-pack` 的联动提示、本地文件托管 |
| J7 | `Worlds.detectKind`：`db` > `level.dat` > `region` | 26.x 世界没有根级 `region/`，但仍有 `level.dat` → 目前**仍能识别**（不需要改，但要加回归用例） |
| J8 | `Worlds.includeDimensions`：只认 `_nether` / `_the_end` 同级目录 | 26.x 的 `dimensions/` 在 world 目录内部，导出/删除是整目录操作 → 不影响；但"只重置某个维度"需要新逻辑 |
| J9 | 无世界重命名 | Java 与基岩同理：改目录名不会同步 `level.dat:LevelName` |
| J10 | 4 套 `values` 文案 | 新功能要 4 套同步 |

---

## 5. 分期实施计划

### P0 · 数据层（无 UI，可单测）

1. **`NbtTag` 抽公共模型**：把 `LevelDat.Tag` 提成独立的 `server/nbt/NbtTag.kt`，`LevelDat`（基岩小端）与 `JavaLevelDat`（Java gzip 大端）共用节点模型，避免两份 300 行的重复实现。
2. **`JavaLevelDat`**：
   - `read(File)`：`GZIPInputStream` → 大端 NBT → 返回根复合；
   - `Data` 访问器：`seed` / `generatorType` / `generatorSettings` / `levelName` / `dataPacks` / `gameRules`；
   - **26.x 双路径**：`seedOf(worldDir)` 先看 `data/minecraft/world_gen_settings.dat`，再退回 `level.dat:Data.WorldGenSettings.seed`；
   - 写：沿用"就地改值 + 全量重序列化 + tmp/备份/rename"，**不重建文件**（与基岩版同一条红线）。
3. **properties 转义**：`ServerProperties` 增加 `decode` / `encode`（`\:` `\=` `\ ` `\t` `\\` `\n`），读写双向；`generator-settings` 取值前先解码再 `Json.parse`。
4. **`JavaWorldGen`**：
   - `level-type` 取值表（`minecraft:normal` / `flat` / `large_biomes` / `amplified` / `single_biome_surface`；1.19+ 还接受世界预设 ID —— **预设 ID 列表未验证**，先只暴露前 5 个）；
   - 超平坦层 ↔ `generator-settings` JSON 的双向转换（`layers[{block,height}]` + `biome`），复用基岩版 `BdsWorldGen` 的层编辑语法（`bedrock, dirt*2`），但字段名不同（`height` 而非 `count`）；
   - 校验：层数上限、`block` 必须是 `namespace:path`、`biome` 非空、序列化后**必须是单行 JSON**（properties 值不能换行）。

**验收**：单测覆盖 1.20.4 / 26.3 两个真实世界样本（`_java_verify/j1204/world`、`j263/world` 各拷一份到 `app/src/test/resources/javalevel/`），断言种子、生成类型、层配置、数据包列表都能读回。

### P1 · 新建世界（对齐基岩版对话框）

- 输入：世界名、种子（数字/文本，文本实时显示 `hashCode`）、生成类型（普通/超平坦/放大化/大型生物群系/单一生物群系）、超平坦层。
- 写：`level-name` + `level-seed` + `level-type` + `generator-settings`。
- 文本种子直接复用现有 `WorldManager.resolveSeed()`（§2.7 已证明两版算法一致）。
- **不需要**预写 `level.dat`（实测由服务端自己生成）。
- 运行中禁止（沿用 `requireServerStopped`）。
- 若目标世界已存在 → 拒绝并给出"换种子只能删世界重建"的提示（对应 §2.3）。

**注意**：`amplified` / `large_biomes` / `single_biome_surface` 在**基岩版没有对应物**，UI 文案要说清"仅 Java 版"。

### P2 · 世界信息展示

- 世界卡片上显示：种子、生成类型（`normal`/`flat`/…）、版本（`Data.Version.Name`）、数据包数量、难度。
- 数据来源按布局分支（§2.4），读失败就**不显示**而不是显示 0。
- 26.x 的 gamerules 与难度改用 `difficulty_settings` + `game_rules.dat` 读；**离线写不做**（两套 schema，收益低、风险高），改规则引导用控制台命令（服务端运行时即可）。

### P3 · 数据包管理（对应基岩版"附加包"页）

- 扫描：`world/datapacks/` 下的目录与 `.zip`，解析 `pack.mcmeta`（`pack.pack_format` / `description`），列出启用状态（来自 `level.dat:DataPacks.Enabled`）。
- 校验：**缺 `pack.mcmeta` 直接判"服务端不会识别"**（实测依据：BDS 侧不存在此约束，Java 侧 `pack.mcmeta` 是唯一必需文件）。
- 导入：zip 拷进 `world/datapacks/<名字>.zip`（保持压缩形态，**不要**解压成目录——目录形态也支持，但 zip 更便于删除与版本回滚）。
- 启用/停用：两种途径，**优先改 `level.dat`**（等价于游戏内 `/datapack enable|disable`），或写 `initial-enabled-packs`（只对**新世界**生效，别混用）。
- 顺序：启用顺序 = 加载顺序 = 覆盖顺序（后加载覆盖前者），UI 只读展示，第一版不做拖动排序。

**红线**：服务端运行中不改 `level.dat`；`/reload` 只能热载部分内容，UI 文案不能说"已生效"，只能说"已启用，重启后生效"。

### P4 · 资源包托管（对应基岩版"强制资源包"）

- UI 只允许**一个**包：URL 输入 + SHA-1 自动计算 + 是否强制 + 拒绝提示语。
- 校验链：URL 非空 → 能 HEAD 到（可选）→ 文件大小 ≤ 250 MiB → SHA-1 匹配 → `require-resource-pack` 与"没有配置 URL"冲突时给出警告（对应基岩版 `ad_tpr_warn_empty`）。
- 本地托管：把用户选的 `.zip` 放到服务端目录下的 `resourcepacks/`，生成 `http://<本机IP>:<端口>/...` 是不行的（Java 服务端**没有**内置静态文件服务）。→ **结论：第一版只支持填外部 URL**；"本机托管"需要额外起一个 HTTP 服务，列到 P6 再议。

### P5 · 世界维护操作

- 重命名：改目录名 + 同步 `level.dat:LevelName` + `server.properties:level-name`（若当前世界就是它）。
- 只重置某个维度（删下界/末地）：按三种布局定位（`DIM-1` / `dimensions/minecraft/the_nether` / `world_nether`），删前强制备份。
- 世界格式升级不可逆 → 任何"重置/删除"前一律 `ExternalBackupStore` 打包（现有逻辑可直接复用）。

### P6 · 延后项（需额外实测）

- Paper / Purpur 的 per-world 配置（`config/paper-world-defaults.yml`、`<world>/dimensions/<ns>/<key>/paper-world.yml`）——路径随 26.x 布局变过，**必须在 Paper 上实跑确认**。
- 内置 HTTP 服务托管资源包。
- 世界预设 ID（1.19+ 的 `level-type` 世界预设）——需要枚举并逐版本核对。

---

## 6. 设计红线（不要为赶功能而破坏）

1. **服务端运行中一律拒改** `server.properties` / `level.dat` / `datapacks/`。基岩版的 `requireServerStopped` 语义直接复用。
2. **不重建 `level.dat`**：解析成节点树 → 改目标字段 → 原结构重序列化。Java 的 `level.dat` 里有 `Version` / `DataVersion` / `version_history`，乱重建会让服务端判定"世界格式不符"。
3. **布局探测顺序**：`db/` → `level.dat` → `region/` 保持现状；新增"26.x 布局"只能在**读维度/读种子**时分支，不要动世界识别本身（否则老世界会漏列）。
4. **properties 编解码必须成对**：解码后展示/比较，写回时保持"最小转义"（只在值里出现行首空格、`\` 时才转义），否则每次保存都会和服务端的重写打架。
5. **UI 只说"已配置"，不说"已加载"**：Java 侧虽然有 `/datapack list` 可查，但离线态无法确认"下次启动真的会加载"。
6. **删除/重置前必须备份成功**，备份失败一个文件都不删（现有 `ResetResult.backupFailed` 语义）。

---

## 7. 风险与兼容性

| 风险 | 影响 | 处置 |
|---|---|---|
| 26.x 布局继续变（26.1 已经重构过一次） | 读种子/维度的路径失效 | 读取逻辑集中在 `JavaWorldLayout` 一个对象里，按"特征文件存在性"探测而非版本号硬编码 |
| 服务端重写 properties 覆盖用户手写注释 | 用户自助改的配置被抹掉 | 现有 `ServerProperties` 已是保真合并（只改目标行），保持 |
| `level-type` 写了非法值 | 服务端启动失败或回落默认 | 写入前只允许白名单值；`generator-settings` 写前先自己 `Json.parse` 一遍 |
| 世界名含空格/`=`/中文 | properties 解析错位 | 世界名沿用 `Worlds.sanitize`；写 properties 时按 Java 规则转义 |
| Forge/NeoForge/Fabric 模组世界 | 世界目录里有额外文件，删除可能误伤 | 只删明确的维度目录，不做通配删除 |
| 数据包版本不匹配（`pack_format`） | 服务端拒绝加载并报错 | 读 `pack.mcmeta` 的 `pack_format` 与当前版本期望值比对，不匹配时**只警告不拦** |

---

## 8. 验收标准（可执行）

1. 用 `_java_verify/j1204`（超平坦 + 自定义层 + 1 个数据包）作为样本：App 能正确显示 种子=1234、生成类型=超平坦、层=基岩1/石头3/玻璃2、数据包 1 个且已启用。
2. 用 `_java_verify/j263` 作为样本：同样信息能显示（走 `world_gen_settings.dat` 分支），且世界列表**不出现**多余条目。
3. 新建一个 `flat + 自定义层 + 文本种子` 的世界，起服后控制台 `OK_*` 三条全中（复刻 §2.1 的验证脚本）。
4. 对一个已存在的世界改 `level-type`，UI 明确提示"不生效，需删世界重建"。
5. 数据包：导入一个缺 `pack.mcmeta` 的 zip → 明确报"服务端不会识别"；导入合法包 → 重启后 `datapack list` 能列出。
6. 资源包：填 URL + 算 SHA-1 → 服务端启动**无** `Invalid sha1` 黄字；开 `require-resource-pack` 但 URL 为空 → UI 警告。
7. 全量 `testDebugUnitTest` 绿 + `assembleDebug` 通过 + 4 套文案键数一致。

---

## 9. 优先级建议

| 阶段 | 价值 | 复杂度 | 依赖 |
|---|---|---|---|
| P0 properties 转义 + `JavaLevelDat` | 高（其余全部依赖它） | 中 | 无 |
| P1 新建世界（含超平坦层） | 高（用户最常见的诉求） | 低（比基岩版简单） | P0 |
| P2 世界信息展示 | 中 | 低 | P0 |
| P3 数据包管理 | 中高（Java 的"附加包"） | 中 | P0 |
| P4 资源包 | 中 | 低（只填 URL）～高（本机托管） | P0 |
| P5 维护操作 | 中 | 中 | P0 |
| P6 Paper per-world / 内置 HTTP | 低（可选） | 高 | P6 自身需先实测 |

建议顺序：**P0 → P1 → P2 → P3 → P4 → P5**，P6 单独立项。

---

## 10. 未验证清单（不得当作事实）

1. **26.3 的方块级地形验证**：只做了"生成设置被记录 + 出生点高度吻合"，没做 `execute if block` 回显（该版本命令无控制台回显）。
2. **Paper / Purpur 26.x 是否仍把维度拆成 `world_nether` / `world_the_end` 同级目录**。
3. **Paper 的 per-world 配置文件路径**随布局变更后是否仍是 `<world>/paper-world.yml`（官方文档现在写 `world/dimensions/<ns>/<key>/paper-world.yml`）。
4. **`level-type` 接受世界预设 ID 的完整取值列表**（1.19+），未枚举。
5. **`initial-enabled-packs` 的精确格式与版本边界**（只在 1.20.4 见过默认值 `vanilla`）。
6. **Forge / NeoForge / Fabric 世界**是否还保持原版目录结构（未实测）。
7. **客户端侧资源包强制行为**（拒绝后踢人）——官方文档明确写了，但没有客户端实测。
8. **`single_biome_surface` 的 `generator-settings`**：官方说明可配，但具体键名（`biome`）未实测。

---

## 11. 复现方式

```bash
cd _java_verify
# 1.20.4（Java 17）
python probe_java.py j1204          # 超平坦 + 自定义层，跑 execute if block
python probe_java2.py               # 已有世界改 level-type + datapack
# 文本种子：level-seed=hello → /seed 返回 99162322（= String.hashCode）
# 26.3（需 JDK 25，脚本里已指向 jdk25/）
python probe_java26.py j263
```

判定要点：控制台出现 `OK_BEDROCK` / `OK_STONE` / `OK_GLASS` = 地形真的按配置生成；
`datapack list` 里出现 `file/xxx.zip (world)` = 世界级数据包已启用。

---

## 12. 落地记录（P0–P5 已实现）

一次性实现完 P0–P5，P6（Paper per-world / 内置 HTTP）按计划暂缓。

### 12.1 新增文件

| 文件 | 内容 |
|---|---|
| `server/PropertiesEscape.kt` | java.util.Properties 转义编解码（`\:` `\=` `\t` `\n` `\uXXXX`）；写回只做"必要"转义 |
| `server/JavaLevelDat.kt` | Java `level.dat` 读写：**gzip + 大端 NBT**，复用 `LevelDat.Tag` 节点模型；写盘 tmp + `.minexa-bak` + rename |
| `server/JavaWorldGen.kt` | `level-type` 枚举与解析、`generator-settings` 构建/解析（`layers[{block,height}]` + `biome`）、资源 ID 规范化 |
| `server/JavaWorlds.kt` | 两代布局探测、元信息（种子/生成类型/难度/版本/数据包）、数据包扫描与启停、重命名、维度目录定位 |
| `server/JavaResourcePack.kt` | 单包模型：URL 校验、SHA-1 计算/校验、UUID 校验、静态问题提示 |

### 12.2 改动文件

| 文件 | 改动 |
|---|---|
| `server/ServerPropertiesManager.kt` | 读值解码、写值做必要转义（修 P0 阻塞项） |
| `server/WorldManager.kt` | 新增 `isJavaLayout` / `readJavaMeta` / `readJavaFlatLayers` / `createJavaWorld` / `renameJavaWorld` / `javaDimensionDirs` / `resetDimension` / 数据包三件套 / 资源包读写；`CreateFailure` 增加 `NotJava` |
| `ui/McViewModel.kt` | `activeCore` / `isJavaCore()` / `activeJavaWorldMeta` / `javaDataPacks` / `javaDimensions` / `resourcePack` 状态与全部操作 |
| `ui/screens/WorldsScreen.kt` | Java 元信息行、按核心分支的"新建世界"对话框（5 种生成类型 + 生物群系 + 超平坦层）、重命名对话框、维度重置卡片 |
| `ui/screens/AddonsScreen.kt` | 拆成基岩版 / Java 版两套内容；Java 版 = 数据包列表（开关、格式、缺 `pack.mcmeta` 提示）+ 资源包表单（URL/SHA-1/ID/提示语/强制） |
| 4 套 `strings.xml` | 各 +57 条，键数 2373 对齐 |

### 12.3 与计划书的偏差

1. **没有抽出公共 `NbtTag`**：`JavaLevelDat` 直接复用 `LevelDat.Tag` 节点模型，但读/写器各自独立（小端 vs 大端 + gzip）。理由：`LevelDat` 已被单测覆盖且在跑，为了共用 200 行读写器去动它风险大于收益。
2. **`level-type` 只暴露 5 个官方枚举值**，不暴露 1.19+ 的世界预设 ID（列表未验证）。
3. **gamerules 离线写没做**（计划里就写了不做）：两代 schema 差异太大，引导用控制台。
4. **数据包只导入 zip**，不把 zip 解成目录（保持可回滚、易删除）。
5. **资源包不支持本机托管**（服务端不提供静态文件服务），只能填外部 URL；SHA-1 可从未上传的本地文件算出来。

### 12.4 验证

**单测（+24 条，全量 889 条通过）**

| 测试 | 覆盖 |
|---|---|
| `JavaWorldsTest`（11） | 真实 1.20.4 与 26.3 样本：gzip 读、写回读回、两代布局、种子、超平坦层、难度、数据包扫描/启停、重命名、三种维度布局 |
| `JavaWorldGenTest`（13） | `level-type` 解析、单行 JSON 生成、往返、坏 JSON、资源 ID、转义解码/编码互补、服务端转义后的 settings 能被解析 |

**真实服务端回归（关键）**：把 Kotlin 写出的 `level.dat`（改过 `DataPacks.Enabled`）喂给官方 1.20.4：

```
/seed              → Seed: [1234]                       （种子未损坏）
/datapack list     → [vanilla], [file/minexa-test.zip (world)]   （我们的写入被正确识别）
/function minexatest:hello → Running function ...       （数据包真的生效）
控制台错误行 0
```

**构建**：`testDebugUnitTest` 889/889 绿，`assembleDebug` 通过（APK 39.2 MB）。

---

## 13. 信息架构调整（2026-11-10 晚）

用户反馈「加完这些功能后 UI 有点乱」，逐条改进如下。

### 13.1 入口按核心能力给，而不是一律显示

| 核心 | 内容入口 | 说明 |
|---|---|---|
| Paper / Spigot / Purpur / Leaves / Leaf / CraftBukkit / PNX | 模组与插件 | 内含「数据包 / 资源包」分类（PNX 除外，它是基岩模型） |
| Fabric / Forge / NeoForge / Quilt | 模组与插件 | 同上 |
| Vanilla / Velocity / BungeeCord | 数据包与资源包 | 它们不吃插件也不吃模组，原来的入口点进去只有一句"不支持" |
| BDS / PocketMine / Allay | 附加包 | 行为包 + 资源包（多包 + 世界级登记，与 Java 单资源包不是一回事） |
| PNX | 模组与插件 **+** 附加包 | 两种能力都有 |

世界页里那颗「附加包」按钮**已删除**：它是"给服务器装内容"，不属于"管理存档"。

### 13.2 「扩展内容」页（原插件管理）

- 一级分类从 2 个（插件/模组）扩到 4 个（插件/模组/数据包/资源包），全部按核心能力动态出现；
- 标题与眉标跟随能力：插件/模组核心显示「插件与模组」，只剩数据包的核心显示「内容扩展」；
  **不再对只能装数据包的核心喊「插件管理」**；
- 「热重载」「插件资源站点」「配置提示」三块只在插件/模组分类下出现 —— 数据包与资源包不支持
  `/reload`，把它们摆在那是误导；
- 数据包 = 列表卡片（大小 / 格式 / 缺 `pack.mcmeta` 提示 / 启用开关）+ 导入按钮；
  资源包 = 单包表单（URL / SHA-1 / ID / 提示语 / 强制）。

### 13.3 世界页瘦身

原来 5 张卡片（当前世界 / 列表 / 维护 / 维度 / 导出说明）压到 2 张 + 一行注脚：

1. **当前世界**：名字 + 状态徽章（已生成 / 待生成 / 读取失败）+ 字段；
2. **世界列表**：新建、导入收进卡片右上角（两者都是"往列表里加东西"）；
3. 导出/导入的注意事项降级成一行小字，不再单独占卡片。

### 13.4 「种子：尚未生成 / 生成类型：未知」的根因与修法

- 核实：`LevelDat.read` 本身没有问题 —— 把本机 5 个版本、**36 个真实 `level.dat`**（含 BDS 自己
  重写过的、以及我们预写后由 BDS 接手生成过的）全部喂给 Kotlin 读取器，**0 失败**，种子与 `Generator` 全部正确。
- 真正的问题在**数据来源单一**：旧实现只认 `level.dat`，而世界在服务端启动前根本没有这个文件
  （Java 版更彻底：建世界只写 `server.properties`），于是两个字段同时取不到，UI 就吐出两个"未知"。
- 修法：新增 `WorldGenLedger`（`<服务器>/.minexa/world-gen.json`），建世界时记下
  **用户输入的种子原文 + 生成方式 + 核心类型**。UI 变成三态：

| 状态 | 数据来源 | 显示 |
|---|---|---|
| 已生成 | `level.dat`（真相） | 真实种子 / 生成方式（种子为空显示「随机」） |
| 待生成 | 建世界记录 | 明确写"存档还没生成"+ 当时的种子原文与生成方式 |
| 读取失败 | — | 直说 `level.dat` 读不了，不再假装"未知" |

记录随世界改名/删除/重置同步维护；坏文件当空表处理（辅助信息不能拖累世界管理）。

### 13.5 「确定」按钮点了没反应

新建世界对话框的确认键原来是 `enabled = name.isNotBlank()`，名字空着时按钮是灰的、点了毫无反馈，
用户只会以为卡住了。现在：

- 按钮**始终可点**；
- 校验放在点击时，错误用 `isError` + 红字写在输入框下面（名字为空 / 层配置格式错 / 生物群系 ID 非法）；
- 重命名对话框同样处理。

### 13.6 验证

- `testDebugUnitTest` 全量 **895 用例通过**（新增 `WorldGenLedgerTest` 5 条、`BdsWorldGenTest` 1 条）；
- `assembleDebug` 通过（39.2 MB）；
- 4 套文案各 +17 条（`sm_entry_addons` / `wl_state_*` / `plx_*` 等），键数对齐；
- **UI 未在真机/模拟器上跑过**（本机 `emulator-5554` 处于 offline）。安装验证命令：
  `adb install -r app/build/outputs/apk/debug/Minexa-arm64-v8a-debug.apk`

---

## 14. 「选了超平坦却建成普通 / 种子显示随机 / 空白按钮」（2026-11-11）

三条现象是**同一个根因链**，逐个拆开：

### 14.1 空白按钮（③）—— chips 排成一行被裁掉

新建世界对话框的生成方式用 `Row` 横排。Java 有 5 种（普通/超平坦/大型生物群系/放大化/单一生物群系），
中文 label 在 360dp 屏宽下必然溢出；`Row` 不会换行也不会滚动，超出的 chip 只露出边框 →
看起来就是"一个空白按钮"。点它是能选中的，但**看不出自己选中了什么**。

修：改成 `FlowRow` 换行；并在 chips 下面加一行文字「已选：超平坦」，
把选中态用文字再确认一次（chip 的浅色底在小屏 + 大字体下几乎看不出差别）。

### 14.2 选超平坦变普通（①）—— 三条可能的路径，逐条堵死

| 路径 | 现象 | 处理 |
|---|---|---|
| 点了被裁掉的空白 chip，以为选的是超平坦，实际仍是默认「普通」 | 建成普通地形 | FlowRow + 「已选：…」回显（14.1） |
| 预写 `level.dat` 失败（目录/文件写不进去） | 旧实现**静默回退**成"只写 level-name/level-seed"，返回"创建成功" | 改为直接返回新增的 `CreateFailure.LevelDatFailed`，一个文件都不留，并明确报错 |
| 世界已生成后再改生成方式 | BDS/Java 都不会重读 | 文案已写明"生成之后改不了，只能删掉重建" |

> 顺带验证：`BdsWorldGen.createLevelDat` 写完会**回读校验**（`Generator`、`RandomSeed`、`FlatWorldLayers`），
> 校验不过会删掉文件并返回失败，所以不存在"写进去的是 2、读出来是 1"这种情况。

### 14.3 种子显示"随机"（②）—— 把"没读到"说成了"随机"

两个独立错误叠在一起：

1. **"是否已生成"判错了**：`generated` 只看 `level.dat` 存在与否。但我们新建世界时会预写一份
   5 字段的 `level.dat`，它同样叫 `level.dat` —— 于是"用户的要求"被当成"世界的状态"，
   服务端还没启动就显示「已生成」，种子栏因为文件里本来就没有 `RandomSeed` 而落到兜底文案「随机」。
   修：`LevelDat.World.generatedByServer` 只认服务端才会写的字段（`InventoryVersion` / `NetworkVersion`），
   预写文件不算已生成 → 状态变「待生成」，种子显示用户填的原文，没填则显示「未设置（随机）」。
2. **Java 生成类型读不到时兜底成"普通"**：`javaGeneratorLabel(null)` 以前返回「普通」。
   这是纯粹撒谎 —— 读不到就该说读不到。已改为「未知」。

### 14.4 验证

- 真实样本：`bds-1.19.83` 与 `bds-1.26.63.1` 的 `level.dat` 都带 `InventoryVersion`+`NetworkVersion`
  ⇒ 判据对真实世界成立；`BdsWorldGen` 预写的文件两者都没有 ⇒ 判为"待生成"（已加 2 条单测固化）。
- 全量单测 **897 通过**；`assembleDebug` 通过。

### 14.5 已经建错的世界怎么办

生成方式只在**世界首次生成前**有效，BDS 与 Java 版都一样。已经按普通生成的世界改不回来 ——
只能在世界列表里删掉重建（删除前会自动打包备份到「世界备份/世界导出」目录）。
