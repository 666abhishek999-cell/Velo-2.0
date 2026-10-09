package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statsJob: Job? = null
    private var isMinimized = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 120
        }

        // Programmatic lightweight UI - 0 XML layout overhead!
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 14, 24, 14)
            val bg = GradientDrawable().apply {
                setColor(0xCC0E131F.toInt()) // Semi-transparent dark slate
                cornerRadius = 32f
                setStroke(2, 0x8800E5FF.toInt())
            }
            background = bg
            elevation = 12f
        }

        val statusText = TextView(this).apply {
            text = "LIVE • 60 FPS • 0 drop"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(statusText)

        // Drag & drop touch handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        isMinimized = !isMinimized
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
            overlayView = container
        } catch (_: Exception) {
            stopSelf()
            return
        }

        // Collect stats and update overlay
        statsJob = scope.launch {
            ScreenCaptureService.serviceStats.collectLatest { stats ->
                val fps = stats.currentFps.toInt()
                val bitrateKbps = if (stats.activeBitrateKbps > 0) stats.activeBitrateKbps
                else if (stats.recordingBitrateKbps > 0) stats.recordingBitrateKbps
                else stats.targetBitrateKbps.toLong()
                val mbps = String.format("%.1f", bitrateKbps / 1000f)
                val drops = stats.droppedFrames
                val duration = formatTime(stats.durationSeconds)

                if (isMinimized) {
                    val badge = if (stats.isStreaming && stats.isRecording) "🔴 REC+LIVE"
                    else if (stats.isStreaming) "🔴 LIVE"
                    else if (stats.isRecording) "REC" else "IDLE"
                    statusText.text = "$badge ${fps}fps"
                } else {
                    val prefix = if (stats.isStreaming) "🔴 LIVE" else if (stats.isRecording) "REC" else "READY"
                    statusText.text = "$prefix $duration | ${fps}fps | ${mbps}M | drop:$drops"
                }
            }
        }
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        statsJob?.cancel()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        super.onDestroy()
    }
}
