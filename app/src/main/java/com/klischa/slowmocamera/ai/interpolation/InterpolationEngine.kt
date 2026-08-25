package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap

/**
 * Интерфейс стратегии нейросетевой интерполяции кадров (Pattern Strategy).
 * Позволяет бесшовно переключаться между RIFE, DAIN, ANVIL и быстрым оптическим потоком.
 */
interface InterpolationEngine {
    val engineName: String
    val isGpuAccelerated: Boolean
    val supportsCustomTimestep: Boolean

    suspend fun initialize(context: Context): Boolean
    suspend fun interpolate(frameA: Bitmap, frameB: Bitmap, timestep: Float): Bitmap
    fun release()
}
