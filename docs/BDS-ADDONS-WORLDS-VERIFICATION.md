# BDS 世界 / 附加包 / 资源包 / 种子 —— 技术验证报告

> 验证对象：**Minecraft Bedrock Dedicated Server 1.26.63.1（Windows 版，官方原包）**
> 跨版本复核：**1.19.83.01 / 1.20.81.01 / 1.21.51.02 / 1.21.100.6**（Windows 版，全部官方原包，见 §11）
> 辅助证据：1.21.51 / 1.19.83 官方 linux 包内二进制字符串
> 验证日期：2026-11-08　验证目录：`_bds_verify/`（独立目录，未触碰 Minexa 任何数据）
> 结论口径：**只把实测到的东西写成"已验证"**；无客户端参与、无法观测的项一律标 `未验证`。

---

## 0. 结论速览

| # | 功能 | 可行性 | 关键依据 |
|---|---|---|---|
| 1 | 附加包安装 / 识别 / 启用 / 禁用 / 删除 | ✅ 可行 | 包放 `behavior_packs/`、`resource_packs/`（或世界目录内同名子目录），在世界 `world_behavior_packs.json` / `world_resource_packs.json` 里登记 `header.uuid` + `header.version` 即生效，控制台 `Pack Stack - [nn]` 逐条打印（**1.19/1.20 无此行**，见 §11） |
| 2 | manifest 解析（UUID / 版本 / 依赖） | ✅ 可行 | `format_version` + `header{uuid,version,min_engine_version}` + `modules[].type`；缺 `uuid` / `min_engine_version` 会被拒并给出原文错误 |
| 3 | 依赖关系处理 | ✅ 可行（BDS 会自动补齐） | 只登记依赖方时，被依赖包自动进入 Pack Stack；依赖版本写错则依赖包**静默不加载** |
| 4 | 强制资源包 `texturepack-required` | ✅ 可行 | 每启动一次 BDS 就把 `server.properties` 的值写进 `level.dat(texturePacksRequired)`，properties 是唯一真相源 |
| 5 | 多资源包同时使用 | ✅ 可行（服务端无报错） | 多条目数组正常启动、无警告；**客户端侧加载顺序/覆盖效果无法在服务端观测 → 未验证** |
| 6 | 自定义世界种子（数字 / 文本） | ✅ 可行 | 纯数字在 int64 内取字面值；否则取 **Java `String.hashCode`**（实测 5 组全中） |
| 7 | 读取已有世界种子 | ✅ 可行 | `worlds/<世界>/level.dat` 小端 NBT，8 字节头，`RandomSeed`(Long) 直接可读 |
| 8 | 创建世界时设置生成参数 | ✅ 可行（需改 `level.dat`） | `Generator`: 1=普通、2=超平坦、5=虚空 均实测；`FlatWorldLayers` 自定义层数实测生效。**新世界只需预写 5 个字段的 `level.dat`，不用两段式**（§12） |

**一句话**：三项需求全部可行，但**没有一项能只靠 `server.properties` 完成** —— 至少还要写世界目录里的两个 pack JSON，生成参数还必须写 `level.dat` 二进制 NBT。

---

## 1. 验证环境与方法

| 项 | 值 |
|---|---|
| BDS | `bedrock-server-1.26.63.1.zip`（`bin-win`，88.7 MB，4103 条目） |
| 运行方式 | Windows 直接执行 `bedrock_server.exe`，每个场景前置改写 `server.properties`，起服 → 采日志 → `stop` |
| 世界 | `worlds/testseed1`（种子 123456789，普通地形），多场景复用 |
| 探测手段 | ① 控制台 `Pack Stack - [nn]` 行（1.21+ 才有，1.19/1.20 无，见 §11.2）② 行为包内 `functions/*.mcfunction` + 控制台 `function` 命令（跨版本可靠，见 §11.2）③ 命令 `tickingarea add` + `testforblock` 回读方块，用于判定真实地形 ④ 自写小端 NBT 解析器读 `level.dat` |
| 驱动脚本 | `_bds_verify/{run_bds.py,test_packs.py,scenarios*.py,probe_*.py,nbtread.py}`，可重跑 |

> ⚠️ 官方包内 `bedrock_server_how_to.html` 对附加包的说明**已过期**：原文写
> "This is where new behavior packs can be installed. At the moment there's no way of activating them in a level."
> 实测 1.19.83 / 1.21.51 / 1.26.63 二进制里都有 `world_behavior_packs.json`、`world_resource_packs.json` 字符串，登记即生效。
> **不要以 how_to 这段为准。**

---

## 2. 附加包管理

### 2.1 包放置位置（三种，实测都生效）

