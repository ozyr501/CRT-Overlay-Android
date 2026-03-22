package com.crtoverlay.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.max

/**
 * Renders screen capture ([SurfaceTexture] + external OES) through a CRT-style fragment shader.
 *
 * The captured texture is at **screen resolution**. The shader snaps each fragment to an
 * **emulated pixel grid** (e.g. ~640 × 480 extended to fill the screen) so the output looks
 * like a low-res CRT display with all typical CRT artifacts.
 */
class CrtGlRenderer(
    private val context: Context,
    initialCaptureWidth: Int,
    initialCaptureHeight: Int,
    private val onFrameAvailable: () -> Unit,
    private val host: CaptureHost,
) : android.opengl.GLSurfaceView.Renderer {

    private var captureWidth: Int = initialCaptureWidth
    private var captureHeight: Int = initialCaptureHeight

    interface CaptureHost {
        fun onCaptureSurfaceReady(surface: Surface)
    }

    private var program = 0
    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private var surfaceReadyPosted = false

    private var oesSamplingLinearOnly = false

    private val stMatrix = FloatArray(16)

    private var aPosition = -1
    private var aTexCoord = -1
    private var uSTMatrix = -1
    private var uTexSize = -1
    private var uEmulatedSize = -1
    private var uScanStrength = -1
    private var uScanSpacing = -1
    private var uMaskStrength = -1
    private var uVignette = -1
    private var uCurvature = -1
    private var uBloom = -1
    private var uPhosphorBleed = -1
    private var uSampler = -1

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer

    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    override fun onSurfaceCreated(unused: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        val vs = CrtGlProgram.loadAsset(context, "shaders/crt_vert.glsl")
        val fs = CrtGlProgram.loadAsset(context, "shaders/crt_frag.glsl")
        program = CrtGlProgram.createProgram(vs, fs)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
        uEmulatedSize = GLES20.glGetUniformLocation(program, "uEmulatedSize")
        uScanStrength = GLES20.glGetUniformLocation(program, "uScanStrength")
        uScanSpacing = GLES20.glGetUniformLocation(program, "uScanSpacing")
        uMaskStrength = GLES20.glGetUniformLocation(program, "uMaskStrength")
        uVignette = GLES20.glGetUniformLocation(program, "uVignette")
        uCurvature = GLES20.glGetUniformLocation(program, "uCurvature")
        uBloom = GLES20.glGetUniformLocation(program, "uBloom")
        uPhosphorBleed = GLES20.glGetUniformLocation(program, "uPhosphorBleed")
        uSampler = GLES20.glGetUniformLocation(program, "sTexture")

        val vb = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )
        val tb = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )
        vertexBuf = ByteBuffer.allocateDirect(vb.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuf.put(vb).position(0)
        texBuf = ByteBuffer.allocateDirect(tb.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texBuf.put(tb).position(0)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        applyOesTextureFilterMode()
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTexId)
        st.setDefaultBufferSize(max(captureWidth, 2), max(captureHeight, 2))
        st.setOnFrameAvailableListener { onFrameAvailable() }
        surfaceTexture = st
        val surf = Surface(st)
        outputSurface = surf

        if (!surfaceReadyPosted) {
            surfaceReadyPosted = true
            host.onCaptureSurfaceReady(surf)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun applyOesTextureFilterMode() {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        if (oesSamplingLinearOnly) {
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return
        }
        GLES20.glGetError()
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val err = GLES20.glGetError()
        if (err == GLES20.GL_INVALID_ENUM) {
            oesSamplingLinearOnly = true
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
    }

    fun resizeCapture(width: Int, height: Int) {
        val w = max(width, 2)
        val h = max(height, 2)
        captureWidth = w
        captureHeight = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    override fun onSurfaceChanged(unused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        viewWidth = max(width, 1)
        viewHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
    }

    /**
     * Computes the emulated pixel grid that covers the full screen.
     * The user's W×H from prefs is the minimum; we extend to fill the view aspect ratio.
     */
    private fun computeEmulatedSize(): Pair<Float, Float> {
        val p = context.getSharedPreferences(CrtPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val userW = p.getInt(CrtPrefs.KEY_INTERNAL_WIDTH, CrtPrefs.DEFAULT_INTERNAL_WIDTH)
            .coerceIn(CrtPrefs.INTERNAL_WIDTH_MIN, CrtPrefs.INTERNAL_WIDTH_MAX)
        val userH = p.getInt(CrtPrefs.KEY_INTERNAL_HEIGHT, CrtPrefs.DEFAULT_INTERNAL_HEIGHT)
            .coerceIn(CrtPrefs.INTERNAL_HEIGHT_MIN, CrtPrefs.INTERNAL_HEIGHT_MAX)
        val vw = max(viewWidth, 1).toFloat()
        val vh = max(viewHeight, 1).toFloat()
        val screenAspect = vw / vh

        var emW = ceil(userH.toFloat() * screenAspect).toInt()
        var emH = userH
        if (emW < userW) {
            emW = userW
            emH = ceil(userW.toFloat() / screenAspect).toInt()
        }
        return max(emW, 1).toFloat() to max(emH, 1).toFloat()
    }

    override fun onDrawFrame(unused: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glDisable(GLES20.GL_BLEND)
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (_: Exception) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        st.getTransformMatrix(stMatrix)

        val p = context.getSharedPreferences(CrtPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val scanAlpha = p.getInt(CrtPrefs.KEY_SCAN_ALPHA, 60).coerceIn(0, 150)
        val spacing = p.getInt(CrtPrefs.KEY_SCAN_SPACING, 3).coerceIn(1, 6)
        val rgb = p.getInt(CrtPrefs.KEY_RGB, 25).coerceIn(0, 100)
        val vig = p.getInt(CrtPrefs.KEY_VIGNETTE, 30).coerceIn(0, 100)
        val curv = p.getInt(CrtPrefs.KEY_CURVATURE, 35).coerceIn(0, 100)
        val bloom = p.getInt(CrtPrefs.KEY_BLOOM, 35).coerceIn(0, 100)
        val phosphor = p.getInt(CrtPrefs.KEY_PHOSPHOR_BLEED, 50).coerceIn(0, 100)

        val scanStrength = min(1.0f, scanAlpha / 120f)
        val maskStrength = min(1.0f, rgb / 100f)
        val vignetteAmt = min(1.15f, vig / 100f * 1.08f)
        val curvature = curv / 100f
        val bloomAmt = bloom / 100f
        val phosphorAmt = phosphor / 100f
        val scanSpacingNorm = spacing.toFloat() / 6f

        val (emW, emH) = computeEmulatedSize()

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        applyOesTextureFilterMode()
        GLES20.glUniform1i(uSampler, 0)

        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform2f(uTexSize, captureWidth.toFloat(), captureHeight.toFloat())
        GLES20.glUniform2f(uEmulatedSize, emW, emH)
        GLES20.glUniform1f(uScanStrength, scanStrength)
        GLES20.glUniform1f(uScanSpacing, scanSpacingNorm)
        GLES20.glUniform1f(uMaskStrength, maskStrength)
        GLES20.glUniform1f(uVignette, vignetteAmt)
        GLES20.glUniform1f(uCurvature, curvature)
        GLES20.glUniform1f(uBloom, bloomAmt)
        GLES20.glUniform1f(uPhosphorBleed, phosphorAmt)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 4, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun releaseGlResources() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        try {
            outputSurface?.release()
        } catch (_: Exception) {
        }
        outputSurface = null
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
            oesTexId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        surfaceReadyPosted = false
        oesSamplingLinearOnly = false
    }
}
