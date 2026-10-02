package com.mineserve.mobile.server.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SakuraFrp（api.natfrp.com/v4）API 客户端。
 *
 * 认证遵循 v4 API 定义：GET 用 `token` 查询参数，POST 用 `Authorization: Bearer`。
 * 隧道列表直接返回数组（无包络）；创建成功为 HTTP 201；204 响应无正文。
 */
object SakuraFrpApi {

    private const val BASE = "https://api.natfrp.com/v4"
    private const val TIMEOUT_MS = 15_000

    /** 本应用自带的上游原版 frpc 版本（/tunnel/config 需声明目标版本） */
    private const val FRPC_VERSION = "0.69.1"

    class ApiException(message: String) : Exception(message)

    data class Account(val username: String, val group: String, val level: Int, val maxTunnels: Int?)

    data class Tunnel(
        val id: String,
        val name: String,
        val nodeId: String,
        val type: String,
        val localIp: String,
        val localPort: Int,
        val remotePort: Int?,
        val remoteAddress: String,
        val online: Boolean
    )

    data class Node(
        val id: String,
        val name: String,
        val hostname: String,
        val description: String,
        val online: Boolean,
        val udpSupport: Boolean
    )

    // ── 底层请求 ───────────────────────────────────────────────

    private fun open(method: String, path: String, token: String?, body: String?): Pair<Int, ByteArray> {
        val conn = URL("$BASE$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
            }
            if (token != null && method != "GET") {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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

    /** 2xx 解析为 JSON；204 等空响应返回 null；非 2xx 抛 ApiException（带服务端 msg） */
    private fun decode(code: Int, bytes: ByteArray): kotlinx.serialization.json.JsonElement? {
        if (code < 200 || code >= 300) {
            var message = "HTTP $code"
            try {
                val body = Json.parseToJsonElement(bytes.decodeToString())
                val msg = (body as? JsonObject)?.get("msg") as? JsonPrimitive
                if (msg != null && msg.isString) message = msg.content
            } catch (_: Exception) {}
            throw ApiException(message)
        }
        if (bytes.isEmpty()) return null
        return Json.parseToJsonElement(bytes.decodeToString())
    }

    private fun get(path: String): kotlinx.serialization.json.JsonElement? =
        open("GET", path, null, null).let { (code, bytes) -> decode(code, bytes) }

    private fun post(path: String, token: String, body: String): kotlinx.serialization.json.JsonElement? =
        open("POST", path, token, body).let { (code, bytes) -> decode(code, bytes) }

    private fun obj(element: kotlinx.serialization.json.JsonElement?): JsonObject? = element as? JsonObject

    private fun arr(element: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
        (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.content == "true" || it.content == "1" } ?: false

    // ── API ────────────────────────────────────────────────────

    /** 验证 token 并获取用户信息 */
    fun userInfo(token: String): Account {
        val body = obj(get("/user/info?token=$token"))
            ?: throw ApiException("获取用户信息失败")
        val group = body["group"] as? JsonObject
        return Account(
            username = body.str("name"),
            group = group?.str("name") ?: "",
            level = group?.int("level") ?: 0,
            maxTunnels = body.int("tunnels")
        )
    }

    /** 我的隧道列表（API 直接返回数组） */
    fun tunnelList(token: String): List<Tunnel> =
        arr(get("/tunnels?token=$token")).map { m ->
            val remote = m.str("remote")
            Tunnel(
                id = "${m.int("id") ?: m.str("id")}",
                name = m.str("name"),
                nodeId = "${m.int("node") ?: m.str("node")}",
                type = m.str("type").ifBlank { "tcp" },
                localIp = m.str("local_ip").ifBlank { "127.0.0.1" },
                localPort = m.int("local_port") ?: 25565,
                remotePort = remote.toIntOrNull(),
                remoteAddress = if (remote.contains(":")) remote else "",
                online = m.bool("online")
            )
        }

    /** 节点列表（API 返回以节点 id 为 key 的对象）。
     *  `flag` 位标记：1<<5 允许 UDP 流量，1<<9 节点离线；vip 高于用户等级的节点不展示。 */
    fun nodeList(token: String, userLevel: Int = 0): List<Node> {
        val body = obj(get("/nodes?token=$token")) ?: return emptyList()
        val flagUdp = 1 shl 5
        val flagOffline = 1 shl 9
        val nodes = mutableListOf<Node>()
        body.forEach { (id, raw) ->
            val m = raw as? JsonObject ?: return@forEach
            val vip = m.int("vip") ?: 0
            if (vip > userLevel) return@forEach // 等级不足的节点不展示
            val flag = m.int("flag") ?: 0
            nodes.add(
                Node(
                    id = id,
                    name = m.str("name"),
                    hostname = m.str("host"),
                    description = m.str("description"),
                    online = flag and flagOffline == 0,
                    udpSupport = flag and flagUdp != 0
                )
            )
        }
        return nodes
    }

    /** 创建隧道（成功为 HTTP 201）。remote 为字符串（端口或绑定域名），tcp 等类型留空由服务端分配。 */
    fun createTunnel(
        token: String,
        node: String,
        name: String,
        type: String,
        localIp: String,
        localPort: Int,
        remotePort: Int? = null
    ) {
        val body = buildJsonObject {
            put("node", node.toIntOrNull() ?: 0)
            put("name", name)
            put("type", type)
            put("note", "Create By MineServe")
            put("local_ip", localIp)
            put("local_port", localPort)
            if (remotePort != null) put("remote", "$remotePort")
        }
        post("/tunnels", token, body.toString())
    }

    /** 获取隧道配置文件（POST /tunnel/config，响应为 text/plain 或 text/toml，非 JSON）。
     *  query 为启动目标：隧道 ID（如 114514）或 n 前缀节点 ID（如 n233）。 */
    fun tunnelConfig(token: String, query: String): String {
        val (code, bytes) = open(
            "POST", "/tunnel/config", token,
            """{"query":"$query","frpc":"$FRPC_VERSION"}"""
        )
        if (code < 200 || code >= 300) {
            var message = "HTTP $code"
            try {
                val body = Json.parseToJsonElement(bytes.decodeToString())
                val msg = (body as? JsonObject)?.get("msg") as? JsonPrimitive
                if (msg != null && msg.isString) message = msg.content
            } catch (_: Exception) {}
            throw ApiException(message)
        }
        return bytes.decodeToString()
    }
}