| 位置 | 作用域 | 实测证据 |
|---|---|---|
| `behavior_packs/<包>` / `resource_packs/<包>` | 全服 | `Pack Stack - [00] BP_alpha ... @ behavior_packs/BP_alpha` |
| `worlds/<世界>/behavior_packs/<包>`（同理 resource） | 单世界 | `@ worlds/testseed1/behavior_packs/BP_alpha`；**同名 uuid 同时存在于两处时，世界内那份胜出**（日志只列世界内路径） |
| `development_behavior_packs/` / `development_resource_packs/` | 全服 | `@ development_behavior_packs/BP_alpha` |

补充实测：
- **目录可以多套一层**：`behavior_packs/nested/BP_alpha` 也能被找到（`@ behavior_packs/nested/BP_alpha`）。社区大量"必须一层"的说法在 1.26 上不成立（但建议仍按一层放，兼容旧版）。
- **`.mcpack` 文件可直接放目录里用**：`@ behavior_packs/BP_alpha.mcpack`，压缩包内 manifest 多套一层也能被找到。
- **`.mcaddon` 不能直接用**：报
  `[Addon] Cannot determine which pack manifest to use: Multiple manifests found at the same directory level in the pack's folder hierarchy.` → 必须先解包再逐包安装。
- **符号链接/目录联接可用**：把 `behavior_packs` 换成指向别处的 junction 后仍正常加载 —— 与 Minexa 现有 run 目录回链方案兼容。

> 结论：**安装流程统一解压成目录**（`.mcpack` 也解压），既避开 `.mcaddon` 硬限制，也避开旧版本是否支持压缩包的未知项。

### 2.2 激活 / 停用：两个世界级 JSON

路径：`worlds/<level-name>/world_behavior_packs.json`、`world_resource_packs.json`

```json
[
  { "pack_id": "11111111-1111-4111-8111-111111111111", "version": [1, 0, 0] },
  { "pack_id": "22222222-2222-4222-8222-222222222222", "version": [1, 0, 0] }
]
```

实测要点：

| 项 | 结果 |
|---|---|
| `pack_id` 用哪个 UUID | **必须 `header.uuid`**；用 `modules[].uuid` → `Configured pack (id: ...) was not found and was ignored` |
| UUID 大小写 | 无关（manifest 写大写、json 写小写，仍匹配成功） |
| 数组顺序 | **即 Pack Stack 顺序**：json 第 1 条 = `[00]`，第 2 条 = `[01]`；换序后日志顺序同步变化 |
| 目录不存在 / uuid 写错 | 只 `WARN ... was not found and was ignored`，服务端照常启动 |
| 文件不是数组（写成对象） | **无任何报错**，等同未登记 |
| 未登记 | `Pack Stack - None`，包完全不生效 |
| 重复条目（同 uuid 两条） | 只加载一次，不报错 |
| BDS 是否会自己写这两个文件 | 新建世界时**不生成**；本次全部测试中 BDS 也未改写它们（`world_*_pack_history.json` 同样未出现）→ Minexa 必须自己维护 |

### 2.3 版本匹配规则（与社区说法不同，逐条实测）

设磁盘上包版本 `I`、世界 json 请求版本 `R`：

| 场景 | 结果 |
|---|---|
| `I=[1,0,0]` `R=[1,0,0]` | 加载 |
| `I=[1,0,0]` `R=[1,0,1]` | 加载（`[00] ... version: 1.0.0`） |
| `I=[1,0,0]` `R=[1,5,0]` | 加载 |
| `I=[1,0,0]` `R=[1,0,99]` | 加载 |
| `I=[1,0,0]` `R=[2,0,0]` | **不加载**（was not found） |
| `I=[1,0,0]` `R=[9,9,9]` | **不加载** |
| `I=[1,5,0]` `R=[1,0,0]` | **不加载**（请求版本低于已装版本） |
| `I=[2,0,0]` `R=[1,0,0]` | **不加载** |

可归纳为：**同 major，且 `I ≤ R` 才算命中**（`I` 只需不比请求的"新"）。这条只影响"世界要的版本能否被满足"；实际加载的永远是磁盘上的 `I`，日志打印的也是 `I`。

> 对 Minexa 的含义：写入 `header.version` 原值永远安全；但**做"版本不匹配就拦住用户"是过度严格**，BDS 自己容忍小版本差异。建议只在「磁盘版本 > 世界请求版本」或「major 不同」时提示"将按磁盘版本加载"。

### 2.4 依赖（`dependencies`）

实测两件事：

1. **BDS 会自动补齐被依赖的包**：只登记 `BP_beta`（依赖 `BP_alpha`），实际 Pack Stack 是
   `[00] BP_beta …` + `[01] BP_alpha …` —— 未登记的依赖被自动拉入。
2. **依赖版本不满足则依赖静默不加载**：把 `dependencies` 里 `BP_alpha` 的版本改成 `[9,9,9]` 后，Pack Stack 只剩 `BP_beta`，**没有任何报错**。

> 对 Minexa 的含义：依赖检测属于"提前告知"价值（避免用户以为生效），不能依赖 BDS 报错；同时**仍应显式登记依赖**，否则客户端的资源包下载清单里可能缺这一份（`未验证`）。

