package com.klischa.slowmocamera.stabilization

/**
 * Настройки и параметры для алгоритмов стабилизации видео.
 */
data class StabilizationParams(
    var mode: StabilizationMode = StabilizationMode.AUTO,
    var shakiness: Int = 6,          // Уровень тряски (1-10)
    var accuracy: Int = 10,          // Точность поиска точек (1-15)
    var smoothing: Int = 30,         // Степень сглаживания траектории (1-100)
    var cropPercentage: Float = 0.9f, // Коэффициент кропа для скрытия черных краев (0.8 .. 1.0)
    var isHardwarePreviewStabilizationEnabled: Boolean = true
)
