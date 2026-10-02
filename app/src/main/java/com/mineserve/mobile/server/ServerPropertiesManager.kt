package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File

/**
 * server.properties 文件读写。
 *
 * - 解析/序列化交给 [ServerProperties]（保真：注释、空行、行序都保留）
 * - 服务器未首次启动时该文件可能不存在，[readProperties] 返回空 Map
 * - 通过 [exists] 判断配置文件是否已生成，供 UI 决定是否展示编辑表单
 *
 * @param termux Termux 运行时，用于定位 server 目录
 */
class ServerPropertiesManager(private val termux: TermuxRuntime) {

    /** server.properties 文件路径：home/servers/{dirName}/server.properties */
    private fun propertiesFile(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName/server.properties")

    /**
     * 配置文件是否已由服务端生成。
     *
     * 服务端首次启动前该文件不存在。此状态下 UI 不应展示可编辑表单：
     * 界面里的预设值并非服务端真实生效值，编辑并保存会生成一份不完整配置，
     * 与服务端自动补齐的默认值产生错位。应引导用户先启动一次服务端。
     */
    fun exists(dirName: String): Boolean = propertiesFile(dirName).isFile

    /**
     * 读取所有属性为有序 Map（注释与空行被忽略，但不会影响原文件）。
     *
     * - 文件不存在返回空 Map
     * - 仅按第一个 `=` 分割（值本身可能含有 `=`）
     * - 保持文件中的出现顺序
     */
    fun readProperties(dirName: String): Map<String, String> =
        readDocument(dirName).toMap()

    /** 读取完整文档（保留注释与行序），文件不存在时返回空文档。 */
    fun readDocument(dirName: String): ServerProperties {
        val file = propertiesFile(dirName)
        val text = try {
            if (!file.exists()) "" else file.readText()
        } catch (e: Exception) {
            ""
        }
        return ServerProperties.parse(text)
    }

    /**
     * 把 [props] 合并写回文件。
     *
     * **保真语义**：以磁盘上现有内容为基准，逐条更新/追加 [props] 中的键，
     * 文件中其它键、注释、空行与行序全部保留。原实现是整份重写，会把用户
     * 在 UI 之外写的注释全部抹掉，已修正。
     *
     * [props] 里**没有提到**的键不会被删除——本方法只做「更新/追加」。
     * 需要删键请用 [ServerProperties.remove]，或把该键的值置空。
     *
     * - 文件不存在时按 [props] 新建一份（带生成标记注释）
     * - 文件不存在时自动创建父目录
     * - **读失败时直接返回 false，不落盘**：把读不到的内容当成空文档写回，
     *   等于把整份配置清空，比写失败严重得多
     * - 成功返回 true，失败返回 false
     */
    fun writeProperties(props: Map<String, String>, dirName: String): Boolean {
        val file = propertiesFile(dirName)
        return try {
            file.parentFile?.mkdirs()
            val doc = if (file.exists()) ServerProperties.parse(file.readText())
            else ServerProperties.of(emptyMap())
            doc.merge(props)
            file.writeText(doc.toText())
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 更新单个属性（保真：只改这一行）。
     *
     * 与 [writeProperties] 的差别是不需要先把整份配置读出来再传回去，
     * 适合「只改一个键」的场景（如切换世界 level-name）。
     */
    fun setProperty(dirName: String, key: String, value: String): Boolean =
        writeProperties(mapOf(key to value), dirName)

    /**
     * 读取单个属性。
     *
     * - 文件不存在或 key 不存在时返回 [default]
     */
    fun getProperty(key: String, dirName: String, default: String = ""): String {
        return readProperties(dirName)[key] ?: default
    }
}