### 2.5 manifest 解析容错（决定能不能给出可读错误）

| manifest 情况 | BDS 行为 |
|---|---|
| 正常 `format_version:2` | 加载 |
| 带 UTF-8 BOM | 正常加载（BOM 容错） |
| 缺 `header.uuid` | `The following issues were found when loading packs: Missing '/header/uuid' element in pack manifest.` + 包**不加载** |
| 缺 `header.min_engine_version` | 同上，`Missing '/header/min_engine_version' element in pack manifest.` + 包**不加载** |
| 缺 / 为 0 的 `format_version` | 仅 `[Packs] Pack Manifest version 0 is deprecated, please include format_version 2 in your manifest.json.`，不影响激活 |
| 非法 JSON | 同上"version 0 deprecated"警告，包不加载 |

> 这两条 `Missing ... element` 是 Minexa 做"入库前体检"的现成判据，错误文案可直接展示给用户。

---

## 3. 强制资源包（`texturepack-required`）

- 官方 how_to 原文：`texturepack-required`　`true, false`　默认 `false`　
  "If the world uses any specific texture packs then this setting will force the client to use it."
- **实测同步方向**：`server.properties` → `level.dat`。
  - 设 `texturepack-required=true` 启动 → `level.dat` 的 `texturePacksRequired` 由 `0` 变 `1`；
  - 再设 `false` 启动 → 变回 `0`。
  - 结论：**`level.dat` 只是镜像，每次启动被 properties 覆盖** → Minexa 只维护 properties 即可。
- **服务端对资源包不做任何内容校验**：把资源包 `blocks.json` 写成引用不存在的贴图、开 `content-log-level=verbose`，控制台**一行日志都没有**。资源包激活与否，服务端侧没有任何正向信号。
- 唯一能看到的资源包相关错误是内置包缺失：
  清空 `resource_packs/` 后启动 → `ERROR Failed to load Vanilla Resource Pack, is the vanilla pack missing from the loaded world?`
  → **Minexa 绝不能清空 `behavior_packs/` / `resource_packs/`，官方包（vanilla、chemistry 等）就在里面。**
- 多资源包：登记两条数组后启动无警告、无报错；顺序语义在服务端观测不到 → 顺序/覆盖 `未验证`。
  第三方文档自相矛盾（一说"后写的覆盖先写的"，一说"最上面的优先"），**Minecraft Wiki 原文是 "the highest pack will be prioritized over any following packs"**，含义仍是"靠前优先"。这一条不要写死进产品文案。

> 对 Minexa 的含义：UI 只能显示"已登记强制资源包 N 个 / 已开启强制"，**不能显示"已加载"**（没有判据）。开 `texturepack-required=true` 但资源包清单为空时，应提示"客户端会收到空的资源包要求"，而不是让它静默生效。

---

## 4. 世界种子与生成参数

### 4.1 种子在哪、怎么读

- 位置：`worlds/<level-name>/level.dat`
- 格式：**8 字节头 + 小端 NBT**（`version=10, length=N` 两个 int32，其后为 `TAG_Compound`），已用自写解析器实测读出。
- 相关字段：

| 字段 | 类型 | 实测值/含义 |
|---|---|---|
| `RandomSeed` | TAG_Long(4) | 123456789（= 输入值）；文本种子存的是哈希值 |
| `Generator` | TAG_Int(3) | 1=普通、2=超平坦、5=虚空（三种都实测过地形） |
| `LevelName` | TAG_String(8) | 世界显示名；**BDS 不会因为目录改名而同步它** |
| `FlatWorldLayers` | TAG_String(8) | 超平坦层配置 JSON（默认层：bedrock×1 / dirt×2 / grass×1） |
| `texturePacksRequired` | TAG_Byte(1) | 由 `texturepack-required` 每次启动覆盖 |
| `hasLockedBehaviorPack` / `hasLockedResourcePack` | TAG_Byte | 本次测试恒为 0 |

- 另有 `worlds/<世界>/levelname.txt`（纯文本世界名，BDS 创建世界时写入）。

### 4.2 数字 / 文本种子的真实规则（5 组实测，全部命中）

| 输入 `level-seed` | `level.dat` 的 `RandomSeed` | 规则 |
|---|---|---|
| `123456789` | `123456789` | 字面值 |
| `-42` | `-42` | 字面值（负数可用） |
| `9223372036854775807` | `9223372036854775807` | int64 上界 |
| `+42` / `" 42 "` | `42` | 允许 `+` 号、首尾空白被 trim |
| `9223372036854775808` | `-1773151197` | 溢出 → 字符串哈希 |
| `hello` | `99162322` | 字符串哈希 |
| `Hello World` | `-862545276` | 字符串哈希 |
| `1.5` | `48568` | **小数当文本处理**（= `hashCode("1.5")`），不是浮点 |
| `99999999999999999999` | `1260560192` | 同哈希 |

哈希算法已逐值核对 = **Java `String.hashCode`（32 位有符号）**：

