package com.crtoverlay.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-screen GLES via [SurfaceView] + EGL14.
 *
 * [SurfaceView] with [SurfaceView.setZOrderOnTop] composites in a separate SurfaceFlinger layer,
 * which is **opaque** and greatly reduces “ghosting” vs [android.view.TextureView] on overlays.
 *
 * **Touch:** Do not rely on touch reaching apps under this overlay; use a gamepad or stop the
 * overlay to interact with the screen (see app strings / docs).
 */
class CrtEffectGlView(
    context: Context,
    private val renderer: CrtGlRenderer,
) : FrameLayout(context) {

    private val surfaceView: SurfaceView = SurfaceView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setZOrderOnTop(true)
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.OPAQUE)
        isClickable = false
        isFocusable = false
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    @Volatile
    private var surfaceReady = false

    @Volatile
    private var glReleased = false

    private var widthPx = 0
    private var heightPx = 0

    init {
        fitsSystemWindows = false
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.BLACK)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
        addView(surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                glReleased = false
                surfaceReady = false
                ensureGlThread()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                ensureGlThread()
                glHandler?.post {
                    if (glReleased) return@post
                    if (!surfaceReady) {
                        widthPx = width
                        heightPx = height
                        try {
                            initEgl(holder.surface)
                        } catch (_: Exception) {
                            releaseEglLocked()
                            return@post
                        }
                        if (!eglMakeCurrent()) {
                            releaseEglLocked()
                            return@post
                        }
                        surfaceReady = true
                        renderer.onSurfaceCreated(null, null)
                        renderer.onSurfaceChanged(null, widthPx, heightPx)
                    } else {
                        widthPx = width
                        heightPx = height
                        if (!eglMakeCurrent()) return@post
                        renderer.onSurfaceChanged(null, width, height)
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                shutdownSync()
            }
        })
    }

    private fun ensureGlThread() {
        if (glThread != null) return
        val thread = HandlerThread("crt-overlay-gl").also { it.start() }
        glThread = thread
        glHandler = Handler(thread.looper)
    }

    fun requestRender() {
        glHandler?.post {
            if (glReleased || !surfaceReady) return@post
            drawFrame()
        }
    }

    fun queueEvent(r: Runnable) {
        glHandler?.post(r)
    }

    fun onPause() {
        shutdownSync()
    }

    fun shutdownSync() {
        if (glReleased && glHandler == null) return
        val h = glHandler
        if (h != null) {
            val latch = CountDownLatch(1)
            h.post {
                try {
                    releaseGlAndEglLocked()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
        } else {
            glReleased = true
            surfaceReady = false
        }
        glThread?.quitSafely()
        try {
            glThread?.join(2000)
        } catch (_: InterruptedException) {
        }
        glThread = null
        glHandler = null
    }

    private fun releaseGlAndEglLocked() {
        if (glReleased) return
        glReleased = true
        surfaceReady = false
        try {
            if (eglDisplay != null && eglSurface != null && eglContext != null) {
                eglMakeCurrent()
                renderer.releaseGlResources()
            }
        } finally {
            releaseEglLocked()
        }
    }

    private fun drawFrame() {
        if (!eglMakeCurrent()) return
        renderer.onDrawFrame(null)
        val d = eglDisplay ?: return
        val s = eglSurface ?: return
        EGL14.eglSwapBuffers(d, s)
    }

    private fun eglMakeCurrent(): Boolean {
        val d = eglDisplay ?: return false
        val s = eglSurface ?: return false
        val c = eglContext ?: return false
        return EGL14.eglMakeCurrent(d, s, s, c)
    }

    private fun chooseConfigForAttribs(display: EGLDisplay, attribList: IntArray): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        return if (
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) {
            configs[0]
        } else {
            null
        }
    }

    private fun initEgl(outputSurface: Surface) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        eglDisplay = display

        val opaqueAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val rgbaAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val candidateConfigs = listOfNotNull(
            chooseConfigForAttribs(display, opaqueAttribs),
            chooseConfigForAttribs(display, rgbaAttribs),
        ).distinctBy { System.identityHashCode(it) }

        check(candidateConfigs.isNotEmpty()) { "eglChooseConfig failed" }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        for (config in candidateConfigs) {
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) continue
            val surf = EGL14.eglCreateWindowSurface(display, config, outputSurface, surfaceAttribs, 0)
            if (surf == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, context)
                continue
            }
            eglContext = context
            eglSurface = surf
            return
        }
        error("eglCreateContext/eglCreateWindowSurface failed for all EGL configs")
    }

    private fun releaseEglLocked() {
        val d = eglDisplay
        if (d != null) {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(d, it) }
            eglContext?.let { EGL14.eglDestroyContext(d, it) }
            EGL14.eglTerminate(d)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
        // holderSurface is owned by SurfaceView — never Surface.release()
    }
}
