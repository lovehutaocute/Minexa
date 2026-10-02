package com.mineserve.mobile.server.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ME Frp（api.mefrp.com）API 客户端。
 *
 * 鉴权：`Authorization: Bearer <访问令牌>`，令牌在 mefrp.com 用户面板复制。
 * 响应包络 `{code, message, data}`，`code == 200` 视为成功。
 *
 * 与 SakuraFrp 的区别：ME Frp 不为用户分发独立的 frpc 凭据，而是把
 * serverAddr / serverPort / user / auth.token / remotePort 全部拼进
 * `/auth/proxy/config` 返回的成品 TOML —— 所以本客户端只需要一个令牌 + 隧道 ID，
 * 即可产出可直接运行的配置。
 */
object MeFrpApi {

    private const val BASE = "https://api.mefrp.com/api"
    private const val TIMEOUT_MS = 15_000

    class ApiException(message: String) : Exception(message)

    data class Account(
        val username: String,
        val group: String,
        val usedTunnels: Int?,
        val maxTunnels: Int?
    )

    data class Proxy(
        val id: String,
        val name: String,
        val nodeId: String,
        val nodeName: String,
        val type: String,
        val localIp: String,
        val localPort: Int,
        val remotePort: Int?,
        /** 公网连接地址；tcp/udp 为 `host:port`，http(s) 为绑定域名（可能为空）。 */
        val remoteAddress: String,
        val online: Boolean
    )

    data class Node(
        val id: String,
        val name: String,
        val hostname: String,
        val description: String
    )

    // ── 底层请求 ───────────────────────────────────────────────

    private fun open(method: String, path: String, token: String?, body: String?): Pair<Int, ByteArray> {
        val conn = URL("$BASE$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null && token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val buf = ByteArrayOutputStream()
                input.copyTo(buf, 8 * 1024)
                buf.toByteArray()
            } ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析包络：`code != 200` 抛 [ApiException]，错误文案优先用服务端 `message`。
     *
     * 注意 ME Frp 的 HTTP 状态码恒为 200，业务结果只看包络里的 `code`，
     * 因此这里不能只按 HTTP 状态判断。
     */
    private fun decode(code: Int, bytes: ByteArray): JsonObject {
        val text = bytes.decodeToString()
        val element: JsonElement? = try {
            if (text.isBlank()) null else Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        val obj = element as? JsonObject
        val bizCode = obj?.let { (it["code"] as? JsonPrimitive)?.content?.toIntOrNull() }
        if (bizCode != 200) {
            val message = (obj?.get("message") as? JsonPrimitive)?.content
                ?: (obj?.get("msg") as? JsonPrimitive)?.content
                ?: "HTTP $code"
            throw ApiException(message)
        }
        return obj
    }

    private fun get(path: String, token: String): JsonObject =
        open("GET", path, token, null).let { (code, bytes) -> decode(code, bytes) }

    private fun post(path: String, token: String, body: String): JsonObject =
        open("POST", path, token, body).let { (code, bytes) -> decode(code, bytes) }

    private fun JsonObject.data(): JsonObject = this["data"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 宽松布尔解析：ME Frp 的 `isOnline` 可能是 bool / 数字 / 字符串。 */
    private fun JsonObject.bool(key: String): Boolean {
        val raw = (this[key] as? JsonPrimitive)?.content ?: return false
        if (raw.equals("true", ignoreCase = true) || raw == "1") return true
        if (raw.equals("false", ignoreCase = true) || raw == "0" || raw.isBlank()) return false
        // "NaN" 也能被 toDoubleOrNull 解析出来，必须先排掉，否则 NaN != 0.0 会返回 true
        return raw.toDoubleOrNull()?.let { !it.isNaN() && it != 0.0 } ?: false
    }

    private fun JsonObject.arr(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    // ── API ────────────────────────────────────────────────────

    /** 验证令牌并获取账号信息。 */
    fun userInfo(token: String): Account {
        val data = get("/auth/user/info", token).data()
        return Account(
            username = data.str("username"),
            group = data.str("friendlyGroup"),
            usedTunnels = data.int("usedProxies"),
            maxTunnels = data.int("maxProxies")
        )
    }

    /** 我的隧道（代理）列表；节点名与连接地址由同一响应里的 `nodes` 表补全。 */
    fun proxyList(token: String): List<Proxy> {
        val data = get("/auth/proxy/list", token).data()
        val nodes = data.arr("nodes").associate {
            it.str("nodeId") to (it.str("name") to it.str("hostname"))
        }
        return data.arr("proxies").map { m ->
            val nodeId = m.str("nodeId")
            val node = nodes[nodeId]
            val hostname = node?.second.orEmpty()
            val type = m.str("proxyType").ifBlank { "tcp" }
            val remotePort = m.int("remotePort")
            val domain = m.str("domain")
            Proxy(
                id = m.str("proxyId"),
                name = m.str("proxyName"),
                nodeId = nodeId,
                nodeName = node?.first.orEmpty(),
                type = type,
                localIp = m.str("localIp").ifBlank { "127.0.0.1" },
                localPort = m.int("localPort") ?: 25565,
                remotePort = remotePort,
                remoteAddress = when {
                    type == "http" || type == "https" -> domain
                    hostname.isNotEmpty() && remotePort != null -> "$hostname:$remotePort"
                    else -> ""
                },
                online = m.bool("isOnline")
            )
        }
    }

    /**
     * 节点列表（创建隧道时用）。
     *
     * 该接口的 `data` 直接是数组，因此**不能**用 [data] 助手取（那边假定 `data` 是对象，
     * 会返回空对象）。形态变化时这里静默返回空列表，属于已知取舍。
     */
    fun nodeList(token: String): List<Node> {
        val data = get("/auth/node/list", token)
        val array = data["data"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }.map { m ->
            Node(
                id = m.str("nodeId"),
                name = m.str("name"),
                hostname = m.str("hostname"),
                description = m.str("description")
            )
        }
    }

    /**
     * 拉取某条隧道的成品 frpc 配置（TOML 文本，可直接运行）。
     *
     * 官方前端启动隧道时调用的就是该接口：返回内容已含 serverAddr / serverPort /
     * user / auth.token / remotePort 等全部启动所需信息。
     */
    fun proxyConfig(token: String, proxyId: String): String {
        val idNumber = proxyId.toIntOrNull()
        val body = buildJsonObject {
            // 服务端接受数字或字符串，数字优先（与官方前端一致）
            if (idNumber != null) put("proxyId", idNumber) else put("proxyId", proxyId)
            put("format", "toml")
        }
        val config = post("/auth/proxy/config", token, body.toString()).data().str("config")
        if (config.isBlank()) throw ApiException("隧道配置内容为空，请检查隧道是否存在")
        return config
    }
}
