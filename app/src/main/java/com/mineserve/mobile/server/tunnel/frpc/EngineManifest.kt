package com.mineserve.mobile.server.tunnel.frpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 引擎元数据 JSON 的解析结果。
 *
 * 字段形状对齐 EdgeCube 的运行时元数据（见 [FrpcEngineConfig.METADATA_URL] 的注释），
 * 以便同源分发：一份 metadata 既能喂 EdgeCube，也能喂本应用。
 *
 * 顶层没有 `{code,data}` 外壳 —— 那是另一套接口的形状，混进来会让解析在
 * 第一层就拿到 null。
 */
@Serializable
internal data class EngineManifest(
    val id: String? = null,
    /** 整数构建号，比较新旧用它；[versionName] 只用于显示。 */
    val version: Int? = null,
    val versionName: String? = null,
    val packages: Map<String, EnginePackage> = emptyMap()
) {
    /** 展示用版本号；没有 `versionName` 时回退到整数构建号。 */
    val displayVersion: String?
        get() = versionName?.takeIf { it.isNotBlank() } ?: version?.toString()
}

/** 单个架构包。 */
@Serializable
internal data class EnginePackage(
    val urls: List<EngineUrl> = emptyList(),
    val sha256: String? = null,
    val size: Long? = null,
    val arch: List<String> = emptyList()
) {
    /**
     * 可下载的直链，按可用性排序。
     *
     * 滤掉 `type == "web"` 的条目：那是给人点开下载页看的，不是给程序抓的，
     * 直接 GET 通常拿回一个 HTML 页面 —— 而它会被当成引擎写进磁盘。EdgeCube
     * 同样只认非 `web` 类型。`type` 缺失时按直链对待（老格式没有这个字段）。
     */
    val directUrls: List<String>
        get() = urls
            .filter { it.type != "web" }
            .map { it.url }
            .filter { it.isNotBlank() }
}

@Serializable
internal data class EngineUrl(
    val type: String? = null,
    val url: String = ""
)

/** 元数据解析器。 */
internal object EngineManifestParser {

    /**
     * 解析失败一律返回 null，而不是抛异常。
     *
     * 元数据来自网络且可能被反代篡改/截断，调用方要的是"这份用不了，换下一个源"，
     * 不是一个需要层层捕获的异常。真正的失败原因由调用方拼成用户可读的提示。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): EngineManifest? = runCatching {
        json.decodeFromString<EngineManifest>(raw)
    }.getOrNull()

    /**
     * 取出 [key] 对应的包，并做可用性检查。
     *
     * 返回 null 的情形都意味着"这份元数据帮不上忙"，调用方应当换个源重试：
     * 缺该架构的包、没有直链、没有 sha256。
     *
     * **sha256 缺失视为不可用是刻意的**：这批二进制会被 `dlopen` 进本进程执行，
     * 而下载链路里全是第三方 GitHub 反代。没有哈希就无从判断拿到的是不是原物，
     * 把"缺少校验信息"当成"跳过校验"会让整条校验形同虚设。
     */
    fun packageFor(manifest: EngineManifest, key: String): EnginePackage? {
        val pkg = manifest.packages[key] ?: return null
        if (pkg.directUrls.isEmpty()) return null
        if (pkg.sha256.isNullOrBlank()) return null
        return pkg
    }
}
