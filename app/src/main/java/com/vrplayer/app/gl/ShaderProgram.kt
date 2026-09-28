package com.vrplayer.app.gl

import android.opengl.GLES20

class ShaderProgram {
    val program: Int
    val posHandle: Int
    val uvHandle: Int
    val mvpHandle: Int
    val texHandle: Int
    val texMatrixHandle: Int
    val uvScaleHandle: Int
    val uvOffsetHandle: Int
    val uvRotateHandle: Int
    val distortHandle: Int
    val lensShiftHandle: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        posHandle = GLES20.glGetAttribLocation(program, "aPos")
        uvHandle = GLES20.glGetAttribLocation(program, "aUv")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        texHandle = GLES20.glGetUniformLocation(program, "uTex")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uvScaleHandle = GLES20.glGetUniformLocation(program, "uUvScale")
        uvOffsetHandle = GLES20.glGetUniformLocation(program, "uUvOffset")
        uvRotateHandle = GLES20.glGetUniformLocation(program, "uUvRotate")
        distortHandle = GLES20.glGetUniformLocation(program, "uDistort")
        lensShiftHandle = GLES20.glGetUniformLocation(program, "uLensShift")
    }

    fun use() {
        GLES20.glUseProgram(program)
    }

    companion object {
        private const val VERT = """
            uniform mat4 uMvp;
            uniform float uDistort;
            uniform float uLensShift;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vec4 clip = uMvp * vec4(aPos, 1.0);
                if (uDistort > 0.5) {
                    vec2 ndc = clip.xy / max(clip.w, 0.0001);
                    ndc.x += uLensShift;
                    float r2 = dot(ndc, ndc);
                    float f = 1.0 - 0.18 * r2 + 0.04 * r2 * r2;
                    ndc *= max(f, 0.15);
                    ndc.x -= uLensShift;
                    clip.xy = ndc * clip.w;
                }
                gl_Position = clip;
                vUv = aUv;
            }
        """

        private const val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform vec2 uUvScale;
            uniform vec2 uUvOffset;
            uniform float uUvRotate;
            varying vec2 vUv;
            void main() {
                vec2 uv = vUv * uUvScale + uUvOffset;
                if (uUvRotate > 45.0 && uUvRotate < 135.0) {
                    uv = vec2(uv.y, 1.0 - uv.x);
                } else if (uUvRotate >= 135.0 && uUvRotate < 225.0) {
                    uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                } else if (uUvRotate >= 225.0 && uUvRotate < 315.0) {
                    uv = vec2(1.0 - uv.y, uv.x);
                }
                vec4 tc = uTexMatrix * vec4(uv, 0.0, 1.0);
                gl_FragColor = texture2D(uTex, tc.xy);
            }
        """

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
    }
}