```kotlin
fun resolveSeed(raw: String): Long {
    val t = raw.trim()
    t.toLongOrNull()?.let { return it }        // 与 Java Long.parseLong 一致（接受 '+'）
    var h = 0
    for (c in t) h = 31 * h + c.code          // Int 自然回绕
    return h.toLong()
}
```

注意：**文本种子不可逆**——存进 `level.dat` 的只有哈希值，33 位以上的文本还会碰撞。要"原样显示用户输入"，只能由 Minexa 自己额外记录；否则只能显示 32/64 位整数。

### 4.3 种子只在"建世界"时生效（重要）

- 世界已存在时改 `level-seed`：**`RandomSeed` 不变**（123456789 → 设 987654321 → 仍是 123456789）。
- 预先放好 `level.dat` 又同时写 `level-seed=555`：以 `level.dat` 为准（42 保持），且控制台**不打印** `CREATING VANILLA WORLD`。
- 因此"重新生成同一世界"的唯一正确做法：**删/移走世界目录**，再让 BDS 重建。
  → Minexa 现有 `clearWorldSeed()` 的"清空种子"文案会误导用户，需要改为"清空后仅对**新建**世界生效"。

### 4.4 生成参数怎么配（两段式，已实测跑通）

`server.properties` 里**没有** `level-type` / `generator` 一类键（完整 key 列表见启动日志，只有 `level-name`、`level-seed`）。生成类型只能写 `level.dat`。

实测流程（`probe_twophase.py`）：

1. 写 `level-seed=hello` → 起服 → BDS 生成 `db/ + level.dat + levelname.txt`，`RandomSeed=99162322`；
2. 停服，把 `level.dat` 的 `Generator` 改成 `5`，**删掉 `db/`**；
3. 再起服 → `tickingarea add` + `testforblock 0 -64 0 bedrock` → `The block at 0,-64,0 is Air` → **虚空世界生成成功，种子仍是 99162322**。

其它已验证参数：
- `Generator=2`（超平坦）：`testforblock 0 -64 0 bedrock` ✓、`0 -61 0 grass_block` ✓、`0 -60 0` 为空气 ✓。
- **自定义层生效**：把 `FlatWorldLayers` 里 dirt 的 `count` 由 2 改 6 → `-63..-58` 全是 dirt、`-57` 是 grass_block、`-56` 空气，全部命中。
- `Generator=1`（普通）：`-64` 是基岩，`-61` 不是草方块（对照）。

> 技术要点：`tickingarea add` 是**无客户端情况下让服务器真正生成区块**的关键；否则 `testforblock` 一律报 `Cannot test for block outside of the world`。

> ⚠️ **本节的"两段式"不是必需的**（2026-11-09 补测推翻）：世界目录里先放一份**只含 5 个字段**的 `level.dat`，BDS 首次启动就直接按它生成地形，无需先跑一遍普通世界。见 §12。

### 4.5 世界目录改名的坑

把 `worlds/testseed1` 整个复制成 `worlds/renamedworld` 并把 `level-name` 指过去，起服后：
`levelname.txt` 仍是 `testseed1`，`level.dat` 的 `LevelName` 仍是 `testseed1` —— **BDS 不会同步**。
→ Minexa 做"导入世界 / 重命名世界"时必须自己重写 `levelname.txt`（`level.dat` 的 `LevelName` 建议同步）。

---

## 5. 与 Minexa 现有实现的差距

| 编号 | 现状（文件:行） | 差距 |
|---|---|---|
| G1 | ~~`WorldManager.isBedrock()` = `PowerNukkitXLayout.isPowerNukkitX()`（只认 PNX 的 jar）~~ **已修复（2026-11-09）** | 原实现里「是不是基岩布局」和「level-name 存在哪」共用一个判据：BDS 目录既没有 jar 也没有 `pnx.yml`，被判成"非基岩"→ 世界根取成服务端根目录 → **世界列表对 BDS 恒为空**。现拆成 `usesWorldsDir()`（`ServerCore.isBedrock`，按目录 mtime 缓存）+ `usesPnxConfig()`（仅 PNX）；识别失败时用 `Worlds.looksLikeWorldsContainer()` 按目录形态兜底。`mcp/McpToolRunner.kt:732` 本来就用 `core.core.isBedrock`，现在两边一致 |
| G2 | 全仓无 `behavior_packs` 业务代码（只有安装/备份/run 目录回链） | 附加包管理 0 实现：无扫描、无 manifest 解析、无 `world_*_packs.json` 读写 |
| G3 | `PropertyCatalog.kt:274` 已有 `texturepack-required` 开关 | 只有布尔开关；没有资源包清单、安装/替换/移除、体积展示、"清单为空"提示 |
| G4 | `WorldManager.clearLevelSeed()`：原先 `if (isBedrock) return false`，注释称"PNX 的种子字段没有映射" | **已修复**：改为只在 PNX 下拒绝（`usesPnxConfig`）；BDS 走 `server.properties` 的 `level-seed`，本身支持。注意 BDS 只在该世界尚未生成时读这个值，已有世界改它无效（§4.3） |
| G5 | 无 | 不能读取已有世界种子（需解析 `level.dat`） |
| G6 | `WorldsScreen`：列表/导入/导出/切换/删除/清空种子 | 没有"创建世界"入口，也没有种子输入、生成类型选择 |
| G7 | `Worlds.importWorldZip` | 导入时不重写 `levelname.txt` / `LevelName`（见 4.5）；世界目录内的 `behavior_packs/` 会随目录整体解出（这点是好的） |
| G8 | `BdsInstallRules.PRESERVED_DIRS = {"worlds"}`，`installTree` 只写不删 | 升级安全 ✓；但**未记录官方内置包的 UUID 清单**，将来做"内置/用户包"区分时没有依据 |
| G9 | 4 套 `values`（简中/en/zh-rTW/fa） | 新增功能需同步 4 套键值 |

