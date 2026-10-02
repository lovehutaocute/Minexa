package com.mineserve.mobile.server

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 离线崩溃日志分析。
 *
 * 两条互相独立的能力：
 * 1. **结构化提取**（代码内实现）：首个异常、Caused by 链、退出码、涉及的 jar。
 *    这部分是纯解析逻辑，规则不适用。
 * 2. **归因规则**（数据驱动）：把「识别一种崩溃 -> 给出主信息 + 建议操作」的知识
 *    放在 `assets/rules/server_errors.json` 里，新增/修订规则不需要改 Kotlin 代码。
 *    加载顺序：内置 asset -> `filesDir/error_rules` 目录下的 json（用户自定义，同 id 覆盖内置）。
 *
 * 规则永远只是提示，不会代替用户执行任何修复动作。
 *
 * 初始化：[initialize] 由 `McApplication.onCreate()` 同步调用（与 `DownloadPrefs.init`
 * 同一处），把规则与 class 版本表读进内存；未初始化时 [analyze] 只返回结构化信息。
 */
object CrashReportAnalyzer {

    enum class Level { Identified, Possible, Reminder }

    data class Finding(val level: Level, val label: String, val detail: String, val evidence: String, val suggestion: String)

    data class Analysis(
        val title: String,
        val firstException: String?,
        val causedBy: List<String>,
        val exitCode: Int?,
        val components: List<String>,
        val findings: List<Finding>
    ) { val primaryLabel: String get() = findings.firstOrNull()?.label ?: "未能明确归因" }

    // ── 结构化提取用的正则 ────────────────────────────────────────

