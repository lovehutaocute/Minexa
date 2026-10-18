package com.mineserve.mobile.data

import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 累计使用人数统计上报。
 * 设备标识：Settings.Secure.ANDROID_ID（Android 标准、无需权限）经 SHA-256 哈希，
 * 不暴露明文；后端按哈希去重，同一设备只计一次。
 * 节流：同一设备每天最多上报一次；任何失败都静默忽略，绝不影响主流程。
 */
object UsageTracker {

    private const val PREFS = "usage_tracker"
    private const val KEY_LAST_PULSE = "last_pulse_day"

    /** 在应用启动时调用（Application.onCreate 或 MainActivity.onCreate）。 */
    fun maybePulse(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString(KEY_LAST_PULSE, null) == today) return // 当天已上报
        // 无网络时直接跳过：不做注定失败的 POST（否则要等 8 秒超时）。
        // 不写当日标记，等下次有网络时再上报。
        if (!NetworkState.isOnline(context)) return

        val deviceId = deviceId(context)
        Thread {
            try {
                val url = URL(UsageConfig.WORKER_BASE_URL + "/pulse")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { out ->
                    out.write("{\"deviceId\":\"$deviceId\"}".toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    prefs.edit().putString(KEY_LAST_PULSE, today).apply()
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 网络失败/后端未部署：静默，不重试（明天再试）
            }
        }.start()
    }

    private fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: ""
        return sha256Hex(androidId)
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