---

## 6. 推荐实现方案（待确认后落地）

### 6.1 数据层（纯路径逻辑，可单测，不依赖 Android）

1. ~~**修 G1**~~ **已完成（2026-11-09）**：`WorldManager` 不再用 PNX 探测当唯一判据，改为 `ServerCoreDetector.detect().core.isBedrock` + 目录形态兜底，并区分「世界根」与「level-name 存放位置」。`app:compileDebugKotlin` 与 `app:testDebugUnitTest` 全绿，新增 3 条回归用例。
2. 新增 `server/BdsPacks.kt`（object，仅 `java.io`）：
   - `scanPackRoots(serverDir, world)` → 依次扫描 `behavior_packs/`、`resource_packs/`、`worlds/<w>/{behavior,resource}_packs/`、`development_*`；
   - `parseManifest(file)` → `PackInfo(uuid, version, name, minEngine, moduleTypes, deps, dir, scope, builtin)`；容错项对齐实测：BOM 可读、缺 `uuid`/`min_engine_version` 直判不合格并回传原文错误、`format_version` 缺失降级警告；
   - `readWorldPacks(worldDir, kind)` / `writeWorldPacks(...)`：严格 JSON 数组、UTF-8 **无 BOM**、原子替换（临时文件 + rename），写入用 `header.uuid` + `header.version` 原值；
   - `resolveDependencies(pack, all)`：闭包展开 + 缺失/版本不满足标记（只提示不阻断）。
3. 新增 `server/LevelDat.kt`：小端 NBT 读写（8 字节头），`readSeed()/readGenerator()/write(seed, generator, flatLayers, levelName)`。**只做"就地改值 + 保留其余字段"**，不重建整个文件。
4. `WorldManager` 增加：`createWorld(name, seedRaw, generation)`（两段式）、`readSeed(world)`、`rename/import 时同步 levelname.txt`。

### 6.2 UI

- 新增 SubPage `Addons`（从「服务器管理」或「世界」进入），两个分栏：**行为包 / 资源包**；每项显示名称、版本、来源（内置/全服/本世界）、依赖状态、体积；操作：启用/禁用（写 json）、删除（删登记，仍被引用时只删登记并提示）、更新（同 uuid 高版本替换目录 + 更新 json 版本）。
- 「世界」页新增「创建世界」对话框：世界名、种子（数字/文本，为空=随机）、生成类型（普通/超平坦/虚空，默认普通）；超平坦展开层编辑（先只给预设，避免过度设计）。
- 世界卡片增加「种子：123456789（或 文本种子不可还原）」+「生成类型」。
- 「强制资源包」开关挪到资源包分栏顶部，与清单联动；清单为空且开关为开 → 显式警告。
- 全部改动**仅在世界未运行时允许写入**（BDS 只在启动时读取这些文件），运行中给"停止服务器后可修改"的明确文案。

### 6.3 安装流程（统一口径）

```
用户选 .mcpack/.mcaddon/.zip
  → 解压到私有临时目录
  → 递归找所有含 manifest.json 的目录（最多 3 层）
  → 按 modules[].type 分类：data/script=行为包，resources=资源包
  → 校验 header.uuid / min_engine_version（缺则拒绝并显示 BDS 原文错误）
  → 目标作用域选择：全服（behavior_packs/）或 本世界（worlds/<w>/behavior_packs/）
  → 复制到 <root>/<包名>（重名时用 uuid 短码去重）
  → 写入目标世界 world_*_packs.json（header.uuid + header.version）
  → 依赖闭包检查：缺失 → 提示；存在 → 一并登记
```

内置包识别：安装 BDS 时（`BdsInstallRules.installTree` 之后）把官方包清单落到 `serverDir/.minexa/builtin-packs.json`（uuid + 目录名）；UI 默认折叠且只读，避免用户误删导致 `Failed to load Vanilla Resource Pack`。

### 6.4 不建议做的事

