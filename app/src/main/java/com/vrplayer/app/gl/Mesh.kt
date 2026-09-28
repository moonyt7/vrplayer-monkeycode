package com.vrplayer.app.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Mesh(
    private val vertexBuffer: FloatBuffer,
    private val indexBuffer: ShortBuffer,
    private val indexCount: Int
) {
    fun draw(posHandle: Int, uvHandle: Int) {
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, STRIDE, vertexBuffer)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
    }

    companion object {
        private const val STRIDE = 5 * 4

        fun sphere(stacks: Int = 48, slices: Int = 96, hemisphere: Boolean = false): Mesh {
            val verts = ArrayList<Float>()
            val indices = ArrayList<Short>()
            val lonMax = if (hemisphere) PI.toFloat() else (PI * 2).toFloat()
            val lonStart = if (hemisphere) (-PI / 2).toFloat() else (-PI).toFloat()
            for (lat in 0..stacks) {
                val theta = (lat.toFloat() / stacks) * PI.toFloat()
                val y = cos(theta.toDouble()).toFloat()
                val r = sin(theta.toDouble()).toFloat()
                for (lon in 0..slices) {
                    val t = lon.toFloat() / slices
                    val phi = lonStart + t * lonMax
                    val x = r * sin(phi.toDouble()).toFloat()
                    val z = -r * cos(phi.toDouble()).toFloat()
                    verts.add(x)
                    verts.add(y)
                    verts.add(z)
                    verts.add(t)
                    verts.add(1f - lat.toFloat() / stacks)
                }
            }
            val stride = slices + 1
            for (lat in 0 until stacks) {
                for (lon in 0 until slices) {
                    val a = (lat * stride + lon).toShort()
                    val b = (lat * stride + lon + 1).toShort()
                    val c = ((lat + 1) * stride + lon).toShort()
                    val d = ((lat + 1) * stride + lon + 1).toShort()
                    indices.add(a)
                    indices.add(c)
                    indices.add(b)
                    indices.add(b)
                    indices.add(c)
                    indices.add(d)
                }
            }
            return from(verts, indices)
        }

        fun cube(): Mesh {
            val faces = listOf(
                face(
                    floatArrayOf(1f, -1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f),
                    0f, 0.5f, 1f / 3f, 1f
                ),
                face(
                    floatArrayOf(-1f, -1f, 1f, -1f, -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f),
                    1f / 3f, 0.5f, 2f / 3f, 1f
                ),
                face(
                    floatArrayOf(-1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, -1f, 1f, 1f),
                    2f / 3f, 0.5f, 1f, 1f
                ),
                face(
                    floatArrayOf(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, -1f, -1f, -1f, -1f),
                    0f, 0f, 1f / 3f, 0.5f
                ),
                face(
                    floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f),
                    1f / 3f, 0f, 2f / 3f, 0.5f
                ),
                face(
                    floatArrayOf(1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f, -1f, -1f, -1f),
                    2f / 3f, 0f, 1f, 0.5f
                )
            )
            val verts = ArrayList<Float>()
            val indices = ArrayList<Short>()
            var base = 0
            for (f in faces) {
                verts.addAll(f.toList())
                indices.add((base + 0).toShort())
                indices.add((base + 1).toShort())
                indices.add((base + 2).toShort())
                indices.add((base + 0).toShort())
                indices.add((base + 2).toShort())
                indices.add((base + 3).toShort())
                base += 4
            }
            return from(verts, indices)
        }

        fun quad(): Mesh {
            val verts = floatArrayOf(
                -1.6f, -0.9f, -2f, 0f, 0f,
                1.6f, -0.9f, -2f, 1f, 0f,
                1.6f, 0.9f, -2f, 1f, 1f,
                -1.6f, 0.9f, -2f, 0f, 1f
            )
            val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
            return from(verts.toList(), indices.toList())
        }

        private fun face(xyz: FloatArray, u0: Float, v0: Float, u1: Float, v1: Float): FloatArray {
            val uv = floatArrayOf(u0, v1, u1, v1, u1, v0, u0, v0)
            val out = FloatArray(20)
            for (i in 0..3) {
                out[i * 5] = xyz[i * 3]
                out[i * 5 + 1] = xyz[i * 3 + 1]
                out[i * 5 + 2] = xyz[i * 3 + 2]
                out[i * 5 + 3] = uv[i * 2]
                out[i * 5 + 4] = uv[i * 2 + 1]
            }
            return out
        }

        private fun from(verts: List<Float>, indices: List<Short>): Mesh {
            val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            verts.forEach { vb.put(it) }
            vb.position(0)
            val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            indices.forEach { ib.put(it) }
            ib.position(0)
            return Mesh(vb, ib, indices.size)
        }
    }
}
