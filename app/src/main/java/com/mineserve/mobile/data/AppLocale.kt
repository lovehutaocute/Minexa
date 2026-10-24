package com.mineserve.mobile.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import java.util.Locale

/**
 * 应用支持的语言。
 *
 * [tag] 为空表示「跟随系统语言」。语言名一律用该语言自身的写法（endonym），
 * 这样用户在语言列表里永远能认出自己的语言，不需要为每个语言再翻译一次语言名。
 *
 * 扩展方式（新增一种语言只需两步）：
 * 1. 在这里加一个枚举项，[tag] 用 BCP-47 标签（如 `de`、`pt-BR`）；
 * 2. 新建 `res/values-<tag 的目录形式>/strings.xml`（`pt-BR` → `values-b+pt+BR`）。
 * UI 的语言列表直接遍历 [entries]，不写死任何一项，因此不用改动设置页。
 */
enum class AppLanguage(
    val tag: String,
    val endonym: String,
) {
    /** 跟随系统语言：不覆盖任何配置，系统给什么就用什么 */
    System("", ""),

    SimplifiedChinese("zh-CN", "简体中文"),
    TraditionalChinese("zh-TW", "繁體中文"),
    English("en", "English"),
    Persian("fa", "فارسی");

    val isSystem: Boolean get() = this == System

    /** 该语言对应的 [Locale]；跟随系统时为 null */
    val locale: Locale?
        get() = if (isSystem) null else Locale.forLanguageTag(tag)

    /** 是否为从右到左（RTL）书写系统 */
    val isRtl: Boolean
        get() = locale?.let {
            TextUtils.getLayoutDirectionFromLocale(it) == View.LAYOUT_DIRECTION_RTL
        } ?: false

    companion object {
        /**
         * 按 tag 反查语言。未知 tag（例如以后删掉了某个语言）回落「跟随系统」，
         * 不抛异常——设置项属于用户数据，宁可静默降级也不要启动崩溃。
         */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.isNotEmpty() && it.tag.equals(tag, ignoreCase = true) }
                ?: System

        /** 语言列表顺序：跟随系统在最前，其后按用户量/常用度排列 */
        fun selectable(): List<AppLanguage> = entries.toList()
    }
}

/**
 * 应用内语言选择器。
 *
 * 不用 `AppCompatDelegate.setApplicationLocales`：本项目没有引入 AppCompat，
 * Activity 是 `ComponentActivity`，而且 targetSdk 停留在 28（Android 13 的
 * per-app language 只在 API 33+ 生效），所以走「自己改写 Context 的 Configuration」
 * 这条最稳的路径 —— 在 `attachBaseContext` 里套一层带 locale 的 Context，
 * 之后 [Context.getString]、Compose 的 `stringResource`、`Locale.getDefault()`
 * 取到的就都是所选语言。
 *
 * 选择持久化在独立的 SharedPreferences（不用 DataStore）：`attachBaseContext`
 * 里必须能同步读到值，DataStore 是挂起 API，在这里拿不到。
 */
object LocaleManager {

    private const val PREFS = "app_locale"
    private const val KEY_LANGUAGE = "language_tag"

    @Volatile
    private var cached: AppLanguage? = null

    /**
     * 进程启动时的系统默认 Locale 列表。
     *
     * 一旦 [select] 改过 `Locale.setDefault`，再想切回「跟随系统」就必须把它还原，
     * 否则 `Locale.getDefault()` 会一直停在旧的自选语言上（日志汉化开关、日期格式
     * 都读这个值）。所以在第一次 [wrap] 时把系统原值留一份。
     */
    @Volatile
    private var systemDefault: Locale? = null

    /** 当前生效的语言（含「跟随系统」） */
    fun current(context: Context): AppLanguage =
        cached ?: read(context).also { cached = it }

    /**
     * 保存用户选择。返回 true 表示语言确实变了，调用方应重建界面。
     *
     * 只写 SharedPreferences 并清缓存：真正的 Configuration 改写发生在下一次
     * `attachBaseContext`，所以必须配合 `Activity.recreate()`（或重启进程）生效。
     */
    fun select(context: Context, language: AppLanguage): Boolean {
        val previous = current(context)
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
        cached = language
        if (previous == language) return false
        // 同步一份到进程级默认 Locale：SimpleDateFormat / String.format 等
        // 不走 Context 的地方（日志时间戳、星期名）也会跟着切换。
        // 切回「跟随系统」时还原系统原值，否则进程默认会停在上一门自选语言上。
        if (language.isSystem) systemDefault?.let { Locale.setDefault(it) }
        else language.locale?.let { Locale.setDefault(it) }
        return true
    }

    /**
     * 用当前选择的语言包一层 Context。`base` 是系统给的原始 Context，
     * 跟随系统时原样返回，不做任何覆盖。
     */
    fun wrap(base: Context): Context {
        if (systemDefault == null) systemDefault = Locale.getDefault()
        val language = current(base)
        val locale = language.locale ?: run {
            // 跟随系统：什么都不覆盖，但要把进程默认 Locale 还原成系统原值
            systemDefault?.let { Locale.setDefault(it) }
            return base
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    /** 供 Compose 使用的 RTL 判定（从已本地化的 Context 反读，保证与资源一致） */
    fun isRtl(context: Context): Boolean =
        context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun read(context: Context): AppLanguage = AppLanguage.fromTag(
        runCatching { prefs(context).getString(KEY_LANGUAGE, "") }.getOrNull()
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