- ❌ 清空 / 重建 `behavior_packs`、`resource_packs`（会删掉官方内置包，直接起不来）。
- ❌ 只在 `server.properties` 里做"强制资源包"而不维护清单。
- ❌ 用 `modules[].uuid` 当 `pack_id`；用注释/BOM 写 pack JSON。
- ❌ 认为改 `level-seed` 能改变已存在世界。
- ❌ 依赖 BDS 报错来发现包问题（大量场景是静默忽略）。
- ❌ 在服务端运行中改 packs / 世界 json 并期待立即生效（BDS 无重载路径，`/reload` 只重载函数与脚本）。

---

## 7. 风险与兼容性

| 风险 | 说明 | 处理 |
|---|---|---|
| 官方包被误删 | 实测清空 `resource_packs/` 后 `Failed to load Vanilla Resource Pack` | 内置包只读 + 删除前校验 uuid 不在内置清单 |
| 版本判定 | BDS 只做"同 major 且已装 ≤ 请求"匹配 | 不做硬拦截，只提示 |
| 静默失败 | uuid 错、版本超范围、json 非数组、依赖版本错 —— 全部无错误日志 | Minexa 自己写完后回读校验，UI 标"已登记"而**不是**"已加载" |
| 资源包无正向反馈 | 服务端零日志 | 状态用"已登记 + 路径 + 体积"呈现 |
| 大资源包 | 手机空间 + 客户端下载时长 | 安装前显示体积，超阈值警告 |
| 世界名非法字符 | `level-name` 即目录名 | 复用 `Worlds.sanitize` |
| `level.dat` 写坏 | 二进制、版本相关字段多 | 只改目标 tag，写前备份 `level.dat`，失败回滚 |
| 老版本 BDS | 1.19.83/1.21.51 二进制含同名文件与键（字符串证据） | 目录方案与 JSON 方案版本无关；`.mcpack` 直放不依赖，因为一律解压 |
| `development_*` 目录 | BDS 1.26 启动时自建，且其中包会被加载 | 备份/升级保留列表补上这 3 个目录 |

---

## 8. 未验证清单（不得当作事实）

1. **资源包对客户端的实际下发与强制提示**：无真实客户端，无法观测 `texturepack-required=true` 的弹窗/踢出行为。依据仅为官方 how_to 与社区一致描述。
2. **多资源包的加载/覆盖顺序**：服务端无日志；社区文档互相矛盾。
3. **`world_behavior_pack_history.json` / `world_resource_pack_history.json`**：本次从未生成（无客户端）。是否由 BDS 在客户端连入后写入、内容如何 → 未知。
4. **`.mcpack` 直接可用** 仅在 1.26.63.1 实测；1.19/1.20/1.21 未实跑（二进制里没有 `.mcpack` 字符串，机制可能不同）。**不影响方案**：安装流程一律解压成目录。
5. **Beta APIs / experiments**：脚本类行为包需要世界开启实验项的流程未验证。
6. **`BiomeOverride`、`FlatWorldLayers` 的 `structure_options` / `world_version` 字段**：只验证了层数与方块种类，其余字段含义未验证。
7. **`Generator` 取值 0/3/4** 的含义未验证（只确认 1/2/5）。
8. **世界级 pack 目录与全服同名包冲突时的完整优先级链**（多包混合场景只测了一例）。
9. **官方是否正式文档化 `world_*_packs.json`**：目前只有 wiki/托管商文档，官方 how_to 仍写"无法激活"。
10. **`.mcaddon` 内嵌多个子包时的注册方式**：只验证了「整体放目录被拒」，未验证「解包后是否要逐个登记依赖」的完整场景（依赖自动补齐已单独验证）。

---

## 9. 参考来源

- 官方：BDS 1.26.63.1 包内 `bedrock_server_how_to.html`、`server.properties`（含 `texturepack-required` 注释）、实际运行日志
- 官方 manifest 规范：<https://learn.microsoft.com/en-us/minecraft/creator/reference/content/addonsreference/examples/addonmanifest>
- Minecraft Wiki · Server maintenance（附加包章节，含 `development_*` 用法与 `world_*_packs.json` 示例）：<https://minecraft.wiki/w/Tutorials/Server_maintenance>
- 开源实现参考：`DJStompZone/bdspackmanager`（.mcpack/.mcaddon 解包 + 校验一致性）、`RustyKnight/MinecraftAddonInstaller`（installed/active 区分、`invalid_known_packs.json` 提示）、`bedrock-server-manager`（`list_world_addons` 的 ACTIVE/INACTIVE/ORPHANED 三态模型 —— 本方案状态模型直接借鉴）
- 第三方托管商文档（交叉对照，用于识别社区误传）：winternode、xgamingserver、mc-node（1.26.52 实测）、gravelhost（其中 `allow_addons=true` 一说为**错误**，1.26 的 `server.properties` 无此键）

---

## 10. 复现方式

