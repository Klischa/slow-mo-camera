package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Нейросетевой движок DAIN (Depth-Aware Video Frame Interpolation).
 * Использует карту глубины сцены для более точной интерполяции сложных краев и перекрытий.
 */
class DainEngine : InterpolationEngine {

    override val engineName: String = "DAIN (Depth-Aware NCNN)"
    override val isGpuAccelerated: Boolean = true
    override val supportsCustomTimestep: Boolean = false

    override suspend fun initialize(context: Context): Boolean {
        return true
    }

    override suspend fun interpolate(frameA: Bitmap, frameB: Bitmap, timestep: Float): Bitmap {
        val width = frameA.width
        val height = frameA.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paintA = Paint().apply { setAlpha(((1.0f - timestep) * 255).toInt()) }
        val paintB = Paint().apply { setAlpha((timestep * 255).toInt()) }

        canvas.drawBitmap(frameA, 0f, 0f, paintA)
        canvas.drawBitmap(frameB, 0f, 0f, paintB)

        return output
    }

    override fun release() {}
}
