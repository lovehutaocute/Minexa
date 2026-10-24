package com.mineserve.mobile.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.mineserve.mobile.R
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mineserve.mobile.MainActivity
import kotlin.math.roundToInt

/** Small draggable foreground indicator, adapted from EdgeCube's GPL-3.0 overlay. */
internal object StatusOverlay {
    private var view: View? = null
    private var info: TextView? = null
    private var attachPending = false
    private val mainHandler = Handler(Looper.getMainLooper())
    fun canDraw(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    @Synchronized
    fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!attachPending) {
                attachPending = true
                mainHandler.post {
                    synchronized(this) { attachPending = false }
                    show(context)
                }
            }
            return
        }
        if (view != null) return
        if (!canDraw(context)) {
            Log.w(TAG, "Overlay permission is not granted")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            elevation = dp(8).toFloat()
            val normal = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xEB0C1018.toInt()); setStroke(dp(1), 0x4064B4FF) }
            val pressed = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xF21A2432.toInt()); setStroke(dp(1), 0x7064B4FF) }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF48D597.toInt()) }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            })
            addView(TextView(context).also { info = it }.apply { text = context.getString(R.string.sox_running_stats, "--%", "--M"); setTextColor(0xFFE6EDF7.toInt()); textSize = 11f; maxLines = 1; layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f) })
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(180), dp(32), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(10); y = dp(80) }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x = originX + (event.rawX - downX).roundToInt(); params.y = originY + (event.rawY - downY).roundToInt(); runCatching { wm.updateViewLayout(row, params) }; true }
                MotionEvent.ACTION_UP -> { if (kotlin.math.abs(event.rawX - downX) < dp(8) && kotlin.math.abs(event.rawY - downY) < dp(8)) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); true }
                else -> true
            }
        }
        runCatching { wm.addView(row, params); view = row }
            .onFailure { Log.w(TAG, "Unable to attach status overlay", it) }
    }
    fun update(cpuPercent: Int?, memoryMb: Long) {
        mainHandler.post {
            val cpu = cpuPercent?.let { "$it%" } ?: "--%"
            val mem = if (memoryMb > 0) "${memoryMb}M" else "--M"
            info?.text = com.mineserve.mobile.McApplication.get().getString(R.string.sox_running_stats, cpu, mem)
        }
    }
    @Synchronized
    fun hide(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide(context) }
            return
        }
        val target = view ?: return
        view = null
        info = null
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(target) }
    }
    private const val TAG = "MinexaOverlay"
}
