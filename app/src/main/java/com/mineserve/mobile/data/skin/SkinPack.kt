package com.mineserve.mobile.data.skin

import kotlinx.serialization.json.Json

/**
 * 主题包的常量与「纯字符串层面」的校验。
 *
 * 需要同时看 zip 条目和解析结果的安全检查放在 [SkinInstaller] 里 —— 这里只放两边都要用的规则，
 * 避免出现两份不一致的实现。
 */
object SkinPack {
    /** 单个条目上限（解压后）。 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 解压总量上限。 */
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    /** 条目数上限。 */
    const val MAX_ENTRIES = 64

    /** 图片单边像素上限。 */
    const val MAX_IMAGE_EDGE = 4096

    const val MAX_ID_LEN = 64

    /** 支持的主题包格式版本。见到更大的直接拒绝并提示升级 App。 */
    const val SCHEMA_VERSION = 1

    /** 允许出现在包里的扩展名。任何 `.so` / `.dex` / `.jar` 都会被拒（见安全清单第 9 条）。 */
    private val ALLOWED_EXT = setOf("json", "txt", "webp", "png", "jpg", "jpeg")

    val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }

    private val idRe = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

    fun validId(id: String) = id.length in 2..MAX_ID_LEN && idRe.matches(id)

    /** 返回 null 表示安全；否则返回拒绝原因。 */
    fun unsafeName(name: String): String? = when {
        name.isEmpty() || name.length > 128 -> "条目名长度非法"
        name.contains('\\') -> "条目名包含反斜杠"
        name.startsWith("/") || name.startsWith("~") -> "条目名是绝对路径"
        name.split('/').any { it == ".." || it == "." } -> "条目名包含 .."
        name.any { it < ' ' } -> "条目名包含控制字符"
        else -> null
    }

    /** 扩展名白名单。目录条目（以 `/` 结尾）不需要检查。 */
    fun unsupportedExt(name: String): String? {
        if (name.endsWith("/")) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in ALLOWED_EXT) null else "不允许的文件类型 .$ext"
    }

    fun parseManifest(text: String): SkinManifest = json.decodeFromString(text)

    fun parseTokens(text: String): SkinTokens = json.decodeFromString(text)

    /** manifest 文本字段的长度上限，与 `themetool.py` 保持一致。 */
    val textLimits = listOf(
        "name" to 32,
        "author" to 64,
        "description" to 200,
        "version" to 24
    )
}
