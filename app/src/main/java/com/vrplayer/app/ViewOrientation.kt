package com.vrplayer.app

import android.hardware.SensorManager
import android.opengl.Matrix
import android.view.Surface
import kotlin.math.max
import kotlin.math.min

class ViewOrientation {
    @Volatile
    var isFixed: Boolean = false

    private val sensor = FloatArray(16)
    private val remapped = FloatArray(16)
    private val worldToEye = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val userOffset = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val drag = FloatArray(16)
    private val frozen = FloatArray(16)
    private val tmp = FloatArray(16)
    private val scratch = FloatArray(16)
    private val pitchFlip = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
    private var hasSensor = false
    private var hasFrozen = false
    private var calibrated = false
    private var displayRotation = Surface.ROTATION_90

    private val axisFix = floatArrayOf(
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f
    )

    @Volatile
    var dragYaw = 0f
        private set

    @Volatile
    var dragPitch = 0f
        private set

    @Synchronized
    fun setDisplayRotation(value: Int) {
        if (value != Surface.ROTATION_90 && value != Surface.ROTATION_270) return
        if (value == displayRotation) return
        displayRotation = value
        calibrated = false
    }

    @Synchronized
    fun onSensorVector(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(sensor, values)
        when (displayRotation) {
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                sensor, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
            )
            else -> SensorManager.remapCoordinateSystem(
                sensor, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
            )
        }
        Matrix.multiplyMM(worldToEye, 0, remapped, 0, axisFix, 0)
        hasSensor = true
        if (!calibrated) {
            Matrix.invertM(userOffset, 0, worldToEye, 0)
            calibrated = true
        }
    }

    fun addDrag(dxDeg: Float, dyDeg: Float) {
        if (isFixed) return
        dragYaw -= dxDeg
        dragPitch = min(89f, max(-89f, dragPitch + dyDeg))
    }

    @Synchronized
    fun recenter() {
        if (hasSensor) {
            Matrix.invertM(userOffset, 0, worldToEye, 0)
        } else {
            Matrix.setIdentityM(userOffset, 0)
        }
        dragYaw = 0f
        dragPitch = 0f
        if (isFixed) {
            snapshot(frozen)
            hasFrozen = true
        }
        calibrated = true
    }

    @Synchronized
    fun lockView(value: Boolean) {
        if (value == isFixed) return
        if (value) {
            snapshot(frozen)
            hasFrozen = true
            isFixed = true
            return
        }
        if (hasSensor) {
            Matrix.invertM(tmp, 0, worldToEye, 0)
            Matrix.multiplyMM(userOffset, 0, frozen, 0, tmp, 0)
            dragYaw = 0f
            dragPitch = 0f
        }
        isFixed = false
    }

    @Synchronized
    fun matrix(out: FloatArray) {
        if (isFixed && hasFrozen) {
            System.arraycopy(frozen, 0, out, 0, 16)
            return
        }
        snapshot(out)
    }

    private fun snapshot(out: FloatArray) {
        Matrix.setIdentityM(drag, 0)
        Matrix.rotateM(drag, 0, dragYaw, 0f, 1f, 0f)
        Matrix.rotateM(drag, 0, dragPitch, 1f, 0f, 0f)
        Matrix.multiplyMM(tmp, 0, userOffset, 0, worldToEye, 0)
        invertPitch(tmp)
        Matrix.multiplyMM(out, 0, drag, 0, tmp, 0)
    }

    private fun invertPitch(m: FloatArray) {
        Matrix.multiplyMM(scratch, 0, pitchFlip, 0, m, 0)
        Matrix.multiplyMM(m, 0, scratch, 0, pitchFlip, 0)
    }
}
