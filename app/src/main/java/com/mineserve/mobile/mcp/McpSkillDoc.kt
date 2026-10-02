package com.mineserve.mobile.mcp

/**
 * 导出给 AI 用的「技能」文档（SKILL.md）。
 *
 * 两点设计取舍：
 *  - 工具清单**由 [McpToolCatalog] 现算**，不手抄。手抄的清单一定会随版本漂移，
 *    而这份文档正是给 AI 看的「能力边界」，写错了比没有更糟。
 *  - 端点与令牌在导出时写入，用户把文件交给 AI 就能直接连上，不必再照屏幕手抄配置。
 *    因此这份文件含凭据，导出后应只交给可信的 AI 客户端。
 */
object McpSkillDoc {

    /** 导出文件名（多数 MCP 客户端的技能约定文件名）。 */
    const val FILE_NAME = "SKILL.md"

    /** 多行内容用占位符替换，避免参与 trimMargin 的公共缩进计算而被吃掉缩进。 */
    private const val TOOLS_SLOT = "@@TOOLS@@"
    private const val COUNT_SLOT = "@@COUNT@@"

    /**
     * 生成技能文档。
     *
     * @param endpoint MCP 端点，例如 `http://192.168.1.99:8931/mcp`
     * @param token 访问令牌；为空时给出「尚未生成」的提示，而不是留一个空的 Bearer
     */
    fun build(
        endpoint: String,
        token: String,
        tools: List<McpToolDef> = McpToolCatalog.definitions()
    ): String {
        val tokenValue = token.ifBlank { "尚未生成：请先在 App 的 MCP 页面开启服务" }
        val body = """
            |---
            |name: minexa-mobile
            |description: 用 MCP 驱动 Minexa —— 手机上的 Minecraft 服务端 App。当用户要求在这台手机上「开服 / 装服务端核心 / 装插件模组 / 启停服务端 / 查看控制台与崩溃日志 / 改服务器配置 / 备份还原世界 / 排查启动失败」，或给出 Minexa 的 MCP 端点（形如 http://192.168.x.x:8931/mcp 加 Bearer 令牌）时使用。能力覆盖环境依赖安装、核心下载与切换、启停与就绪等待、控制台指令、服务器目录读写、插件/模组管理、快照与世界备份、日志与崩溃诊断。
            |---
            |
            |# Minexa · AI 操作手册
            |
            |手机上的 Minecraft 服务端 App（Java 系 Vanilla / Spigot / Paper / Forge / NeoForge / Fabric，
            |基岩系 PowerNukkitX）。应用内置一个 MCP 服务端，下面所有能力都通过它调用 ——
            |不需要 shell，也不需要 adb。
            |
            |## 一、连接
            |
            |现成的客户端配置（端点与令牌在导出时已写入）：
            |
            |```json
            |{
            |  "mcpServers": {
            |    "mineserve": {
            |      "type": "http",
            |      "url": "$endpoint",
            |      "headers": {
            |        "Authorization": "Bearer $tokenValue"
            |      }
            |    }
            |  }
            |}
            |```
            |
            |- 传输：Streamable HTTP 上的 JSON-RPC 2.0，单端点 `POST`。请求头固定三件套：
            |  `Authorization: Bearer <令牌>`、`Content-Type: application/json`、
            |  `Accept: application/json, text/event-stream`。
            |- 鉴权语义：没带或带错令牌 → 401；带了 `Origin` 头 → 403（这是给浏览器用的防护，
            |  命令行工具不会命中）；调用不存在的工具 → -32602。
            |- 端点默认只监听 `127.0.0.1`。要让**另一台设备**上的 AI 连进来，必须先在 App 里打开
            |  「允许局域网访问」，否则局域网地址根本不通。
            |- 令牌可在 App 里重新生成；重新生成后旧配置立即失效。
            |
            |## 二、调用姿势
            |
            |### 长任务不要同步等
            |
            |装依赖、下核心、装插件这类操作可能跑几分钟，它们都是**异步任务**：调用后立刻拿到
            |`job-N` 形式的任务 id，再用 `get_job` 轮询。只等一小会就超时的调用方，
            |分不清「失败」和「还没做完」。
            |
            |- 想省一轮往返：给工具传 `wait: true` 与 `timeout_sec`，在宽限期内完成就直接返回结果；
            |- 想全程等：`wait: true` 加足够大的 `timeout_sec`（客户端自身的超时也要跟着放宽，
            |  否则只会拿到空响应）；
            |- 拿不准就轮询 `list_jobs` / `get_job`（`get_job` 可带 `lines` 看任务日志尾部）。
            |
            |### 常用的几条链路
            |
            |1. **从零开一个服**：`get_runtime_info` 看环境 → 缺 Java 就 `install_dependencies` 或
            |   `install_java` → `list_core_versions` 挑版本 → `create_server` → `start_server` →
            |   `wait_for_server {condition:"ready"}` → `get_server_status` 确认在线。
            |   `install_java` 有两套来源：`vendor=Termux`（缺省，apt 装，需要环境就绪）与
            |   `vendor=Fcl`（FoldCraftLauncher 血统的原生 JRE，17/21/25，App 内下载、自带 IPv6、
            |   不需要 Termux 环境）。`get_runtime_info` 里的 `java` 是按 `Termux.Java17` 这种键列出的。
            |2. **装插件 / 模组**：`install_plugin {url}`，或 `search_mods` 加 `install_mod {slug}`；
            |   装完用 `list_plugins` / `list_mods` 核对。服务端运行中安装会被拒（jar 被占用时改动不安全），
            |   先 `stop_server`。
            |3. **改配置**：先 `get_config` 看现状 → `set_config {key, value}` → 再 `get_config` 回读确认。
            |   布尔值接受 true/false/yes/no/on/off/1/0，其余取值会明确报错。
            |4. **日志排查**：`get_console_logs {lines}` 看控制台尾部，`get_last_run_log` 看上一次运行的完整输出，
            |   `get_app_log` 看应用自身日志（安装、修复流程的真实输出都在这里），`wait_for_log {pattern}` 轮询等特征行。
            |   技巧：复现前先 `clear_app_log`，日志就干净了。
            |5. **读写文件**：`list_files` → `read_file` / `write_file`（单次上限 1 MB；更大的文件用
            |   `upload_file` 分块 base64，`append: true` 追加）→ `extract_archive` 解压（自带路径穿越防护）。
            |   路径一律相对服务器根目录，越界会被拒。
            |6. **备份与还原**：`create_snapshot` 打快照（落在 `home/snapshots`）→ `list_snapshots` 查看 →
            |   `restore_snapshot` 还原 → `delete_snapshot` 清理。
            |   快照要挑服务端停止时打，运行中打出来的是撕裂的存档。
            |
            |## 三、边界（别撞墙）
            |
            |- **不能改 MCP 自身的配置**：`set_config` 不接受 `mcpEnabled` / `mcpPort` / `mcpAllowLan` /
            |  `mcpToken` —— 改了会断掉正在用的这条通道，而且没有回退手段。只能让人在 App 界面上改。
            |- **删除类操作要显式确认**：`delete_server` / `delete_file` / `delete_plugin` /
            |  `delete_snapshot` / `restore_snapshot` / `remove_java` / `remove_dependencies`
            |  都必须带 `confirm: true`（没带只是返回一个提示，什么都不会发生）。删之前先想清楚，
            |  快照是先见之明。
            |- **服务端运行中不能**：装卸依赖、换 Java 版本、启停插件或模组、改插件 jar。
            |  这些操作会打死正在运行的 JVM（表现为 SIGKILL / 退出码 137）。
            |- **明文 http**：`http_probe` 与下载通道都支持 `http://`（走裸 socket，绕开 Android 的明文策略），
            |  所以内网 PC 上临时起的文件服务也能用；代价是这类地址只走单流，不参与多线程分片。
            |- **日志有刷盘延迟**：刚发的控制台指令可能几秒后才落进 `logs/`。
            |  校验副作用时先等一会，或直接看 `get_console_logs`。
            |- **别靠猜**：端口通不通用 `check_port`，命令在不在用 `check_command`。
            |
            |## 四、工具清单（$COUNT_SLOT 个）
            |
            |$TOOLS_SLOT
            |
            |---
            |
            |本文件由 Minexa 导出，工具清单按当前版本的 `tools/list` 生成。
        """.trimMargin()
        val toolList = tools.joinToString("\n") { tool ->
            "- `${tool.name}` — ${tool.description.lineSequence().first().trim()}"
        }
        return body
            .replace(TOOLS_SLOT, toolList)
            .replace(COUNT_SLOT, tools.size.toString())
            .trimEnd() + "\n"
    }
}
