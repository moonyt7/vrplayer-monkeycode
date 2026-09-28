package com.vrplayer.app.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.vrplayer.app.ProjectionMode
import com.vrplayer.app.ViewOrientation
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrRenderer(
    private val orientation: ViewOrientation,
    private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var projectionMode: ProjectionMode = ProjectionMode.EQUIRECT_360

    @Volatile
    var splitScreen: Boolean = true

    @Volatile
    var ipdMm: Int = 64

    @Volatile
    var videoRotationDegrees: Int = 0

    private var shader: ShaderProgram? = null
    private var sphere: Mesh? = null
    private var hemisphere: Mesh? = null
    private var cube: Mesh? = null
    private var quad: Mesh? = null
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var width = 1
    private var height = 1
    private val texMatrix = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(16)
    private val viewEye = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        shader = ShaderProgram()
        sphere = Mesh.sphere()
        hemisphere = Mesh.sphere(hemisphere = true)
        cube = Mesh.cube()
        quad = Mesh.quad()
        textureId = createOesTexture()
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(1920, 1080)
        surfaceTexture = st
        onSurfaceReady(Surface(st))
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        val program = shader ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        orientation.matrix(view)
        program.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(program.texHandle, 0)
        GLES20.glUniformMatrix4fv(program.texMatrixHandle, 1, false, texMatrix, 0)
        if (splitScreen) {
            drawEye(program, left = true)
            drawEye(program, left = false)
        } else {
            drawMono(program)
        }
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun drawMono(program: ShaderProgram) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 90f, aspect, 0.1f, 200f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glUniformMatrix4fv(program.mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform1f(program.distortHandle, 0f)
        GLES20.glUniform1f(program.lensShiftHandle, 0f)
        GLES20.glUniform1f(program.uvRotateHandle, videoRotationDegrees.toFloat())
        bindUv(program, left = true)
        mesh().draw(program.posHandle, program.uvHandle)
    }

    private fun drawEye(program: ShaderProgram, left: Boolean) {
        val half = width / 2
        GLES20.glViewport(if (left) 0 else half, 0, half, height)
        val aspect = half.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 90f, aspect, 0.1f, 200f)
        Matrix.setIdentityM(eye, 0)
        val halfIpdM = (ipdMm / 1000f) * 0.5f
        val ipd = if (left) halfIpdM else -halfIpdM
        Matrix.translateM(eye, 0, ipd, 0f, 0f)
        Matrix.multiplyMM(viewEye, 0, eye, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, viewEye, 0)
        GLES20.glUniformMatrix4fv(program.mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform1f(program.distortHandle, 1f)
        val shift = ((ipdMm - 64) / 30f) * 0.22f
        GLES20.glUniform1f(program.lensShiftHandle, if (left) -shift else shift)
        GLES20.glUniform1f(program.uvRotateHandle, videoRotationDegrees.toFloat())
        bindUv(program, left)
        mesh().draw(program.posHandle, program.uvHandle)
    }

    private fun bindUv(program: ShaderProgram, left: Boolean) {
        val scaleX: Float
        val scaleY: Float
        val offX: Float
        val offY: Float
        when (projectionMode) {
            ProjectionMode.STEREO_SBS, ProjectionMode.STEREO_SBS_180 -> {
                scaleX = 0.5f
                scaleY = 1f
                offX = if (left) 0f else 0.5f
                offY = 0f
            }
            ProjectionMode.STEREO_TB, ProjectionMode.STEREO_TB_180 -> {
                scaleX = 1f
                scaleY = 0.5f
                offX = 0f
                offY = if (left) 0.5f else 0f
            }
            else -> {
                scaleX = 1f
                scaleY = 1f
                offX = 0f
                offY = 0f
            }
        }
        GLES20.glUniform2f(program.uvScaleHandle, scaleX, scaleY)
        GLES20.glUniform2f(program.uvOffsetHandle, offX, offY)
    }

    private fun mesh(): Mesh {
        return when (projectionMode) {
            ProjectionMode.CUBEMAP_3X2 -> cube!!
            ProjectionMode.FLAT_2D -> quad!!
            else -> if (projectionMode.hemisphere) hemisphere!! else sphere!!
        }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }
}
