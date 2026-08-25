package com.klischa.slowmocamera.ai.interpolation

import android.content.Context

/**
 * Фабрика выбора стратегии интерполяции (Strategy Pattern).
 * Подбирает оптимальный движок на основе режима пользователя и возможностей GPU.
 */
object InterpolationStrategyFactory {

    fun createEngine(context: Context, mode: InterpolationMode): InterpolationEngine {
        val thermalMonitor = ThermalMonitor(context)
        val isThermalSafe = thermalMonitor.isSafeToRunNeuralInference()

        return when (mode) {
            InterpolationMode.QUALITY -> {
                if (isThermalSafe) RifeNcnnEngine() else FastOpticalFlowEngine()
            }
            InterpolationMode.SPEED -> {
                FastOpticalFlowEngine()
            }
            InterpolationMode.AUTO -> {
                if (isThermalSafe) RifeNcnnEngine() else FastOpticalFlowEngine()
            }
        }
    }
}
