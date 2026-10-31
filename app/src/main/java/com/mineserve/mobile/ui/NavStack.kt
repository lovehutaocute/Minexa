package com.mineserve.mobile.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * 二级页面类型。
 *
 * 只有底部 Tab（[McTab]）算一级页面；这里列出的每个页面进入时都要压入返回栈，
 * 因此它们进入后底部导航栏隐藏、左上角出现悬浮返回键。
 *
 * 放在 NavStack.kt 而不是 McApp.kt：返回栈的序列化需要它，两者放一起才不会各改各的。
 */
enum class SubPage {
    Properties,
    Network,
    Backup,
    WorldManage,
    DownloadHelp,
    MtGuide,
    KeepAlive,
    OpLevelGuide,
    Diagnostics,
    Plugins,
    ServerIcon,
    CrashReports,
    WidgetSettings,
    Mcp,
    EnvManager,
    Ftp,
    FrpProfileEditor,
    EasyTierGuide,
    More,
    Automation
}

/**
 * 返回栈里的一层。
 *
 * [arg] 承载「打开这一页时带的参数」（FRP 配置 id、环境管理页的初始模块号）。
 * 它参与 [key] 的计算，于是同一个页面用不同参数打开时状态互不串味：
 * 「从崩溃报告进 Java 安装」和「从概览进依赖管理」各自保留自己的选中模块。
 */
data class NavEntry(val page: SubPage, val arg: String = "") {

    /** 页面状态（滚动位置、选中项）在 [androidx.compose.runtime.saveable.SaveableStateHolder] 里的键。 */
    val key: String get() = encode()

    /**
     * 是否属于「一级页面」。
     *
     * 「更多」挂在底部导航栏的最后一个位置、是从任何 Tab 都能直达到的入口页，
     * 它在层级上等同 Tab 页：压栈只是为了记住「从哪个 Tab 进来的」，
     * 但不能因此隐藏底栏、也不能给它加悬浮返回键（底栏本身就是返回入口）。
     */
    val isRootLevel: Boolean get() = page == SubPage.More

    fun encode(): String = if (arg.isEmpty()) page.name else "${page.name}#$arg"

    companion object {
        /** 解析失败（旧版本残留、枚举被删）返回 null，由调用方丢弃该层而不是崩溃。 */
        fun decode(raw: String): NavEntry? {
            if (raw.isEmpty()) return null
            val sep = raw.indexOf('#')
            val name = if (sep < 0) raw else raw.substring(0, sep)
            val arg = if (sep < 0) "" else raw.substring(sep + 1)
            val page = SubPage.entries.firstOrNull { it.name == name } ?: return null
            return NavEntry(page, arg)
        }
    }
}

/**
 * 返回栈的 Saveable 化。
 *
 * 用「名字#参数」的字符串编码而不是 Parcelable：枚举名天然稳定，
 * 旋转屏幕 / 进程被系统回收后重建时能原样还原层级，不会把用户甩回首页。
 */
val NavStackSaver: Saver<List<NavEntry>, Any> = listSaver<List<NavEntry>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.mapNotNull { NavEntry.decode(it) } }
)