```bash
cd _bds_verify
python run_bds.py '{"tag":"t1","seconds":60,"online-mode":"false","allow-list":"false",
                    "level-name":"testseed1","level-seed":"123456789",
                    "enable-lan-visibility":"false","server-port":"19301"}'
python scenarios.py s2          # 登记/不登记、module uuid、版本匹配
python scenarios2.py v7         # .mcpack / .mcaddon / 非数组 / 缺目录
python probe_flat.py flatprobe 2 42   # Generator + testforblock 探地形
python probe_twophase.py        # 两段式：建世界 → 改 Generator → 重建（已非必需，见 §12）
python probe_gen.py bds126 1.26.63 2 12345   # 只用最小 level.dat 建超平坦（§12.1）
python probe_gen.py bds126 1.26.63 5 42      # 同上，虚空
python nbtread.py bds126/worlds/testseed1/level.dat   # 读种子/Generator
python mvtest.py bds119 v1.19.83    # 单版本同口径复核（种子/包/tpr/Generator）
python mvrules.py bds121 old121     # 版本匹配 / 世界内包目录（旧版）
python extract_all.py               # 解压全部已下载版本到 bds119/120/121/1210/126
```

---

## 11. 跨版本一致性复核（2026-11-09 补测）

**动机**：Minexa 允许用户选任意历史 BDS 版本，若上述结论只是 1.26 的特性，按 1.26 写死会翻车。
**方法**：每个版本解压到独立目录（`bds119/`、`bds120/`、`bds121/`、`bds1210/`、`bds126/`），跑同一套 `mvtest.py`：
建世界（`level-seed=hello`，`texturepack-required=true`，登记一个含 `functions/packload` 的行为包 + 一个资源包）→ 起服 → 控制台 `function packload` → 读 `level.dat` → 改 `Generator=5` 删 `db/` 再起服 → `tickingarea add` + `testforblock 0 -64 0 bedrock`。

### 11.1 结果（5 个版本全部一致）

| 版本 | `level.dat` 头 | 文本种子 `hello` | `texturePacksRequired` | 行为包真的激活 | `Pack Stack` 日志 | `Generator=5` 虚空 | 改 Generator 后种子保持 |
|---|---|---|---|---|---|---|---|
| 1.19.83.01 | `[10, 2612]` | 99162322 | 1 | ✅ | ❌ 无 | ✅ | ✅ |
| 1.20.81.01 | `[10, 2798]` | 99162322 | 1 | ✅ | ❌ 无 | ✅ | ✅ |
| 1.21.51.02 | `[10, 2869]` | 99162322 | 1 | ✅ | ✅ 格式 A | ✅ | ✅ |
| 1.21.100.6 | `[10, 2917]` | 99162322 | 1 | ✅ | ✅ 格式 A | ✅ | ✅ |
| 1.26.63.1 | `[10, 3025]` | 99162322 | 1 | ✅ | ✅ 格式 B | ✅ | ✅ |

→ **种子哈希规则、`level.dat` 结构、`texturepack-required` 落盘、`world_*_packs.json` 生效机制、`Generator` 取值语义，从 1.19.83 到 1.26.63 完全一致**，可以按一套逻辑实现。

### 11.2 但控制台日志**不能**按固定格式解析

| 版本 | 实际输出 |
|---|---|
| 1.21.51.02 / 1.21.100.6 | `[SERVER] Pack Stack - [00][BP_func] [packid_ver][5555…_1.0.0] [path][behavior_packs/BP_func]` |
| 1.26.63.1 | `Pack Stack - [00] BP_func (id: 5555…, version: 1.0.0) @ behavior_packs/BP_func` |
| 1.19.83 / 1.20.81 | **完全没有这行** |

→ Minexa 若要显示"服务端实际装载了哪些包"，只能用**宽松匹配**（`Pack Stack` 子串 + 从中抠 uuid/路径），或者干脆不解析、只报"已登记"。**判定"包到底生效没有"的跨版本可靠办法**（实测）：

1. 放一个带 `functions/<名字>.mcfunction` 的行为包，控制台执行 `function <名字>`；
2. 未生效 → `ERROR Function packload not found.`；生效 → `INFO Successfully executed 1 function entries.`
3. 这套反证在 5 个版本上全部成立，且不依赖日志格式（`function` 命令需 `allow-cheats=true`）。

### 11.3 旧版本的"静默"程度更高

在 1.19.83 / 1.21.51 上补测三条规则（`mvrules.py`）：

| 场景 | 1.19.83 | 1.21.51 | 1.26.63 |
|---|---|---|---|
| 登记请求版本 `[9,9,9]`，磁盘 `[1,0,0]` | 不激活，**无任何提示** | 不激活，**无任何提示** | 不激活 + `WARN … was not found and was ignored` |
| 登记请求版本 `[1,0,1]`，磁盘 `[1,0,0]` | 激活 | 激活 | 激活 |
| 包只放 `worlds/<世界>/behavior_packs/` | 激活 | 激活 | 激活 |

