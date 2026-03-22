package com.crtoverlay.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.IntentCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class OverlayService : Service(), CrtGlRenderer.CaptureHost {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: CrtEffectGlView? = null
    private var renderer: CrtGlRenderer? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var captureSurface: android.view.Surface? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_INTENT, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { tearDownAndStopSelf() }
            }
        }
        projectionCallback = cb
        mediaProjection?.registerCallback(cb, mainHandler)

        if (!attachOverlayIfNeeded()) {
            tearDown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        return START_STICKY
    }

    /**
     * Capture at the physical display resolution so the shader has the highest-quality input
     * to downsample into the emulated CRT pixel grid.
     */
    private fun computeCaptureSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        var w = bounds.width()
        var h = bounds.height()
        w -= w % 2
        h -= h % 2
        return w.coerceAtLeast(2) to h.coerceAtLeast(2)
    }

    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.OPAQUE,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.alpha = 1f
        params.dimAmount = 0f
        params.format = PixelFormat.OPAQUE
        // Without this, many devices leave cutout / status-bar bands undrawn; the live app shows
        // through next to the curvature-warped capture and reads as a misaligned “ghost”.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return params
    }

    private fun attachOverlayIfNeeded(): Boolean {
        if (overlayView != null) return true
        val (w, h) = computeCaptureSize()
        captureWidth = w
        captureHeight = h

        val r = CrtGlRenderer(
            applicationContext,
            w,
            h,
            onFrameAvailable = { overlayView?.requestRender() },
            host = this,
        )
        renderer = r
        val view = CrtEffectGlView(this, r)
        overlayView = view

        return try {
            val wms = getSystemService(WINDOW_SERVICE) as WindowManager
            wms.addView(view, buildOverlayLayoutParams())
            true
        } catch (_: Exception) {
            overlayView = null
            renderer = null
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayView != null) {
            mainHandler.post { refreshOverlayForDisplayChange() }
        }
    }

    /**
     * Keeps the overlay full-screen and resizes capture when rotation or display size changes.
     */
    private fun refreshOverlayForDisplayChange() {
        val view = overlayView ?: return
        val wms = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            wms.updateViewLayout(view, buildOverlayLayoutParams())
        } catch (_: Exception) {
        }

        val (w, h) = computeCaptureSize()
        if (w == captureWidth && h == captureHeight) return

        captureWidth = w
        captureHeight = h

        val vd = virtualDisplay
        val ren = renderer
        if (ren != null) {
            view.queueEvent {
                ren.resizeCapture(w, h)
                if (vd != null) {
                    mainHandler.post {
                        try {
                            vd.resize(w, h, resources.displayMetrics.densityDpi)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    override fun onCaptureSurfaceReady(surface: android.view.Surface) {
        mainHandler.post {
            if (virtualDisplay != null) return@post
            captureSurface = surface
            val mp = mediaProjection ?: return@post
            val metrics = resources.displayMetrics
            val density = metrics.densityDpi
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            val cw = captureWidth.coerceAtLeast(2)
            val ch = captureHeight.coerceAtLeast(2)
            val vd = mp.createVirtualDisplay(
                "crt_overlay_capture",
                cw,
                ch,
                density,
                flags,
                surface,
                null,
                null,
            )
            virtualDisplay = vd
            if (vd == null) {
                tearDownAndStopSelf()
            }
        }
    }

    private fun tearDown() {
        isRunning = false
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        overlayView?.let { v ->
            try {
                v.onPause()
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        renderer = null
        captureSurface = null

        projectionCallback?.let { c ->
            try {
                mediaProjection?.unregisterCallback(c)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun tearDownAndStopSelf() {
        tearDown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_overlay),
                stopPi,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "crt_overlay"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.crtoverlay.app.STOP_OVERLAY"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_INTENT = "extra_projection_intent"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
