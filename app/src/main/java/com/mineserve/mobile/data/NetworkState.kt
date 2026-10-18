package com.mineserve.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 轻量网络可用性判断。
 *
 * 用途：给「自动」网络任务（每日使用脉冲上报、启动自动检查更新）加一道约束——
 * 没有网络时直接跳过，不做注定失败的连接尝试（每次失败都要等 8~12 秒超时才返回）。
 * 手动触发的网络操作（用户点「检查更新」）不走这里，保证用户始终能拿到失败反馈。
 *
 * 只做一次性探测，不做实时监听：拿不到 ConnectivityManager 时保守返回 true，
 * 让真正的网络调用自己去处理失败（静默）。
 */
object NetworkState {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