→ **只有 1.26 会告诉你"配置的包没找到"；1.19~1.21 连警告都没有。** 写坏一个 uuid，用户看到的就是"包没生效，但服务器一切正常"。
→ 这直接决定 Minexa 必须自己回读校验 `world_*_packs.json`，不能把"服务端没报错"当成写对了。
→ 版本匹配规则（同 major 且 已装 ≤ 请求）在旧版同样成立，`[1,0,1]` 仍能命中 `[1,0,0]`。

---

## 12. 离线建世界与压缩包安装（2026-11-09 晚补测）

### 12.1 推翻原结论：新世界不需要"两段式"

§4.4 的两段式（先让 BDS 生成 → 改 `Generator` → 删 `db/` → 再启动）**不是必需的**。

在世界目录里预先放一份 `level.dat`，只写 5 个字段即可：

| 字段 | 类型 | 值 |
|---|---|---|
| `LevelName` | String | 世界名 |
| `RandomSeed` | Long | 种子（不写 = 服务端随机） |
| `Generator` | Int | 1 普通 / 2 超平坦 / 5 虚空 |
| `StorageVersion` | Int | 10 |
| `FlatWorldLayers` | String | 仅超平坦需要，JSON |

BDS 首次启动**直接按它生成地形**，随后自己把 `NetworkVersion`、`InventoryVersion`、
`lastOpenedWithVersion` 等 100+ 字段补齐写回，`RandomSeed` 保持不变。
`NetworkVersion` / `InventoryVersion` **故意不写**：实测把 1.26 的值喂给 1.19 核心也会被覆盖，
写死反而给旧核心塞了不属于它的协议号。

验证（`probe_gen.py`，命令 `tickingarea add` + `testforblock` 回读真实方块）：

| 版本 | 超平坦（`Generator=2`） | 虚空（`Generator=5`） | 种子保持 |
|---|---|---|---|
| 1.19.83.01 | `-61` 草方块 ✓ / `-60` 空气 ✓ | `-64` 空气 ✓ / `0` 空气 ✓ | 12345 ✓ |
| 1.20.81.01 | 同上 ✓ | — | ✓ |
| 1.21.51.02 | 同上 ✓ | — | ✓ |
| 1.21.100.6 | 同上 ✓ | — | ✓ |
| 1.26.63.1 | 同上 ✓ | 同上 ✓ | ✓ |

自定义层 `bedrock, stone*3, glass*2` → `-64` 基岩 ✓、`-61` 石头 ✓、`-59` 玻璃 ✓
（层序**自下而上**、`count` 累加，顶面 = `-64 + Σcount - 1`）。

> Kotlin 实现（`BdsWorldGen` + `LevelDat`）写出的文件另外单独喂给真实 BDS 1.26.63.1 跑了一遍：
> 超平坦 / 虚空 / 自定义层三种地形全部命中，确认不是只有试验脚本能跑通。

### 12.2 压缩包安装（`.mcpack` / `.mcaddon`）

| 事实 | 依据 |
|---|---|
| `.mcaddon` **不能**直接放进 `behavior_packs/` | BDS 报 `Multiple manifests found at the same directory level` |
| `.mcpack` 在 1.26 可以直接放目录用 | §2.1；但旧版不确定，且无法精确控制登记 → 统一解压 |
| 一个 mcaddon 里可含多个包 | 按 manifest 的 `modules[].type` 分派到 `behavior_packs` / `resource_packs` |
| `subpacks/` 内层的 manifest 属于同一个包 | 只取**最外层** manifest，否则会被当成两个冲突的包 |

Minexa 的实现（`BdsPacks.installArchive`）：只取最外层 manifest、按类型分目录、
剥掉包内前缀路径、拒绝 `../` 与绝对路径、解压总量与条目数设上限（防 zip 炸弹）、
同 uuid + 同版本重复安装直接跳过、装成功后把原压缩包移到 `.minexa/imported-archives/`
（**必须等 `ZipFile` 关闭后再移动**，Windows 上句柄未释放会静默失败）。

### 12.3 `texturepack-required` 的联动校验

键本身是 `server.properties` 里的布尔（§3），每次启动同步进 `level.dat:texturePacksRequired`。
Minexa 额外做两条**纯静态**校验（不做任何运行期推断）：

1. 开关为开、但世界 `world_resource_packs.json` 为空 → 提示"不会产生任何效果"；
2. 开关为开、但有条目在磁盘上找不到（orphan）→ 提示"玩家可能卡在下载这一步"。

> 第 2 条的后果**未实测**（无客户端），文案写"可能"，代码注释里也标注为未验证。

### 12.4 仍未验证

- 客户端侧的强制下载弹窗 / 拒绝进服的具体表现（无客户端）；
- 多资源包的加载与覆盖顺序；
- `Generator` 取 0 / 3 / 4 的含义；
- 预写 `level.dat` 时若同时给 `NetworkVersion` 与核心版本不一致，是否有副作用（当前实现不写该字段，规避）。
