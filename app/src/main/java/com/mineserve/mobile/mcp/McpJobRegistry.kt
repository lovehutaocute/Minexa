package com.mineserve.mobile.mcp

import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 长任务注册表。
 *
 * ## 为什么需要它
 * 安装 Java/PHP 运行时、拉取服务端核心、apt 装依赖、还原世界快照这类操作
 * 动辄几十秒到十几分钟。如果在 HTTP 请求里同步等待：
 *  - MCP 客户端（以及中间的任何网关/代理）会先超时断开，调用方只能看到
 *    "连接中断"，无法区分「任务失败」和「只是还没跑完」；
 *  - 一次工具调用占住一个 HTTP 工作线程，几个并发就能把线程池耗尽。
 * 所以长任务一律丢进后台执行，立即返回 job id，由调用方用 `get_job` 轮询。
 *
 * ## 为什么是单线程
 * 这些任务改的是同一份 rootfs / apt 状态（dpkg 锁、bin 链接、JVM 目录）。
 * 并发跑不仅没有收益，还会互相破坏（apt 锁冲突、半装状态）。
 * 单线程执行器让它们天然串行，顺带保证了 job 日志的顺序性。
 */
internal class McpJobRegistry {

    enum class State(val label: String) {
        Running("running"),
        Succeeded("succeeded"),
        Failed("failed")
    }

    /** 一个后台任务。字段各自加锁/volatile，因为写方是执行器线程、读方是 HTTP 线程。 */
    class Job internal constructor(val id: String, val action: String) {

        @Volatile
        var state: State = State.Running
            private set

        /** 结束时的一句话结论（成功摘要或失败原因） */
        @Volatile
        var summary: String = ""
            private set

        val startedAtMs: Long = System.currentTimeMillis()

        @Volatile
        var finishedAtMs: Long = 0L
            private set

        private val lines = Collections.synchronizedList(ArrayList<String>())

        internal fun log(line: String) {
            val text = line.trimEnd()
            if (text.isEmpty()) return
            synchronized(lines) {
                lines.add(text)
                // 环形保留：apt 安装能刷出上万行，全留会白白占着堆。
                // 这里丢掉最旧的部分，只保证最近 MAX_LOG_LINES 行可查。
                if (lines.size > MAX_LOG_LINES) {
                    lines.subList(0, lines.size - MAX_LOG_LINES).clear()
                }
            }
        }

        internal fun complete(text: String) {
            summary = text
            state = State.Succeeded
            finishedAtMs = System.currentTimeMillis()
        }

        internal fun fail(text: String) {
            summary = text
            state = State.Failed
            finishedAtMs = System.currentTimeMillis()
        }

        fun tailLines(count: Int): List<String> =
            synchronized(lines) { if (count <= 0) emptyList() else lines.takeLast(count) }

        val lineCount: Int get() = synchronized(lines) { lines.size }

        val isFinished: Boolean get() = state != State.Running

        val elapsedMs: Long
            get() = (if (finishedAtMs > 0L) finishedAtMs else System.currentTimeMillis()) - startedAtMs

        companion object {
            private const val MAX_LOG_LINES = 500
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcp-job").apply { isDaemon = true }
    }

    private val counter = AtomicInteger(0)
    private val jobMap = ConcurrentHashMap<String, Job>()
    private val order = Collections.synchronizedList(ArrayList<String>())

    /**
     * 提交任务并立即返回。
     *
     * [block] 在专用线程里以 runBlocking 执行，因此可以自由调用挂起函数与阻塞式
     * Termux 调用；抛出的任何 Throwable 都会被捕获并转成 Failed 状态 + 日志，
     * 不会泄漏到线程池（泄漏会让执行器线程死掉，后续任务全部卡住）。
     */
    fun submit(action: String, block: suspend (Job) -> String): Job {
        val id = "job-" + counter.incrementAndGet()
        val job = Job(id, action)
        jobMap[id] = job
        synchronized(order) {
            order.add(id)
            while (order.size > MAX_KEPT_JOBS) {
                jobMap.remove(order.removeAt(0))
            }
        }
        executor.execute {
            try {
                job.complete(runBlocking { block(job) })
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                job.log("[error] $reason")
                job.fail("$action failed: $reason")
            }
        }
        return job
    }

    fun get(id: String): Job? = jobMap[id]

    /** 最近提交在前 */
    fun listNewestFirst(): List<Job> =
        synchronized(order) { order.mapNotNull { jobMap[it] } }.asReversed()

    companion object {
        /** 只保留最近这么多条任务记录，超出的连日志一起丢弃 */
        private const val MAX_KEPT_JOBS = 30
    }
}