    private val exception = Regex("""(?:[A-Za-z0-9_.]+)?(?:Exception|Error|Throwable)(?::[^\n]*)?""")
    private val causedBy = Regex("""Caused by: ([^\n]+)""")
    private val exitCode = Regex("""(?:exit(?: code)?|退出码)\s*[:=]\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val component = Regex("""([A-Za-z0-9_.-]+\.(?:jar|zip|class))""")
    private val classVersion = Regex("""(?:class file version|major\.minor version)\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val requiresJava = Regex("""(?:requires|need)\s+Java\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val javaOrHigher = Regex("""Java\s+(\d+)\s+or\s+higher""", RegexOption.IGNORE_CASE)

    /**
     * class 文件版本号 -> Java 主版本。
     *
     * 这是 JVM 规范里的固定对应关系（45 = Java 1.1 … 70 = Java 26），因此以代码常量为准；
     * `server_errors.json` 里的 `classVersions` 若存在则覆盖本表，便于将来 JVM 出新版本时
     * 不改代码就能补一行。
     */
    private val BUILTIN_CLASS_VERSIONS: Map<Int, Int> =
        (45..70).associateWith { it - 44 }

    // ── 规则 ────────────────────────────────────────────────────

    private class Rule(
        val id: String,
        val level: Level,
        val contains: List<String>,
        val regex: Regex?,
        val exitCodes: Set<Int>,
        val label: String,
        val detail: String,
        val evidence: String,
        val suggest: String
    )

    @Volatile
    private var rules: List<Rule> = emptyList()

    @Volatile
    private var classVersions: Map<Int, Int> = BUILTIN_CLASS_VERSIONS

    @Volatile
    private var loaded: Boolean = false

    /**
     * 规则加载过程中的告警出口。
     *
     * 刻意不直接用 `android.util.Log`：本类的规则解析逻辑要在 JVM 单元测试里跑，
     * 而本地单测没有 mock Android API（调用会抛 not-mocked）。由 `McApplication`
     * 在初始化时注入真正的日志实现。
     */
    @Volatile
    var logger: ((String) -> Unit)? = null

    private fun warn(message: String) {
        logger?.invoke(message)
    }

    /** 规则是否已加载（诊断用）。 */
    val isLoaded: Boolean get() = loaded

    /** 当前生效的规则条数（诊断用）。 */
    val ruleCount: Int get() = rules.size

    /** 用户自定义规则目录：`filesDir/error_rules`，放 json 文件即生效。 */
    fun customRulesDir(context: Context): File = File(context.filesDir, "error_rules")

    /**
     * 载入内置规则 + 用户自定义规则。幂等；重复调用会重新加载（便于用户在文件管理器里
     * 改完规则后刷新）。
     *
     * **注意调用时机**：`McApplication.onCreate()` 在主线程同步调用，因此这里只做有界的
     * 文件读取——内置 asset 只有几 KB，自定义规则按 [MAX_CUSTOM_RULE_BYTES] 逐个限额，
     * 超出体积的文件直接跳过，不会把启动路径拖住。
     */
    fun initialize(context: Context) {
        val builtin = try {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            warn("读取内置崩溃规则失败: ${e.message}")
            null
        }
        val custom = try {
            customRulesDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.mapNotNull { f ->
                    if (f.length() > MAX_CUSTOM_RULE_BYTES) {
                        warn("自定义规则 ${f.name} 超过 ${MAX_CUSTOM_RULE_BYTES / 1024} KB，已跳过")
                        return@mapNotNull null
                    }
                    runCatching { f.readText() }
                        .onFailure { warn("读取自定义规则 ${f.name} 失败: ${it.message}") }
                        .getOrNull()
                }
                .orEmpty()
        } catch (e: Exception) {
            warn("扫描自定义规则目录失败: ${e.message}")
            emptyList()
        }
        loadFromJson(builtin, *custom.toTypedArray())
    }

    /**
     * 由 JSON 文本装载规则。
     *
     * 第一个参数是内置规则表；其余（用户自定义规则）按 id 覆盖同名内置规则，
     * 并排在前面优先显示。`classVersions` 是**叠加**语义：以内置表为基底，
     * 各 JSON 里出现的条目逐个覆盖，方便将来补一个新的 JVM 版本号而不用重抄全表。
     */
    fun loadFromJson(vararg jsons: String?) {
        val builtinRules = mutableListOf<Rule>()
        val overrides = linkedMapOf<String, Rule>()
        var versions: Map<Int, Int>? = null
        var parsedAny = false

        for ((index, json) in jsons.withIndex()) {
            if (json.isNullOrBlank()) continue
            val parsed = runCatching { Json.parseToJsonElement(json) as? JsonObject }
                .onFailure { warn("崩溃规则 JSON 解析失败: ${it.message}") }
                .getOrNull()
            if (parsed == null) {
                warn("第 ${index + 1} 份规则 JSON 无法解析")
                if (index == 0) {
                    // 内置规则是主表。它解析不了就整体放弃本次重载，保留上一份成功的规则：
                    // 否则自定义规则会顶掉全部内置规则，崩溃页会大面积退化成「未能明确归因」。
                    warn("内置规则解析失败，保留现有 ${rules.size} 条规则")
                    loaded = true
                    return
                }
                continue
            }
            parsedAny = true
            parseVersions(parsed)?.let { table ->
                versions = (versions ?: emptyMap()) + table
            }
            val ruleList = parseRules(parsed)
            if (index == 0) {
                builtinRules += ruleList
            } else {
                ruleList.forEach { overrides[it.id] = it }
            }
        }

        // 全部输入都解析失败：保留上一次成功加载的规则。语义是「宁可沿用旧规则，
        // 也不要静默退化成零规则」——后者会让崩溃页只剩「未能明确归因」。
        if (!parsedAny) {
            warn("没有任何可解析的规则 JSON，保留现有 ${rules.size} 条规则")
            loaded = true
            return
        }

        // 自定义规则优先显示：先放覆盖项，再放未被覆盖的内置项
        val effective = buildList {
            addAll(overrides.values)
            addAll(builtinRules.filter { it.id !in overrides })
        }
        classVersions = versions?.let { BUILTIN_CLASS_VERSIONS + it } ?: BUILTIN_CLASS_VERSIONS
        rules = effective
        loaded = true
    }

    // ── JSON 解析 ───────────────────────────────────────────────

    private fun parseVersions(root: JsonObject): Map<Int, Int>? {
        val obj = root["classVersions"] as? JsonObject ?: return null
        val map = obj.mapNotNull { (k, v) ->
            val key = k.toIntOrNull() ?: return@mapNotNull null
            val value = (v as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            key to value
        }.toMap()
        return map.ifEmpty { null }
    }

    private fun parseRules(root: JsonObject): List<Rule> {
        val array = root["rules"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val label = obj.str("label") ?: return@mapNotNull null
            val contains = (obj["contains"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            val regex = obj.str("regex")?.let { pattern ->
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                    .onFailure { warn("规则 $id 的正则不合法: ${it.message}") }
                    .getOrNull()
            }
            val exitCodes = (obj["exitCodes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                ?.toSet()
                .orEmpty()
            if (contains.isEmpty() && regex == null && exitCodes.isEmpty()) return@mapNotNull null
            Rule(
                id = id,
                level = parseLevel(obj.str("level")),
                contains = contains,
                regex = regex,
                exitCodes = exitCodes,
                label = label,
                detail = obj.str("detail").orEmpty(),
                evidence = obj.str("evidence").orEmpty(),
                suggest = obj.str("suggest").orEmpty()
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseLevel(raw: String?): Level = when (raw?.lowercase()) {
        "possible" -> Level.Possible
        "reminder" -> Level.Reminder
        else -> Level.Identified
    }

    // ── 对外能力 ────────────────────────────────────────────────

    /**
     * 日志行里明确要求的最低 Java 主版本。
     *
     * 支持两种线索：class 文件版本号（如 61 -> 17）与文案形式（如 "requires Java 21"）。
     * 在控制台逐行解析的热路径上被调用，必须是纯同步、无 IO。
     */
    fun requiredJavaVersion(text: String): Int? {
        classVersion.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { version ->
            classVersions[version]?.let { return it }
        }
        requiresJava.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        javaOrHigher.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun analyze(text: String): Analysis {
        val first = exception.find(text)?.value?.trim()
        val causes = causedBy.findAll(text).map { it.groupValues[1].trim() }.distinct().take(6).toList()
        val code = exitCode.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val components = component.findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()

        val findings = mutableListOf<Finding>()
        for (rule in rules) {
            val hit = rule.regex?.find(text)
            val matched = (rule.contains.isNotEmpty() && rule.contains.any { text.contains(it, ignoreCase = true) }) ||
                hit != null ||
                (code != null && code in rule.exitCodes)
            if (!matched) continue
            findings += Finding(
                level = rule.level,
                // label 若被占位符整条吃掉（渲染成空），退回规则 id，避免卡片出现空标题
                label = render(rule.label, hit).ifBlank { rule.id },
                detail = render(rule.detail, hit),
                evidence = render(rule.evidence, hit),
                suggestion = render(rule.suggest, hit)
            )
        }
        if (findings.isEmpty()) {
            findings += Finding(
                level = Level.Reminder,
                label = "未能明确归因",
                detail = "报告中没有匹配到可靠的已知模式。",
                evidence = first ?: "未找到明确异常",
                suggestion = "查看原始报告中的第一个异常和完整 Caused by 链，并核对核心官方要求。"
            )
        }
        return Analysis(first ?: "未找到首个异常", first, causes, code, components, findings)
    }

    // ── 文案占位符 ──────────────────────────────────────────────

    private val placeholder = Regex("""\$(\d+|java)""")

    /** [render] 的清理正则。提到字段上：render 在规则循环里被逐条调用，不应每次现 new。 */
    /** 可选片段：`{{端口 $1}}` —— 里面任一占位符落空时整段删除。 */
    private val optionalFragment = Regex("""\{\{([^{}]*)\}\}""")

    private val emptyParens = Regex("""[（(]\s*[)）]""")
    private val emptyBrackets = Regex("""[【\[]\s*[】\]]""")
    private val repeatedSpaces = Regex("""\s{2,}""")
    private val spaceBeforePunctuation = Regex("""\s+([，。、；：！？）】」])""")

    /**
     * 展开文案里的占位符：`$0..$9` 为正则捕获组，`$java` 为 class 版本号查表结果。
     *
     * 解析不到时替换为空串。为了保证「没有捕获值时文案仍然通顺」，替换后统一做清理：
     * 删掉被替换空掉的括号片段、压掉连续空格、去掉中文标点前的空格。
     * 因此规则文案里把可选值写在括号内即可，例如「（端口 $1）」。
     *
     * 整条文案全部由落空的占位符组成时返回空串——**不能**回退成未渲染的模板，
     * 那会把字面量 `$1` 直接展示给用户。调用方用 [Finding.label] 的兜底值处理空标题。
     */
    private fun render(template: String, match: MatchResult?): String {
        if (template.isEmpty()) return template
        val groups = match?.groupValues
        val valueOf = fun(token: String): String = when (token) {
            "java" -> {
                val code = groups?.getOrNull(1)?.toIntOrNull()
                val release = code?.let { classVersions[it] }
                release?.let { "Java $it" }.orEmpty()
            }
            else -> token.toIntOrNull()?.let { groups?.getOrNull(it) }.orEmpty()
        }
        // 可选片段 `{{...}}`：里面任一占位符落空就整段丢掉。
        // 为什么不写成「（端口 $1）」靠括号清洗：占位符落空后括号里还剩「端口 」这些字，
        // emptyParens 只认紧挨着的空括号，根本匹配不上，于是 UI 上留下「（端口 ）」。
        val withoutOptionals = optionalFragment.replace(template) { m ->
            val body = m.groupValues[1]
            val tokens = placeholder.findAll(body).map { it.groupValues[1] }.toList()
            if (tokens.isNotEmpty() && tokens.all { valueOf(it).isEmpty() }) "" else body
        }
        val rendered = placeholder.replace(withoutOptionals) { m -> valueOf(m.groupValues[1]) }
        return rendered
            .replace(emptyParens, "")
            .replace(emptyBrackets, "")
            .replace(repeatedSpaces, " ")
            .replace(spaceBeforePunctuation, "$1")
            .trim()
    }

    private const val ASSET_PATH = "rules/server_errors.json"

    /**
     * 单个用户自定义规则文件的体积上限。
     *
     * 规则表正常只有几 KB；`initialize()` 在主线程同步执行，单个文件设上限可以挡住
     * 「用户往 error_rules 里丢了一个大文件」导致的启动卡顿。
     */
    private const val MAX_CUSTOM_RULE_BYTES = 256L * 1024
}
