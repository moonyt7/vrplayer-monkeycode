package com.vrplayer.app.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs

class VrGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    var onDrag: ((dxDeg: Float, dyDeg: Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) > 2f || abs(dy) > 2f) {
                    moved = true
                    val yaw = dx / width * 90f
                    val pitch = dy / height * 60f
                    onDrag?.invoke(yaw, pitch)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
