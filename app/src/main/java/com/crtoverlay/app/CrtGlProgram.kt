package com.crtoverlay.app

import android.content.Context
import android.opengl.GLES20
import java.io.BufferedReader
import java.io.InputStreamReader

internal object CrtGlProgram {

    fun loadAsset(context: Context, path: String): String {
        context.assets.open(path).use { ins ->
            BufferedReader(InputStreamReader(ins)).use { r ->
                return r.readText()
            }
        }
    }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (v == 0 || f == 0) {
            if (v != 0) GLES20.glDeleteShader(v)
            if (f != 0) GLES20.glDeleteShader(f)
            return 0
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("Program link failed: $log")
        }
        return p
    }

    private fun loadShader(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed ($type): $log")
        }
        return s
    }
}
